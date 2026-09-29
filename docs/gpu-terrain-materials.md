# Sculpted terrain

Dry hexes are rendered as one continuous sculpted landform: tops, cliffs, rim
formations and fallen rock share vertices across hex edges, so nothing is drawn as
separate hex prisms. Game data stays authoritative. Every top sits at its hex's
level, every cliff foot at the lower neighbour's level, and the area where units
stand is exactly level. Rendering, picking, unit support and cast shadows all use
the same triangles. The hex is 30 metres corner to corner (`BoardRelief.metres`).

Hexes with water, roads or ramps keep the previous geometry. Their shores and beds
use the new materials of the land beside them.

## Geometry (`BoardRelief`)

Everything two meshes share is a pure function of world position and the board
data around it. That includes hex corners, edge samples and cliff rows. Each
neighbour therefore computes bit-identical vertices without exchanging meshes.

- **Corners** sit on an integer lattice. The three hexes around a corner derive the
  same `Corner`, whose displacement depends only on those three hexes.
- **Cliffs** belong to the higher hex. The foot row is the lower hex's boundary.
  Rows lie at fixed absolute heights, so cliffs of different heights that meet at
  a corner share every vertex.
- **Corner fillets.** Every corner is rounded along each of its edges by a length
  set by the geology. It moves by a sixth of that length toward the hex on the
  other side of the outline, with a cubic falloff. That makes each outline
  through the corner tangent-continuous, so there is no crease. On three-level
  corners the fillet turns about the middle hex within a quarter level of its own
  height. Concrete keeps a tiny fillet, which gives crisp arrises.
- **The cliff profile**, relative to the hex edge, has these parts:
  - a face set back from the edge, with broad buttresses, jointed rock masses and dark fractures;
  - irregular hard and soft beds;
  - a caprock lip at the rim;
  - a talus apron at the foot.
- **Rim formations.** A thin lip is used for drops of up to two levels. From three
  levels it becomes a heavy, frequent caprock with larger rim rocks.
- **Water hexes** are sculpted too (`Site.liquid`), in the family of the dry
  natural land around them. Their walls are canonical cliffs, their corners take
  fillets and bands like land's, and their own ground is a bank from the canonical
  boundary to the waterline (`BoardRelief.bank`); the bed and the water are
  `BoardSurface`'s. At a water hex's own level the relief is held to the line
  (`footPinned`, `rimPinned`), so its outline stays on the edge. For its landforms
  a step counts from the upper hex's level, a water hex's surface, down to the
  ground below, in water its bed (`BoardRelief.drop`): land two levels above water
  one level deep stands over a three-level rock cliff, as does water three levels
  above land. Corners where two water hexes step down to each other stay pinned, so
  the straight walls under a fall meet the cliffs round them.
- **Shores.** Where natural land pokes a corner in between two water hexes of its
  level, that corner moves toward the land's centre at that level, 17 units
  (`BoardRelief.SHORE_SHIFT`), or 7.5 when the land is a point with three water
  neighbours in a row (`SHORE_POINT`). `edgePoint` spreads the move evenly along
  each edge through the corner, so neighbours still share every seam vertex and the
  land's top stays convex and level near the moved seam (`segments` measures its
  seams from the moved corners); the water hexes' tops take in the tip. The bank's
  stubs and strips measure along the moved mouth, and the strip never stitches a
  face-down triangle where the other order gives an upward one. See the Liquids
  section of `gpu-board.md` for the waterline and the measured results.
- **The soil mantle.** Grassland and snowfield steps of up to two levels are banks
  of the mantle: less jointing, and the face leans back. From three levels they
  are rock cliffs. Sand and rock stay steep.
- **Concrete slabs.** Concrete faces are flat. A step of up to two levels is a wall
  of cast slabs. From three levels, the top level is one slab that rests on
  bedrock. The rock stands back at least half a metre under the slab, so the
  slab's lower edge casts a clean line of shadow, and it has talus at its foot.
