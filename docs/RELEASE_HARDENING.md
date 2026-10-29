# Release hardening — 29 October 2026

## Implemented

- Autosave and pause saves capture detached document snapshots and coalesce revisions on one process-wide writer. Serialization and disk work run off the UI thread. A flushed sibling temporary file is atomically moved over the previous document; a failed replacement preserves the old file and reports failure. Activity recreation can restore the latest queued revision.
- Your studio provides named checkpoints, rename, duplicate, open, recoverable trash and restore. Existing recovery backups appear in the library. Portable `.zoomart` ZIP archives include document JSON and referenced image assets. Imports stage and validate before publishing, rewrite asset paths, and roll back staged assets on failure. Export selection survives activity recreation.
- Document loading checks numeric bounds, IDs, parent graphs, level references, duplicate JSON keys, nesting, points, object counts and relative asset paths. Limits: 64 MiB document/entry, 256 MiB decompressed archive, 10,000 archive entries, 4,096 levels, depth 512, 512 layers, 200,000 objects and 1,000,000 sampled points. These are resource safeguards, not an unlimited document promise.
- Bookmark interpolation uses logarithmic magnification and decimal coordinates relative to a common ancestor. Regression coverage includes flights below 180 nested levels, beyond usable root-space Double precision. Same-level extreme scale ratios now calculate in Double before returning local Float values.
- System cancellation, activity pause and drawing-view detachment cancel unfinished gestures. Eraser fragments and selection transforms roll back without adding history entries. Eraser transactions retain their original target layer.
- The dock exposes primary tools, labels, selected accessibility state, contextual selection commands and a More menu. Studio panels share titled gradient headers and bounded scrolling. Layer cards separate names from horizontally scrollable actions, increase action hit targets and fix drag-reorder payloads. Dock positions are constrained when the viewport changes.
- Library/panel/dock strings are resources; debug pseudolocales are enabled. Dock labels accommodate font scale. Activity recreation and explicit system-bar/cutout/IME insets replace fixed orientation handling. The welcome page scrolls in short windows.
- Compile/target SDK 36, AGP 8.9.2, Gradle 8.11.1. Release code/resource shrinking enabled. CI now builds release APK/AAB and mapping files and defines API 26/36 emulator smoke jobs.

## Verification

- 254 local tests pass, including atomic replacement, latest queued revision, detached snapshots, archive/image round trips, trash restoration, rename replacement, archive traversal, document validation, gesture rollback and deep flights.
- Compact layout tests cover 360×640 portrait, 640×360 landscape, a 320×480 narrow window and RTL.
- Eight instrumentation tests pass on Galaxy Tab S9 FE (SM-X510, Android 16), including real-window frame measurement. User files were backed up locally before release installation.
- Debug/release lint: zero errors; 94/95 warnings remain. These warnings are not considered resolved.
- Optimized release APK and AAB build successfully. A locally test-signed optimized APK was installed on the tablet: cold launch, session resume and panel navigation work. This test signature is not a production signing configuration.
- Visual checks found and fixed clipped layer names and an offscreen dock after simulated compact resizing. Display size and the pre-existing density override (320) were restored afterward. Simulated phone dimensions are not a physical phone or actual Samsung split-screen certification.
- Private screenshots, device logs and backups are kept under ignored `cache_devicetest/review_2026_10_29/`. Do not upload that directory as CI evidence; it contains user artwork.

## Drawing frame profile

Deterministic 40-point stroke grids rendered through a hardware-backed canvas while panning and zooming for six seconds. Measurements include cold rendering and are informational, not a stable benchmark gate. The profile runs in a debug-only isolated activity and does not open the user's document.

| Workload | Initial median / p95 | After cache and culling changes |
|---|---|---|
| 1,000 strokes / 40,000 points | 12.73 / 17.77 ms | 11.97 / 15.16 ms |
| 10,000 strokes / 400,000 points | 3,170 / 3,299 ms | 114.24 / 130.55 ms |

A final repeat measured 12.44 / 16.96 ms for 1,000 strokes and 149.55 / 167.94 ms for 10,000 strokes. This variation reinforces that the test is diagnostic rather than a performance guarantee.

The original 8 MiB outline cache thrashed when the working set exceeded it. Cache capacity now follows a bounded fraction of the app heap (8–32 MiB); spatial queries also reject offscreen neighbours within the same grid bucket. The improvement is substantial, but 10,000 visible strokes are still too slow for release-quality interaction. Next: separate UI/RenderThread/GPU costs and evaluate retained rendering or tiling with representative translucent brushes, images and many zoom levels. Do not extrapolate these grids to every real document.

## Remaining release work

1. Background snapshot capture still copies geometry on the UI thread. The executor is process-scoped, not a durable job: process termination before an atomic commit can lose the latest queued revision. Explicit project switching/startup still uses blocking durability barriers. Add process-kill/fault-injection coverage and an incremental journal or immutable document model before claiming crash-proof saving.
2. Archive operations are background Activity work, not resumable jobs. Interrupted exports can leave an incomplete destination file. Test multiple document providers, low storage, cancellation and process death; add progress/retry UI, thumbnails and orphaned-asset collection.
3. Deep validation is bounded and regression-tested, not exhaustively fuzzed. Keep resource limits consistent with authoring limits so extremely large documents cannot be created but then rejected on reopen. Profile extreme bookmark-flight costs and extend precision assertions beyond finite outputs.
4. Dense drawing performance remains a release blocker. Extend workloads to real mixed-brush documents, exports and extended S Pen sessions.
5. Localization is partial: older controls/messages remain hardcoded. Complete extraction and reviewed translations. Conduct TalkBack, Switch Access, large-font and contrast audits; resource-backed labels alone do not establish accessibility compliance.
6. Test actual split-screen, portrait/landscape transitions with open panels, keyboard/SAF interruption, and a physical phone. The current compact/RTL tests plus tablet resizing do not cover the full matrix.
7. CI configuration is written but has not run remotely. Production signing, upload-key handling, Play internal-track installation, upgrade testing and store checks remain. Local QA signing must never be used for a store release.
8. Other panel bodies still need further visual refinement and localization beyond their shared contextual shells. The layer layout was visually verified; do not infer every tool combination has been audited.

## Repeat local verification

Use JDK 17 and Android SDK 36:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease bundleRelease lintDebug lintRelease --console=plain
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w com.example.zoomart.test/androidx.test.runner.AndroidJUnitRunner
```

The frame profile writes `cache/frame_profile_1000.txt` and `cache/frame_profile_10000.txt` inside the debug app. Production artifacts remain unsigned until an appropriate release signing configuration is supplied.

References: [Android 16 setup](https://developer.android.com/about/versions/16/setup-sdk), [AGP/API compatibility](https://developer.android.com/build/releases/about-agp), [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16).

## Pen workflow follow-up

Floating panels dismiss when a stylus or stylus eraser contacts the canvas, including contact after a finger. Standard modal menus use outside-touch dismissal (the dismissal touch is consumed). Ink Lab shares stabilizer geometry, smoothing and taper with the canvas and offers Clear. Calligraphy no longer receives circular endpoint caps. The rope calculation now maintains its configured maximum distance behind the pen; regression expectations cover that distance at different zoom levels.

This follow-up does not resolve the previously documented dense-scene performance, complete localization, or full phone/split-screen release matrix. Those remain release gates.
