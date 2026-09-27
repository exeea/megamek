# GPU special terrain: rendering decisions

This audit covers all terrain IDs in `Terrains` (1–61), plus clear ground. It separates the desired rendering from what is implemented. Terrain appearance remains a presentation of the game's hex, terrain levels and exits; cosmetic shapes must not change movement, cover, elevation or water depth.

## What causes the flat tiles

`BoardFeatures.detailedGround` currently makes one decision for a whole hex. An unfamiliar terrain or helper property makes it false. `GpuTerrain.sculpt` then puts the captured ground artwork on its top, even when the actual feature already has a model. `BoardRelief` also uses the flag to disable ground relief and protect the hex from shoreline movement.

`BoardView.captureGroundArtwork` includes roads, fields, rubble, mud, swamp, ice and magma in the fallback ground image. Ordinary dry roads now use a separate road strip over engine ground; custom road artwork and unsupported combinations retain that fallback. Rough has engine ground and embedded boulders; its painted rocks are excluded from GPU artwork. `captureDecals` captures other symbols separately, removing features presumed to have models. Buildings, industrial structures and tanks are selected by matching their tileset art to existing model files. That is different from the generic bridge and crop models.

Consequences:

- A model and the material below it need independent treatment. The building's height or CF does not make the concrete underneath it a different substance.
- Enabling terrain relief everywhere is unsafe. A building currently sits at the height sampled under its centre; a raised mound or recessed shore could leave part of its footprint unsupported.
- Some missing features are only visible in the legacy artwork. Removing that artwork without replacing the feature would hide real game state.
- Rough has deterministic boulders and denser cover for ultra rough. Rubble still has only ordinary cosmetic scatter, which is not a representation of its terrain level.
- Decorative grass currently has no building-footprint exclusion. Converting buildings on natural ground must address that too.

## Decisions for every terrain type

“Engine” below means the existing procedural/sculpted terrain material and shared board geometry. “New work” is a rendering decision, not a claim that the feature has been implemented.

