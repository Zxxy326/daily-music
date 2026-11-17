package com.dailymusic.app.data

import org.json.JSONObject

/**
 * 统一的歌曲模型。
 *
 * 酷狗不同接口返回的字段名和类型完全不一样（搜索用 `MixSongID`/`FileHash`/`Image`，
 * 每日推荐用 `mixsongid`/`hash`/`sizable_cover`，且 ID 有时是 String 有时是 Long），
 * 所以这里用一组宽松的取值函数做归一化，上层只认这一个类型。
 *
 * 播放地址必须同时带 hash + album_audio_id（= MixSongID），缺一个上游就返回
 * `_errno: 6 audio is not found`。这是踩过的坑，别删。
 */
data class Song(
    val hash: String,
    val albumAudioId: Long,
    val name: String,
    val singer: String,
    val albumName: String,
    val albumId: String,
    val durationMs: Long,
    val coverTemplate: String,
    val bitrate: Int,
    val extName: String,
    val fileSize: Long,
) {
    /** 稳定的列表 key：同一首歌在不同接口下 hash 大小写可能不同。 */
    val key: String get() = hash.lowercase() + "_" + albumAudioId

    fun cover(size: Int = 240): String? =
        coverTemplate.takeIf { it.isNotBlank() }?.replace("{size}", size.toString())

    val durationText: String
        get() {
            val total = durationMs / 1000
            return "%d:%02d".format(total / 60, total % 60)
        }

    val qualityLabel: String
        get() = when {
            bitrate >= 900 -> "SQ"
            bitrate >= 300 -> "HQ"
            else -> "标准"
        }

    companion object {

        /** 搜索结果（/search）→ Song。 */
        fun fromSearch(o: JSONObject): Song? {
            val hash = o.str("FileHash").ifBlank { return null }
            return Song(
                hash = hash,
                albumAudioId = o.long("MixSongID"),
                name = o.str("SongName").ifBlank { o.str("FileName") },
                singer = o.str("SingerName"),
                albumName = o.str("AlbumName"),
                albumId = o.str("AlbumID"),
                durationMs = o.long("Duration") * 1000L,
                coverTemplate = o.str("Image"),
                bitrate = o.int("Bitrate"),
                extName = o.str("ExtName").ifBlank { "mp3" },
                fileSize = o.long("FileSize"),
            )
        }

        /** 每日推荐（/everyday/recommend）→ Song。 */
        fun fromRecommend(o: JSONObject): Song? {
            val hash = o.str("hash").ifBlank { o.str("hash_128") }.ifBlank { return null }
            return Song(
                hash = hash,
                albumAudioId = o.long("album_audio_id").takeIf { it > 0 } ?: o.long("mixsongid"),
                name = o.str("songname").ifBlank { o.str("ori_audio_name") },
                singer = o.str("author_name"),
                albumName = o.str("album_name"),
                albumId = o.str("album_id"),
                durationMs = o.long("time_length") * 1000L,
                coverTemplate = o.str("sizable_cover"),
                bitrate = o.int("bitrate").takeIf { it > 0 } ?: 128,
                extName = o.str("extname").ifBlank { "mp3" },
                fileSize = o.long("file_size"),
            )
        }

        /**
         * 歌单曲目（`/playlist/track/all`、`/playlist/track/all/new`、`/rank/audio`）→ Song。
         *
         * 这一族接口的字段藏得很散：`audio_info.hash_128`、`album_info.sizable_cover`、
         * `base.album_id`、`mixsongid` / `Audioid` / `add_mixsongid` ……
         * 上游的做法是**先把嵌套对象浅展开成顶层**再按候选键取值（顶层优先），
         * 这里照抄 —— 只认某一种形态的话，换一个歌单就整单解析成空，
         * 「我喜欢」同步后一片空白就是这么来的。
         */
        fun fromPlaylistFile(o: JSONObject): Song? {
            val m = flattenNested(o)
            val hash = m.str("hash")
                .ifBlank { m.str("FileHash") }
                .ifBlank { m.str("hash_128") }
                .ifBlank { m.str("Hash128") }
                .ifBlank { m.str("hash_320") }
                .ifBlank { m.str("sq_hash") }
                .ifBlank { m.str("hq_hash") }
                .ifBlank { m.str("sd_hash") }
                .ifBlank { return null }
            val albumAudioId = m.long("album_audio_id").takeIf { it > 0 }
                ?: m.long("AlbumAudioID").takeIf { it > 0 }
                ?: m.long("MixSongID").takeIf { it > 0 }
                ?: m.long("mixsongid").takeIf { it > 0 }
                ?: m.long("add_mixsongid").takeIf { it > 0 }
                ?: m.long("Audioid").takeIf { it > 0 }
                ?: m.long("audio_id").takeIf { it > 0 }
                ?: return null

            // 歌单曲目的「名字」有好几种给法，而且都可能是「歌手 - 歌名」的合并串：
            //   /playlist/track/all  → 顶层 `name`（就是合并串）
            //   /playlist/track/all/new → `filename`
            // 不拆的话整行会显示成「周杰伦 - 稻香」当成歌名。
            //
            // 注意 `name` 只从**原始对象**取，不能用展开后的 m：展开会把
            // `album_info.name`（专辑名）也塞进 `name`，没有歌名时就会拿专辑名顶替。
            val merged = m.str("songname")
                .ifBlank { m.str("SongName") }
                .ifBlank { m.str("ori_audio_name") }
                .ifBlank { m.str("audio_name") }
                .ifBlank { m.str("filename") }
                .ifBlank { m.str("FileName") }
                .ifBlank { o.str("name") }
            val sep = merged.indexOf(" - ")
            val split = if (sep > 0) merged.substring(0, sep) to merged.substring(sep + 3) else null
            // 拆出来的后半段才是歌名；前半段只在接口没给 singerinfo 时当歌手用。
            val name = split?.second ?: merged
            val singer = singerOf(m).ifBlank { split?.first.orEmpty() }

            return Song(
                hash = hash,
                albumAudioId = albumAudioId,
                name = name,
                singer = singer,
                albumName = m.str("album_name")
                    .ifBlank { m.str("AlbumName") }
                    .ifBlank { m.str("albumname") },
                albumId = m.str("album_id")
                    .ifBlank { m.str("AlbumID") }
                    .ifBlank { m.str("albumid") },
                durationMs = durationMsOf(m),
                coverTemplate = m.str("sizable_cover")
                    .ifBlank { m.str("imgurl") }
                    .ifBlank { m.str("Image") }
                    .ifBlank { m.str("img") }
                    .ifBlank { m.str("cover") }
                    .ifBlank { m.str("cover_pic") }
                    .ifBlank { m.str("pic") }
                    // /playlist/track/all 的封面只挂在 trans_param.union_cover 上，
                    // 而且也是带 {size} 的模板。
                    .ifBlank { o.obj("trans_param")?.str("union_cover").orEmpty() },
                bitrate = m.int("bitrate").takeIf { it > 0 } ?: 128,
                extName = m.str("extname").ifBlank { m.str("ExtName") }.ifBlank { "mp3" },
                fileSize = m.long("filesize_128").takeIf { it > 0 }
                    ?: m.long("file_size").takeIf { it > 0 }
                    ?: m.long("FileSize"),
            )
        }

        /**
         * 听歌识曲结果（`/audio/match` 的 `data[0]`）→ Song。
         *
         * 识别结果**不一定带 hash**，只给 `album_audio_id` + 歌名歌手。
         * 没 hash 时这里返回 null，调用方改用「歌手 + 歌名」搜索兜底。
         */
        fun fromMatch(o: JSONObject): Song? {
            val hash = o.str("hash").ifBlank { o.str("FileHash") }.ifBlank { o.str("file_hash") }
            if (hash.isBlank()) return null
            val albumAudioId = o.long("album_audio_id").takeIf { it > 0 }
                ?: o.long("MixSongID").takeIf { it > 0 }
                ?: o.long("mixsongid").takeIf { it > 0 }
                ?: return null
            return Song(
                hash = hash,
                albumAudioId = albumAudioId,
                name = o.str("songname").ifBlank { o.str("song_name") }.ifBlank { o.str("name") },
                singer = o.str("singername").ifBlank { o.str("singer_name") }
                    .ifBlank { o.str("author_name") },
                albumName = o.str("album_name").ifBlank { o.str("albumname") },
                albumId = o.str("album_id"),
                durationMs = (o.long("timelen") / 1000L).takeIf { it > 0 }
                    ?: (o.long("duration") * 1000L).takeIf { it > 0 }
                    ?: 0L,
                coverTemplate = o.str("sizable_cover").ifBlank { o.str("imgurl") }.ifBlank { o.str("img") },
                bitrate = o.int("bitrate").takeIf { it > 0 } ?: 128,
                extName = o.str("extname").ifBlank { "mp3" },
                fileSize = o.long("file_size"),
            )
        }

        /**
         * 榜单曲目（/rank/audio）→ Song。
         *
         * 这个接口的字段嵌套最深：hash 不在顶层，而是按音质拆在
         * `audio_info.hash_128 / hash_320 / hash_flac` 里；封面在
         * `album_info.sizable_cover`。取 `hash_128` 是因为匿名状态下只有
         * 128k 拿得到，用别的 hash 去换地址会直接失败。
         */
        fun fromRank(o: JSONObject): Song? {
            val audio = o.obj("audio_info") ?: return null
            val album = o.obj("album_info")
            val hash = audio.str("hash_128")
                .ifBlank { audio.str("hash_320") }
                .ifBlank { return null }
            val albumAudioId = o.long("album_audio_id")
            if (albumAudioId <= 0) return null
            return Song(
                hash = hash,
                albumAudioId = albumAudioId,
                name = o.str("songname"),
                singer = o.str("author_name"),
                albumName = album?.str("album_name").orEmpty(),
                albumId = o.str("album_id"),
                durationMs = audio.long("duration_128").takeIf { it > 0 }
                    ?: audio.long("duration_320"),
                coverTemplate = album?.str("sizable_cover").orEmpty(),
                bitrate = audio.int("bitrate").takeIf { it > 0 } ?: 128,
                extName = audio.str("extname").ifBlank { "mp3" },
                fileSize = audio.long("filesize_128"),
            )
        }
    }
}

