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
package org.linphone.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.car.app.connection.CarConnection
import androidx.core.app.ActivityCompat
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.compatibility.Compatibility
import org.linphone.core.tools.Log
import org.linphone.databinding.MainActivityBinding
import org.linphone.mango9.Mango9AccountContextSync
import org.linphone.mango9.Mango9ChatRouting
import org.linphone.mango9.Mango9ExternalShare
import org.linphone.mango9.Mango9NativeConversationRoute
import org.linphone.mango9.Mango9ChatTarget
import org.linphone.mango9.Mango9MessagePush
import org.linphone.mango9.Mango9MessagePushCoordinator
import org.linphone.mango9.Mango9MessagePushTarget
import org.linphone.mango9.Mango9SmsRouting
import org.linphone.mango9.Mango9SmsTarget
import org.linphone.mango9.Mango9SessionLease
import org.linphone.mango9.Mango9SessionStore
import org.linphone.mango9.Mango9NavigationSession
import org.linphone.mango9.Mango9OpenRequestGate
import org.linphone.ui.GenericActivity
import org.linphone.ui.assistant.AssistantActivity
import org.linphone.ui.main.chat.fragment.ConversationsListFragmentDirections
import org.linphone.ui.main.chat.fragment.ConversationsListFragment
import org.linphone.ui.main.crm.fragment.Mango9CrmFragment
import org.linphone.ui.main.crm.fragment.Mango9ReminderHost
import org.linphone.ui.main.crm.viewmodel.Mango9ReminderEditsViewModel
import org.linphone.ui.main.crm.fragment.Mango9MessagingListFragment
import org.linphone.utils.PasswordDialogModel
import org.linphone.ui.main.viewmodel.MainViewModel
import org.linphone.ui.main.viewmodel.SharedMainViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.DialogUtils
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import org.linphone.utils.LinphoneUtils
import androidx.core.content.edit
import org.linphone.ui.sso.SingleSignOnActivity

@UiThread
class MainActivity : GenericActivity() {
    companion object {
        private const val TAG = "[Main Activity]"

        private const val DEFAULT_FRAGMENT_KEY = "default_fragment"
        private const val CONTACTS_FRAGMENT_ID = 1
        private const val HISTORY_FRAGMENT_ID = 2
        private const val CHAT_FRAGMENT_ID = 3
        private const val MEETINGS_FRAGMENT_ID = 4

        const val ARGUMENTS_CHAT = "Chat"
        const val ARGUMENTS_CONVERSATION_ID = "ConversationId"
    }

    private lateinit var binding: MainActivityBinding

    private lateinit var viewModel: MainViewModel

    private lateinit var sharedViewModel: SharedMainViewModel

    private var currentlyDisplayedAuthDialog: Dialog? = null

    private var navigatedToDefaultFragment = false

    private val messageOpenRequests = Mango9OpenRequestGate()
    private var messageOpenJob: Job? = null
    private var contactsRefreshJob: Job? = null
    private var reminderHost: Mango9ReminderHost? = null

    private val destinationListener = object : NavController.OnDestinationChangedListener {
        override fun onDestinationChanged(
            controller: NavController,
            destination: NavDestination,
            arguments: Bundle?
        ) {
            Log.i("$TAG Latest visited fragment was restored")
            navigatedToDefaultFragment = true
            controller.removeOnDestinationChangedListener(this)
        }
    }

