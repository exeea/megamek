# Road relief and surface contacts

The terrain snapshot and `BoardRelief` own the slope/cliff shape. Road grades
only constrain the exposed rim and foot at an actual cut or fill. Unchanged
edges keep the native cliff grid; changed edges clip that same grid instead of
building a separate road wall. Corner rock coverage, soil/deposit distances,
normal calculation and terrain LOD therefore belong to the shared engine.

Material transitions need to work **within** a surface family as well as
between neighbouring families. Grassland's turf, soil, scree and exposed rock
are separate roles even when both hexes are GRASS. Adjoining two- and three-level
faces must agree on their material inputs at their shared corner. A texture
crossfade cannot repair contradictory rim/foot distances supplied by geometry.

## Contact inventory

| Family | Cover | Soil or mantle | Exposed face | Deposit | Required contacts |
| --- | --- | --- | --- | --- | --- |
| Grass | Turf | Mineral soil | Granite | Scree | All six pairs of the four roles |
| Dirt | Bare dirt | Mineral soil | Granite | Gravel | All six pairs; low banks keep soil, tall faces expose rock |
| Sand | Sand | Sandstone | Sandstone | Broken sandstone | Sand/stone, sand/debris, stone/debris |
| Rock | Weathered rock top | Granite | Granite | Scree | Top/face, top/scree, face/scree |
| Snow | Snow | Snow | Granite | Scree | Snow/rock, snow/scree, rock/scree |
| Concrete | Pavement | Cast concrete | Supporting granite | Scree | Constructed slab boundaries stay crisp; deposits and natural foundation contacts blend |
| Magma crust | Cooled crust | Existing volcanic evaluator | Volcanic rock | Existing volcanic evaluator | Crust/bank and every neighbouring natural family |
| Molten bank | Heated bank | Existing volcanic evaluator | Volcanic rock | Existing volcanic evaluator | Bank/crust and neighbouring ground; liquid remains in its separate surface pass |

The shader evaluates role weights once and uses them for colour, height,
normal and occlusion together. Soil/rock contacts vary in world space, including
down the face, before the existing height competition. They do not require a
separate transition image for each pairing or edge orientation.

Cross-family coverage includes all 64 ordered pairs of the eight render families.
Level concrete edges deliberately retain their constructed boundary. Frozen
ground and unsupported authored artwork retain their existing handling. Water
optics remain separate: underwater coverage uses sediment and rock, rather than
borrowing living turf from a dry bank. Road asphalt, dirt and gravel overlays
continue to use the existing road material joins and clearance.

## Image generation and runtime assets

The built-in image generator produced photographic mineral soil and weathered
granite sources. Unlike the former earth map, the soil has no repeated topsoil
stripe and no fixed top/bottom. Both sources and exact prompts are maintained in
`mm-data/tools/terrain-contact-sources/`.

`mm-data/tools/prepare_terrain_contact.py` reuses the existing periodic filter and
sculpt material baker. Runtime output is 512 square, with colour/height and
normal/AO packed into the established RGBA layout:

- `data/models/board/textures/sculpt/soil-contact.png` and `soil-contact-normal.png`
- `data/models/board/textures/sculpt/granite-contact.png` and `granite-contact-normal.png`

The material manifest records repeat scales, source hashes and relief estimates.
Normals are source-derived artistic estimates, not measured photogrammetry.
Original procedural assets are retained. After rebuilding procedural materials,
run `python tools/prepare_terrain_contact.py`; use `--check` to verify all pixels
and manifest entries without rewriting them.

## Rendering cost and LOD

The generated maps replace existing roles. The family array still contains 13
unique material pairs (26 RGBA layers); no extra runtime sampler or transition
draw pass is added. Texture filtering, mipmaps, candidate pruning and derivative
filtering remain in the shared evaluator. Texture coordinates are world based
at every mesh tier. Roadside cliff interiors now use the same row-stride LOD as
ordinary cliffs, retaining their shared boundary samples. These are structural
cost properties, not a measured frame-rate improvement.

## Verification

`BoardRoadSlopeTest` checks a graded road beside an unchanged cliff in all six
orientations and all four terrain tiers. `BoardSurfaceContactTest` checks every
ordered family pair, six orientations and rises of one through four levels,
plus the actual material attributes of coincident slope/cliff corner vertices
at every terrain tier. Existing road junction, cliff seam and support checks
cover the affected geometry boundaries.

`GpuSurfaceContactSmokeTest` renders all 64 ordered pairs both level and at a
four-level cliff, then road/soil/cliff junctions for all six ground families at
four zoom distances using production LOD refinement. It checks OpenGL errors,
renderer readiness and captures the results for visual inspection.

Local results and any limitations are recorded below after verification.
