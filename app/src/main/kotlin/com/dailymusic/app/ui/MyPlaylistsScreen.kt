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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.MyPlaylist
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceContainerHigh),
        ) {
            if (playlist.coverTemplate.isNotBlank()) {
                RemoteImage(
                    url = playlist.cover(200),
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(14.dp),
                    showFallbackIcon = true,
                )
            }
        }
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
