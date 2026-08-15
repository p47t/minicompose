package com.example.minicompose

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  Right CPU Activity — Process: `:right_cpu`
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Runs in its own dedicated Linux OS Process (isolated PID, ART VM, UI Thread,
 * and Choreographer).
 *
 * Demonstrates Modifier.offset (Layout Phase / CPU):
 * - Every frame marks the subtree dirty, forcing recursive constraint
 *   solving, text measurement (Paint.measureText), and child coordinate placement.
 * - Under heavy 1000-node layout and CPU load, drops frame rate to 15-30 FPS,
 *   completely isolated from the `:left_gpu` process!
 */
class RightCpuActivity : Activity() {

    companion object {
        private const val TAG = "RightCpuProcess"
    }

    private lateinit var composeView: MiniComposeView
    private lateinit var statsText: TextView
    private lateinit var processHeader: TextView
    private lateinit var layoutDelayButton: Button
    private lateinit var drawDelayButton: Button
    private lateinit var complexityButton: Button
    private lateinit var animateToggleButton: Button

    private var isAnimating: Boolean = true
    private var animationProgress: Float = 0f
    private var animator: ValueAnimator? = null

    // Layout tree complexity: 0 = Light (~100 nodes), 1 = Medium (~500 nodes), 2 = Heavy (~1000 nodes)
    private var complexityLevel: Int = 1

    // Simulated additional main-thread layout calculation delay in ms
    private var simulatedLayoutDelayMs: Long = 0L
    private var drawLoadPasses: Int = 0

    private var offsetNode: LayoutNode? = null
    private val motionTrail = ArrayDeque<Float>()

