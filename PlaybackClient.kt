package com.example.iptv

import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/** Se connecte au PlaybackService et expose son lecteur (null tant que la connexion n'est pas prête). */
@Composable
fun rememberController(): Player? {
    val ctx = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            controller = try { future.get() } catch (e: Exception) { null }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    return controller
}

fun mediaItemFor(ch: Channel): MediaItem = MediaItem.Builder()
    .setMediaId(ch.url)
    .setUri(ch.url)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(ch.url)).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(ch.name)
            .setArtworkUri(ch.logo?.let { Uri.parse(it) })
            .build()
    )
    .build()
