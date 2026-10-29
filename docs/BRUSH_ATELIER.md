# Brush Atelier — current implementation

## Controls and collection

The Atelier combines a pale orbital panel, a real-renderer Ink Lab, a horizontal visual shelf and **Find a brush**, a labeled picker. There are **24 built-in brushes** and up to **32 device-local personal presets**. See the [user guide](USER_GUIDE.md) for the complete collection and usage instructions.

Ink Lab accepts finger or stylus input without editing the artwork. **Clear** empties it; **Replay** animates a generated pressure sample. Fine tuning includes size, opacity, pressure response, minimum width, nib angle, spacing/scatter, dash length/gap, pressure opacity, eraser size and stabilization. Stamp spacing and dashes are mutually exclusive. Zero pressure response intentionally ignores pressure for width. Personal presets preserve the current ink color when selected and exit erase mode.

The stabilizer uses the same rope model in the pad and canvas: the brush stays within the configured screen-space radius behind the pointer. Live strokes are smoothed; completed stabilized strokes receive smoothing and taper. Cancelling a preview gesture restores the previous preview. Selecting Lazy from the dock is reflected when reopening the Atelier.

## Specialty rendering and persistence

The original nine presets retain their indices so saved default-brush preferences remain valid. Fifteen appended presets add technical/expressive ink, broad nib lettering, procedural grain and dust, spatter, stipple, stitch and decorative marks. The renderer supports ROUND, STAR, LEAF and CHISEL stamp silhouettes. Preview, artwork and exports share this renderer and deterministic stamp placement.

`BrushSpec.stampShape` is serialized in document JSON and personal presets. Missing or unrecognized values fall back to ROUND. Portable archives use the document codec and retain the new field. Older app versions may ignore it and display round stamps. Shapes are procedural vector marks; there is no wet-media simulation or multicolor paint mixing.

Calligraphy outlines omit circular endpoint caps; a single angled-nib contact uses a narrow rectangular mark. Ordinary translucent round caps are merged into the outline to avoid darker overlaps. Existing swept eraser clipping, local-level transforms and bounded outline caching remain in place.

## Panel lifecycle

Stylus or stylus-eraser contact on the canvas immediately dismisses all floating tool shells, including contact after a finger. Dismissal cancels springs, marks the shell explicitly closed and removes it from interaction. The auto-hide idle timer cannot restore it. Only explicit reopening clears that state. The dock retains automatic hide/restore behavior. Modal dialogs still consume their outside-dismissal touch.

## Verification and limits

The specialty-brush snapshot passed **259 local tests**, debug/release APK assembly and debug lint. Regressions check dismissal across idle restore, distinct stamp silhouettes and identical rendering after document round trips. Galaxy Tab S9 FE checks covered dismissal, picker visibility/navigation and the Star chain preview; the build is installed on that tablet.

Earlier instrumentation and software-render measurements are historical evidence in [release hardening](RELEASE_HARDENING.md). This update does not establish physical S Pen latency, full phone/split-screen coverage, localization completion or release-ready dense-scene performance. Specialty stamps are rendered individually and still need representative workload profiling.

## Design references

[Procreate's brush library](https://help.procreate.com/procreate/handbook/brushes/brush-library) informed the breadth of drawing, charcoal and spatter categories. All presets and stamp geometry here are original procedural implementations; no third-party brush assets are bundled. See [Sketchbook brush types](https://help.sketchbook.com/docs/what-are-brush-types) for the distinction between standard brushes and natural-media blending, which this collection does not claim to reproduce.
