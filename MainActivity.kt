package com.example.dubber

import android.content.ContentValues
import android.media.MediaMetadataRetriever
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
        var srt by remember { mutableStateOf<Uri?>(null) }
        var key by remember { mutableStateOf("") }
        var voice by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { video = it }
        val pickSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { srt = it }
        val scope = rememberCoroutineScope()

        Column(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(key, { key = it }, label = { Text("ElevenLabs API key") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(voice, { voice = it }, label = { Text("Voice ID") }, modifier = Modifier.fillMaxWidth())
            Button({ pickVideo.launch(arrayOf("video/*")) }) { Text(if (video == null) "Выбрать видео" else "Видео выбрано ✓") }
            Button({ pickSrt.launch(arrayOf("*/*")) }) { Text(if (srt == null) "Выбрать SRT" else "SRT выбран ✓") }
            Button(
                enabled = video != null && srt != null && key.isNotBlank() && voice.isNotBlank(),
                onClick = {
                    scope.launch {
                        try {
                            val wav = File(cacheDir, "dub.wav")
                            withContext(Dispatchers.IO) {
                                val cues = parseSrt(contentResolver.openInputStream(srt!!)!!.bufferedReader().readText())
                                val r = MediaMetadataRetriever().apply { setDataSource(this@MainActivity, video) }
                                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                                r.release()
                                writeWav(wav, dub(cues, ElevenLabs(key, voice), dur) { i, n -> status = "Озвучка $i/$n" })
                            }
                            status = "Сборка видео…"
                            val out = File(cacheDir, "dubbed.mp4").also { it.delete() }
                            export(this@MainActivity, video!!, wav, out) { e ->
                                status = if (e == null) { saveToMovies(out); "Готово: Movies/Dubbed" }
                                else "Ошибка: ${e.message}"
                            }
                        } catch (e: Exception) {
                            status = "Ошибка: ${e.message}"
                        }
                    }
                }
            ) { Text("Озвучить") }
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
