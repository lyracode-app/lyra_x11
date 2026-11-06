package com.yukisoffd.lyra.x11

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class GuiScreenshot(val uri: String, val width: Int, val height: Int)

object X11Screenshots {
    /** Captures the live Linux framebuffer directly, without shell commands or Android controls. */
    fun save(context: Context, session: GuiSession): GuiScreenshot {
        val bitmap = session.nativeDisplay?.capture() ?: run {
            val buffer = session.framebuffer ?: error("GUI_FRAME_NOT_READY")
            synchronized(buffer) {
            Bitmap.createBitmap(buffer.pixels, buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
            }
        }
        val name = "Lyra-GUI-${System.currentTimeMillis()}.png"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LyraCode")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create screenshot")
                try {
                    resolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        ?: error("Cannot write screenshot")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (error: Exception) { resolver.delete(uri, null, null); throw error }
                return GuiScreenshot(uri.toString(), bitmap.width, bitmap.height)
            }
            val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "lyra_x11").apply { mkdirs() }
            val file = File(directory, name)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return GuiScreenshot(file.absolutePath, bitmap.width, bitmap.height)
        } finally { bitmap.recycle() }
    }
}
