package com.hebatt.bG

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hebatt.bG.ui.theme.BartTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToLong
import android.graphics.Canvas as NativeCanvas
import androidx.compose.foundation.layout.Box

// ============ Tema aplikasi (FIXED) ============
private val APP_BG = Color.Black
private val APP_FONT = Color(0xFF8ACE00)

// ============ Konstanta ============
private const val LYRIC_OFFSET_MS = 150L      // lirik muncul sedikit lebih awal (kalibrasi)
private const val DEFAULT_HOLD_MS = 1500L     // lirik tetap tampil setelah kata terakhir
private const val MAX_WORDS_PER_LINE = 6      // maksimal kata per baris lirik
private const val MIN_CUT_MS = 1000L          // minimal durasi hasil potongan
private const val EXPORT_FPS = 30

// ============ Warna video (customizable) ============
private val COLOR_PALETTE = listOf(
    Color(0xFF8ACE00), Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFFF1744),
    Color(0xFFFF4081), Color(0xFF00E5FF), Color(0xFFFFEA00), Color(0xFF651FFF),
)
private val DEFAULT_VIDEO_BG = Color(0xFF8ACE00)
private val DEFAULT_VIDEO_FONT = Color.Black

private data class BratPreset(val label: String, val bg: Color, val font: Color)

private val PRESETS = listOf(
    BratPreset("BRAT", Color(0xFF8ACE00), Color.Black),
    BratPreset("DARK", Color(0xFF000000), Color(0xFF8ACE00)),
    BratPreset("NEON", Color(0xFF000000), Color(0xFF00E5FF)),
    BratPreset("LIGHT", Color(0xFFFFFFFF), Color.Black),
)

private val ColorSaver = Saver<Color, Int>(
    save = { it.toArgb() },
    restore = { Color(it) }
)

// ============ Rasio video ============
// Resolusi dipilih kelipatan 16 supaya aman di encoder H.264
private enum class AspectOption(val label: String, val w: Int, val h: Int) {
    R_1_1("1:1", 1024, 1024),
    R_16_9("16:9", 1280, 720),
    R_9_16("9:16", 720, 1280),
    R_4_5("4:5", 832, 1040),
    R_3_4("3:4", 768, 1024),
    R_4_3("4:3", 1024, 768);

    val ratio: Float get() = w.toFloat() / h
}

// ============ Gaya reveal teks ============
private enum class RevealStyle(val label: String, val desc: String) {
    SMOOTH("SMOOTH", "Kata muncul dengan fade halus"),
    BLINK("BLINK", "Kata langsung muncul tanpa transisi"),
    BRAT("BRAT", "Kata muncul satu per satu, semua kata otomatis menyesuaikan ukuran & posisi")
}

// ============ Warna kunci untuk background "transparan" (chroma key) ============
private enum class KeyColor(val label: String, val color: Color) {
    GREEN("GREEN", Color(0xFF00FF00)),
    BLUE("BLUE", Color(0xFF0000FF)),
    MAGENTA("MAGENTA", Color(0xFFFF00FF))
}

// ============ Model lirik (waktu dalam milidetik absolut) ============
@Serializable
data class LyricWord(val text: String, val startMs: Long, val endMs: Long)

@Serializable
data class LyricLine(val words: List<LyricWord>) {
    val startMs: Long get() = words.first().startMs
    val endMs: Long get() = words.last().endMs
}

// ============ Groq response model ============
@Serializable
private data class GroqWord(val word: String, val start: Double, val end: Double)

@Serializable
private data class GroqSegment(val start: Double, val end: Double, val text: String = "")

@Serializable
private data class GroqResponse(
    val text: String = "",
    val words: List<GroqWord>? = null,
    val segments: List<GroqSegment>? = null,
)

private val JsonConfig = Json { ignoreUnknownKeys = true }

// ============ Dummy lyrics (fallback preview) ============
private fun buildDummyLyrics(durationMs: Long): List<LyricLine> {
    fun line(startFrac: Float, endFrac: Float, words: List<String>): LyricLine {
        val s = durationMs * startFrac
        val e = durationMs * endFrac
        val step = (e - s) / words.size
        return LyricLine(words.mapIndexed { i, w ->
            LyricWord(w, (s + i * step).toLong(), (s + (i + 1) * step).toLong())
        })
    }
    return listOf(
        line(0.03f, 0.18f, listOf("hola", "cómo", "estás")),
        line(0.20f, 0.36f, listOf("i", "feel", "so", "brat", "today")),
        line(0.38f, 0.54f, listOf("aku", "juga", "sama")),
        line(0.56f, 0.72f, listOf("привет", "как", "дела")),
        line(0.74f, 0.94f, listOf("we", "don't", "stop", "we", "don't", "quit")),
    )
}

// ============ Groq transcribe ============

