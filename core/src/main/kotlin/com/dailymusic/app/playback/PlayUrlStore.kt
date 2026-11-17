package com.dailymusic.app.playback

import android.net.Uri
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.PlayUrl
import com.dailymusic.app.data.Song
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Collections

/**
 * 播放地址中转站：把「歌曲」和「酷狗 URL」之间的解析结果共享给播放器底层。
 *
 * 为什么需要它：
 * 为了让通知栏、锁屏、蓝牙耳机都能「上一首 / 下一首」，必须把**整个列表**作为
 * 队列塞进 ExoPlayer，而不是每次只塞一首。但酷狗的播放地址是**逐首**换取且有
 * 有效期的，不可能在点歌时把 30 首的地址全部请求一遍（30 次网络往返，好几秒）。
 *
 * 解法：队列里每首歌的 URI 先放一个占位符 `kugou://<key>`，等 ExoPlayer 真正
 * 加载到这一首时，由 [ResolvingDataSource][androidx.media3.datasource.ResolvingDataSource]
 * 回调到这里同步换出真实地址。于是队列、自动续播、通知栏控制全部是原生行为，
 * 而地址解析依然是懒加载的。
 *
 * 注意 [resolve] 是**阻塞**的：它跑在 ExoPlayer 的 loading 线程上，不在主线程，
 * 所以可以用 runBlocking 把 suspend 的 [KugouApi.playUrl] 拉进来。
 */
object PlayUrlStore {

    /** 占位 URI 的 scheme。用自定义 scheme 避免和真实 http 地址混淆。 */
    const val SCHEME = "kugou"

    /** 当前请求音质。由 [PlayerManager] 写入，服务侧读取。 */
    @Volatile
    var quality: String = "320"

    private val songs: MutableMap<String, Song> = Collections.synchronizedMap(HashMap())

    /** key + 音质 → 地址。LRU 上限 64，切音质会自然产生新条目。 */
    private val urls: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 64
        },
    )

    /** 最近一次解析出的地址，用于 UI 如实显示实际音质（CDN 路径里带 _qu128_ 这样的段）。 */
    private val labels: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 64
        },
    )

    fun placeholder(key: String): Uri = Uri.parse("$SCHEME://$key")

    /** 点歌时把整批歌曲登记进来，之后 [resolve] 才查得到 hash / album_audio_id。 */
    fun remember(list: List<Song>) {
        list.forEach { songs[it.key] = it }
    }

    fun labelFor(key: String): String? = labels[key]

    /**
     * 音质代号 → 展示文案。
     *
     * 优先用**服务端回写的实际音质**（`/song/url/new` 的 `data.quality`），
     * 它才是真正加载的档位；[labelFrom] 只在拿不到该字段时从 CDN 路径反推。
     */
    fun labelOf(quality: String): String = when (quality) {
        "128" -> "标准 128k"
        "320" -> "高品质 320k"
        "flac" -> "无损 FLAC"
        "high" -> "Hi-Res"
        "viper_tape" -> "蝰蛇母带"
        else -> ""
    }

    fun labelFrom(url: String): String = Regex("""_qu(\d+)_""").find(url)?.groupValues?.get(1)
        ?.toIntOrNull()
        ?.let {
            when (it) {
                0 -> "试听"
                in 1..191 -> "标准 128k"
                in 192..320 -> "高品质 320k"
                in 321..1500 -> "无损 FLAC"
                else -> "Hi-Res"
            }
        }.orEmpty()

    /**
     * 占位 URI → 真实可播地址。失败抛 [IOException]，ExoPlayer 会转成
     * `PlaybackException`，[PlayerManager] 再把原因翻译成人话。
     */
    fun resolve(uri: Uri): String {
        // 注意不能用 schemeSpecificPart 直接当 key：`kugou://xxx` 的
        // schemeSpecificPart 是 `//xxx`（带两个斜杠），会拼出查不到的 key。
        val key = (uri.host ?: uri.schemeSpecificPart?.removePrefix("//"))
            ?: throw IOException("bad media uri: $uri")
        val cacheKey = "$key@$quality"
        urls[cacheKey]?.let { return it }

        val song = songs[key] ?: throw IOException("未登记的歌曲：$key")
        val ok = when (val result = runBlocking { KugouApi.playUrl(song, quality) }) {
            is PlayUrl.Ok -> result
            PlayUrl.Encrypted -> throw EncryptedAudioException()
            PlayUrl.Unavailable -> throw IOException("酷狗没有给出可用地址（无版权或需要登录）")
        }
        urls[cacheKey] = ok.url
        // 实际音质以服务端回写为准；它缺失时才从 CDN 路径段反推。
        labelOf(ok.quality).ifEmpty { labelFrom(ok.url) }
            .takeIf { it.isNotEmpty() }?.let { labels[key] = it }
        return ok.url
    }

    /** 切音质后旧地址作废。 */
    fun invalidate(key: String? = null) {
        if (key == null) urls.clear() else urls.keys.removeAll { it.startsWith("$key@") }
    }
}

/** 只有 .mgg 加密流、客户端暂无解密能力。单独成类是为了让 UI 给出准确提示。 */
class EncryptedAudioException : IOException("这首是加密格式（.mgg），当前版本还不支持解密")
