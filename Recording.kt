package com.example.iptv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingState {
    val active = MutableStateFlow<String?>(null)   // nom de la chaîne en cours d'enregistrement
    val error = MutableStateFlow<String?>(null)
}

fun recordingsDir(ctx: Context): File =
    (ctx.getExternalFilesDir("recordings") ?: File(ctx.filesDir, "recordings")).also { it.mkdirs() }

fun listRecordings(ctx: Context): List<File> =
    recordingsDir(ctx).listFiles { f -> f.extension == "ts" }?.sortedByDescending { it.lastModified() }
        ?: emptyList()

fun startRecording(ctx: Context, ch: Channel, minutes: Int) {
    val i = Intent(ctx, RecordingService::class.java)
        .setAction(RecordingService.ACTION_START)
        .putExtra(RecordingService.EXTRA_URL, ch.url)
        .putExtra(RecordingService.EXTRA_NAME, ch.name)
        .putExtra(RecordingService.EXTRA_MINUTES, minutes)
    ContextCompat.startForegroundService(ctx, i)
}

fun stopRecording(ctx: Context) {
    ctx.startService(Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
}

/**
 * Enregistre le flux dans un fichier .ts (service au premier plan).
 * Flux TS direct : copie brute. HLS (.m3u8) : téléchargement des segments successifs (TS non chiffré).
 */
class RecordingService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_MINUTES = "minutes"
        private const val CHANNEL = "recordings"
        private const val NOTIF_ID = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: "chaine"
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 120)
                val n = notification(name)
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIF_ID, n)
                }
                job?.cancel()
                job = scope.launch {
                    record(url, name, minutes)
                    stopSelf()
                }
            }
            else -> {
                job?.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(name: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Enregistrements", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Enregistrement en cours")
            .setContentText(name)
            .setOngoing(true)
            .addAction(0, "Arrêter", stop)
            .build()
    }

    private suspend fun record(url: String, name: String, minutes: Int) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val safe = name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "chaine" }
        val file = File(recordingsDir(this), "${safe}_$stamp.ts")
        val deadline = System.currentTimeMillis() + minutes * 60_000L

        RecordingState.error.value = null
        RecordingState.active.value = name
        try {
            FileOutputStream(file).use { out ->
                if (url.substringBefore('?').lowercase().endsWith(".m3u8")) recordHls(url, out, deadline)
                else recordRaw(url, out, deadline)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecordingState.error.value = e.message ?: "Erreur d'enregistrement"
        } finally {
            RecordingState.active.value = null
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "IPTVApp/1.0")
        }

    private suspend fun copy(input: InputStream, out: OutputStream, deadline: Long) {
        val buf = ByteArray(64 * 1024)
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    private suspend fun recordRaw(url: String, out: OutputStream, deadline: Long) {
        val c = open(url)
        try {
            c.inputStream.use { copy(it, out, deadline) }
        } finally {
            c.disconnect()
        }
    }

    private fun resolve(base: String, rel: String): String = URL(URL(base), rel).toString()

    private fun fetchText(url: String): String {
        val c = open(url)
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private suspend fun recordHls(url: String, out: OutputStream, deadline: Long) {
        var playlistUrl = url
        val seen = LinkedHashSet<String>()
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val lines = fetchText(playlistUrl).lines().map { it.trim() }

            val i = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            if (i >= 0) { // playlist maître : on suit la première variante
                val variant = lines.drop(i + 1).first { it.isNotEmpty() && !it.startsWith("#") }
                playlistUrl = resolve(playlistUrl, variant)
                continue
            }

            val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
                ?.substringAfter(":")?.toDoubleOrNull() ?: 6.0

            for (l in lines) {
                if (l.isEmpty() || l.startsWith("#")) continue
                val seg = resolve(playlistUrl, l)
                if (seen.add(seg)) {
                    val c = open(seg)
                    try {
                        c.inputStream.use { copy(it, out, Long.MAX_VALUE) }
                    } finally {
                        c.disconnect()
                    }
                }
                if (seen.size > 500) seen.remove(seen.first())
            }
            delay((target * 500).toLong())
        }
    }
}