| IDs / terrain | Current representation | Rendering decision |
| --- | --- | --- |
| 0 clear | Engine material selected by theme | Keep continuous ground; ordinary decoration follows the biome. |
| 1 woods, 5 jungle, 54 foliage elevation | Species, density and height drive tree models; foliage artwork can supplement them | Keep models rooted in the actual surface. Preserve ground family; foliage height is a model input, not a reason for flat ground art. |
| 2 water, 16 rapids | Water surface, riverbed, flow, slopes and waterfalls | Keep the shared water system. Surface height uses elevation; riverbed and optical depth use water depth. Rapids modify flow/foam without replacing the ground. |
| 3 rough | Engine ground and embedded rock-kit boulders, shaded with the local geology | Normal and ultra rough use different rock counts. Cover the centre, slopes and talus while preserving coexisting woods, road/bridge exits and building foundations. The complete rocks belong to the shared terrain mesh and picking; their bases reach the sampled ground, canonical cliff grid or bed. Ordinary units and formation vehicles may clip the cover; standing infantry seek gaps inside the plateau, then stand on rocks when crowded. |
| 4 rubble | Ground artwork; possible ordinary scatter | New work: broken masonry, slabs and rubble piles. Use rubble level to distinguish source/density, including ultra rubble. Remains need ground contact; destruction replaces the building, rather than layering an intact building over rubble. |
| 6 sand | Sand engine | Keep dunes, sandy banks and existing ground material. |
| 7 tundra | Base ground family and vegetation selection | Add sparse scrub, exposed soil and frost variation to the appropriate biome; do not automatically turn tundra into deep snow. |
| 8 magma | Molten liquid has an animated material; crust uses artwork | Keep molten mode. New work for crust: solid dark rock with glowing fissures. Changing crust/liquid state must update both geometry and material. |
| 9 planted fields | Generic crop model over ground artwork | Keep crops, add soil rows aligned across adjoining fields, fit rows to boundaries and keep them grounded. Avoid duplicate painted crops beneath the model. |
| 10 heavy industrial | Selected model when matching asset exists; old ground underneath | Engine ground plus supported industrial models. Keep height from terrain; preserve access and neighbouring roads. Retain fallback art until an unmatched industrial feature has a replacement. |
| 11 space, 56 sky | Board atmosphere/context exists; no dedicated per-hex conversion in the ground-material selector | Render as the appropriate backdrop/volume, without a solid grass slab. Decide this from board/hex context, not from a terrain-family guess. |
| 12 pavement | Concrete engine | Use connected slabs and constructed shores. Buildings do not require a different ground texture. Sidewalks are geometry only where there is room and a road/building context. |
| 13 road, 45 road fluff | Ordinary dry roads use engine ground and separate strips; exits and ramps remain authoritative | Normal/alley/dirt/gravel have distinct surfaces, joined junctions, matched widths and bridge approaches. Natural ground remains visible outside the strip. Custom road fluff and unsupported combinations retain their artwork; extracting arbitrary custom markings as decals is still future work. See [GPU roads](gpu-roads.md). |
| 14 swamp | Ground artwork; dirt family | New work: saturated soil, shallow puddles and vegetation, with separate quicksand appearance for its levels. Do not infer gameplay water depth or treat every swamp as a flowing river. |
| 15 mud | Ground artwork; dirt family | New work: wet, uneven soil and small puddles; blend into neighbouring ground without replacing the entire hex with one image. |
| 17 ice | Frozen water has a top mesh using artwork | New work: an ice surface material with cracks, thickness at exposed edges and the existing water/bed beneath. Preserve the distinction between ice over water and ice on land. |
| 18 snow | Snow engine | Keep the material; make thin/deep snow distinct without inventing another game elevation. Snow on structures/roads is a covering, not a replacement of those objects. |
| 19 fire | Captured terrain decal; combat effects are a separate system | New work: terrain-driven flames, embers and scorching. Fire kind comes from the terrain level. Start/stop on snapshot changes and use the shared animation clock. |
| 20 smoke | Captured terrain decal | New work: a world-space plume/volume, with density and type from smoke terrain. LOS remains the game's result; smoke must not erase the ground material. |
| 21 geyser | Artwork; no terrain-driven geyser model/effect | New work: a vent rooted in rock, with dormant, steam/water eruption and magma-vent appearances matching its state. |
| 22 building | Selected extruded structure model | Engine material underneath; grounded foundation covering its actual footprint. Concrete buildings can already use the engine safely because concrete is flat. Natural ground needs a supported pad and decoration exclusion before conversion. |
| 23 building CF, 24 building elevation, 25 basement type, 26 building class, 27 building armour | Helpers currently also trigger special ground | These configure structure/damage/interior rendering, not ground material. Hidden basements must not produce visible information unavailable to the player. |
| 28 bridge, 29 bridge CF, 30 bridge elevation, 60 repaired bridge | Deck model, exits, approach geometry; repair badge in tactical capture | Keep deck and ground/water independent. New work: suitable piers/abutments and material treatment; supports extend to the actual bed/ground. Preserve repair state and multi-hex deck continuity. |
| 31 fuel tank, 32 tank CF, 33 tank elevation, 34 tank magnitude | Selected structure model | Engine ground plus supported tank base. CF/height/magnitude are structure inputs. Add a concrete pad only when justified by the existing paved ground or model footprint, not as a whole-hex material override. |
| 35 impassable | Terrain/tactical marking | Keep an explicit optional tactical indicator. Do not invent a cliff or wall whose geometry contradicts the map. |
| 36 Solaris elevator | Artwork and authoritative moving elevation | New work: platform and frame following the game's changing elevation. Never animate a second independent lift state. |
| 37 fortified | Artwork | New work: earthworks/trench/low emplacement geometry grounded in the local material, keeping unit placement and access clear. Do not imply a building height the hex does not have. |
| 38 screen | Artwork | New work: a translucent screen/chaff volume in space, driven by the terrain state. It is not a ground coating. |
| 39 fluff | Captured decal | Keep arbitrary custom art as a decal until explicitly understood. Known cleared-rubble-path variants can use scattered debris with a clear route. Unknown numeric variants must not become random models. |
| 40 arms, 41 legs | Limb models, with a decal fallback if their model is unavailable | Keep the authoritative counts and stable placement. They lie on terrain; they must not switch its material. Retain the existing load-success fallback. |
| 42 metal content | Data/tactical information; engine ground allowed | No automatic metallic surface. Underground metal content does not establish the visible ground substance. |
| 43 collapsed basement | Artwork | New work: a bounded collapse/debris cavity using revealed game state and supported surviving structure. Treat separately from an intact building on normal ground. |
| 44 building fluff | Used in tileset/model selection; currently also affects ground selection | Keep it as structure appearance input. When a selected model represents it, it must not force a flat base tile. Unknown unmatched artwork retains its fallback. |
| 46 ground fluff | Captured decal | Keep explicit ground markings as surface-following decals. Known sculptable patterns can gain geometry; arbitrary artwork remains supported. |
| 47 water fluff | Removed from decals as a modeled-water terrain | Audit individual custom variants before discarding them. Ordinary water remains the water engine; unique authored features require an explicit overlay or replacement. |
| 48 cliff top, 49 cliff bottom, 50 incline top, 51 incline bottom, 52 high incline top, 53 high incline bottom | Shared terrain geometry | Keep canonical slopes/walls; helper markings must not select another material. Terrain/riverbed slopes include water depth, while water-surface drops do not. |
| 55 black ice | Artwork | New work: a thin reflective glaze on the existing surface, especially roads/pavement, without turning the tile into a deep ice slab. |
| 57 deployment zone | Tactical representation; engine ground allowed | Keep in the tactical layer. No physical replacement ground. |
| 58 hazardous liquid | Liquid geometry and tinted animation | Keep the liquid system and its distinct palette/effects. It must not force legacy ground art around it. |
| 59 ultra sublevel | Helper state; no dedicated renderer treatment | Resolve the represented floor/void from the authoritative terrain rules, then use matching ground and walls. Do not assign a material solely from this flag. |
| 61 industrial elevator | Artwork; game owns capacity and shaft state | New work: shaft walls, supported frame and platform at the authoritative elevation. Preserve interaction and unit placement; avoid an independently animated platform. |

