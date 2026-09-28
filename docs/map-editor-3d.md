# 2D and 3D map editing

The bottom-right **3D Editor** button switches the current map to the native board renderer.
The existing editor controls move into a floating **Tools** palette. Both modes use the same board,
selected terrain, brush settings, file commands, and undo/redo history. Unsaved changes do not need
to be saved before switching.

Use **2D Editor** in the palette or native toolbar to return. Closing the native window also returns
to 2D; it does not discard edits. If the palette is hidden, reopen it with **Tools** in the toolbar.
File operations remain available through the editor menus and palette.

- Left-click or drag applies the selected editor tool and brush.
- Ctrl, Shift, and Alt retain their existing editor meanings: for example, Ctrl paints elevation with
  terrain, Alt samples a hex, and Shift/Alt raise/lower terrain when the elevation tool is selected.
- Ctrl + wheel over the map raises (up) or lowers (down) each hex in the current brush by one level
  per notch, preserving relative heights and terrain. This also works while a button or text field in
  **Tools** has focus, using the selected 1-, 7-, or 19-hex brush. Hold Ctrl to preview the brush; the only-on-clear
  filter also applies. Release Ctrl to finish one undo step. Focus loss or another editing action
  also finishes it. Rapid wheel ticks are combined between scene captures.
- Right-drag pans; middle-drag orbits. Shift swaps them: Shift + right-drag orbits, and Shift +
  middle-drag pans. The wheel zooms. These controls also apply to the game and map previews.
- Undo/redo shortcuts operate on the shared history. A brush drag is one undo step, including release
  over a toolbar, focus loss, or switching modes.

Each impassable hex has a transparent, flat marker with a red border and broad red diagonal stripes.
It uses the same floating plane as other hex markings; trees and other features may intersect it.
Hover strengthens the marking; gameplay also strengthens it while a movement path is being planned.
This presentation is shared by both camera views and map previews. It does not change elevation,
picking or movement legality. Geometry is retained across camera movement and updated when terrain
or emphasis changes, without clipping the stripes to the terrain mesh.

Flat hex annotations share `BoardTacticalGeometry.floatingZ`: the owning hex's highest finished
ground/water surface plus `HEX_PLANE_CLEARANCE`. Movement envelopes and modifiers, path arrows,
flight indicators, firing solutions, sensor/objective bands, and measurement/hover/editor cursors
use that plane in both cameras. Strafing/VTOL/orbital footprints, Nova CEWS and demolition selections,
predicted heat-map fills, ECM/ECCM source rings, embedded-board indicators and map-sheet borders
also use it. The raster compatibility layer uses the same helper with its completed terrain chunk,
including editor deployment/INVALID overlays and crane highlights, across terrain edits and detail changes.
Camera-facing tactical labels use the same height. Shape painters opt in through
`BoardTacticalGraphics.onHexPlane`; individual hex-border painters can request the same placement.
Unit bands retain their animated unit support, range walls retain their upright/overhead presentations,
and terrain tints still follow the ground. Ruler lines, drift arrows, C3 links and flyover routes span
hexes and retain terrain-following placement. Ordinary hex text retains its roof/depth-aware placement;
weapon-range letters retain their camera-dependent clearance.

The map browser's **Preview in 3D** action uses a temporary board view. Editing instead reuses the
editor's own `BoardView`. All changes run through the existing editor methods on the Swing thread;
the render thread receives snapshots and submits picked hexes with their board generation. Picks
from an old board are ignored after New, Open, or Resize replaces it.

The tools palette is a separate Swing window alongside the native viewport. Only one native board
window can run at a time. A renderer startup failure restores the existing 2D editor.

The on-demand `GpuBoardWindowSmokeTest.editorSwitchSharesBrushesUndoHistoryAndBoardAndRejectsStalePicks`
test exercises native picking, brush modifiers, Ctrl+wheel (including Tools focus, all brush sizes,
fractional scrolling and the only-on-clear filter), local scene updates, shared undo/redo, focus/UI boundaries, mode switching,
new-board input invalidation, and native-close restoration. Run it with:

```text
gradlew :megamek:gpuBoardSmoke --tests "*GpuBoardWindowSmokeTest.editorSwitch*"
```
