/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.chat.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.widget.ArrayAdapter
import android.widget.AdapterView
import org.linphone.ui.main.crm.viewmodel.Mango9SocialViewModel
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.contacts.getListOfSipAddressesAndPhoneNumbers
import org.linphone.core.tools.Log
import org.linphone.databinding.ChatListFragmentBinding
import org.linphone.mango9.Mango9ChatModerationStore
import org.linphone.mango9.Mango9ChatConnectionState
import org.linphone.mango9.Mango9ChatState
import org.linphone.mango9.Mango9ChatStore
import org.linphone.mango9.Mango9ChatRouting
import org.linphone.mango9.Mango9SessionStore
import org.linphone.mango9.Mango9ExternalShare
import org.linphone.mango9.Mango9NativeConversationRoute
import org.linphone.mango9.Mango9NavigationSession
import org.linphone.ui.fileviewer.FileViewerActivity
import org.linphone.ui.fileviewer.MediaViewerActivity
import org.linphone.ui.main.MainActivity.Companion.ARGUMENTS_CONVERSATION_ID
import org.linphone.ui.main.chat.adapter.ConversationsListAdapter
import org.linphone.ui.main.chat.model.ConversationModel
import org.linphone.ui.main.chat.viewmodel.ConversationsListViewModel
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressClickListener
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressModel
import org.linphone.ui.main.contacts.model.NumberOrAddressPickerDialogModel
import org.linphone.ui.main.crm.adapter.Mango9MessagingListAdapter
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItem
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItems
import org.linphone.ui.main.crm.adapter.Mango9MessagingTab
import org.linphone.ui.main.crm.fragment.Mango9MessagingListFragment
import org.linphone.ui.main.crm.fragment.showMango9ConversationMenu
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.utils.ConfirmationDialogModel
import org.linphone.utils.DialogUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.RecyclerViewHeaderDecoration

