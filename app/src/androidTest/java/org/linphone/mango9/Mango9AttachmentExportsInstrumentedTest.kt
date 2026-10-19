// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Mango9AttachmentExportsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun media(name: String, mime: String, kind: Mango9ChatMedia.Kind) = Mango9ChatMedia("", "", name, mime, kind)

    @Test fun galleryUsesScopedStorageAndPreservesOriginalBytes() = runBlocking {
        val source = File(context.cacheDir, "export-test-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        try { source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        try {
            val item = media(source.name, "image/png", Mango9ChatMedia.Kind.Image)
            val uri = Mango9AttachmentExports.saveGallery(context, source, item) { true }
            try {
                assertArrayEquals(source.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE), null, null, null)!!.use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals("image/png", it.getString(1))
                }
            } finally { context.contentResolver.delete(uri, null, null) }
        } finally { source.delete() }
    }

    @Test fun externalViewerReceivesReadOnlyContentUriWithOriginalFileName() = runBlocking {
        val source = File(context.cacheDir, "export-test-${UUID.randomUUID()}.txt").apply {
            writeText("Local export regression")
        }
        var copy: File? = null
        try {
            val item = media("example.txt", "text/plain", Mango9ChatMedia.Kind.File)
            val exported = Mango9AttachmentExports.externalCopy(context, source, item) { true }
            copy = exported
            val intent = Mango9AttachmentExports.openIntent(context, exported, item)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data!!.scheme)
            assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            source.delete()
            assertEquals(
                "Local export regression",
                context.contentResolver.openInputStream(intent.data!!)!!.bufferedReader().use {
                it.readText()
            }
            )
            val save = Mango9AttachmentExports.createDocumentIntent(item)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, save.action)
            assertEquals("example.txt", save.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals("text/plain", save.type)
            assertEquals("attachment.bin", Mango9AttachmentExports.fileName(media("..", "application/octet-stream", Mango9ChatMedia.Kind.File)))
        } finally { source.delete(); copy?.parentFile?.deleteRecursively() }
    }

    @Test fun accountSwitchStopsCopyBeforeAnotherChunkIsExported() = runBlocking {
        val source = ByteArrayInputStream(ByteArray(100_000) { 7 })
        val output = ByteArrayOutputStream()
        var checks = 0
        try {
            Mango9AttachmentExports.copy(source, output) { ++checks == 1 }
            fail("A changed login must stop copying")
        } catch (_: IllegalStateException) { assertTrue(output.size() in 1..32768) }
    }

    @Test fun replayActuallyRoutesToLoudspeakerWithoutChangingCallMode() {
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue(
            Mango9MediaPlaybackRoute.preferSpeaker(
                audio.mode,
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
            it.type
        }
            )
        )
        val source = File(context.cacheDir, "export-audio-test-${UUID.randomUUID()}.m4a")
        Mango9MediaTestFixtures.createAudio(source)
        var player: MediaPlayer? = null
        var route: Mango9MediaPlaybackRoute? = null
        try {
            instrumentation.runOnMainSync {
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(source.path)
                    route = Mango9MediaPlaybackRoute(context, this)
                    prepare(); start()
                }
            }
            android.os.SystemClock.sleep(300)
            instrumentation.runOnMainSync {
                assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, player!!.preferredDevice!!.type)
                assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, player!!.routedDevice!!.type)
                assertEquals(AudioManager.MODE_NORMAL, audio.mode)
            }
        } finally {
            instrumentation.runOnMainSync { route?.close(); player?.release() }
            source.delete()
        }
    }
}
