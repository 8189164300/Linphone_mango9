// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class Mango9TemplateFailure(val status: Int, val code: String) : Exception(when (status) {
    401 -> "Your account changed or signed out. Reopen Templates from the current chat."
    403 -> "Templates are unavailable for this account. Ask your administrator to check template permissions."
    404 -> "This template is no longer available. Return to the list and refresh."
    409 -> "This template changed on another device. Your edits are kept here. Reload it before saving changes."
    400, 413 -> "Check the title and message length, then try again."
    else -> "Couldn’t load or save templates. Check your connection and try again."
})

internal object Mango9TemplateText {
    fun valid(title: String, content: String, titleLimit: Int, contentLimit: Int) =
        title.isNotBlank() && content.isNotBlank() && title.toByteArray(Charsets.UTF_8).size <= titleLimit && content.toByteArray(Charsets.UTF_8).size <= contentLimit
    fun insert(draft: String, text: String, cursor: Int): String {
        val index = cursor.coerceIn(0, draft.length)
        return draft.substring(0, index) + text + draft.substring(index)
    }
}

internal class Mango9TemplateApi internal constructor(
    sessions: Mango9SessionAccess,
    refresh: suspend (Mango9Session) -> Mango9Session,
    private val transport: Mango9CalendarTransport,
) {
    constructor(context: Context) : this(Mango9SessionStore(context), Mango9ApiClient(context)::refresh, Mango9CalendarHttpTransport())
    private val authorized = Mango9AuthorizedRequests(sessions, refresh)
    suspend fun request(lease: Mango9SessionLease, path: String = "", method: String = "GET", query: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        authorized.execute(lease) { session ->
            val response = transport.execute(wire(session, path, method, query, body))
            if (response.status == 401) throw Mango9ApiException.InvalidCredentials
            val json = runCatching { JSONObject(response.body) }.getOrNull()
            if (response.status !in 200..299) throw Mango9TemplateFailure(response.status, json?.optJSONObject("error")?.optString("code").orEmpty())
            if (json?.opt("success") != true) throw Mango9TemplateFailure(0, "invalid_response")
            json.optJSONObject("data") ?: throw Mango9TemplateFailure(0, "invalid_response")
        }
    }
    companion object {
        fun wire(session: Mango9Session, path: String = "", method: String = "GET", query: Map<String, String> = emptyMap(), body: JSONObject? = null): Request {
            require(path.isEmpty() || path == "capabilities" || (path.toLongOrNull() ?: 0L) > 0)
            val base = Mango9Configuration.verifiedHttpsUrl(session.crmApiBaseUrl)?.toString()?.toHttpUrl() ?: throw Mango9TemplateFailure(0, "invalid_configuration")
            require(base.query == null && base.username.isEmpty() && base.password.isEmpty())
            val builder = base.newBuilder().apply { if (base.pathSegments.lastOrNull() == "") removePathSegment(base.pathSize - 1) }
            listOf("mobile", "messaging", "templates").forEach(builder::addPathSegment)
            if (path.isNotEmpty()) builder.addPathSegment(path)
            query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
            return Request.Builder().url(builder.build()).header("Authorization", "Bearer ${session.accessToken}")
                .header("Accept", "application/json").header("Cache-Control", "no-cache")
                .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        }
    }
}
