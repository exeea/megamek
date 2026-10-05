# Terrain geometry and materials

[Docs index](README.md) · [Class responsibilities](gpu-code-map.md)

The native board separates game terrain, finished geometry and shading. The game
provides levels/types/exits; CPU builders derive a shared surface; shaders add
material detail. Geometry changes affect picking and support, while texture relief
changes only appearance.

## What does what

| Code | Responsibility |
| --- | --- |
| `BoardGeometry` | Board/world conversion, common surface queries, bounds and picking integration. |
| `BoardSurface` | Finished topography: plateau, road approaches, water bank/bed and surface-role data. |
| `BoardRelief` | Canonical corners, stepped profiles, cliff rows, transition bands, shore shapes and rock placement. |
| `BoardConcrete` | Constructed slab outlines, retaining walls and joins. |
| `BoardRocks / BoardShape` | Reusable rock CPU meshes; placement stays with the feature/relief builders. |
| `BoardRough` | Authored rough variants whose drawing, picking and support use the same placement. |
| `GpuTerrain` | Worker preparation, installed chunks, resource ownership and rendering. |
| `TerrainLod` | Projected-size render sampling; board dimensions do not select quality. |
| `terrain-detail.glsl` | Projected-size material detail, independent of cached mesh LOD. |
| `GpuAssets / terrain-sculpt.frag` | Material resources and the main geological surface program. |

## Canonical geometry

Anything adjacent meshes share must be derived from the same board data and world
position. Corners use an integer lattice; all three adjoining hexes compute the same
corner. The higher hex owns a cliff and its foot coincides with the lower boundary.
Rows at fixed absolute heights let cliffs of different spans meet without cracks.

`BoardRelief` combines an inset face, broad buttresses, bedding/joints, a caprock rim
and a talus foot. Its corner fillets and transition bands must preserve a usable top
and shared edge samples. Decorative displacement remains bounded; picking bounds must
include the permitted horizontal overhang and vertical headroom.

Tall natural transitions use elongated joints along a shared oblique strike.
The same joints connect vertical ribs, breaks in the caprock and deposits at the
foot. Smaller slopes keep their soil mantle and quieter jointing. Fallen-block
groups sample recesses in the actual shaped face; they reuse
the existing candidate count, assets and clearance checks. The sampling grid stays
fixed across dressing LODs. No additional terrain subdivisions are introduced.
The transition band and rock relief share the talus-height field; fallen blocks
use it too, so the deposit does not repeat a uniform horizontal band.

Grass/dirt and snow use soil/cover mantles on small steps, exposing rock on taller
faces. Desert, Mars and rock retain steeper geological profiles. Concrete stays constructed:
up to two levels it is a cast wall; taller differences expose bedrock beneath the top
slab. `BoardConcrete` also owns the fitted corners used by adjacent material contacts.

Dry grassy two-level transitions have two earthen faces separated by a shallow shoulder
at the intermediate level. `BoardRelief.band` shapes it within the existing transition
width and preserves the rim, foot and game elevations. Canonical corner evaluation,
surface support and grass planting see the same shoulder. Its strength varies in
world space, while the height profile stays monotone. Single-level slopes, water
contacts and explicitly marked cliff faces retain their own profiles. The grass material
uses the existing rim/foot heights and world-space variation to add an irregular turf
lip and patches on the shoulder, including overhead views with grass blades hidden.
At grass detail scales, fitted cutout crowns add the hanging fringe; see
[grass rendering](gpu-grass-rendering.md). Their pigment follows the terrain's material
weights and shared elevation grade, rather than the generated atlas's original color.
The ImageGen reference and prompt are saved as `grass-bank-concept.png` and
`grass-bank-concept-prompt.json` in `mm-data/tools/terrain-contact-sources`.

Poured tops use six triangles and a rectangular cast wall panel uses two. A road cut,
water contact or junction can require additional boundary vertices; bedrock beneath a
tall slab remains a separate shaped surface. Higher natural terrain must not spread
its material across lower concrete or subdivide the slab for that blend.

