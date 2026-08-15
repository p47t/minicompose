package com.example.minicompose

import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * ── Blog Sections 4 & 5: Graphics Layer & RenderNode Architecture ──────────
 *
 * When Compose encounters `Modifier.graphicsLayer`, it creates an independent
 * drawing layer backed by Android's [RenderNode].
 *
 * A RenderNode contains TWO distinct parts in memory:
 *
 * ┌──────────────────────────────────────────────────────┐
 * │  RenderNode (native C++ object)                      │
 * │                                                      │
 * │  1. Header Properties (MUTABLE, < 1μs to update)     │
 * │     • translationX, translationY                     │
 * │     • scaleX, scaleY                                 │
 * │     • rotationX, rotationY, rotationZ                │
 * │     • alpha, elevation, pivotX, pivotY               │
 * │                                                      │
 * │  2. Display List (IMMUTABLE once recorded)            │
 * │     • drawRect(0, 0, 100, 100)                       │
 * │     • drawText("Hello")                              │
 * │     • drawBitmap(...)                                 │
 * └──────────────────────────────────────────────────────┘
 *
 * KEY INSIGHT: When animating with graphicsLayer, only the Header Properties
 * are updated (a single float assignment). The Display List is NOT re-recorded.
 * The RenderThread on the GPU can replay the unchanged Display List with the
 * new transform matrix, achieving 60/120 FPS even if the main thread is busy.
 *
 * Compare with Modifier.offset: changing offset triggers re-layout + re-record
 * of the Display List every frame — much more expensive!
 */

/**
 * Compositing strategy for a graphics layer.
 *
 * Real Compose: CompositingStrategy.kt
 */
enum class CompositingStrategy {
    /** System decides whether to allocate a GPU offscreen buffer. */
    Auto,
    /** Force GPU offscreen buffer (for complex BlendMode / masking). */
    Offscreen,
    /** Skip offscreen buffer; multiply alpha directly into draw commands. */
    ModulateAlpha
}

/**
 * A hardware-accelerated graphics layer wrapping [android.graphics.RenderNode].
 *
 * Demonstrates the core of Compose's rendering optimization:
 * - [record]: captures drawing commands into an immutable Display List
 * - Property setters: update only the native C++ header fields (< 1μs)
 * - [drawInto]: asks the GPU to replay the Display List with current transforms
 *
 * Real Compose sources:
 * - GraphicsLayerV29.android.kt
 * - RenderNodeLayer.android.kt
 */
@RequiresApi(Build.VERSION_CODES.Q)  // RenderNode public API requires API 29+
class GraphicsLayer(name: String = "MiniComposeLayer") {

    /** The native Android RenderNode — the heart of GPU-accelerated rendering. */
    private val renderNode: RenderNode = RenderNode(name)

    /** Tracks whether the Display List needs re-recording. */
    var isDirty: Boolean = true

    /** Invalidate the recorded DisplayList so it gets re-recorded on the next frame. */
    fun invalidate() {
        isDirty = true
    }

    var compositingStrategy: CompositingStrategy = CompositingStrategy.Auto

    // ╔══════════════════════════════════════════════════════════════════════╗
    // ║  HEADER PROPERTIES                                                  ║
    // ║                                                                     ║
    // ║  These write directly to the native C++ RenderNode fields.          ║
    // ║  Cost: < 1 microsecond per update.                                  ║
    // ║  The Display List is NOT re-recorded!                               ║
    // ║                                                                     ║
    // ║  This is WHY graphicsLayer animations are so smooth:                ║
    // ║  - No recomposition                                                 ║
    // ║  - No re-layout                                                     ║
    // ║  - No Display List re-recording                                     ║
    // ║  - Just a single float assignment on the native object              ║
    // ╚══════════════════════════════════════════════════════════════════════╝

    var translationX: Float = 0f
        set(value) {
            field = value
            renderNode.translationX = value  // Direct native write!
        }

    var translationY: Float = 0f
        set(value) {
            field = value
            renderNode.translationY = value
        }

    var scaleX: Float = 1f
        set(value) {
            field = value
            renderNode.scaleX = value
        }

