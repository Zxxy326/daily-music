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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.MyPlaylist
import com.dailymusic.app.data.Profile
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 我的歌单：我创建的 + 我收藏的。
 *
 * 数据全部来自账号（`/user/playlist`），换设备登录看到的是同一份 —— 不做本地缓存，
 * 缓存和云端一旦不一致，用户会以为「收藏的歌单丢了」。
 */
@Composable
fun MyPlaylistsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenPlaylist: (MyPlaylist) -> Unit,
) {
    val colors = MiuixTheme.colorScheme

    var items by remember { mutableStateOf<List<MyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        error = null
        if (!Auth.isLoggedIn) {
            items = emptyList()
            error = "登录后才能同步账号里的歌单"
            loading = false
            return@LaunchedEffect
        }
        val list = runCatching { KugouApi.myPlaylists() }.getOrNull()
        if (list == null) {
            error = "拉取歌单失败，检查网络后重试"
            items = emptyList()
        } else {
            items = list
        }
        loading = false
    }

    val created = items.filter { it.created }
    val collected = items.filter { !it.created }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item { SubPageHeader(title = "我的歌单", subtitle = "${items.size} 个", onBack = onBack) }

        when {
            loading -> item { LoadingHint("正在同步账号歌单…") }

            // 没登录时「重试」没有意义，引导去登录而不是给个点了没反应的按钮
            error != null -> item {
                if (Auth.isLoggedIn) {
                    EmptyHint(error!!, "重试") { attempt++ }
                } else {
                    EmptyHint(error!!, null, null)
                }
            }

            items.isEmpty() -> item { EmptyHint("账号里还没有歌单", null, null) }

            else -> {
                if (created.isNotEmpty()) {
                    item { SectionTitle("我创建的歌单", trailing = "${created.size} 个") }
                    items(created, key = { "c-${it.listid}" }) { p ->
                        PlaylistRow(p, onClick = { onOpenPlaylist(p) })
                    }
                }
                if (collected.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(6.dp))
                        SectionTitle("我收藏的歌单", trailing = "${collected.size} 个")
                    }
                    items(collected, key = { "f-${it.listid}" }) { p ->
                        PlaylistRow(p, onClick = { onOpenPlaylist(p) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: MyPlaylist, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme

    // 账号歌单经常没有自己的封面（「我喜欢的音乐」就是典型）。
    // 概念版的做法是拿这个歌单第一首歌的专辑图当封面 —— 这里照做，
    // 只对「没封面且不是空歌单」的那几个发请求，通常只有一两条。
    var derivedCover by remember(playlist.listid) { mutableStateOf<String?>(null) }
    LaunchedEffect(playlist.listid, playlist.coverTemplate, playlist.created) {
        // 自建歌单即使「有封面」也要拉首曲 —— 那个封面其实是账号头像。
        // 之前这里用 coverTemplate.isNotBlank() 直接 return，导致自建歌单永远走不到首曲逻辑。
        val needFirstSong = playlist.songCount > 0 &&
            (playlist.created || playlist.coverTemplate.isBlank())
        if (!needFirstSong) return@LaunchedEffect
        // 注意别传 pageSize=1：`/playlist/track/all/new` 在小页长下会返回空，
        // 拉回来再取第一条就行（自建歌单本来就没多少首）。
        derivedCover = runCatching {
            KugouApi.myPlaylistSongs(playlist.listid)
                ?.firstOrNull()
                ?.let { com.dailymusic.app.data.Song.fromPlaylistFile(it) }
                ?.cover(300)
        }.getOrNull()
    }
    // 我创建的歌单：有歌 → 用第一首歌的封面；没歌 → 用账号头像。
    // 收藏来的歌单：用歌单自己的封面，没有才退回首曲。
    // （自建歌单接口经常把「账号头像」塞进封面字段，所以自建的一律优先首曲。）
    val coverUrl = when {
        playlist.created && playlist.songCount > 0 -> derivedCover
            ?: playlist.cover(200).takeIf { it.isNotBlank() }
        playlist.created -> Profile.avatar.takeIf { it.isNotBlank() }
            ?: playlist.cover(200).takeIf { it.isNotBlank() }
        else -> playlist.cover(200).takeIf { it.isNotBlank() } ?: derivedCover
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(
            url = coverUrl,
            name = playlist.name,
            size = 56.dp,
            icon = null,
            gradient = null,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (playlist.songCount > 0) "${playlist.songCount} 首" else "歌单",
                fontSize = 11.5.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}
