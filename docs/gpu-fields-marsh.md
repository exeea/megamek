# Fields, wetlands and liquid contacts

The native terrain path draws supported fields, marsh, quicksand and mud using
ground shading and instanced vegetation. Unsupported combinations retain their
tileset artwork. Rules, movement costs and cover stay in the game model;
placement and picking use the finished board surface.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `BoardFeatures`, `BoardBiome` | Capture biome kind and compute common world-space rows, patches and coverage. |
| `GpuBiomeSurface` | Upload biome, aqueous palette and elevation attributes for material shaders. |
| `terrain-biome.glsl`, `terrain-biome-mask.glsl` | Shade cultivated soil, peat, pools and continuous biome contacts. |
| `BoardPlants` | What each terrain detail level plants: crops, reed tiers and grass. |
| `GpuBiomeVegetation` | Plant crop strips and reed roots for terrain workers, select detail and submit per-chunk instanced plants. |
| `terrain-biome-vegetation.glsl` | Shape/bend the plant vertices; use the shared vegetation lighting fragment program. |
| `GpuTerrain` | Own the wind phase, plant ground cover with each prepared chunk, install it with the chunk, and invalidate support after edits. |

## Ground and boundary appearance

Fields have world-aligned furrows and crop rows that continue across adjacent
hexes. Marsh uses a shared wetness field for peat, sedge hummocks, shallow pools
and reeds. Quicksand is bare mineral silt; mud is darker wet soil without reeds.
Hummocks change shading normals, not physical terrain or the board's water flags.

Fields and reed marshes retain their theme's natural surface family, usually
grass. Mud and quicksand classify as dirt. Cultivation fades before a steep face;
the bank retains its grass/soil mantle and normal relief.
`terrain-biome.glsl` borrows `textures/sculpt/earth.png` and
`earth-normal.png` for soil color/height and normal/occlusion. Peat, moss,
saturation and pools are procedural combinations of those shared materials.

Land-owned and water-owned banks evaluate the same coverage and slope gate.
Emergent cover stops at the actual waterline rather than the original hex edge.
A wider soil/peat fringe reaches slope toes and downhill ground; pools require
nearly level ground and retreat before a downhill lip. Crop rows and moss fade
on steep faces. Adjacent wetlands at different elevations share peat over their
bank while slope normals and level grading preserve the height change.

Sediment reaches lower ground with an irregular world-space boundary and uses the
existing slope projection. Underwater influence fades within 1.1 metres; deep
beds retain their own material. Reeds thin deterministically onto exposed banks
and bars, sampling finished ground and rejecting submerged ground, the water
plane and rock props. Neighbor edits invalidate both ground and vegetation
inputs. Ordinary family boundaries use [terrain contacts](gpu-terrain-contacts.md).

## Plant assets and placement

Runtime cutouts live in `mm-data/data/models/board/textures/foliage`:

| Files | Use |
| --- | --- |
| `marsh-sedge.png` | Sedge/cattail clumps on bent crossed cards, reduced to a camera-facing card at distance. |
| `crop-row-side.png`, `crop-row-front.png`, `crop-row-top.png` | Seven distinct wheat variants, with matching upright and overhead views. |
| `crop-row.json` | Pixel-space stem bases and crown centers for registering the three views. |

Crop strips repeat seven broad-leaf wheat variants with large ripe golden ears.
Alternate 1.15 m furrows are planted, giving 2.3 m between crop rows. Strips crop
their UVs at edges and roads and split to follow the published ground. Their
centerlines stay grounded. Each plant has a side plane through its stem, a
perpendicular front plane, and an overhead plane through the grain head. The
three views share one atlas and the same registered plant center. Individual
overhead cards fade out at oblique angles, where their flat leaves would otherwise
cut through the larger grain heads. From above they fill out each crown while
the upright cards fade, hiding the edges exposed by their individual leans. The
sprites derive their fill along the row from width and spacing, and fill 70%
across it, leaving space between plants; the near geometry omits those empty
gutters. All seven variants are retained in every strip.
Pixel anchors belong to the artwork metadata, rather than independently cropping
the three sheets and shifting their plants. Source prompts are recorded in
`mm-data/tools/crop-texture-prompts.json`.

