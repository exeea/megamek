# GPU board

[Docs index](README.md). Native rendering lives in
[boardview/gpu](../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/).

## What the renderer owns

`GpuBoardWindow` manages the native libGDX/LWJGL3 window, its startup and shutdown.
[GpuBattleView](../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/GpuBattleView.java)
is the frame loop: it accepts source updates, prepares the scene, advances animation,
and draws the board, units, effects and Scene2D controls. There is one native application
context, so gameplay, previews and editing share that window ownership mechanism.

The battle HUD drawn over the board is documented in [gpu-hud.md](gpu-hud.md). The board editor
and the map preview get the HUD's map tools (`GpuMapHud`) instead.

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

## Unit display

GPU Tuning's General tab has a **Unit display** selector, applied live in both cameras:

- **All Meeples** draws every visible unit as an extruded token from its existing artwork.
- **Mek Meeples** is the default: Meks use tokens; other units use their existing 3D models.
- **3D Models** restores the existing authored-model presentation, including flat artwork when a model is unavailable.

The Defaults button restores Mek Meeples. Sensor contacts retain their anonymous markers, and tactical overview
icons still follow the overview setting. The token mesh reuses the libGDX branch's alpha-contour extrusion through
`GpuCutout`. Both cameras use the same posed geometry for drawing, picking, shadows, attachments and effects.

`MeepleVisual` owns token geometry, wall paint and attachment sockets. `MeepleAnimator` supplies rigid token poses;
`UnitAnimator` retains articulated model poses. Both sample the existing movement/combat timeline. Walkers bounce,
vehicles rock subtly and airborne craft bank through turns; displacement slides. Push/punch/kick and the stronger
club swing or lance thrust approach and recover on the physical attack clock. Jumps reuse flame, smoke and landing
timing with nozzles fixed to the token's local underside. Battle Armor uses the existing boarding/release
trajectory and grips the token's actual contour, following its final pose. In All Meeples, the BA token attaches as
one piece; hostile tokens rock about their grip while friendly riders remain steady. Ranged effects retain every reported weapon, fire from the token center, and hit varied visible surfaces
on the incoming side instead of anatomical locations.

Voluntary prone and hull-down share the shortened standing pose. Prone shooting uses its lowered center without
standing attack tilts or physical approaches. Forced prone rotates the full solid onto the ground without changing
its scale. The captured facing is already resolved by the rules engine (Core preserves facing; Total Warfare may
change it); the token does not apply a second facing change. Rear impacts use the face-up pose, other impacts the
face-down pose, with the upper end pointing along the captured heading. Side impacts never create a side-resting pose.
In top view, fallen Meeples instead show their upright artwork at prone height, oriented along the resolved facing;
the existing PRONE text indicates their state. Returning to an angled view restores the toppled pose.

Meeples rest on a flat plane at their captured hex elevation, including while toppled, while preserving occupied
roofs, bridges and airborne elevations. Decorative terrain and scatter do not tilt or lift the tokens.
Token height uses occupied game levels (`Entity.height() + 1`) before visual scaling: ordinary vehicles occupy
one level, while large support vehicles such as the Saturn Harvester occupy two.
Both Meeples and authored models display flight altitude above the occupied hex: altitude 1 over level-10 terrain
is drawn at level 11. Movement playback uses each waypoint's hex elevation; landing returns to the landing surface.
LAM and QuadVee forms use their captured movement mode and occupied height, with a brief tilt and smooth height
transition on the conversion clock. Whole multi-hex tokens fit and center on the existing occupied footprint.

Token caps use clean camouflaged tileset artwork; walls use the shared camouflage shader. Whole-body damage is
captured from remaining versus original armor (including rear armor) and structure, reaching the final stage when
doomed/destroyed. The four existing body-damage textures apply only to the walls at half opacity to preserve team
color/camouflage. The identification artwork stays clear at every damage stage. Repair and mode changes rebuild
per-instance materials while retaining shared mesh/texture ownership.

## Wireframe and thermal signatures

The HUD's Wireframe utility (beside Tactical view; also on the tuning panel's Camera page)
draws terrain, buildings and trees as green lines,
and units with a separate thermal shader. The unit pass borrows the same posed models,
visible parts, sprite alpha cutouts and scene depth as the shaded view. Sensor contacts
and tactical overview icons retain their existing presentation.

`GpuBoardSource` captures `Entity.heat` only when `tracksHeat()` is true. The value stays
in `BoardScene.Unit` and follows the displayed movement/combat snapshots; the render
thread never reads live entities. A value of -1 selects a fixed warm signature for
infantry, battle armor, vehicles and other units that do not track heat. Sensor contacts
also carry -1 and never enter the thermal pass.

The display palette runs from blue/violet at zero heat through magenta, red, orange and
yellow to white at high heat, saturating at 30. This is a visual range, independent of
heat rules or capacity. Body gradients and facets are stylized variation, not measured
temperatures of individual weapons, limbs or troopers. Formation bounds vary with poses
and ground support, so their vertical gradient is an approximation across the formation.

`GpuThermalUnits` creates its batch and programs on first use. Normal rendering does not
run the thermal shader or queue thermal draws. Turning Wireframe off retains the shader
cache until renderer disposal; no additional unit meshes or full-screen buffers are made.

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