- **Hex transitions** (Tuning > General > Hex transitions, on by default since
  2026-09-24, `BoardGeometry.DEFAULT_TRANSITIONS`). A
  step between two natural grounds takes 4 m (`BoardRelief.TRANSITION`) on each
  side of its edge; paving, special artwork, buildings and roads keep their
  outline on the edge. Beside water a step a tank or a mek can climb, up to two
  levels from the land down to a water hex's bed, is a slope like any other that
  carries on under the water to its bed (`BoardRelief.band`): land a level above
  water a level deep takes a two-level slope that meets the water on the edge, and
  the water runs up to it. A wall of three levels or more, and land below raised
  water, keep the water's outline on the edge and take a step half as wide on their
  own side. Steps
  between two water hexes
  (falls) take no room. Up to two levels the room is a slope in the ground's own
  materials, flatter at the rim and the foot than in its middle. From three levels
  the face stands the whole room back above a concave talus that reaches the room
  into the lower hex; its rock masses stand out further, and gullies cut it every
  few metres, each shedding a cone of debris. The band offsets the exact hex
  outline, so neighbours still share every vertex. Near the rim and the foot the
  relief moves along rays from the hex centres, so each top stays star-shaped and
  never folds. Trees and scatter on a receding rim move onto the top; units,
  markers and the tactical overlay lie on the slope, and each half of a slope picks
  the hex it lies in. Across straight steps of one to five levels the upper top
  ends 3.1–4.4 m inside its hex and the lower top begins 4.4–4.8 m into its own.
- **Hex padding** (Tuning > General > Hex padding, 0 to 8 m, off by default). The
  same steps as hex transitions, with room of half the padding on each side of the
  edge (`BoardRelief.stepRoom()`); padding turns the transitions checkbox off.
  Hexes of one level stay one continuous ground, so the textures run on across
  them. The lattice keeps its size, so the room comes out of the hexes. Beyond
  8 m the tops of hexes with steps on several sides begin to fold (`FoldProbe`
  on the rugged probe board: sand 2 folds at 9 m, 5 at 10 m, 68 at 12 m; none up
  to 8 m), which is why the control stops there. A padding that spreads the
  lattice apart, so every hex keeps its size and the board grows around the gaps,
  is planned but not built: it touches about 70 lines in 20 files, among them the
  water and unit code.
- **Displacement is bounded.** Vertices stay within `BoardRelief.headroom(tile)`
  above the hex level. Horizontally they stay within `BoardRelief.overhang()` (18%
  of the hex width, plus the transition room when hex transitions are on) of the
  hex outline. Picking bounds include both.
- **Detail by board size.** Up to 2,500 hexes the board uses 12 samples per open
  edge and 12 wall rows per level, plus the rock kit. Up to 10,000 hexes it uses
  about half the vertices. Larger boards use 4 samples per edge, 4 rows per level
  and no rock kit. One board always uses one detail tier, so shared edges still match.

## The rock kit (`BoardRocks`)

