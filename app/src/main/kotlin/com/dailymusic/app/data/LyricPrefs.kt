package com.dailymusic.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 竖屏歌词的显示偏好：对齐方式 + 字号。
 *
 * 只影响竖屏歌词页，横屏三套动效不读这里 —— 那三套是「效果」，排版是设计定死的。
 */
object LyricPrefs {

    enum class Align(val label: String) {
        LEFT("居左"),
        CENTER("居中"),
        RIGHT("居右"),
    }

    /** 字号档位，倍率作用在 21sp 的基准上。 */
    enum class Size(val label: String, val scale: Float) {
        SMALL("小", 0.86f),
        NORMAL("标准", 1f),
        LARGE("大", 1.16f),
        HUGE("特大", 1.32f),
    }

    var align by mutableStateOf(Align.CENTER)
        private set

    var size by mutableStateOf(Size.NORMAL)
        private set

    /** 基准字号（sp），实际字号 = 基准 × 档位倍率。 */
    val baseSp: Float = 21f

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(K_ALIGN, null)?.let { name ->
            Align.entries.firstOrNull { it.name == name }?.let { align = it }
        }
        p.getString(K_SIZE, null)?.let { name ->
            Size.entries.firstOrNull { it.name == name }?.let { size = it }
        }
    }

    fun setAlign(context: Context, value: Align) {
        align = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(K_ALIGN, value.name).apply()
    }

    fun setSize(context: Context, value: Size) {
        size = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(K_SIZE, value.name).apply()
    }

    private const val PREFS = "lyric_prefs"
    private const val K_ALIGN = "align"
    private const val K_SIZE = "size"
}
