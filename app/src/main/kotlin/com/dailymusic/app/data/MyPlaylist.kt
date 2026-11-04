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
        /** 像图片地址：http 开头 + 常见图床/后缀。 */
        private fun looksLikeImage(v: String): Boolean =
            v.startsWith("http") &&
                (v.contains("kgimg.com") || v.contains(".jpg") ||
                    v.contains(".jpeg") || v.contains(".png") || v.contains(".webp"))

        /**
         * 取封面。
         *
         * `/user/playlist` 的封面字段名很不统一：自建歌单常常整段为空，
         * 收藏来的歌单又可能塞在 `pic` / `flexible_cover` 里。所以先按已知字段试，
         * 再扫一遍所有字符串字段找「像图片地址」的那个 —— 比猜字段名靠谱。
         */
        private fun pickCover(o: JSONObject): String {
            val known = listOf(
                "imgurl", "sizable_cover", "cover", "pic", "img", "img_9",
                "album_img_9", "album_img", "flexible_cover",
            )
            known.forEach { k ->
                val v = o.str(k)
                if (v.isNotBlank() && looksLikeImage(v)) return v
            }
            // 扫全字段时**跳过头像类字段**：`/user/playlist` 里带着账号头像，
            // 不排除的话「我创建的歌单」会全部显示成头像，看起来像没封面。
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val lk = k.lowercase()
                if (lk.contains("avatar") || lk.contains("head") ||
                    lk.contains("portrait") || lk.contains("icon") || lk.contains("logo")
                ) continue
                val v = o.opt(k) as? String ?: continue
                if (looksLikeImage(v)) return v
            }
            return ""
        }

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
                coverTemplate = pickCover(o),
                songCount = o.int("song_count").takeIf { it > 0 } ?: o.int("count"),
                created = !collected,
                isDefault = isDef != 0,
            )
        }
    }
}
