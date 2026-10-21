/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.core.tools.AndroidPlatformHelper
import org.linphone.core.tools.Log
import org.linphone.core.tools.firebase.FirebaseMessaging
import org.linphone.ui.main.MainActivity

/** Receives the same FCM token for SIP calling and Mango9 account-scoped messaging. */
class Mango9FirebaseMessagingService : FirebaseMessaging() {
    override fun onNewToken(token: String) {
        val stored = Mango9MessagePushTokenStore(applicationContext).save(token)
        if (!stored) {
            Log.e("[Mango9 Message Push] Firebase supplied an invalid token")
            return
        }
        Mango9FirebaseTokenBridge.forwardToLinphone(token)
        Mango9MessagePushSync.get(applicationContext).refreshAsync()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val push = Mango9MessagePush.parse(message.data)
        if (push == null) {
            // Malformed/unknown Mango9 message payloads must not become VoIP wakes.
            if (message.data.containsKey("mango9") || message.data.containsKey("mango9_event") || message.data.containsKey("event")) return
            super.onMessageReceived(message)
            return
        }
        Mango9MessagePushCoordinator.receive(applicationContext, push)
    }
}

object Mango9FirebaseTokenSync {
    private const val TAG = "[Mango9 Message Push]"
    private val retryDelaysMs = longArrayOf(5_000L, 15_000L, 30_000L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refresh(context: Context) {
        request(context.applicationContext, attempt = 0)
    }

    private fun request(appContext: Context, attempt: Int) {
        scope.launch {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        val retryDelayMs = retryDelaysMs.getOrNull(attempt)
                        if (retryDelayMs != null) {
                            scope.launch {
                                delay(retryDelayMs)
                                request(appContext, attempt + 1)
                            }
                        } else {
                            Log.e("$TAG Failed to obtain the Firebase registration token after retries")
                        }
                        return@addOnCompleteListener
                    }
                    val token = task.result
                    if (!Mango9MessagePushTokenStore(appContext).save(token)) {
                        Log.e("$TAG Firebase supplied an invalid token")
                        return@addOnCompleteListener
                    }
                    Mango9FirebaseTokenBridge.forwardToLinphone(token)
                    Mango9MessagePushSync.get(appContext).refreshAsync()
                }
        }
    }
}

/** Mirrors the iOS token hand-off by changing Linphone push state only on its core thread. */
private object Mango9FirebaseTokenBridge {
    fun forwardToLinphone(token: String) {
        if (!AndroidPlatformHelper.isReady()) return
        AndroidPlatformHelper.instance().dispatchOnCoreThread {
            if (AndroidPlatformHelper.isReady()) {
                AndroidPlatformHelper.instance().setPushToken(token)
            }
        }
    }
}

object Mango9MessagePushCoordinator {
    const val ACTION_OPEN = "com.mango9.phone.action.OPEN_MANGO9_MESSAGE_PUSH"
    const val EXTRA_PUSH = "com.mango9.phone.extra.MANGO9_MESSAGE_PUSH"
    private const val TAG = "[Mango9 Message Push]"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextNotificationId = AtomicInteger(3900)

    fun receive(context: Context, push: Mango9MessagePush) {
        val appContext = context.applicationContext
        val sessions = Mango9SessionStore(appContext)
        val login = sessions.resolvePushLogin(push) ?: run {
            Log.w("$TAG Ignoring a push for an account not stored on this device")
            return
        }
        val identity = login.session.sipIdentity ?: return
        val resolved = sessions.bindNotification(push, login) ?: return
        if (sessions.isActive(identity)) {
            scope.launch {
                if (sessions.currentLogin(login) == null || !sessions.isActive(identity)) return@launch
                val store = Mango9ChatStore.get(appContext)
                when (resolved.target) {
                    is Mango9MessagePushTarget.Chat -> store.refreshDirectory()
                    is Mango9MessagePushTarget.Sms -> store.refreshSmsDirectory()
                    else -> Unit
                }
            }
        }
        if (sessions.currentLogin(login) != null) showNotification(appContext, resolved)
    }

