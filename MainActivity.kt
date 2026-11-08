package com.example.dubber

import android.content.ContentValues
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Bg = Color(0xFF0E0E14)
private val Card = Color(0xFF181822)
private val Accent = Color(0xFF8B7BFF)
private val Text1 = Color(0xFFECECF4)
private val Text2 = Color(0xFF9A9AB0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Bg, background = Bg,
                    surface = Card, onSurface = Text1
                )
            ) { Screen() }
        }
    }

    private fun nameOf(u: Uri?): String? = u?.let {
        contentResolver.query(it, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }

    @Composable
    fun Screen() {
        var video by remember { mutableStateOf<Uri?>(null) }
        var audio by remember { mutableStateOf<Uri?>(null) }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var srt by remember { mutableStateOf<Uri?>(null) }
        var maxSp by remember { mutableFloatStateOf(1.25f) }
        val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { video = it ?: video }
        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { audio = it ?: audio }
        val pickSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { srt = it ?: srt }
        val scope = rememberCoroutineScope()
        val srtName = remember(srt) { nameOf(srt) }
        val videoName = remember(video) { nameOf(video) }
        val audioName = remember(audio) { nameOf(audio) }

        Column(
            Modifier.fillMaxSize().background(Bg)
                .verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Dubber", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Text1)
            Text("Подгони озвучку под видео", color = Text2)
            Spacer(Modifier.height(6.dp))

            FileCard("🎬", "Видео", videoName) { pickVideo.launch(arrayOf("video/*")) }
            FileCard("🎙️", "Озвучка (mp3 / mp4)", audioName) { pickAudio.launch(arrayOf("audio/*", "video/*")) }

            FileCard("📝", "Субтитры (SRT)", srtName) { pickSrt.launch(arrayOf("*/*")) }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Card),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Максимальное ускорение озвучки: ×" + "%.2f".format(maxSp), fontWeight = FontWeight.SemiBold, color = Text1)
                    Slider(value = maxSp, onValueChange = { maxSp = it }, valueRange = 1f..1.5f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("не ускорять", color = Text2, fontSize = 12.sp)
                        Text("×1.5", color = Text2, fontSize = 12.sp)
                    }
                }
            }

            Button(
                enabled = video != null && audio != null && srt != null && !busy,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            status = "Анализ озвучки…"
                            val wav = File(cacheDir, "dub.wav")
                            val info = withContext(Dispatchers.IO) {
                                val cues = parseSrt(contentResolver.openInputStream(srt!!)!!.bufferedReader().readText())
                                require(cues.isNotEmpty()) { "В SRT нет реплик" }
                                val voice = decode(this@MainActivity, audio!!)
                                val vMs = durationMs(this@MainActivity, video!!)
                                val d = dubAligned(this@MainActivity, cues, voice, vMs, maxSp)
                                writeWav(wav, d.pcm, voice.rate)
                                d.info
                            }
                            status = "$info\nСборка видео…"
                            val out = File(cacheDir, "dubbed.mp4").also { it.delete() }
                            export(this@MainActivity, video!!, wav, out) { e ->
                                busy = false
                                status = if (e == null) {
                                    saveToMovies(out)
                                    "Готово ✓ Movies/Dubbed\n$info"
                                } else "Ошибка: ${e.message}"
                            }
                        } catch (e: Exception) {
                            busy = false
                            status = "Ошибка: ${e.message}"
                        }
                    }
                }
            ) { Text(if (busy) "Работаю…" else "Подогнать", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
            if (status.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Card),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(status, Modifier.padding(18.dp), color = Text1) }
            }
        }
    }

    private fun saveToMovies(f: File) {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "dubbed_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Dubbed")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
    }
}

@Composable
private fun FileCard(emoji: String, title: String, name: String?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 28.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Text1)
                Text(name ?: "Нажми, чтобы выбрать", color = Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (name != null) Text("✓", color = Accent, fontSize = 22.sp)
        }
    }
}
