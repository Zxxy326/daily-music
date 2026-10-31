package com.dailymusic.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Lyrics
import com.dailymusic.app.data.Song
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 播放器单例（UI 侧）。
 *
 * 架构：UI → [MediaController] → [PlaybackService] 里的 ExoPlayer。
 *
 * 为什么不直接持有一个 ExoPlayer：
 * 那样 [PlaybackService] 里的 MediaSession 永远不会被注册进 MediaSessionService
 * （只有 MediaController 连上来、走 onGetSession 时才会注册），后果是
 * **没有媒体通知、服务不进前台、退到后台就被回收**。这个坑踩过一次。
 *
 * 队列：点歌时把**整个列表**作为队列交给播放器（URI 用 `kugou://<key>` 占位，
 * 加载时才换真实地址，见 [PlayUrlStore]）。所以上一首/下一首/自动续播都是
 * ExoPlayer 的原生行为，通知栏和蓝牙耳机也能直接用。
 */
object PlayerManager {

    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var connected: CompletableDeferred<MediaController?>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var lyricJob: Job? = null

    /** 连续因播放错误自动跳过的次数。成功播放一首就清零。 */
    private var autoSkips = 0

    /** 已记入播放历史的 key，避免同一首重复计数。 */
    private var recordedKey: String? = null

    /** 当前音质档位。匿名只有 128 能真正拿到，其余会在请求失败后自动降级。 */
    var quality by mutableStateOf(PlayUrlStore.quality)
        private set

    var queue by mutableStateOf<List<Song>>(emptyList())
        private set

    var index by mutableStateOf(-1)
        private set

    var current by mutableStateOf<Song?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var isBuffering by mutableStateOf(false)
        private set

    var positionMs by mutableStateOf(0L)
        private set

    var durationMs by mutableStateOf(0L)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var lyrics by mutableStateOf(Lyrics.Empty)
        private set

    var lyricsLoading by mutableStateOf(false)
        private set

    /** 实际拿到的码率（用于如实显示，而不是显示请求的档位）。 */
    var actualQualityLabel by mutableStateOf("")
        private set

    /**
     * 已知被 DRM 挡住的曲目（key）。只在真正播放失败后写入，
     * 列表里据此提前标「加密」，避免用户反复点到放不了的歌。
     */
    var encryptedKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    private val listener = object : Player.Listener {

        /** 队列自动走到下一首、或用户点上一首/下一首时，都在这里换状态。 */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            val song = queue.getOrNull(c.currentMediaItemIndex)
            if (song == null || song.key != mediaItem?.mediaId) {
                // 队列被外部改动（理论上不会），以 mediaId 反查兜底。
                current = queue.firstOrNull { it.key == mediaItem?.mediaId } ?: current
            } else {
                current = song
            }
            index = c.currentMediaItemIndex
            positionMs = 0
            durationMs = current?.durationMs ?: 0
            actualQualityLabel = ""
            // 上一首的错误不能留在界面上，否则新歌一进来就顶着旧报错。
            error = null
            loadLyrics(current)
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING
            when (state) {
                Player.STATE_READY -> {
                    durationMs = controller?.duration?.takeIf { it > 0 } ?: (current?.durationMs ?: 0)
                    error = null
                    autoSkips = 0
                    // 地址是懒解析的，READY 时才能知道实际码率。
                    current?.key?.let { key ->
                        PlayUrlStore.labelFor(key)?.let { actualQualityLabel = it }
                        // 只有真正加载成功才记历史 —— 加密/无版权的歌不该污染播放记录。
                        // 用 key 去重，避免暂停后继续播又被记一次。
                        if (key != recordedKey) {
                            recordedKey = key
                            current?.let { Library.recordPlay(it) }
                        }
                    }
                }

                Player.STATE_ENDED -> {
                    // 队列播完：保留最后一首的封面，但回到停止态。
                    isPlaying = false
                    positionMs = durationMs
                    // 「播完本曲」的睡眠定时到此收尾，把 pauseAtEndOfMediaItems 复位，
                    // 否则用户下次点播放会在每首歌结尾都被莫名停住。
                    SleepTimer.onTrackEnded()
                }
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            isBuffering = false
            val cause = e.cause
            if (cause is EncryptedAudioException) {
                // 记下来，列表里就能提前标「加密」，用户不会再白点一次。
                current?.let { encryptedKeys = encryptedKeys + it.key }
            }
            error = when {
                cause is EncryptedAudioException ->
                    "这首是加密曲目（.mgg）\nKugou 只下发了 DRM 流，客户端解不开 —— 已自动跳到下一首"

                cause is IOException ->
                    "无法获取播放地址：${cause.message}"

                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "网络连接失败，检查一下网络"

                else -> "播放失败：${e.errorCodeName}"
            }

            // 一首放不出来不应该让整个队列停在这里。
            // 连续跳过 3 首还不行就停下，避免整张列表都不可播时疯狂空转。
            scope.launch {
                delay(700)
                val c = controller ?: return@launch
                if (autoSkips >= 3) return@launch
                if (!c.hasNextMediaItem()) return@launch
                autoSkips++
                c.moveToNextAndPlay()
            }
        }
    }