@UiThread
class ConversationsListFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[Conversations List Fragment]"
        private const val SMS_TAB_POSITION = 0
        private const val TEAM_TAB_POSITION = 1
    }

    private lateinit var binding: ChatListFragmentBinding

    private lateinit var listViewModel: ConversationsListViewModel

    private lateinit var adapter: ConversationsListAdapter

    private lateinit var mango9SmsMessagesAdapter: Mango9MessagingListAdapter

    private lateinit var mango9TeamMessagesAdapter: Mango9MessagingListAdapter
    private lateinit var socialAdapter: Mango9MessagingListAdapter
    private lateinit var social: Mango9SocialViewModel
    private var socialAccountIds = emptyList<String>()
    private var changingSocialFilters = false

    private var mango9Tab = Mango9MessagingTab.Sms
    private lateinit var liveInbox: org.linphone.ui.main.crm.fragment.Mango9LiveInbox
    private var rebuildingTabs = false
    private var normalizedMango9Inbox = false
    private fun normalizeMango9Inbox() {
        if (normalizedMango9Inbox || mango9Sessions.load() == null) return
        normalizedMango9Inbox = true
        val pane = binding.slidingPaneLayout
        pane.getChildAt(0).layoutParams = pane.getChildAt(0).layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        // Mango9 detail destinations live in the main navigator, so the legacy SIP detail pane is unused.
        pane.getChildAt(1).visibility = View.GONE
        val ui = org.linphone.ui.main.crm.fragment.Mango9LiveViews(requireContext())
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            val parent = binding.mango9MessagesPanel.parent as androidx.constraintlayout.widget.ConstraintLayout
            for (i in 0 until parent.childCount) parent.getChildAt(i).let { if (it.id == View.NO_ID) it.id = View.generateViewId() }
            androidx.constraintlayout.widget.ConstraintSet().apply {
                clone(parent)
                clear(R.id.bottom_nav_bar, androidx.constraintlayout.widget.ConstraintSet.TOP)
                constrainWidth(R.id.bottom_nav_bar, 0); constrainHeight(R.id.bottom_nav_bar, ui.dp(48))
                connect(R.id.bottom_nav_bar, androidx.constraintlayout.widget.ConstraintSet.START, 0, androidx.constraintlayout.widget.ConstraintSet.START)
                connect(R.id.bottom_nav_bar, androidx.constraintlayout.widget.ConstraintSet.END, 0, androidx.constraintlayout.widget.ConstraintSet.END)
                connect(R.id.bottom_nav_bar, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, 0, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
                connect(R.id.mango9_messages_panel, androidx.constraintlayout.widget.ConstraintSet.START, 0, androidx.constraintlayout.widget.ConstraintSet.START)
                connect(R.id.mango9_messages_panel, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, R.id.bottom_nav_bar, androidx.constraintlayout.widget.ConstraintSet.TOP)
                connect(R.id.top_bar, androidx.constraintlayout.widget.ConstraintSet.START, 0, androidx.constraintlayout.widget.ConstraintSet.START)
                constrainHeight(R.id.top_bar, ui.dp(48))
                connect(R.id.new_conversation, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, R.id.bottom_nav_bar, androidx.constraintlayout.widget.ConstraintSet.TOP, ui.dp(12))
                applyTo(parent)
            }
            val bar = binding.bottomNavBar.root as androidx.constraintlayout.widget.ConstraintLayout
            val crm = binding.bottomNavBar.crm ?: ui.text(getString(R.string.mango9_crm_title), 12f).apply {
                id = R.id.crm; gravity = android.view.Gravity.CENTER
                setOnClickListener { findNavController().navigate(R.id.action_global_mango9CrmFragment) }; bar.addView(this)
            }
            val controls = listOf(binding.bottomNavBar.contacts, crm, binding.bottomNavBar.calls, binding.bottomNavBar.conversations)
            for (i in 0 until bar.childCount) bar.getChildAt(i).let { if (it.id == View.NO_ID) it.id = View.generateViewId() }
            androidx.constraintlayout.widget.ConstraintSet().apply {
                clone(bar)
                controls.forEach { control ->
                    clear(control.id); constrainWidth(control.id, 0); constrainHeight(control.id, ui.dp(48))
                    connect(control.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 0, androidx.constraintlayout.widget.ConstraintSet.TOP)
                    connect(control.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, 0, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
                    val icon = control.compoundDrawables[1] ?: ContextCompat.getDrawable(requireContext(), R.drawable.users)
                    icon?.setBounds(0, 0, ui.dp(18), ui.dp(18)); control.setCompoundDrawables(null, icon, null, null)
                    control.compoundDrawablePadding = ui.dp(2); control.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)); control.maxLines = 1; control.textSize = 12f
                }
                createHorizontalChain(0, androidx.constraintlayout.widget.ConstraintSet.LEFT, 0, androidx.constraintlayout.widget.ConstraintSet.RIGHT, controls.map { it.id }.toIntArray(), null, androidx.constraintlayout.widget.ConstraintSet.CHAIN_SPREAD)
                applyTo(bar)
            }
        }
    }
    private fun rebuildMango9Tabs() {
        val preferences = org.linphone.mango9.Mango9ChatTabPreferences(requireContext())
        val visible = Mango9MessagingTab.entries.filter { it == Mango9MessagingTab.Sms || preferences.visible(it.name.lowercase()) }
        if (mango9Tab !in visible) mango9Tab = Mango9MessagingTab.Sms
        val tabs = binding.mango9MessagesTabs
        if ((0 until tabs.tabCount).map { tabs.getTabAt(it)?.tag } != visible) {
            rebuildingTabs = true; tabs.removeAllTabs()
            visible.forEach { tab ->
                val title = when (tab) { Mango9MessagingTab.Sms -> "SMS"; Mango9MessagingTab.Team -> "Team Chat"; Mango9MessagingTab.Social -> "Social"; Mango9MessagingTab.Live -> "Live Chat" }
                val icon = when (tab) { Mango9MessagingTab.Sms -> R.drawable.chat_teardrop_text; Mango9MessagingTab.Team -> R.drawable.users; Mango9MessagingTab.Social -> R.drawable.chat_teardrop_dots; Mango9MessagingTab.Live -> R.drawable.globe_hemisphere_west }
                val ui = org.linphone.ui.main.crm.fragment.Mango9LiveViews(requireContext())
                val colors = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(ui.accent, ui.secondary))
                val label = ui.text(title, 12f, true).apply {
                    id = android.R.id.text1; maxLines = 1; isDuplicateParentStateEnabled = true
                    setTextColor(colors)
                }
                val image = android.widget.ImageView(requireContext()).apply {
                    isDuplicateParentStateEnabled = true; setImageResource(icon); imageTintList = colors
                }
                val content = ui.row().apply {
                    gravity = android.view.Gravity.CENTER; isDuplicateParentStateEnabled = true
                    background = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_selected), ui.round(ui.tinted, 8)); addState(intArrayOf(), ui.round(android.graphics.Color.TRANSPARENT, 8))
                    }
                    setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8))
                    addView(image, android.widget.LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)).apply { marginEnd = ui.dp(4) })
                    addView(label)
                }
                tabs.addTab(tabs.newTab().setText(title).setContentDescription(title).setCustomView(content).setTag(tab), tab == mango9Tab)
            }
            tabs.isInlineLabel = true; rebuildingTabs = false
        }
        tabs.selectTab(tabs.getTabAt(visible.indexOf(mango9Tab)))
    }

    private var bottomSheetDialog: BottomSheetDialogFragment? = null

    private val mango9ChatStore by lazy { Mango9ChatStore.get(requireContext()) }
    private val mango9Sessions by lazy { Mango9SessionStore(requireContext()) }
    private val mango9Moderation by lazy { Mango9ChatModerationStore(requireContext()) }

    private fun numberOrAddressClickListener(request: String?) = object : ContactNumberOrAddressClickListener {
        @UiThread
        override fun onClicked(model: ContactNumberOrAddressModel) {
            coreContext.postOnCoreThread {
                val address = model.address
                if (address != null) {
                    Log.i("$TAG Creating 1-1 conversation with to [${address.asStringUriOnly()}]")
                    listViewModel.createOneToOneChatRoomWith(address, request)
                }
            }
        }

        override fun onLongPress(model: ContactNumberOrAddressModel) { }
    }

    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            Log.i("$TAG [$itemCount] added, scrolling to top")
            binding.conversationsList.scrollToPosition(0)
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            Log.i("$TAG [$itemCount] moved, scrolling to top")
            binding.conversationsList.scrollToPosition(0)
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { }
    }

    override fun onDefaultAccountChanged() {
        Log.i(
            "$TAG Default account changed, updating avatar in top bar & re-computing conversations"
        )
        listViewModel.filter()
        mango9ChatStore.disconnect()
        viewLifecycleOwner.lifecycleScope.launch { mango9ChatStore.connect() }
    }

    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        if (
            findNavController().currentDestination?.id == R.id.startConversationFragment ||
            findNavController().currentDestination?.id == R.id.meetingWaitingRoomFragment
        ) {
            // Holds fragment in place while new fragment slides over it
            return AnimationUtils.loadAnimation(activity, R.anim.hold)
        }
        return super.onCreateAnimation(transit, enter, nextAnim)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mango9Tab = savedInstanceState?.getString("mango9_message_tab")
            ?.let { name -> Mango9MessagingTab.entries.firstOrNull { it.name == name } }
            ?: Mango9MessagingTab.Sms

        adapter = ConversationsListAdapter()
        mango9SmsMessagesAdapter = Mango9MessagingListAdapter(onClick = { item ->
            openMango9Item(Mango9MessagingTab.Sms, item)
        }, onHold = ::holdMango9Conversation)
        mango9TeamMessagesAdapter = Mango9MessagingListAdapter(onClick = { item ->
            openMango9Item(Mango9MessagingTab.Team, item)
        }, onHold = ::holdMango9Conversation)
        socialAdapter = Mango9MessagingListAdapter(onClick = {
            openMango9Item(Mango9MessagingTab.Social, it)
        }, onHold = ::holdMango9Conversation, onSocialVisible = { social.preview(it) })
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        normalizedMango9Inbox = false
        binding = ChatListFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("mango9_message_tab", mango9Tab.name)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listViewModel = ViewModelProvider(this)[ConversationsListViewModel::class.java]
        social = ViewModelProvider(this)[Mango9SocialViewModel::class.java]
        social.bind(mango9Sessions.captureSession())
        adapter.onNearEnd = listViewModel::revealMoreContacts

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = listViewModel
        observeToastEvents(listViewModel)

        binding.conversationsList.setHasFixedSize(true)
        binding.conversationsList.layoutManager = LinearLayoutManager(requireContext())
        // Attach even when there are no rooms. Preserve scroll restoration until rows arrive.
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.conversationsList.adapter = adapter
        binding.conversationsList.outlineProvider = outlineProvider
        binding.conversationsList.clipToOutline = true

        binding.mango9MessagesList.layoutManager = LinearLayoutManager(requireContext())
        binding.mango9MessagesList.adapter = mango9SmsMessagesAdapter
        liveInbox = org.linphone.ui.main.crm.fragment.Mango9LiveInbox(this,
            ViewModelProvider(this)[org.linphone.ui.main.crm.viewmodel.Mango9LiveChatViewModel::class.java]) { item ->
            org.linphone.ui.main.crm.fragment.Mango9LiveChatFragment.open(this, item.id)
        }
        binding.mango9LiveHost.addView(liveInbox.view, android.widget.FrameLayout.LayoutParams(-1, -1))
        rebuildMango9Tabs()
        binding.mango9MessagesTabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (rebuildingTabs) return
                    mango9Tab = tab.tag as? Mango9MessagingTab ?: return
                    showMango9MessagesAdapter(mango9Tab)
                    renderMango9Messages(mango9ChatStore.state.value)
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (mango9Tab == Mango9MessagingTab.Social) {
                            social.inbox()
                        } else if (mango9Tab == Mango9MessagingTab.Sms) {
                            mango9ChatStore.refreshSmsDirectory()
                        } else if (mango9Tab == Mango9MessagingTab.Team) {
                            mango9ChatStore.refreshDirectory()
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )
        val platforms = listOf("", "facebook", "instagram", "tiktok")
        binding.socialPlatform.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.mango9_all_platforms), "Facebook", "Instagram", "TikTok"))
        binding.socialPlatform.setSelection(platforms.indexOf(social.current().platform).coerceAtLeast(0))

        fun selected(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!changingSocialFilters) block(position)
            }
        }
        binding.socialPlatform.onItemSelectedListener = selected {
            if (social.current().platform != platforms[it]) social.filters(platforms[it], "")
        }
        binding.socialAccount.onItemSelectedListener = selected { position ->
            val id = socialAccountIds.getOrNull(position).orEmpty()
            if (social.current().account != id) social.filters(social.current().platform, id)
        }
        social.state.observe(viewLifecycleOwner) { renderSocial() }
        binding.mango9MessagesList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (mango9Tab == Mango9MessagingTab.Social && dy > 0 && layout.findLastVisibleItemPosition() >= socialAdapter.itemCount - 5) social.inbox(more = true)
            }
        })
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    social.bind(mango9Sessions.captureSession())
                    if (mango9Tab == Mango9MessagingTab.Social) social.inbox()
                    delay(15_000)
                }
            }
        }

        val headerItemDecoration = RecyclerViewHeaderDecoration(requireContext(), adapter)
        binding.conversationsList.addItemDecoration(headerItemDecoration)

        adapter.conversationLongClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                val modalBottomSheet = ConversationDialogFragment(
                    model.isMuted.value == true,
                    model.isGroup,
                    model.isReadOnly.value == true,
                    (model.unreadMessageCount.value ?: 0) > 0,
                    { // onDismiss
                        adapter.resetSelection()
                    },
                    { // onMarkConversationAsRead
                        Log.i("$TAG Marking conversation [${model.id}] as read")
                        model.markAsRead()
                    },
                    { // onToggleMute
                        Log.i("$TAG Changing mute status of conversation [${model.id}]")
                        model.toggleMute()
                    },
                    { // onCall
                        Log.i("$TAG Calling conversation [${model.id}]")
                        model.call()
                    },
                    { // onDeleteConversation
                        showDeleteConfirmationDialog(model)
                    },
                    { // onLeaveGroup
                        showLeaveConfirmationDialog(model)
                    }
                )
                modalBottomSheet.show(parentFragmentManager, ConversationDialogFragment.TAG)
                bottomSheetDialog = modalBottomSheet
            }
        }

        adapter.conversationClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                Log.i("$TAG Show conversation with ID [${model.id}]")
                sharedViewModel.displayedChatRoom = model.chatRoom
                openNativeShare(Mango9NativeConversationRoute(model.id, sharedViewModel.externalShare.pendingRequestId()))
            }
        }

        adapter.createConversationWithFriendClickedEvent.observe(viewLifecycleOwner) {
            it.consume { friend ->
                val request = sharedViewModel.externalShare.pendingRequestId()
                val numberListener = numberOrAddressClickListener(request)
                coreContext.postOnCoreThread {
                    coreContext.contactsManager.mango9ChatTarget(friend)?.let { target ->
                        Mango9ChatRouting.open(target, request)
                        return@postOnCoreThread
                    }
                    val singleAvailableAddress = LinphoneUtils.getSingleAvailableAddressForFriend(friend)
                    if (singleAvailableAddress != null) {
                        Log.i(
                            "$TAG Only 1 SIP address or phone number found for contact [${friend.name}], using it"
                        )
                        listViewModel.createOneToOneChatRoomWith(singleAvailableAddress, request)
                    } else {
                        val list = friend.getListOfSipAddressesAndPhoneNumbers(numberListener)
                        Log.i(
                            "$TAG [${list.size}] numbers or addresses found for contact [${friend.name}], showing selection dialog"
                        )
                        coreContext.postOnMainThread {
                            showNumbersOrAddressesDialog(list)
                        }
                    }
                }
            }
        }

        adapter.createConversationWithAddressClickedEvent.observe(viewLifecycleOwner) {
            it.consume { address ->
                val request = sharedViewModel.externalShare.pendingRequestId()
                Log.i("$TAG Creating 1-1 conversation with to [${address.asStringUriOnly()}]")
                coreContext.postOnCoreThread {
                    listViewModel.createOneToOneChatRoomWith(address, request)
                }
            }
        }

        binding.setOnNewConversationClicked {
            if (findNavController().currentDestination?.id == R.id.conversationsListFragment) {
                // The social API replies to existing provider threads; it cannot start an
                // arbitrary new DM. Never route this button to a duplicate inbox or to SMS.
                if (mango9Sessions.captureSession() != null && mango9Tab == Mango9MessagingTab.Social) return@setOnNewConversationClicked
                if (mango9Sessions.captureSession() != null && mango9Tab != Mango9MessagingTab.Sms) {
                    // Team creation must never fall through to the phone/SMS recipient picker.
                    findNavController().navigate(
                        R.id.action_global_mango9MessagingListFragment,
                        Bundle().apply {
                        putString(Mango9MessagingListFragment.ARG_INITIAL_TAB, if (mango9Tab == Mango9MessagingTab.Team) Mango9MessagingListFragment.TYPE_TEAM else Mango9MessagingListFragment.TYPE_SOCIAL)
                    }
                    )
                    return@setOnNewConversationClicked
                }
                Log.i("$TAG Navigating to start conversation fragment")
                val action =
                    ConversationsListFragmentDirections.actionConversationsListFragmentToStartConversationFragment()
                val arguments = action.arguments.apply {
                    putString(Mango9ExternalShare.ARG_REQUEST, sharedViewModel.externalShare.pendingRequestId())
                }
                findNavController().navigate(action.actionId, arguments)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mango9ChatStore.state.collect(::renderMango9Messages)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch { mango9ChatStore.connect() }

        listViewModel.conversations.observe(viewLifecycleOwner) {
            adapter.submitList(it)

            // Wait for adapter to have items before setting it in the RecyclerView,
            // otherwise scroll position isn't retained
            if (binding.conversationsList.adapter != adapter) {
                binding.conversationsList.adapter = adapter
            }

            Log.i("$TAG Conversations list ready with [${it.size}] items")
            listViewModel.fetchInProgress.value = false
        }

        listViewModel.chatRoomCreatedEvent.observe(viewLifecycleOwner) {
            it.consume(::openNativeShare)
        }
        sharedViewModel.showSharedConversationEvent.observe(viewLifecycleOwner) {
            it.consume(::openNativeShare)
        }

        sharedViewModel.showConversationEvent.observe(viewLifecycleOwner) {
            it.consume { conversationId ->
                Log.i("$TAG Navigating to conversation fragment with ID [$conversationId]")
                val action = ConversationFragmentDirections.actionGlobalConversationFragment(conversationId)
                binding.chatNavContainer.findNavController().navigate(action)
            }
        }

        sharedViewModel.goToMeetingWaitingRoomEvent.observe(viewLifecycleOwner) {
            it.consume { uri ->
                if (findNavController().currentDestination?.id == R.id.conversationsListFragment) {
                    Log.i("$TAG Navigating to meeting waiting room fragment with URI [$uri]")
                    val action =
                        ConversationsListFragmentDirections.actionConversationsListFragmentToMeetingWaitingRoomFragment(
                            uri
                        )
                    findNavController().navigate(action)
                } else {
                    Log.e("$TAG Failed to navigate to meeting waiting room, wrong current destination (expected conversationsListFragment but was something else)")
                }
            }
        }

        sharedViewModel.goToAccountProfileEvent.observe(viewLifecycleOwner) {
            it.consume {
                if (findNavController().currentDestination?.id == R.id.conversationsListFragment) {
                    val identity = LinphoneUtils.getDefaultAccount()?.params?.identityAddress?.asStringUriOnly().orEmpty()
                    val action =
                        ConversationsListFragmentDirections.actionConversationsListFragmentToAccountProfileFragment(
                            identity
                        )
                    findNavController().navigate(action)
                }
            }
        }

        sharedViewModel.displayFileEvent.observe(viewLifecycleOwner) {
            it.consume { bundle ->
                if (findNavController().currentDestination?.id == R.id.conversationsListFragment) {
                    val path = bundle.getString("path", "")
                    val isMedia = bundle.getBoolean("isMedia", false)
                    if (path.isEmpty()) {
                        Log.e("$TAG Can't navigate to file viewer for empty path!")
                        return@consume
                    }

                    Log.i(
                        "$TAG Navigating to [${if (isMedia) "media" else "file"}] viewer fragment with path [$path]"
                    )
                    if (isMedia) {
                        val intent = Intent(requireActivity(), MediaViewerActivity::class.java)
                        intent.putExtras(bundle)
                        startActivity(intent)
                    } else {
                        val intent = Intent(requireActivity(), FileViewerActivity::class.java)
                        intent.putExtras(bundle)
                        startActivity(intent)
                    }
                }
            }
        }

        sharedViewModel.updateConversationLastMessageEvent.observe(viewLifecycleOwner) {
            it.consume { conversationId ->
                val model = listViewModel.conversations.value.orEmpty().find { wrapperModel ->
                    wrapperModel.conversationModel?.id == conversationId
                }
                model?.conversationModel?.updateLastMessageInfo()
            }
        }

        sharedViewModel.forceRefreshDisplayedConversationEvent.observe(viewLifecycleOwner) {
            it.consume {
                val displayChatRoom = sharedViewModel.displayedChatRoom
                if (displayChatRoom != null) {
                    val found = listViewModel.conversations.value.orEmpty().find { wrapperModel ->
                        wrapperModel.conversationModel?.chatRoom == displayChatRoom
                    }
                    found?.conversationModel?.updateMuteState()
                }
            }
        }

        sharedViewModel.updateUnreadMessageCountForCurrentConversationEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume {
                val displayChatRoom = sharedViewModel.displayedChatRoom
                if (displayChatRoom != null) {
                    val found = listViewModel.conversations.value.orEmpty().find { model ->
                        model.conversationModel?.chatRoom == displayChatRoom
                    }
                    found?.conversationModel?.updateUnreadCount()
                }
                listViewModel.updateUnreadMessagesCount()
            }
        }

        // AbstractMainFragment related

        listViewModel.title.value = getString(R.string.bottom_navigation_conversations_label)
        setViewModel(listViewModel)
        initViews(
            binding.slidingPaneLayout,
            binding.topBar,
            binding.bottomNavBar,
            R.id.conversationsListFragment
        )

        // Handle intent params if any

        val args = arguments
        if (args != null) {
            val conversationId = args.getString(ARGUMENTS_CONVERSATION_ID)
            if (!conversationId.isNullOrEmpty()) {
                Log.i("$TAG Found conversation ID [$conversationId] in arguments")
                sharedViewModel.showConversationEvent.value = Event(conversationId)
                args.clear()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (listViewModel.hideConversations.value == true) {
            Log.w(
                "$TAG Resuming fragment that should no longer be accessible, going to contacts list instead"
            )
            sharedViewModel.navigateToContactsEvent.value = Event(true)
        }

        try {
            adapter.registerAdapterDataObserver(dataObserver)
        } catch (e: IllegalStateException) {
            Log.e("$TAG Failed to unregister data observer to adapter: $e")
        }

        listViewModel.filter()
        val notificationTab = findNavController().getBackStackEntry(R.id.conversationsListFragment)
            .savedStateHandle.remove<String>("mango9_push_tab")
        Mango9MessagingTab.entries.firstOrNull { it.name == notificationTab }?.let { mango9Tab = it }
        rebuildMango9Tabs()
        renderMango9Messages(mango9ChatStore.state.value)

        if (mango9Sessions.load() != null) {
            viewLifecycleOwner.lifecycleScope.launch { mango9ChatStore.refreshDirectory() }
        }
    }

    override fun onDestroyView() {
        adapter.onNearEnd = null
        binding.conversationsList.adapter = null
        listViewModel.cancelContactSearch()
        super.onDestroyView()
    }

    override fun onPause() {
        super.onPause()

        bottomSheetDialog?.dismiss()
        bottomSheetDialog = null

        try {
            adapter.unregisterAdapterDataObserver(dataObserver)
        } catch (e: IllegalStateException) {
            Log.e("$TAG Failed to unregister data observer to adapter: $e")
        }
    }

    private fun showNumbersOrAddressesDialog(list: List<ContactNumberOrAddressModel>) {
        val numberOrAddressModel = NumberOrAddressPickerDialogModel(list)
        val dialog =
            DialogUtils.getNumberOrAddressPickerDialog(
                requireActivity(),
                numberOrAddressModel
            )

        numberOrAddressModel.dismissEvent.observe(viewLifecycleOwner) { event ->
            event.consume {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun renderMango9Messages(state: Mango9ChatState) {
        val visible = mango9Sessions.load() != null
        listViewModel.mango9MessagesAvailable.value = visible
        if (visible) normalizeMango9Inbox()
        binding.newConversation.visibility = if (visible && mango9Tab in setOf(Mango9MessagingTab.Social, Mango9MessagingTab.Live)) View.GONE else View.VISIBLE
        if (!visible) return
        listOf(mango9SmsMessagesAdapter, mango9TeamMessagesAdapter, socialAdapter).forEach {
            it.notificationScope = mango9Sessions.captureSession()
        }
        val teamUnread = mango9ChatStore.teamUnreadCount(state)
        val smsUnread = mango9ChatStore.smsUnreadCount(state)
        updateTabBadge(Mango9MessagingTab.Team, teamUnread)
        updateTabBadge(Mango9MessagingTab.Sms, smsUnread)
        val teamItems = Mango9MessagingListItems.team(
            state,
            mango9Moderation::isConversationDeleted,
            mango9ChatStore::roomTitle,
        ).filterNot { it is Mango9MessagingListItem.User && it.room == null }
        val smsItems = Mango9MessagingListItems.sms(state, mango9Moderation::isSmsMuted)
        mango9TeamMessagesAdapter.submitList(teamItems)
        mango9SmsMessagesAdapter.submitList(smsItems)
        showMango9MessagesAdapter(mango9Tab)
        binding.socialFilters.visibility = if (mango9Tab == Mango9MessagingTab.Social) View.VISIBLE else View.GONE
        val live = mango9Tab == Mango9MessagingTab.Live
        liveInbox.show(live); binding.mango9LiveHost.visibility = if (live) View.VISIBLE else View.GONE
        binding.mango9MessagesList.visibility = if (live) View.GONE else View.VISIBLE
        binding.mango9MessagesStatus.visibility = if (live) View.GONE else View.VISIBLE
        if (live) { binding.mango9MessagesEmpty.visibility = View.GONE; return }
        if (mango9Tab == Mango9MessagingTab.Social) { renderSocial(); return }
        val items = if (mango9Tab == Mango9MessagingTab.Team) teamItems else smsItems
        binding.mango9MessagesEmpty.text = getString(
            if (mango9Tab == Mango9MessagingTab.Team) R.string.mango9_chat_empty else R.string.mango9_sms_empty,
        )
        binding.mango9MessagesEmpty.visibility = if (
            state.connection != Mango9ChatConnectionState.Connecting && items.isEmpty()
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.mango9MessagesStatus.visibility = if (state.connection == Mango9ChatConnectionState.Connected) View.GONE else View.VISIBLE
        binding.mango9MessagesStatus.text = getString(
            when (state.connection) {
                Mango9ChatConnectionState.Connecting -> R.string.mango9_chat_connecting
                Mango9ChatConnectionState.Connected -> R.string.mango9_chat_connected
                Mango9ChatConnectionState.Disconnected -> R.string.mango9_chat_disconnected
            },
        )
    }

    private fun updateTabBadge(kind: Mango9MessagingTab, unread: Int) {
        val tabs = binding.mango9MessagesTabs
        val tab = (0 until tabs.tabCount).mapNotNull { tabs.getTabAt(it) }.firstOrNull { it.tag == kind } ?: return
        if (unread <= 0) {
            tab.removeBadge()
            return
        }
        tab.orCreateBadge.apply {
            number = unread
            maxCharacterCount = 3
            backgroundColor = ContextCompat.getColor(requireContext(), R.color.orange_main_500)
            badgeTextColor = ContextCompat.getColor(requireContext(), R.color.bc_white)
            isVisible = true
        }
    }

    private fun showMango9MessagesAdapter(tab: Mango9MessagingTab) {
        val selected = when (tab) {
            Mango9MessagingTab.Team -> mango9TeamMessagesAdapter
            Mango9MessagingTab.Social -> socialAdapter
            Mango9MessagingTab.Sms, Mango9MessagingTab.Live -> mango9SmsMessagesAdapter
        }
        if (binding.mango9MessagesList.adapter !== selected) {
            // These adapters own different click routing callbacks. A compatible swap may reuse a
            // Team ViewHolder in the SMS adapter (or vice versa), retaining the wrong callback.
            binding.mango9MessagesList.adapter = selected
        }
    }

    private fun renderSocial() {
        if (mango9Tab != Mango9MessagingTab.Social || view == null) return
        val value = social.current()
        showMango9MessagesAdapter(mango9Tab)
        socialAdapter.submitList(
            value.threads.map {
            Mango9MessagingListItem.Social(it, value.previews[it.id] ?: "Loading preview…", value.previewEpoch)
        }
        )
        updateTabBadge(Mango9MessagingTab.Social, value.threads.count { it.unread })
        binding.mango9MessagesStatus.visibility = if (value.error != null || (value.loading && value.threads.isEmpty())) View.VISIBLE else View.GONE
        binding.mango9MessagesStatus.text = value.error ?: if (value.loading) getString(R.string.mango9_chat_connecting) else getString(R.string.mango9_social)
        binding.mango9MessagesEmpty.setText(R.string.mango9_social_empty)
        binding.mango9MessagesEmpty.visibility = if (!value.loading && value.threads.isEmpty() && value.error == null) View.VISIBLE else View.GONE
        val accounts = value.accounts.filter { value.platform.isBlank() || it.platform == value.platform }
        val ids = listOf("") + accounts.map { it.id }
        if (ids != socialAccountIds || binding.socialAccount.adapter == null) {
            changingSocialFilters = true
            socialAccountIds = ids
            binding.socialAccount.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.mango9_all_accounts)) + accounts.map {
                it.name
            }
            )
            binding.socialAccount.setSelection(ids.indexOf(value.account).coerceAtLeast(0))
            changingSocialFilters = false
        }
        val platformPosition = listOf("", "facebook", "instagram", "tiktok").indexOf(value.platform).coerceAtLeast(0)
        if (binding.socialPlatform.selectedItemPosition != platformPosition) binding.socialPlatform.setSelection(platformPosition)
        val accountPosition = ids.indexOf(value.account).coerceAtLeast(0)
        if (binding.socialAccount.selectedItemPosition != accountPosition) binding.socialAccount.setSelection(accountPosition)
    }

    private fun holdMango9Conversation(anchor: View, item: Mango9MessagingListItem) {
        showMango9ConversationMenu(anchor, item) {
            listOf(mango9SmsMessagesAdapter, mango9TeamMessagesAdapter, socialAdapter).forEach { it.notifyDataSetChanged() }
            if (mango9Tab == Mango9MessagingTab.Social) {
                social.inbox()
            } else {
                viewLifecycleOwner.lifecycleScope.launch { mango9ChatStore.refreshDirectory(); mango9ChatStore.refreshSmsDirectory() }
            }
        }
    }

    private fun openNativeShare(route: Mango9NativeConversationRoute) {
        if (route.shareRequestId != null && sharedViewModel.externalShare.pendingRequestId() != route.shareRequestId) return
        val action = ConversationFragmentDirections.actionGlobalConversationFragment(route.conversationId)
        val arguments = action.arguments.apply {
            putString(
                Mango9ExternalShare.ARG_DELIVERY,
                sharedViewModel.externalShare.bind(
                route.shareRequestId, Mango9ExternalShare.nativeDestination(route.conversationId),
            )
            )
        }
        binding.chatNavContainer.findNavController().navigate(action.actionId, arguments)
    }

    /** Close only this existing pane; never replay a close event into the new recipient screen. */
    fun showShareRecipientPicker() {
        if (view == null) return
        binding.bottomNavBar.root.visibility = View.VISIBLE
        binding.slidingPaneLayout.closePane()
    }

    private fun openMango9Item(sourceTab: Mango9MessagingTab, item: Mango9MessagingListItem) {
        if (sourceTab != mango9Tab || !sourceTab.accepts(item)) {
            Log.w("$TAG Ignoring stale Mango9 row from a different message tab")
            renderMango9Messages(mango9ChatStore.state.value)
            return
        }
        when (item) {
            is Mango9MessagingListItem.Social -> openMango9Conversation(Mango9MessagingListFragment.TYPE_SOCIAL, 0, item.thread.id, item.thread.title, null)
            is Mango9MessagingListItem.Group -> openMango9Conversation(
                Mango9MessagingListFragment.TYPE_TEAM,
                0,
                item.room.id,
                item.title,
                null,
            )
            is Mango9MessagingListItem.User -> openMango9Conversation(
                Mango9MessagingListFragment.TYPE_TEAM,
                item.user.id,
                item.room?.id,
                item.user.name,
                null,
            )
            is Mango9MessagingListItem.Sms -> openMango9Conversation(
                Mango9MessagingListFragment.TYPE_SMS,
                0,
                null,
                item.party.phone,
                item.party.phone,
            )
        }
    }

    private fun openMango9Conversation(
        type: String,
        userId: Int,
        roomId: String?,
        target: String,
        phone: String?,
    ) {
        if (findNavController().currentDestination?.id != R.id.conversationsListFragment) return
        val session = mango9Sessions.captureSession() ?: return
        val arguments = Bundle().apply {
            Mango9NavigationSession.bind(this, session)
            putString(
                Mango9ExternalShare.ARG_DELIVERY,
                sharedViewModel.externalShare.bind(
                sharedViewModel.externalShare.pendingRequestId(),
                Mango9ExternalShare.crmDestination(type, userId, roomId, phone),
            )
            )
            putString(Mango9MessagingListFragment.ARG_TYPE, type)
            putInt(Mango9MessagingListFragment.ARG_USER_ID, userId)
            putString(Mango9MessagingListFragment.ARG_ROOM_ID, roomId)
            putString(Mango9MessagingListFragment.ARG_TARGET, target)
            putString(Mango9MessagingListFragment.ARG_PHONE, phone)
        }
        findNavController().navigate(R.id.action_global_mango9MessagingConversationFragment, arguments)
    }

    private fun showDeleteConfirmationDialog(conversationModel: ConversationModel) {
        val dialogModel = ConfirmationDialogModel()
        val dialog = DialogUtils.getDeleteConversationConfirmationDialog(
            requireActivity(),
            dialogModel
        )

        dialogModel.dismissEvent.observe(viewLifecycleOwner) {
            it.consume {
                dialog.dismiss()
            }
        }

        dialogModel.confirmEvent.observe(viewLifecycleOwner) {
            it.consume {
                Log.i("$TAG Deleting conversation [${conversationModel.id}]")
                conversationModel.delete()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showLeaveConfirmationDialog(conversationModel: ConversationModel) {
        val dialogModel = ConfirmationDialogModel()
        val dialog = DialogUtils.getLeaveConversationConfirmationDialog(
            requireActivity(),
            dialogModel
        )

        dialogModel.dismissEvent.observe(viewLifecycleOwner) {
            it.consume {
                dialog.dismiss()
            }
        }

        dialogModel.confirmEvent.observe(viewLifecycleOwner) {
            it.consume {

                Log.i("$TAG Leaving group conversation [${conversationModel.id}]")
                conversationModel.leaveGroup()
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
