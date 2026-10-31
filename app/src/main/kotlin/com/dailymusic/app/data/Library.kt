package com.dailymusic.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** 一条播放记录。`count` 是累计播放次数，重复听同一首不会刷出一堆重复行。 */
data class PlayRecord(
    val song: Song,
    val lastPlayedAt: Long,
    val count: Int,
)

/**
 * 本地音乐库：收藏 + 播放历史。
 *
 * 为什么用 SharedPreferences 而不是 Room：
 * 这里最多存几百首歌的元数据（每条约 300 字节），Room 要引入注解处理器、
 * 迁移、DAO，收益为零。上游 md3Music 的收藏也是放在 shared_preferences 里，
 * 数据结构与这里一致（key = hash + album_audio_id）。
 *
 * 为什么 Song 要整体序列化而不是只存 key：
 * 收藏页、历史页都要能**离线**显示歌名/歌手/封面/时长，只存 key 的话必须
 * 联网重新查一遍，列表会先空白再跳动。整存 11 个字段代价很小。
 */
object Library {

    private const val PREFS_NAME = "daily_music_library"
    private const val KEY_FAVORITES = "favorites_v1"
    private const val KEY_HISTORY = "history_v1"

    /** 本地收藏归属的账号 userid，用来识别「换了个人登录」。 */
    private const val KEY_FAVORITE_OWNER = "fav_owner_v1"
    private const val HISTORY_LIMIT = 300

    private var prefs: SharedPreferences? = null

    var favorites by mutableStateOf<List<Song>>(emptyList())
        private set

    var history by mutableStateOf<List<PlayRecord>>(emptyList())
        private set

    /** 幂等。在 Application.onCreate 里调一次即可。 */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        favorites = runCatching { decodeSongs(p.getString(KEY_FAVORITES, null)) }.getOrDefault(emptyList())
        history = runCatching { decodeHistory(p.getString(KEY_HISTORY, null)) }.getOrDefault(emptyList())
    }

    fun isFavorite(key: String): Boolean = favorites.any { it.key == key }

    /** 返回操作后的状态，方便 UI 立刻给反馈。 */
    fun toggleFavorite(song: Song): Boolean {
        val nowFavorite = !isFavorite(song.key)
        favorites = if (nowFavorite) {
            listOf(song) + favorites.filterNot { it.key == song.key }
        } else {
            favorites.filterNot { it.key == song.key }
        }
        persistFavorites()
        // 本地先改（红心必须立刻响应），云端在后台追。
        // 未登录时 push 内部会直接返回 —— 收藏仍然只存本地，但不会再假装同步过。
        FavoriteCloud.push(song, nowFavorite)
        return nowFavorite
    }

    /**
     * 与账号「我喜欢」对齐。
     *
     * 合并策略是**并集**，不是「云端覆盖本地」：
     * - 云端有、本地没有 → 收进来（换设备登录后收藏页立刻是满的，不用等用户自己点）；
     * - 本地有、云端没有 → 留在本地并**补传**（离线时点的红心、或上次同步失败的）。
     *
     * 不做「云端没有就删本地」：那会在同步失败的瞬间把用户的收藏清空，
     * 代价远大于「在另一台设备上取消的收藏又被带回来」。
     *
     * @return 云端新增的条数；null 表示这次没拉到（未登录 / 接口失败），本地保持不动。
     */
    suspend fun syncFromCloud(): Int? {
        val uid = Auth.currentUserId() ?: return null
        val cloud = FavoriteCloud.pullAll() ?: return null

        // 云端读到空、本地却有收藏 —— 这不可能是「用户把收藏删光了」，
        // 只可能是接口抽风或字段又变了。这种情况绝不能拿空列表去覆盖本地，
        // 否则用户的收藏会被一次同步悄悄清空（这就是上一版的表现）。
        if (cloud.isEmpty() && favorites.isNotEmpty()) {
            FavoriteCloud.reportError("云端「我喜欢」读到空列表，本地收藏已保留")
            return null
        }
        val cloudKeys = cloud.map { it.key }.toHashSet()

        // 本地这批收藏属于谁。
        //
        // 没有记录（从没登录过）→ 认作当前账号，本地独有的那批补传上去；
        // 记录的是别人 → **绝不能补传**，否则会把上一个账号的收藏灌进新账号。
        // 这种情况下直接以云端为准，本地那批留在本机但不进新账号。
        val owner = prefs?.getString(KEY_FAVORITE_OWNER, null)
        val switched = owner != null && owner != uid
        val localOnly = if (switched) emptyList() else favorites.filter { it.key !in cloudKeys }
        val merged = cloud + localOnly

        if (merged.map { it.key } != favorites.map { it.key }) {
            favorites = merged
            persistFavorites()
        }
        if (localOnly.isNotEmpty()) FavoriteCloud.pushAll(localOnly)
        prefs?.edit()?.putString(KEY_FAVORITE_OWNER, uid)?.apply()
        return cloud.size
    }

    private fun persistFavorites() {
        prefs?.edit()?.putString(KEY_FAVORITES, encodeSongs(favorites))?.apply()
    }

    fun recordPlay(song: Song) {
        val now = System.currentTimeMillis()
        val existing = history.firstOrNull { it.song.key == song.key }
        val rest = history.filterNot { it.song.key == song.key }
        history = (listOf(PlayRecord(song, now, (existing?.count ?: 0) + 1)) + rest).take(HISTORY_LIMIT)
        prefs?.edit()?.putString(KEY_HISTORY, encodeHistory(history))?.apply()
    }

    fun clearHistory() {
        history = emptyList()
        prefs?.edit()?.putString(KEY_HISTORY, "[]")?.apply()
    }

    // ---------------- 序列化 ----------------

    private fun Song.toJson(): JSONObject = JSONObject().apply {
        put("hash", hash)
        put("albumAudioId", albumAudioId)
        put("name", name)
        put("singer", singer)
        put("albumName", albumName)
        put("albumId", albumId)
        put("durationMs", durationMs)
        put("coverTemplate", coverTemplate)
        put("bitrate", bitrate)
        put("extName", extName)
        put("fileSize", fileSize)
    }

    private fun songFrom(o: JSONObject): Song? {
        val hash = o.str("hash")
        if (hash.isBlank()) return null
        return Song(
            hash = hash,
            albumAudioId = o.long("albumAudioId"),
            name = o.str("name"),
            singer = o.str("singer"),
            albumName = o.str("albumName"),
            albumId = o.str("albumId"),
            durationMs = o.long("durationMs"),
            coverTemplate = o.str("coverTemplate"),
            bitrate = o.int("bitrate"),
            extName = o.str("extName"),
            fileSize = o.long("fileSize"),
        )
    }

    private fun encodeSongs(list: List<Song>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    private fun decodeSongs(raw: String?): List<Song> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { songFrom(it) }?.let { out += it }
        }
        return out
    }

    private fun encodeHistory(list: List<PlayRecord>): String {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("song", r.song.toJson())
                    put("lastPlayedAt", r.lastPlayedAt)
                    put("count", r.count)
                },
            )
        }
        return arr.toString()
    }

    private fun decodeHistory(raw: String?): List<PlayRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<PlayRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val song = o.obj("song")?.let { songFrom(it) } ?: continue
            out += PlayRecord(song, o.long("lastPlayedAt"), o.int("count").coerceAtLeast(1))
        }
        return out
    }
}
