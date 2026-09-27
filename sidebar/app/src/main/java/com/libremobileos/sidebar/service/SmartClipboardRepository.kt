package com.libremobileos.sidebar.service

import com.libremobileos.sidebar.room.DatabaseRepository
import com.libremobileos.sidebar.room.SmartClipboardEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

class SmartClipboardRepository(
    private val repository: DatabaseRepository,
    private val clipboardDir: File,
) {
    fun observe(limit: Int): Flow<List<SmartClipboardEntity>> =
        repository.getRecentSmartClipboardItemsByFlow(limit)

    suspend fun insert(entity: SmartClipboardEntity): Long =
        repository.insertSmartClipboardItem(entity)

    suspend fun countByHash(hash: String): Int = repository.countByHash(hash)

    suspend fun promote(hash: String, now: Long) = repository.bumpTimestampByHash(hash, now)

    suspend fun delete(id: Long, path: String?) {
        repository.deleteSmartClipboardItem(id)
        path?.let { deleteFile(it) }
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = repository.setSmartClipboardItemPinned(id, pinned)

    suspend fun clearUnpinned() {
        repository.clearUnpinnedReturningPaths().forEach { deleteFile(it) }
    }

    suspend fun activePaths(): Set<String> = repository.getAllImagePaths().toSet()

    fun deleteFile(path: String) {
        runCatching {
            val f = File(path)
            if (f.exists()) f.delete()
        }
    }
}
