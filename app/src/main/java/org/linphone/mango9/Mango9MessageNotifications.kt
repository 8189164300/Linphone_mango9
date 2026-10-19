// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.app.NotificationManager
import android.content.Context
import android.app.Notification
import android.os.Bundle
import java.util.UUID

/** Separate from SDK call notifications, including across Core/process restart. */
internal object Mango9MessageNotifications {
    private const val INSTANCE = "mango9.notification_instance"
    private val published = LinkedHashMap<Pair<String, Int>, String>()

    fun tag(login: String) = "mango9.message.$login"

    fun instanceExtras() = Bundle().apply { putString(INSTANCE, UUID.randomUUID().toString()) }

    @Synchronized fun publish(context: Context, login: String, id: Int, notification: Notification) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val instance = requireNotNull(notification.extras.getString(INSTANCE))
        context.getSystemService(NotificationManager::class.java).notify(tag(login), id, notification)
        published[login to id] = instance
        // Only short-lived confirmation timers use this ledger. Missing entries fail closed.
        while (published.size > 256) published.remove(published.keys.first())
    }

    /** Caller serializes this check/cancel with publication through withNotificationLogin. */
    @Synchronized fun cancelIfUnchanged(context: Context, login: String, id: Int, sent: Notification) {
        val expected = sent.extras.getString(INSTANCE) ?: return
        // NotificationManager publication is queued: activeNotifications alone can still return
        // the OLD notice immediately after a new one was enqueued. Check our synchronous ledger.
        if (published[login to id] != expected) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val current = manager.activeNotifications.firstOrNull { it.tag == tag(login) && it.id == id }
        if (current?.notification?.extras?.getString(INSTANCE) == expected) manager.cancel(tag(login), id)
        published.remove(login to id)
    }

    @Synchronized fun cancelLogin(context: Context, login: String) {
        val queuedIds = published.keys.filter { it.first == login }.map { it.second }
        published.keys.removeAll { it.first == login }
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching {
            val activeIds = manager.activeNotifications.filter { it.tag == tag(login) }.map { it.id }
            (queuedIds + activeIds).distinct().forEach { manager.cancel(tag(login), it) }
        }
    }
}
