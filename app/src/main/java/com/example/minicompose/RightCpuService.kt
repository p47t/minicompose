package com.example.minicompose

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.SurfaceControlViewHost
import android.view.animation.LinearInterpolator
import androidx.annotation.RequiresApi

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  Right CPU Remote Render Service — Process: `:right_cpu`
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Runs in its own dedicated Linux OS Process (isolated PID, ART VM, UI Thread,
 * and RenderThread).
 *
 * Uses SurfaceControlViewHost (Android 11+ / API 30+) to render the heavy
 * `offset` MiniComposeView tree inside Process :right_cpu and stream the hardware
 * SurfacePackage into MainActivity for 100% thread-isolated side-by-side display.
 */
class RightCpuService : Service() {

    companion object {
        const val TRANSACTION_CREATE_SURFACE = 1
        const val TRANSACTION_SET_COMPLEXITY = 2
        const val TRANSACTION_SET_LAYOUT_DELAY = 3
        const val TRANSACTION_GET_STATS = 4
        const val TRANSACTION_SET_DRAW_LOAD = 5
        const val TRANSACTION_SET_ANIMATING = 6
        private const val TAG = "RightCpuProcess"
    }

    private var host: SurfaceControlViewHost? = null
    private var composeView: MiniComposeView? = null
    private var offsetNode: LayoutNode? = null

    private var animator: ValueAnimator? = null
    private var animationProgress: Float = 0f
    private var complexityLevel: Int = 1
    private var simulatedLayoutDelayMs: Long = 0L
    private var drawLoadPasses: Int = 0

    private var viewWidth: Int = 0
    private var viewHeight: Int = 0

    private val motionTrail = ArrayDeque<Float>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentFps = 60
    private var currentLayoutsPerSec = 60L
    private var currentLayoutUs = 0L
    private var currentDrawUs = 0L

