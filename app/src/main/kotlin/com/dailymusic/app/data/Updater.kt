package com.dailymusic.app.data

import android.content.Context
import android.content.Intent
import android.util.Log
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在线更新。
 *
 * 三个现实约束决定了这里的写法：
 *
 * 1. **国内直连 GitHub 经常不通**，所以 API 和下载都准备了镜像回退：
 *    先打官方域名，失败再依次套 `ghfast.top` / `gh-proxy.com` / `ghproxy.net`。
 *    镜像只是把 `https://github.com/...` 前缀换成自己，路径原样保留。
 * 2. **版本号只能自己比**：AGP 8 默认不开 `buildConfig`，拿不到 BuildConfig.VERSION_NAME，
 *    所以从 PackageManager 读当前 versionName，再和 release 的 tag（`v1.0.2`）逐段比数字。
 * 3. **安装必须交给系统**：应用内不能静默装包，只能下载完抛 ACTION_VIEW 让系统弹安装器；
 *    用户没给「安装未知应用」权限时先把他导到那个设置页。
 */
object Updater {

    private const val REPO = "Zxxy326/daily-music"
    private const val TAG = "Updater"

    /**
     * 首选源：仓库根目录的 `version.json`（raw 域名，镜像最多、不吃 API 配额）。
     *
     * 为什么不用 API 当主源：未认证的 GitHub API 每个 IP 每小时只有 60 次，
     * 走代理 / 公司出口 IP 很容易被别人吃光 —— 实测直接回 403，
     * 那样更新功能就等于没有。
     */
    /**
     * `version.json` 的候选地址。
     *
     * 顺序不重要 —— [fetchLatest] 是**并发竞速**，谁先回来用谁，所以这里只要把
     * 「国内能直连的」和「需要梯子的」都列上，任何网络环境下总有一个能通：
     *
     * | 源 | 国内直连 |
     * |---|---|
     * | `raw.gitmirror.com` | ✅ 专为 raw 做的镜像 |
     * | `cdn.jsdelivr.net` | ✅ 有国内节点 |
     * | `ghfast.top` / `gh-proxy.com` / `ghproxy.net` | 大多数时候 ✅ |
     * | `github.moeyy.xyz` / `gh.llkk.cc` | 备选，轮换可用 |
     * | `raw.githubusercontent.com` | ❌ 需要梯子 |
     */
    private val RawBases = listOf(
        "https://raw.gitmirror.com/$REPO/main/version.json",
        "https://cdn.jsdelivr.net/gh/$REPO@main/version.json",
        "https://ghfast.top/https://raw.githubusercontent.com/$REPO/main/version.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/$REPO/main/version.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/$REPO/main/version.json",
        "https://github.moeyy.xyz/https://raw.githubusercontent.com/$REPO/main/version.json",
        "https://gh.llkk.cc/https://raw.githubusercontent.com/$REPO/main/version.json",
        "https://raw.githubusercontent.com/$REPO/main/version.json",
    )

    /** 兜底：GitHub API（未认证 60 次/小时，只在前面的都失败时才用）。 */
    private val ApiBases = listOf(
        "https://api.github.com",
        "https://ghfast.top/https://api.github.com",
        "https://gh-proxy.com/https://api.github.com",
    )

