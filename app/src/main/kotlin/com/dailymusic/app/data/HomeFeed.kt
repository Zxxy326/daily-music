package com.dailymusic.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 首页「推荐」流里的一个模块。
 *
 * @param title 模块标题。来自上游 `card_info.card_title`（会带活动日期，
 *   例如「概念er新推09.30-10.29」），不要在前端写死。
 * @param subtitle 副标题，目前只有「曲风盲盒」用（本期开箱：电子）。
 */
data class FeedModule(
    val id: String,
    val title: String,
    val subtitle: String,
    val songs: List<Song>,
)

/**
 * 概念版首页的推荐模块。
 *
 * 全部走 `/top/card/youth`（概念版单卡推荐）+ 私人 FM + 曲风推荐，
 * 这三条链路匿名就能出数据，登录后内容会按听歌习惯个性化。
 *
 * 为什么模块用 `card_id` 而不是名字：上游的卡是**运营配的**，
 * 名字会换（「概念er新推09.30-10.29」每周带新日期），id 稳定。
 * 扫出来的对应关系：
 * ```
 * 3001 私人专属好歌     3004 小众宝藏佳作     3005 潮流尝鲜
 * 3101 概念er新推       3102 喜欢这首歌的TA也喜欢
 * ```
 * 「红心 Radio」是 FM 的红心档（`song_pool_id=0`），「曲风盲盒」是曲风推荐，
 * 两者都不是卡片，单独走接口。
 */
object HomeFeed {

    /** 曲风盲盒的池子：S1..S20 是实测可用的曲风 id。 */
    private val Genres = listOf(
        "S1" to "华语流行", "S2" to "国风", "S3" to "电子", "S4" to "嘻哈",
        "S5" to "民谣", "S6" to "爵士", "S7" to "R&B", "S8" to "乡村",
        "S9" to "DJ", "S10" to "ACG", "S11" to "摇滚", "S12" to "古典乐",
        "S15" to "粤语流行", "S16" to "韩语流行", "S17" to "日语流行",
        "S18" to "英语流行", "S19" to "网络流行", "S20" to "纯音乐",
    )

    /**
     * 拉全部模块，**并发**。
     *
     * 七个模块串行的话首页要多等好几秒；并发之后总耗时约等于最慢的那一个。
     * 任何一块失败都只是少一行，不影响其他模块（用户不需要知道某个推荐挂了）。
     */
    suspend fun load(size: Int = 12): List<FeedModule> = coroutineScope {
        val jobs = listOf(
            async { heartRadio(size) },
            async { youthCard("personal", 3001, size) },
            async { youthCard("also-like", 3102, size) },
            async { youthCard("concept-new", 3101, size) },
            async { genreBlindBox(size) },
            async { youthCard("trend", 3005, size) },
            async { youthCard("hidden-gem", 3004, size) },
        )
        jobs.awaitAll().filterNotNull().filter { it.songs.isNotEmpty() }
    }

    /** 红心 Radio：FM 红心档，登录后按听歌习惯出歌。 */
    private suspend fun heartRadio(size: Int): FeedModule? {
        val songs = runCatching { KugouApi.personalFm("normal", 0, size) }.getOrDefault(emptyList())
        if (songs.isEmpty()) return null
        return FeedModule(
            id = "heart-radio",
            title = "红心 Radio",
            subtitle = if (Auth.isLoggedIn) "根据你的听歌习惯" else "登录后按你的口味推荐",
            songs = songs,
        )
    }

    /** 曲风盲盒：每次随机挑一个曲风，标题写「曲风盲盒」，副标题说开出了什么。 */
    private suspend fun genreBlindBox(size: Int): FeedModule? {
        val (tag, name) = Genres.random()
        val result = runCatching { KugouApi.styleRecommend(tag, size) }.getOrNull() ?: return null
        val songs = result.second
        if (songs.isEmpty()) return null
        return FeedModule(
            id = "genre-box",
            title = "曲风盲盒",
            subtitle = "本期开箱：${result.first.ifBlank { name }}",
            songs = songs,
        )
    }

    /** 概念版单卡。标题用上游给的，缺省时用兜底名。 */
    private suspend fun youthCard(id: String, cardId: Int, size: Int): FeedModule? {
        val result = runCatching { KugouApi.youthCard(cardId, size) }.getOrNull() ?: return null
        val (title, songs) = result
        if (songs.isEmpty()) return null
        return FeedModule(
            id = id,
            title = title.ifBlank { fallbackTitle(cardId) },
            subtitle = "",
            songs = songs,
        )
    }

    private fun fallbackTitle(cardId: Int): String = when (cardId) {
        3001 -> "私人专属好歌"
        3004 -> "小众宝藏佳作"
        3005 -> "潮流尝鲜"
        3101 -> "概念er新推"
        3102 -> "喜欢这首歌的TA也喜欢"
        else -> "为你推荐"
    }
}