// ---- 歌单曲目的通用解析 ----

/** 需要被「浅展开」到顶层的嵌套键。顺序无关，顶层永远覆盖嵌套。 */
private val SongNestedKeys = listOf(
    "album_info", "albuminfo", "audio", "audio_info", "song_info", "base", "rec_song_info",
)

/**
 * 把嵌套对象浅展开到顶层（顶层优先）。
 *
 * 酷狗同一首歌在不同接口里，`hash_128` 可能在顶层，也可能在 `audio_info` 里；
 * `album_audio_id` 可能叫 `mixsongid` / `Audioid` / `add_mixsongid`。
 * 展开之后再按候选键取值，就不用为每种形态各写一遍。
 */
internal fun flattenNested(o: JSONObject): JSONObject {
    val merged = JSONObject()
    for (k in SongNestedKeys) {
        val nested = o.obj(k) ?: continue
        val keys = nested.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!merged.has(key)) merged.put(key, nested.opt(key))
        }
    }
    val keys = o.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        merged.put(key, o.opt(key))
    }
    return merged
}

/** 歌手名：标量优先，其次 singerinfo / Singers / authors 数组。 */
internal fun singerOf(o: JSONObject): String {
    val scalar = o.str("author_name")
        .ifBlank { o.str("SingerName") }
        .ifBlank { o.str("artist_name") }
        .ifBlank { o.str("singername") }
    if (scalar.isNotBlank()) return scalar
    for (k in listOf("singerinfo", "Singers", "singers", "authors")) {
        val arr = o.arr(k) ?: continue
        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val n = item.str("name").ifBlank { item.str("author_name") }
            if (n.isNotBlank()) names += n
        }
        if (names.isNotEmpty()) return names.joinToString("、")
    }
    return ""
}

