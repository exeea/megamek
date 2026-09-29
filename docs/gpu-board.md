# GPU board

[Docs index](README.md). Native rendering lives in
[boardview/gpu](../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/).

## What the renderer owns

`GpuBoardWindow` manages the native libGDX/LWJGL3 window, its startup and shutdown.
[GpuBattleView](../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/GpuBattleView.java)
is the frame loop: it accepts source updates, prepares the scene, advances animation,
and draws the board, units, effects and Scene2D controls. There is one native application
context, so gameplay, previews and editing share that window ownership mechanism.

`ClientGUI` retains the network connection, phase controllers and common commands.
[BoardClientState](../megamek/src/megamek/client/ui/clientGUI/boardview/BoardClientState.java)
holds client presentation state used by the renderers. `BoardView` supplies classic
Swing painting when that view is selected. Native startup can use the shared state
without constructing the classic viewport, and the native capture API (planar hexes,
tactical geometry, field of view, overlays, annotations) exists only on the state.
Tests build a `BoardClientState` directly (`GpuBoardFixture`); a `BoardView` appears
only in tests that exercise both renderers on the same state.

The tactical and Free Flight cameras look at the same scene. `BoardCamera` handles
movement and view selection; `BoardProjectionCamera` handles projection and
`BoardCameraCollision` handles camera contact with the scene. Changing cameras retains
the same unit instances, picking geometry and playback timeline.

## From game state to a frame

[BoardSource](../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/BoardSource.java)
is the boundary between client state and rendering. Its two implementations have
different inputs:

- `GpuBoardSource` captures gameplay from `BoardClientState`, the game and the current
  phase controls. It applies existing visibility rules before capturing unit identity,
  loadouts, annotations and commands.
- `GpuMapSource` captures a preview or editor board directly. Editor operations and
  undo history remain with the existing editor.

Both use `BoardArtwork` for tileset lookup, ground artwork, decals and model selection.
They publish a `BoardScene` containing terrain and unit presentation data.
A `BoardSource.Frame` packages that scene with the animation events, command descriptions,
focus requests and other UI state needed by the native view.

On a render update, `GpuBattleView` takes a frame and feeds its events into
`UnitPlayback`. The playback state determines which positions, poses and scene changes
are currently displayed. Terrain changes go through `GpuTerrain`; unchanged chunks and
shared assets can stay installed. Camera movement changes the view and visible detail
without asking the game to construct another scene.

## Threads and update ownership

The Swing event thread owns access to live client presentation state and captures the
render snapshots. The render thread consumes that captured data. A native click is sent
back through the source adapter, where the existing phase or editor operation runs on
the Swing thread and rechecks current state.

Terrain workers prepare CPU geometry from owned snapshots. The GL thread creates GPU
objects, installs completed work and disposes retired resources. Board generations and
scene revisions distinguish current work from results or input belonging to a replaced
board. Picking must use the installed scene's coordinate system while a replacement is
being prepared.

Keep rules-derived visibility, legal moves, selection and command availability at the
client/game boundary. A render snapshot can retain an earlier visual state for playback;
it must not become an independent game model.

## Geometry and resource lifetime

`BoardGeometry`, `BoardSurface` and the terrain helpers provide the common geometric
basis for drawing, picking and unit support. Camera-specific code should project or
query that scene. `UnitPlayback` similarly provides one movement/combat timeline for
all views.

Closing a source stops its timer and removes its listeners. The native renderer releases
its GL resources when closed or replaced. Shared model and texture libraries own their
buffers; individual instances borrow them. A cache eviction or renderer switch must
respect that ownership to avoid leaking resources or disposing something still in use.

The [code responsibilities](gpu-code-map.md) lists each native class by subsystem.
Continue with [terrain](gpu-terrain.md) for surfaces and features,
[updates and caches](gpu-board-performance.md) for invalidation and batching,
[unit models](unit-models.md) for posing/playback, or
[UI and controls](controls-and-interaction.md) for the command bridge.
