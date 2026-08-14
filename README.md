# MiniCompose: Jetpack Compose Rendering Architecture Deep Dive

A minimal, educational reimplementation of Jetpack Compose's core rendering engine written from scratch in Kotlin. 

This project explores and demonstrates the 5 foundational architectural decisions that make Jetpack Compose high-performance, GPU-accelerated, and interoperable with Android's legacy View system.

---

## 📸 Interactive Benchmark & Demo

The included Android app provides a live, split-screen microsecond ($\mu\text{s}$) benchmark comparing the two ways to animate elements in Jetpack Compose: **`Modifier.graphicsLayer`** (Draw Phase) vs. **`Modifier.offset`** (Layout Phase).

```
┌──────────────────────────────────────┬──────────────────────────────────────┐
│ ⚡ graphicsLayer (Draw Phase / GPU)   │ ⚠️ offset (Layout Phase / CPU)       │
│                                      │                                      │
│  [ GPU Rich Component Card ]         │  [ CPU Rich Component Card ]         │
│                                      │                                      │
│  • Layout Phase: 0 µs (SKIPPED)      │  • Layout Phase: 8,500+ µs (RE-MEASURE)│
│  • Draw Phase:   ~200 µs             │  • Draw Phase:   ~250 µs             │
│  • Passes: 0 layouts / 60 draws/s    │  • Passes: 60 layouts / 60 draws/s   │
└──────────────────────────────────────┴──────────────────────────────────────┘
```

### Key Interactive Features:
* **Deep Component Trees**: Benchmark trees of **100**, **500**, or **1000** `LayoutNode`s with realistic text measurement (`Paint.measureText`) and flex constraint solving.
* **Live Microsecond ($\mu\text{s}$) Profiling**: Real-time measurement of exact CPU time spent in the Layout Phase vs. Draw Phase using `System.nanoTime()`.
* **Motion Trail Tracks**: Visual trajectory dots demonstrating sub-pixel smoothness.

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
└── MainActivity.kt          # Split-screen live benchmark with 100/500/1000 node trees
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
3. Run on an Android Emulator or physical device (Android 10+ / API 29+ recommended for full `RenderNode` hardware acceleration).
4. Tap **`▶ Animate`** and experiment with the **`📊 Tree Size (100 vs 500 vs 1000 Nodes)`** switcher to observe live microsecond execution time differences.

---

## 📜 License

MIT License. Feel free to use this project for educational and architectural research purposes.
