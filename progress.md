# ZoomArt-Android — Progress Log

Persistent project-state file. Add one checkpoint row per bug fix, fix, or new feature that lands; keep it in sync with code changes. This file lives at the repo root and travels with every commit.

---

## Project identity

- **Name:** ZoomArt-Android
- **What:** Android/Kotlin infinite-canvas vector drawing app (a clone of Infinitefy OU's Endless Zoom Canvas / ZoomArt): endlessly zoomable canvas, pressure-sensitive brushes, layers, bookmark pins, autosave, crash recovery, PNG/MP4 export
- **Module:** `app/` — Gradle Kotlin DSL, compileSdk 34
- **Toolchain:** JDK 17 at `C:/Users/silo1/AndroidSDK/jdk-17` (JAVA_HOME); Android SDK at `C:/Users/silo1/AndroidSDK` (platform-34, build-tools 34.0.0); build via `./gradlew` (wrapper); AVD `zoomart`
- **Verified device:** Galaxy Tab S9 FE (SM-X510, Android 16, S Pen), adb serial `ce081718958624350c7e`
- **Repo:** Git initialized, pushed to https://github.com/Strongfabandrepair/ZoomArt-Android (branch `main`)

**Current state — checkpoint 1 (v0.7.0 Phase 1, 2026-10-27)**

**New (Phase 1 — Title page + Settings):**
- `TitleActivity` — launcher: glass-styled title card with "Resume Project", "Start New", "Settings"; Resume disabled when no session exists; spring show-in animation
- `SettingsActivity` — default brush preset picker, auto-hide on/off toggle, autosave interval (off/15s/30s/60s), all persisted via `AppSettings` SharedPreferences
- `AppSettings` — SharedPreferences helper for brush preset index, auto-hide flag, autosave interval, and session-saved timestamp
- `MainActivity` — no longer launcher; accepts `zoomart.mode` intent extra ("new"/"resume"); applies default brush preset; clears session on "new"; records session saves for resume eligibility
- `OverlayLayer.autoHideEnabled` — user can disable auto-hide from Settings
- `AndroidManifest.xml` — TitleActivity is MAIN/LAUNCHER; MainActivity + SettingsActivity are non-exported

**Architecture (unchanged from baseline):** nested zoom levels, screen-relative brush width, pencil pressure→opacity, pen/finger routing, layers v2, 8 shapes, lazy brush, smudge, image v2, PNG/MP4 export, crash guard + autosave, UI overhaul glass panels.

**Verification (Phase 1):**
- Tests: **200/200 green** (all 20 pre-existing test classes untouched)
- APK builds via `./gradlew assembleDebug` (~3.36 MB)
- Device: APK installed on Galaxy Tab S9 FE; app launches to TitleActivity; Resume/Start New/Settings all respond

**Checkpoint 2 — UI/UX Redesign + Responsiveness + Gaps (v0.7.0 Phase 5, 2026-10-27):**
- Brand palette + zoomart_icon_512.png on TitleActivity/SettingsActivity
- Vector drawable icons for all toolbar actions (brush, erase, shapes, blend, layers, image, undo, redo, select, import, png, video, demo, pins, clear, flip, rot90, lazy, pick, zoom±, settings, resume, new)
- GlassStyle.iconButton + iconChip: icon above label on grouped toolbars
- Responsive cards: capped dp width, density-aware padding/sizing
- Gap 1: Calligraphy direction-varying width (stroke angle vs nib angle)
- Gap 2: Long-press drag layer reorder in LayersPanel
- Gap 3: FLIP mirrors images horizontally (flipH + render-time scale)
- All 200/200 tests green; APK installed on Tab S9 FE

**Checkpoint 3 — Dock fixes + canvas rotation + glass UI + haptics (v0.7.0 Phase 6, 2026-10-27):**
- Docks separated: Edit bottom-left, View/Export top-right (no overlap)
- Docks now draggable with handles
- canvasRotationDegrees + two-finger rotate gesture (canvas rotation, touch un-rotation)
- Translucent glass buttons (BUTTON_FILL 0x80, brand-blue active BUTTON_ACTIVE)
- Press/selection haptics (selectionTap, pressTap) wired to icon taps
- Icon press color feedback (Brand.BLUE on press)
- All 200/200 tests green; APK installed on Note 8

**Checkpoint 4 — Fix dock crash on rotate + toolbar scrolling + dock orientation (v0.7.0 Phase 7, 2026-10-27):**
- Removed unrotateEvent MotionEvent creation that caused ClassCastException crash on zoom/rotate
- Wrapped toolbar content in HorizontalScrollView/ScrollView so icons scroll when overflowing
- Added dock orientation setting (horizontal/vertical) in SettingsActivity
- Dock orientation persists via AppSettings and applies to dock gravity/layout
- Dock rest positions clamped to current screen dimensions on rotation (survives rotation)
- All 200/200 tests green; APK installed on Note 8 (SM_N950U)
- Device: APK installed on Galaxy Tab S9 FE; app launches to TitleActivity; Resume/Start New/Settings all respond

**Honest gaps (remaining):**
4. Image display filters are session-volatile UI-state by design (not serialized) — intentional.
5. docs/USER_GUIDE.md v0.5.0 refresh still in flight — verify against current source.

**Resolved in Phase 5:** calligraphy direction-varying width; long-press drag layer reorder; FLIP mirrors images; title page branding; vector icons on toolbars; responsive card sizing.

## Checkpoint log

| # | Date (US/Eastern) | Scope | Commit | Tests | Device | Notes |
|---|---|---|---|---|---|---|
| 0 | 2026-10-27 | Baseline: full P0–P4 + UI overhaul complete | *(initial commit)* | 200/200 green | Tab S9 FE P1–P4 passed | progress.md created; git repo initialized; pushed to GitHub |
| 1 | 2026-10-27 | Phase 1: Title page + Settings entry flow | *(phase 1 commit)* | 200/200 green | Tab S9 FE installed + launched | TitleActivity launcher; SettingsActivity persists; MainActivity honors entry mode |
| 2 | 2026-10-27 | Phase 2: Grouped floating toolbars + extendable panels | *(phase 2 commit)* | 200/200 green | Tab S9 FE installed + launched | 7 grouped toolbars; panels extend/collapse via chevron; all in auto-hide pipeline |
| 3 | 2026-10-27 | Phase 3: Erase-as-modifier + smart brush switching | *(phase 3 commit)* | 200/200 green | Tab S9 FE installed + launched | Erase toggles on current brush; tapping any brush preset returns to draw |
| 4 | 2026-10-27 | Phase 4: Live previews + color wheel | *(phase 4 commit)* | 200/200 green | Tab S9 FE installed + launched | BrushPanel live preview strip; ColorWheelView replaces HSV dialog |
| 5 | 2026-10-27 | Phase 5: Responsiveness + branding + icons + gap fixes | *(phase 5 commit)* | 200/200 green | Note 8 (SM_N950U) installed + launched | Brand icon + colors on title page; vector icons on all toolbars; responsive cards; FLIP mirrors images; calligraphy direction-varying width; long-press drag layer reorder |
| 6 | 2026-10-27 | Bug fixes + canvas rotation + glass UI + haptics | *(bug fix commit)* | 200/200 green | Note 8 (SM_N950U) installed + launched | OverlayLayer restores INVISIBLE docks; docks separated (Edit bottom-left, View/Export top-right); docks now draggable with handles; canvasRotationDegrees + two-finger rotate gesture; translucent glass buttons with brand-blue active state; press/selection haptics; icon press color feedback |
| 7 | 2026-10-27 | Fix: dock crash on rotate + toolbar scrolling + dock orientation | *(bug fix commit)* | 200/200 green | Note 8 (SM_N950U) installed + launched | Removed unrotateEvent MotionEvent crash; HorizontalScrollView/ScrollView wrap toolbar content for scrolling; dock orientation setting (horizontal/vertical) in SettingsActivity; dock rest positions clamped to screen on rotation |

## How to use this file

- After any bug fix, fix, or feature lands: append a checkpoint row (date, scope, commit hash, test result, device verification, notes).
- Update "Current state" and "Honest gaps" when they change.
- Tests: `./gradlew testDebugUnitTest` (expect 200/200 green at baseline).
- Device checks: `adb -s R52X200E6LK ...` on the Galaxy Tab S9 FE; never adb-swipe near the bottom toolbar (One UI gesture zone).

---
