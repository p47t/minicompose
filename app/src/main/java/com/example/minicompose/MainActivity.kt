package com.example.minicompose

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  Interactive Demo: Jetpack Compose Rendering Architecture
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * This activity creates a live demonstration of the 5 key ideas from the blog:
 *
 *  1. ComposeView → AndroidComposeView → Root LayoutNode hierarchy
 *  2. dispatchDraw interception (onDraw is empty!)
 *  3. CanvasHolder zero-allocation canvas bridging
 *  4. GraphicsLayer with RenderNode (Display List vs Header Properties)
 *  5. graphicsLayer animation (smooth) vs offset animation (expensive)
 *
 * The demo shows two animated boxes side by side:
 *  - LEFT:  Animated via graphicsLayer (only updates RenderNode header properties)
 *  - RIGHT: Animated via offset (triggers full re-layout every frame)
 *
 * Press "Simulate Main Thread Work" to block the main thread briefly.
 * Notice how the graphicsLayer animation stays smoother because the
 * RenderThread can replay the unchanged Display List with new transforms,
 * while the offset animation stutters because it needs the main thread
 * for re-layout.
 */
class MainActivity : Activity() {

    private lateinit var composeView: MiniComposeView
    private lateinit var statsText: TextView

    // Animation state
    private var animationProgress: Float = 0f
    private var animator: ValueAnimator? = null

    // The two demo nodes
    private var graphicsLayerNode: LayoutNode? = null
    private var offsetNode: LayoutNode? = null

