package com.dailymusic.tv

import android.app.Application
import com.dailymusic.app.AppBootstrap
import com.dailymusic.app.data.AppCtx
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.FavoriteCloud
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.LyricPrefs
import com.dailymusic.app.data.SearchHistory
import com.dailymusic.app.data.YouthVip

class TvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCtx.init(this)
        Library.init(this)
        FavoriteCloud.init(this)
        Auth.init(this)
        LyricPrefs.load(this)
        SearchHistory.load(this)
        YouthVip.init(this)
        AppBootstrap.launch(this)
    }
}
