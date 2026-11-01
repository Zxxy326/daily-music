package com.dailymusic.app.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dailymusic.app.R

/**
 * 应用里用到的两套第三方字体。
 *
 * 字体文件做过**子集化**（GB2312 全部汉字 + 常用标点 + 日文假名 + ASCII），
 * 原文件 7MB，裁完 2.5MB。歌里偶尔出现的生僻字会退回系统字体，
 * 一行里混一两颗字，比为了几个字多背 20MB 划算。
 *
 * 底栏和歌词动效页都用这一套。
 *
 * 方圆体原文件是可变字体（wght 200–700 / BEVL 方圆 1–100），
 * 这里用 fontTools 实例化到 SemiBold(600) + BEVL 80 存成静态字体 ——
 * Compose 没法直接选可变轴，实例化之后字重才是确定的。
 */
val FangYuanFamily = FontFamily(
    Font(R.font.alimama_fangyuan, weight = FontWeight.SemiBold),
)
