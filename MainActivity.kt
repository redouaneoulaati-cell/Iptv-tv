package com.example.iptv

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.delay

// FragmentActivity (et non ComponentActivity) : requis par le sélecteur d'appareils Chromecast
class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()
    private val billing: BillingViewModel by viewModels()
    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addOnPictureInPictureModeChangedListener { inPip = it.isInPictureInPictureMode }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(vm, billing, inPip) }
            }
        }
    }

    // Image dans l'image quand on quitte l'appli pendant la lecture (le son continue via le service)
    override fun onUserLeaveHint() {
        if (vm.current != null && vm.pipEnabled && Build.VERSION.SDK_INT >= 26) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }
}

@Composable
fun App(vm: MainViewModel, billing: BillingViewModel, inPip: Boolean) {
    val ctx = LocalContext.current
    val player = rememberController()
    val favs by vm.favorites.collectAsState()
    val recording by RecordingState.active.collectAsState()
    val current = vm.current

    var playError by remember { mutableStateOf<String?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var sleepMin by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    // Notifications (lecture en arrière-plan, enregistrement) : permission Android 13+
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    val list = remember(vm.channels, vm.query, vm.group, favs) { vm.filtered(favs) }
    val (curProg, nextProg) = remember(vm.epg, current, now) { vm.epg.nowNext(current?.id, now) }

    // Erreurs de lecture
    DisposableEffect(player) {
        val p = player
        if (p == null) {
            onDispose { }
        } else {
            val l = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { playError = error.errorCodeName }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) playError = null
                }
            }
            p.addListener(l)
            onDispose { p.removeListener(l) }
        }
    }

    // Appli rouverte pendant qu'une lecture continue dans le service : on réaffiche la chaîne
    LaunchedEffect(player) {
        val c = player ?: return@LaunchedEffect
        val mi = c.currentMediaItem
        if (vm.current == null && mi != null && c.mediaItemCount > 0) {
            vm.restore(mi.mediaId, mi.mediaMetadata.title?.toString() ?: "")
        }
    }

    // Lance la lecture de la chaîne choisie
    LaunchedEffect(current?.url, player) {
        val c = player ?: return@LaunchedEffect
        val ch = current ?: return@LaunchedEffect
        // Abonnement requis (REQUIRE_SUBSCRIPTION) : on renvoie vers l'onglet Abonnement
        if (REQUIRE_SUBSCRIPTION && !billing.status.active && !ch.url.startsWith("file:")) {
            vm.stop(); tab = 4; return@LaunchedEffect
        }
        if (c.currentMediaItem?.mediaId == ch.url && c.playbackState != Player.STATE_IDLE) return@LaunchedEffect
        playError = null
        c.setMediaItem(mediaItemFor(ch))
        c.prepare()
        c.playWhenReady = true
    }

    // Arrêt demandé (bouton ✕, minuteur, changement de playlist)
    LaunchedEffect(vm.stopTick, player) {
        if (vm.stopTick > 0) player?.let { it.stop(); it.clearMediaItems() }
    }

    // Minuteur de mise en veille
    LaunchedEffect(sleepMin) {
        if (sleepMin > 0) { delay(sleepMin * 60_000L); vm.stop(); sleepMin = 0 }
    }

    FullscreenEffect(fullscreen && current != null && !inPip)
    LaunchedEffect(fullscreen) {
        if (fullscreen) try { focus.requestFocus() } catch (e: Exception) { }
    }
    BackHandler(fullscreen) { fullscreen = false }
    BackHandler(!fullscreen && tab != 0) { tab = 0 }

    val bar: @Composable (Channel) -> Unit = { ch ->
        PlayerBar(
            ch = ch, isFav = ch.url in favs, now = curProg, next = nextProg, error = playError,
            sleepMin = sleepMin, fullscreen = fullscreen, recording = recording != null,
            onZap = { vm.zap(list, it) },
            onFav = { vm.toggleFavorite(ch) },
            onSleep = { sleepMin = when (sleepMin) { 0 -> 15; 15 -> 30; 30 -> 60; else -> 0 } },
            onRecord = {
                if (recording != null) stopRecording(ctx)
                else if (!ch.url.startsWith("file:")) startRecording(ctx, ch, vm.recMinutes)
            },
            onFullscreen = { fullscreen = !fullscreen },
            onRetry = { playError = null; player?.prepare() },
            onClose = { fullscreen = false; vm.stop() }
        )
    }

    when {
        current != null && inPip -> VideoSurface(player, Modifier.fillMaxSize())

        current != null && fullscreen -> Box(
            Modifier.fillMaxSize().background(Color.Black)
                .focusRequester(focus)
                .onKeyEvent { e ->
                    // Télécommande / clavier : haut-bas = zapping, OK = afficher les contrôles
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        Key.DirectionUp, Key.ChannelUp -> { vm.zap(list, -1); true }
                        Key.DirectionDown, Key.ChannelDown -> { vm.zap(list, 1); true }
                        Key.DirectionCenter, Key.Enter -> { controls = !controls; true }
                        else -> false
                    }
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    controls = !controls
                }
        ) {
            VideoSurface(player, Modifier.fillMaxSize())
            if (controls) Box(Modifier.align(Alignment.BottomCenter)) { bar(current) }
        }

        else -> Column(Modifier.fillMaxSize().systemBarsPadding()) {
            if (current != null) {
                VideoSurface(player, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                bar(current)
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                listOf("Chaînes", "Playlists", "Enreg.", "Réglages", "Abonnement").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
                }
            }
            when (tab) {
                0 -> ChannelsScreen(vm, list, favs, now)
                1 -> PlaylistsScreen(vm) { tab = 0 }
                2 -> RecordingsScreen(vm)
                3 -> SettingsScreen(vm)
                else -> SubscriptionScreen(billing)
            }
        }
    }

    PinDialog(vm)
}
