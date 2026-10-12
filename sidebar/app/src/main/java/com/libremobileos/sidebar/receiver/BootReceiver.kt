package com.libremobileos.sidebar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import com.libremobileos.sidebar.service.SidebarService
import com.libremobileos.sidebar.utils.Logger

class BootReceiver : BroadcastReceiver() {
    private val logger = Logger(TAG)
    companion object {
        private const val BOOT = "android.intent.action.BOOT_COMPLETED"
        private const val TAG = "BootReceiver"
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BOOT) {
            logger.d("Boot Completed")
            runCatching {
                context.startServiceAsUser(Intent(context, SidebarService::class.java), UserHandle(UserHandle.USER_CURRENT))
            }.onFailure { e ->
                logger.e("failed to start SidebarService on boot: ", e)
            }
        }
    }
}