    suspend fun activateForOpen(
        context: Context,
        push: Mango9MessagePush,
        ticket: Mango9OpenRequestGate.Ticket,
    ): Mango9SessionLease? {
        val sessions = Mango9SessionStore(context.applicationContext)
        val login = sessions.resolveNotificationLogin(push) ?: return null
        return Mango9AccountContextSync.activateStoredAccount(context, login, ticket)
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(context: Context, push: Mango9MessagePush) {
        val sms = push.target as? Mango9MessagePushTarget.Sms
        if (sms != null && Mango9ChatModerationStore(context).isSmsMuted(sms.phone, push.sipIdentity)) {
            Log.i("$TAG Suppressing the alert for a muted SMS conversation")
            return
        }
        ensureChannel(context)
        val notificationId = nextNotificationId.getAndIncrement()
        val notification = buildNotification(context, push, notificationId)
        publishNotification(context, push, notificationId, notification)
    }

    @SuppressLint("MissingPermission")
    internal fun updateSmsReplyNotification(
        context: Context,
        push: Mango9MessagePush,
        notificationId: Int,
        state: Mango9SmsReplyState,
    ) {
        if (push.target !is Mango9MessagePushTarget.Sms || Mango9SessionStore(context).resolveNotificationLogin(push) == null) return
        ensureChannel(context)
        val body = context.getString(
            when (state) {
                Mango9SmsReplyState.Sending -> R.string.mango9_push_sms_reply_sending
                Mango9SmsReplyState.Sent -> R.string.mango9_push_sms_reply_sent
                Mango9SmsReplyState.Failed -> R.string.mango9_push_sms_reply_failed
                Mango9SmsReplyState.CheckDelivery -> R.string.mango9_push_sms_reply_check
            },
        )
        val notification = buildNotification(
            context,
            push,
            notificationId,
            bodyOverride = body,
            includeSmsReply = state == Mango9SmsReplyState.Failed,
        )
        publishNotification(context, push, notificationId, notification)
        if (state == Mango9SmsReplyState.Sent) {
            scope.launch {
                delay(REPLY_CONFIRMATION_MS)
                runCatching {
                    Mango9SessionStore(context).withNotificationLogin(push) {
                        Mango9MessageNotifications.cancelIfUnchanged(context, requireNotNull(push.notificationLogin), notificationId, notification)
                    }
                }
            }
        }
    }

    private fun buildNotification(
        context: Context,
        push: Mango9MessagePush,
        notificationId: Int,
        bodyOverride: String? = null,
        includeSmsReply: Boolean = true,
    ): Notification {
        val serialized = push.toJson()
        val requestCode = serialized.hashCode() and Int.MAX_VALUE
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            putExtra(EXTRA_PUSH, serialized)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, body) = when (val target = push.target) {
            is Mango9MessagePushTarget.Chat -> (push.previewTitle ?: target.name) to (push.previewBody ?: context.getString(R.string.mango9_push_team_chat))
            is Mango9MessagePushTarget.Sms -> target.name to context.getString(R.string.mango9_push_sms)
            is Mango9MessagePushTarget.Lead -> (push.previewTitle ?: context.getString(R.string.mango9_crm_title)) to
                (push.previewBody ?: context.getString(R.string.mango9_push_lead))
            Mango9MessagePushTarget.Leads -> (push.previewTitle ?: "Leads imported") to (push.previewBody ?: "Open your leads to view the completed import.")
            is Mango9MessagePushTarget.Appointment -> (push.previewTitle ?: "New appointment") to (push.previewBody ?: "Open Events to view the booking.")
            is Mango9MessagePushTarget.Social -> (push.previewTitle ?: "Social message") to (push.previewBody ?: "You have a new direct message.")
            is Mango9MessagePushTarget.LiveChat -> (push.previewTitle ?: "Live Chat") to (push.previewBody ?: "You have a new website message.")
        }
        val notification = NotificationCompat.Builder(
            context,
            context.getString(R.string.notification_channel_chat_id),
        )
            .addExtras(Mango9MessageNotifications.instanceExtras())
            .setSmallIcon(R.drawable.chat_teardrop_text)
            .setContentTitle(title)
            .setContentText(bodyOverride ?: body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(bodyOverride != null)
        val sms = push.target as? Mango9MessagePushTarget.Sms
        if (
            includeSmsReply &&
            sms != null &&
            Mango9SessionStore(context).isActive(push.sipIdentity)
        ) {
            notification.addAction(Mango9MessageReplyAction.create(context, push, notificationId))
        }
        return notification.build()
    }

    @SuppressLint("MissingPermission")
    private fun publishNotification(context: Context, push: Mango9MessagePush, notificationId: Int, notification: Notification) {
        runCatching {
            Mango9SessionStore(context).withNotificationLogin(push) {
                Mango9MessageNotifications.publish(context, requireNotNull(push.notificationLogin), notificationId, notification)
            }
        }.onFailure { Log.e("$TAG Failed to publish a message notification: $it") }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = context.getString(R.string.notification_channel_chat_id)
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                id,
                context.getString(R.string.notification_channel_chat_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private const val REPLY_CONFIRMATION_MS = 1_500L
}
