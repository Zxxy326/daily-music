package com.dailymusic.app.data

import org.json.JSONObject

/**
 * 账号里的一个歌单（我创建的 / 我收藏的）。
 *
 * 和 [PlaylistBrief] 不是一回事：广场歌单靠 `global_collection_id` 取曲目，
 * 自己的歌单要靠 `listid` 走 `/playlist/track/all/new`。混用会拿到空列表。
 */
data class MyPlaylist(
    val listid: String,
    val name: String,
    /** 封面模板，可能带 `{size}` 占位符，也可能是完整 URL。 */
    val coverTemplate: String,
    val songCount: Int,
    /** true = 我创建的，false = 我收藏的。 */
    val created: Boolean,
    /** 「默认收藏」（我喜欢）这个特殊歌单。 */
    val isDefault: Boolean,
) {
    fun cover(size: Int = 320): String = coverTemplate.replace("{size}", size.toString())

    companion object {
        fun from(o: JSONObject, meUserId: String?): MyPlaylist? {
            val listid = o.str("listid").ifBlank { o.str("listId") }
            val name = o.str("name")
            if (listid.isBlank() || name.isBlank()) return null

            val creator = o.str("list_create_userid").ifBlank { o.str("listCreateUserid") }
            val isDef = o.int("is_def")
            // 三种判据，命中任一即可：
            // 1. 显式 type == 1 是收藏；2. 创建者不是自己；3. 默认收藏算自建。
            // 都拿不到时按「自己创建」处理 —— 大多数人的歌单是自己建的。
            val collected = o.int("type") == 1 ||
                (creator.isNotBlank() && meUserId != null && creator != meUserId)
            return MyPlaylist(
                listid = listid,
                name = name,
                coverTemplate = o.str("imgurl").ifBlank { o.str("img") },
                songCount = o.int("song_count").takeIf { it > 0 } ?: o.int("count"),
                created = !collected,
                isDefault = isDef != 0,
            )
        }
    }
}
