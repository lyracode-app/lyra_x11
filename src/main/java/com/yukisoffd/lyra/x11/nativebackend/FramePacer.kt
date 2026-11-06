package com.yukisoffd.lyra.x11.nativebackend

/** Keep the requested rate on displays whose refresh rate is not an integer multiple. */
internal class FramePacer(framesPerSecond: Int) {
    private val period=1_000_000_000L/framesPerSecond
    private var next=-1L
    fun reset() { next=-1L }
    fun frame(time: Long): Boolean {
        if(next<0) next=time
        if(time+100_000<next) return false
        next+=period
        if(next<time-period) next=time+period // Drop missed frames after a stall.
        return true
    }
}
