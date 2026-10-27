# ZoomArt-Android — UI/UX Redesign Plan (v0.7.0 "Infinite Design")

## Source of truth / brief

User wants the 0.6.0 glass overhaul evolved into an "infinite design / Google Earth style" UI/UX:

1. **Grouped floating toolbars** — break the single horizontal dock into separate grouped floating toolbars, each extendable into a full panel when needed.
2. **Auto-hide on all toolbars** — panels slide away while drawing, spring back after idle.
3. **Live previews** — slider/color changes preview in-panel; no need to draw on canvas to see the effect.
4. **Micro-animation transitions** — spring show/hide, extend/collapse, tool-selection pulses.
5. **Smart tool switching** — erase is a brush modifier, not a locked mode. Tapping a brush type while erasing auto-returns to draw.
6. **Main title page** — resume existing project / start new one / settings before opening a canvas.
7. **Settings menu** — configure brush defaults, UI behavior, autosave, etc. pre-canvas.

Reference images (4 attached): category-based brush lists with named stroke previews; dark tool palette (Transform/Edit/Pattern/Canvas groups); circular color wheel with "easy color selection"; card-based unique tool set with per-brush preview strokes.

## Gap review (current 0.6.0 → target 0.7.0)

| Area | Current (0.6.0) | Target (0.7.0) | Gap |
|---|---|---|---|
| Entry | Straight to canvas on launch | Title page: resume / new / settings | Missing title activity |
| Toolbar | Single horizontal dock, all 24 buttons, 8 section labels | Separate grouped floating toolbars, each extendable | Dock is monolithic |
| Extendable panels | Panels toggle open/closed via toolbar buttons | Each toolbar extends into its panel inline | No extend/collapse per toolbar |
| Auto-hide | Stylus/pinch triggers hide on all panels | Same, extended to grouped toolbars | Partially covered |
| Erase | Dedicated ERASE tool mode; manual toggle | Erase as brush modifier; smart switch back to draw | Manual toggle only |
| Live preview | Sliders mutate BrushSpec; no visual preview | In-panel preview stroke/sample of current brush | No preview |
| Color | HSV dialog picker + swatch button | Circular color wheel + live swatch | Dialog-only picker |
| Settings | None | Settings screen (brush defaults, UI, autosave) | Missing |
| Animations | Spring show/hide + press feedback | + extend/collapse transitions, panel glide | Partial |

## Phased execution plan

### Phase 1 — Title page + Settings (entry flow)
- New `TitleActivity`: launcher; "Resume" (most recent session), "Start New", "Settings" buttons; glass-styled.
- New `SettingsActivity`: brush default preset, UI scale/auto-hide toggle, autosave interval, theme (dark only for now), about.
- `MainActivity` becomes the canvas activity (no longer launcher); `TitleActivity` launches it with a "new vs resume" intent extra.
- Update `AndroidManifest.xml`: TitleActivity is `MAIN/LAUNCHER`.

### Phase 2 — Grouped floating toolbars + extendable panels
- Replace the single dock with grouped floating toolbars: **Brushes**, **Select/Shapes**, **Blend/Smudge**, **Layers**, **Image**, **Edit**, **View/Export**.
- Each toolbar is its own `FloatingPanel` (compact row of icon buttons + a chevron).
- Chevron tap extends the toolbar into its full panel (BrushPanel, ShapesPanel, etc.) anchored in place; tap again collapses.
- All toolbars join the existing auto-hide pipeline.
- Keep gesture isolation + backdrop blur + position persistence.

### Phase 3 — Smart tool switching + erase-as-modifier
- `Erase` toolbar button toggles erase on the **current brush** (not a locked ERASE tool mode).
- `BrushSpec` gains an `erase: Boolean` field; the canvas reads it to decide draw vs. carve.
- Tapping any brush type while `erase == true` auto-resets to draw mode with that brush (smart switch — no manual "back to drawing" needed).
- Eraser end of the S Pen still forces erase (existing behavior preserved).

### Phase 4 — Live previews + color wheel
- `BrushPanel`: add a live preview strip showing the current brush stroke (size/opacity/pressure) rendered in-panel; updates on every slider change.
- New `ColorWheelView`: circular color wheel (reference image 3) replacing the HSV dialog; live swatch updates the brush color instantly.
- Slider dragging previews the stroke sample in real time (no canvas draw required).

### Phase 5 — Micro-animation polish + final QA
- Extend/collapse spring transitions for grouped toolbars.
- Tool-selection pulse on brush/toolbar taps.
- Run `./gradlew testDebugUnitTest` — keep 200/200 green (add new tests for title/settings/preview).
- Device verification on Galaxy Tab S9 FE.

## Success criteria (verifiable)

- App launches to TitleActivity (not straight to canvas).
- Title page has Resume / Start New / Settings.
- Settings screen persists to SharedPreferences and affects canvas behavior.
- Grouped toolbars replace the monolithic dock; each extends/collapses.
- Erase toggles on current brush; tapping a brush auto-returns to draw.
- Slider moves show an in-panel brush preview without touching canvas.
- Color wheel replaces HSV dialog; live swatch updates brush color.
- All 200+ tests green; APK builds and installs on Tab S9 FE.
- progress.md checkpoint added per phase.
