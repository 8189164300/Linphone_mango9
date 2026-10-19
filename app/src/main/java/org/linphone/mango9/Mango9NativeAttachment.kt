// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.linphone.core.Content
import org.linphone.utils.FileUtils

/** A transfer's XML envelope is not the MIME type of the file it describes. */
internal fun Content.attachmentMimeType(): String? =
    if (isFileTransfer) null else Mango9NativeAttachment.payloadType("$type/$subtype")

/** Keep the payload type and original name when a local cache path has no useful extension. */
internal object Mango9NativeAttachment {
    const val MIME = "attachmentMimeType"
    const val NAME = "attachmentName"

    fun payloadType(value: String?): String? = Mango9MediaType.explicit(value)?.takeUnless {
        it == "application/vnd.gsma.rcs-ft-http+xml" || it.startsWith("file/")
    }

    fun mimeType(path: String, name: String? = null, declared: String? = null): String {
        payloadType(declared)?.let { return it }
        // Prepared/cache paths may have a corrected extension after conversion;
        // the original display name must not undo that correction.
        for (hint in listOfNotNull(path, name)) {
            val extension = FileUtils.getExtensionFromFileName(hint)
            val known = Mango9MediaType.fromExtension(extension)
            if (known != "application/octet-stream") return known
            payloadType(FileUtils.getMimeTypeFromExtension(extension))?.let { return it }
        }
        return "application/octet-stream"
    }
}
