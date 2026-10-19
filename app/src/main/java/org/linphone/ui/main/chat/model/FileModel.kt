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
package org.linphone.ui.main.chat.model

import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.ThumbnailUtils
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log
import org.linphone.utils.FileUtils
import org.linphone.utils.TimestampUtils
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.io.Closeable
import org.linphone.mango9.Mango9NativeAttachment
import org.linphone.mango9.Mango9NativeFileLease
import org.linphone.mango9.Mango9NativeFileAsset
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import android.os.Handler
import android.os.Looper

class FileModel
    @AnyThread
    constructor(
    path: String,
    val fileName: String,
    val fileSize: Long,
    val fileCreationTimestamp: Long,
    val isEncrypted: Boolean,
    val originalPath: String,
    val isFromEphemeralMessage: Boolean,
    val isWaitingToBeDownloaded: Boolean = false,
    val flexboxLayoutWrapBefore: Boolean = false,
    private val loadMetadata: Boolean = true,
    private val mimeTypeOverride: String? = null,
    val relatedMessageId: String? = null,
    retainedFile: Mango9NativeFileLease? = null,
    ownsEncryptedFile: Boolean = true,
    private val presentationScoped: Boolean = false,
    private val deferredFile: (suspend () -> Mango9NativeFileAsset)? = null,
    private val onLoadFailed: (() -> Unit)? = null,
    private val onClicked: ((model: FileModel) -> Unit)? = null
) {
    companion object {
        private const val TAG = "[File Model]"
        private val cleanup = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val decoders = Semaphore(2)
        private val main = Handler(Looper.getMainLooper())
    }

    private class MetadataLoad {
        @Volatile var asset: Mango9NativeFileAsset? = null

        @Volatile var preview: File? = null

        @Volatile var ready = false
        lateinit var job: Job
    }

    private val initialPath = path

    @Volatile private var activeLoad: MetadataLoad? = null
    val path: String get() = activeLoad?.asset?.path ?: initialPath
    val preparing = MutableLiveData(false)
    val fileUnavailable = MutableLiveData(false)
    private var presentations = 0
    private var pendingClick = false

    val formattedFileSize = MutableLiveData<String>()

    val transferProgress = MutableLiveData<Int>()

    val transferProgressLabel = MutableLiveData<String>()

    val mediaPreview = MutableLiveData<String>()

    val mediaPreviewAvailable = MutableLiveData<Boolean>()

    val mimeType: FileUtils.MimeType

    val mimeTypeString: String

    val isMedia: Boolean

    val isImage: Boolean

    val isVideoPreview: Boolean

    val audioVideoDuration = MutableLiveData<String>()

    val isPdf: Boolean

    val isAudio: Boolean

    val month = TimestampUtils.month(fileCreationTimestamp)

    val dateTime = TimestampUtils.toString(
        fileCreationTimestamp,
        shortDate = false,
        hideYear = false
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal val nativeOwner = org.linphone.mango9.Mango9SessionStore(coreContext.context).selectionStamp()
    private val fileLease = retainedFile ?: if (isEncrypted && ownsEncryptedFile && !isWaitingToBeDownloaded) {
        Mango9NativeFileLease { cleanup.launch { FileUtils.deleteFile(path) } }
    } else {
        null
    }

    @Volatile private var destroyed = false

    @Volatile internal var metadataRequested = false
        private set

    init {
        updateTransferProgress(-1)
        computeFileSize(fileSize)

        if (!isWaitingToBeDownloaded) {
            val extension = FileUtils.getExtensionFromFileName(path)
            val mime = Mango9NativeAttachment.mimeType(path, fileName, mimeTypeOverride)
            isPdf = mime == "application/pdf"

            mimeTypeString = mime

            mimeType = FileUtils.getMimeType(mime)
            isImage = mimeType == FileUtils.MimeType.Image
            isVideoPreview = mimeType == FileUtils.MimeType.Video
            isAudio = mimeType == FileUtils.MimeType.Audio

            if (isImage && !presentationScoped && deferredFile == null) {
                mediaPreview.postValue(path)
                mediaPreviewAvailable.postValue(true)
            }
            Log.d(
                "$TAG File has already been downloaded, extension is [$extension], MIME is [$mime]"
            )
        } else {
            mimeType = FileUtils.MimeType.Unknown
            mimeTypeString = "application/octet-stream"
            isPdf = false
            isImage = false
            isVideoPreview = false
            isAudio = false
        }

        isMedia = isVideoPreview || isImage
        if (loadMetadata && !isWaitingToBeDownloaded) requestMetadata()
    }

    @AnyThread
    @Synchronized fun destroy() {
        if (destroyed) return
        destroyed = true
        releaseMetadata()
        // A decoder already inside a blocking platform call must finish before unlinking.
        scope.coroutineContext[Job]!!.invokeOnCompletion {
            fileLease?.close()
        }
        scope.cancel()
    }

    @Synchronized fun retainFile(): Mango9NativeFileLease? {
        check(!destroyed)
        if (deferredFile != null) return checkNotNull(activeLoad?.asset) { "File is not ready" }.lease.retain()
        return fileLease?.retain()
    }

    /** A recycled/offscreen row has no right to keep a decode or plaintext export alive. */
    @Synchronized fun retainPresentation(): Closeable {
        if (destroyed) return Closeable {}
        presentations++
        requestMetadata()
        val released = AtomicBoolean(false)
        return Closeable {
            if (released.compareAndSet(false, true)) {
                synchronized(this) {
                    presentations--
                    if (presentations == 0 && presentationScoped) releaseMetadata()
                }
            }
        }
    }

    @AnyThread
    fun computeFileSize(fileSize: Long) {
        formattedFileSize.postValue(FileUtils.bytesToDisplayableSize(fileSize))
    }

    @AnyThread
    fun updateTransferProgress(percent: Int) {
        transferProgress.postValue(percent)
        if (percent !in 0..100) {
            transferProgressLabel.postValue("")
        } else {
            transferProgressLabel.postValue("$percent%")
        }
    }

    @UiThread
    @Synchronized fun onClick() {
        if (destroyed || !current()) return
        if (deferredFile != null) {
            if (presentations == 0) return
            if (activeLoad?.ready != true) {
                pendingClick = true
                requestMetadata()
                return
            }
        }
        onClicked?.invoke(this)
    }

    @AnyThread
    suspend fun deleteFile() {
        Log.i("$TAG Deleting file [$path]")
        FileUtils.deleteFile(path)
    }

    /** Visible rows opt in. Each presentation generation owns its export and preview. */
    @AnyThread
    @Synchronized fun requestMetadata() {
        if (destroyed || metadataRequested || isWaitingToBeDownloaded) return
        metadataRequested = true
        val load = MetadataLoad()
        activeLoad = load
        publish(load) { preparing.value = deferredFile != null; fileUnavailable.value = false }
        load.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                load.asset = deferredFile?.invoke()
                ensureActive()
                if (!current(load)) return@launch
                val resourcePath = load.asset?.path ?: initialPath
                publish(load) {
                    load.ready = true; preparing.value = false
                    if (isImage) { mediaPreview.value = resourcePath; mediaPreviewAvailable.value = true }
                    if (pendingClick) { pendingClick = false; onClicked?.invoke(this@FileModel) }
                }
                if (!isPdf && !isVideoPreview && !isAudio) return@launch
                decoders.withPermit {
                    ensureActive()
                    if (!current(load)) return@withPermit
                    val bitmap = if (isPdf) {
                        pdfPreview(resourcePath)
                    } else if (isVideoPreview) {
                        ThumbnailUtils.createVideoThumbnail(resourcePath, MediaStore.Images.Thumbnails.MINI_KIND)
                    } else {
                        null
                    }
                    if (bitmap != null) {
                        try {
                            ensureActive()
                            val file = File.createTempFile("mango9-native-preview-", ".jpg", coreContext.context.cacheDir)
                            load.preview = file
                            val previewPath = FileUtils.storeBitmap(bitmap, file)
                            ensureActive()
                            publish(load) {
                                mediaPreview.value = previewPath
                                mediaPreviewAvailable.value = previewPath.isNotBlank()
                            }
                        } finally { bitmap.recycle() }
                    }
                    ensureActive()
                    if ((isVideoPreview || isAudio) && current(load)) {
                        // Duration is optional; a retriever failure must not delete a valid thumbnail.
                        try {
                            val duration = readDuration(resourcePath)
                            ensureActive()
                            publish(load) { audioVideoDuration.value = duration }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { /* Keep the file and any rendered preview openable. */ }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                if (deferredFile != null && load.asset == null) {
                    publish(load) {
                        val notify = pendingClick
                        releaseMetadata()
                        preparing.value = false; fileUnavailable.value = true
                        if (notify) onLoadFailed?.invoke()
                    }
                }
                // A malformed preview must not invalidate an otherwise openable file.
                load.preview?.let { FileUtils.deleteFile(it.path); load.preview = null }
            }
        }
        load.job.start()
    }

    @Synchronized private fun releaseMetadata() {
        val load = activeLoad
        activeLoad = null; metadataRequested = false; pendingClick = false
        main.post {
            synchronized(this) {
                if (activeLoad == null) {
                    preparing.value = false; mediaPreview.value = ""
                    mediaPreviewAvailable.value = false; audioVideoDuration.value = ""
                }
            }
        }
        if (load != null) {
            load.job.invokeOnCompletion {
                load.asset?.close()
                load.preview?.let { file -> cleanup.launch { FileUtils.deleteFile(file.path) } }
            }
            load.job.cancel()
        }
    }

    private fun publish(load: MetadataLoad, action: () -> Unit) {
        main.post { synchronized(this) { if (current(load)) action() } }
    }

    private fun current(load: MetadataLoad) = activeLoad === load && current()

    private fun current() = !destroyed &&
        org.linphone.mango9.Mango9SessionStore(coreContext.context).selectionStamp() == nativeOwner

    private fun pdfPreview(path: String): android.graphics.Bitmap = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(0).use { page ->
                val scale = minOf(1f, 512f / maxOf(page.width, page.height).coerceAtLeast(1))
                val bitmap =
                    createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()))
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(
                        bitmap, null,
                        android.graphics.Matrix().apply {
                        setScale(scale, scale)
                    },
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    bitmap
                } catch (failure: Throwable) { bitmap.recycle(); throw failure }
            }
        }
    }

    private fun readDuration(path: String): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(coreContext.context, path.toUri())
            val milliseconds = retriever.extractMetadata(METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            TimestampUtils.durationToString((milliseconds.coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        } finally { retriever.release() }
    }
}
