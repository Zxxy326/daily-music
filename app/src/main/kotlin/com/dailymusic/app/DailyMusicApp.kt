package com.dailymusic.app

import android.app.Application
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.FavoriteCloud
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.YouthVip
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

    fun launch(context: Application) {
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

class DailyMusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 收藏 / 历史是同步读盘的（几百条 JSON，毫秒级），放在这里能让首帧就显示正确计数。
        Library.init(this)
        // 收藏要同步到账号「我喜欢」，它的 listid / fileid 缓存同样要首帧就绪。
        FavoriteCloud.init(this)
        Auth.init(this)
        // 签到状态要在首帧之前就绪：没有它「我的」页面会闪一下「未签到」。
        YouthVip.init(this)
        AppBootstrap.launch(this)
    }
}
