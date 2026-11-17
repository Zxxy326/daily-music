package com.dailymusic.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 搜索历史：最近 10 条，重复搜同一个词只留最新的一条。 */
object SearchHistory {

    var items by mutableStateOf<List<String>>(emptyList())
        private set

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_ITEMS, null)
            .orEmpty()
        items = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX)
    }

    fun add(context: Context, keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val next = (listOf(kw) + items.filter { !it.equals(kw, ignoreCase = true) }).take(MAX)
        items = next
        save(context, next)
    }

    fun remove(context: Context, keyword: String) {
        val next = items.filter { it != keyword }
        items = next
        save(context, next)
    }

    fun clear(context: Context) {
        items = emptyList()
        save(context, emptyList())
    }

    private fun save(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(K_ITEMS, list.joinToString("\n")).apply()
    }

    private const val PREFS = "search_history"
    private const val K_ITEMS = "items"
    private const val MAX = 10
}
