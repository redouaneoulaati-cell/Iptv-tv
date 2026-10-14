package com.example.iptv

import androidx.room.Entity
import androidx.room.PrimaryKey

data class Channel(
    val id: String?,      // tvg-id, sert à relier la chaîne à l'EPG
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?
)

data class Program(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: String,   // URL http(s) ou content:// (fichier local)
    val epgUrl: String? = null
)

@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val url: String)
