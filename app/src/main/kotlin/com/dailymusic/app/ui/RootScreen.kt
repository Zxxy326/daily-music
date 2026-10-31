package com.dailymusic.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.FavoriteCloud
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.PlaylistBrief
import com.dailymusic.app.data.Profile
import com.dailymusic.app.data.Rank
import com.dailymusic.app.data.Song
import com.dailymusic.app.data.YouthVip
import com.dailymusic.app.playback.PlayUrlStore
import com.dailymusic.app.playback.PlayerManager
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

private data class Tab(val label: String, val icon: ImageVector)

// 搜索和音乐库不再是底栏 tab：
// 搜索从首页的搜索框进（改成二级页），音乐库从「我的 → 收藏/历史」进。
// 底栏中间让给播放条。
private val Tabs = listOf(
    Tab("首页", AppIcons.Home),
    Tab("我的", AppIcons.Person),
)

/**
 * 首页四宫格进来的二级页。
 *
 * 单独抽一个 sealed 状态，而不是每页各加一个 Boolean —— 四个入口互斥，
 * 用四个布尔会出现「两个页面同时为真」的非法组合。
 */
private sealed interface SubPage {
    data object Daily : SubPage
    data object RankList : SubPage
    data object PlaylistSquare : SubPage
    data object Recognize : SubPage

    /** 搜索。以前是底栏 tab，现在从首页搜索框进，所以变成二级页。 */
    data object Search : SubPage
    /**
     * 歌单详情。[backTo] 是「返回后回到哪」—— 从歌单广场进来要回广场，
     * 从首页推荐位进来要回首页。写死一个返回值会让其中一条路径跑错地方。
     */
    data class PlaylistDetail(val playlist: PlaylistBrief, val backTo: SubPage? = null) : SubPage
}

/**
 * 应用主骨架。
 *
 * 关键结构：内容层包在 `layerBackdrop(backdrop)` 里 → 它的绘制结果被录进一个
 * GraphicsLayer；悬浮底栏（或侧边栏）和迷你播放器是**兄弟节点**，用同一个 backdrop
 * 做 `liquidGlass`，于是能真实地模糊「从它们背后流过去」的列表内容。
 *
 * 如果把它们放进同一个 Column / 同一个 padding 容器里，就什么都模糊不到了 ——
 * 因为那时玻璃背后是空白背景而不是列表。
 *
 * 布局随宽度切换：Compact 用悬浮底栏，Medium/Expanded 换成左侧玻璃导航栏。
 */
