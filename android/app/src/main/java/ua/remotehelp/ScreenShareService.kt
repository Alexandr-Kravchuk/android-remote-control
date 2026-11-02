package ua.remotehelp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that owns a help session:
 *  - captures the screen via MediaProjection,
 *  - streams JPEG frames to the relay server over a WebSocket,
 *  - receives the helper's commands and hands them to [ControlService].
 */
class ScreenShareService : Service() {

    companion object {
        private const val TAG = "RemoteHelp"
        private const val ACTION_START = "ua.remotehelp.START"
        private const val ACTION_STOP = "ua.remotehelp.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL = "session"
        private const val NOTIF_ID = 1
        private const val MAX_IN_FLIGHT = 2

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenShareService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ScreenShareService::class.java).setAction(ACTION_STOP))
        }
    }

    /** Capture profile chosen by the helper. */
    private enum class Quality(val maxSide: Int, val jpeg: Int, val frameIntervalMs: Long) {
        LOW(960, 40, 250), MEDIUM(1280, 60, 120), HIGH(1920, 80, 70)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var capture: Handler

    // --- capture state (touched only on the capture thread) ---
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frame: Bitmap? = null
    private var packed: ByteBuffer? = null
    private var dirty = false
    private var lastSendAt = 0L
    private var quality = Quality.MEDIUM
    private var lastScreenW = 0
    private var lastScreenH = 0
    private val jpegOut = ByteArrayOutputStream(256 * 1024)

    // --- network state ---
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var viewerConnected = false
    @Volatile private var stopping = false
    private val inFlight = AtomicInteger(0)
    private var code: String? = null
    private var secret: String? = null
    private var reconnectDelayMs = 1000L

    private val sendRunnable = Runnable { maybeSendFrame() }
    private val infoRunnable = object : Runnable {
        override fun run() {
            if (viewerConnected) sendInfo()
            main.postDelayed(this, 60_000)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val s = realScreenSize(this@ScreenShareService)
            if (s.x != lastScreenW || s.y != lastScreenH) setupCapture()   // rotated
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("capture").apply { start() }
        capture = Handler(worker.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (projection != null) return START_NOT_STICKY
                // Must be in the foreground with type mediaProjection before obtaining the projection.
                ServiceCompat.startForeground(
                    this, NOTIF_ID, buildNotification(),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
                )
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (data == null) {
                    stopSession(); return START_NOT_STICKY
                }
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mp = try {
                    mpm.getMediaProjection(resultCode, data)
                } catch (e: Exception) {
                    Log.e(TAG, "getMediaProjection failed", e); null
                }
                if (mp == null) {
                    stopSession(); return START_NOT_STICKY
                }
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        main.post { stopSession() }     // user tapped "Stop sharing" in the status bar
                    }
                }, main)
                projection = mp
                stopping = false
                SessionState.set(SessionState.State(SessionState.Phase.CONNECTING))
                (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, capture)
                capture.post { setupCapture() }
                connect()
                main.postDelayed(infoRunnable, 60_000)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession()
        worker.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ capture

    private fun setupCapture() {
        if (Looper.myLooper() != worker.looper) { capture.post { setupCapture() }; return }
        val mp = projection ?: return
        val real = realScreenSize(this)
        lastScreenW = real.x
        lastScreenH = real.y
        val scale = min(1.0, quality.maxSide.toDouble() / max(real.x, real.y))
        val w = ((real.x * scale).toInt() / 2) * 2
        val h = ((real.y * scale).toInt() / 2) * 2
        val dpi = max(120, (realDensityDpi(this) * scale).toInt())

        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener({ r -> onImage(r) }, capture)
        val vd = virtualDisplay
        try {
            if (vd == null) {
                virtualDisplay = mp.createVirtualDisplay(
                    "RemoteHelp", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newReader.surface, null, capture
                )
            } else {
                vd.resize(w, h, dpi)
                vd.surface = newReader.surface
            }
        } catch (e: Exception) {
            Log.e(TAG, "virtual display failed", e)
            newReader.close()
            main.post { stopSession() }
            return
        }
        reader?.close()
        reader = newReader
        frame = null
        packed = null
        sendJson(JSONObject().put("type", "screen").put("width", real.x).put("height", real.y))
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
                // Rows are padded: repack them tightly.
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
            dirty = true
        } catch (e: Exception) {
            Log.w(TAG, "frame copy failed", e)
        } finally {
            img.close()
        }
        maybeSendFrame()
    }

    private fun scheduleSend(delayMs: Long) {
        capture.removeCallbacks(sendRunnable)
        capture.postDelayed(sendRunnable, max(1, delayMs))
    }

    /** Sends the latest frame if the helper is watching and the pipe isn't full. */
    private fun maybeSendFrame() {
        val ws = socket ?: return
        val bmp = frame ?: return
        if (!viewerConnected || !dirty) return
        val now = SystemClock.uptimeMillis()
        if (inFlight.get() >= MAX_IN_FLIGHT) {
            if (now - lastSendAt > 3000) inFlight.set(0) // lost acks — recover
            else { scheduleSend(100); return }
        }
        val wait = lastSendAt + quality.frameIntervalMs - now
        if (wait > 0) { scheduleSend(wait); return }
        if (ws.queueSize() > 1_000_000) { scheduleSend(100); return }

        dirty = false
        jpegOut.reset()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.jpeg, jpegOut)
        if (ws.send(jpegOut.toByteArray().toByteString())) {
            inFlight.incrementAndGet()
            lastSendAt = now
        }
    }

    // ------------------------------------------------------------------ network

    private fun connect() {
        if (stopping) return
        val url = try { Prefs.deviceSocketUrl(this) } catch (_: Exception) { null }
        val req = try { Request.Builder().url(url ?: "").build() } catch (e: Exception) {
            SessionState.set(SessionState.State(SessionState.Phase.IDLE, error = getString(R.string.server_not_set)))
            stopSession(); return
        }
        socket = http.newWebSocket(req, Listener())
    }

    private fun scheduleReconnect() {
        if (stopping) return
        viewerConnected = false
        SessionState.update { it.copy(phase = SessionState.Phase.RECONNECTING) }
        updateNotification()
        main.postDelayed({ connect() }, reconnectDelayMs)
        reconnectDelayMs = min(15_000, reconnectDelayMs * 2)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            reconnectDelayMs = 1000
            val hello = JSONObject()
                .put("type", "hello")
                .put("name", deviceName())
            code?.let { hello.put("code", it) }
            secret?.let { hello.put("secret", it) }
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            val msg = try { JSONObject(text) } catch (_: Exception) { return }
            handleMessage(msg)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === socket) main.post { scheduleReconnect() }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.message}")
            if (webSocket === socket) main.post { scheduleReconnect() }
        }
    }

    private fun sendJson(obj: JSONObject) {
        socket?.send(obj.toString())
    }

    private fun notice(text: String) = sendJson(JSONObject().put("type", "notice").put("text", text))

    private fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "registered" -> {
                code = msg.optString("code")
                secret = msg.optString("secret")
                main.post {
                    SessionState.update {
                        it.copy(
                            phase = if (viewerConnected) SessionState.Phase.CONNECTED else SessionState.Phase.WAITING,
                            code = code, error = null
                        )
                    }
                    updateNotification()
                }
                capture.post {
                    if (lastScreenW > 0) sendJson(
                        JSONObject().put("type", "screen").put("width", lastScreenW).put("height", lastScreenH)
                    )
                }
            }
            "viewer-joined" -> {
                viewerConnected = true
                inFlight.set(0)
                capture.post { dirty = true; lastSendAt = 0; maybeSendFrame() }
                sendInfo()
                sendJson(JSONObject().put("type", "a11y").put("enabled", ControlService.instance != null))
                main.post {
                    SessionState.update { it.copy(phase = SessionState.Phase.CONNECTED) }
                    updateNotification()
                    Toast.makeText(this, R.string.toast_helper_joined, Toast.LENGTH_LONG).show()
                }
            }
            "viewer-left" -> {
                viewerConnected = false
                main.post {
                    SessionState.update { it.copy(phase = SessionState.Phase.WAITING) }
                    updateNotification()
                    Toast.makeText(this, R.string.toast_helper_left, Toast.LENGTH_SHORT).show()
                }
            }
            "ack" -> {
                if (inFlight.decrementAndGet() < 0) inFlight.set(0)
                capture.post(sendRunnable)
            }
            "quality" -> {
                val q = when (msg.optString("level")) {
                    "low" -> Quality.LOW
                    "high" -> Quality.HIGH
                    else -> Quality.MEDIUM
                }
                capture.post {
                    if (q != quality) { quality = q; setupCapture() }
                }
            }
            "stop" -> main.post { stopSession() }
            else -> main.post { handleControl(msg) }
        }
    }

    /** Helper's input commands — executed on the main thread via the accessibility service. */
    private fun handleControl(msg: JSONObject) {
        val type = msg.optString("type")
        if (type == "message") {
            val text = msg.optString("text").take(500)
            val svc = ControlService.instance
            if (svc != null) svc.showMessage(text) else Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            return
        }
        val svc = ControlService.instance
        if (svc == null) {
            notice("Керування вимкнено на телефоні (Спеціальні можливості → «Допомога — керування»)")
            return
        }
        when (type) {
            "tap" -> svc.tap(msg.optDouble("x"), msg.optDouble("y"))
            "longpress" -> svc.longPress(msg.optDouble("x"), msg.optDouble("y"))
            "swipe" -> {
                val arr = msg.optJSONArray("points") ?: return
                val pts = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONArray(i)?.let { it.optDouble(0) to it.optDouble(1) }
                }.filter { !it.first.isNaN() && !it.second.isNaN() }
                svc.swipe(pts, msg.optLong("duration", 300))
            }
            "scroll" -> svc.scroll(
                msg.optDouble("x", 0.5), msg.optDouble("y", 0.5),
                msg.optDouble("dx", 0.0), msg.optDouble("dy", 0.0)
            )
            "global" -> if (!svc.global(msg.optString("action"))) notice("Ця дія недоступна на цьому телефоні")
            "text" -> if (!svc.inputText(msg.optString("text"))) notice("Немає активного поля вводу — спершу тапніть у поле")
            "key" -> if (!svc.key(msg.optString("key"))) notice("Немає активного поля вводу")
        }
    }

    private fun sendInfo() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        sendJson(
            JSONObject()
                .put("type", "info")
                .put("model", deviceName())
                .put("android", Build.VERSION.RELEASE)
                .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("charging", bm.isCharging)
        )
    }

    private fun deviceName(): String {
        val m = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$m ${Build.MODEL}"
    }

    // ------------------------------------------------------------------ stop

    private fun stopSession() {
        if (stopping && projection == null) return
        val wasActive = projection != null
        stopping = true
        viewerConnected = false
        main.removeCallbacksAndMessages(null)

        socket?.let {
            it.send(JSONObject().put("type", "bye").toString())
            it.close(1000, "stopped")
        }
        socket = null
        code = null
        secret = null

        try {
            (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        val mp = projection
        projection = null
        capture.post {
            virtualDisplay?.release(); virtualDisplay = null
            reader?.close(); reader = null
            frame = null; packed = null
        }
        try { mp?.stop() } catch (_: Exception) {}

        SessionState.update { SessionState.State(SessionState.Phase.IDLE, error = it.error) }
        if (wasActive) Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val st = SessionState.state.value
        val title = when (st.phase) {
            SessionState.Phase.CONNECTED -> getString(R.string.notif_title_connected)
            SessionState.Phase.WAITING -> getString(R.string.notif_title_waiting, formatCode(st.code))
            SessionState.Phase.RECONNECTING -> getString(R.string.reconnecting)
            else -> getString(R.string.connecting)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun updateNotification() {
        if (projection == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}

fun formatCode(code: String?): String =
    if (code == null || code.length != 6) code ?: "" else code.substring(0, 3) + " " + code.substring(3)
