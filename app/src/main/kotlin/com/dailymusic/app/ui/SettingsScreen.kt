package com.dailymusic.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.res.painterResource
import com.dailymusic.app.R
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dailymusic.app.data.Updater
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 项目主页。改仓库地址只改这一处。 */
private const val PROJECT_URL = "https://github.com/Zxxy326/daily-music"

/**
 * 设置 → 项目信息。
 *
 * 只做「关于这个 App 本身」的事：版本、更新、致谢、仓库、赞助。
 * 播放相关的设置（音质）留在「我的」页，不往这里搬 —— 两处都能改会让人不知道改哪个生效。
 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()

    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
    }

    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }
    var showThanks by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(
                    text = "设置",
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 2.dp),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        item { SectionLabelText("项目信息") }

        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(colors.surfaceContainer, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                SettingRow(
                    title = "当前版本",
                    value = if (version.isBlank()) "未知" else "v$version",
                )
                SettingDivider()
                SettingRow(
                    title = "检查更新",
                    value = when {
                        checking -> "检查中…"
                        else -> checkResult
                    },
                    onClick = {
                        if (checking) return@SettingRow
                        checking = true
                        checkResult = null
                        scope.launch {
                            val has = runCatching { Updater.checkSilently(context, manual = true) }
                                .getOrDefault(false)
                            checking = false
                            // 有新版本时弹窗由 RootScreen 统一弹（它盯着 Updater.state），
                            // 这里只回一行字，避免同一件事弹两次。
                            checkResult = if (has) "发现新版本" else "已是最新版本"
                        }
                    },
                )
                SettingDivider()
                SettingRow(title = "致谢名单", onClick = { showThanks = true })
                SettingDivider()
                SettingRow(
                    title = "项目地址",
                    value = "GitHub",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
                SettingDivider()
                SettingRow(title = "请作者喝杯咖啡", onClick = { showDonate = true })
            }
        }

        item {
            Text(
                text = "Daily Music 是个人项目，免费、无广告、无内购。",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp).padding(top = 14.dp),
                fontSize = 11.5.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }

    if (showThanks) {
        InfoDialog(title = "致谢名单", onDismiss = { showThanks = false }) {
            Credit("Miuix", "HyperOS 风格的 Compose 组件库")
            Credit("AndroidX Media3 / ExoPlayer", "播放内核")
            Credit("JNA", "直接调用 Rust 导出的 C 符号，绕开 JNI 包名耦合")
            Credit("md3Music", "上游的 Rust 服务与接口实现")
            Credit("阿里妈妈方圆体", "底栏与歌词动效页的字体。阿里巴巴免费商用字体，本项目按 GB2312 子集化后打包")
            Spacer(Modifier.height(6.dp))
            Text(
                text = "歌词相关实现参考",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            Credit("apple-music-like-lyrics", "Apple Music 风格逐字歌词渲染参考")
            Credit("Lyricon", "桌面歌词 Provider（词幕 / 悬浮歌词）")
            Credit("SuperLyric", "系统级实时歌词（lyricon / SuperLyric 协议）")
            Credit("LyricInfo", "蓝牙歌词推送（AVRCP / LyricInfo）参考")
            Credit("lyricy", "本地音乐标签编辑 / Lyricy 外部编辑协作")
            Credit("ColorOS-Live-Lyrics-Bridge", "ColorOS 息屏歌词桥接（lyricInfo 开放协议）")
            Credit("AM-Lyrics-for-Flyme / FlymeLyricBridge", "魅族 Flyme 状态栏歌词的独立实现，用于机制交叉验证")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "还有每一个愿意把歌听完的人。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    if (showDonate) {
        InfoDialog(title = "请作者喝杯咖啡", onDismiss = { showDonate = false }) {
            Text(
                text = "Daily Music 完全免费，没有广告，也没有内购。\n" +
                    "如果它帮你省下了一个会员，或者只是让你听歌时心情好了一点，" +
                    "欢迎请作者喝杯咖啡。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(14.dp))
            // 收款码：微信赞赏码，扫了就能直接打赏，不用跳浏览器
            Image(
                painter = painterResource(R.drawable.donate_qr),
                contentDescription = "赞赏码",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(196.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "微信扫一扫",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "去 GitHub 点个 Star",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SectionLabelText(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 8.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun SettingRow(
    title: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.5.sp, color = colors.onSurface)
        Spacer(Modifier.weight(1f))
        if (!value.isNullOrBlank()) {
            Text(value, fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
            Spacer(Modifier.width(6.dp))
        }
        if (onClick != null) {
            Text("›", fontSize = 17.sp, color = colors.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun SettingDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
    )
}

@Composable
private fun Credit(name: String, desc: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(
            text = name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = desc,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 统一样式的说明弹窗（致谢 / 赞助）。 */
@Composable
private fun InfoDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .background(colors.surface, RoundedCornerShape(24.dp))
                .padding(20.dp),
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.primary.copy(alpha = 0.12f))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "知道了",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                    )
                }
            }
        }
    }
}
