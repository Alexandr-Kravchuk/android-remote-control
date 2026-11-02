package ua.remotehelp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Foreground service that owns a help session:
 *  - keeps a WebSocket to the relay server (reconnects and resumes the same code),
 *  - streams the screen via [ScreenStreamer] while a helper is watching,
 *  - executes the helper's commands through [ControlService],
 *  - runs the voice call ([VoiceCall]) when the helper starts one.
 */
class ScreenShareService : Service() {

    companion object {
        private const val TAG = "RemoteHelp"
        private const val ACTION_START = "ua.remotehelp.START"
        private const val ACTION_STOP = "ua.remotehelp.STOP"
        private const val ACTION_MUTE = "ua.remotehelp.MUTE"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_MUTED = "muted"
        private const val CHANNEL = "session"
        private const val NOTIF_ID = 1

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

        fun setMuted(ctx: Context, muted: Boolean) {
            ctx.startService(
                Intent(ctx, ScreenShareService::class.java).setAction(ACTION_MUTE).putExtra(EXTRA_MUTED, muted)
            )
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var capture: Handler

    private var projection: MediaProjection? = null
    private var streamer: ScreenStreamer? = null   // touched on the capture thread
    private var preset = ScreenStreamer.Preset.AUTO
    private var voice: VoiceCall? = null
    private var iceServers: JSONArray? = null

    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)      // notice dead mobile links quickly
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var viewerConnected = false
    @Volatile private var stopping = false
    private var code: String? = null
    private var secret: String? = null
    private var reconnectDelayMs = 1000L

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
            if (displayId == Display.DEFAULT_DISPLAY) streamer?.onDisplayChanged()   // rotation
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("capture").apply { start() }
        capture = Handler(worker.looper)
        createChannel()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_MUTE -> {
                if (projection == null) { stopSelf(); return START_NOT_STICKY }
                val m = intent.getBooleanExtra(EXTRA_MUTED, false)
                voice?.setMuted(m)
                SessionState.update { it.copy(muted = m) }
                updateNotification()
            }
            ACTION_START -> startSession(intent)
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        if (projection != null) return
        // Must already be in the foreground (type mediaProjection) before obtaining the projection.
        var types = 0
        if (Build.VERSION.SDK_INT >= 29) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 30 && hasMic()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), types)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            data?.let { mpm.getMediaProjection(resultCode, it) }
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e); null
        }
        if (mp == null) {
            stopSession(); return
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSession() }     // "Stop sharing" from the status bar
            }
        }, main)
        projection = mp
        stopping = false
        streamer = ScreenStreamer(
            this, mp, capture,
            sendBinary = { bytes -> socket?.send(bytes.toByteString()) ?: false },
            sendJson = { sendJson(it) },
            queuedBytes = { socket?.queueSize() ?: 0L },
        )
        SessionState.set(SessionState.State(SessionState.Phase.CONNECTING))
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, capture)
        connect()
        main.postDelayed(infoRunnable, 60_000)
    }

    override fun onDestroy() {
        stopSession()
        worker.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ network

    private fun connect() {
        if (stopping) return
        val req = try {
            Request.Builder().url(Prefs.deviceSocketUrl(this)).build()
        } catch (e: Exception) {
            SessionState.set(SessionState.State(SessionState.Phase.IDLE, error = getString(R.string.server_not_set)))
            stopSession(); return
        }
        socket = http.newWebSocket(req, Listener())
    }

    private fun scheduleReconnect() {
        if (stopping) return
        socket = null
        viewerConnected = false
        capture.post { streamer?.pause() }
        SessionState.update { it.copy(phase = SessionState.Phase.RECONNECTING) }
        updateNotification()
        main.postDelayed({ connect() }, reconnectDelayMs)
        reconnectDelayMs = min(10_000, reconnectDelayMs * 2)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            reconnectDelayMs = 1000
            val hello = JSONObject().put("type", "hello").put("name", deviceName())
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
            main.post { if (webSocket === socket) scheduleReconnect() }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.message}")
            main.post { if (webSocket === socket) scheduleReconnect() }
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
                msg.optJSONArray("iceServers")?.let { iceServers = it }
                main.post {
                    SessionState.update {
                        it.copy(
                            phase = if (viewerConnected) SessionState.Phase.CONNECTED else SessionState.Phase.WAITING,
                            code = code, error = null
                        )
                    }
                    updateNotification()
                }
            }
            "viewer-joined" -> {
                viewerConnected = true
                sendInfo()
                sendJson(JSONObject().put("type", "a11y").put("enabled", ControlService.instance != null))
                sendJson(JSONObject().put("type", "voice-available").put("available", hasMic()))
                voice?.let { v ->
                    sendJson(JSONObject().put("type", "call-state").put("state", v.state.name.lowercase()).put("muted", v.muted))
                }
                val resumed = msg.optBoolean("resumed")
                main.post {
                    SessionState.update { it.copy(phase = SessionState.Phase.CONNECTED) }
                    updateNotification()
                    if (!resumed) Toast.makeText(this, R.string.toast_helper_joined, Toast.LENGTH_LONG).show()
                }
                // Streaming starts when the helper tells us which formats it can decode ("caps").
            }
            "caps" -> {
                val mode = if (msg.optBoolean("h264")) ScreenStreamer.Mode.H264 else ScreenStreamer.Mode.JPEG
                capture.post { streamer?.start(mode, preset) }
            }
            "viewer-left" -> {
                viewerConnected = false
                capture.post { streamer?.pause() }
                main.post {
                    endCall()
                    SessionState.update { it.copy(phase = SessionState.Phase.WAITING) }
                    updateNotification()
                    Toast.makeText(this, R.string.toast_helper_left, Toast.LENGTH_SHORT).show()
                }
            }
            "ack" -> {
                val id = msg.optInt("id", -1)
                capture.post { streamer?.onAck(id) }
            }
            "keyframe" -> capture.post { streamer?.requestKeyFrame() }
            "quality" -> {
                val p = when (msg.optString("level")) {
                    "low" -> ScreenStreamer.Preset.LOW
                    "high" -> ScreenStreamer.Preset.HIGH
                    else -> ScreenStreamer.Preset.AUTO
                }
                capture.post { preset = p; streamer?.setPreset(p) }
            }
            "rtc-offer" -> main.post { onOffer(msg.optString("sdp")) }
            // Same thread as "rtc-offer", so candidates never overtake the offer that creates the call.
            "rtc-ice" -> main.post {
                voice?.handleIce(msg.optString("candidate"), msg.optString("sdpMid").ifEmpty { null }, msg.optInt("sdpMLineIndex"))
            }
            "call-end" -> main.post { endCall() }
            "stop" -> main.post { stopSession() }
            else -> main.post { handleControl(msg) }
        }
    }

    // ------------------------------------------------------------------ voice

    private fun onOffer(sdp: String) {
        if (!hasMic()) {
            sendJson(JSONObject().put("type", "call-unavailable").put("reason", "no-permission"))
            return
        }
        val v = voice ?: VoiceCall(this, ::sendJson) { st -> main.post { onCallState(st) } }.also {
            voice = it
            it.setMuted(SessionState.state.value.muted)
            Toast.makeText(this, R.string.toast_call_started, Toast.LENGTH_LONG).show()
        }
        val servers: List<PeerConnection.IceServer> = VoiceCall.parseIceServers(iceServers)
        v.handleOffer(sdp, servers)
    }

    private fun onCallState(st: VoiceCall.State) {
        val ui = when (st) {
            VoiceCall.State.CONNECTING -> SessionState.Call.CONNECTING
            VoiceCall.State.CONNECTED -> SessionState.Call.ACTIVE
            VoiceCall.State.RECONNECTING -> SessionState.Call.RECONNECTING
            VoiceCall.State.FAILED -> SessionState.Call.FAILED
            VoiceCall.State.ENDED -> SessionState.Call.NONE
        }
        SessionState.update { it.copy(call = ui) }
        updateNotification()
    }

    private fun endCall() {
        voice?.close()
        voice = null
        SessionState.update { it.copy(call = SessionState.Call.NONE) }
        updateNotification()
    }

    // ------------------------------------------------------------------ control

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
            if (type in setOf("tap", "longpress", "swipe", "scroll", "global", "text", "key")) {
                notice("Керування вимкнено на телефоні (Спеціальні можливості → «Допомога — керування»)")
            }
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

        voice?.close(notify = false)
        voice = null

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
        val s = streamer
        streamer = null
        capture.post { s?.release() }
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
            SessionState.Phase.CONNECTED ->
                if (st.call == SessionState.Call.ACTIVE) getString(R.string.notif_title_call)
                else getString(R.string.notif_title_connected)
            SessionState.Phase.WAITING -> getString(R.string.notif_title_waiting, formatCode(st.code))
            SessionState.Phase.RECONNECTING -> getString(R.string.reconnecting)
            else -> getString(R.string.connecting)
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_stop), stop)
        if (st.call != SessionState.Call.NONE) {
            val mute = PendingIntent.getService(
                this, 2,
                Intent(this, ScreenShareService::class.java).setAction(ACTION_MUTE).putExtra(EXTRA_MUTED, !st.muted),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, getString(if (st.muted) R.string.mic_unmute else R.string.mic_mute), mute)
        }
        return b.build()
    }

    private fun updateNotification() {
        if (projection == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}

fun formatCode(code: String?): String =
    if (code == null || code.length != 6) code ?: "" else code.substring(0, 3) + " " + code.substring(3)
