package com.example.dubber

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

data class Cue(val startMs: Long, val endMs: Long, val text: String)
data class Seg(val s: Long, val e: Long)
class Pcm(val data: ShortArray, val rate: Int)
class DubOut(val pcm: ShortArray, val info: String)

// ---------- SRT ----------
fun parseSrt(raw: String): List<Cue> {
    val ts = Regex("""(\d+):(\d+):(\d+)[,.](\d+)""")
    fun ms(s: String): Long {
        val m = ts.find(s)!!.groupValues
        return m[1].toLong() * 3_600_000 + m[2].toLong() * 60_000 +
            m[3].toLong() * 1000 + m[4].padEnd(3, '0').take(3).toLong()
    }
    return raw.removePrefix("\uFEFF").replace("\r", "").trim()
        .split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val i = lines.indexOfFirst { "-->" in it }
            if (i < 0) return@mapNotNull null
            val (a, b) = lines[i].split("-->")
            val text = lines.drop(i + 1).joinToString(" ").replace(Regex("<[^>]+>"), "").trim()
            if (text.isEmpty()) null else Cue(ms(a), ms(b), text)
        }.sortedBy { it.startMs }
}

fun durationMs(ctx: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
    } finally {
        r.release()
    }
}

// ---------- decode mp3/mp4 -> mono PCM ----------
fun decode(ctx: Context, uri: Uri): Pcm {
    val ex = MediaExtractor()
    ex.setDataSource(ctx, uri, null)
    val t = (0 until ex.trackCount).first {
        ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
    }
    ex.selectTrack(t)
    val fmt = ex.getTrackFormat(t)
    val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(fmt, null, null, 0)
    codec.start()
    var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val info = MediaCodec.BufferInfo()
    val out = ByteArrayOutputStream()
    var inDone = false
    var outDone = false
    while (!outDone) {
        if (!inDone) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                if (n < 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inDone = true
                } else {
                    codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                    ex.advance()
                }
            }
        }
        val o = codec.dequeueOutputBuffer(info, 10_000)
        if (o >= 0) {
            val b = codec.getOutputBuffer(o)!!
            b.position(info.offset)
            b.limit(info.offset + info.size)
            val arr = ByteArray(info.size)
            b.get(arr)
            out.write(arr)
            codec.releaseOutputBuffer(o, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
        } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val f = codec.outputFormat
            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }
    }
    codec.stop(); codec.release(); ex.release()
    val sb = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val frames = sb.remaining() / ch
    val mono = ShortArray(frames) { f ->
        var s = 0
        for (c in 0 until ch) s += sb.get(f * ch + c)
        (s / ch).toShort()
    }
    return Pcm(mono, rate)
}

private fun to16k(p: Pcm): FloatArray {
    val n = (p.data.size.toLong() * 16000 / p.rate).toInt()
    return FloatArray(n) { i ->
        val x = i.toDouble() * p.rate / 16000
        val a = x.toInt().coerceAtMost(p.data.size - 1)
        val b = (a + 1).coerceAtMost(p.data.size - 1)
        val f = (x - a).toFloat()
        (p.data[a] * (1 - f) + p.data[b] * f) / 32768f
    }
}

// ---------- speech detection: neural net (Silero VAD, free/MIT) ----------
private fun sileroSpeech(ctx: Context, x: FloatArray): BooleanArray {
    val env = OrtEnvironment.getEnvironment()
    val model = ctx.assets.open("silero_vad.onnx").use { it.readBytes() }
    env.createSession(model).use { sess ->
        var s0 = FloatArray(128)
        var s1 = FloatArray(128)
        var context = FloatArray(64)
        val sr = OnnxTensor.createTensor(env, 16000L)
        val n = x.size / 512
        val res = BooleanArray(n)
        for (i in 0 until n) {
            val inp = context + x.copyOfRange(i * 512, i * 512 + 512)
            OnnxTensor.createTensor(env, arrayOf(inp)).use { t ->
                OnnxTensor.createTensor(env, arrayOf(arrayOf(s0), arrayOf(s1))).use { st ->
                    sess.run(mapOf("input" to t, "state" to st, "sr" to sr)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val prob = (r[0].value as Array<FloatArray>)[0][0]
                        @Suppress("UNCHECKED_CAST")
                        val ns = r[1].value as Array<Array<FloatArray>>
                        s0 = ns[0][0]
                        s1 = ns[1][0]
                        res[i] = prob > 0.5f
                    }
                }
            }
            context = inp.copyOfRange(inp.size - 64, inp.size)
        }
        sr.close()
        return res
    }
}

// fallback without a neural net: simple loudness threshold
private fun energySpeech(x: FloatArray): BooleanArray {
    val n = x.size / 512
    val e = FloatArray(n) { i ->
        var s = 0f
        for (j in i * 512 until i * 512 + 512) s += x[j] * x[j]
        Math.sqrt(s / 512.0).toFloat()
    }
    val thr = maxOf((e.maxOrNull() ?: 0f) * 0.05f, 0.004f)
    return BooleanArray(n) { e[it] > thr }
}

private fun toSegs(speech: BooleanArray): List<Seg> {
    val fr = 32L // 512 samples @16k
    val raw = mutableListOf<Seg>()
    var st = -1
    for (i in speech.indices) {
        if (speech[i] && st < 0) st = i
        if (!speech[i] && st >= 0) { raw += Seg(st * fr, i * fr); st = -1 }
    }
    if (st >= 0) raw += Seg(st * fr, speech.size * fr)
    val merged = mutableListOf<Seg>()
    for (s in raw) {
        if (merged.isNotEmpty() && s.s - merged.last().e < 100) merged[merged.size - 1] = Seg(merged.last().s, s.e)
        else merged += s
    }
    return merged.filter { it.e - it.s >= 100 }
}

