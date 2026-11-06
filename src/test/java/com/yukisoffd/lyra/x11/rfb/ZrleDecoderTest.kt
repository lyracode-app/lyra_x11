package com.yukisoffd.lyra.x11.rfb

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.*
import org.junit.Test

class ZrleDecoderTest {
    @Test fun multipleViewersTrackDamageIndependently() {
        val pixels = PixelBuffer(10, 10)
        pixels.commitFrame()
        val a = pixels.revision; val b = pixels.revision
        pixels.mark(2, 3, 2, 4); pixels.commitFrame()
        assertEquals(Damage(2, 3, 2, 4), pixels.damageAfter(a))
        assertEquals(Damage(2, 3, 2, 4), pixels.damageAfter(b))
        assertNull(pixels.damageAfter(pixels.revision))
        pixels.mark(0, 0, 1, 1); pixels.commitFrame()
        assertEquals(Damage(0, 0, 10, 10), pixels.damageAfter(a))
    }
    private val red = 0xffff0000.toInt()
    private val blue = 0xff0000ff.toInt()
    private fun red() = byteArrayOf(0, 0, -1)
    private fun blue() = byteArrayOf(-1, 0, 0)
    private fun compress(bytes: ByteArray, deflater: Deflater): ByteArray {
        deflater.setInput(bytes)
        val result = ByteArrayOutputStream()
        val scratch = ByteArray(256)
        do {
            val count = deflater.deflate(scratch, 0, scratch.size, Deflater.SYNC_FLUSH)
            result.write(scratch, 0, count)
        } while (count == scratch.size)
        return result.toByteArray()
    }
    private fun decode(tile: ByteArray, w: Int, h: Int): PixelBuffer {
        val target = PixelBuffer(w, h)
        val deflater = Deflater()
        try { ZrleDecoder().use { it.decode(compress(tile, deflater), target, 0, 0, w, h) } }
        finally { deflater.end() }
        return target
    }
    @Test fun rawAndSolidPixels() {
        assertArrayEquals(intArrayOf(red, blue), decode(byteArrayOf(0) + red() + blue(), 2, 1).pixels)
        assertArrayEquals(IntArray(9) { red }, decode(byteArrayOf(1) + red(), 3, 3).pixels)
    }
    @Test fun packedPaletteRowsHaveIndependentPadding() {
        val target = decode(byteArrayOf(2) + red() + blue() + byteArrayOf(0x40, 0xa0.toByte()), 3, 2)
        assertArrayEquals(intArrayOf(red, blue, red, blue, red, blue), target.pixels)
    }
    @Test fun twoAndFourBitPalettes() {
        assertArrayEquals(intArrayOf(red, blue, red), decode(byteArrayOf(3) + red() + blue() + red() + byteArrayOf(0x18), 3, 1).pixels)
        val colors = red() + blue() + red() + blue() + red()
        assertArrayEquals(intArrayOf(red, blue, red), decode(byteArrayOf(5) + colors + byteArrayOf(0x01, 0x40), 3, 1).pixels)
    }
    @Test fun plainAndPaletteRunLengths() {
        assertArrayEquals(IntArray(260) { red }, decode(byteArrayOf(128.toByte()) + red() + byteArrayOf(255.toByte(), 4), 52, 5).pixels)
        assertArrayEquals(intArrayOf(red, red, blue), decode(byteArrayOf(130.toByte()) + red() + blue() + byteArrayOf(128.toByte(), 1, 1), 3, 1).pixels)
    }
    @Test fun tilesAndPersistentCompressionStream() {
        val deflater = Deflater()
        try {
            ZrleDecoder().use { decoder ->
                val target = PixelBuffer(65, 1)
                decoder.decode(compress(byteArrayOf(1) + red() + byteArrayOf(1) + blue(), deflater), target, 0, 0, 65, 1)
                assertEquals(red, target.pixels[63]); assertEquals(blue, target.pixels[64])
                decoder.decode(compress(byteArrayOf(1) + blue(), deflater), target, 0, 0, 64, 1)
                assertTrue(target.pixels.all { it == blue })
            }
        } finally { deflater.end() }
    }
    @Test fun malformedTilesAndRunsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { decode(byteArrayOf(17), 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { decode(byteArrayOf(129.toByte()), 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { decode(byteArrayOf(128.toByte()) + red() + byteArrayOf(4), 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { decode(byteArrayOf(3) + red() + blue() + red() + byteArrayOf(0xc0.toByte()), 1, 1) }
    }
    @Test fun copyRectangleHandlesBothOverlapDirections() {
        val target = PixelBuffer(2, 3)
        for (i in target.pixels.indices) target.pixels[i] = i
        target.copy(0, 1, 2, 2, 0, 0)
        assertArrayEquals(intArrayOf(0, 1, 0, 1, 2, 3), target.pixels)
        target.copy(0, 0, 2, 2, 0, 1)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 2, 3), target.pixels)
        assertThrows(IllegalArgumentException::class.java) { target.copy(1, 1, 2, 2, 0, 0) }
    }
    @Test fun allocationAndResizeAreBounded() {
        assertThrows(IllegalArgumentException::class.java) { PixelBuffer(65535, 65535) }
        val target = PixelBuffer(1, 1)
        assertThrows(IllegalArgumentException::class.java) { target.resize(3841, 2160) }
        assertEquals(1, target.width)
    }
}
