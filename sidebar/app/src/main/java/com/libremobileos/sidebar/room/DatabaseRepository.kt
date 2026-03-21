package com.libremobileos.sidebar.room

import android.content.Context
import androidx.lifecycle.LiveData
import com.libremobileos.sidebar.room.MyDatabase.Companion.getDatabase
import kotlinx.coroutines.flow.Flow
import java.lang.Exception

/**
 * @author sunshine
 * @date 2021/1/31
 */
class DatabaseRepository(context: Context) {

    private val sidebarAppsDao: SidebarAppsDao
    private val smartClipboardDao: SmartClipboardDao

    fun insertSidebarApp(packageName: String, activityName: String, userId: Int) {
        try {
            sidebarAppsDao.insert(packageName, activityName, userId)
        }catch (e: Exception) { }
    }

    fun deleteSidebarApp(packageName: String, activityName: String, userId: Int) {
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

    fun getCount(): Int {
        return sidebarAppsDao.getCount()
    }

    fun update(entity: SidebarAppsEntity) {
        sidebarAppsDao.update(entity)
    }

    fun getAllSidebarWithoutLiveData() : List<SidebarAppsEntity>? {
        return sidebarAppsDao.getAllWithoutLiveData()
    }

    fun deleteAllSidebar() {
        sidebarAppsDao.deleteAll()
    }

    fun deleteMore(sidebarAppsEntityList: List<SidebarAppsEntity>) {
        sidebarAppsDao.deleteList(sidebarAppsEntityList)
    }

    fun insertSmartClipboardItem(entity: SmartClipboardEntity): Long {
        return smartClipboardDao.insert(entity)
    }

    fun getRecentSmartClipboardItemsByFlow(limit: Int): Flow<List<SmartClipboardEntity>> {
        return smartClipboardDao.getRecentByFlow(limit)
    }

    fun getRecentSmartClipboardItems(limit: Int): List<SmartClipboardEntity> {
        return smartClipboardDao.getRecent(limit)
    }

    fun getLatestSmartClipboardItem(): SmartClipboardEntity? {
        return smartClipboardDao.getLatest()
    }

    fun getRecentHashes(limit: Int): List<String> {
        return smartClipboardDao.getRecentHashes(limit)
    }

    fun deleteSmartClipboardItem(id: Long) {
        smartClipboardDao.deleteById(id)
    }

    fun deleteAllSmartClipboardItems() {
        smartClipboardDao.deleteAll()
    }

    fun setSmartClipboardItemPinned(id: Long, isPinned: Boolean) {
        smartClipboardDao.setPinned(id, isPinned)
    }

    fun trimSmartClipboardHistory(limit: Int) {
        smartClipboardDao.trimTo(limit)
    }

    fun deleteExpiredSmartClipboardItems(expirationTimestamp: Long) {
        smartClipboardDao.deleteExpired(expirationTimestamp)
    }

    init {
        val database = getDatabase(context)
        sidebarAppsDao = database.sidebarAppsDao
        smartClipboardDao = database.smartClipboardDao
    }
}
