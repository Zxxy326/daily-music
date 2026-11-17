package com.dailymusic.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager

/**
 * 搜索页。
 *
 * 电视没有键盘，所以自带一个屏幕键盘：遥控器方向键选字母、OK 输入。
 * 键盘常驻在右侧而不是弹层 —— 弹层会抢焦点，退出时焦点回到哪很难保证。
 */
@Composable
fun TvSearchScreen(onOpenPlayer: () -> Unit) {
    val context = LocalContext.current
    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(keyword) {
        if (keyword.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        searching = true
        results = runCatching { KugouApi.search(keyword) }.getOrDefault(emptyList())
        searching = false
    }

    Row(Modifier.fillMaxSize().padding(top = 28.dp, end = 40.dp)) {
        // 左侧：搜索框 + 结果
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(TvColor.card, RoundedCornerShape(18.dp))
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(
                    text = if (keyword.isBlank()) "用右侧键盘输入关键词" else keyword,
                    style = if (keyword.isBlank()) TvText.body else TvText.h2,
                )
            }

            Spacer(Modifier.height(16.dp))

            when {
                keyword.isBlank() -> Hint("输入关键词后自动搜索")
                searching -> Hint("搜索中…")
                results.isEmpty() -> Hint("没有找到「$keyword」")
                else -> {
                    BasicText(
                        text = "找到 ${results.size} 首",
                        style = TvText.caption,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(results, key = { _, s -> s.key }) { index, song ->
                            SongRow(
                                index = index,
                                name = song.name,
                                singer = song.singer,
                                cover = song.cover(160),
                                onClick = {
                                    PlayerManager.play(context, song, results)
                                    onOpenPlayer()
                                },
                                modifier = Modifier.fillMaxWidth().height(72.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(28.dp))

        // 右侧：屏幕键盘
        Keyboard(
            onKey = { keyword += it },
            onBackspace = { keyword = keyword.dropLast(1) },
            onClear = { keyword = "" },
            onSearch = { /* LaunchedEffect 已经跟着 keyword 走了 */ },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = TvText.body)
    }
}

private val KEY_ROWS = listOf(
    "abcdefg",
    "hijklmn",
    "opqrstu",
    "vwxyz'.",
    "0123456",
    "789-_ ",
)

@Composable
private fun Keyboard(
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    Column(
        Modifier.width(360.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KEY_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ch ->
                    KeyBox(label = ch.toString()) { onKey(ch.toString()) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyBox(label = "删除", wide = true) { onBackspace() }
            KeyBox(label = "清空", wide = true) { onClear() }
        }
    }
}

@Composable
private fun KeyBox(label: String, wide: Boolean = false, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.size(width = if (wide) 176.dp else 44.dp, height = 44.dp),
        shape = RoundedCornerShape(10.dp),
        scaleUp = 1.12f,
    ) {
        Box(
            Modifier.fillMaxSize().background(TvColor.card),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = TvText.h3.copy(fontSize = if (wide) 14.sp else 17.sp))
        }
    }
}
