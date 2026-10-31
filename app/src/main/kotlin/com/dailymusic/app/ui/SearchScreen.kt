package com.dailymusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hot by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) { hot = KugouApi.hotKeywords() }

    fun runSearch(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty() || loading) return
        query = kw
        keyboard?.hide()
        scope.launch {
            loading = true
            error = null
            searched = true
            val list = KugouApi.search(kw)
            results = list
            if (list.isEmpty()) error = "没有搜到「$kw」"
            loading = false
        }
    }

    val currentKey = PlayerManager.current?.key
    val playing = PlayerManager.isPlaying

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp)) {
                // 搜索从底栏 tab 改成二级页了，所以补一个返回键。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        TintedIcon(AppIcons.ArrowBack, colors.onSurface, 20.dp)
                    }
                }
                Text(
                    text = "搜索",
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                )
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(46.dp)
                        .background(colors.surfaceContainerHigh, RoundedCornerShape(23.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TintedIcon(AppIcons.Search, colors.onSurfaceVariantSummary, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp, color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runSearch(query) }),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = "搜索歌曲、歌手、专辑",
                                        fontSize = 15.sp,
                                        color = colors.onSurfaceVariantSummary,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    if (query.isNotEmpty()) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clickable { query = "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            TintedIcon(AppIcons.Close, colors.onSurfaceVariantSummary, 16.dp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (!searched && hot.isNotEmpty()) {
            item { SectionTitle("热门搜索") }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    hot.chunked(3).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { word ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .background(colors.surfaceContainer, RoundedCornerShape(12.dp))
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { runSearch(word) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = word,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = colors.onSurfaceSecondary,
                                    )
                                }
                            }
                            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        if (loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("搜索中…", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                }
            }
        }

        if (searched && error != null && !loading) {
            item { ErrorBlock(error!!) { runSearch(query) } }
        }

        if (results.isNotEmpty()) {
            item { SectionTitle("搜索结果", trailing = "${results.size} 首") }
            items(results, key = { it.key }) { song ->
                SongRow(
                    song = song,
                    isCurrent = song.key == currentKey,
                    isPlaying = playing,
                    onClick = { onPlay(song, results) },
                    showFavorite = true,
                    favorite = Library.isFavorite(song.key),
                    onToggleFavorite = { Library.toggleFavorite(song) },
                )
            }
        }
    }
}
