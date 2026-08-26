/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import androidx.core.content.edit
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.tools.Log

sealed class Mango9ApiException(val userMessage: String) : Exception(userMessage) {
    data object InvalidEmail : Mango9ApiException("Enter a valid email address.")

    data object InvalidOrExpiredCode : Mango9ApiException("That code is invalid or expired. Request a new code and try again.")

    data object EmailCodeUnavailable : Mango9ApiException("Email code sign-in is temporarily unavailable. Use your password or try again later.")

    data object InvalidCredentials : Mango9ApiException("The CRM username or password is incorrect.")

    data object AccountNotProvisionable : Mango9ApiException("This CRM user does not have an active SIP extension.")

    data object EnrollmentUnavailable : Mango9ApiException(
        "Mango9 accepted your sign-in, but phone setup expired. Please sign in again.",
    )

    data object RateLimited : Mango9ApiException("Too many sign-in attempts. Wait a moment and try again.")

    data object CrmUnavailable : Mango9ApiException("The CRM is temporarily unavailable. Please try again.")

    data object Unauthorized : Mango9ApiException("Your saved Mango9 session has expired. Enter your password to connect again.")

    data object InvalidResponse : Mango9ApiException("The provisioning service returned an unexpected response.")

    data object RegistrationFailed : Mango9ApiException("The Mango9 extension could not register. Please try again.")

    data object RegistrationTimedOut : Mango9ApiException("The Mango9 extension registration timed out. Please try again.")

    data object Network : Mango9ApiException("We couldn't reach Mango9. Check your connection and try again.")
}

internal class Mango9ApiClient(context: Context) {
    private companion object {
        private const val TAG = "[Mango9 API]"
        private const val KEY_DEVICE_ID = "login_device_uuid"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }

    private val devicePreferences = context.applicationContext.getSharedPreferences(
        "mango9_device",
        Context.MODE_PRIVATE,
    )

    suspend fun signIn(username: String, password: String): Mango9LoginResponse {
        val response = postProvisioning(
            "v1/mobile/login",
            JSONObject().apply {
                put("username", username)
                put("password", password)
                put("device_id", deviceId)
                put("platform", "android")
            },
        )
        return try {
            parseLoginResponse(response)
        } catch (error: Mango9ApiException.InvalidResponse) {
            Log.e(
                "$TAG Login succeeded but the response contract was invalid" +
                    supportReference(response),
            )
            throw error
        }
    }

    suspend fun requestLoginCode(email: String): Int {
        val response = postProvisioning(
            "v1/mobile/login-code/request",
            JSONObject().apply {
                put("email", email)
                put("device_id", deviceId)
            },
        )
        if (!response.optBoolean("success", false)) throw Mango9ApiException.InvalidResponse
        return response.optInt("resend_after", 60).coerceAtLeast(0)
    }

    suspend fun verifyLoginCode(email: String, code: String): Mango9LoginResponse {
        val response = postProvisioning(
            "v1/mobile/login-code/verify",
            JSONObject().apply {
                put("email", email)
                put("code", code)
                put("device_id", deviceId)
                put("platform", "android")
            },
        )
        return parseLoginResponse(response)
    }

    suspend fun fetchEnrollment(enrollmentUrl: String): Mango9SipEnrollment {
        val url = Mango9Configuration.verifiedProvisioningUrl(enrollmentUrl)
            ?: throw Mango9ApiException.InvalidResponse
        return Mango9EnrollmentParser.parse(
            request(
                url,
                "GET",
                accept = "application/xml, text/xml;q=0.9, */*;q=0.1",
                purpose = RequestPurpose.Enrollment,
            ).body,
        )
    }

    suspend fun storedProvisioning(session: Mango9Session): StoredProvisioning {
        val base = Mango9Configuration.verifiedHttpsUrl(session.crmApiBaseUrl)
            ?: throw Mango9ApiException.InvalidResponse
        val url = URL(base.toString().trimEnd('/') + "/provisioning")
        val response = request(url, "GET", bearerToken = session.accessToken)
        val envelope = JSONObject(response.body.toString(Charsets.UTF_8))
        if (!envelope.optBoolean("success", false)) throw Mango9ApiException.AccountNotProvisionable
        val sip = envelope.requiredObject("data").requiredObject("sip")
        return StoredProvisioning(
            identity = sip.requiredString("identity"),
            username = sip.requiredString("username"),
            activeNumber = sip.optionalString("active_number") ?: sip.optionalString("activeNumber"),
            password = sip.requiredString("password"),
            domain = sip.requiredString("domain"),
            realm = sip.requiredString("realm"),
        )
    }

