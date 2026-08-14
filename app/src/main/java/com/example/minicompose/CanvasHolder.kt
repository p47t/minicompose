package com.example.minicompose

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * ── Blog Section 3: Zero-Allocation Canvas Adapter ──────────────────────────
 *
 * In Android, dispatchDraw provides an [android.graphics.Canvas].
 * Compose internally uses its own cross-platform Canvas interface.
 *
 * If Compose allocated a new wrapper object every frame (60-120 fps),
 * it would cause severe memory churn and GC pauses.
 *
 * The solution: [CanvasHolder] pre-allocates a single [MiniCanvas] wrapper
 * and swaps its internal canvas reference each frame — zero allocation!
 *
 * Real Compose source: AndroidCanvas.android.kt
 * ```kotlin
 * class CanvasHolder {
 *     val androidCanvas = AndroidCanvas()
 *     inline fun drawInto(target: Canvas, block: Canvas.() -> Unit) {
 *         val prev = androidCanvas.internalCanvas
 *         androidCanvas.internalCanvas = target
 *         androidCanvas.block()
 *         androidCanvas.internalCanvas = prev
 *     }
 * }
 * ```
 */

// ─── MiniCanvas: Compose's platform-agnostic Canvas abstraction ─────────────

/**
 * A thin wrapper around [android.graphics.Canvas] that provides
 * Compose-style drawing methods.
 *
 * KEY DESIGN: The [internalCanvas] field is mutable — [CanvasHolder]
 * swaps it each frame to avoid allocating a new MiniCanvas object.
 */
class MiniCanvas {
    /**
     * The underlying Android canvas. This reference is swapped by
     * [CanvasHolder.drawInto] every frame WITHOUT creating a new MiniCanvas.
     */
    @PublishedApi
    internal var internalCanvas: Canvas = Canvas()

    // ── Drawing primitives (delegated to the underlying Android canvas) ──

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        internalCanvas.drawRect(left, top, right, bottom, paint)
    }

    fun drawRoundRect(
        left: Float, top: Float, right: Float, bottom: Float,
        rx: Float, ry: Float, paint: Paint
    ) {
        internalCanvas.drawRoundRect(RectF(left, top, right, bottom), rx, ry, paint)
    }

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        internalCanvas.drawCircle(cx, cy, radius, paint)
    }

    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        internalCanvas.drawText(text, x, y, paint)
    }

    fun save(): Int = internalCanvas.save()

    fun restore() = internalCanvas.restore()

    fun translate(dx: Float, dy: Float) = internalCanvas.translate(dx, dy)

    fun clipRect(left: Float, top: Float, right: Float, bottom: Float) {
        internalCanvas.clipRect(left, top, right, bottom)
    }

    /**
     * Provides direct access to the underlying Android canvas.
     * Used by [GraphicsLayer] to draw RenderNodes via [Canvas.drawRenderNode].
     */
    fun getNativeCanvas(): Canvas = internalCanvas
}

// ─── CanvasHolder: The zero-allocation adapter ──────────────────────────────

/**
 * Bridges [android.graphics.Canvas] → [MiniCanvas] with ZERO memory allocation.
 *
 * Instead of creating a new MiniCanvas every frame, it pre-allocates one
 * and temporarily swaps its internal canvas reference for each draw pass.
 *
 * This is called from [MiniAndroidComposeView.dispatchDraw] every frame.
 */
class CanvasHolder {
    /** Pre-allocated — reused every single frame. */
    val miniCanvas: MiniCanvas = MiniCanvas()

    /**
     * Temporarily points [miniCanvas] at [targetCanvas], executes [block],
     * then restores the previous reference.
     *
     * ```
     * Frame N:   canvasHolder.drawInto(systemCanvasA) { /* draw */ }
     * Frame N+1: canvasHolder.drawInto(systemCanvasB) { /* draw */ }
     *            ↑ same MiniCanvas object, different underlying canvas
     * ```
     */
    inline fun drawInto(targetCanvas: Canvas, block: MiniCanvas.() -> Unit) {
        val previousCanvas = miniCanvas.internalCanvas  // save
        miniCanvas.internalCanvas = targetCanvas         // swap
        miniCanvas.block()                               // execute Compose drawing
        miniCanvas.internalCanvas = previousCanvas       // restore
    }
}
