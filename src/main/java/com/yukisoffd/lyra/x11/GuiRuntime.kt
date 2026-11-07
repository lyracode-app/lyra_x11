package com.yukisoffd.lyra.x11

import java.io.InputStream
import java.io.OutputStream
import com.yukisoffd.lyra.x11.rfb.RfbTransport

/** The only boundary to the container owner. No rootfs paths, mounts or distro logic in the viewer. */
interface GuiRuntime {
    fun environments(): List<GuiEnvironment>
    fun launch(environmentId: String, sessionId: String, command: String): GuiProcess
    fun transport(sessionId: String): RfbTransport
    fun prepareMedia(environmentId: String, sessionId: String, options: GuiOptions, log: (String) -> Unit): GuiMedia = GuiMedia.None
    fun prepareDisplay(environmentId: String, sessionId: String, display: Int, options: GuiOptions, log: (String) -> Unit): GuiDisplay? = null
    /** Withdraw the environment from environments() before invoking this synchronous shutdown barrier. */
    fun beforeEnvironmentRemoved(listener: (String) -> Unit): AutoCloseable
}

data class GuiEnvironment(val id: String, val name: String)

interface GuiProcess : AutoCloseable {
    // Declare the owned lifecycle method here. Inheriting only AutoCloseable.close makes
    // Android desugaring inject generic ExecutorService handling into monitor-protected cleanup.
    override fun close()
    val input: InputStream
    val output: OutputStream
    val isAlive: Boolean
}

data class GuiOptions(
    val width: Int = 1280,
    val height: Int = 720,
    val framesPerSecond: Int = 30,
    val desktopCommand: String = "startxfce4",
    val appMode: Boolean = false,
    val audio: AudioBackend = AudioBackend.AUTO,
    val graphics: GraphicsBackend = GraphicsBackend.AUTO,
    val displayBackend: DisplayBackend = DisplayBackend.AUTO,
) {
    init {
        require(width in 320..3840 && height in 240..2160 && width.toLong() * height <= 8_294_400)
        require(framesPerSecond in 5..120) { "fps must be between 5 and 120" }
        require(desktopCommand.isNotBlank() && '\u0000' !in desktopCommand)
    }
}

enum class AudioBackend(val id: String) { AUTO("auto"), PULSEAUDIO("pulseaudio"), PIPEWIRE("pipewire"), OFF("off") }
enum class GraphicsBackend(val id: String) { AUTO("auto"), VIRGL("virgl"), SOFTWARE("software") }
enum class DisplayBackend(val id: String) { AUTO("auto"), NATIVE("native"), VNC("vnc") }
