// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.Closeable

/** One owned decrypted export; presentation and an opened viewer retain independent leases. */
class Mango9NativeFileAsset(val path: String, val lease: Mango9NativeFileLease) : Closeable {
    override fun close() = lease.close()
}
