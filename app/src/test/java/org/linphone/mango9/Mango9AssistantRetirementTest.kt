package org.linphone.mango9
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class Mango9AssistantRetirementTest {
 @Test fun oldAssistantPushAndSavedIntentsCannotOpenAChat() {
  val id = "11111111-1111-4111-8111-111111111111"
  val data =
      mapOf("event" to "supervisor.reply", "crm_id" to "fixture", "sip_identity" to "sip:100@voice.invalid", "recipient_user_id" to "7", "conversation_id" to id)
  assertNull(Mango9MessagePush.parse(data))
  val old = JSONObject().put("type", "assistant").put("conversation_id", id).put("crm_id", "fixture").put("sip_identity", "sip:100@voice.invalid").put("recipient_user_id", "7")
  assertNull(Mango9MessagePush.fromJson(old.toString()))
 }
}