The adjacent appearance constants in `GpuBiomeVegetation` control plant shape:
`CROP_SCALE` sets overall size; `CROP_WIDTH_SCALE` widens both horizontal axes
without adding height; `PLANT_GAP` sets the clear gap along each row. At the
current overall scale 2 and width scale 2, the 1.2 m leaf span plus a 1 m gap
gives a 2.2 m plant pitch and a 15.4 m seven-plant strip.
Strip length and atlas occupancy derive from that pitch. Every other planted row
is shifted by half the pitch (currently 1.1 m), derived from those controls. Near
plants and distant canopy runs share this world-space stagger across hex boundaries.

Marsh clumps use deterministic candidate selection and smaller nested subsets
at distance. Crops retain continuous rows: thinning whole strips would leave
large gaps. Plants clear road corridors and fade at biome perimeters.
Placement belongs in `GpuBiomeVegetation`; ground patterns and coverage belong
in `BoardBiome` and the shared biome helpers.

Each supporting triangle is intersected once with the furrows that cross it, which
gives the exact published ground along every furrow. A strip on level ground farther
than the 2.2 m coverage fade from other ground or levels follows those pieces and
splits only where the ground bends by more than 8 mm; there the shared coverage is
exactly the hex's own level weight. Strips near such edges, on slopes or in road
hexes sample coverage and road clearance every 7.5 cm, as before.

## Lighting, wind and detail

Crops and reeds receive scene ambient/direct light, geometry/cloud shadows and
wetness; wet leaves also catch a water-film glint in direct light. They do not
cast individual plant shadows. Shader wind bends vertices
above fixed roots and adjusts normals without rebuilding instance buffers.
The shared phase and bend contract are described in [grass](gpu-grass-rendering.md).

Marsh uses 8/4/2 triangles per clump; middle and distant tiers use nested 35% and
8% subsets of prepared roots. Crops use seven six-triangle plants per instanced strip
nearby and a two-triangle canopy at distance, crossfading over 64–128 projected
pixels per hex. The distant canopy is planted as runs: adjoining strips of one
furrow merge while every strip end stays within 5 cm of the run's line, and the
run repeats the strip artwork along its length. The crossfade is evaluated at
each vertex, so a long run hands over smoothly. Seen at an angle, the canopy takes
its colour from the side artwork's golden head band, as the near rows show it;
from overhead it shows the crowns. Rows this tall hide the furrows between them
from an oblique view, so the canopy widens from its own width to the full row
spacing as the view leaves the vertical. At very small scale only ground shading
remains; its crop tint spans the same leaf-to-head range.

Each near plant also has a small permanent lean, bounded by `CROP_LEAN` per
horizontal axis. Its planted row and stem index determine that lean, so the
heads vary within a row while every root remains on its staggered seed position.
Wind combines with this resting lean; the distant tier retains its canopy approximation.
All three planes sample wind at their shared plant center. Clipping a strip to
another hex or supporting triangle retains that original center, texture phase,
resting lean and gust. Front planes belong to one half-open support interval, preventing
duplicate faces at a split. Trilinear mipmaps remain enabled for both the near
atlas and distant canopy. Crops use opaque alpha cutouts with depth writes;
the extra near geometry trades vertex work for less shading of empty gutters,
not an assumed performance improvement.

Nearly transparent crop texels retain a region's average plant colour, preventing
dark fringes when mipmaps average colour and alpha separately. The distant canopy
scales its alpha with the sampled mip level so box-filtered alpha cannot thin a
row below the cut-off. The field ground is shaded olive under nearby plants and
takes the golden crop tint only where plants dissolve at distance.

Complementary per-root coverage is shared by both camera projections.
Cutout sampling occurs before coverage discard to preserve texture derivatives
during detail handoff. Ground patterns use derivative-based fading and the same
material coordinates across all terrain mesh tiers.

## Water mixing

Marsh, mud and quicksand add suspended peat/silt to nearby shallow water, calm
its waves and reduce shore foam. Suspended tint starts below the actual waterline;
the wet biome's own dampness avoids double darkening at the bank.

