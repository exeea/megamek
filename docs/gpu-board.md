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
Visual fixtures load map files through `BoardFile.loadSheet(file, false, true)`.
In-memory legacy boards pass through `BoardSceneryLayouts.convert` before capture,
including in `GpuBoardFixture`, so scenery and inferred bridge roads match gameplay.
Native boards retain their authored contents through the same conversion boundary.

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

Both use `BoardArtwork` for the Classic art, decals and model selection,
and turn each hex into a tile with the same `BoardScene.captureTile`. A game only relabels
its visible hexes (`BoardArtwork.labels`, `Tile.withText`, which keeps every other field),
so it shows the board exactly as the 3D preview and editor do.
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
captures no tiles. `GpuSourceParityTest` now compares the game source,
the preview and the editor on one converted fixture.

On a render update, `GpuBattleView` takes a frame and feeds its events into
`UnitPlayback`. The playback state determines which positions, poses and scene changes
are currently displayed. Terrain changes go through `GpuTerrain`; unchanged chunks and
shared assets can stay installed. Camera movement changes the view and visible detail
without asking the game to construct another scene.

## Enhanced terrain coverage

Legacy `.board` loading ignores explicit `none` markers: they are absent terrain,
not a separate ground material. It retains every real terrain entry alongside
them and reports unknown terrain names or malformed numeric fields with the
hex and token. Empty `""` terrain remains clear ground.

Enhanced draws `building:5` as solid modular walls joined by the stored building
exits. Wall geometry is shared by rendering, picking and dressing clearance. Grass, crops, reeds and
turf grow up to a military wall's body (`BoardWall.footprint`: the 30-unit deck and 52-unit junction every
military family shares) and everywhere under a civilian wall's open net.
The layered GLBs in `data/models/buildings/walls/<family>/` supply straight sections,
piers and terminals; their arms are connected-kit pieces (`data/models/buildings/README.md`). The game and editor select the family from pristine
map CF (`BLDG_CF`): civilian 1–10, light 11–15, medium 16–40, heavy 41–90,
hardened 91–150, Castle Brian 151+. Damage never changes the family. Editing map CF
across a tier boundary rebuilds the geometry; explicit model overrides keep their artwork.
Base and floor modules each fill one rules level, with the roof capping the complete
stack. Tall bodies repeat floors without stretching their textures. Connecting arms
meet the neighboring wall's roof in world space, with narrower civilian junction offsets.
Two authored mesh levels use the existing screen-size LOD rule; distant slots reuse LOD1, and picking keeps
the nearest mesh.
Metal deposits use Blender-authored quartz and ore GLBs with embedded colour,
normal and material maps, over a soft terrain-following soil splat. Each GLB has
one detailed LOD0 and one 88-triangle LOD1; farther slots reuse LOD1. Deposit size
and spacing use the same 1.5 scale factor, with soil and grass clearance derived
from the resulting footprint. The authoring
script is `mm-data/scripts/author-minerals.py`; run
`blender --background --python scripts/author-minerals.py` from the `mm-data`
root. Its editable scene is `mm-data/tools/minerals/minerals.blend`, and generated
assets belong in `mm-data/data/models/board`. Screens use the native chaff volume,
including `screen:0`. Their legacy sprites remain in Classic. Legacy `.board`
imports discard `road:0`, retain road levels 1–4, and normalize other values to 1
while retaining exits and fluff. Native `.board2` imports retain stored values.

SPACE has no opaque ground mesh. Its selectable plane, faint grid and light
labels remain, while bridges and platforms retain their own geometry. The
presence of empty SPACE automatically enables a procedural star background;
removing the last such tile restores the ordinary sky. SKY retains its opaque
cloud background. Real pavement and liquid override aerospace ground markers.
The space background uses the existing composite pass, with sparse stars and
subdued dust and galaxies. Slow drift runs in every light mode. Fixed Sun uses
the same camera-relative frame as terrain lighting; world lighting and Free
Flight use world directions. The visible light source stays aligned with the
actual terrain light and does not drift with the decorative stars.
`space-sky.glsl` exposes `SPACE_DRIFT_SPEED` (radians per second, default 0.004;
zero pauses) and `SPACE_DENSITY` (0–2, default 1). Density changes the star
population, dust coverage and galaxy extent. These are shader constants.

