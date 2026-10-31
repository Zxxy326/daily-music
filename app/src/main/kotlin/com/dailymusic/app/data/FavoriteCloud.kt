package com.dailymusic.app.data

import android.content.Context
import android.util.Log
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收藏（红心）与酷狗账号「我喜欢」歌单的双向同步。
 *
 * 为什么要单独一个 object：本地收藏是 [Library] 管的 SharedPreferences，
 * 和账号没关系；点红心时必须**同时**写云端，否则换台设备登录后「我喜欢」里是空的
 * —— 这正是之前的行为。
 *
 * 四条实测约束（都是上游踩过的坑，照着做即可）：
 * 1. 「我喜欢」是私有歌单，靠 `/user/playlist` 里 `name == "我喜欢"` 或 `is_def == 2` 认。
 * 2. 取它的曲目只能用 `/playlist/track/all/new?listid=`；用 `global_collection_id`
 *    版本会拉不到（私有歌单 gcid 常为空）。
 * 3. 取消收藏必须用**歌单记录 fileid**，传 hash 上游回 `status=1` 但歌还在。
 *    所以拉列表时顺手把 `hash -> fileid` 记下来。
 * 4. 加歌的 `data` 是 `歌名|hash|album_id|album_audio_id`，四段给全，
 *    少后两段在部分曲库下会被拒。
 */
object FavoriteCloud {

    private const val PREFS_NAME = "daily_music_library"
    private const val KEY_LIST_ID = "fav_listid_v1"
    private const val KEY_FILE_IDS = "fav_fileids_v1"
    private const val PAGE_SIZE = 200
    private const val TAG = "FavoriteCloud"

    private var prefs: SharedPreferences? = null
    private var listId: String? = null

    /** hash（小写）→ 歌单记录 fileid。取消收藏靠它。 */
    private val fileIds = HashMap<String, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 正在同步（UI 可以据此显示转圈）。 */
    var syncing by mutableStateOf(false)
        private set

    /** 最近一次同步失败的原因；成功后清空。 */
    var lastError by mutableStateOf<String?>(null)
        private set

    var lastSyncAt by mutableLongStateOf(0L)
        private set