@Composable
fun RootScreen() {
    val context = LocalContext.current
    val backdrop = rememberLayerBackdrop()
    val wc = windowClass()
    val wide = wc != WindowClass.Compact
    val railWidth = if (wc == WindowClass.Expanded) 104.dp else 88.dp

    // 用 rememberSaveable 而不是 remember：Activity 一旦重建（旋转、系统回收、
    // 后台被清），remember 会归零，表现是「正扫码/输验证码，页面自己弹回我的」。
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var playerOpen by remember { mutableStateOf(false) }
    var lyricsOpen by remember { mutableStateOf(false) }

    /** 「我的」tab 里的二级页面：-1 = 首页，0 = 收藏，1 = 历史，2 = 登录，3 = 每日畅听。 */
    var minePage by rememberSaveable { mutableIntStateOf(-1) }

    /** 当前打开的歌单（榜单）。null = 没打开。 */
    var openRank by remember { mutableStateOf<Rank?>(null) }

    /** 首页四宫格打开的二级页。 */
    var subPage by remember { mutableStateOf<SubPage?>(null) }

    // 提前连上播放服务，把「服务冷启动 + 建连」的时间从第一次点歌里省掉。
    // 注意不要在这里 release：控制器要一直活着，退到后台才能继续播。
    LaunchedEffect(Unit) { PlayerManager.connect(context) }

    // 冷启动自动签到。概念版的权益按天计，漏一天就少一天畅听；
    // 放在这里而不是 Application 里，是因为它要等本地服务起来才有端口可用。
    LaunchedEffect(Unit) {
        if (Auth.isLoggedIn) YouthVip.autoSignInIfNeeded()
    }

    // 收藏与账号「我喜欢」对齐。
    //
    // 用 `Auth.isLoggedIn` 当 key 而不是 Unit：登录是在本进程内完成的（扫码/短信），
    // Activity 不会重建，Unit 那次早就跑完了 —— 结果就是「登录了但收藏没同步」。
    // 退出登录时把缓存清掉，否则换账号会往别人的「我喜欢」里写歌。
    LaunchedEffect(Auth.isLoggedIn) {
        if (Auth.isLoggedIn) {
            Library.syncFromCloud()
        } else {
            FavoriteCloud.clearCache()
        }
    }

    // 返回键的优先级：先收播放页 → 再关歌单 → 再退二级页 → 都没有才交还系统。
    // 榜单详情排在二级页之前，是因为「排行榜 → 某个榜」这条路径上，返回应该先回榜目，
    // 而不是一路跳回首页。
    BackHandler(
        enabled = lyricsOpen || playerOpen || openRank != null || subPage != null || minePage >= 0,
    ) {
        when {
            lyricsOpen -> lyricsOpen = false
            playerOpen -> playerOpen = false
            openRank != null -> openRank = null
            subPage != null -> subPage = null
            else -> minePage = -1
        }
    }

    val current = PlayerManager.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 列表底部要留出「底栏/迷你播放器」的高度，否则最后几首永远被挡住。
    // 数值按视觉稿拆开算：底栏 64 + 距底 22 + 间距 14 + 迷你条 62 = 162。
    // 没有正在播放的歌时，迷你条那一段（62 + 14）不需要留。
    // 末尾再补 12dp 呼吸 —— 正好等于底栏高度时，最后一行的图标会被圆角切掉半截。
    // 窄屏：播放条已经并进底栏，只需要给「底栏 64 + 距底 22」留位置，不再额外加 76。
    // 宽屏：侧栏 + 独立悬浮迷你条，仍然要留那 76。
    val bottomSpace = if (wide) {
        navBottom + 24.dp + if (current != null) 76.dp else 0.dp
    } else {
        navBottom + 98.dp
    }
    val contentPadding = PaddingValues(bottom = bottomSpace)

    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        // 注意顺序：layerBackdrop 必须在 background 之前。
        // LayerBackdropNode 的 draw() 是「先 drawContent() 再录制」，
        // 排在它后面的 background 属于它的内层内容，才会被录进去；
        // 排在它前面（更外层）的背景不在录制范围内。
        // 录进去的画面必须是不透明的，否则模糊会把透明区当黑色 → 玻璃上出现深色色块。
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MiuixTheme.colorScheme.background),
        ) {
            // 一层极淡的环境色，让玻璃有东西可模糊 —— 纯色背景上的模糊等于没模糊。
            AmbientWash()

            // 宽屏时内容整体右移，给左侧导航让位；这一层本身仍然铺满全屏，
            // 否则玻璃会采样到「录制范围之外」的透明区。
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = if (wide) railWidth + 16.dp else 0.dp),
            ) {
                val rank = openRank
                val sub = subPage
                when {
                    sub is SubPage.PlaylistDetail -> PlaylistDetailScreen(
                        playlist = sub.playlist,
                        contentPadding = contentPadding,
                        onBack = { subPage = sub.backTo },
                        onPlay = { song, list -> PlayerManager.play(context, song, list) },
                    )

                    sub != null -> when (sub) {
                        SubPage.Daily -> DailyScreen(
                            contentPadding = contentPadding,
                            onBack = { subPage = null },
                            onPlay = { song, list -> PlayerManager.play(context, song, list) },
                        )

                        SubPage.RankList -> RankListScreen(
                            contentPadding = contentPadding,
                            onBack = { subPage = null },
                            onOpenRank = { openRank = it },
                        )

                        SubPage.PlaylistSquare -> PlaylistSquareScreen(
                            contentPadding = contentPadding,
                            onBack = { subPage = null },
                            onOpenPlaylist = {
                                subPage = SubPage.PlaylistDetail(it, SubPage.PlaylistSquare)
                            },
                        )

                        SubPage.Recognize -> RecognizeScreen(
                            contentPadding = contentPadding,
                            onBack = { subPage = null },
                            onPlay = { song ->
                                PlayerManager.play(context, song, listOf(song))
                                playerOpen = true
                            },
                        )

                        SubPage.Search -> SearchScreen(
                            contentPadding = contentPadding,
                            onBack = { subPage = null },
                            onPlay = { song, list -> PlayerManager.play(context, song, list) },
                        )
                    }

                    rank != null -> PlaylistScreen(
                        rank = rank,
                        contentPadding = contentPadding,
                        onBack = { openRank = null },
                        onPlay = { song, list -> PlayerManager.play(context, song, list) },
                    )

                    tab == 0 -> HomeScreen(
                        contentPadding = contentPadding,
                        onOpenSearch = { subPage = SubPage.Search },
                        onOpenRank = { openRank = it },
                        onPlay = { song, list -> PlayerManager.play(context, song, list) },
                        onOpenDaily = { subPage = SubPage.Daily },
                        onOpenRankList = { subPage = SubPage.RankList },
                        onOpenPlaylistSquare = { subPage = SubPage.PlaylistSquare },
                        onOpenRecognize = { subPage = SubPage.Recognize },
                        onOpenPlaylist = { subPage = SubPage.PlaylistDetail(it) },
                    )

                    else -> when (minePage) {
                        -1 -> MineScreen(
                            contentPadding = contentPadding,
                            onQualityChange = { PlayerManager.setQuality(context, it) },
                            // 底栏现在只有 0=首页 / 1=我的，写 tab=2 会落到 else 分支
                            // 又把「我的」渲染一遍 —— 表现就是点了没反应。
                            onOpenLibrary = { minePage = it },
                            onOpenLogin = { minePage = 2 },
                            onOpenSignIn = { minePage = 3 },
                            onLogout = {
                                Auth.clear()
                                // 签到状态和账号资料都属于账号，换人要清掉，
                                // 否则新账号会看到别人的打卡记录和头像。
                                YouthVip.clear()
                                Profile.clear()
                                PlayUrlStore.invalidate()
                            },
                        )

                        2 -> LoginScreen(
                            onBack = { minePage = -1 },
                            onLoggedIn = { minePage = -1 },
                        )

                        3 -> SignInScreen(onBack = { minePage = -1 })

                        else -> LibraryScreen(
                            contentPadding = contentPadding,
                            initialTab = minePage,
                            onBack = { minePage = -1 },
                            onPlay = { song, list -> PlayerManager.play(context, song, list) },
                        )
                    }
                }
            }
        }

        if (wide) {
            GlassRail(
                backdrop = backdrop,
                selected = tab,
                width = railWidth,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp, top = 20.dp, bottom = 20.dp),
                onSelect = {
                    tab = it
                    minePage = -1
                },
            )
            current?.let { song ->
                MiniPlayer(
                    backdrop = backdrop,
                    song = song,
                    isPlaying = PlayerManager.isPlaying,
                    onOpen = { playerOpen = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            start = railWidth + 16.dp,
                            end = 16.dp,
                            bottom = navBottom + 12.dp,
                        ),
                )
            }
        } else {
            // 底栏是悬浮的玻璃胶囊，它下面还留着 22dp + 手势区的一截。
            // 列表滚到中途时，这一截会漏出半行内容，看着像「被裁断的列表行」。
            //
            // 渐变要算准位置：胶囊底边在「距底 navBottom + 22」处，所以从距底 86dp
            // 往下开始由透明渐变为页面底色 —— 渐变的过渡段整段压在胶囊背后看不见，
            // 露出来的那截缝隙已经是纯底色。只做「整块透明→底色」会让最靠近胶囊的
            // 那几行反而最清楚（渐变顶端最透），等于没盖。
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(navBottom + 88.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.46f to MiuixTheme.colorScheme.background,
                            1f to MiuixTheme.colorScheme.background,
                        ),
                    ),
            )
            // 播放条并进底栏本身：底栏一颗胶囊里是「首页 · 播放条 · 我的」。
            // 以前是「迷你条 + 底栏」两块玻璃上下摞着，占掉 140dp 还互相打架。
            //
            // 外面这层 Box 不能省：`align` 只有 BoxScope 里有，而 GlassNavBar 内部
            // 拿不到外层的 BoxScope。少了它底栏会跑到屏幕**顶部**去。
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                GlassNavBar(
                    backdrop = backdrop,
                    selected = tab,
                    onSelect = {
                        tab = it
                        minePage = -1
                    },
                    song = current,
                    onOpenPlayer = { playerOpen = true },
                )
            }
        }

        AnimatedVisibility(
            visible = playerOpen,
            enter = slideInVertically(animationSpec = tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(animationSpec = tween(280)) { it } + fadeOut(tween(180)),
        ) {
            PlayerScreen(
                onClose = { playerOpen = false },
                onOpenLyrics = { lyricsOpen = true },
            )
        }

        // 必须排在播放页**之后**：同一层 Box 里后声明的节点绘制在上层，
        // 顺序反过来会让歌词页被播放页整块盖住（点了封面像是没反应）。
        // 歌词页的进出改成「淡入 + 轻微放大」，不再横向滑入。
        // 它是从播放页展开的一层（顶部那个向下箭头就是「收回去」），
        // 横向滑动的方向感和这个语义对不上；Apple Music 也是交叉淡化。
        AnimatedVisibility(
            visible = lyricsOpen,
            enter = fadeIn(tween(260)) + scaleIn(initialScale = 1.05f, animationSpec = tween(320)),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 1.03f, animationSpec = tween(240)),
        ) {
            LyricsScreen(onClose = { lyricsOpen = false })
        }
    }
}

