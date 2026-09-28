# Planted fields, wetlands, and liquid contacts

The 3D board now draws supported planted fields, marsh, quicksand, and mud through
the natural terrain renderer. This replaces the legacy field prop and flat tile
artwork for those supported hexes. Unsupported combinations retain their original
artwork. Terrain rules, movement, cover modifiers, picking, and unit support remain
owned by the existing game and board surface.

## Appearance and continuity

Fields have world-aligned furrows and instanced crop stalks with leaves and muted
seed heads. Rows continue across adjacent field hexes. Plants vary in height around
half a displayed terrain level; they follow the actual surface, leave road
corridors clear, and thin out at the field perimeter.

Marsh uses a shared world-space wetness field for peat, low sedge hummocks, shallow
reflective pools, and reed/cattail placement. Adjacent marsh hexes therefore share
pool patterns rather than repeating a separate stamp. Quicksand is bare mineral
silt; mud is darker wet soil. Neither receives the marsh's reeds. The small
hummocks affect shading normals; they do not add physical terrain heights or
invent water terrain where the board has none.

The refinement uses a warped wetness field, sharper filtered pool margins, shared
earth colour/normal maps, and duckweed at sheltered edges. Dense sedge/cattail
clumps use a [generated transparent cutout](marsh-sedge-asset.md) on bent crossed
surfaces. This retains curled leaf detail with 8/4/2 triangles per clump across
LOD0/1/2. The distant clump is a single camera-facing quad; the nearer tiers use
two crossed cards. Ordinary grass, fields, and the soil under the marsh retain their own
materials; the bank's wider sediment reach does not cover cultivated rows.

Marsh, mud, and quicksand influence nearby shallow water with suspended peat/silt,
calmer waves, and reduced shore foam. The material query reaches the sculpted
waterline inside the water hex. Wet sediment fades out within 1.1 metres below
water; crops and turf do not extend down the bed. Natural grass/sand/rock/snow and
cliff transitions retain the earlier [material transition implementation](gpu-terrain-transitions.md).

Land and water-owned bank triangles now evaluate the same wetland coverage and
slope gate. Emergent marsh cover is suppressed below the waterline rather than
at the original hex boundary, removing the bare triangular wedges in the reported
shoreline. The wet biome's own dampness also prevents double darkening at that join.

The follow-up shoreline correction removes two remaining boundaries: suspended
sediment tint now fades in only below the actual waterline, and reeds use the same
wide wetland coverage as the ground instead of stopping at the original hex edge.
Clumps thin out deterministically onto exposed banks and bars. Their roots sample
the finished ground triangles and reject the water plane, submerged ground, and
rock props. Bank patches retain the existing preparation budget, shared batches,
and three vegetation LODs; neighbour edits invalidate them with the existing key.

Biome shading also runs on natural slope/cliff faces. The same nine-hex stencil
now supplies a wider, irregular soil/peat fringe as well as the existing narrower
plant/pool support. Cultivated earth and wet sediment therefore continue through
the slope toe instead of ending at the flat mesh. Reflective pools require nearly
level ground; crop rows and moss growth fade off steeper faces. Deep submerged
beds still keep their own material. The fringe adds no terrain geometry or mask
texture fetches. Fields reuse the existing soil maps for the exposed earth.

Fields and reed marshes now keep the underlying theme's natural surface family
(grass on the default theme) instead of classifying the entire hex and its bank
as dirt. Mud and quicksand remain dirt. Cultivation fades before the steep face;
the bank uses the established grass/soil mantle, including its texture relief and
normal. Biome margins use that same material-height competition rather than a
broad colour tint. Wet peat can reach the toe without painting pools up the slope.

Adjacent wetlands at different elevations now share peat across the intervening
bank. The existing slope normals and continuous level grading still show the
height change from above. Toward lower ordinary ground, sediment has an uneven,
world-aligned drainage reach and fades through damp turf into the native surface;
it does not end at a constant height contour. The bank retains partial coverage
instead of amplifying every sediment patch to a solid mask. Soil colour and normal
maps use the existing slope projection to avoid stretching down steep faces.
Pools retreat irregularly before a downhill lip, remain confined to level ground,
and retain the narrower uphill/underwater limits. These changes add no triangles,
instances, textures, draw calls, or neighbour-mask fetches.

