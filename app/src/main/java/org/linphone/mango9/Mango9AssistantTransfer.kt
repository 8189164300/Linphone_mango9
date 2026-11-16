// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object Mango9AssistantMediaContract {
    const val maximum = 500_000_000L
    const val partSize = 8_388_608
    val types =
        setOf("video/mp4", "video/quicktime", "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "application/pdf", "text/plain", "text/csv", "audio/wav", "audio/mpeg")

    fun validFile(f: JSONObject) {
        require(f.getLong("size") in 1..maximum && f.getString("mime") in types && URI(f.getString("uri")).scheme == "file")
        require(f.getString("name").toByteArray().size in 1..240 && !Regex("[\\x00-\\x1f\\x7f/\\\\\\u202a-\\u202e\\u2066-\\u2069]").containsMatchIn(f.getString("name")))
        require(Regex("[a-f0-9]{64}").matches(f.getString("sha256")))
        val parts = f.getJSONArray("partMD5")
        require(parts.length().toLong() == (f.getLong("size") + partSize - 1) / partSize)
        for (n in 0 until parts.length()) require(Regex("[A-Za-z0-9+/]{22}==").matches(parts.getString(n)))
    }

    fun restore(value: JSONObject, account: String, origin: String, conversation: String): Pair<List<JSONObject>, Boolean> {
        val items = if (value.optInt("version") == 2) {
            value.getJSONArray("drafts").let { a ->
                (0 until a.length()).map {
            a.getJSONObject(it)
        }
        }
        } else {
            listOf(value)
        }
        require(items.size in 1..3 && items.map { it.getString("id") }.toSet().size == items.size)
        val frozen = if (value.optInt("version") == 2) value.getBoolean("sealed") else value.has("caption")
        items.forEach { d ->
            require(Mango9SupervisorPresentation.uuid(d.getString("id")) && d.getString("conversationId") == conversation && d.getString("account") == account && d.getString("origin") == origin)
            require(d.optString("caption").toByteArray().size <= 8000 && (!frozen || d.optString("caption") == items[0].optString("caption")))
            validFile(d.getJSONObject("file"))
        }
        return items to frozen
    }

    fun length(f: JSONObject, n: Int): Long {
        require(n in 1..f.getJSONArray("partMD5").length())
        return minOf(partSize.toLong(), f.getLong("size") - (n - 1L) * partSize)
    }

    fun objectURL(raw: String, id: String, origin: String): String {
        require(Mango9SupervisorPresentation.uuid(id) && raw.length <= 12000)
        val u = URI(raw); val realm = URI(origin).host
        require(realm in setOf("2.mango9.com", "us.mango9.com") && u.scheme == "https" && u.rawUserInfo == null && u.port == -1 && u.fragment == null)
        require(u.host in setOf("s3.us-central-1.wasabisys.com", "newdev-chatmedia.s3.us-central-1.wasabisys.com"))
        val prefix = if (u.host == "s3.us-central-1.wasabisys.com") "/newdev-chatmedia" else ""
        require(Regex("^$prefix/ephonix-supervisor/v1/" + Regex.escape(realm) + "/[1-9][0-9]*/[1-9][0-9]*/" + Regex.escape(id) + "$" ).matches(u.path))
        return raw
    }

    fun status(raw: JSONObject, d: JSONObject): Pair<String, Set<Int>> {
        val f = d.getJSONObject("file")
        require(raw.getInt("version") == 1 && raw.getString("id") == d.getString("id") && raw.getString("conversationId") == d.getString("conversationId"))
        require(raw.getLong("size") == f.getLong("size") && raw.getString("sha256") == f.getString("sha256") && raw.getInt("partSize") == partSize && raw.getInt("partCount") == f.getJSONArray("partMD5").length())
        val state = raw.getString("state"); require(state in setOf("creating", "uploading", "completing", "validating", "ready", "aborting", "aborted", "rejected"))
        val found = mutableSetOf<Int>(); val parts = raw.getJSONArray("uploaded")
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i); val n = p.getInt("number"); require(found.add(n) && p.getLong("size") == length(f, n))
        }
        if (state == "ready") {
            val a = raw.getJSONObject("attachment"); require(a.getString("id") == d.getString("id")); require(a.getLong("size") == f.getLong("size")); for (key in listOf("name", "mime", "sha256")) require(a.getString(key) == f.getString(key))
        }
        return state to found
    }

    fun ticket(raw: JSONObject, d: JSONObject, n: Int): JSONObject {
        val f = d.getJSONObject("file"); require(raw.getInt("version") == 1 && raw.getString("id") == d.getString("id"))
        val parts = raw.getJSONArray("parts"); require(parts.length() == 1); val p = parts.getJSONObject(0)
        require(p.getInt("number") == n && p.getLong("size") == length(f, n) && p.getString("md5") == f.getJSONArray("partMD5").getString(n - 1))
        val headers = p.getJSONObject("headers"); require(headers.length() == 2 && headers.getString("Content-Length") == length(f, n).toString() && headers.getString("Content-MD5") == p.getString("md5"))
        val url = objectURL(p.getString("url"), d.getString("id"), d.getString("origin")).toHttpUrl()
        for (key in listOf("partNumber", "uploadId", "X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date", "X-Amz-Expires", "X-Amz-SignedHeaders", "X-Amz-Signature")) require(url.queryParameterValues(key).size == 1)
        require(url.queryParameter("partNumber") == n.toString() && !url.queryParameter("uploadId").isNullOrBlank() && url.queryParameter("X-Amz-Algorithm") == "AWS4-HMAC-SHA256")
        require(url.queryParameter("X-Amz-Expires")?.toIntOrNull() in 1..600 && Regex("[a-f0-9]{64}").matches(url.queryParameter("X-Amz-Signature").orEmpty()))
        require(url.queryParameter("X-Amz-SignedHeaders").orEmpty().split(';').containsAll(listOf("host", "content-length", "content-md5")))
        return p
    }

    fun sizeLabel(size: Long): String = when { size <= 0 -> ""; size < 1000 -> "$size B"; size < 1_000_000 -> "%.1f KB".format(java.util.Locale.US, size / 1000.0); else -> "%.1f MB".format(java.util.Locale.US, size / 1_000_000.0) }
}

