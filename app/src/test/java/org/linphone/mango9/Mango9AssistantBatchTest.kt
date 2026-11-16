// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class Mango9AssistantBatchTest {
    @Test fun batchDraftPreservesOrderAndRejectsWrongScopeOrDuplicates() {
        val chat = UUID.randomUUID().toString()

        fun draft(name: String) = JSONObject().put("id", UUID.randomUUID().toString()).put("account", "fixture").put("origin", "https://2.mango9.com").put("conversationId", chat).put("file", JSONObject().put("uri", "file:///tmp/$name").put("name", name).put("size", 100).put("mime", "text/plain").put("sha256", "a".repeat(64)).put("partMD5", JSONArray().put("A".repeat(22) + "==")))
        val first = draft("a.txt"); val second = draft("b.txt")

        fun batch(items: List<JSONObject>, sealed: Boolean = false) = JSONObject().put("version", 2).put("drafts", JSONArray(items)).put("sealed", sealed)

        fun restore(value: JSONObject) = Mango9AssistantMediaContract.restore(value, "fixture", "https://2.mango9.com", chat)
        assertEquals(first.getString("id"), restore(first).first[0].getString("id")); assertFalse(restore(first).second)
        val value = batch(listOf(second, first))
        assertEquals(listOf(second.getString("id"), first.getString("id")), restore(value).first.map { it.getString("id") })

        fun rejects(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
        rejects { Mango9AssistantMediaContract.restore(value, "different", "https://2.mango9.com", chat) }
        rejects { Mango9AssistantMediaContract.restore(value, "fixture", "https://us.mango9.com", chat) }
        rejects { Mango9AssistantMediaContract.restore(value, "fixture", "https://2.mango9.com", UUID.randomUUID().toString()) }
        for (items in listOf(emptyList(), listOf(first, first), listOf(first, second, draft("c.txt"), draft("d.txt")))) rejects {
            restore(batch(items))
        }
        first.put("caption", "One caption"); second.put("caption", "One caption")
        assertTrue(restore(batch(listOf(first, second), true)).second)
        second.put("caption", "Different")
        rejects { restore(batch(listOf(first, second), true)) }
    }

    @Test fun deletedChatMigrationKeepsWholeBatchAndResumesInterruptedCleanup() {
        val root = java.nio.file.Files.createTempDirectory("assistant-batch-recovery").toFile()
        try {
            val chat = UUID.randomUUID().toString(); val account = "fixture"; val origin = "https://2.mango9.com"
            val source = java.io.File(root, "$account.$chat.json")
            val drafts = (1..3).map { n ->
                JSONObject().put("id", UUID.randomUUID().toString()).put("account", account).put("origin", origin).put("conversationId", chat).put("caption", "Frozen caption")
                .put("file", JSONObject().put("uri", "file:///tmp/voice$n.wav").put("name", "voice$n.wav").put("size", 100).put("mime", "audio/wav").put("sha256", "a".repeat(64)).put("partMD5", JSONArray().put("A".repeat(22) + "==")))
            }
            val original = JSONObject().put("version", 2).put("drafts", JSONArray(drafts)).put("sealed", true).toString()
            source.writeText(original)

            fun recover() = Mango9AssistantDraftMigration.recover(source, chat, account, origin)
            val destination = recover(); val target = java.io.File(root, "$account.$destination.json"); val saved = target.readText()
            val moved = Mango9AssistantMediaContract.restore(JSONObject(saved), account, origin, destination)
            assertEquals(
                drafts.map {
                it.getJSONObject("file").toString()
            },
                moved.first.map { it.getJSONObject("file").toString() }
            ); assertFalse(moved.second)
            assertTrue(
                moved.first.none {
                it.has("caption")
            }
            ); assertTrue(moved.first.none { d -> drafts.any { it.getString("id") == d.getString("id") } })
            assertFalse(source.exists()); source.writeText(original)
            assertEquals(destination, recover()); assertEquals(saved, target.readText())
            source.writeText(original); target.writeText("bad journal")
            assertTrue(runCatching { recover() }.isFailure); assertEquals(original, source.readText())
        } finally { root.deleteRecursively() }
    }
}
