// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Factory
import org.linphone.core.Participant
import org.linphone.core.ParticipantDevice
import org.linphone.core.ParticipantDeviceListener
import org.linphone.core.StreamType
import org.linphone.ui.call.conference.model.ConferenceParticipantDeviceModel

@RunWith(AndroidJUnit4::class)
class Mango9ConferenceVideoWindowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun clearingOrDestroyingAudioOnlyParticipantNeverEntersVideoNegotiation() = fixture { f ->
        core { f.model.clearWindowId(); f.model.destroy(); f.model.destroy() }
        assertEquals(0, f.writes)
        assertTrue(f.listeners.isEmpty())
    }

    @Test fun pendingViewWaitsForStreamAndClearsItsBindingExactlyOnce() = fixture { f ->
        ui { f.model.setTextureView(f.view) }
        core { assertEquals(0, f.writes); f.available = true; f.notifyAvailable() }
        assertEquals(1, f.writes)
        core { assertSame(f.view, f.window); f.model.clearWindowId(); f.model.clearWindowId() }
        assertNull(f.window)
        assertEquals(2, f.writes)
    }

    @Test fun replacementModelKeepsItsSurfaceWhenOldModelIsDestroyed() = fixture { f ->
        lateinit var other: ConferenceParticipantDeviceModel
        lateinit var otherView: TextureView
        core { f.available = true; other = ConferenceParticipantDeviceModel(f.device) }
        try {
            ui { otherView = TextureView(instrumentation.targetContext); f.model.setTextureView(f.view) }
            core {}
            ui { other.setTextureView(otherView) }
            core { f.model.destroy(); assertSame(otherView, f.window); assertEquals(2, f.writes) }
        } finally { core { other.destroy() } }
        assertNull(f.window)
        assertEquals(3, f.writes)
    }

    @Test fun clearBeforeQueuedUiBindingCancelsThatBinding() = fixture { f ->
        withCorePaused { release ->
            ui { f.model.setTextureView(f.view) }
            release { f.model.clearWindowId() }
        }
        core { f.available = true; f.notifyAvailable() }
        assertEquals(0, f.writes)
    }

    @Test fun destroyBeforeQueuedUiBindingNeverResurrectsModel() = fixture { f ->
        withCorePaused { release ->
            ui { f.model.setTextureView(f.view) }
            release { f.model.destroy() }
        }
        ui { f.model.setTextureView(f.view) }
        core { assertTrue(f.listeners.isEmpty()) }
        assertEquals(0, f.writes)
    }

    @Test fun surfaceTextureBackedSdkBindingUsesListenerOwnershipNotViewIdentity() = fixture { f ->
        f.listenerBacked = true
        core { f.available = true }
        ui { f.model.setTextureView(f.view) }
        core { assertNotNull(f.view.surfaceTextureListener); assertNotSame(f.view, f.window); f.model.clearWindowId() }
        assertEquals(2, f.writes)
        assertNull(f.window)
        assertNull(f.view.surfaceTextureListener)
    }

    @Test fun replacedListenerOnSameViewCannotBeClearedByPreviousOwner() = fixture { f ->
        f.listenerBacked = true
        core { f.available = true }
        ui { f.model.setTextureView(f.view) }
        core {}
        val replacement = NoopSurfaceListener()
        ui { f.view.surfaceTextureListener = replacement }
        core { f.model.destroy(); assertEquals(1, f.writes) }
        assertSame(replacement, f.view.surfaceTextureListener)
        // Synthetic newer owner releases its own listener, not the destroyed model.
        ui { f.view.surfaceTextureListener = null }
    }

    private class NoopSurfaceListener : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
    }

    private inner class Fixture {
        lateinit var view: TextureView
        lateinit var model: ConferenceParticipantDeviceModel
        lateinit var device: ParticipantDevice
        val listeners = mutableListOf<ParticipantDeviceListener>()
        var available = false
        var listenerBacked = false
        var window: Any? = null
        var writes = 0

        fun notifyAvailable() = listeners.toList().forEach { it.onStreamAvailabilityChanged(device, true, StreamType.Video) }

        fun initialize() {
            val address = requireNotNull(Factory.instance().createAddress("sip:video-window@example.invalid"))
            val participant = fake<Participant> { method, _ -> if (method == "getAddress") address else error(method) }
            device = fake { method, args ->
                when (method) {
                    "getParticipant" -> participant
                    "getAddress" -> address
                    "getName" -> "Synthetic video window"
                    "getState" -> ParticipantDevice.State.Joining
                    "isInConference", "getIsMuted", "getIsSpeaking", "isScreenSharingEnabled", "getThumbnailStreamAvailability" -> false
                    "getStreamAvailability" -> available
                    "addListener" -> { listeners.add(args[0] as ParticipantDeviceListener); null }
                    "removeListener" -> { listeners.remove(args[0]); null }
                    "getNativeVideoWindowId" -> window
                    "setNativeVideoWindowId" -> {
                        writes++
                        if (listenerBacked) {
                            view.surfaceTextureListener = if (args[0] == null) null else NoopSurfaceListener()
                            window = if (args[0] == null) null else Any()
                        } else {
                            window = args[0]
                        }
                        null
                    }
                    else -> error("Unexpected device access: $method")
                }
            }
            model = ConferenceParticipantDeviceModel(device)
        }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val f = Fixture()
        ui { f.view = TextureView(instrumentation.targetContext) }
        core { f.initialize() }
        try { test(f) } finally { core { f.model.destroy() } }
    }

    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun core(action: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread { try { action() } catch (t: Throwable) { failure = t } finally { done.countDown() } }
        assertTrue("Core queue timed out", done.await(15, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
    }

    private fun withCorePaused(test: ((() -> Unit) -> Unit) -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        var beforeRelease: () -> Unit = {}
        var failure: Throwable? = null
        coreContext.postOnCoreThread {
            entered.countDown()
            try {
                check(release.await(10, TimeUnit.SECONDS)); beforeRelease()
            } catch (t: Throwable) { failure = t } finally { done.countDown() }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try { test { beforeRelease = it } } finally { release.countDown() }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
        core {}
    }

    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            when (method.name) {
                "equals" -> self === args?.get(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "Synthetic participant interface"
                else -> call(method.name, args.orEmpty())
            }
        } as T
}
