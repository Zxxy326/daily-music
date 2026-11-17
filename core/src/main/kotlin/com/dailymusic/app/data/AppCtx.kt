package com.dailymusic.app.data

import android.content.Context

/**
 * 全局 Context 的持有者。
 *
 * core 模块不能引用 app 模块的 Application 类，但接口层（KugouApi）偶尔需要
 * 一个 Context 去拿 SharedPreferences / 缓存目录。由各自的 Application 在
 * onCreate 里调 [init] 注入。
 */
object AppCtx {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun require(): Context = appContext
        ?: error("AppCtx 未初始化：Application.onCreate 里要先调 AppCtx.init(this)")
}
