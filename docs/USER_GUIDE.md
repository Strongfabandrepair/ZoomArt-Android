# ZoomArt user guide

For the current **0.7.0 development build**. Requires Android 8.0 or later. A pressure-sensitive stylus such as an S Pen is recommended; finger drawing can be enabled in Settings.

Open **User Guide** on the title screen to read these instructions offline. Use **Topics** to jump to a section and **Back** to return to the welcome screen.

## Your first drawing

1. Open **ZoomArt**. Choose **Start New** on the welcome screen. If a session already exists, confirm only after reading the backup message.
2. Tap **Brush** in the dock to open **Brush Atelier**. Choose a preset and try it in the **Ink Lab** before drawing on your canvas.
3. Draw with your stylus. Use one finger to move around and two fingers to pinch/zoom. If you enabled finger drawing, use two fingers to navigate instead.
4. Tap the color swatch to choose a color. Use **Undo** and **Redo** to adjust recent edits.
5. Open **Studio → Save copy**, give the drawing a name and save a checkpoint. For a backup outside the app, export that checkpoint as a portable archive.

**Resume Project** opens the working session. **Your studio** opens saved checkpoints. **Settings** controls drawing and navigation preferences. The animated orbital background is decorative; it is not added to your drawing or exports.

## Dock and panels

The dock holds **Studio, Brush, Erase, Undo, Redo, Select, Color, Layers** and **More**. It can scroll if the window is narrow. Drag its thin top handle to reposition it. Settings can switch the dock between horizontal and vertical orientations.

**More** opens secondary tools: **Pick, Shapes, Blend, Lazy, Image, Pins, Import, Clear, Zoom out, Zoom in, PNG, Video** and **Demo**. **Delete, Flip and Rotate** appear when Select is active. Rotate advances the selection by 15 degrees per tap.

Tap a tool's button again where applicable to toggle its panel or mode. Panels have draggable handles and scrollable content. By default, the controls move out of the way while you draw and return when you stop. You can disable automatic hiding in Settings. Ambient background motion still pauses during canvas gestures.

## Navigate and draw

| Input | Default behavior |
|---|---|
| Stylus drag | Use the active drawing/editing tool; pressure affects brushes that support it. |
| Eraser-type stylus input | Erase, if the hardware supplies that input type. |
| One finger | Pan the canvas. |
| Two fingers | Navigate with pinch/zoom; selection transforms can use pinch and rotation when a selection is active. |
| Stylus double-tap | Add a bookmark pin. |
| Finger drawing enabled | A single finger draws; two fingers navigate. |

ZoomArt stores drawn geometry in nested local coordinate frames. Zooming can move into deeper levels, and the readout indicates zoom/depth. This supports very deep detail, but it is not a promise of unlimited precision or unlimited document size. Imported photographs retain their original pixel resolution. The loader applies depth, object-count and size limits to protect the app from oversized or malformed files.

## Brush Atelier

Open **Brush** and choose a preset from **Your brush shelf**. Use the **Ink Lab** scratchpad to try the actual brush renderer without changing your drawing. **Replay** animates a sample; **Save brush** stores your current brush settings as a personal preset on this device.

The main controls are **Size**, **Opacity** and **Pressure**. **Fine tune** reveals:

- **Nib angle** and **Minimum width** for the stroke's shape and pressure response.
- **Spacing** and **Scatter** for separated marks and spray-like effects.
- **Dash length** and **Dash gap** for broken lines. Dash and spacing modes may switch one another off.
- **Pressure controls opacity** and **Minimum opacity** for lighter or darker ink under pressure.
- **Erase size**, **Stabilizer** and its radius for editing and steadier curves.

The stabilizer makes the brush trail the pointer, smoothing small movements. A larger radius produces more lag. Brush effects vary by preset, so use the scratchpad when changing several settings together. Saved personal brush presets are device preferences; a portable project archive preserves the brush settings used by existing strokes, not your entire personal preset shelf.

## Color, erasing and undo

Tap the color swatch for the color picker. **More → Pick** arms the eyedropper; tap the canvas with the stylus to sample a color.

**Erase** toggles erasing for the current brush. The vector eraser cuts supported ink into surviving fragments on the active layer. Change its size in the Atelier. **Undo** can restore the completed erase gesture. Locked layers reject edits. Unfinished ink, erasing and selection transformations are cancelled when a gesture is cancelled or the activity pauses.

Undo history is an editing aid, not a backup. It is not preserved as a full history across app restarts. Use named checkpoints and exported archives for versions you want to retain.

## Layers and selections

**Layers** opens the layer stack. The selected radio button identifies the active layer. Tap a layer name to rename it, or use its checkbox to show/hide it. Each card has an action row that can scroll horizontally:

- **Lock/Unlock** protects editing.
- **Dup** duplicates the layer.
- **Normal / Multiply / Screen / Overlay** cycles its blend mode.
- **Up / Dn** changes stack order; long-press drag also supports reordering.
- **X** removes the layer. Use Undo promptly if this was accidental.
- **Opacity** changes the layer's transparency.

