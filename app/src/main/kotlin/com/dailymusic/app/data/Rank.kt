package com.dailymusic.app.data

import org.json.JSONObject

/**
 * 一个榜单（= 一个只读歌单）。
 *
 * 为什么用榜单来承载「歌单详情」：酷狗的用户自建歌单需要登录且只能看到自己的，
 * 而榜单是匿名就能拿全的、结构稳定的歌单。对自用场景来说，榜单已经覆盖了
 * 「进入一个歌单 → 看到完整曲目 → 整单播放」这条路径。
 */
data class Rank(
    val id: Long,
    val name: String,
    val coverTemplate: String,
    val playCount: Long,
    val updateHint: String,
) {
    fun cover(size: Int = 240): String? =
        coverTemplate.takeIf { it.isNotBlank() }?.replace("{size}", size.toString())

    val playCountText: String
        get() = when {
            playCount >= 100_000_000 -> "%.1f 亿".format(playCount / 100_000_000.0)
            playCount >= 10_000 -> "%.1f 万".format(playCount / 10_000.0)
            playCount > 0 -> playCount.toString()
            else -> ""
        }

    companion object {
        fun from(o: JSONObject): Rank? {
            val id = o.long("rankid")
            if (id <= 0) return null
            val name = o.str("rankname").ifBlank { return null }
            return Rank(
                id = id,
                name = name,
                coverTemplate = o.str("album_img_9").ifBlank { o.str("img_9") },
                playCount = o.long("play_times"),
                updateHint = o.str("update_frequency"),
            )
        }
    }
}
