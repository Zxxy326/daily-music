package com.dailymusic.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Miuix 默认种子色，与 HyperOS 蓝一致 */
private val SeedColor = Color(0xFF3482FF)

@Composable
fun DailyMusicTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val controller = remember(dark) {
        ThemeController(
            ColorSchemeMode.MonetSystem,
            keyColor = SeedColor,
            isDark = dark,
        )
    }
    MiuixTheme(controller = controller, content = content)
}