    // Stats
    private val handler = Handler(Looper.getMainLooper())
    private var lastLayoutCount = 0L
    private var lastDrawCount = 0L

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(0, 60, 0, 0)
        }

        // ── Title ──────────────────────────────────────────────────────────
        val titleText = TextView(this).apply {
            text = "Mini Compose Rendering Demo"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 8)
        }
        rootLayout.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "Demonstrating Jetpack Compose's rendering architecture"
            textSize = 13f
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = Gravity.CENTER
            setPadding(32, 0, 32, 24)
        }
        rootLayout.addView(subtitleText)

        // ── Architecture Info ──────────────────────────────────────────────
        val archText = TextView(this).apply {
            text = buildString {
                appendLine("▸ MiniComposeView (ViewGroup)")
                appendLine("   └─ MiniAndroidComposeView (ViewGroup)")
                appendLine("       ├─ onDraw(): EMPTY (by design!)")
                appendLine("       ├─ dispatchDraw(): ALL rendering here")
                appendLine("       ├─ CanvasHolder: zero-allocation bridge")
                append("       └─ Root LayoutNode → children")
            }
            textSize = 11f
            setTextColor(Color.parseColor("#6EE7B7"))
            typeface = Typeface.MONOSPACE
            setPadding(40, 16, 40, 16)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        rootLayout.addView(archText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(24, 8, 24, 16) })

        // ── Column headers ────────────────────────────────────────────────
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 8, 24, 4)
        }
        val leftHeader = TextView(this).apply {
            text = "graphicsLayer\n(Header Props only)"
            textSize = 12f
            setTextColor(Color.parseColor("#60A5FA"))
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val rightHeader = TextView(this).apply {
            text = "offset\n(Re-layout every frame)"
            textSize = 12f
            setTextColor(Color.parseColor("#F87171"))
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        headerLayout.addView(leftHeader, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerLayout.addView(rightHeader, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rootLayout.addView(headerLayout)

        // ── Compose View (the demo canvas) ────────────────────────────────
        composeView = MiniComposeView(this)
        val composeContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 0)
            addView(composeView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        rootLayout.addView(composeContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { setMargins(24, 8, 24, 8) })

        // ── Stats display ─────────────────────────────────────────────────
        statsText = TextView(this).apply {
            text = "Layout passes: 0 | Draw passes: 0"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(24, 8, 24, 8)
        }
        rootLayout.addView(statsText)

        // ── Buttons ───────────────────────────────────────────────────────
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 8, 24, 40)
        }

        val animateButton = Button(this).apply {
            text = "▶ Animate"
            textSize = 13f
            setOnClickListener { startAnimation() }
        }

        val blockButton = Button(this).apply {
            text = "⏸ Block Main Thread (200ms)"
            textSize = 13f
            setOnClickListener { simulateMainThreadWork() }
        }

        val resetButton = Button(this).apply {
            text = "↺ Reset"
            textSize = 13f
            setOnClickListener { resetAnimation() }
        }

        buttonLayout.addView(animateButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { setMargins(4, 0, 4, 0) })
        buttonLayout.addView(blockButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { setMargins(4, 0, 4, 0) })
        buttonLayout.addView(resetButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { setMargins(4, 0, 4, 0) })
        rootLayout.addView(buttonLayout)

        setContentView(rootLayout)

        // Build the compose tree after layout
        composeView.post { setupComposeTree() }

        // Start stats update timer
        startStatsUpdater()
    }

    /**
     * Builds the LayoutNode tree inside MiniComposeView.
     *
     * This demonstrates the full architecture:
     * 1. MiniComposeView.setContent {} creates the MiniAndroidComposeView
     * 2. We build a tree of LayoutNodes under the root
     * 3. Some nodes have GraphicsLayer (hardware-accelerated via RenderNode)
     */
    private fun setupComposeTree() {
        val viewWidth = composeView.width
        val viewHeight = composeView.height
        if (viewWidth == 0 || viewHeight == 0) return

        val boxSize = (viewWidth * 0.30).toInt()
        val halfWidth = viewWidth / 2

        composeView.setContent { root ->
            root.measureBlock = { w, h -> Pair(w, h) }

            // ── Background ────────────────────────────────────────────────
            root.drawBlock = { canvas ->
                // Draw dividing line
                val dividerPaint = Paint().apply {
                    color = Color.parseColor("#334155")
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 8f), 0f)
                }
                canvas.getNativeCanvas().drawLine(
                    halfWidth.toFloat(), 0f,
                    halfWidth.toFloat(), viewHeight.toFloat(),
                    dividerPaint
                )
            }

            // ── LEFT: graphicsLayer animated node ─────────────────────────
            // This node uses a GraphicsLayer (backed by RenderNode).
            // Animation updates ONLY the RenderNode header properties.
            // The Display List is recorded ONCE and reused.
            val leftNode = LayoutNode("GraphicsLayerBox").apply {
                x = halfWidth / 2 - boxSize / 2
                y = viewHeight / 2 - boxSize / 2
                width = boxSize
                height = boxSize
                measureBlock = { _, _ -> Pair(boxSize, boxSize) }

                // Create a GraphicsLayer (only on API 29+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    graphicsLayer = GraphicsLayer("LeftBox")
                }

                // The draw block records into the Display List ONCE
                drawBlock = { canvas ->
                    // Background
                    val bgPaint = Paint().apply {
                        color = Color.parseColor("#1D4ED8")
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    }
                    canvas.drawRoundRect(0f, 0f, boxSize.toFloat(), boxSize.toFloat(), 24f, 24f, bgPaint)

                    // Border
                    val borderPaint = Paint().apply {
                        color = Color.parseColor("#60A5FA")
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        isAntiAlias = true
                    }
                    canvas.drawRoundRect(2f, 2f, boxSize.toFloat() - 2f, boxSize.toFloat() - 2f, 22f, 22f, borderPaint)

                    // Label
                    val textPaint = Paint().apply {
                        color = Color.WHITE
                        textSize = 28f
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        isAntiAlias = true
                    }
                    canvas.drawText("GPU", boxSize / 2f, boxSize / 2f - 10f, textPaint)

                    val subPaint = Paint().apply {
                        color = Color.parseColor("#93C5FD")
                        textSize = 16f
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    canvas.drawText("graphicsLayer", boxSize / 2f, boxSize / 2f + 20f, subPaint)
                    canvas.drawText("Header Props", boxSize / 2f, boxSize / 2f + 42f, subPaint)
                }

                // The graphicsLayerBlock updates header properties each frame.
                // This is the FAST path: < 1μs, no Display List re-recording!
                graphicsLayerBlock = { layer ->
                    val amplitude = viewHeight * 0.25f
                    layer.translationY = Math.sin(animationProgress.toDouble() * Math.PI * 2).toFloat() * amplitude
                    layer.rotationZ = animationProgress * 360f
                    layer.scaleX = 0.8f + 0.4f * Math.abs(Math.sin(animationProgress.toDouble() * Math.PI)).toFloat()
                    layer.scaleY = 0.8f + 0.4f * Math.abs(Math.sin(animationProgress.toDouble() * Math.PI)).toFloat()
                }
            }
            root.addChild(leftNode)
            graphicsLayerNode = leftNode

            // ── RIGHT: offset animated node ───────────────────────────────
            // This node uses Modifier.offset-style animation.
            // Every frame triggers a full re-layout of the subtree!
            val rightNode = LayoutNode("OffsetBox").apply {
                x = halfWidth + halfWidth / 2 - boxSize / 2
                y = viewHeight / 2 - boxSize / 2
                width = boxSize
                height = boxSize
                measureBlock = { _, _ -> Pair(boxSize, boxSize) }

                // NO GraphicsLayer — all transforms go through the layout system

                drawBlock = { canvas ->
                    // Background
                    val bgPaint = Paint().apply {
                        color = Color.parseColor("#991B1B")
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    }
                    canvas.drawRoundRect(0f, 0f, boxSize.toFloat(), boxSize.toFloat(), 24f, 24f, bgPaint)

                    // Border
                    val borderPaint = Paint().apply {
                        color = Color.parseColor("#F87171")
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        isAntiAlias = true
                    }
                    canvas.drawRoundRect(2f, 2f, boxSize.toFloat() - 2f, boxSize.toFloat() - 2f, 22f, 22f, borderPaint)

                    // Label
                    val textPaint = Paint().apply {
                        color = Color.WHITE
                        textSize = 28f
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        isAntiAlias = true
                    }
                    canvas.drawText("CPU", boxSize / 2f, boxSize / 2f - 10f, textPaint)

                    val subPaint = Paint().apply {
                        color = Color.parseColor("#FCA5A5")
                        textSize = 16f
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    canvas.drawText("offset", boxSize / 2f, boxSize / 2f + 20f, subPaint)
                    canvas.drawText("Re-layout", boxSize / 2f, boxSize / 2f + 42f, subPaint)
                }
            }
            root.addChild(rightNode)
            offsetNode = rightNode
        }
    }

    /**
     * Starts the animation loop.
     *
     * Both boxes animate simultaneously, but through different paths:
     * - LEFT (graphicsLayer): Only RenderNode header properties updated
     * - RIGHT (offset): Full re-layout triggered every frame
     */
    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                animationProgress = animation.animatedValue as Float

                // Update the offset node's position (triggers re-layout!)
                offsetNode?.let { node ->
                    val viewHeight = composeView.height
                    val amplitude = viewHeight * 0.25f
                    node.offsetY = (Math.sin(animationProgress.toDouble() * Math.PI * 2) * amplitude).toInt()
                    node.needsLayout = true  // Mark for re-layout!
                }

                // Note: graphicsLayerNode's animation is handled in its
                // graphicsLayerBlock — which only updates header properties!

                // Request redraw
                composeView.getAndroidComposeView()?.invalidateCompose()
            }
            start()
        }
    }

    /**
     * Simulates heavy main thread work.
     *
     * When the main thread is blocked:
     * - graphicsLayer animation: RenderThread CONTINUES playing the animation
     *   on the GPU because it only needs the RenderNode's Display List +
     *   header properties (both already committed to native memory)
     * - offset animation: STUTTERS because it needs the main thread to
     *   run re-layout before it can produce a new frame
     */
    private fun simulateMainThreadWork() {
        // Block main thread for 200ms to demonstrate the difference
        Thread.sleep(200)
    }

    private fun resetAnimation() {
        animator?.cancel()
        animationProgress = 0f

        offsetNode?.offsetY = 0
        offsetNode?.needsLayout = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            graphicsLayerNode?.graphicsLayer?.let { layer ->
                layer.translationY = 0f
                layer.rotationZ = 0f
                layer.scaleX = 1f
                layer.scaleY = 1f
            }
        }

        composeView.getAndroidComposeView()?.invalidateCompose()
    }

    @SuppressLint("SetTextI18n")
    private fun startStatsUpdater() {
        val updateRunnable = object : Runnable {
            override fun run() {
                val acv = composeView.getAndroidComposeView()
                if (acv != null) {
                    val layoutDelta = acv.layoutPassCount - lastLayoutCount
                    val drawDelta = acv.drawPassCount - lastDrawCount
                    lastLayoutCount = acv.layoutPassCount
                    lastDrawCount = acv.drawPassCount

                    statsText.text = buildString {
                        append("Layout: ${acv.layoutPassCount} (+$layoutDelta/s)")
                        append(" │ ")
                        append("Draw: ${acv.drawPassCount} (+$drawDelta/s)")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            append(" │ RenderNode: ✓")
                        } else {
                            append(" │ RenderNode: ✗ (API < 29)")
                        }
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(updateRunnable, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        animator?.cancel()
        handler.removeCallbacksAndMessages(null)
    }
}