private suspend fun transcribeWithGroq(
    context: Context,
    uri: Uri,
    fileName: String,
    language: String? = null
): List<LyricLine> = withContext(Dispatchers.IO) {
    val apiKey = BuildConfig.GROQ_API_KEY
    if (apiKey.isBlank()) {
        throw IllegalStateException("GROQ_API_KEY belum diset. Tambahkan di local.properties lalu Clean + Rebuild.")
    }

    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Gagal baca file audio")

    if (bytes.size > 25 * 1024 * 1024) {
        throw IllegalStateException(
            "File ${bytes.size / 1024 / 1024}MB > 25MB limit Groq free tier. " +
                    "Coba audio lebih pendek atau bitrate lebih rendah."
        )
    }

    val safeName = if (fileName.contains('.')) fileName else "$fileName.m4a"

    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    val bodyBuilder = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", safeName, bytes.toRequestBody("audio/*".toMediaTypeOrNull()))
        .addFormDataPart("model", "whisper-large-v3")
        .addFormDataPart("response_format", "verbose_json")
        .addFormDataPart("timestamp_granularities[]", "word")
        .addFormDataPart("prompt", "Lirik lagu yang dinyanyikan.")

    if (language != null && language != "auto") {
        bodyBuilder.addFormDataPart("language", language)
    }

    val request = Request.Builder()
        .url("https://api.groq.com/openai/v1/audio/transcriptions")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(bodyBuilder.build())
        .build()

    client.newCall(request).execute().use { response ->
        val raw = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw IllegalStateException("Groq ${response.code}: ${raw.take(200)}")
        }
        val parsed = JsonConfig.decodeFromString<GroqResponse>(raw)
        val lines = convertToLyricLines(parsed)
        if (lines.isEmpty()) {
            throw IllegalStateException("Transkripsi kosong — coba audio dengan vokal lebih jelas")
        }
        lines
    }
}

private fun secToMs(sec: Double): Long =
    ((sec * 1000).toLong() - LYRIC_OFFSET_MS).coerceAtLeast(0L)

private fun convertToLyricLines(response: GroqResponse): List<LyricLine> {
    val words = response.words
    val segs = response.segments

    val flat: List<LyricWord> = when {
        !words.isNullOrEmpty() -> words.mapNotNull { w ->
            val t = w.word.trim()
            if (t.isBlank()) null
            else LyricWord(t, secToMs(w.start), secToMs(w.end))
        }

        !segs.isNullOrEmpty() -> segs.flatMap { seg ->
            val ws = seg.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (ws.isEmpty()) emptyList()
            else {
                val step = (seg.end - seg.start) / ws.size
                ws.mapIndexed { i, w ->
                    LyricWord(w, secToMs(seg.start + i * step), secToMs(seg.start + (i + 1) * step))
                }
            }
        }

        else -> emptyList()
    }

    // Kelompokkan jadi baris: jeda panjang, akhir kalimat, atau terlalu banyak kata
    val lines = mutableListOf<LyricLine>()
    var cur = mutableListOf<LyricWord>()
    for (w in flat) {
        if (cur.isNotEmpty()) {
            val prev = cur.last()
            val gap = w.startMs - prev.endMs
            val lastChar = prev.text.last()
            val sentenceEnd = lastChar == '.' || lastChar == '!' || lastChar == '?'
            val softBreak = lastChar == ',' && cur.size >= 3
            if (gap > 600 || cur.size >= MAX_WORDS_PER_LINE || sentenceEnd || softBreak) {
                lines.add(LyricLine(cur))
                cur = mutableListOf()
            }
        }
        cur.add(w)
    }
    if (cur.isNotEmpty()) lines.add(LyricLine(cur))
    return lines
}

// ============ Renderer gaya brat (dipakai preview DAN export) ============

private class BratFrameRenderer(private val lyrics: List<LyricLine>) {

    private class Placed(val word: LyricWord, val text: String, val x: Float, val baseline: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Mirip Arial Narrow yang dipakai album brat
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }

    private var cacheIdx = -1
    private var cacheCount = -1
    private var cacheFactor = 0f
    private var cacheTextScale = 1f
    private var cacheW = 0
    private var cacheH = 0
    private var cacheSize = 0f
    private var cachePlaced: List<Placed> = emptyList()

    private var blurRadius = -1f
    private var blurFilter: BlurMaskFilter? = null

    fun draw(
        canvas: NativeCanvas,
        w: Int,
        h: Int,
        bgColor: Int,
        fontColor: Int,
        rawTimeMs: Long,
        style: RevealStyle,
        holdMs: Long,
        shiftMs: Long,
        blur: Boolean = true,
        textScale: Float = 1f
    ) {
        val timeMs = rawTimeMs - shiftMs
        canvas.drawColor(bgColor)

        val idx = lyrics.indexOfLast { it.startMs <= timeMs }
        if (idx < 0) return
        val line = lyrics[idx]
        if (timeMs > line.endMs + holdMs) return

        // BRAT: hanya kata yang sudah muncul yang ikut di-layout, jadi ukuran
        // semua kata menyesuaikan setiap ada kata baru.
        val isBrat = style == RevealStyle.BRAT
        val count = if (isBrat) line.words.count { it.startMs <= timeMs }.coerceAtLeast(1)
        else line.words.size
        val factor = if (isBrat) 0.40f else 0.22f

        ensureLayout(idx, line, count, factor, textScale, w, h)

        val radius = (min(w, h) * 0.005f).coerceAtLeast(0.5f)
        if (radius != blurRadius) {
            blurRadius = radius
            blurFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        paint.maskFilter = if (blur) blurFilter else null
        paint.textSize = cacheSize
        val baseAlpha = fontColor ushr 24

        for (p in cachePlaced) {
            val age = timeMs - p.word.startMs
            if (!isBrat && age < 0) continue // kata belum dinyanyikan -> belum muncul
            val a = when (style) {
                RevealStyle.SMOOTH -> (age / 100f).coerceIn(0f, 1f) // fade-in halus
                else -> 1f                                           // BLINK & BRAT: langsung muncul
            }
            paint.color = fontColor
            paint.alpha = (baseAlpha * a).toInt()
            canvas.drawText(p.text, p.x, p.baseline, paint)
        }
    }

    private fun ensureLayout(
        idx: Int,
        line: LyricLine,
        count: Int,
        factor: Float,
        textScale: Float,
        w: Int,
        h: Int
    ) {
        if (cacheIdx == idx && cacheCount == count && cacheFactor == factor &&
            cacheTextScale == textScale && cacheW == w && cacheH == h
        ) return
        cacheIdx = idx
        cacheCount = count
        cacheFactor = factor
        cacheTextScale = textScale
        cacheW = w
        cacheH = h

        val ws = line.words.take(count)
        val texts = ws.map { it.text.lowercase() } // brat = huruf kecil semua
        val padX = w * 0.08f
        val maxW = w - 2 * padX
        val maxH = h * 0.72f
        val minSide = min(w, h).toFloat()
        var size = minSide * factor * textScale.coerceIn(0.5f, 1.5f)

        // Auto-fit tetap aktif walaupun pengguna memperbesar teks.
        // Ukuran terus dikecilkan sampai semua kata muat secara horizontal
        // dan seluruh baris muat secara vertikal di dalam frame.
        var rows: List<List<Int>> = emptyList()
        for (attempt in 0 until 80) {
            paint.textSize = size
            rows = wrap(texts, maxW)
            val widest = texts.maxOfOrNull { paint.measureText(it) } ?: 0f
            val totalHeight = rows.size * size * 1.02f

            if (widest <= maxW && totalHeight <= maxH) break

            val widthScale = if (widest > maxW) maxW / widest else 1f
            val heightScale = if (totalHeight > maxH) maxH / totalHeight else 1f
            val shrink = min(widthScale, heightScale).coerceIn(0.05f, 0.95f)
            size *= shrink * 0.98f
        }

        // Final safety pass: kata yang sangat panjang tidak boleh keluar frame.
        paint.textSize = size
        rows = wrap(texts, maxW)
        val widest = texts.maxOfOrNull { paint.measureText(it) } ?: 0f
        if (widest > maxW) {
            size *= (maxW / widest).coerceIn(0.01f, 1f)
            paint.textSize = size
            rows = wrap(texts, maxW)
        }

        val lineH = size * 1.02f
        val top = (h - rows.size * lineH) / 2f
        val space = paint.measureText(" ")
        val placed = mutableListOf<Placed>()
        rows.forEachIndexed { r, row ->
            var x = padX
            for (i in row) {
                placed.add(Placed(ws[i], texts[i], x, top + r * lineH + size * 0.82f))
                x += paint.measureText(texts[i]) + space
            }
        }
        cacheSize = size
        cachePlaced = placed
    }

    private fun wrap(texts: List<String>, maxW: Float): List<List<Int>> {
        val space = paint.measureText(" ")
        val rows = mutableListOf<List<Int>>()
        var cur = mutableListOf<Int>()
        var x = 0f
        texts.forEachIndexed { i, t ->
            val ww = paint.measureText(t)
            if (cur.isNotEmpty() && x + space + ww > maxW) {
                rows.add(cur); cur = mutableListOf(); x = 0f
            }
            x = if (cur.isEmpty()) ww else x + space + ww
            cur.add(i)
        }
        if (cur.isNotEmpty()) rows.add(cur)
        return rows
    }
}

// ============ Export video (MediaCodec + MediaMuxer) ============

/** Potong audio [startUs, endUs] lalu simpan sebagai AAC (.m4a). */
private fun transcodeAudioRange(context: Context, uri: Uri, startUs: Long, endUs: Long, out: File) {
    val extractor = MediaExtractor()
    extractor.setDataSource(context, uri, null)

    var trackIdx = -1
    var inFormat: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
            trackIdx = i; inFormat = f; break
        }
    }
    if (trackIdx < 0 || inFormat == null) {
        extractor.release()
        throw IllegalStateException("Tidak ada track audio di file ini")
    }
    extractor.selectTrack(trackIdx)
    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

    val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
    decoder.configure(inFormat, null, null, 0)
    decoder.start()

    val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var encoder: MediaCodec? = null
    var muxTrack = -1
    var muxStarted = false
    var sampleRate = 44100
    var channels = 2
    var bytesFed = 0L

    fun ensureEncoder(sr: Int, ch: Int) {
        if (encoder != null) return
        sampleRate = sr; channels = ch
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        encoder = enc
    }

    fun drainEncoder(untilEos: Boolean) {
        val enc = encoder ?: return
        val bi = MediaCodec.BufferInfo()
        while (true) {
            val i = enc.dequeueOutputBuffer(bi, if (untilEos) 10_000L else 0L)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxTrack = muxer.addTrack(enc.outputFormat)
                    muxer.start()
                    muxStarted = true
                }

                i >= 0 -> {
                    val buf = enc.getOutputBuffer(i)!!
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bi.size = 0
                    if (bi.size > 0 && muxStarted) {
                        buf.position(bi.offset)
                        buf.limit(bi.offset + bi.size)
                        muxer.writeSampleData(muxTrack, buf, bi)
                    }
                    enc.releaseOutputBuffer(i, false)
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun feedPcm(data: ByteArray) {
        val enc = encoder ?: return
        val frameBytes = 2 * channels
        var off = 0
        while (off < data.size) {
            val ii = enc.dequeueInputBuffer(10_000L)
            if (ii < 0) {
                drainEncoder(false); continue
            }
            val ib = enc.getInputBuffer(ii)!!
            ib.clear()
            var n = min(ib.remaining(), data.size - off)
            n -= n % frameBytes
            if (n <= 0) {
                enc.queueInputBuffer(ii, 0, 0, 0, 0); continue
            }
            ib.put(data, off, n)
            val pts = bytesFed * 1_000_000L / (frameBytes.toLong() * sampleRate)
            enc.queueInputBuffer(ii, 0, n, pts, 0)
            bytesFed += n
            off += n
            drainEncoder(false)
        }
    }

    try {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val ii = decoder.dequeueInputBuffer(10_000L)
                if (ii >= 0) {
                    val ib = decoder.getInputBuffer(ii)!!
                    val sz = extractor.readSampleData(ib, 0)
                    val st = extractor.sampleTime
                    if (sz < 0 || st >= endUs) {
                        decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(ii, 0, sz, st, 0)
                        extractor.advance()
                    }
                }
            }

            val oi = decoder.dequeueOutputBuffer(info, 10_000L)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = decoder.outputFormat
                    ensureEncoder(
                        f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    )
                }

                oi >= 0 -> {
                    if (encoder == null) {
                        val f = decoder.outputFormat
                        ensureEncoder(
                            f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        )
                    }
                    val ob = decoder.getOutputBuffer(oi)!!
                    val usable = info.size > 0 &&
                            info.presentationTimeUs >= startUs &&
                            info.presentationTimeUs < endUs
                    if (usable) {
                        val arr = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.get(arr)
                        decoder.releaseOutputBuffer(oi, false)
                        feedPcm(arr)
                    } else {
                        decoder.releaseOutputBuffer(oi, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }

        val enc = encoder ?: throw IllegalStateException("Audio tidak bisa di-decode")
        var eosQueued = false
        while (!eosQueued) {
            val ii = enc.dequeueInputBuffer(10_000L)
            if (ii >= 0) {
                val pts = bytesFed * 1_000_000L / (2L * channels * sampleRate)
                enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                eosQueued = true
            } else {
                drainEncoder(false)
            }
        }
        drainEncoder(true)
    } finally {
        runCatching { decoder.stop() }; decoder.release()
        encoder?.let { runCatching { it.stop() }; it.release() }
        runCatching { if (muxStarted) muxer.stop() }; muxer.release()
        extractor.release()
    }
}

/** Gambar frame demi frame (Canvas) -> H.264 video tanpa suara. */
private fun encodeVideoFrames(
    outFile: File,
    width: Int,
    height: Int,
    fps: Int,
    totalFrames: Int,
    drawFrame: (NativeCanvas, Int) -> Unit,
    onProgress: (Int) -> Unit
) {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * 0.15).toInt())
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val surface = encoder.createInputSurface()
    encoder.start()

    val muxer = MediaMuxer(outFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var track = -1
    var started = false
    var written = 0L
    val info = MediaCodec.BufferInfo()

    fun drain(untilEos: Boolean) {
        // Saat menunggu akhir stream, jangan menunggu selamanya
        val deadline = System.currentTimeMillis() + 10_000L
        while (true) {
            if (untilEos && System.currentTimeMillis() > deadline) {
                android.util.Log.w("BratExport", "Sinyal EOS dari encoder tidak datang dalam 10 detik, lanjut finalize")
                return
            }
            val i = encoder.dequeueOutputBuffer(info, if (untilEos) 10_000L else 0L)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                }

                i >= 0 -> {
                    val buf = encoder.getOutputBuffer(i)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        // Timestamp dari Surface = jam sistem, jadi kita timpa dengan nomor frame
                        info.presentationTimeUs = written * 1_000_000L / fps
                        written++
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    encoder.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    try {
        for (i in 0 until totalFrames) {
            val canvas = surface.lockCanvas(null)
            try {
                drawFrame(canvas, i)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            drain(false)
            if (i % 10 == 0) onProgress(i)
        }
        onProgress(totalFrames) // semua frame sudah digambar, tinggal finalisasi
        encoder.signalEndOfInputStream()
        drain(true)
    } finally {
        runCatching { encoder.stop() }; encoder.release(); surface.release()
        runCatching { if (started) muxer.stop() }; muxer.release()
    }
}

/** Gabungkan video + audio (tanpa re-encode). */
private fun muxVideoAndAudio(videoFile: File, audioFile: File, out: File) {
    val vEx = MediaExtractor().apply { setDataSource(videoFile.path) }
    val aEx = MediaExtractor().apply { setDataSource(audioFile.path) }

    fun selectTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) {
                ex.selectTrack(i); return i
            }
        }
        throw IllegalStateException("Track $prefix tidak ditemukan")
    }

    val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var started = false
    try {
        val vi = selectTrack(vEx, "video/")
        val ai = selectTrack(aEx, "audio/")
        val vTrack = muxer.addTrack(vEx.getTrackFormat(vi))
        val aTrack = muxer.addTrack(aEx.getTrackFormat(ai))
        muxer.start(); started = true

        val buf = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val vt = if (vEx.sampleTrackIndex >= 0) vEx.sampleTime else -1L
            val at = if (aEx.sampleTrackIndex >= 0) aEx.sampleTime else -1L
            if (vt < 0 && at < 0) break
            val useVideo = vt >= 0 && (at < 0 || vt <= at)
            val ex = if (useVideo) vEx else aEx
            val track = if (useVideo) vTrack else aTrack

            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, ex.sampleTime, flags)
            muxer.writeSampleData(track, buf, info)
            ex.advance()
        }
    } finally {
        runCatching { if (started) muxer.stop() }; muxer.release()
        vEx.release(); aEx.release()
    }
}

