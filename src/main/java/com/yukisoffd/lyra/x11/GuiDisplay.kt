package com.yukisoffd.lyra.x11

import android.graphics.Bitmap
import android.view.Surface

/** Input is independent of the display transport. X11 keysyms also support IME Unicode text. */
interface GuiInput {
    fun key(keysym: Int, down: Boolean)
    fun text(text: String)
    fun pointer(x: Int, y: Int, mask: Int, motionOnly: Boolean = false)
    /** Android wheel units, positive right and up. */
    fun scroll(x: Int, y: Int, mask: Int, horizontal: Float, vertical: Float)
}

interface GuiDisplay : GuiInput, AutoCloseable {
    override fun close()
    val authority: String
    val isAlive: Boolean
    val hasFrame: Boolean
    val pointerLocked: Boolean
    fun relativePointer(dx: Float, dy: Float, mask: Int)
    fun relativeScroll(horizontal: Float, vertical: Float)
    fun scanCode(code: Int, down: Boolean)
    fun surface(id: Long, surface: Surface?)
    fun capture(): Bitmap
    fun diagnostics(): Map<String, Any>
}