/**
 * 两团柔光。不用图片资源，纯 Canvas 画径向渐变，深色下稍微提亮一点。
 * 它的唯一职责是「给玻璃一点颜色可采样」，不参与任何布局。
 */
@Composable
private fun AmbientWash() {
    val colors = MiuixTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val alpha = if (dark) 0.18f else 0.11f
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(colors.primary.copy(alpha = alpha), Color.Transparent),
                center = Offset(size.width * 0.16f, size.height * 0.05f),
                radius = size.minDimension * 1.0f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(colors.primaryVariant.copy(alpha = alpha * 0.8f), Color.Transparent),
                center = Offset(size.width * 0.96f, size.height * 0.88f),
                radius = size.minDimension * 0.85f,
            ),
        )
    }
}

// 取值直接对齐视觉稿 .glass / body.dark .glass：
//   浅色 background rgba(255,255,255,.68)、深色 rgba(34,34,38,.60)。
// 染色再高一点会把模糊结果整块盖死 —— 白底上就退化成一块实心白板，看不出是玻璃。
@Composable
private fun glassTint(dark: Boolean): Color =
    if (dark) Color(0x99222226) else Color(0xADFFFFFF)

// 视觉稿 border：浅色 rgba(255,255,255,.66)、深色 rgba(255,255,255,.12)。
// 这圈描边是浅色下玻璃唯一能「立」起来的依据，缺了它白压白就糊成一片。
@Composable
private fun glassBorder(dark: Boolean): Color =
    if (dark) Color(0x1FFFFFFF) else Color(0xA8FFFFFF)

