package com.dailymusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dailymusic.app.data.Updater
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 发现新版本时的提示框。
 *
 * 下载过程就地显示进度，不另开页面 —— 更新包 30 多 MB，用户需要看到「在动」，
 * 否则很容易以为卡死直接杀掉进程。
 */
@Composable
fun UpdateDialog(
    state: Updater.State,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is Updater.State.Idle) return
    val colors = MiuixTheme.colorScheme

    val info = when (state) {
        is Updater.State.Available -> state.info
        is Updater.State.Downloading -> state.info
        is Updater.State.Ready -> state.info
        else -> null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(colors.surface)
                .padding(22.dp),
        ) {
            Text(
                text = if (info != null) "发现新版本 ${info.version}" else "更新失败",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(10.dp))

            when (state) {
                is Updater.State.Available -> {
                    Text(
                        text = state.info.notes.ifBlank { "包含若干问题修复与体验优化。" },
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = colors.onSurfaceSecondary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "安装包 ${formatSize(state.info.sizeBytes)}",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DialogAction("忽略本次", colors.onSurfaceSecondary, colors.surfaceContainerHigh, onDismiss)
                        Spacer(Modifier.width(10.dp))
                        DialogAction("下载更新", colors.onPrimary, colors.primary, onStart)
                    }
                }

                is Updater.State.Downloading -> {
                    val pct = (state.progress * 100).toInt()
                    Text(
                        text = "正在下载… $pct%",
                        fontSize = 13.sp,
                        color = colors.onSurfaceSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    // 自己画进度条：Miuix 的进度组件在不同版本里名字不一样，这里不引依赖。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(colors.surfaceContainerHigh),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(colors.primary),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "下载完成后会拉起系统安装器，按提示覆盖安装即可。",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                }

                is Updater.State.Ready -> {
                    Text(
                        text = "已下载完成，正在打开安装器…如果没反应，请检查是否允许「安装未知应用」。",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = colors.onSurfaceSecondary,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DialogAction("知道了", colors.onPrimary, colors.primary, onDismiss)
                    }
                }

                is Updater.State.Failed -> {
                    Text(
                        text = state.message,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = colors.error,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DialogAction("关闭", colors.onSurfaceSecondary, colors.surfaceContainerHigh, onDismiss)
                        Spacer(Modifier.width(10.dp))
                        DialogAction("重试", colors.onPrimary, colors.primary, onStart)
                    }
                }

                Updater.State.Idle -> Unit
            }
        }
    }
}

@Composable
private fun DialogAction(
    label: String,
    fg: androidx.compose.ui.graphics.Color,
    bg: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "未知大小"
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%d KB".format(bytes / 1024)
}
