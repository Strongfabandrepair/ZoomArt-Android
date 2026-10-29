# Brush Atelier implementation — 29 October 2026

This iteration adds a more expressive drawing workspace and addresses several remaining canvas defects. It builds on the [earlier review](REVIEW_2026-10-29.md); it does not complete every release priority in that report.

## Artist-facing changes

- **Brush Atelier:** plum-gradient heading, warm paper test pad, dotted guides and a horizontal brush shelf. The welcome screen uses original vector ribbons over a midnight/plum/teal background; it has no continuous animation loop.
- **Real previews:** both preset cards and the live test pad use the canvas's actual stroke renderer. The pad accepts finger or stylus input without touching the artwork. Replay draws the pressure sample through a short reveal animation.
- **Fine tuning:** size (1–80 px), opacity, pressure response, minimum width, nib angle, stamp spacing, scatter, dash length/gap, pressure-driven opacity and its minimum, eraser size and stabilizer controls. Dash and stamp modes are mutually exclusive. Sliders show values and use 48 dp control heights.
- **Personal brushes:** save up to 32 named presets on the device; an existing name replaces that preset. Hold a saved card to remove it after confirmation. Geometry/pressure settings survive recreation. Presets preserve the artist's current ink color and leave erase mode.
- **Motion and touch:** interruptible press/selection springs, expandable fine controls, preview replay and welcome/canvas fades. Slider release uses a light tick; successful tool selection uses contextual feedback. System haptic preferences are respected. Shared animations and floating-panel visibility support disabled system animations. There is no claim that every existing animation in the app has been migrated.

## Correctness and brush rendering

- Stamp spacing now continues across input segments instead of restarting at each point. Dense pencil/spray input no longer silently skips stamps because individual segments are shorter than the spacing. Jitter uses a stable seed, so adding samples does not randomly rearrange all earlier particles.
- Translucent stroke caps are merged into the outline before painting, removing darker overlap spots. The merged outline is cached with the existing geometry revision.
- The eraser clips segments against the swept path, including intersections where no sampled point is nearby. New boundaries interpolate pressure and retain the source level/brush/taper metadata. No-hit strokes retain identity; endpoint-only trims are applied even when the point count stays the same. Only the newest sweep is processed at each input event.
- Eraser clipping uses a conservative segment envelope based on maximum endpoint width. This can remove slightly more ink along strongly varying-width segments. Exact outline subtraction and independent treatment of newly cut taper endpoints remain future work.
- Camera transforms now find a shared ancestor and support sibling coordinate frames without routing through root coordinates. A regression covers siblings below 180 nested levels. Interactive level visibility and bookmark flight interpolation are unchanged; this is not a complete deep-navigation rewrite.

## Verification

- Debug app, test APK, unit/native-render tests and lint build passed.
- **241 local tests passed**; zero failed, errored or skipped.
- **7 Galaxy Tab S9 FE smoke tests passed**, including input routing, gallery PNG, hardware video and session restoration.
- **Zero lint errors; 69 warnings remain.** Localization, programmatic view constructors and existing performance/accessibility warnings still need triage.
- Final tablet software-render probe (1,000 strokes / 40,000 points): cold **257.85 ms**, warm median **15.15 ms**, warm p95 **15.82 ms** over 20 samples. Merging outlines adds preparation cost while reducing repeated drawing work. These are single-run software-Canvas timings, not measured interactive frame pacing; cold-frame work still needs optimization.
- New regression coverage includes swept eraser intersections, pressure interpolation, no-hit identity, endpoint trimming and undo, deep sibling transforms, stamp sampling density, cap opacity, custom-preset recreation and color/nib preservation during editing.
- On-device visual inspection covered the welcome screen, brush shelf, actual scratch-pad drawing, replay and scrolling to fine controls. Existing artwork was retained. Synthetic tests and screenshots do not establish physical S Pen latency, haptic quality or smooth frame pacing under heavy workloads.
- Local evidence: `cache_devicetest/review_2026_10_29/atelier_*`. It remains ignored by Git because this directory includes private artwork and backups.

## Still required before release

Background transactional saving; a recoverable project library and portable archives; deeper document validation; bookmark-flight precision and complete gesture cancellation; performance profiling on real drawing workloads; accessibility/localization; phone/split-screen coverage; current target SDK and a tested release pipeline. The dock and other panels also need the same contextual design treatment as the Atelier.

## Implementation references

[Android haptic feedback](https://developer.android.com/develop/ui/views/haptics/haptic-feedback) informed semantic view feedback and respecting system preferences. [ValueAnimator](https://developer.android.com/reference/android/animation/ValueAnimator) supplies the system animation-enabled check used by the new motion paths. No additional plugins or dependencies were necessary for this iteration.
