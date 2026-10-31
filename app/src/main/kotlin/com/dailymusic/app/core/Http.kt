package com.dailymusic.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

/**
 * 极简 HTTP 客户端。
 *
 * 只跟本机 Rust 服务（127.0.0.1:<port>）通信，所以不需要连接池 / 重试 / 拦截器这些
 * 重型设施。用 HttpURLConnection 而非 OkHttp，是为了不给这个项目引入任何额外的
 * 网络依赖 —— 缓存里没有 OkHttp 4，只有 3.12，那版对 Kotlin 不友好。
 */
object Http {

    suspend fun getJson(
        base: String,
        path: String,
        params: Map<String, Any?> = emptyMap(),
        timeoutMs: Int = 20_000,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val query = params.entries
            .filter { it.value != null }
            .joinToString("&") { (k, v) -> "$k=${encodeValue(v.toString())}" }
        val full = if (query.isEmpty()) "$base$path" else "$base$path?$query"
        var conn: HttpURLConnection? = null
        try {
            // **必须显式 NO_PROXY**：这两个方法只打 127.0.0.1 上的本地 Rust 服务，
            // 而 Android 的 HttpURLConnection 会遵循系统全局 HTTP 代理
            // （模拟器上实测设了 10.0.2.2:7897 之后，所有本机请求都被塞进那个代理，
            // 连不上就变成 12s 超时 —— 表现为首页一直「加载中」、设备注册失败）。
            // 手机用户开 VPN / 抓包工具时同样会踩到。
            conn = (URL(full).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "DailyMusic/0.1 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 查询参数值编码。
     *
     * **不能用 `URLEncoder.encode` 的默认结果直接拼 URL**：它把空格编成 `+`，
     * 而 Rust 侧 `percent_decode_preserve_plus` 是按 `decodeURIComponent` 语义
     * 实现的（`+` 原样保留，只有 `%20` 才解成空格）—— 这是为了对齐上游 Dart
     * 客户端（Dio 用 `%20`）。
     *
     * 踩过的坑：`cookie=token=xxx; userid=2509...` 里的 `"; "` 被编成 `";+"`，
     * 服务端 `cookie_to_json` 按 `;` 切分后第二段的 key 变成 `"+userid"`，
     * **userid / vip_token / t1 全部丢失**。后果是 `/user/detail` 的 body 带着
     * `userid: 0` 却用真实 token 做 RSA 签名，上游判「身份不一致」回 `20018`
     * —— 整整查了半天，token 明明是好的。
     *
     * 同理，`keywords=周杰伦 - 稻香` 也会被编成 `+`，搜索/歌词搜索关键词被污染。
     */
    private fun encodeValue(raw: String): String =
        URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    /** 直接抓二进制（封面图用）。 */
    suspend fun getBytes(url: String, timeoutMs: Int = 15_000): ByteArray? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", "DailyMusic/0.1 (Android)")
                }
                if (conn.responseCode !in 200..299) return@withContext null
                conn.inputStream.use { it.readBytes() }
            } catch (_: Throwable) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    /**
     * POST 裸二进制 body，返回 JSON。
     *
     * 目前只有听歌识曲（`/audio/match`）用到 —— 它要的是 16bit PCM 原始字节，
     * 不是表单也不是 JSON，所以不能走 [getJson]。
     */
    suspend fun postBytes(
        base: String,
        path: String,
        params: Map<String, Any?> = emptyMap(),
        body: ByteArray,
        contentType: String,
        timeoutMs: Int = 20_000,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val query = params.entries
            .filter { it.value != null }
            .joinToString("&") { (k, v) -> "$k=${encodeValue(v.toString())}" }
        val full = if (query.isEmpty()) "$base$path" else "$base$path?$query"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(full).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("User-Agent", "DailyMusic/0.1 (Android)")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
