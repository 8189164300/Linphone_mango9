// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.*
import java.util.UUID

internal class Mango9TemplatesViewModel : ViewModel() {
    enum class Mode { LIST, DETAIL, EDITOR }
    private val sessions = Mango9SessionStore(coreContext.context)
    private val api = Mango9TemplateApi(coreContext.context)
    val changed = MutableLiveData(0)
    var lease: Mango9SessionLease? = null; private set
    var mode = Mode.LIST
    var query = ""
    var title = ""
    var text = ""
    var busy = false
    var loaded = false
    var error: String? = null
    var rows = listOf<JSONObject>()
    var selected: JSONObject? = null
    var editing: JSONObject? = null
    var metadata = JSONObject()
    var pending: JSONObject? = null
    var page = 0
    var total = 0
    private var job: Job? = null
    private var generation = 0
    fun owner() = lease?.takeIf { sessions.currentSession(it) != null }
    fun bind(value: Mango9SessionLease?) {
        if (lease != null) return
        lease = value
        if (value == null) { error = "Reopen Templates from your current chat."; notifyChange() } else load()
    }
    fun notifyChange() { changed.value = (changed.value ?: 0) + 1 }
    fun canCreate() = owner() != null && metadata.optJSONObject("permissions")?.optBoolean("can_create") == true
    fun titleLimit() = metadata.optJSONObject("limits")?.optJSONObject("title")?.optInt("max_utf8_bytes", 255) ?: 255
    fun textLimit() = metadata.optJSONObject("limits")?.optJSONObject("content")?.optInt("max_utf8_bytes", 10000) ?: 10000
    fun valid() = Mango9TemplateText.valid(title, text, titleLimit(), textLimit())
    fun failure(e: Exception) = if (owner() == null) "Your account changed. Close Templates and reopen it from the current chat." else e.message.takeIf { e is Mango9TemplateFailure } ?: "Couldn’t load or save templates. Check your connection and try again."
    fun load(more: Boolean = false, debounce: Boolean = false) {
        val owner = owner() ?: return
        job?.cancel(); generation++; val stamp = generation; val next = if (more) page + 1 else 1
        if (!more) { rows = emptyList(); loaded = false }
        busy = true; error = null; notifyChange()
        job = viewModelScope.launch {
            try {
                if (debounce) delay(300)
                val result = api.request(owner, query = mapOf("page" to next.toString(), "search" to query))
                if (stamp != generation) return@launch
                metadata = result; val items = result.optJSONArray("templates")?.liveObjects().orEmpty()
                rows = if (more) rows + items else items; page = next; total = result.optJSONObject("pagination")?.optInt("total") ?: rows.size; loaded = true
            } catch (e: Exception) { if (e is CancellationException) throw e; if (stamp == generation) error = failure(e) }
            finally { if (stamp == generation) { busy = false; notifyChange() } }
        }
    }
    fun select(row: JSONObject) {
        val owner = owner() ?: return
        mode = Mode.DETAIL; selected = null; busy = true; error = null; notifyChange()
        viewModelScope.launch {
            try { val result = api.request(owner, row.getLong("id").toString()); metadata = result; selected = result.getJSONObject("template") }
            catch (e: Exception) { if (e is CancellationException) throw e; error = failure(e) }
            finally { busy = false; notifyChange() }
        }
    }
    fun edit(row: JSONObject? = null) {
        editing = row; title = row?.liveText("title").orEmpty(); text = row?.liveText("content").orEmpty()
        pending = null; error = null; mode = Mode.EDITOR; notifyChange()
    }
    fun save() {
        val owner = owner() ?: return
        if (busy || !valid()) return
        val body = pending ?: JSONObject().put("requestId", UUID.randomUUID().toString()).put("values", JSONObject().put("title", title).put("content", text)).also {
            if (editing != null) it.put("revision", editing!!.getString("revision"))
        }
        pending = body; busy = true; error = null; notifyChange()
        viewModelScope.launch {
            try {
                val result = api.request(owner, editing?.getLong("id")?.toString().orEmpty(), if (editing == null) "POST" else "PATCH", body = body)
                metadata = result; selected = result.getJSONObject("template"); pending = null; mode = Mode.DETAIL; loaded = false
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = failure(e)
                if (e is Mango9TemplateFailure && e.status in 400..499) pending = null
            } finally { busy = false; notifyChange() }
        }
    }
    fun back() { error = null; mode = if (mode == Mode.EDITOR && editing != null) Mode.DETAIL else Mode.LIST; notifyChange(); if (mode == Mode.LIST && !loaded) load() }
}
