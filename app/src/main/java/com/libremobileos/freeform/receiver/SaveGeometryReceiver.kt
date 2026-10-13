package com.libremobileos.freeform.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.libremobileos.freeform.utils.Logger

class SaveGeometryReceiver : BroadcastReceiver() {
    private val logger = Logger(TAG)

    companion object {
        private const val TAG = "SaveGeometryReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.libremobileos.freeform.SAVE_GEOMETRY") return
        val packageName = intent.getStringExtra("packageName")
        val activityName = intent.getStringExtra("activityName")
        val userId = intent.getIntExtra("userId", 0)
        val wf = intent.getFloatExtra("wf", -1f)
        val hf = intent.getFloatExtra("hf", -1f)
        val o = intent.getIntExtra("o", -1)
        if (packageName != null && activityName != null) {
            logger.d("saved geometry $userId $packageName/$activityName $wf x $hf o=$o")
            StartFreeformReceiver.saveGeometryForApp(context, userId, packageName, activityName, wf, hf, o)
        }
    }
}
