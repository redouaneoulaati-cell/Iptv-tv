package com.example.iptv

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun all(): Flow<List<PlaylistEntity>>

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Delete
    suspend fun delete(p: PlaylistEntity)
}

@Dao
interface FavoriteDao {
    @Query("SELECT url FROM favorites")
    fun all(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE url = :url")
    suspend fun remove(url: String)
}

@Database(entities = [PlaylistEntity::class, FavoriteEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun favorites(): FavoriteDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "iptv.db")
                .build().also { inst = it }
        }
    }
}
