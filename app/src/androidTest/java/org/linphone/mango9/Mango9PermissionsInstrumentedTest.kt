/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.os.Build
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.databinding.AssistantPermissionsFragmentBinding
import org.linphone.ui.assistant.viewmodel.PermissionsViewModel

@RunWith(AndroidJUnit4::class)
class Mango9PermissionsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val permissions = arrayOf(
        Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS,
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.ACCESS_LOCAL_NETWORK
    )

    @Test fun denialDoesNotLeaveLaterPermissionRowsStaleAndRevocationsRefresh() = instrumentation.runOnMainSync {
        val model = PermissionsViewModel()
        val checked = mutableListOf<String>()
        assertFalse(model.refreshPermissions(permissions) { checked += it; it != permissions.first() })
        assertEquals(permissions.toList(), checked)
        assertEquals(Build.VERSION.SDK_INT < 33, model.postNotificationsPermissionGranted.value)
        assertEquals(true, model.readContactsPermissionGranted.value)
        assertEquals(true, model.recordAudioPermissionGranted.value)
        assertEquals(true, model.cameraPermissionGranted.value)
        assertEquals(true, model.accessLocalNetworkPermissionGranted.value)
        assertFalse(model.refreshPermissions(permissions) { it == permissions.first() })
        assertEquals(true, model.postNotificationsPermissionGranted.value)
        assertEquals(false, model.readContactsPermissionGranted.value)
        assertEquals(false, model.recordAudioPermissionGranted.value)
        assertEquals(false, model.cameraPermissionGranted.value)
        assertEquals(Build.VERSION.SDK_INT < 37, model.accessLocalNetworkPermissionGranted.value)
        assertTrue(model.refreshPermissions(permissions) { true })
    }

    @Test fun permissionsAbsentOnOlderAndroidDoNotShowAnUnrequestableRow() = instrumentation.runOnMainSync {
        val model = PermissionsViewModel()
        assertTrue(
            model.refreshPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)) {
            true
        }
        )
        assertEquals(true, model.postNotificationsPermissionGranted.value)
        assertEquals(true, model.accessLocalNetworkPermissionGranted.value)
    }

    @Test fun explanationsWrapWithoutOverlappingAndOptionalActionsRemainAvailable() = instrumentation.runOnMainSync {
        for (night in listOf(false, true)) for (font in listOf(1f, 1.8f)) for (width in listOf(320, 360)) {
            val context = ContextThemeWrapper(
                instrumentation.targetContext.createConfigurationContext(
                    Configuration(instrumentation.targetContext.resources.configuration).apply {
                    fontScale = font
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                ),
                    R.style.Theme_Linphone
            )
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle = registry
            }
            owner.registry.currentState = Lifecycle.State.RESUMED
            val model = PermissionsViewModel()
            val binding = AssistantPermissionsFragmentBinding.inflate(LayoutInflater.from(context))
            var skipped = 0; var requested = 0
            binding.lifecycleOwner = owner; binding.viewModel = model
            binding.setSkipClickListener { skipped++ }; binding.setGrantAllClickListener { requested++ }
            try {
                for (mixed in listOf(false, true)) {
                    model.refreshPermissions(permissions) {
                        mixed && it in listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
                    }
                    // Exercise the future-platform row's geometry without
                    // pretending that Android35 can request that permission.
                    model.setPermissionGranted(Manifest.permission.ACCESS_LOCAL_NETWORK, false)
                    model.setPermissionGranted(Manifest.permission.POST_NOTIFICATIONS, mixed)
                    binding.executePendingBindings()
                    val root = binding.root as ScrollView
                    val density = context.resources.displayMetrics.density
                    val w = (width * density).toInt(); val h = (600 * density).toInt()
                    root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, w, h)
                    val content = root.getChildAt(0)
                    var previousBottom = 0
                    for (id in listOf(R.id.title, R.id.subtitle, R.id.post_notifications_title, R.id.read_contacts_title, R.id.record_audio_title, R.id.access_camera_title, R.id.access_local_network_title, R.id.skip, R.id.grant_all_permissions)) {
                        val label = root.findViewById<TextView>(id)
                        if (label.visibility != View.VISIBLE) continue
                        val bounds = Rect(); label.getDrawingRect(bounds); root.offsetDescendantRectToMyCoords(label, bounds)
                        assertTrue("No overlap: ${label.text}, $width/$font/$night/$mixed", bounds.top >= previousBottom)
                        assertTrue(bounds.left >= 0 && bounds.right <= w)
                        assertTrue(bounds.bottom <= content.height)
                        val layout = label.layout!!
                        assertEquals(label.text.length, layout.getLineEnd(layout.lineCount - 1))
                        assertTrue("Complete explanation: ${label.text}", layout.getLineBottom(layout.lineCount - 1) <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                        for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                        previousBottom = bounds.bottom
                    }
                    assertEquals(if (mixed) View.GONE else View.VISIBLE, binding.postNotificationsTitle.visibility)
                    assertEquals(View.VISIBLE, binding.readContactsTitle.visibility)
                    assertTrue(binding.skip.performClick()); assertTrue(binding.grantAllPermissions.performClick())
                    if (width == 320 && font == 1.8f && !mixed) {
                        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        root.background?.constantState?.newDrawable()?.apply {
                            setBounds(0, 0, content.width, content.height); draw(canvas)
                        }
                        content.draw(canvas)
                        File(instrumentation.targetContext.getExternalFilesDir(null), "permissions-large-$night.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
                assertEquals(2, skipped); assertEquals(2, requested)
            } finally {
                binding.unbind(); owner.registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }
}
