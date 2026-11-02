package ua.remotehelp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns the phone screen into a stream of binary messages for the helper.
 *
 * Preferred format is H.264 (hardware encoder, ~5–10× less traffic than JPEG), with
 * JPEG as a fallback when the helper's browser or the phone can't do H.264.
 *
 * Built for bad mobile links:
 *  - every frame carries an id, the helper acks it → we know the real round-trip time;
 *  - when too much is unacknowledged we drop frames until the next key frame instead
 *    of letting a backlog (and seconds of lag) build up;
 *  - bitrate adapts continuously (AIMD), and resolution steps down when bitrate is very low.
 *
 * Everything here runs on [handler]'s thread.
 *
 * Binary message: [kind:1][frameId:4 BE][payload], kind 1 = H.264 key, 2 = H.264 delta, 3 = JPEG.
 */
class ScreenStreamer(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val handler: Handler,
    private val sendBinary: (ByteArray) -> Boolean,
    private val sendJson: (JSONObject) -> Unit,
    private val queuedBytes: () -> Long,
) {
    enum class Mode { NONE, H264, JPEG }

    /** Presets chosen by the helper. Bitrates in bit/s. */
    enum class Preset(
        val sides: IntArray,     // resolution tiers (long side, px), best first
        val startBitrate: Int,
        val minBitrate: Int,
        val maxBitrate: Int,
        val maxFps: Int,
        val jpegQuality: Int,
    ) {
        AUTO(intArrayOf(1280, 960, 720), 900_000, 120_000, 2_500_000, 20, 55),
        LOW(intArrayOf(960, 720, 540), 300_000, 80_000, 600_000, 10, 40),
        HIGH(intArrayOf(1920, 1280, 960), 2_500_000, 300_000, 6_000_000, 30, 75),
    }

    companion object {
        private const val TAG = "RemoteHelp"
        private const val KIND_KEY = 1
        private const val KIND_DELTA = 2
        private const val KIND_JPEG = 3
        private const val JPEG_MAX_IN_FLIGHT = 2
    }

    private var vd: VirtualDisplay? = null
    var mode = Mode.NONE
        private set
    private var preset = Preset.AUTO
    private var tier = 0
    private var screenW = 0
    private var screenH = 0

    // ---- H.264
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var csd: ByteArray? = null
    private var bitrate = preset.startBitrate
    private var waitingForKey = true
    private var lastKeyRequest = 0L

    // ---- JPEG
    private var reader: ImageReader? = null
    private var frame: Bitmap? = null
    private var packed: ByteBuffer? = null
    private var jpegDirty = false
    private var lastJpegAt = 0L
    private val jpegOut = ByteArrayOutputStream(256 * 1024)
    private val jpegRunnable = Runnable { maybeSendJpeg() }

    // ---- flow control / stats
    private var nextId = 1
    private val inFlight = LinkedHashMap<Int, LongArray>() // id -> [sentAt, bytes]
    private var inFlightBytes = 0L
    private var rttMs = 0.0
    private var dropsInWindow = 0
    private var framesInWindow = 0
    private var bytesInWindow = 0L
    private var goodWindows = 0
    private var lastTierChange = 0L
    private val controlRunnable = object : Runnable {
        override fun run() {
            adapt()
            handler.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ public API

    fun screenSize(): Pair<Int, Int> = screenW to screenH

    /** (Re)starts streaming in [newMode]. */
    fun start(newMode: Mode, newPreset: Preset = preset) {
        stopOutput()
        preset = newPreset
        tier = 0
        bitrate = preset.startBitrate
        mode = newMode
        if (mode == Mode.H264 && !startH264()) {
            Log.w(TAG, "H.264 unavailable, falling back to JPEG")
            mode = Mode.JPEG
        }
        if (mode == Mode.JPEG) startJpeg()
        handler.removeCallbacks(controlRunnable)
        handler.postDelayed(controlRunnable, 1000)
        sendJson(JSONObject().put("type", "stream").put("mode", mode.name.lowercase()))
    }

    fun setPreset(p: Preset) {
        if (p == preset || mode == Mode.NONE) { preset = p; return }
        start(mode, p)
    }

    /** Stops sending frames (no helper watching) but keeps the projection alive. */
    fun pause() {
        stopOutput()
        mode = Mode.NONE
        handler.removeCallbacks(controlRunnable)
    }

    /** Screen rotated or resized: rebuild the pipeline at the new size. */
    fun onDisplayChanged() {
        val s = realScreenSize(ctx)
        if (s.x == screenW && s.y == screenH) return
        if (mode != Mode.NONE) {
            val m = mode
            val t = tier
            stopOutput()
            mode = m
            tier = t
            if (mode == Mode.H264 && !startH264()) mode = Mode.JPEG
            if (mode == Mode.JPEG) startJpeg()
        }
    }

    fun onAck(id: Int) {
        val e = inFlight.remove(id) ?: return
        inFlightBytes -= e[1]
        // Anything older than an acked frame is lost/irrelevant now.
        val it = inFlight.entries.iterator()
        while (it.hasNext()) {
            val n = it.next()
            if (n.key < id) { inFlightBytes -= n.value[1]; it.remove() } else break
        }
        val sample = (SystemClock.uptimeMillis() - e[0]).toDouble()
        rttMs = if (rttMs == 0.0) sample else rttMs * 0.8 + sample * 0.2
        if (mode == Mode.H264 && waitingForKey && !congested()) requestKeyFrame()
        if (mode == Mode.JPEG) handler.post(jpegRunnable)
    }

    fun requestKeyFrame() {
        val now = SystemClock.uptimeMillis()
        if (now - lastKeyRequest < 700) return
        lastKeyRequest = now
        waitingForKey = true
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
        }
        if (mode == Mode.JPEG) { jpegDirty = true; handler.post(jpegRunnable) }
    }

    fun release() {
        pause()
        vd?.release()
        vd = null
    }

    // ------------------------------------------------------------------ geometry

    /** Capture size for the current tier, keeping aspect ratio, aligned to 16 px. */
    private fun targetSize(align: Int): Pair<Int, Int> {
        val real = realScreenSize(ctx)
        screenW = real.x
        screenH = real.y
        val side = preset.sides[min(tier, preset.sides.size - 1)]
        val scale = min(1.0, side.toDouble() / max(real.x, real.y))
        val w = max(align, ((real.x * scale) / align).roundToInt() * align)
        val h = max(align, ((real.y * scale) / align).roundToInt() * align)
        return w to h
    }

    private fun attach(surface: Surface?, w: Int, h: Int) {
        val dpi = max(120, (realDensityDpi(ctx) * w.toDouble() / screenW).toInt())
        val d = vd
        if (d == null) {
            vd = projection.createVirtualDisplay(
                "RemoteHelp", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler
            )
        } else {
            d.resize(w, h, dpi)
            d.surface = surface
        }
        sendJson(JSONObject().put("type", "screen").put("width", screenW).put("height", screenH)
            .put("streamWidth", w).put("streamHeight", h))
    }

    private fun stopOutput() {
        vd?.surface = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        inputSurface?.release()
        inputSurface = null
        csd = null
        reader?.close()
        reader = null
        frame = null
        packed = null
        handler.removeCallbacks(jpegRunnable)
        inFlight.clear()
        inFlightBytes = 0
        waitingForKey = true
    }

    // ------------------------------------------------------------------ H.264

    private fun startH264(): Boolean {
        for (attempt in 0 until 3) {
            var (w, h) = targetSize(16)
            val enc = try { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) } catch (_: Exception) { return false }
            val caps = try {
                enc.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            } catch (_: Exception) { null }
            // Some encoders cap the long side (e.g. 1920) or need landscape ordering.
            while (caps != null && !caps.isSizeSupported(w, h) && max(w, h) > 320) {
                w = ((w * 0.8) / 16).roundToInt() * 16
                h = ((h * 0.8) / 16).roundToInt() * 16
            }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 20)  // key frames mostly on demand
                // Static screen: re-send a frame after 1 s so quality converges and late joiners get a picture.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L)
                setFloat("max-fps-to-encoder", preset.maxFps.toFloat())
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                if (attempt == 0) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            try {
                enc.setCallback(EncoderCallback(), handler)
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = enc.createInputSurface()
                enc.start()
                codec = enc
                inputSurface = surface
                waitingForKey = true
                attach(surface, w, h)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "encoder configure failed (attempt $attempt) ${w}x$h", e)
                try { enc.release() } catch (_: Exception) {}
                if (attempt == 1) tier = min(tier + 1, preset.sides.size - 1)
            }
        }
        return false
    }

    private inner class EncoderCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            val data: ByteArray
            try {
                val buf = c.getOutputBuffer(index) ?: return
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                data = ByteArray(info.size)
                buf.get(data)
            } catch (_: Exception) {
                return
            } finally {
                try { c.releaseOutputBuffer(index, false) } catch (_: Exception) {}
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                csd = data
                return
            }
            if (data.isEmpty()) return
            onEncodedFrame(data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            if (c !== codec) return
            // Some encoders deliver SPS/PPS here instead of a CODEC_CONFIG buffer.
            val sps = format.getByteBuffer("csd-0")
            val pps = format.getByteBuffer("csd-1")
            if (sps != null && csd == null) {
                val out = ByteArrayOutputStream()
                for (b in listOfNotNull(sps, pps)) {
                    val a = ByteArray(b.remaining()); b.duplicate().get(a); out.write(a)
                }
                csd = out.toByteArray()
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (c !== codec) return
            Log.e(TAG, "encoder error", e)
            start(Mode.JPEG)
        }
    }

    /** Unacked data worth more than ~0.7 s at the current bitrate → the link is backed up. */
    private fun congested(): Boolean {
        val budget = max(48_000L, bitrate / 8L * 7 / 10)
        return inFlightBytes > budget || queuedBytes() > budget || inFlight.size > 30
    }

    private fun onEncodedFrame(data: ByteArray, isKey: Boolean) {
        framesInWindow++
        if (!isKey) {
            if (waitingForKey) { dropsInWindow++; return }
            if (congested()) {
                // Sending more would only add lag. Skip until a fresh key frame.
                dropsInWindow++
                waitingForKey = true
                return
            }
        }
        waitingForKey = false
        val payload = if (isKey && csd != null) csd!! + data else data
        send(if (isKey) KIND_KEY else KIND_DELTA, payload)
    }

    private fun send(kind: Int, payload: ByteArray): Boolean {
        val id = nextId++
        val msg = ByteArray(5 + payload.size)
        msg[0] = kind.toByte()
        msg[1] = (id ushr 24).toByte(); msg[2] = (id ushr 16).toByte()
        msg[3] = (id ushr 8).toByte(); msg[4] = id.toByte()
        System.arraycopy(payload, 0, msg, 5, payload.size)
        if (!sendBinary(msg)) return false
        inFlight[id] = longArrayOf(SystemClock.uptimeMillis(), msg.size.toLong())
        inFlightBytes += msg.size
        bytesInWindow += msg.size
        return true
    }

    // ------------------------------------------------------------------ adaptation

    private fun adapt() {
        if (mode == Mode.NONE) return
        val now = SystemClock.uptimeMillis()
        // Frames that were never acked for 5 s are gone (helper's tab stalled etc.).
        inFlight.entries.removeAll { (_, v) ->
            (now - v[0] > 5000).also { old -> if (old) inFlightBytes -= v[1] }
        }
        if (inFlight.isEmpty()) inFlightBytes = 0
        if (mode == Mode.H264 && waitingForKey && !congested()) requestKeyFrame()

        val bad = dropsInWindow > 0 || rttMs > 900
        val good = dropsInWindow == 0 && rttMs in 1.0..400.0
        if (mode == Mode.H264) {
            val old = bitrate
            if (bad) {
                bitrate = max(preset.minBitrate, (bitrate * 0.7).toInt())
                goodWindows = 0
            } else if (good) {
                goodWindows++
                if (goodWindows >= 3) bitrate = min(preset.maxBitrate, (bitrate * 1.15).toInt())
            }
            if (bitrate != old) {
                try {
                    codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
                } catch (_: Exception) {
                }
            }
            // Very low bitrate at high resolution looks like mush: step the resolution down (and back up).
            if (now - lastTierChange > 10_000) {
                val newTier = when {
                    bitrate <= preset.minBitrate * 2 && tier < preset.sides.size - 1 -> tier + 1
                    bitrate >= preset.startBitrate && tier > 0 && goodWindows > 5 -> tier - 1
                    else -> tier
                }
                if (newTier != tier) {
                    lastTierChange = now
                    tier = newTier
                    val b = bitrate
                    stopOutput()
                    bitrate = b
                    if (!startH264()) start(Mode.JPEG)
                }
            }
        }
        sendJson(
            JSONObject().put("type", "stats")
                .put("mode", mode.name.lowercase())
                .put("bitrate", if (mode == Mode.H264) bitrate else (bytesInWindow * 8))
                .put("rtt", rttMs.roundToInt())
                .put("drops", dropsInWindow)
                .put("fps", framesInWindow)
                .put("kbps", (bytesInWindow * 8 / 1000))
        )
        dropsInWindow = 0
        framesInWindow = 0
        bytesInWindow = 0
    }

    // ------------------------------------------------------------------ JPEG fallback

    private fun startJpeg() {
        val (w, h) = targetSize(2)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        reader = r
        attach(r.surface, w, h)
    }

    private fun onImage(r: ImageReader) {
        if (r !== reader) return
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val plane = img.planes[0]
            val w = img.width
            val h = img.height
            val rowBytes = w * 4
            val bmp = frame?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { frame = it }
            val src = plane.buffer
            if (plane.rowStride == rowBytes && plane.pixelStride == 4) {
                src.rewind()
                bmp.copyPixelsFromBuffer(src)
            } else {
                val dst = packed?.takeIf { it.capacity() == rowBytes * h }
                    ?: ByteBuffer.allocateDirect(rowBytes * h).also { packed = it }
                dst.clear()
                for (row in 0 until h) {
                    val start = row * plane.rowStride
                    src.limit(min(src.capacity(), start + rowBytes))
                    src.position(start)
                    dst.put(src)
                }
                dst.rewind()
                bmp.copyPixelsFromBuffer(dst)
            }
            jpegDirty = true
        } catch (e: Exception) {
            Log.w(TAG, "frame copy failed", e)
        } finally {
            img.close()
        }
        maybeSendJpeg()
    }

    private fun maybeSendJpeg() {
        if (mode != Mode.JPEG || !jpegDirty) return
        val bmp = frame ?: return
        val now = SystemClock.uptimeMillis()
        val interval = 1000L / preset.maxFps
        if (inFlight.size >= JPEG_MAX_IN_FLIGHT || queuedBytes() > 500_000) {
            handler.removeCallbacks(jpegRunnable); handler.postDelayed(jpegRunnable, 100); return
        }
        val wait = lastJpegAt + interval - now
        if (wait > 0) { handler.removeCallbacks(jpegRunnable); handler.postDelayed(jpegRunnable, wait); return }
        jpegDirty = false
        jpegOut.reset()
        // Lower JPEG quality when the link is slow.
        val q = if (rttMs > 900) max(25, preset.jpegQuality - 25) else preset.jpegQuality
        bmp.compress(Bitmap.CompressFormat.JPEG, q, jpegOut)
        if (send(KIND_JPEG, jpegOut.toByteArray())) lastJpegAt = now
    }
}