private fun saveVideoToGallery(context: Context, src: File, displayName: String): Uri {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        throw IllegalStateException("Simpan ke galeri butuh Android 10 (API 29) ke atas")
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BratLyric")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Gagal membuat file di galeri")
    resolver.openOutputStream(uri)?.use { os ->
        src.inputStream().use { it.copyTo(os) }
    } ?: throw IllegalStateException("Gagal menulis file video")
    values.clear()
    values.put(MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}

private suspend fun exportLyricVideo(
    context: Context,
    audioUri: Uri,
    lyrics: List<LyricLine>,
    aspect: AspectOption,
    style: RevealStyle,
    holdMs: Long,
    shiftMs: Long,
    startMs: Long,
    endMs: Long,
    bgColor: Color,
    fontColor: Color,
    transparent: Boolean,
    keyColor: Color,
    textScale: Float,
    onProgress: (Float) -> Unit
): Uri = withContext(Dispatchers.Default) {
    val cache = context.cacheDir
    val videoTmp = File(cache, "brat_video_tmp.mp4")
    val audioTmp = File(cache, "brat_audio_tmp.m4a")
    val finalTmp = File(cache, "brat_final_tmp.mp4")
    try {
        onProgress(0.02f)
        transcodeAudioRange(context, audioUri, startMs * 1000L, endMs * 1000L, audioTmp)
        onProgress(0.10f)

        val renderer = BratFrameRenderer(lyrics)
        val bg = if (transparent) keyColor.toArgb() else bgColor.toArgb()
        val fg = fontColor.toArgb()
        val totalFrames = ceil((endMs - startMs) * EXPORT_FPS / 1000.0).toInt().coerceAtLeast(1)

        encodeVideoFrames(
            outFile = videoTmp,
            width = aspect.w,
            height = aspect.h,
            fps = EXPORT_FPS,
            totalFrames = totalFrames,
            drawFrame = { canvas, frame ->
                val t = startMs + frame * 1000L / EXPORT_FPS
                renderer.draw(
                    canvas, aspect.w, aspect.h, bg, fg, t, style, holdMs, shiftMs,
                    blur = !transparent,
                    textScale = textScale
                )
            },
            onProgress = { frame -> onProgress(0.10f + 0.75f * frame / totalFrames) }
        )

        onProgress(0.92f)
        muxVideoAndAudio(videoTmp, audioTmp, finalTmp)
        onProgress(0.97f)

        val uri = saveVideoToGallery(context, finalTmp, "brat_lyric_${System.currentTimeMillis()}.mp4")
        onProgress(1f)
        uri
    } finally {
        videoTmp.delete(); audioTmp.delete(); finalTmp.delete()
    }
}

