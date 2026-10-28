package com.hiweb.hiwebremotedesk.rdp

import android.view.KeyEvent

/** Скан-код PC/AT (набор 1) и признак расширенной клавиши (префикс E0). */
data class ScanKey(val code: Int, val extended: Boolean)

object ScanCodes {
    /** Linux evdev → XT. Для кодов 1..88 значения совпадают, расширенные клавиши перечислены явно. */
    private val evdevExtended = mapOf(
        96 to 0x1C,  // KP Enter
        97 to 0x1D,  // Right Ctrl
        98 to 0x35,  // KP /
        99 to 0x37,  // PrtSc
        100 to 0x38, // Right Alt
        102 to 0x47, // Home
        103 to 0x48, // Up
        104 to 0x49, // Page Up
        105 to 0x4B, // Left
        106 to 0x4D, // Right
        107 to 0x4F, // End
        108 to 0x50, // Down
        109 to 0x51, // Page Down
        110 to 0x52, // Insert
        111 to 0x53, // Delete
        125 to 0x5B, // Left Win
        126 to 0x5C, // Right Win
        127 to 0x5D, // Menu
    )

    private val byKeyCode: Map<Int, ScanKey> = buildMap {
        val letters = intArrayOf(
            0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
            0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C,
        )
        letters.forEachIndexed { i, c -> put(KeyEvent.KEYCODE_A + i, ScanKey(c, false)) }
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, ScanKey(if (i == 0) 0x0B else 0x01 + i, false))
        for (i in 0..9) put(KeyEvent.KEYCODE_F1 + i, ScanKey(0x3B + i, false))
        put(KeyEvent.KEYCODE_F11, ScanKey(0x57, false))
        put(KeyEvent.KEYCODE_F12, ScanKey(0x58, false))
        put(KeyEvent.KEYCODE_ESCAPE, ScanKey(0x01, false))
        put(KeyEvent.KEYCODE_MINUS, ScanKey(0x0C, false))
        put(KeyEvent.KEYCODE_EQUALS, ScanKey(0x0D, false))
        put(KeyEvent.KEYCODE_DEL, ScanKey(0x0E, false))
        put(KeyEvent.KEYCODE_TAB, ScanKey(0x0F, false))
        put(KeyEvent.KEYCODE_LEFT_BRACKET, ScanKey(0x1A, false))
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, ScanKey(0x1B, false))
        put(KeyEvent.KEYCODE_ENTER, ScanKey(0x1C, false))
        put(KeyEvent.KEYCODE_CTRL_LEFT, ScanKey(0x1D, false))
        put(KeyEvent.KEYCODE_SEMICOLON, ScanKey(0x27, false))
        put(KeyEvent.KEYCODE_APOSTROPHE, ScanKey(0x28, false))
        put(KeyEvent.KEYCODE_GRAVE, ScanKey(0x29, false))
        put(KeyEvent.KEYCODE_SHIFT_LEFT, ScanKey(0x2A, false))
        put(KeyEvent.KEYCODE_BACKSLASH, ScanKey(0x2B, false))
        put(KeyEvent.KEYCODE_COMMA, ScanKey(0x33, false))
        put(KeyEvent.KEYCODE_PERIOD, ScanKey(0x34, false))
        put(KeyEvent.KEYCODE_SLASH, ScanKey(0x35, false))
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, ScanKey(0x36, false))
        put(KeyEvent.KEYCODE_ALT_LEFT, ScanKey(0x38, false))
        put(KeyEvent.KEYCODE_SPACE, ScanKey(0x39, false))
        put(KeyEvent.KEYCODE_CAPS_LOCK, ScanKey(0x3A, false))
        put(KeyEvent.KEYCODE_NUM_LOCK, ScanKey(0x45, false))
        put(KeyEvent.KEYCODE_SCROLL_LOCK, ScanKey(0x46, false))
        put(KeyEvent.KEYCODE_CTRL_RIGHT, ScanKey(0x1D, true))
        put(KeyEvent.KEYCODE_ALT_RIGHT, ScanKey(0x38, true))
        put(KeyEvent.KEYCODE_SYSRQ, ScanKey(0x37, true))
        put(KeyEvent.KEYCODE_MOVE_HOME, ScanKey(0x47, true))
        put(KeyEvent.KEYCODE_DPAD_UP, ScanKey(0x48, true))
        put(KeyEvent.KEYCODE_PAGE_UP, ScanKey(0x49, true))
        put(KeyEvent.KEYCODE_DPAD_LEFT, ScanKey(0x4B, true))
        put(KeyEvent.KEYCODE_DPAD_RIGHT, ScanKey(0x4D, true))
        put(KeyEvent.KEYCODE_MOVE_END, ScanKey(0x4F, true))
        put(KeyEvent.KEYCODE_DPAD_DOWN, ScanKey(0x50, true))
        put(KeyEvent.KEYCODE_PAGE_DOWN, ScanKey(0x51, true))
        put(KeyEvent.KEYCODE_INSERT, ScanKey(0x52, true))
        put(KeyEvent.KEYCODE_FORWARD_DEL, ScanKey(0x53, true))
        put(KeyEvent.KEYCODE_META_LEFT, ScanKey(0x5B, true))
        put(KeyEvent.KEYCODE_META_RIGHT, ScanKey(0x5C, true))
        put(KeyEvent.KEYCODE_MENU, ScanKey(0x5D, true))
    }

    /** Системные клавиши, которые не пересылаем на удалённый ПК. */
    private val local = setOf(
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_BRIGHTNESS_UP, KeyEvent.KEYCODE_BRIGHTNESS_DOWN, KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_SEARCH,
    )

    fun isLocalOnly(keyCode: Int) = keyCode in local

    fun fromEvent(e: KeyEvent): ScanKey? {
        val sc = e.scanCode
        if (sc in 1..88) return ScanKey(sc, false)
        evdevExtended[sc]?.let { return ScanKey(it, true) }
        return byKeyCode[e.keyCode]
    }

    /**
     * Символ в скан-код раскладки ПК — нужен, когда символ приходит из экранной клавиатуры Android,
     * а на ПК уже зажаты модификаторы (Ctrl, Alt, Win): без скан-кода сочетание не сработает.
     * Берутся только «позиционные» символы; для редких ([forChar] вернёт null) остаётся Unicode.
     */
    /**
     * Символ, который на раскладке US набирается через Shift, → клавиша без Shift (`!` → `1`).
     * Такие символы тоже уходят скан-кодом: Unicode-события (VK_PACKET) не принимают
     * Notepad++, редакторы JetBrains и другие программы со своей обработкой клавиатуры.
     */
    fun unshifted(ch: Char): Char? {
        val i = SHIFTED.indexOf(ch)
        return if (i >= 0) UNSHIFTED[i] else null
    }

    private const val SHIFTED = "!@#\$%^&*()_+{}|:\"<>?~"
    private const val UNSHIFTED = "1234567890-=[]\\;',./`"

    fun forChar(ch: Char): ScanKey? {
        val kc = when (ch) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
            in 'A'..'Z' -> KeyEvent.KEYCODE_A + (ch - 'A')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '.' -> KeyEvent.KEYCODE_PERIOD
            ',' -> KeyEvent.KEYCODE_COMMA
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '/' -> KeyEvent.KEYCODE_SLASH
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            '`' -> KeyEvent.KEYCODE_GRAVE
            '\n', '\r' -> KeyEvent.KEYCODE_ENTER
            '\b' -> KeyEvent.KEYCODE_DEL
            else -> 0
        }
        return byKeyCode[kc]
    }
}
