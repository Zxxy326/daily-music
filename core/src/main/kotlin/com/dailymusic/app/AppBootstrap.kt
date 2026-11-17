package com.dailymusic.app

import android.content.Context
import com.dailymusic.app.native.KugouServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 启动引导。
 *
 * Rust 服务（JNA → libkugou_server.so）必须跑起来，所有接口才有意义。它在
 * Application 里异步启动，首帧不被阻塞；任何接口调用前用 [await] 等它 ready，
 * 避免出现「baseUrl 端口还是 0」的竞态。
 */
object AppBootstrap {

    private val ready = CompletableDeferred<Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var launched = false

    fun launch(context: Context) {
        if (launched) return
        launched = true
        scope.launch {
            val port = KugouServer.start(context)
            ready.complete(port)
        }
    }

    /** 返回本地服务端口；启动失败返回 0。 */
    suspend fun await(): Int = ready.await()
}
