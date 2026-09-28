# GPU road surfaces

Ordinary roads are material splats on the terrain's existing faces. Asphalt, gravel, dirt, markings and tyre wear do not subdivide the ground or have a separate road-shaped mesh. This replaces the full-hex road artwork for supported dry terrain combinations. Road connectivity, movement costs, tile elevations and bridge deck heights remain authoritative. Rendering, picking and unit support share the same ground shape, including its curved road ramps.

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

`BoardScene.Tile.road` captures the game's road level as an immutable visual kind. `roadExits` remains the authoritative exit mask. `BoardRoad` builds the footprint and clearance query; `GpuRoads` rasterizes material coverage into small masks and supplies whole existing `BoardSurface` triangles to carry them. The fragment shader draws the borders, dashes and tyre wear. No second height field or road simulation is introduced. Repeating material maps come from `GpuAssets`; the existing ground shader supplies lighting, shadows, normal detail, and rain response.

Masks contain coverage and transition controls at four pixels per logical board unit, cropped to each patch's bounds. They do not replace or lower the resolution of the repeating material textures. CPU workers prepare immutable masks; the render thread uploads a chunk-owned atlas. Local mask UVs are vertex data, allowing repeated roads to share a material batch. Reused geometry binds the replacement chunk's atlas before the old one is disposed. Road kind survives tactical-only snapshots and participates in terrain invalidation; changing kind, exits or nearby geometry rebuilds the affected road and ground together.

These are additional material passes over copies of the terrain faces, with a small depth offset. They introduce no road-specific tessellation, but still submit triangles and consume fill rate. This implementation does not combine every road layer into a single terrain-shader pass.

## Ramp geometry

`BoardSurface` rounds the change of grade between a flat tile centre and its road approach. A parabolic vertical curve leaves the plateau, followed by a constant grade through the shared hex edge. Both adjacent hexes agree on height and tangent there; the other half eases back onto the next plateau. A bridge approach contains both vertical curves within the road hex so it reaches the bridge deck level, with a horizontal tangent.

Flat road hexes use six terrain triangles. Ramps derive their longitudinal and bank resolution from rise and geometric error instead of the former fixed 24 sections, eight bank divisions and twelve hub rings. Bridge approaches add samples at their flat departure and arrival. `BoardRampMesh` removes redundant interior vertices, retaining shared boundaries, curved carriageways and the tile centre, and checks retained height samples before accepting a change. A two-level climb starts near one hex centre and finishes near the other; a one-level climb uses a shorter run. Grades never propagate into additional hexes. The centres keep their authoritative elevation, including six-way junctions with mixed-height neighbours. The carriageway width stays fixed.

The surrounding cut/fill is part of the ground mesh, with smooth lateral shoulders instead of vertical panels against the road. Shared mouth corners use the median of the three surrounding land elevations to avoid isolated cliff spikes. Excavated ground follows the envelope between a cliff's foot and rim, rather than folding sideways over each rock ledge. Wall joins retain the canonical corner profile. Asphalt, paint, tracks and shoulders drape onto the supporting triangles. Contact queries prefer a triangle that contains the point; their edge tolerance only recovers rounding gaps. Normal maps add detail, not replacement geometry. The slope is still limited by the available two-hex distance and chosen elevation scale.

Concrete keeps level geometric slabs outside the road corridor. Its cut/fill uses vertical retaining walls, with wall-oriented material coordinates and continuous joins to the ramp. It does not receive the natural ground's weathered bank or displaced cliff profile. All three concrete boundary modes retain this engineered ramp geometry; the default remains Everywhere, None keeps sharp hex boundaries, and isolated concrete remains hexagonal.

## Road material maps

`mm-data/data/models/board/textures/roads/` contains dedicated asphalt, compacted dirt and crushed-gravel materials. Each has 1024px albedo and tangent normals plus packed height/roughness/cavity-AO/range. Assets use mipmaps and the existing anisotropic filtering. The road shader uses roughness for the specular response, AO for small cavities, and height for grain coverage and preferential wetting. Color, normal and roughness follow the same material coverage through transitions. The existing normal-map toggle applies to roads too.

Normal maps come from the same quantized height and relief range shipped in the surface texture. The relief is a shallow artistic estimate, not a scanned physical surface. It does not displace the terrain, change unit support, or encode road width. Markings and wheel spacing come from the shared road footprint and its masks.

