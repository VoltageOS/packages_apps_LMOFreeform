package com.libremobileos.freeform.server.ui

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import android.os.UserHandle
import android.util.Slog
import android.view.Choreographer
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
import com.android.internal.inputmethod.SoftInputShowHideReason
import com.android.server.LocalServices
import com.android.server.inputmethod.InputMethodManagerInternal
import com.android.server.wm.WindowManagerInternal
import com.libremobileos.freeform.ILMOFreeformDisplayCallback
import com.libremobileos.freeform.server.Debug.dlog
import com.libremobileos.freeform.server.LMOFreeformServiceHolder
import com.libremobileos.freeform.server.SystemServiceHolder
import com.libremobileos.freeform.server.ui.gesture.FreeformGestureRouter
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
    lateinit var freeformView: FreeformTextureView
    private lateinit var topBarView: View
    private var bottomBarView: View? = null
    private var optionsMenuView: View? = null
    private var optionsScrimView: View? = null
    private var optionsIcon: ImageView? = null
    private var navPillView: View? = null
    private var bubbleView: ImageView? = null
    private var displayWm: WindowManager? = null
    private var chromeSampled = false
    private var chromeSampleAttempts = 0
    private var destroyed = false
    private var displaySurfaceTexture: SurfaceTexture? = null
    private var topInsetView: View? = null
    private var bottomInsetView: View? = null
    private val insetsOwner = Binder()
    private var displayId = Display.INVALID_DISPLAY
    private var gestureRouter: FreeformGestureRouter? = null

    private fun router(): FreeformGestureRouter {
        var r = gestureRouter
        if (r == null) {
            r = FreeformGestureRouter(context, object : FreeformGestureRouter.Callbacks {
                override fun surfaceWidth(): Int =
                    if (::freeformView.isInitialized) freeformView.width else 0
                override fun isAlive(): Boolean = !destroyed && displayId != Display.INVALID_DISPLAY
                override fun onDownFocus() = focusFreeformTask()
                override fun forward(event: MotionEvent) = forwardTouch(event)
                override fun fireBack() = LMOFreeformServiceHolder.back(displayId)
                override fun haptic(feedbackConstant: Int) {
                    runCatching {
                        if (::freeformView.isInitialized) freeformView.performHapticFeedback(feedbackConstant)
                    }
                }
            })
            gestureRouter = r
        }
        return r
    }
    var defaultDisplayWidth = context.resources.displayMetrics.widthPixels
    var defaultDisplayHeight = context.resources.displayMetrics.heightPixels
    var defaultDisplayRotation = context.display.rotation
    private val hangUpGestureListener = HangUpGestureListener(this)
    private val defaultDisplayInfo = DisplayInfo()
    private val destroyRunnable = Runnable { destroy("destroyRunnable", true) }
    
    private lateinit var appPackageName: String
    private var appIcon: Drawable? = null
    private var userResized = false

    private var pendingX = 0
    private var pendingY = 0
    private var framePending = false
    private var geometryAnimator: Animator? = null

    private val frameCallback = Choreographer.FrameCallback {
        framePending = false
        if (destroyed) return@FrameCallback
        runCatching {
            windowManager.updateViewLayout(freeformLayout, windowParams.apply { x = pendingX; y = pendingY })
        }.onFailure { Slog.w(TAG, "updateViewLayout: $it") }
    }

    fun requestMove(x: Int, y: Int) {
        pendingX = x; pendingY = y
        if (framePending) return
        framePending = true
        runCatching { Choreographer.getInstance().postFrameCallback(frameCallback) }
            .onFailure {
                framePending = false
                runCatching {
                    windowManager.updateViewLayout(freeformLayout, windowParams.apply {
                        this.x = pendingX; this.y = pendingY
                    })
                }
            }
    }

    fun cancelGeometryAnimations() { geometryAnimator?.cancel(); geometryAnimator = null }

    fun animateTo(x: Int? = null, y: Int? = null, dur: Long = 200) {
        if (!::freeformLayout.isInitialized || destroyed) return
        cancelGeometryAnimations()
        val startX = windowParams.x
        val startY = windowParams.y
        val endX = x ?: startX
        val endY = y ?: startY
        if (startX == endX && startY == endY) return
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            addUpdateListener {
                val f = it.animatedValue as Float
                runCatching {
                    windowManager.updateViewLayout(freeformLayout, windowParams.apply {
                        this.x = (startX + (endX - startX) * f).roundToInt()
                        this.y = (startY + (endY - startY) * f).roundToInt()
                    })
                }.onFailure { e -> Slog.w(TAG, "animateTo failed: $e") }
            }
        }
        geometryAnimator = animator
        animator.start()
    }

    private fun minWidthPx(): Int = (140 * context.resources.displayMetrics.density).roundToInt()
    private fun minHeightPx(): Int = (200 * context.resources.displayMetrics.density).roundToInt()
    private fun maxWidthPx(): Int = (defaultDisplayWidth * 0.8).roundToInt()
    private fun maxHeightPx(): Int = (defaultDisplayHeight * 0.8).roundToInt()

    fun exceedsMax(w: Int, h: Int): Boolean = w > maxWidthPx() || h > maxHeightPx()

    fun requestResize(w: Int, h: Int) {
        if (!::freeformRootView.isInitialized || destroyed) return
        val clampedW = w.coerceIn(minWidthPx(), maxWidthPx())
        val clampedH = h.coerceIn(minHeightPx(), maxHeightPx())
        val lp = freeformRootView.layoutParams ?: return
        if (lp.width == clampedW && lp.height == clampedH) return
        freeformRootView.layoutParams = lp.apply { width = clampedW; height = clampedH }
    }

    fun commitGeometry(w: Int, h: Int, enforceMax: Boolean = true) {
        if (destroyed) return
        val clampedW = if (enforceMax) w.coerceIn(minWidthPx(), maxWidthPx())
            else w.coerceAtLeast(minWidthPx())
        val clampedH = if (enforceMax) h.coerceIn(minHeightPx(), maxHeightPx())
            else h.coerceAtLeast(minHeightPx())
        freeformConfig.width = clampedW
        freeformConfig.height = clampedH
        userResized = true
        measureScale()
        if (::freeformRootView.isInitialized) {
            runCatching {
                freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
                    width = clampedW; height = clampedH
                }
            }.onFailure { Slog.w(TAG, "commitGeometry layout failed: $it") }
        }
        runCatching {
            LMOFreeformServiceHolder.resizeFreeform(
                this, freeformConfig.freeformWidth, freeformConfig.freeformHeight, freeformConfig.densityDpi)
        }.onFailure { Slog.w(TAG, "commitGeometry resizeFreeform failed: $it") }
        if (::freeformView.isInitialized) {
            runCatching {
                freeformView.surfaceTexture?.setDefaultBufferSize(
                    freeformConfig.freeformWidth, freeformConfig.freeformHeight)
            }.onFailure { Slog.w(TAG, "commitGeometry buffer failed: $it") }
        }
        updateDisplayInsets()
        handler.post { updateSystemGestureExclusion() }
    }

    fun commitResize() {
        if (!::freeformRootView.isInitialized) return
        val lp = freeformRootView.layoutParams ?: return
        commitGeometry(lp.width, lp.height)
        persistGeometry(lp.width, lp.height)
    }

    private fun persistGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (appConfig.userId < 0) return
        runCatching {
            val intent = Intent("com.libremobileos.freeform.SAVE_GEOMETRY").apply {
                setPackage(FREEFORM_PACKAGE)
                putExtra("packageName", appConfig.packageName)
                putExtra("activityName", appConfig.activityName)
                putExtra("width", w)
                putExtra("height", h)
            }
            context.sendBroadcastAsUser(intent, UserHandle.of(appConfig.userId))
            Slog.i(TAG, "persistGeometry ${appConfig.packageName}/${appConfig.activityName} ${w}x$h")
        }.onFailure { Slog.w(TAG, "persistGeometry failed: $it") }
    }

    private val rotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            dlog(TAG, "onRotationChanged($rotation)")
            handler.post {
                if (destroyed) return@post
                runCatching {
                    defaultDisplayWidth = context.resources.displayMetrics.widthPixels
                    defaultDisplayHeight = context.resources.displayMetrics.heightPixels
                    defaultDisplayRotation = context.display.rotation
                    measureSize()
                    changeOrientation()
                    if (freeformConfig.isHangUp) toHangUp()
                    else {
                        commitGeometry(freeformConfig.width, freeformConfig.height)
                        makeSureFreeformInScreen()
                    }
                }.onFailure { Slog.w(TAG, "onRotationChanged failed: $it") }
            }
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
        private const val CHROME_TOUCH_EXTRA_DP = 12
        private const val BUBBLE_DP = 72
        private const val CARD_RADIUS_DP = 16
    }

    init {
        if (LMOFreeformServiceHolder.ping()) {
            Slog.i(TAG, "FreeformWindow init")
            extractPackageInfo()
            if (freeformConfig.width > 0 && freeformConfig.height > 0) userResized = true
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
            runCatching {
                SystemServiceHolder.windowManager.setDisplayImePolicy(
                    displayId, WindowManager.DISPLAY_IME_POLICY_LOCAL)
            }.onFailure { Slog.e(TAG, "setDisplayImePolicy LOCAL failed: $it") }
            addDisplayInsets(displayId)
            freeformTaskStackListener = FreeformTaskStackListener(displayId, this)
            runCatching {
                SystemServiceHolder.activityTaskManager.registerTaskStackListener(freeformTaskStackListener)
            }.onFailure { Slog.e(TAG, "registerTaskStackListener failed: $it") }
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
        setWindowFlag(WindowManager.LayoutParams.FLAG_SECURE, hasSecureWindowOnScreen)
        handler.post {
            runCatching { windowManager.updateViewLayout(freeformLayout, windowParams) }
                .onFailure { Slog.e(TAG, "updateViewLayout failed: $it") }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (displayId == Display.INVALID_DISPLAY || destroyed) {
            return true
        }
        return router().onSurfaceTouch(view, event)
    }

    private var lastFocusTaskId = -1

    private fun focusFreeformTask() {
        val taskId = taskIdOrNull ?: return
        if (taskId == lastFocusTaskId) return
        lastFocusTaskId = taskId
        runCatching { SystemServiceHolder.activityTaskManager.setFocusedTask(taskId) }
            .onFailure {
                lastFocusTaskId = -1
                dlog(TAG, "setFocusedTask failed: $it")
            }
    }

    private fun hideLocalIme() {
        val did = displayId
        if (did == Display.INVALID_DISPLAY) return
        runCatching {
            val imm = LocalServices.getService(InputMethodManagerInternal::class.java)
            if (imm != null) {
                imm.hideInputMethod(SoftInputShowHideReason.HIDE_CLOSE_CURRENT_SESSION, did)
            } else {
                LMOFreeformServiceHolder.back(did)
            }
        }.onFailure { Slog.w(TAG, "hideLocalIme failed: $it") }
    }

    var typeModeEnabled = true
    private var preTypeGeometry: Triple<Int, Int, Int>? = null

    fun onLocalImeVisibilityChanged(visible: Boolean) = handler.post {
        if (destroyed || freeformConfig.isHangUp || !typeModeEnabled) return@post
        if (visible && preTypeGeometry == null) {
            preTypeGeometry = Triple(freeformConfig.width, freeformConfig.height, windowParams.y)
            val w = min(defaultDisplayWidth, (defaultDisplayWidth * 0.95f).roundToInt())
            val h = min((defaultDisplayHeight * 0.85f).roundToInt(), (freeformConfig.height * 1.6f).roundToInt())
            commitGeometry(w, h, enforceMax = false)
            animateTo(y = -(defaultDisplayHeight - h) / 4)
        } else if (!visible) {
            preTypeGeometry?.let { (w, h, y) ->
                commitGeometry(w, h)
                animateTo(y = y)
            }
            preTypeGeometry = null
        }
    }

    private fun forwardTouch(event: MotionEvent) {
        forward(event)
    }

    private fun forward(event: MotionEvent, actionOverride: Int? = null) {
        if (!::freeformView.isInitialized) return
        val vw = freeformView.width
        val vh = freeformView.height
        if (vw <= 0 || vh <= 0) return
        val sx = freeformConfig.freeformWidth.toFloat() / vw
        val sy = freeformConfig.freeformHeight.toFloat() / vh
        val copy = MotionEvent.obtain(event)
        try {
            if (actionOverride != null) copy.action = actionOverride
            if (sx != 1f || sy != 1f) copy.transform(Matrix().apply { setScale(sx, sy) })
            if (copy.source == InputDevice.SOURCE_UNKNOWN) copy.source = InputDevice.SOURCE_TOUCHSCREEN
            LMOFreeformServiceHolder.touch(copy, displayId)
        } finally {
            copy.recycle()
        }
    }

    private fun forwardCancel(event: MotionEvent) {
        forward(event, MotionEvent.ACTION_CANCEL)
    }

    private fun updateSystemGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!::freeformLayout.isInitialized) return

        val w = freeformLayout.width
        val h = freeformLayout.height
        if (w <= 0 || h <= 0) return

        runCatching { freeformLayout.systemGestureExclusionRects = listOf(Rect(0, 0, w, h)) }
            .onFailure { Slog.w(TAG, "updateSystemGestureExclusion failed: $it") }
    }

    private fun expandChromeTouchTargets() {
        if (!::freeformLayout.isInitialized) return
        freeformLayout.post {
            runCatching {
                val extra = (dpToPx(CHROME_TOUCH_EXTRA_DP)).roundToInt()
                val barRect = Rect()
                topBarView.getHitRect(barRect)
                barRect.top -= extra
                barRect.bottom += extra
                barRect.left -= extra
                barRect.right += extra
                freeformLayout.touchDelegate = android.view.TouchDelegate(barRect, topBarView)
            }.onFailure { Slog.w(TAG, "expandChromeTouchTargets failed: $it") }
        }
    }

    private fun dpToPx(dp: Int): Float = dp * context.resources.displayMetrics.density

    private fun bubbleSizePx(): Int = (BUBBLE_DP * context.resources.displayMetrics.density).roundToInt()

    private fun setCardRadius(radiusPx: Float) {
        runCatching {
            freeformLayout.javaClass.getMethod("setRadius", Float::class.javaPrimitiveType)
                .invoke(freeformLayout, radiusPx)
        }
    }

    private fun applyBubbleChrome() {
        val b = bubbleSizePx()
        freeformConfig.hangUpWidth = b
        freeformConfig.hangUpHeight = b
        bubbleView?.setImageDrawable(appIcon)
        bubbleView?.visibility = View.VISIBLE
        if (::freeformRootView.isInitialized) freeformRootView.visibility = View.GONE
        if (::topBarView.isInitialized) topBarView.visibility = View.GONE
        bottomBarView?.visibility = View.GONE
        setCardRadius(b / 2f)
        attachBubbleTouch()
    }

    private fun clearBubbleChrome() {
        bubbleView?.visibility = View.GONE
        if (::freeformRootView.isInitialized) freeformRootView.visibility = View.VISIBLE
        if (::topBarView.isInitialized) topBarView.visibility = View.VISIBLE
        bottomBarView?.visibility = View.VISIBLE
        setCardRadius(dpToPx(CARD_RADIUS_DP))
        if (::freeformView.isInitialized) freeformView.setOnTouchListener(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBubbleTouch() {
        val bubble = bubbleView ?: return
        val detector = GestureDetector(context, hangUpGestureListener)
        bubble.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) makeSureFreeformInScreen()
            true
        }
    }

    private val taskIdOrNull: Int?
        get() = freeformTaskStackListener?.taskId?.takeIf { it != -1 }

    private fun setWindowFlag(flag: Int, enabled: Boolean) {
        windowParams.flags = if (enabled) windowParams.flags or flag else windowParams.flags and flag.inv()
    }

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
            animateTo(x = snapLeftX, dur = 200)
        } else if (rightEdge >= screenW - threshold && windowParams.x != snapRightX) {
            animateTo(x = snapRightX, dur = 200)
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
        if (userResized) {
            freeformConfig.width = freeformConfig.width.coerceIn(minWidthPx(), maxWidthPx())
            freeformConfig.height = freeformConfig.height.coerceIn(minHeightPx(), maxHeightPx())
            dlog(TAG, "measureSize: userResized, clamped to ${freeformConfig.width}x${freeformConfig.height}")
            return
        }
        val isPortrait = defaultDisplayRotation == Surface.ROTATION_0 ||
                defaultDisplayRotation == Surface.ROTATION_180
        freeformConfig.apply {
            height = (defaultDisplayHeight * (if (isPortrait) 0.6 else 0.7)).roundToInt()
            width = if (isPortrait) {
                (defaultDisplayWidth * 0.85).roundToInt()
            } else {
                (defaultDisplayWidth * 0.45).roundToInt()
            }
            dlog(TAG, "measureSize: isPortrait=$isPortrait width=$width height=$height")
        }
    }

    fun measureScale() {
        freeformConfig.apply {
            if (baseDensityDpi <= 0) baseDensityDpi = densityDpi
            val widthScale = min(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / min(width, height)
            val heightScale = max(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / max(width, height)
            scale = min(widthScale, heightScale)
            freeformWidth = (width * scale).roundToInt()
            freeformHeight = (height * scale).roundToInt()
            densityDpi = com.libremobileos.freeform.server.ui.gesture.GeometryMath
                .computeDensityDpi(baseDensityDpi, scale, width, densityMode)
            dlog(TAG, "measureScale: $scale freeformWidth=$freeformWidth freeformHeight=$freeformHeight densityDpi=$densityDpi")
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
        bubbleView = resourceHolder.getLayoutChildViewByTag<ImageView>(freeformLayout, "bubbleView")
        bubbleView?.setImageDrawable(appIcon)
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
        rightScaleView.setOnTouchListener(ScaleTouchListener(this, true, uniform = true))
        pillScaleView?.setOnTouchListener(ScaleTouchListener(this, uniform = true, useHorizontal = false))
        expandChromeTouchTargets()

        freeformView = FreeformTextureView(context).apply {
            setOnTouchListener(this@FreeformWindow)
            onGenericMotion = { event ->
                runCatching { forward(event) }.isSuccess
            }
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
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            privateFlags = privateFlags or
                    WindowManager.LayoutParams.PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
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
            }
            freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
                width = freeformConfig.width
                height = freeformConfig.height
            }
            windowManager.updateViewLayout(freeformLayout, windowParams)
            handler.post { updateSystemGestureExclusion() }
            clearBubbleChrome()
            freeformConfig.isHangUp = false
        } else {
            freeformConfig.notInHangUpX = windowParams.x
            freeformConfig.notInHangUpY = windowParams.y
            hideLocalIme()
            toHangUp()
            applyBubbleChrome()
            runCatching { windowManager.updateViewLayout(freeformLayout, windowParams) }
            handler.post { updateSystemGestureExclusion() }
            freeformConfig.isHangUp = true
        }
    }

    /**
     * Called in system handler
     */
    fun toHangUp() {
        val b = bubbleSizePx()
        freeformConfig.hangUpWidth = b
        freeformConfig.hangUpHeight = b
        windowParams.apply {
            x = (defaultDisplayWidth / 2 - b / 2)
            y = -(defaultDisplayHeight / 2 - b / 2)
        }
        if (::freeformRootView.isInitialized) {
            freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
                width = b
                height = b
            }
        }
        runCatching { windowManager.updateViewLayout(freeformLayout, windowParams) }.onFailure { Slog.e(TAG, "$it") }
        handler.post { updateSystemGestureExclusion() }
    }

    /**
     * Called in uiHandler
     */
    fun makeSureFreeformInScreen() {
        if (destroyed || !::freeformRootView.isInitialized || !::freeformLayout.isInitialized) return
        if (!freeformConfig.isHangUp) {
            val lp = freeformRootView.layoutParams ?: return
            val curW = if (lp.width > 0) lp.width else freeformConfig.width
            val curH = if (lp.height > 0) lp.height else freeformConfig.height
            val newW = min(curW, maxWidthPx())
            val newH = min(curH, maxHeightPx())
            if (newW != lp.width || newH != lp.height) {
                commitGeometry(newW, newH)
            }
        }
        clampPositionAnimated()
    }

    private fun clampPositionAnimated() {
        val (curW, curH) = if (freeformConfig.isHangUp) {
            freeformConfig.hangUpWidth to freeformConfig.hangUpHeight
        } else if (::freeformRootView.isInitialized) {
            val lp = freeformRootView.layoutParams
            ((lp?.width?.takeIf { it > 0 } ?: freeformConfig.width)) to
                ((lp?.height?.takeIf { it > 0 } ?: freeformConfig.height))
        } else {
            freeformConfig.width to freeformConfig.height
        }
        val limitX = max(0, (defaultDisplayWidth - curW) / 2)
        val limitY = max(0, (defaultDisplayHeight - curH) / 2)
        val targetX = windowParams.x.coerceIn(-limitX, limitX)
        val targetY = windowParams.y.coerceIn(-limitY, limitY)
        if (targetX != windowParams.x || targetY != windowParams.y) {
            animateTo(x = targetX, y = targetY, dur = 300)
        }
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

    fun dumpState(pw: java.io.PrintWriter) {
        runCatching {
            val (vw, vh) = if (::freeformRootView.isInitialized) {
                val lp = freeformRootView.layoutParams
                (lp?.width ?: -1) to (lp?.height ?: -1)
            } else -1 to -1
            pw.println("  id=${getFreeformId()} displayId=$displayId taskId=${taskIdOrNull ?: "none"}")
            pw.println("    pos=(${windowParams.x},${windowParams.y}) view=${vw}x${vh} " +
                    "display=${freeformConfig.freeformWidth}x${freeformConfig.freeformHeight} " +
                    "scale=${freeformConfig.scale} dpi=${freeformConfig.densityDpi} " +
                    "(base=${freeformConfig.baseDensityDpi} mode=${freeformConfig.densityMode})")
            pw.println("    hangUp=${freeformConfig.isHangUp} destroyed=$destroyed " +
                    "gesture=${gestureRouter?.mode ?: "none"}")
        }.onFailure { pw.println("  <dump failed: $it>") }
    }

    fun close() {
        dlog(TAG, "close()")
        val taskId = taskIdOrNull
        if (taskId == null) { destroy("close:no task"); return }
        runCatching {
            SystemServiceHolder.activityTaskManager.removeTask(taskId)
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
        val preType = preTypeGeometry
        if (preType != null) {
            persistGeometry(preType.first, preType.second)
        } else if (userResized && !freeformConfig.isHangUp) {
            persistGeometry(freeformConfig.width, freeformConfig.height)
        }
        destroyed = true
        Slog.i(TAG, "destroy ${getFreeformId()}, displayId=$displayId callReason: $callReason")
        runCatching { gestureRouter?.destroy() }
        gestureRouter = null
        cancelGeometryAnimations()
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        removeView(false)
        handler.removeCallbacks(destroyRunnable)
        freeformTaskStackListener?.let { listener ->
            runCatching { SystemServiceHolder.activityTaskManager.unregisterTaskStackListener(listener) }
                .onFailure { Slog.w(TAG, "unregisterTaskStackListener failed: $it") }
        }
        runCatching { SystemServiceHolder.windowManager.removeRotationWatcher(rotationWatcher) }
            .onFailure { Slog.w(TAG, "removeRotationWatcher failed: $it") }
        runCatching { hideLocalIme() }
            .onFailure { Slog.w(TAG, "hideLocalIme failed: $it") }
        runCatching { LMOFreeformServiceHolder.releaseFreeform(this) }
            .onFailure { Slog.w(TAG, "releaseFreeform failed: $it") }
        runCatching { displaySurfaceTexture?.release() }
            .onFailure { Slog.w(TAG, "surfaceTexture release failed: $it") }
        displaySurfaceTexture = null
        runCatching { FreeformWindowManager.removeWindow(getFreeformId()) }
            .onFailure { Slog.w(TAG, "removeWindow failed: $it") }
        runCatching { windowManagerInt?.unregisterDisplaySecureContentListener(this) }
            .onFailure { Slog.w(TAG, "unregisterDisplaySecureContentListener failed: $it") }
        handler.post { removeDisplayInsets() }
        taskIdOrNull?.let {
            if (shouldRemoveTask) {
                Slog.i(TAG, "destroy: remove taskId $it again")
                runCatching { SystemServiceHolder.activityTaskManager.removeTask(it) }
                    .onFailure { e -> Slog.w(TAG, "destroy removeTask failed: $e") }
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