# ZoomArt

**A little mark. An endless world.**

ZoomArt is an Android drawing studio for pressure-sensitive ink, layered artwork and exploring detail across nested zoom levels. The orbital identity connects its animated welcome screen, Brush Atelier, floating controls and project library.

This is a development build, currently **0.7.0**, for **Android 8.0+ (API 26)**, targeting **API 36**. It is not yet a production release. Deep zoom has resource and precision limits; imported images remain raster images.

## Start here

- [User guide](docs/USER_GUIDE.md): drawing, navigation, brushes, projects and exports.
- [Documentation index](docs/README.md): current references and historical implementation notes.
- [Brand and icon system](docs/BRAND_GUIDE.md): palette, vector artwork, adaptive launcher and icon catalog.
- [Release readiness](docs/RELEASE_HARDENING.md): measured performance, verification and unresolved release work.
- [Changes](CHANGELOG.md): the current development snapshot.

## What is implemented

- Pressure-sensitive stylus drawing, optional finger drawing, pan/pinch navigation and cancellation rollback.
- Brush Atelier with 24 built-in brushes, a labeled picker, live ink trials, Clear/Replay, personal presets and pressure/stabilization controls. Specialty stamps include stars, leaves and chisels; textures include charcoal grain, pastel dust and ink spatter.
- Collapsible dock with remembered state, mirror/four-way ink symmetry with grouped Undo, and Rope/Weighted/Finish stabilization.
- Floating panels close immediately on stylus contact and stay closed until reopened. The dock retains its separate automatic hide/restore behavior.
- Offline User Guide on the title screen, with Topics navigation and instructions bundled from this repository.
- Layers, shape tools, image tracing, selections, swept vector erasing, bookmark flights and undo/redo.
- Searchable studio gallery with cached artwork thumbnails, dates and bounded pagination.
- Background session serialization and atomic replacement, named project checkpoints, recoverable trash and portable `.zoomart` archives with image assets.
- PNG framing previews before saving, image thumbnails after export, and MP4 bookmark tours with poster frames when available.
- A shared orbital theme with reduced ambient motion on controls, system-animation and Battery Saver handling, and a consistent vector icon family.

## Build and verify

Use **JDK 17**, Android SDK **36**, and the checked-in Gradle wrapper (8.11.1 / Android Gradle Plugin 8.9.2). Configure the SDK using `local.properties` or the normal Android SDK environment variables. Do not commit machine-specific paths or signing keys.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease bundleRelease lintDebug lintRelease --console=plain
```

On macOS/Linux use `./gradlew` with the same tasks. Output locations:

| Artifact | Location |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Unsigned optimized APK | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Release bundle | `app/build/outputs/bundle/release/app-release.aab` |
| Test/lint reports | `app/build/reports/` |

Install debug and test APKs on a selected device, then run:

```text
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w com.example.zoomart.test/androidx.test.runner.AndroidJUnitRunner
```

The full device suite includes an isolated drawing frame profile. For functional smoke checks alone, add `-e class com.example.zoomart.DeviceSmokeTest` before the runner component. Back up artwork before testing an experimental build.

## Verification and limits

The 30 October workspace snapshot passed **267 local tests**, **nine isolated tablet smoke tests**, debug/release APK assembly and debug lint with zero errors. Galaxy Tab S9 FE / Android 16 UI checks covered studio thumbnails, toolbar collapse/expand, drawing-aids navigation and PNG framing preview. Regression tests cover symmetry grouping/cancellation, duplicate-axis suppression, smoothing modes, thumbnail caching and toolbar recreation.

Release-bundle and release-lint results from earlier snapshots remain historical; this pass did not rerun the full frame profile. Production signing and remote CI execution remain unverified.

Dense drawings remain a performance concern: a 10,000-stroke workload improved substantially but still measured roughly 114–150 ms median frame duration in earlier device runs. Full localization, accessibility auditing, actual split-screen/phone coverage, process-death durability and resumable archive operations remain unfinished. See the release-readiness report for details rather than treating passing tests as release certification.

## Architecture

Kotlin model code stores geometry in a tree of local zoom-coordinate frames. `Camera` composes transforms between levels; `StrokeIndex` narrows visible candidates; `ZoomableCanvasView` renders the scene and routes input. `DocumentCodec` and `DocumentValidation` handle persistence boundaries, `SessionWriter` handles queued saves, and `ProjectLibrary` manages checkpoints and archives. Exporters reuse the scene renderer. UI panels live separately from the canvas so their animations do not request artwork repaints.

CI configuration builds/tests/lints the app, produces unsigned release artifacts, and defines API 26/36 emulator smoke jobs. Device captures, artwork backups, local build output and machine configuration are intentionally excluded from Git.