    var scaleY: Float = 1f
        set(value) {
            field = value
            renderNode.scaleY = value
        }

    var rotationZ: Float = 0f
        set(value) {
            field = value
            renderNode.rotationZ = value
        }

    var rotationX: Float = 0f
        set(value) {
            field = value
            renderNode.rotationX = value
        }

    var rotationY: Float = 0f
        set(value) {
            field = value
            renderNode.rotationY = value
        }

    var alpha: Float = 1f
        set(value) {
            field = value
            renderNode.alpha = value
        }

    var elevation: Float = 0f
        set(value) {
            field = value
            renderNode.elevation = value
        }

    var pivotX: Float = 0f
        set(value) {
            field = value
            renderNode.pivotX = value
        }

    var pivotY: Float = 0f
        set(value) {
            field = value
            renderNode.pivotY = value
        }

    // ╔══════════════════════════════════════════════════════════════════════╗
    // ║  DISPLAY LIST RECORDING                                             ║
    // ║                                                                     ║
    // ║  Records draw commands (drawRect, drawText, drawPath...) into the   ║
    // ║  RenderNode's Display List. Once recorded, the list is immutable.   ║
    // ║                                                                     ║
    // ║  This is the EXPENSIVE operation — only done when content changes,  ║
    // ║  NOT on every animation frame.                                      ║
    // ╚══════════════════════════════════════════════════════════════════════╝

    /**
     * Records drawing commands into the RenderNode's Display List.
     *
     * After this call, the Display List is sealed — [drawInto] will replay it
     * with whatever Header Properties (translation, scale, rotation, alpha)
     * are currently set, entirely on the RenderThread/GPU.
     *
     * @param width  Width of the recording surface in pixels
     * @param height Height of the recording surface in pixels
     * @param block  Lambda that receives a raw [Canvas] to draw into
     */
    fun record(width: Int, height: Int, block: (Canvas) -> Unit) {
        renderNode.setPosition(0, 0, width, height)
        val recordingCanvas: Canvas = renderNode.beginRecording()
        try {
            block(recordingCanvas)
        } finally {
            renderNode.endRecording()
        }
        isDirty = false
    }

    /** Marks the Display List as needing re-recording on the next draw. */
    fun invalidate() {
        isDirty = true
    }

    // ╔══════════════════════════════════════════════════════════════════════╗
    // ║  RENDERING                                                          ║
    // ║                                                                     ║
    // ║  Draws the recorded Display List onto a target canvas.              ║
    // ║  The RenderThread applies the Header Properties as a 4x4 transform  ║
    // ║  matrix on the GPU — no main thread work needed!                    ║
    // ║                                                                     ║
    // ║  C++ pseudocode (from the blog):                                    ║
    // ║  void drawRenderNode(RenderNode* node, Canvas* canvas) {            ║
    // ║      canvas->save();                                                ║
    // ║      canvas->translate(node->getTranslationX(), ...);               ║
    // ║      canvas->scale(node->getScaleX(), ...);                         ║
    // ║      canvas->rotate(node->getRotation());                           ║
    // ║      canvas->setAlpha(node->getAlpha());                            ║
    // ║      canvas->drawDisplayList(node->getDisplayList()); // UNCHANGED! ║
    // ║      canvas->restore();                                             ║
    // ║  }                                                                  ║
    // ╚══════════════════════════════════════════════════════════════════════╝

    /**
     * Draws this layer's recorded Display List onto [canvas].
     *
     * The GPU applies current Header Properties (translation, scale, rotation,
     * alpha) as a hardware transform — the Display List itself is untouched.
     */
    fun drawInto(canvas: Canvas) {
        if (renderNode.hasDisplayList()) {
            canvas.drawRenderNode(renderNode)
        }
    }

    /**
     * Draws this layer via a [MiniCanvas] wrapper.
     * Unwraps to the native canvas for RenderNode compatibility.
     */
    fun drawInto(miniCanvas: MiniCanvas) {
        drawInto(miniCanvas.getNativeCanvas())
    }

    /** Returns true if a Display List has been recorded. */
    fun hasDisplayList(): Boolean = renderNode.hasDisplayList()
}
