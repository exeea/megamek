# Terrain realism: reference analysis and implementation plan

[Docs index](README.md) · [Geometry and materials](gpu-terrain-materials.md) · [Terrain contacts](gpu-terrain-contacts.md)

Updated 2026-10-04. Work belongs in `megamek_temp` and the sibling `mm-data`.
This replaces the September proposal recovered from commit `ef03a24dd1`.
Several foundations from that proposal now exist; they should be improved rather
than built again. The first implementation covers stone materials, rim contacts,
broader rock masses, cleft-linked debris, elevation color, ground/debris maps and
meadow proportions.
The second pass connects cliff ribs, broken rims and deposits through the existing
profile and varies the native slope shoulders. The renders still fall short of
photorealism, particularly in tree silhouettes, flat plateau interiors and the
visible regularity of the larger landforms.

## What we want

A board that reads as a connected natural landscape, with memorable rock walls,
pillars, ridges, sheltered hollows and deposits. Hexes remain the game model and
can remain visible as a tactical overlay. The natural surface should not look
like individually decorated hexagonal pedestals.

Richness needs hierarchy: a few prominent geological features, supporting groups
of smaller forms, and quiet traversable ground. Uniformly increasing noise,
scatter density or mesh resolution would obscure that hierarchy and cost more.

## What the references actually show