/**
 * 底栏中间的播放条。
 *
 * 只保留「圆形封面 + 一行歌词」：不铺底色（直接坐在玻璃上）、不放进度条、
 * 不放播放键 —— 底栏内高只有 52dp，多一个元素就开始互相挤。
 * 整条可点 → 打开播放页。
 *
 * 没歌在放的时候不能空着，也不能点（播放页没有当前曲时会渲染成一片空白），
 * 所以轮播「每日一言」。
 */
@Composable
private fun MiniBar(song: Song?, onOpen: () -> Unit) {
    val colors = MiuixTheme.colorScheme

    // 播放中取当前这句歌词；没歌词（纯音乐/还没加载完）退回歌名。
    val lyric = remember(song, PlayerManager.positionMs, PlayerManager.lyrics) {
        val lyrics = PlayerManager.lyrics
        val anchor = lyrics.anchorIndex(PlayerManager.positionMs)
        lyrics.lines.getOrNull(anchor)?.text?.takeIf { it.isNotBlank() } ?: song?.name.orEmpty()
    }

    // 空闲时轮播每日一言：每 4 秒换一句，顺序打乱，避免每次启动都是同一句开头。
    var quoteIndex by remember { mutableIntStateOf(0) }
    val quotes = remember { DailyQuotes.shuffled() }
    LaunchedEffect(song) {
        if (song != null) return@LaunchedEffect
        while (true) {
            delay(4_000)
            quoteIndex = (quoteIndex + 1) % quotes.size
        }
    }

    val text = if (song != null) lyric else quotes[quoteIndex]
    val hasSong = song != null

    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .then(if (hasSong) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (song != null) {
            RemoteImage(
                url = song.cover(120),
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
            )
            Spacer(Modifier.width(9.dp))
        }
        // 换行/换句都走淡入淡出：歌词一句一句跳变会显得很生硬。
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(180))
            },
            label = "mini-text",
        ) { line ->
            Text(
                text = line,
                modifier = Modifier.fillMaxWidth(),
                fontSize = if (hasSong) 12.sp else 11.5.sp,
                fontWeight = if (hasSong) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (hasSong) TextAlign.Start else TextAlign.Center,
                color = if (hasSong) colors.onSurface else colors.onSurfaceVariantSummary,
            )
        }
    }
}