Transition masks carry along/across coordinates in their red/green channels; alpha carries road-edge coverage. Wheel-compaction masks instead carry the path direction in those channels. The road shader interprets those coordinates instead of tinting the albedo. Keeping the controls separate prevents the material blend from narrowing the road. Coverage is sampled per fragment, so a triangle remains visible even when its vertices fall outside the road. Dirt/gravel joins retain the same broad loose margin on both sides. The shared road constants supply transition reach, wheel spacing, wear width and deposit tint to the shader.

Sources, exact built-in ImageGen prompts, the transition reference, and baking instructions are in `mm-data/tools/road-sources/README.md`. `tools/prepare_road_materials.py --check` verifies deterministic outputs and normal/height alignment using the existing terrain-map helpers.

The later dirt/gravel margin studies and their exact prompts are in `mm-data/tools/road-sources/unpaved-references.md`. These visual references guide the procedural wear and edge masks; they are not stretched onto the road or loaded at runtime. The existing material maps and shared noise texture are reused.

## Preserved fallbacks and limits

`ROAD_FLUFF:1` selects a standard road bend and uses the native road over the tile's terrain material. Other custom `ROAD_FLUFF`, unsupported road levels, submerged/frozen roads, and combinations whose other visible terrain still requires artwork keep the existing full-tile appearance. Bridge decks remain baked GLBs with shared road materials; their five/six-exit variants use the same roundabouts, with a solid raised concrete island and rails outside the carriageway. This changes presentation, not traffic rules or movement legality.

Rain uses the existing weather wetness input. Dirt darkens in uneven muddy patches and holds reflective water in shallow, nearly level basins. Wheel wear varies compaction, roughness, normals and coverage while retaining fixed track spacing. This is a material effect, not a new water simulation.

## Verification

### Mines 1 road coverage (2026-09-28)

The reported missing sections on `Deserts/16x17 Mines 1.board` did not reproduce with the current road-mask implementation, but nine bends with `ROAD_FLUFF:1` incorrectly fell back to legacy artwork with grass baked into it. They now use the native road renderer over desert sand. The source regression verifies all 30 authored road hexes, every exit and the sand surface, including those nine bends. Live capture checks adding a standard bend decoration and retaining the fallback for unsupported custom decoration.

The native `GpuRoadSmokeTest.keepsMinesRoadsVisibleAcrossTheRealBoard` loads that same captured scene through the asynchronous terrain path, checks visible asphalt on each approach, zooms out and back through cached detail, and checks again after a nearby terrain edit and its reversal replace the mask atlas while reusing unchanged meshes. Distant views are captured for inspection; pixel assertions run at close zoom where asphalt and markings occupy separate pixels. Close top and oblique captures of 0815 confirm sand around the bend without the grass patch. This material fix does not address the separate cliff-corner gaps visible elsewhere in the captures.

28 focused CPU/integration cases (`BoardRoadTest`, `BoardFeaturesTest`, `GpuRoadSourceTest`), the native Mines OpenGL case, and scoped main/test Checkstyle passed. Captures are in `build/road-review/megamek/gpu-board-review/roads/mines-roads-*.png` and `mines-road-fluff-sand-*.png`. The full project suite was not run.

### Current road/concrete follow-up (2026-09-28)

199 targeted CPU cases passed across the geometry and final mask runs: `BoardRoadTest` (17), `BoardRoadRampTest` (30), `BoardSurfaceTest` (66), `BoardConcreteShoreTest` (18), `BoardBridgeTest` (64), `UnitLandingSupportsTest` (3), and `GpuRoadSourceTest` (1). This includes the previously failing road contact case and six bridge approach cases. The final mask-only change reran the 17 road cases and three triangle-budget cases; the remaining geometry tests had passed before that material-only change.

Both `GpuRoadSmokeTest` cases and `GpuBridgeSmokeTest` passed with native OpenGL. They cover visible asphalt on every roundabout approach, mask reuse during live edits, normal maps, wet surfaces, mixed road materials, concrete walls and all 64 bridge exit patterns. Captures were visually reviewed for the concrete retaining walls, two-level natural earthworks and mixed-height six-way junction. Scoped main/test Checkstyle and `git diff --check` passed.

Measured terrain-roof triangles for one hex at the default test dimensions:

| Fixture | Former fixed grid | Reduced mesh |
| --- | ---: | ---: |
| Flat road with flat surroundings | — | 6 |
| One-level straight climb | 7,171 | 775 |
| Two-level straight climb | 7,171 | 1,702 |
| Six connected roads with mixed neighbouring heights | 6,887 | 3,516 |

