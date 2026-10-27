# Fields, wetlands and liquid contacts

The native terrain path draws supported fields, marsh, quicksand and mud using
ground shading and instanced vegetation. Unsupported combinations retain their
tileset artwork. Rules, movement costs and cover stay in the game model;
placement and picking use the finished board surface.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `BoardFeatures`, `BoardBiome` | Capture biome kind and compute common world-space rows, patches and coverage. |
| `BoardFields` | Divide planted ground into straight-edged parcels, each with its crop and row direction. |
| `GpuBiomeSurface` | Upload biome, aqueous palette and elevation attributes for material shaders. |
| `terrain-biome.glsl`, `terrain-biome-mask.glsl` | Shade cultivated soil, peat, pools and continuous biome contacts. |
| `BoardPlants` | What each terrain detail level plants: crop plants, reed tiers and grass. |
| `GpuBiomeVegetation` | Plant crop plants and reed roots for terrain workers; submit per-chunk instanced reeds. |
| `terrain-biome-vegetation.glsl` | Bend the reeds; use the shared vegetation lighting fragment program. |
| `terrain-vegetation.frag` | Light the grass, crop plants and reeds. |
| `terrain-crops.glsl` | The crops' colours and their plants' hand-over, shared by their plants and ground. |
| `GpuTerrain` | Own the wind phase, plant ground cover with each prepared chunk, install it with the chunk, and invalidate support after edits. |

## Ground and boundary appearance

Planted ground is divided into straight-edged parcels (`BoardFields`): the
world splits into north-south strips about 90 m wide, and each strip into
parallelograms by cuts about 75 m apart at 30° to the x axis, rising or falling
per strip, so every parcel edge runs along one of the board's road directions.
Widths and spacings are jittered. A parcel's look depends only on where it is;
the FIELD hexes decide only where planted ground lies, and its outer edge keeps
the usual coverage fade. Each parcel grows its own crop, wheat or maize, so
every planted field stands full, 0.9 levels tall, with rows along one of its edges.
Neighbouring parcels meet at a grass headland 2.4 m wide, at least a pixel at any
zoom. The ground
shader evaluates the parcel per fragment (`terrain-biome.glsl` mirrors
`BoardFields`); plants are planted per parcel.

Marsh uses a shared wetness field for peat, sedge hummocks, shallow pools
and reeds. Quicksand is bare mineral silt; mud is darker wet soil without reeds.
Hummocks change shading normals, not physical terrain or the board's water flags.

Fields and reed marshes retain their theme's natural surface family, usually
grass. Mud and quicksand classify as dirt. Cultivation fades before a steep face;
the bank retains its grass/soil mantle and normal relief.
`terrain-biome.glsl` borrows the `terrain/surfaces/biome-soil` material's
colour/height and normal/occlusion layers for soil. On horizontal surfaces,
colour mixes two repeats of the earth map; relief reads only the first. Steep faces
blend the two wall projections instead. Peat, moss,
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

The marsh's runtime cutout, `marsh-sedge_albedo.png` in
`mm-data/data/models/board/textures/vegetation`, draws sedge/cattail clumps on bent
crossed cards, reduced to a camera-facing card at distance. Crops have no artwork:
they are geometry in their own colours (`terrain-crops.glsl`).

Alternate 1.15 m furrows are planted, giving 2.3 m between maize rows. A crop is its
plants, whether or not the grass blades are shown (`GpuBiomeVegetation.CROP_KEEP` and
`CROP_FADE`):

| Projected pixels per hex | Representation |
| --- | --- |
| Above 550 (wheat), 160 (maize) | Every plant, among the grass blades (`GpuGroundCover`). |
| Down to 40 | Fewer plants by rank, each survivor wider by as much. |
| 40 to 24 | The plants thin out over the ground's crop colour. |
| Below 24 | The ground's crop colour alone (`terrain-biome.glsl`). |

