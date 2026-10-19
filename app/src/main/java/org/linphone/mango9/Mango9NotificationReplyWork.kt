// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** A broadcast reply has one bounded attempt; uncertain delivery is never automatically replayed. */
internal suspend fun runMango9NotificationReply(
    timeoutMillis: Long = 8_000,
    finish: () -> Unit,
    publish: (Mango9SmsReplyState) -> Unit,
    onFailure: (Exception) -> Unit,
    send: suspend () -> Boolean?,
) {
    fun publishSafely(state: Mango9SmsReplyState) {
        try {
            publish(state)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(error)
        }
    }
    try {
        val sent = withTimeout(timeoutMillis) { send() }
        if (sent != null) publishSafely(if (sent) Mango9SmsReplyState.Sent else Mango9SmsReplyState.Failed)
    } catch (_: TimeoutCancellationException) {
        publishSafely(Mango9SmsReplyState.CheckDelivery)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
        publishSafely(Mango9SmsReplyState.Failed)
    } finally {
        finish()
    }
}
