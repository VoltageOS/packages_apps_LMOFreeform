package com.libremobileos.freeform.server.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.util.Slog
import android.view.Display
import android.view.DisplayInfo
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.InsetsFrameProvider
import android.view.IRotationWatcher
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.android.server.LocalServices
import com.android.server.wm.WindowManagerInternal
import com.libremobileos.freeform.ILMOFreeformDisplayCallback
import com.libremobileos.freeform.server.Debug.dlog
import com.libremobileos.freeform.server.LMOFreeformServiceHolder
import com.libremobileos.freeform.server.SystemServiceHolder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FreeformWindow(
    val handler: Handler,
    val context: Context,
    private val appConfig: AppConfig,
    val freeformConfig: FreeformConfig
): TextureView.SurfaceTextureListener, ILMOFreeformDisplayCallback.Stub(), View.OnTouchListener,
    WindowManagerInternal.DisplaySecureContentListener {

    var freeformTaskStackListener: FreeformTaskStackListener? = null
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val windowManagerInt = LocalServices.getService(WindowManagerInternal::class.java)
    val windowParams = WindowManager.LayoutParams()
    private val resourceHolder = RemoteResourceHolder(context, FREEFORM_PACKAGE)
    lateinit var freeformLayout: ViewGroup
    lateinit var freeformRootView: ViewGroup
    lateinit var freeformView: TextureView
    private lateinit var topBarView: View
    private var bottomBarView: View? = null
    private var optionsMenuView: View? = null
    private var optionsScrimView: View? = null
    private var optionsIcon: ImageView? = null
    private var navPillView: View? = null
    private var displayWm: WindowManager? = null
    private var chromeSampled = false
    private var chromeSampleAttempts = 0
    private var destroyed = false
    private var displaySurfaceTexture: SurfaceTexture? = null
    private var topInsetView: View? = null
    private var bottomInsetView: View? = null
    private val insetsOwner = Binder()
    private var displayId = Display.INVALID_DISPLAY
    private var backGestureEdge = BACK_GESTURE_EDGE_NONE
    private var backGestureStartX = 0f
    private var backGestureStartY = 0f
    private var backGestureTriggered = false
    var defaultDisplayWidth = context.resources.displayMetrics.widthPixels
    var defaultDisplayHeight = context.resources.displayMetrics.heightPixels
    var defaultDisplayRotation = context.display.rotation
    private val hangUpGestureListener = HangUpGestureListener(this)
    private val defaultDisplayInfo = DisplayInfo()
    private val destroyRunnable = Runnable { destroy("destroyRunnable", true) }
    
    private lateinit var appPackageName: String
    private var appIcon: Drawable? = null

    private val rotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            dlog(TAG, "onRotationChanged($rotation)")
            defaultDisplayWidth = context.resources.displayMetrics.widthPixels
            defaultDisplayHeight = context.resources.displayMetrics.heightPixels
            defaultDisplayRotation = context.display.rotation
            measureSize()
            handler.post {
                changeOrientation()
                if (freeformConfig.isHangUp) toHangUp()
                else makeSureFreeformInScreen()
            }
            measureScale()
            LMOFreeformServiceHolder.resizeFreeform(
                this@FreeformWindow,
                freeformConfig.freeformWidth,
                freeformConfig.freeformHeight,
                freeformConfig.densityDpi
            )
            freeformView?.surfaceTexture?.setDefaultBufferSize(
                freeformConfig.freeformWidth,
                freeformConfig.freeformHeight
            )
        }
    }

    companion object {
        private const val TAG = "LMOFreeform/FreeformWindow"
        private const val FREEFORM_PACKAGE = "com.libremobileos.freeform"
        private const val FREEFORM_LAYOUT = "view_freeform"
        private const val WINDOW_DESTROY_WAIT_MS = 10000L
        private const val TOP_INSET_DP = 18
        private const val BOTTOM_INSET_DP = 16
        private const val MENU_FADE_MS = 120L
        private const val BACK_GESTURE_EDGE_NONE = 0
        private const val BACK_GESTURE_EDGE_LEFT = 1
        private const val BACK_GESTURE_EDGE_RIGHT = 2
        private const val BACK_GESTURE_EDGE_WIDTH_DP = 24
        private const val BACK_GESTURE_TRIGGER_DISTANCE_DP = 64
        private const val BACK_GESTURE_VERTICAL_SLOP_DP = 48
    }

    init {
        if (LMOFreeformServiceHolder.ping()) {
            Slog.i(TAG, "FreeformWindow init")
            extractPackageInfo()
            populateFreeformConfig()
            handler.post { if (!addFreeformView()) destroy("init:addFreeform failed") }
        } else {
            destroy("init:service not running")
            // NOT RUNNING !!!
        }
    }

    override fun onDisplayPaused() {
        //NOT USED
    }

    override fun onDisplayResumed() {
        //NOT USED
    }

    override fun onDisplayStopped() {
        //NOT USED
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        dlog(TAG, "onSurfaceTextureAvailable width:$width height:$height")
        if (displayId < 0) {
            displaySurfaceTexture = surfaceTexture
            surfaceTexture.setDefaultBufferSize(freeformConfig.freeformWidth, freeformConfig.freeformHeight)
            LMOFreeformServiceHolder.createDisplay(freeformConfig, appConfig, Surface(surfaceTexture), this)
            handler.postDelayed({ updateChromeColors() }, 800L)
            return
        }
        val kept = displaySurfaceTexture
        if (kept != null && kept !== surfaceTexture && ::freeformView.isInitialized) {
            freeformView.setSurfaceTexture(kept)
            surfaceTexture.release()
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(freeformConfig.freeformWidth, freeformConfig.freeformHeight)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        return surfaceTexture !== displaySurfaceTexture
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        //NOT USED
    }

    override fun onDisplayAdd(displayId: Int) {
        Slog.i(TAG, "onDisplayAdd displayId=$displayId, $appConfig")
        handler.post {
            this.displayId = displayId
            runCatching { SystemServiceHolder.windowManager.setDisplayImePolicy(displayId, WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY) }
            addDisplayInsets(displayId)
            freeformTaskStackListener = FreeformTaskStackListener(displayId, this)
            SystemServiceHolder.activityTaskManager.registerTaskStackListener(freeformTaskStackListener)
            if (appConfig.taskId != -1) {
                dlog(TAG, "moving taskId=${appConfig.taskId} to freeform display")
                freeformTaskStackListener!!.taskId = appConfig.taskId
                runCatching {
                    // TODO: find a new way for this since getTaskDescription was removed in fwb commit a7cae90a991e
                    // if (SystemServiceHolder.activityTaskManager.getTaskDescription(appConfig.taskId) == null) {
                    //     throw Exception("stale task")
                    // }
                    SystemServiceHolder.activityTaskManager.moveRootTaskToDisplay(appConfig.taskId, displayId)
                }
                .onFailure { e ->
                    Slog.e(TAG, "failed to move task ${appConfig.taskId}: $e, fallback to startApp")
                    startApp()
                }
            } else if (appConfig.userId == -100) {
                if (appConfig.pendingIntent == null) destroy("onDisplayAdd:userId=-100, but pendingIntent is null")
                else {
                    LMOFreeformServiceHolder.startPendingIntent(appConfig.pendingIntent, displayId)
                }
            } else {
                startApp()
            }

        }
    }

    private fun startApp() {
        if (displayId == Display.INVALID_DISPLAY) {
            Slog.e(TAG, "cannot startApp: displayId not yet set!")
            return
        }
        if (LMOFreeformServiceHolder.startApp(context, appConfig, displayId).not())
            destroy("startApp failed")
    }

    override fun onDisplayHasSecureWindowOnScreenChanged(displayId: Int, hasSecureWindowOnScreen: Boolean) {
        if (displayId != this.displayId) return;
        dlog(TAG, "onDisplayHasSecureWindowOnScreenChanged: $hasSecureWindowOnScreen")
        windowParams.apply {
            flags = if (hasSecureWindowOnScreen) {
                flags or WindowManager.LayoutParams.FLAG_SECURE
            } else {
                flags xor WindowManager.LayoutParams.FLAG_SECURE
            }
        }
        handler.post {
            runCatching { windowManager.updateViewLayout(freeformLayout, windowParams) }
                .onFailure { Slog.e(TAG, "updateViewLayout failed: $it") }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (displayId == Display.INVALID_DISPLAY) {
            return true
        }

        if (updateBackGesture(event)) {
            return true
        }

        forwardTouch(event)
        return true
    }

    private fun forwardTouch(event: MotionEvent) {
        val transformedEvent = MotionEvent.obtain(event)
        try {
            if (freeformConfig.scale != 1.0f) {
                val transform = Matrix()
                transform.setScale(freeformConfig.scale, freeformConfig.scale)
                transformedEvent.transform(transform)
            }
            transformedEvent.source = InputDevice.SOURCE_TOUCHSCREEN
            LMOFreeformServiceHolder.touch(transformedEvent, displayId)
        } finally {
            transformedEvent.recycle()
        }
    }

    private fun forwardCancel(event: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(event)
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        try {
            if (freeformConfig.scale != 1.0f) {
                val transform = Matrix()
                transform.setScale(freeformConfig.scale, freeformConfig.scale)
                cancelEvent.transform(transform)
            }
            cancelEvent.source = InputDevice.SOURCE_TOUCHSCREEN
            LMOFreeformServiceHolder.touch(cancelEvent, displayId)
        } finally {
            cancelEvent.recycle()
        }
    }

    private fun updateBackGesture(event: MotionEvent): Boolean {
        val edgeWidth = dpToPx(BACK_GESTURE_EDGE_WIDTH_DP)
        val triggerDistance = dpToPx(BACK_GESTURE_TRIGGER_DISTANCE_DP)
        val verticalSlop = dpToPx(BACK_GESTURE_VERTICAL_SLOP_DP)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                backGestureStartX = event.x
                backGestureStartY = event.y
                backGestureTriggered = false
                backGestureEdge = when {
                    event.x <= edgeWidth -> BACK_GESTURE_EDGE_LEFT
                    event.x >= freeformView.width - edgeWidth -> BACK_GESTURE_EDGE_RIGHT
                    else -> BACK_GESTURE_EDGE_NONE
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (backGestureEdge == BACK_GESTURE_EDGE_NONE) {
                    return false
                }
                if (backGestureTriggered) {
                    return true
                }
                val dx = event.x - backGestureStartX
                val dy = kotlin.math.abs(event.y - backGestureStartY)
                val inwardDistance = when (backGestureEdge) {
                    BACK_GESTURE_EDGE_LEFT -> dx
                    BACK_GESTURE_EDGE_RIGHT -> -dx
                    else -> 0f
                }
                if (inwardDistance >= triggerDistance && dy <= verticalSlop) {
                    backGestureTriggered = true
                    forwardCancel(event)
                    LMOFreeformServiceHolder.back(displayId)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = backGestureTriggered
                backGestureEdge = BACK_GESTURE_EDGE_NONE
                backGestureTriggered = false
                return consumed
            }
        }
        return false
    }

    private fun updateSystemGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!::freeformView.isInitialized) return

        val edgeWidth = dpToPx(BACK_GESTURE_EDGE_WIDTH_DP).roundToInt()
        val width = freeformView.width
        val height = freeformView.height
        if (width <= 0 || height <= 0) return

        freeformView.systemGestureExclusionRects = listOf(
            Rect(0, 0, min(edgeWidth, width), height),
            Rect(max(0, width - edgeWidth), 0, width, height)
        )
    }

    private fun dpToPx(dp: Int): Float = dp * context.resources.displayMetrics.density

    private fun toggleOptionsMenu() {
        val menu = optionsMenuView ?: return
        if (menu.visibility == View.VISIBLE) hideOptionsMenu() else showOptionsMenu()
    }

    private fun showOptionsMenu() {
        fadeIn(optionsScrimView)
        fadeIn(optionsMenuView)
    }

    private fun hideOptionsMenu() {
        fadeOut(optionsMenuView)
        fadeOut(optionsScrimView)
    }

    private fun fadeIn(view: View?) {
        view ?: return
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate().alpha(1f).setDuration(MENU_FADE_MS).start()
    }

    private fun fadeOut(view: View?) {
        view ?: return
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(MENU_FADE_MS).withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f
        }.start()
    }

    private fun snapToEdges() {
        if (freeformConfig.isHangUp) return
        val screenW = defaultDisplayWidth
        val winW = freeformConfig.width
        if (winW <= 0 || winW >= screenW) return
        if (windowParams.x <= -(screenW / 2) || windowParams.x >= screenW / 2) return
        val threshold = dpToPx(40).roundToInt()
        val leftEdge = screenW / 2 + windowParams.x - winW / 2
        val rightEdge = screenW / 2 + windowParams.x + winW / 2
        val snapLeftX = -((screenW - winW) / 2)
        val snapRightX = (screenW - winW) / 2
        if (leftEdge <= threshold && windowParams.x != snapLeftX) {
            FreeformAnimation.moveInScreenAnimator(windowParams.x, snapLeftX, 200, true, this)
        } else if (rightEdge >= screenW - threshold && windowParams.x != snapRightX) {
            FreeformAnimation.moveInScreenAnimator(windowParams.x, snapRightX, 200, true, this)
        }
    }

    /**
     * get freeform screen dimen / freeform view dimen
     */
    private fun populateFreeformConfig() {
        measureSize()
        measureScale()
        context.display.getDisplayInfo(defaultDisplayInfo)
        freeformConfig.apply {
            refreshRate = defaultDisplayInfo.refreshRate
            presentationDeadlineNanos = defaultDisplayInfo.presentationDeadlineNanos
            dlog(TAG, "populateFreeformConfig: $this")
        }
    }

    fun measureSize() {
        val isPortrait = defaultDisplayRotation == Surface.ROTATION_0 ||
                defaultDisplayRotation == Surface.ROTATION_180
        freeformConfig.apply {
            height = (defaultDisplayHeight * (if (isPortrait) 0.5 else 0.6)).roundToInt()
            width = if (isPortrait) {
                (defaultDisplayWidth * 0.75).roundToInt()
            } else {
                // preserving the aspect ratio
                defaultDisplayHeight * defaultDisplayHeight / defaultDisplayWidth
            }
            dlog(TAG, "measureSize: isPortrait=$isPortrait width=$width height=$height")
        }
    }

    fun measureScale() {
        freeformConfig.apply {
            val widthScale = min(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / min(width, height)
            val heightScale = max(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / max(width, height)
            scale = min(widthScale, heightScale)
            freeformWidth = (width * scale).roundToInt()
            freeformHeight = (height * scale).roundToInt()
            dlog(TAG, "measureScale: $scale freeformWidth=$freeformWidth freeformHeight=$freeformHeight")
        }
    }

    /**
     * Called in system handler
     */
    @SuppressLint("WrongConstant")
    private fun addFreeformView(): Boolean {
        dlog(TAG, "addFreeformView")
        val tmpFreeformLayout = resourceHolder.getLayout(FREEFORM_LAYOUT)!! ?: return false
        freeformLayout = tmpFreeformLayout
        freeformRootView = resourceHolder.getLayoutChildViewByTag<FrameLayout>(freeformLayout, "freeform_root") ?: return false
        topBarView = resourceHolder.getLayoutChildViewByTag(freeformLayout, "topBarView") ?: return false
        bottomBarView = resourceHolder.getLayoutChildViewByTag(freeformLayout, "bottomBarView")
        val topBarMoveListener = MoveTouchListener(this)
        val maximizeListener = MaximizeClickListener(this)
        val topBarTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                handler.post { maximizeListener.onClick(topBarView) }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handler.post { handleHangUp() }
            }
        })
        val topBarTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var topBarDownX = 0f
        var topBarDownY = 0f
        var topBarDragging = false
        topBarView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    topBarDownX = event.rawX
                    topBarDownY = event.rawY
                    topBarDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!topBarDragging && (kotlin.math.abs(event.rawX - topBarDownX) > topBarTouchSlop
                                || kotlin.math.abs(event.rawY - topBarDownY) > topBarTouchSlop)) {
                        topBarDragging = true
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        topBarTapDetector.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
            }
            if (!topBarDragging) topBarTapDetector.onTouchEvent(event)
            val handled = topBarMoveListener.onTouch(v, event)
            if (event.actionMasked == MotionEvent.ACTION_UP) snapToEdges()
            handled
        }

        val optionsView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "optionsView")
        val optionsMenu = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "optionsMenu")
        val optionsScrim = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "optionsScrim")
        val menuFullscreen = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "menuFullscreen")
        val menuMinimize = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "menuMinimize")
        val menuClose = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "menuClose")
        val leftScaleView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "leftScaleView")
        val rightScaleView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "rightScaleView")
        navPillView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "navPill")
        val pillScaleView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "pillScaleView")
        if (null == optionsView || null == optionsMenu || null == optionsScrim
                || null == menuFullscreen || null == menuMinimize || null == menuClose
                || null == leftScaleView || null == rightScaleView) {
            Slog.e(TAG, "freeform chrome view is null")
            destroy("addFreeformView:freeform chrome view is null")
            return false
        }
        optionsMenuView = optionsMenu
        optionsScrimView = optionsScrim
        optionsView.setOnClickListener { toggleOptionsMenu() }
        optionsIcon = optionsView as? ImageView
        optionsIcon?.imageTintList = ColorStateList.valueOf(Color.WHITE)
        optionsScrim.setOnClickListener { hideOptionsMenu() }
        menuFullscreen.setOnClickListener {
            hideOptionsMenu()
            maximizeListener.onClick(it)
        }
        menuMinimize.setOnClickListener {
            hideOptionsMenu()
            handler.post { handleHangUp() }
        }
        menuClose.setOnClickListener {
            hideOptionsMenu()
            close()
        }
        leftScaleView.setOnTouchListener(ScaleTouchListener(this, false, uniform = true))
        rightScaleView.visibility = View.GONE
        pillScaleView?.setOnTouchListener(ScaleTouchListener(this, uniform = true, useHorizontal = false))

        freeformView = FreeformTextureView(context).apply {
            setOnTouchListener(this@FreeformWindow)
            surfaceTextureListener = this@FreeformWindow
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSystemGestureExclusion() }
        }
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = freeformConfig.width
            height = freeformConfig.height
        }
        freeformRootView.addView(freeformView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        windowParams.apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            privateFlags = privateFlags or
                    WindowManager.LayoutParams.PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
        }
        runCatching {
            windowManager.addView(freeformLayout, windowParams)
            updateSystemGestureExclusion()
            SystemServiceHolder.windowManager.watchRotation(rotationWatcher, Display.DEFAULT_DISPLAY)
            windowManagerInt.registerDisplaySecureContentListener(this)
        }.onFailure {
            Slog.e(TAG, "addView failed: $it")
            return false
        }
        return true
    }

    private fun scanTint(bmp: Bitmap, fromTop: Boolean): Int? {
        val w = bmp.width
        val h = bmp.height
        val x0 = w / 4
        val x1 = w - w / 4 - 1
        val rowWidth = x1 - x0 + 1
        val need = (rowWidth / 2).coerceAtLeast(1)
        val row = IntArray(rowWidth)
        val ys = if (fromTop) 0 until h else h - 1 downTo 0
        for (y in ys) {
            bmp.getPixels(row, 0, rowWidth, x0, y, rowWidth, 1)
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            for (c in row) {
                if (Color.alpha(c) < 128) continue
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                n++
            }
            if (n >= need) {
                val lum = Color.luminance(Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()))
                return if (lum < 0.5f) Color.WHITE else Color.BLACK
            }
        }
        return null
    }

    private fun updateChromeColors() {
        handler.post {
            if (chromeSampled || destroyed) return@post
            if (!::freeformView.isInitialized) { retryChromeSample(); return@post }
            val tv = freeformView
            if (!tv.isAvailable) { retryChromeSample(); return@post }
            val bmp = runCatching { tv.getBitmap(32, 32) }.getOrNull()
            if (bmp == null) { retryChromeSample(); return@post }
            val topTint = scanTint(bmp, true)
            val bottomTint = scanTint(bmp, false)
            bmp.recycle()
            if (topTint == null && bottomTint == null) { retryChromeSample(); return@post }
            chromeSampled = true
            optionsIcon?.imageTintList = ColorStateList.valueOf(topTint ?: Color.WHITE)
            navPillView?.backgroundTintList = ColorStateList.valueOf(bottomTint ?: topTint ?: Color.WHITE)
            dlog(TAG, "chrome tint top=$topTint bottom=$bottomTint")
        }
    }

    private fun retryChromeSample() {
        if (destroyed || chromeSampleAttempts >= 6) return
        chromeSampleAttempts++
        handler.postDelayed({ updateChromeColors() }, 500L)
    }

    fun requestChromeResample() {
        if (destroyed) return
        chromeSampled = false
        chromeSampleAttempts = 0
        handler.postDelayed({ updateChromeColors() }, 500L)
    }

    private fun addDisplayInsets(displayId: Int) {
        if (topInsetView != null || bottomInsetView != null) removeDisplayInsets()
        val dm = context.getSystemService(DisplayManager::class.java) ?: return
        val display = dm.getDisplay(displayId) ?: return
        val dctx = context.createDisplayContext(display)
        val wm = dctx.getSystemService(WindowManager::class.java) ?: return
        displayWm = wm
        val topPx = (dpToPx(TOP_INSET_DP) * freeformConfig.scale).roundToInt()
        val bottomPx = (dpToPx(BOTTOM_INSET_DP) * freeformConfig.scale).roundToInt()
        topInsetView = addInsetProvider(dctx, wm, 0, WindowInsets.Type.statusBars(), Gravity.TOP, Insets.of(0, topPx, 0, 0))
        bottomInsetView = addInsetProvider(dctx, wm, 1, WindowInsets.Type.navigationBars(), Gravity.BOTTOM, Insets.of(0, 0, 0, bottomPx))
    }

    private fun addInsetProvider(dctx: Context, wm: WindowManager, index: Int, type: Int, gravity: Int, insets: Insets): View? {
        val view = View(dctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (gravity == Gravity.TOP) insets.top else insets.bottom,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = gravity
        lp.setFitInsetsTypes(0)
        lp.providedInsets = arrayOf(
            InsetsFrameProvider(insetsOwner, index, type).setInsetsSize(insets)
        )
        return runCatching {
            wm.addView(view, lp)
            view
        }.onFailure { Slog.e(TAG, "addInsetProvider failed: $it") }.getOrNull()
    }

    private fun removeDisplayInsets() {
        val wm = displayWm ?: return
        topInsetView?.let { runCatching { wm.removeViewImmediate(it) } }
        bottomInsetView?.let { runCatching { wm.removeViewImmediate(it) } }
        topInsetView = null
        bottomInsetView = null
        displayWm = null
    }

    fun updateDisplayInsets() {
        if (destroyed || displayId == Display.INVALID_DISPLAY) return
        handler.post {
            if (destroyed) return@post
            addDisplayInsets(displayId)
        }
    }

    /**
     * Called in system handler
     */
    @SuppressLint("ClickableViewAccessibility")
    fun handleHangUp() {
        hideOptionsMenu()
        if (freeformConfig.isHangUp) {
            windowParams.apply {
                x = freeformConfig.notInHangUpX
                y = freeformConfig.notInHangUpY
                flags = flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }
            freeformRootView.layoutParams.apply {
                width = freeformConfig.width
                height = freeformConfig.height
            }
            windowManager.updateViewLayout(freeformLayout, windowParams)
            topBarView.visibility = View.VISIBLE
            bottomBarView?.visibility = View.VISIBLE
            freeformConfig.isHangUp = false
            freeformView.setOnTouchListener(this)
        } else {
            freeformConfig.notInHangUpX = windowParams.x
            freeformConfig.notInHangUpY = windowParams.y
            toHangUp()
            topBarView.visibility = View.GONE
            bottomBarView?.visibility = View.GONE
            freeformConfig.isHangUp = true
            val gestureDetector = GestureDetector(context, hangUpGestureListener)
            freeformView.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP) makeSureFreeformInScreen()
                true
            }
        }
    }

    /**
     * Called in system handler
     */
    fun toHangUp() {
        windowParams.apply {
            x = (defaultDisplayWidth / 2 - freeformConfig.hangUpWidth / 2)
            y = -(defaultDisplayHeight / 2 - freeformConfig.hangUpHeight / 2)
            flags = flags xor WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        }
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = freeformConfig.hangUpWidth
            height = freeformConfig.hangUpHeight
        }
        runCatching { windowManager.updateViewLayout(freeformLayout, windowParams) }.onFailure { Slog.e(TAG, "$it") }
    }

    /**
     * Called in uiHandler
     */
    fun makeSureFreeformInScreen() {
        if (!freeformConfig.isHangUp) {
            val maxWidth = defaultDisplayWidth
            val maxHeight = (defaultDisplayHeight * 0.9).roundToInt()
            if (freeformRootView.layoutParams.width > maxWidth || freeformRootView.layoutParams.height > maxHeight) {
                freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
                    width = min(freeformRootView.width, maxWidth)
                    height = min(freeformRootView.height, maxHeight)
                }
            }
        }
        if (windowParams.x < -(defaultDisplayWidth / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.x, -(defaultDisplayWidth / 2), 300, true, this)
        else if (windowParams.x > (defaultDisplayWidth / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.x, (defaultDisplayWidth / 2), 300, true, this)
        if (windowParams.y < -(defaultDisplayHeight / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.y, -(defaultDisplayHeight / 2), 300, false, this)
        else if (windowParams.y > (defaultDisplayHeight / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.y, (defaultDisplayHeight / 2), 300, false, this)
    }

    /**
     * Change freeform orientation
     * Called in system handler
     */
    fun changeOrientation() {
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = if (freeformConfig.isHangUp) freeformConfig.hangUpWidth else freeformConfig.width
            height = if (freeformConfig.isHangUp) freeformConfig.hangUpHeight else freeformConfig.height
        }
    }

    fun getFreeformId(): String {
        return "${appConfig.packageName},${appConfig.activityName},${appConfig.userId}"
    }

    fun close() {
        dlog(TAG, "close()")
        runCatching {
            SystemServiceHolder.activityTaskManager.removeTask(freeformTaskStackListener!!.taskId)
            removeView()
        }.onFailure { exception ->
            Slog.e(TAG, "removeTask failed: ", exception)
            destroy("window.close() fallback")
        }
    }

    fun removeView(runDestroy: Boolean = true) {
        dlog(TAG, "removeView($runDestroy)")
        handler.removeCallbacks(destroyRunnable)
        handler.post {
            runCatching {
                windowManager.removeViewImmediate(freeformLayout)
                dlog(TAG, "removeView success")
            }.onFailure { exception ->
                Slog.e(TAG, "removeView failed $exception")
            }
        }
        // wait for onTaskRemoved(), but take it into our own hands in case its never triggered.
        if (runDestroy)
            handler.postDelayed(destroyRunnable, WINDOW_DESTROY_WAIT_MS)
    }

    fun destroy(callReason: String, shouldRemoveTask: Boolean = false) {
        if (destroyed) return
        destroyed = true
        Slog.i(TAG, "destroy ${getFreeformId()}, displayId=$displayId callReason: $callReason")
        removeView(false)
        handler.removeCallbacks(destroyRunnable)
        SystemServiceHolder.activityTaskManager.unregisterTaskStackListener(freeformTaskStackListener)
        SystemServiceHolder.windowManager.removeRotationWatcher(rotationWatcher)
        LMOFreeformServiceHolder.releaseFreeform(this)
        displaySurfaceTexture?.release()
        displaySurfaceTexture = null
        FreeformWindowManager.removeWindow(getFreeformId())
        windowManagerInt.unregisterDisplaySecureContentListener(this)
        handler.post { removeDisplayInsets() }
        freeformTaskStackListener!!.taskId.let {
            if (it != -1 && shouldRemoveTask) {
                Slog.i(TAG, "destroy: remove taskId $it again")
                runCatching { SystemServiceHolder.activityTaskManager.removeTask(it) }
            }
        }
    }
    
    private fun extractPackageInfo() {
        try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(appConfig.packageName, 0)
            appPackageName = pm.getApplicationLabel(ai).toString()
            appIcon = pm.getApplicationIcon(ai)
        } catch (e: Exception) {
            Slog.e(TAG, "Failed to retrieve app info: ${e.message}")
            appPackageName = ""
            appIcon = null
        }
    }
}