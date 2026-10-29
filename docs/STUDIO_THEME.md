# Orbital studio theme

The title uses an animated particle field with perspective scale, depth-based brightness, tilted orbital paths and a lavender/teal/amber palette. This is a projected Canvas effect, not a separate OpenGL surface. The transparent orbital brand mark uses the same colors.

Floating docks and all shared panel shells use a quieter version of the same background. The Atelier uses a pale variant so ink previews and controls remain readable. Dialogs and popup menus share a static gradient; Settings and Your studio use the animated full-page backdrop. Toolbar icons now distinguish the studio library and More menu.

## Motion and interaction

- Display-synchronized scheduling, capped at roughly 30 updates/second for full-page backgrounds and 15 for small surfaces (actual cadence depends on display refresh).
- Hidden, detached and unfocused backgrounds stop scheduling frames. Decorative views neither accept touch nor receive accessibility focus.
- Canvas activity pauses ambient motion, including when automatic toolbar hiding is disabled.
- System animation disabling and Battery Saver produce a still background. Battery Saver changes are observed while attached; observers are removed on detach.
- No particles are rendered on the artwork or into exports. Background updates invalidate their own View and do not request a canvas repaint or backdrop snapshot.
- Atmospheric shading is cached in a 256×256 texture per surface. Orbit segments reuse a float buffer; particle points are batched by tint and depth. Paint and buffers are reused without per-frame object-list allocation.

## Verification

255 local tests pass, including a new visible/paused/hidden/detached motion scheduling regression. Debug lint and optimized release assembly pass. Eight tablet instrumentation tests passed with the theme; the final renderer also passed all seven functional device smoke tests. Captures are in the ignored local device-evidence folder; they may contain private drawings.

The title, layer panel and dock have been visually checked on the Galaxy Tab S9 FE. Ambient animation adds rendering work and is intentionally reduced around drawing controls. This does not resolve the separate dense-document performance limitations recorded in RELEASE_HARDENING.md.

A short unrecorded title sample before particle batching measured 22 ms median / 24 ms p95 versus 26 / 36 ms earlier. Android still reported missed display deadlines at the tablet refresh rate; this ambient effect is capped below display refresh and is not certified as a full-refresh-rate animation. Final particle batching further reduces command count but has no separate timing claim.
