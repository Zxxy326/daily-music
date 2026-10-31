package com.dailymusic.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dailymusic.app.data.AudioMatch
import com.dailymusic.app.data.Song
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 听歌识曲。
 *
 * 交互只有一条路径：点麦克风 → 录 8 秒（环形进度 + 呼吸圈）→ 上传指纹 → 出结果。
 * 识别不出来不报错，直接提示「再试一次」，因为环境噪音导致失败太常见了。
 */
@Composable
fun RecognizeScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (Song) -> Unit,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<AudioMatch.Result?>(null) }
    var hint by remember { mutableStateOf("点一下开始听，把手机靠近音源") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (!ok) hint = "没有麦克风权限，识曲用不了"
    }

    fun start() {
        if (recording || busy) return
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        scope.launch {
            result = null
            recording = true
            busy = true
            progress = 0f
            hint = "正在听…"
            val r = runCatching {
                AudioMatch.recognize { p ->
                    progress = p
                    if (p > 0.99f) hint = "识别中…"
                }
            }.getOrElse { AudioMatch.Result.Failed(it.message ?: "录音失败") }
            recording = false
            busy = false
            progress = 0f
            result = r
            hint = when (r) {
                is AudioMatch.Result.Found -> "识别到了"
                AudioMatch.Result.Silent -> "太安静了，没听到声音"
                AudioMatch.Result.NotFound -> "没听出来，换个地方再试"
                is AudioMatch.Result.Failed -> r.message
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        SubPageHeader(title = "识曲", subtitle = "8 秒听音辨歌", onBack = onBack)

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(26.dp))
            MicButton(
                recording = recording,
                progress = progress,
                enabled = !busy || recording,
                accent = colors.primary,
                onAccent = colors.onPrimary,
                onClick = ::start,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = hint,
                fontSize = 13.5.sp,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )

            val r = result
            if (r is AudioMatch.Result.Found) {
                Spacer(Modifier.height(26.dp))
                RecognizedCard(
                    song = r.song,
                    onPlay = { onPlay(r.song) },
                    onRetry = ::start,
                )
            } else if (r != null && r !is AudioMatch.Result.Found) {
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.primary.copy(alpha = 0.10f))
                        .clickable { start() }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = "再试一次",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                    )
                }
            }
        }
    }
}

/** 麦克风按钮：录音时外面套一圈呼吸光 + 进度环。 */
@Composable
private fun MicButton(
    recording: Boolean,
    progress: Float,
    enabled: Boolean,
    accent: Color,
    onAccent: Color,
    onClick: () -> Unit,
) {
    val pulse = rememberInfiniteTransition(label = "mic")
    val breath by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "breath",
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        if (recording) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2 * (0.72f + 0.28f * breath)
                drawCircle(
                    color = accent.copy(alpha = 0.16f * (1f - breath)),
                    radius = r,
                    center = Offset(size.width / 2, size.height / 2),
                )
            }
        }
        // 进度环
        Canvas(Modifier.size(168.dp)) {
            val stroke = 5.dp.toPx()
            val radius = size.minDimension / 2 - stroke
            drawCircle(
                color = accent.copy(alpha = 0.12f),
                radius = radius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
            )
            if (progress > 0f) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round),
                    topLeft = Offset(
                        center.x - radius,
                        center.y - radius,
                    ),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.92f), accent),
                    ),
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            TintedIcon(AppIcons.Mic, onAccent, 46.dp)
        }
    }
}

@Composable
private fun RecognizedCard(song: Song, onPlay: () -> Unit, onRetry: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainer)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(
                url = song.cover(240),
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = song.singer,
                    fontSize = 12.sp,
                    color = colors.onSurfaceSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(13.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.primary)
                    .clickable(onClick = onPlay),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                TintedIcon(AppIcons.Play, colors.onPrimary, 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "播放",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onPrimary,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.primary.copy(alpha = 0.10f))
                    .clickable(onClick = onRetry),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "再听一次",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                )
            }
        }
    }
}
