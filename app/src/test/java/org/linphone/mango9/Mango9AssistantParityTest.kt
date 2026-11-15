package org.linphone.mango9
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Mango9AssistantParityTest {
    @Test fun acknowledgedReadsRejectStaleSnapshotsButKeepNewArrivals() {
        val reads = Mango9InboxReadLedger()
        reads.read("chat", "social", 100)
        assertFalse(reads.unread(true, "chat", "social", 99))
        assertFalse(reads.unread(true, "chat", "social", 100))
        assertTrue(reads.unread(true, "chat", "social", 101))
        reads.read("chat", "social", 90)
        assertFalse(reads.unread(true, "chat", "social", 100))
        assertTrue(reads.unread(true, "chat", "live", 100))
        assertTrue(reads.unread(true, "other", "social", 100))
        assertTrue(reads.unread(true, "chat", "social", null))
        assertFalse(reads.unread(false, "chat", "social", 101))
        assertTrue(Mango9InboxReadLedger().unread(true, "chat", "social", 100))
    }
    @Test fun assistantOnlyUnreadDoesNotLightChatFooter() {
        val counts = mapOf("sms" to 0, "team" to 0, "social" to 0, "live" to 0, "assistant" to 3)
        assertEquals(0, Mango9InboxCountPolicy.total(counts, counts.keys - "assistant"))
        assertEquals(2, Mango9InboxCountPolicy.total(counts + ("social" to 2), counts.keys - "assistant"))
    }

    @Test fun unreadIsDistinctConversationsNotReplies() {
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        val raw = JSONObject().put("conversations", JSONObject().put(a, 3).put(b, 1)).put("unread", 4).put("readThrough", "10")
        assertEquals(2, Mango9AssistantUnread.parse(raw).first.size)
        assertThrows(IllegalArgumentException::class.java) { Mango9AssistantUnread.parse(raw.put("unread", 5)) }
        raw.put("unread", 5).getJSONObject("conversations").put(a.uppercase(), 1)
        assertThrows(IllegalArgumentException::class.java) { Mango9AssistantUnread.parse(raw) }
    }

    @Test fun multipartHashingVerifiesLastShortPartAndRefusesChangedBytes() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val input = File(dir, "input"); input.writeBytes(ByteArray(Mango9AssistantMediaFile.partSize + 123) { 87 })
            val manifest = Mango9AssistantMediaFile.inspect(input); assertEquals(2, manifest.partMD5.size)
            val output = File(dir, "part")
            output.outputStream().use {
                assertEquals(123, Mango9AssistantMediaFile.copyPart(input, it, manifest.size, 2, manifest.partMD5[1]))
            }
            assertEquals(123L, output.length())
            output.outputStream().use {
                assertThrows(IllegalArgumentException::class.java) {
                    Mango9AssistantMediaFile.copyPart(input, it, manifest.size, 2, "AAAAAAAAAAAAAAAAAAAAAA==")
                }
            }
            assertThrows(IllegalArgumentException::class.java) { Mango9AssistantMediaFile.requireSpace(500_000_001, 1_000_000_000) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun scopedStorageRejectsCrossServerAndForeignHost() {
        val id = UUID.randomUUID().toString(); val prefix = "https://s3.us-central-1.wasabisys.com/newdev-chatmedia/ephonix-supervisor/v1/"
        assertEquals(prefix + "2.mango9.com/7/7/" + id, Mango9AssistantMediaContract.objectURL(prefix + "2.mango9.com/7/7/" + id, id, "https://2.mango9.com"))
        for (raw in listOf(prefix + "us.mango9.com/7/7/" + id, prefix + "2.mango9.com/7/7/../" + id, "https://foreign.invalid/" + id)) assertThrows(IllegalArgumentException::class.java) {
            Mango9AssistantMediaContract.objectURL(raw, id, "https://2.mango9.com")
        }
    }

    @Test fun assistantPushCannotFallIntoSmsAndRequiresScope() {
        val data =
            mapOf("event" to "supervisor.reply", "crm_id" to "fixture", "sip_identity" to "sip:100@voice.invalid", "recipient_user_id" to "7", "conversation_id" to UUID.randomUUID().toString())
        val push = requireNotNull(Mango9MessagePush.parse(data)); assertTrue(push.target is Mango9MessagePushTarget.Assistant)
        assertEquals(push, Mango9MessagePush.fromJson(push.toJson()))
        for (key in listOf("crm_id", "sip_identity", "recipient_user_id", "conversation_id")) assertNull(Mango9MessagePush.parse(data - key))
    }

    @Test fun audioAttachmentMetadataSurvivesPrivateCache() {
        val id = UUID.randomUUID().toString()
        val message =
            JSONObject("""{"id":"$id","role":"user","parts":[{"type":"attachment","id":"$id","name":"Voice.wav","size":48044,"mime":"audio/wav"},{"type":"tool","secret":"hidden"}]}""")
        val saved = requireNotNull(Mango9SupervisorPresentation.savedMessage(message))
        assertEquals(1, saved.getJSONArray("parts").length()); assertEquals("48.0 KB", Mango9AssistantMediaContract.sizeLabel(saved.getJSONArray("parts").getJSONObject(0).getLong("size")))
        assertFalse(saved.toString().contains("hidden"))
    }
    @org.junit.Test fun footerCountsChatsAndClearsOnlyTheReadChannel() {
        org.junit.Assert.assertEquals(1, Mango9InboxCountPolicy.ids(listOf("+15555550100" to 3, "15555550100" to 7, "15555550101" to 0), true).size)
        val counts = mutableMapOf("sms" to 1, "team" to 2, "social" to 3, "live" to 1, "assistant" to 1)
        org.junit.Assert.assertEquals(8, Mango9InboxCountPolicy.total(counts,counts.keys))
        counts["social"] = 0
        org.junit.Assert.assertEquals(5, Mango9InboxCountPolicy.total(counts,counts.keys))
        org.junit.Assert.assertEquals(2, Mango9InboxCountPolicy.total(counts,setOf("sms","assistant")))
    }
}