`EnhancedTerrainCorpusTest` is an opt-in regression for all installed maps
(`MEGAMEK_LEGACY_CORPUS=1`). It strictly loads both board formats, converts
legacy scenery, checks structure selections and requires native ground for
every non-liquid hex. Liquids have their own native renderer. This verifies
coverage; it does not render every map or judge the quality of every asset.

The 2026-10-21 corpus regression passed for 2,427 installed maps and 3,771,653
hexes, with native ground and complete structure selections. The earlier
2026-10-20 audit also checked residual scenery paint and replaced metal/screen
sprites; its 264 failed ground-only predicates were road/water combinations
rendered by the native liquid path. The initial cleanup removed 970 `none`
markers from 33 legacy maps while preserving every other byte. The later
corpus check also found and removed two stray numeric tokens left after road
removal in Valencia City. Focused CPU/GPU checks cover the final wall LODs,
mixed heights, Shipyard loading, enlarged minerals and space/light behavior.
These counts describe that installed corpus, not arbitrary future maps.

## Unit display

GPU Tuning's General tab has a **Unit display** selector, applied live in both cameras:

- **All Meeples**, the default, draws troops as meeple figures and every other visible unit as an extruded token
  from its existing artwork.
- **Mek Meeples**: Meks use tokens; other units use their existing 3D models.
- **3D Models** restores the existing authored-model presentation, including flat artwork when a model is unavailable.