These counts include the earthworks and remaining land surface. They exclude cliff walls, bridge assets and the repeated triangles submitted by material passes. Complex junctions still cost substantially more than flat ground. Comparing 7,930 height samples per ramp fixture against the former grid gave RMS differences of 0.021, 0.019 and 0.024 board units; maximum differences were 0.146, 0.116 and 0.254 respectively (one level is 18 units in these fixtures). These are geometry measurements, not frame-rate or large-board memory benchmarks.

The temporary isolated build harness excludes `GpuPropBatchSmokeTest` and `GpuBoardWindowSmokeTest` from compilation because other in-progress work changed their instancing/UI APIs. They were not reported as passing. No unrelated test sources were modified to unblock this review. Already-staged assets were used because the running application held a font file open. The full project suite was not run.

### Earlier checks

88 targeted CPU/integration tests passed: `BoardRoadTest` (6), `BoardFeaturesTest` (9), `BoardSurfaceBlendTest` (7), `BoardSurfaceTest` (65), and `GpuRoadSourceTest` (1). Coverage includes every exit mask, all six orientations and both column parities for mixed-width joins, draping on ramps, existing bridge support/movement, terrain capture/edit/removal, fallback retention, and Rough/woods clearance.

The road-ending follow-up passed 26 focused tests: `BoardRoadTest` (9), `BoardFeaturesTest` (9), `BoardSurfaceBlendTest` (7), and `GpuRoadSourceTest` (1). The added coverage checks full-width terminal cuts in all six directions and both column parities, clearance for extended ends, fading material without opaque backing, continuing wheel marks, and isolated road tiles. The earlier 65 surface tests were not rerun for this footprint-only change.

`GpuRoadSmokeTest` checks native OpenGL rendering and compares a live road-type edit with a clean rebuild. Its captures cover top and oblique views, a close junction, wet roads, bridge approaches, and close views of all four material endings. The rendered review also caught oversized gravel and a dirt-road material discontinuity at biome borders; both were corrected. Scoped main/test Checkstyle passed.

The material-quality and transition follow-up passed 29 focused tests on 2026-09-27: `BoardRoadTest` (12), `BoardFeaturesTest` (9), `BoardSurfaceBlendTest` (7), and `GpuRoadSourceTest` (1). Added assertions cover steady carriageway width, fixed tyre spacing, consistent material coverage at shared edges, all three material pairs in all six directions, and an unchanged main-road dash sequence through an unmarked branch.

The updated native OpenGL review also passed. It checks normal-map on/off differences and wet/dry response in actual road pixels for all four road kinds, live-edit equivalence, and brighter dirt deposits at both fixed tyre offsets on the asphalt side of a dirt/asphalt join. Captures include each material pair and a top view of the dirt traces. The wet review now configures rain through the atmosphere, whose frame preparation owns wetness. Scoped main/test Checkstyle and the deterministic road-map check passed. The earlier 65 surface tests were not rerun for this road-only follow-up. This does not replace a large-board performance review or subjective review of every custom map.

The ramp follow-up passed 87 tests on 2026-09-27: `BoardRoadRampTest` (6), `BoardRoadTest` (12), `BoardSurfaceTest` (65), `UnitLandingSupportsTest` (3), and `GpuRoadSourceTest` (1). It checks actual triangle grades on uphill/downhill ramps across all six directions and both column parities, matching height and lighting tangents at shared edges, level bridge arrivals, draped road layers, picking and unit support. Native `GpuRoadSmokeTest` and scoped main/test Checkstyle passed as well. The native review includes close ramp captures for asphalt, dirt and gravel, alongside the existing road materials and bridge checks.

The unsealed-margin and wheel-wear follow-up passed 30 focused tests on 2026-09-27: `BoardRoadTest` (14), `BoardRoadRampTest` (6), `BoardFeaturesTest` (9), and `GpuRoadSourceTest` (1). New checks ensure that loose margins have no opaque or contrasting backing, dirt/gravel joins retain that margin in all six directions, and wheel wear keeps its spacing and follows the road direction. Native `GpuRoadSmokeTest` and scoped main/test Checkstyle passed. Rendered dirt/gravel strips, material joins and road endings were visually reviewed; the review caught and corrected a sharp verge remaining at dirt/gravel joins. Existing native checks also cover normal maps, wet response, asphalt dirt deposits and live-edit equivalence. The larger terrain support suite was not repeated for this surface-only change.

The review uses isolated build output through `build/road-review.init.gradle`. Tests used already-staged assets (`-x :megamek:stageDataFiles`) because the running application held a mapped font file open. Captures are under `build/road-review/megamek/gpu-board-review/roads/`. No large-board performance claim is made by this visual/geometry validation.
