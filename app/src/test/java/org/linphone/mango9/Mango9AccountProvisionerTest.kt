/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mango9AccountProvisionerTest {
    @Test
    fun retriesWithoutSipPushOnlyForTheProxyUnsupportedPushResponse() {
        assertTrue(
            Mango9AccountProvisioner.shouldRetryWithoutSipPush(
                555,
                "Push Notification Service Not Supported",
            )
        )
        assertTrue(
            Mango9AccountProvisioner.shouldRetryWithoutSipPush(
                555,
                "push notification service not supported for fcm",
            )
        )

        assertFalse(
            Mango9AccountProvisioner.shouldRetryWithoutSipPush(
                500,
                "Registration Processing Error",
            )
        )
        assertFalse(Mango9AccountProvisioner.shouldRetryWithoutSipPush(403, "Forbidden"))
        assertFalse(Mango9AccountProvisioner.shouldRetryWithoutSipPush(555, null))
    }
}
