// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.util.Base64
import java.util.Locale
import org.json.JSONObject
import org.linphone.core.Call
import org.linphone.core.CallLog
import org.linphone.core.Privacy

/** Display metadata only. Neither this value nor a stored refKey is a dialing address. */
data class Mango9CallerPresentation(val handle: String, val name: String, val source: String) {
    companion object {
        val unknown = Mango9CallerPresentation("Incoming call", "Incoming call", "unknown")
        val withheld = Mango9CallerPresentation("Anonymous", "Private caller", "withheld")
    }
}

/** Pending pushes expire; native-call-bound presentation lives until that call ends. */
internal class Mango9IncomingCallerStore {
    private data class Pending(val identity: Mango9PushCallerIdentity, val at: Long)

    private data class Active(val token: Long, var account: String?, var authoritative: Boolean, var value: Mango9CallerPresentation)

    private val pending = linkedMapOf<String, Pending>()
    private val active = mutableMapOf<String, Active>()
    private val ended = linkedMapOf<String, Long>()

    @Synchronized fun cache(identity: Mango9PushCallerIdentity, now: Long): Boolean {
        prune(now)
        if (identity.callId.isBlank() || identity.callId.length > 512 || identity.callId in ended) return false
        val bound = active[identity.callId]
        if (bound != null) {
            if (bound.authoritative || bound.value.source == "push" || !matches(identity, bound.account)) return false
            bound.value = pushed(identity)
        } else {
            if (identity.callId in pending) return false
            pending[identity.callId] = Pending(identity, now)
            while (pending.size > 64) pending.remove(pending.keys.first())
        }
        return true
    }

    @Synchronized fun pending(callId: String?, now: Long): Mango9PushCallerIdentity? {
        prune(now)
        return pending[callId]?.identity
    }

    @Synchronized fun resolve(
        id: String, token: Long, account: String?, placeholder: Boolean,
        sip: Mango9CallerPresentation, withheld: Boolean, now: Long,
    ): Mango9CallerPresentation {
        prune(now)
        val authoritative = if (withheld) Mango9CallerPresentation.withheld else sip
        if (id.isBlank() || id in ended) return if (placeholder) Mango9CallerPresentation.unknown else authoritative
        val owner = accountKey(account)
        val entry = active[id] ?: Active(token, owner, false, Mango9CallerPresentation.unknown)
        if (entry.token != token || (entry.account != null && owner != null && entry.account != owner)) {
            return if (placeholder) Mango9CallerPresentation.unknown else authoritative
        }
        if (entry.account == null) entry.account = owner
        if (!placeholder) {
            entry.authoritative = true; entry.value = authoritative; pending.remove(id)
        } else if (!entry.authoritative) {
            pending[id]?.identity?.takeIf { matches(it, owner) }?.let {
                pending.remove(id); entry.value = pushed(it)
            }
            if (entry.value.source != "push") entry.value = sip
        }
        active[id] = entry
        return entry.value
    }

    @Synchronized fun finish(id: String?, token: Long?, now: Long): Mango9CallerPresentation? {
        if (id.isNullOrBlank()) return null
        val entry = active[id]
        if (entry != null && token != entry.token) return null
        active.remove(id); pending.remove(id); ended[id] = now
        prune(now)
        while (ended.size > 128) ended.remove(ended.keys.first())
        return entry?.value
    }

    @Synchronized fun clear() { pending.clear(); active.clear(); ended.clear() }

    private fun prune(now: Long) {
        pending.entries.removeAll { now < it.value.at || now - it.value.at >= 120_000 }
        ended.entries.removeAll { now - it.value >= 300_000 }
    }

    private fun matches(identity: Mango9PushCallerIdentity, account: String?): Boolean =
        identity.recipient == null || (account != null && accountKey(identity.recipient) == account)

    private fun pushed(identity: Mango9PushCallerIdentity) = Mango9CallerPresentation(identity.handle, identity.displayName, "push")

    companion object {
        fun accountKey(raw: String?): String? {
            var value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if ('<' in value && '>' in value) value = value.substringAfter('<').substringBefore('>')
            value = value.replace(Regex("^sips?:", RegexOption.IGNORE_CASE), "").substringBefore(';').substringBefore('?')
            val split = value.lastIndexOf('@')
            if (split <= 0 || split == value.lastIndex) return null
            return value.substring(0, split) + "@" + value.substring(split + 1).lowercase(Locale.ROOT)
        }

        fun hasPrivacy(mask: Int, header: String?, user: String?, name: String?): Boolean {
            val tokens = header.orEmpty().lowercase(Locale.ROOT).split(Regex("[;,\\s]+"))
            val labels = setOf("anonymous", "anonimous", "anonymous caller", "private", "restricted", "no caller id")
            return mask and (Privacy.User.toInt() or Privacy.Id.toInt()) != 0 ||
                tokens.any { it == "user" || it == "id" } ||
                listOf(user, name).any { it?.trim()?.lowercase(Locale.ROOT) in labels }
        }
    }
}