Clear, Mars, volcanic and hazardous water use a continuous optical mixture.
Surface and submerged bed share absorption, scattering and shallow-tint weights;
warped coordinates and pigment filaments break up the boundary. Ice, magma and
water at different surface elevations are excluded. This affects appearance,
while liquid type and hazards continue to come from the board.

## One pipeline for ground cover

Crops, reeds and grass follow one contract, so a board of thousands of planted
hexes loads, draws and edits the same way as a small one:

1. **Planting is terrain work.** `BoardPlants.plant` runs on the terrain worker
   for every hex of a chunk, on the hex's finished support, and returns what
   that chunk's detail level can show: nothing at distant detail; crops from
   coarse detail; reeds thinned to the tiers their scale draws (8% at coarse,
   35% at medium, all at full); grass on full detail only. The installed tile
   keeps the result, a tile an edit reuses keeps its plants, and a chunk
   replaced at another detail level brings its own. Memory follows detail, not
   board size. Planting is deterministic: a chunk prepared again on equal ground
   yields equal plants.
2. **Submission is per chunk.** Each renderer keeps persistent instance buffers
   per chunk and template. A chunk's buffer holds all of its candidate plants
   and uploads only when their contents change; its in-view hexes choose which
   templates draw, and the shaders skip plants outside a template's band or the
   view. Grass draws each hex's prefix by rank as one range of the chunk's
   buffer. The coarsest tier of each kind (crop canopy runs, distant reeds) is
   one board-wide batch.
3. **Weather is shared.** Every kind bends in the same world-space gust, darkens
   and glints with the same wetness, and receives the same geometry and cloud
   shadows, through `terrain-vegetation-wind.glsl` and `terrain-vegetation.frag`.
   No plant casts its own shadow, and no cover accumulates snow: the ground
   does not either.
4. **Nothing prepares on the render thread.** A board shows its plants when its
   terrain is ready, and a stationary view reuses its submission until a chunk
   changes.

The remaining pop-in risk is the terrain's own: a chunk refined to full detail
while the view already exceeds the grass threshold shows its grass when the
chunk arrives, as it shows the finer ground then.

## Caches and ownership

`GpuBiomeSurface` owns one RGBA8 texel per hex and a bounded nine-hex shader
stencil. It updates on the GL thread when captured tile data changes, and is
disabled when no special ground or palette mixture needs it. Spare bits of the
texel also flag the quarters of the hex's stencil cell (halves of its column and
of its row) that a biome hex, or liquids of two or more palettes, can reach:
14 m for biome blends, 5 m around a liquid position the noise moves up to 3 m.
A fragment reads its own quarter's flag first and skips the stencil elsewhere,
which on a mixed board such as MesaCity is most of the ground (the stencil was
about 4 ms of the marsh test board's 19 ms ground at 110 px/hex). Water of a
single palette is never flagged: there the stencil can only return its own
palette, which the shaders already use when it is skipped.

Reeds plant their distant subset first and extend the same deterministic
lattice for closer detail, so a chunk refined from medium to full detail keeps
the reeds it showed. A small CPU index accelerates queries into published
support triangles while retaining the same triangle sampler and edge tolerance.

Near crop strips and the two nearer reed tiers are submitted per terrain chunk;
the distant canopy runs and distant reeds each share one board-wide batch. A
chunk's buffers hold all of its candidate plant hexes, in view or not, so panning
selects chunks instead of rebuilding buffers; a chunk draws only the templates
its in-view hexes need. A batch uploads again only when its membership or
contents change, such as a replaced chunk with different crops. The vertex shader
skips plants outside a template's detail band or beyond a clip plane before
shaping them. Draws are ordered by chunk, nearest first, so near rows can reject
the rows behind them. Plant-kind membership is derived once per captured tile
list. A settled camera with unchanged installed plants reuses the previous
submission. Shader wind still advances every frame.

The vegetation renderer owns shared mipmapped cutouts and disposes them with
its batches; soil maps are borrowed from the terrain asset cache. Marsh uses a
512-square upload, crops a 2048×640 atlas with a 512×256 distant canopy. Instances
store roots/seeds and, for crop strips, length, rise and source UV endpoints.
These cutout cards still have fragment cost where they overlap; ground pools and
furrows add no terrain geometry.