/** Splits speech into up to n phrases by cutting at the n-1 longest pauses. */
private fun groupSegs(segs: List<Seg>, n: Int): List<Seg> {
    if (segs.isEmpty()) return emptyList()
    val k = minOf(n, segs.size)
    val cuts = (0 until segs.size - 1).sortedByDescending { segs[it + 1].s - segs[it].e }.take(k - 1).sorted()
    val out = mutableListOf<Seg>()
    var from = 0
    for (g in cuts + (segs.size - 1)) { out += Seg(segs[from].s, segs[g].e); from = g + 1 }
    return out
}

/** Pitch-preserving time stretch (WSOLA). speed > 1 = faster. */
private fun stretch(x: ShortArray, rate: Int, speed: Float): ShortArray {
    if (abs(speed - 1f) < 0.02f || x.size < 4000) return x
    val win = rate * 30 / 1000 / 2 * 2
    val hopS = win / 2
    val hopA = (hopS * speed).roundToInt()
    val seek = rate * 8 / 1000
    val outLen = (x.size / speed).toInt()
    val out = FloatArray(outLen + 2 * win)
    val w = FloatArray(win) { (0.5 - 0.5 * cos(2.0 * Math.PI * it / win)).toFloat() }
    var prev = 0
    var k = 0
    while (k * hopS < outLen) {
        val outPos = k * hopS
        var p = 0
        if (k > 0) {
            val target = k * hopA
            val lo = (target - seek).coerceAtLeast(0)
            val hi = (target + seek).coerceAtMost(x.size - win)
            if (hi < lo) break
            val tpl = prev + hopS
            p = target.coerceIn(lo, hi)
            if (tpl + win <= x.size) {
                var bestC = Long.MIN_VALUE
                var c = lo
                while (c <= hi) {
                    var s = 0L
                    var i = 0
                    while (i < win) { s += x[tpl + i] * x[c + i]; i += 2 }
                    if (s > bestC) { bestC = s; p = c }
                    c += 2
                }
            }
        }
        for (i in 0 until win) out[outPos + i] += x[p + i].toFloat() * w[i]
        prev = p
        k++
    }
    return ShortArray(outLen) { out[it].roundToInt().coerceIn(-32768, 32767).toShort() }
}

/** Finds phrases in the voiceover and fits each one into its subtitle slot. */
fun dubAligned(ctx: Context, cues: List<Cue>, voice: Pcm, totalMs: Long, maxSpeed: Float): DubOut {
    val x = to16k(voice)
    var detector = "нейросеть Silero VAD"
    val speech = try {
        sileroSpeech(ctx, x)
    } catch (e: Throwable) {
        detector = "простой детектор пауз (нейросеть не загрузилась)"
        energySpeech(x)
    }
    val groups = groupSegs(toSegs(speech), cues.size)
    val rate = voice.rate
    val buf = ShortArray((totalMs * rate / 1000).toInt())
    var fast = 0
    groups.forEachIndexed { i, g ->
        val c = cues[i]
        val a = ((g.s - 30).coerceAtLeast(0L) * rate / 1000).toInt()
        val b = (((g.e + 30) * rate / 1000).toInt()).coerceAtMost(voice.data.size)
        if (b <= a) return@forEachIndexed
        val slot = maxOf(c.endMs - c.startMs, 300L)
        val speed = ((g.e - g.s).toFloat() / slot).coerceIn(0.9f, maxSpeed)
        if (speed > 1.02f) fast++
        val st = stretch(voice.data.copyOfRange(a, b), rate, speed)
        val limit = ((cues.getOrNull(i + 1)?.startMs ?: totalMs) * rate / 1000).toInt().coerceAtMost(buf.size)
        var p = (c.startMs * rate / 1000).toInt()
        for (v in st) {
            if (p >= limit) break
            buf[p] = (buf[p] + v).coerceIn(-32768, 32767).toShort()
            p++
        }
    }
    val warn = if (groups.size < cues.size) "\n⚠ Фраз в озвучке меньше, чем реплик в SRT" else ""
    return DubOut(buf, "Реплик: ${cues.size}, найдено фраз: ${groups.size}, ускорено: $fast\nДетектор: $detector$warn")
}

fun writeWav(f: File, pcm: ShortArray, rate: Int) {
    val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    pcm.forEach { data.putShort(it) }
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(pcm.size * 2)
    f.outputStream().use { it.write(h.array()); it.write(data.array()) }
}

/** Replaces the video's audio with the WAV. Main thread only. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun export(ctx: Context, video: Uri, wav: File, out: File, onDone: (Exception?) -> Unit) {
    val v = EditedMediaItem.Builder(MediaItem.fromUri(video)).setRemoveAudio(true).build()
    val a = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav))).build()
    val comp = Composition.Builder(
        EditedMediaItemSequence.Builder(v).build(),
        EditedMediaItemSequence.Builder(a).build()
    ).build()
    Transformer.Builder(ctx).addListener(object : Transformer.Listener {
        override fun onCompleted(composition: Composition, result: ExportResult) = onDone(null)
        override fun onError(composition: Composition, result: ExportResult, e: ExportException) = onDone(e)
    }).build().start(comp, out.path)
}
