/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

object Mango9EnrollmentParser {
    fun parse(data: ByteArray): Mango9SipEnrollment {
        val source = data.toString(Charsets.UTF_8)
        if (source.contains("<!DOCTYPE", ignoreCase = true) ||
            source.contains("<!ENTITY", ignoreCase = true)
        ) {
            throw Mango9ApiException.InvalidResponse
        }

        val values = mutableMapOf<String, MutableMap<String, String>>()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)

        val handler = object : DefaultHandler() {
            private var section: String? = null
            private var entry: String? = null
            private val text = StringBuilder()

            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                InputSource(StringReader(""))

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                when (qName) {
                    "section" -> section = attributes.getValue("name")
                    "entry" -> {
                        entry = attributes.getValue("name")
                        text.clear()
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (entry != null) text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "entry" -> {
                        val currentSection = section
                        val currentEntry = entry
                        if (!currentSection.isNullOrBlank() && !currentEntry.isNullOrBlank()) {
                            values.getOrPut(currentSection) { mutableMapOf() }[currentEntry] = text.toString().trim()
                        }
                        entry = null
                        text.clear()
                    }
                    "section" -> section = null
                }
            }
        }

        try {
            factory.newSAXParser().parse(ByteArrayInputStream(data), handler)
        } catch (error: Mango9ApiException) {
            throw error
        } catch (_: Exception) {
            throw Mango9ApiException.InvalidResponse
        }

        val proxy = values["proxy_0"] ?: throw Mango9ApiException.InvalidResponse
        val auth = values["auth_info_0"] ?: throw Mango9ApiException.InvalidResponse
        val identity = Mango9SessionStore.normalizedIdentity(proxy["reg_identity"])
            ?: throw Mango9ApiException.InvalidResponse
        return Mango9SipEnrollment(
            identity = identity,
            username = auth.requiredValue("username"),
            domain = auth.requiredValue("domain"),
            realm = auth.requiredValue("realm"),
            ha1 = auth.requiredValue("ha1"),
        )
    }

    private fun setFeatureIfSupported(factory: SAXParserFactory, feature: String, enabled: Boolean) {
        try {
            factory.setFeature(feature, enabled)
        } catch (_: Exception) {
            // The explicit DOCTYPE/ENTITY rejection and empty entity resolver remain active.
        }
    }

    private fun Map<String, String>.requiredValue(key: String): String =
        get(key)?.trim()?.takeIf { it.isNotEmpty() } ?: throw Mango9ApiException.InvalidResponse
}