- **Gaea:** erosion has separate wear, deposit and sediment outputs, and its
  documentation recommends establishing larger structures before smaller erosion.
  The useful idea is that a gully, its exposed rock and its debris have the same
  cause. We can borrow that relationship without running an erosion simulation
  during play. [Erosion](https://docs.gaea.app/reference/nodes/simulate/erosion),
  [Erosion workflow](https://docs.gaea.app/using/using-gaea/understanding-erosion/erosion_2/).
- **Gaea's showcase:** broad rock masses, irregular subordinate cuts and strong
  light/shadow organization matter more than fine noise. The reviewed official
  monolith image has large quiet faces and a flared, dissected root. It is a
  composition reference, not a suitable literal obstacle for an ordinary clear
  hex. [Gaea 3](https://quadspinner.com/Gaea3),
  [official monolith image](https://cdn.gaea.app/web2026/img/gaea3/gi2.webp).
- **World Creator:** the reviewed debris image connects exposed rock, channels,
  angular fragments and quieter lower slopes. Its sand workflow also supports
  masked accumulation and an associated material layer. That suggests shared
  distribution controls for our existing geometry, cover and scatter, rather
  than independent noise for each. [Showcase](https://www.world-creator.com/en/index.phtml),
  [official debris image](https://world-creator.b-cdn.net/assets/img/index/World-Creator-0015.webp),
  [sand workflow](https://docs.world-creator.com/walkthrough/run-simulations/add-sand).

These are visual/engineering interpretations, not claims that MegaMek needs
either product's architecture. Their presentation renderers and offline terrain
tools are not a real-time performance target. Gaea's live page failed certificate
validation during this review; its indexed official content, documentation and
accessible official imagery were used without bypassing that failure.

ImageGen references and exact prompts are kept in
[mm-data/tools/terrain-realism](../../mm-data/tools/terrain-realism/README.md).
Generated paintovers are direction studies, never screenshots proving an
implementation. The first canyon concept exaggerates height; the sand paintover
also turns some gentle banks into cliffs. Keep their material hierarchy and
weathering, not those geometry changes. Review every generated asset for scale,
repeat, baked lighting and invented features before using it.

## Audit of the current renderer

| Area | Existing foundation | Observed gap and smallest useful change |
| --- | --- | --- |
| Landform | `BoardRelief` already has canonical corners, buttresses, joints, bedding, caprock and talus. | Repeated smooth rims and similarly sized lobes make whole cliff chains look extruded. Tune coherent broad masses inside this profile before adding vertices. |
| Plateaus | Ordinary natural tops are flat and use six triangles. | Top/face contacts can use different effective rim distances. Correct the shared contact; use material exposure for small surface detail. Keep the playable top and center height. |
| Stone | Shared array textures, world projections, normal/AO maps and material LOD exist. | The former sandstone crack lattice read as brickwork. Replace that source with irregular stratified rock; preserve metres-per-repeat and aligned maps. The granite family also needs broader recognizable planes at normal viewing distance. |
| Sand/snow cover | `naturalMaterialFor` already blends ground, mantle, rubble and rock together. | Too many continuous pale caps and repeated foot bands. Break exposed shoulders and deposits into coherent patches, keeping each material's height, color and normal weights together. |
| Grass banks | Native one/two-level slopes, soil maps and turf shoulders exist. | Their bands are still conspicuously uniform in overview. Add variation to existing coverage and edge shape, without replacing slopes with cliffs or shaving roads flat. |
| Fallen blocks | `BoardRelief.rocks`, route clearance, support and the reusable rock kit already exist. | Placement is mostly independent of the clefts that should shed the blocks. Reuse the existing cleft signal to group a bounded number of blocks near its outlets. |
| Prominent formations | Tall isolated hexes already form pillars; authored rough and outcrop variants have LOD. | Stronger regional silhouettes need deliberate selection and placement. Make existing tall/rough terrain memorable instead of putting new giant obstructions on clear hexes. |
| Material boundaries | `BoardSurfaceBlend` and `GpuSurfaceBlend` already handle same-family roles, cross-family boundaries and authored gradients. | Judge grass–soil, snow–grass, sand–grass and rocky contacts in actual renders. Refine those weights; do not introduce another splat system. |
| Vegetation | Instanced grass and authored tree/rock LOD systems exist. | Close views reveal polygonal cactus/conifer silhouettes and repeated crowns. Terrain alone cannot make these scenes photorealistic. Improve the existing assets and density hierarchy after the landform/material pass. |
| Lighting | Shared atmosphere, weather, shadows, contact shading and normal response exist. | Elevation color grading noticeably bleaches high plateaus. Calibrate material/light response together at matched exposure, rather than painting lighting into textures. Preserve tactical readability. |
| Performance | Chunk workers, camera-projected `TerrainLod`, batched material arrays and shader detail fading exist. | Measure whole-scene cost and allocations. More detailed textures do not justify a new material or draw call per hex. |

The current dependencies are sufficient. No new terrain framework, node graph,
heightmap authority, biome registry or runtime hydraulic simulation is needed.

## Non-negotiable behavior

- Board terrain, elevation, exits, visibility, movement and LOS remain owned by
  the game. Cosmetic relief must not silently create a new gameplay obstacle.
- Drawing, picking and unit support share `BoardSurface`. Use the installed
  finished geometry for placement; do not maintain a second cosmetic heightfield.
- Reuse canonical edge/corner samples and absolute cliff rows. Preserve seams at
  mixed elevations, mixed LOD, negative levels, road cuts, shores and map edges.
- Ordinary ground remains six triangles. Put pores, grains, small cracks and
  wear in material maps. Spend triangles on visible silhouette or support only.
- Concrete remains constructed, with geometric tops and retaining faces. Roads
  keep their width, native grades, roundabouts and tunnel/bridge clearances.
- Keep level one/two transitions as native slopes where the board allows them.
  Prominent features must not be created by converting all small banks to cliffs.
- Reuse existing resource ownership and draw batches. No per-hex textures,
  materials, simulation state or unbounded scatter attempts.

## Implementation sequence

The first material pass and second landform pass are implemented and compared in the native renderer.
The remaining work is ordered by its visible effect, not by adding more systems.

| Stage | Current result | Remaining acceptance work |
| --- | --- | --- |
| Material/contact foundation | New stone, sand, meadow and scree sources, aligned baked maps, corrected rim distance, broken sand/snow shoulders. | Larger contact review across mixed biomes and unusual elevation junctions. |
| Shape and debris | Directional joints connect ribs to broken rims; native slope shoulders and talus heights vary spatially; bounded fallen-block groups follow actual clefts. | Broader composition review on long cliff chains and mixed terrain. |
| Landmarks | Existing isolated pillars and buttresses benefit from connected ribs and rim breaks. | Deliberate connected fins/column groups on suitable rough/elevated terrain; no new landmark assets yet. |
| Material families | Shared stone, snow/sand exposure and elevation colors improved; authored gradients retained. | Volcanic/lunar-specific art direction and broader wet/dry surface review. |
| Vegetation and light | Gentler height tint; original fuller grass proportions restored at the user's request, retaining existing density and LOD. | Tree/cactus/conifer silhouettes and snow-covered foliage remain conspicuously stylized. |

### 1. Establish a repeatable material/contact slice

Use `GpuTerrainShowcaseSmokeTest` for identical sand, grass and snow boards, with
textured and clay views. Keep layout, camera, light and grid setting fixed.
Save unmodified captures before judging any generated concept.

Replace the sandstone and granite sources and bake them through `prepare_terrain_contact.py`
into the existing albedo/height and normal/AO pair. Preserve world scale and
the material-array layout. Correct the plateau's contact distance at the actual
sculpted rim, then expose interrupted rock patches through sand/snow there.
Do not change the macrogeometry to hide a material problem.

Acceptance: the stone no longer reads as a tiled masonry wall; the plateau and
face meet without a painted seam; sand/snow still dominate open flats; normal
maps respond to light near the camera and fade correctly at distance. Compare
both tactical and oblique views. Inspect every material family affected by a
shared helper, including concrete and volcanic terrain.

### 2. Connect shape, weathering and debris

Keep `BoardRelief.profile` as the one geometry source. The first implementation
samples the shaped face on the canonical full-detail cliff grid, using the
recess relative to neighboring columns to locate an outlet. This includes native
rounding and road cuts without duplicating their calculations. Bias the current
finite rock candidates toward that outlet instead of multiplying their count.
Retain smaller pieces away from the main deposit and leave clear intervals
between deposits. Keep the existing clearance checks and LOD ownership. The
outlet search is bounded and uses the same grid at each dressing LOD; it adds no
board-sized state. Submerged, lunar and constructed faces retain their existing
scatter. A later material review must also check mixed-family fallen blocks,
whose rendering currently belongs to the receiving tile's material family.

The second pass stretches the existing joint field along one oblique world-space
strike on tall transitions. Some of that same mass continues through the height
of the face; its joints interrupt the cap and feed the toe. The previous two
cell samples are reused, rather than adding noise octaves or another field cache.
Quiet stretches retain solid shelves. Small slopes and the no-transition mode
keep their previous joint coordinates.

The native transition band now samples a broad shoulder field, including at its
canonical corners. Two-level grass banks retain their shallow bench but vary its
strength instead of repeating an almost level ledge. The band's height profile
remains monotone and keeps its exact rim and toe endpoints. One shared
`talusHeight` calculation drives the band, its rocky relief and the height range
of fallen-block placements. It varies the apron without widening its footprint.
Sampling, corner blending, road carving, displacement bounds and game heights
remain shared with the existing renderer.

Acceptance: deposits correspond to breaks in the face; neighboring hexes read
as one cliff system; a large cliff has both broad quiet planes and deep recesses;
small slopes remain slopes. Watertightness, picking, support and road-clearance
tests must pass before the next visual stage.

### 3. Make landmarks from the board's real features

Use existing elevated/rough cells and cliff topology to identify opportunities:
isolated tall hexes for columns, convex cliff ends for buttresses, narrow ridges
for fins and concave feet for talus fans. Preserve their legal footprint and
center/support constraints. Connected cliffs share regional bedding and strike;
individual hexes should not roll unrelated geological styles.

Start with the reusable rock/outcrop assets and existing placement paths. Only
author an additional GLB when the current kit cannot produce a materially
different silhouette. Keep its editable source, bounds, material role and LOD
beside the runtime asset. A distant LOD must retain the landmark's outline;
small chips may disappear. Do not blindly remove a whole pillar at a LOD boundary.

Acceptance: a board has recognizable focal formations without a forest of random
towers. The same board/seed produces the same landscape; edits invalidate the
existing affected chunks. Roads, bridges, tunnel entrances and unit anchors remain
usable. Review typical maps as well as the attractive synthetic showcase.

### 4. Finish the material families and transitions

| Surface | Physical cues to retain | Implementation boundary |
| --- | --- | --- |
| Temperate rock/grass | Rock planes and fractures, soil in recesses, grass rooted in soil, sparse moss stains. | Existing rock/mantle/ground roles; grass pigment and roots must follow the same broad cover. |
| Desert | Stratification, broken caprock, exposed wind-scoured shoulders, sand in sheltered pockets. | Existing sandstone/sand roles; keep vertical strata upright when breaking repeats. |
| Snow/ice | Scoured rock ribs, snow on upward ledges and in hollows, exposed side facets on blocks. | Existing snow roles and dedicated ice renderer; do not reveal undiscovered black ice. |
| Dirt/gravel | Compaction, grains, sparse ruts and local wet depressions. | Existing road/ground material response; road geometry and wheel-track spacing are already separate concerns. |
| Volcanic/lunar | Cooling fractures, irregular rock plates and terrain-specific deposits. | Preserve the volcanic shader and zero-gravity behavior; never add gravity-driven snow/talus there by accident. |
| Concrete | Cast planes, joints, clean silhouette and correct side projection. | `BoardConcrete`, not natural weathered cliff geometry. |

Review same-family ground–mantle–stone contacts as carefully as cross-biome
contacts. Snow should accumulate over soil/grass, sand should form deposits rather
than recolor turf, and grass should establish irregular tufts at a sandy boundary.
Preserve long authored `ground_fluff` gradients. Avoid adding branches for each
ordered biome pair when the existing shared role calculation can express it.

Color, normal, height and cavity must agree spatially. The current generated
normal/height maps are artistic estimates from the color source, not measured
PBR scans. Correct baked lighting and wrong texture scale before raising map
resolution. Add packed roughness data only where existing material response
cannot produce a visible required effect; first verify that the lighting path
actually consumes it. No displacement texture should change legal support.

### 5. Calibrate vegetation and light as part of the landscape

Use matched midday, low-sun and overcast captures. Check that relief reads from
normals/shadows rather than baked black cracks. Revisit excessive elevation
bleaching with terrain and rooted vegetation together. Keep the shared lighting
path; do not special-case a showcase camera.

The first pass reduces the existing height tint from 14/-18 to 4/-6 percentage
points per effective level, with gentler saturation/contrast. The same helper
continues to color the bank turf. Midday and morning drafts were compared with
fixed cameras. Grass retains its shared maximum size and root/density contract,
and its original blade height/width distribution. The shorter, narrower blade
experiment made the meadow visibly sparse despite keeping its root count, and
was reverted at the user's request after the second pass. Lowering the shared
maximum also shrank shrubs and admitted more of them; that separate experiment
was rejected after native draw/vertex counts exposed its cost. First- and
second-pass comparison images below precede the full-coverage restoration.

Improve the silhouettes of the most visible existing tree families, their LOD
transitions and ground contact. Clustering should follow the current biome/soil
field while preserving the game's woods density. Broad areas of quiet ground
are necessary for both visual composition and runtime cost.

Acceptance: the terrain looks consistent under different lighting, vegetation
does not look like pasted geometric props, and materials retain their identity
at normal playing distance. A good close-up alone does not pass this stage.

## Budgets and verification gates

Budgets here are acceptance rules, not claims of measured speedups:

- Material/contact-only work: no extra geometry, material groups or draw calls
  for the same scene and camera. Reuse the two 512² sculpt layers per material.
- Landform work: use existing samples first. Any increase in vertices must show
  a silhouette/support defect that cannot be solved with those samples or maps.
- Scatter: keep bounded candidates, shared meshes/materials and current LOD.
  Measure accepted placements and submitted geometry; do not infer cost from
  triangle count of a single asset.
- Test a dense 200×200 board under the existing 512 MB test heap before approving
  changes that add board-sized state or increase per-tile allocation. A small
  showcase does not establish large-board memory safety.
- Record warm repeated CPU/GPU frame times, build/edit latency, heap/VRAM and
  per-pass submissions when making a performance claim. First-run build timing
  includes cache/initialization effects and is not a benchmark.
- Keep GL's 16-sampler portability check. Run material-LOD near/far comparisons
  when changing normal/height reads, filtering or layer selection.

Use the existing `BoardSculptTest`, `BoardReliefTest`,
`BoardSurfaceContactTest`, road/slope/tunnel regressions and mixed-LOD checks for
shared geometry changes. Use `GpuTerrainContactSmokeTest`,
`GpuMaterialLodSmokeTest` and `GpuSamplerBudgetSmokeTest` for shared shader changes.
Add a test only for a newly identified behavioral gap.

Visual regression set: this showcase, Short Canyon, `16x17 Mines 1`,
`16x17 Lava Tubes 1`, `Strassengitter 3`, a concrete city, snow/grass and sand/grass
contacts, six-exit ramps/bridges, and a large board. Check holes, floating dressing,
terrain penetration, repeating patterns, transition rings and distant shimmering.

## Native validation and measured cost

Local native captures: RTX 4070 Laptop GPU, NVIDIA 610.88, 1440×1080,
showcase hour 13, clear sky. Paths are local build artifacts:
`build/terrain-realism/before/terrain-showcase` and
`build/terrain-realism/iteration-7/terrain-showcase`. Selected before/after images
and the reports are retained in
[mm-data/tools/terrain-realism/review](../../mm-data/tools/terrain-realism/review/README.md).

| Family / oblique view | Draw calls before → after | Submitted vertices before → after |
| --- | ---: | ---: |
| Sand / textured | 47 → 47 | 1,381,602 → 1,380,810 |
| Sand / clay | 25 → 25 | 699,939 → 699,543 |
| Grass / textured | 225 → 225 | 672,105 → 667,185 |
| Grass / clay | 204 → 204 | 355,410 → 352,830 |
| Snow / textured | 44 → 44 | 369,510 → 371,070 |
| Snow / clay | 24 → 24 | 193,893 → 194,673 |

These are complete review-frame submissions, including shadows and other passes;
they are **not unique terrain triangles**. The material-only pass had identical
counts in all twelve compared views. The subsequent profile/scatter pass keeps
the same draw counts and subdivision rules; accepted dressing placements vary,
so submitted vertices vary slightly, including a small snow increase. These
figures do not prove better FPS. Build times varied and are not used as a speed
claim. No new large-board heap or cross-GPU performance claim is made.

The rim regression first failed with a roughly five-metre contact error at a
shared top/cliff vertex, then passed after using the emitted rim. Geometry checks
cover seam closure across LODs, level anchors, outward walls, picking, slope room,
rocks, native road ramps, tunnel clearance and wet cliffs. One broader rock
experiment exceeded the existing transition-room limit; its projection was
reduced before acceptance.

Native material-LOD, material-contact and sampler-budget checks passed. Shipped
board captures include Short Canyon, Mines 1, Lava Tubes 1 and Strassengitter 3;
grass fog and authored ground gradients have dedicated native checks. The final
maps were also captured at hours 9 and 13 under full cloud cover, and on Mines 1,
Lava Tubes 1, Strassengitter 3, Maze A and Thunder Rift. Lunar has
independent maps/geology: its regression now verifies that changing terrestrial
rock leaves lunar unchanged, rather than requiring both independent families to
remain visually identical forever. Contact bakes reproduce every pixel and
manifest entry with `prepare_terrain_contact.py --check`.

Unverified beyond those checks: arbitrary board combinations, a full tree-asset
overhaul, the final landmark distribution and the
overall photorealism target. Generated concepts do not close these gaps.

## Second-pass validation

The shape pass is compared against the completed first pass, not the original
renderer. [Native comparisons and reports](../../mm-data/tools/terrain-realism/review/pass-2/README.md)
retain matched textured/clay views and representative boards. The runtime change
is confined to `BoardRelief`; no new mesh rows/columns, textures, assets, material
groups, state caches or scatter candidates were introduced.

| Family / oblique view | Draw calls first → second pass | Submitted vertices first → second pass |
| --- | ---: | ---: |
| Sand / textured | 47 → 47 | 1,380,810 → 1,390,554 |
| Sand / clay | 25 → 25 | 699,543 → 704,415 |
| Grass / textured | 225 → 225 | 667,185 → 660,177 |
| Grass / clay | 204 → 204 | 352,830 → 347,238 |
| Snow / textured | 44 → 44 | 371,070 → 372,126 |
| Snow / clay | 24 → 24 | 194,673 → 195,201 |

All twelve comparable views keep their draw counts. Submitted vertices vary
between -1.58% and +1.09%, because changed relief admits different rock placements.
As above, these are complete frames including shadows, not unique triangles or
evidence of an FPS gain. The flat-top regression still measures six triangles;
existing cliff and LOD sampling budgets are unchanged.

201 geometry cases and four native cases passed (repeated classes counted once,
parameterized invocations separately). Checks cover seam closure at mixed LOD,
level anchors, support/picking, material contacts, natural transition room,
concrete, rock bounds, tunnel clearance, road ramps/banks and water slopes.
The final native set includes sand, grass and snow, plus Mines 1, Short Canyon,
Lava Tubes 1 and Strassengitter 3. No shader or material-LOD changes were made in
this pass. Two preliminary profiles exceeded the existing rim-room test; their
rim displacement was reduced rather than weakening that test.

The pass improves existing pillars and cliff ribs, but does not complete the
planned outcrop/fin-group placement or vegetation overhaul. Broad hex-derived
composition and flat plateau interiors remain apparent. No new large-board
memory or cross-GPU performance claim is made.

## Deliberately deferred

Full heightfield replacement, runtime hydraulic/thermal erosion, voxels, dynamic
tessellation, a new terrain editor, a new asset registry, virtual texturing,
path tracing and a global geology simulation. Reconsider one only after a
specific measured limitation defeats the existing implementation. The immediate
work is better materials, coherent use of the current relief and careful art
direction, all verified in the actual renderer.