The Defaults button restores All Meeples. Sensor contacts retain their anonymous markers. The Tactical View draws
every unit, troops included, as its token on a base, whatever the selector shows ([HUD](gpu-hud.md#5-the-tactical-view)). The token mesh reuses the libGDX branch's alpha-contour extrusion through
`GpuCutout`. Both cameras use the same posed geometry for drawing, picking, shadows, attachments and effects.
The extrusion traces the half-alpha contour and simplifies pixel steps into panels within 0.55 source pixels.
Caps and walls share those outlines, including holes and separate islands. Gentle wall turns share normals;
turns of 60 degrees or more and the cap rims remain sharp. This removes pixel-scale lighting stripes at the
geometry level; screen antialiasing alone cannot smooth independently lit wall facets.

Meeple figures are a formation like the 3D troops: each formation recipe's `meeple` field names a recipe in
`units/modular/meeples/`, which `GpuUnitModels` assembles with the same figure count, layout, transports, boarding,
mounting, swarming and casualties. The figures are rigid silhouettes on the `meeple-v1` rig, as tall as the 3D
trooper and battle armour suit; they hop along their path instead of moving limbs. The APC (tracked, wheeled and
hover) and buggy (motorized) are the `Infantry/mechanized_apc.png` and `motorized_buggy.png` sprites extruded into
GLBs; their grey pixels take the camouflage, like the 2D icon.

Meeples leave no wreck in the 3D view (a wreck has no gameplay; the Tactical View keeps its crossed-out base). The
first frame a unit shows destroyed, its meeple explodes (`MeepleCasualties`): `MeepleShatter` cuts it along four
planes in different directions into closed pieces, and `MeepleDebris` throws them away from the hits playing against
the unit, a third of them into the hexes around, under a fast tabletop gravity. They bounce off the ground, roofs and
rocks, sampled lazily from the board's picking (foliage gives way, liquids give way to their bed), and come to rest;
after four seconds every piece stops, whatever it is caught in. A formation's lost soldier turns to the hits, is
knocked back onto its back, and a lost vehicle explodes like a meeple. Once everything is still it lies a moment,
then sinks into the ground and is gone. The pieces do not collide with each other.

`MeepleVisual` owns token geometry, wall paint and attachment sockets. `MeepleAnimator` supplies rigid token poses;
`UnitAnimator` retains articulated model poses. Both sample the existing movement/combat timeline. Walkers bounce,
vehicles rock subtly and airborne craft bank through turns; displacement slides. Push/punch/kick and the stronger
club swing or lance thrust approach and recover on the physical attack clock. Jumps reuse flame, smoke and landing
timing with nozzles fixed to the token's local underside. Battle Armor uses the existing boarding/release
trajectory and grips the token's actual contour, following its final pose. In the Tactical View, the BA token attaches as
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

The 3D Graphics setting (View > Renderer > Classic or Enhanced, Client Settings; `GUIPreferences.BOARD_GRAPHICS`, published to the render
thread as `BoardSource.UiPreferences.graphics`) chooses what `GpuTerrain` builds. `GpuBattleView` calls
`GpuTerrain.setColumns(graphics == CLASSIC)` before each `update`, so a game saved as Classic never builds the 3D terrain
first. Enhanced, the default, builds the 3D relief, water and lava fields, trees, scatter, ground cover, biomes and every
feature. Classic, for low-end computers, is a build mode of the same chunk pipeline (b11-design 2.4):

- **Art.** The update plan composes each hex's column art on a terrain worker: its Classic art
  (`BoardScene.Tile.tileset`, which `BoardArtwork` composes from the Classic tileset, `data/models/board/classic`, never
  the 2D board's) with `BoardRim.column`'s incline mask along each edge above a lower neighbour, or its high-incline mask
  where the drop exceeds two levels or the map marks a cliff side there. It goes into the hex's base slot of the ground
  atlas, which a water neighbour's bank borrows. The tileset decals and object sprites go into the decal atlas;
  Enhanced packs neither, only its legacy paint. Bridges have no Classic art: both graphics draw the same 3D bridge.
  The tileset holds level-0 ground art; `TilesetLevels` turns each image for the hex's level by its recipe in
  `classic/tileset/levels.json`: its table's entry (levels -3 to 10, fitted to the old per-level copies), or past the
  table the nearest entry plus the recipe's formula, eased as `levelGrade` eases heights. The column shader adds
  Enhanced's `levelGrade`;
  liquids, as in Enhanced, are not graded. Baked road and flat bridge art are omitted because infrastructure uses the
  shared 3D geometry in both modes.
- **Geometry.** A Classic chunk's worker job is `GpuTerrain.prepareColumns`: each hex's `GpuTilesetTerrain.column`, a
  plain column at its level, walled in its material down to lower neighbours or the board's floor. A road leaving a hex
  toward one of another level ramps through their shared edge to the height `BoardSurface.roadEdgeElevation` gives both,
  between banks, and the walls and rims leave that mouth open. Natural shores round into land-textured banks and sandy
  shallows; concrete quays keep their fitted corners (`BoardConcrete`). Road and railway
  carriers use `BoardSurface`, and `prepareInfrastructure` builds the same roads, built bridges,
  bridge footings and tunnels as Enhanced. Natural bridges have a full hex cap over
  their hollow arch, topped with the art of an abutting bank of the span's surface; without one the arch shows its rock.
  Flat road and rail carriers retain normal Classic tops and vertical terrain edges;
  actual grades and tunnel openings retain their shared carrier geometry. A nearby
  flat route does not introduce sculpted hillsides into Classic terrain.
  Unrelated hexes retain plain columns; no water
  or lava field or planting is prepared.
- **Chunk.** The GL thread's collection step writes each column (`GpuTilesetTerrain.write`) into the chunk's opaque
  layer (tops, walls, banks, sand shallows, molten liquid) and its liquid
  layer (open water in its animated frames, `BoardLiquid.textures`, moving with its river's current, and the falls over
  each drop into the liquid below; hazardous liquid is tinted green, Mars and volcano water toned to their theme).
  Frozen water shows its water's first frame, still and opaque, under Enhanced's ice sheet (`GpuTerrain.lakeIce`).
  Of the hex's features it builds only buildings,
  fuel tanks, industrial plants, bridges, rail and maglev routes, road controls and placed objects other than plants
  (`GpuTerrain.columnFeature`), standing on their shared carrier or column (`GpuTerrain.GroundView`).
  Tileset decals and sprites, authored paint and tactical markings follow that supporting surface.
- **Shared.** Everything else is the Enhanced path: the opaque layer draws, casts and receives shadows through the
  terrain pages and the depth snapshot, the liquid layer through the transparent pass, liquids animate with the
  terrain's clocks, picking and overlay draping (`GpuTerrain.tacticalSurface`) read the supporting carrier or column, the wireframe draws it,
  and an edit rebuilds only the chunks it touches; the change scan also notices the tileset art, tileset
  decals and sprites, which only a Classic build reads. Classic chunks have one level of detail, so the camera never
  refines them. The ground cover and wave simulations are not created, and are released on a switch to Classic;
  of the biome map only its per-hex texels are, which the ice over frozen water reads.
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
  It renders 1024-pixel tiles at 200 dots per inch (a hex 1.5 by 1.3 inches) and writes a JPEG at quality 0.92 without
  chroma subsampling, with that density in its JFIF header: on the 12 × 9 smoke board 1.9 MB against 9.5 MB as the
  earlier PNG, its labels and grid indistinguishable at three times magnification. Each finished row of tiles (a band)
  is encoded on one background thread while the next renders (`GpuPrintJpeg`): the JDK's encoder writes each band
  with the same tables, and restart markers join the bands' scans into one baseline JPEG, so at most two bands are in
  memory (2 × 1024 rows × width × 3 bytes; 177 MB for Valencia City's 28,875-pixel width). A cancelled or failed
  print deletes its unfinished file. While it runs, the map HUD shows a Print card with a progress bar
  (`GpuMapHud.print`, centred in the board area the editor's panels leave free): Preparing terrain (the print's own
  terrain build), Rendering (tiles done of all tiles) and Writing file (the last band's encoding). Its Cancel stops
  the print at the next frame (`GpuBoardPrint.cancel`): the unfinished file is deleted, no error is reported and Print
  is offered again. Valencia City
  printed in 754 s (28,875 × 50,050 pixels, a 557 MB JPEG). A JPEG has no alpha, so the empty
  background is white paper; a board with SPACE hexes prints the space sky behind them instead, one sky field across
  the whole print (`GpuAtmosphere.setPrintTile`; the stars keep their pixel size, so a large print shows them sparser
  than the screen does). Tiles get no lens vignette (it had darkened every tile's corners by about 6 %). The only size
  limit is the JPEG's 65,500 pixels a side: 291 columns or 252 rows. `GpuBoardPrintSmokeTest` prints in Enhanced (preview and
  editor) and in Classic, and a SPACE board in both.
- **Bridges.** Drawing, picking and object support use the installed deck footprint. Built bridges
  share that footprint across graphics modes. Classic natural bridges cover the normal hex,
  with a deck-height centre and sloped bank entrances over the lower passage; Enhanced uses the eroded span outline.
  Tactical View retains the selected graphics mode's geometry. The atmosphere (grading, fog, weather,
  glare, lightning) applies as in Enhanced; fire and smoke particles are left out, since the tileset art shows them.

Tests: `GpuGraphicsModeSmokeTest` (a game started in Classic builds no water or lava field, wave simulation, tree,
scatter or grass, of the biome map only its per-hex texels; infrastructure retains its shared materials; the building
stands on its column; overlays drape on and picks hit the column tops; a switch either way shows the loading stage and retires the other
build, also when reversed after its atlases were packed; T starts no rebuild), `GpuTilesetTerrainSmokeTest` (an edit
rebuilds only the chunks of its hex and of the neighbours that read it; water stays inside the shore picking uses;
review captures), `GpuTilesetTerrainTest` (column geometry), `BoardRimTest.classicColumnsBakeNoCliffStroke`,
`BoardInfrastructureModeTest` and `GpuRoadModeTest` (shared infrastructure, support and paint). `GpuTerrainLoadBenchmarkTest` with
`-Dmegamek.gpu.benchmarkGraphics=CLASSIC` times `prepareColumns` and digests the columns in the region's order.

## Tactical View

The HUD's Tactical view utility and the Toggle Tactical View key (T) switch the camera to the Tactical View, a top view, and back ([HUD](gpu-hud.md#5-the-tactical-view)). The terrain
does not follow the view: the 3D Graphics setting chooses it, and T never rebuilds it. Every unit is its meeple on a
`GpuUnitBases` base (side ring, facing and twist notches, status badges, wreck cross), the ground layer of `GpuHexGrid`
marks cliffs (including Cliff Top exits, by `BoardRim.high`), 1- and 2-level steps, woods, roofs with their CF, water
by depth, smoke and fire, and the fixed north-west light (`BoardAtmosphere.TACTICAL`) replaces the hour's.

`GpuBattleView` calls `GpuTerrain.setFluffHidden(boardCamera.tactical())` every frame. The rule is a draw rule only
(b11-design 4.3): trees, scatter, grass and biome vegetation, building, industrial, fuel tank and placed object models,
decals and authored paint, combat scars and ground damage, water (Enhanced water pages and ice, Classic liquid frames
and ice), the ocean and lava wave simulations, spores and geysers hide; the relief or the columns, roads, bridges,
rough, rubble and fortifications (`Prop.tactical`), tunnel portals and rail and maglev routes stay.
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