    private var lastDrawCount = 0L
    private var lastLayoutCount = 0L

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TRANSACTION_CREATE_SURFACE -> {
                    val hostToken = data.readStrongBinder()
                    val width = data.readInt()
                    val height = data.readInt()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hostToken != null && reply != null) {
                        val future = java.util.concurrent.CompletableFuture<SurfaceControlViewHost.SurfacePackage?>()
                        mainHandler.post {
                            try {
                                val pkg = createEmbeddedViewHierarchy(hostToken, width, height)
                                future.complete(pkg)
                            } catch (e: Throwable) {
                                Log.e(TAG, "Error initializing SurfaceControlViewHost on main thread", e)
                                future.complete(null)
                            }
                        }

                        val surfacePackage = try {
                            future.get(3, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            Log.e(TAG, "Timeout waiting for SurfaceControlViewHost creation", e)
                            null
                        }

                        reply.writeNoException()
                        reply.writeInt(Process.myPid())
                        if (surfacePackage != null) {
                            reply.writeInt(1)
                            surfacePackage.writeToParcel(reply, 0)
                        } else {
                            reply.writeInt(0)
                        }
                    }
                    return true
                }
                TRANSACTION_SET_COMPLEXITY -> {
                    val level = data.readInt()
                    mainHandler.post { setComplexity(level) }
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_SET_LAYOUT_DELAY -> {
                    val delayMs = data.readLong()
                    mainHandler.post { simulatedLayoutDelayMs = delayMs }
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_SET_DRAW_LOAD -> {
                    val passes = data.readInt()
                    mainHandler.post { drawLoadPasses = passes }
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_SET_ANIMATING -> {
                    val shouldAnimate = data.readInt() != 0
                    mainHandler.post {
                        if (shouldAnimate) {
                            startAnimation()
                        } else {
                            resetAnimation()
                        }
                    }
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_GET_STATS -> {
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeInt(Process.myPid())
                        reply.writeInt(currentFps)
                        reply.writeLong(currentLayoutsPerSec)
                        reply.writeLong(currentLayoutUs)
                        reply.writeLong(currentDrawUs)
                    }
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createEmbeddedViewHierarchy(
        hostToken: IBinder,
        width: Int,
        height: Int
    ): SurfaceControlViewHost.SurfacePackage? {
        this.viewWidth = width
        this.viewHeight = height

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        val newHost = SurfaceControlViewHost(this, display, hostToken)
        this.host = newHost

        val newComposeView = MiniComposeView(this).apply {
            setBackgroundColor(Color.parseColor("#020617"))
        }
        this.composeView = newComposeView

        // Directly set MiniComposeView as the root view in SurfaceControlViewHost
        newHost.setView(newComposeView, width, height)

        setupComposeTree(width, height)
        startAnimation()
        startStatsUpdater()

        return newHost.surfacePackage
    }

    private fun setupComposeTree(w: Int, h: Int) {
        val view = composeView ?: return
        val cardWidth = (w * 0.86).toInt().coerceAtLeast(100)
        val cardHeight = (h * 0.44).toInt().coerceAtLeast(100)

        view.setContent { root ->
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
                        canvas.getNativeCanvas().drawCircle(w / 2f + cardWidth / 2f + 8f, yPos, 4f, dotPaint)
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
                    child.x = 8
                    child.y = currY
                    currY += child.height + 4
                }
            }
        }

        // Header Row (5 nodes)
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

        // Content Rows (5 nodes per row)
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

        // Footer Action Row (5 nodes)
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
            color = Color.parseColor("#991B1B")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 16f, 16f, bgPaint)

        val borderPaint = Paint().apply {
            color = Color.parseColor("#F87171")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            isAntiAlias = true
        }
        canvas.drawRoundRect(1.5f, 1.5f, w.toFloat() - 1.5f, h.toFloat() - 1.5f, 14.5f, 14.5f, borderPaint)

        val avatarPaint = Paint().apply {
            color = Color.parseColor("#EF4444")
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
        canvas.drawText("CPU Card (:right_cpu)", 38f, 20f, titlePaint)

        val modePaint = Paint().apply {
            color = Color.parseColor("#FCA5A5")
            textSize = 10f
            isAntiAlias = true
        }
        canvas.drawText("PID ${Process.myPid()} | Re-measuring Subtree", 38f, 32f, modePaint)

        val textRowPaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            textSize = 9.5f
            isAntiAlias = true
        }
        val tagBgPaint = Paint().apply {
            color = Color.parseColor("#7F1D1D")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val tagTextPaint = Paint().apply {
            color = Color.parseColor("#FCA5A5")
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

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                animationProgress = animation.animatedValue as Float
                val view = composeView ?: return@addUpdateListener
                val h = if (view.height > 0) view.height else viewHeight
                val amplitude = h * 0.26f
                val targetOffsetY = (Math.sin(animationProgress.toDouble() * Math.PI * 2) * amplitude).toInt()

                offsetNode?.let { node ->
                    node.offsetY = targetOffsetY
                    markTreeDirty(node)
                }
                view.getAndroidComposeView()?.invalidateCompose()
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
        composeView?.getAndroidComposeView()?.invalidateCompose()
    }

    private fun markTreeDirty(node: LayoutNode) {
        node.markNeedsLayout()
        for (child in node.children) {
            markTreeDirty(child)
        }
    }

    private fun setComplexity(level: Int) {
        complexityLevel = level
        val w = if (viewWidth > 0) viewWidth else 300
        val h = if (viewHeight > 0) viewHeight else 600
        setupComposeTree(w, h)
        startAnimation()
    }

    @SuppressLint("SetTextI18n")
    private fun startStatsUpdater() {
        val updateRunnable = object : Runnable {
            override fun run() {
                val acv = composeView?.getAndroidComposeView()
                if (acv != null) {
                    val draws = (acv.drawPassCount - lastDrawCount).coerceAtLeast(0)
                    val layouts = (acv.layoutPassCount - lastLayoutCount).coerceAtLeast(0)

                    currentFps = draws.toInt()
                    currentLayoutsPerSec = layouts
                    currentLayoutUs = if (layouts > 0) acv.windowLayoutTimeUs / layouts else 0L
                    currentDrawUs = if (draws > 0) acv.windowDrawTimeUs / draws else 0L

                    acv.resetTimingWindow()
                    lastDrawCount = acv.drawPassCount
                    lastLayoutCount = acv.layoutPassCount

                    Log.i(TAG, "[:right_cpu | PID ${Process.myPid()}] FPS=$draws, Layout=${currentLayoutUs}µs ($layouts passes/s), Draw=${currentDrawUs}µs")
                }
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.postDelayed(updateRunnable, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        animator?.cancel()
        host?.release()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
