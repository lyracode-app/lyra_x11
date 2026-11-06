package com.yukisoffd.lyra.x11

import android.view.KeyEvent

internal object X11Input {
    fun unicode(codePoint: Int): Int = if (codePoint in 0x20..0xff) codePoint else 0x01000000 or codePoint

    fun special(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> 0xff08
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
        KeyEvent.KEYCODE_TAB -> 0xff09
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xff0d
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> 0xff1b
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
        KeyEvent.KEYCODE_DPAD_UP -> 0xff52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
        KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
        KeyEvent.KEYCODE_MOVE_END -> 0xff57
        KeyEvent.KEYCODE_PAGE_UP -> 0xff55
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
        KeyEvent.KEYCODE_INSERT -> 0xff63
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xffe1
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe2
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xffe3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe4
        KeyEvent.KEYCODE_ALT_LEFT -> 0xffe9
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xffea
        KeyEvent.KEYCODE_META_LEFT -> 0xffeb
        KeyEvent.KEYCODE_META_RIGHT -> 0xffec
        KeyEvent.KEYCODE_CAPS_LOCK -> 0xffe5
        KeyEvent.KEYCODE_NUM_LOCK -> 0xff7f
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0xff14
        KeyEvent.KEYCODE_BREAK -> 0xff13
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0xffbe + keyCode - KeyEvent.KEYCODE_F1
        else -> null
    }
}
