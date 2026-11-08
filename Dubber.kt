package com.example.dubber

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.transformer.*
import java.io.File

fun durationMs(ctx: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
    } finally {
        r.release()
    }
}

/** Replaces the video's audio with [audio], time-stretched by [speed] (pitch stays). Main thread only. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun export(ctx: Context, video: Uri, audio: Uri, speed: Float, out: File, onDone: (Exception?) -> Unit) {
    val sonic = SonicAudioProcessor()
    sonic.setSpeed(speed)
    sonic.setPitch(1f)
    val v = EditedMediaItem.Builder(MediaItem.fromUri(video)).setRemoveAudio(true).build()
    val a = EditedMediaItem.Builder(MediaItem.fromUri(audio))
        .setRemoveVideo(true)
        .setEffects(Effects(listOf(sonic), emptyList()))
        .build()
    val comp = Composition.Builder(
        EditedMediaItemSequence.Builder(v).build(),
        EditedMediaItemSequence.Builder(a).build()
    ).build()
    Transformer.Builder(ctx).addListener(object : Transformer.Listener {
        override fun onCompleted(composition: Composition, result: ExportResult) = onDone(null)
        override fun onError(composition: Composition, result: ExportResult, e: ExportException) = onDone(e)
    }).build().start(comp, out.path)
}
