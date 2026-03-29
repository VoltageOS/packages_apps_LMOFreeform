package com.libremobileos.sidebar.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.UserHandle
import android.view.DragEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.ui.theme.SidebarTheme
import com.libremobileos.sidebar.utils.Logger

/**
 * @author KindBrave
 * @since 2023/9/26
 */
class SidebarView(
    private val context: Context,
    private val viewModel: ServiceViewModel,
    private val callback: Callback
) : SavedStateRegistryOwner {

    private var lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var composeView: View
    private var sidebarPositionX = 0
    private var isShowing = false
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val layoutParams = LayoutParams()
    private val logger = Logger(TAG)
    private val handler = Handler()

    private val sharedPrefs by lazy {
        context.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PACKAGE = "com.libremobileos.freeform"
        private const val ACTION = "com.libremobileos.freeform.START_FREEFORM"
        private const val TAG = "SidebarView"
    }

    init {
        savedStateRegistryController.performRestore(null)
    }

    private fun launchAppInFreeform(appInfo: AppInfo) {
        val intent = Intent(ACTION).apply {
            setPackage(PACKAGE)
            putExtra("packageName", appInfo.packageName)
            putExtra("activityName", appInfo.activityName)
            putExtra("userId", appInfo.userId)
        }
        context.sendBroadcastAsUser(intent, UserHandle(UserHandle.USER_CURRENT))
        removeView()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showView() {
        if (isShowing) return

        if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
            lifecycleRegistry = LifecycleRegistry(this)
        }

        initComposeView()

        layoutParams.apply {
            type = LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LayoutParams.FLAG_HARDWARE_ACCELERATED
            privateFlags = LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                    LayoutParams.PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            flags = LayoutParams.FLAG_NOT_FOCUSABLE or
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LayoutParams.FLAG_HARDWARE_ACCELERATED
        }

        updateSidebarPosition()
        composeView.translationX = sidebarPositionX * 1.0f * 200
        composeView.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    viewModel.supportsSmartClipboardContent(event.clipDescription)
                }
                DragEvent.ACTION_DROP -> {
                    if (!viewModel.supportsSmartClipboardContent(event.clipDescription)) {
                        return@setOnDragListener false
                    }
                    viewModel.addClipDataToSmartClipboard(
                        clipData = event.clipData,
                        enableIfNeeded = true,
                        showToast = true
                    )
                    true
                }
                DragEvent.ACTION_DRAG_ENTERED,
                DragEvent.ACTION_DRAG_LOCATION,
                DragEvent.ACTION_DRAG_EXITED,
                DragEvent.ACTION_DRAG_ENDED -> true
                else -> false
            }
        }

        handler.post {
            runCatching {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                windowManager.addView(composeView, layoutParams)
                composeView.animate().translationX(0f).setDuration(300).start()
                isShowing = true
            }.onFailure {
                logger.e("failed to add sidebar view: ", it)
            }
        }
    }

    fun removeView(force: Boolean = false) {
        if (!isShowing && !force) return

        logger.d("removeView")
        handler.post {
            runCatching {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                windowManager.removeViewImmediate(composeView)
                callback.onRemove()
                isShowing = false
            }.onFailure {
                logger.e("failed to remove sidebar view: $it")
            }
        }
    }

    fun updateSidebarPosition() {

        sidebarPositionX = sharedPrefs.getInt(SidebarService.SIDELINE_POSITION_X, 1)

        layoutParams.apply {
            width = LayoutParams.MATCH_PARENT
            height = LayoutParams.MATCH_PARENT
            x = 0
            y = 0
        }

        logger.d("updateSidebarPosition: posX=$sidebarPositionX (MATCH_PARENT window)")

        if (isShowing) {
            handler.post {
                runCatching {
                    windowManager.updateViewLayout(composeView, layoutParams)
                }.onFailure { e ->
                    logger.e("failed to updateViewLayout: ", e)
                }
            }
        }
    }

    private fun initComposeView() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@SidebarView)
            setViewTreeSavedStateRegistryOwner(this@SidebarView)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SidebarTheme {
                    SidebarComposeView(
                        viewModel = viewModel,
                        launchApp = { launchAppInFreeform(it) },
                        closeSidebar = { removeView() },
                    )
                }
            }
        }
    }

    interface Callback {
        fun onRemove()
    }
}
