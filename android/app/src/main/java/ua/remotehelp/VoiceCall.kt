package ua.remotehelp

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Two-way voice with the helper over WebRTC (Opus, echo cancellation, jitter buffer,
 * forward error correction). The helper's browser makes the offer; the phone answers
 * automatically — the owner already asked for help by starting the session.
 *
 * Audio goes peer-to-peer when possible (or via TURN if the server provides one);
 * the relay server only forwards the signalling messages.
 */
class VoiceCall(
    private val ctx: Context,
    private val signal: (JSONObject) -> Unit,
    private val onStateChanged: (State) -> Unit,
) {
    enum class State { CONNECTING, CONNECTED, RECONNECTING, FAILED, ENDED }

    companion object {
        private const val TAG = "RemoteHelp"
        @Volatile private var initialized = false

        private fun ensureInit(ctx: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                    .createInitializationOptions()
            )
            initialized = true
        }

        fun parseIceServers(arr: JSONArray?): List<PeerConnection.IceServer> {
            if (arr == null) return listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            val out = mutableListOf<PeerConnection.IceServer>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val urls = when (val u = o.opt("urls")) {
                    is JSONArray -> (0 until u.length()).map { u.optString(it) }
                    is String -> listOf(u)
                    else -> emptyList()
                }.filter { it.isNotBlank() }
                if (urls.isEmpty()) continue
                out += PeerConnection.IceServer.builder(urls)
                    .setUsername(o.optString("username", ""))
                    .setPassword(o.optString("credential", ""))
                    .createIceServer()
            }
            return out
        }

        /** Ask for Opus with in-band FEC and DTX at a modest bitrate — robust on bad links. */
        fun tuneOpus(sdp: String): String {
            val pt = Regex("a=rtpmap:(\\d+) opus/48000", RegexOption.IGNORE_CASE).find(sdp)?.groupValues?.get(1) ?: return sdp
            val fmtp = Regex("a=fmtp:$pt ([^\\r\\n]*)")
            val extra = mapOf("useinbandfec" to "1", "usedtx" to "1", "maxaveragebitrate" to "32000")
            return if (fmtp.containsMatchIn(sdp)) {
                fmtp.replace(sdp) { m ->
                    val params = m.groupValues[1].split(';').map { it.trim() }.filter { it.isNotEmpty() }
                        .associate { p -> p.substringBefore('=') to p.substringAfter('=', "") }
                        .toMutableMap()
                    params.putAll(extra)
                    "a=fmtp:$pt " + params.entries.joinToString(";") { "${it.key}=${it.value}" }
                }
            } else sdp
        }
    }

    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    @Volatile private var remoteSet = false
    @Volatile var muted = false
        private set
    @Volatile var state = State.ENDED
        private set

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false

    private fun setState(s: State) {
        if (state == s) return
        state = s
        signal(JSONObject().put("type", "call-state").put("state", s.name.lowercase()).put("muted", muted))
        onStateChanged(s)
    }

    @Synchronized
    fun handleOffer(sdp: String, iceServers: List<PeerConnection.IceServer>) {
        if (pc == null && !create(iceServers)) {
            signal(JSONObject().put("type", "call-unavailable").put("reason", "init-failed"))
            return
        }
        val p = pc ?: return
        if (state == State.ENDED || state == State.FAILED) setState(State.CONNECTING)
        p.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteSet = true
                drainIce()
                p.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        val tuned = SessionDescription(desc.type, tuneOpus(desc.description))
                        p.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                signal(JSONObject().put("type", "rtc-answer").put("sdp", tuned.description))
                            }
                        }, tuned)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    @Synchronized
    fun handleIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val c = IceCandidate(sdpMid ?: "0", sdpMLineIndex, candidate)
        if (remoteSet) pc?.addIceCandidate(c) else pendingIce += c
    }

    @Synchronized
    private fun drainIce() {
        pendingIce.forEach { pc?.addIceCandidate(it) }
        pendingIce.clear()
    }

    fun setMuted(m: Boolean) {
        muted = m
        track?.setEnabled(!m)
        if (state == State.ENDED) return   // not in a call yet — just remember the choice
        signal(JSONObject().put("type", "call-state").put("state", state.name.lowercase()).put("muted", m))
        onStateChanged(state)
    }

    private fun create(iceServers: List<PeerConnection.IceServer>): Boolean = try {
        ensureInit(ctx)
        val module = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        adm = module
        val f = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        factory = f

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val p = f.createPeerConnection(config, Observer()) ?: throw IllegalStateException("no peer connection")
        pc = p

        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        }
        source = f.createAudioSource(constraints)
        track = f.createAudioTrack("mic", source).also {
            it.setEnabled(!muted)
            p.addTrack(it, listOf("voice"))
        }
        routeAudio()
        true
    } catch (e: Throwable) {
        Log.e(TAG, "voice init failed", e)
        close(notify = false)
        false
    }

    /** Loudspeaker by default (the owner is looking at the screen), headset if plugged in. */
    private fun routeAudio() {
        savedMode = audio.mode
        @Suppress("DEPRECATION")
        savedSpeaker = audio.isSpeakerphoneOn
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val order = listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            )
            val pick = order.firstNotNullOfOrNull { t -> devices.firstOrNull { it.type == t } }
            if (pick != null) audio.setCommunicationDevice(pick)
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = !audio.isWiredHeadsetOn
        }
    }

    private fun restoreAudio() {
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        else @Suppress("DEPRECATION") { audio.isSpeakerphoneOn = savedSpeaker }
        audio.mode = savedMode
    }

    @Synchronized
    fun close(notify: Boolean = true) {
        val had = pc != null
        try { pc?.close() } catch (_: Throwable) {}
        try { pc?.dispose() } catch (_: Throwable) {}
        pc = null
        try { track?.dispose() } catch (_: Throwable) {}
        track = null
        try { source?.dispose() } catch (_: Throwable) {}
        source = null
        try { factory?.dispose() } catch (_: Throwable) {}
        factory = null
        try { adm?.release() } catch (_: Throwable) {}
        adm = null
        remoteSet = false
        pendingIce.clear()
        if (had) restoreAudio()
        if (notify) setState(State.ENDED) else state = State.ENDED
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            signal(
                JSONObject().put("type", "rtc-ice")
                    .put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
            )
        }

        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            when (s) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> setState(State.CONNECTED)
                PeerConnection.IceConnectionState.DISCONNECTED -> setState(State.RECONNECTING)
                PeerConnection.IceConnectionState.FAILED -> setState(State.FAILED)
                else -> Unit
            }
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(b: Boolean) = Unit
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
        override fun onAddStream(s: MediaStream) = Unit
        override fun onRemoveStream(s: MediaStream) = Unit
        override fun onDataChannel(d: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) = Unit
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) { Log.w(TAG, "sdp create failed: $error") }
        override fun onSetFailure(error: String?) { Log.w(TAG, "sdp set failed: $error") }
    }
}