    /** 供调用方（[Library]）报告「拉到了但不可信」这类问题。 */
    fun reportError(message: String) {
        lastError = message
    }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        listId = p.getString(KEY_LIST_ID, null)
        runCatching { decodeFileIds(p.getString(KEY_FILE_IDS, null)) }
    }

    /** 登录后清掉上一个账号的缓存 —— 否则会往别人的「我喜欢」里写歌。 */
    fun clearCache() {
        listId = null
        fileIds.clear()
        lastError = null
        prefs?.edit()?.remove(KEY_LIST_ID)?.remove(KEY_FILE_IDS)?.apply()
    }

    /**
     * 定位「我喜欢」的 listid。
     *
     * 缓存优先：这个值一个账号内不会变，但 `/user/playlist` 是 POST 且带分页，
     * 每点一次红心都拉一遍太浪费。
     */
    private suspend fun resolveListId(force: Boolean = false): String? {
        if (!force) listId?.takeIf { it.isNotBlank() }?.let { return it }
        val items = KugouApi.userPlaylists() ?: run {
            lastError = "拉取账号歌单列表失败"
            return null
        }
        // 名字带空格的（"我喜欢 "）和改过名的都见过，所以用 contains 而不是全等；
        // is_def == 2 是「默认收藏歌单」的标记，名字被改掉时靠它兜底。
        val mine = items.firstOrNull {
            it.str("name").contains("我喜欢") || it.int("is_def") == 2
        } ?: run {
            lastError = "账号里没有名为「我喜欢」的歌单（共 ${items.size} 个歌单）"
            return null
        }
        val id = mine.str("listid").ifBlank { mine.str("list_id") }
        val foundName = mine.str("name")
        val foundDef = mine.int("is_def")
        Log.w(TAG, "我喜欢 listid=$id name=$foundName is_def=$foundDef")
        if (id.isBlank()) return null
        listId = id
        prefs?.edit()?.putString(KEY_LIST_ID, id)?.apply()
        return id
    }

    /** 点红心后的云端写入。UI 不等待结果，失败只记 [lastError]。 */
    fun push(song: Song, favorite: Boolean) {
        if (!Auth.isLoggedIn) return
        scope.launch {
            val ok = runCatching {
                val id = resolveListId() ?: return@runCatching false
                if (favorite) add(id, song) else remove(id, song)
            }.getOrElse { false }
            lastError = when {
                ok -> null
                favorite -> "收藏没同步到账号，下次启动会重试"
                else -> "取消收藏没同步到账号，下次启动会重试"
            }
            if (ok) lastSyncAt = System.currentTimeMillis()
        }
    }

    /** 批量补传（本地有、云端没有的歌）。 */
    suspend fun pushAll(songs: List<Song>): Int {
        if (songs.isEmpty() || !Auth.isLoggedIn) return 0
        val id = resolveListId() ?: return 0
        var done = 0
        // 每批 20 首：data 走 URL query，中文歌名百分号编码后膨胀 3 倍，太多会被网关截断。
        songs.chunked(20).forEach { batch ->
            val data = batch.joinToString(",") { trackData(it) }
            if (runCatching { KugouApi.playlistTracksAdd(id, data) }.getOrNull()?.int("status") == 1) {
                done += batch.size
            }
        }
        return done
    }

    /**
     * 拉云端「我喜欢」整表。
     *
     * 返回 null 表示「没拉到」（未登录 / 没找到歌单 / 请求失败），
     * 调用方据此决定要不要动本地数据 —— 不能把空列表当「云端没有收藏」。
     */
    suspend fun pullAll(maxPages: Int = 10): List<Song>? {
        if (!Auth.isLoggedIn) return null
        syncing = true
        try {
            return pullAllInner(maxPages)
        } finally {
            syncing = false
        }
    }

    private suspend fun pullAllInner(maxPages: Int): List<Song>? {
        val id = resolveListId() ?: return null
        val out = LinkedHashMap<String, Song>()
        var raw = 0
        var pages = 0
        for (page in 1..maxPages) {
            val items = KugouApi.myPlaylistSongs(id, page, PAGE_SIZE) ?: run {
                lastError = "歌单曲目请求失败（第 $page 页）"
                return null
            }
            if (items.isEmpty()) break
            raw += items.size
            pages++
            items.forEach { o ->
                val song = Song.fromPlaylistFile(o) ?: return@forEach
                val fid = o.long("fileid").takeIf { it > 0 }
                    ?: o.long("fileId").takeIf { it > 0 }
                    ?: o.long("FileId").takeIf { it > 0 }
                if (fid != null) fileIds[song.hash.lowercase()] = fid
                out[song.key] = song
            }
            if (items.size < PAGE_SIZE) break
        }
        // 一条都没解析出来 ≠ 云端没有收藏。
        // 这两种情况的处理完全不同：前者必须报错并保留本地数据，
        // 后者才是「确实空」。之前不区分，接口字段一变就把收藏页清空了。
        Log.w(TAG, "我喜欢 原始=$raw 解析=${out.size} 页数=$pages")
        if (raw > 0 && out.isEmpty()) {
            lastError = "「我喜欢」里 $raw 首歌都解析失败，本地收藏已保留"
            return null
        }
        if (raw == 0) {
            // 空歌单是合法状态（新账号），但也可能是缓存的 listid 失效了
            // （歌单被重建 / 换过账号）。清掉缓存，下次同步重新解析一次。
            listId = null
            prefs?.edit()?.remove(KEY_LIST_ID)?.apply()
            lastError = null
            persistFileIds()
            lastSyncAt = System.currentTimeMillis()
            return emptyList()
        }
        lastError = null
        persistFileIds()
        lastSyncAt = System.currentTimeMillis()
        return out.values.toList()
    }

    // ---------------- 内部 ----------------

    private suspend fun add(id: String, song: Song): Boolean {
        val j = KugouApi.playlistTracksAdd(id, trackData(song)) ?: return false
        return j.int("status") == 1
    }

    private suspend fun remove(id: String, song: Song): Boolean {
        val fileId = resolveFileId(id, song)
        val j = KugouApi.playlistTracksDel(id, fileId) ?: return false
        val ok = j.int("status") == 1
        if (ok) {
            fileIds.remove(song.hash.lowercase())
            persistFileIds()
        }
        return ok
    }

    /**
     * 找这首歌在「我喜欢」里的记录 fileid。
     *
     * 三条路径按代价从低到高：内存缓存 → 现场拉一页 → 退回 hash。
     * 最后那条基本删不掉（上游按 fileid 匹配），但本地状态照常清理，
     * 不至于卡在「点了取消收藏但红心还在」。
     */
    private suspend fun resolveFileId(id: String, song: Song): String {
        fileIds[song.hash.lowercase()]?.takeIf { it > 0 }?.let { return it.toString() }
        // 同会话内刚收藏的歌，启动时的整表里还没有 —— 现场补拉一次首页。
        runCatching {
            KugouApi.myPlaylistSongs(id, 1, PAGE_SIZE)?.forEach { o ->
                val s = Song.fromPlaylistFile(o) ?: return@forEach
                o.long("fileid").takeIf { it > 0 }?.let { fileIds[s.hash.lowercase()] = it }
            }
        }
        fileIds[song.hash.lowercase()]?.takeIf { it > 0 }?.let {
            persistFileIds()
            return it.toString()
        }
        return song.hash
    }

    private fun trackData(song: Song): String {
        val albumId = song.albumId.toLongOrNull() ?: 0L
        return "${song.name}|${song.hash}|$albumId|${song.albumAudioId}"
    }

    private fun persistFileIds() {
        val arr = JSONArray()
        fileIds.forEach { (hash, fid) ->
            arr.put(JSONObject().put("h", hash).put("f", fid))
        }
        prefs?.edit()?.putString(KEY_FILE_IDS, arr.toString())?.apply()
    }

    private fun decodeFileIds(raw: String?) {
        if (raw.isNullOrBlank()) return
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val h = o.str("h")
            val f = o.long("f")
            if (h.isNotBlank() && f > 0) fileIds[h] = f
        }
    }
}
