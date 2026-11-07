package com.yukisoffd.lyra.x11

/** Replace consecutive moves in place so they cannot overtake a button or wheel event. */
internal class PointerQueue<Output>(
    private val enqueue: ((Output) -> Unit) -> Unit,
    private val send: (Output, Int, Int, Int) -> Unit,
) {
    private data class Move(var x: Int, var y: Int, var mask: Int)
    private var pending: Move? = null

    @Synchronized
    fun pointer(x: Int, y: Int, mask: Int, motionOnly: Boolean) {
        if (!motionOnly) {
            ordered { send(it, x, y, mask) }
            return
        }
        pending?.let {
            it.x = x
            it.y = y
            it.mask = mask
            return
        }
        val move = Move(x, y, mask)
        pending = move
        enqueue { output ->
            synchronized(this) { if (pending === move) pending = null }
            send(output, move.x, move.y, move.mask)
        }
    }

    @Synchronized
    fun ordered(command: (Output) -> Unit) {
        pending = null
        enqueue(command)
    }
}
