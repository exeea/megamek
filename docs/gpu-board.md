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
holds client presentation state used by the renderers. `BoardView` supplies Legacy 2D
Swing painting when that view is selected. Native startup can use the shared state
without constructing the Legacy 2D viewport, and the native capture API (planar hexes,
tactical geometry, field of view, overlays, annotations) exists only on the state.
Tests build a `BoardClientState` directly (`GpuBoardFixture`); a `BoardView` appears
only in tests that exercise both renderers on the same state.

The tactical and Free Flight cameras look at the same scene. `BoardCamera` handles
movement and view selection; `BoardProjectionCamera` handles projection and
`BoardCameraCollision` handles camera contact with the scene. Changing cameras retains
the same unit instances, picking geometry and playback timeline.

Both cameras use right-drag to pan and middle-drag to orbit (look around in Free Flight).
Holding Shift when pressing either button swaps those actions for the whole drag.
Free Flight starts with a 45-degree vertical field of view; the Camera tuning slider can
change it, and Defaults restores 45 degrees.

Manual panning and pointer-centered zoom constrain the full viewport center in both the
3D and Tactical views, including the area behind HUD panels. The limits act per screen
axis: the board's leftmost, rightmost, nearest and farthest points cannot pass the viewport
center, so at a side limit a drag still moves the board up and down (and at a top or bottom
limit sideways), also when the view is turned. The board also stays on the center's row and
column inside the viewport, so a turned board cannot leave the screen beside one of its
corners; with the view turned, the center itself may therefore lie beside the board. A view
square to the board (north, east, south or west up) keeps the center over it. The inset
board footprint extends upward to the highest terrain or feature using the current viewing
angle. A tall pillar's summit remains reachable even when its base leaves the screen. This
adds vertical room without widening the sideways bounds; flat boards retain their existing
limits. Each screen axis of a drag stops at a limit on its own, along its requested
direction, instead of sliding along a rotated board edge. Reversing responds immediately,
and dragging along the boundary remains possible.
The displayed terrain snapshot refreshes the bounds after edits and board replacement,
and geometry scaling changes their world size. Feature heights use the same nominal
placement and height calculation as Fit board. Automatic framing and Free Flight retain
their own camera behavior.

## From game state to a frame

[BoardSource](../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/BoardSource.java)
is the boundary between client state and rendering. Its two implementations have
different inputs:

- `GpuBoardSource` captures gameplay from `BoardClientState`, the game and the current
  phase controls. It applies existing visibility rules before capturing unit identity,
  loadouts, annotations and commands.
- `GpuMapSource` captures a preview or editor board directly. Editor operations and
  undo history remain with the existing editor.

Both use `BoardArtwork` for tileset lookup, ground artwork, decals and model selection,
and turn each hex into a tile with the same `BoardScene.captureTile`. A game adds only
its tactical marking and labels (`HexImage.withTactical`, `Tile.withMarkings`, which keep
every other field), so it shows the board exactly as the 3D preview and editor do.
`GpuSourceParityTest` compares both sources' tiles on a board with legacy scenery.
They publish a `BoardScene` containing terrain and unit presentation data.
A `BoardSource.Frame` packages that scene with the animation events, command descriptions,
focus requests and other UI state needed by the native view.

