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
    private var mode = Mango9MessagingTab.Sms

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = Mango9MessagingListFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[Mango9MessagingViewModel::class.java]
        mode = if (arguments?.getString(ARG_INITIAL_TAB) == TYPE_TEAM) {
            Mango9MessagingTab.Team
        } else {
            Mango9MessagingTab.Sms
        }
        smsAdapter = Mango9MessagingListAdapter { item -> openItem(Mango9MessagingTab.Sms, item) }
        teamAdapter = Mango9MessagingListAdapter { item -> openItem(Mango9MessagingTab.Team, item) }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapterFor(mode)
        binding.back.setOnClickListener { goBack() }
        binding.retry.setOnClickListener {
            viewModel.clearError()
            viewModel.connect(force = true)
        }
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.addGroup.setOnClickListener { showCreateGroupDialog() }
        binding.tabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = if (checkedId == R.id.sms_tab) Mango9MessagingTab.Sms else Mango9MessagingTab.Team
            showAdapter(mode)
            render(viewModel.state.value ?: Mango9ChatState())
        }
        binding.tabs.check(if (mode == Mango9MessagingTab.Sms) R.id.sms_tab else R.id.team_tab)
        viewModel.state.observe(viewLifecycleOwner, ::render)
        viewModel.openedRoomEvent.observe(viewLifecycleOwner) { event ->
            event.consume(::openRoom)
        }
        viewModel.connect()
    }

    override fun onResume() {
        super.onResume()
        render(viewModel.currentState())
        viewModel.refresh()
    }

    private fun render(state: Mango9ChatState) {
        val teamItems = teamItems(state)
        val smsItems = smsItems(state)
        teamAdapter.submitList(teamItems)
        smsAdapter.submitList(smsItems)
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
        if (tab == Mango9MessagingTab.Team) teamAdapter else smsAdapter

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

    private fun openRoom(room: Mango9ChatRoom) {
        navigateConversation(TYPE_TEAM, 0, room.id, viewModel.roomTitle(room), null)
    }

    private fun navigateConversation(type: String, userId: Int, roomId: String?, target: String, phone: String?) {
        val arguments = Bundle().apply {
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
                viewModel.createGroup(selected)
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
    }
}
