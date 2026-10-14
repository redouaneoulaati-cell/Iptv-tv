package com.example.iptv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

const val FAVORITES = "__favorites__"
private val ADULT_KEYWORDS = listOf("adult", "xxx", "18+")
private const val UNLOCK_MS = 30 * 60 * 1000L

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    private val prefs = app.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    val playlists = db.playlists().all()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favorites = db.favorites().all().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var epg by mutableStateOf<Map<String, List<Program>>>(emptyMap())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var current by mutableStateOf<Channel?>(null)
        private set
    /** Incrémenté à chaque arrêt demandé : l'UI arrête alors le lecteur du service. */
    var stopTick by mutableIntStateOf(0)
        private set

    var query by mutableStateOf("")
    var group by mutableStateOf<String?>(null)

    // ----- Réglages -----
    var pipEnabled by mutableStateOf(prefs.getBoolean("pip", true))
        private set
    var recMinutes by mutableIntStateOf(prefs.getInt("rec_minutes", 120))
        private set

    // ----- Code parental -----
    var pinSet by mutableStateOf(prefs.contains("pin_hash"))
        private set
    var lockedGroups by mutableStateOf(prefs.getStringSet("locked", emptySet())!!.toSet())
        private set
    var unlockedUntil by mutableLongStateOf(0L)
        private set
    var pendingChannel by mutableStateOf<Channel?>(null)
        private set
    var pinError by mutableStateOf(false)
        private set

    val unlocked: Boolean get() = !pinSet || System.currentTimeMillis() < unlockedUntil

    init {
        // Recharge automatiquement la dernière playlist (sans couper une lecture en cours)
        viewModelScope.launch {
            val id = prefs.getLong("playlist", -1)
            db.playlists().all().first().firstOrNull { it.id == id }?.let { load(it, stopPlayback = false) }
        }
    }

    // ---------- Playlists / EPG ----------

    fun load(p: PlaylistEntity, stopPlayback: Boolean = true) {
        viewModelScope.launch {
            loading = true
            error = null
            if (stopPlayback) stop()
            group = null
            epg = emptyMap()
            prefs.edit().putLong("playlist", p.id).apply()
            try {
                channels = withContext(Dispatchers.IO) {
                    Net.open(getApplication(), p.source).bufferedReader().use {
                        M3uParser.parse(it.readText())
                    }
                }
                if (channels.isEmpty()) error = "Aucune chaîne trouvée dans cette playlist"
            } catch (e: Exception) {
                channels = emptyList()
                error = "Impossible de charger la playlist : ${e.message}"
            }
            loading = false
        }
        p.epgUrl?.takeIf { it.isNotBlank() }?.let { loadEpg(it) }
    }

    private fun loadEpg(src: String) {
        viewModelScope.launch {
            epg = try {
                withContext(Dispatchers.IO) {
                    Net.open(getApplication(), src).use { XmltvParser.parse(it) }
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    fun addPlaylist(name: String, source: String, epgUrl: String?) {
        viewModelScope.launch {
            val p = PlaylistEntity(name = name, source = source, epgUrl = epgUrl)
            val id = db.playlists().insert(p)
            load(p.copy(id = id))
        }
    }

    fun removePlaylist(p: PlaylistEntity) {
        viewModelScope.launch {
            db.playlists().delete(p)
            if (p.id == prefs.getLong("playlist", -1)) {
                channels = emptyList(); epg = emptyMap(); stop()
            }
        }
    }

    fun toggleFavorite(ch: Channel) {
        viewModelScope.launch {
            if (ch.url in favorites.value) db.favorites().remove(ch.url)
            else db.favorites().add(FavoriteEntity(ch.url))
        }
    }

    // ---------- Lecture ----------

    fun play(ch: Channel) {
        if (isLocked(ch)) {
            pinError = false
            pendingChannel = ch
        } else {
            current = ch
        }
    }

    fun stop() {
        current = null
        stopTick++
    }

    /** Reprend l'affichage d'une lecture déjà en cours dans le service (appli rouverte). */
    fun restore(url: String, name: String) {
        if (current == null) current = Channel(null, name, url, null, null)
    }

    /** Chaîne suivante (+1) ou précédente (-1) dans la liste affichée, en sautant les chaînes verrouillées. */
    fun zap(list: List<Channel>, delta: Int) {
        if (list.isEmpty()) return
        var i = list.indexOfFirst { it.url == current?.url }
        repeat(list.size) {
            i = if (i < 0) 0 else (i + delta + list.size) % list.size
            val c = list[i]
            if (!isLocked(c)) {
                current = c
                return
            }
        }
    }

    fun filtered(favs: Set<String>): List<Channel> {
        val q = query.trim()
        return channels.filter { c ->
            (group == null || (group == FAVORITES && c.url in favs) || c.group == group) &&
                (q.isEmpty() || c.name.contains(q, ignoreCase = true))
        }
    }

    // ---------- Réglages ----------

    fun setPip(on: Boolean) {
        pipEnabled = on
        prefs.edit().putBoolean("pip", on).apply()
    }

    fun cycleRecMinutes() {
        recMinutes = when (recMinutes) { 30 -> 60; 60 -> 120; 120 -> 180; else -> 30 }
        prefs.edit().putInt("rec_minutes", recMinutes).apply()
    }

    // ---------- Code parental ----------

    private fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun checkPin(pin: String): Boolean {
        val salt = prefs.getString("pin_salt", "") ?: ""
        return prefs.getString("pin_hash", null) == hash(pin, salt)
    }

    fun setPin(pin: String) {
        val salt = UUID.randomUUID().toString()
        prefs.edit().putString("pin_salt", salt).putString("pin_hash", hash(pin, salt)).apply()
        pinSet = true
        unlockedUntil = System.currentTimeMillis() + UNLOCK_MS
    }

    fun removePin(pin: String): Boolean {
        if (!checkPin(pin)) return false
        prefs.edit().remove("pin_hash").remove("pin_salt").remove("locked").apply()
        pinSet = false
        lockedGroups = emptySet()
        return true
    }

    /** Déverrouille pour 30 minutes (réglages et chaînes verrouillées). */
    fun unlock(pin: String): Boolean {
        if (!checkPin(pin)) return false
        unlockedUntil = System.currentTimeMillis() + UNLOCK_MS
        return true
    }

    fun toggleLockedGroup(g: String) {
        lockedGroups = if (g in lockedGroups) lockedGroups - g else lockedGroups + g
        prefs.edit().putStringSet("locked", lockedGroups).apply()
    }

    fun isLocked(ch: Channel): Boolean {
        if (!pinSet || System.currentTimeMillis() < unlockedUntil) return false
        val g = ch.group ?: return false
        return g in lockedGroups || ADULT_KEYWORDS.any { g.contains(it, ignoreCase = true) }
    }

    fun submitPin(pin: String) {
        if (unlock(pin)) {
            pinError = false
            current = pendingChannel
            pendingChannel = null
        } else {
            pinError = true
        }
    }

    fun cancelPin() {
        pendingChannel = null
        pinError = false
    }
}
