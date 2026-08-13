package com.example.minicompose

import android.graphics.Paint
import android.os.Build

/**
 * ── The Compose Node Tree ───────────────────────────────────────────────────
 *
 * In Jetpack Compose, the UI is represented as a tree of [LayoutNode]s.
 * Each node goes through three phases:
 *   1. Composition — determine WHAT nodes exist (driven by @Composable functions)
 *   2. Layout     — determine WHERE and HOW BIG each node is (measure + place)
 *   3. Drawing    — determine HOW to paint each node onto the Canvas
 *
 * This minimal implementation demonstrates:
 * - Tree structure with parent/children relationships
 * - Simple measure & layout (fixed sizes for demo simplicity)
 * - Draw phase with optional [GraphicsLayer] for hardware acceleration
 *
 * Real Compose source: LayoutNode.kt
 */
class LayoutNode(val name: String = "Node") {

    var parent: LayoutNode? = null
        private set

    private val _children = mutableListOf<LayoutNode>()
    val children: List<LayoutNode> get() = _children

    // ── Measurement & Layout ────────────────────────────────────────────────

    /** Position relative to parent (set during layout phase). */
    var x: Int = 0
    var y: Int = 0

    /** Size in pixels (set during measure phase). */
    var width: Int = 0
    var height: Int = 0

    /**
     * Custom measure logic. Returns (width, height).
     * If null, uses the assigned [width] and [height] values.
     */
    var measureBlock: ((availableWidth: Int, availableHeight: Int) -> Pair<Int, Int>)? = null

    /**
     * Custom layout logic. Places children.
     * If null, children retain their assigned (x, y) positions.
     */
    var layoutBlock: ((LayoutNode) -> Unit)? = null

    // ── Drawing ─────────────────────────────────────────────────────────────

    /**
     * The draw callback for this node's own content.
     * Receives a [MiniCanvas] to draw on.
     */
    var drawBlock: ((MiniCanvas) -> Unit)? = null

    // ── Graphics Layer (Blog Sections 4 & 5) ────────────────────────────────

    /**
     * Optional hardware-accelerated layer.
     *
     * When set, this node's drawing commands are recorded into a [GraphicsLayer]
     * backed by [android.graphics.RenderNode]. Animation properties like
     * translationX, scaleX, rotationZ, alpha are updated as Header Properties
     * (< 1μs) WITHOUT re-recording the Display List.
     */
    var graphicsLayer: GraphicsLayer? = null

    /**
     * Callback to update [GraphicsLayer] header properties each frame.
     * This is analogous to `Modifier.graphicsLayer { translationX = ... }`.
     *
     * CRITICAL: This lambda only modifies header properties on the native
     * RenderNode — it does NOT trigger recomposition, re-layout, or
     * Display List re-recording!
     */
    var graphicsLayerBlock: ((GraphicsLayer) -> Unit)? = null

    // ── "Offset" position (Blog Section 5 comparison) ───────────────────────

    /**
     * Positional offset — analogous to `Modifier.offset`.
     *
     * Unlike [graphicsLayer] header properties, changing this triggers
     * a full re-layout of the subtree. This is the EXPENSIVE path.
     */
    var offsetX: Int = 0
    var offsetY: Int = 0

    // ── Dirty flags ─────────────────────────────────────────────────────────

    var needsLayout: Boolean = true
    var needsRedraw: Boolean = true

    // ── Tree operations ─────────────────────────────────────────────────────

    fun addChild(child: LayoutNode) {
        child.parent = this
        _children.add(child)
    }

    fun removeChild(child: LayoutNode) {
        child.parent = null
        _children.remove(child)
    }

    // ── Phase 2: Measure & Layout ───────────────────────────────────────────

    /**
     * Recursively measures and lays out the subtree.
     *
     * In real Compose, this is triggered by [AndroidComposeView.measureAndLayout()]
     * which is called at the start of [dispatchDraw] — ensuring the tree is
     * up-to-date before any drawing begins.
     */
    fun measureAndLayout(availableWidth: Int, availableHeight: Int) {
        // Measure: determine own size
        measureBlock?.let { measure ->
            val (w, h) = measure(availableWidth, availableHeight)
            width = w
            height = h
        }

        // Layout: place children
        layoutBlock?.invoke(this)

        // Recursively measure & layout children
        for (child in _children) {
            child.measureAndLayout(width, height)
        }

        needsLayout = false
    }

    // ── Phase 3: Draw ───────────────────────────────────────────────────────

    /**
     * Recursively draws this node and its children.
     *
     * If a [graphicsLayer] is attached:
     *   1. Update header properties via [graphicsLayerBlock] (< 1μs)
     *   2. Re-record Display List only if content changed ([needsRedraw])
     *   3. Ask GPU to render the RenderNode with current transforms
     *
     * If no graphicsLayer:
     *   1. Apply positional offset (x + offsetX, y + offsetY)
     *   2. Draw self via [drawBlock]
     *   3. Recursively draw children
     */
    fun draw(canvas: MiniCanvas) {
        val layer = graphicsLayer

        if (layer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // ── Hardware-accelerated path (graphicsLayer) ─────────────────

            // Step 1: Update header properties (the FAST path!)
            //
            // This is the key to smooth animation:
            //   - Only modifies native C++ float fields on the RenderNode
            //   - Cost: < 1 microsecond
            //   - NO recomposition, NO re-layout, NO Display List re-record
            graphicsLayerBlock?.invoke(layer)

            // Set pivot to center for intuitive rotation/scaling
            layer.pivotX = width / 2f
            layer.pivotY = height / 2f

            // Step 2: Re-record Display List ONLY if content changed
            if (layer.isDirty || needsRedraw) {
                layer.record(width, height) { recordingCanvas ->
                    // These draw commands become the immutable Display List.
                    // On subsequent animation frames, this block is NOT re-executed!
                    val tempCanvas = MiniCanvas()
                    tempCanvas.internalCanvas = recordingCanvas

                    drawBlock?.invoke(tempCanvas)
                    for (child in _children) {
                        tempCanvas.save()
                        tempCanvas.translate(
                            (child.x + child.offsetX).toFloat(),
                            (child.y + child.offsetY).toFloat()
                        )
                        child.draw(tempCanvas)
                        tempCanvas.restore()
                    }
                }
                needsRedraw = false
            }

            // Step 3: Ask the GPU to render the RenderNode
            // The RenderThread applies header properties as a 4x4 transform matrix
            canvas.getNativeCanvas().save()
            canvas.getNativeCanvas().translate(
                (x + offsetX).toFloat(),
                (y + offsetY).toFloat()
            )
            layer.drawInto(canvas)
            canvas.getNativeCanvas().restore()

        } else {
            // ── Software path (no graphicsLayer) ─────────────────────────
            canvas.save()
            canvas.translate((x + offsetX).toFloat(), (y + offsetY).toFloat())

            // Draw this node's content
            drawBlock?.invoke(canvas)

            // Recursively draw children
            for (child in _children) {
                child.draw(canvas)
            }

            canvas.restore()
        }
    }

    override fun toString(): String = "LayoutNode($name, ${width}x${height} at ($x,$y))"
}
