package org.linphone.ui.fileviewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.UiThread
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.FileMediaViewerActivityBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.fileviewer.adapter.MediaListAdapter
import org.linphone.ui.fileviewer.viewmodel.MediaListViewModel
import org.linphone.ui.fileviewer.viewmodel.Mango9MediaViewerLease
import org.linphone.ui.fileviewer.viewmodel.Mango9NativeViewerLease
import org.linphone.ui.fileviewer.fragment.MediaViewerFragment
import org.linphone.mango9.Mango9MediaCache
import org.linphone.mango9.Mango9NavigationSession
import org.linphone.mango9.Mango9SessionStore
import org.linphone.mango9.Mango9NativeAttachment
import kotlinx.coroutines.flow.collect
import org.linphone.ui.main.chat.model.FileModel
import org.linphone.ui.main.viewmodel.SharedMainViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.FileUtils
import androidx.core.net.toUri

@UiThread
class MediaViewerActivity : GenericActivity() {
    companion object {
        private const val TAG = "[Media Viewer Activity]"
    }

    private lateinit var binding: FileMediaViewerActivityBinding

    private lateinit var adapter: MediaListAdapter

    private lateinit var viewModel: MediaListViewModel

    private lateinit var viewPager: ViewPager2

    private lateinit var sharedViewModel: SharedMainViewModel

    private val pageListener = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val list = viewModel.mediaList.value.orEmpty()
            if (position >= 0 && position < list.size) {
                val model = list[position]
                viewModel.currentlyDisplayedFileName.value = model.fileName
                viewModel.currentlyDisplayedFileDateTime.value = model.dateTime

                val isFromEphemeral = model.isFromEphemeralMessage
                viewModel.isCurrentlyDisplayedFileFromEphemeralMessage.value = isFromEphemeral
                if (!corePreferences.enableSecureMode) {
                    // Force preventing screenshots for ephemeral messages contents, but allow it for others
                    enableWindowSecureMode(isFromEphemeral)
                }
                viewModel.onSelected(model.path)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val nativeLease = ViewModelProvider(this)[Mango9NativeViewerLease::class.java]
        if (intent.extras?.let(nativeLease::accept) != true) {
            nativeLease.release()
            finish()
            return
        }
        lifecycleScope.launch {
            Mango9SessionStore.contextChanges.collect {
                if (!nativeLease.isCurrent()) {
                    supportFragmentManager.fragments.filterIsInstance<MediaViewerFragment>()
                        .forEach(MediaViewerFragment::releaseOwnedPlayback)
                    finish()
                }
            }
        }

        if (intent.getBooleanExtra(Mango9MediaCache.OWNED_MEDIA, false)) {
            val args = requireNotNull(intent.extras)
            val sessions = Mango9SessionStore(applicationContext)
            val held = ViewModelProvider(this)[Mango9MediaViewerLease::class.java]
            val lease = Mango9NavigationSession.matching(args, sessions.captureSession())
            if (!held.accept(args, lease)) {
                held.release()
                finish()
                return
            }
            lifecycleScope.launch {
                Mango9SessionStore.contextChanges.collect {
                    if (Mango9NavigationSession.matching(args, sessions.captureSession()) == null) {
                        supportFragmentManager.fragments.filterIsInstance<MediaViewerFragment>()
                            .forEach(MediaViewerFragment::releaseOwnedPlayback)
                        held.release()
                        finish()
                    }
                }
            }
        }

        binding = DataBindingUtil.setContentView(this, R.layout.file_media_viewer_activity)
        binding.lifecycleOwner = this
        setUpToastsArea(binding.toastsArea)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        sharedViewModel = run {
            ViewModelProvider(this)[SharedMainViewModel::class.java]
        }
        binding.sharedViewModel = sharedViewModel
        sharedViewModel.mediaViewerFullScreenMode.value = true

        viewModel = ViewModelProvider(this)[MediaListViewModel::class.java]
        binding.viewModel = viewModel

        adapter = MediaListAdapter(this)

        viewPager = binding.mediaViewPager
        viewPager.adapter = adapter
        viewPager.registerOnPageChangeCallback(pageListener)

        val args = intent.extras
        if (args == null) {
            finish()
            return
        }

        val path = args.getString("path", "")
        if (path.isNullOrEmpty()) {
            finish()
            return
        }

        val isFromEphemeralMessage = args.getBoolean("isFromEphemeralMessage", false)
        if (isFromEphemeralMessage) {
            Log.i("$TAG Displayed content is from an ephemeral chat message, force secure mode to prevent screenshots")
            // Force preventing screenshots for ephemeral messages contents
            enableWindowSecureMode(true)
        }

        val timestamp = args.getLong("timestamp", -1)
        val isEncrypted = args.getBoolean("isEncrypted", false)
        val originalPath = args.getString("originalPath", "")
        Log.i("$TAG Path argument is [$path], timestamp [$timestamp], encrypted [$isEncrypted] and original path [$originalPath]")
        if (viewModel.mediaList.value.isNullOrEmpty()) {
            viewModel.initTempModel(
                path, timestamp, isEncrypted, originalPath, isFromEphemeralMessage,
                args.getString(Mango9NativeAttachment.MIME), args.getString(Mango9NativeAttachment.NAME),
                args.getString(org.linphone.mango9.Mango9NativeFileTransfers.MESSAGE), nativeLease.retainFile()
            )
        }

        val conversationId = args.getString("conversationId").orEmpty()
        Log.i(
            "$TAG Looking up for conversation with conversation ID [$conversationId] trying to display file [$path]"
        )
        if (conversationId.isNotEmpty()) viewModel.findChatRoom(null, conversationId)

        viewModel.mediaList.observe(this) {
            updateMediaList(path, it)
        }

        binding.setBackClickListener {
            finish()
        }

        binding.setShareClickListener {
            shareFile()
        }

        binding.setExportClickListener {
            exportFile()
        }
    }

