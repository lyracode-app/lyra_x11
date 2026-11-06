package com.yukisoffd.lyra.x11.rfb

import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.Inflater

/** ZRLE has one persistent zlib stream per connection; tiles are at most 64 × 64. */
class ZrleDecoder : AutoCloseable {
    private val inflater = Inflater()
    private val scratch = ByteArray(16 * 1024)

    fun decode(compressed: ByteArray, target: PixelBuffer, x: Int, y: Int, width: Int, height: Int) {
        target.validate(x, y, width, height)
        require(inflater.needsInput()) { "Unconsumed ZRLE input" }
        inflater.setInput(compressed)
        val limit = width.toLong() * height * 5 + ((width + 63L) / 64) * ((height + 63L) / 64) * 1024 + 4096
        // Stream tiles through a reusable 16 KiB buffer; never allocate an inflated full-screen copy.
        val input = DataInputStream(object : InputStream() {
            var offset = 0
            var size = 0
            var total = 0L
            override fun read(): Int {
                if (offset == size) {
                    if (inflater.needsInput()) return -1
                    size = inflater.inflate(scratch)
                    total += size
                    require(total <= limit) { "Oversized ZRLE stream" }
                    require(size > 0 || inflater.needsInput()) { "Invalid ZRLE zlib stream" }
                    offset = 0
                    if (size == 0) return -1
                }
                return scratch[offset++].toInt() and 255
            }
        })
        synchronized(target) {
            for (ty in y until y + height step 64) for (tx in x until x + width step 64) {
                tile(input, target, tx, ty, minOf(64, x + width - tx), minOf(64, y + height - ty))
            }
            require(input.read() == -1) { "Trailing ZRLE tile data" }
            target.mark(x, y, width, height)
        }
    }

    private fun pixel(input: DataInputStream): Int {
        val b = input.readUnsignedByte()
        val g = input.readUnsignedByte()
        val r = input.readUnsignedByte()
        return (0xff000000.toInt() or (r shl 16) or (g shl 8) or b)
    }

    private fun tile(input: DataInputStream, target: PixelBuffer, x: Int, y: Int, w: Int, h: Int) {
        val mode = input.readUnsignedByte()
        require(mode != 129 && (mode <= 16 || mode >= 128)) { "Invalid ZRLE subencoding" }
        val paletteSize = mode and 127
        val palette = IntArray(paletteSize) { pixel(input) }
        val size = w * h
        var offset = y * target.width + x
        var column = 0
        fun put(color: Int) {
            target.pixels[offset++] = color
            if (++column == w) { column = 0; offset += target.width - w }
        }
        fun runLength(remaining: Int): Int {
            var count = 1
            var next: Int
            do {
                next = input.readUnsignedByte()
                count += next
                require(count <= remaining) { "ZRLE run exceeds tile" }
            } while (next == 255)
            return count
        }
        when {
            mode == 0 -> repeat(size) { put(pixel(input)) }
            mode == 1 -> for (row in 0 until h) {
                val start = (y + row) * target.width + x
                java.util.Arrays.fill(target.pixels, start, start + w, palette[0])
            }
            mode in 2..16 -> {
                val bits = when { mode <= 2 -> 1; mode <= 4 -> 2; else -> 4 }
                for (row in 0 until h) {
                    var packed = 0
                    var remaining = 0
                    for (col in 0 until w) {
                        if (remaining == 0) { packed = input.readUnsignedByte(); remaining = 8 }
                        remaining -= bits
                        val index = (packed ushr remaining) and ((1 shl bits) - 1)
                        require(index < paletteSize) { "Invalid ZRLE palette index" }
                        put(palette[index])
                    }
                }
            }
            mode == 128 -> {
                var index = 0
                while (index < size) {
                    val color = pixel(input)
                    val run = runLength(size - index)
                    repeat(run) { put(color) }; index += run
                }
            }
            mode >= 130 -> {
                var index = 0
                while (index < size) {
                    val encoded = input.readUnsignedByte()
                    val paletteIndex = encoded and 127
                    require(paletteIndex < paletteSize) { "Invalid ZRLE palette index" }
                    val run = if (encoded and 128 != 0) runLength(size - index) else 1
                    repeat(run) { put(palette[paletteIndex]) }; index += run
                }
            }
        }
    }

    override fun close() = inflater.end()
}
