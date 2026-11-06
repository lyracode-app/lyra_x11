package com.yukisoffd.lyra.x11.media

/** Frame-aligned, bounded PCM queue. A stalled output discards old audio, never grows latency. */
internal class PcmQueue(val capacity: Int = 15_360, private val frameBytes: Int = 4) {
    init { require(capacity > 0 && capacity % frameBytes == 0) }
    private val bytes = ByteArray(capacity)
    private val tail = ByteArray(frameBytes)
    private var tailSize = 0
    private var discardTail = false
    private var head = 0
    private var size = 0
    @Volatile var droppedBytes = 0L; private set
    @Synchronized fun offer(input: ByteArray, count: Int) {
        require(count in 0..input.size)
        for (index in 0 until count) {
            tail[tailSize++] = input[index]
            if (tailSize == frameBytes) {
                if (discardTail) { droppedBytes += frameBytes; discardTail = false }
                else {
                    if (size == capacity) { head = (head + frameBytes) % capacity; size -= frameBytes; droppedBytes += frameBytes }
                    for (byte in tail) { bytes[(head + size) % capacity] = byte; size++ }
                }
                tailSize = 0
            }
        }
    }
    @Synchronized fun read(output: ByteArray): Int {
        val count = minOf(size, output.size - output.size % frameBytes)
        for (i in 0 until count) output[i] = bytes[(head + i) % capacity]
        head = (head + count) % capacity; size -= count
        return count
    }
    @Synchronized fun clear() {
        droppedBytes += size; size = 0; head = 0
        // The FIFO remains a continuous byte stream across mute/route changes.
        // Finish discarding its partial frame before accepting the next one.
        discardTail = tailSize != 0
    }
    @Synchronized fun queuedBytes(): Int = size
}
