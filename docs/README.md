# Documentation

## Current references

- [User guide](USER_GUIDE.md) — offline title-screen access, all 24 brushes, pen dismissal, drawing workflows and backups.
- [Brand guide](BRAND_GUIDE.md) — identity, palette, original vector icons and adaptive launcher.
- [Studio theme](STUDIO_THEME.md) — ambient animation and lifecycle behavior.
- [Brush Atelier](BRUSH_ATELIER.md) — specialty stamp rendering, previews, presets, persistence and panel lifecycle.
- [Release hardening](RELEASE_HARDENING.md) — implementation status, tests, profiling and remaining release gaps.
- [Repository README](../README.md) — setup, build and architecture overview.
- [Changelog](../CHANGELOG.md) — development snapshot history.

## Historical engineering notes

The P0–P5 phase documents, UI_OVERHAUL.md, UI_STYLE_SPEC.md and REVIEW_2026-10-29.md explain earlier implementation decisions. They are historical records, not the current user interface specification. Names, version counts, menus and performance claims in those notes may have been superseded. Prefer the current references above for product behavior and release readiness.

Local device screenshots, recordings and artwork backups are deliberately not published in this documentation. The checked-in brand SVGs contain only original artwork created for the project.

## Current evidence

The specialty-brush snapshot passed 259 local tests, debug/release APK builds and debug lint. Tablet checks covered panel dismissal and brush browsing/preview. Each current reference distinguishes these checks from earlier instrumentation runs. The guide is packaged from USER_GUIDE.md during the build, keeping the in-app offline content synchronized with this repository.