There is no dedicated marsh ground bitmap. The ground shader borrows
`data/models/board/textures/sculpt/earth.png` (colour/height) and `earth-normal.png`
(normal/occlusion); peat, moss, saturated margins and pools are composed in
`terrain-biome.glsl`. The plant cutout is owned by mm-data at
`data/models/board/textures/foliage/marsh-sedge.png` and loaded from the configured
game data directory after normal data staging.

The reduced crop mesh has broader leaves, a more legible seed head and stronger
lit leaf colour. Its row canopy is fuller and shades the furrows more strongly.
This improves its silhouette without restoring the removed stalks or triangles;
plant height, lighting and shader wind remain shared with the existing vegetation.

Clear, Mars-tinted, volcano-tinted, and hazardous green waters share a continuous
world-space optical mixture. Both surface and submerged bed use the same weights
for absorption, scattering, and shallow tint. Warped coordinates and filtered
pigment filaments break up the contact. Green and volcanic water use muted olive
and rust palettes. This is a visual mixing band, not fluid transport; liquid
types and hazards remain those of the board. Ice, magma, and water at different
surface elevations do not enter the same mixture. Hazardous water now qualifies
for sculpted terrain, so its bed participates in these transitions too.

## Lighting, wind, and LOD

Crops and reeds use the existing vegetation lighting shader: sunlight, ambient
light, geometry/cloud shadows, and wetness affect their colour. Wind bends the
vertices progressively above fixed roots and adjusts their normals. It does not
rebuild or upload plant instances. Wet ground catches specular light; marsh pools
use the existing sky reflection and rain response, with small wind ripples.
Individual crop/reed shadow casting is not implemented.
The native result still uses the board's existing lighting and sky reflections;
it does not reproduce the generated reference's individual plant reflections or
fine ground relief. The reference remains an art target, not a description of the
renderer's present fidelity.

Plant LOD0/LOD1/LOD2 reduce both geometry and deterministic subsets of roots, using
the existing projected-size crossfades. Crops use 10/6/2 triangles per stalk; their
distant tier is one tapered camera-facing stalk. A stable world-space selection
retains 38% of crop candidates and 46% of marsh candidates before support sampling.
It is independent of wetland coverage and plant height, and shared by every LOD,
so density reductions do not move rows or create tile boundaries. Middle and
distant tiers retain smaller nested subsets of these roots. At very distant
scales plants fade away entirely, leaving
the ground shader. Per-root complementary coverage is shared
by perspective and orthographic views. Cutout textures are sampled before coverage
discard, preserving derivatives and preventing mip flicker during a handoff. Distant ground retains
the row and wetland shader patterns with derivative-based detail fading. All four
terrain mesh tiers (FULL/MEDIUM/COARSE/DISTANT) use the same material coordinates.

`GpuBiomeSurface` owns one RGBA8 texel per hex: biome, aqueous palette, and elevation.
It updates on the render thread when the captured tile data changes and is disposed
with the terrain. The stencil is bounded to nine candidate hexes. It is disabled
on ordinary boards with no special ground and only one water palette. Plant roots
are prepared within the existing frame-budget approach and uploaded only when
their visible patch data changes. There are at most six plant batches: two plant
types times three LODs. No per-hex material textures are generated.

Root preparation starts with the LOD2 subset and extends the same deterministic
lattice only when LOD1 or LOD0 is visible. A small 8x8 CPU index references the
published support triangles; queries still use the existing triangle sampler and
its edge tolerance. Both ground and water-plane rejection use it. Uniform biome
interiors skip the neighbour stencil only where its coverage is necessarily one.
Replacing a patch during a terrain LOD handoff now shares the 2 ms preparation
budget, including patch construction. The budget is checked every eight candidate
sites. These changes add no GPU geometry, textures or plant draw calls.
The vegetation renderer owns one shared, mipmapped cutout texture and disposes it
with its batches. It filters the original PNG to a 512x512 RGBA8 GPU upload:
1,398,100 bytes (1.33 MiB) including mipmaps, versus approximately 8 MiB previously.
The full-resolution source asset is retained. Wetland soil maps are borrowed from
the existing asset cache.

