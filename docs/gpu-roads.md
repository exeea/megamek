# GPU road surfaces

Ordinary road hexes now use the terrain engine for their ground and a separate road strip for their road. This replaces the full-hex road artwork for supported dry terrain combinations. Road connectivity, movement costs, tile elevations and bridge deck heights remain authoritative. Rendering, picking and unit support share the same ground mesh, including its curved road ramps.

## Representation

- Normal roads have dark textured asphalt, faded centre dashes, and a narrow loose shoulder.
- Alleys use unmarked asphalt. Road materials share one carriageway width, so a connected change of material does not pinch the route.
- Dirt roads have continuous compacted soil and broad, intermittent wheel-worn swaths, including on sandy ground where a plain sand strip would disappear. Directional scuffs vary their brightness, roughness and normal detail instead of drawing two continuously dark rails.
- Gravel roads use finer aggregate without painted lanes, with restrained patches of compaction. Both unsealed kinds have a broad porous margin which exposes the surrounding ground. They have no separate pale shoulder stripe underneath that fade.
- Two exits form a bend inside the central hub, with straight approaches over the ramps. Three/four exits join into one footprint. Five/six exits form a roundabout with a smaller central island, a full-width circulating carriageway and tangent entry/exit curves. The ring is 15 units wide, matching the approach roads; road width does not change to make the island fit. Concave entrance corners are rounded before tessellation. Bridge decks use these same outlines.
- Dead ends stay full width through the centre and continue 80% of the way from the centre to the opposite edge. Pavement ends with a transverse square cut. Gravel thins into loose aggregate; dirt fades into disturbed soil and two unequal wheel marks. A ground margin remains before the next hex, so the visual end does not invent a connection. An explicit road without exits uses a short north/south stretch with two such ends.
- A straight paved route keeps its dash sequence through an unmarked side junction. Lane paint does not spill onto the side branch.
- Asphalt/dirt/gravel joins keep their width and use a shared material order on both tiles: dirt over asphalt, gravel over either. A full-width transverse construction seam separates the surfaces. On the approach to it, dirt leaves parallel tyre-carried dust, while gravel leaves scattered aggregate and fines. All orientations use the actual board dimensions, including both column parities.
- Wheel wear keeps fixed lateral offsets through bends and faded endings. Its broad swaths represent overlapping passes, not the width of one tyre. Only their visibility varies; changing material does not change axle spacing. World-anchored noise varies the loose margins and wear, including when neighbouring paths run in opposite directions.
- Natural ground around the road retains the engine's material blending. Snow and sand influence the paved shoulder; unsealed margins expose the local ground directly. The road's surface remains continuous across biome borders.
- Rough boulders and tree trunks use the same footprint for clearance. Woods keep their tree count, and grass tufts stay outside the verge.

`BoardScene.Tile.road` captures the game's road level as an immutable visual kind. `roadExits` remains the authoritative exit mask. `BoardRoad` builds the footprint and clearance query; `GpuRoads` supplies the material patches and drapes them onto the existing `BoardSurface` triangles. It reuses `BoardTacticalGeometry`'s tessellator and triangle clipper. No second height field or road simulation is introduced. Repeating material maps come from `GpuAssets`; the existing ground shader supplies lighting, shadows, normal detail, and rain response.

Road kind survives tactical-only snapshots and participates in terrain invalidation. Changing kind, exits, or nearby geometry rebuilds the road together with its surroundings. Road geometry and textures are owned by the existing chunk and asset lifecycles.

## Ramp geometry

`BoardSurface` rounds the change of grade between a flat tile centre and its road approach. A parabolic vertical curve leaves the plateau, followed by a constant grade through the shared hex edge. Both adjacent hexes agree on height and tangent there; the other half eases back onto the next plateau. A bridge approach contains both vertical curves within the road hex so it reaches the bridge deck level, with a horizontal tangent.

Each half-ramp has 24 mesh sections. A two-level road climb starts near one hex centre and finishes near the other; a one-level climb uses a shorter run. Grades never propagate into additional hexes. The centres keep their authoritative elevation, including six-way junctions with mixed-height neighbours. The carriageway width stays fixed.

The surrounding cut/fill is part of the ground mesh, with smooth lateral shoulders instead of vertical panels against the road. Shared mouth corners use the median of the three surrounding land elevations to avoid isolated cliff spikes. Excavated ground follows the envelope between a cliff's foot and rim, rather than folding sideways over each rock ledge. Wall joins retain the canonical corner profile. Asphalt, paint, tracks and shoulders drape onto the supporting triangles. Contact queries prefer a triangle that contains the point; their edge tolerance only recovers rounding gaps. Normal maps add detail, not replacement geometry. The slope is still limited by the available two-hex distance and chosen elevation scale.

## Road material maps

`mm-data/data/models/board/textures/roads/` contains dedicated asphalt, compacted dirt and crushed-gravel materials. Each has 1024px albedo and tangent normals plus packed height/roughness/cavity-AO/range. Assets use mipmaps and the existing anisotropic filtering. The road shader uses roughness for the specular response, AO for small cavities, and height for grain coverage and preferential wetting. Color, normal and roughness follow the same material coverage through transitions. The existing normal-map toggle applies to roads too.

Normal maps come from the same quantized height and relief range shipped in the surface texture. The relief is a shallow artistic estimate, not a scanned physical surface. It does not displace the terrain, change unit support, or encode road width. Markings and wheel spacing stay in the shared road geometry.

