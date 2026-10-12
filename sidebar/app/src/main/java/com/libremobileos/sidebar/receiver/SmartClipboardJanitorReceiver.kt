package com.libremobileos.sidebar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.libremobileos.sidebar.service.SmartClipboardJanitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SmartClipboardJanitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmartClipboardJanitor.ACTION_SWEEP) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8000L) {
                    val janitor = SmartClipboardJanitor.create(context)
                    janitor.sweep()
                    janitor.schedule()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