Like ordinary grass, plants use small shared meshes, GPU instancing and shader
wind; each instance stores just its root and seed (16 bytes). Marsh cards additionally
sample the cutout, so overlapping cards still have a fragment-shading cost. The wet
ground, pools and field furrows shade the existing terrain and add no terrain triangles.
Ordinary grass uses 7/3 triangles per individual blade; a marsh instance depicts a
whole tuft, so those primitive counts are not equivalent vegetation densities.

## Native verification

Checked locally on 28 September 2026 using the actual OpenGL renderer at 1280x960:

- `GpuBiomeSmokeTest`: fields, marsh, quicksand, mud, all three plant LODs, all four
  terrain tiers, day/evening lighting, wind, stable instance uploads, terrain edits,
  texture disposal, shallow/deep shore contacts, shoreline captures at all three
  plant LODs with water shown/hidden, and the actual Fire And Ice 2 map.
- `GpuLiquidBlendSmokeTest`: all six pairings of the four aqueous palettes, all six
  hex-edge directions, three-way contacts, equal surface/bed mixture weights,
  uniform interiors, and elevation/ice/magma barriers. It compiles and reads back
  the production GLSL mask rather than reproducing its logic in Java.
- `GpuBiomeLodSmokeTest`: both fields and marsh, both camera projections, both zoom
  directions, every fade boundary, and unchanged-surface handoffs. The strict image
  continuity check caught and verified the correction for mip flicker in cutout plants.
- `GpuTerrainContactSmokeTest`: the earlier cliff and submerged-material regression.
- `GpuMarshSlopeSmokeTest`: connected marsh levels, upper marsh draining to lower
  grass, and lower marsh against an uphill bank, in top and isometric views across
  all four terrain mesh tiers. A production-GLSL readback checks continuous peat,
  uneven downhill reach, limited uphill reach and no pool support halfway down
  the bank. The preceding shader fails the connected-peat regression.
- `GpuTerrainBlendSmokeTest`: natural material rendering and local-edit versus
  clean-rebuild comparison. The comparison now waits for bounded grass preparation
  and uses the same animation time on both renderers.
- `GpuTerrainTextureBindingSmokeTest`: shared texture residency and atlas ownership.
- `GpuVegetationEditSmokeTest`: vegetation responds to terrain edits and reuses
  preparation when the finished supporting surface is unchanged.
- Scoped main/test Checkstyle and whitespace checks.

The earlier focused CPU run passed 55 tests covering biome capture/root placement,
terrain features, surface blending, liquid types, rivers, terrain LOD, and shader
source validation.

The earlier `BoardSource`/map-editor compile blocker has been resolved by the
concurrent refactor. The shoreline follow-up compiles the current application and
test sources. Its new CPU regression checks reed placement on exposed banks,
unique ownership, actual ground support, exclusion below water, and invalidation
when the neighbouring marsh is removed.
The follow-up passed 23 focused CPU tests, seven native tests across the biome,
LOD, liquid-contact, terrain-contact, and vegetation-edit suites, and scoped
main/test Checkstyle.

The lightweight follow-up uses the native biome test and both parameterized LOD
tests, including strict image continuity through every handoff in perspective and
orthographic views. The native fixture enforces triangle ceilings at all three
tiers and checks the shared cutout upload size. It captures fields and marsh both
above and below a neighbouring slope at every terrain mesh tier, and verifies that
removing only the biome mask changes rendered bank-face pixels. The distant crop
and marsh quads are also captured directly from above and after rotating the camera.

