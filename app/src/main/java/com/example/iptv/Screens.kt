package com.example.iptv

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- EPG ----------

fun Map<String, List<Program>>.nowNext(id: String?, now: Long): Pair<Program?, Program?> {
    if (id == null) return null to null
    val l = this[id] ?: return null to null
    val i = l.indexOfFirst { now >= it.start && now < it.stop }
    return if (i >= 0) l[i] to l.getOrNull(i + 1) else null to l.firstOrNull { it.start > now }
}

// ---------- Lecteur ----------

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: Player?, modifier: Modifier) {
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                keepScreenOn = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
        },
        update = { it.player = player },
        modifier = modifier
    )
}

@Composable
fun FullscreenEffect(on: Boolean) {
    val activity = LocalContext.current as Activity
    DisposableEffect(on) {
        val w = activity.window
        val ctrl = WindowCompat.getInsetsController(w, w.decorView)
        if (on) {
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

@Composable
fun PlayerBar(
    ch: Channel, isFav: Boolean, now: Program?, next: Program?, error: String?,
    sleepMin: Int, fullscreen: Boolean, recording: Boolean,
    onZap: (Int) -> Unit, onFav: () -> Unit, onSleep: () -> Unit, onRecord: () -> Unit,
    onFullscreen: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit
) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(8.dp)) {
        Text(ch.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        now?.let {
            Text(it.title, color = Color.White, maxLines = 1)
            val p = ((System.currentTimeMillis() - it.start).toFloat() / (it.stop - it.start)).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }
        next?.let {
            Text(
                "Ensuite ${clock.format(Date(it.start))} : ${it.title}",
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1
            )
        }
        error?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Erreur : $it", color = Color(0xFFFF8A80), modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text("Réessayer") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onZap(-1) }) { Text("◀") }
            TextButton(onClick = { onZap(1) }) { Text("▶") }
            TextButton(onClick = onFav) { Text(if (isFav) "★" else "☆") }
            TextButton(onClick = onSleep) { Text(if (sleepMin == 0) "Veille" else "Veille $sleepMin min") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRecord) {
                Text(
                    if (recording) "⏹ Arrêter" else "⏺ Enreg.",
                    color = if (recording) Color(0xFFFF5252) else Color.Unspecified
                )
            }
            CastButton()
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onFullscreen) { Text(if (fullscreen) "Réduire" else "Plein écran") }
            TextButton(onClick = onClose) { Text("✕") }
        }
    }
}

// ---------- Liste des chaînes ----------

@Composable
fun ChannelsScreen(vm: MainViewModel, list: List<Channel>, favs: Set<String>, now: Long) {
    val groups = remember(vm.channels) { vm.channels.mapNotNull { it.group }.distinct().sorted() }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query, onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            placeholder = { Text("Rechercher une chaîne") },
            singleLine = true
        )
        LazyRow(
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { FilterChip(vm.group == null, { vm.group = null }, { Text("Tous") }) }
            item { FilterChip(vm.group == FAVORITES, { vm.group = FAVORITES }, { Text("★ Favoris") }) }
            items(groups) { g -> FilterChip(vm.group == g, { vm.group = g }, { Text(g) }) }
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }
        if (!vm.loading && vm.channels.isEmpty() && vm.error == null) {
            Text("Aucune chaîne. Ajoutez une playlist via « Playlists ».", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list) { ch ->
                val (cur, _) = vm.epg.nowNext(ch.id, now)
                ListItem(
                    leadingContent = { AsyncImage(model = ch.logo, contentDescription = null, modifier = Modifier.size(48.dp)) },
                    headlineContent = { Text(ch.name, maxLines = 1) },
                    supportingContent = { cur?.let { Text(it.title, maxLines = 1) } },
                    trailingContent = {
                        TextButton(onClick = { vm.toggleFavorite(ch) }) {
                            Text(if (ch.url in favs) "★" else "☆")
                        }
                    },
                    modifier = Modifier.clickable { vm.play(ch) }
                )
            }
        }
    }
}
