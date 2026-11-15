// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.json.JSONObject
import org.linphone.R
import org.linphone.mango9.*
import org.linphone.ui.main.crm.viewmodel.Mango9SupervisorViewModel
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class Mango9AssistantMediaUI(private val host: Fragment, private val model: () -> Mango9SupervisorViewModel) {
    private var capture: File? = null
    private var captureChat: String? = null
    private var selectedChat: String? = null
    private var recording: PcmRecording? = null
    private var audio: MediaPlayer? = null
    private var playingVideo: VideoView? = null
    private var focus: AudioFocusRequest? = null
    private var saveSource: Uri? = null
    private val saveFile = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = saveSource; saveSource = null
        val destination = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && source != null && destination != null) {
            val context = host.requireContext().applicationContext
            host.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(source).use { input ->
                            requireNotNull(input)
                            context.contentResolver.openOutputStream(destination, "wt").use { output ->
                                requireNotNull(output); input.copyTo(output, 65536)
                            }
                        }
                    }; toast("File saved.")
                } catch (_: Exception) { toast("Could not save this file. Try again.") }
            }
        }
    }
    private val picked = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) copySelected(uri, selectedChat) }
    private val photo = host.registerForActivityResult(ActivityResultContracts.TakePicture()) { okay -> captured(okay) }
    private val video = host.registerForActivityResult(ActivityResultContracts.CaptureVideo()) { okay -> captured(okay) }
    private var permissionStart: (() -> Unit)? = null
    private val microphone = host.registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> val start = permissionStart; permissionStart = null; if (allowed && host.view != null) start?.invoke() else toast("Allow microphone access to record a message.") }

    private fun dp(n: Int) = (host.resources.displayMetrics.density * n).toInt()

    private fun toast(message: String) {
        if (host.isAdded) Toast.makeText(host.requireContext(), message, Toast.LENGTH_LONG).show()
    }

    fun menu(anchor: View) {
        val m = model(); if (!m.accepted || !m.authorized || !m.transfer.enabled || m.transfer.draft != null || m.transfer.active) return
        // Keep this order aligned with the iOS Assistant's fixed attachment menu.
        val options = mutableListOf(
            Triple(1, "Photos and videos", R.drawable.image),
            Triple(2, "Take photo", R.drawable.camera),
            Triple(3, "Record video", R.drawable.video_camera),
            Triple(4, "Choose file", R.drawable.file),
        )
        if ("audio/wav" in m.transfer.allowedTypes) options.add(Triple(5, "Voice", R.drawable.waveform))
        val popup = androidx.appcompat.widget.PopupMenu(host.requireContext(), anchor)
        options.forEachIndexed { order, (id, title, icon) ->
            popup.menu.add(android.view.Menu.NONE, id, order, title).icon =
                ContextCompat.getDrawable(host.requireContext(), icon)?.mutate()?.apply {
                    setTint(ContextCompat.getColor(host.requireContext(), R.color.orange_main_500))
                }
        }
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            selectedChat = model().conversation
            when (item.itemId) {
                1 -> picked.launch(arrayOf("image/*", "video/*"))
                2 -> capture(false)
                3 -> capture(true)
                4 -> picked.launch(Mango9AssistantMediaContract.types.toTypedArray())
                5 -> record()
                else -> return@setOnMenuItemClickListener false
            }; true
        }
        popup.show()
    }

    private fun capture(movie: Boolean) {
        try {
            val dir = File(host.requireContext().cacheDir, "ephonix-supervisor-picked").apply { mkdirs() }
            val file = File(dir, UUID.randomUUID().toString() + if (movie) ".mp4" else ".jpg")
            capture = file; captureChat = model().conversation
            val uri = FileProvider.getUriForFile(host.requireContext(), host.getString(R.string.file_provider), file)
            if (movie) video.launch(uri) else photo.launch(uri)
        } catch (_: Exception) { toast("A camera app could not be opened.") }
    }

    private fun captured(okay: Boolean) {
        val file = capture ?: return; capture = null
        if (okay && host.view != null && model().conversation == captureChat) {
            model().selectAttachment(Uri.fromFile(file), file.name)
        } else {
            file.delete()
        }
    }

    private fun copySelected(uri: Uri, chat: String?) {
        val context = host.requireContext().applicationContext
        host.lifecycleScope.launch {
            var target: File? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    var name = ""
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            name = c.getString(0); if (!c.isNull(1)) {
                                require(c.getLong(1) in 1..Mango9AssistantMediaContract.maximum) {
                                "Choose a file up to 500 MB."
                            }
                            }
                        }
                    }
                    require(name.isNotBlank() && name.length <= 240)
                    val dir = File(context.cacheDir, "ephonix-supervisor-picked").apply { mkdirs() }
                    val output = File(dir, UUID.randomUUID().toString() + "." + name.substringAfterLast('.', "")); target = output
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        output.outputStream().use { out ->
                        var bytes = 0L; val buffer = ByteArray(65536)
                        while (true) {
                            ensureActive(); val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= Mango9AssistantMediaContract.maximum && dir.usableSpace > 134217728L) {
                                "Choose a smaller file or free some space."
                            }; out.write(buffer, 0, n)
                        }
                        require(bytes > 0)
                    }
                    }
                    output to name
                }
                if (host.view != null && model().conversation == chat && model().accepted) {
                    model().selectAttachment(Uri.fromFile(result.first), result.second); target = null
                }
            } catch (e: Exception) {
                if (e !is CancellationException) toast(e.message ?: "Could not read this file.")
            } finally { target?.delete() }
        }
    }

    fun tray(): View? {
        val m = model(); val d = m.transfer.draft
        if (d == null && !m.transfer.active && m.transfer.error == null) return null
        val ui = Mango9LiveViews(host.requireContext()); val layout = ui.column().apply {
            setPadding(dp(8), dp(4), dp(8), dp(4)); background = ui.round(0xffeef1ff.toInt(), 14)
        }
        if (d != null) {
            val f = d.getJSONObject("file"); val row = ui.row()
            row.addView(
                ui.button(if (f.getString("mime").startsWith("audio/")) "▶" else "▣") {
                openLocal(Uri.parse(f.getString("uri")), f.getString("mime"))
            }.apply { contentDescription = "Preview attachment" },
                LinearLayout.LayoutParams(dp(44), dp(48))
            )
            row.addView(
                ui.text(f.getString("name"), 13f, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            row.addView(
                ui.button("×") {
                host.viewLifecycleOwner.lifecycleScope.launch {
                    m.transfer.remove()
                }
            }.apply {
                contentDescription = "Remove attachment"; isEnabled = !m.transfer.active
            },
                LinearLayout.LayoutParams(dp(44), dp(44))
            )
            layout.addView(row)
            layout.addView(ui.text(m.transfer.label + " · " + Mango9AssistantMediaContract.sizeLabel(f.getLong("size")), 11f))
        } else if (m.transfer.active) layout.addView(ui.text(m.transfer.label, 12f))
        m.transfer.error?.let { layout.addView(ui.text(it, 12f).apply { setTextColor(0xff9e3548.toInt()) }) }
        return layout
    }

    fun bubble(file: JSONObject): View {
        val context = host.requireContext(); val ui = Mango9LiveViews(context)
        val box = ui.column().apply { setPadding(dp(10), dp(8), dp(10), dp(4)); background = ui.round(Color.WHITE, 16) }
        val chat = model().conversation; val owner = model().owner(); val id = file.getString("id")

        fun current() = host.view != null && model().conversation == chat && model().owner() == owner
        val title = ui.text(file.optString("name", "Attachment"), 13f, true).apply { maxLines = 2 }; box.addView(title)
        val preview = FrameLayout(context); box.addView(preview)
        val controls = ui.row()
        val size = ui.text(Mango9AssistantMediaContract.sizeLabel(file.optLong("size")), 11f); controls.addView(size, LinearLayout.LayoutParams(0, -2, 1f))
        var metadata = file; var source: String? = null
        val open = ui.button("▶") {
            host.viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!current() || Mango9SupervisorVoice.hasCall()) return@launch
                if (metadata.optString("mime").startsWith("audio/")) {
                    stopPlayback(); val resource = model().transfer.resource(file); if (!current()) return@launch
                    audio = MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()); setDataSource(resource.first); setOnPreparedListener {
                            if (current() && audioFocus()) it.start() else stopPlayback()
                        }; setOnCompletionListener {
                            stopPlayback()
                        }; setOnErrorListener { _, _, _ -> stopPlayback(); toast("Could not play this recording."); true }; prepareAsync()
                    }
                } else if (metadata.optString("mime").startsWith("video/")) {
                    stopPlayback(); val resource = model().transfer.resource(file); if (!current()) return@launch
                    val player = VideoView(context); playingVideo = player; preview.removeAllViews(); preview.addView(player, FrameLayout.LayoutParams(-1, dp(200)))
                    player.setMediaController(
                        MediaController(context).apply {
                        setAnchorView(player)
                    }
                    ); player.setVideoURI(Uri.parse(resource.first)); player.start()
                } else { val uri = model().transfer.download(file); if (current()) openLocal(uri, metadata.optString("mime")) }
            } catch (_: Exception) { if (current()) toast("Could not open this attachment. Try again.") }
        }
        }.apply { contentDescription = "Open attachment" }
        controls.addView(open, LinearLayout.LayoutParams(dp(44), dp(44)))
        controls.addView(
            ui.button("↗") {
                host.viewLifecycleOwner.lifecycleScope.launch {
            try {
                stopPlayback(); val uri = model().transfer.download(file); if (current()) exportLocal(uri, metadata.optString("mime"), metadata.optString("name", "Attachment"))
            } catch (_: Exception) { if (current()) toast("Could not prepare this file. Try again.") }
        }
            }.apply { contentDescription = "Save or share attachment" },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )
        box.addView(controls)
        host.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resource = model().transfer.resource(file); if (!current()) return@launch
                metadata = resource.second; source = resource.first; title.text = metadata.optString("name", "Attachment"); size.text = Mango9AssistantMediaContract.sizeLabel(metadata.getLong("size"))
                if (metadata.getString("mime").startsWith("image/") && metadata.getLong("size") <= 8_000_000) {
                    val local = model().transfer.download(file)
                    val poster = model().transfer.nativeResult {
                        model().transfer.native.previewLargeMedia(local.toString(), metadata.getString("mime"), id, it)
                    } as? String
                    if (current() && poster != null) {
                        preview.addView(
                            ImageView(context).apply {
                        setImageURI(Uri.parse(poster)); scaleType = ImageView.ScaleType.FIT_CENTER; setOnClickListener {
                            open.performClick()
                        }
                    },
                        FrameLayout.LayoutParams(-1, dp(200))
                        )
                    }
                } else if (metadata.getString("mime").startsWith("video/")) {
                    // A paused native stream displays the actual first frame without autoplaying sound.
                    val player = VideoView(context); preview.addView(player, FrameLayout.LayoutParams(-1, dp(200)))
                    player.setAudioFocusRequest(android.media.AudioManager.AUDIOFOCUS_NONE); player.setVideoURI(Uri.parse(resource.first))
                    player.setOnPreparedListener {
                        it.setVolume(0f, 0f); player.seekTo(1)
                    }; player.setOnTouchListener { _, event -> if (event.action == android.view.MotionEvent.ACTION_UP) open.performClick(); true }
                    box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener { override fun onViewAttachedToWindow(v: View) {
                        }

                    override fun onViewDetachedFromWindow(v: View) { player.stopPlayback() } })
                }
            } catch (_: Exception) { if (current()) title.text = metadata.optString("name", "Attachment") + " · Tap to open" }
        }
        return box
    }

    private fun openLocal(uri: Uri, mime: String) {
        if (mime.startsWith("audio/")) {
            host.viewLifecycleOwner.lifecycleScope.launch {
                if (Mango9SupervisorVoice.hasCall()) return@launch
                stopPlayback(); audio = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()); setDataSource(host.requireContext(), uri); setOnPreparedListener {
                        if (audioFocus()) it.start() else stopPlayback()
                    }; setOnCompletionListener { stopPlayback() }; prepareAsync()
                }
            }; return
        }
        try {
            val safe = FileProvider.getUriForFile(host.requireContext(), host.getString(R.string.file_provider), File(uri.path!!)); host.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(safe, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { toast("No app can open this file. Use Save or share instead.") }
    }

    private fun exportLocal(uri: Uri, mime: String, name: String) {
        MaterialAlertDialogBuilder(host.requireContext()).setTitle("Attachment")
            .setItems(arrayOf("Save as…", "Share…", "Open with…")) { _, choice ->
                when (choice) {
                    0 -> {
                        saveSource = uri
                        saveFile.launch(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(mime.ifBlank { "application/octet-stream" }).putExtra(Intent.EXTRA_TITLE, name)
                        )
                    }
                    1 -> shareLocal(uri, mime)
                    2 -> openLocal(uri, mime)
                }
            }.show()
    }

    private fun shareLocal(uri: Uri, mime: String) {
        val safe = FileProvider.getUriForFile(host.requireContext(), host.getString(R.string.file_provider), File(uri.path!!))
        host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, safe).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Save or share attachment"))
    }

    private fun record() {
        if (host.view == null || !model().accepted || model().voice.running) return
        host.viewLifecycleOwner.lifecycleScope.launch {
            if (Mango9SupervisorVoice.hasCall()) { toast("Finish your call before recording."); return@launch }
            val chat = model().conversation; val ui = Mango9LiveViews(host.requireContext()); val layout = ui.column().apply {
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }
            val time = ui.text("00:00", 30f, true).apply { gravity = Gravity.CENTER }; layout.addView(time)
            layout.addView(ui.text("High quality · WAV · 48 kHz · Up to 5 minutes", 12f).apply { gravity = Gravity.CENTER })
            val rec = PcmRecording(
                File(host.requireContext().cacheDir, "ephonix-supervisor-picked").apply {
                mkdirs()
            }
            ); recording = rec
            layout.background = ui.round(0xfff5f6fc.toInt(), 22)
            val status = ui.text("Tap Record to start", 14f).apply { gravity = Gravity.CENTER }; layout.addView(status)
            val controls = ui.row().apply { gravity = Gravity.CENTER }
            val retake = ui.button("↻") { stopPlayback(); rec.reset(); status.text = "Tap Record to start" }.apply { contentDescription = "Record again" }
            val control = ImageButton(host.requireContext()).apply { setImageResource(R.drawable.microphone); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); setPadding(dp(18),dp(18),dp(18),dp(18)); contentDescription = "Start recording"; background = ui.round(0xff4454ca.toInt(), 32) }
            retake.visibility = View.GONE
            controls.addView(retake, LinearLayout.LayoutParams(dp(44), dp(44))); controls.addView(control, LinearLayout.LayoutParams(dp(64), dp(64)))
            val wave = ui.row().apply { gravity = Gravity.CENTER; background = ui.round(0xffeceeff.toInt(),16) }
            val bars = (0 until 32).map { index -> View(host.requireContext()).apply { background = ui.round(0xff4454ca.toInt(),2); wave.addView(this,LinearLayout.LayoutParams(dp(3),dp(4)).apply { marginStart = dp(2); marginEnd = dp(2) }) } }
            layout.addView(wave,0,LinearLayout.LayoutParams(-1,dp(64)).apply { bottomMargin = dp(12) })
            layout.addView(controls); layout.addView(ui.text("Review here, then send from your message.", 12f).apply { gravity = Gravity.CENTER })
            var inserted = false
            val dialog = MaterialAlertDialogBuilder(host.requireContext()).setTitle("Voice recording").setView(layout).setBackground(ui.round(0xfff5f6fc.toInt(), 24))
                .setNegativeButton("Cancel", null).setPositiveButton("Insert", null).create()
            dialog.setOnDismissListener { permissionStart = null; stopPlayback(); rec.stop(); if (!inserted) rec.file.delete(); if (recording == rec) recording = null }
            dialog.show()
            dialog.getButton(-2).setTextColor(0xff4454ca.toInt())
            dialog.getButton(-1).setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled),intArrayOf()),intArrayOf(0xff4454ca.toInt(),0xff9699a6.toInt())))
            fun start() {
                if (!dialog.isShowing || host.view == null || model().conversation != chat) return
                host.viewLifecycleOwner.lifecycleScope.launch {
                    if (Mango9SupervisorVoice.hasCall()) { toast("Finish your call before recording."); return@launch }
                    try { stopPlayback(); rec.start(); status.text = "Recording…" }
                    catch (_: Exception) { status.text = "Could not start recording. Please try again." }
                }
            }
            control.setOnClickListener {
                when {
                    rec.active -> { rec.stop(); status.text = "Ready to attach" }
                    audio != null -> { stopPlayback(); status.text = "Ready to attach" }
                    rec.seconds > .2 -> { openLocal(Uri.fromFile(rec.file), "audio/wav"); status.text = "Playing preview" }
                    ContextCompat.checkSelfPermission(host.requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> start()
                    else -> { permissionStart = ::start; microphone.launch(Manifest.permission.RECORD_AUDIO) }
                }
            }
            dialog.getButton(-1).setOnClickListener {
                if (!rec.active && rec.seconds > .2 && model().conversation == chat) {
                    stopPlayback(); inserted = true; model().selectAttachment(Uri.fromFile(rec.file), "Voice recording.wav"); dialog.dismiss()
                }
            }
            while (dialog.isShowing && host.view != null) {
                time.text = "%02d:%02d".format(rec.seconds.toInt() / 60, rec.seconds.toInt() % 60)
                val ready = !rec.active && rec.seconds > .2
                control.setImageResource(if (rec.active || audio != null) R.drawable.stop_fill else if (ready) R.drawable.play_fill else R.drawable.microphone)
                bars.forEachIndexed { index, bar -> bar.layoutParams = (bar.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (rec.active) 4 + (rec.level * (12 + index * 17 % 42)).toInt() else 4) } }
                control.contentDescription = if (rec.active) "Stop recording" else if (audio != null) "Stop playback" else if (ready) "Play recording" else "Start recording"
                retake.visibility = if (ready) View.VISIBLE else View.GONE
                dialog.getButton(-1).isEnabled = ready
                if (ready && audio == null) status.text = "Ready to attach"
                delay(200)
            }
        }
    }

    private fun audioFocus(): Boolean {
        val manager = host.requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change < 0) stopPlayback() }.build()
        focus = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun stopPlayback() {
        audio?.runCatching {
            stop(); release()
        }; audio = null; focus?.let {
            if (host.isAdded) (host.requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it)
        }; focus = null; playingVideo?.stopPlayback(); playingVideo = null
    }

    fun background() { stopPlayback(); recording?.stop() }

    fun close() { stopPlayback(); recording?.stop(); recording?.file?.delete(); recording = null }
}

