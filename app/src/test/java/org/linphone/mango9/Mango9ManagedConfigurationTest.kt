/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class Mango9ManagedConfigurationTest {
    @Test
    fun snapshotUsesTheSameThreeKeysAsIosAndIgnoresInvalidValues() {
        val snapshot = Mango9ManagedConfigurationSnapshot.from(
            mapOf(
                "xmlConfig" to "<config />",
                "rootCa" to 42,
                "configUri" to "   ",
                "unrelated" to "ignored",
            ),
        )

        assertEquals("<config />", snapshot.xmlConfig)
        assertNull(snapshot.rootCa)
        assertNull(snapshot.configUri)
    }

    @Test
    fun snapshotSignaturesAreStableAndIncludeEveryManagedValue() {
        val first = Mango9ManagedConfigurationSnapshot.from(
            mapOf("xmlConfig" to "<config />", "rootCa" to "certificate"),
        )
        val same = Mango9ManagedConfigurationSnapshot.from(
            linkedMapOf("rootCa" to "certificate", "xmlConfig" to "<config />"),
        )
        val changed = same.copy(configUri = "https://provision.mango9.com/v1/device")

        assertEquals(first.signature, same.signature)
        assertNotEquals(first.signature, changed.signature)
        assertEquals(first.xmlSignature, same.xmlSignature)
        assertNotEquals(first.coreSignature, changed.coreSignature)
    }

    @Test
    fun parserFindsNamespacedLinphoneEntryKeysWithoutReadingValues() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <config xmlns="http://www.linphone.org/xsds/lpconfig.xsd">
              <section name="ui">
                <entry name="disable_chat_feature">1</entry>
              </section>
              <section name="sip">
                <entry name="verify_server_certs">1</entry>
                <entry name="verify_server_cn">1</entry>
              </section>
            </config>
        """.trimIndent()

        assertEquals(
            linkedSetOf(
                Mango9ManagedConfigEntryKey("ui", "disable_chat_feature"),
                Mango9ManagedConfigEntryKey("sip", "verify_server_certs"),
                Mango9ManagedConfigEntryKey("sip", "verify_server_cn"),
            ),
            Mango9ManagedConfigXmlParser.entryKeys(xml),
        )
    }

    @Test
    fun parserRejectsExternalEntityDeclarations() {
        val xml = """
            <!DOCTYPE config [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <config><section name="ui"><entry name="value">&secret;</entry></section></config>
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            Mango9ManagedConfigXmlParser.entryKeys(xml)
        }
    }

    @Test
    fun encryptedStateCodecRoundTripsAllConfigMetadata() {
        val backup = Mango9ManagedConfigEntryBackup(
            section = "auth_info_0",
            key = "ha1",
            existed = true,
            value = "line one\nline two.=+/",
            overwrite = true,
            skip = false,
        )

        assertEquals(backup, Mango9ManagedConfigBackupCodec.decode(Mango9ManagedConfigBackupCodec.encode(backup)))
        assertEquals(
            backup.entryKey,
            Mango9ManagedConfigBackupCodec.decodeKey(Mango9ManagedConfigBackupCodec.encodeKey(backup.entryKey)),
        )
    }
}