## Neighbour patterns and mixed terrain

The construction comes from the connected shape, not just one tile's material:

- Broad concrete waterfronts have straight quay segments. A shortcut must leave a safe area around every affected water centre. Test the actual lattice distances in all directions; a rule safe vertically may bisect a horizontally arranged water hex.
- Dock shape follows the water/land neighbour pattern below. Fit rectangular arms with one axis and width for both sides, including diagonal runs. Fit the landing to those rectangles; independently simplifying the two banks can taper a dock and is not acceptable.
- Breakwaters can occupy otherwise awkward wet wedges beside constructed corners. This is a planned feature, detailed below; it must use the completed shore geometry rather than independent per-hex decoration.
- Buildings, road approaches and other supported structures constrain that shape. The present conservative protection retains their original land. Further recession needs actual transformed model footprints, including multi-hex structures; checking only the hex centre is insufficient.
- Mixed terrain composes: concrete + building, road + bridge + water, snow + road, rubble + woods, mud + limbs, fire + structure. One extra helper must not silently erase another feature.
- A depth-zero shore can expose shallows and land patches; a concrete quay should remain a constructed edge. Each shore and exposed bar now uses the material of its facing land. Material partitions preserve shared river-mouth geometry and smooth normals.

| Water neighbours | Constructed shape |
| --- | --- |
| Six | Always retain a sharp hexagonal platform, empty or occupied. |
| Five | Rectangular end, with a square cap facing the water and its open end pointing toward the land neighbour. |
| Four, with opposite land neighbours | Straight rectangular span joining both neighbours. Both banks use the same axis and fixed width. |
| Four, with non-opposite land neighbours | Angled join between two rectangular arms; extend their parallel banks to their intersections. |
| Three or fewer | Broader landing/quay. Continue dock sides into a safe landing and fit intervening straight quay runs. |

All cases preserve water-centre clearance, dry unit support, and protected building/road foundations. Invalid fits keep the supported footprint; they do not taper the rectangular arm or fill a water centre. Unequal water levels and incompatible terrain interrupt a constructed run.

## Implementation order and acceptance checks

