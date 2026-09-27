package com.libremobileos.sidebar.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartClipboardDao {
    @Insert
    suspend fun insert(entity: SmartClipboardEntity): Long

    @Query("SELECT * FROM SmartClipboardEntity ORDER BY isPinned DESC, createdAt DESC, id DESC LIMIT :limit")
    fun getRecentByFlow(limit: Int): Flow<List<SmartClipboardEntity>>

    @Query("SELECT * FROM SmartClipboardEntity ORDER BY isPinned DESC, createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SmartClipboardEntity>

    @Query("SELECT * FROM SmartClipboardEntity ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun getLatest(): SmartClipboardEntity?

    @Query("DELETE FROM SmartClipboardEntity WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM SmartClipboardEntity")
    suspend fun deleteAll()

    @Query("SELECT imagePath FROM SmartClipboardEntity WHERE isPinned = 0 AND id NOT IN (SELECT id FROM SmartClipboardEntity WHERE isPinned = 0 ORDER BY createdAt DESC, id DESC LIMIT :limit) AND imagePath IS NOT NULL")
    suspend fun getTrimPaths(limit: Int): List<String>

    @Query("DELETE FROM SmartClipboardEntity WHERE isPinned = 0 AND id NOT IN (SELECT id FROM SmartClipboardEntity WHERE isPinned = 0 ORDER BY createdAt DESC, id DESC LIMIT :limit)")
    suspend fun trimTo(limit: Int)

    @Transaction
    suspend fun trimToReturningPaths(limit: Int): List<String> {
        val paths = getTrimPaths(limit)
        trimTo(limit)
        return paths
    }

    @Query("SELECT imagePath FROM SmartClipboardEntity WHERE isPinned = 1 AND id NOT IN (SELECT id FROM SmartClipboardEntity WHERE isPinned = 1 ORDER BY createdAt DESC, id DESC LIMIT :limit) AND imagePath IS NOT NULL")
    suspend fun getPinnedTrimPaths(limit: Int): List<String>

    @Query("DELETE FROM SmartClipboardEntity WHERE isPinned = 1 AND id NOT IN (SELECT id FROM SmartClipboardEntity WHERE isPinned = 1 ORDER BY createdAt DESC, id DESC LIMIT :limit)")
    suspend fun trimPinnedTo(limit: Int)

    @Transaction
    suspend fun trimPinnedToReturningPaths(limit: Int): List<String> {
        val paths = getPinnedTrimPaths(limit)
        trimPinnedTo(limit)
        return paths
    }

    @Query("SELECT imagePath FROM SmartClipboardEntity WHERE isPinned = 0 AND createdAt < :expirationTimestamp AND imagePath IS NOT NULL")
    suspend fun getExpiredPaths(expirationTimestamp: Long): List<String>

    @Query("DELETE FROM SmartClipboardEntity WHERE isPinned = 0 AND createdAt < :expirationTimestamp")
    suspend fun deleteExpired(expirationTimestamp: Long)

    @Transaction
    suspend fun deleteExpiredReturningPaths(expirationTimestamp: Long): List<String> {
        val paths = getExpiredPaths(expirationTimestamp)
        deleteExpired(expirationTimestamp)
        return paths
    }

    @Query("UPDATE SmartClipboardEntity SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(id: Long, isPinned: Boolean)

    @Query("SELECT contentHash FROM SmartClipboardEntity ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentHashes(limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM SmartClipboardEntity WHERE contentHash = :hash")
    suspend fun countByHash(hash: String): Int

    @Query("UPDATE SmartClipboardEntity SET createdAt = :now WHERE contentHash = :hash")
    suspend fun bumpTimestampByHash(hash: String, now: Long)

    @Query("SELECT imagePath FROM SmartClipboardEntity WHERE imagePath IS NOT NULL")
    suspend fun getAllImagePaths(): List<String>

    @Query("SELECT MIN(createdAt) FROM SmartClipboardEntity WHERE isPinned = 0")
    suspend fun getOldestUnpinnedCreatedAt(): Long?

    @Query("SELECT imagePath FROM SmartClipboardEntity WHERE isPinned = 0 AND imagePath IS NOT NULL")
    suspend fun getUnpinnedPaths(): List<String>

    @Query("DELETE FROM SmartClipboardEntity WHERE isPinned = 0")
    suspend fun clearUnpinned()

    @Transaction
    suspend fun clearUnpinnedReturningPaths(): List<String> {
        val paths = getUnpinnedPaths()
        clearUnpinned()
        return paths
    }
}