internal object Mango9AssistantDraftMigration {
    fun recover(original: File, source: String, account: String, origin: String): String {
        val marker = File(original.path + ".recovery")
        val destination = if (marker.exists()) {
            marker.readText().also {
            require(Mango9SupervisorPresentation.uuid(it) && it != source)
        }
        } else {
            UUID.randomUUID().toString().also { id ->
            val temp = File(marker.path + ".tmp"); temp.writeText(id); check(temp.renameTo(marker))
        }
        }
        if (original.exists()) {
            val batch = Mango9AssistantMediaContract.restore(JSONObject(original.readText()), account, origin, source).first
            val target = File(original.parentFile, "$account.$destination.json")
            if (target.exists()) {
                val restored = Mango9AssistantMediaContract.restore(JSONObject(target.readText()), account, origin, destination).first
                require(restored.map { it.getJSONObject("file").toString() } == batch.map { it.getJSONObject("file").toString() })
            } else {
                val moved = batch.map {
                    JSONObject(it.toString()).put("id", UUID.randomUUID().toString()).put("conversationId", destination).apply {
                        remove("caption")
                    }
                }
                val value = JSONObject().put("version", 2).put("drafts", JSONArray(moved)).put("sealed", false)
                val temp = File(target.path + ".tmp"); temp.writeText(value.toString()); check(temp.renameTo(target))
            }
            check(original.delete())
        }
        return destination
    }
}