    suspend fun refresh(session: Mango9Session): Mango9Session {
        val base = Mango9Configuration.verifiedHttpsUrl(session.crmApiBaseUrl)
            ?: throw Mango9ApiException.InvalidResponse
        val url = URL(base.toString().trimEnd('/') + "/auth/refresh")
        val response = request(
            url,
            "POST",
            JSONObject().put("refresh_token", session.refreshToken).toString().toByteArray(),
        )
        val envelope = JSONObject(response.body.toString(Charsets.UTF_8))
        if (!envelope.optBoolean("success", false)) throw Mango9ApiException.Unauthorized
        val tokens = envelope.requiredObject("data").requiredObject("tokens")
        return session.copy(
            accessToken = tokens.requiredString("access_token"),
            refreshToken = tokens.requiredString("refresh_token"),
        )
    }

    suspend fun dashboard(session: Mango9Session): Mango9CrmDashboard {
        val data = authorizedCrmData(session, listOf("dashboard"))
        val balance = data.requiredObject("balance")
        return Mango9CrmDashboard(
            totalBalance = balance.optDouble("total", 0.0),
            usedBalance = balance.optDouble("used", 0.0),
            remainingBalance = balance.optDouble("remaining", 0.0),
            leads = data.optInt("leads", 0),
            clients = data.optInt("clients", 0),
        )
    }

    suspend fun records(
        session: Mango9Session,
        kind: Mango9RecordKind,
        search: String = "",
        status: String = "",
        groupId: String = "",
        dateFilter: String = "",
        page: Int = 1,
    ): Mango9CrmRecordPage {
        val query = linkedMapOf("page" to page.toString(), "limit" to "50")
        search.trim().takeIf { it.isNotEmpty() }?.let { query["search"] = it }
        status.takeIf { it.isNotEmpty() }?.let { query["status"] = it }
        groupId.takeIf { it.isNotEmpty() }?.let { query["group_id"] = it }
        dateFilter.takeIf { it.isNotEmpty() }?.let { query["date_filter"] = it }
        val data = authorizedCrmData(session, listOf("mobile", kind.path), query = query)
        val records = data.optJSONArray(kind.responseKey).orEmpty().mapObjects(::parseRecord)
        val pagination = data.optJSONObject("pagination") ?: JSONObject()
        return Mango9CrmRecordPage(
            records,
            Mango9CrmPagination(
                total = pagination.optInt("total", records.size),
                page = pagination.optInt("page", page),
                limit = pagination.optInt("limit", 50),
                pages = pagination.optInt("pages", 1),
            ),
        )
    }

    suspend fun schema(session: Mango9Session, kind: Mango9RecordKind): Mango9CrmSchema {
        val data = authorizedCrmData(session, listOf("mobile", "schema", kind.path))
        val sections = data.optJSONArray("sections").orEmpty().mapObjects {
            Mango9CrmSchema.Section(it.requiredString("id"), it.requiredString("label"))
        }
        val fields = data.optJSONArray("fields").orEmpty().mapObjects { field ->
            Mango9CrmSchema.Field(
                key = field.requiredString("key"),
                fieldId = field.optInt("field_id").takeIf { field.has("field_id") && !field.isNull("field_id") },
                name = field.optionalString("name"),
                label = field.requiredString("label"),
                type = field.requiredString("type"),
                section = field.requiredString("section"),
                required = field.optBoolean("required", false),
                editable = field.optBoolean("editable", false),
                visible = if (field.has("visible")) field.optBoolean("visible", true) else true,
                custom = field.optBoolean("custom", false),
                options = field.optJSONArray("options").orEmpty().mapStrings(),
            )
        }
        return Mango9CrmSchema(
            entity = data.requiredString("entity"),
            version = data.requiredString("version"),
            sections = sections,
            fields = fields,
            statuses = data.optJSONArray("statuses").orEmpty().mapStrings(),
        )
    }