// ============ Activity ============

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BartTheme { BratHomeScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BratHomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var audioName by rememberSaveable { mutableStateOf<String?>(null) }
    var audioUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableLongStateOf(0L) }

    // === Cut / trim ===
    var trimStartMs by remember { mutableLongStateOf(0L) }
    var trimEndMs by remember { mutableLongStateOf(0L) }

    var videoBgColor by rememberSaveable(stateSaver = ColorSaver) { mutableStateOf(DEFAULT_VIDEO_BG) }
    var videoFontColor by rememberSaveable(stateSaver = ColorSaver) { mutableStateOf(DEFAULT_VIDEO_FONT) }
    var isPreviewing by rememberSaveable { mutableStateOf(false) }
    var selectedAspect by remember { mutableStateOf(AspectOption.R_1_1) }
    var transparentBg by remember { mutableStateOf(false) }
    var keyColor by remember { mutableStateOf(KeyColor.GREEN) }

    // === Gaya & timing lirik ===
    var revealStyle by remember { mutableStateOf(RevealStyle.SMOOTH) }
    var lyricTextScale by rememberSaveable { mutableFloatStateOf(1f) }
    var holdMs by remember { mutableLongStateOf(DEFAULT_HOLD_MS) }
    var shiftMs by remember { mutableLongStateOf(0L) }
    var showEditor by remember { mutableStateOf(false) }

    // === Transcription state ===
    var lyrics by remember { mutableStateOf<List<LyricLine>?>(null) }
    var selectedLanguage by remember { mutableStateOf("auto") } // "auto", "id", "en", "ru", "es"
    var isTranscribing by remember { mutableStateOf(false) }
    var transcribeError by remember { mutableStateOf<String?>(null) }

    // === Export state ===
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exportedUri by remember { mutableStateOf<Uri?>(null) }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }

            audioUri = it
            isPlaying = false; isPrepared = false
            positionMs = 0L; durationMs = 0L
            trimStartMs = 0L; trimEndMs = 0L
            lyrics = null; transcribeError = null
            exportedUri = null; exportError = null
            context.contentResolver.query(it, null, null, null, null)?.use { data ->
                if (data.moveToFirst()) {
                    val idx = data.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    audioName = if (idx >= 0) data.getString(idx) else "Audio selected"
                }
            }
        }
    }

    DisposableEffect(audioUri) {
        val player = audioUri?.let { uri ->
            MediaPlayer().apply {
                setDataSource(context, uri)
                setOnPreparedListener { mp ->
                    isPrepared = true
                    durationMs = mp.duration.toLong()
                    positionMs = 0L
                    trimStartMs = 0L
                    trimEndMs = durationMs
                }
                setOnCompletionListener { mp ->
                    isPlaying = false; positionMs = 0L; mp.seekTo(0)
                }
                prepareAsync()
            }
        }
        mediaPlayer = player
        onDispose {
            player?.release()
            mediaPlayer = null; isPrepared = false; isPlaying = false
            positionMs = 0L; durationMs = 0L
        }
    }

    LaunchedEffect(isPlaying, isPrepared) {
        while (isPlaying && isPrepared) {
            if (!isDragging) mediaPlayer?.let { positionMs = it.currentPosition.toLong() }
            delay(200L)
        }
    }

    // ============== MAIN UI ==============
    Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = APP_BG) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Text("brat.", fontSize = 64.sp, fontWeight = FontWeight.Black, color = APP_FONT)
            Spacer(Modifier.height(8.dp))
            Text("LYRIC VIDEO GENERATOR", fontSize = 13.sp, letterSpacing = 3.sp, color = APP_FONT)
            Spacer(Modifier.height(40.dp))
            Text("Turn your music into visuals.", fontSize = 18.sp, color = APP_FONT)
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { audioPicker.launch(arrayOf("audio/*")) },
                colors = ButtonDefaults.buttonColors(containerColor = APP_FONT, contentColor = APP_BG),
                modifier = Modifier.fillMaxWidth()
            ) { Text("SELECT AUDIO") }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    mediaPlayer?.let { p ->
                        if (p.isPlaying) {
                            p.pause(); isPlaying = false
                        } else {
                            if (positionMs >= durationMs && durationMs > 0) {
                                p.seekTo(0); positionMs = 0L
                            }
                            p.start(); isPlaying = true
                        }
                    }
                },
                enabled = isPrepared,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = APP_FONT, contentColor = APP_BG)
            ) { Text(if (isPlaying) "PAUSE" else "PLAY") }

            Spacer(Modifier.height(16.dp))

            if (isPrepared) {
                Slider(
                    value = (if (isDragging) dragPositionMs else positionMs)
                        .coerceIn(0L, durationMs).toFloat(),
                    onValueChange = { isDragging = true; dragPositionMs = it.toLong() },
                    onValueChangeFinished = {
                        mediaPlayer?.seekTo(dragPositionMs.toInt())
                        positionMs = dragPositionMs; isDragging = false
                    },
                    valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = APP_FONT,
                        activeTrackColor = APP_FONT,
                        inactiveTrackColor = APP_FONT.copy(alpha = 0.3f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val cur = if (isDragging) dragPositionMs else positionMs
                    Text(formatTime(cur), color = APP_FONT, fontSize = 12.sp)
                    Text(formatTime(durationMs), color = APP_FONT, fontSize = 12.sp)
                }
            }

            audioName?.let {
                Spacer(Modifier.height(8.dp))
                Text("Selected: $it", color = APP_FONT, fontWeight = FontWeight.Bold)
            }

            // ============ CUT SONG ============
            if (isPrepared && durationMs > 0) {
                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = APP_FONT.copy(alpha = 0.2f))
                Spacer(Modifier.height(16.dp))

                Text(
                    "CUT SONG", fontSize = 12.sp, letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold, color = APP_FONT,
                    modifier = Modifier.align(Alignment.Start)
                )
                Text(
                    "Video hanya dibuat untuk bagian yang dipilih",
                    fontSize = 11.sp, color = APP_FONT.copy(alpha = 0.65f),
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(Modifier.height(8.dp))

                RangeSlider(
                    value = trimStartMs.toFloat()..trimEndMs.toFloat(),
                    onValueChange = { r ->
                        val s = r.start.toLong()
                        val e = r.endInclusive.toLong()
                        if (e - s >= MIN_CUT_MS) {
                            trimStartMs = s; trimEndMs = e
                        }
                    },
                    valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = APP_FONT,
                        activeTrackColor = APP_FONT,
                        inactiveTrackColor = APP_FONT.copy(alpha = 0.3f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("START ${formatTime(trimStartMs)}", color = APP_FONT, fontSize = 12.sp)
                    Text(
                        "LENGTH ${formatTime(trimEndMs - trimStartMs)}",
                        color = APP_FONT, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                    Text("END ${formatTime(trimEndMs)}", color = APP_FONT, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (positionMs < trimEndMs - MIN_CUT_MS) trimStartMs = positionMs
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, APP_FONT),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = APP_FONT)
                    ) { Text("SET START HERE", fontSize = 10.sp) }
                    OutlinedButton(
                        onClick = {
                            if (positionMs > trimStartMs + MIN_CUT_MS) trimEndMs = positionMs
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, APP_FONT),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = APP_FONT)
                    ) { Text("SET END HERE", fontSize = 10.sp) }
                }
            }

            Spacer(Modifier.height(24.dp))

            // === UI PILIH BAHASA ===
            Text(
                "LAGU INI BAHASA APA?",
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = APP_FONT.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val langs = listOf(
                    "auto" to "AUTO", "id" to "INDO", "en" to "ENG", "ru" to "RUS", "es" to "ESP"
                )
                langs.forEach { (code, label) ->
                    val isSelected = selectedLanguage == code
                    Surface(
                        modifier = Modifier.weight(1f).height(32.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = if (isSelected) APP_FONT else Color.Transparent,
                        border = BorderStroke(1.dp, APP_FONT),
                        onClick = { selectedLanguage = code }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                label,
                                color = if (isSelected) APP_BG else APP_FONT,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    val uri = audioUri ?: return@Button
                    val name = audioName ?: "audio.m4a"
                    isTranscribing = true
                    transcribeError = null
                    scope.launch {
                        try {
                            lyrics = transcribeWithGroq(context, uri, name, selectedLanguage)
                        } catch (e: Exception) {
                            transcribeError = e.message ?: "Gagal transkripsi"
                        } finally {
                            isTranscribing = false
                        }
                    }
                },
                enabled = isPrepared && !isTranscribing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = APP_FONT, contentColor = APP_BG)
            ) {
                if (isTranscribing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = APP_BG, strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("TRANSCRIBING...")
                } else {
                    Text(if (lyrics != null) "RE-TRANSCRIBE" else "TRANSCRIBE LYRIC")
                }
            }

            lyrics?.let {
                Spacer(Modifier.height(8.dp))
                val wordCount = it.sumOf { l -> l.words.size }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Transcription berhasil",
                        tint = APP_FONT,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${it.size} lines · $wordCount words",
                        color = APP_FONT.copy(alpha = 0.8f), fontSize = 11.sp
                    )
                }
            }
            transcribeError?.let {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Transcription error",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(it, color = Color(0xFFFF5252), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ============ EDIT LYRICS ============
            OutlinedButton(
                onClick = { showEditor = true },
                enabled = lyrics != null,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, APP_FONT.copy(alpha = if (lyrics != null) 1f else 0.3f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = APP_FONT)
            ) { Text("EDIT LYRICS (teks & timing)", fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = APP_FONT.copy(alpha = 0.2f))
            Spacer(Modifier.height(16.dp))

            // ============ REVEAL STYLE ============
            Text(
                "REVEAL STYLE", fontSize = 12.sp, letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold, color = APP_FONT,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RevealStyle.values().forEach { st ->
                    val isSelected = revealStyle == st
                    Surface(
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = if (isSelected) APP_FONT else Color.Transparent,
                        border = BorderStroke(1.dp, APP_FONT),
                        onClick = { revealStyle = st }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                st.label,
                                color = if (isSelected) APP_BG else APP_FONT,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                revealStyle.desc,
                fontSize = 11.sp, color = APP_FONT.copy(alpha = 0.65f),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "LYRIC TEXT SIZE · ${(lyricTextScale * 100).toInt()}%",
                fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                color = APP_FONT, modifier = Modifier.align(Alignment.Start)
            )
            Slider(
                value = lyricTextScale,
                onValueChange = { lyricTextScale = it },
                valueRange = 0.5f..1.5f,
                steps = 19,
                colors = SliderDefaults.colors(
                    thumbColor = APP_FONT,
                    activeTrackColor = APP_FONT,
                    inactiveTrackColor = APP_FONT.copy(alpha = 0.3f)
                )
            )
            Text(
                "Ukuran otomatis diperkecil bila lirik terlalu panjang agar tidak keluar frame.",
                fontSize = 10.sp, color = APP_FONT.copy(alpha = 0.65f),
                modifier = Modifier.align(Alignment.Start)
            )
            TextButton(onClick = { lyricTextScale = 1f }) {
                Text(
                    "RESET TEXT SIZE", fontSize = 11.sp, letterSpacing = 2.sp,
                    color = APP_FONT.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "TEXT DURATION · tampil ${formatSecs(holdMs)} setelah kata terakhir",
                fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                color = APP_FONT, modifier = Modifier.align(Alignment.Start)
            )
            Slider(
                value = holdMs.toFloat(),
                onValueChange = { holdMs = ((it / 100f).roundToLong() * 100L) },
                valueRange = 0f..4000f,
                colors = SliderDefaults.colors(
                    thumbColor = APP_FONT,
                    activeTrackColor = APP_FONT,
                    inactiveTrackColor = APP_FONT.copy(alpha = 0.3f)
                )
            )

            Text(
                "LYRIC SHIFT · ${String.format(Locale.US, "%+.2f", shiftMs / 1000f)}s " +
                        "(minus = lebih cepat, plus = lebih lambat)",
                fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                color = APP_FONT, modifier = Modifier.align(Alignment.Start)
            )
            Slider(
                value = shiftMs.toFloat(),
                onValueChange = { shiftMs = ((it / 50f).roundToLong() * 50L) },
                valueRange = -1000f..1000f,
                colors = SliderDefaults.colors(
                    thumbColor = APP_FONT,
                    activeTrackColor = APP_FONT,
                    inactiveTrackColor = APP_FONT.copy(alpha = 0.3f)
                )
            )
            TextButton(onClick = { holdMs = DEFAULT_HOLD_MS; shiftMs = 0L }) {
                Text(
                    "RESET TIMING", fontSize = 11.sp, letterSpacing = 2.sp,
                    color = APP_FONT.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    mediaPlayer?.let { p ->
                        p.seekTo(trimStartMs.toInt())
                        positionMs = trimStartMs
                        p.start(); isPlaying = true
                    }
                    isPreviewing = true
                },
                enabled = isPrepared,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(2.dp, APP_FONT),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = APP_FONT)
            ) { Text("PREVIEW LYRIC", fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = APP_FONT.copy(alpha = 0.2f))
            Spacer(Modifier.height(20.dp))

            // ============ VIDEO RATIO ============
            Text(
                "VIDEO RATIO", fontSize = 12.sp, letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold, color = APP_FONT,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AspectOption.values().forEach { opt ->
                    val isSelected = selectedAspect == opt
                    Surface(
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = if (isSelected) APP_FONT else Color.Transparent,
                        border = BorderStroke(1.dp, APP_FONT),
                        onClick = { selectedAspect = opt }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                opt.label,
                                color = if (isSelected) APP_BG else APP_FONT,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ============ VIDEO THEME ============
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "VIDEO THEME", fontSize = 12.sp, letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold, color = APP_FONT
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Colors applied to the generated lyric video",
                        fontSize = 11.sp, color = APP_FONT.copy(alpha = 0.65f)
                    )
                }
                Box(
                    modifier = Modifier.size(width = 72.dp, height = 40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(videoBgColor)
                        .border(1.dp, APP_FONT.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("aa", color = videoFontColor, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "PRESET", fontSize = 11.sp, letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold, color = APP_FONT,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PRESETS.forEach { preset ->
                    PresetButton(
                        preset = preset,
                        onClick = { videoBgColor = preset.bg; videoFontColor = preset.font },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "TRANSPARENT BACKGROUND", fontSize = 11.sp, letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold, color = APP_FONT
                    )
                    Text(
                        "MP4 tidak bisa transparan. Video dibuat dengan warna kunci polos " +
                                "yang tinggal dihapus (Chroma Key) di CapCut/editor lain. " +
                                "Pastikan warna font tidak sama dengan warna kunci.",
                        fontSize = 10.sp, color = APP_FONT.copy(alpha = 0.65f)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = transparentBg,
                    onCheckedChange = { transparentBg = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = APP_BG,
                        checkedTrackColor = APP_FONT,
                        uncheckedThumbColor = APP_FONT,
                        uncheckedTrackColor = APP_BG,
                        uncheckedBorderColor = APP_FONT
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
            if (transparentBg) {
                Text(
                    "WARNA KUNCI (yang nanti dihapus)", fontSize = 11.sp, letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold, color = APP_FONT,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KeyColor.values().forEach { k ->
                        val sel = keyColor == k
                        Surface(
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = k.color,
                            border = BorderStroke(
                                if (sel) 3.dp else 1.dp,
                                if (sel) Color.White else APP_FONT.copy(alpha = 0.4f)
                            ),
                            onClick = { keyColor = k }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    k.label,
                                    color = if (k == KeyColor.GREEN) Color.Black else Color.White,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else {
                ColorPickerRow("VIDEO BACKGROUND", videoBgColor) { videoBgColor = it }
            }
            Spacer(Modifier.height(16.dp))
            ColorPickerRow("VIDEO FONT", videoFontColor) { videoFontColor = it }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                videoBgColor = DEFAULT_VIDEO_BG; videoFontColor = DEFAULT_VIDEO_FONT
            }) {
                Text(
                    "RESET VIDEO COLORS", fontSize = 11.sp, letterSpacing = 2.sp,
                    color = APP_FONT.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = APP_FONT.copy(alpha = 0.2f))
            Spacer(Modifier.height(20.dp))

            // ============ GENERATE & SAVE VIDEO ============
            Button(
                onClick = {
                    val uri = audioUri ?: return@Button
                    val ly = lyrics ?: return@Button
                    mediaPlayer?.pause(); isPlaying = false
                    isExporting = true
                    exportProgress = 0f
                    exportError = null
                    exportedUri = null
                    scope.launch {
                        try {
                            exportedUri = exportLyricVideo(
                                context = context,
                                audioUri = uri,
                                lyrics = ly,
                                aspect = selectedAspect,
                                style = revealStyle,
                                holdMs = holdMs,
                                shiftMs = shiftMs,
                                startMs = trimStartMs,
                                endMs = trimEndMs,
                                bgColor = videoBgColor,
                                fontColor = videoFontColor,
                                transparent = transparentBg,
                                keyColor = keyColor.color,
                                textScale = lyricTextScale,
                                onProgress = { exportProgress = it }
                            )
                        } catch (e: Exception) {
                            exportError = e.message ?: "Gagal membuat video"
                        } finally {
                            isExporting = false
                        }
                    }
                },
                enabled = isPrepared && lyrics != null && !isExporting,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = APP_FONT, contentColor = APP_BG)
            ) {
                Text(
                    if (isExporting) {
                        if (exportProgress >= 0.85f) "FINALIZING VIDEO..."
                        else "GENERATING ${(exportProgress * 100).toInt()}%"
                    }
                    else "GENERATE & SAVE VIDEO",
                    fontWeight = FontWeight.Black
                )
            }

            if (lyrics == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Transcribe lirik dulu sebelum generate video",
                    color = APP_FONT.copy(alpha = 0.6f), fontSize = 11.sp
                )
            }

            if (isExporting) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { exportProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = APP_FONT,
                    trackColor = APP_FONT.copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Jangan tutup aplikasi sampai selesai",
                    color = APP_FONT.copy(alpha = 0.6f), fontSize = 11.sp
                )
            }

            exportError?.let {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Export error",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(it, color = Color(0xFFFF5252), fontSize = 11.sp)
                }
            }

            exportedUri?.let { uri ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Video tersimpan",
                        tint = APP_FONT,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "Video tersimpan di Galeri / Movies/BratLyric",
                        color = APP_FONT, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, APP_FONT),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = APP_FONT)
                    ) { Text("OPEN VIDEO", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "Share video"))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, APP_FONT),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = APP_FONT)
                    ) { Text("SHARE", fontSize = 11.sp) }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    val player = mediaPlayer
    if (isPreviewing && player != null) {
        LyricPreviewOverlay(
            player = player,
            startMs = trimStartMs,
            endMs = trimEndMs,
            lyrics = lyrics ?: buildDummyLyrics(durationMs),
            aspect = selectedAspect,
            style = revealStyle,
            holdMs = holdMs,
            shiftMs = shiftMs,
            bgColor = videoBgColor,
            fontColor = videoFontColor,
            transparent = transparentBg,
            textScale = lyricTextScale,
            onExit = {
                runCatching { player.pause() }
                isPlaying = false; isPreviewing = false
            }
        )
    }

    if (showEditor) {
        lyrics?.let { current ->
            LyricEditorDialog(
                initial = current,
                player = mediaPlayer,
                maxMs = durationMs,
                onSave = { edited ->
                    lyrics = if (edited.isEmpty()) null else edited
                    showEditor = false
                    isPlaying = false
                },
                onCancel = {
                    showEditor = false
                    isPlaying = false
                }
            )
        }
    }
}

// ===================== Editor lirik =====================

private class EditRow(
    val id: Int,
    text: String,
    startMs: Long,
    endMs: Long,
    val original: LyricLine?
) {
    var text by mutableStateOf(text)
    var startMs by mutableLongStateOf(startMs)
    var endMs by mutableLongStateOf(endMs)
}

/** Bangun LyricLine dari hasil edit (teks + start/end). Timing kata dihitung ulang. */
private fun buildLine(text: String, startMs: Long, endMs: Long, original: LyricLine?): LyricLine? {
    val ws = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (ws.isEmpty()) return null
    val s = startMs
    val e = maxOf(endMs, startMs + 100L)

    val timings: List<Pair<Long, Long>> =
        if (original != null && original.words.size == ws.size && original.endMs > original.startMs) {
            // Jumlah kata sama: pertahankan proporsi timing kata, skala ke rentang baru
            val os = original.startMs
            val oe = original.endMs
            val k = (e - s).toDouble() / (oe - os)
            original.words.map { w ->
                (s + ((w.startMs - os) * k).toLong()) to (s + ((w.endMs - os) * k).toLong())
            }
        } else {
            // Jumlah kata berubah: bagi rata
            val step = (e - s).toDouble() / ws.size
            ws.indices.map { i -> (s + (i * step).toLong()) to (s + ((i + 1) * step).toLong()) }
        }

    return LyricLine(ws.mapIndexed { i, t -> LyricWord(t, timings[i].first, timings[i].second) })
}

@Composable
private fun LyricEditorDialog(
    initial: List<LyricLine>,
    player: MediaPlayer?,
    maxMs: Long,
    onSave: (List<LyricLine>) -> Unit,
    onCancel: () -> Unit
) {
    var nextId by remember { mutableIntStateOf(initial.size) }
    val rows = remember {
        mutableStateListOf<EditRow>().also { list ->
            initial.forEachIndexed { i, l ->
                list.add(
                    EditRow(
                        id = i,
                        text = l.words.joinToString(" ") { it.text },
                        startMs = l.startMs,
                        endMs = l.endMs,
                        original = l
                    )
                )
            }
        }
    }

    // Putar potongan audio untuk mengecek timing baris
    var playCounter by remember { mutableIntStateOf(0) }
    var playFrom by remember { mutableLongStateOf(0L) }
    var playTo by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playCounter) {
        if (playCounter == 0) return@LaunchedEffect
        val p = player ?: return@LaunchedEffect
        try {
            p.seekTo(playFrom.toInt())
            p.start()
            while (p.currentPosition < playTo) delay(50L)
            p.pause()
        } catch (_: IllegalStateException) {
        }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { player?.pause() } }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = APP_BG) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "EDIT LYRICS", fontSize = 16.sp, letterSpacing = 2.sp,
                            fontWeight = FontWeight.Black, color = APP_FONT
                        )
                        Text(
                            "${rows.size} baris · ubah teks, atur start/end, tekan PLAY untuk cek",
                            fontSize = 10.sp, color = APP_FONT.copy(alpha = 0.65f)
                        )
                    }
                    TextButton(onClick = onCancel) {
                        Text("CANCEL", color = APP_FONT.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            val result = rows
                                .mapNotNull { buildLine(it.text, it.startMs, it.endMs, it.original) }
                                .sortedBy { it.startMs }
                            onSave(result)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = APP_FONT, contentColor = APP_BG)
                    ) { Text("SAVE", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
                HorizontalDivider(color = APP_FONT.copy(alpha = 0.2f))

                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    itemsIndexed(rows, key = { _, r -> r.id }) { index, row ->
                        EditRowItem(
                            index = index,
                            row = row,
                            maxMs = maxMs,
                            onPlay = {
                                playFrom = (row.startMs - 300L).coerceAtLeast(0L)
                                playTo = row.endMs + 300L
                                playCounter++
                            },
                            onDelete = { rows.remove(row) }
                        )
                    }
                    item {
                        OutlinedButton(
                            onClick = {
                                val lastEnd = rows.maxOfOrNull { it.endMs } ?: 0L
                                val start = lastEnd + 300L
                                rows.add(EditRow(nextId++, "new line", start, start + 2000L, null))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, APP_FONT),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = APP_FONT)
                        ) { Text("+ ADD LINE", fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditRowItem(
    index: Int,
    row: EditRow,
    maxMs: Long,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, APP_FONT.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${index + 1} · ${formatTenths(row.endMs - row.startMs)} detik",
                color = APP_FONT, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            SmallBtn("PLAY", icon = Icons.Filled.PlayArrow, onClick = onPlay)
            Spacer(Modifier.width(8.dp))
            SmallBtn("DELETE", icon = Icons.Filled.Delete, onClick = onDelete)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = row.text,
            onValueChange = { row.text = it },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = APP_FONT,
                unfocusedTextColor = APP_FONT,
                cursorColor = APP_FONT,
                focusedBorderColor = APP_FONT,
                unfocusedBorderColor = APP_FONT.copy(alpha = 0.4f)
            )
        )
        Spacer(Modifier.height(8.dp))
        TimeRow("START", row.startMs) { d ->
            row.startMs = (row.startMs + d).coerceAtLeast(0L)
                .coerceAtMost(maxOf(0L, row.endMs - 100L))
        }
        Spacer(Modifier.height(6.dp))
        TimeRow("END", row.endMs) { d ->
            row.endMs = (row.endMs + d).coerceAtLeast(row.startMs + 100L)
                .coerceAtMost(maxOf(maxMs, row.startMs + 100L))
        }
    }
}

@Composable
private fun TimeRow(label: String, valueMs: Long, onDelta: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label ${formatTenths(valueMs)}",
            color = APP_FONT, fontSize = 12.sp,
            modifier = Modifier.width(104.dp)
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallBtn("-0.5") { onDelta(-500L) }
            SmallBtn("-0.1") { onDelta(-100L) }
            SmallBtn("+0.1") { onDelta(100L) }
            SmallBtn("+0.5") { onDelta(500L) }
        }
    }
}

@Composable
private fun SmallBtn(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, APP_FONT),
        onClick = onClick
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = APP_FONT,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(text, color = APP_FONT, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ===================== Preview overlay =====================

@Composable
private fun LyricPreviewOverlay(
    player: MediaPlayer,
    startMs: Long,
    endMs: Long,
    lyrics: List<LyricLine>,
    aspect: AspectOption,
    style: RevealStyle,
    holdMs: Long,
    shiftMs: Long,
    bgColor: Color,
    fontColor: Color,
    transparent: Boolean,
    textScale: Float,
    onExit: () -> Unit
) {
    var timeMs by remember { mutableLongStateOf(startMs) }
    val renderer = remember(lyrics) { BratFrameRenderer(lyrics) }

    // Update posisi setiap frame (±60fps) supaya lirik halus mengikuti audio
    LaunchedEffect(player, startMs, endMs) {
        while (true) {
            withFrameNanos { }
            val p = try {
                player.currentPosition.toLong()
            } catch (_: Exception) {
                break
            }
            if (p >= endMs) {
                player.seekTo(startMs.toInt())
                timeMs = startMs
            } else {
                timeMs = p
            }
        }
    }

    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { onExit() } }
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.aspectRatio(aspect.ratio).clipToBounds()) {
                val t = timeMs // dibaca di sini supaya canvas digambar ulang tiap berubah
                if (transparent) {
                    // Pola kotak-kotak = indikator "transparan" (hanya di preview)
                    drawRect(Color(0xFF2E2E2E))
                    val cell = size.width / 20f
                    var row = 0
                    var y = 0f
                    while (y < size.height) {
                        var col = 0
                        var x = 0f
                        while (x < size.width) {
                            if ((row + col) % 2 == 0) {
                                drawRect(Color(0xFF4A4A4A), topLeft = Offset(x, y), size = Size(cell, cell))
                            }
                            x += cell; col++
                        }
                        y += cell; row++
                    }
                }
                drawIntoCanvas { c ->
                    renderer.draw(
                        c.nativeCanvas,
                        size.width.toInt(), size.height.toInt(),
                        if (transparent) 0 else bgColor.toArgb(), fontColor.toArgb(), t,
                        style, holdMs, shiftMs,
                        blur = !transparent,
                        textScale = textScale
                    )
                }
            }

            Text(
                "PREVIEW ${aspect.label} · ${style.label}",
                modifier = Modifier.align(Alignment.TopCenter),
                color = APP_FONT.copy(alpha = 0.6f),
                fontSize = 11.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "TAP ANYWHERE TO EXIT",
                modifier = Modifier.align(Alignment.BottomCenter),
                color = APP_FONT.copy(alpha = 0.5f),
                fontSize = 10.sp, letterSpacing = 3.sp
            )
        }
    }
}

// ===================== Komponen kecil =====================

@Composable
private fun PresetButton(
    preset: BratPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(6.dp),
        color = preset.bg,
        border = BorderStroke(1.dp, preset.font.copy(alpha = 0.4f)),
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                preset.label, color = preset.font,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun ColorPickerRow(label: String, selected: Color, onSelect: (Color) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label, fontSize = 11.sp, letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold, color = APP_FONT
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            COLOR_PALETTE.forEach { color ->
                ColorSwatch(color, color == selected) { onSelect(color) }
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) APP_FONT else APP_FONT.copy(alpha = 0.3f),
                shape = CircleShape
            )
            .clickable { onClick() }
    )
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    return String.format(Locale.getDefault(), "%d:%02d", totalSec / 60, totalSec % 60)
}

private fun formatTenths(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    val m = t / 60000
    val s = (t / 1000) % 60
    val d = (t % 1000) / 100
    return String.format(Locale.US, "%d:%02d.%d", m, s, d)
}

private fun formatSecs(ms: Long): String =
    String.format(Locale.US, "%.1fs", ms / 1000f)