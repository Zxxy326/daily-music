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
import androidx.compose.ui.platform.LocalContext
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
import com.dailymusic.app.data.SearchHistory
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    onOpenArtist: (String) -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
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
            if (list.isNotEmpty()) SearchHistory.add(context, kw)
            if (list.isEmpty()) error = "没有搜到「$kw」"
            loading = false
        }
    }

    val currentKey = PlayerManager.current?.key
    val playing = PlayerManager.isPlaying

    // 搜的是人、是专辑、还是歌？酷狗只给了一个「按歌曲搜」的接口，
    // 所以这里按结果反推意图：命中的歌里大半都是同一个歌手，那用户多半在搜人。
    val q = query.trim()
    val artistName = remember(results, q) { dominantArtist(results, q) }
    val albumName = remember(results, q) { dominantAlbum(results, q) }
    val kind = when {
        artistName != null -> SearchKind.ARTIST
        albumName != null -> SearchKind.ALBUM
        else -> SearchKind.SONG
    }
    // 点开卡片后只看这一类，再点一次取消
    var onlyKind by remember(results, kind) { mutableStateOf(false) }
    val shown = if (!onlyKind) results else when (kind) {
        SearchKind.ARTIST -> results.filter { it.singer.contains(artistName.orEmpty(), true) }
        SearchKind.ALBUM -> results.filter { it.albumName.equals(albumName.orEmpty(), true) }
        SearchKind.SONG -> results
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp)) {
                // 二级页进来要返回键；宽屏下它是侧栏的一级页，onBack 为 null 就不画。
                if (onBack != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onBack),
                            contentAlignment = Alignment.Center,
                        ) {
                            TintedIcon(AppIcons.ArrowBack, colors.onSurface, 20.dp)
                        }
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
                                .clickable {
                                    // 清空要连「已搜过」一起复位，否则结果区还挂着上一次的列表，
                                    // 历史 / 热词区块永远出不来。
                                    query = ""
                                    results = emptyList()
                                    error = null
                                    searched = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            TintedIcon(AppIcons.Close, colors.onSurfaceVariantSummary, 16.dp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (!searched && SearchHistory.items.isNotEmpty()) {
            item { SectionTitle("搜索历史", trailing = "${SearchHistory.items.size} 条") }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    SearchHistory.items.chunked(3).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { word ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colors.surfaceContainer)
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
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { SearchHistory.clear(context) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("清空历史", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    }
                }
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
            if (kind != SearchKind.SONG) {
                item {
                    SearchHeaderCard(
                        kind = kind,
                        title = if (kind == SearchKind.ARTIST) artistName.orEmpty() else albumName.orEmpty(),
                        subtitle = if (kind == SearchKind.ARTIST) {
                            "歌手"
                        } else {
                            results.firstOrNull { it.albumName == albumName }?.singer.orEmpty()
                        },
                        count = if (kind == SearchKind.ARTIST) {
                            results.count { it.singer.contains(artistName.orEmpty(), true) }
                        } else {
                            results.count { it.albumName.equals(albumName.orEmpty(), true) }
                        },
                        coverUrl = results.firstOrNull {
                            if (kind == SearchKind.ARTIST) it.singer.contains(artistName.orEmpty(), true)
                            else it.albumName.equals(albumName.orEmpty(), true)
                        }?.cover(300),
                        active = onlyKind,
                        onOpen = {
                            if (kind == SearchKind.ARTIST) onOpenArtist(artistName.orEmpty())
                            else onlyKind = !onlyKind
                        },
                        onToggle = { onlyKind = !onlyKind },
                    )
                }
            }
            item {
                SectionTitle(
                    if (kind == SearchKind.SONG) "搜索结果" else "单曲",
                    trailing = "${shown.size} 首",
                )
            }
            items(shown, key = { it.key }) { song ->
                SongRow(
                    song = song,
                    isCurrent = song.key == currentKey,
                    isPlaying = playing,
                    onClick = { onPlay(song, shown) },
                    showFavorite = true,
                    favorite = Library.isFavorite(song.key),
                    onToggleFavorite = { Library.toggleFavorite(song) },
                )
            }
        }
    }
}


/** 搜索意图：搜人 / 搜专辑 / 搜歌。 */
private enum class SearchKind { ARTIST, ALBUM, SONG }

/**
 * 从搜索结果反推「用户搜的是不是某个歌手」。
 *
 * 酷狗只给按歌曲搜的接口，没有独立的歌手/专辑接口，所以只能看结果分布：
 * 前 20 条里至少 4 条是同一个歌手、且占了一半以上，就认为在搜人。
 * 返回的歌名要是整串匹配（忽略大小写）才算，避免「周杰伦」把「周杰伦的徒弟」也算进去。
 */
private fun dominantArtist(results: List<Song>, query: String): String? {
    if (query.isEmpty() || results.size < 4) return null
    val hits = results.mapNotNull { song ->
        song.singer.split("/", "、", ",", "&", "；", ";")
            .map { it.trim() }
            .firstOrNull { it.equals(query, ignoreCase = true) }
    }
    if (hits.isEmpty()) return null
    val top = hits.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
    return if (top.value >= 4 && top.value * 2 >= results.size) top.key else null
}

/** 同理判断专辑：整串匹配专辑名，至少 3 条、且占比够高。 */
private fun dominantAlbum(results: List<Song>, query: String): String? {
    if (query.isEmpty() || results.size < 3) return null
    val hits = results.map { it.albumName.trim() }.filter { it.equals(query, ignoreCase = true) }
    if (hits.size < 3) return null
    return hits.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

/**
 * 搜索结果的「头图卡片」：搜人时显示歌手、搜专辑时显示专辑。
 *
 * 没有歌手/专辑接口，头像就用 TA 在结果里的第一首歌封面 —— 比空着好看，
 * 也不会假装是官方资料页。点一下只在当前结果里筛选，再点取消。
 */
@Composable
private fun SearchHeaderCard(
    kind: SearchKind,
    title: String,
    subtitle: String,
    count: Int,
    coverUrl: String?,
    active: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val isArtist = kind == SearchKind.ARTIST
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(16.dp))
                .background(colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUrl != null) {
                RemoteImage(
                    url = coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    shape = if (isArtist) CircleShape else RoundedCornerShape(16.dp),
                    showFallbackIcon = false,
                )
            } else {
                TintedIcon(AppIcons.Person, colors.onSurfaceVariantSummary, 28.dp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (isArtist) "$subtitle · $count 首" else "$subtitle · 专辑 · $count 首",
                fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (active) colors.primary else colors.surfaceContainerHigh)
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                text = if (active) "看全部" else if (isArtist) "只看 TA" else "只听这张",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (active) colors.onPrimary else colors.onSurfaceSecondary,
            )
        }
    }
}