**+ Add layer** creates another sheet above the active one. Layers and zoom levels are different: one layer can contain details drawn at many zoom levels.

Choose **Select**, then use the stylus to drag a selection rectangle or tap a supported shape/image. Drag inside a selection to move it, use corner handles to resize, or use selection pinch/rotation gestures. The contextual **Delete**, **Flip** and **Rotate** commands operate on the selection. Tap Select again to leave selection mode. Locked content is protected.

## Shapes, blending and tracing

**More → Shapes** opens shape choices and their fill/outline controls. Draw a shape by dragging the stylus on the canvas; a live preview follows the gesture.

**More → Blend** opens the smudge/blend controls. Its results depend on the visible colors beneath the gesture. This tool is distinct from a layer's blend mode.

**More → Import** chooses an image through Android's file picker and places it on a new image layer near the current view. Use Select to move, resize, rotate or flip it. **More → Image** opens controls for the selected image, or the latest image on the active layer.

Image controls include opacity, locking, display filters and **Trace**. Trace dims and locks the reference and creates a drawing layer above it. Display filters are viewing aids and do not modify the original image file. Images will blur when zoomed far beyond their resolution.

## Pins and tours

Double-tap with the stylus to save a pin at a location and zoom level. Open **More → Pins**, then tap a pin to fly to that view. The pin's **X** button removes it. Pins are saved with the document.

**More → Video** exports a bookmark-based camera tour where supported. Create pins at useful views first. Exporting can take time, especially with dense documents. **Demo** runs the built-in demonstration; use your own named checkpoint before experimenting with demonstrations or major edits.

## Save, reopen and back up

ZoomArt maintains a working session using the autosave interval selected in Settings and saving when the activity pauses. Serialization and file replacement run in the background. A save that has not committed can still be lost if Android terminates the process, so do not treat autosave as an external backup.

**Studio → Save copy** creates a named checkpoint of the current session. A checkpoint is a saved snapshot, not a continuously updated project tab. After editing an opened checkpoint, use Save copy again to retain another version.

Tap a checkpoint to choose:

| Action | What it does |
|---|---|
| Open drawing | Open the saved snapshot as the current working session. |
| Rename | Change the checkpoint's name. |
| Duplicate | Create another saved copy. |
| Export portable archive | Choose an Android document destination for a `.zoomart` file containing the document and referenced images. |
| Trash | Move the checkpoint into recoverable local trash after confirmation. |

Use **Trash** at the top of the library to switch between the regular list and trash. Tap a trashed entry and choose **Restore** to return it. Recovery backups, when present, also appear in the regular library.

To move a drawing to another installation, export a portable archive, transfer it, then use **Studio → Import** on the destination device. The library's Import opens project archives; **More → Import** on the canvas imports an image. Keep the exported archive outside the app's private storage. Uninstalling the app or clearing app data can remove local sessions, checkpoints, trash and custom brush preferences.

Archive operations are not resumable background jobs. Keep the operation open until it completes; an interrupted export may leave an incomplete file. If import fails, retain the original archive and retry after checking available storage. Validation rejects malformed paths, missing referenced assets and oversized documents.

## Export pictures and video

**More → PNG** offers **Current view** or **Entire drawing**. Current view is useful for a detailed composition at your current zoom. Entire drawing fits supported content into a bounded output image, so very small deep details may become too small to see. Exported images are raster snapshots, not editable project archives.

**More → Video** creates an MP4 zoom tour using bookmark views. Supported destination and media-publication behavior depend on the Android version. Follow the app's export result message and use Android's Files/Gallery apps to locate completed output. For editable transfer, use a portable project archive instead.

## Settings, motion and troubleshooting

Settings includes default brush, automatic panel hiding, finger drawing, autosave interval and dock orientation. Android's animation setting and Battery Saver can stop ambient motion. Hidden or unfocused screens also stop animating. Disabling ambient motion does not affect the document.

| Symptom | Check |
|---|---|
| My finger moves the view instead of drawing | Enable finger drawing in Settings, or use a stylus. |
| Ink does not appear | Active layer visibility/lock, brush opacity, active tool and current zoom. |
| A control is missing | Scroll the dock, open More, or enter Select for selection commands. |
| The brush feels delayed | Reduce the stabilizer radius or disable it. |
| A saved drawing seems unchanged | Checkpoints are snapshots; create a new Save copy after editing. |
| An image looks pixelated | Imported images have finite resolution. |
| An archive cannot be opened | Confirm it is a `.zoomart` project archive, has all referenced assets and is within supported limits. |
| Large drawings respond slowly | Dense visible strokes, images and effects can exceed the current rendering budget. Save a checkpoint before continuing. |

This build has been tested on a Galaxy Tab S9 FE and in automated compact-layout checks. Full TalkBack/localization review, physical-phone and actual split-screen coverage, crash-durability testing and dense-document optimization are still in progress. See [release readiness](RELEASE_HARDENING.md) for the current limits and test evidence.
