// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.linphone.R
import org.linphone.mango9.*
import org.linphone.ui.main.crm.viewmodel.Mango9SupervisorViewModel
import org.linphone.ui.main.fragment.GenericMainFragment
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class Mango9SupervisorFragment : GenericMainFragment() {
    private val model by lazy { ViewModelProvider(this)[Mango9SupervisorViewModel::class.java] }
    private lateinit var ui: Mango9LiveViews
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var notice: TextView
    private lateinit var mode: TextView
    private lateinit var ai: TextView
    private var spinner: android.animation.ObjectAnimator? = null
    private lateinit var messages: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var composer: EditText
    private lateinit var send: TextView
    private lateinit var bottom: LinearLayout
    private lateinit var chat: LinearLayout
    private lateinit var voice: LinearLayout
    private lateinit var orb: SupervisorOrb
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var mute: TextView
    private lateinit var pending: LinearLayout
    private var rendered = ""
    private var renderedConversation = ""
    private var visibleMessageCount = 40
    private var followingLatest = true
    private var aligningMessages = false
    private var layoutVersion = 0
    private lateinit var latest: ImageButton
    private var historyList: RecyclerView? = null
    private var historyRows = listOf<JSONObject>()
    private var historySearch: Job? = null
    private var historySignature = ""
    private val historyFilters = mutableMapOf<String, TextView>()
    private var privacyPrompted = false
    private var restorePrivacy = false
    private var privacyDialog: BottomSheetDialog? = null
    private var toolsDialog: BottomSheetDialog? = null
    private var toolsBody: LinearLayout? = null
    private var toolsMode = ""
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed && view != null && model.voicePage && model.accepted) {
            model.connectVoice()
        } else if (!allowed && view != null) MaterialAlertDialogBuilder(requireContext()).setMessage("Allow microphone access in Android Settings to use Supervisor voice.").setPositiveButton("OK", null).show()
    }

    private fun text(value: String, size: Float = 15f, bold: Boolean = false) = ui.text(value, size, bold).apply {
        setTextColor(INK)
    }

    private fun button(value: String, action: () -> Unit) = ui.button(value, action).apply {
        setTextColor(BLUE); minHeight = ui.dp(48)
    }

    private fun pill(value: String, fill: Int, color: Int, action: () -> Unit) = button(value, action).apply {
        background = ui.round(fill, 28); setTextColor(color); setTypeface(typeface, Typeface.BOLD); isSingleLine = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        ui = Mango9LiveViews(androidx.appcompat.view.ContextThemeWrapper(requireContext(), com.google.android.material.R.style.Theme_MaterialComponents_Light))
        privacyPrompted = state?.getBoolean("privacyPrompted") == true; restorePrivacy = state?.getBoolean("privacyOpen") == true
        rendered = ""; root = ui.column().apply { setBackgroundColor(SURFACE) }
        val header = ui.row().apply { setBackgroundColor(BLUE); setPadding(ui.dp(4), 0, ui.dp(8), 0) }
        header.addView(
            ui.icon(R.drawable.caret_left, "Close Supervisor") {
            close()
        }.apply { imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
        )
        header.addView(
            text("Supervisor", 20f, true).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL; ui.oneLine(this)
        },
            LinearLayout.LayoutParams(0, ui.dp(56), 1f)
        )
        mode = pill("Voice", 0x28ffffff, Color.WHITE) {
            hideKeyboard(); if (!model.accepted) privacy() else if (model.voicePage) model.endVoice() else model.showVoice()
        }.apply { contentDescription = "Supervisor voice or chat" }
        header.addView(mode, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginEnd = ui.dp(8) })
        ai = pill("AI", Color.WHITE, BLUE) { menu(ai) }.apply { contentDescription = "Supervisor options" }
        header.addView(ai, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        root.addView(header)
        notice = button("") {
            model.retry()
        }.apply {
            setTextColor(0xff9e3548.toInt()); textSize = 13f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; isSingleLine = false
        }; root.addView(notice)
        body = FrameLayout(ui.context); root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        buildChat(); buildVoice()
        return root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        model.bind(
            Mango9NavigationSession.resolve(
                arguments ?: Bundle().also {
            arguments = it
        },
            Mango9SessionStore(requireContext())
            )
        )
        model.changed.observe(viewLifecycleOwner) { render() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {
            close()
        } }
        )
    }

    private fun close() { hideKeyboard(); model.background(); findNavController().popBackStack() }

    private fun hideKeyboard() {
        view?.let {
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
        }; if (::composer.isInitialized) composer.clearFocus()
    }

    override fun onStop() { if (!requireActivity().isChangingConfigurations) model.background(); super.onStop() }

    override fun onStart() { super.onStart(); model.foreground(latest = followingLatest) }

    override fun onDestroyView() {
        layoutVersion++; historySearch?.cancel(); historyList = null
        spinner?.cancel(); spinner = null; privacyDialog?.dismiss(); toolsDialog?.dismiss(); privacyDialog = null; toolsDialog = null; toolsBody = null; super.onDestroyView()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("privacyPrompted", privacyPrompted); out.putBoolean("privacyOpen", privacyDialog?.isShowing == true); super.onSaveInstanceState(out)
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        render()
        if (privacyDialog?.isShowing == true) { privacyDialog?.dismiss(); privacy() }
    }

    private fun buildChat() {
        chat = ui.column(); body.addView(chat, FrameLayout.LayoutParams(-1, -1))
        messages = ui.column().apply { setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(14)) }
        chatScroll = ScrollView(ui.context).apply { isFillViewport = true; addView(messages) }
        val viewport = FrameLayout(ui.context)
        viewport.addView(chatScroll, FrameLayout.LayoutParams(-1, -1))
        latest = Mango9ChatNavigation.button(ui.context) {
            followingLatest = true; chatScroll.scrollTo(0, messages.height); updateLatest()
        }
        viewport.addView(
            latest,
            FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = ui.dp(12); bottomMargin = ui.dp(12)
        }
        )
        chat.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        chatScroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE) followingLatest = false
            false
        }
        chatScroll.setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int ->
            if (!aligningMessages) {
                if (!chatScroll.canScrollVertically(1)) followingLatest = true
                updateLatest()
            }
        }
        chatScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!aligningMessages && followingLatest) {
                chatScroll.post {
                if (view != null && followingLatest) { chatScroll.scrollTo(0, messages.height); updateLatest() }
            }
            }
        }
        pending = ui.column(); chat.addView(pending)
        bottom = ui.column().apply {
            setBackgroundColor(Color.WHITE); setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
        }; chat.addView(bottom)
        composer = EditText(ui.context).apply {
            hint = "Message Supervisor…"; contentDescription = "Message Supervisor"; textSize = 16f; setTextColor(INK); setHintTextColor(SECONDARY)
            background = ui.round(0xfff1f2f8.toInt(), 24); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); minHeight = ui.dp(48)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI; maxLines = 4
            doAfterTextChanged {
                model.draft = it.toString(); send.isEnabled = model.canSend(); send.alpha = if (send.isEnabled) 1f else 0.4f
            }
        }
        send = pill("↑", BLUE, Color.WHITE) {
            followingLatest = true; hideKeyboard(); model.send()
        }.apply { contentDescription = "Send to Supervisor"; textSize = 26f }
    }

    private fun updateLatest() { latest.isVisible = !aligningMessages && chatScroll.canScrollVertically(1) }

    private fun buildVoice() {
        voice = ui.column(); body.addView(voice, FrameLayout.LayoutParams(-1, -1))
        val content = ui.column().apply { gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)) }
        val scroll = ScrollView(ui.context).apply {
            isFillViewport = true; addView(content)
        }; voice.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        orb = SupervisorOrb(ui.context).apply {
            contentDescription = "Connect Supervisor voice"; isClickable = true; isFocusable = true
            setOnClickListener {
                if (model.voice.state == Mango9SupervisorVoice.State.CONNECTED) {
                    model.voice.setMuted(!model.voice.muted)
                } else if (!model.voice.running) {
                    if (!model.accepted) {
                        privacy()
                    } else if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        model.connectVoice()
                    } else {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
        content.addView(orb, LinearLayout.LayoutParams(-1, ui.dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 170 else 276)))
        status = text("Ready to connect", 20f, true).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(4))
        }; content.addView(status)
        hint = text("Tap the microphone to connect", 14f).apply {
            gravity = Gravity.CENTER; setTextColor(SECONDARY)
        }; content.addView(hint)
        val actions = ui.row().apply {
            gravity = Gravity.CENTER; setBackgroundColor(Color.WHITE); setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
        }
        mute = pill("Mute", TINT, BLUE) {
            model.voice.setMuted(!model.voice.muted)
        }.apply { contentDescription = "Mute microphone" }
        actions.addView(mute, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { marginEnd = ui.dp(12) })
        actions.addView(
            pill("■  End voice", 0xfffbece8.toInt(), 0xff9e3548.toInt()) {
            model.endVoice()
        }.apply { contentDescription = "End voice" },
            LinearLayout.LayoutParams(0, ui.dp(48), 1f)
        )
        voice.addView(actions)
    }

    private fun render() {
        if (view == null) return
        root.keepScreenOn = model.voice.running
        val voiceMode = model.voicePage
        chat.isVisible = !voiceMode; voice.isVisible = voiceMode
        mode.text = if (voiceMode) "Chat" else "Voice"; mode.isVisible = model.authorized
        mode.isEnabled = !model.accepted || voiceMode || model.canVoice()
        if ((model.loading || model.sending) && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            ai.text = "◌"
            if (spinner == null) {
                spinner = android.animation.ObjectAnimator.ofFloat(ai, View.ROTATION, 0f, 360f).apply {
                duration = 900; repeatCount = android.animation.ValueAnimator.INFINITE; start()
            }
            }
        } else { spinner?.cancel(); spinner = null; ai.rotation = 0f; ai.text = "AI" }
        notice.text = (if (voiceMode) model.voice.error else model.error).orEmpty()
        if (model.loading && notice.text.isEmpty()) notice.text = "Loading Supervisor…"
        notice.isVisible = notice.text.isNotEmpty()
        status.text = model.voice.status
        hint.text = if (!model.voice.running) "Tap the microphone to connect" else if (model.voice.muted) "You can still hear Supervisor" else "Tap Mute to pause microphone sharing"
        mute.text = if (model.voice.muted) "Unmute" else "Mute"; mute.isEnabled = model.voice.state == Mango9SupervisorVoice.State.CONNECTED; mute.alpha = if (mute.isEnabled) 1f else 0.45f
        orb.update(model.voice.running, model.voice.state == Mango9SupervisorVoice.State.CONNECTING, model.voice.muted, model.voice.level)
        orb.contentDescription = if (model.voice.running) "Mute or unmute Supervisor microphone" else "Connect Supervisor voice"
        val diameter = ui.dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 170 else 276)
        if (orb.layoutParams.height != diameter) { orb.layoutParams.height = diameter; orb.requestLayout() }
        if (renderedConversation != model.conversation) {
            renderedConversation = model.conversation; visibleMessageCount = 40; followingLatest = true; rendered = ""
        }
        val signature = model.messages.joinToString {
            it.toString()
        } + model.loading + model.moreMessages + model.configurationDetails.toString() + model.actionBusy + model.loadingOlder + visibleMessageCount + model.savedConfigurationIds
        if (signature != rendered) {
            rendered = signature
            val anchor = (0 until messages.childCount).map {
                messages.getChildAt(it)
            }.firstOrNull { it.bottom > chatScroll.scrollY && it.tag is String }
            val anchorId = anchor?.tag; val offset = (anchor?.top ?: 0) - chatScroll.scrollY
            val previousY = chatScroll.scrollY; val layout = ++layoutVersion
            aligningMessages = true
            messages.removeAllViews()
            if (model.moreMessages || visibleMessageCount < model.messages.size) {
                messages.addView(
                    button(if (model.loadingOlder) "Loading earlier messages…" else "Load earlier messages") {
                followingLatest = false
                if (visibleMessageCount < model.messages.size) { visibleMessageCount += 40; render() } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                    val previous = model.messages.size; model.loadOlder()
                    visibleMessageCount += (model.messages.size - previous).coerceAtLeast(0); render()
                }
                }
            }
                )
            }
            if (model.messages.isEmpty() && !model.loading && model.authorized) {
                messages.addView(
                    text("What can I help you with?", 20f, true).apply {
                gravity = Gravity.CENTER; setPadding(0, ui.dp(30), 0, 0)
            }
                )
            }
            model.messages.takeLast(visibleMessageCount).forEach { bubble(it) }
            messages.doOnNextLayout {
                chatScroll.post {
                    if (view == null || layout != layoutVersion) return@post
                    val restored = (0 until messages.childCount).map {
                        messages.getChildAt(it)
                    }.firstOrNull { it.tag == anchorId && anchorId != null }
                    chatScroll.scrollTo(0, if (followingLatest) messages.height else restored?.let { it.top - offset } ?: previousY)
                    aligningMessages = false; updateLatest()
                }
            }
        }
        val expected = if (model.accepted) "composer" else "consent"
        if (bottom.tag != expected) {
            bottom.tag = expected; bottom.removeAllViews()
            if (model.accepted) {
                val row = ui.row(); (composer.parent as? ViewGroup)?.removeView(composer); (send.parent as? ViewGroup)?.removeView(send)
                row.addView(
                    composer,
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = ui.dp(8)
                }
                ); row.addView(send, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))); bottom.addView(row)
                if (model.billing) {
                    bottom.addView(
                        text("Uses your Mango9 AI credit", 11f).apply {
                    setTextColor(SECONDARY); setPadding(ui.dp(8), ui.dp(4), 0, 0)
                }
                    )
                }
            } else {
                val row = ui.row(); row.addView(text("AI sharing is off", 14f), LinearLayout.LayoutParams(0, -2, 1f)); row.addView(
                    button("Review") {
                    privacy()
                }.apply { contentDescription = "Review AI privacy" }
                ); bottom.addView(row)
            }
        }
        if (composer.text.toString() != model.draft) { composer.setText(model.draft); composer.setSelection(composer.length()) }
        send.isEnabled = model.canSend(); send.alpha = if (send.isEnabled) 1f else 0.4f
        pending.removeAllViews(); pending.isVisible = model.pending != null && !model.sending
        if (pending.isVisible) {
            pending.addView(
                text("Request status is unconfirmed. It won’t be sent again automatically.", 12f).apply {
                setPadding(ui.dp(12), ui.dp(6), ui.dp(12), 0)
            }
            )
            val row = ui.row(); row.addView(
                button("Check status") {
                viewLifecycleOwner.lifecycleScope.launch { model.refresh() }
            },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            row.addView(
                button("Start new chat") {
                MaterialAlertDialogBuilder(ui.context).setMessage("This does not cancel work already received by the server. Check the saved request status first. Start a new chat without retrying?").setNegativeButton("Cancel", null).setPositiveButton("Start new chat") { _, _ -> model.leavePending() }.show()
            },
                LinearLayout.LayoutParams(0, -2, 1f)
            ); pending.addView(row)
        }
        renderTools()
        if (restorePrivacy || (model.authorized && !model.accepted && !privacyPrompted)) {
            restorePrivacy = false; root.post { if (view != null) privacy() }
        }
    }

    private fun bubble(row: JSONObject) {
        val outgoing = row.optString("role") == "user"
        val raw = Mango9SupervisorWire.fullLinks(Mango9SupervisorWire.messageText(row), model.lease?.session?.crmBaseUrl.orEmpty())
        val group = ui.column(); val message = text(raw, 15f).apply {
            background = ui.round(if (outgoing) TINT else Color.WHITE, 18); setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance(); setLinkTextColor(BLUE)
        }
        val display = SpannableStringBuilder(raw)
        Regex("https?://[^\\s<>]+").findAll(raw).forEach { match ->
            val clean = match.value.trimEnd('.', ',', ')', ']'); val url = Mango9SupervisorWire.webUrl(clean, model.lease?.session?.crmBaseUrl.orEmpty())
            if (url != null) {
                display.setSpan(
                    object : ClickableSpan() { override fun onClick(widget: View) {
                openLink(url)
            } },
                match.range.first, match.range.first + clean.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        message.text = display; group.addView(message)
        if (!outgoing) {
            val ids = row.optJSONObject("metadata")?.optJSONArray("actionIds")
            for (index in 0 until (ids?.length() ?: 0)) {
                val id = ids?.optString(index).orEmpty()
                if (runCatching { java.util.UUID.fromString(id) }.isSuccess) group.addView(configuration(id))
            }
            Mango9SupervisorPresentation.objects(row.optJSONObject("metadata")?.optJSONArray("creations")).forEach { proof ->
                val detail = JSONObject().put("state", "SUCCEEDED").put("recap", JSONObject().put("creation", proof))
                Mango9SupervisorPresentation.creation(detail, model.lease?.session?.crmBaseUrl.orEmpty())?.let {
                    group.addView(creation(it))
                }
            }
        }
        val footer = ui.row().apply { gravity = Gravity.END }
        val stamp = row.optJSONObject("metadata")?.optString("createdAt") ?: row.optString("createdAt")
        val time = runCatching {
            DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()).format(Instant.parse(stamp))
        }.getOrDefault("")
        footer.addView(text(time, 11f).apply { setTextColor(SECONDARY) })
        footer.addView(
            button("Copy") {
            (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Supervisor message", raw))
        }.apply { textSize = 11f; contentDescription = "Copy Supervisor message" }
        )
        group.addView(footer)
        val cell = FrameLayout(ui.context).apply { tag = row.optString("id") }
        cell.addView(
            group,
            FrameLayout.LayoutParams(-1, -2, if (outgoing) Gravity.END else Gravity.START).apply {
            if (outgoing) marginStart = ui.dp(28) else marginEnd = ui.dp(16)
        }
        )
        messages.addView(cell, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
    }

    private fun configuration(id: String): View {
        val box = ui.column().apply {
            background = ui.round(0xffeaf6ff.toInt(), 16); setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        }
        val detail = model.configurationDetails[id]
        val saved = id in model.savedConfigurationIds || detail?.optBoolean("summaryOnly") == true
        val recap = detail?.optJSONObject("recap")
        val fields = recap?.optJSONArray("fields")
        var readable = recap?.optInt("version") == 1 && fields != null
        var complete = readable
        box.addView(text(recap?.optString("title", "Settings") ?: "Settings", 16f, true))
        recap?.optString("effect")?.takeIf {
            it.isNotBlank()
        }?.let { box.addView(text(it, 13f).apply { setTextColor(SECONDARY) }) }
        val additional = ui.column().apply { visibility = View.GONE }
        for (index in 0 until (fields?.length() ?: 0)) {
            val field = fields?.optJSONObject(index)
            if (field == null || field.opt("label") !is String || field.opt("value") !is String) {
                readable = false; complete = false; continue
            }
            if (field.optBoolean("incomplete")) complete = false
            val line = ui.row().apply { gravity = Gravity.TOP; setPadding(0, ui.dp(5), 0, ui.dp(5)) }
            line.addView(
                text(field.optString("label"), 13f).apply {
                setTextColor(SECONDARY)
            },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            line.addView(
                text(field.optString("value"), 13f).apply {
                setTextIsSelectable(true)
            },
                LinearLayout.LayoutParams(0, -2, 2f)
            )
            if (index < 5) box.addView(line) else additional.addView(line)
        }
        if ((fields?.length() ?: 0) > 5) {
            val more =
                button("More settings") {
                    additional.visibility = if (additional.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            more.textSize = 12f; box.addView(more); box.addView(additional)
        }
        if (readable && detail != null) {
            if (!complete) box.addView(text("Some settings couldn’t load. Refresh before applying.", 12f))
            if (detail.optString("state") == "PENDING_APPROVAL") {
                box.addView(text(recap?.optString("question", "Does this look right?") ?: "Does this look right?", 13f))
                val controls = ui.row()
                controls.addView(
                    button("Change") {
                    model.draft = "I’d like to change these settings."; composer.setText(model.draft); composer.requestFocus(); model.decide("reject", detail)
                }.apply { isEnabled = !saved && model.authorized && !model.actionBusy && !model.sending }
                )
                controls.addView(
                    button("Apply") {
                    model.decide("approve", detail)
                }.apply { isEnabled = !saved && model.authorized && complete && !model.actionBusy && !model.sending && model.accepted }
                )
                box.addView(controls)
                if (saved) box.addView(text("Saved on this device. Refresh before applying.", 12f))
            } else {
                val created = Mango9SupervisorPresentation.creation(detail, model.lease?.session?.crmBaseUrl.orEmpty())
                if (created != null) {
                    box.addView(creation(created))
                } else {
                    box.addView(
                    text(
                        recap?.optString("statusText")?.takeIf {
                    it.isNotBlank()
                } ?: "Check the result before trying again.",
                    12f
                    )
                )
                }
            }
        } else {
            box.addView(text("Couldn’t load settings. You can refresh or keep chatting.", 13f))
        }
        if (saved || detail?.optString("state") != "PENDING_APPROVAL" || !complete) {
            box.addView(
            button("Refresh") {
            if (model.authorized) model.loadConfiguration(id, true) else model.retry()
        }.apply { isEnabled = !model.actionBusy; textSize = 12f }
        )
        }
        box.post { model.loadConfiguration(id) }
        return box
    }

    private fun creation(value: Mango9SupervisorPresentation.Creation): View = ui.column().apply {
        val owner = model.owner()
        background = ui.round(0xffeaf6ff.toInt(), 16); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        addView(text("✓ ${value.status}", 15f, true).apply { setTextColor(0xff237c3b.toInt()) })
        value.links.forEach { (label, url) ->
            addView(
                button(label) {
            if (owner != null && model.owner() == owner) {
                val task = Mango9SupervisorPresentation.nativeTaskId(url, owner.session.crmApiBaseUrl)
                if (task != null) {
                    findNavController().navigate(
                        R.id.action_global_mango9CalendarFragment,
                        Bundle().apply {
                        Mango9NavigationSession.bind(this, owner)
                        putString("presentation", "activities"); putInt("mango9_task_id", task)
                    }
                    )
                } else {
                    openLink(url)
                }
            }
        }.apply { gravity = Gravity.START }
            )
        }
        if (value.recipients.isNotEmpty()) {
            addView(text("Confirmation email", 12f, true))
            value.recipients.forEach { addView(text(it, 12f).apply { setTextIsSelectable(true) }) }
        }
    }

    private fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }

    private fun sheet(title: String): Pair<BottomSheetDialog, LinearLayout> {
        val dialog = BottomSheetDialog(ui.context); val layout = ui.column().apply {
            setBackgroundColor(Color.WHITE); setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(16))
        }
        val heading = ui.row(); heading.addView(text(title, 20f, true), LinearLayout.LayoutParams(0, -2, 1f)); heading.addView(
            button("×") {
            dialog.dismiss()
        }.apply { contentDescription = "Close $title" }
        ); layout.addView(heading)
        dialog.setContentView(layout); return dialog to layout
    }

    private fun privacy() {
        if (privacyDialog?.isShowing == true || view == null) return
        hideKeyboard(); privacyPrompted = true
        val (dialog, layout) = sheet(if (model.accepted) "AI sharing is on" else "Use Supervisor AI?"); privacyDialog = dialog
        val details = ui.column()
        for (value in listOf(Mango9SupervisorConsent.CHAT, Mango9SupervisorConsent.VOICE, "AI can make mistakes. Review replies and actions.")) details.addView(
            text(value, 15f).apply {
            setPadding(0, ui.dp(8), 0, ui.dp(8))
        }
        )
        details.addView(
            button("Privacy policy") {
            openLink("https://www.mango9.com/privacy")
        }.apply { gravity = Gravity.START }
        )
        details.addView(
            text("Turn sharing off from AI → AI privacy. Earlier requests may finish and saved history remains. For deletion requests: privacy@mango9.com.", 12f).apply {
            setTextColor(SECONDARY); setPadding(0, 0, 0, ui.dp(12))
        }
        )
        val available = resources.displayMetrics.heightPixels - ui.dp(210)
        layout.addView(
            ScrollView(ui.context).apply {
            addView(details)
        },
            LinearLayout.LayoutParams(-1, minOf(ui.dp(350), available.coerceAtLeast(ui.dp(100))))
        )
        val actions = ui.row()
        actions.addView(
            pill(if (model.accepted) "Done" else "Not now", TINT, BLUE) {
            dialog.dismiss()
        },
            LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { marginEnd = ui.dp(10) }
        )
        actions.addView(
            pill(if (model.accepted) "Turn off AI" else "Allow AI", if (model.accepted) 0xff9e3548.toInt() else BLUE, Color.WHITE) {
            model.consent(!model.accepted); dialog.dismiss()
        },
            LinearLayout.LayoutParams(0, ui.dp(48), 1f)
        )
        layout.addView(actions); dialog.show(); dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    private fun menu(anchor: View) {
        hideKeyboard(); val popup = PopupMenu(ui.context, anchor, Gravity.END)
        listOf("AI privacy", "History", "New chat").forEach { popup.menu.add(it) }
        popup.setOnMenuItemClickListener {
            when (it.title.toString()) {
                "AI privacy" -> privacy()
                "History" -> {
                    showTools("History"); viewLifecycleOwner.lifecycleScope.launch {
                        runCatching { model.loadConversations() }; renderTools()
                    }
                }
                "New chat" -> model.newConversation()
                else -> Unit
            }; true
        }; popup.show()
    }

    private fun showTools(title: String) {
        if (title != "History") return
        val (dialog, layout) = sheet(title); toolsDialog = dialog; toolsMode = title; toolsBody = ui.column()
        val search = EditText(ui.context).apply {
            hint = "Search conversation titles"; contentDescription = "Search conversation titles"; textSize = 15f; setSingleLine()
            setText(model.historyQuery)
            doAfterTextChanged {
                historySearch?.cancel()
                val query = it.toString()
                historySearch = viewLifecycleOwner.lifecycleScope.launch { delay(250); model.loadConversations(query = query) }
            }
        }
        layout.addView(search)
        val filters = ui.row()
        historyFilters.clear()
        listOf("all" to "All", "text" to "Text only", "voice" to "With voice").forEach { (key, label) ->
            filters.addView(
                button(label) {
                historySearch?.cancel()
                viewLifecycleOwner.lifecycleScope.launch { model.loadConversations(query = search.text.toString(), channel = key) }
            }.apply { contentDescription = "History filter: $label"; textSize = 13f; historyFilters[key] = this },
                LinearLayout.LayoutParams(0, ui.dp(44), 1f)
            )
        }
        layout.addView(filters); layout.addView(toolsBody)
        val list = RecyclerView(ui.context).apply {
            layoutManager = LinearLayoutManager(ui.context); itemAnimator = null; contentDescription = "Supervisor history"
        }
        list.adapter = object : RecyclerView.Adapter<HistoryHolder>() {
            override fun getItemCount() = historyRows.size

            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = HistoryHolder(ui.column())

            override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
                val row = historyRows[position]; val cell = holder.cell; cell.removeAllViews(); cell.setPadding(0, ui.dp(9), 0, ui.dp(9))
                val line = ui.row()
                line.addView(
                    text(row.optString("title").ifBlank { "Supervisor conversation" }, 15f, true).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                line.addView(
                    ui.icon(R.drawable.trash, "Delete conversation") {
                    confirmDelete(row)
                }.apply { isEnabled = model.canManageHistory() }
                )
                cell.addView(line)
                val metadata = ui.row(); val count = row.optInt("messageCount").coerceAtLeast(0)
                metadata.addView(
                    text((if (row.optBoolean("hasVoice")) "Voice · " else "Text · ") + "$count " + if (count == 1) "message" else "messages", 11f).apply {
                    setTextColor(SECONDARY)
                }
                )
                metadata.addView(
                    text(Mango9SupervisorPresentation.date(row.optString("updatedAt", row.optString("createdAt"))), 11f).apply {
                    setTextColor(SECONDARY); gravity = Gravity.END
                },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                cell.addView(metadata); cell.addView(ui.line())
                cell.setOnClickListener {
                    if (!model.loading && !model.sending && !model.actionBusy && model.pending == null) {
                        model.select(row.optString("id")); dialog.dismiss()
                    }
                }
                cell.setOnLongClickListener { confirmDelete(row); true }
            }
        }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) = if (model.canManageHistory()) super.getSwipeDirs(recyclerView, viewHolder) else 0

            override fun onMove(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val index = holder.bindingAdapterPosition
                historyRows.getOrNull(index)?.let(::confirmDelete)
                if (index >= 0) list.adapter?.notifyItemChanged(index)
            }
        }).attachToRecyclerView(list)
        historyList = list; historySignature = ""
        layout.addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * .50).toInt()))
        dialog.setOnDismissListener { historySearch?.cancel(); historyList = null; toolsBody = null }
        dialog.show(); renderTools()
    }

    private class HistoryHolder(val cell: LinearLayout) : RecyclerView.ViewHolder(cell) {
        init { cell.layoutParams = RecyclerView.LayoutParams(-1, -2) }
    }

    private fun confirmDelete(row: JSONObject) {
        if (!model.canManageHistory()) return
        MaterialAlertDialogBuilder(ui.context).setTitle("Delete this conversation?")
            .setMessage("This removes the saved chat. Tasks and changes already applied are not undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> model.deleteConversation(row.optString("id")) }.show()
    }

    private fun renderTools() {
        val content = toolsBody ?: return
        if (toolsDialog?.isShowing != true) return
        content.removeAllViews()
        if (toolsMode == "History") {
            historyFilters.forEach { (key, control) ->
                control.isSelected = model.historyChannel == key
                control.background = ui.round(if (control.isSelected) TINT else Color.TRANSPARENT, 18)
            }
            val signature = model.conversations.toString() + model.canManageHistory()
            if (signature != historySignature) {
                historySignature = signature; historyRows = model.conversations; historyList?.adapter?.notifyDataSetChanged()
            }
            if (model.loadingConversations) content.addView(text("Loading history…", 12f))
            model.historyError?.let {
                content.addView(
                    button(it + " Tap to refresh.") {
                    viewLifecycleOwner.lifecycleScope.launch { model.loadConversations() }
                }
                )
            }
            if (model.moreConversations) {
                content.addView(
                    button("Load more") {
                viewLifecycleOwner.lifecycleScope.launch { runCatching { model.loadConversations(true) }; renderTools() }
            }
                )
            }
            if (model.conversations.isEmpty() && !model.loadingConversations) content.addView(text(if (model.historyQuery.isEmpty() && model.historyChannel == "all") "No saved conversations yet." else "No matching conversations.", 13f))
        }
    }

    companion object {
        const val BLUE = 0xff4053c8.toInt(); const val TINT = 0xffeef1ff.toInt(); const val INK = 0xff22334d.toInt(); const val SECONDARY = 0xff6c7a87.toInt(); const val SURFACE = 0xfff9f9f9.toInt()

        fun open(fragment: androidx.fragment.app.Fragment) {
            val lease = Mango9SessionStore(fragment.requireContext()).captureSession() ?: return
            fragment.findNavController().navigate(
                R.id.action_global_mango9SupervisorFragment,
                Bundle().apply {
                Mango9NavigationSession.bind(this, lease)
            }
            )
        }
    }
}

/** A single centered microphone with a bounded oscillating ring; no floating control. */
private class SupervisorOrb(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var running = false; private var connecting = false; private var muted = false; private var level = 0.0

    fun update(active: Boolean, waiting: Boolean, silent: Boolean, meter: Double) {
        running = active; connecting = waiting; muted = silent; level = meter; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat(); val x = width / 2f; val y = height / 2f
        val phase = android.os.SystemClock.uptimeMillis() / 600.0
        val moving = running && android.animation.ValueAnimator.areAnimatorsEnabled()
        val pulse = if (moving) ((kotlin.math.sin(phase) + 1) * .012 + level * .04).toFloat() else 0f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = size * .004f
        for (scale in listOf(.42f, .445f, .47f)) {
            paint.color = Color.argb(if (scale == .42f) 70 else 22, 64, 83, 200); canvas.drawCircle(x, y, size * (scale + pulse), paint)
        }
        paint.strokeWidth = size * .009f; paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 40) {
            val angle = i * Math.PI / 20 + if (connecting && moving) phase / 2 else 0.0
            val a = size * .34f; val b = a + size * (.012f + pulse)
            paint.color = Color.argb(if (running) 100 else 45, 64, 83, 200)
            canvas.drawLine(x + kotlin.math.cos(angle).toFloat() * a, y + kotlin.math.sin(angle).toFloat() * a, x + kotlin.math.cos(angle).toFloat() * b, y + kotlin.math.sin(angle).toFloat() * b, paint)
        }
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawCircle(x, y, size * .18f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = size * .005f; paint.color = Mango9SupervisorFragment.BLUE; canvas.drawCircle(x, y, size * .18f, paint)
        paint.strokeWidth = size * .012f
        canvas.drawRoundRect(RectF(x - size * .018f, y - size * .055f, x + size * .018f, y + size * .02f), size * .02f, size * .02f, paint)
        canvas.drawArc(RectF(x - size * .036f, y - size * .018f, x + size * .036f, y + size * .045f), 0f, 180f, false, paint)
        canvas.drawLine(x, y + size * .045f, x, y + size * .068f, paint); canvas.drawLine(x - size * .025f, y + size * .068f, x + size * .025f, y + size * .068f, paint)
        if (muted && running) canvas.drawLine(x - size * .06f, y - size * .07f, x + size * .06f, y + size * .07f, paint)
        if (moving && isShown) postInvalidateOnAnimation()
    }
}
