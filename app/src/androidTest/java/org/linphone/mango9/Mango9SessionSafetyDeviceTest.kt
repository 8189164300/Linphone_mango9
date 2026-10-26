// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.BuildConfig

/** Only isolated debug packages; synthetic accounts, no authentication or messaging traffic. */
@RunWith(AndroidJUnit4::class)
class Mango9SessionSafetyDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun requireIsolatedPackage() {
        check(BuildConfig.APPLICATION_ID.endsWith(".debug") && context.packageName == BuildConfig.APPLICATION_ID)
    }

    private fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    @Test fun hardwareProofHasValidSignatureAndUniqueRequestBinding() {
        val id = Mango9DeviceProof.create()
        try {
            val endpoint = "https://auth.example.invalid/refresh"
            val first = Mango9DeviceProof.proof(id, endpoint).split('.')
            val second = Mango9DeviceProof.proof(id, endpoint).split('.')
            val header = JSONObject(String(decode(first[0])))
            val claims = JSONObject(String(decode(first[1])))
            assertEquals("ES256", header.getString("alg")); assertEquals("dpop+jwt", header.getString("typ"))
            assertEquals("POST", claims.getString("htm")); assertEquals(endpoint, claims.getString("htu"))
            assertNotEquals(claims.getString("jti"), JSONObject(String(decode(second[1]))).getString("jti"))
            assertTrue(kotlin.math.abs(claims.getLong("iat") - System.currentTimeMillis() / 1000) < 5)
            val jwk = header.getJSONObject("jwk")
            val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
            val point = ECPoint(BigInteger(1, decode(jwk.getString("x"))), BigInteger(1, decode(jwk.getString("y"))))
            val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
            val raw = decode(first[2]); assertEquals(64, raw.size)

            fun integer(bytes: ByteArray): ByteArray {
                val positive = BigInteger(1, bytes).toByteArray()
                return byteArrayOf(2, positive.size.toByte()) + positive
            }
            val parts = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
            val der = byteArrayOf(0x30, parts.size.toByte()) + parts
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key); verifier.update((first[0] + "." + first[1]).toByteArray(Charsets.US_ASCII))
            assertTrue(verifier.verify(der)); assertArrayEquals(raw, Mango9DeviceProof.joseSignature(der))
            val canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"${jwk.getString("x")}\",\"y\":\"${jwk.getString("y")}\"}"
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            assertArrayEquals(digest, decode(Mango9DeviceProof.thumbprint(id)))
        } finally { Mango9DeviceProof.remove(id) }
    }

    @Test fun deletedKeyRequiresFreshLoginAndInsecureEndpointsAreRejected() {
        val id = Mango9DeviceProof.create()
        try {
            listOf(
                "http://auth.example.invalid/refresh", "https://auth.example.invalid/refresh?q=1",
                "https://user@auth.example.invalid/refresh"
            ).forEach { endpoint ->
                assertTrue(runCatching { Mango9DeviceProof.proof(id, endpoint) }.isFailure)
            }
        } finally { Mango9DeviceProof.remove(id) }
        assertEquals(
            Mango9ApiException.SessionExpired,
            runCatching { Mango9DeviceProof.proof(id, "https://auth.example.invalid/refresh") }.exceptionOrNull()
        )
    }

    @Test fun loginContractRejectsUnboundOrIncompleteServerTokens() {
        val id = Mango9DeviceProof.create()
        try {
            val api = Mango9ApiClient(context)
            val tokens = JSONObject().put("refresh_protocol", 2).put("session_id", "fixture-family")
                .put("logout_token", "fixture-capability").put("dpop_jkt", Mango9DeviceProof.thumbprint(id))
            api.checkBoundTokens(tokens, id)
            for (field in listOf("refresh_protocol", "session_id", "logout_token", "dpop_jkt")) {
                val missing = JSONObject(tokens.toString()).apply { remove(field) }
                assertEquals(Mango9ApiException.InvalidResponse, runCatching { api.checkBoundTokens(missing, id) }.exceptionOrNull())
            }
            tokens.put("dpop_jkt", "wrong-device")
            assertEquals(Mango9ApiException.InvalidResponse, runCatching { api.checkBoundTokens(tokens, id) }.exceptionOrNull())
        } finally { Mango9DeviceProof.remove(id) }
    }

    private fun fixture() = Mango9Session(
        crmId = "fixture", crmBaseUrl = "https://crm.example.invalid", crmApiBaseUrl = "https://crm.example.invalid/api",
        userId = "fixture", parentClientId = "fixture", role = "agent", loginId = "fixture@example.invalid",
        displayName = "Session test", accessToken = "fixture-access", refreshToken = "fixture-refresh",
        smsChatApi = "https://chat.example.invalid/api", connectWebsocket = "wss://chat.example.invalid/socket",
        enrollmentExpiresAtEpochSeconds = 1_900_000_000, sipIdentity = "sip:${UUID.randomUUID()}@example.invalid"
    )

    @Test fun realEncryptedStoreFencesAccountReplacementAndRejectsTokenIdentityChanges() {
        val store = Mango9SessionStore(context) {}
        val fixture = fixture()
        try {
            store.save(fixture, makeActive = true)
            val old = store.captureLogin(fixture.sipIdentity!!)!!
            assertFalse(store.replaceLoginTokens(old, old.session, old.session.copy(userId = "wrong-user")))
            store.save(fixture, makeActive = true)
            val replacement = store.load()!!
            assertNotEquals(old.session.loginEpoch, replacement.loginEpoch)
            assertFalse(store.removeIfCurrent(old) { fail("Stale account must not remove fresh login") })
            assertEquals(replacement, store.load())
        } finally { store.remove(fixture.sipIdentity!!) }
    }

    @Test fun oldNotificationsCannotSelectAReplacementLogin() {
        val store = Mango9SessionStore(context) {}; val fixture = fixture()
        try {
            store.save(fixture, makeActive = true)
            val lease = store.captureLogin(fixture.sipIdentity!!)!!
            val push =
                Mango9MessagePush(Mango9MessagePushTarget.LiveChat("fixture-thread"), fixture.sipIdentity, fixture.crmId)
            val bound = store.bindNotification(push, lease)!!
            assertEquals(lease.session.loginEpoch, bound.notificationLogin)
            assertNotNull(store.resolveNotificationLogin(bound))
            store.save(fixture, makeActive = true)
            assertNull(store.resolveNotificationLogin(bound))
        } finally { store.remove(fixture.sipIdentity!!) }
    }

    @Test fun durableLogoutResumesAfterInterruptedLocalRemoval() {
        val store = Mango9SessionStore(context) {}; val fixture = fixture()
        store.save(fixture, makeActive = true)
        val old = store.captureLogin(fixture.sipIdentity!!)!!
        assertTrue(runCatching { store.removeIfCurrent(old) { error("Simulated interruption") } }.isFailure)
        assertNull(store.load(fixture.sipIdentity))
        var removals = 0
        Mango9SessionStore(context) {}.recoverLocalRemovals { identity ->
            if (identity == fixture.sipIdentity) removals++ else error("Unexpected account")
        }
        assertEquals(1, removals)
        store.recoverLocalRemovals { fail("Completed logout must not repeat") }
    }

    @Test fun interruptedOldLogoutCannotRemoveNewLoginAfterRestart() {
        val store = Mango9SessionStore(context) {}; val fixture = fixture()
        try {
            store.save(fixture, makeActive = true)
            val old = store.captureLogin(fixture.sipIdentity!!)!!
            runCatching { store.removeIfCurrent(old) { error("Simulated interruption") } }
            store.save(fixture, makeActive = true)
            Mango9SessionStore(context) {}.recoverLocalRemovals { fail("Old intent cannot remove fresh login") }
            assertNotNull(store.load(fixture.sipIdentity))
        } finally { store.remove(fixture.sipIdentity!!) }
    }

    @Test fun uncertainStorageCommitRestoresOriginalAndDoesNotRemoveAccount() {
        val name = "session-fixture-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        var failNext = false
        val wrapped = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            val value = method.invoke(prefs, *(args ?: emptyArray()))
            if (method.name != "edit") {
                value
            } else {
                val editor = value as SharedPreferences.Editor
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, edit, values ->
                    val result = edit.invoke(editor, *(values ?: emptyArray()))
                    if (edit.name == "commit" && failNext) { failNext = false; false } else if (result is SharedPreferences.Editor) {
                        proxy
                    } else {
                        result
                    }
                }
            }
        } as SharedPreferences
        val store = Mango9SessionStore(context, {}, wrapped); val fixture = fixture()
        try {
            store.save(fixture, makeActive = true)
            val saved = store.captureLogin(fixture.sipIdentity!!)!!
            failNext = true
            assertEquals(
                Mango9ApiException.SecureStorage,
                runCatching {
                store.replaceLoginTokens(saved, saved.session, saved.session.copy(accessToken = "new", refreshToken = "new"))
            }.exceptionOrNull()
            )
            assertEquals(saved.session, store.load())
            val disk = JSONObject(prefs.getString("session.${fixture.sipIdentity}", null)!!).getJSONObject("session")
            assertEquals(saved.session.refreshToken, disk.getString("refresh_token"))
            failNext = true
            assertEquals(
                Mango9ApiException.SecureStorage,
                runCatching {
                store.removeIfCurrent(saved) { fail("Failed tombstone must preserve account") }
            }.exceptionOrNull()
            )
            assertEquals(saved.session, store.load())
        } finally { store.remove(fixture.sipIdentity!!); context.deleteSharedPreferences(name) }
    }
    @Test fun backgroundAccountReadDoesNotHideActiveAccountStorageFailure() {
        val name = "session-read-fixture-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val store = Mango9SessionStore(context, {}, prefs)
        val good = fixture(); val corrupt = fixture()
        val metadata = context.getSharedPreferences(Mango9SessionStore.METADATA_FILE, Context.MODE_PRIVATE)
        try {
            store.save(good, makeActive = false)
            prefs.edit().putString("session.${corrupt.sipIdentity}", "invalid-json").commit()
            metadata.edit().putString("active_sip_identity", corrupt.sipIdentity).commit()
            assertNull(store.load())
            assertEquals(Mango9ApiException.SecureStorage, store.lastStorageError)
            assertNotNull(store.load(good.sipIdentity))
            assertEquals(Mango9ApiException.SecureStorage, store.lastStorageError)
            metadata.edit().putString("active_sip_identity", good.sipIdentity).commit()
            assertNotNull(store.load())
            assertNull(store.lastStorageError)
        } finally {
            store.remove(good.sipIdentity!!); store.remove(corrupt.sipIdentity!!)
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun failedMetadataCommitCannotLetOldRenewalOverwriteFreshCredentials() {
        val prefs = context.getSharedPreferences(Mango9SessionStore.METADATA_FILE, Context.MODE_PRIVATE)
        var failNext = false
        val wrapped = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            val value = method.invoke(prefs, *(args ?: emptyArray()))
            if (method.name != "edit") value else {
                val editor = value as SharedPreferences.Editor
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, edit, values ->
                    val result = edit.invoke(editor, *(values ?: emptyArray()))
                    if (edit.name == "commit" && failNext) { failNext = false; false }
                    else if (result is SharedPreferences.Editor) proxy else result
                }
            }
        } as SharedPreferences
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == Mango9SessionStore.METADATA_FILE) wrapped else super.getSharedPreferences(name, mode)
        }
        val store = Mango9SessionStore(isolated, {}); val original = fixture()
        try {
            store.save(original, makeActive = true)
            val old = store.captureLogin(original.sipIdentity!!)!!
            failNext = true
            assertEquals(Mango9ApiException.SecureStorage, runCatching {
                store.save(original.copy(accessToken = "fresh-access", refreshToken = "fresh-refresh"), makeActive = true)
            }.exceptionOrNull())
            assertNull(store.currentLogin(old))
            assertFalse(store.replaceLoginTokens(old, old.session, old.session.copy(accessToken = "stale-response")))
            assertEquals("fresh-access", store.load(original.sipIdentity)?.accessToken)
        } finally { store.remove(original.sipIdentity!!) }
    }

}