The density-and-slope follow-up passed five CPU biome tests and five native cases:
both plant LOD continuity cases, the biome/bank material test, liquid mixing, and
cliff/submerged-bed contacts. Native tests compiled the current vegetation and
renderer sources into a separate output and used a copy of the last successful
application/test classes. This avoided unrelated `BoardView`/`BoardSession` compile
errors and concurrent builds replacing class files during a running test. It
verifies this rendering change, not a complete build of the concurrently edited
application. Native screenshots are actual renderer output, not generated references.

The grass-bank/zoom follow-up passed seven CPU biome cases and six native cases
(biome materials/budgets, both plant LOD continuity cases, actual terrain-LOD zoom,
liquid mixing and cliff/waterbed contacts), plus scoped main/test Checkstyle.
The CPU checks compare indexed support against the original triangle sampler at
bank vertices and random positions across all four mesh tiers, and verify that
progressive LOD preparation produces the same full root lattice. The native zoom
test exercises separate grass, field and marsh 24x24 boards over three continuous
zoom-in/out passes, with terrain LOD enabled, and records a JFR profile.

On the RTX 4070 Laptop test machine, an old/new vegetation comparison with the
same frozen application/shader snapshot recorded these CPU frame p95 ranges over
the three passes: fields 8.05–15.05 ms before, 3.97–4.89 ms after; marsh
7.30–7.50 ms before, 2.71–4.02 ms after. The original path was preparing plants
in all 180 frames of each pass. The updated path needed 118/79/80 frames for
fields and 64/35/83 for marsh. These are local render-thread submission timings,
not whole-game FPS or a guarantee on other machines; builds and other native
work remained active. Raw reports/JFR and screenshots are under
`megamek/build/biome-zoom-review/{baseline-captures,captures}`. Java and shader
files were frozen together for this comparison; concurrent wind/scatter changes
were outside the verified snapshot.

The marsh-slope refinement passed four native cases (marsh slopes, biome materials
and plant budgets, liquid mixing, and cliff/waterbed contacts), scoped test
Checkstyle, and whitespace checks. Its current shaders and new test ran against
the preserved application runtime to isolate concurrent Java work. The captures
and metrics are under `megamek/build/marsh-slope-review/captures`. Plant counts
remain the same as the table below. This verifies the shader change and existing
geometry budgets, not a complete build or an FPS claim for the concurrently
edited application.

The 3x3 special-ground fixture produced these submitted plant counts, including
the marsh fringe. These are triangles sent to the GPU, not whole-frame cost or
counts of triangles ultimately visible on screen:

| Plants | LOD0 triangles, before → after | LOD1 triangles, before → after | LOD2 triangles, before → after |
| --- | ---: | ---: | ---: |
| Crops | 135,040 → 26,080 | 29,100 → 5,778 | 4,448 → 420 |
| Marsh reeds/sedge, including its fringe | 20,952 → 9,248 | 5,020 → 2,164 | 630 → 272 |

Compared with the preceding lightweight pass, marsh geometry falls by
55.9%/56.9%/56.8%; crops by 80.6%/80.1%/90.6%. The near fixture submits 1,156 marsh
clumps and 2,608 crop stalks. The middle/far tiers submit 541/136 clumps and
963/210 crop stalks. Pure-tier fixture ceilings are
10,476/2,510/314 marsh triangles and 30,000/6,000/500 crop triangles; these
are regression budgets for this fixture, not hard caps for arbitrary maps.

Each homogeneous fixture used one plant draw at each LOD. Mud and quicksand added
none. During crossfades adjacent tiers overlap; a mixed board can use up to six
plant draws. The native shader check observed at most 15 active texture samplers,
within the existing 16-sampler budget.

The surrounding ordinary grass submits 688,128/27,432/0 triangles at these three
scales in both fixtures, far more than either special vegetation batch up close.
That grass occupies the surrounding area, not the nine special hexes, so this is
not an equal-area grass-versus-marsh benchmark. The fixture waits for both grass
and biome root preparation before recording counts or timings. It reports grass
separately and excludes cached buffers when the renderer skips grass at distant scales.