Natural tops stay exactly at their board level. An ordinary flat hex, including map
edges, uses six triangles at every detail level. Slopes use six columns, cliffs twelve,
with six absolute-height rows per level. Concave road cuts retain twelve slope boundary
samples and twenty-four cliff samples on both sides of the join. Their planar tops keep
those boundary vertices; extra top bands are reserved for deeply notched outlines.
Mixed-material tops subdivide only where sampled blend weights differ from linear
interpolation by more than 4%, down to the existing detail-dependent spacing limit.
Homogeneous tops remain six triangles in the submitted mesh.
Natural material contacts share a world-space cover field with grass placement.
Their nominal half-width is seven metres, with bounded irregular edges. The shader
combines that broad gradient with texture-height contacts, softening transitions
between themes while keeping pure tile centres and visible material detail.
Material detail belongs in the fragment shader: world-space wear exposes soil and
rock on low-poly slopes without relying on small changes in mesh normals.
Ground vertices shared with a sculpted cliff foot use zero foot distance, so debris
coverage continues across that contact despite the displaced outline.
Plateau vertices at the sculpted rim likewise use zero rim distance. Measuring those
vertices against the approximate lattice segment instead could move the cover contact
several metres away. Sand and snow now expose irregular rock shoulders across that
shared crest through the existing material roles; open flats retain their cover.
Corner relief fades over the tangent space left by both transition bands and rounded
corners. Using the original edge length let adjacent columns reverse into a hanging
strip in Mines 1; the corrected fade keeps the shared endpoints and triangle count.

Natural hex transitions reserve room on each side of a step for the slope or cliff
and its foot. Roads, buildings and special artwork have additional boundary constraints.
An explicit `cliff_top` exit on the higher hex selects the cliff profile and rock
material even for a one- or two-level drop. Only the marked directions change;
the real rim and foot elevations stay fixed. The captured exit mask participates
in terrain, support and picking cache invalidation, including neighbouring shores.
Hex padding uses the same step-room machinery; it does not enlarge the lattice.
When altering these controls, follow `BoardRelief.stepRoom`, corner/edge sampling,
surface construction and bounds together.

## Shores, ramps and support

Water geometry also participates in canonical corners and cliffs. `BoardSurface`
owns the bank/bed and water surface; `BoardRelief.bank` connects the shoreline to the
shared terrain boundary. Water-to-water drops keep the seam needed by the falling
surface. A land-to-bed difference includes the water depth when deciding whether the
bank behaves like a small slope or a taller cliff.

Open-water banks and beds use one sampled material field across every natural
sector. A water hex's authored ground mixture must not consume its bank faces in
the ordinary land-material pass. Adjacent water hexes at the same surface level
blend their bank/bed fields at their shared edges, avoiding triangular underwater
wedges. Bank coverage references the water column's surface elevation, so increasing
the water depth cannot discard the neighboring land cover. Higher banks and cliffs
continue their land material below the waterline. Protected road, concrete
and frozen banks retain their existing boundaries; geometry and picking are unchanged.

Road approaches change the finished surface, not just its texture. The rest of a
roadside cliff keeps the native relief. `BoardRampMesh` can simplify interior samples,
but must preserve shared mouth/edge vertices and the actual grade. Outline detection
accounts for coordinate float precision: otherwise far-map road vertices can be
mistaken for interior ones and moved away from their cliff (MesaCity1 N, near 5634). See
[roads](gpu-roads.md) and [water](gpu-water.md) for their connection rules.

Trees, rocks, vegetation and units must sample the installed finished surface.
Dressing such as a tree pit can be drawn/picked with the ground without raising the
support height. Field stones, shrubs and tree-pit kerbs seat against TOP faces, so other
dressing cannot raise them. Small field stones use eight triangles and
[small shrubs](gpu-scatter-bushes.md) use open branches and folded leaves;
both appear only at full detail. Keep support roles distinct when adding a
decorative layer.

