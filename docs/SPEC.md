# Project Specification: Android Vector Drawing Canvas Clone (ZoomArt)

> Historical planning/engineering record. For the current product behavior, brush collection and verification status, use the [documentation index](README.md) and [user guide](USER_GUIDE.md). Earlier test counts and release targets below are preserved as historical evidence.

## 1. Goal & Vision
To build a high-fidelity, performance-optimized, and feature-rich Android drawing application clone of Infinitefy OU's Endless Zoom Canvas - ZoomArt. The core technical challenge is managing an infinite canvas surface that maintains perfect vector quality at arbitrary zoom levels, while supporting complex brush customization and layer management.

## 2. Target Platform & Stack
*   **Platform:** Android OS (Targeting API Level 30+ for modern APIs).
*   **Language:** Kotlin (Primary) / Java (Secondary/API interaction).
*   **Architecture:** MVVM (Model-View-ViewModel) utilizing Jetpack components.
*   **Drawing Core:** Custom `View` implementation extending Android's drawing framework (`android.view.View`), heavily relying on `Canvas`, `Matrix`, and `Path` for all drawing primitives to ensure vector precision.

## 3. Core Technical Modules (MVP Scope)

### A. Infinite Zoom Canvas Engine (The Core Challenge)
*   **Requirement:** Seamless, continuous zooming and panning across a theoretically infinite surface.
*   **Implementation Strategy:** Implement a custom `ZoomableCanvasView`. Instead of drawing to a finite bitmap, all coordinates must be managed in **"World Space"**. The `onDraw()` method will apply the current View's transformation matrix (scale/translate) derived from user input (pinch gestures).
    *   **Key Tool:** Android `Matrix` and `Canvas.save()/restore()`. Drawing operations (`Path`, `Paint`) must use world coordinates, which are then transformed by the canvas' current matrix when rendering to the screen viewport.
    *   **Performance Optimization:** Use a quadtree or tile-caching system (similar to map views) to only process and render drawing primitives/strokes within the visible bounds of the viewport, improving performance dramatically at high zoom levels.

### B. Vector Drawing Engine
*   **Requirement:** Draw precise vectors regardless of scale.
*   **Implementation Strategy:** All user input (finger/stylus path) must be immediately converted into an Android `Path` object, which is then persisted in the model layer as a sequence of points and control handles (for curve types).
    *   **Brush Customization:** Brush strokes should not rely on fixed brush images but rather on parametric definitions:
        1.  Base Shape (e.g., Circle, Line, Feather Curve).
        2.  Parameter Set (Thickness/Size function over distance, Sharpness, Corner Radius).
        3.  *Pressure Sensitivity:* The system must map raw input pressure data (`MotionEvent.getPressure()`) to the size or opacity parameters of the `Paint` object *dynamically* during drawing, not just at the end.

### C. State Management & Workflow Features
*   **Layer System:** Implement a stack/list of drawable layers. Each layer holds its own collection of persisted strokes/paths and is rendered independently by iterating through the layers in the `onDraw()` loop (e.g., Layer 1, then Layer 2, etc.).
*   **Undo/Redo:** Command pattern implementation. All drawing actions must be encapsulated as reversible commands (`StrokeAddedCommand`, `LayerVisibilityToggledCommand`).
*   **Autosave:** Implement a background worker that serializes the entire canvas state (all layers + all strokes) to local storage at fixed intervals (e.g., every 10 seconds).

### D. UI/UX Components
*   **Color Picker & Eyedropper:** Standard Android implementations. The eyedropper must be capable of sampling color from any pixel on the visible canvas, which requires careful interaction with the underlying `Bitmap` or rendering surface (potential performance hurdle to address).
*   **Bookmarks:** Allow users to save a world-coordinate point (`[x_world, y_world]`) and an optional small surrounding area. These bookmarks are stored in metadata and displayed as interactive anchors on the canvas.
*   **Sharing/Exporting:**
    *   High Resolution Image: Export must generate a bitmap large enough to encompass the current *visible* area at 100% scale, or allow the user to define an output crop region based on world coordinates.
    *   Animated Video: Requires recording the transformation matrices and drawing commands over time (e.g., simulating camera movement across bookmarks). This is a complex feature requiring separate rendering passes.

## 4. Implementation Phases (Roadmap)

1.  **Phase 1 (MVP):** Implement basic infinite panning/zooming of a single layer with simple vector lines. Focus on the `Matrix` math for coordinate transformation.
2.  **Phase 2:** Integrate customizable brushes and pressure-sensitive input handling in Kotlin's `onTouchEvent`.
3.  **Phase 3:** Build the Layer Manager and implement Undo/Redo functionality (Command Pattern).
4.  **Phase 4:** Implement advanced features: Bookmarks, Color Picker integration, Autosave persistence.
5.  **Phase 5:** Develop export features (Image/Video) and finalize UI polish.

## Technical Notes for the Scaffolder
*   The code should be written in Kotlin and follow Android best practices.
*   Since this is a scaffold, initial files should focus on structure: `MainActivity.kt`, `ZoomableCanvasView.kt` (the drawing engine), and `ViewModel.kt` (state management).