1. **Material/foundation separation.** Remove false fallback for supported concrete buildings now. Then give natural-ground structures supported pads and grass/rock exclusion, so their engine materials can be enabled safely. Reuse existing assets; do not create a parallel terrain configuration framework.
2. **Connected construction.** Quays and docks use shared geometry for drawing, picking and neighbouring water. Check vertical and diagonal runs, all rotations, building/road interruptions, inlets, water centres, depth changes and folded triangles.
3. **Ground features.** Rubble, mud/swamp, fields, roads and ice need the highest-priority replacements. Rough now has material-matched boulders. Build comparison boards with combinations, not just isolated demo tiles. Check transitions, unit support and material continuity.
4. **Terrain effects and unusual structures.** Fire/smoke/geysers/screens, fortified terrain, collapses and elevators require state-driven meshes/effects and cleanup on terrain changes. Reuse the board's shared clock and ownership model.
5. **Fallback policy.** Keep unknown custom art and unimplemented visible terrain identifiable until a validated replacement exists. Explicitly document remaining artwork rather than claiming that all special terrain is now procedural.

Tests should verify feature presence and removal, authoritative heights/exits, ground contact across a footprint, shared boundaries and visibility. Screenshots from above and obliquely are needed for each new treatment; a classification test alone cannot establish visual correctness.

## Implemented in this change

- Water-bank faces, exposed shallow bars and bank rocks inherit the material of their facing land, including mixed concrete and natural shores. Shared geometry and normals remain continuous across material batches.
- Rough uses engine ground and embedded boulders from the existing rock library, with local geology and snow treatment. Ultra rough has more boulders. Centre and slope cover persists on isolated elevations; the canonical cliff geometry supplies the foundation beside steps. Snow covers upper facets while stone sides stay visible. Moss is a faint texture-preserving stain, including on Short Canyon. Placement clears road/bridge exits and building foundations; GPU artwork no longer duplicates the painted rocks.
- Infantry gap fitting uses the actual Rough mesh and stays within the existing plateau constraints. When no gap fits, feet rest on the boulder; vehicles keep their positions and use the ground beneath Rough for their support plane. The derived standing layout is cached until terrain, placement, scale or membership changes.
- Concrete buildings with a selected model use the concrete engine beneath them. Other special terrain in the same hex still retains its current handling.
- Concrete bank segments are straightened only when their chord clears the water centre. Canonical mouth positions eliminate repeated dents along long quays. Original building ground remains supported.
- Constructed-shore shaping distinguishes platforms, dock ends, straight spans, angled joins and broader landings. All adjoining meshes consume the same displaced lattice corners; buildings and other modelled props retain their land.
- GPU Tuning → Terrain → Concrete shapes offers **None**, **Water only**, and **Everywhere** (the default). `BoardConcrete.DEFAULT_MODE` sets the default. None preserves the exact sharp hex borders, including beside water; concrete never receives natural shoreline rounding. Everywhere also fits broad rectangular patches against grass and other terrain. Enclosed terrain patches retain their space while their concrete boundaries become straight runs meeting at corners. Fits remain constrained by terrain centres, existing foundations and incompatible elevations; arbitrary irregular connected patches are not forcibly replaced with one bounding rectangle.
- `BoardConcrete` holds one bounded immutable outline derived from the tile snapshot. The cache keeps only a weak reference to the input tile list; it owns no game state, textures or GL objects. Surface keys and terrain chunk invalidation include changed coast corners, including changes propagated along connected construction.

The terrain-specific work marked “New work” above remains a design decision and implementation backlog, not a completed renderer.

Verification on 2026-09-26: 157 focused terrain geometry/capture tests and seven native GPU smoke tests passed, with no skipped tests; `checkstyleMain` also passed. The native runs covered mixed shores, Rough across all six ground families, both AeroBases, docks, constructed coast patterns, None mode and terrain tuning. Representative top and oblique screenshots were inspected. This is focused terrain verification, not the full repository test suite.

