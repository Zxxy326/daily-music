package com.dailymusic.app.native

import android.content.Context
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File

/**
 * Rust 侧导出的纯 C 符号（见 rust/kugou_server/src/lib.rs）。
 * 走 JNA 直连，避开上游写死的 `Java_com_md3music_premium_...` JNI 包名耦合。
 */
private interface KugouLib : Library {
    fun start_server(port: Int, dataDir: String): Int
    fun stop_server()
    fun is_server_running(): Int
    fun get_server_port(): Int
}

/**
 * 内嵌酷狗 API 服务的生命周期封装。
 * 端口 0 表示由 Rust 侧随机选端口（被占用会自动换下一个）。
 */
object KugouServer {

    private var lib: KugouLib? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean
        get() = port > 0 && runCatching { lib?.is_server_running() == 1 }.getOrDefault(false)

    fun start(context: Context): Int {
        if (port > 0) return port
        return try {
            val l = lib ?: Native.load("kugou_server", KugouLib::class.java).also { lib = it }
            val dataDir = File(context.filesDir, "kugou").apply { mkdirs() }
            val p = l.start_server(0, dataDir.absolutePath)
            if (p > 0) {
                port = p
                lastError = null
            } else {
                lastError = "start_server 返回 0（Rust 侧启动失败）"
            }
            p
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            0
        }
    }

    fun stop() {
        runCatching { lib?.stop_server() }
        port = 0
    }
}
