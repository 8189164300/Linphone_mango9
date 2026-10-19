/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.StrictMode
import android.view.LayoutInflater
import android.widget.ImageView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.dispose
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.BR
import org.linphone.R
import org.linphone.contacts.AbstractAvatarModel
import org.linphone.contacts.AvatarGenerator
import org.linphone.contacts.ContactAvatarDrawable
import org.linphone.contacts.ContactInitials
import org.linphone.ui.assistant.AssistantActivity
import org.linphone.utils.loadAvatarWithCoil
import org.linphone.utils.ImageUtils

/** Synthetic names/photos only; no contact-provider writes, calls or accounts. */
@RunWith(AndroidJUnit4::class)
class Mango9ContactAvatarInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun scrollingFallbacksDoNotReadWriteOrEncodeContactFiles() {
        val violations = ConcurrentLinkedQueue<String>()
        ui {
            val image = ImageView(themed())
            val model = model("Warm Up")
            image.loadAvatarWithCoil(model)
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            image.drawable.setBounds(0, 0, 96, 96)
            image.drawable.draw(canvas)
            val prior = StrictMode.getThreadPolicy()
            try {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
                        .penaltyListener({ it.run() }) { violations.add(it.toString()) }.build(),
                )
                repeat(1_000) { index ->
                    model.initials.value = "${'A' + index % 26}${'A' + index / 26 % 26}"
                    image.loadAvatarWithCoil(model)
                    assertTrue(image.drawable is ContactAvatarDrawable)
                    image.drawable.setBounds(0, 0, 96, 96)
                    image.drawable.draw(canvas)
                }
            } finally {
                StrictMode.setThreadPolicy(prior)
                image.loadAvatarWithCoil(null)
                bitmap.recycle()
            }
        }
        instrumentation.waitForIdleSync()
        assertTrue("Fallback binding performed IO: $violations", violations.isEmpty())
    }

    @Test fun allFiveXmlSizesObserveInitialsAndRecycleWithoutOldModelUpdates() = ui {
        val owner = Owner()
        val themed = themed()
        val layouts = listOf(
            R.layout.contact_avatar_small, R.layout.contact_avatar, R.layout.contact_avatar_medium,
            R.layout.contact_avatar_big, R.layout.contact_avatar_huge
        )
        for (layout in layouts) {
            val binding = DataBindingUtil.inflate<ViewDataBinding>(LayoutInflater.from(themed), layout, null, false)
            binding.lifecycleOwner = owner
            val old = model("Alice Brown")
            binding.setVariable(BR.model, old)
            binding.executePendingBindings()
            val image = binding.root.findViewById<ImageView>(R.id.avatar)
            val initial = pixels(image)
            old.initials.value = "WW"
            binding.executePendingBindings()
            assertFalse("Same-model changes must redraw layout $layout", initial.contentEquals(pixels(image)))
            val replacement = model("Maya Lane")
            binding.setVariable(BR.model, replacement)
            binding.executePendingBindings()
            val expected = pixels(image)
            old.initials.value = "ZZ"
            old.forceConversationIcon.value = true
            binding.executePendingBindings()
            assertArrayEquals(expected, pixels(image))
            replacement.skipInitials.value = true
            binding.executePendingBindings()
            assertFalse(expected.contentEquals(pixels(image)))
            binding.setVariable(BR.model, null)
            binding.executePendingBindings()
            assertNull("Recycled null model must clear $layout", image.drawable)
            binding.unbind()
        }
        owner.registry.currentState = Lifecycle.State.DESTROYED
    }

    @Test fun emptyNumericNamesAndPlatformIconsHaveVisiblePersonFallback() = ui {
        val themed = themed()
        val anonymous = AvatarGenerator(themed).setInitials("").setAvatarSize(128).buildBitmap(false)
        try {
            val expected = pixels(anonymous)
            for (name in listOf("700", "+1 (202) 555-0142", "sip:700@example.invalid", "👩🏽‍💻")) {
                val bitmap = AvatarGenerator(themed).setInitials(ContactInitials.fromName(name)).setAvatarSize(128).buildBitmap(false)
                try { assertArrayEquals(expected, pixels(bitmap)) } finally { bitmap.recycle() }
            }
            assertTrue("Person fallback must contain visible ink", expected.toSet().size > 8)
            assertEquals(Color.TRANSPARENT, anonymous.getPixel(0, 0))
        } finally { anonymous.recycle() }
        val transparent = AvatarGenerator(themed).setInitials("").setAvatarSize(128).buildBitmap(true)
        try {
            assertEquals(Color.TRANSPARENT, transparent.getPixel(64, 10))
            assertTrue(pixels(transparent).any { Color.alpha(it) > 0 })
        } finally { transparent.recycle() }
    }

    @Test fun avatarsScaleAcrossBoundsFontsAndThemesWithoutClippedLetters() = ui {
        for (night in listOf(false, true)) for (scale in listOf(1f, 1.8f)) {
            val themed = themed(night, scale)
            for (label in listOf("WW", "ԱՊ", "E\u0301Z", "SSS")) for (edge in listOf(32, 96, 320)) {
                val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
                ContactAvatarDrawable(themed, label, 96, 96f * scale, true).apply {
                    setBounds(0, 0, edge, edge)
                    draw(Canvas(bitmap))
                }
                try {
                    val occupied = pixels(bitmap).indices.filter { Color.alpha(bitmap.getPixel(it % edge, it / edge)) > 0 }
                    assertTrue("Missing initials $label", occupied.isNotEmpty())
                    assertTrue("Clipped horizontal initials $label", occupied.all { it % edge in (edge / 10)..(edge - edge / 10) })
                    assertTrue("Clipped vertical initials $label", occupied.all { it / edge in (edge / 10)..(edge - edge / 10) })
                } finally { bitmap.recycle() }
            }
        }
        val light = AvatarGenerator(themed(false)).setInitials("AB").buildBitmap(false)
        val dark = AvatarGenerator(themed(true)).setInitials("AB").buildBitmap(false)
        try {
            assertFalse("Theme changes must not reuse stale cached colors", pixels(light).contentEquals(pixels(dark)))
        } finally { light.recycle(); dark.recycle() }
    }

    @Test fun specialGroupAndConferenceFallbacksRemainDistinctAndReactive() = ui {
        val image = ImageView(themed())
        val model = model("")
        image.loadAvatarWithCoil(model)
        val person = pixels(image)
        model.defaultToConversationIcon.value = true
        image.loadAvatarWithCoil(model)
        val group = pixels(image)
        model.forceConferenceIcon.value = true
        image.loadAvatarWithCoil(model)
        assertFalse(group.contentEquals(person))
        assertFalse(group.contentEquals(pixels(image)))
        image.loadAvatarWithCoil(null)
    }

    @Test fun notificationPhotosDecodeSmallSoftwarePixelsWithoutFullSizeCopy() {
        val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "avatar-icon-test-").toFile()
        val photo = File(root, "synthetic.png")
        val original = Bitmap.createBitmap(1_024, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        try {
            photo.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { original.recycle() }
        try {
            for (path in listOf(photo.absolutePath, photo.toURI().toString())) {
                val decoded = ImageUtils.getBitmap(context, path)!!
                try {
                    assertEquals(256, decoded.width); assertEquals(128, decoded.height)
                    assertTrue(decoded.config != Bitmap.Config.HARDWARE)
                    assertTrue(decoded.allocationByteCount <= 256 * 256 * 8)
                    assertEquals(Color.MAGENTA, decoded.getPixel(128, 64))
                } finally { decoded.recycle() }
                val round = ImageUtils.getBitmap(context, path, round = true)!!
                try {
                    assertEquals(128, round.width); assertEquals(128, round.height)
                    assertEquals(Color.TRANSPARENT, round.getPixel(0, 0))
                    assertEquals(Color.MAGENTA, round.getPixel(64, 64))
                } finally { round.recycle() }
            }
            assertNull(ImageUtils.getBitmap(context, ""))
            assertNull(ImageUtils.getBitmap(context, File(root, "missing.png").absolutePath))
        } finally { check(photo.delete()); check(root.delete()) }
    }

    @Test fun photosDecodeAtBoundedSizeThenFailureAndNullReuseCannotRestoreOldAvatar() {
        val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "avatar-test-").toFile()
        val photo = File(root, "synthetic.png")
        val bitmap = Bitmap.createBitmap(1_024, 1_024, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        try { photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        try {
            // This isolated photo test has no SIP account. MainActivity would
            // route to onboarding and stop the host lifecycle before Coil runs.
            ActivityScenario.launch(AssistantActivity::class.java).use { scenario ->
                lateinit var image: ImageView
                lateinit var dialog: Dialog
                scenario.onActivity { activity ->
                    image = ImageView(activity)
                    dialog = Dialog(activity).apply { setContentView(image); show(); window!!.setLayout(180, 180) }
                    val person = model("Alice Brown").apply { picturePath.value = photo.absolutePath }
                    image.loadAvatarWithCoil(person, size = 64f)
                }
                try {
                    await { image.drawable != null && pixels(image).any { it == Color.MAGENTA } }
                    ui {
                        assertTrue(image.drawable.intrinsicWidth <= 64)
                        assertTrue(image.drawable.intrinsicHeight <= 64)
                        val missing = model("Old Person").apply { picturePath.value = File(root, "missing.png").absolutePath }
                        image.loadAvatarWithCoil(missing)
                        image.loadAvatarWithCoil(model("New Person"))
                    }
                    var expected = IntArray(0)
                    ui { expected = pixels(image) }
                    instrumentation.waitForIdleSync()
                    ui { assertArrayEquals(expected, pixels(image)); image.loadAvatarWithCoil(null); assertNull(image.drawable) }
                    instrumentation.waitForIdleSync()
                    ui { assertNull(image.drawable) }
                } finally { ui { image.dispose(); dialog.dismiss() } }
            }
        } finally {
            check(photo.delete())
            check(root.delete())
        }
    }

    private fun model(name: String) = object : AbstractAvatarModel() {}.apply {
        initials.value = ContactInitials.fromName(name)
        picturePath.value = ""
    }

    private fun themed(night: Boolean = false, scale: Float = 1f): Context {
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            fontScale = scale
        }
        return ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_Linphone)
    }

    private fun pixels(image: ImageView): IntArray {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        return try {
            image.drawable.setBounds(0, 0, 96, 96)
            image.drawable.draw(Canvas(bitmap))
            pixels(bitmap)
        } finally { bitmap.recycle() }
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            var done = false
            ui { done = condition() }
            if (done) return
            Thread.sleep(30)
        } while (System.nanoTime() < deadline)
        fail("Avatar request did not complete")
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle = registry
    }
}
