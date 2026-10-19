// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.IOException

/** Only explicit app-owned messages reach the composer; provider errors may contain private paths. */
internal class Mango9AttachmentFailure(
    val reason: Reason,
    cause: Throwable? = null,
    maxMmsVideoSeconds: Int? = null,
) : IOException(
    if (reason == Reason.MMS_VIDEO_TOO_LONG && maxMmsVideoSeconds != null) {
        if (maxMmsVideoSeconds > 0) {
            "This video would lose too much quality in MMS. Choose a clip of $maxMmsVideoSeconds seconds or less. Sending fewer attachments may allow a longer clip."
        } else {
            "These attachments leave too little room for a clear MMS video. Send fewer attachments or choose a shorter clip."
        }
    } else {
        reason.message
    },
    cause,
) {
    val userMessage: String get() = requireNotNull(message)

    enum class Reason(val message: String) {
        TOO_LARGE("This attachment exceeds the 50 MB limit. Choose a smaller file."),
        MMS_TOO_LARGE("This attachment is too large for SMS/MMS. Choose a smaller file, a shorter video, or send fewer attachments."),
        MMS_VIDEO_DURATION_LIMIT("MMS videos are limited to 20 seconds. Choose a shorter clip."),
        MMS_VIDEO_TOO_LONG("This video would lose too much quality in MMS. Choose a shorter clip."),
        MMS_TOO_MANY("SMS/MMS supports up to 10 attachments. Send fewer attachments."),
        EMPTY("The selected attachment is empty. Choose another file."),
        UNAVAILABLE("The attachment could not be opened. Download it to the phone and select it again."),
        CONVERSION("This media file could not be converted for sending. Please try another file."),
        TIMEOUT("Preparing the attachment took too long. Try a shorter video or select the file again."),
    }

    companion object {
        fun message(error: Exception): String = when (error) {
            is Mango9AttachmentFailure -> error.userMessage
            is SecurityException -> "Access to the attachment was denied. Select it again to allow access."
            else -> "The attachment could not be prepared. Select it again or try another file."
        }
    }
}
