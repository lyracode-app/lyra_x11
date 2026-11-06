package com.yukisoffd.lyra.x11.rfb

data class Damage(val x: Int, val y: Int, val width: Int, val height: Int)
data class CursorImage(val width: Int, val height: Int, val hotX: Int, val hotY: Int, val pixels: IntArray)

/** Decoder and renderer synchronize on this object. Only damaged pixels are uploaded to Android. */
class PixelBuffer(width: Int, height: Int) {
    var width: Int = width; private set
    var height: Int = height; private set
    var pixels: IntArray = IntArray(checkedArea(width, height)); private set
    var cursor: CursorImage? = null
    private var damage: Damage? = Damage(0, 0, width, height)
    var revision = 0L; private set
    private var committedDamage: Damage? = null

    @Synchronized fun resize(width: Int, height: Int) {
        pixels = IntArray(checkedArea(width, height))
        this.width = width
        this.height = height
        damage = Damage(0, 0, width, height)
    }

    fun validate(x: Int, y: Int, width: Int, height: Int) {
        require(x >= 0 && y >= 0 && width > 0 && height > 0 &&
            x.toLong() + width <= this.width && y.toLong() + height <= this.height) { "Invalid RFB rectangle" }
    }

    fun mark(x: Int, y: Int, width: Int, height: Int) {
        val old = damage
        if (old == null) damage = Damage(x, y, width, height)
        else {
            val left = minOf(old.x, x)
            val top = minOf(old.y, y)
            damage = Damage(left, top, maxOf(old.x + old.width, x + width) - left,
                maxOf(old.y + old.height, y + height) - top)
        }
    }

    @Synchronized fun takeDamage(): Damage? = damage.also { damage = null }
    @Synchronized fun commitFrame() {
        committedDamage = takeDamage()
        revision++
    }
    /** Every viewer tracks its own revision, so a floating window cannot consume the full-screen view's damage. */
    @Synchronized fun damageAfter(lastRevision: Long): Damage? = when {
        lastRevision == revision -> null
        lastRevision == revision - 1 -> committedDamage
        else -> Damage(0, 0, width, height)
    }

    @Synchronized fun copy(x: Int, y: Int, width: Int, height: Int, sourceX: Int, sourceY: Int) {
        validate(x, y, width, height)
        validate(sourceX, sourceY, width, height)
        val rows = if (y > sourceY) (height - 1 downTo 0) else (0 until height)
        for (row in rows) System.arraycopy(pixels, (sourceY + row) * this.width + sourceX,
            pixels, (y + row) * this.width + x, width)
        mark(x, y, width, height)
    }

    companion object {
        fun checkedArea(width: Int, height: Int): Int {
            require(width in 1..3840 && height in 1..2160 && width.toLong() * height <= 8_294_400) {
                "Desktop exceeds the 3840 × 2160 memory limit"
            }
            return width * height
        }
    }
}