Since batch 9 both capture through one owner, `BoardTiles` (the editor's palette samples and
the tests use its one-shot `captureAll`): one `BoardArtwork`, the board's tiles, one
invalidation rule (a changed hex invalidates the art and tile of its 3x3, because
`Board.setHexes` rewrites the neighbours' exits without an event of their own) and a
tripwire. It draws each hex without the legacy tokens the conversion took
(`BoardSceneryLayouts.withoutTakenTokens`), and no view decodes legacy scenery: a board is
converted where it is loaded (docs/3d-board-editor.md, Legacy conversion), so the game and
the previews draw what the editor and the saved `.board2` hold. A board that reaches a
view unconverted logs one warning and draws nothing for those tokens. `BoardClientState`
keeps only the game's tactical pass. `GpuSourceParityTest` now compares the game source,
the preview and the editor on one converted fixture.

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

The Defaults button restores Mek Meeples. Sensor contacts retain their anonymous markers. The Tactical View draws
every unit as its meeple on a base, whatever the selector shows ([HUD](gpu-hud.md#5-the-tactical-view)). The token mesh reuses the libGDX branch's alpha-contour extrusion through
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

Token caps use clean camouflaged tileset artwork; walls use the shared camouflage shader. Whole-body damage, on
tokens and on models without location parts (vehicles, aerospace, DropShips, naval), follows the unit's damage level,
the state its board label's damage tile and the unit card show: light, moderate, heavy and crippled take the four
stages, and doomed/destroyed the last. On tokens the four body-damage textures apply only to the walls at half
opacity to preserve team color/camouflage, and the identification artwork stays clear at every damage stage. Repair
and mode changes rebuild per-instance materials while retaining shared mesh/texture ownership.

## Wireframe and thermal signatures

The HUD's Wireframe utility (beside Tactical view; also on the tuning panel's Camera page)
draws terrain, buildings and trees as green lines,
and units with a separate thermal shader. All cosmetic scatter, including baked field stones and shrubs and
instanced ground/cliff fungi, is omitted from both wireframe lines and its depth fill.
The terrain's packed meshes and instanced scatter models share the same chunk scatter visibility in color,
depth and shadow passes; the wireframe excludes that layer.
The two views exclude each other: turning the wireframe on leaves the Tactical
View, and entering the Tactical View turns the wireframe off. The unit pass borrows the same posed models,
visible parts, sprite alpha cutouts and scene depth as the shaded view. Sensor contacts
and tactical overview icons retain their existing presentation. Source bloom, glare and lens reflections are
suppressed in wireframe; leaving it restores the configured glare strength.

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

## Graphics: Classic and Enhanced

The 3D Graphics setting (View > 3D Graphics, Client Settings; `GUIPreferences.BOARD_GRAPHICS`, published to the render
thread as `BoardSource.UiPreferences.graphics`) chooses what `GpuTerrain` builds. `GpuBattleView` calls
`GpuTerrain.setColumns(graphics == CLASSIC)` before each `update`, so a game saved as Classic never builds the 3D terrain
first. Enhanced, the default, builds the 3D relief, water and lava fields, trees, scatter, ground cover, biomes and every
feature. Classic, for low-end computers, is a build mode of the same chunk pipeline (b11-design 2.4):

- **Art.** The update plan composes each hex's column art on a terrain worker: its Saxarba tileset art
  (`BoardScene.Tile.tileset`, which `BoardArtwork` composes from the 3D board's own tileset, never the 2D board) with
  `BoardRim.column`'s incline mask along each edge above a lower neighbour, or its high-incline mask where the drop
  exceeds two levels or the map marks a cliff side there. It goes into the hex's base slot of the ground atlas, which a
  water neighbour's bank borrows as in Enhanced. The tileset decals, object sprites and bridge art (`Tile.bridge`) go into
  the decal atlas; Enhanced no longer packs or builds them.
- **Geometry.** A Classic chunk's worker job is `GpuTerrain.prepareColumns`: each hex's `GpuTilesetTerrain.column`, a
  plain column at its level, walled in its material down to lower neighbours or the board's floor. A road leaving a hex
  toward one of another level ramps through their shared edge to the height `BoardSurface.roadEdgeElevation` gives both,
  between banks, and the walls and rims leave that mouth open. Natural shores round into land-textured banks and sandy
  shallows; concrete quays keep their fitted corners (`BoardConcrete`). No relief, sculpting, water or lava field, plant,
  road or bridge footing is prepared.
- **Chunk.** The GL thread's collection step writes each column (`GpuTilesetTerrain.write`) into the chunk's opaque
  layer (tops, walls, banks, sand shallows, the bridge's deck art at its deck height, molten liquid) and its liquid
  layer (open water in its animated frames, `BoardLiquid.textures`, moving with its river's current, and the falls over
  each drop into the liquid below; hazardous liquid is tinted green). Of the hex's features it builds only buildings,
  fuel tanks, industrial plants, rail and maglev routes and placed objects other than plants
  (`GpuTerrain.columnFeature`), standing on the column (`GpuTerrain.GroundView`); a rail lies on its carrier plane and a
  maglev has no piers. Tileset decals and sprites, authored paint and the tactical markings lie on the columns.
- **Shared.** Everything else is the Enhanced path: the opaque layer draws, casts and receives shadows through the
  terrain pages and the depth snapshot, the liquid layer through the transparent pass, liquids animate with the
  terrain's clocks, picking and overlay draping (`GpuTerrain.tacticalSurface`) read the column, the wireframe draws it,
  and an edit rebuilds only the chunks it touches; the change scan also notices the tileset art, bridge art, tileset
  decals and sprites, which only a Classic build reads. Classic chunks have one level of detail, so the camera never
  refines them. The board-wide biome map, ground cover and wave simulations are not created, and are released on a
  switch to Classic.
- **Switching.** A change of the setting is a full rebuild either way: `update` sees the wanted graphics differ from
  the installed ones and starts a new build, cancelling running jobs; `ready` stays false until it commits, so the view
  shows its loading stage (overall progress and section grid) and the build uploads with the opening's frame slice;
  the commit retires the other build's chunks. A switch reversed after its build packed the ground and decal atlases
  for the other graphics (`GpuTerrain.atlasColumns`) is such a rebuild too, since every chunk's atlas regions moved.
  Camera, Tactical View, selection, orders, HUD and units stay. The editor's palette and terrain previews stay Enhanced.
  Measured costs: [performance](gpu-board-performance.md#classic-and-enhanced-graphics).
- **Cliffs.** The Classic tops carry no cliff stroke; the Tactical View's ground layer strokes cliffs in both graphics.
- **Print.** Menu > Print (`GpuBoardPrint`) builds its own terrain with the live view's installed graphics
  (`setColumns(lender.columns())`; the print starts only once the live terrain is ready), so a Classic print shows the
  columns and an Enhanced print the 3D terrain. It keeps the 3D view's fluff in the Tactical View too and draws the
  plain grid, not the ground layer, so a print strokes no cliff in either graphics; its labels give the levels.
  `GpuBoardPrintSmokeTest` prints in Enhanced (preview and editor) and in Classic.
- **Limits.** A Classic bridge hex is picked on its column, under the deck art (no 3D deck); placed objects, decals
  and paint on the bridge take the plane of the deck art over the whole hex. The atmosphere (grading, fog, weather,
  glare, lightning) applies as in Enhanced; fire and smoke particles are left out, since the tileset art shows them.

Tests: `GpuGraphicsModeSmokeTest` (a game started in Classic builds no water or lava field, wave simulation, tree,
scatter, grass, biome map or sculpted material and no sculpted shader warm-up; the building stands on its column;
overlays drape on and picks hit the column tops; a switch either way shows the loading stage and retires the other
build, also when reversed after its atlases were packed; T starts no rebuild), `GpuTilesetTerrainSmokeTest` (an edit
rebuilds only the chunks of its hex and of the neighbours that read it; water stays inside the shore picking uses;
review captures), `GpuTilesetTerrainTest` (column geometry), `BoardRimTest.classicColumnsBakeNoCliffStroke`. `GpuTerrainLoadBenchmarkTest` with
`-Dmegamek.gpu.benchmarkGraphics=CLASSIC` times `prepareColumns` and digests the columns in the region's order.

## Tactical View

The HUD's Tactical view utility and the Toggle Tactical View key (T) switch the camera to the Tactical View, a
north-up top view that does not orbit, tilt or turn, and back ([HUD](gpu-hud.md#5-the-tactical-view)). The terrain
does not follow the view: the 3D Graphics setting chooses it, and T never rebuilds it. Every unit is its meeple on a
`GpuUnitBases` base (side ring, facing and twist notches, status badges, wreck cross), the ground layer of `GpuHexGrid`
marks cliffs (including Cliff Top exits, by `BoardRim.high`), 1- and 2-level steps, woods, roofs with their CF, water
by depth, smoke and fire, and the fixed north-west light (`BoardAtmosphere.TACTICAL`) replaces the hour's.

`GpuBattleView` calls `GpuTerrain.setFluffHidden(boardCamera.tactical())` every frame. The rule is a draw rule only
(b11-design 4.3): trees, scatter, grass and biome vegetation, building, industrial, fuel tank and placed object models,
decals and authored paint, combat scars and ground damage, water (Enhanced water pages and ice, Classic liquid frames),
the ocean and lava wave simulations, spores and geysers hide; the relief or the columns, roads, bridges (3D decks or
Classic deck art), rough, rubble and fortifications (`Prop.tactical`), tunnel portals and rail and maglev routes stay.
Molten lava and magma lie in the opaque layer in both graphics and stay, still (their waves stop). T neither rebuilds
nor recaches: the 3D prop caches and their pages stay, and the passes draw each chunk's kept props instead, packed on
the view's first draw of the chunk (`GpuTerrain.Chunk#keptProps`), so only the shadow map is redrawn. Picking is
unchanged: a click picks the same hex and floor (a building's roof included) and overlays drape on the same surfaces as
in the 3D view (`GpuTacticalViewSmokeTest.verifyFluffHidden`, which also audits that one Tactical View frame submits no
tree, placed object, building module, scatter, grass, water or packed prop draw, in both graphics, and draws the
Enhanced bridge from the kept props).

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
