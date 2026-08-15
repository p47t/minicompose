package com.example.minicompose

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  Multi-Process Dual Rendering Coordinator — Process: `:left_gpu`
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Simultaneously renders BOTH processes side-by-side in the SAME window:
 *
 *  - LEFT COLUMN: Process `:left_gpu` (PID X)
 *    Uses Modifier.graphicsLayer (Draw Phase / GPU). Always runs at a solid 60 FPS.
 *
 *  - RIGHT COLUMN: Process `:right_cpu` (PID Y)
 *    Uses SurfaceControlViewHost to render from a completely separate OS process.
 *    Performs recursive 1000-node layout and CPU loads.
 *
 * Complete OS-level process isolation guarantees that stalls on the right
 * will NEVER affect the left!
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MultiProcessCoordinator"
    }

    // Left View (Process :left_gpu)
    private lateinit var leftComposeView: MiniComposeView
    private lateinit var leftStatsText: TextView
    private lateinit var leftHeader: TextView

    // Right Surface (Process :right_cpu)
    private lateinit var rightSurfaceView: SurfaceView
    private lateinit var rightStatsText: TextView
    private lateinit var rightHeader: TextView

    private lateinit var summaryBanner: TextView
    private lateinit var complexityButton: Button
    private lateinit var cpuLoadButton: Button

    private var rightServiceBinder: IBinder? = null
    private var isRightBound = false
    private var rightPid: Int = -1

    // Local Left animation state
    private var animationProgress: Float = 0f
    private var leftAnimator: ValueAnimator? = null
    private var complexityLevel: Int = 1
    private var simulatedCpuLoadMs: Long = 0L

    private var graphicsLayerNode: LayoutNode? = null
    private val leftTrail = ArrayDeque<Float>()

    private val handler = Handler(Looper.getMainLooper())
    private var leftLastDraw = 0L
    private var leftLastLayout = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rightServiceBinder = service
            isRightBound = true
            Log.i(TAG, "Connected to RightCpuService in :right_cpu process")
            attachSurfaceIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rightServiceBinder = null
            isRightBound = false
            Log.w(TAG, "Disconnected from RightCpuService")
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(0, 40, 0, 0)
        }

        // ── Title ──────────────────────────────────────────────────────────
        val titleText = TextView(this).apply {
            text = "Multi-Process Compose Benchmark"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(20, 8, 20, 2)
        }
        rootLayout.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "2 Isolated OS Processes Running Simultaneously Side-by-Side"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(20, 0, 20, 6)
        }
        rootLayout.addView(subtitleText)

        // ── Multi-Process Summary Banner ───────────────────────────────────
        summaryBanner = TextView(this).apply {
            text = "▸ Left: :left_gpu (PID ${Process.myPid()}) | Right: :right_cpu (Connecting...)"
            textSize = 11f
            setTextColor(Color.parseColor("#6EE7B7"))
            typeface = Typeface.MONOSPACE
            setPadding(20, 8, 20, 8)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        rootLayout.addView(summaryBanner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(12, 2, 12, 6) })

        // ── Side-by-Side Dual-Process Columns ──────────────────────────────
        val splitLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 0, 8, 0)
        }

        // ── LEFT COLUMN (Process :left_gpu) ────────────────────────────────
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(6, 6, 6, 6)
        }

        leftHeader = TextView(this).apply {
            text = "⚡ PROCESS: :left_gpu (PID ${Process.myPid()})\nModifier.graphicsLayer (Draw / GPU)"
            textSize = 11f
            setTextColor(Color.parseColor("#60A5FA"))
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(0, 2, 0, 4)
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
            text = "FPS: 60 fps | Passes: 0/s\nLayout: 0 µs (SKIPPED)\nDraw: 0 µs"
            textSize = 10f
            setTextColor(Color.parseColor("#4ADE80"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(2, 4, 2, 4)
        }
        leftColumn.addView(leftStatsText)

        // ── RIGHT COLUMN (Process :right_cpu via SurfaceView) ──────────────
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(6, 6, 6, 6)
        }

        rightHeader = TextView(this).apply {
            text = "⚠️ PROCESS: :right_cpu (Connecting...)\nModifier.offset (Layout / CPU)"
            textSize = 11f
            setTextColor(Color.parseColor("#F87171"))
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(0, 2, 0, 4)
        }
        rightColumn.addView(rightHeader)

        rightSurfaceView = SurfaceView(this).apply {
            setZOrderMediaOverlay(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    attachSurfaceIfReady()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    attachSurfaceIfReady()
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {}
            })
        }
        rightColumn.addView(rightSurfaceView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        rightStatsText = TextView(this).apply {
            text = "FPS: ~60 fps | Passes: 60/s\nLayout: Measuring...\nDraw: 0 µs"
            textSize = 10f
            setTextColor(Color.parseColor("#FCA5A5"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(2, 4, 2, 4)
        }
        rightColumn.addView(rightStatsText)

        splitLayout.addView(leftColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(3, 0, 3, 0)
        })
        splitLayout.addView(rightColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(3, 0, 3, 0)
        })
        rootLayout.addView(splitLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ── Interactive Multi-Process Controls ─────────────────────────────
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 4, 12, 28)
        }

        val animateButton = Button(this).apply {
            text = "▶ Animate"
            textSize = 11f
            setOnClickListener { startAnimation() }
        }

        complexityButton = Button(this).apply {
            text = "📊 Tree: 500 Nodes"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener { cycleComplexity() }
        }

        cpuLoadButton = Button(this).apply {
            text = "🔥 CPU Delay: 0ms"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener { cycleCpuLoad() }
        }

        buttonLayout.addView(animateButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) })
        buttonLayout.addView(complexityButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f).apply { setMargins(2, 0, 2, 0) })
        buttonLayout.addView(cpuLoadButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f).apply { setMargins(2, 0, 2, 0) })
        rootLayout.addView(buttonLayout)

        setContentView(rootLayout)

        // Bind to RightCpuService (:right_cpu process)
        bindService(Intent(this, RightCpuService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        // Setup local Left Compose Tree (:left_gpu process)
        leftComposeView.post {
            setupLeftComposeTree()
            startAnimation()
        }

        startStatsUpdater()
    }

    private fun attachSurfaceIfReady() {
        val service = rightServiceBinder ?: return
        val hostToken = rightSurfaceView.hostToken ?: return
        val w = rightSurfaceView.width
        val h = rightSurfaceView.height
        if (w <= 0 || h <= 0) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeStrongBinder(hostToken)
                    data.writeInt(w)
                    data.writeInt(h)
                    service.transact(RightCpuService.TRANSACTION_CREATE_SURFACE, data, reply, 0)
                    reply.readException()
                    rightPid = reply.readInt()
                    val hasPackage = reply.readInt()
                    if (hasPackage != 0) {
                        val surfacePackage = SurfaceControlViewHost.SurfacePackage.CREATOR.createFromParcel(reply)
                        rightSurfaceView.setChildSurfacePackage(surfacePackage)
                    }

                    rightHeader.text = "⚠️ PROCESS: :right_cpu (PID $rightPid)\nModifier.offset (Layout / CPU)"
                    summaryBanner.text = "▸ Left: :left_gpu (PID ${Process.myPid()}) ⚡ | Right: :right_cpu (PID $rightPid) ⚠️"
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error attaching SurfacePackage from :right_cpu", e)
            }
        }
    }

    private fun setupLeftComposeTree() {
        val w = leftComposeView.width
        val h = leftComposeView.height
        if (w == 0 || h == 0) return
        val cardWidth = (w * 0.86).toInt()
        val cardHeight = (h * 0.44).toInt()

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
                        canvas.getNativeCanvas().drawCircle(w / 2f - cardWidth / 2f - 8f, yPos, 4f, dotPaint)
                    }
                }
            }

            val cardNode = buildRichCardNode(cardWidth, cardHeight).apply {
                x = w / 2 - cardWidth / 2
                y = h / 2 - cardHeight / 2

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    graphicsLayer = GraphicsLayer("LeftGpuCard")
                }

                drawBlock = { canvas ->
                    drawCardVisuals(canvas, cardWidth, cardHeight)
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

    private fun buildRichCardNode(cardWidth: Int, cardHeight: Int): LayoutNode {
        val rowCount = when (complexityLevel) {
            0 -> 18   // 101 nodes
            1 -> 98   // 501 nodes
            else -> 198 // 1001 nodes
        }

        val cardNode = LayoutNode("GpuCard").apply {
            width = cardWidth
            height = cardHeight
            measureBlock = { pw, ph -> Pair(cardWidth, cardHeight) }
            layoutBlock = { parent ->
                var currY = 44
                for (child in parent.children) {
                    child.x = 8
                    child.y = currY
                    currY += child.height + 4
                }
            }
        }

        val header = LayoutNode("HeaderRow").apply {
            width = cardWidth - 16
            height = 36
            measureBlock = { aw, _ -> Pair(aw, 36) }
        }
        header.addChild(LayoutNode("Avatar").apply { measureBlock = { _, _ -> Pair(24, 24) } })
        header.addChild(LayoutNode("Title").apply { measureBlock = { _, _ -> Pair(110, 14) } })
        header.addChild(LayoutNode("Subtitle").apply { measureBlock = { _, _ -> Pair(90, 12) } })
        header.addChild(LayoutNode("Timestamp").apply { measureBlock = { _, _ -> Pair(35, 12) } })
        cardNode.addChild(header)

        for (i in 0 until rowCount) {
            val itemRow = LayoutNode("ItemRow_$i").apply {
                width = cardWidth - 16
                height = 16
                measureBlock = { aw, _ ->
                    val textPaint = Paint().apply { textSize = 11f }
                    val textW = textPaint.measureText("Component #$i metrics data binding & constraints")
                    Pair(textW.toInt().coerceAtMost(aw), 16)
                }
            }
            itemRow.addChild(LayoutNode("Icon_$i").apply { measureBlock = { _, _ -> Pair(10, 10) } })
            itemRow.addChild(LayoutNode("RowTitle_$i").apply { measureBlock = { _, _ -> Pair(75, 14) } })
            itemRow.addChild(LayoutNode("Badge_$i").apply { measureBlock = { _, _ -> Pair(32, 14) } })
            itemRow.addChild(LayoutNode("Dot_$i").apply { measureBlock = { _, _ -> Pair(8, 8) } })
            cardNode.addChild(itemRow)
        }

        val footer = LayoutNode("FooterRow").apply {
            width = cardWidth - 16
            height = 28
            measureBlock = { aw, _ -> Pair(aw, 28) }
        }
        for (action in listOf("Like", "Share", "Save", "Stats")) {
            footer.addChild(LayoutNode("Btn_$action").apply {
                measureBlock = { _, _ -> Pair(38, 24) }
            })
        }
        cardNode.addChild(footer)

        return cardNode
    }

    private fun drawCardVisuals(canvas: MiniCanvas, w: Int, h: Int) {
        val bgPaint = Paint().apply {
            color = Color.parseColor("#1D4ED8")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 16f, 16f, bgPaint)

        val borderPaint = Paint().apply {
            color = Color.parseColor("#60A5FA")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            isAntiAlias = true
        }
        canvas.drawRoundRect(1.5f, 1.5f, w.toFloat() - 1.5f, h.toFloat() - 1.5f, 14.5f, 14.5f, borderPaint)

        val avatarPaint = Paint().apply {
            color = Color.parseColor("#3B82F6")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.getNativeCanvas().drawCircle(22f, 22f, 10f, avatarPaint)

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawText("GPU Card (:left_gpu)", 38f, 20f, titlePaint)

        val modePaint = Paint().apply {
            color = Color.parseColor("#93C5FD")
            textSize = 10f
            isAntiAlias = true
        }
        canvas.drawText("PID ${Process.myPid()} | 0 µs Layout Phase", 38f, 32f, modePaint)

        val textRowPaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            textSize = 9.5f
            isAntiAlias = true
        }
        val tagBgPaint = Paint().apply {
            color = Color.parseColor("#1E3A8A")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val tagTextPaint = Paint().apply {
            color = Color.parseColor("#93C5FD")
            textSize = 8f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isAntiAlias = true
        }

        val dataLabels = listOf(
            "Node Layout Policy", "Constraint Bounds", "Flex Box Measure",
            "Text Glyph Layout", "Child Placement", "Subtree Traversal"
        )
        var yOffset = 48f
        val maxLines = when (complexityLevel) {
            0 -> 3
            1 -> 6
            else -> 9
        }
        for (i in 0 until maxLines) {
            val label = dataLabels[i % dataLabels.size]
            canvas.drawText("#${i + 1} $label", 12f, yOffset + 8f, textRowPaint)
            canvas.drawRoundRect(w - 42f, yOffset - 2f, w - 10f, yOffset + 10f, 4f, 4f, tagBgPaint)
            canvas.drawText("v${i + 1}", w - 26f, yOffset + 7f, tagTextPaint)
            yOffset += 15f
            if (yOffset > h - 36f) break
        }
    }

    private fun startAnimation() {
        leftAnimator?.cancel()
        leftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                animationProgress = animation.animatedValue as Float
                leftComposeView.getAndroidComposeView()?.invalidateCompose()
            }
            start()
        }
    }

    private fun cycleComplexity() {
        complexityLevel = (complexityLevel + 1) % 3
        when (complexityLevel) {
            0 -> complexityButton.text = "📊 Tree: 100 Nodes"
            1 -> complexityButton.text = "📊 Tree: 500 Nodes"
            2 -> complexityButton.text = "📊 Tree: 1000 Nodes"
        }
        val w = leftComposeView.width
        val h = leftComposeView.height
        if (w > 0 && h > 0) setupLeftComposeTree()
        startAnimation()

        // Notify RightCpuService (:right_cpu process)
        rightServiceBinder?.let { service ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInt(complexityLevel)
                service.transact(RightCpuService.TRANSACTION_SET_COMPLEXITY, data, reply, 0)
            } catch (_: Exception) {}
            finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private fun cycleCpuLoad() {
        simulatedCpuLoadMs = when (simulatedCpuLoadMs) {
            0L -> 8L
            8L -> 20L
            else -> 0L
        }
        when (simulatedCpuLoadMs) {
            0L -> {
                cpuLoadButton.text = "🔥 CPU Delay: 0ms"
                cpuLoadButton.setBackgroundColor(Color.parseColor("#334155"))
            }
            8L -> {
                cpuLoadButton.text = "🔥 CPU Delay: 8ms"
                cpuLoadButton.setBackgroundColor(Color.parseColor("#D97706"))
            }
            20L -> {
                cpuLoadButton.text = "🔥 CPU Delay: 20ms (JANK)"
                cpuLoadButton.setBackgroundColor(Color.parseColor("#DC2626"))
            }
        }

        // Send CPU load directly to :right_cpu process
        rightServiceBinder?.let { service ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeLong(simulatedCpuLoadMs)
                service.transact(RightCpuService.TRANSACTION_SET_CPU_LOAD, data, reply, 0)
            } catch (_: Exception) {}
            finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private fun resetAnimation() {
        leftAnimator?.cancel()
        animationProgress = 0f
        synchronized(leftTrail) { leftTrail.clear() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            graphicsLayerNode?.graphicsLayer?.let { layer ->
                layer.translationY = 0f
                layer.rotationZ = 0f
                layer.scaleX = 1f
                layer.scaleY = 1f
            }
        }
        leftComposeView.getAndroidComposeView()?.invalidateCompose()
    }

    @SuppressLint("SetTextI18n")
    private fun startStatsUpdater() {
        val updateRunnable = object : Runnable {
            override fun run() {
                // 1. Update Left Stats (:left_gpu process)
                val leftAcv = leftComposeView.getAndroidComposeView()
                if (leftAcv != null) {
                    val leftDraws = (leftAcv.drawPassCount - leftLastDraw).coerceAtLeast(0)
                    val leftLayouts = (leftAcv.layoutPassCount - leftLastLayout).coerceAtLeast(0)

                    val leftAvgLayoutUs = if (leftLayouts > 0) leftAcv.windowLayoutTimeUs / leftLayouts else 0L
                    val leftAvgDrawUs = if (leftDraws > 0) leftAcv.windowDrawTimeUs / leftDraws else 0L

                    leftAcv.resetTimingWindow()
                    leftLastDraw = leftAcv.drawPassCount
                    leftLastLayout = leftAcv.layoutPassCount

                    leftStatsText.text = buildString {
                        appendLine("FPS: $leftDraws fps | Passes: $leftLayouts/s (SKIPPED)")
                        appendLine("Layout: ${leftAvgLayoutUs} µs (0 µs)")
                        append("Draw: ${leftAvgDrawUs} µs | Total: ${leftAvgLayoutUs + leftAvgDrawUs} µs")
                    }

                    Log.i(TAG, "[:left_gpu | PID ${Process.myPid()}] FPS=$leftDraws, Layout=${leftAvgLayoutUs}µs ($leftLayouts passes/s), Draw=${leftAvgDrawUs}µs")
                }

                // 2. Fetch Right Stats across Binder from :right_cpu process
                rightServiceBinder?.let { service ->
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        service.transact(RightCpuService.TRANSACTION_GET_STATS, data, reply, 0)
                        reply.readException()
                        val rPid = reply.readInt()
                        val rFps = reply.readInt()
                        val rLayouts = reply.readLong()
                        val rLayoutUs = reply.readLong()
                        val rDrawUs = reply.readLong()

                        rightStatsText.text = buildString {
                            appendLine("FPS: $rFps fps | Passes: $rLayouts/s")
                            appendLine("Layout: ${rLayoutUs} µs (RE-MEASURING)")
                            append("Draw: ${rDrawUs} µs | Total: ${rLayoutUs + rDrawUs} µs")
                        }

                        Log.i(TAG, "[:right_cpu | PID $rPid] FPS=$rFps, Layout=${rLayoutUs}µs ($rLayouts passes/s), Draw=${rDrawUs}µs")
                    } catch (_: Exception) {}
                    finally {
                        data.recycle()
                        reply.recycle()
                    }
                }

                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(updateRunnable, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        leftAnimator?.cancel()
        if (isRightBound) {
            unbindService(serviceConnection)
            isRightBound = false
        }
        handler.removeCallbacksAndMessages(null)
    }
}
