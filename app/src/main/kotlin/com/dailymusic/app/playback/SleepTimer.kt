package com.dailymusic.app.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 睡眠定时。
 *
 * 两种模式：
 * - **倒计时**：到点直接暂停，队列不动 —— 用户半夜醒来点一下播放就能接着听；
 * - **播完本曲**：交给 Media3 的 `pauseAtEndOfMediaItems`，播到当前曲结尾自然停。
 *   自己监听「切歌」再暂停是不行的：那时下一首已经开始出声了。
 *
 * 状态放在 object 里而不是某个 Composable 的 `remember`：定时必须活过播放页的
 * 打开/关闭，用户设完 30 分钟就该能退出播放页。
 */
object SleepTimer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** 剩余毫秒。0 表示没有倒计时在跑。 */
    var remainingMs by mutableStateOf(0L)
        private set

    /** 「播完本曲」模式。和 [remainingMs] 互斥。 */
    var untilTrackEnd by mutableStateOf(false)
        private set

    val active: Boolean get() = remainingMs > 0L || untilTrackEnd

    /** 供 UI 直接显示的文案：`29:58` / `播完本曲` / 空。 */
    val label: String
        get() = when {
            untilTrackEnd -> "播完本曲"
            remainingMs <= 0L -> ""
            else -> {
                // 向上取整，免得最后一秒显示 0:00 却还在响。
                val total = (remainingMs + 999) / 1000
                "%d:%02d".format(total / 60, total % 60)
            }
        }

    fun start(minutes: Int) {
        cancel()
        remainingMs = minutes * 60_000L
        job = scope.launch {
            while (remainingMs > 0L) {
                delay(1_000)
                remainingMs = (remainingMs - 1_000).coerceAtLeast(0L)
            }
            PlayerManager.pause()
            remainingMs = 0L
            job = null
        }
    }

    fun startUntilTrackEnd() {
        cancel()
        untilTrackEnd = true
    }

    fun cancel() {
        job?.cancel()
        job = null
        remainingMs = 0L
        untilTrackEnd = false
    }

    /** 当前曲自然播完时由 [PlayerManager] 回调，把「播完本曲」模式收尾。 */
    fun onTrackEnded() {
        if (!untilTrackEnd) return
        untilTrackEnd = false
    }
}
