// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyInfo
import android.util.Base64
import java.net.URI
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.math.BigInteger
import java.util.UUID
import org.json.JSONObject
import org.linphone.BuildConfig

/** Non-exportable hardware key. No first-seen-key enrollment during refresh. */
internal object Mango9DeviceProof {
    private val prefix get() = BuildConfig.APPLICATION_ID + ".session-proof."

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun create(): String {
        val id = UUID.randomUUID().toString()
        try {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(prefix + id, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build()
            )
            val pair = generator.generateKeyPair()
            val info = KeyFactory.getInstance(pair.private.algorithm, "AndroidKeyStore").getKeySpec(pair.private, KeyInfo::class.java)
            @Suppress("DEPRECATION")
            check(info.isInsideSecureHardware)
            return id
        } catch (error: Exception) { remove(id); throw Mango9ApiException.DeviceAuthorization }
    }

    fun remove(id: String) { runCatching { store().deleteEntry(prefix + id) } }

    private fun entry(id: String): KeyStore.PrivateKeyEntry {
        try {
            return store().getEntry(prefix + id, null) as? KeyStore.PrivateKeyEntry ?: throw Mango9ApiException.SessionExpired
        } catch (error: Mango9ApiException) { throw error } catch (error: Exception) { throw Mango9ApiException.SecureStorage }
    }

    private fun jwk(id: String): JSONObject {
        val key = entry(id).certificate.publicKey as ECPublicKey
        return JSONObject().put("kty", "EC").put("crv", "P-256")
            .put("x", base64(unsigned(key.w.affineX))).put("y", base64(unsigned(key.w.affineY)))
    }

    fun thumbprint(id: String): String {
        val key = jwk(id)
        val canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"${key.getString("x")}\",\"y\":\"${key.getString("y")}\"}"
        return base64(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
    }

    fun proof(id: String, endpoint: String): String {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        val header = JSONObject().put("typ", "dpop+jwt").put("alg", "ES256").put("jwk", jwk(id))
        val claims = JSONObject().put("htm", "POST").put("htu", endpoint)
            .put("iat", System.currentTimeMillis() / 1000).put("jti", UUID.randomUUID().toString())
        val input = base64(header.toString().toByteArray()) + "." + base64(claims.toString().toByteArray())
        val signer = Signature.getInstance("SHA256withECDSA")
        val signature = try {
            signer.initSign(entry(id).privateKey); signer.update(input.toByteArray(Charsets.US_ASCII))
            joseSignature(signer.sign())
        } catch (error: android.security.keystore.KeyPermanentlyInvalidatedException) {
            throw Mango9ApiException.SessionExpired
        } catch (error: GeneralSecurityException) { throw Mango9ApiException.SecureStorage }
        return input + "." + base64(signature)
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(bytes.size <= 32)
        return ByteArray(32 - bytes.size) + bytes
    }

    internal fun joseSignature(der: ByteArray): ByteArray {
        var index = 0

        fun next(): Int { require(index < der.size); return der[index++].toInt() and 255 }
        require(next() == 0x30 && next() == der.size - 2)

        fun integer(): ByteArray {
            require(next() == 2)
            val count = next(); require(count in 1..33 && index + count <= der.size)
            val bytes = der.copyOfRange(index, index + count); index += count
            require(bytes[0].toInt() and 128 == 0)
            require(count == 1 || bytes[0] != 0.toByte() || bytes[1].toInt() and 128 != 0)
            return unsigned(BigInteger(1, bytes))
        }
        val result = integer() + integer(); require(index == der.size); return result
    }

    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
