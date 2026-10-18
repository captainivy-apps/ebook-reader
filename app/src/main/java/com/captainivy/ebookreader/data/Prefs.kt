package com.captainivy.ebookreader.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局设置，落盘于 SharedPreferences。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("ebook_reader_prefs", Context.MODE_PRIVATE)

    /** 书库排序字段："last_read" 或 "added" */
    var sortKey: String
        get() = sp.getString(KEY_SORT_KEY, SORT_LAST_READ) ?: SORT_LAST_READ
        set(v) = sp.edit().putString(KEY_SORT_KEY, v).apply()

    /** 书库排序方向：true 升序，false 降序 */
    var sortAscending: Boolean
        get() = sp.getBoolean(KEY_SORT_ASC, false)
        set(v) = sp.edit().putBoolean(KEY_SORT_ASC, v).apply()

    /** 屏幕亮度 0..255，-1 表示跟随系统 */
    var brightness: Int
        get() = sp.getInt(KEY_BRIGHTNESS, -1)
        set(v) = sp.edit().putInt(KEY_BRIGHTNESS, v).apply()

    /** 字体族标识：system / serif / sans / mono / 自定义字体文件绝对路径 */
    var fontFamily: String
        get() = sp.getString(KEY_FONT_FAMILY, FONT_DEFAULT) ?: FONT_DEFAULT
        set(v) = sp.edit().putString(KEY_FONT_FAMILY, v).apply()

    /** 字号 sp */
    var fontSizeSp: Float
        get() = sp.getFloat(KEY_FONT_SIZE, 18f)
        set(v) = sp.edit().putFloat(KEY_FONT_SIZE, v).apply()

    /** 行间距倍数 */
    var lineSpacing: Float
        get() = sp.getFloat(KEY_LINE_SPACING, 1.4f)
        set(v) = sp.edit().putFloat(KEY_LINE_SPACING, v).apply()

    /** 左右阅读区边距 dp */
    var marginDp: Int
        get() = sp.getInt(KEY_MARGIN, 20)
        set(v) = sp.edit().putInt(KEY_MARGIN, v).apply()

    /** 段落首行缩进（2 个汉字宽度） */
    var indentFirstLine: Boolean
        get() = sp.getBoolean(KEY_INDENT_FIRST_LINE, true)
        set(v) = sp.edit().putBoolean(KEY_INDENT_FIRST_LINE, v).apply()

    /** 正文两端对齐 */
    var justify: Boolean
        get() = sp.getBoolean(KEY_JUSTIFY, false)
        set(v) = sp.edit().putBoolean(KEY_JUSTIFY, v).apply()

    /** 翻页全刷间隔（页）：0 关闭自动全刷，其余每 N 页清刷一次残影 */
    var refreshInterval: Int
        get() = sp.getInt(KEY_REFRESH_INTERVAL, REFRESH_INTERVAL_DEFAULT)
        set(v) = sp.edit().putInt(KEY_REFRESH_INTERVAL, v).apply()

    /** 是否开启 HTTP 局域网上传 */
    var httpEnabled: Boolean
        get() = sp.getBoolean(KEY_HTTP_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_HTTP_ENABLED, v).apply()

    /** 左手持有模式：翻转左右翻页区域 */
    var leftHandMode: Boolean
        get() = sp.getBoolean(KEY_LEFT_HAND_MODE, false)
        set(v) = sp.edit().putBoolean(KEY_LEFT_HAND_MODE, v).apply()

    /** 书库展示模式：true 封面网格，false 列表 */
    var libraryGrid: Boolean
        get() = sp.getBoolean(KEY_LIBRARY_GRID, false)
        set(v) = sp.edit().putBoolean(KEY_LIBRARY_GRID, v).apply()

    /** HTTP 上传端口 */
    var httpPort: Int
        get() = sp.getInt(KEY_HTTP_PORT, 8080)
        set(v) = sp.edit().putInt(KEY_HTTP_PORT, v).apply()

    companion object {
        const val SORT_LAST_READ = "last_read"
        const val SORT_ADDED = "added"
        const val FONT_DEFAULT = "default"
        const val FONT_SERIF = "serif"
        const val FONT_SANS = "sans"
        const val FONT_MONO = "mono"
        const val REFRESH_INTERVAL_DEFAULT = 5

        private const val KEY_SORT_KEY = "sort_key"
        private const val KEY_SORT_ASC = "sort_asc"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LINE_SPACING = "line_spacing"
        private const val KEY_MARGIN = "margin"
        private const val KEY_INDENT_FIRST_LINE = "indent_first_line"
        private const val KEY_JUSTIFY = "justify"
        private const val KEY_REFRESH_INTERVAL = "refresh_interval"
        private const val KEY_HTTP_ENABLED = "http_enabled"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_LEFT_HAND_MODE = "left_hand_mode"
        private const val KEY_LIBRARY_GRID = "library_grid"
    }
}
