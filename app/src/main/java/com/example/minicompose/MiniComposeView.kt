package com.example.minicompose

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *   Blog Section 1: Compose in the View System
 *   ─ ComposeView & AndroidComposeView ─
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Architecture:
 *
 *   Android View Tree
 *     └─ MiniComposeView (ViewGroup)         ← Public API container
 *          └─ MiniAndroidComposeView (ViewGroup)  ← Internal bridge & Owner
 *               ├─ Root LayoutNode                ← Compose tree root
 *               └─ (could hold AndroidViewsHandler for embedded native Views)
 *
 * Why ViewGroup and not View?
 *
 * 1. ComposeView is a ViewGroup because it needs to addView() the internal
 *    AndroidComposeView as its sole child.
 *
 * 2. AndroidComposeView is a ViewGroup because when Compose embeds traditional
 *    Android Views (via AndroidView), it must manage them as child Views
 *    through an internal AndroidViewsHandler container.
 *
 * Real Compose sources:
 * - ComposeView.android.kt
 * - AndroidComposeView.android.kt
 * - Wrapper.android.kt
 */

// ═══════════════════════════════════════════════════════════════════════════════
// MiniComposeView — The public API container (like real ComposeView)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * The public entry point for embedding Mini Compose UI into a View hierarchy.
 *
 * Usage:
 * ```kotlin
 * val composeView = MiniComposeView(context)
 * composeView.setContent { root ->
 *     root.addChild(LayoutNode("Button").apply {
 *         drawBlock = { canvas -> /* draw button */ }
 *     })
 * }
 * setContentView(composeView)
 * ```
 *
 * Like real ComposeView, this class:
 * - Extends ViewGroup (to host the internal AndroidComposeView)
 * - Prevents external addView() calls
 * - Manages the lifecycle of the internal compose view
 */
class MiniComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private var internalView: MiniAndroidComposeView? = null
    private var creatingComposition = false

    init {
        // Ensure hardware acceleration is enabled
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Set the compose content. This mirrors real ComposeView.setContent {}.
     *
     * Internally creates an [MiniAndroidComposeView] as the sole child
     * (just like Wrapper.android.kt creates AndroidComposeView).
     */
    fun setContent(builder: (rootNode: LayoutNode) -> Unit) {
        creatingComposition = true
        try {
            if (internalView == null) {
                internalView = MiniAndroidComposeView(context).also {
                    // This is the ONLY addView allowed — internal use only.
                    // Mirrors: addView(composeView.view, DefaultLayoutParams)
                    super.addView(
                        it,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                    )
                }
            }
        } finally {
            creatingComposition = false
        }

        // Build the Compose LayoutNode tree
        internalView!!.root.let { root ->
            root.clearChildren()
            builder(root)
        }
    }

    /**
     * Prevent adding arbitrary views — exactly like real ComposeView.
     *
     * Real source:
     * ```kotlin
     * private fun checkAddView() {
     *     if (!creatingComposition) {
     *         throw UnsupportedOperationException(
     *             "Cannot add views to ComposeView; only Compose content is supported"
     *         )
     *     }
     * }
     * ```
     */
    override fun addView(child: View?) {
        if (!creatingComposition) {
            throw UnsupportedOperationException(
                "Cannot add views to MiniComposeView; only Mini Compose content is supported. " +
                "Use setContent {} to add UI."
            )
        }
        super.addView(child)
    }

    override fun addView(child: View?, params: LayoutParams?) {
        if (!creatingComposition) {
            throw UnsupportedOperationException(
                "Cannot add views to MiniComposeView; only Mini Compose content is supported."
            )
        }
        super.addView(child, params)
    }

    /**
     * Get the internal compose view for direct access (for demo purposes).
     */
    fun getAndroidComposeView(): MiniAndroidComposeView? = internalView

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Measure the sole child (MiniAndroidComposeView)
        internalView?.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        internalView?.layout(0, 0, r - l, b - t)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MiniAndroidComposeView — The internal bridge (like real AndroidComposeView)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * The internal View that bridges Android's View system with the Compose
 * LayoutNode tree.
 *
 * This class demonstrates two critical architectural decisions:
 *
 * 1. It extends ViewGroup (not View) — to support embedding native Android
 *    Views within the Compose tree via an AndroidViewsHandler.
 *
 * 2. It overrides dispatchDraw() for all rendering, leaving onDraw() empty.
 *    This ensures correct Z-order mixing between Compose UI and embedded
 *    native Views.
 *
 * Real Compose source: AndroidComposeView.android.kt
 */
class MiniAndroidComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "MiniAndroidComposeView"
    }

    /**
     * The root of the Compose LayoutNode tree.
     *
     * All composable content hangs off this root node, which is
     * managed by this view (acting as the "Owner" in real Compose).
     */
    val root: LayoutNode = LayoutNode("Root")

    /**
     * Pre-allocated zero-allocation canvas adapter.
     * See [CanvasHolder] for the zero-allocation pattern explanation.
     */
    private val canvasHolder: CanvasHolder = CanvasHolder()

    /** Track if we're in the drawing phase (for debugging). */
    private var isDrawingContent: Boolean = false

    /** Tracks layout pass count for the demo's performance comparison. */
    var layoutPassCount: Long = 0
        private set

    /** Tracks draw pass count. */
    var drawPassCount: Long = 0
        private set

    /** Microseconds spent in the most recent layout phase. */
    var lastLayoutTimeUs: Long = 0L
        private set

    /** Microseconds spent in the most recent draw phase. */
    var lastDrawTimeUs: Long = 0L
        private set

    /** Sum of layout phase microseconds in the current 1-second sample window. */
    var windowLayoutTimeUs: Long = 0L
        private set

    /** Sum of draw phase microseconds in the current 1-second sample window. */
    var windowDrawTimeUs: Long = 0L
        private set

    /** Resets the 1-second sample window timing counters. */
    fun resetTimingWindow() {
        windowLayoutTimeUs = 0L
        windowDrawTimeUs = 0L
    }

    init {
        // Enable hardware acceleration for RenderNode support
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // We handle our own drawing
        setWillNotDraw(false)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Blog Section 2: Why dispatchDraw and NOT onDraw?
    // ═════════════════════════════════════════════════════════════════════════
    //
    // Android's View.draw(Canvas) pipeline executes in this order:
    //
    //   1. drawBackground(canvas)
    //   2. onDraw(canvas)          ← draws the View's OWN content
    //   3. dispatchDraw(canvas)    ← draws CHILD Views (ViewGroup only)
    //   4. onDrawForeground(canvas)
    //
    // If Compose drew in onDraw(), ALL embedded native Views (WebView, MapView)
    // would always render ON TOP of Compose UI, because dispatchDraw (which
    // draws children) executes AFTER onDraw.
    //
    // By drawing in dispatchDraw(), Compose can precisely interleave its
    // LayoutNode rendering with native child Views for correct Z-ordering.
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Intentionally EMPTY — just like real AndroidComposeView!
     *
     * Real source:
     * ```kotlin
     * override fun onDraw(canvas: android.graphics.Canvas) {
     *     // Empty!
     * }
     * ```
     *
     * All drawing happens in [dispatchDraw] for Z-order control.
     */
    override fun onDraw(canvas: Canvas) {
        // ╔═══════════════════════════════════════════════════╗
        // ║  INTENTIONALLY EMPTY!                            ║
        // ║                                                  ║
        // ║  If we drew here, embedded native Views          ║
        // ║  (AndroidView) would always cover Compose UI.    ║
        // ║                                                  ║
        // ║  All rendering happens in dispatchDraw() below.  ║
        // ╚═══════════════════════════════════════════════════╝
        Log.d(TAG, "onDraw called — but EMPTY (by design)")
    }

    /**
     * The REAL drawing entry point — intercepts the system Canvas here.
     *
     * This mirrors the actual AndroidComposeView.dispatchDraw():
     * ```kotlin
     * override fun dispatchDraw(canvas: android.graphics.Canvas) {
     *     measureAndLayout()  // ensure layout is done
     *     canvasHolder.drawInto(canvas) {
     *         root.draw(canvas = this, graphicsLayer = null)
     *     }
     * }
     * ```
     */
    override fun dispatchDraw(canvas: Canvas) {
        drawPassCount++

        // Step 1: Measure & Layout phase timing (in microseconds)
        val layoutStartNs = System.nanoTime()
        measureAndLayoutNodes()
        val layoutDurationUs = (System.nanoTime() - layoutStartNs) / 1000L
        lastLayoutTimeUs = layoutDurationUs
        windowLayoutTimeUs += layoutDurationUs

        isDrawingContent = true
        val drawStartNs = System.nanoTime()
        try {
            // Step 2: Bridge the system Canvas to Compose's MiniCanvas
            // using the zero-allocation CanvasHolder pattern.
            canvasHolder.drawInto(canvas) {
                // Step 3: Recursively draw the entire LayoutNode tree,
                // starting from the root.
                root.draw(canvas = this)
            }
        } finally {
            isDrawingContent = false
            val drawDurationUs = (System.nanoTime() - drawStartNs) / 1000L
            lastDrawTimeUs = drawDurationUs
            windowDrawTimeUs += drawDurationUs
        }

        // Step 4: Let ViewGroup draw any native child Views
        // (supports AndroidView interop — Z-order mixing!)
        super.dispatchDraw(canvas)
    }

    /**
     * Ensures the LayoutNode tree is measured and laid out.
     * Called at the start of every dispatchDraw, just like real Compose.
     */
    private fun measureAndLayoutNodes() {
        layoutPassCount++
        root.measureAndLayout(width, height)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)

        // Set root node size to match the View size
        root.width = w
        root.height = h
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        root.width = r - l
        root.height = b - t
    }

    /**
     * Request a redraw of the Compose tree.
     * In real Compose, this is managed by the Snapshot system and
     * invalidation scheduling.
     */
    fun invalidateCompose() {
        invalidate()
    }
}
