package com.dailymusic.app.data

import android.util.Base64
import org.json.JSONObject

/**
 * KRC / LRC 歌词解析。
 *
 * KRC 明文长这样：
 *
 * ```
 * [id:$00000000]
 * [ti:你的酒馆对我打了烊]
 * [language:eyJjb250ZW50IjpbXSwidmVyc2lvbiI6MX0=]
 * [0,2510]<0,160,0>你<160,160,0>的<320,160,0>酒<480,160,0>馆...
 * ```
 *
 * 两个关键点（都踩过坑）：
 *
 * 1. `[行起始ms,行时长ms]` 后面跟一串 `<字相对偏移ms,字时长ms,0>字面`。
 *    逐字时间轴是**相对行首**的偏移，不是绝对时间。这里在解析时就换算成绝对时间，
 *    上层不用再关心偏移。
 *
 * 2. `[language:<base64>]` 是一个**单行标签**，不是「从这里开始后面都是翻译」的块。
 *    它出现在歌词行之前还是之后都有可能。base64 解开是：
 *    `{"content":[{"language":0,"lyricContent":[["整行翻译"],...]},
 *                 {"language":1,"lyricContent":[["yi","er","ta"],...]}]}`
 *    `language=0` 是中文翻译，`language=1` 是音译/罗马音；
 *    数组下标与歌词行一一对应，时间戳直接复用歌词行的。
 *
 *    早期实现把 `[language:]` 当成「进入翻译模式」，结果整首歌的歌词行全被当成翻译吞掉，
 *    表现就是「这首歌没有歌词」。
 */
data class Word(
    val text: String,
    val startMs: Long,
    val durationMs: Long,
)

data class LyricLine(
    val startMs: Long,
    val durationMs: Long,
    val words: List<Word>,
    val translation: String? = null,
    val romaji: String? = null,
) {
    val text: String get() = words.joinToString("") { it.text }
    val endMs: Long get() = startMs + durationMs
    val hasWordTiming: Boolean get() = words.size > 1
}

data class Lyrics(
    val lines: List<LyricLine>,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** 当前播放位置命中的行下标；无命中返回 -1。 */
    fun indexAt(positionMs: Long): Int {
        val found = anchorIndex(positionMs)
        if (found < 0) return -1
        // 行与行之间常有几秒空白（间奏）。超过 1.2s 就认为不在任何一行上，
        // 这样间奏时高亮自然消失，而不是死挂在上一句。
        return if (positionMs <= lines[found].endMs + 1_200L) found else -1
    }

    /** 最后一个起始时间 <= positionMs 的行（不管是否已超出该行时长），用于歌词窗口定位。 */
    fun anchorIndex(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        val Empty = Lyrics(emptyList())
    }
}

object LyricParser {