/** 空闲时轮播的「每日一言」。本地常量，不发请求。 */
private val DailyQuotes = listOf(
    "耳机一戴，世界与我无关",
    "有些心情，只有某首歌懂",
    "单曲循环的，从来不是歌，是心事",
    "把音量调大，把烦恼调小",
    "深夜的歌，最适合一个人听",
    "下一首，也许就是今天的心情",
    "听歌的人，心里都住着一个故事",
    "慢慢来，好歌值得等",
    "旋律响起的瞬间，时间就慢了下来",
    "今天适合听点慢的",
    "音乐是唯一不需要翻译的语言",
    "一首歌的时间，刚好够放空",
)

@Composable
private fun GlassNavBar(
    backdrop: LayerBackdrop,
    selected: Int,
    onSelect: (Int) -> Unit,
    song: Song?,
    onOpenPlayer: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(32.dp)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 底栏是「动态」的：没歌时只包住两个图标（一颗小胶囊），有歌时横向撑开，
    // 把播放条挤进中间。宽度用 spring 过渡，所以按钮是被「推开」而不是瞬间跳位。
    val expanded = song != null
    val widthFraction by animateFloatAsState(
        targetValue = if (expanded) 0.67f else 0.26f,
        // stiffness 放软一点：260 大约 250ms 就到位，肉眼看只是「闪了一下」；
        // 180 + 0.75 阻尼能拖到 450ms 左右，还带一点回弹，才看得出是「撑开」。
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 180f),
        label = "bar-width",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = navBottom + 22.dp),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(widthFraction)
                .height(64.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.26f),
                    spotColor = Color.Black.copy(alpha = 0.26f),
                )
                .liquidGlass(
                    backdrop = backdrop,
                    shape = shape,
                    blurRadius = 18.dp,
                )
                .background(glassTint(dark), shape)
                .border(1.dp, glassBorder(dark), shape)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左：首页；中：播放条；右：我的。
            GlassTab(
                tab = Tabs[0],
                selected = selected == 0,
                onClick = { onSelect(0) },
                modifier = Modifier.width(46.dp),
            )
            // 播放条挤进中间：淡入 + 轻微放大，宽度由上面的 spring 负责。
            // 隐藏时 weight 仍占位，所以两个图标始终贴在胶囊两端，撑开的过程看起来
            // 就是「胶囊变长、按钮被推开」。
            AnimatedVisibility(
                visible = expanded,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 2.dp),
                enter = fadeIn(tween(260, delayMillis = 110)) +
                    scaleIn(initialScale = 0.88f, animationSpec = tween(320)),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.94f, animationSpec = tween(140)),
            ) {
                MiniBar(song, onOpenPlayer)
            }
            GlassTab(
                tab = Tabs[1],
                selected = selected == 1,
                onClick = { onSelect(1) },
                modifier = Modifier.width(46.dp),
            )
        }
    }
}

