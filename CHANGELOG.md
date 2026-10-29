# Changelog

## Unreleased — orbital studio development snapshot

The application still reports version 0.7.0. This snapshot consolidates the preceding working changes and updates its identity and documentation; it is not a store release.

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
