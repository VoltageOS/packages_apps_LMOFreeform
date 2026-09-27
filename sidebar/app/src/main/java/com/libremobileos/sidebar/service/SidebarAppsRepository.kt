package com.libremobileos.sidebar.service

import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.room.DatabaseRepository
import com.libremobileos.sidebar.room.SidebarAppsEntity
import kotlinx.coroutines.flow.Flow

class SidebarAppsRepository(private val repository: DatabaseRepository) {
    fun observe(): Flow<List<SidebarAppsEntity>?> = repository.getAllSidebarAppsByFlow()

    suspend fun all(): List<SidebarAppsEntity>? = repository.getAllSidebarWithoutLiveData()

    suspend fun remove(packageName: String, activityName: String, userId: Int) {
        repository.deleteSidebarApp(packageName, activityName, userId)
    }

    suspend fun cleanupInvalid(resolve: (SidebarAppsEntity) -> AppInfo) {
        val apps = all() ?: return
        apps.forEach { entity ->
            runCatching { resolve(entity) }.onFailure {
                repository.deleteSidebarApp(entity.packageName, entity.activityName, entity.userId)
            }
        }
    }
}
