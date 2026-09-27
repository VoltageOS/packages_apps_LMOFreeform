package com.libremobileos.sidebar.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.receiver.SmartClipboardJanitorReceiver
import com.libremobileos.sidebar.room.DatabaseRepository
import java.io.File

class SmartClipboardJanitor(
    private val context: Context,
    private val repository: DatabaseRepository,
    private val prefs: SharedPreferences,
    private val clipboardDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun sweep() {
        val now = clock()
        val retentionMs = retentionMs(prefs)
        if (retentionMs > 0) {
            repository.deleteExpiredReturningPaths(now - retentionMs).forEach { deleteFile(it) }
        }
        repository.trimSmartClipboardHistory(MAX_UNPINNED_ITEMS).forEach { deleteFile(it) }
        repository.trimPinnedHistory(MAX_PINNED_ITEMS).forEach { deleteFile(it) }
        val active = repository.getAllImagePaths().toSet()
        clipboardDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in active) {
                runCatching { file.delete() }
            }
        }
    }

    suspend fun schedule() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val due = nextDueAt()
        val pi = pendingIntent()
        if (due == null) {
            am.cancel(pi)
            return
        }
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
        }.onFailure {
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
            }
        }
    }

    fun cancel() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent())
    }

    suspend fun nextDueAt(): Long? {
        val retentionMs = retentionMs(prefs)
        if (retentionMs <= 0) return null
        val oldest = repository.getOldestUnpinnedCreatedAt() ?: return null
        val now = clock()
        return maxOf(oldest + retentionMs, now + MIN_DELAY_MS)
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, SmartClipboardJanitorReceiver::class.java).apply {
            action = ACTION_SWEEP
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun deleteFile(path: String) {
        runCatching {
            val f = File(path)
            if (f.exists()) f.delete()
        }
    }

    companion object {
        const val ACTION_SWEEP = "com.libremobileos.sidebar.action.CLIPBOARD_JANITOR_SWEEP"
        const val MAX_UNPINNED_ITEMS = 12
        const val MAX_PINNED_ITEMS = 8
        const val MIN_DELAY_MS = 60_000L
        const val KEY_EXPIRATION = ServiceViewModel.KEY_CLIPBOARD_EXPIRATION_HOURS

        fun retentionMinutes(prefs: SharedPreferences): Int {
            return when (val v = prefs.getInt(KEY_EXPIRATION, 0)) {
                1 -> 60
                24 -> 1440
                168 -> 10080
                else -> v
            }
        }

        fun retentionMs(prefs: SharedPreferences): Long {
            val minutes = retentionMinutes(prefs)
            if (minutes <= 0) return 0L
            return minutes * 60_000L
        }

        fun create(ctx: Context, clock: () -> Long = System::currentTimeMillis): SmartClipboardJanitor {
            val app = ctx.applicationContext
            val prefs = app.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
            val repo = DatabaseRepository(app)
            val dir = File(app.filesDir, "smart_clipboard").apply { mkdirs() }
            return SmartClipboardJanitor(app, repo, prefs, dir, clock)
        }
    }
}
