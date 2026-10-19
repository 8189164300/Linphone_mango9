// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class Mango9AttachmentTypeTest {
    @Test fun compatibleMp4AudioKeepsAllOriginalAacAndAlacTracks() {
        assertTrue(Mango9MediaType.canPreserveMp4Audio(listOf("audio/mp4a-latm")))
        assertTrue(Mango9MediaType.canPreserveMp4Audio(listOf("audio/alac")))
        assertTrue(Mango9MediaType.canPreserveMp4Audio(listOf("audio/mp4a-latm", "audio/alac")))
        assertTrue(Mango9MediaType.canPreserveMp4Audio(listOf("audio/mp4a-latm", "audio/mp4a-latm")))
        assertFalse(Mango9MediaType.canPreserveMp4Audio(emptyList()))
        assertFalse(Mango9MediaType.canPreserveMp4Audio(listOf("audio/opus")))
        assertFalse(Mango9MediaType.canPreserveMp4Audio(listOf("audio/mp4a-latm", "video/avc")))
    }

    @Test fun explicitAudioTypeWinsOverMp4AndImageLookingNames() {
        for (name in listOf("voice.mp4", "voice.jpg", "voice.MOV", "voice.webm")) {
            val media = parse(name, "audio/mp4")
            assertEquals(Mango9ChatMedia.Kind.Audio, media.kind)
            assertEquals("audio/mp4", media.mimeType)
        }
    }

    @Test fun mimeCaseAndParametersDoNotChangeAudioSelection() {
        val media = parse("voice.mp4", " Audio/MP4 ; codecs=mp4a.40.2 ")
        assertEquals(Mango9ChatMedia.Kind.Audio, media.kind)
        assertEquals("audio/mp4", media.mimeType)
    }

    @Test fun explicitVideoImageAndDocumentTypesAlsoOverrideFilenameHints() {
        assertEquals(Mango9ChatMedia.Kind.Video, parse("movie.jpg", "video/mp4").kind)
        assertEquals(Mango9ChatMedia.Kind.Image, parse("photo.mp4", "image/jpeg").kind)
        assertEquals(Mango9ChatMedia.Kind.File, parse("document.mp4", "application/pdf").kind)
    }

    @Test fun missingOrGenericTypeUsesTheExtensionButKeepsDownloadsUnchanged() {
        for (mime in listOf("", "application/octet-stream", "invalid mime")) {
            val audio = parse("voice.m4a", mime)
            assertEquals(Mango9ChatMedia.Kind.Audio, audio.kind)
            assertEquals("audio/mp4", audio.mimeType)
            assertEquals("https://cdn.example.com/item?signature=synthetic", audio.url)
            assertEquals(Mango9ChatMedia.Kind.Video, parse("movie.mp4", mime).kind)
            assertEquals(Mango9ChatMedia.Kind.File, parse("document.bin", mime).kind)
        }
    }

    @Test fun invalidTypesCannotBecomeHeadersOrWildcards() {
        for (mime in listOf("audio/*", "audio/", "audio/mp4\r\nX-Header: injected", "not a type", "/mp4")) {
            assertNull(Mango9MediaType.normalized(mime))
            assertEquals(Mango9ChatMedia.Kind.File, parse("opaque.bin", mime).kind)
        }
        assertEquals("audio/x-matroska", parse("voice.mka", "").mimeType)
        assertEquals(Mango9ChatMedia.Kind.Audio, parse("voice.mka", "").kind)
        assertEquals("audio/ogg", parse("voice.opus", "").mimeType)
        assertEquals(Mango9ChatMedia.Kind.Image, parse("photo.heic", "").kind)
    }

    private fun parse(name: String, mime: String): Mango9ChatMedia = Mango9ChatMedia.parse(
        "https://cdn.example.com/item?signature=synthetic&ffName=" + URLEncoder.encode(name, "UTF-8") +
            "&ffType=" + URLEncoder.encode(mime, "UTF-8") + "&ffExt=" + name.substringAfterLast('.'),
    ).single()
}