    /** 提前建立连接，省掉第一次点播放的等待。幂等。 */
    fun connect(context: Context) {
        if (future != null) return
        // 恢复上次选的音质：以前只存内存，每次冷启动都掉回默认档，
        // 用户选了 Hi-Res 重启就变回 320 —— 这行为不像「设置」。
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_QUALITY, null)
            ?.takeIf { it in KNOWN_QUALITIES }
            ?.let {
                quality = it
                PlayUrlStore.quality = it
            }
        val deferred = CompletableDeferred<MediaController?>()
        connected = deferred
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context.applicationContext, token).buildAsync()
        future = f
        f.addListener(
            {
                val c = runCatching { f.get() }.getOrNull()
                controller = c
                c?.addListener(listener)
                deferred.complete(c)
                startTicker()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private suspend fun await(): MediaController? {
        return connected?.await() ?: controller
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = scope.launch {
            while (true) {
                controller?.let { c ->
                    if (c.isPlaying) {
                        val pos = c.currentPosition.coerceAtLeast(0L)
                        positionMs = pos
                        // 「播完本曲」的睡眠定时。
                        // 本想用 setPauseAtEndOfMediaItems，但那是 ExoPlayer 上的
                        // @UnstableApi 方法，MediaController 根本没暴露 —— 只能在这里
                        // 提前 350ms 踩刹车。再晚一点就到 STATE_ENDED 了，队列会自己
                        // 切下一首，等于没停住。
                        if (SleepTimer.untilTrackEnd) {
                            val total = c.duration
                            if (total > 0L && pos >= total - 350L) {
                                c.pause()
                                SleepTimer.onTrackEnded()
                            }
                        }
                    }
                }
                delay(120)
            }
        }
    }

    // ---------------- 对外操作 ----------------

    fun play(context: Context, song: Song, list: List<Song> = listOf(song)) {
        val items = list.ifEmpty { listOf(song) }
        val startIndex = items.indexOfFirst { it.key == song.key }.let { if (it < 0) 0 else it }

        connect(context)
        queue = items
        index = startIndex
        current = song
        error = null
        lyrics = Lyrics.Empty
        actualQualityLabel = ""
        positionMs = 0
        durationMs = song.durationMs
        isBuffering = true
        // 用户主动点同一首歌也算一次新的播放，所以这里清掉去重标记；
        // 暂停后继续播不会经过这里，因此不会被重复计数。
        recordedKey = null

        // 先把整批歌曲登记给地址解析器，再让播放器接手。
        PlayUrlStore.remember(items)
        loadLyrics(song)

        scope.launch {
            val c = await()
            if (c == null) {
                error = "播放服务连接失败"
                isBuffering = false
                return@launch
            }
            c.setMediaItems(items.map { it.toMediaItem() }, startIndex, 0L)
            c.prepare()
            c.play()
        }
    }

    fun toggle(context: Context) {
        val c = controller
        if (c == null) {
            connect(context)
            return
        }
        if (c.isPlaying) c.pause() else c.play()
    }

    /**
     * 只暂停，不切换。睡眠定时到点时用这个而不是 [toggle] ——
     * 万一那会儿本来就没在播，toggle 反而会把它播起来。
     */
    fun pause() {
        controller?.pause()
    }

    /**
     * 把一首歌放到当前曲之后。
     *
     * 两种情况分开处理：
     * 1. 队列里没有 → `addMediaItem` 增量插入。不要重建整个队列，重建会让当前正在放的
     *    这首重新 prepare，表现为「点了下一首播放，当前歌卡一下」。
     * 2. 队列里已经有 → 用 `moveMediaItem` 挪到当前曲之后。之前的写法是「已在队列就直接
     *    return」，但「更多」菜单只从播放页打开，这时歌曲**一定**在队列里，等于这一项
     *    永远点不出效果。挪位置才是「下一首播放」的语义。
     */
    fun enqueueNext(song: Song) {
        val c = controller ?: return
        val target = (index + 1).coerceIn(0, queue.size)
        val existing = queue.indexOfFirst { it.key == song.key }
        if (existing == target) return // 已经是下一首，不用动
        // 当前曲「排到自己后面」没有意义（挪一格只会把真正的下一首挤到第三位）。
        if (existing == index) return
        if (existing >= 0) {
            // moveMediaItem 的 newIndex 是「移除之后」的下标；歌曲原本在当前位置之前时
            // 移除会让当前曲整体前移一格，target 正好还是 index + 1，两个方向都对。
            c.moveMediaItem(existing, target)
            queue = queue.toMutableList().apply { add(target, removeAt(existing)) }
            return
        }
        c.addMediaItem(target, song.toMediaItem())
        queue = queue.toMutableList().apply { add(target, song) }
        // 地址解析器只认登记过的歌，漏登记会导致下一首解析不到 URL。
        PlayUrlStore.remember(listOf(song))
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0L))
        positionMs = ms.coerceAtLeast(0L)
    }

    fun next() {
        val c = controller ?: return
        c.moveToNextAndPlay(forcePlay = c.playWhenReady)
    }

    fun previous() {
        val c = controller ?: return
        // 播过 3 秒以上，上一首先回到本曲开头 —— 和主流播放器一致。
        if (c.currentPosition > 3000) {
            c.seekTo(0)
            return
        }
        if (!c.hasPreviousMediaItem()) return
        c.seekToPreviousMediaItem()
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playWhenReady) c.play()
    }

    /**
     * 切到下一首并确保真的开始加载。
     *
     * 为什么不能只调 `seekToNextMediaItem()`：一旦上一首抛过错，播放器会停在
     * [Player.STATE_IDLE]，此时 `seekToNextMediaItem()` 只把索引挪过去、**不会**
     * 重新 prepare，表现为「标题变了但一直 0:00、没有声音」。必须显式 prepare。
     */
    private fun MediaController.moveToNextAndPlay(forcePlay: Boolean = true) {
        if (!hasNextMediaItem()) return
        seekToNextMediaItem()
        if (playbackState == Player.STATE_IDLE) prepare()
        if (forcePlay) play()
    }

    fun setQuality(context: Context, q: String) {
        if (q == quality) return
        quality = q
        PlayUrlStore.quality = q
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(K_QUALITY, q).apply()
        // 旧地址是按旧音质换的，必须整体作废，否则 READY 时会继续播旧码率。
        PlayUrlStore.invalidate()
        val c = controller ?: return
        if (c.mediaItemCount == 0) return

        val at = c.currentPosition
        actualQualityLabel = ""
        isBuffering = true
        // 必须**重新 setMediaItems**，不能只 prepare()：ExoPlayer 会沿用同一个
        // MediaSource 及其已缓冲的样本，地址不会重新解析，结果就是切了音质还在放旧码率。
        c.setMediaItems(queue.map { it.toMediaItem() }, c.currentMediaItemIndex, at)
        c.prepare()
        if (isPlaying) c.play()
    }

    // ---------------- 内部 ----------------

    private fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(PlayUrlStore.placeholder(key))
        .setMediaId(key)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(singer)
                .setAlbumTitle(albumName)
                .setArtworkUri(cover(480)?.let { android.net.Uri.parse(it) })
                .build(),
        )
        .build()

    private fun loadLyrics(song: Song?) {
        lyricJob?.cancel()
        lyrics = Lyrics.Empty
        if (song == null) return
        lyricJob = scope.launch {
            lyricsLoading = true
            lyrics = KugouApi.lyric(song)
            lyricsLoading = false
        }
    }

    fun release() {
        ticker?.cancel()
        ticker = null
        lyricJob?.cancel()
        controller?.release()
        controller = null
        future = null
        connected = null
        isPlaying = false
    }

    private const val PREFS = "dailymusic_player"
    private const val K_QUALITY = "quality"

    /** 认得的音质代号，避免旧版本/脏数据把请求打到不存在的档位。 */
    private val KNOWN_QUALITIES = setOf(
        KugouApi.Q_128, KugouApi.Q_320, KugouApi.Q_FLAC,
        KugouApi.Q_HIRES, KugouApi.Q_VIPER_TAPE,
    )
}
