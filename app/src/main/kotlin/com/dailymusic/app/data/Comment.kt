package com.dailymusic.app.data

import org.json.JSONObject

/** 一条歌曲评论。只保留列表里真正要显示的字段。 */
data class Comment(
    val id: String,
    val user: String,
    val avatar: String?,
    val content: String,
    val time: String,
    val likes: Int,
) {
    companion object {
        /**
         * 字段名做多重兜底。
         *
         * 上游 `mcomment/v1/cmtlist` 在不同 appid / clientver 下返回的 key 并不统一
         * （`content` vs `text`、`user_name` vs `nickname`、`like.count` vs `likecount`），
         * 只认一种写法的话，换个版本就整片空白。
         */
        fun from(o: JSONObject): Comment? {
            val content = o.str("content").ifBlank { o.str("text") }.trim()
            if (content.isEmpty()) return null

            val user = o.str("user_name")
                .ifBlank { o.str("nickname") }
                .ifBlank { o.str("username") }
                .ifBlank { "匿名用户" }

            val avatar = o.str("user_pic")
                .ifBlank { o.str("user_head") }
                .ifBlank { o.str("avatar") }
                .ifBlank { null }

            val likes = o.obj("like")?.int("count")
                ?: o.int("likecount").takeIf { it > 0 }
                ?: o.int("like")

            return Comment(
                id = o.str("id").ifBlank { o.str("commentid") }.ifBlank { content.hashCode().toString() },
                user = user,
                avatar = avatar,
                content = content,
                // addtime 是 `2025-02-19 20:51:55`，列表里只显示日期就够。
                time = o.str("addtime").ifBlank { o.str("time") }
                    .let { if (it.length > 10) it.take(10) else it },
                likes = likes,
            )
        }
    }
}

data class CommentPage(
    val total: Int,
    val list: List<Comment>,
) {
    companion object {
        val Empty = CommentPage(0, emptyList())
    }
}
