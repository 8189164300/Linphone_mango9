/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class Mango9EnrollmentParserTest {
    @Test
    fun parsesExpectedLinphoneEnrollmentSections() {
        val xml = """
            <config>
              <section name="proxy_0">
                <entry name="reg_identity"> sips:8189164300@example.com;transport=tls </entry>
              </section>
              <section name="auth_info_0">
                <entry name="username">8189164300</entry>
                <entry name="domain">example.com</entry>
                <entry name="realm">example.com</entry>
                <entry name="ha1">0123456789abcdef</entry>
              </section>
            </config>
        """.trimIndent().toByteArray()

        val enrollment = Mango9EnrollmentParser.parse(xml)

        assertEquals("sips:8189164300@example.com", enrollment.identity)
        assertEquals("8189164300", enrollment.username)
        assertEquals("example.com", enrollment.domain)
        assertEquals("example.com", enrollment.realm)
        assertEquals("0123456789abcdef", enrollment.ha1)
    }

    @Test
    fun rejectsEnrollmentWithoutRequiredAuthValues() {
        val xml = """
            <config>
              <section name="proxy_0"><entry name="reg_identity">sip:100@example.com</entry></section>
              <section name="auth_info_0"><entry name="username">100</entry></section>
            </config>
        """.trimIndent().toByteArray()

        assertThrows(Mango9ApiException.InvalidResponse::class.java) {
            Mango9EnrollmentParser.parse(xml)
        }
    }

    @Test
    fun rejectsDoctypeAndEntityDeclarations() {
        val xml = """
            <!DOCTYPE config [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <config><section name="proxy_0"><entry name="reg_identity">&xxe;</entry></section></config>
        """.trimIndent().toByteArray()

        assertThrows(Mango9ApiException.InvalidResponse::class.java) {
            Mango9EnrollmentParser.parse(xml)
        }
    }

    @Test
    fun locksOneTimeEnrollmentToProvisioningHost() {
        assertEquals(
            "https://provision.mango9.com/v1/enroll/one-time%20token?state=a%2Fb",
            Mango9Configuration.verifiedProvisioningUrl(
                "https://PROVISION.MANGO9.COM/v1/enroll/one-time%20token?state=a%2Fb#ignored",
            )?.toString(),
        )
        assertNull(Mango9Configuration.verifiedProvisioningUrl("http://provision.mango9.com/v1/enroll/token"))
        assertNull(Mango9Configuration.verifiedProvisioningUrl("https://evil.example/v1/enroll/token"))
        assertNull(Mango9Configuration.verifiedProvisioningUrl("https://user@provision.mango9.com/v1/enroll/token"))
        assertNull(Mango9Configuration.verifiedProvisioningUrl("https://provision.mango9.com:8443/v1/enroll/token"))
    }

    @Test
    fun normalizesSipIdentityWithoutDisplayNameOrParameters() {
        assertEquals(
            "sip:100@example.com",
            Mango9SessionStore.normalizedIdentity("  Alice <SIP:100@EXAMPLE.COM;transport=tls> "),
        )
    }

    @Test
    fun messagingMediaAcceptsOnlyVerifiedHttpsDownloads() {
        val media = Mango9ChatMedia.parse(
            "https://cdn.example.com/files/report?signature=abc" +
                "&ffName=Quarterly%20Report.pdf&ffExt=pdf&ffType=application%2Fpdf",
        ).single()

        assertEquals("Quarterly Report.pdf", media.name)
        assertEquals("application/pdf", media.mimeType)
        assertEquals(Mango9ChatMedia.Kind.File, media.kind)
        assertEquals("https://cdn.example.com/files/report?signature=abc", media.url)
        assertEquals(emptyList<Mango9ChatMedia>(), Mango9ChatMedia.parse("http://cdn.example.com/file.pdf"))
        assertEquals(emptyList<Mango9ChatMedia>(), Mango9ChatMedia.parse("https://user@cdn.example.com/file.pdf"))
        assertEquals(emptyList<Mango9ChatMedia>(), Mango9ChatMedia.parse("https://cdn.example.com:8443/file.pdf"))
    }
}