internal class Mango9AssistantTransfer(context: Context, private val api: Mango9SupervisorApi, private val current: () -> Mango9SessionLease?, private val changed: () -> Unit) {
    val native = Mango9AssistantMediaNative(context.applicationContext)
    private val sessions = Mango9SessionStore(context)
    private val root = File(context.noBackupFilesDir, "assistant-upload-drafts").apply { mkdirs() }
    private var owner: Mango9SessionLease? = null
    private var conversation = ""
    private var epoch = 0
    private var busy = false
    var draft: JSONObject? = null; private set
    var drafts = emptyList<JSONObject>(); private set
    var sealed = false; private set
    var maximum = 1; private set
    val canAdd get() = enabled && !busy && !sealed && drafts.size < maximum
    val total get() = drafts.sumOf { it.getJSONObject("file").getLong("size") }
    var enabled = false; private set
    var allowedTypes = emptySet<String>(); private set
    var phase = "idle"; private set
    var uploaded = 0L; private set
    var error: String? = null
    private var captions = false
    val active get() = phase in setOf("preparing", "uploading", "verifying")
    val label get() = when (phase) { "preparing" -> "Preparing file…"; "uploading" -> "Uploading ${(100 * uploaded / maxOf(1L, total))}%"; "verifying" -> "Checking file…"; "paused" -> "Upload paused"; else -> if (draft != null) "Ready to send" else "" }

    private fun check(version: Int) {
        check(version == epoch && owner != null && current() == owner && sessions.currentSession(owner!!) != null)
    }

    private fun path(id: String = conversation): File {
        require(Mango9SupervisorPresentation.uuid(id)); return File(root, Mango9SupervisorConsent.key(owner!!.session) + ".$id.json")
    }

    private fun save(values: List<JSONObject>, frozen: Boolean, id: String = conversation) {
        require(values.size <= 3 && values.map { it.getString("id") }.toSet().size == values.size)
        val target = path(id)
        if (values.isEmpty()) { if (target.exists()) check(target.delete()); return }
        val value = JSONObject().put("version", 2).put("drafts", JSONArray(values)).put("sealed", frozen)
        val temp = File(target.path + ".tmp"); temp.writeText(value.toString()); check(temp.renameTo(target))
    }