The kit has eight jointed blocks, eight boulders and eight shrub masses (boxes with
every corner cut away: 43 triangles on average against a boulder's 64). The original
seeded rocks are stored in sixteen GLBs under `mm-data/data/models/board/rocks/`,
one file per block or boulder variant, with `-lod0`, `-lod1` and `-lod2` root nodes.
`mm-data/data/models/board/scatter/` contains 26 separate GLBs for shrub masses,
dedicated eight-triangle open-base stones and the grass/plant meshes. Each contains
only its `<shape>-lod0` root mesh. Scatter uses no additional levels and never borrows
a terrain boulder. Missing optional terrain-rock levels reuse the preceding level,
resolved once when loading each shape. Terrain rocks remain closed solids.
The importer restores shared corner identity across flat-shaded triangles,
so placement, picking and rendering continue to consume one geometry source.
`BoardRelief` places them canonically per edge:

- formations stand on rims, grouped, more and larger from three levels;
- blocks lie on the talus and on the ground below rises;
- a hex without features of its own gets a few loose stones and low shrubs, each
  shrub a clump of three or four masses, shaded as a soft mass rather than facets
  and kept out of the unit's standing area. Desert hexes grow three bushes on
  average, 1.2 to 2.4 m across and rounder than a meadow's;
- below a sandstone cliff the talus gathers 1.8 times as many fallen blocks, up
  to 30% larger.

Placements stay inside the owning hex, except toward the cliff they belong to. They
keep the unit anchor clear and stay under the picking headroom. Special ground art,
concrete rims and open paved ground stay clear of the kit. Only the bedrock under a
concrete slab sheds rubble, onto its talus.

## Materials (`terrain-sculpt.frag`)

Each surface family binds four materials from
`mm-data/data/models/board/textures/sculpt/`:

| Family   | Ground   | Debris (rims, feet, talus) | Wall      | Mantle (low steps, cliff cap) |
| -------- | -------- | -------------------------- | --------- | ----------------------------- |
| Grass    | grass    | scree                      | granite   | earth                         |
| Dirt     | dirt     | gravel                     | earth     | earth                         |
| Sand     | sand     | pavement                   | sandstone | sandstone                     |
| Rock     | rock     | scree                      | granite   | granite                       |
| Concrete | concrete | scree                      | granite   | cast                          |
| Snow     | snow     | scree                      | granite   | snow                          |

Each material is an albedo, with height in its alpha channel, plus a tangent normal
with ambient occlusion in its alpha channel. The manifest gives the metres each
repeat spans.

**Ground.** Ground is mapped in world space at two repeats, turned 34° against
each other and mixed by a broad noise field. Debris joins by height blending
at rims and cliff feet, on slopes and in patches. On meadows and snowfields, scree
appears only beside rock cliffs. Beside earth banks a meadow wears through to its
soil instead, in patches.

**Slopes.** On the slopes of transitions and padding the ground's own cover
(sand, turf, snow, soil) lies only where the face is nearly flat (normal z above
0.62 to 0.9), never on the metre and a half below the crest, and the slope's toe
takes a contact shade (`wallOcclusion`). So a step shows its rock or its earth
bank and a clear crest line against the ground above and below it; before, sand
drifted over most of a desert slope and hid it. At its foot a slope becomes the
ground it meets: its lowest row faces up and takes the same occlusion as the lower
hex's edge (`BoardRelief.footOcclusion`, from that one step, so both sides compute
it identically), easing into the slope's own shading within 1.5 m, and within its
lowest 0.8 m the shader covers it wholly with the ground, mapped from above and
toned by the same `groundTone()` as the ground itself. So no seam shows where the
slope meets the flat.

**Walls.** Walls blend two vertical projections, with V running down the face.
The blend is height-aware, so a rounded corner changes projection along the
rock's own relief. Where the grassland mantle covers the face, its horizons follow
the depth below the rim. Bed colour follows the same beds as the geometric ledges.
Rubble on a talus apron is mapped from the side like the wall where the apron is
steep, and from above only where it lies back, so it never stretches down the slope.

**Desert.** The sandstone is jointed. Vertical master joints split thick beds into
columns, minor joints reach part way down a bed, and only some bed partings are
open. Each block breaks into tilted spalls with eroded edges, and varnish streaks
run down from the partings. The map spans 12 m. Hard and soft beds differ only a
little in tone, so a mesa reads as red-brown columns rather than stripes. The
geometry has the same columns: the sand geology's jointed masses are 5 m wide and
stand up to 14 m tall, split by joints up to 2.2 m deep, with only faint bedding
ledges (`BoardRelief.GEOLOGY`). Desert ground is orange sand, iron-red where it
lies thin over the rock and paler where fine silt settles; broad flats of pale,
less saturated sand lie between the orange drifts, and long dune swells of light
and shade (about 120 m apart, bent by the broad noise fields) run across the
flats regardless of the hexes. Desert bushes are olive.

**Concrete.** The shader lays cast concrete out in slabs. On steps of up to two
levels they stand in courses one level tall, jointed every 4.8 m, with the joints
staggered from course to course. From three levels one slab spans the top level,
jointed every 12 m, over bedrock tinted darker than the concrete. Each slab
samples its own window of the `cast` map and has its own tone, grime toward its
foot and run-off stains below its top edge, so the map's repeat never shows. A
joint narrower than a pixel darkens that pixel only by the share it covers.

**Distance.** Between 18 and 60 cm per pixel, ground detail fades toward each
map's average colour, keeping a fifth of the detail. Without that, the map's
repeats beat against each other in the overview.

