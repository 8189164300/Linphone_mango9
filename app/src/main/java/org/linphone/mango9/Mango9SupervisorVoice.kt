// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.sqrt

/** Foreground, explicitly started voice. No ticket reuse or automatic reconnect. */
internal class Mango9SupervisorVoice(private val context: Context, private val scope: CoroutineScope) {
    enum class State { IDLE, CONNECTING, CONNECTED }

    var state = State.IDLE; private set
    var muted = false; private set
    var speaking = false; private set
    var level = 0.0; private set
    var error: String? = null; private set
    var changed: () -> Unit = {}
    var historyChanged: () -> Unit = {}
    val running get() = state != State.IDLE
    val status get() = when { state == State.CONNECTING -> "Connecting…"; state == State.IDLE -> if (error == null) "Ready to connect" else "Voice disconnected"; muted -> "Microphone muted"; speaking -> "Speaking…"; else -> "Listening…" }
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var generation = 0L
    private var connectJob: Job? = null
    private var watch: Job? = null
    private var cleanup: Job? = null
    private var audio: SupervisorAudio? = null
    private var socket: WebSocket? = null
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()

    fun ready() { if (!running) { error = null; changed() } }

    fun start(api: Mango9SupervisorApi, lease: Mango9SessionLease, conversation: String) {
        if (running) return
        stop(); val stamp = generation; state = State.CONNECTING; muted = false; error = null; changed()
        connectJob = scope.launch {
            try {
                checkAccess(api, lease)
                cleanup?.join()
                if (hasCall()) throw Mango9SupervisorFailure("call_active")
                ensureActive(); if (stamp != generation) return@launch
                checkAccess(api, lease)
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw Mango9SupervisorFailure("voice_permission")
                val (ticket, bearer) = api.ticket(lease, conversation)
                ensureActive(); if (stamp != generation) return@launch
                checkAccess(api, lease)
                require(ticket.optString("ticket").matches(Regex("[A-Za-z0-9_-]{43}")))
                for ((key, rate) in listOf("inputAudio" to 16000, "outputAudio" to 24000)) {
                    val format = ticket.getJSONObject(key)
                    require(format.optInt("sampleRate") == rate && format.optString("encoding") == "pcm_s16le" && format.optInt("channels") == 1)
                }
                val request = Request.Builder().url(Mango9SupervisorWire.voiceUrl(bearer, ticket.getString("websocketPath")))
                    .header("Authorization", "Bearer ${bearer.accessToken}").build()
                socket = client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        scope.launch {
                            if (stamp == generation && api.active(lease) && api.consent(lease)) ws.send(JSONObject().put("type", "authenticate").put("ticket", ticket.getString("ticket")).toString()) else ws.cancel()
                        }
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        if (text.toByteArray().size > 524288) {
                            scope.launch { if (stamp == generation) fail("voice_connection_failed") }; return
                        }
                        scope.launch {
                            if (stamp != generation) return@launch
                            try {
                                checkAccess(api, lease)
                                val event = JSONObject(text)
                                when (event.optString("type")) {
                                    "ready" -> {
                                        if (state != State.CONNECTING || audio != null) return@launch
                                        val engine = SupervisorAudio(
                                            context,
                                            allowed = { stamp == generation && api.active(lease) && api.consent(lease) },
                                            send = { data -> if (ws.queueSize() > 32768 || !ws.send(data.toByteString())) throw Mango9SupervisorFailure("voice_connection_lost") },
                                            update = { value, talking ->
                                                handler.post {
                                                if (stamp == generation) { level = value; speaking = talking; changed() }
                                            }
                                            },
                                            failure = {
                                                scope.launch {
                                                val inCall = runCatching { hasCall() }.getOrDefault(true)
                                                if (stamp == generation) fail("voice_interrupted", releaseRoute = !inCall)
                                            }
                                            }
                                        )
                                        audio = engine
                                        try { withContext(Dispatchers.IO) { engine.start() } } catch (e: Exception) { engine.close(); throw e }
                                        if (stamp != generation) { engine.close(); return@launch }
                                        state = State.CONNECTED; changed()
                                    }
                                    "audio" -> {
                                        if (state != State.CONNECTED || event.optInt("sampleRate") != 24000) return@launch
                                        val pcm = Base64.decode(event.getString("pcm"), Base64.DEFAULT)
                                        if (pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > 262144) throw Mango9SupervisorFailure("voice_audio_format")
                                        audio?.play(pcm)
                                    }
                                    "interrupted" -> audio?.clearPlayback()
                                    "history_saved", "task_settled", "supervisor_result" -> historyChanged()
                                    "notice" -> { error = Mango9SupervisorFailure(event.optString("code")).message; changed() }
                                    "closed" -> {
                                        val code = event.optString("code"); stop(); if (code != "voice_session_complete") error = Mango9SupervisorFailure(code).message; changed(); historyChanged()
                                    }
                                }
                            } catch (e: Exception) {
                                if (e !is CancellationException && stamp == generation) fail((e as? Mango9SupervisorFailure)?.code ?: "voice_connection_failed")
                            }
                        }
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        scope.launch { if (stamp == generation) fail("voice_connection_lost") }
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        scope.launch { if (stamp == generation) fail("voice_connection_lost") }
                    }
                }
                )
                watch = scope.launch {
                    val started = SystemClock.elapsedRealtime()
                    while (isActive && stamp == generation) {
                        delay(1000)
                        try {
                            checkAccess(api, lease)
                            if (hasCall()) { fail("call_active", releaseRoute = false); return@launch }
                            if (state == State.CONNECTING && SystemClock.elapsedRealtime() - started > 25000) {
                                fail("voice_connection_failed"); return@launch
                            }
                        } catch (e: Exception) {
                            if (e !is CancellationException && stamp == generation) fail((e as? Mango9SupervisorFailure)?.code ?: "voice_connection_failed")
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException && stamp == generation) fail((e as? Mango9SupervisorFailure)?.code ?: "voice_connection_failed")
            }
        }
    }

    private fun checkAccess(api: Mango9SupervisorApi, lease: Mango9SessionLease) {
        if (!api.active(lease)) throw Mango9SupervisorFailure("scope_changed")
        if (!api.consent(lease)) throw Mango9SupervisorFailure("ai_consent_required")
    }

    fun setMuted(value: Boolean) {
        if (state != State.CONNECTED) return
        muted = value; audio?.muted = value; level = 0.0
        if (value) socket?.send("{\"type\":\"flush\"}")
        changed()
    }

    private fun fail(code: String, releaseRoute: Boolean = true) {
        stop(releaseRoute); error = Mango9SupervisorFailure(code).message; changed(); historyChanged()
    }

    fun stop(releaseRoute: Boolean = true) {
        generation++; state = State.IDLE; speaking = false; level = 0.0
        connectJob?.cancel(); connectJob = null; watch?.cancel(); watch = null
        val previous = audio; audio = null
        if (previous != null) { previous.deactivate(); cleanup = cleanupScope.launch { previous.close(releaseRoute) } }
        val ws = socket; socket = null
        ws?.send("{\"type\":\"stop\"}"); ws?.close(1000, "Voice ended")
        if (ws != null) handler.postDelayed({ ws.cancel() }, 1000)
        changed()
    }

    companion object {
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        suspend fun hasCall(): Boolean = withTimeout(3000) {
            suspendCancellableCoroutine { continuation ->
                val ticket = coreContext.postOnCoreThreadCancellable { core -> if (continuation.isActive) continuation.resume(core.callsNb > 0) }
                continuation.invokeOnCancellation { ticket.cancel() }
            }
        }
    }
}