    suspend fun groups(session: Mango9Session, kind: Mango9RecordKind): List<Mango9CrmGroup> {
        val envelope = authorizedCrmEnvelope(session, listOf("mobile", kind.path, "groups"))
        val data = envelope.opt("data")
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("groups")
            else -> null
        }
        return array.orEmpty().mapObjects {
            Mango9CrmGroup(it.opt("id")?.toString().orEmpty(), it.requiredString("name"))
        }
    }

    suspend fun record(session: Mango9Session, kind: Mango9RecordKind, id: Int): Mango9CrmRecordDetail {
        val data = authorizedCrmData(session, listOf("mobile", kind.path, id.toString()))
        return parseRecordDetail(data, kind)
    }

    suspend fun createRecord(
        session: Mango9Session,
        kind: Mango9RecordKind,
        firstName: String,
        lastName: String,
        phone: String,
        email: String,
        groupId: String,
    ): Mango9CrmRecordDetail {
        val fullName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
        val body = JSONObject().apply {
            put("first_name", firstName)
            put("last_name", lastName)
            put("contact_name", fullName)
            put("phone", phone)
            put("email", email)
            if (groupId.isNotBlank()) put("group_id", groupId)
        }
        val data = authorizedCrmData(session, listOf("mobile", kind.path), "POST", body = body)
        return parseRecordDetail(data, kind)
    }

    suspend fun updateRecord(
        session: Mango9Session,
        kind: Mango9RecordKind,
        id: Int,
        values: Map<String, String>,
    ): Mango9CrmRecordDetail {
        val valueJson = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value) }
        }
        val data = authorizedCrmData(
            session,
            listOf("mobile", kind.path, id.toString()),
            "PATCH",
            body = JSONObject().put("values", valueJson),
        )
        return parseRecordDetail(data, kind)
    }

    suspend fun deleteRecord(session: Mango9Session, kind: Mango9RecordKind, id: Int) {
        authorizedCrmData(session, listOf("mobile", kind.path, id.toString()), "DELETE", allowEmptyData = true)
    }

    suspend fun teamMembers(session: Mango9Session): List<Mango9TeamMember> {
        val data = authorizedCrmData(session, listOf("mobile", "team-members"))
        return data.optJSONArray("members").orEmpty().mapObjects {
            Mango9TeamMember(
                userId = it.optInt("user_id"),
                loginId = it.requiredString("login_id"),
                name = it.optString("name"),
                email = it.optString("email"),
                mobile = it.optString("mobile"),
                role = it.optString("role"),
                extension = it.optString("extension"),
                sipUri = it.optionalString("sip_uri"),
            )
        }
    }

    suspend fun chatBootstrap(session: Mango9Session): Mango9ChatBootstrap {
        val data = authorizedCrmData(session, listOf("mobile", "chat", "bootstrap"))
        return Mango9ChatBootstrap(
            websocketUrl = data.requiredString("websocket_url"),
            token = data.requiredString("token"),
            expiresIn = data.optInt("expires_in", 0),
            userId = data.optInt("user_id"),
            transport = data.requiredString("transport"),
        )
    }

    suspend fun callSettings(session: Mango9Session): Mango9CallSettings {
        return parseCallSettings(authorizedCrmData(session, listOf("mobile", "call-settings")))
    }

    suspend fun updateCallForwarding(
        session: Mango9Session,
        enabled: Boolean,
        destination: String,
    ): Mango9CallSettings {
        val data = authorizedCrmData(
            session,
            listOf("mobile", "call-settings", "forwarding"),
            "POST",
            body = JSONObject().put("enabled", enabled).put("destination", destination),
        )
        return parseCallSettings(data)
    }

    private suspend fun authorizedCrmData(
        session: Mango9Session,
        path: List<String>,
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        allowEmptyData: Boolean = false,
    ): JSONObject {
        val envelope = authorizedCrmEnvelope(session, path, method, query, body)
        return envelope.optJSONObject("data")
            ?: if (allowEmptyData) JSONObject() else throw Mango9ApiException.InvalidResponse
    }

    private suspend fun authorizedCrmEnvelope(
        session: Mango9Session,
        path: List<String>,
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
    ): JSONObject {
        val base = Mango9Configuration.verifiedHttpsUrl(session.crmApiBaseUrl)
            ?: throw Mango9ApiException.InvalidResponse
        val encodedPath = path.joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
        val encodedQuery = query.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, Charsets.UTF_8.name())}=${URLEncoder.encode(it.value, Charsets.UTF_8.name())}"
        }
        val endpoint = base.toString().trimEnd('/') + "/$encodedPath" +
            if (encodedQuery.isEmpty()) "" else "?$encodedQuery"
        val response = request(
            URL(endpoint),
            method,
            body?.toString()?.toByteArray(),
            bearerToken = session.accessToken,
        )
        val envelope = try {
            JSONObject(response.body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw Mango9ApiException.InvalidResponse
        }
        if (!envelope.optBoolean("success", false)) throw Mango9ApiException.InvalidResponse
        return envelope
    }

    private fun parseRecord(json: JSONObject): Mango9CrmRecord = Mango9CrmRecord(
        id = json.optInt("id"),
        ownerUserId = json.optInt("owner_user_id"),
        ownerName = json.optString("owner_name"),
        name = json.optString("name"),
        phone = json.optString("phone"),
        email = json.optString("email"),
        status = json.optString("status"),
        source = json.optString("source"),
        createdAt = json.optString("created_at"),
    )

    private fun parseRecordDetail(data: JSONObject, kind: Mango9RecordKind): Mango9CrmRecordDetail {
        val recordJson = data.optJSONObject(if (kind == Mango9RecordKind.Lead) "lead" else "client")
            ?: data.optJSONObject("lead")
            ?: data.optJSONObject("client")
            ?: throw Mango9ApiException.InvalidResponse
        val values = mutableMapOf<String, String>()
        data.optJSONObject("values")?.let { json ->
            json.keys().forEach { key -> values[key] = jsonValueString(json.opt(key)) }
        }
        return Mango9CrmRecordDetail(
            parseRecord(recordJson),
            data.opt("schema_version")?.toString().orEmpty(),
            values,
        )
    }

    private fun parseCallSettings(data: JSONObject): Mango9CallSettings {
        val line = data.requiredObject("line")
        val forwarding = data.requiredObject("forwarding")
        return Mango9CallSettings(
            activeNumber = line.optString("active_number"),
            extension = line.optString("extension"),
            forwardingEnabled = forwarding.optBoolean("enabled", false),
            forwardingDestination = forwarding.optString("destination"),
        )
    }

    private fun jsonValueString(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until value.length()).joinToString(", ") { value.opt(it)?.toString().orEmpty() }
        else -> value.toString()
    }

    private suspend fun postProvisioning(path: String, body: JSONObject): JSONObject {
        val url = Mango9Configuration.verifiedProvisioningUrl(
            "${Mango9Configuration.PROVISIONING_BASE_URL}/${path.trimStart('/')}",
        ) ?: throw Mango9ApiException.InvalidResponse
        val response = request(url, "POST", body.toString().toByteArray())
        return try {
            JSONObject(response.body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw Mango9ApiException.InvalidResponse
        }
    }

    private fun parseLoginResponse(json: JSONObject): Mango9LoginResponse {
        val crm = json.requiredObject("crm")
        val services = json.requiredObject("services")
        val tokens = json.requiredObject("tokens")
        val user = json.requiredObject("user")
        val expiresIn = json.optInt("expires_in", -1)
        if (expiresIn < 0) throw Mango9ApiException.InvalidResponse
        val session = Mango9Session(
            crmId = crm.requiredString("id"),
            crmBaseUrl = crm.requiredString("base_url"),
            crmApiBaseUrl = crm.requiredString("api_base_url"),
            userId = crm.requiredString("user_id"),
            parentClientId = crm.requiredString("parent_client_id"),
            role = crm.requiredString("role"),
            loginId = user.requiredString("login_id"),
            displayName = user.optionalString("name"),
            accessToken = tokens.requiredString("access_token"),
            refreshToken = tokens.requiredString("refresh_token"),
            smsChatApi = services.requiredString("sms_chat_api"),
            connectWebsocket = services.requiredString("connect_websocket"),
            enrollmentExpiresAtEpochSeconds = System.currentTimeMillis() / 1000 + expiresIn,
            sipIdentity = null,
        )
        return Mango9LoginResponse(
            enrollmentUrl = json.requiredString("enrollment_url"),
            expiresIn = expiresIn,
            session = session,
        )
    }

    private suspend fun request(
        url: URL,
        method: String,
        body: ByteArray? = null,
        bearerToken: String? = null,
        accept: String = "application/json",
        purpose: RequestPurpose = RequestPurpose.Json,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val verifiedUrl = Mango9Configuration.verifiedHttpsUrl(url.toString())
            ?: throw Mango9ApiException.InvalidResponse
        val connection = (verifiedUrl.openConnection() as? HttpsURLConnection)
            ?: throw Mango9ApiException.InvalidResponse
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "Mango9Android/1.0")
            if (bearerToken != null) connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use(::readLimited) ?: ByteArray(0)
            if (status !in 200..299) {
                throw mapHttpError(status, responseBody, verifiedUrl, purpose)
            }
            HttpResponse(status, responseBody)
        } catch (error: Mango9ApiException) {
            throw error
        } catch (error: Exception) {
            Log.e(
                "$TAG Network failure for ${verifiedUrl.host}${verifiedUrl.path}: " +
                    error.javaClass.simpleName,
            )
            throw Mango9ApiException.Network
        } finally {
            connection.disconnect()
        }
    }

    private fun mapHttpError(
        status: Int,
        body: ByteArray,
        url: URL,
        purpose: RequestPurpose,
    ): Mango9ApiException {
        val errorEnvelope = try {
            JSONObject(body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
        val serverError = errorEnvelope?.optionalString("error")
        Log.e(
            "$TAG HTTP $status for ${url.host}${url.path}; purpose=${purpose.logName}; " +
                "code=${serverError ?: "unknown"}" +
                supportReference(errorEnvelope),
        )
        return when {
            purpose == RequestPurpose.Enrollment &&
                status in setOf(
                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN,
                    HttpURLConnection.HTTP_NOT_FOUND,
                    HttpURLConnection.HTTP_GONE,
                ) -> Mango9ApiException.EnrollmentUnavailable
            status == HttpURLConnection.HTTP_UNAUTHORIZED && serverError == "invalid_or_expired_code" ->
                Mango9ApiException.InvalidOrExpiredCode
            status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN ->
                Mango9ApiException.InvalidCredentials
            status == HttpURLConnection.HTTP_BAD_REQUEST && serverError == "invalid_email" ->
                Mango9ApiException.InvalidEmail
            status == HttpURLConnection.HTTP_BAD_REQUEST && serverError == "invalid_code" ->
                Mango9ApiException.InvalidOrExpiredCode
            status == HttpURLConnection.HTTP_NOT_FOUND -> Mango9ApiException.EmailCodeUnavailable
            status == HttpURLConnection.HTTP_CONFLICT && serverError == "account_not_provisionable" ->
                Mango9ApiException.AccountNotProvisionable
            status == 429 -> Mango9ApiException.RateLimited
            status == HttpURLConnection.HTTP_BAD_GATEWAY || status == HttpURLConnection.HTTP_UNAVAILABLE ->
                Mango9ApiException.CrmUnavailable
            else -> Mango9ApiException.InvalidResponse
        }
    }

    private fun readLimited(stream: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw Mango9ApiException.InvalidResponse
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private val deviceId: String
        get() {
            val existing = devicePreferences.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            return UUID.randomUUID().toString().also {
                devicePreferences.edit(commit = true) { putString(KEY_DEVICE_ID, it) }
            }
        }

    data class StoredProvisioning(
        val identity: String,
        val username: String,
        val activeNumber: String?,
        val password: String,
        val domain: String,
        val realm: String,
    )

    private data class HttpResponse(val status: Int, val body: ByteArray)

    private enum class RequestPurpose(val logName: String) {
        Json("json"),
        Enrollment("enrollment"),
    }

}

private fun supportReference(json: JSONObject?): String =
    json?.optionalString("request_id")?.let { "; request_id=$it" }.orEmpty()

private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { optJSONObject(it)?.let(transform) }

private fun JSONArray.mapStrings(): List<String> =
    (0 until length()).mapNotNull { opt(it)?.toString() }