@Suppress("MissingPermission")
private class PcmRecording(root: File) {
    val file = File(root, "Voice-" + UUID.randomUUID() + ".wav")
    val paused = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    val active get() = running.get()

    @Volatile private var bytes = 0L
    @Volatile var level = 0f; private set
    val seconds get() = bytes / 96000.0
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null

    fun reset() { stop(); file.delete(); bytes = 0L; paused.set(false) }

    fun start() {
        reset()
        val buffer =
            maxOf(8192, AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val record =
            AudioRecord(MediaRecorder.AudioSource.MIC, 48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer)
        require(record.state == AudioRecord.STATE_INITIALIZED); recorder = record; running.set(true); record.startRecording()
        worker = Thread {
            try {
                RandomAccessFile(file, "rw").use { output ->
                    output.setLength(0); output.write(ByteArray(44)); val data = ByteArray(buffer)
                while (running.get() && bytes < 28_800_000) {
                    val n = record.read(data, 0, data.size); if (n <= 0) break; if (!paused.get()) {
                        var peak = 0
                        for (index in 0 until n - 1 step 2) { val sample = ((data[index].toInt() and 255) or (data[index+1].toInt() shl 8)).toShort().toInt(); peak = maxOf(peak,kotlin.math.abs(sample)) }
                        level = (peak / 32768f).coerceIn(0f,1f)
                        output.write(data, 0, n); bytes += n
                    }
                }
                output.seek(0); output.write(header(bytes.toInt()))
            }
            } finally { running.set(false); runCatching { record.stop() }; record.release() }
        }.apply { name = "Assistant voice recording"; start() }
    }

    fun stop() { running.set(false); runCatching { recorder?.stop() }; worker?.join(2000); recorder = null }

    private fun header(size: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(48000); putInt(96000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
    }.array()
}
