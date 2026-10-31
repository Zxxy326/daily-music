package com.dailymusic.app.playback

import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放服务。
 *
 * 职责边界：**服务是播放器的唯一所有者**。它创建 ExoPlayer、包进 MediaSession，
 * 然后等着 MediaController 连上来。
 *
 * 为什么播放器归服务所有、而不是归 UI 侧单例所有：
 * MediaSessionService 只有在 `onGetSession()` 被调用（也就是有控制器连上来）时
 * 才会把 session 注册进系统，进而拿到媒体通知、进入前台、响应蓝牙按键。
 * 如果 UI 侧自己 new 一个 ExoPlayer 再传给服务，服务重启后 session 会指向一个
 * 已经 release 的播放器 —— 之前踩过这个坑，表现为「退到后台没有通知、几秒后被回收」。
 *
 * 所以 [PlayerManager] 只做代理（MediaController），真正的状态在这里。
 *
 * 数据源：队列里的 URI 是 `kugou://<key>` 占位符，由 [ResolvingDataSource] 在
 * 真正加载某一首时才换出真实地址（见 [PlayUrlStore]）。这样队列、自动续播、
 * 通知栏的上一首/下一首都是 ExoPlayer 的原生能力，不用自己模拟。
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // 酷狗 CDN 会 302 到另一台机器，必须允许跨协议重定向，否则报 301。
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)

        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme == PlayUrlStore.SCHEME) {
                spec.withUri(Uri.parse(PlayUrlStore.resolve(spec.uri)))
            } else {
                spec
            }
        }

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .build()
            .apply {
                // 音频焦点：来电/其他 App 播放时自动压低或暂停，恢复后自动续播。
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                // 拔耳机 / 断蓝牙时暂停，而不是突然外放。
                setHandleAudioBecomingNoisy(true)
                // 播完最后一首就停，不要绕回第一首 —— 队列顺序播完是用户的预期。
                repeatMode = ExoPlayer.REPEAT_MODE_OFF
            }

        player = exo
        session = MediaSession.Builder(this, exo).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * 用户从最近任务里划掉 App 时调用。
     *
     * 正在播放就继续（这是听歌 App 的默认预期），否则收掉服务，
     * 免得留下一个空壳进程和一条没用的通知。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 先放 session 再放 player：MediaSession 内部还持有 player 引用。
        session?.run {
            player.clearMediaItems()
            release()
        }
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
