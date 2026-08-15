# MiniCompose: Jetpack Compose Rendering Architecture Deep Dive

A minimal, educational reimplementation of Jetpack Compose's core rendering engine written from scratch in Kotlin. 

This project explores and demonstrates the 5 foundational architectural decisions that make Jetpack Compose high-performance, GPU-accelerated, and interoperable with Android's legacy View system.

---

## ⚡ Multi-Process Split-Screen Benchmark

To guarantee **100% thread isolation** between `Modifier.graphicsLayer` and `Modifier.offset`, the app runs across **two independent Linux OS processes** launched side-by-side in Android's native Split-Screen mode:

```
┌─────────────────────────────────────────┬─────────────────────────────────────────┐
│ Process 1: :left_gpu (PID 10420)        │ Process 2: :right_cpu (PID 10421)       │
│                                         │                                         │
│ ⚡ graphicsLayer (Draw Phase / GPU)     │ ⚠️ offset (Layout Phase / CPU)          │
│                                         │                                         │
│  • Dedicated Main UI Thread             │  • Dedicated Main UI Thread             │
│  • Dedicated RenderThread & ART VM      │  • Dedicated RenderThread & ART VM      │
│  • Layout Phase: 0 µs (SKIPPED)         │  • Layout Phase: ~8,500 µs (RE-MEASURE) │
│  • Frame Rate: Solid 60.0 FPS           │  • Frame Rate: Drops to ~15–30 FPS      │
└─────────────────────────────────────────┴─────────────────────────────────────────┘
```

> **Why Multi-Process Isolation Matters:** In a single-process demo, if the CPU side blocks the main thread for 20ms, it stalls the single shared Looper/Choreographer, artificially dragging down both views. With **two OS processes**, `:left_gpu` continues animating at a pristine **60 FPS** on the GPU even while `:right_cpu` is heavily overloaded with 1000-node layout passes and CPU delays.

---

## 🏛️ The 5 Core Architectural Insights

### 1. Compose in the View System (`ComposeView` & `AndroidComposeView`)
```
Android View Tree
  └─ MiniComposeView (ViewGroup)         ← Public API container
       └─ MiniAndroidComposeView (ViewGroup)  ← Internal bridge & Owner
            ├─ Root LayoutNode                ← Compose tree root
            └─ AndroidViewsHandler            ← Hosts embedded native Views
```
* **Why are they `ViewGroup`s and not `View`?**
  1. `ComposeView` hosts the internal `AndroidComposeView` as its sole child.
  2. `AndroidComposeView` must be a `ViewGroup` to host native Android views (`WebView`, `MapView`, etc.) embedded within the Compose hierarchy via `AndroidView`.

### 2. The Empty `onDraw()` Architecture
Android's `View.draw(Canvas)` pipeline executes in this order:
1. `drawBackground(canvas)`
2. `onDraw(canvas)` *(Draws the view's own content)*
3. `dispatchDraw(canvas)` *(Draws child views - ViewGroup only)*
4. `onDrawForeground(canvas)`

> **The Insight:** If Compose rendered its tree in `onDraw()`, all native child views (drawn in `dispatchDraw()`) would always paint **on top** of Compose UI. By leaving `onDraw()` empty and intercepting `dispatchDraw()`, Compose can precisely interleave `LayoutNode` rendering with native views for correct Z-ordering.

### 3. CanvasHolder: Zero-Allocation Bridge
Instead of allocating a new `androidx.compose.ui.graphics.Canvas` wrapper on every single frame, Compose uses a reusable [`CanvasHolder`](app/src/main/java/com/example/minicompose/CanvasHolder.kt) pattern:
```kotlin
class CanvasHolder {
    val miniCanvas = MiniCanvas()
    
    inline fun drawInto(targetCanvas: android.graphics.Canvas, block: MiniCanvas.() -> Unit) {
        miniCanvas.internalCanvas = targetCanvas
        miniCanvas.block()
        miniCanvas.internalCanvas = null
    }
}
```
This guarantees **0 object allocations** during the draw loop, preventing garbage collector pauses.

### 4. GraphicsLayer & RenderNode Separation
A hardware-accelerated [`GraphicsLayer`](app/src/main/java/com/example/minicompose/GraphicsLayer.kt) wraps Android's native C++ `android.graphics.RenderNode` (API 29+), which divides rendering into two distinct memory structures:

```
┌────────────────────────────────────────────────────────┐
│  RenderNode (Native C++ Object)                        │
│                                                        │
│  1. Header Properties (MUTABLE, < 1 µs to update)      │
│     • translationX, translationY                       │
│     • scaleX, scaleY, rotationZ, alpha                 │
│                                                        │
│  2. Display List (IMMUTABLE once recorded)             │
│     • drawRect(), drawText(), drawPath()               │
└────────────────────────────────────────────────────────┘
```
When animating properties on a `GraphicsLayer`, only the **Header Properties** are updated. The recorded Display List is never invalidated or re-recorded.

### 5. `Modifier.graphicsLayer` vs `Modifier.offset`

$$\text{Composition Phase} \longrightarrow \text{Layout Phase (Measure \& Place)} \longrightarrow \text{Draw Phase}$$

| Aspect | `Modifier.offset` (Right Box) | `Modifier.graphicsLayer` (Left Box) |
| :--- | :--- | :--- |
| **Phase** | **Layout Phase** | **Draw Phase** |
| **Action** | Updates `LayoutCoordinates` (`needsLayout = true`) | Updates GPU 4x4 matrix on `RenderNode` |
| **Tree Overhead** | Recursively traverses & re-measures $N$ nodes | **0 nodes traversed** (Layout Phase skipped) |
| **1000-Node Cost** | **~25,000 µs / frame** | **0 µs** ($<1\mu\text{s}$ C++ float write) |

---

## 📂 Project Structure

```
app/src/main/java/com/example/minicompose/
├── CanvasHolder.kt          # Zero-allocation canvas adapter
├── GraphicsLayer.kt         # Hardware RenderNode layer & Header Property setters
├── LayoutNode.kt            # Compose tree hierarchy, measure policy & dirty flags
├── MiniComposeView.kt       # Public MiniComposeView & MiniAndroidComposeView bridge
├── MainActivity.kt          # Left GPU Activity (:left_gpu process)
└── RightCpuActivity.kt      # Right CPU Activity (:right_cpu process)
```

---

## 🚀 Getting Started

### Prerequisites
* Android Studio Iguana / Ladybug or newer
* Android SDK (compileSdk 35, minSdk 26)
* JDK 11 or newer

### Building and Running
1. Clone the repository:
   ```bash
   git clone git@github.com:p47t/minicompose.git
   cd minicompose
   ```
2. Open the project in Android Studio.
3. Run on an Android Emulator or physical device (Android 10+ / API 29+ recommended).
4. Tap **`🚀 Launch CPU Process Side-by-Side (Split Screen)`** to open both processes adjacent to each other.
5. Experiment with the **`📊 Tree Size (100 / 500 / 1000 Nodes)`**, **`🔥 Layout delay (Phase 2)`**, and **`🎨 Draw delay (Phase 3 DisplayList Rebuild)`** toggles to see real-time process isolation and phase-specific profiling in action!

---

## 📜 License

MIT License. Feel free to use this project for educational and architectural research purposes.
