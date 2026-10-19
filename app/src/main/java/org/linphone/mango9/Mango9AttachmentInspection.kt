// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only called off-main for an owned audio/video snapshot, never for photos or documents. */
internal object Mango9AttachmentInspection {
    enum class Preparation { Video, Audio, ReadyAudio }

    suspend fun inspect(file: File): Preparation {
        val extractor = MediaExtractor()
        try {
            currentCoroutineContext().ensureActive()
            extractor.setDataSource(file.path)
            if (extractor.trackCount !in 1..32) throw IOException("This media file has no usable tracks.")
            val audio = mutableListOf<String>()
            var video = false
            for (index in 0 until extractor.trackCount) {
                currentCoroutineContext().ensureActive()
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("audio/") && !mime.startsWith("video/")) continue
                extractor.selectTrack(index)
                try {
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    if (extractor.sampleSize !in 1..Mango9AttachmentPreparer.MAX_BYTES ||
                        extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0
                    ) {
                        throw IOException("This media file is empty or protected.")
                    }
                } finally { extractor.unselectTrack(index) }
                if (mime.startsWith("video/")) video = true else audio.add(mime)
            }
            if (video) return Preparation.Video
            if (audio.isEmpty()) throw IOException("This media file has no usable audio or video.")
            if (Mango9MediaType.canPreserveMp4Audio(audio) && isMp4(file)) return Preparation.ReadyAudio
            // Multiple language/program tracks cannot be silently discarded by the audio exporter.
            if (audio.size > 1) throw IOException("Please select a recording with one audio track.")
            return Preparation.Audio
        } finally { extractor.release() }
    }

    private fun isMp4(file: File): Boolean {
        if (file.length() < 16) return false
        return DataInputStream(file.inputStream()).use { input ->
            val size = input.readInt()
            val type = input.readInt()
            val brand = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            // Do not relabel QuickTime/3GPP or image ISOBMFF containers as M4A.
            size in 16..file.length() && type == 0x66747970 &&
                (brand in setOf("isom", "mp41", "mp42", "M4A ", "M4B ") || brand.matches(Regex("iso[2-9]")))
        }
    }
}