/** 宽屏的左侧导航。和底栏共用同一套玻璃与选中态，只是排列方向变成竖向。 */
@Composable
private fun GlassRail(
    backdrop: LayerBackdrop,
    selected: Int,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(34.dp)

    Column(
        modifier = modifier
            .width(width)
            // 不铺满高度：让它像一颗悬浮胶囊，而不是一整条白色侧板。
            .shadow(
                elevation = 14.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.26f),
                spotColor = Color.Black.copy(alpha = 0.26f),
            )
            .liquidGlass(backdrop = backdrop, shape = shape, blurRadius = 20.dp)
            .background(glassTint(dark), shape)
            .border(1.dp, glassBorder(dark), shape)
            .padding(vertical = 14.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Tabs.forEachIndexed { index, tab ->
            RailTab(
                tab = tab,
                selected = selected == index,
                onClick = { onSelect(index) },
            )
            if (index != Tabs.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RailTab(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val progress by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "railProgress")
    val tint = lerp(colors.onSurfaceSecondary, colors.primary, progress)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.primary.copy(alpha = 0.13f * progress))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TintedIcon(tab.icon, tint, 22.dp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = tab.label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}

@Composable
private fun GlassTab(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val progress by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "tabProgress")
    val tint = lerp(colors.onSurfaceSecondary, colors.primary, progress)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.primary.copy(alpha = 0.13f * progress))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 只留图标：底栏已经收窄，再带一行 10sp 的标签会把图标挤小。
        TintedIcon(tab.icon, tint, 22.dp)
    }
}

@Composable
private fun MiniPlayer(
    backdrop: LayerBackdrop,
    song: Song,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    // 视觉稿 .mini：left/right 16、bottom 100、height 62、radius 24。
    // 底栏 bottom 22 + height 64 = 顶边距底 86，100 - 86 = 14 就是两者的间距。
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 14.dp)
            .height(62.dp)
            .shadow(
                elevation = 14.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.14f),
                spotColor = Color.Black.copy(alpha = 0.14f),
            )
            .clip(shape)
            .liquidGlass(
                backdrop = backdrop,
                shape = shape,
                blurRadius = 26.dp,
            )
            .background(glassTint(dark), shape)
            .border(1.dp, glassBorder(dark), shape)
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                url = song.cover(120),
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = song.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = song.singer,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
            }
            // 视觉稿这里是普通图标按钮（20px、继承文字色），不是主题色圆钮 ——
            // 主题色圆钮会和底栏选中态抢焦点。40dp 触摸区保留，图标按稿缩到 20dp。
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { PlayerManager.toggle(context) },
                contentAlignment = Alignment.Center,
            ) {
                TintedIcon(
                    icon = if (isPlaying) AppIcons.Pause else AppIcons.Play,
                    tint = MiuixTheme.colorScheme.onSurface,
                    size = 20.dp,
                )
            }
        }

        // 底部进度条：视觉稿 .mini .prog —— left/right 12、bottom 6、height 2.5，
        // 轨道用 --pill（浅色 6% 黑 / 深色 9% 白），填充用主题色。
        val duration = PlayerManager.durationMs
        if (duration > 0L) {
            val fraction = (PlayerManager.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        if (dark) Color(0x17FFFFFF) else Color(0x0F000000),
                    ),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(MiuixTheme.colorScheme.primary),
                )
            }
        }
    }
}
