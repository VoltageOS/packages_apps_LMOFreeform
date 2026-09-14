package com.libremobileos.freeform.receiver

import android.app.ActivityOptions
import android.app.WindowConfiguration
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.provider.Settings
import android.view.Display

import com.libremobileos.freeform.LMOFreeform
import com.libremobileos.freeform.LMOFreeformServiceManager
import com.libremobileos.freeform.utils.Debug
import com.libremobileos.freeform.utils.Logger

import kotlin.math.roundToInt

/**
 * @author KindBrave
 * @since 2023/9/19
 */
class StartFreeformReceiver : BroadcastReceiver() {

    private val logger = Logger(TAG)

    companion object {
        private const val TAG = "StartFreeformReceiver"
        private const val PACKAGE_NAME = "com.libremobileos.sidebar"
        private const val ACTION = "com.libremobileos.freeform.START_FREEFORM"
        private const val SAVE_ACTION = "com.libremobileos.freeform.SAVE_GEOMETRY"
        private const val INITIAL_MAX_WIDTH = 1080
        private const val INITIAL_MAX_HEIGHT = 1560
        private const val GEOMETRY_PREFS = "freeform_geometry"

        fun getSavedGeometry(context: Context, packageName: String, activityName: String): Pair<Int, Int>? {
            val sp = context.getSharedPreferences(GEOMETRY_PREFS, Context.MODE_PRIVATE)
            val w = sp.getInt("$packageName/$activityName/w", -1)
            val h = sp.getInt("$packageName/$activityName/h", -1)
            return if (w > 0 && h > 0) w to h else null
        }

        fun saveGeometryForApp(context: Context, packageName: String, activityName: String, w: Int, h: Int) {
            context.getSharedPreferences(GEOMETRY_PREFS, Context.MODE_PRIVATE).edit()
                .putInt("$packageName/$activityName/w", w)
                .putInt("$packageName/$activityName/h", h)
                .apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SAVE_ACTION -> {
                val packageName = intent.getStringExtra("packageName")
                val activityName = intent.getStringExtra("activityName")
                val w = intent.getIntExtra("width", -1)
                val h = intent.getIntExtra("height", -1)
                if (packageName != null && activityName != null && w > 0 && h > 0) {
                    logger.d("saved geometry $packageName/$activityName ${w}x$h")
                    saveGeometryForApp(context, packageName, activityName, w, h)
                }
                return
            }
            ACTION -> {
            val isNativeFreeformEnabled = Settings.System.getInt(
                context.contentResolver,
                "freeform_launch_mode",
                1
            ) == 0
            val isSideBarBroadcast = 
                intent.getStringExtra("packageName").equals(PACKAGE_NAME)
            if (isNativeFreeformEnabled && !isSideBarBroadcast) {
                launchAppInNativeFreeform(context, intent)
            } else {
                launchAppInLMOFreeform(context, intent)
            }
            }
        }
    }

    private fun launchAppInLMOFreeform(context: Context, intent: Intent) {
        if (Debug.isDebug) logger.d("onReceive ${intent.extras}")
        val packageName = intent.getStringExtra("packageName")
        val activityName = intent.getStringExtra("activityName")
        val userId = intent.getIntExtra("userId", 0)
        val taskId = intent.getIntExtra("taskId", -1)

        if (packageName != null && activityName != null) {
            val sp = context.getSharedPreferences(LMOFreeform.CONFIG, Context.MODE_PRIVATE)
            val screenWidth = context.resources.displayMetrics.widthPixels
            val screenHeight = context.resources.displayMetrics.heightPixels
            val screenDensityDpi = context.resources.displayMetrics.densityDpi
            val saved = getSavedGeometry(context, packageName, activityName)
            val freeformWidth = (saved?.first
                ?: sp.getInt("freeform_width", (screenWidth * 0.85).roundToInt()))
                .coerceAtMost(INITIAL_MAX_WIDTH)
            val freeformHeight = (saved?.second
                ?: sp.getInt("freeform_height", (screenHeight * 0.6).roundToInt()))
                .coerceAtMost(INITIAL_MAX_HEIGHT)
            LMOFreeformServiceManager.createWindow(
                packageName,
                activityName,
                userId,
                taskId,
                freeformWidth,
                freeformHeight,
                sp.getInt("freeform_dpi", screenDensityDpi)
            )
        }
    }

    private fun launchAppInNativeFreeform(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("packageName") ?: return

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager

        val screenSize = Point()
        windowManager?.defaultDisplay?.getSize(screenSize)

        val centerX = screenSize.x / 2
        val centerY = screenSize.y / 2
        val width = (screenSize.x * 0.5).roundToInt()
        val height = (screenSize.y * 0.5).roundToInt()
        val launchBounds = Rect(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)

        val activityOptions = ActivityOptions.makeBasic().apply {
            setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM)
            setLaunchBounds(launchBounds)
            setTaskAlwaysOnTop(true)
        }

        val packageManager = context.packageManager
        val startAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startAppIntent?.let {
                context.startActivity(it, activityOptions.toBundle())
            }
        } catch (e: Exception) {
            logger.e("Error launching app in native freeform: $e")
        }
    }
}