    private val postNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG POST_NOTIFICATIONS permission has been granted")
            viewModel.updateMissingPermissionAlert()
        } else {
            Log.w("$TAG POST_NOTIFICATIONS permission has been denied!")
        }
    }

    private val fullScreenIntentPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG USE_FULL_SCREEN_INTENT permission has been granted")
            viewModel.updateMissingPermissionAlert()
        } else {
            Log.w("$TAG USE_FULL_SCREEN_INTENT permission has been denied!")
        }
    }

    private val accessLocalNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG ACCESS_LOCAL_NETWORK permission has been granted")
            viewModel.updateMissingPermissionAlert()
        } else {
            Log.w("$TAG ACCESS_LOCAL_NETWORK permission has been denied!")
        }
    }

    override fun onDestroy() {
        reminderHost?.close(); reminderHost = null
        contactsRefreshJob?.cancel()
        messageOpenRequests.cancel()
        messageOpenJob?.cancel()
        super.onDestroy()
    }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be done before the setContentView
        installSplashScreen()

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) {
                true // Force dark mode to always have white icons in status bar
            }
        )

        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.main_activity)
        binding.lifecycleOwner = this
        setUpToastsArea(binding.toastsArea)
        val reminderEdits = androidx.lifecycle.ViewModelProvider(this)[Mango9ReminderEditsViewModel::class.java]
        reminderHost = Mango9ReminderHost(this, binding.mango9ReminderBanner, repository = reminderEdits.repository, retainedEdits = reminderEdits.edits)

        // Will give the device's status bar background color
        ViewCompat.setOnApplyWindowInsetsListener(binding.notificationsArea) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(0, insets.top, 0, 0)
            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainNavContainer) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(insets.left, 0, insets.right, max(insets.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerMenuContent) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // SDK work in the view models is queued until Core/listeners exist.
        // Waiting here blocks Android lifecycle/input and can deadlock startup.
        viewModel = run {
            ViewModelProvider(this)[MainViewModel::class.java]
        }
        binding.viewModel = viewModel

        sharedViewModel = run {
            ViewModelProvider(this)[SharedMainViewModel::class.java]
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Mango9ChatRouting.openRequests.collect {
                    messageOpenRequests.cancel()
                    messageOpenJob?.cancel()
                    openMango9Chat(it.target, it.session, it.shareRequestId)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Mango9SmsRouting.openRequests.collect {
                    messageOpenRequests.cancel()
                    messageOpenJob?.cancel()
                    openMango9Sms(it.target, it.session, it.shareRequestId)
                }
            }
        }
        refreshMango9AccountContext()
        viewModel.defaultAccountChangedEvent.observe(this) { event ->
            event.consume {
                refreshMango9AccountContext()
            }
        }

        viewModel.goBackToCallEvent.observe(this) {
            it.consume {
                coreContext.showCallActivity()
            }
        }

        viewModel.openDrawerEvent.observe(this) {
            it.consume {
                openDrawerMenu()
            }
        }

        viewModel.askPostNotificationsPermissionEvent.observe(this) {
            it.consume {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    Log.w("$TAG Asking for POST_NOTIFICATIONS permission")
                    postNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Log.e("$TAG Permission request for POST_NOTIFICATIONS will be automatically denied, go to android app settings instead")
                    goToAndroidPermissionSettings()
                }
            }
        }

        viewModel.askFullScreenIntentPermissionEvent.observe(this) {
            it.consume {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.USE_FULL_SCREEN_INTENT)) {
                    Log.w("$TAG Asking for USE_FULL_SCREEN_INTENT permission")
                    fullScreenIntentPermissionLauncher.launch(Manifest.permission.USE_FULL_SCREEN_INTENT)
                } else {
                    Log.e("$TAG Permission request for USE_FULL_SCREEN_INTENT will be automatically denied, go to manage app full screen intent android settings instead")
                    Compatibility.requestFullScreenIntentPermission(this)
                }
            }
        }

        viewModel.askAccessLocalNetworkPermissionEvent.observe(this) {
            it.consume {
                Log.w("$TAG Asking for ACCESS_LOCAL_NETWORK permission")
                accessLocalNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }

        viewModel.showNewAccountToastEvent.observe(this) {
            it.consume {
                val message = getString(R.string.new_account_configured_toast)
                showGreenToast(message, R.drawable.user_circle)
            }
        }

        viewModel.startLoadingContactsEvent.observe(this) {
            it.consume {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    loadContacts()
                }
            }
        }

        viewModel.lastAccountRemovedEvent.observe(this) {
            it.consume {
                startActivity(Intent(this, AssistantActivity::class.java))
            }
        }

        viewModel.clearFilesOrTextPendingSharingEvent.observe(this) {
            it.consume { sharedViewModel.externalShare.clear() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sharedViewModel.externalShare.state.collect { share ->
                    when {
                        share.id == null -> viewModel.filesOrTextPendingSharingListCleared()
                        share.error != null -> viewModel.showPendingSharing(share.error)
                        share.preparing -> viewModel.showPendingSharing(getString(R.string.mango9_share_preparing))
                        share.count > 0 -> viewModel.addFilesPendingSharing(share.count)
                        else -> viewModel.addTextPendingSharing()
                    }
                }
            }
        }

        // Wait for latest visited fragment to be displayed before hiding the splashscreen
        binding.root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                return if (navigatedToDefaultFragment) {
                    Log.i("$TAG Report UI has been fully drawn (TTFD)")
                    try {
                        reportFullyDrawn()
                    } catch (se: SecurityException) {
                        Log.e("$TAG Security exception when doing reportFullyDrawn(): $se")
                    }
                    binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                    true
                } else {
                    false
                }
            }
        })
        coreContext.bearerAuthenticationRequestedEvent.observe(this) {
            it.consume { pair ->
                val serverUrl = pair.first
                val username = pair.second

                Log.i(
                    "$TAG Bearer auth request, navigating to Single Sign On Fragment with server URL [$serverUrl] and username [$username]"
                )
                val intent = Intent(this, SingleSignOnActivity::class.java)
                intent.putExtra(SingleSignOnActivity.INTENT_EXTRA_USERNAME, username)
                intent.putExtra(SingleSignOnActivity.INTENT_EXTRA_SERVER_URL, serverUrl)
                startActivity(intent)
            }
        }

        coreContext.digestAuthenticationRequestedEvent.observe(this) {
            it.consume { identity ->
                try {
                    if (coreContext.digestAuthInfoPendingPasswordUpdate != null) {
                        showAuthenticationRequestedDialog(identity)
                    }
                } catch (e: WindowManager.BadTokenException) {
                    Log.e("$TAG Failed to show authentication dialog: $e")
                }
            }
        }

        coreContext.clearAuthenticationRequestDialogEvent.observe(this) {
            it.consume {
                currentlyDisplayedAuthDialog?.dismiss()
            }
        }

        coreContext.showGreenToastEvent.observe(this) {
            it.consume { pair ->
                val message = getString(pair.first)
                val icon = pair.second
                showGreenToast(message, icon)
            }
        }

        coreContext.showRedToastEvent.observe(this) {
            it.consume { pair ->
                val message = getString(pair.first)
                val icon = pair.second
                showRedToast(message, icon)
            }
        }

        coreContext.showFormattedRedToastEvent.observe(this) {
            it.consume { pair ->
                val message = pair.first
                val icon = pair.second
                showRedToast(message, icon)
            }
        }

        coreContext.provisioningAppliedEvent.observe(this) {
            it.consume {
                Log.i("$TAG Remote provisioning was applied, checking if theme has changed")
                checkMainColorTheme()
            }
        }

        coreContext.filesToExportToNativeMediaGalleryEvent.observe(this) {
            it.consume { files ->
                Log.i("$TAG Found [${files.size}] files to export to native media gallery")
                for (file in files) {
                    exportFileToNativeMediaGallery(file)
                }

                coreContext.postOnCoreThread {
                    coreContext.clearFilesToExportToNativeGallery()
                }
            }
        }

        CarConnection(this).type.observe(this) {
            val asString = when (it) {
                CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "NOT CONNECTED"
                CarConnection.CONNECTION_TYPE_PROJECTION -> "PROJECTION"
                CarConnection.CONNECTION_TYPE_NATIVE -> "NATIVE"
                else -> "UNEXPECTED ($it)"
            }
            Log.i("$TAG Car connection is [$asString]")
            val projection = it == CarConnection.CONNECTION_TYPE_PROJECTION
            coreContext.isConnectedToAndroidAuto = projection
        }

        coreContext.postOnCoreThread { core ->
            if (corePreferences.firstLaunch) {
                Log.i("$TAG First Mango9 launch detected")
                corePreferences.firstLaunch = false
            }

            if (core.accountList.isEmpty()) {
                Log.w("$TAG No account found, showing Mango9 assistant")
                coreContext.postOnMainThread {
                    if (isFinishing || isDestroyed) return@postOnMainThread
                    try {
                        startActivity(Intent(this, AssistantActivity::class.java))
                    } catch (ise: IllegalStateException) {
                        Log.e("$TAG Can't start activity: $ise")
                    }
                }
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        // NavHost restores its back stack for every entry route, not only ACTION_MAIN.
        // Reopening the default tab here destroys forms/retained drafts after rotation
        // when the Activity originally came from a notification or another app.
        if (savedInstanceState == null) goToLatestVisitedFragment() else navigatedToDefaultFragment = true

        // We don't want that intent to be handled upon rotation
        if (savedInstanceState == null && intent != null) {
            Log.d("$TAG savedInstanceState is null but intent isn't, handling it")
            handleIntent(intent)
        }
    }

    override fun onPause() {
        contactsRefreshJob?.cancel()
        contactsRefreshJob = null
        viewModel.enableAccountMonitoring(false)

        currentlyDisplayedAuthDialog?.dismiss()
        currentlyDisplayedAuthDialog = null

        val defaultFragmentId = when (sharedViewModel.currentlyDisplayedFragment.value) {
            R.id.contactsListFragment -> {
                CONTACTS_FRAGMENT_ID
            }
            R.id.historyListFragment -> {
                HISTORY_FRAGMENT_ID
            }
            R.id.conversationsListFragment -> {
                CHAT_FRAGMENT_ID
            }
            R.id.meetingsListFragment -> {
                MEETINGS_FRAGMENT_ID
            }
            else -> { // Default
                HISTORY_FRAGMENT_ID
            }
        }
        getPreferences(MODE_PRIVATE).edit {
            putInt(DEFAULT_FRAGMENT_KEY, defaultFragmentId)
        }
        Log.i("$TAG Stored [$defaultFragmentId] as default page")

        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        viewModel.enableAccountMonitoring(true)
        viewModel.updateMissingPermissionAlert()
        viewModel.updateAccountsAndNetworkReachability()
        // Refresh permission/deletions on return from Android Contacts or app settings.
        // The loader performs its query off-thread and also clears only the native
        // cache after permission revocation; it never requests access automatically.
        contactsRefreshJob?.cancel()
        contactsRefreshJob = lifecycleScope.launch {
            coreContext.awaitReady()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) loadContacts()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("$TAG Handling new intent")
        handleIntent(intent)
    }

    @SuppressLint("RtlHardcoded")
    fun toggleDrawerMenu() {
        if (binding.drawerMenu.isDrawerOpen(Gravity.LEFT)) {
            closeDrawerMenu()
        } else {
            openDrawerMenu()
        }
    }

    fun closeDrawerMenu() {
        binding.drawerMenu.closeDrawer(binding.drawerMenuContent, true)
    }

    private fun openDrawerMenu() {
        binding.drawerMenu.openDrawer(binding.drawerMenuContent, true)
    }

    fun findNavController(): NavController {
        return findNavController(R.id.main_nav_container)
    }

    private fun openMango9Chat(
        target: Mango9ChatTarget,
        lease: Mango9SessionLease? = Mango9SessionStore(this).captureSession(),
        shareRequestId: String? = null,
    ) {
        if (shareRequestId != null && sharedViewModel.externalShare.pendingRequestId() != shareRequestId) return
        if (lease == null || Mango9SessionStore(this).currentSession(lease) == null) return
        val arguments = Bundle().apply {
            Mango9NavigationSession.bind(this, lease)
            putString(
                Mango9ExternalShare.ARG_DELIVERY,
                sharedViewModel.externalShare.bind(
                shareRequestId, Mango9ExternalShare.crmDestination(Mango9MessagingListFragment.TYPE_TEAM, target.userId, target.roomId, null),
            )
            )
            putString(Mango9MessagingListFragment.ARG_TYPE, Mango9MessagingListFragment.TYPE_TEAM)
            putInt(Mango9MessagingListFragment.ARG_USER_ID, target.userId)
            putString(Mango9MessagingListFragment.ARG_ROOM_ID, target.roomId)
            putString(Mango9MessagingListFragment.ARG_TARGET, target.name)
            putString(Mango9MessagingListFragment.ARG_PHONE, null)
        }
        findNavController().navigate(R.id.action_global_mango9MessagingConversationFragment, arguments)
    }

    private fun openMango9Sms(
        target: Mango9SmsTarget,
        lease: Mango9SessionLease? = Mango9SessionStore(this).captureSession(),
        shareRequestId: String? = null,
    ) {
        if (shareRequestId != null && sharedViewModel.externalShare.pendingRequestId() != shareRequestId) return
        if (lease == null || Mango9SessionStore(this).currentSession(lease) == null) return
        val arguments = Bundle().apply {
            Mango9NavigationSession.bind(this, lease)
            putString(
                Mango9ExternalShare.ARG_DELIVERY,
                sharedViewModel.externalShare.bind(
                shareRequestId, Mango9ExternalShare.crmDestination(Mango9MessagingListFragment.TYPE_SMS, 0, null, target.phone),
            )
            )
            putString(Mango9MessagingListFragment.ARG_TYPE, Mango9MessagingListFragment.TYPE_SMS)
            putInt(Mango9MessagingListFragment.ARG_USER_ID, 0)
            putString(Mango9MessagingListFragment.ARG_ROOM_ID, null)
            putString(Mango9MessagingListFragment.ARG_TARGET, target.name)
            putString(Mango9MessagingListFragment.ARG_PHONE, target.phone)
        }
        findNavController().navigate(R.id.action_global_mango9MessagingConversationFragment, arguments)
    }

    private fun openMango9Lead(id: Int, lease: Mango9SessionLease) {
        val arguments = Bundle().apply {
            Mango9NavigationSession.bind(this, lease)
            putString(Mango9CrmFragment.ARG_PUSH_RECORD_KIND, "lead")
            putInt(Mango9CrmFragment.ARG_PUSH_RECORD_ID, id)
        }
        findNavController().navigate(R.id.action_global_mango9CrmFragment, arguments)
    }

    private fun refreshMango9AccountContext() {
        lifecycleScope.launch {
            Mango9AccountContextSync.refresh(this@MainActivity)
        }
    }

    fun loadContacts() {
        coreContext.contactsManager.loadContacts(this)
    }

    private fun goToLatestVisitedFragment() {
        try {
            // Prevent navigating to default fragment upon rotation (we only want to do it on first start)
            if (intent.action == Intent.ACTION_MAIN && intent.type == null && intent.data == null) {
                if (viewModel.mainIntentHandled) {
                    Log.d(
                        "$TAG Main intent without type nor data was already handled, do nothing"
                    )
                    navigatedToDefaultFragment = true
                    return
                } else {
                    viewModel.mainIntentHandled = true
                }
            }

            val defaultFragmentId = getPreferences(MODE_PRIVATE).getInt(
                DEFAULT_FRAGMENT_KEY,
                HISTORY_FRAGMENT_ID
            )
            Log.i(
                "$TAG Trying to navigate to set default destination [$defaultFragmentId]"
            )
            try {
                val navOptionsBuilder = NavOptions.Builder()
                navOptionsBuilder.setPopUpTo(R.id.historyListFragment, true)
                navOptionsBuilder.setLaunchSingleTop(true)
                val navOptions = navOptionsBuilder.build()
                val args = bundleOf()
                when (defaultFragmentId) {
                    CONTACTS_FRAGMENT_ID -> {
                        findNavController().addOnDestinationChangedListener(destinationListener)
                        findNavController().navigate(
                            R.id.contactsListFragment,
                            args,
                            navOptions
                        )
                    }
                    CHAT_FRAGMENT_ID -> {
                        findNavController().addOnDestinationChangedListener(destinationListener)
                        findNavController().navigate(
                            R.id.conversationsListFragment,
                            args,
                            navOptions
                        )
                    }
                    MEETINGS_FRAGMENT_ID -> {
                        findNavController().addOnDestinationChangedListener(destinationListener)
                        findNavController().navigate(
                            R.id.meetingsListFragment,
                            args,
                            navOptions
                        )
                    }
                    else -> {
                        Log.i("$TAG Default fragment is the same as the latest visited one")
                        navigatedToDefaultFragment = true
                    }
                }
            } catch (ise: IllegalStateException) {
                Log.e("$TAG Can't navigate to Conversations fragment: $ise")
            }
        } catch (ise: IllegalStateException) {
            Log.i("$TAG Failed to handle intent: $ise")
        }
    }

    private fun handleIntent(intent: Intent) {
        messageOpenRequests.cancel()
        messageOpenJob?.cancel()
        val extras = intent.extras
        val hasExtra = extras != null && !extras.isEmpty
        Log.i(
            "$TAG Handling intent action [${intent.action}], type [${intent.type}] and has ${if (hasExtra) "extras" else "no extra"}"
        )

        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_SEND -> {
                handleSendIntent(intent)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                handleSendIntent(intent)
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data?.toString() ?: ""
                if (uri.startsWith("linphone-config:")) {
                    handleConfigIntent(uri)
                } else {
                    handleCallIntent(intent)
                }
            }
            Intent.ACTION_DIAL, Intent.ACTION_CALL -> {
                handleCallIntent(intent)
            }
            Intent.ACTION_VIEW_LOCUS -> {
                val locus = Compatibility.extractLocusIdFromIntent(intent)
                if (locus != null) {
                    Log.i("$TAG Found chat room locus intent extra: $locus")
                    handleLocusOrShortcut(locus)
                }
            }
            else -> {
                handleMainIntent(intent)
            }
        }
    }

    private fun handleLocusOrShortcut(id: String) {
        Log.i("$TAG Found locus ID [$id]")
        if (id.isNotEmpty()) {
            Log.i("$TAG Navigating to conversation with ID [$id], computed from shortcut ID")
            sharedViewModel.showConversationEvent.value = Event(id)
        }
    }

    private fun handleMainIntent(intent: Intent) {
        messageOpenJob?.cancel()
        val request = messageOpenRequests.begin()
        val messagePush = Mango9MessagePush.fromJson(
            intent.getStringExtra(Mango9MessagePushCoordinator.EXTRA_PUSH),
        )
        if (messagePush != null) {
            intent.removeExtra(Mango9MessagePushCoordinator.EXTRA_PUSH)
            messageOpenJob = lifecycleScope.launch {
                val lease = Mango9MessagePushCoordinator.activateForOpen(this@MainActivity, messagePush, request)
                if (lease == null) {
                    Log.w("$TAG Ignoring Mango9 message push for an unavailable account")
                    return@launch
                }
                request.ifCurrent {
                    if (Mango9SessionStore(this@MainActivity).currentSession(lease) == null) return@ifCurrent
                    when (val target = messagePush.target) {
                        is Mango9MessagePushTarget.Chat -> openMango9Chat(
                            Mango9ChatTarget(target.userId, target.name, target.roomId), lease,
                        )
                        is Mango9MessagePushTarget.Sms -> openMango9Sms(Mango9SmsTarget(target.phone, target.name), lease)
                        is Mango9MessagePushTarget.Lead -> openMango9Lead(target.id, lease)
                        Mango9MessagePushTarget.Leads -> findNavController().navigate(
                            R.id.action_global_mango9CrmFragment,
                            Bundle().apply {
                            Mango9NavigationSession.bind(this, lease); putBoolean("mango9_open_leads", true)
                        }
                        )
                        is Mango9MessagePushTarget.Appointment -> findNavController().navigate(
                            R.id.action_global_mango9CalendarFragment,
                            Bundle().apply {
                            Mango9NavigationSession.bind(this, lease); putInt("mango9_event_id", target.id)
                        }
                        )
                        is Mango9MessagePushTarget.Social -> findNavController().navigate(
                            R.id.action_global_mango9MessagingConversationFragment,
                            Bundle().apply {
                            Mango9NavigationSession.bind(this, lease)
                            putString(Mango9MessagingListFragment.ARG_TYPE, Mango9MessagingListFragment.TYPE_SOCIAL)
                            putString(Mango9MessagingListFragment.ARG_ROOM_ID, target.threadId)
                            putString(Mango9MessagingListFragment.ARG_TARGET, "Social conversation")
                        }
                        )
                    }
                }
            }
            return
        }
        coreContext.postOnCoreThread { core ->
            if (intent.hasExtra(ARGUMENTS_CHAT)) {
                Log.i("$TAG Intent has [Chat] extra")
                coreContext.postOnMainThread {
                    try {
                        Log.i("$TAG Trying to go to Conversations fragment")
                        val args = intent.extras
                        val conversationId = args?.getString(ARGUMENTS_CONVERSATION_ID, "")
                        if (conversationId.isNullOrEmpty()) {
                            Log.w("$TAG Found [Chat] extra but no conversation ID!")
                        } else {
                            Log.i("$TAG Found [Chat] extra with conversation ID [$conversationId]")
                            sharedViewModel.showConversationEvent.value = Event(conversationId)
                        }
                        args?.clear()

                        if (findNavController().currentDestination?.id == R.id.conversationsListFragment) {
                            Log.w(
                                "$TAG Current destination is already conversations list, skipping navigation"
                            )
                        } else {
                            val navOptionsBuilder = NavOptions.Builder()
                            navOptionsBuilder.setPopUpTo(
                                findNavController().currentDestination?.id ?: R.id.historyListFragment,
                                true
                            )
                            navOptionsBuilder.setLaunchSingleTop(true)
                            val navOptions = navOptionsBuilder.build()
                            findNavController().navigate(
                                R.id.conversationsListFragment,
                                args,
                                navOptions
                            )
                        }
                    } catch (ise: IllegalStateException) {
                        Log.e("$TAG Can't navigate to Conversations fragment: $ise")
                    }
                }
            }
        }
    }

    private fun handleSendIntent(intent: Intent) {
        val request = sharedViewModel.externalShare.begin(
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
            Mango9ExternalShare.streams(intent),
            intent.type,
        ) ?: return
        if (binding.drawerMenu.isOpen) closeDrawerMenu()
        val host = supportFragmentManager.findFragmentById(R.id.main_nav_container) as? androidx.navigation.fragment.NavHostFragment
        (host?.childFragmentManager?.primaryNavigationFragment as? ConversationsListFragment)?.showShareRecipientPicker()
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.conversationsListFragment) {
            val action = ConversationsListFragmentDirections.actionGlobalConversationsListFragment()
            val options = NavOptions.Builder().setLaunchSingleTop(true)
            if (controller.currentDestination?.id == R.id.debugFragment) options.setPopUpTo(R.id.helpFragment, true)
            controller.navigate(action, options.build())
        }
        parseShortcutIfAny(intent)?.let { id ->
            sharedViewModel.showSharedConversationEvent.value = Event(Mango9NativeConversationRoute(id, request))
        }
    }

    private fun parseShortcutIfAny(intent: Intent): String? {
        val shortcutId = intent.getStringExtra("android.intent.extra.shortcut.ID") // Intent.EXTRA_SHORTCUT_ID
        if (shortcutId != null) {
            Log.i("$TAG Found conversation shortcut")
            return shortcutId
        } else {
            Log.i("$TAG No shortcut ID was found")
        }
        return null
    }

    private fun handleCallIntent(intent: Intent) {
        val uri = intent.data?.toString()
        if (uri.isNullOrEmpty()) {
            Log.e("$TAG Intent data is null or empty, can't process [${intent.action}] intent")
            return
        }

        Log.i("$TAG Found URI [$uri] as data for intent [${intent.action}]")
        val sipUriToCall = when {
            uri.startsWith("tel:") -> uri.substring("tel:".length)
            uri.startsWith("callto:") -> uri.substring("callto:".length)
            uri.startsWith("sip-linphone:") -> uri.replace("sip-linphone:", "sip:")
            uri.startsWith("linphone-sip:") -> uri.replace("linphone-sip:", "sip:")
            uri.startsWith("sips-linphone:") -> uri.replace("sips-linphone:", "sips:")
            uri.startsWith("linphone-sips:") -> uri.replace("linphone-sips:", "sips:")
            else -> uri.replace("%40", "@") // Unescape @ character if needed
        }

        coreContext.postOnCoreThread {
            val address = coreContext.core.interpretUrl(
                sipUriToCall,
                LinphoneUtils.applyInternationalPrefix()
            )
            Log.i("$TAG Interpreted SIP URI is [${address?.asStringUriOnly()}]")
            if (address != null) {
                // Skip network reachability test, this code will be called too soon
                coreContext.startAudioCall(address, skipNetworkReachabilityTest = true)
            }
        }
    }

    private fun handleConfigIntent(uri: String) {
        Log.i("$TAG Trying to parse config intent [$uri] as remote provisioning URL")
        val url = LinphoneUtils.getRemoteProvisioningUrlFromUri(uri)
        if (url == null) {
            Log.e("$TAG Couldn't parse URI [$uri] into a valid remote provisioning URL, aborting")
            return
        }

        coreContext.postOnCoreThread { core ->
            core.provisioningUri = url
            Log.w("$TAG Remote provisioning URL set to [$url], restarting Core now")
            core.stop()
            Log.i("$TAG Core has been stopped, let's restart it")
            core.start()
            Log.i("$TAG Core has been restarted")
        }
    }

    private fun showAuthenticationRequestedDialog(identity: String) {
        currentlyDisplayedAuthDialog?.dismiss()

        val label = AppUtils.getFormattedString(
            R.string.account_settings_dialog_invalid_password_message,
            identity
        )
        val model = PasswordDialogModel(label)
        val dialog = DialogUtils.getAuthRequestedDialog(this, model)

        model.dismissEvent.observe(this) {
            it.consume {
                dialog.dismiss()
            }
        }

        model.confirmEvent.observe(this) {
            it.consume { password ->
                coreContext.postOnCoreThread {
                    coreContext.updateAuthInfo(password)
                }
                dialog.dismiss()
            }
        }

        dialog.show()
        currentlyDisplayedAuthDialog = dialog
    }

    private fun exportFileToNativeMediaGallery(filePath: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                Log.i("$TAG Export file [$filePath] to Android's MediaStore")
                val mediaStorePath = FileUtils.addContentToMediaStore(filePath)
                if (mediaStorePath.isNotEmpty()) {
                    Log.i("$TAG File [$filePath] has been successfully exported to MediaStore")
                } else {
                    Log.e("$TAG Failed to export file [$filePath] to MediaStore!")
                }
            }
        }
    }
}
