package com.yukisoffd.lyra.x11

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class GuiSessionsTest {
    @Test fun visibleOverlayKeepsItsSessionActiveWhenActivityLeaves() {
        val target = GuiSession("id", GuiEnvironment("a", "Linux"), 100, GuiOptions())
        val activity = Any(); val overlay = Any()
        target.setViewerVisible(activity, true); target.setViewerVisible(overlay, true)
        target.setViewerVisible(activity, false)
        assertTrue(target.visible)
        target.setViewerVisible(overlay, false)
        assertFalse(target.visible)
    }
    private class FakeProcess : GuiProcess {
        val stopped = AtomicBoolean(false)
        override val input = ByteArrayInputStream(ByteArray(0))
        override val output = ByteArrayOutputStream()
        override val isAlive get() = !stopped.get()
        override fun close() { stopped.set(true) }
    }
    private class Runtime(private val gate: CountDownLatch? = null) : GuiRuntime {
        val entered = CountDownLatch(1)
        val processes = CopyOnWriteArrayList<Pair<String, FakeProcess>>()
        private val removed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var removal: (String) -> Unit = {}
        override fun environments() = listOf(GuiEnvironment("a", "Debian"), GuiEnvironment("b", "Alpine")).filter { it.id !in removed }
        override fun launch(environmentId: String, sessionId: String, command: String): GuiProcess {
            entered.countDown(); gate?.await(3, TimeUnit.SECONDS)
            return FakeProcess().also { processes += environmentId to it }
        }
        override fun transport(sessionId: String) = com.yukisoffd.lyra.x11.rfb.TcpRfbTransport(1)
        override fun beforeEnvironmentRemoved(listener: (String) -> Unit): AutoCloseable {
            removal = { id -> removed += id; listener(id) }
            return AutoCloseable { removal = {} }
        }
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    @Test fun nativeInitializationFailureFallsBackBeforeLaunchingTheCommandOnce() {
        val base=Runtime()
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val runtime=object : GuiRuntime by base {
            override fun prepareDisplay(environmentId: String,sessionId: String,display: Int,options: GuiOptions,log: (String)->Unit): GuiDisplay? {
                calls.incrementAndGet();error("EGL unavailable")
            }
        }
        GuiSessions(runtime).use { sessions ->
            val target=sessions.start("a",GuiOptions())
            eventually { base.processes.size==1 }
            assertEquals(1,calls.get())
            assertEquals("EGL unavailable",target.displayDiagnostics()["fallback_reason"])
            assertEquals("vnc",target.displayDiagnostics()["backend"])
        }
        assertEquals(1,base.processes.size)
    }
    @Test fun explicitNativeFailureNeverLaunchesGuestAndExplicitVncSkipsNative() {
        val base=Runtime();val calls=java.util.concurrent.atomic.AtomicInteger()
        val runtime=object : GuiRuntime by base {
            override fun prepareDisplay(environmentId: String,sessionId: String,display: Int,options: GuiOptions,log: (String)->Unit): GuiDisplay? {
                calls.incrementAndGet();error("Native unavailable")
            }
        }
        GuiSessions(runtime).use { sessions ->
            val target=sessions.start("a",GuiOptions(displayBackend=DisplayBackend.NATIVE))
            eventually { target.status==GuiStatus.ERROR }
            assertTrue(base.processes.isEmpty())
            sessions.start("a",GuiOptions(displayBackend=DisplayBackend.VNC))
            eventually { base.processes.size==1 }
            assertEquals(1,calls.get())
        }
    }

    @Test fun aDisplayCannotBeReusedWhileItsOldServerIsStillClosing() {
        val base=Runtime();val closing=CountDownLatch(1);val release=CountDownLatch(1)
        val launches=java.util.concurrent.atomic.AtomicInteger()
        val runtime=object : GuiRuntime by base {
            override fun launch(environmentId: String,sessionId: String,command: String): GuiProcess {
                val number=launches.incrementAndGet()
                val process=FakeProcess()
                return object : GuiProcess by process {
                    override fun close() {
                        if(number==1) { closing.countDown();release.await(3,TimeUnit.SECONDS) }
                        process.close()
                    }
                }
            }
        }
        GuiSessions(runtime).use { sessions ->
            try {
                val old=sessions.start("a",GuiOptions())
                eventually { launches.get()==1 }
                sessions.stop(old)
                assertTrue(closing.await(3,TimeUnit.SECONDS))
                val other=sessions.start("a",GuiOptions())
                assertNotEquals(old.display,other.display)
                release.countDown()
                eventually { old.released.get() }
                assertEquals(old.display,sessions.start("a",GuiOptions()).display)
            } finally { release.countDown() }
        }
    }

    @Test fun environmentsAndDisplaysHaveSeparateLifetimes() {
        val runtime = Runtime()
        GuiSessions(runtime).use { sessions ->
            val a = sessions.start("a", GuiOptions())
            val b = sessions.start("b", GuiOptions())
            val secondA = sessions.start("a", GuiOptions())
            assertEquals(3, setOf(a.display, b.display, secondA.display).size)
            eventually { runtime.processes.size == 3 }
            runtime.removal("a")
            assertThrows(IllegalStateException::class.java) { sessions.start("a", GuiOptions()) }
            assertEquals(listOf(b.id), sessions.snapshot().map { it.id })
            assertTrue(runtime.processes.filter { it.first == "a" }.all { it.second.stopped.get() })
            assertFalse(runtime.processes.single { it.first == "b" }.second.stopped.get())
            sessions.stop(b)
            eventually { !sessions.hasRunningSessions() }
        }
        assertTrue(runtime.processes.all { it.second.stopped.get() })
    }

    @Test fun environmentRemovalWaitsForAnInflightLaunchAndClosesIt() {
        val gate = CountDownLatch(1)
        val runtime = Runtime(gate)
        val executor = Executors.newSingleThreadExecutor()
        try {
            GuiSessions(runtime).use { sessions ->
                sessions.start("a", GuiOptions())
                assertTrue(runtime.entered.await(3, TimeUnit.SECONDS))
                val removed = executor.submit { runtime.removal("a") }
                Thread.sleep(50)
                assertFalse(removed.isDone)
                gate.countDown()
                removed.get(3, TimeUnit.SECONDS)
                assertTrue(runtime.processes.single().second.stopped.get())
                assertTrue(sessions.snapshot().isEmpty())
            }
        } finally { gate.countDown(); executor.shutdownNow() }
    }

    @Test fun sessionAndCombinedFramebufferLimitsAreEnforced() {
        GuiSessions(Runtime()).use { sessions ->
            repeat(4) { sessions.start("a", GuiOptions()) }
            assertThrows(IllegalArgumentException::class.java) { sessions.start("a", GuiOptions()) }
        }
        GuiSessions(Runtime()).use { sessions ->
            repeat(2) { sessions.start("a", GuiOptions(width = 3840, height = 2160)) }
            assertThrows(IllegalArgumentException::class.java) { sessions.start("b", GuiOptions()) }
            assertThrows(IllegalStateException::class.java) { sessions.start("missing", GuiOptions()) }
        }
    }
}
