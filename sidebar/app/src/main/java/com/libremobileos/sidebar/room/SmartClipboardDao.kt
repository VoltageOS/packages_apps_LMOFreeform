package com.libremobileos.sidebar.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartClipboardDao {
    @Insert
    fun insert(entity: SmartClipboardEntity): Long

    @Query("SELECT * FROM SmartClipboardEntity ORDER BY isPinned DESC, createdAt DESC, id DESC LIMIT :limit")
    fun getRecentByFlow(limit: Int): Flow<List<SmartClipboardEntity>>

    @Query("SELECT * FROM SmartClipboardEntity ORDER BY isPinned DESC, createdAt DESC, id DESC LIMIT :limit")
    fun getRecent(limit: Int): List<SmartClipboardEntity>

    @Query("SELECT * FROM SmartClipboardEntity ORDER BY createdAt DESC, id DESC LIMIT 1")
    fun getLatest(): SmartClipboardEntity?

    @Query("DELETE FROM SmartClipboardEntity WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM SmartClipboardEntity")
    fun deleteAll()

    @Query(
        "DELETE FROM SmartClipboardEntity " +
            "WHERE isPinned = 0 AND id NOT IN (" +
            "SELECT id FROM SmartClipboardEntity ORDER BY isPinned DESC, createdAt DESC, id DESC LIMIT :limit" +
            ")"
    )
    fun trimTo(limit: Int)

    @Query("DELETE FROM SmartClipboardEntity WHERE isPinned = 0 AND createdAt < :expirationTimestamp")
    fun deleteExpired(expirationTimestamp: Long)

    @Query("UPDATE SmartClipboardEntity SET isPinned = :isPinned WHERE id = :id")
    fun setPinned(id: Long, isPinned: Boolean)

    @Query("SELECT contentHash FROM SmartClipboardEntity ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentHashes(limit: Int): List<String>

}
