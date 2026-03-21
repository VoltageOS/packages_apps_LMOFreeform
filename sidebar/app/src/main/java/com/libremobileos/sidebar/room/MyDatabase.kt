package com.libremobileos.sidebar.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import java.util.concurrent.Executors
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * @author sunshine
 * @date 2021/1/31
 */
@Database(
    entities = [SidebarAppsEntity::class, SmartClipboardEntity::class],
    version = 8,
    exportSchema = false
)
abstract class MyDatabase : RoomDatabase() {
    abstract val sidebarAppsDao: SidebarAppsDao
    abstract val smartClipboardDao: SmartClipboardDao

    companion object {
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `SmartClipboardEntity`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `SmartClipboardEntity` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`text` TEXT, " +
                        "`fileName` TEXT, " +
                        "`imagePath` TEXT, " +
                        "`mimeType` TEXT, " +
                        "`contentHash` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`isPinned` INTEGER NOT NULL" +
                    ")"
                )
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `SmartClipboardEntity` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`text` TEXT, " +
                        "`imagePath` TEXT, " +
                        "`mimeType` TEXT, " +
                        "`contentHash` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL" +
                        ")"
                )
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE SmartClipboardEntity ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }
        private var database: MyDatabase? = null

        @Synchronized
        fun getDatabase(context: Context): MyDatabase {
            if (database == null) {
                database = Room.databaseBuilder(context.applicationContext, MyDatabase::class.java, "database.db")
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .allowMainThreadQueries()
                    .setQueryExecutor(Executors.newSingleThreadExecutor())
                    .setTransactionExecutor(Executors.newSingleThreadExecutor())
                    .build()
            }
            return database!!
        }
    }
}