/**
 * 时长归一化成毫秒。
 *
 * 单位完全不统一：`time_length` / `Duration` 可能是毫秒也可能是秒，
 * `timelen` 是**微秒**，`timelength` 是毫秒。大于 10000 的一律当毫秒 ——
 * 一首歌不可能只有 10 秒，但也不会有 10000 秒（2.7 小时）的流行歌。
 */
internal fun durationMsOf(o: JSONObject): Long {
    val raw = o.long("time_length").takeIf { it > 0 }
        ?: o.long("HQDuration").takeIf { it > 0 }
        ?: o.long("Duration").takeIf { it > 0 }
        ?: o.long("duration").takeIf { it > 0 }
        ?: 0L
    if (raw > 0) return if (raw > 10_000) raw else raw * 1000
    val tl = (o.long("timelength").takeIf { it > 0 }
        ?: o.long("timelength_128").takeIf { it > 0 }
        ?: o.long("timelength_320").takeIf { it > 0 }
        ?: o.long("timelength_flac").takeIf { it > 0 }
        ?: 0L)
    if (tl > 0) return if (tl > 10_000) tl else tl * 1000
    // `timelen` 的单位随接口变：有的给微秒（217,000,000），
    // 有的直接给毫秒（217,364 = 3:37）。按量级判断，不要写死除法 ——
    // 写死 /1000 会让 3 分半的歌显示成 0:00。
    val tl2 = o.long("timelen").takeIf { it > 0 } ?: 0L
    if (tl2 >= 10_000_000L) return tl2 / 1000
    if (tl2 >= 10_000L) return tl2
    if (tl2 > 0) return tl2 * 1000
    return 0
}

