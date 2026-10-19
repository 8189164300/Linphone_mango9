// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** MP4/H.264/AAC, SDR, bounded resolution. Audio-only sources produce AAC/M4A without a video track. */
@OptIn(UnstableApi::class)
internal class Mango9MediaExporter(private val context: Context) {
    suspend fun exportVideo(source: File, output: File) = export(source, output, video = true)

    suspend fun exportAudio(source: File, output: File) = export(source, output, video = false)

    suspend fun exportMmsVideo(source: File, output: File, byteLimit: Long) {
        val settings = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.path)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (width <= 0 || height <= 0) throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.CONVERSION)
                Mango9MmsPolicy.videoSettings(duration, byteLimit, width, height, rotation)
            } finally { retriever.release() }
        }
        export(source, output, video = true, mms = settings)
        if (withContext(Dispatchers.IO) { output.length() > byteLimit }) {
            // Hardware bitrate requests are approximate, but retries must never cross the quality floor.
            val retry = settings.lowerBitrateRetry() ?: throw settings.qualityFailure()
            withContext(Dispatchers.IO) { check(output.delete()) }
            export(source, output, video = true, mms = retry)
            if (withContext(Dispatchers.IO) { output.length() > byteLimit }) throw settings.qualityFailure()
        }
    }

    private suspend fun export(source: File, output: File, video: Boolean, mms: Mango9MmsPolicy.VideoSettings? = null) = exports.withPermit {
        val targetSize = if (video) {
            withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.absolutePath)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (width <= 0 || height <= 0) throw IOException("This video cannot be prepared.")
                mms?.size ?: Mango9VideoDimensions.fit(width, height, rotation)
            } finally { retriever.release() }
        }
        } else {
            null
        }
        withContext(Dispatchers.Main.immediate) {
            val complete = CompletableDeferred<Unit>()
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setEffects(
                    Effects(
                        emptyList(),
                        targetSize?.let {
                    listOf(Presentation.createForWidthAndHeight(it.width, it.height, Presentation.LAYOUT_SCALE_TO_FIT)) +
                        if (mms != null) listOf(FrameDropEffect.createDefaultFrameDropEffect(Mango9MmsPolicy.VIDEO_FRAME_RATE.toFloat())) else emptyList()
                } ?: emptyList()
                    )
                )
                .apply { if (mms != null) setFrameRate(Mango9MmsPolicy.VIDEO_FRAME_RATE) }
                .build()
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
            val encoder = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(mms?.videoBitrate ?: 1_500_000)
                    .apply { if (mms != null) setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) }.build()
                )
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(mms?.audioBitrate ?: 64_000).build())
                .setEnableFormatFallback(true).build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .setMuxerFactory(InAppMp4Muxer.Factory().setAttemptStreamableOutputEnabled(true))
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { complete.complete(Unit) }

                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                        complete.completeExceptionally(Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.CONVERSION, exception))
                    }
                }).build()
            try {
                transformer.start(composition, output.absolutePath)
                withTimeout(110_000) {
                    while (!complete.isCompleted) {
                        if (withContext(Dispatchers.IO) { output.length() > Mango9AttachmentPreparer.MAX_BYTES }) {
                            throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.TOO_LARGE)
                        }
                        delay(200)
                    }
                    complete.await()
                }
            } finally { transformer.cancel() }
        }
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                val compatible = if (video) {
                    formats.any { it == "video/avc" }
                } else {
                    formats.count { it == "audio/mp4a-latm" } == 1 && formats.none { it?.startsWith("video/") == true }
                }
                if (!compatible || formats.any { it?.startsWith("audio/") == true && it != "audio/mp4a-latm" }) {
                    throw IOException("This media file is not compatible with messaging.")
                }
            } finally { extractor.release() }
        }
    }

    companion object { private val exports = Semaphore(1) }
}