**Per-level grade.** Each game level gets its own grade, so levels read from
above (`levelGrade()` in `terrain-sculpt.frag`). Higher ground turns lighter and paler toward
cream, as drier, sun-bleached ground; lower ground darker and a little warmer. No family
turns toward red or pink. The grade has no hue rotation: one rotation turns orange sand
toward yellow but green grass toward blue, and the other way sand toward red. The user's
direction (2026-09-24) is good-looking, natural colour rather than a match to the prints.
Per level:

| | Saturation | Lightness | Contrast | Warmth |
| --- | ---: | ---: | ---: | --- |
| Each level up | −3% | +14%, toward cream (1, .97, .90) | +2% | — |
| Each level down | +4% | −18% | 0 | blue −4%, green −1% |

The lift toward cream scales with the albedo's luma up to 0.55, so dark ground lifts
less: grass (luma about .36) lifts about two thirds as much as sand, and a meadow's upper
levels stay olive and keep their texture instead of bleaching to a flat pale sage.
Levels count almost fully near the ground and ease off further away, as
6·(1 − e^(−levels/6)): 0.92, 1.70, 2.36, 2.92 and 3.39 for one to five levels, so no
height grades to white or black. Cliffs grade continuously with height, down to −1.5
levels. Measured on the Mines 1 top view at 13:00 (board `Deserts/16x17 Mines 1`, RTX 4070,
`GpuBoardFileSmokeTest`), median of plain hex centres, CIE L*/C*/hue:

| Level | Print | Hue rotation (2026-09-24 look pass) | Now |
| --- | --- | --- | --- |
| +3 | 75.5 / 28.7 / 69 | 71.8 / 30.1 / 71.6 | 73.2 / 29.1 / 70.4 |
| +2 | 70.7 / 34.7 / 68 | 69.3 / 32.3 / 69.0 | 70.4 / 31.6 / 67.8 |
| +1 | 67.2 / 33.1 / 62 | 66.2 / 36.8 / 66.7 | 66.8 / 36.3 / 65.7 |
| 0 | 57.8 / 31.1 / 64 | 62.3 / 40.5 / 64.0 | 62.3 / 40.5 / 63.9 |
| −1 | 47.5 / 29.7 / 58 | 55.0 / 37.2 / 62.4 | 52.1 / 37.4 / 64.1 |
| −2 | 43.4 / 29.2 / 58 | 52.6 / 32.6 / 56.1 | 46.7 / 32.5 / 60.8 |

The print's level 0 is darkened by its painted mesa shadows. Basins now step 10.2 and 5.4
L* below level 0 instead of 7.3 and 2.4, and no longer turn redder (hue 64 and 61 instead
of 62 and 56). On the showcase overview at 13:00 the grass levels −2 to +5 measure L*
26.6, 31.5, 37.0, 43.2, 45.9, 50.2 and 53.7, with chroma 20–25 on every level (the rotation
left 16.5–19.9 on levels +3 and +5). Sand levels +1 and +2 remain close (L* 70.1 and 70.4
on the showcase): bright sand has little room left toward white, and the ground's own
regional variation spans about as much. The earlier grades (hue −1°, saturation +2%,
lightness +3%, contrast +3% up; hue +6°, lightness −6% down) made basins yellow and khaki
and barely lightened the mesas.

**Lighting.** The sculpted terrain uses the board's one light model
(`light-model.glsl`, `surface-lighting.glsl`), like every other lit surface: the
albedo is linearised, multiplied by BoardAtmosphere's linear, pre-exposed light and
encoded for display once. The light is the sky from above and, from below, the
sunlit ground reflecting the family's own colour (`groundBounce()`: warm in the
desert, olive on grassland, white on snow), plus the sun or moon. Vertex and texture occlusion scale the
sky and bounce, a 12-tap rotated PCF shadow with receiver-plane depth scales the
direct light, and cloud shadows scale the direct light and the bounce. The shadow lookup moves out along the surface normal
by half a shadow texel, and by up to two where the light skims the surface, so flat
faces lit at a grazing angle do not shadow themselves. There are no separate sun,
sky or bounce gains. Very bright materials, such as sunlit snow and sand, roll off in
the composite's highlight shoulder like every other surface, so they keep their
detail instead of clipping.

