package com.dailymusic.app.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 听歌识曲的录音 + PCM 前处理。
 *
 * 上游要的是 **8kHz / 16bit / 单声道 / 裸 PCM**，不是 WAV。手机麦能直接采到的最低
 * 常用采样率是 16kHz（8kHz 在部分机型上初始化失败），所以按 16k 采、再降一半。
 *
 * 三个细节都不能省，少一个识别率就掉到几乎为零：
 * 1. **静音检测**：最大振幅低于 [SILENCE_THRESHOLD] 直接跳过，不要拿一段空气去请求。
 * 2. **去直流 + 增益归一化**：手机麦克风原始信号振幅普遍偏小（正常放歌才 100~200），
 *    不放大到 [TARGET_AMPLITUDE] 指纹库匹配不上。
 * 3. **抗混叠**：降采样时取窗口均值而不是直接抽点，直接抽点会把高频折回低频。
 */
object AudioMatch {

    /** 采集采样率。 */
    const val RECORD_HZ = 16_000

    /** 上传采样率（上游指纹库固定 8k）。 */
    const val TARGET_HZ = 8_000

    /** 每轮录多久。上游建议 8s，太短命中率差、太长用户等得烦。 */
    const val DURATION_MS = 8_000

    /** 判静音的振幅阈值。 */
    const val SILENCE_THRESHOLD = 30

    /** 归一化目标振幅。 */
    private const val TARGET_AMPLITUDE = 25_000

    /** 识别结果。四种状态分开，UI 才能给出不同的提示。 */
    sealed interface Result {
        data class Found(val song: Song) : Result

        /** 录到的是静音（环境太安静 / 麦克风被挡）。 */
        data object Silent : Result

        /** 请求成功但没有匹配到曲目。 */
        data object NotFound : Result

        data class Failed(val message: String) : Result
    }

    /**
     * 录音 [durationMs] 毫秒，回调进度 0..1。
     *
     * 用 `AudioSource.MIC` 而不是 `VOICE_RECOGNITION`：后者会开 AGC/降噪，
     * 把音乐的频谱改得面目全非，指纹反而更难匹配。
     */
    @SuppressLint("MissingPermission")
    suspend fun record(durationMs: Int = DURATION_MS, onProgress: (Float) -> Unit = {}): ByteArray? =
        withContext(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                RECORD_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return@withContext null

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RECORD_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4,
                )
            } catch (_: Throwable) {
                null
            } ?: return@withContext null

            val total = RECORD_HZ * 2 * durationMs / 1000
            val out = ByteArray(total)
            var offset = 0
            val chunk = ByteArray(minBuf)

            try {
                if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext null
                record.startRecording()
                while (offset < total && isActive) {
                    val n = record.read(chunk, 0, minOf(chunk.size, total - offset))
                    if (n <= 0) break
                    System.arraycopy(chunk, 0, out, offset, n)
                    offset += n
                    onProgress(offset.toFloat() / total)
                }
            } catch (_: Throwable) {
                return@withContext null
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
            if (offset < total / 2) return@withContext null
            if (offset == total) out else out.copyOf(offset)
        }

    /** 16k → 8k。窗口均值抗混叠。 */
    fun downsample(input: ByteArray, ratio: Int = RECORD_HZ / TARGET_HZ): ByteArray {
        if (ratio <= 1) return input
        val inSamples = input.size / 2
        val outSamples = inSamples / ratio
        val out = ByteArray(outSamples * 2)
        val half = ratio / 2
        for (i in 0 until outSamples) {
            val center = i * ratio
            var sum = 0
            var count = 0
            for (j in -half..half) {
                val idx = center + j
                if (idx in 0 until inSamples) {
                    sum += sampleAt(input, idx)
                    count++
                }
            }
            writeSample(out, i, if (count > 0) sum / count else 0)
        }
        return out
    }

    /** 最大振幅（16bit 小端）。 */
    fun maxAmplitude(pcm: ByteArray): Int {
        var max = 0
        var i = 0
        while (i < pcm.size - 1) {
            val v = kotlin.math.abs(sampleAt(pcm, i / 2))
            if (v > max) max = v
            i += 2
        }
        return max
    }

    /**
     * 去直流 + 放大到 [target]。
     *
     * 增益只放大不缩小：本来就够响的录音再放大只会削顶，反而更难匹配。
     */
    fun normalize(pcm: ByteArray, target: Int = TARGET_AMPLITUDE): ByteArray {
        if (pcm.size < 2) return pcm
        val count = pcm.size / 2
        var sum = 0L
        for (i in 0 until count) sum += sampleAt(pcm, i)
        val dc = (sum / count).toInt()

        var peak = 0
        for (i in 0 until count) {
            val v = kotlin.math.abs(sampleAt(pcm, i) - dc)
            if (v > peak) peak = v
        }
        if (peak < 1) return pcm

        val gain = target.toFloat() / peak
        if (gain <= 1f) {
            // 已经够响：仍然去掉直流，直流偏置会让指纹的过零点全偏。
            val out = ByteArray(pcm.size)
            for (i in 0 until count) writeSample(out, i, sampleAt(pcm, i) - dc)
            return out
        }
        val out = ByteArray(pcm.size)
        for (i in 0 until count) {
            val v = ((sampleAt(pcm, i) - dc) * gain).toInt().coerceIn(-32768, 32767)
            writeSample(out, i, v)
        }
        return out
    }

    /** 完整链路：录音 → 降采样 → 静音判断 → 归一化 → 请求。 */
    suspend fun recognize(onProgress: (Float) -> Unit = {}): Result {
        val raw = record(onProgress = onProgress) ?: return Result.Failed("麦克风打不开，检查是否被别的应用占用")
        val pcm = downsample(raw)
        val peak = maxAmplitude(pcm)
        if (peak < SILENCE_THRESHOLD) return Result.Silent
        val ready = normalize(pcm)
        val json = KugouApi.matchAudio(ready) ?: return Result.Failed("识别请求失败，稍后再试")
        return parse(json)
    }

    /** 从 `/audio/match` 的响应里挖出第一首。 */
    private suspend fun parse(json: JSONObject): Result {
        val data = json.opt("data")
        val first = when (data) {
            is JSONObject -> data
            is org.json.JSONArray -> data.optJSONObject(0)
            else -> null
        } ?: return Result.NotFound

        Song.fromMatch(first)?.let { return Result.Found(it) }

        // 识别结果常常只给歌名 + 歌手，没有 hash。用「歌手 歌名」回搜一次拿 hash。
        val name = first.str("songname").ifBlank { first.str("song_name") }.ifBlank { first.str("name") }
        if (name.isBlank()) return Result.NotFound
        val singer = first.str("singername").ifBlank { first.str("author_name") }
        val query = if (singer.isNotBlank()) "$singer $name" else name
        val hits = runCatching { KugouApi.search(query, pageSize = 5) }.getOrDefault(emptyList())
        if (hits.isEmpty()) return Result.NotFound
        val wanted = first.long("album_audio_id").takeIf { it > 0 }
        return Result.Found(hits.firstOrNull { it.albumAudioId == wanted } ?: hits.first())
    }

    private fun sampleAt(pcm: ByteArray, index: Int): Int {
        val lo = pcm[index * 2].toInt() and 0xFF
        val hi = pcm[index * 2 + 1].toInt()
        return (hi shl 8) or lo
    }

    private fun writeSample(out: ByteArray, index: Int, value: Int) {
        val v = value.coerceIn(-32768, 32767)
        out[index * 2] = (v and 0xFF).toByte()
        out[index * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