    suspend fun nativeResult(action: (Mango9MediaCallback) -> Unit): Any? = suspendCancellableCoroutine { continuation ->
        action(object : Mango9MediaCallback { override fun resolve(value: Any?) {
            if (continuation.isActive) continuation.resume(value)
        }

        override fun reject(code: String, message: String?) {
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message ?: "File unavailable. Try again."))
        } })
    }

    private suspend fun request(action: String, version: Int, id: String? = null, body: JSONObject = JSONObject()): JSONObject {
        for (attempt in 0..90) {
            check(version); body.put("action", action).put("conversationId", conversation); if (id != null) body.put("id", id)
            try {
                val raw = api.request(owner!!, "transport", body); check(version); return raw
            } catch (e: Mango9SupervisorFailure) {
                if (e.status != 409 || e.code != "media_upload_busy" || attempt == 90) throw e; delay(2000)
            }
        }
        error("File unavailable")
    }

    fun recoverDraft(source: String): String {
        val lease = requireNotNull(current()); require(owner == null || owner == lease)
        owner = lease; check(sessions.currentSession(lease) != null); pause()
        return Mango9AssistantDraftMigration.recover(path(source), source, Mango9SupervisorConsent.key(lease.session), origin())
    }

    suspend fun configure(lease: Mango9SessionLease, id: String) {
        pause(); owner = lease; conversation = id; draft = null; drafts = emptyList(); sealed = false; maximum = 1; phase = "idle"; error = null; enabled = false; uploaded = 0
        val version = epoch
        try {
            val file = path(); if (file.exists()) {
                val value = withContext(Dispatchers.IO) { JSONObject(file.readText()) }; check(version)
                val restored = Mango9AssistantMediaContract.restore(value, Mango9SupervisorConsent.key(lease.session), origin(), id)
                drafts = restored.first; draft = drafts.first(); sealed = restored.second; phase = "paused"
            }
            val cap = request("capabilities", version)
            enabled = cap.optInt("version") == 1 && cap.optBoolean("enabled") && cap.optString("mode") == "transport" && cap.optLong("maxBytes") == Mango9AssistantMediaContract.maximum && cap.optInt("partSize") == Mango9AssistantMediaContract.partSize
            val types = cap.optJSONArray("types"); allowedTypes = (0 until (types?.length() ?: 0)).map {
                types!!.getString(it)
            }.toSet().intersect(Mango9AssistantMediaContract.types)
            captions = cap.optBoolean("textWithAttachment")
            maximum = if (cap.optBoolean("multipleAttachments") && cap.optInt("maxFiles") == 3) 3 else 1
        } catch (e: Exception) {
            if (version == epoch && e !is CancellationException) error = "Files are temporarily unavailable. Text chat still works."
        }
        if (version == epoch) changed()
    }

    private fun origin() = "https://" + URI(owner!!.session.crmApiBaseUrl).host

    suspend fun select(uri: Uri, name: String) = selectMany(listOf(uri to name))

    suspend fun selectMany(files: List<Pair<Uri, String>>) {
        if (!canAdd) return
        if (files.isEmpty() || files.size + drafts.size > maximum) {
            error = "Choose up to $maximum files. Nothing was added."; changed(); return
        }
        val version = epoch; busy = true; phase = "preparing"; error = null; changed()
        val prepared = mutableListOf<JSONObject>()
        try {
            val added = mutableListOf<JSONObject>()
            for ((uri, name) in files) {
                check(version); val file = nativeResult {
                    native.prepareLargeVideo(uri.toString(), name, it)
                } as JSONObject; prepared.add(file)
                check(version); Mango9AssistantMediaContract.validFile(file); require(file.getString("mime") in allowedTypes)
                require(
                    (drafts + added).none {
                    it.getJSONObject("file").getString("sha256") == file.getString("sha256") && it.getJSONObject("file").getLong("size") == file.getLong("size")
                }
                ) { "This file is already attached. Choose different files." }
                added.add(JSONObject().put("id", UUID.randomUUID().toString()).put("account", Mango9SupervisorConsent.key(owner!!.session)).put("origin", origin()).put("conversationId", conversation).put("file", file))
            }
            val all = drafts + added; save(all, false); drafts = all; draft = all.first(); phase = "selected"; uploaded = 0
        } catch (e: Exception) {
            for (file in prepared) if (drafts.none {
                it.getJSONObject("file").getString("uri") == file.getString("uri")
            }
            ) {
                native.discardLargeMedia(file.getString("uri"), discardCallback)
            }
            if (version == epoch && e !is CancellationException) { phase = "error"; error = e.message }
        } finally { if (version == epoch) { busy = false; changed() } }
    }

    fun pause() {
        epoch++; busy = false; draft?.let {
            native.cancelLargeVideoUpload(it.getString("id"), discardCallback)
        }; phase = if (draft == null) "idle" else "paused"; changed()
    }

    suspend fun remove(id: String? = null) {
        if (drafts.isEmpty() || busy) return
        val version = epoch; busy = true
        val removing = if (sealed || id == null) drafts else drafts.filter { it.getString("id") == id }
        try {
            for (item in removing) {
                try {
                    request("abort", version, item.getString("id"))
                } catch (e: Mango9SupervisorFailure) { if (e.status != 404) throw e }
                check(version)
            }
            val kept = drafts.filter {
                it !in removing
            }; save(kept, false); drafts = kept; draft = kept.firstOrNull(); sealed = false; phase = if (kept.isEmpty()) "idle" else "selected"; error = null; uploaded = 0
            for (item in removing) native.discardLargeMedia(item.getJSONObject("file").getString("uri"), discardCallback)
        } catch (e: Exception) {
            if (version == epoch) error = "Removal could not be confirmed. Your files have been kept."
        } finally { if (version == epoch) { busy = false; changed() } }
    }

    suspend fun send(text: String): String? {
        if (drafts.isEmpty() || !enabled || busy) return null
        val version = epoch; busy = true; error = null
        try {
            check(version); require(drafts.size <= maximum) {
                "Multiple attachments are unavailable on this server. Your files have been kept."
            }
            val caption = if (drafts[0].has("caption")) drafts[0].getString("caption") else text.trim()
            require(caption.toByteArray().size <= 8000 && (captions || caption.isEmpty()))
            val all = drafts.map { JSONObject(it.toString()).put("caption", caption) }; save(all, true); drafts = all; sealed = true
            var completed = 0L
            for (d in all) {
            draft = d
            val f = d.getJSONObject("file"); val id = d.getString("id"); val input = JSONObject()
            for (key in listOf("name", "size", "mime", "sha256", "partMD5")) input.put(key, f.get(key))
            Mango9AssistantMediaContract.status(request("start", version, id, input), d)
            var status = Mango9AssistantMediaContract.status(request("status", version, id), d)
            if (status.first == "uploading") {
                uploaded = completed + status.second.sumOf {
                    Mango9AssistantMediaContract.length(f, it)
                }; phase = "uploading"; changed()
                for (part in 1..f.getJSONArray("partMD5").length()) if (part !in status.second) {
                    val ticket = Mango9AssistantMediaContract.ticket(request("parts", version, id, JSONObject().put("parts", JSONArray().put(part))), d, part)
                    val result = nativeResult {
                        native.uploadLargeVideoPart(f.getString("uri"), f.getDouble("size"), f.getString("sha256"), ticket, id, it)
                    } as JSONObject
                    check(version); require(result.getInt("status") in 200..299); uploaded += Mango9AssistantMediaContract.length(f, part); changed()
                }
                phase = "verifying"; changed(); status = Mango9AssistantMediaContract.status(request("complete", version, id), d)
            }
            var tries = 0
            while (status.first in setOf("validating", "completing") && tries++ < 90) {
                phase = "verifying"; changed(); delay(2000); status = Mango9AssistantMediaContract.status(request("status", version, id), d)
            }
            require(status.first == "ready") { "Verification is not ready. Tap Resume to check again." }
            completed += f.getLong("size"); uploaded = completed
            }
            val ids = all.map { it.getString("id") }; val first = ids.first()
            val body = JSONObject().apply {
                if (caption.isNotEmpty()) put("text", caption); if (ids.size > 1) put("ids", JSONArray(ids))
            }
            val result = request("attach", version, first, body)
            require(result.optBoolean("received") && result.optString("id") == first && result.optString("conversationId") == conversation)
            if (ids.size > 1) {
                val got = result.getJSONArray("ids"); require(
                    got.length() == ids.size && ids.indices.all {
                    got.getString(it) == ids[it]
                }
                )
            }
            save(emptyList(), false); draft = null; drafts = emptyList(); sealed = false; phase = "received"
            for (item in all) native.discardLargeMedia(item.getJSONObject("file").getString("uri"), discardCallback)
            return caption
        } catch (e: Exception) {
            if (version == epoch && e !is CancellationException) {
                phase = "error"; error = e.message ?: "Upload paused. Tap Resume."
            }; return null
        } finally { if (version == epoch) { busy = false; changed() } }
    }

    suspend fun resource(file: JSONObject): Pair<String, JSONObject> {
        val version = epoch; val raw = request("download", version, file.getString("id"))
        require(raw.getString("id") == file.getString("id") && raw.getLong("expiresAt") in (System.currentTimeMillis() + 1)..(System.currentTimeMillis() + 610000))
        val metadata = JSONObject(file.toString()); raw.optJSONObject("file")?.let {
            for (key in listOf("name", "mime", "size")) if (it.has(key)) metadata.put(key, it.get(key))
        }
        require(metadata.getString("mime") in Mango9AssistantMediaContract.types && metadata.getLong("size") in 1..Mango9AssistantMediaContract.maximum)
        return Mango9AssistantMediaContract.objectURL(raw.getString("url"), file.getString("id"), origin()) to metadata
    }

    suspend fun download(file: JSONObject): Uri {
        val version = epoch; val r = resource(file)
        val raw = nativeResult {
            native.downloadLargeMedia(r.first, file.getString("id"), r.second.getString("mime"), r.second.getDouble("size"), it)
        } as String
        check(version); return Uri.parse(raw)
    }

    fun close() { pause(); native.invalidate() }

    private val discardCallback = object : Mango9MediaCallback { override fun resolve(value: Any?) {}

    override fun reject(code: String, message: String?) {} }
}