object Mango9CallHistoryPresentation {
    private const val PREFIX = "mango9-caller-v1:"

    fun canReplace(value: String?) = value.isNullOrEmpty() || value.startsWith(PREFIX)

    private fun sanitize(value: Mango9CallerPresentation): Mango9CallerPresentation? {
        if (value.handle.length > 512 || value.name.length > 512) return null
        if (value.source == "withheld") return Mango9CallerPresentation.withheld
        if (value.source !in setOf("push", "sip")) return null
        val handle = Mango9CallerIdentity.normalizedLabel(value.handle) ?: return null
        return value.copy(handle = handle, name = Mango9CallerIdentity.normalizedLabel(value.name) ?: handle)
    }

    fun encode(value: Mango9CallerPresentation): String? = sanitize(value)?.let {
        PREFIX + Base64.getEncoder().encodeToString(
            JSONObject().put("handle", it.handle).put("displayName", it.name)
            .put("source", it.source).toString().toByteArray(Charsets.UTF_8)
        )
    }

    fun decode(value: String?): Mango9CallerPresentation? {
        if (value == null || !value.startsWith(PREFIX) || value.length > 8192) return null
        return runCatching {
            val json = JSONObject(String(Base64.getDecoder().decode(value.removePrefix(PREFIX)), Charsets.UTF_8))
            sanitize(Mango9CallerPresentation(json.getString("handle"), json.getString("displayName"), json.getString("source")))
        }.getOrNull()
    }

    fun persist(log: CallLog, presentation: Mango9CallerPresentation) {
        if (log.dir == Call.Dir.Incoming && canReplace(log.refKey)) encode(presentation)?.let { log.refKey = it }
    }
}

/** Called on the SDK core thread. The selected/default account is never used to bind identity. */
object Mango9PushCallerIdentityCache {
    private val store = Mango9IncomingCallerStore()

    fun cache(payload: String, nowMillis: Long = System.currentTimeMillis()): Mango9PushCallerIdentity? =
        Mango9PushCallerIdentity.parse(payload)?.takeIf { store.cache(it, nowMillis) }

    // Pending-only accessor retained for parser/cache checks; UI must use the native-call binding.
    internal fun get(callId: String?, nowMillis: Long = System.currentTimeMillis()) = store.pending(callId, nowMillis)

    fun remove(callId: String?, nowMillis: Long = System.currentTimeMillis()) { store.finish(callId, null, nowMillis) }

    internal fun clear() = store.clear()

    fun capture(call: Call): Mango9CallerPresentation {
        val address = call.remoteAddress
        val placeholder = call.state == Call.State.PushIncomingReceived
        val account = if (placeholder) call.toAddress?.asStringUriOnly() else call.params.account?.params?.identityAddress?.asStringUriOnly()
        val sip = Mango9CallerIdentity.normalizedLabel(address.username)?.let { user ->
            Mango9CallerPresentation(
                Mango9CallerIdentity.externalPhoneNumber(user) ?: user,
                Mango9CallerIdentity.normalizedLabel(address.displayName) ?: user, "sip"
            )
        } ?: Mango9CallerPresentation.unknown
        if (call.dir != Call.Dir.Incoming) return sip
        val remote = call.remoteParams
        val presentation = store.resolve(
            call.callLog.callId.orEmpty(), call.nativePointer, account, placeholder, sip,
            !placeholder && Mango9IncomingCallerStore.hasPrivacy(remote?.privacy ?: 0, remote?.getCustomHeader("Privacy"), address.username, address.displayName),
            System.currentTimeMillis()
        )
        // The SDK saves its call log before the terminal callback. Attach the current
        // display metadata while the call is active so that final SDK write includes it.
        runCatching { Mango9CallHistoryPresentation.persist(call.callLog, presentation) }
            .onFailure { org.linphone.core.tools.Log.w("[Mango9 Caller] Unable to attach display metadata") }
        return presentation
    }

    fun forCall(call: Call): Mango9PushCallerIdentity? {
        if (call.dir != Call.Dir.Incoming || call.state in setOf(Call.State.End, Call.State.Error, Call.State.Released)) return null
        val value = capture(call)
        return if (value.source in setOf("push", "withheld")) Mango9PushCallerIdentity(call.callLog.callId.orEmpty(), value.handle, value.name) else null
    }

    fun finish(call: Call) {
        if (call.dir != Call.Dir.Incoming) return
        val presentation = store.finish(call.callLog.callId, call.nativePointer, System.currentTimeMillis()) ?: return
        runCatching { Mango9CallHistoryPresentation.persist(call.callLog, presentation) }
            .onFailure { org.linphone.core.tools.Log.w("[Mango9 Caller] Unable to save display metadata") }
    }
}
