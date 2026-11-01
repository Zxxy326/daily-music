package com.dailymusic.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 实时频谱。
 *
 * 为什么不走 `android.media.audiofx.Visualizer`：那东西要 RECORD_AUDIO 运行时授权，
 * 一个播放器为了画个波纹去弹录音权限，用户第一反应是关掉。
 *
 * 这里直接在 ExoPlayer 的音频链路里插一个 [AudioProcessor]：数据本来就以 PCM 流过，
 * 顺手做一次 FFT 就行，零权限、零额外解码。
 *
 * 三档能量分别喂给 WAVE 动效：
 * - `bass` 20–250Hz（底鼓 / 贝斯）→ 出波快慢 + 整体亮度
 * - `mid`  250–2000Hz（人声）      → 波带宽度
 * - `high` 2000–8000Hz（镲片）     → 环与环的明暗差
 *
 * 注意：**必须关掉音频直通（offload）**，否则 MP3/AAC 会绕过整条 AudioProcessor 链，
 * 表现是「有声音，但这里一次都收不到数据」。见 PlaybackService 里的
 * `setAudioOffloadSupportProvider`。
 */
object SpectrumTap {

    @Volatile
    var bass: Float = 0f
        private set

    @Volatile
    var mid: Float = 0f
        private set

    @Volatile
    var high: Float = 0f
        private set

    private const val FFT_SIZE = 1024

    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) {
        (0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1))).toFloat()
    }
    private val mono = FloatArray(FFT_SIZE)

    /** 每档自己的「最近峰值」，见 [mapBand]。 */
    private val refDb = floatArrayOf(-40f, -40f, -40f)

    private var fill = 0
    private var sampleRate = 44_100
    private var channels = 2

    /** 暂停 / 切歌时清干净，免得波纹停在最后一帧不动。 */
    fun reset() {
        fill = 0
        bass = 0f
        mid = 0f
        high = 0f
        refDb.fill(-40f)
    }

    /**
     * 从音频链路里喂一段 PCM。**只读**，不动 buffer 的 position。
     *
     * 调用方在播放线程上，所以这里不能分配对象、不能做重活：
     * 1024 点 FFT 大约几万次浮点运算，够快。
     */
    fun feed(buffer: ByteBuffer, format: AudioProcessor.AudioFormat) {
        val ch = format.channelCount.coerceAtLeast(1)
        if (format.sampleRate > 0) sampleRate = format.sampleRate
        channels = ch

        val dup = buffer.duplicate().order(ByteOrder.nativeOrder())
        when (format.encoding) {
            C.ENCODING_PCM_16BIT -> {
                val shorts = dup.asShortBuffer()
                val frames = shorts.remaining() / ch
                for (f in 0 until frames) {
                    var acc = 0
                    val base = f * ch
                    for (c in 0 until ch) acc += shorts.get(base + c).toInt()
                    push(acc / (ch * 32_768f))
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val floats = dup.asFloatBuffer()
                val frames = floats.remaining() / ch
                for (f in 0 until frames) {
                    var acc = 0f
                    val base = f * ch
                    for (c in 0 until ch) acc += floats.get(base + c)
                    push(acc / ch)
                }
            }
        }
    }

    private fun push(sample: Float) {
        mono[fill++] = sample
        if (fill == FFT_SIZE) {
            fill = 0
            analyse()
        }
    }

    private fun analyse() {
        for (i in 0 until FFT_SIZE) {
            re[i] = mono[i] * window[i]
            im[i] = 0f
        }
        fft(re, im)

        val binHz = sampleRate.toFloat() / FFT_SIZE
        bass = smooth(bass, mapBand(0, bandDb(20f, 250f, binHz)))
        mid = smooth(mid, mapBand(1, bandDb(250f, 2_000f, binHz)))
        high = smooth(high, mapBand(2, bandDb(2_000f, 8_000f, binHz)))
    }

    /**
     * 频段能量，取**峰值 bin** 而不是平均。
     *
     * 平均会让宽频段（高频 140 个 bin）天然吃亏、窄频段（低频 5 个 bin）天然占便宜，
     * 同一首歌里三档永远对不上；取峰值只跟「这一段里最响的成分」有关。
     */
    private fun bandDb(lo: Float, hi: Float, binHz: Float): Float {
        val from = (lo / binHz).toInt().coerceAtLeast(1)
        val to = (hi / binHz).toInt().coerceAtMost(FFT_SIZE / 2 - 1)
        if (to < from) return -100f
        var peak = 0f
        for (i in from..to) {
            val mag = sqrt(re[i] * re[i] + im[i] * im[i])
            if (mag > peak) peak = mag
        }
        return 20f * log10(peak + 1e-7f)
    }

    /**
     * dB → 0..1，用**自适应参考峰值**而不是写死的分贝区间。
     *
     * 不同歌的响度差十几 dB（录音电平、母带、有没有做响度战争），
     * 写死区间要么全程贴 1、要么全程贴 0。这里给每一档维护一个「最近的峰值」：
     * 峰值快速跟上、每秒回退约 2dB，再把「峰值以下 24dB」映射到 0..1 ——
     * 于是每首歌都能把这三档用满，安静段落也照样有起伏。
     */
    private fun mapBand(index: Int, db: Float): Float {
        val ref = maxOf(db, refDb[index] - 0.045f)
        refDb[index] = ref
        return ((db - (ref - 24f)) / 24f).coerceIn(0f, 1f)
    }

    /** 起得快、落得慢，波纹才跟得上鼓点又不会一闪一闪。 */
    private fun smooth(prev: Float, next: Float): Float =
        prev + (next - prev) * (if (next > prev) 0.55f else 0.14f)

    /** 迭代版 Cooley-Tukey，原地计算。 */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            val half = len shr 1
            while (i < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until half) {
                    val ur = real[i + k]
                    val ui = imag[i + k]
                    val vr = real[i + k + half] * curR - imag[i + k + half] * curI
                    val vi = real[i + k + half] * curI + imag[i + k + half] * curR
                    real[i + k] = ur + vr
                    imag[i + k] = ui + vi
                    real[i + k + half] = ur - vr
                    imag[i + k + half] = ui - vi
                    val nr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * 直通式 AudioProcessor：把 PCM 抄一份给 [SpectrumTap] 分析，然后原样输出。
 *
 * 继承 [BaseAudioProcessor] 而不是自己实现接口，是因为 configure / flush / reset
 * 这些生命周期它已经处理好了，这里只需要补 `queueInput`。
 */
class SpectrumProcessor : BaseAudioProcessor() {

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return

        runCatching { SpectrumTap.feed(inputBuffer, inputAudioFormat) }

        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }
}
