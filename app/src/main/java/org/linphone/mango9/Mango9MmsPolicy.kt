// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

/** Conservative Telnyx MMS payload budgets, leaving room for text and carrier framing.
 * Carrier limits vary; fitting these budgets does not guarantee delivery.
 */
internal object Mango9MmsPolicy {
    const val MAX_ATTACHMENTS = 10
    const val MAX_MEDIA_BYTES = 850_000L
    const val MAX_FILE_BYTES = 550_000L

    fun perAttachmentBudget(count: Int): Long {
        if (count !in 1..MAX_ATTACHMENTS) throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.MMS_TOO_MANY)
        return minOf(MAX_FILE_BYTES, MAX_MEDIA_BYTES / count)
    }

    fun validateSizes(sizes: List<Long>) {
        if (sizes.isEmpty()) return
        perAttachmentBudget(sizes.size)
        if (sizes.any { it <= 0 || it > MAX_FILE_BYTES } || sizes.sum() > MAX_MEDIA_BYTES) {
            throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.MMS_TOO_LARGE)
        }
    }

    // Product quality floor, not a guarantee about every scene or recipient carrier.
    // Budget bits per pixel per frame rather than reducing bitrate indefinitely for long clips.
    const val MAX_VIDEO_DURATION_MILLIS = 20_000L
    const val VIDEO_FRAME_RATE = 24
    private const val AUDIO_BITRATE = 48_000
    private const val MIN_VIDEO_BITRATE = 300_000
    private const val BITS_PER_PIXEL_PER_FRAME = 0.10

    data class VideoSettings(
        val videoBitrate: Int,
        val audioBitrate: Int,
        val minimumVideoBitrate: Int,
        val size: Mango9VideoDimensions.Size,
        val maxDurationSeconds: Int,
    ) {
        fun qualityFailure() = Mango9AttachmentFailure(
            Mango9AttachmentFailure.Reason.MMS_VIDEO_TOO_LONG, maxMmsVideoSeconds = maxDurationSeconds,
        )

        fun lowerBitrateRetry(): VideoSettings? {
            val reduced = maxOf(minimumVideoBitrate, videoBitrate * 2 / 3)
            return if (reduced < videoBitrate) copy(videoBitrate = reduced) else null
        }
    }

    fun videoSettings(durationMillis: Long, byteLimit: Long, width: Int, height: Int, rotation: Int = 0): VideoSettings {
        require(byteLimit in 1..MAX_FILE_BYTES)
        if (durationMillis > MAX_VIDEO_DURATION_MILLIS) {
            throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.MMS_VIDEO_DURATION_LIMIT)
        }
        val size = Mango9VideoDimensions.fit(width, height, rotation, 480, 360)
        val minimum = maxOf(
            MIN_VIDEO_BITRATE,
            kotlin.math.ceil(
            size.width.toDouble() * size.height * VIDEO_FRAME_RATE * BITS_PER_PIXEL_PER_FRAME,
        ).toInt()
        )
        // Reserve 25% for container overhead and encoder variability. Never trim or remove audio.
        val maxDurationMillis = minOf(MAX_VIDEO_DURATION_MILLIS, byteLimit * 6_000 / (minimum + AUDIO_BITRATE))
        val maxSeconds = (maxDurationMillis / 1_000).toInt()
        if (durationMillis <= 0 || durationMillis > maxDurationMillis) {
            throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.MMS_VIDEO_TOO_LONG, maxMmsVideoSeconds = maxSeconds)
        }
        val availableVideoBitrate = byteLimit * 6_000 / durationMillis - AUDIO_BITRATE
        return VideoSettings(availableVideoBitrate.coerceAtMost(1_500_000).toInt(), AUDIO_BITRATE, minimum, size, maxSeconds)
    }
}