    private val handler = Handler(Looper.getMainLooper())
    private var lastDrawCount = 0L
    private var lastLayoutCount = 0L

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(16, 36, 16, 16)
        }

        // ── Process & Architecture Header ──────────────────────────────────
        processHeader = TextView(this).apply {
            text = "⚠️ PROCESS: :right_cpu | PID: ${Process.myPid()}\nModifier.offset (CPU / Layout Phase)"
            textSize = 13f
            setTextColor(Color.parseColor("#F87171"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#1E1B4B"))
        }
        rootLayout.addView(processHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 8) })

        // ── Canvas Container ───────────────────────────────────────────────
        composeView = MiniComposeView(this)
        val canvasContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#020617"))
            addView(composeView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        rootLayout.addView(canvasContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ── Stats Telemetry HUD ─────────────────────────────────────────────
        statsText = TextView(this).apply {
            text = "FPS: 60 fps | Passes: 60/s\nLayout: 0 µs (RE-MEASURING)\nDraw: 0 µs"
            textSize = 11f
            setTextColor(Color.parseColor("#FCA5A5"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.parseColor("#18181B"))
        }
        rootLayout.addView(statsText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 8, 0, 8) })

        // ── Control Buttons ────────────────────────────────────────────────
        val buttonRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        animateToggleButton = Button(this).apply {
            text = "⏹ Stop & Reset"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#B91C1C"))
            setTextColor(Color.WHITE)
            setOnClickListener { toggleAnimation() }
        }

        complexityButton = Button(this).apply {
            text = "📊 Tree: Medium (500 Nodes)"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener { cycleComplexity() }
        }

        buttonRow1.addView(animateToggleButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply { setMargins(2, 0, 2, 0) })
        buttonRow1.addView(complexityButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.8f).apply { setMargins(2, 0, 2, 0) })
        rootLayout.addView(buttonRow1)

        val buttonRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        layoutDelayButton = Button(this).apply {
            text = "🔥 Layout delay: 0ms"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener { cycleLayoutDelay() }
        }

        drawDelayButton = Button(this).apply {
            text = "🎨 Draw load: Normal"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener { cycleDrawLoad() }
        }

        buttonRow2.addView(layoutDelayButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) })
        buttonRow2.addView(drawDelayButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) })
        rootLayout.addView(buttonRow2)

        val splitScreenBtn = Button(this).apply {
            text = "⚡ Launch GPU Process Adjacent"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#1E3A8A"))
            setTextColor(Color.WHITE)
            setOnClickListener { launchLeftGpuAdjacent() }
        }
        rootLayout.addView(splitScreenBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 4, 0, 12) })

        setContentView(rootLayout)

        composeView.post {
            setupComposeTree()
            startAnimation()
        }

        startStatsUpdater()
    }

    private fun launchLeftGpuAdjacent() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        startActivity(intent)
    }

    private fun setupComposeTree() {
        val w = composeView.width
        val h = composeView.height
        if (w == 0 || h == 0) return
        val cardWidth = (w * 0.88).toInt()
        val cardHeight = (h * 0.44).toInt()

        composeView.setContent { root ->
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
                synchronized(motionTrail) {
                    motionTrail.forEachIndexed { index, yPos ->
                        dotPaint.alpha = (255 * (index + 1) / (motionTrail.size.coerceAtLeast(1))).coerceIn(30, 255)
                        canvas.getNativeCanvas().drawCircle(w / 2f + cardWidth / 2f + 10f, yPos, 4f, dotPaint)
                    }
                }
            }

            val cardNode = buildRichCardNode(cardWidth, cardHeight).apply {
                x = w / 2 - cardWidth / 2
                y = h / 2 - cardHeight / 2

                drawBlock = { canvas ->
                    drawCardVisuals(canvas, cardWidth, cardHeight)

                    val centerScreenY = (h / 2f) + (offsetNode?.offsetY ?: 0)
                    synchronized(motionTrail) {
                        motionTrail.addLast(centerScreenY)
                        if (motionTrail.size > 26) motionTrail.removeFirst()
                    }
                }
            }
            root.addChild(cardNode)
            offsetNode = cardNode
        }
    }

    private fun buildRichCardNode(cardWidth: Int, cardHeight: Int): LayoutNode {
        val rowCount = when (complexityLevel) {
            0 -> 18   // 101 nodes
            1 -> 98   // 501 nodes
            else -> 198 // 1001 nodes
        }

        val cardNode = LayoutNode("CpuCard").apply {
            width = cardWidth
            height = cardHeight

            measureBlock = { pw, ph ->
                if (simulatedLayoutDelayMs > 0) {
                    val start = System.nanoTime()
                    val targetNs = simulatedLayoutDelayMs * 1_000_000L
                    var dummy = 0.0
                    while (System.nanoTime() - start < targetNs) {
                        dummy += Math.sin(dummy + 1.0)
                    }
                }
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
        }

        // Header Row (5 nodes)
        val header = LayoutNode("HeaderRow").apply {
            width = cardWidth - 20
            height = 36
            measureBlock = { aw, _ ->
                val p = Paint().apply { textSize = 13f }
                Pair(aw, 36)
            }
        }
        header.addChild(LayoutNode("Avatar").apply { measureBlock = { _, _ -> Pair(24, 24) } })
        header.addChild(LayoutNode("Title").apply { measureBlock = { _, _ -> Pair(120, 14) } })
        header.addChild(LayoutNode("Subtitle").apply { measureBlock = { _, _ -> Pair(100, 12) } })
        header.addChild(LayoutNode("Timestamp").apply { measureBlock = { _, _ -> Pair(40, 12) } })
        cardNode.addChild(header)

        // Multiple Content Items (5 nodes per row)
        for (i in 0 until rowCount) {
            val itemRow = LayoutNode("ItemRow_$i").apply {
                width = cardWidth - 20
                height = 16
                measureBlock = { aw, _ ->
                    val textPaint = Paint().apply { textSize = 11f }
                    val textW = textPaint.measureText("Component #$i metrics data binding & constraints")
                    Pair(textW.toInt().coerceAtMost(aw), 16)
                }
            }
            itemRow.addChild(LayoutNode("Icon_$i").apply { measureBlock = { _, _ -> Pair(12, 12) } })
            itemRow.addChild(LayoutNode("RowTitle_$i").apply { measureBlock = { _, _ -> Pair(80, 14) } })
            itemRow.addChild(LayoutNode("Badge_$i").apply { measureBlock = { _, _ -> Pair(36, 14) } })
            itemRow.addChild(LayoutNode("Dot_$i").apply { measureBlock = { _, _ -> Pair(8, 8) } })
            cardNode.addChild(itemRow)
        }

        // Footer Action Row (5 nodes)
        val footer = LayoutNode("FooterRow").apply {
            width = cardWidth - 20
            height = 28
            measureBlock = { aw, _ -> Pair(aw, 28) }
        }
        for (action in listOf("Like", "Share", "Save", "Stats")) {
            footer.addChild(LayoutNode("Btn_$action").apply {
                measureBlock = { _, _ -> Pair(42, 24) }
            })
        }
        cardNode.addChild(footer)

        return cardNode
    }

    private fun drawCardVisuals(canvas: MiniCanvas, w: Int, h: Int) {
        val bgPaint = Paint().apply {
            color = Color.parseColor("#991B1B")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 18f, 18f, bgPaint)

        val borderPaint = Paint().apply {
            color = Color.parseColor("#F87171")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            isAntiAlias = true
        }
        canvas.drawRoundRect(1.5f, 1.5f, w.toFloat() - 1.5f, h.toFloat() - 1.5f, 16.5f, 16.5f, borderPaint)

        val avatarPaint = Paint().apply {
            color = Color.parseColor("#EF4444")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.getNativeCanvas().drawCircle(26f, 24f, 12f, avatarPaint)

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawText("CPU Card (:right_cpu)", 46f, 22f, titlePaint)

        val modePaint = Paint().apply {
            color = Color.parseColor("#FCA5A5")
            textSize = 11f
            isAntiAlias = true
        }
        canvas.drawText("PID ${Process.myPid()} | Re-measuring Subtree", 46f, 34f, modePaint)

        val textRowPaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            textSize = 10f
            isAntiAlias = true
        }
        val tagBgPaint = Paint().apply {
            color = Color.parseColor("#7F1D1D")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val tagTextPaint = Paint().apply {
            color = Color.parseColor("#FCA5A5")
            textSize = 8.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isAntiAlias = true
        }

        val dataLabels = listOf(
            "Node Layout Policy", "Constraint Bounds", "Flex Box Measure",
            "Text Glyph Layout", "Child Placement", "Subtree Traversal"
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

        // DisplayList Rebuild Simulation: Record extra drawing commands & text glyphs into Skia HWUI DisplayList
        if (drawLoadPasses > 0) {
            val extraPaint = Paint().apply {
                color = Color.parseColor("#EF4444")
                alpha = 20
                textSize = 7.5f
                style = Paint.Style.STROKE
                strokeWidth = 1f
                isAntiAlias = true
            }
            val extraTextPaint = Paint().apply {
                color = Color.parseColor("#FCA5A5")
                alpha = 25
                textSize = 7.5f
                isAntiAlias = true
            }
            val path = Path()
            for (p in 0 until drawLoadPasses) {
                val lineY = 38f + (p % 25) * 6.5f
                path.reset()
                path.moveTo(8f, lineY)
                path.lineTo(w - 8f, lineY + 2f)
                canvas.getNativeCanvas().drawPath(path, extraPaint)
                canvas.drawRoundRect(8f, lineY, w - 8f, lineY + 5f, 2f, 2f, extraPaint)
                canvas.drawText("DL Command #$p [Skia DL]", 14f, lineY + 4f, extraTextPaint)
            }
        }
    }

    private fun toggleAnimation() {
        isAnimating = !isAnimating
        if (isAnimating) {
            animateToggleButton.text = "⏹ Stop & Reset"
            animateToggleButton.setBackgroundColor(Color.parseColor("#B91C1C"))
            startAnimation()
        } else {
            animateToggleButton.text = "▶ Animate"
            animateToggleButton.setBackgroundColor(Color.parseColor("#15803D"))
            resetAnimation()
        }
    }

    private fun startAnimation() {
        isAnimating = true
        animateToggleButton.text = "⏹ Stop & Reset"
        animateToggleButton.setBackgroundColor(Color.parseColor("#B91C1C"))
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                animationProgress = animation.animatedValue as Float
                val viewHeight = composeView.height
                val amplitude = viewHeight * 0.26f
                val targetOffsetY = (Math.sin(animationProgress.toDouble() * Math.PI * 2) * amplitude).toInt()

                offsetNode?.let { node ->
                    node.offsetY = targetOffsetY
                    markTreeDirty(node)
                }
                composeView.getAndroidComposeView()?.invalidateCompose()
            }
            start()
        }
    }

    private fun resetAnimation() {
        animator?.cancel()
        animationProgress = 0f
        synchronized(motionTrail) { motionTrail.clear() }
        offsetNode?.let { node ->
            node.offsetY = 0
            markTreeDirty(node)
        }
        composeView.getAndroidComposeView()?.invalidateCompose()
    }

    private fun markTreeDirty(node: LayoutNode) {
        node.markNeedsLayout()
        for (child in node.children) {
            markTreeDirty(child)
        }
    }

    private fun cycleComplexity() {
        complexityLevel = (complexityLevel + 1) % 3
        when (complexityLevel) {
            0 -> complexityButton.text = "📊 Tree: Light (100)"
            1 -> complexityButton.text = "📊 Tree: Medium (500)"
            2 -> complexityButton.text = "📊 Tree: HEAVY (1000)"
        }
        setupComposeTree()
        startAnimation()
    }

    private fun cycleLayoutDelay() {
        simulatedLayoutDelayMs = when (simulatedLayoutDelayMs) {
            0L -> 8L
            8L -> 20L
            else -> 0L
        }
        when (simulatedLayoutDelayMs) {
            0L -> {
                layoutDelayButton.text = "🔥 Layout delay: 0ms"
                layoutDelayButton.setBackgroundColor(Color.parseColor("#334155"))
            }
            8L -> {
                layoutDelayButton.text = "🔥 Layout delay: 8ms"
                layoutDelayButton.setBackgroundColor(Color.parseColor("#D97706"))
            }
            20L -> {
                layoutDelayButton.text = "🔥 Layout delay: 20ms"
                layoutDelayButton.setBackgroundColor(Color.parseColor("#DC2626"))
            }
        }
    }

    private fun cycleDrawLoad() {
        drawLoadPasses = when (drawLoadPasses) {
            0 -> 200
            200 -> 400
            else -> 0
        }
        when (drawLoadPasses) {
            0 -> {
                drawDelayButton.text = "🎨 Draw load: Normal"
                drawDelayButton.setBackgroundColor(Color.parseColor("#334155"))
            }
            200 -> {
                drawDelayButton.text = "🎨 Draw load: +200 DL"
                drawDelayButton.setBackgroundColor(Color.parseColor("#D97706"))
            }
            400 -> {
                drawDelayButton.text = "🎨 Draw load: +400 DL"
                drawDelayButton.setBackgroundColor(Color.parseColor("#DC2626"))
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startStatsUpdater() {
        val updateRunnable = object : Runnable {
            override fun run() {
                val acv = composeView.getAndroidComposeView()
                if (acv != null) {
                    val draws = (acv.drawPassCount - lastDrawCount).coerceAtLeast(0)
                    val layouts = (acv.layoutPassCount - lastLayoutCount).coerceAtLeast(0)

                    val avgLayoutUs = if (layouts > 0) acv.windowLayoutTimeUs / layouts else 0L
                    val avgDrawUs = if (draws > 0) acv.windowDrawTimeUs / draws else 0L

                    acv.resetTimingWindow()
                    lastDrawCount = acv.drawPassCount
                    lastLayoutCount = acv.layoutPassCount

                    statsText.text = buildString {
                        appendLine("FPS: $draws fps | Passes: $layouts layouts/s")
                        appendLine("Layout Phase: ${avgLayoutUs} µs (RE-MEASURING)")
                        append("Draw Phase: ${avgDrawUs} µs | Total: ${avgLayoutUs + avgDrawUs} µs")
                    }

                    Log.i(TAG, "[:right_cpu | PID ${Process.myPid()}] FPS=$draws, Layout=${avgLayoutUs}µs ($layouts passes/s), Draw=${avgDrawUs}µs")
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