**Weather.** Rain darkens exposed ground and rock, with a sheen and puddles on
level ground. Snow takes no liquid film. Wind sways the meadow ground.

**Tree pits.** On paved ground every tree stands in its own planting pit: a square
of earth and bark mulch inside a low, pale kerb. Pits are sized to the drawn crown,
and small enough that neighbouring pits never touch. They are thin dressing
(`BoardSurface.Finish.DRESSING`): drawn and picked with the ground, but they never
raise the height that trees and units stand on.

**Trees.** Tree models keep their own colours and detail maps but take the
terrain's light: `terrain-foliage.frag` uses the same light model and 12-tap
shadow (`sculptShadow()` in `surface-lighting.glsl`) as `terrain-sculpt.frag`. Below
a tree the ground reflects a neutral 20% (`GROUND_ALBEDO`), as below units and
props, not the family's colour. Leaves, needles and
fronds wrap the sun around the canopy instead of stopping at a hard terminator.
Bark and cactus stems are lit as solids. Snow on the branches stays neutral and
never darkens in rain.

**Views.** The clay view (`GpuTerrain.setClay`) drops the materials, leaving
geometry, light and occlusion. The Normal maps toggle also switches material relief.

## Asset sources and licences

All 13 materials are original procedural works, released as CC0-1.0. No
photographs, downloaded images or image generators were used. They are
synthesized with NumPy and Pillow by `mm-data/tools/build_terrain_materials.py`
from fixed seeds. On 2026-09-24 the grass palette moved from lawn greens to the muted
olive greens and straw of the printed map Grassland #3, the user's target, matched by
eye and by patch means (no pixels used): the map's mean changed from (66, 94, 33),
HSV saturation .65, hue 88°, to (96, 98, 55), .44 and 63°, against (92–109, 92–108,
54–63), .40–.43 and 55–62° on the print's open grass. Its height, normals and
occlusion are unchanged; `groundBounce()` for grassland follows the new colour.
The concrete maps were matched by eye to a reference photograph
of cast concrete; none of its pixels were used. Each map is authored from a height
field in metres, so normals and occlusion match its relief, and colours carry no
baked sunlight. To rebuild from the mm-data root:

```text
python tools/build_terrain_materials.py                # all maps, 512 px, and manifest.json
python tools/build_terrain_materials.py --only sand --preview sand.png
```

The runtime maps are 512 px (26 files, about 16 MB of PNG). `--size 1024` builds a
sharper set if needed. If the `sculpt` directory is missing, a neutral albedo and a
flat normal stand in, so geometry, light and grading still render.

The earlier Blender outcrops (`outcrop-*.g3dj`, `BoardOutcrops`) are removed. The
rock kit replaces them.

## Verification

Unit tests, with no GPU required:

- `BoardSculptTest`: watertight tops and cliffs for every family, with hex
  transitions off and on, including
  three-level junctions and the reduced detail tiers. Also checks for no folded
  top triangles, level anchors, bounded vertices, cliffs facing outward, and
  picking on tops and cliffs.
- `BoardReliefTest`: deep rims carry more prominent formations than shallow ones,
  concrete faces are flat and from three levels the bedrock stands back under a
  one-level slab, trees on paving stand in pits that never touch or raise the
  ground, special art stays level, joined landforms have no seam, and compressed
  level heights stay inside the picking floor.
- `BoardTransitionsTest`: with hex transitions on, steps take their room on both
  sides of the edge for grass, rock and sand at one to five levels; paving and
  special artwork keep their edge; trees settle onto their own top; each half of a
  slope picks its own hex, and units stand on the slope.
- `GpuShaderSourceTest`: no shader uses a word that newer GLSL versions reserve,
  such as `patch` or `sample`. Some drivers reject them even in shaders without a
  `#version` line, while the software renderer the smoke tests use accepts them.
- `BoardRocksTest`: closed, outward-wound kit solids; more and taller formations
  on deep rims; anchors kept clear; on paved ground, rubble only below a concrete
  slab; picking and support meeting the same rock; deterministic placement.