Rough follow-up verification on 2026-09-26: 36 focused geometry, capture, footprint and support tests passed. Native terrain and infantry tests passed, covering Short Canyon, isolated elevations, snow contrast, unchanged vehicle positions, stable terrain edits, gap fitting and forced standing on boulders when no gap remains. The existing native river-plateau placement test also passed. Rock tops now sample the stone texture from above, preserving detail on horizontal facets; moss only tints that texture faintly. Reviewed captures are in `build/terrain-review/rough/`.

## Breakwater task

- [ ] Find residual wedges between original concrete outlines and the constructed shore. Prefer exposed outer corners with enough neighbouring open water; exclude narrow river channels, dock access, inlet mouths and all unit-centre clearances.
- [ ] Derive a usable placement polygon from that wedge, inset by the block's full footprint. Reject wedges too small for a supported block. Follow the bank tangent, with deterministic variation in rotation and spacing; do not sprinkle blocks independently in every tile.
- [ ] Generate simple concrete armour blocks/tetrapods like the reference, using the concrete material. Bound cluster height and width by available shore space and water depth. Do not turn them into fictitious gameplay obstacles or conceal tactical state.
- [ ] Build a submerged foundation from the actual riverbed up to the lowest armour blocks. Blocks must touch the mound or other supported blocks; none may float above water or hang across mesh gaps. Keep building/model foundations clear.
- [ ] Integrate water contact, local foam and wetting. Shallow-water foam remains weak; do not paint white patches over dry blocks. Check depth changes and views from below/behind the coast.
- [ ] Verify repeatability, shared ownership at hex/chunk boundaries, no duplicate clusters, clear water centres and boat channels, no overlaps with buildings, and stable rebuilding after terrain edits.

Keep constructed-shore pattern and clearance calculations in `BoardConcrete`. A future block mesh generator should consume its placement result; it should not grow `BoardRelief` or introduce another terrain-state model.

## Further `BoardRelief` separation

The file is about 2,800 lines. Its existing sections suggest a few useful boundaries, but splitting each section into an object would require passing much of the same mutable geometry between them.

| Responsibility | Decision | Reason / boundary |
| --- | --- | --- |
| Constructed coastline patterns | Extracted to `BoardConcrete` in this change | Uses scene tiles and canonical points. Owns pattern classification, fitted rectangles and quay joins, plus a bounded derived outline cache. Returns geometry decisions without owning meshes or game state. |
| Cliff shape functions (`profile`, caprock, Voronoi masses, strata, bedding, displacement limits) | Best next extraction: a package-private `BoardCliffProfile` | Mostly numerical functions of position, height and geology. Expose profile displacement and bedding shade; keep one geology/tuning owner. Move existing calculations unchanged before altering their behaviour. Shared noise/math must remain one implementation. |
| Canonical corners, edges, transition bands and wall joins | Keep together in `BoardRelief` | They share the same displaced endpoints, height bands and caches. Separating them now risks mismatched terrain/water seams and would need a broad back-reference interface. This is the cohesive core of the class. |
| Water-bank strip construction | Possible later `BoardBanks` | A useful boundary when revisiting bank materials: consume an already-computed boundary and waterline, emit faces and normals. Do not let it recompute shoreline or terrain heights independently. Current shade and parameter ownership need a clean boundary first. |
| Rocks, plants and pavement tree pits | Possible later `BoardGroundDetails` | They form one placement context but currently depend on terrain height, clearance, wetness and shading. Extract when footprint support/exclusion work provides those inputs cleanly; avoid many callback parameters or making private internals public solely to move code. Reuse `BoardRocks` for shapes. |
| Shore field | Keep for now; `BoardRiver` already owns connected river shape | A second shore object would still need the same `Site` classification, corner movement and land-retention calculations. Moving only the formula would obscure their relationship rather than simplify it. |
| Tuning and material definitions | Keep one existing source | No terrain provider registry, factory hierarchy, duplicate tuning record or per-feature configuration system. Separate configuration only if a real second consumer needs a clearer ownership boundary. |

Recommended sequence: finish and verify `BoardConcrete`; extract the numerical cliff-profile functions in a separate behaviour-preserving change; revisit bank meshing and ground-detail placement alongside the actual features that need them. Validate a pure extraction with the existing geometry suite and identical review-scene output, rather than adding tests that only repeat moved code.
