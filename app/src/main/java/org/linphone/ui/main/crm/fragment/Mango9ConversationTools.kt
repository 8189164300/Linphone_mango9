/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.R
import org.linphone.databinding.Mango9MessagingConversationFragmentBinding
import org.linphone.mango9.Mango9ChatMedia
import org.linphone.mango9.Mango9ConversationSearch
import org.linphone.ui.main.crm.adapter.Mango9ConversationAttachment
import org.linphone.ui.main.crm.adapter.Mango9ConversationAttachmentsView
import org.linphone.ui.main.crm.adapter.Mango9MessageAdapter
import org.linphone.ui.main.crm.adapter.Mango9MessageListItem
import org.linphone.ui.main.crm.adapter.Mango9ThumbnailLoader

/** Owned by one conversation view and login lease, never the selected account at callback time. */
internal class Mango9ConversationTools(
    private val binding: Mango9MessagingConversationFragmentBinding,
    private val adapter: Mango9MessageAdapter,
    private val scope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val onMedia: (Mango9ChatMedia) -> Unit,
    private val onSearchVisibility: (Boolean) -> Unit = {},
    private val thumbnails: Mango9ThumbnailLoader? = null,
) {
    private var disposed = false
    private var items = emptyList<Mango9MessageListItem>()
    private var matches = emptyList<String>()
    private var selected: String? = null
    private var query = ""
    private var searchJob: Job? = null
    private var searchRevision = 0L
    private var browserJob: Job? = null
    private var browserRevision = 0L
    private var browser: Mango9ConversationAttachmentsView? = null
    private var browserDialog: AlertDialog? = null
    private var documents = false
    var searchOpen = false
        private set
    private val watcher = binding.searchQuery.doAfterTextChanged {
        query = it?.toString()?.trim().orEmpty()
        selected = null
        search()
    }

    init {
        binding.searchPrevious.setOnClickListener { move(-1) }
        binding.searchNext.setOnClickListener { move(1) }
        binding.searchClose.setOnClickListener { closeSearch() }
        binding.searchQuery.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                if (selected == null) search() else move(-1)
                true
            } else {
                false
            }
        }
    }

    fun submit(snapshot: List<Mango9MessageListItem>) {
        if (!current()) return
        if (items == snapshot) return
        items = snapshot
        search()
        updateBrowser()
    }

    fun openSearch() {
        if (!current()) return
        searchOpen = true
        onSearchVisibility(true)
        binding.searchPanel.visibility = View.VISIBLE
        binding.searchQuery.requestFocus()
        (binding.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(binding.searchQuery, InputMethodManager.SHOW_IMPLICIT)
        search()
    }

    fun closeSearch() {
        searchOpen = false
        onSearchVisibility(false)
        searchJob?.cancel()
        ++searchRevision
        matches = emptyList()
        selected = null
        query = ""
        binding.searchCount.text = ""
        binding.searchPrevious.isEnabled = false
        binding.searchNext.isEnabled = false
        hideKeyboard()
        binding.searchQuery.text.clear()
        binding.searchQuery.clearFocus()
        binding.searchPanel.visibility = View.GONE
        adapter.highlight("", null)
    }

    private fun search() {
        searchJob?.cancel()
        val revision = ++searchRevision
        matches = emptyList()
        binding.searchPrevious.isEnabled = false
        binding.searchNext.isEnabled = false
        adapter.highlight("", null)
        if (!current() || !searchOpen || query.isEmpty()) {
            binding.searchCount.text = ""
            return
        }
        binding.searchCount.setText(R.string.mango9_chat_search_working)
        val capturedQuery = query
        val capturedItems = items
        searchJob = scope.launch {
            delay(150)
            val result = withContext(Dispatchers.Default) {
                Mango9ConversationSearch.matchingIds(capturedItems.map { it.stableId to it.text }, capturedQuery) { ensureActive() }
            }
            if (!current() || revision != searchRevision) return@launch
            matches = result
            selected = Mango9ConversationSearch.select(matches, selected)
            renderMatch(scroll = true)
        }
    }

    private fun move(step: Int) {
        if (!current()) return
        selected = Mango9ConversationSearch.select(matches, selected, step)
        renderMatch(scroll = true)
    }

    private fun renderMatch(scroll: Boolean) {
        binding.searchPrevious.isEnabled = matches.size > 1
        binding.searchNext.isEnabled = matches.size > 1
        binding.searchCount.text = if (matches.isEmpty()) {
            binding.root.context.getString(R.string.conversation_search_no_match_found)
        } else {
            binding.root.context.getString(R.string.mango9_chat_search_count, matches.indexOf(selected) + 1, matches.size)
        }
        adapter.highlight(query, selected)
        if (scroll) {
            val position = adapter.currentList.indexOfFirst { it.stableId == selected }
            if (position >= 0) (binding.messages.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
        }
    }

    fun openAttachments(documents: Boolean) {
        if (!current()) return
        closeBrowser()
        hideKeyboard()
        this.documents = documents
        val content = Mango9ConversationAttachmentsView(binding.root.context, documents, thumbnails) { attachment ->
            if (current() && items.any {
                it.stableId == attachment.messageId && Mango9ChatMedia.parse(it.files).getOrNull(attachment.index) == attachment.media
            }
            ) {
                onMedia(attachment.media)
            }
        }
        browser = content
        val height = (binding.root.resources.displayMetrics.heightPixels * 0.55f).toInt()
        content.recycler.layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, height)
        val dialog = MaterialAlertDialogBuilder(binding.root.context)
            .setTitle(if (documents) R.string.conversation_document_list_title else R.string.conversation_media_list_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        browserDialog = dialog
        dialog.setOnDismissListener { if (browserDialog === dialog) closeBrowser() }
        dialog.show()
        updateBrowser()
    }

    private fun updateBrowser() {
        val content = browser ?: return
        browserJob?.cancel()
        val revision = ++browserRevision
        val capturedItems = items
        val capturedDocuments = documents
        browserJob = scope.launch {
            val rows = withContext(Dispatchers.Default) {
                Mango9ConversationAttachment.collect(capturedItems, capturedDocuments) { ensureActive() }
            }
            if (current() && revision == browserRevision && browser === content) content.submit(rows)
        }
    }

    private fun closeBrowser() {
        ++browserRevision
        browserJob?.cancel()
        val dialog = browserDialog
        browserDialog = null
        browser?.dispose()
        browser = null
        dialog?.dismiss()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        binding.searchQuery.removeTextChangedListener(watcher)
        closeSearch()
        closeBrowser()
        items = emptyList()
    }

    private fun current(): Boolean = !disposed && isCurrent()

    private fun hideKeyboard() {
        (binding.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.searchQuery.windowToken, 0)
    }
}
