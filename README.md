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
- Brush Atelier with live ink trials, replay, custom presets, spacing/scatter, dashes, pressure and stabilization controls.
- Layers, shape tools, image tracing, selections, swept vector erasing, bookmark flights and undo/redo.
- Background session serialization and atomic replacement, named project checkpoints, recoverable trash and portable `.zoomart` archives with image assets.
- PNG export of the current view or entire drawing, plus MP4 bookmark tours.
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

The branding snapshot passed **255 local tests** and **eight tablet instrumentation tests** on a **Galaxy Tab S9 FE / Android 16**. Debug/release builds and lint complete with zero errors (98/100 lint warnings remain). The updated title and launcher were visually checked on the tablet. A locally test-signed optimized APK was smoke-tested during the preceding release-hardening pass. Production signing and remote CI execution are not established.

Dense drawings remain a performance concern: a 10,000-stroke workload improved substantially but still measured roughly 114–150 ms median frame duration in earlier device runs. Full localization, accessibility auditing, actual split-screen/phone coverage, process-death durability and resumable archive operations remain unfinished. See the release-readiness report for details rather than treating passing tests as release certification.

## Architecture

Kotlin model code stores geometry in a tree of local zoom-coordinate frames. `Camera` composes transforms between levels; `StrokeIndex` narrows visible candidates; `ZoomableCanvasView` renders the scene and routes input. `DocumentCodec` and `DocumentValidation` handle persistence boundaries, `SessionWriter` handles queued saves, and `ProjectLibrary` manages checkpoints and archives. Exporters reuse the scene renderer. UI panels live separately from the canvas so their animations do not request artwork repaints.

CI configuration builds/tests/lints the app, produces unsigned release artifacts, and defines API 26/36 emulator smoke jobs. Device captures, artwork backups, local build output and machine configuration are intentionally excluded from Git.