    /**
     * 下载前缀：空串 = 直连官方。
     *
     * 和检查一样是「并发探路、谁先通用谁」，所以顺序只影响探路时的排队，不影响可用性。
     */
    private val DownloadPrefixes = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://github.moeyy.xyz/",
        "",
    )

    data class UpdateInfo(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    /** UI 观察的状态机。 */
    sealed interface State {
        data object Idle : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val info: UpdateInfo, val progress: Float) : State
        data class Ready(val info: UpdateInfo, val file: File) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    /** 最近一次拿到的版本信息。下载失败后点「重试」要用它，失败态里就不必再带一份。 */
    private var lastInfo: UpdateInfo? = null

    /** 「立即更新」和「重试」共用：拿最近一次检查到的包重新下载。 */
    suspend fun startOrRetry(context: Context) {
        val info = lastInfo ?: return
        downloadAndInstall(context, info)
    }

    /** 用户点了「稍后」，本次进程内不再打扰。 */
    private var dismissedVersion: String? = null

    fun dismiss() {
        val info = when (val s = state) {
            is State.Available -> s.info
            is State.Downloading -> s.info
            is State.Ready -> s.info
            else -> null
        }
        dismissedVersion = info?.version
        state = State.Idle
    }

    /**
     * 静默检查。启动后调用一次即可，失败完全静默（不打扰用户）。
     *
     * @return 是否有新版本（供调用方决定要不要弹提示）。
     */
    suspend fun checkSilently(context: Context): Boolean {
        Log.w(TAG, "checkSilently start")
        if (state is State.Downloading) return false
        val current = currentVersionName(context)
        // 总超时兜底：某个镜像「握手很快、body 一点点往外吐」时，单次 readTimeout 会被
        // 每次 read 重置，检查能拖十几秒。这里直接给整次检查一个上限，超时就当没有更新。
        val info = withTimeoutOrNull(12_000) { fetchLatest() } ?: return false
        Log.w(TAG, "current=$current remote=${info.version}")
        if (compareVersion(info.version, current) <= 0) return false
        if (info.version == dismissedVersion) return false
        lastInfo = info
        state = State.Available(info)
        return true
    }

    /** 下载 + 拉起安装器。进度写回 [state]。 */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val file = withContext(Dispatchers.IO) { download(context, info) }
        if (file == null) {
            state = State.Failed("下载失败，请检查网络后重试")
            return
        }
        state = State.Ready(info, file)
        install(context, file)
    }

    // ---------------- 检查 ----------------

    private suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        // 1) version.json —— **并发竞速**，不排队。
        //
        // 之前是 for 循环逐个试、每个 8s 超时：国内没梯子时前两个源必然超时，
        // 光「检查更新」就要干等 16s，用户以为卡死了。现在所有源同时发，
        // 谁先回用谁，总耗时 = 最快的那个（通常 0.5~2s）。
        val texts = race(RawBases.map { url ->
            {
                Log.w(TAG, "try $url")
                // JSON 只有几百字节，5s 读不完就是这条链路有问题，别再等。
                readText(url, 5_000)
            }
        }, overallTimeoutMs = 10_000)

        for (text in texts) {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val ver = o.str("version").trim().removePrefix("v")
            val apk = o.str("apk").ifBlank {
                "https://github.com/$REPO/releases/download/v$ver/daily-music-$ver.apk"
            }
            if (ver.isBlank() || apk.isBlank()) continue
            Log.w(TAG, "raw ok: $ver")
            return@withContext UpdateInfo(
                version = ver,
                notes = o.str("notes").trim(),
                apkUrl = apk,
                sizeBytes = o.long("size"),
            )
        }

        // 2) GitHub API 兜底
        for (base in ApiBases) {
            Log.w(TAG, "try $base")
            val json = runCatching {
                val conn = (URL("$base/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "DailyMusic-Updater")
                }
                try {
                    val code = conn.responseCode
                    Log.w(TAG, "$base -> HTTP $code")
                    if (code !in 200..299) return@runCatching null
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w(TAG, "check via $base failed: ${it.javaClass.simpleName} ${it.message}") }
                .getOrNull() ?: continue

            val o = runCatching { JSONObject(json) }.getOrNull() ?: continue
            val tag = o.str("tag_name").trim().removePrefix("v").removePrefix("V")
            if (tag.isBlank()) continue
            val assets = o.optJSONArray("assets")
            var apkUrl = ""
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.str("name")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    apkUrl = a.str("browser_download_url")
                    size = a.long("size")
                    break
                }
            }
            if (apkUrl.isBlank()) continue
            return@withContext UpdateInfo(
                version = tag,
                notes = o.str("body").trim(),
                apkUrl = apkUrl,
                sizeBytes = size,
            )
        }
        null
    }

    private fun readText(url: String, timeoutMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DailyMusic-Updater")
        }
        return try {
            val code = conn.responseCode
            Log.w(TAG, "$url -> HTTP $code")
            if (code !in 200..299) error("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 下载 ----------------

    private suspend fun download(context: Context, info: UpdateInfo): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // 每次换源重下都写同一个文件，先清掉半截包。
        val out = File(dir, "daily-music-${info.version}.apk")
        out.delete()

        val urls = DownloadPrefixes.map { it + info.apkUrl }.distinct()

        // 并发探路：五个源同时发请求，谁先回 200 就从谁下。
        // 逐个试的话，第一个源（国内经常不通）会把 12s 连接超时全吃掉。
        val first = raceOpen(urls, 7_000)

        if (first != null && streamToFile(first.second, out, info)) {
            return@withContext out
        }
        out.delete()

        // 探路的那条断了就按顺序把剩下的补一遍。
        for (url in urls) {
            if (url == first?.first) continue
            Log.w(TAG, "download fallback $url")
            val conn = runCatching { openConn(url, 10_000) }.getOrNull() ?: continue
            if (streamToFile(conn, out, info)) return@withContext out
            out.delete()
        }
        null
    }

    /**
     * 并发开连接，返回**最先连通**的那一条（其余立刻关掉）。
     *
     * 只发请求头不读 body，所以探路本身很快；返回的 connection 直接交给 [streamToFile]
     * 继续读，等于省掉了一次重复的握手。
     */
    private suspend fun raceOpen(
        urls: List<String>,
        timeoutMs: Int,
    ): Pair<String, HttpURLConnection>? = coroutineScope {
        val wins = Channel<Pair<String, HttpURLConnection>>(Channel.UNLIMITED)
        val claimed = AtomicBoolean(false)
        val jobs = urls.map { url ->
            launch(Dispatchers.IO) {
                val conn = runCatching { openConn(url, timeoutMs) }.getOrNull() ?: return@launch
                if (claimed.compareAndSet(false, true)) {
                    wins.send(url to conn)
                } else {
                    runCatching { conn.disconnect() }
                }
            }
        }
        val result = withTimeoutOrNull(timeoutMs + 2_000L) { wins.receive() }
        jobs.forEach { it.cancel() }
        wins.close()
        result
    }

    /** 打开并确认是 200；失败返回 null（连接已经关掉，不会漏 socket）。 */
    private fun openConn(url: String, timeoutMs: Int): HttpURLConnection? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            // 走系统默认代理（可能有 VPN / 代理工具），这里**不能** NO_PROXY：
            // 下载的是外网资源，和打本机服务不是一回事。
            connectTimeout = timeoutMs
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DailyMusic-Updater")
        }
        return try {
            val code = conn.responseCode
            Log.w(TAG, "probe $url -> HTTP $code")
            if (code in 200..299) conn else null.also { conn.disconnect() }
        } catch (e: Throwable) {
            runCatching { conn.disconnect() }
            null
        }
    }

    /** 把一条已连通的 connection 读进文件；大小对不上算失败（镜像限流常给半截）。 */
    private fun streamToFile(conn: HttpURLConnection, out: File, info: UpdateInfo): Boolean {
        return try {
            val total = conn.contentLengthLong
            var read = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            state = State.Downloading(info, (read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            val ok = total <= 0 || read == total
            if (!ok) Log.w(TAG, "incomplete: $read / $total")
            ok && read > 0
        } catch (e: Throwable) {
            Log.w(TAG, "stream failed: ${e.javaClass.simpleName}")
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 并发跑一组请求，收集「先回来的几个」。
     *
     * 拿到第一个成功后再多等 1.2s 收其他源的结果 —— 万一第一个返回的是镜像的错误页
     * （200 + HTML），调用方还能用第二个去解析。总时长封顶 [overallTimeoutMs]。
     */
    private suspend fun race(
        tasks: List<suspend () -> String?>,
        overallTimeoutMs: Long,
    ): List<String> = coroutineScope {
        // 只把**成功**的结果送进来。送 null 的话 `withTimeoutOrNull { receive() }`
        // 收到 null 会和「超时」长得一模一样，循环会被一次失败直接打断。
        val channel = Channel<String>(Channel.UNLIMITED)
        val jobs = tasks.map { task ->
            launch(Dispatchers.IO) {
                runCatching { task() }.getOrNull()?.let { channel.send(it) }
            }
        }
        val results = mutableListOf<String>()
        val start = System.currentTimeMillis()
        var firstAt = 0L
        while (true) {
            val now = System.currentTimeMillis()
            // 拿到第一条之后最多再等 0.6s 收备胎：万一第一条是镜像的错误页，还能用第二条。
            val limit = if (firstAt == 0L) {
                start + overallTimeoutMs
            } else {
                minOf(firstAt + 600L, start + overallTimeoutMs)
            }
            val remain = limit - now
            if (remain <= 0) break
            val text = withTimeoutOrNull(remain) { channel.receive() } ?: break
            results.add(text)
            if (firstAt == 0L) firstAt = System.currentTimeMillis()
            if (results.size >= 2) break
        }
        jobs.forEach { it.cancel() }
        channel.close()
        results
    }

    // ---------------- 安装 ----------------

    private fun install(context: Context, file: File) {
        // 没有「安装未知应用」权限时，直接抛 Intent 会失败且没有任何提示，
        // 所以先判断再决定是弹安装器还是把用户送到授权页。
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            state = State.Failed("请允许「安装未知应用」后再点一次更新")
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            state = State.Failed("拉起安装器失败：${it.javaClass.simpleName}")
        }
    }

    // ---------------- 工具 ----------------

    private fun currentVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 逐段比数字：1.0.10 > 1.0.9（字符串比较会翻车）。 */
    private fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