Cosmetic scatter clears the selected authored footprints of buildings, tanks and solid
props on its own and adjacent hexes, including the scatter's full radius. Projected
triangles retain courtyards and gaps; offset props use conservative vertical bounds
without building a second terrain surface. Reloading assets clears the CPU footprint
cache. Positive-depth water and volcanic liquids reject decorative scatter before
loading its kits. Depth-zero shallows permit only visible placements above the water;
the board's explicit Rough terrain remains separate from this cosmetic rule.

## Material roles and textures

Each natural family combines ground cover, soil/mantle, exposed rock and deposits.
`GpuTerrain.SCULPT_MATERIALS` selects each family's maps; `GpuAssets` loads and
owns the shared textures. Active sculpt maps are under
`mm-data/data/models/board/textures/sculpt`. Every sculpt map is a layer of one
texture array (`GpuAssets.sculptArray`, bound as `u_terrainLayers`): colour/height at
a map's layer and normal/AO at the next. A sculpt material's `SculptLayers` gives
its family's ground, debris, wall and mantle layers (`u_sculptLayers`), and boundary
palettes give theirs per family, so all of them take one texture unit (see
[shaders](gpu-shaders.md#boundaries-to-preserve) for the 16-unit budget). The arrays set
their own anisotropic filtering: libGDX's `setAnisotropicFilter` only addresses
`GL_TEXTURE_2D`, which left them blurred at grazing angles.

Sculpt albedo textures store height in alpha; their tangent-normal partners store
ambient occlusion in alpha. Repeat scales come from the asset metadata.
LUNAR has independent `lunar`, `lunar-scree` and `lunar-cliff` map pairs and a
`terrain/lunar.png` fallback. These began as copies of the original ROCK maps and
geology. Terrestrial rock now has broader masses and a new granite contact source;
lunar now has authored neutral regolith, angular scree and fractured bedrock in
its existing slots, with its separate profile. Editing either family must
leave the other alone.
Their existing 512-square map pairs span 8, 4 and 12 metres respectively; the
contact baker estimates 2.5, 8 and 12 centimetres of shading relief. Sources and
exact ImageGen prompts are retained in `mm-data/tools/terrain-contact-sources`.
World-space projections keep coordinates continuous across hexes. Two differently
oriented ground samples reduce repetition; walls use compatible vertical projections.
Bare dirt, the soil mantle beneath grass/dirt and natural stone now share one
vertical sampler. They blend translated source windows selected by a continuous world field,
preserving wind/bedding direction and feature size without a regular per-hex motif.
The former earth-only two-scale bypass was removed. The fungal materials retain
their four overlapping translated windows, varying in both horizontal and vertical
directions. Enlarging a ground sample by 2.37 also divides its normal gradient by
2.37, rather than exaggerating the same relief over a longer distance.
Slope and height-aware blending determine how cover gives way to mantle, rock and scree.

The dirt and `soil-contact` pairs are baked from ImageGen sources in
`mm-data/tools/terrain-contact-sources`; `dirt-prompts.json` records the original
prompts and `bank-soil-prompt.json` records the replacement eroded bank material.
Run `python tools/prepare_terrain_contact.py --only dirt soil` in mm-data after any
procedural material rebuild; `--check` verifies pixels and source metadata. The shared
baker makes opposite edges periodic, flattens broad baked lighting, and derives aligned
normal/AO and height channels. This relief is an artistic luminance estimate, not a
measured scan. Dirt repeats at 5 m, soil at 4 m; no additional runtime maps are needed.
The bank source represents cohesive eroded subsoil instead of a carpet of loose gravel;
its estimated relief spans 9.5 cm, with aligned normal and cavity channels.
Updating dirt alone does not update exposed grass banks, which use `soil-contact`.

The same baker supplies the sandstone and `granite-contact` pairs from
`sandstone.png` and `granite-bedrock.png` in that source directory, with exact prompts
beside them. Sandstone repeats at 12 m with estimated relief of 16 cm; granite at 8 m
with 8 cm. Their broader rock planes and discontinuous fractures replace the former
brick-like sandstone and densely mottled granite. Run
`python tools/prepare_terrain_contact.py --only sandstone granite-bedrock`, then the
same command with `--check`. The older `granite.png` source remains available for
comparison; `granite-bedrock` now bakes the existing `granite-contact` runtime slot.
See the [realism plan](gpu-terrain-realism.md) for references and visual acceptance.

The authored `sand-ground`, `meadow-ground` and `granite-scree` sources also bake
through that tool, into the existing `sand`, `grass` and `scree` slots. Their
repeats remain 6, 4 and 4 metres; estimated relief is 2.5, 2.5 and 10 cm. They
replace the former procedural surface patterns without extra maps, layers or
mesh detail. Lunar retains its own `lunar-scree` map. Run the baker without
`--only` to restore all authored materials after a procedural rebuild.

`terrain-materials.glsl` owns that common evaluation.
`terrain-projection.glsl` owns projection helpers, and `terrain-concrete.glsl` handles
constructed slab appearance. The same role weights must affect color, normal, cavity
and roughness together. Cross-family contacts are described in
[terrain contacts](gpu-terrain-contacts.md).

For a change in silhouette or support, edit CPU geometry. For grain, wet response,
material breakup or per-level color grade, edit the relevant material helper and its
maps. Editing a normal map will not correct a geometric seam.

### Physical texture scale

`BoardRelief.metres` defines 30 metres across a hex. Sculpt UVs are world positions
divided by the repeat in the asset manifest; slopes blend ground and vertical
projections at the same physical scale. No UV origin or fixed repetition count is
assigned per hex. A material can span several source windows within a hex without
restarting at its edges. Window blending breaks up recognisable repetitions.

Natural cliff sampling also advances its translated source selection with projected
height. The horizontal ground field alone stays constant down a vertical face and
repeats identical patches on tall walls such as Thunder Rift's basalt. Color/height
and normal/AO use the same height-dependent offsets, keeping the source's metre scale
and upright grain. This reuses the existing two reads per projection, material LOD,
texture array and geometry; flat ground, constructed concrete and flowing lava retain
their own sampling.

| Material | Base source span in metres |
| --- | --- |
| Grass / dirt / desert hardpan / Mars hardpan | 4 / 5 / 6 / 6 |
| Loose SAND / rock / snow / lunar / concrete tops | 12 / 8 / 8 / 8 / 6 |
| Soil banks / scree / gravel | 4 / 4 / 3 |
| Granite / sandstone / Martian / lunar cliff maps | 8 / 12 / 10 / 12 |
| Cast concrete wall map | 8; panel joints have their own constructed dimensions |
| Fungal ground / mat / cliff / fibrous slope | 22 / 8 / 12 / 10 |
| Volcanic ash ground / basalt cliff | 6 / 12 |
| Wetland earth | 9; the shared earth map sampled at 45% of its 20 m source span |
| Road and bridge deck maps | 6 for the ordinary repeat of one legacy detail unit |
| Ice / solid volcanic crust / flowing lava | 24 / 12 / 24 |

Fungal material roles and their mushroom effects are described in [fungal terrain](gpu-fungus.md).

The legacy `detailMetres` helper uses a different unit: one is six physical metres.
Road and bridge deck projections agree in that convention; new geological materials
use physical metres. The ground sampler's second source window spans 2.37 times its
base repeat. Ice blends complete samples at its original and turned coordinates for
colour, surface properties and normals; its normal gradients follow the rotation,
scale and wind stretch. Solid crust bends its domain within a few repeats to reduce
the visible plate grid while retaining one material sample and its matching Jacobian.
The solid magma source uses varied broken crust, quiet cold faces and sparse narrow
fissures instead of a few repeated large slabs. Its four aligned maps are baked by
`mm-data/tools/build_magma_materials.py`.

`GpuSurfaceScaleSmokeTest` captures 14 materials at identical overhead, oblique and
close scales, including one-level slopes and three-level cliffs. These are visual
review images, not an automated assertion of realism. `GpuSurfaceProjectionSmokeTest`
compares GPU normals with independently differentiated physical height and checks
the CPU/GPU sand exposure field. The scale audit also renders road ramps, retaining
walls, wet/dry road materials and material LOD. Native sampler-budget and ice checks
pass on the tested machine; no frame-time or cross-platform performance claim follows.

## Lighting, grading and inspection

`BoardAtmosphere` supplies linear light. Lit shaders decode albedo, apply the common
sky/direct/ground-bounce model and encode display color once.
`light-model.glsl` and `surface-lighting.glsl` are shared by terrain and other lit
surfaces; introducing a terrain-only light multiplier would make those surfaces disagree.

Per-level grading belongs to the terrain material path and changes continuously down
cliffs. Final exposure and highlight treatment belong to `GpuAtmosphere`'s composite.
Rain response uses the shared surface helpers; snow retains its separate handling.

The clay view in `GpuTerrain.setClay` helps isolate geometry/light from materials.
The normal/relief toggle controls material detail. `GpuBoardTuning` and
`TerrainSettings` carry geometry settings through capture/build/install so displayed
frames do not mix results from different settings.

## Material detail at distance

`terrain-detail.glsl` measures metres per pixel from world-position derivatives before
material branching. Fine ground normals fade out between 0.4375 and 0.625 metres per
pixel (about 48 pixels across a 30-metre hex). Cliff normals survive twice as far,
fading between 0.875 and 1.25 metres per pixel. This follows apparent surface area in
both cameras, without rebuilding geometry or making mesh LOD authoritative for shading.

At zero detail the material branches skip normal/AO texture reads. Colour and height
used for terrain contact blending remain sampled, preserving slope bands, shore
materials and concrete joints. Fine cavity AO fades to 0.9; vertex/contact occlusion
and geometry shadows remain. This approximates small pores at distance rather than
turning off the terrain's broad shading.

Volcanic materials also skip their relief trace when normals fade out. Their surface
map (height, roughness, AO, relief range) fades out by 1.25 metres per pixel, using a
roughness of 0.85 and no parallax. Lava colour, heat, currents and emission remain
active at every distance. Near detail and physical shared boundaries are unchanged.

`GpuMaterialLodSmokeTest` renders sand, desert, Mars, concrete, crust and lava with altered detail
maps. Close pixels must respond, while distant pixels must be identical despite
changes to the normal/AO and volcanic surface maps. These native checks establish
the rendering behaviour, not an FPS improvement; frame-time gains need measurement
in the complete board view.

## Themes and the SAND gameplay treatment

Desert and Mars supply ground and geology. Desert uses compact ochre hardpan,
pale abraded sandstone shoulders and browner exposed faces. Mars has independent
rust-red hardpan and darker iron-rich bedrock maps; it does not darken the dirt theme.
Neither theme implies `Terrains.SAND`.

`BoardSurfaceBlend.capture` derives one shared SAND cover from that gameplay flag.
Its pale loose-sand material is the same in desert, Mars and grassland. The tile
keeps its theme's geology, vegetation selection and coexisting rough/swamp data.
Its palette retains a small share of the substrate. A continuous metre-based field
opens sparse patches through the sand, exposing the substrate's complete material:
turf, stone, earth or the theme's hardpan, including normals, cavity and wet response.
Grass roots use that same field and grow only where enough turf is exposed. Existing
rock scatter and authored Rough features remain in place. Sand stays the dominant
gameplay cue; these openings neither change the board flags nor subdivide the mesh.
Exposed cliffs below the surface deposit regain the theme's rock. Snow, pavement
and magma retain their established material precedence. Buildings block neighbouring
cover without discarding their own authored SAND; water keeps its bed treatment.
Authored `ground_fluff` gradients mix themes (including Desert and Mars), not SAND.

The shared loose-sand source has broad branching waves and finer cross-ripples.
Its 512-square color/height and normal/AO pair repeats over 12 metres. The baker's
22-centimetre relief is an artistic estimate for normals, not mesh displacement.
The shader translates source windows instead of rotating or resizing them, retaining
the dominant wind direction and wave size. Existing material LOD filters detail at
distance. Uniform flat SAND tops still use six triangles; no extra sampler or
draw pass is introduced. Only existing mixed-material boundaries need refinement.

`BoardAridSurfaceTest` covers actual artwork capture of SAND with desert, Mars,
grass/marsh, rough and buildings, the shared grass-root field, plus the flat mesh
budget at all four LODs in grass, dirt, rock, lunar, desert and Mars themes.
`GpuAridSurfaceSmokeTest` provides matching overhead, oblique and close native
views of these themes. Sources, exact ImageGen prompts and reproducible map bakes
live in `mm-data/tools/terrain-contact-sources` and `prepare_terrain_contact.py`.

## Volcano theme

`volcano` selects cool ash-grey ground and blue-grey basalt, independently of the
rust-coloured Mars theme. Explicit magma still selects its own solid crust or
flowing lava; ordinary volcanic rock does not become emissive. Shared SAND keeps
its terrain identity and wave detail, with a grey mineral tint over lunar or
volcanic substrates. The tint follows the existing cover weights at contacts.

`volcano-ground` and `volcano-basalt` use the existing sculpt array, world-space
projection, material LOD and native slope/cliff builders. Their aligned 512-square
color/height and normal/AO maps repeat over 6 and 12 metres. Estimated relief is
4.5 and 14 cm respectively; this is shading relief, not extra mesh subdivision.
Scatter reuses the basalt map in its shared atlas. No per-hex texture or draw pass
is added.

ImageGen source images and exact prompts are maintained in
`mm-data/tools/terrain-contact-sources/volcano-*.png` and `volcano-prompts.json`.
`prepare_terrain_contact.py --only volcano-ground volcano-basalt` reproduces the
runtime maps. Height and normal maps are artistic estimates derived from the
generated images, not measured photogrammetry.

The theme audit's recommendation to separate Volcano from generic rock was valid;
its claim that Mars still used generic dirt was stale. Theme variations belong in
the shared material/cover system, while SAND, ICE, pavement and magma retain their
gameplay precedence. The audit's remaining lunar-water and enclosed-road artwork
suggestions require their own material/model work and are not provided by this
Volcano change.

`BoardAridSurfaceTest` covers volcanic/lunar SAND capture and the six-triangle flat
mesh at every LOD. Native board captures cover Thunder Rift and Crystalline Canyon;
`GpuMaterialLodSmokeTest` also exercises the volcanic maps' distance filtering.

## Ultra-sublevel pits

Presence of `ULTRA_SUBLEVEL`, including `ultra_sublevel:0`, selects a pit in every
theme. Scene capture folds edges toward it into the existing `cliffTopExits` mask;
the normal cliff builder supplies the walls, including one- and two-level drops.
Pit boundaries disable the normal slope/talus transition and pin the cliff foot
to the opening, so there is no textured shelf inside the hex. The shared cliff
shader fades these walls into black with depth; their rock texture and rim still
come from the surrounding theme. Pit corners keep one shared vertical boundary;
all incident walls fade from the same cap height, including mixed-height rims.
Neither the board's elevations nor its gameplay cliff exits are modified.

The opaque black hex cap lies one visual level below the authored sublevel, so
even equal-level neighbours have an exposed rim. `Tile.groundLevel()` is the one
derived height used by the surface and its receiving cliffs. The cap replaces
ground artwork, relief and vegetation in the sculpted view, seals the sky, and
remains pickable. Tactical View keeps its existing tileset pit artwork at the
authored game elevation. Neighbouring pits at the same level join without an internal wall. Pit
edits invalidate the cap and surrounding cliff geometry.

`BoardUltraSublevelTest` checks zero-valued flags, theme independence, cap closure
and picking at all LODs, forced cliff classification and an unobstructed opening,
closed mixed-height seams and their shading, cache invalidation, live game/map-preview
edits, adjacent pits, and the three pits on Fungal Crevasse.

## Authoring and related features

The [board asset format](../../mm-data/data/models/board/README.md) owns dimensions,
mesh roles and editable source locations. Runtime loads the generated models/maps;
offline tools in mm-data author them.

Use the dedicated guides for [rough](gpu-rough.md), [fields/marsh](gpu-fields-marsh.md),
[grass](gpu-grass-rendering.md), [buildings](gpu-modular-buildings.md) and
[terrain fire/smoke](gpu-terrain-fire-smoke.md). Their builders share the surface and
resource boundaries described here.
