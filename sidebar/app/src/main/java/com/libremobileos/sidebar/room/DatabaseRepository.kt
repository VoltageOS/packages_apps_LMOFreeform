package com.libremobileos.sidebar.room

import android.content.Context
import androidx.lifecycle.LiveData
import com.libremobileos.sidebar.room.MyDatabase.Companion.getDatabase
import kotlinx.coroutines.flow.Flow
import java.lang.Exception

class DatabaseRepository(context: Context) {

    private val sidebarAppsDao: SidebarAppsDao
    private val smartClipboardDao: SmartClipboardDao

    suspend fun insertSidebarApp(packageName: String, activityName: String, userId: Int) {
        try {
            sidebarAppsDao.insert(packageName, activityName, userId)
        }catch (e: Exception) { }
    }

    suspend fun deleteSidebarApp(packageName: String, activityName: String, userId: Int) {
        sidebarAppsDao.delete(packageName, activityName, userId)
    }

    fun getAllSidebarName(): LiveData<List<String>?> {
        return sidebarAppsDao.getAllName()
    }

    fun getAllSidebar() : LiveData<List<SidebarAppsEntity>?> {
        return sidebarAppsDao.getAll()
    }

    fun getAllSidebarAppsByFlow(): Flow<List<SidebarAppsEntity>?> {
        return sidebarAppsDao.getAllByFlow()
    }

    suspend fun getCount(): Int {
        return sidebarAppsDao.getCount()
    }

    suspend fun update(entity: SidebarAppsEntity) {
        sidebarAppsDao.update(entity)
    }

    suspend fun getAllSidebarWithoutLiveData() : List<SidebarAppsEntity>? {
        return sidebarAppsDao.getAllWithoutLiveData()
    }

    suspend fun deleteAllSidebar() {
        sidebarAppsDao.deleteAll()
    }

    suspend fun deleteMore(sidebarAppsEntityList: List<SidebarAppsEntity>) {
        sidebarAppsDao.deleteList(sidebarAppsEntityList)
    }

    suspend fun insertSmartClipboardItem(entity: SmartClipboardEntity): Long {
        return smartClipboardDao.insert(entity)
    }

    fun getRecentSmartClipboardItemsByFlow(limit: Int): Flow<List<SmartClipboardEntity>> {
        return smartClipboardDao.getRecentByFlow(limit)
    }

    suspend fun getRecentSmartClipboardItems(limit: Int): List<SmartClipboardEntity> {
        return smartClipboardDao.getRecent(limit)
    }

    suspend fun getLatestSmartClipboardItem(): SmartClipboardEntity? {
        return smartClipboardDao.getLatest()
    }

    suspend fun getRecentHashes(limit: Int): List<String> {
        return smartClipboardDao.getRecentHashes(limit)
    }

    suspend fun countByHash(hash: String): Int {
        return smartClipboardDao.countByHash(hash)
    }

    suspend fun bumpTimestampByHash(hash: String, now: Long) {
        smartClipboardDao.bumpTimestampByHash(hash, now)
    }

    suspend fun deleteSmartClipboardItem(id: Long) {
        smartClipboardDao.deleteById(id)
    }

    suspend fun deleteAllSmartClipboardItems() {
        smartClipboardDao.deleteAll()
    }

    suspend fun clearUnpinnedReturningPaths(): List<String> {
        return smartClipboardDao.clearUnpinnedReturningPaths()
    }

    suspend fun setSmartClipboardItemPinned(id: Long, isPinned: Boolean) {
        smartClipboardDao.setPinned(id, isPinned)
    }

    suspend fun trimSmartClipboardHistory(limit: Int): List<String> {
        return smartClipboardDao.trimToReturningPaths(limit)
    }

    suspend fun trimPinnedHistory(limit: Int): List<String> {
        return smartClipboardDao.trimPinnedToReturningPaths(limit)
    }

    suspend fun deleteExpiredReturningPaths(expirationTimestamp: Long): List<String> {
        return smartClipboardDao.deleteExpiredReturningPaths(expirationTimestamp)
    }

    suspend fun getAllImagePaths(): List<String> {
        return smartClipboardDao.getAllImagePaths()
    }

    suspend fun getOldestUnpinnedCreatedAt(): Long? {
        return smartClipboardDao.getOldestUnpinnedCreatedAt()
    }

    init {
        val database = getDatabase(context)
        sidebarAppsDao = database.sidebarAppsDao
        smartClipboardDao = database.smartClipboardDao
    }
}
