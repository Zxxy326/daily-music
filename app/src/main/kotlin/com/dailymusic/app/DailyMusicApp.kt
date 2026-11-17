package com.dailymusic.app

import android.app.Application
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.FavoriteCloud
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.LyricPrefs
import com.dailymusic.app.data.SearchHistory
import com.dailymusic.app.data.YouthVip

class DailyMusicApp : Application() {

    companion object {
        lateinit var instance: DailyMusicApp
            private set
    }
    override fun onCreate() {
        com.dailymusic.app.data.AppCtx.init(this)
        super.onCreate()
        // 收藏 / 历史是同步读盘的（几百条 JSON，毫秒级），放在这里能让首帧就显示正确计数。
        instance = this
        Library.init(this)
        // 收藏要同步到账号「我喜欢」，它的 listid / fileid 缓存同样要首帧就绪。
        FavoriteCloud.init(this)
        Auth.init(this)
        LyricPrefs.load(this)
        SearchHistory.load(this)
        // 签到状态要在首帧之前就绪：没有它「我的」页面会闪一下「未签到」。
        YouthVip.init(this)
        AppBootstrap.launch(this)
    }
}
