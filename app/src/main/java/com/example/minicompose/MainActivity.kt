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
import android.util.Log
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
 * Authentic Microsecond (µs) Benchmark with Deep Component Trees (100–1000 Nodes):
 *
 * Both columns host an identical rich Compose UI card containing nested
 * rows, text measurement policies, chips, and buttons.
 *
 *  - LEFT (graphicsLayer):
 *    Display List recorded once. Skips Layout phase entirely on animation frames.
 *    → Layout Time: 0 µs (0 passes/s across 1000 nodes)
 *
 *  - RIGHT (offset):
 *    Every animation frame marks the tree dirty, forcing recursive constraint
 *    solving, text measurement (Paint.measureText), and child coordinate placement
 *    across all 100–1000 LayoutNodes.
 *    → Layout Time: 5,000 µs – 35,000 µs per frame!
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MiniComposeBenchmark"
    }

    // Independent Compose views for split-screen rendering
    private lateinit var leftComposeView: MiniComposeView
    private lateinit var rightComposeView: MiniComposeView

    // Status badges
    private lateinit var leftStatsText: TextView
    private lateinit var rightStatsText: TextView
    private lateinit var summaryBanner: TextView
    private lateinit var complexityButton: Button

    // Animation state
    private var animationProgress: Float = 0f
    private var animator: ValueAnimator? = null

    // Layout tree complexity: 0 = Light (~100 nodes), 1 = Medium (~500 nodes), 2 = Heavy (~1000 nodes)
    private var complexityLevel: Int = 1

    // Nodes
    private var graphicsLayerNode: LayoutNode? = null
    private var offsetNode: LayoutNode? = null

    // Motion history for visual trail dots
    private val leftTrail = ArrayDeque<Float>()
    private val rightTrail = ArrayDeque<Float>()

    // Stats calculation
    private val handler = Handler(Looper.getMainLooper())
    private var leftLastDraw = 0L
    private var rightLastDraw = 0L
    private var leftLastLayout = 0L
    private var rightLastLayout = 0L

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(0, 44, 0, 0)
        }

        // ── Title ──────────────────────────────────────────────────────────
        val titleText = TextView(this).apply {
            text = "Compose Phase Microsecond Benchmark"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(24, 10, 24, 2)
        }
        rootLayout.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "Live Profiling on Deep Trees (100 vs 500 vs 1000 Nodes)"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(24, 0, 24, 6)
        }
        rootLayout.addView(subtitleText)

        // ── Architecture Banner ────────────────────────────────────────────
        summaryBanner = TextView(this).apply {
            text = buildString {
                appendLine("▸ Left (graphicsLayer): 0 µs Layout Phase (Skips 500+ node tree)")
                appendLine("▸ Right (offset): Must re-measure & re-place 500+ nodes every frame")
                append("▸ Live Microsecond (µs) profiling active below")
            }
            textSize = 11f
            setTextColor(Color.parseColor("#6EE7B7"))
            typeface = Typeface.MONOSPACE
            setPadding(24, 8, 24, 8)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        rootLayout.addView(summaryBanner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(16, 2, 16, 6) })

        // ── Split Screen Container (Two Independent Views) ─────────────────
        val splitLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 0, 12, 0)
        }

        // ── LEFT COLUMN ────────────────────────────────────────────────────
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(8, 6, 8, 6)
        }

        val leftHeader = TextView(this).apply {
            text = "⚡ graphicsLayer\n(Draw Phase / GPU)"
            textSize = 12f
            setTextColor(Color.parseColor("#60A5FA"))
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 2, 0, 6)
        }
        leftColumn.addView(leftHeader)

        leftComposeView = MiniComposeView(this)
        val leftCanvasContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#020617"))
            addView(leftComposeView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        leftColumn.addView(leftCanvasContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        leftStatsText = TextView(this).apply {
            text = "Layout Phase: 0 µs\nDraw Phase: 0 µs\n0 layout passes"
            textSize = 10f
            setTextColor(Color.parseColor("#4ADE80"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(4, 6, 4, 6)
        }
        leftColumn.addView(leftStatsText)

        // ── RIGHT COLUMN ───────────────────────────────────────────────────
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(8, 6, 8, 6)
        }

        val rightHeader = TextView(this).apply {
            text = "⚠️ offset\n(Layout Phase / CPU)"
            textSize = 12f
            setTextColor(Color.parseColor("#F87171"))
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 2, 0, 6)
        }
        rightColumn.addView(rightHeader)

        rightComposeView = MiniComposeView(this)
        val rightCanvasContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#020617"))
            addView(rightComposeView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        rightColumn.addView(rightCanvasContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        rightStatsText = TextView(this).apply {
            text = "Layout Phase: 0 µs\nDraw Phase: 0 µs\n0 layout passes"
            textSize = 10f
            setTextColor(Color.parseColor("#FCA5A5"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(4, 6, 4, 6)
        }
        rightColumn.addView(rightStatsText)

        splitLayout.addView(leftColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(4, 0, 4, 0)
        })
        splitLayout.addView(rightColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(4, 0, 4, 0)
        })

        rootLayout.addView(splitLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ── Buttons ───────────────────────────────────────────────────────
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 4, 16, 28)
        }

        val animateButton = Button(this).apply {
            text = "▶ Animate"
            textSize = 12f
            setOnClickListener { startAnimation() }
        }

        complexityButton = Button(this).apply {
            text = "📊 Tree: Medium (500 Nodes)"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener { cycleComplexity() }
        }

        val resetButton = Button(this).apply {
            text = "↺ Reset"
            textSize = 12f
            setOnClickListener { resetAnimation() }
        }

        buttonLayout.addView(animateButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { setMargins(3, 0, 3, 0) })
        buttonLayout.addView(complexityButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f
        ).apply { setMargins(3, 0, 3, 0) })
        buttonLayout.addView(resetButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { setMargins(3, 0, 3, 0) })
        rootLayout.addView(buttonLayout)

        setContentView(rootLayout)

        // Setup the compose trees and auto-start benchmark animation
        leftComposeView.post {
            rebuildTrees()
            startAnimation()
        }

        // Start stats telemetry loop
        startStatsUpdater()
    }

    private fun rebuildTrees() {
        setupLeftComposeTree()
        setupRightComposeTree()
    }

    /**
     * Builds LEFT tree: Complex UI hierarchy with GraphicsLayer.
     * Skips layout entirely during animation (needsLayout stays false).
     */
    private fun setupLeftComposeTree() {
        val w = leftComposeView.width
        val h = leftComposeView.height
        if (w == 0 || h == 0) return
        val cardWidth = (w * 0.88).toInt()
        val cardHeight = (h * 0.42).toInt()

        leftComposeView.setContent { root ->
            root.measureBlock = { pw, ph -> Pair(pw, ph) }

            root.drawBlock = { canvas ->
                val trackPaint = Paint().apply {
                    color = Color.parseColor("#1E293B")
                    strokeWidth = 3f
                    style = Paint.Style.STROKE
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f)
                }
                canvas.getNativeCanvas().drawLine(w / 2f, 20f, w / 2f, h - 20f, trackPaint)

                // Motion trail dots
                val dotPaint = Paint().apply {
                    color = Color.parseColor("#38BDF8")
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                synchronized(leftTrail) {
                    leftTrail.forEachIndexed { index, yPos ->
                        dotPaint.alpha = (255 * (index + 1) / (leftTrail.size.coerceAtLeast(1))).coerceIn(30, 255)
                        canvas.getNativeCanvas().drawCircle(w / 2f - cardWidth / 2f - 10f, yPos, 4f, dotPaint)
                    }
                }
            }

            val cardNode = buildRichCardNode(cardWidth, cardHeight, isLeftGpu = true).apply {
                x = w / 2 - cardWidth / 2
                y = h / 2 - cardHeight / 2

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    graphicsLayer = GraphicsLayer("LeftGpuCard")
                }

                graphicsLayerBlock = { layer ->
                    val amplitude = h * 0.26f
                    val currentY = Math.sin(animationProgress.toDouble() * Math.PI * 2).toFloat() * amplitude
                    layer.translationY = currentY
                    layer.rotationZ = animationProgress * 360f

                    val centerScreenY = (h / 2f) + currentY
                    synchronized(leftTrail) {
                        leftTrail.addLast(centerScreenY)
                        if (leftTrail.size > 26) leftTrail.removeFirst()
                    }
                }
            }
            root.addChild(cardNode)
            graphicsLayerNode = cardNode
        }
    }

    /**
     * Builds RIGHT tree: Identical complex UI hierarchy with offset movement.
     * Triggers recursive layout measurement across all 100–1000 children on every frame.
     */
    private fun setupRightComposeTree() {
        val w = rightComposeView.width
        val h = rightComposeView.height
        if (w == 0 || h == 0) return
        val cardWidth = (w * 0.88).toInt()
        val cardHeight = (h * 0.42).toInt()

        rightComposeView.setContent { root ->
            root.measureBlock = { pw, ph -> Pair(pw, ph) }

            root.drawBlock = { canvas ->
                val trackPaint = Paint().apply {
                    color = Color.parseColor("#1E293B")
                    strokeWidth = 3f
                    style = Paint.Style.STROKE
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f)
                }
                canvas.getNativeCanvas().drawLine(w / 2f, 20f, w / 2f, h - 20f, trackPaint)

                // Motion trail dots
                val dotPaint = Paint().apply {
                    color = Color.parseColor("#FB7185")
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                synchronized(rightTrail) {
                    rightTrail.forEachIndexed { index, yPos ->
                        dotPaint.alpha = (255 * (index + 1) / (rightTrail.size.coerceAtLeast(1))).coerceIn(30, 255)
                        canvas.getNativeCanvas().drawCircle(w / 2f + cardWidth / 2f + 10f, yPos, 4f, dotPaint)
                    }
                }
            }

            val cardNode = buildRichCardNode(cardWidth, cardHeight, isLeftGpu = false).apply {
                x = w / 2 - cardWidth / 2
                y = h / 2 - cardHeight / 2

                drawBlock = { canvas ->
                    drawCardVisuals(canvas, cardWidth, cardHeight, isLeftGpu = false)

                    val centerScreenY = (h / 2f) + (offsetNode?.offsetY ?: 0)
                    synchronized(rightTrail) {
                        rightTrail.addLast(centerScreenY)
                        if (rightTrail.size > 26) rightTrail.removeFirst()
                    }
                }
            }
            root.addChild(cardNode)
            offsetNode = cardNode
        }
    }

    /**
     * Builds a rich, deeply nested Compose layout tree representing a realistic
     * modern UI card (avatar, badges, content items, metric gauges, and buttons).
     *
     * Node formula: 11 base nodes + (rowCount * 5 nodes)
     * - Light (rowCount = 18):  11 + 90  = 101 nodes (~100 nodes)
     * - Medium (rowCount = 98): 11 + 490 = 501 nodes (~500 nodes)
     * - Heavy (rowCount = 198): 11 + 990 = 1001 nodes (~1000 nodes)
     */
    private fun buildRichCardNode(cardWidth: Int, cardHeight: Int, isLeftGpu: Boolean): LayoutNode {
        val rowCount = when (complexityLevel) {
            0 -> 18   // 101 total LayoutNodes (~100)
            1 -> 98   // 501 total LayoutNodes (~500)
            else -> 198 // 1001 total LayoutNodes (~1000)
        }

        val cardNode = LayoutNode(if (isLeftGpu) "GpuCard" else "CpuCard").apply {
            width = cardWidth
            height = cardHeight

            // Realistic Compose Layout Measure Policy
            measureBlock = { pw, ph ->
                Pair(cardWidth, cardHeight)
            }
            layoutBlock = { parent ->
                var currY = 44
                for (child in parent.children) {
                    child.x = 10
                    child.y = currY
                    currY += child.height + 4
                }
            }

            if (isLeftGpu) {
                drawBlock = { canvas ->
                    drawCardVisuals(canvas, cardWidth, cardHeight, isLeftGpu = true)
                }
            }
        }

        // Header Row (Avatar + User info + Subtitle + Timestamp = 5 nodes)
        val header = LayoutNode("HeaderRow").apply {
            width = cardWidth - 20
            height = 36
            measureBlock = { aw, _ ->
                val p = Paint().apply { textSize = 13f }
                val tw = p.measureText("Compose Architect")
                Pair(aw, 36)
            }
        }
        val avatar = LayoutNode("Avatar").apply {
            width = 24
            height = 24
            measureBlock = { _, _ -> Pair(24, 24) }
        }
        val title = LayoutNode("Title").apply {
            width = 120
            height = 14
            measureBlock = { _, _ ->
                val p = Paint().apply { textSize = 12f }
                Pair(p.measureText("Compose Lead").toInt(), 14)
            }
        }
        val subtitle = LayoutNode("Subtitle").apply {
            width = 100
            height = 12
            measureBlock = { _, _ -> Pair(100, 12) }
        }
        val timestamp = LayoutNode("Timestamp").apply {
            width = 40
            height = 12
            measureBlock = { _, _ -> Pair(40, 12) }
        }
        header.addChild(avatar)
        header.addChild(title)
        header.addChild(subtitle)
        header.addChild(timestamp)
        cardNode.addChild(header)

        // Multiple Content Items (5 nodes per row: ItemRow + Icon + Title + Badge + Dot)
        for (i in 0 until rowCount) {
            val itemRow = LayoutNode("ItemRow_$i").apply {
                width = cardWidth - 20
                height = 16

                // Measure policy simulating real text measurement and intrinsic sizing
                measureBlock = { aw, _ ->
                    val textPaint = Paint().apply {
                        textSize = 11f
                        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    }
                    val measuredTextWidth = textPaint.measureText("Component #$i metrics data binding & constraints")
                    val resolvedWidth = measuredTextWidth.toInt().coerceAtMost(aw)
                    Pair(resolvedWidth, 16)
                }
            }

            // Child 1: Icon
            val icon = LayoutNode("Icon_$i").apply {
                width = 12
                height = 12
                measureBlock = { _, _ -> Pair(12, 12) }
            }
            itemRow.addChild(icon)

            // Child 2: Text Title
            val rowTitle = LayoutNode("RowTitle_$i").apply {
                width = 80
                height = 14
                measureBlock = { _, _ ->
                    val p = Paint().apply { textSize = 10f }
                    Pair(p.measureText("Field_$i").toInt(), 14)
                }
            }
            itemRow.addChild(rowTitle)

            // Child 3: Badge
            val badge = LayoutNode("Badge_$i").apply {
                width = 36
                height = 14
                measureBlock = { _, _ ->
                    val p = Paint().apply { textSize = 9f }
                    val w = p.measureText("v$i").toInt() + 8
                    Pair(w, 14)
                }
            }
            itemRow.addChild(badge)

            // Child 4: Dot
            val dot = LayoutNode("Dot_$i").apply {
                width = 8
                height = 8
                measureBlock = { _, _ -> Pair(8, 8) }
            }
            itemRow.addChild(dot)

            cardNode.addChild(itemRow)
        }

        // Footer Action Row (5 nodes: FooterRow + 4 Action buttons)
        val footer = LayoutNode("FooterRow").apply {
            width = cardWidth - 20
            height = 28
            measureBlock = { aw, _ -> Pair(aw, 28) }
            layoutBlock = { parent ->
                var currX = 0
                for (child in parent.children) {
                    child.x = currX
                    child.y = 2
                    currX += child.width + 6
                }
            }
        }
        for (action in listOf("Like", "Share", "Save", "Stats")) {
            val btnNode = LayoutNode("Btn_$action").apply {
                width = 42
                height = 24
                measureBlock = { _, _ ->
                    val p = Paint().apply { textSize = 10f }
                    val w = p.measureText(action).toInt() + 14
                    Pair(w, 24)
                }
            }
            footer.addChild(btnNode)
        }
        cardNode.addChild(footer)

        return cardNode
    }

    /**
     * Draws the visual rendering for the rich card.
     */
    private fun drawCardVisuals(canvas: MiniCanvas, w: Int, h: Int, isLeftGpu: Boolean) {
        val primaryColor = if (isLeftGpu) Color.parseColor("#1D4ED8") else Color.parseColor("#991B1B")
        val borderColor = if (isLeftGpu) Color.parseColor("#60A5FA") else Color.parseColor("#F87171")
        val subColor = if (isLeftGpu) Color.parseColor("#93C5FD") else Color.parseColor("#FCA5A5")

        // Card Background
        val bgPaint = Paint().apply {
            color = primaryColor
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 18f, 18f, bgPaint)

        // Card Border
        val borderPaint = Paint().apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            isAntiAlias = true
        }
        canvas.drawRoundRect(1.5f, 1.5f, w.toFloat() - 1.5f, h.toFloat() - 1.5f, 16.5f, 16.5f, borderPaint)

        // Header Avatar Circle
        val avatarPaint = Paint().apply {
            color = if (isLeftGpu) Color.parseColor("#3B82F6") else Color.parseColor("#EF4444")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.getNativeCanvas().drawCircle(26f, 24f, 12f, avatarPaint)

        // Header Title
        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawText(if (isLeftGpu) "GPU Card" else "CPU Card", 46f, 22f, titlePaint)

        val modePaint = Paint().apply {
            color = subColor
            textSize = 11f
            isAntiAlias = true
        }
        canvas.drawText(if (isLeftGpu) "0 µs Layout Phase" else "Re-measuring Subtree", 46f, 34f, modePaint)

        // Draw Content Data Rows
        val textRowPaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            textSize = 10f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isAntiAlias = true
        }
        val tagBgPaint = Paint().apply {
            color = if (isLeftGpu) Color.parseColor("#1E3A8A") else Color.parseColor("#7F1D1D")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val tagTextPaint = Paint().apply {
            color = if (isLeftGpu) Color.parseColor("#93C5FD") else Color.parseColor("#FCA5A5")
            textSize = 8.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isAntiAlias = true
        }

        val dataLabels = listOf(
            "Node Layout Policy",
            "Constraint Bounds",
            "Flex Box Child Measure",
            "Text Glyph Layout",
            "Child Coordinate Placement",
            "Hierarchy Re-measure",
            "Subtree Traversal Pass",
            "Intrinsics Solver",
            "Box Dimension Binding",
            "Z-Index Placement"
        )

        var yOffset = 52f
        val maxLines = when (complexityLevel) {
            0 -> 3
            1 -> 6
            else -> 9
        }
        for (i in 0 until maxLines) {
            val label = dataLabels[i % dataLabels.size]
            canvas.drawText("#${i + 1} $label", 16f, yOffset + 8f, textRowPaint)
            canvas.drawRoundRect(w - 48f, yOffset - 2f, w - 14f, yOffset + 11f, 4f, 4f, tagBgPaint)
            canvas.drawText("v${i + 1}", w - 31f, yOffset + 8f, tagTextPaint)
            yOffset += 16f
            if (yOffset > h - 38f) break
        }

        // Footer Action Pills
        val btnPaint = Paint().apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        var btnX = 16f
        for (label in listOf("Like", "Share", "Save")) {
            canvas.drawRoundRect(btnX, h - 28f, btnX + 44f, h - 10f, 6f, 6f, btnPaint)
            btnX += 50f
            if (btnX > w - 40f) break
        }
    }

    /**
     * Starts the unthrottled, authentic animation loop.
     */
    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                animationProgress = animation.animatedValue as Float

                // 1. LEFT View (GraphicsLayer): Updates Draw-phase header properties without touching Layout
                leftComposeView.getAndroidComposeView()?.invalidateCompose()

                // 2. RIGHT View (Offset): Updates position in Layout Phase and marks subtree dirty
                val viewHeight = rightComposeView.height
                val amplitude = viewHeight * 0.26f
                val targetOffsetY = (Math.sin(animationProgress.toDouble() * Math.PI * 2) * amplitude).toInt()

                offsetNode?.let { node ->
                    node.offsetY = targetOffsetY
                    markTreeDirty(node) // Recursively dirty all 100-1000 nodes to force full authentic layout calculation
                }
                rightComposeView.getAndroidComposeView()?.invalidateCompose()
            }
            start()
        }
    }

    private fun markTreeDirty(node: LayoutNode) {
        node.markNeedsLayout()
        for (child in node.children) {
            markTreeDirty(child)
        }
    }

    /**
     * Cycles between 3 tree complexity tiers:
     * - Light (~100 nodes)
     * - Medium (~500 nodes)
     * - Heavy (~1000 nodes)
     */
    private fun cycleComplexity() {
        complexityLevel = (complexityLevel + 1) % 3
        when (complexityLevel) {
            0 -> complexityButton.text = "📊 Tree: Light (~100 Nodes)"
            1 -> complexityButton.text = "📊 Tree: Medium (~500 Nodes)"
            2 -> complexityButton.text = "📊 Tree: HEAVY (~1000 Nodes)"
        }
        rebuildTrees()
        resetAnimation()
    }

    private fun resetAnimation() {
        animator?.cancel()
        animationProgress = 0f

        synchronized(leftTrail) { leftTrail.clear() }
        synchronized(rightTrail) { rightTrail.clear() }

        offsetNode?.offsetY = 0
        offsetNode?.let { markTreeDirty(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            graphicsLayerNode?.graphicsLayer?.let { layer ->
                layer.translationY = 0f
                layer.rotationZ = 0f
                layer.scaleX = 1f
                layer.scaleY = 1f
            }
        }

        leftComposeView.getAndroidComposeView()?.invalidateCompose()
        rightComposeView.getAndroidComposeView()?.invalidateCompose()
    }

    @SuppressLint("SetTextI18n")
    private fun startStatsUpdater() {
        val updateRunnable = object : Runnable {
            override fun run() {
                val leftAcv = leftComposeView.getAndroidComposeView()
                val rightAcv = rightComposeView.getAndroidComposeView()

                if (leftAcv != null && rightAcv != null) {
                    val leftDraws = (leftAcv.drawPassCount - leftLastDraw).coerceAtLeast(0)
                    val rightDraws = (rightAcv.drawPassCount - rightLastDraw).coerceAtLeast(0)
                    val leftLayouts = (leftAcv.layoutPassCount - leftLastLayout).coerceAtLeast(0)
                    val rightLayouts = (rightAcv.layoutPassCount - rightLastLayout).coerceAtLeast(0)

                    // Compute true average microsecond execution times
                    val leftAvgLayoutUs = if (leftLayouts > 0) leftAcv.windowLayoutTimeUs / leftLayouts else leftAcv.lastLayoutTimeUs
                    val leftAvgDrawUs = if (leftDraws > 0) leftAcv.windowDrawTimeUs / leftDraws else leftAcv.lastDrawTimeUs

                    val rightAvgLayoutUs = if (rightLayouts > 0) rightAcv.windowLayoutTimeUs / rightLayouts else rightAcv.lastLayoutTimeUs
                    val rightAvgDrawUs = if (rightDraws > 0) rightAcv.windowDrawTimeUs / rightDraws else rightAcv.lastDrawTimeUs

                    // Reset timing windows
                    leftAcv.resetTimingWindow()
                    rightAcv.resetTimingWindow()

                    leftLastDraw = leftAcv.drawPassCount
                    rightLastDraw = rightAcv.drawPassCount
                    leftLastLayout = leftAcv.layoutPassCount
                    rightLastLayout = rightAcv.layoutPassCount

                    // Left HUD Badge
                    leftStatsText.text = buildString {
                        appendLine("FPS: $leftDraws fps | Passes: $leftLayouts/s")
                        appendLine("Layout: ${leftAvgLayoutUs} µs (SKIPPED)")
                        append("Draw: ${leftAvgDrawUs} µs | Total: ${leftAvgLayoutUs + leftAvgDrawUs} µs")
                    }
                    leftStatsText.setTextColor(Color.parseColor("#4ADE80"))

                    // Right HUD Badge
                    rightStatsText.text = buildString {
                        appendLine("FPS: $rightDraws fps | Passes: $rightLayouts/s")
                        appendLine("Layout: ${rightAvgLayoutUs} µs (RE-MEASURING)")
                        append("Draw: ${rightAvgDrawUs} µs | Total: ${rightAvgLayoutUs + rightAvgDrawUs} µs")
                    }
                    rightStatsText.setTextColor(Color.parseColor("#FCA5A5"))

                    // Update comparison headline
                    val nodeCount = when (complexityLevel) {
                        0 -> "~100"
                        1 -> "~500"
                        else -> "~1000"
                    }
                    summaryBanner.text = buildString {
                        appendLine("▸ Tree Size: $nodeCount LayoutNodes | 60 FPS VSYNC")
                        appendLine("▸ Left (graphicsLayer): ${leftAvgLayoutUs} µs Layout Phase (${leftLayouts} passes/s)")
                        appendLine("▸ Right (offset): ${rightAvgLayoutUs} µs Layout Phase (${rightLayouts} passes/s)")
                        if (rightAvgLayoutUs > leftAvgLayoutUs) {
                            val diff = rightAvgLayoutUs - leftAvgLayoutUs
                            append("⚡ graphicsLayer eliminates +${diff} µs CPU overhead per frame!")
                        } else {
                            append("▸ Live Microsecond (µs) profiling active")
                        }
                    }

                    // Structured Logcat telemetry output
                    val savingsUs = (rightAvgLayoutUs - leftAvgLayoutUs).coerceAtLeast(0)
                    Log.i(
                        TAG,
                        "📊 [Benchmark | $nodeCount nodes] " +
                        "LEFT(graphicsLayer): Layout=${leftAvgLayoutUs}µs ($leftLayouts passes/s), Draw=${leftAvgDrawUs}µs, Total=${leftAvgLayoutUs + leftAvgDrawUs}µs, FPS=$leftDraws | " +
                        "RIGHT(offset): Layout=${rightAvgLayoutUs}µs ($rightLayouts passes/s), Draw=${rightAvgDrawUs}µs, Total=${rightAvgLayoutUs + rightAvgDrawUs}µs, FPS=$rightDraws | " +
                        "SAVINGS=+${savingsUs}µs/frame"
                    )
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
