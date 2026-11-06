package com.yukisoffd.lyra.x11

/** Container-neutral media contract; the host supplies a private bind visible to all guest processes. */
interface GuiMedia : AutoCloseable {
    override fun close()
    val guest: GuestMedia?
    fun diagnostics(): Map<String, Any>
    fun mute(muted: Boolean)
    object None : GuiMedia {
        override val guest: GuestMedia? = null
        override fun diagnostics(): Map<String, Any> = mapOf("audio" to "off", "graphics" to "software")
        override fun mute(muted: Boolean) = Unit
        override fun close() = Unit
    }
}

data class GuestMedia(val directory: String, val virgl: Boolean) {
    init { require(Regex("/run/lyra/[a-f0-9]{16}").matches(directory)) }
    val exports: String get() = ". ${GuestBootstrap.quote("$directory/environment")}" 
}
