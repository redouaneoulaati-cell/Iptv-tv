package com.example.iptv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.android.gms.cast.framework.CastContext
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Le lecteur vit dans ce service : la lecture continue quand l'appli est fermée
 * (notification média) et bascule automatiquement sur Chromecast si un appareil est connecté.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var exo: ExoPlayer
    private var cast: CastPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        session = MediaSession.Builder(this, exo).setCallback(Callback()).build()
        initCast()
    }

    private fun initCast() {
        try {
            CastContext.getSharedInstance(this, ContextCompat.getMainExecutor(this))
                .addOnSuccessListener { castContext ->
                    val cp = CastPlayer(castContext)
                    cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                        override fun onCastSessionAvailable() { swap(cp) }
                        override fun onCastSessionUnavailable() { swap(exo) }
                    })
                    cast = cp
                    if (cp.isCastSessionAvailable) swap(cp)
                }
                .addOnFailureListener { /* Cast indisponible */ }
        } catch (e: Exception) {
            // Google Play Services absent : Chromecast désactivé, le reste fonctionne
        }
    }

    /** Transfère la lecture en cours d'un lecteur à l'autre (téléphone <-> Chromecast). */
    private fun swap(to: Player) {
        val s = session ?: return
        val from = s.player
        if (from === to) return
        val item = from.currentMediaItem
        val pos = from.currentPosition
        val play = from.playWhenReady
        from.stop()
        from.clearMediaItems()
        s.player = to
        if (item != null) {
            to.setMediaItem(if (to === cast) withCastMime(item) else item, pos)
            to.prepare()
            to.playWhenReady = play
        }
    }

    private fun withCastMime(item: MediaItem): MediaItem {
        val uri = item.localConfiguration?.uri ?: return item
        if (item.localConfiguration?.mimeType != null) return item
        return item.buildUpon().setMimeType(mimeFor(uri) ?: MimeTypes.VIDEO_MP2T).build()
    }

    private fun mimeFor(uri: Uri): String? {
        val s = uri.toString().substringBefore('?').lowercase()
        return when {
            s.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            s.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    private inner class Callback : MediaSession.Callback {
        // Les MediaItem envoyés par l'appli arrivent sans URI : on la restitue ici
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val casting = mediaSession.player === cast
            val out = mediaItems.map { item ->
                val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
                    ?: return@map item
                val b = item.buildUpon().setUri(uri)
                val mime = mimeFor(uri) ?: if (casting) MimeTypes.VIDEO_MP2T else null
                mime?.let { b.setMimeType(it) }
                b.build()
            }.toMutableList()
            return Futures.immediateFuture(out)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        cast?.release()
        exo.release()
        super.onDestroy()
    }
}
