package com.libremobileos.sidebar.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * @author sunshine
 * @date 2021/1/31
 */
@Dao
interface SidebarAppsDao {

    @Query("INSERT INTO SidebarAppsEntity(packageName, activityName, userId) VALUES(:packageName, :activityName, :userId)")
    suspend fun insert(packageName: String, activityName: String, userId: Int)

    @Query("DELETE FROM SidebarAppsEntity WHERE packageName = :packageName and activityName = :activityName and userId = :userId")
    suspend fun delete(packageName: String, activityName: String, userId: Int)

    @Query("SELECT * FROM SidebarAppsEntity")
    fun getAll() : LiveData<List<SidebarAppsEntity>?>

    @Query("SELECT * FROM SidebarAppsEntity")
    fun getAllByFlow() : Flow<List<SidebarAppsEntity>?>

    @Query("SELECT packageName FROM SidebarAppsEntity")
    fun getAllName() : LiveData<List<String>?>

    @Query("SELECT * FROM SidebarAppsEntity")
    suspend fun getAllWithoutLiveData() : List<SidebarAppsEntity>?

    @Query("SELECT COUNT(*) FROM SidebarAppsEntity")
    suspend fun getCount(): Int

    @Query("DELETE FROM SidebarAppsEntity")
    suspend fun deleteAll()

    @Delete
    suspend fun deleteList(sidebarAppsEntityList: List<SidebarAppsEntity>)

    @Update
    suspend fun update(entity: SidebarAppsEntity)
}