Transition vertices carry along/across coordinates in their red/green channels; alpha carries only road-edge coverage. Wheel-compaction vertices instead carry the path direction in those channels. The road shader interprets those coordinates instead of tinting the albedo. Keeping the controls separate prevents the material blend from narrowing the road. The transition's interior and its edge strip are tessellated separately so an interior triangle cannot disappear because all its vertices lie on a zero-opacity outer boundary. Dirt/gravel joins retain the same broad loose margin on both sides. Geometry supplies the shared transition reach, wheel spacing, wear width and deposit tint to the shader.

Sources, exact built-in ImageGen prompts, the transition reference, and baking instructions are in `mm-data/tools/road-sources/README.md`. `tools/prepare_road_materials.py --check` verifies deterministic outputs and normal/height alignment using the existing terrain-map helpers.

The later dirt/gravel margin studies and their exact prompts are in `mm-data/tools/road-sources/unpaved-references.md`. These visual references guide the procedural wear and edge masks; they are not stretched onto the road or loaded at runtime. The existing material maps and shared noise texture are reused.

## Preserved fallbacks and limits

Custom `ROAD_FLUFF`, unsupported road levels, submerged/frozen roads, and combinations whose other visible terrain still requires artwork keep the existing full-tile appearance. Bridge decks remain baked GLBs with shared road materials; their five/six-exit variants use the same roundabouts, with a solid raised concrete island and rails outside the carriageway. This changes presentation, not traffic rules or movement legality.

Rain uses the existing weather wetness input. Dirt darkens in uneven muddy patches and holds reflective water in shallow, nearly level basins. Wheel wear varies compaction, roughness, normals and coverage while retaining fixed track spacing. This is a material effect, not a new water simulation.

## Verification

88 targeted CPU/integration tests passed: `BoardRoadTest` (6), `BoardFeaturesTest` (9), `BoardSurfaceBlendTest` (7), `BoardSurfaceTest` (65), and `GpuRoadSourceTest` (1). Coverage includes every exit mask, all six orientations and both column parities for mixed-width joins, draping on ramps, existing bridge support/movement, terrain capture/edit/removal, fallback retention, and Rough/woods clearance.

The road-ending follow-up passed 26 focused tests: `BoardRoadTest` (9), `BoardFeaturesTest` (9), `BoardSurfaceBlendTest` (7), and `GpuRoadSourceTest` (1). The added coverage checks full-width terminal cuts in all six directions and both column parities, clearance for extended ends, fading material without opaque backing, continuing wheel marks, and isolated road tiles. The earlier 65 surface tests were not rerun for this footprint-only change.

`GpuRoadSmokeTest` checks native OpenGL rendering and compares a live road-type edit with a clean rebuild. Its captures cover top and oblique views, a close junction, wet roads, bridge approaches, and close views of all four material endings. The rendered review also caught oversized gravel and a dirt-road material discontinuity at biome borders; both were corrected. Scoped main/test Checkstyle passed.

The material-quality and transition follow-up passed 29 focused tests on 2026-09-27: `BoardRoadTest` (12), `BoardFeaturesTest` (9), `BoardSurfaceBlendTest` (7), and `GpuRoadSourceTest` (1). Added assertions cover steady carriageway width, fixed tyre spacing, consistent material coverage at shared edges, all three material pairs in all six directions, and an unchanged main-road dash sequence through an unmarked branch.

The updated native OpenGL review also passed. It checks normal-map on/off differences and wet/dry response in actual road pixels for all four road kinds, live-edit equivalence, and brighter dirt deposits at both fixed tyre offsets on the asphalt side of a dirt/asphalt join. Captures include each material pair and a top view of the dirt traces. The wet review now configures rain through the atmosphere, whose frame preparation owns wetness. Scoped main/test Checkstyle and the deterministic road-map check passed. The earlier 65 surface tests were not rerun for this road-only follow-up. This does not replace a large-board performance review or subjective review of every custom map.

The ramp follow-up passed 87 tests on 2026-09-27: `BoardRoadRampTest` (6), `BoardRoadTest` (12), `BoardSurfaceTest` (65), `UnitLandingSupportsTest` (3), and `GpuRoadSourceTest` (1). It checks actual triangle grades on uphill/downhill ramps across all six directions and both column parities, matching height and lighting tangents at shared edges, level bridge arrivals, draped road layers, picking and unit support. Native `GpuRoadSmokeTest` and scoped main/test Checkstyle passed as well. The native review includes close ramp captures for asphalt, dirt and gravel, alongside the existing road materials and bridge checks.

The unsealed-margin and wheel-wear follow-up passed 30 focused tests on 2026-09-27: `BoardRoadTest` (14), `BoardRoadRampTest` (6), `BoardFeaturesTest` (9), and `GpuRoadSourceTest` (1). New checks ensure that loose margins have no opaque or contrasting backing, dirt/gravel joins retain that margin in all six directions, and wheel wear keeps its spacing and follows the road direction. Native `GpuRoadSmokeTest` and scoped main/test Checkstyle passed. Rendered dirt/gravel strips, material joins and road endings were visually reviewed; the review caught and corrected a sharp verge remaining at dirt/gravel joins. Existing native checks also cover normal maps, wet response, asphalt dirt deposits and live-edit equivalence. The larger terrain support suite was not repeated for this surface-only change.

The review uses isolated build output through `build/road-review.init.gradle`. Tests used already-staged assets (`-x :megamek:stageDataFiles`) because the running application held a mapped font file open. Captures are under `build/road-review/megamek/gpu-board-review/roads/`. No large-board performance claim is made by this visual/geometry validation.
