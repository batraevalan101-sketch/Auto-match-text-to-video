package com.example.dubber

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

const val RATE = 24000 // pcm_24000: 16-bit mono

data class Cue(val startMs: Long, val endMs: Long, val text: String)

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
            val text = lines.drop(i + 1).joinToString(" ")
                .replace(Regex("<[^>]+>"), "").trim()
            if (text.isEmpty()) null else Cue(ms(a), ms(b), text)
        }.sortedBy { it.startMs }
}

class ElevenLabs(private val key: String, private val voiceId: String) {
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    /** Returns raw 16-bit mono PCM @ 24 kHz. speed: 0.7..1.2 */
    fun tts(text: String, speed: Double = 1.0): ByteArray {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", "eleven_multilingual_v2")
            .put("voice_settings", JSONObject().put("speed", speed))
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=pcm_24000")
            .header("xi-api-key", key).post(body).build()
        http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "ElevenLabs ${r.code}: ${r.body?.string()?.take(200)}" }
            return r.body!!.bytes()
        }
    }
}

private fun lenMs(pcm: ByteArray) = pcm.size / 2 * 1000L / RATE

/** Synthesizes every cue, speeds up lines that don't fit, mixes all onto one timeline. */
fun dub(cues: List<Cue>, tts: ElevenLabs, totalMs: Long, onProgress: (Int, Int) -> Unit): ShortArray {
    val buf = ShortArray((totalMs * RATE / 1000).toInt())
    cues.forEachIndexed { idx, c ->
        onProgress(idx + 1, cues.size)
        val slot = (c.endMs - c.startMs).coerceAtLeast(300)
        var pcm = tts.tts(c.text)
        if (lenMs(pcm) > slot) {
            val speed = (lenMs(pcm).toDouble() / slot).coerceIn(1.0, 1.2)
            pcm = tts.tts(c.text, Math.round(speed * 100) / 100.0)
        }
        // never run into the next cue
        val limitMs = cues.getOrNull(idx + 1)?.startMs ?: totalMs
        val end = minOf((limitMs * RATE / 1000).toInt(), buf.size)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        var i = (c.startMs * RATE / 1000).toInt()
        while (bb.remaining() >= 2 && i < end) {
            buf[i] = (buf[i] + bb.short).coerceIn(-32768, 32767).toShort()
            i++
        }
    }
    return buf
}

fun writeWav(f: File, pcm: ShortArray) {
    val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    pcm.forEach { data.putShort(it) }
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(pcm.size * 2)
    f.outputStream().use { it.write(h.array()); it.write(data.array()) }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
/** Replaces the video's audio with the WAV. Call from the main thread. */
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
