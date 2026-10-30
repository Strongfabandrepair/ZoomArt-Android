# Workspace previews and drawing aids — 30 October 2026

## Studio and exports

ProjectLibraryActivity filters names and loads at most 24 cards per page. Each card includes a 112×84 dp preview and a timestamp. ProjectThumbnails owns a detached renderer created on the UI thread and used exclusively by the library worker. Reading, parsing, rendering and cache writes run on that serial worker. Stale search results are discarded using a generation counter. Search/page state survives Activity recreation.

Thumbnails fit visible saved artwork to a maximum 320-pixel edge using the PNG renderer. Cache keys include checkpoint path, modification time and size. PNG cache writes are atomic; the cache retains at most 64 image files. A cache-write failure still permits displaying the rendered bitmap, and a rendering failure produces a placeholder. Blank saved content can produce an all-white preview. Preview data is derived, not a replacement for document data; imported archives regenerate it locally.

PNG export uses a detached snapshot to display a 512-pixel framing preview before Save image. Saving uses the established full-size export path. Completed PNGs carry a 384-pixel thumbnail in their sharing dialog. Video export attempts to decode a poster frame from the encoded file; failure to decode a thumbnail does not invalidate the export. Editing guides and live strokes are excluded. UI-thread snapshot capture remains a known cost on large drawings.

## Toolbar

Hide tools collapses the scrollable strip into a Show tools button. Both orientations retain a minimum touch target. The preference survives reopening. Dock positioning and automatic temporary hiding are separate: after drawing, the dock returns in its chosen expanded/collapsed state. Tool-panel dismissal remains permanent until explicit reopening.

## Symmetry

More → Drawing aids offers Off, vertical mirror, horizontal mirror and four-way symmetry. Axes are centered on the view, rotate with the canvas, and recenter with navigation. Their local coordinates and mode are captured when each freehand stroke starts. The model reflects pressure samples, level and taper metadata; live copies use the same renderer. Completed groups are a single document command, so Undo/Redo covers every copy. Exact duplicate geometry on an axis is suppressed. Cancelled or locked-layer gestures cannot commit a partial group.

The copies are ordinary strokes and survive save, archive and export with optional stampFlipX/stampFlipY brush flags to preserve reflected motifs and scatter. Missing flags default to false; older builds may ignore those flags. The aid setting is a workspace preference. Shape tools, erasers, smudges and selections are not mirrored. This is not a movable document-anchored or radial symmetry ruler; view-centered axes are intentional in this implementation.

## Stabilization

LazyBrush now offers ROPE, WEIGHTED and FINISH. Rope maintains a bounded trailing distance. Weighted is a per-sample low-pass filter whose response depends on radius and sampling cadence. Finish returns raw live positions and applies the existing midpoint-curve smoothing and taper at commit. Radius zero disables the effect. Ink Lab uses the same mode, with completed sample replay matching the finish path. The workspace stores the enabled method and strength; personal brush presets still describe brush appearance, not drawing-aid preferences.

## Verification and remaining work

267 local tests and nine isolated Galaxy Tab S9 FE smoke tests passed, along with debug/release APK builds and debug lint. Tests include grouped symmetry Undo/Redo and persistence, axis overlap, cancellation/locks, weighted/finish behavior, cache refresh, collapse recreation, and worker-based device thumbnail rendering. Tablet UI inspections covered gallery cards, collapse/expand, aids navigation and PNG preview.

New UI captures remain private in ignored cache_devicetest. The full phone/split-screen, TalkBack and localization matrix is still outstanding. First-time preview generation can be slow for large checkpoints. Symmetry multiplies geometry/rendering cost, and weighted smoothing is not time-normalized; neither is a claim of measured physical S Pen latency. There is no new dense-scene performance result. See [release hardening](RELEASE_HARDENING.md).
