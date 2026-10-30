# Changelog

## Unreleased — orbital studio development snapshot

The application still reports version 0.7.0. This snapshot consolidates the preceding working changes and updates its identity and documentation; it is not a store release.

### Latest: gallery previews and drawing aids — 30 October 2026

- Studio artwork thumbnails, modification dates, name search, 24-card pagination and a capped private thumbnail cache.
- PNG framing preview with explicit Save/Cancel; completed PNG thumbnails and video poster frames where available.
- Remembered Hide tools / Show tools toolbar state in either dock orientation.
- View-centered left/right, top/bottom and four-way ink symmetry, live copies, one-step Undo/Redo and cancellation rollback; strokes exactly on a mirror axis are not double-deposited.
- Weighted trailing and finish-only smoothing alongside the rope stabilizer, shared with Ink Lab; workspace choices and strength persist.
- Verification: 267 local tests, nine isolated Galaxy Tab S9 FE smoke tests, debug/release APK assembly and debug lint passed. UI inspections covered gallery thumbnails, collapsed toolbar, aids menu and PNG preview.

### Previous: specialty brushes and persistent panel dismissal

- Expanded the built-in shelf from 9 to 24 brushes, with a labeled **Find a brush** picker.
- Added Technical pen, Expressive ink, Broad nib, Charcoal grain, Graphite shade, Pastel dust, Ink spatter, Mist, Stipple, Stitch, Stardust, Star chain, Foliage, Confetti and Dry chisel.
- Added round/star/leaf/chisel stamp rendering; new shapes persist in documents, archives and personal brush presets. Existing documents default to round stamps when the field is absent.
- Stylus contact closes floating panels immediately. The idle restore timer cannot reopen an explicitly dismissed panel; the dock still restores normally.
- Added the offline title-screen User Guide; Ink Lab Clear; shared stabilizer preview/smoothing/taper; corrected the stabilizer radius calculation and removed round calligraphy endpoint caps.
- Current verification: **259 local tests**, debug/release APK builds and debug lint passed. Tablet UI checks covered dismissal after the idle delay, the picker and Star chain preview. Earlier instrumentation counts below are historical, not a rerun of this snapshot.

### Drawing and reliability

- Renderer, eraser, selection, pressure/brush and export corrections with regression coverage.
- Brush Atelier, real brush previews, scratchpad, replay, custom presets and fine controls.
- Background transactional session writer, stronger document validation, project checkpoints, recoverable trash and portable archives.
- Deeper bookmark interpolation and cancellation rollback for unfinished edits.
- More precise viewport culling and a bounded heap-aware outline cache.

### Interface and identity

- Contextual dock and selection actions, shared panel styling, improved layer cards and compact-window positioning.
- Animated orbital backgrounds with drawing/visibility/power safeguards and batched particles.
- Original 24-unit vector icons with consistent line weight, recognizable tool silhouettes and updated title/launcher branding.
- Adaptive launcher artwork plus an Android 13+ monochrome themed-icon layer.
- Corrected Pins helper text to describe stylus double-tap.

### Build and documentation

- Compile/target SDK 36, AGP 8.9.2, Gradle 8.11.1 and optimized unsigned release artifacts.
- CI definitions for build/lint/release artifacts and API 26/36 emulator smoke checks.
- Rewritten README and user guide, documentation index, brand guide and shareable vector catalog.
- Historical implementation notes are marked as historical rather than used as the current product guide.

### Verification

Branding snapshot: **255 local tests and all eight Galaxy Tab S9 FE device tests passed**. Debug APK, test APK, optimized release APK and release bundle build successfully. Debug/release lint report zero errors (98/100 warnings remain). The title screen and adaptive launcher were visually checked on the tablet. Release readiness remains limited by dense-scene performance, incomplete accessibility/localization, process-death durability, actual phone/split-screen coverage, production signing and remote CI verification. See docs/RELEASE_HARDENING.md.
