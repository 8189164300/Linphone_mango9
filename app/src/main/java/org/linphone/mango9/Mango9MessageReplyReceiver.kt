/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.core.tools.Log

internal object Mango9SmsNotificationReplyPolicy {
    fun text(value: CharSequence?): String? = value?.toString()?.trim()?.takeIf(String::isNotEmpty)

    fun canReply(activeIdentity: String?, pushedIdentity: String?): Boolean {
        val active = Mango9SessionStore.normalizedIdentity(activeIdentity)
        return active != null && active == Mango9SessionStore.normalizedIdentity(pushedIdentity)
    }
}

internal object Mango9MessageReplyAction {
    const val ACTION_REPLY = "com.mango9.phone.action.REPLY_MANGO9_SMS"
    const val EXTRA_NOTIFICATION_ID = "com.mango9.phone.extra.MANGO9_NOTIFICATION_ID"
    const val KEY_TEXT_REPLY = "mango9_sms_text_reply"

    fun create(
        context: Context,
        push: Mango9MessagePush,
        notificationId: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, Mango9MessageReplyReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(Mango9MessagePushCoordinator.EXTRA_PUSH, push.toJson())
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.notification_reply_to_message))
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.paper_plane_right,
            context.getString(R.string.notification_reply_to_message),
            pendingIntent,
        )
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setShowsUserInterface(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    fun replyText(intent: Intent): String? = Mango9SmsNotificationReplyPolicy.text(
        RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT_REPLY),
    )
}

class Mango9MessageReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Mango9MessageReplyAction.ACTION_REPLY) return
        val notificationId = intent.getIntExtra(Mango9MessageReplyAction.EXTRA_NOTIFICATION_ID, -1)
        val push = Mango9MessagePush.fromJson(
            intent.getStringExtra(Mango9MessagePushCoordinator.EXTRA_PUSH),
        )
        val reply = Mango9MessageReplyAction.replyText(intent)
        if (notificationId < 0 || push?.target !is Mango9MessagePushTarget.Sms || reply == null) {
            Log.e("$TAG Ignoring an invalid Mango9 SMS notification reply")
            return
        }

        val appContext = context.applicationContext
        Mango9MessagePushCoordinator.updateSmsReplyNotification(
            appContext,
            push,
            notificationId,
            Mango9SmsReplyState.Sending,
        )
        val pendingResult = goAsync()
        scope.launch {
            try {
                val sessions = Mango9SessionStore(appContext)
                val sent = if (
                    Mango9SmsNotificationReplyPolicy.canReply(sessions.activeIdentity, push.sipIdentity)
                ) {
                    Mango9ChatStore.get(appContext).sendSmsFromNotification(
                        push.target.phone,
                        reply,
                    )
                } else {
                    Log.w("$TAG Refusing to switch Mango9 accounts while replying from a notification")
                    false
                }
                Mango9MessagePushCoordinator.updateSmsReplyNotification(
                    appContext,
                    push,
                    notificationId,
                    if (sent) Mango9SmsReplyState.Sent else Mango9SmsReplyState.Failed,
                )
            } catch (error: Exception) {
                Log.e("$TAG Failed to send the Mango9 SMS notification reply: $error")
                Mango9MessagePushCoordinator.updateSmsReplyNotification(
                    appContext,
                    push,
                    notificationId,
                    Mango9SmsReplyState.Failed,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "[Mango9 SMS Reply]"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

internal enum class Mango9SmsReplyState {
    Sending,
    Sent,
    Failed,
}
