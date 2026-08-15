package com.nexusagent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A Compose surface floating over every other app.
 *
 * ## Why this class is more than a WindowManager call
 *
 * `ComposeView` refuses to compose unless the view tree can supply a `LifecycleOwner`,
 * a `ViewModelStoreOwner`, and a `SavedStateRegistryOwner`. Inside an Activity those come
 * for free; a view added directly to `WindowManager` has no host to inherit them from and
 * silently renders nothing. This class *is* those three owners, which is what makes
 * Compose usable outside an Activity at all.
 *
 * The window is `TYPE_APPLICATION_OVERLAY` with `FLAG_NOT_FOCUSABLE`: it must never steal
 * focus, or the keyboard would close in whatever app the agent is currently typing into.
 * The consequence is that touches outside the bubble pass straight through to the app
 * below, which is exactly the behaviour wanted.
 */
class AgentOverlay(
    private val context: Context,
    private val compositionContext: CompositionContext,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = composeView != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        if (isShowing) return
        if (!canDrawOverlays(context)) return

        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE is load-bearing: taking focus would dismiss the keyboard in
            // whichever app the agent is typing into. WATCH_OUTSIDE_TOUCH is deliberately
            // absent - touches outside the bubble should reach the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 220
        }
        layoutParams = params

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@AgentOverlay)
            setViewTreeViewModelStoreOwner(this@AgentOverlay)
            setViewTreeSavedStateRegistryOwner(this@AgentOverlay)
            // Reuses the host's composition context so recomposition is driven by the
            // same frame clock rather than an orphaned one.
            setParentCompositionContext(compositionContext)
            setContent(content)
            setOnTouchListener(DragListener(params))
        }

        composeView = view
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        runCatching { windowManager.addView(view, params) }
            .onFailure { hide() }
    }

    fun hide() {
        composeView?.let { view ->
            runCatching { windowManager.removeView(view) }
            view.disposeComposition()
        }
        composeView = null
        layoutParams = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    /**
     * Drag-to-move, with a slop threshold so a tap is still a tap.
     *
     * Without the threshold every press moves the bubble by a pixel or two and the click
     * never registers - the classic draggable-button bug.
     */
    private inner class DragListener(
        private val params: WindowManager.LayoutParams,
    ) : View.OnTouchListener {

        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    return false // let Compose see the press
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = initialX + dx.roundToInt()
                        params.y = initialY + dy.roundToInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                        return true // consume, so Compose doesn't also treat it as a click
                    }
                    return false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = dragging
                    dragging = false
                    return wasDragging
                }
            }
            return false
        }
    }

    companion object {
        private const val TOUCH_SLOP = 16f

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}
