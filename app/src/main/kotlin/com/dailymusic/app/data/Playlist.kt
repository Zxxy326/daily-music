package com.dailymusic.app.data

import org.json.JSONObject

/**
 * 歌单广场里的一个歌单。
 *
 * 和 [Rank] 的区别：榜单是「只读的官方歌单」，`rankid` 就能取曲目；
 * 广场里的歌单取曲目要的是 `global_collection_id`（gcid），
 * 而且它是字符串，不是数字 —— 拿 `specialid` 去打 `/playlist/track/all` 会空列表。
 */
data class PlaylistBrief(
    val id: String,
    val name: String,
    /**
     * 封面**模板**，形如 `http://c1.kgimg.com/custom/{size}/2022.../x.jpg`。
     *
     * 酷狗给的是带 `{size}` 占位符的模板，直接拿去请求会 404 —— 必须换成具体尺寸。
     */
    val coverTemplate: String,
    val songCount: Int,
    val playCount: Long,
    val collectCount: Long = 0,
    val globalCollectionId: String,
) {
    fun cover(size: Int = 320): String = coverTemplate.replace("{size}", size.toString())

    /** 取曲目用的 id：优先 gcid，缺失时退回 specialid。 */
    val trackKey: String get() = globalCollectionId.ifBlank { id }

    /** 副标题用：收藏数比「歌单」两个字有信息量。 */
    val collectText: String
        get() = when {
            collectCount >= 10_000 -> "%.1f 万人收藏".format(collectCount / 10_000.0)
            collectCount > 0 -> "${collectCount} 人收藏"
            songCount > 0 -> "${songCount} 首"
            else -> "歌单"
        }

    val playCountText: String
        get() = when {
            playCount >= 100_000_000 -> "%.1f 亿".format(playCount / 100_000_000.0)
            playCount >= 10_000 -> "%.1f 万".format(playCount / 10_000.0)
            playCount > 0 -> playCount.toString()
            else -> ""
        }

    companion object {
        fun from(o: JSONObject): PlaylistBrief? {
            val name = o.str("specialname").ifBlank { o.str("name") }
            if (name.isBlank()) return null
            val id = o.str("specialid").ifBlank { o.str("id") }
            val gcid = o.str("global_collection_id").ifBlank { o.str("gid") }
            if (id.isBlank() && gcid.isBlank()) return null
            return PlaylistBrief(
                id = id,
                name = name,
                // 广场列表的封面字段各家不一样，按命中率从高到低试。
                coverTemplate = o.str("imgurl")
                    .ifBlank { o.str("sizable_cover") }
                    .ifBlank { o.str("img") }
                    .ifBlank { o.str("pic") }
                    .ifBlank { o.str("cover") },
                songCount = o.int("songcount").takeIf { it > 0 }
                    ?: o.int("song_count").takeIf { it > 0 }
                    ?: o.int("count"),
                collectCount = o.long("collectcount").takeIf { it > 0 }
                    ?: o.long("collect_count").takeIf { it > 0 }
                    ?: 0L,
                playCount = o.long("playcount").takeIf { it > 0 }
                    ?: o.long("play_count").takeIf { it > 0 }
                    ?: o.long("total_play_count"),
                globalCollectionId = gcid,
            )
        }
    }
}

/** 一页歌单广场数据。 */
data class PlaylistPage(val list: List<PlaylistBrief>, val hasNext: Boolean) {
    companion object {
        val Empty = PlaylistPage(emptyList(), false)
    }
}
