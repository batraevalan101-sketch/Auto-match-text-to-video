package com.example.dubber

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    @Composable
    fun Screen() {
        var video by remember { mutableStateOf<Uri?>(null) }
        var audio by remember { mutableStateOf<Uri?>(null) }
        var status by remember { mutableStateOf("") }
        val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { video = it }
        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { audio = it }
        val scope = rememberCoroutineScope()

        Column(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ pickVideo.launch(arrayOf("video/*")) }) {
                Text(if (video == null) "Выбрать видео" else "Видео выбрано ✓")
            }
            Button({ pickAudio.launch(arrayOf("audio/*", "video/*")) }) {
                Text(if (audio == null) "Выбрать озвучку (mp3/mp4)" else "Озвучка выбрана ✓")
            }
            Button(
                enabled = video != null && audio != null,
                onClick = {
                    scope.launch {
                        try {
                            status = "Анализ…"
                            val (vMs, aMs) = withContext(Dispatchers.IO) {
                                durationMs(this@MainActivity, video!!) to durationMs(this@MainActivity, audio!!)
                            }
                            val speed = (aMs.toFloat() / vMs).coerceIn(0.5f, 2f)
                            val sp = "%.2f".format(speed)
                            status = "Видео ${vMs / 1000} с, озвучка ${aMs / 1000} с → скорость озвучки ×$sp. Сборка…"
                            val out = File(cacheDir, "dubbed.mp4").also { it.delete() }
                            export(this@MainActivity, video!!, audio!!, speed, out) { e ->
                                status = if (e == null) {
                                    saveToMovies(out)
                                    "Готово: Movies/Dubbed (скорость ×$sp)"
                                } else "Ошибка: ${e.message}"
                            }
                        } catch (e: Exception) {
                            status = "Ошибка: ${e.message}"
                        }
                    }
                }
            ) { Text("Подогнать") }
            Text(status)
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