Whole-frame GPU samples at 1280x960 on a GeForce RTX 4070 Laptop GPU are recorded
after 12 warmup frames. They include terrain, ordinary grass and atmosphere and
omit units; they do not isolate the plant draw. Concurrent native/build activity
and large run-to-run timing variation make them unsuitable for claiming an FPS
gain. Triangle and resource counts are the verified budgets, not a hardware-wide
performance guarantee. The comparison baseline for this reduction is in
`build/biome-budget-review/before`; the latest native counts and timings are in
`megamek/build/gpu-board-review/fields-marsh/metrics.txt`.

An earlier broader CPU check reported six failures in
`BoardSurfaceTest.roadsRampUpAndDownToAlignedBridgeDecksOverLandAndWater`, at the
assertion about rounding the road's departure from its hub. Bridge-ramp geometry
was not changed by this work. The current Fire And Ice native check exposed a null
fallback road normal; it now safely defaults to the upward normal outside a road
ramp. The capture still exposes gaps around bridge/road geometry; it is not a
clean bill of health for that code.

Native output is in `megamek/build/gpu-board-review/fields-marsh` and
`megamek/build/gpu-board-review/liquid-blends`. Useful captures:

- [Crop field](../megamek/build/gpu-board-review/fields-marsh/FIELD-close.png)
- [Crops below a slope](../megamek/build/gpu-board-review/fields-marsh/FIELD-slope-lower-FULL.png)
- [Crops above a slope](../megamek/build/gpu-board-review/fields-marsh/FIELD-slope-upper-FULL.png)
- [Connected marsh](../megamek/build/gpu-board-review/fields-marsh/MARSH-close.png)
- [Marsh at a slope toe](../megamek/build/gpu-board-review/fields-marsh/MARSH-slope-lower-FULL.png)
- [Shallow water to marsh](../megamek/build/gpu-board-review/fields-marsh/water-MARSH-shallow.png)
- [Shoreline at LOD0](../megamek/build/gpu-board-review/fields-marsh/shore-MARSH-LOD0.png)
- [Shoreline at LOD1](../megamek/build/gpu-board-review/fields-marsh/shore-MARSH-LOD1.png)
- [Shoreline at LOD2](../megamek/build/gpu-board-review/fields-marsh/shore-MARSH-LOD2.png)
- [LOD2 clumps from above](../megamek/build/gpu-board-review/fields-marsh/MARSH-LOD2-top.png)
- [LOD2 clumps after rotation](../megamek/build/gpu-board-review/fields-marsh/MARSH-LOD2-orbit.png)
- [Fields, marsh, and mud meeting](../megamek/build/gpu-board-review/fields-marsh/field-marsh-mud.png)
- [Shallow water to mud](../megamek/build/gpu-board-review/fields-marsh/water-MUD-shallow.png)
- [Three liquid colours](../megamek/build/gpu-board-review/liquid-blends/blue-red-green-FULL.png)
- [Their submerged bed](../megamek/build/gpu-board-review/liquid-blends/bed-blue-red-green-FULL.png)
- [Fire And Ice 2](../megamek/build/gpu-board-review/fields-marsh/Fire-And-Ice-2-top.png)

## Generated references

The built-in image generator supplied field, marsh, shoreline, and liquid-contact
concepts. Its API does not expose an Imagen 2.5 selector. These images are design
references, not native screenshots or a claim of matching Gaea's output.
[Saved images and complete prompts](../build/fields-marsh-review/references.md)
are in `build/fields-marsh-review`.
The production cutout's saved path, provenance and complete prompt are recorded in
[marsh-sedge-asset.md](marsh-sedge-asset.md).

The native suites can be rerun with:

```powershell
.\gradlew.bat :megamek:gpuBoardSmoke --tests '*GpuBiomeSmokeTest' --tests '*GpuBiomeLodSmokeTest' --tests '*GpuLiquidBlendSmokeTest' --tests '*GpuTerrainBlendSmokeTest' --tests '*GpuTerrainContactSmokeTest' --tests '*GpuTerrainTextureBindingSmokeTest' --offline
```
