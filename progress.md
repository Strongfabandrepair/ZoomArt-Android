# ZoomArt-Android — Progress Log

Persistent project-state file. Add one checkpoint row per bug fix, fix, or new feature that lands; keep it in sync with code changes. This file lives at the repo root and travels with every commit.

---

## Project identity

- **Name:** ZoomArt-Android
- **What:** Android/Kotlin infinite-canvas vector drawing app (a clone of Infinitefy OU's Endless Zoom Canvas / ZoomArt): endlessly zoomable canvas, pressure-sensitive brushes, layers, bookmark pins, autosave, crash recovery, PNG/MP4 export
- **Module:** `app/` — Gradle Kotlin DSL, compileSdk 34
- **Toolchain:** JDK 17 at `C:/Users/silo1/AndroidSDK/jdk-17` (JAVA_HOME); Android SDK at `C:/Users/silo1/AndroidSDK` (platform-34, build-tools 34.0.0); build via `./gradlew` (wrapper); AVD `zoomart`
- **Verified device:** Galaxy Tab S9 FE (SM-X510, Android 16, S Pen), adb serial `R52X200E6LK`
- **Repo:** NOT yet under version control (created at checkpoint 0 below)

## Current state — checkpoint 0 baseline (2026-10-27)

**Architecture (complete):**
- Nested zoom levels: `ZoomLevel` tree; child origin = zoom focus; 1 child unit = 1/100 parent unit; promote/demote at the 100× threshold with exact screen-point continuity; cross-level log-space camera flights
- Brush widths screen-relative (stored as level-local units at commit); pencil pressure→opacity; pen draws / fingers pan+zoom routing with palm rejection
- Layers v2: duplicate/rename/reorder (Up/Dn MVP)/lock/opacity + Normal/Multiply/Screen/Overlay blend via `saveLayer` across all visible levels
- Tools: 8 parametric shapes (line, rect, rounded rect, ellipse, triangle, polygon, star, arrow), lazy-brush rope stabilizer with overlay + quadratic smoothing + end taper, blend/smudge with 80/20 color carry and soft vector stamps
- Image v2: own-layer import centered at camera level, Select-tool tap/move/corner-resize, pinch rotate+scale, transparency 0–100, lock, tracing mode (40% + lock + fresh layer), grayscale/contrast display filters (UI-state, not serialized), bitmap-blur note
- Export: PNG (world bbox up to 4096 px) and MP4 H.264 camera fly-through of pins
- Crash guard (session snapshot on uncaught exception) + atomic autosave (15 s + on pause) + crash recovery on relaunch
- UI overhaul (0.6.0): floating draggable glass panels, frosted backdrop blur (API 31+), spring micro-animations, auto-hide while drawing, glass dock, canvas HUD chip, position persistence

**Verification (this baseline):**
- Tests: **200/200 green** (`./gradlew testDebugUnitTest`, JVM + Robolectric) — 20 test classes incl. CameraLevelTest, P1SpecTest, P2LayersTest/P2SmokeTest, P3ShapesTest/P3LazyTest/P3SmudgeTest/P3SmokeTest, P4ImageUiTest/P4SmokeTest, AppSmokeTest (real stylus/finger MotionEvent dispatch headlessly), glass-panel tests
- APK: `app/build/outputs/apk/debug/app-debug.apk` (~3.36 MB), builds via `./gradlew assembleDebug`
- Device (Galaxy Tab S9 FE): P1, P2, P3, P4 build passes all logged 0 `FATAL EXCEPTION`s; finger pan + fling verified via adb swipes; pressure→opacity pixel-verified; blend modes pixel-verified via exported PNGs + PIL; image display filters pixel-verified; demo tour + cancellations verified; real-S-Pen gestures (image transforms, cross-level eraser) are Robolectric/JVM-verified — adb cannot inject stylus tool types, so S Pen feel remains a hand-test item

**Honest gaps (post-program, tracked here):**
1. Calligraphy: angled nib only; direction-varying width is an open gap
2. Layer reorder: Up/Dn buttons MVP; long-press drag reorder not implemented
3. FLIP does not mirror images (pinch rotate/scale used instead)
4. Image display filters are session-volatile UI-state by design (not serialized)
5. `docs/USER_GUIDE.md` v0.5.0 refresh was in flight at last save — verify against current source

## Checkpoint log

| # | Date (US/Eastern) | Scope | Commit | Tests | Device | Notes |
|---|---|---|---|---|---|---|
| 0 | 2026-10-27 | Baseline: full P0–P4 + UI overhaul complete | *(initial commit)* | 200/200 green | Tab S9 FE P1–P4 passed | progress.md created; git repo initialized; pushed to GitHub |

## How to use this file

- After any bug fix, fix, or feature lands: append a checkpoint row (date, scope, commit hash, test result, device verification, notes).
- Update "Current state" and "Honest gaps" when they change.
- Tests: `./gradlew testDebugUnitTest` (expect 200/200 green at baseline).
- Device checks: `adb -s R52X200E6LK ...` on the Galaxy Tab S9 FE; never adb-swipe near the bottom toolbar (One UI gesture zone).

---