Plants stand on medium and full terrain detail only, which a chunk keeps down to 24
pixels per hex.

**Plants.** Wheat stalks stand anywhere in their parcels, as many candidates per
hex as the grass's (4096): each a stiff ribbon with its ear between 81% and 97% of
its height and the awns closing it. Maize stands on the planted rows 1.25 m apart,
alternate rows staggered by half a step, each plant with a hashed rank: a stalk
with six leaves alternating up it, arching out and drooping at their tips, and a
thin tassel above. The grass engine draws a prefix by rank
(`GpuBiomeVegetation.plantStalks`). Below its keep size the share of a field's
plants drawn falls with the hex's projected size, and each survivor widens by the
same factor (`terrain-crops.glsl`), so the stand keeps its cover, colour and
grain while its instances thin out: the field looks the same as it shrinks. The
last plant of the prefix widens in rather than appearing at once.

**Ground.** Between the plants the field is tilled earth (`cropEarth`). As the
plants thin out, from 40 to 24 projected pixels per hex, it takes the stand's mean
colour from above, so the field keeps its colour without them.

Marsh clumps use deterministic candidate selection and smaller nested subsets
at distance. Plants clear road corridors and fade at biome perimeters.
Placement belongs in `GpuBiomeVegetation`; ground patterns and coverage belong
in `BoardBiome` and the shared biome helpers.

A hex reaching several parcels plants each with its own plants; a plant keeps to its
parcel, clear of its headland by its reach (`ROW_HALF_WIDTH` for maize,
`STALK_REACH` for wheat).

## Lighting, wind and detail

Crops and reeds receive scene ambient/direct light, geometry/cloud shadows and
wetness; wet leaves also catch a water-film glint in direct light. They do not
cast individual plant shadows. Shader wind bends vertices
above fixed roots and adjusts normals without rebuilding instance buffers.
The shared phase and bend contract are described in [grass](gpu-grass-rendering.md).

Marsh uses 8/4/2 triangles per clump; middle and distant tiers use nested 35% and
8% subsets of prepared roots.

Complementary per-root coverage is shared by both camera projections.
Cutout sampling comes before the coverage discard, to preserve its derivatives
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
   that chunk's detail level can show: nothing at distant detail; reeds thinned
   to the tiers their scale draws (8% at coarse,
   35% at medium, all at full); grass and the crops' plants on medium and full
   detail. The installed tile
   keeps the result, a tile an edit reuses keeps its plants, and a chunk
   replaced at another detail level brings its own. Memory follows detail, not
   board size. Planting is deterministic: a chunk prepared again on equal ground
   yields equal plants.
2. **Submission is per chunk.** Each renderer keeps persistent instance buffers
   per chunk and template. A chunk's buffer holds all of its candidate plants
   and uploads only when their contents change; its in-view hexes choose which
   templates draw, and the shaders skip plants outside a template's band or the
   view. Grass and the crops' plants draw each hex's prefix by rank as one range
   of the chunk's buffer. The distant reeds are one board-wide batch.
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

The two nearer reed tiers are submitted per terrain chunk; the
distant reeds share one board-wide batch. A chunk's buffers hold all of its
candidate plant hexes, in view or not, so panning selects chunks instead of
rebuilding buffers; a chunk draws only the templates its in-view hexes need. A
batch uploads again only when its membership or contents change, such as a
replaced chunk with different reeds. The vertex shader skips plants
outside their detail band or beyond a clip plane before shaping them. Draws are
ordered by chunk, nearest first, so near rows can reject the rows behind them. Plant-kind membership is derived once per captured tile
list. A settled camera with unchanged installed plants reuses the previous
submission. Shader wind still advances every frame.

The vegetation renderer owns the reeds' mipmapped cutout, a 512-square upload,
and disposes it with its batches; soil maps are borrowed from the terrain asset
cache. Instances store roots and seeds. Reed cards still have fragment cost where
they overlap; ground pools and furrows add no terrain geometry.
