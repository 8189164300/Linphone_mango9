/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Locally encoded media: no downloads, microphone, camera or personal media. */
internal object Mango9MediaTestFixtures {
    /** Await the production owner's asynchronous unlink before walking the synthetic test tree. */
    suspend fun removeReleasedAttachments(root: File) = withContext(NonCancellable + Dispatchers.IO) {
        require(listOf("outgoing-regression-", "attachment-types-regression-", "attachment-directory-regression-").any(root.name::startsWith))
        val prepared = File(root, "prepared")
        withTimeout(8000) {
            while (true) {
                val entries = prepared.listFiles()
                check(entries != null || !prepared.exists()) { "Cannot inspect fixture snapshots" }
                if (entries.orEmpty().none { it.isDirectory }) break
                delay(10)
            }
        }
        check(root.deleteRecursively() && !root.exists()) { "Fixture directory was not removed" }
    }

    fun createAudio(file: File, webm: Boolean = false) {
        val sampleRate = if (webm) 48_000 else 44_100
        val sampleCount = sampleRate * 4
        val mime = if (webm) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
            if (!webm) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
        }
        val encoder = MediaCodec.createEncoderByType(mime)
        var muxer: MediaMuxer? = null
        var encoderStarted = false
        var muxerStarted = false
        try {
            val output =
                MediaMuxer(file.path, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true
            val info = MediaCodec.BufferInfo()
            var samples = 0
            var track = -1
            var inputEnded = false
            var outputEnded = false
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Audio fixture encoding timed out" }
                if (!inputEnded) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val timestamp = samples * 1_000_000L / sampleRate
                        val buffer = requireNotNull(encoder.getInputBuffer(index))
                        buffer.clear()
                        val count = minOf(sampleCount - samples, buffer.remaining() / 2)
                        if (count == 0) {
                            encoder.queueInputBuffer(index, 0, 0, timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            repeat(count) { buffer.putShort(0) }
                            encoder.queueInputBuffer(index, 0, count * 2, timestamp, 0)
                            samples += count
                        }
                    }
                }
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxerStarted)
                    track = output.addTrack(encoder.outputFormat)
                    output.start()
                    muxerStarted = true
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            val buffer = requireNotNull(encoder.getOutputBuffer(index))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            output.writeSampleData(track, buffer, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            check(samples == sampleCount && muxerStarted)
        } finally {
            try {
                if (encoderStarted) encoder.stop()
            } finally {
                encoder.release()
                try {
                    if (muxerStarted) muxer?.stop()
                } finally {
                    muxer?.release()
                }
            }
        }
        check(file.length() > 0)
    }

    fun createVideo(file: File, webm: Boolean = false) {
        val width = 128
        val height = 96
        val frameRate = 12
        val frameCount = frameRate * 8
        val mime = if (webm) MediaFormat.MIMETYPE_VIDEO_VP8 else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        // Fixture pixels are filled through CPU Image planes. Some vendor AVC encoders
        // advertise flexible YUV but fail when started with these small test frames.
        // Use the platform software codec for fixture creation; the real exporter below
        // still exercises the device-selected production encoder.
        val fixtureEncoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && mime in it.supportedTypes &&
                (it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google."))
        }
        val encoder = fixtureEncoder?.let { MediaCodec.createByCodecName(it.name) }
            ?: MediaCodec.createEncoderByType(mime)
        var muxer: MediaMuxer? = null
        var encoderStarted = false
        var muxerStarted = false
        try {
            val output =
                MediaMuxer(file.path, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true
            val info = MediaCodec.BufferInfo()
            var frame = 0
            var track = -1
            var inputEnded = false
            var outputEnded = false
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Video fixture encoding timed out" }
                if (!inputEnded) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val timestamp = frame * 1_000_000L / frameRate
                        if (frame == frameCount) {
                            encoder.queueInputBuffer(index, 0, 0, timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val image = requireNotNull(encoder.getInputImage(index))
                            image.planes.forEachIndexed { planeIndex, plane ->
                                val planeWidth = if (planeIndex == 0) width else width / 2
                                val planeHeight = if (planeIndex == 0) height else height / 2
                                val value = when (planeIndex) { 0 -> 120; 1 -> 90; else -> 200 }.toByte()
                                val buffer = plane.buffer
                                val offset = buffer.position()
                                repeat(planeHeight) { row ->
                                    repeat(planeWidth) { column ->
                                        buffer.put(offset + row * plane.rowStride + column * plane.pixelStride, value)
                                    }
                                }
                            }
                            // queueInputBuffer invalidates this input image and returns buffer ownership.
                            encoder.queueInputBuffer(index, 0, width * height * 3 / 2, timestamp, 0)
                            frame++
                        }
                    }
                }
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxerStarted)
                    track = output.addTrack(encoder.outputFormat)
                    output.start()
                    muxerStarted = true
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            val buffer = requireNotNull(encoder.getOutputBuffer(index))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            output.writeSampleData(track, buffer, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            check(muxerStarted && file.length() > 0)
        } finally {
            try {
                if (encoderStarted) encoder.stop()
            } finally {
                encoder.release()
                try {
                    if (muxerStarted) muxer?.stop()
                } finally {
                    muxer?.release()
                }
            }
        }
    }
}
