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
    private val RawBases = listOf(
        "https://raw.githubusercontent.com/$REPO/main/version.json",
        "https://ghfast.top/https://raw.githubusercontent.com/$REPO/main/version.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/$REPO/main/version.json",
        "https://cdn.jsdelivr.net/gh/$REPO@main/version.json",
    )

    /** 兜底：GitHub API。 */
    private val ApiBases = listOf(
        "https://api.github.com",
        "https://ghfast.top/https://api.github.com",
    )

    /** 下载前缀：空串 = 直连。 */
    private val DownloadPrefixes = listOf(
        "",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
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
        val info = fetchLatest() ?: return false
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
        // 1) version.json
        for (url in RawBases) {
            Log.w(TAG, "try $url")
            val text = runCatching { readText(url, 8_000) }
                .onFailure { Log.w(TAG, "raw failed: ${it.javaClass.simpleName}") }
                .getOrNull() ?: continue
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

        for (prefix in DownloadPrefixes) {
            val url = prefix + info.apkUrl
            val ok = runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    // 走系统默认代理（可能有 VPN / 代理工具），这里**不能** NO_PROXY：
                    // 下载的是外网资源，和打本机服务不是一回事。
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "DailyMusic-Updater")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching false
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
                                    val p = (read.toFloat() / total).coerceIn(0f, 1f)
                                    state = State.Downloading(info, p)
                                }
                            }
                        }
                    }
                    // 大小对不上就是下了一半（镜像限流很常见），当失败处理换下一个源。
                    total <= 0 || read == total
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)

            if (ok && out.length() > 0) return@withContext out
            out.delete()
        }
        null
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
