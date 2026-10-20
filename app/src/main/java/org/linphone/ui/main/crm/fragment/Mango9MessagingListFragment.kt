/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import org.linphone.ui.main.crm.viewmodel.Mango9SocialViewModel
import androidx.annotation.UiThread
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.linphone.R
import org.linphone.databinding.Mango9MessagingListFragmentBinding
import org.linphone.mango9.Mango9ChatConnectionState
import org.linphone.mango9.Mango9ChatRoom
import org.linphone.mango9.Mango9ChatState
import org.linphone.mango9.Mango9NavigationSession
import org.linphone.mango9.Mango9SessionStore
import org.linphone.mango9.Mango9SessionLease
import org.linphone.ui.main.crm.adapter.Mango9MessagingListAdapter
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItem
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItems
import org.linphone.ui.main.crm.adapter.Mango9MessagingTab
import org.linphone.ui.main.crm.viewmodel.Mango9MessagingViewModel
import org.linphone.ui.main.fragment.GenericMainFragment

@UiThread
class Mango9MessagingListFragment : GenericMainFragment() {
    private lateinit var binding: Mango9MessagingListFragmentBinding
    private lateinit var viewModel: Mango9MessagingViewModel
    private lateinit var smsAdapter: Mango9MessagingListAdapter
    private lateinit var teamAdapter: Mango9MessagingListAdapter
    private lateinit var socialAdapter: Mango9MessagingListAdapter
    private lateinit var social: Mango9SocialViewModel
    private var settingFilters = false
    private var accountIds = emptyList<String>()
    private var mode = Mango9MessagingTab.Sms

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = Mango9MessagingListFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[Mango9MessagingViewModel::class.java]
        social = ViewModelProvider(this)[Mango9SocialViewModel::class.java]
        social.bind(Mango9SessionStore(requireContext()).captureSession())
        mode = when (savedInstanceState?.getString(ARG_INITIAL_TAB) ?: arguments?.getString(ARG_INITIAL_TAB)) {
            TYPE_TEAM -> Mango9MessagingTab.Team
            TYPE_SOCIAL -> Mango9MessagingTab.Social
            else -> Mango9MessagingTab.Sms
        }
        smsAdapter = Mango9MessagingListAdapter({ item -> openItem(Mango9MessagingTab.Sms, item) }, ::holdConversation)
        teamAdapter = Mango9MessagingListAdapter({ item -> openItem(Mango9MessagingTab.Team, item) }, ::holdConversation)
        socialAdapter = Mango9MessagingListAdapter({ item -> openItem(Mango9MessagingTab.Social, item) }, ::holdConversation, social::preview)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapterFor(mode)
        binding.back.setOnClickListener { goBack() }
        binding.retry.setOnClickListener {
            viewModel.clearError()
            if (mode == Mango9MessagingTab.Social) social.inbox() else viewModel.connect(force = true)
        }
        binding.swipeRefresh.setOnRefreshListener {
            if (mode == Mango9MessagingTab.Social) social.inbox() else viewModel.refresh()
        }
        binding.addGroup.setOnClickListener { showCreateGroupDialog() }
        binding.tabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) { R.id.sms_tab -> Mango9MessagingTab.Sms; R.id.social_tab -> Mango9MessagingTab.Social; else -> Mango9MessagingTab.Team }
            showAdapter(mode)
            render(viewModel.state.value ?: Mango9ChatState())
            if (mode == Mango9MessagingTab.Social) { renderSocial(); social.inbox() }
        }
        binding.tabs.check(when (mode) { Mango9MessagingTab.Sms -> R.id.sms_tab; Mango9MessagingTab.Team -> R.id.team_tab; Mango9MessagingTab.Social -> R.id.social_tab })
        viewModel.state.observe(viewLifecycleOwner, ::render)
        social.state.observe(viewLifecycleOwner) { renderSocial() }
        val platforms = listOf("", "facebook", "instagram", "tiktok")
        binding.platformFilter.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.mango9_all_platforms), "Facebook", "Instagram", "TikTok"))
        binding.platformFilter.setSelection(platforms.indexOf(social.current().platform).coerceAtLeast(0))

        fun selected(callback: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!settingFilters) callback(position)
            }
        }
        binding.platformFilter.onItemSelectedListener = selected { position ->
            if (social.current().platform != platforms[position]) social.filters(platforms[position], "")
        }
        binding.accountFilter.onItemSelectedListener = selected { position ->
            val account = accountIds.getOrNull(position).orEmpty()
            if (social.current().account != account) social.filters(social.current().platform, account)
        }
        binding.list.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (mode == Mango9MessagingTab.Social && dy > 0 && layout.findLastVisibleItemPosition() >= socialAdapter.itemCount - 5) social.inbox(more = true)
            }
        })
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { if (mode == Mango9MessagingTab.Social) social.inbox(); delay(15_000) }
        }
        }
        viewModel.openedRoomEvent.observe(viewLifecycleOwner) { event ->
            event.consume { route ->
                if (Mango9SessionStore(requireContext()).currentSession(route.session) != null) {
                    openRoom(route.target, route.session)
                }
            }
        }
        viewModel.connect()
    }

    override fun onResume() {
        super.onResume()
        social.bind(Mango9SessionStore(requireContext()).captureSession())
        render(viewModel.currentState())
        viewModel.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(ARG_INITIAL_TAB, when (mode) { Mango9MessagingTab.Sms -> TYPE_SMS; Mango9MessagingTab.Team -> TYPE_TEAM; Mango9MessagingTab.Social -> TYPE_SOCIAL })
        super.onSaveInstanceState(outState)
    }

    private fun render(state: Mango9ChatState) {
        val scope = Mango9SessionStore(requireContext()).captureSession()
        listOf(smsAdapter, teamAdapter, socialAdapter).forEach { it.notificationScope = scope }
        binding.socialFilters.visibility = if (mode == Mango9MessagingTab.Social) View.VISIBLE else View.GONE
        if (mode == Mango9MessagingTab.Social) { renderSocial(); return }
        val teamItems = teamItems(state)
        val smsItems = smsItems(state)
        submitRows(teamAdapter, teamItems)
        submitRows(smsAdapter, smsItems)
        showAdapter(mode)
        val items = if (mode == Mango9MessagingTab.Team) teamItems else smsItems
        val connecting = state.connection == Mango9ChatConnectionState.Connecting
        binding.loading.visibility = if (connecting && items.isEmpty()) View.VISIBLE else View.GONE
        binding.swipeRefresh.isRefreshing = false
        binding.addGroup.visibility = if (mode == Mango9MessagingTab.Team) View.VISIBLE else View.INVISIBLE
        binding.empty.text = getString(
            if (mode == Mango9MessagingTab.Team) R.string.mango9_chat_empty else R.string.mango9_sms_empty,
        )
        binding.empty.visibility = if (!connecting && items.isEmpty() && state.errorMessage.isNullOrBlank()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val fatalError = state.errorMessage?.takeIf { items.isEmpty() }
        binding.errorCard.visibility = if (fatalError == null) View.GONE else View.VISIBLE
        binding.errorMessage.text = fatalError.orEmpty()
        binding.connectionStatus.text = getString(
            when (state.connection) {
                Mango9ChatConnectionState.Connecting -> R.string.mango9_chat_connecting
                Mango9ChatConnectionState.Connected -> R.string.mango9_chat_connected
                Mango9ChatConnectionState.Disconnected -> R.string.mango9_chat_disconnected
            },
        )
    }

    private fun teamItems(state: Mango9ChatState): List<Mango9MessagingListItem> {
        return Mango9MessagingListItems.team(
            state,
            viewModel.moderation::isConversationDeleted,
            viewModel::roomTitle,
        )
    }

    private fun smsItems(state: Mango9ChatState): List<Mango9MessagingListItem> =
        Mango9MessagingListItems.sms(state, viewModel.moderation::isSmsMuted)

    private fun adapterFor(tab: Mango9MessagingTab): Mango9MessagingListAdapter =
        when (tab) { Mango9MessagingTab.Team -> teamAdapter; Mango9MessagingTab.Social -> socialAdapter; else -> smsAdapter }

    private fun renderSocial() {
        if (mode != Mango9MessagingTab.Social || !::socialAdapter.isInitialized) return
        val value = social.current()
        submitRows(
            socialAdapter,
            value.threads.map {
            Mango9MessagingListItem.Social(it, value.previews[it.id] ?: "Loading preview…", value.previewEpoch)
        }
        )
        showAdapter(mode)
        binding.addGroup.visibility = View.INVISIBLE
        binding.loading.visibility = if (value.loading && value.threads.isEmpty()) View.VISIBLE else View.GONE
        binding.swipeRefresh.isRefreshing = false
        binding.empty.setText(R.string.mango9_social_empty)
        binding.empty.visibility = if (!value.loading && value.threads.isEmpty() && value.error == null) View.VISIBLE else View.GONE
        binding.errorMessage.text = value.error.orEmpty()
        binding.errorCard.visibility = if (value.error == null) View.GONE else View.VISIBLE
        binding.connectionStatus.setText(R.string.mango9_social)
        val accounts = value.accounts.filter { value.platform.isBlank() || it.platform == value.platform }
        val ids = listOf("") + accounts.map { it.id }
        if (ids != accountIds || binding.accountFilter.adapter == null) {
            settingFilters = true; accountIds = ids
            binding.accountFilter.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.mango9_all_accounts)) + accounts.map {
                it.name
            }
            )
            binding.accountFilter.setSelection(ids.indexOf(value.account).coerceAtLeast(0)); settingFilters = false
        }
        val platformPosition = listOf("", "facebook", "instagram", "tiktok").indexOf(value.platform).coerceAtLeast(0)
        if (binding.platformFilter.selectedItemPosition != platformPosition) binding.platformFilter.setSelection(platformPosition)
        val accountPosition = ids.indexOf(value.account).coerceAtLeast(0)
        if (binding.accountFilter.selectedItemPosition != accountPosition) binding.accountFilter.setSelection(accountPosition)
    }

    private fun submitRows(adapter: Mango9MessagingListAdapter, rows: List<Mango9MessagingListItem>) {
        val owner = view ?: return
        val layout = binding.list.layoutManager as? LinearLayoutManager
        val keepLatestVisible = binding.list.adapter === adapter &&
            (layout?.findFirstVisibleItemPosition() ?: -1) <= 0 &&
            adapter.currentList.firstOrNull()?.stableId != rows.firstOrNull()?.stableId
        adapter.submitList(rows) {
            // RecyclerView otherwise anchors the old first row, hiding the newly promoted row above it.
            // Preserve the reader's position when they are browsing older conversations.
            if (view === owner && binding.list.adapter === adapter && keepLatestVisible &&
                binding.list.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
            ) {
                binding.list.scrollToPosition(0)
            }
        }
    }

    private fun showAdapter(tab: Mango9MessagingTab) {
        val selected = adapterFor(tab)
        // A full adapter replacement prevents recycled holders from retaining the other tab's
        // navigation callback.
        if (binding.list.adapter !== selected) binding.list.adapter = selected
    }

    private fun openItem(sourceTab: Mango9MessagingTab, item: Mango9MessagingListItem) {
        if (sourceTab != mode || !sourceTab.accepts(item)) {
            render(viewModel.currentState())
            return
        }
        when (item) {
            is Mango9MessagingListItem.Social -> navigateConversation(TYPE_SOCIAL, 0, item.thread.id, item.thread.title, null, social.activeLease())
            is Mango9MessagingListItem.Group -> openRoom(item.room)
            is Mango9MessagingListItem.User -> navigateConversation(
                type = TYPE_TEAM,
                userId = item.user.id,
                roomId = item.room?.id,
                target = item.user.name,
                phone = null,
            )
            is Mango9MessagingListItem.Sms -> navigateConversation(
                type = TYPE_SMS,
                userId = 0,
                roomId = null,
                target = item.party.phone,
                phone = item.party.phone,
            )
        }
    }

    private fun holdConversation(anchor: View, item: Mango9MessagingListItem) {
        showMango9ConversationMenu(anchor, item) {
            listOf(smsAdapter, teamAdapter, socialAdapter).forEach { it.notifyDataSetChanged() }
            if (mode == Mango9MessagingTab.Social) social.inbox() else viewModel.refresh()
        }
    }

    private fun openRoom(room: Mango9ChatRoom, session: Mango9SessionLease? = viewModel.navigationSession()) {
        navigateConversation(TYPE_TEAM, 0, room.id, viewModel.roomTitle(room), null, session)
    }

    private fun navigateConversation(
        type: String, userId: Int, roomId: String?, target: String, phone: String?,
        session: Mango9SessionLease? = viewModel.navigationSession(),
    ) {
        if (session == null || Mango9SessionStore(requireContext()).currentSession(session) == null) return
        val arguments = Bundle().apply {
            Mango9NavigationSession.bind(this, session)
            putString(
                org.linphone.mango9.Mango9ExternalShare.ARG_DELIVERY,
                sharedViewModel.externalShare.bind(
                sharedViewModel.externalShare.pendingRequestId(), org.linphone.mango9.Mango9ExternalShare.crmDestination(type, userId, roomId, phone)
                )
            )
            putString(ARG_TYPE, type)
            putInt(ARG_USER_ID, userId)
            putString(ARG_ROOM_ID, roomId)
            putString(ARG_TARGET, target)
            putString(ARG_PHONE, phone)
        }
        findNavController().navigate(
            R.id.action_mango9MessagingListFragment_to_mango9MessagingConversationFragment,
            arguments,
        )
    }

    private fun showCreateGroupDialog() {
        val session = viewModel.navigationSession() ?: return
        val users = viewModel.state.value?.users.orEmpty()
        if (users.size < 2) {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.mango9_chat_choose_two)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val selected = mutableSetOf<Int>()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mango9_chat_new_group_title)
            .setMultiChoiceItems(users.map { it.name }.toTypedArray(), null) { _, which, checked ->
                if (checked) selected.add(users[which].id) else selected.remove(users[which].id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mango9_chat_create, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selected.size < 2) return@setOnClickListener
                viewModel.createGroup(selected, session)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    companion object {
        const val ARG_TYPE = "mango9_message_type"
        const val ARG_USER_ID = "mango9_user_id"
        const val ARG_ROOM_ID = "mango9_room_id"
        const val ARG_TARGET = "mango9_target_name"
        const val ARG_PHONE = "mango9_sms_phone"
        const val ARG_INITIAL_TAB = "mango9_initial_tab"
        const val TYPE_TEAM = "team"
        const val TYPE_SMS = "sms"
        const val TYPE_SOCIAL = "social"
    }
}
