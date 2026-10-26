package com.dailymusic.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dailymusic.app.native.KugouServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.HttpURLConnection
import java.net.URL

/**
 * M1 地基验证页：确认 Miuix 主题、Rust 服务、本地 HTTP 三者都通了。
 * 后续里程碑会替换成真正的首页。
 */
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles

    var running by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("点下面的按钮启动内嵌服务") }

    Scaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(72.dp))

            Text("Daily Music", style = styles.title1)
            Spacer(Modifier.height(6.dp))
            Text(
                "M1 · 地基验证",
                style = styles.body2,
                color = colors.onBackgroundVariant,
            )

            Spacer(Modifier.height(28.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("内嵌酷狗服务", style = styles.title4)
                Text(
                    if (running) "运行中 · 127.0.0.1:$port" else "未启动",
                    style = styles.body1,
                    color = if (running) colors.primary else colors.onSurfaceSecondary,
                )
                Text(
                    message,
                    style = styles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
                KugouServer.lastError?.let {
                    Text(it, style = styles.footnote1, color = colors.error)
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    scope.launch {
                        message = "启动中…"
                        val p = withContext(Dispatchers.IO) { KugouServer.start(context) }
                        port = p
                        running = p > 0
                        message = if (p > 0) {
                            "HTTP 探测中…"
                        } else {
                            "启动失败，看上面的错误"
                        }
                        if (p > 0) {
                            message = withContext(Dispatchers.IO) { probe(p) }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("启动服务并测试连通性")
            }
        }
    }
}

/** 打一次本地 HTTP，拿到状态码就说明 Rust 服务真的在监听 */
private fun probe(port: Int): String {
    return try {
        val conn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "GET"
        }
        val code = conn.responseCode
        val body = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        "HTTP $code · ${body?.take(120)?.replace("\n", " ") ?: "空响应"}"
    } catch (t: Throwable) {
        "探测失败：${t.javaClass.simpleName}: ${t.message}"
    }
}
