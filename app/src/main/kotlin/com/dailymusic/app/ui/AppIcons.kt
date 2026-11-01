package com.dailymusic.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 手写的矢量图标。
 *
 * 没有引 `material-icons-extended`（体积大、而且这个项目里只用到十来个图标），
 * 也没有引 Miuix 的图标包（命名和 API 需要额外核对，风险大于收益）。
 * 直接喂 Material 的 path data 给 [ImageVector.Builder]，配合 `Image(colorFilter = tint)`
 * 就能拿到任意染色，且完全不依赖外部资源。
 */
private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

object AppIcons {

    val Home: ImageVector = icon(
        "home",
        "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
    )

    val Search: ImageVector = icon(
        "search",
        "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 " +
            "5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 " +
            "5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
    )

    val Person: ImageVector = icon(
        "person",
        "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2" +
            "c0-2.66-5.33-4-8-4z",
    )

    val Play: ImageVector = icon("play", "M8 5v14l11-7z")

    val Pause: ImageVector = icon("pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")

    val SkipNext: ImageVector = icon("skip_next", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z")

    val SkipPrevious: ImageVector = icon("skip_previous", "M6 6h2v12H6zm3.5 6l8.5 6V6z")

    val Heart: ImageVector = icon(
        "heart",
        "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09" +
            "C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
    )

    val HeartOutline: ImageVector = icon(
        "heart_outline",
        "M16.5 3c-1.74 0-3.41.81-4.5 2.09C10.91 3.81 9.24 3 7.5 3 4.42 3 2 5.42 2 8.5c0 3.78 3.4 6.86 " +
            "8.55 11.54L12 21.35l1.45-1.32C18.6 15.36 22 12.28 22 8.5 22 5.42 19.58 3 16.5 3zm-4.4 " +
            "15.55l-.1.1-.1-.1C7.14 14.24 4 11.39 4 8.5 4 6.5 5.5 5 7.5 5c1.54 0 3.04.99 3.57 2.36h1.87" +
            "C13.46 5.99 14.96 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.89-3.14 5.74-7.9 10.05z",
    )

    val ChevronDown: ImageVector = icon("chevron_down", "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z")

    val ArrowBack: ImageVector = icon(
        "arrow_back",
        "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
    )

    val QueueMusic: ImageVector = icon(
        "queue_music",
        "M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 " +
            "1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z",
    )

    val Refresh: ImageVector = icon(
        "refresh",
        "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 " +
            "7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 " +
            "1.78L13 11h7V4l-2.35 2.35z",
    )

    val MusicNote: ImageVector = icon(
        "music_note",
        "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z",
    )

    val Close: ImageVector = icon(
        "close",
        "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 " +
            "13.41 12z",
    )

    /** 签到打勾。 */
    val Check: ImageVector = icon(
        "check",
        "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
    )

    /** 日历（签到入口）。 */
    val Calendar: ImageVector = icon(
        "calendar",
        "M19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16" +
            "H5V8h14v11zM7 10h5v5H7z",
    )

    /** 耳机（听歌领取）。 */
    val Headphones: ImageVector = icon(
        "headphones",
        "M12 3a9 9 0 0 0-9 9v7c0 1.1.9 2 2 2h1c.55 0 1-.45 1-1v-5c0-.55-.45-1-1-1H5v-2a7 7 0 0 1 14 0v2h-" +
            "1c-.55 0-1 .45-1 1v5c0 .55.45 1 1 1h1c1.1 0 2-.9 2-2v-7a9 9 0 0 0-9-9z",
    )

    // ---- 视觉稿（design/mockup.html）需要的图标 ----

    /** 音乐库：四宫格。 */
    val Library: ImageVector = icon(
        "library",
        "M4 4h6v6H4V4zm10 0h6v6h-6V4zM4 14h6v6H4v-6zm10 0h6v6h-6v-6z",
    )

    /** 行尾「更多」三点。 */
    /** 设置（滑杆）。Material 的 `tune`。 */
    val Tune: ImageVector = icon(
        "tune",
        "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7z" +
            "m14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
    )

    val MoreHoriz: ImageVector = icon(
        "more_horiz",
        "M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2" +
            "-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
    )

    /** 每日推荐：星形。 */
    val Sparkle: ImageVector = icon(
        "sparkle",
        "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
    )

    /** 排行榜：柱状图。 */
    val Chart: ImageVector = icon(
        "chart",
        "M5 9.2h3V19H5V9.2zM10.6 5h2.8v14h-2.8V5zm5.6 8H19v6h-2.8v-6z",
    )

    /** 歌单广场：列表。 */
    val ListBullet: ImageVector = icon(
        "list_bullet",
        "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z",
    )

    /** 识曲：麦克风。 */
    val Mic: ImageVector = icon(
        "mic",
        "M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-" +
            "5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z",
    )

    /** 定时关闭。 */
    val Clock: ImageVector = icon(
        "clock",
        "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10 10-4.5 10-10S17.5 2 12 2zm4.2 14.2L11 13V7h1.5v5.2l4.5 2.7-.8 1.3z",
    )

    /** 评论。 */
    val Comment: ImageVector = icon(
        "comment",
        "M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z",
    )
}