// ---- 宽松取值：字段可能是 String / Number，缺失时给默认值 ----

internal fun JSONObject.str(key: String): String {
    if (!has(key) || isNull(key)) return ""
    return when (val v = opt(key)) {
        is String -> v
        is Number -> v.toString()
        else -> v?.toString().orEmpty()
    }
}

internal fun JSONObject.long(key: String): Long {
    if (!has(key) || isNull(key)) return 0L
    return when (val v = opt(key)) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: 0L
        else -> 0L
    }
}

internal fun JSONObject.int(key: String): Int = long(key).toInt()

internal fun JSONObject.obj(key: String): JSONObject? =
    if (has(key) && !isNull(key)) optJSONObject(key) else null

internal fun JSONObject.arr(key: String): org.json.JSONArray? =
    if (has(key) && !isNull(key)) optJSONArray(key) else null

/**
 * 上游业务错误文案。
 *
 * 酷狗的报错形态不统一，同一个「失败」在不同接口里分别长这样：
 * `{"error_msg":"..."}` / `{"msg":"..."}` / `{"data":"验证码错误"}`。
 * 第三种最坑 —— `data` 本该是对象，失败时直接退化成字符串，所以调用方
 * 用 `obj("data")` 拿不到任何东西，只能靠这里兜。
 */
internal fun JSONObject.bizMessage(): String {
    for (k in listOf("error_msg", "msg", "message", "error")) {
        val s = str(k)
        if (s.isNotBlank() && s != "null") return s
    }
    return when (val d = opt("data")) {
        is String -> if (d.isBlank() || d == "null") "" else d
        is JSONObject -> d.bizMessage()
        else -> ""
    }
}
