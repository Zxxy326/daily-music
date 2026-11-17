package com.dailymusic.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * TV 端配色。
 *
 * 深蓝灰底 + 浅蓝强调，和设计稿一致。电视距离 3 米，
 * 对比度必须够 —— 次要文字也不能低于 #8B95A5。
 */
object TvColor {
    val bg = Color(0xFF0D1117)
    val card = Color(0xFF161B22)
    val cardHigh = Color(0xFF1F2630)
    val stroke = Color(0xFF2A3441)
    val text = Color(0xFFF2F5F9)
    val textDim = Color(0xFF8B95A5)
    val accent = Color(0xFFA8C8FF)
    val accentDeep = Color(0xFF3E5C8A)
    val onAccent = Color(0xFF0B1220)
}

/** 统一文字样式，避免每个组件各写一套 fontSize/weight。 */
object TvText {
    val h1 = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TvColor.text)
    val h2 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TvColor.text)
    val h3 = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TvColor.text)
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, color = TvColor.textDim)
    val caption = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, color = TvColor.textDim)
    val button = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TvColor.onAccent)
}