    private val KRC_LINE = Regex("""\[(\d+),(\d+)](.*)""")
    private val KRC_WORD = Regex("""<(\d+),(\d+),-?\d+>([^<]*)""")
    private val META = Regex("""\[([a-zA-Z]+):(.*)]""")
    private val LRC_LINE = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?](.*)""")
    private const val LANG_TAG = "[language:"

    /**
     * @param raw   /lyric 接口返回的 decodeContent
     * @param isKrc true = 逐字（fmt=krc），false = 普通 LRC
     */
    fun parse(raw: String, isKrc: Boolean = true): Lyrics {
        if (raw.isBlank()) return Lyrics.Empty

        val meta = HashMap<String, String>()
        val rawLines = ArrayList<LyricLine>()
        var languagePayload: String? = null

        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim('\r', '\uFEFF').trimEnd()
            if (line.isEmpty()) continue

            if (line.startsWith(LANG_TAG)) {
                val end = line.indexOf(']')
                if (end > LANG_TAG.length) {
                    languagePayload = line.substring(LANG_TAG.length, end)
                }
                continue
            }

            val metaMatch = META.matchEntire(line)
            if (metaMatch != null) {
                meta[metaMatch.groupValues[1].lowercase()] = metaMatch.groupValues[2]
                continue
            }

            val krc = KRC_LINE.matchEntire(line)
            if (krc != null) {
                val start = krc.groupValues[1].toLongOrNull() ?: continue
                val dur = krc.groupValues[2].toLongOrNull() ?: 0L
                val words = KRC_WORD.findAll(krc.groupValues[3])
                    .map { w ->
                        Word(
                            text = w.groupValues[3],
                            startMs = start + (w.groupValues[1].toLongOrNull() ?: 0L),
                            durationMs = w.groupValues[2].toLongOrNull() ?: 0L,
                        )
                    }
                    .filter { it.text.isNotEmpty() }
                    .toList()
                if (words.isEmpty()) continue
                rawLines += LyricLine(start, dur, words)
                continue
            }

            val lrc = LRC_LINE.matchEntire(line)
            if (lrc != null) {
                val min = lrc.groupValues[1].toLongOrNull() ?: continue
                val sec = lrc.groupValues[2].toLongOrNull() ?: 0L
                val frac = when (lrc.groupValues[3].length) {
                    0 -> 0L
                    1 -> (lrc.groupValues[3].toLongOrNull() ?: 0L) * 100
                    2 -> (lrc.groupValues[3].toLongOrNull() ?: 0L) * 10
                    else -> lrc.groupValues[3].take(3).toLongOrNull() ?: 0L
                }
                val text = lrc.groupValues[4]
                if (text.isBlank()) continue
                val start = min * 60_000 + sec * 1_000 + frac
                rawLines += LyricLine(start, 0L, listOf(Word(text, start, 0L)))
            }
        }

        if (rawLines.isEmpty()) return Lyrics.Empty

        val offsetMs = meta["offset"]?.trim()?.toLongOrNull() ?: 0L
        rawLines.sortBy { it.startMs }

        val (translations, romaji) = parseLanguage(languagePayload)

        val fixed = ArrayList<LyricLine>(rawLines.size)
        for (i in rawLines.indices) {
            val cur = rawLines[i]
            val duration = if (cur.durationMs > 0L) {
                cur.durationMs
            } else {
                val next = rawLines.getOrNull(i + 1)?.startMs ?: (cur.startMs + 4_000L)
                (next - cur.startMs).coerceIn(600L, 8_000L)
            }
            val shifted = if (offsetMs == 0L) cur else cur.copy(
                startMs = (cur.startMs + offsetMs).coerceAtLeast(0L),
                words = cur.words.map { it.copy(startMs = (it.startMs + offsetMs).coerceAtLeast(0L)) },
            )
            fixed += shifted.copy(
                durationMs = duration,
                translation = translations.getOrNull(i)?.takeIf { it.isNotBlank() },
                romaji = romaji.getOrNull(i)?.takeIf { it.isNotBlank() },
            )
        }

        return Lyrics(
            lines = dropLeadingCredits(fixed, meta["ti"].orEmpty(), meta["ar"].orEmpty()),
            title = meta["ti"].orEmpty(),
            artist = meta["ar"].orEmpty(),
            album = meta["al"].orEmpty(),
        )
    }

    /**
     * 酷狗歌词的开头常常是一整段「制作名单」，而不是歌词：
     *
     * ```
     * 指纹 - 杜宣达        ← 标题行
     * 作词：周剑光 / 作曲：郑宇界 / 原唱：胡歌 / 编曲：任斌
     * 和声：… / 出品：… / 音乐发行：… / 制作总监：…
     * 策略营销：武鹏 / 项目统筹：… / 企划营销：… / 项目协力：… / 封面设计：…
     * ```
     *
     * 实测这首歌前面有 14 行这样的内容。不裁掉的话，进歌词页第一屏全是人名，
     * 用户得往上翻才看得到歌词。
     *
     * 两个坑：
     * 1. **第一行是标题行**（`歌名 - 歌手`），它不是名单，会让「连续裁剪」在第一步就断掉，
     *    所以标题行也要算进可跳过集合；
     * 2. 不能设死行数上限（14 行已经超过常见上限），改用「裁完至少还剩 4 行」兜底 ——
     *    万一整首都是 `XX：YY` 形式的歌词，宁可脏一点也不要变成空歌词。
     */
    private fun dropLeadingCredits(
        lines: List<LyricLine>,
        title: String,
        artist: String,
    ): List<LyricLine> {
        var from = 0
        while (from < lines.size) {
            val text = lines[from].text
            // 空行也要一起吃掉：KRC 经常以一条 `[0,0]` 的空行开头，
            // 不跳过的话第一行就是「不是署名」→ 循环立刻 break，后面整片署名全留下。
            if (text.isBlank()) {
                from++
                continue
            }
            if (!isCreditLine(text) && !isTitleLine(text, title, artist) && !isBareTitleLine(text)) break
            from++
        }
        if (from == 0 || lines.size - from < 4) return lines
        return lines.subList(from, lines.size)
    }

    /**
     * 没有 `[ti:]` / `[ar:]` 的歌词（酷狗不少 KRC 就是这种）认不出标题行，
     * 「歌名 - 歌手」留在第一行会让后面的连续裁剪在第一步就断掉，整段名单全留下。
     * 这里按结构兜底：只有一个「-」分隔、两边都短，就是标题行。
     */
    private fun isBareTitleLine(text: String): Boolean {
        val t = text.trim()
        if (t.length > 40) return false
        val parts = t.split(" - ", " -", "- ").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.size == 2 && parts.all { it.length in 1..24 }
    }

    /** 「歌名 - 歌手」「歌名 - 歌手 - 专辑」，以及单独的纯歌名/纯歌手行。 */
    private fun isTitleLine(text: String, title: String, artist: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 60) return false
        if (title.isNotBlank() && t.equals(title, ignoreCase = true)) return true
        if (artist.isNotBlank() && t.equals(artist, ignoreCase = true)) return true
        val parts = t.split('-', '－', '—', '–').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return false
        val hitTitle = title.isNotBlank() && parts.any { it.equals(title, ignoreCase = true) }
        val hitArtist = artist.isNotBlank() && parts.any { it.equals(artist, ignoreCase = true) }
        return hitTitle || hitArtist
    }

    /** 常见制作角色。歌词正文几乎不会以这些词 + 冒号开头。 */
    private val CREDIT_LABELS = setOf(
        "作词", "作曲", "编曲", "填词", "谱曲", "制作", "制作人", "监制", "出品", "出品人",
        "发行", "录音", "混音", "母带", "和声", "合声", "人声", "配唱", "吉他", "贝斯",
        "鼓", "键盘", "弦乐", "统筹", "企划", "策划", "营销", "宣传", "文案", "设计",
        "协力", "美术", "摄影", "导演", "剪辑", "后期", "总监", "经理", "助理", "版权",
        "授权", "演唱", "原唱", "翻唱", "词", "曲", "编", "op", "sp",
    )

    private fun isCreditLine(text: String): Boolean {
        val idx = text.indexOfFirst { it == '：' || it == ':' }
        // 标签过长就不是「角色：人名」这种结构了，多半是正文。
        // 上限给到 16：中英混写的署名很常见（「制作人Producer」11 个字），
        // 卡在 10 会让整段名单因为第一行匹配不上而全部留下。
        if (idx <= 0 || idx > 16) return false
        val label = text.substring(0, idx).trim().lowercase()
        if (label.isEmpty()) return false
        if (label in CREDIT_LABELS) return true
        // 「项目统筹」「企划营销」「母带工程师」这类带前缀/后缀的写法。
        // 必须用 contains 而不是 endsWith —— 「母带工程师」以「工程师」结尾，
        // 用 endsWith 匹配不到「母带」，连续裁剪就会卡在这里断掉。
        // 单字标签（词/曲/编）用 contains 会误伤正文（「这首歌的词：…」），所以只对多字标签做包含匹配。
        return CREDIT_LABELS.any { it.length >= 2 && label.contains(it) }
    }

    /** 解 `[language:]` 里的 base64 JSON，返回 (翻译行, 罗马音行)，下标与歌词行对齐。 */
    private fun parseLanguage(payload: String?): Pair<List<String>, List<String>> {
        if (payload.isNullOrBlank()) return emptyList<String>() to emptyList()
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val json = runCatching {
            val bytes = Base64.decode(padded, Base64.DEFAULT)
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrNull() ?: return emptyList<String>() to emptyList()

        val content = json.optJSONArray("content") ?: return emptyList<String>() to emptyList()
        var translation: List<String> = emptyList()
        var romaji: List<String> = emptyList()

        for (i in 0 until content.length()) {
            val entry = content.optJSONObject(i) ?: continue
            val lang = entry.optInt("language", -1)
            val rows = entry.optJSONArray("lyricContent") ?: continue
            val lines = (0 until rows.length()).map { idx ->
                val row = rows.optJSONArray(idx) ?: return@map ""
                // 翻译是单元素数组，罗马音是多音节数组 —— 都用空格拼接
                (0 until row.length()).joinToString(" ") { row.optString(it) }.trim()
            }
            when (lang) {
                0 -> translation = lines
                1 -> romaji = lines
            }
        }
        return translation to romaji
    }
}