    override fun onDestroy() {
        if (::viewPager.isInitialized) {
            viewPager.unregisterOnPageChangeCallback(pageListener)
        }

        super.onDestroy()
    }

    private fun updateMediaList(path: String, list: List<FileModel>) {
        val count = list.size
        Log.i("$TAG Found [$count] media for conversation")

        val targetPath = adapter.pathAt(viewPager.currentItem) ?: viewModel.selectedFilePath() ?: path
        adapter.submitList(list)
        var index = list.indexOfFirst { model ->
            model.path == targetPath
        }

        val position = if (index == -1) {
            Log.e(
                "$TAG File [$path] not found, using most recent one instead!"
            )
            val message = getString(R.string.conversation_media_not_found_toast)
            val icon = R.drawable.warning_circle
            showRedToast(message, icon)

            0
        } else {
            index
        }

        viewPager.setCurrentItem(position, false)
        viewPager.offscreenPageLimit = 1
        // A replacement at the same index need not dispatch onPageSelected.
        pageListener.onPageSelected(position)
    }

    private fun exportFile() {
        if (!isOwnedMediaCurrent()) return
        val list = viewModel.mediaList.value.orEmpty()
        val currentItem = binding.mediaViewPager.currentItem
        val model = if (currentItem >= 0 && currentItem < list.size) list[currentItem] else null
        if (model != null) {
            val filePath = model.path
            lifecycleScope.launch {
                val exported = withContext(Dispatchers.IO) {
                    if (isOwnedMediaCurrent()) FileUtils.addContentToMediaStore(filePath).isNotEmpty() else null
                }
                if (!isOwnedMediaCurrent() || exported == null) return@launch
                if (exported) {
                    showGreenToast(AppUtils.getString(R.string.file_successfully_exported_to_media_store_toast), R.drawable.check)
                } else {
                    showRedToast(AppUtils.getString(R.string.export_file_to_media_store_error_toast), R.drawable.warning_circle)
                }
            }
        } else {
            Log.e(
                "$TAG Failed to get FileModel at index [$currentItem], only [${list.size}] items in list"
            )
        }
    }

    private fun shareFile() {
        if (!isOwnedMediaCurrent()) return
        val list = viewModel.mediaList.value.orEmpty()
        val currentItem = binding.mediaViewPager.currentItem
        val model = if (currentItem >= 0 && currentItem < list.size) list[currentItem] else null
        if (model != null) {
            lifecycleScope.launch {
                val filePath = FileUtils.getProperFilePath(model.path)
                val copy = FileUtils.getFilePath(
                    baseContext,
                    filePath.toUri(),
                    overrideExisting = true,
                    copyToCache = true
                )
                if (!copy.isNullOrEmpty() && isOwnedMediaCurrent()) {
                    val publicUri = FileProvider.getUriForFile(
                        baseContext,
                        getString(R.string.file_provider),
                        File(copy)
                    )
                    Log.i(
                        "$TAG Public URI for file is [$publicUri], starting intent chooser"
                    )

                    val sendIntent: Intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, publicUri)
                        putExtra(Intent.EXTRA_SUBJECT, model.fileName)
                        type = model.mimeTypeString
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    val shareIntent = Intent.createChooser(sendIntent, null)
                    try {
                        startActivity(shareIntent)
                    } catch (anfe: ActivityNotFoundException) {
                        Log.e("$TAG Failed to start intent chooser: $anfe")
                    }
                } else {
                    Log.e(
                        "$TAG Failed to copy file [$filePath] to share!"
                    )
                }
            }
        } else {
            Log.e(
                "$TAG Failed to get FileModel at index [$currentItem], only [${list.size}] items in list"
            )
        }
    }

    private fun isOwnedMediaCurrent(): Boolean =
        !intent.getBooleanExtra(Mango9MediaCache.OWNED_MEDIA, false) ||
            intent.extras?.let {
                Mango9NavigationSession.matching(it, Mango9SessionStore(applicationContext).captureSession())
            } != null
}
