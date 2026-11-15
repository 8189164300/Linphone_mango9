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

internal class Mango9AssistantTransfer(context: Context, private val api: Mango9SupervisorApi, private val current: () -> Mango9SessionLease?, private val changed: () -> Unit) {
    val native = Mango9AssistantMediaNative(context.applicationContext)
    private val sessions = Mango9SessionStore(context)
    private val root = File(context.noBackupFilesDir, "assistant-upload-drafts").apply { mkdirs() }
    private var owner: Mango9SessionLease? = null
    private var conversation = ""
    private var epoch = 0
    private var busy = false
    var draft: JSONObject? = null; private set
    var enabled = false; private set
    var allowedTypes = emptySet<String>(); private set
    var phase = "idle"; private set
    var uploaded = 0L; private set
    var error: String? = null
    private var captions = false
    val active get() = phase in setOf("preparing", "uploading", "verifying")
    val label get() = when (phase) { "preparing" -> "Preparing file…"; "uploading" -> "Uploading ${(100 * uploaded / (draft?.getJSONObject("file")?.getLong("size") ?: 1))}%"; "verifying" -> "Checking file…"; "paused" -> "Upload paused"; else -> if (draft != null) "Ready to send" else "" }

    private fun check(version: Int) {
        check(version == epoch && owner != null && current() == owner && sessions.currentSession(owner!!) != null)
    }

    private fun path(id: String = conversation): File {
        require(Mango9SupervisorPresentation.uuid(id)); return File(root, Mango9SupervisorConsent.key(owner!!.session) + ".$id.json")
    }

    private fun save(value: JSONObject?) {
        val target = path()
        if (value == null) { if (target.exists()) check(target.delete()); return }
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

    suspend fun configure(lease: Mango9SessionLease, id: String) {
        pause(); owner = lease; conversation = id; draft = null; phase = "idle"; error = null; enabled = false; uploaded = 0
        val version = epoch
        try {
            val file = path(); if (file.exists()) {
                val value = withContext(Dispatchers.IO) { JSONObject(file.readText()) }; check(version)
                require(value.getString("conversationId") == id && value.getString("account") == Mango9SupervisorConsent.key(lease.session) && value.getString("origin") == origin())
                Mango9AssistantMediaContract.validFile(value.getJSONObject("file")); draft = value; phase = "paused"
            }
            val cap = request("capabilities", version)
            enabled = cap.optInt("version") == 1 && cap.optBoolean("enabled") && cap.optString("mode") == "transport" && cap.optLong("maxBytes") == Mango9AssistantMediaContract.maximum && cap.optInt("partSize") == Mango9AssistantMediaContract.partSize
            val types = cap.optJSONArray("types"); allowedTypes = (0 until (types?.length() ?: 0)).map {
                types!!.getString(it)
            }.toSet().intersect(Mango9AssistantMediaContract.types)
            captions = cap.optBoolean("textWithAttachment")
        } catch (e: Exception) {
            if (version == epoch && e !is CancellationException) error = "Files are temporarily unavailable. Text chat still works."
        }
        if (version == epoch) changed()
    }

    private fun origin() = "https://" + URI(owner!!.session.crmApiBaseUrl).host

    suspend fun select(uri: Uri, name: String) {
        if (!enabled || busy || draft != null) return
        val version = epoch; busy = true; phase = "preparing"; error = null; changed()
        var f: JSONObject? = null
        try {
            check(version); f = nativeResult { native.prepareLargeVideo(uri.toString(), name, it) } as JSONObject
            check(version); Mango9AssistantMediaContract.validFile(f); require(f.getString("mime") in allowedTypes)
            val value = JSONObject().put("id", UUID.randomUUID().toString()).put("account", Mango9SupervisorConsent.key(owner!!.session)).put("origin", origin()).put("conversationId", conversation).put("file", f)
            save(value); draft = value; phase = "selected"
        } catch (e: Exception) {
            f?.optString("uri")?.let { native.discardLargeMedia(it, discardCallback) }
            if (version == epoch && e !is CancellationException) { phase = "error"; error = e.message }
        } finally { busy = false; if (version == epoch) changed() }
    }

    fun pause() {
        epoch++; draft?.let {
            native.cancelLargeVideoUpload(it.getString("id"), discardCallback)
        }; phase = if (draft == null) "idle" else "paused"; changed()
    }

    suspend fun remove() {
        val d = draft ?: return; if (busy) return; val version = epoch
        try {
            try { request("abort", version, d.getString("id")) } catch (e: Mango9SupervisorFailure) { if (e.status != 404) throw e }
            check(version); save(null); native.discardLargeMedia(d.getJSONObject("file").getString("uri"), discardCallback); draft = null; phase = "idle"; error = null
        } catch (e: Exception) { if (version == epoch) error = "Removal could not be confirmed. Try again." }
        if (version == epoch) changed()
    }

    suspend fun send(text: String): String? {
        val d = draft ?: return null; if (!enabled || busy) return null
        val version = epoch; busy = true; error = null
        try {
            check(version); if (!d.has("caption")) d.put("caption", text.trim())
            val caption = d.getString("caption"); require(caption.toByteArray().size <= 8000 && (captions || caption.isEmpty())); save(d)
            val f = d.getJSONObject("file"); val id = d.getString("id"); val input = JSONObject()
            for (key in listOf("name", "size", "mime", "sha256", "partMD5")) input.put(key, f.get(key))
            Mango9AssistantMediaContract.status(request("start", version, id, input), d)
            var status = Mango9AssistantMediaContract.status(request("status", version, id), d)
            if (status.first == "uploading") {
                uploaded = status.second.sumOf { Mango9AssistantMediaContract.length(f, it) }; phase = "uploading"; changed()
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
            val result =
                request("attach", version, id, JSONObject().apply { if (caption.isNotEmpty()) put("text", caption) })
            require(result.optBoolean("received") && result.optString("id") == id && result.optString("conversationId") == conversation)
            save(null); draft = null; phase = "received"; native.discardLargeMedia(f.getString("uri"), discardCallback); return caption
        } catch (e: Exception) {
            if (version == epoch && e !is CancellationException) {
                phase = "error"; error = e.message ?: "Upload paused. Tap Resume."
            }; return null
        } finally { busy = false; if (version == epoch) changed() }
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