Native OpenGL tests, tagged `on-demand`, run with `gpuBoardSmoke`:

- `GpuTerrainShowcaseSmokeTest` renders a deterministic review scene for chosen
  families: a plateau with a notch, a tall formation, a ridge, mixed-height
  junctions, a basin, a lake and woods of every density. Views are the overview,
  oblique, clay, grid-free, rim, base, formation, ridge, corner, notch, a top-down
  junction, the woods and a light wood up close. Frames pass through the production
  composite's
  exposure. It writes a report of build time, draws and vertices. Use
  `-Dmegamek.gpu.showcase.families=SAND,GRASS`, `-Dmegamek.gpu.showcase.hour=13`
  and, to capture only some views, `-Dmegamek.gpu.showcase.views=rim,woods`.
  `-Dmegamek.gpu.showcase.transitions=true` renders with hex transitions on and
  adds `-transitions` to the file names. `-Dmegamek.gpu.showcase.hours=17.5,17.75,18` captures
  each view at several hours in one run, adding `-h<hour>` to the file names (time-lapses of
  the sun/moon handover).
- `GpuTerrainMaterialsSmokeTest` checks that grassland banks are earth and cliffs
  rock, that concrete steps and the slab of a three-level concrete cliff are paler
  than the bedrock under it, that rain darkens every family but snow, and that
  special art keeps its own colour.
- `GpuShadowSmokeTest` and `GpuFieldOfViewCliffSmokeTest` use a two-level
  concrete step. Its faces stand exactly on the hex outline with no relief, so
  any shadow on a sunlit face is an artefact, and every wall pixel belongs to the
  raised hex. Natural cliffs shade themselves with their relief, and their lips
  and talus take the visibility of the hex whose ground they cover.

## Measured cost and limits

These numbers are from this change's cloud runs: Mesa llvmpipe software rendering
and a two-core CPU. They are not GPU frame times.

- CPU sculpting of a 32×32 rugged board takes about 1 ms per hex, single-threaded.
  Chunks are prepared in parallel. A full `GpuTerrain.update` of that board takes
  about 0.9 s after warm-up.
- With hex transitions on, building the review scene's terrain takes 10–17%
  longer (sand 1,610 to 1,777 ms, grass 890 to 1,042 ms).
- The review scene (224 hexes) draws in 3–12 calls per view, with 0.4–1.1 million
  vertices submitted. Zoomed-in grassland views add the existing per-hex grass-blade
  cover, up to 185 calls in total.

Known limits:

- Wall and ground maps are 512 px procedural textures. They are convincing at
  board distances, but close-ups show their synthetic origin.
- Water and road hexes keep their previous geometry. Corners next to them stay
  sharp, because those corners are pinned.
- Legacy skirts (cornices) with rain run-off remain only on unsculpted edges.
  Sculpted cliffs darken in rain but have no run-off streaks.
- With hex transitions on, the deeper sand joints turn more wall triangles toward
  their own hex on the rugged probe board (`FoldProbe`): 332 below a dot of -0.35
  (40 before the columns), 30 of them below -0.6, all sub-metre crumples inside
  joints near one convex corner of a one-hex pillar five levels high. With
  transitions off there are none. No top folds in either case.

On the user's laptop, 2026-09-24, with a MegaMek game open at the same time (so
indicative): the 32×32 production board (`GpuTerrainBenchmarkSmokeTest`, two
runs per GPU, alternating) takes a median 14.6–24.8 ms per frame at rest and
19.5–40.9 ms while panning on the Intel Iris Xe (driver 32.0.101.7088), and
2.9–10.5 ms at rest and 5.3–8.7 ms while panning on the NVIDIA RTX 4070 Laptop
GPU (driver 610.88). The forest benchmark (40×40 hexes, 5,499 trees) takes
13–21 ms at far and middle zoom on both GPUs alike, so there the CPU sets the
pace. Triangles on the probe board, sand: tops 58,752, walls 124,080, rocks and
shrubs 45,896 (49,900 before the desert's larger bushes, which use the cheaper
shrub masses).