/** VOICE_COMMUNICATION capture/output share Android's echo reference. */
@Suppress("DEPRECATION", "MissingPermission")
internal class SupervisorAudio(
    context: Context, val allowed: () -> Boolean, val send: (ByteArray) -> Unit,
    val update: (Double, Boolean) -> Unit, val failure: () -> Unit,
) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var active = true

    @Volatile var muted = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var nativeEcho = false
    private var previousMode = manager.mode
    private var previousSpeaker = manager.isSpeakerphoneOn
    private var ownsRoute = false
    private var focus: AudioFocusRequest? = null
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Packet(val bytes: ByteArray, val epoch: Int)

    private val packets = LinkedBlockingQueue<Packet>(32)
    private val queued = AtomicInteger()
    private val epoch = AtomicInteger()

    @Volatile private var quietAt = 0L
    private var routeListener: AudioDeviceCallback? = null
    val capturing: Boolean get() = active && record?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    val echoCancellationEnabled: Boolean get() = nativeEcho

    @Synchronized fun start() {
        check(active && allowed())
        if (manager.mode == AudioManager.MODE_IN_CALL) throw Mango9SupervisorFailure("call_active")
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change -> if (change < 0 && active) { active = false; failure() } }.build()
        if (manager.requestAudioFocus(focus!!) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw Mango9SupervisorFailure("voice_interrupted")
        previousMode = manager.mode; previousSpeaker = manager.isSpeakerphoneOn
        manager.mode = AudioManager.MODE_IN_COMMUNICATION; ownsRoute = true
        if (Build.VERSION.SDK_INT >= 31) {
            val current = manager.communicationDevice
            if (current == null || current.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || current.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                manager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }?.let { check(manager.setCommunicationDevice(it)) }
            }
        } else if (!manager.isWiredHeadsetOn && !manager.isBluetoothScoOn) manager.isSpeakerphoneOn = true
        val inMin = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outMin = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (inMin <= 0 || outMin <= 0) throw Mango9SupervisorFailure("voice_audio_format")
        record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(inMin * 2, 3200)).build()
        check(record!!.state == AudioRecord.STATE_INITIALIZED)
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(record!!.audioSessionId); aec?.enabled = true; nativeEcho = aec?.enabled == true
        }
        track = AudioTrack.Builder().setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(outMin, 9600)).build()
        check(track!!.state == AudioTrack.STATE_INITIALIZED)
        check(active && allowed())
        record!!.startRecording(); track!!.play()
        routeListener = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
                if (active && devices.any {
                    it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
                }
                ) { active = false; failure() }
            }
        }.also { manager.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }
        jobs.launch {
            val input = ByteArray(640); var lastUpdate = 0L
            try {
                while (isActive && active) {
                    val count = record!!.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) { if (active) throw Mango9SupervisorFailure("voice_audio_format") else break }
                    if (!active || !allowed()) break
                    val speaking = queued.get() > 0 || SystemClock.elapsedRealtime() < quietAt
                    val audible = !muted && (nativeEcho || !speaking)
                    if (audible) send(input.copyOf(count))
                    if (SystemClock.elapsedRealtime() - lastUpdate >= 100) {
                        var energy = 0.0
                        for (i in 0 until count - 1 step 2) {
                            val v = ((input[i].toInt() and 255) or (input[i + 1].toInt() shl 8)).toShort().toDouble() / 32768; energy += v * v
                        }
                        update(if (audible) (sqrt(energy / (count / 2)) * 5).coerceAtMost(1.0) else 0.0, speaking); lastUpdate = SystemClock.elapsedRealtime()
                    }
                }
                if (active) failure()
            } catch (_: Exception) { if (active) failure() }
        }
        jobs.launch {
            try {
                while (isActive && active) {
                    val packet = packets.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    var offset = 0
                    while (active && allowed() && packet.epoch == epoch.get() && offset < packet.bytes.size) {
                        val size = minOf(2048, packet.bytes.size - offset)
                        val written = track!!.write(packet.bytes, offset, size, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) throw Mango9SupervisorFailure("voice_audio_format")
                        offset += written; quietAt = SystemClock.elapsedRealtime() + 300
                    }
                    if (packet.epoch == epoch.get()) queued.addAndGet(-packet.bytes.size)
                }
            } catch (_: Exception) { if (active) failure() }
        }
    }

    fun play(bytes: ByteArray) {
        if (!active) return
        if (queued.addAndGet(bytes.size) > 960000 || !packets.offer(Packet(bytes, epoch.get()))) throw Mango9SupervisorFailure("voice_connection_failed")
    }

    @Synchronized fun clearPlayback() {
        epoch.incrementAndGet(); packets.clear(); queued.set(0); quietAt = 0
        runCatching { track?.pause(); track?.flush(); if (active) track?.play() }
    }

    fun deactivate() { active = false }

    @Synchronized fun close(restore: Boolean = true) {
        active = false; jobs.cancel(); packets.clear()
        routeListener?.let { manager.unregisterAudioDeviceCallback(it) }; routeListener = null
        runCatching { record?.stop() }; runCatching { track?.stop() }
        aec?.release(); aec = null; record?.release(); record = null; track?.release(); track = null
        if (restore && ownsRoute && manager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice() else manager.isSpeakerphoneOn = previousSpeaker
            manager.mode = previousMode
        }
        ownsRoute = false; focus?.let { manager.abandonAudioFocusRequest(it) }; focus = null
    }
}
