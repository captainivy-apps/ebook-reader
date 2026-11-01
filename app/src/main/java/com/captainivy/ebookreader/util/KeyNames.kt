package com.captainivy.ebookreader.util

import android.content.Context
import android.view.KeyEvent
import com.captainivy.ebookreader.R

/**
 * 按键 KeyCode 与展示名称之间的转换。
 */
object KeyNames {

    fun label(context: Context, keyCode: Int): String {
        val resId = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> R.string.key_dpad_up
            KeyEvent.KEYCODE_DPAD_DOWN -> R.string.key_dpad_down
            KeyEvent.KEYCODE_DPAD_LEFT -> R.string.key_dpad_left
            KeyEvent.KEYCODE_DPAD_RIGHT -> R.string.key_dpad_right
            KeyEvent.KEYCODE_VOLUME_UP -> R.string.key_volume_up
            KeyEvent.KEYCODE_VOLUME_DOWN -> R.string.key_volume_down
            KeyEvent.KEYCODE_SPACE -> R.string.key_space
            KeyEvent.KEYCODE_ENTER -> R.string.key_enter
            KeyEvent.KEYCODE_PAGE_UP -> R.string.key_page_up
            KeyEvent.KEYCODE_PAGE_DOWN -> R.string.key_page_down
            KeyEvent.KEYCODE_ESCAPE -> R.string.key_escape
            KeyEvent.KEYCODE_TAB -> R.string.key_tab
            KeyEvent.KEYCODE_MENU -> R.string.key_menu
            KeyEvent.KEYCODE_BACK -> R.string.key_back
            else -> 0
        }
        if (resId != 0) return context.getString(resId)
        return KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .replace('_', ' ')
    }

    /** 纯修饰键，不作为翻页按键录入 */
    fun isModifierKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_META_LEFT,
        KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK,
        KeyEvent.KEYCODE_NUM_LOCK,
        KeyEvent.KEYCODE_SCROLL_LOCK,
        KeyEvent.KEYCODE_FUNCTION,
        KeyEvent.KEYCODE_SYM -> true
        else -> false
    }
}
