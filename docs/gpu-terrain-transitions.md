# Natural terrain material transitions

Implemented in `megamek_temp`, September 2026. This is the material milestone of
[the realism plan](gpu-terrain-realism-plan.md). Large pillars, connected erosion
features, and more ambitious landforms belong to the subsequent `megamek_gaea`
work. The board's terrain types, heights, movement rules, and unit support remain
authoritative.

## Coverage between natural surfaces

`BoardSurfaceBlend` evaluates cover in world space from the existing `BoardScene`.
Grass, dirt, sand, rock, and snow can meet. A hex retains its interior identity;
neighbouring cover is confined to a boundary region whose nominal half-width is
`WIDTH_METRES` (4.5 metres). Two scales of deterministic variation make the edge
irregular. Three materials can contribute at a hex corner; the water tile's
material is never borrowed to colour an entire surrounding shore.

The footprint containing a position owns the neighbour query, regardless of which
mesh emitted that position. Both sides of a shared contact therefore sample the
same cover. One-level natural steps can exchange cover when hex transitions are
enabled; actual surface height limits the influence. Cliff faces query the
natural columns that reach the sample's height, so adjacent geological families
can meet without borrowing a valley floor's cover. The contact widens modestly
and varies down the face instead of extruding the top boundary vertically.
Ice, concrete, buildings, and authored special ground block this exchange;
rendered roads retain their separate overlay.
Concrete keeps the existing None / Water only / Everywhere outline policy.

Natural banks use the same query, with the adjoining land as their source. Inside
a water footprint, distance is measured relative to the nearest eligible bank so
the blend continues to a recessed waterline instead of stopping at the hex edge.
Its angular reach narrows inward; opposite shores do not paint one another.
Shallow exposed bars participate too. The small central bed region converges on
the most common eligible bank family (stable family order breaks ties), retaining
each shore's own material outside that region. This is a bounded visual sediment
heuristic, not a transport simulation or a change to the board's terrain type.

Banks and bed faces pass through the existing water-coverage clipping before
material refinement. Dry and submerged polygons retain separate shading data;
absorption, water level, depth, and caustics still use the existing water inputs.
Natural cliffs pass through that same clipping before blending. Below the
waterline, blended families contribute bed sediment and exposed stone; their
ground-cover and soil-mantle weights reach zero within 0.25 metres below water.
This prevents land grass/sand textures from painting submerged contact stripes.
Concrete banks and authored road ground keep their existing treatment.

`GpuSurfaceBlend` prepares the extra attributes only for affected natural faces.
It refines long triangles by interpolation, preserving their planes, area and
support geometry, then groups triangles by a palette of up to three families.
Coincident vertices retain their identity: a top and a cliff can meet at exactly
the same position while carrying different packed shading roles and UV meanings.
Each family contributes its existing ground, soil, rock and debris maps through
one shared texture array owned by `GpuAssets`. The same material evaluator selects
roles from slope, rim, foot and deposition for all families. The renderer's terrain
detail level controls boundary sampling at distance (2/2/4/8 metres for
FULL/MEDIUM/COARSE/DISTANT). LOD selection and the asset LOD0/LOD1/LOD2 meshes are
unchanged; the coverage field and texture projection use world coordinates at
every tier.

The shader refines broad cover with world-space patches and texture height.
Competing textures remain legible rather than becoming a wide colour crossfade.
The resulting weights drive colour, normal, ambient occlusion, ground bounce,
and rain response together. Colours are mixed in linear light. Snow remains
excluded from the ordinary rain-darkening/puddle treatment. Grass tufts use the
shared broad coverage and thin out before small snow/sand margins.

## Continuity within each family

`terrain-materials.glsl` supplies one natural-surface evaluation for ground and
cliffs. Ground cover, soil mantle, exposed rock and broken debris use compatible
world-space projections and the same weights for their visible channels. An
inclined face gradually switches from horizontal to vertical projection without
resetting texture coordinates at its rim or foot. Geological bed colour remains
on exposed rock.

Cliff-foot distance allows deposition but no longer draws a mandatory collar.
Slope and world-space patches decide where scree persists, where soil shows,
and where grass or snow covers it. The grassland apron reported in the review
therefore tapers into broken patches instead of revealing one solid polygon of
scree. This changes material coverage, not the cliff's silhouette. The later
geology work should improve the landform and sediment placement together.

## Ownership, edits, and cost

There is no new editable terrain state, texture atlas, background simulator, or
material registry. Existing neighbour-aware surface keys invalidate coverage and
grass after a local edit. The render snapshot remains the input; caches are not
game state. The same geometry continues to serve picking, formations and shadows.

Uniform family interiors retain their ordinary vertex layout and material maps.
Boundary vertices add three floats (12 bytes). Boundary families use one array
sampler alongside the existing base and environment maps, staying below the
16-sampler budget. The array is built lazily on the render thread from the same
sculpt asset names, contains interleaved colour/height and normal/AO layers, and is
disposed with the renderer. Its current 26 RGBA layers at 512 square with mipmaps
add about 34.7 MiB when mixed materials are used. Missing maps use neutral fallback
layers. Native OpenGL compilation and rendering are part of verification.
No speedup or compatibility beyond the tested local renderer is claimed.
In the seven-hex crowded-family CPU fixture,
full-detail refinement changed 3,276 triangles to 7,144 without changing area or
surface height. That is a geometry cost measurement, not a frame-time benchmark.

## Verification

The targeted suite covers shared contacts in all six directions, three-family
corners, uniform interiors, blocked contacts, neighbour edits, mixed bank joins
through to the waterline, shallow bars, material palette completeness, unchanged
triangle area/height, and separate cliff/top shading at coincident vertices.
Existing water-coverage, concrete-shore, terrain-detail and infantry-footprint
tests are included to catch integration regressions.
The scoped Java style checks cover the changed rendering files and new tests.

The original material milestone passed 42 targeted tests and two native capture
suites. The cliff/waterbed follow-up adds shared high-column contacts, steep-face
coverage at each terrain LOD, and a native submerged-material regression. These
are targeted checks, not the full repository test suite.

Verified locally on 27 September 2026: 101 focused CPU tests passed, both
`GpuTerrainBlendSmokeTest` and `GpuTerrainContactSmokeTest` passed, and scoped
main/test Checkstyle passed. This includes the crowded cliff palette check,
local-edit versus clean-rebuild image comparison, and unchanged submerged pixels
after replacing the land-cover maps. Run logs are saved under
`build/terrain-contact-review/validation-complete.log` and
`build/terrain-contact-review/validation-crowded.log`.

`GpuTerrainBlendSmokeTest` runs the real shaders for snow/grass/sand boundaries,
five-family crowded contacts, isolated patches and edits, a mixed-material step,
rain, disabled normal maps, and grass/sand/snow elevation bands. An edited sand
patch is compared with a fresh renderer's image, including its grass scatter.
It saves top and oblique views, plus close-ups of the grass cliff foot and mixed
shores at depths zero and one. `GpuTerrainDetailSmokeTest`
checks the existing mixed banks, Rough across materials and elevations, and the
shipped Short Canyon board.

`GpuTerrainContactSmokeTest` captures low banks, tall cliffs, shores and exposed
beds at all four terrain mesh tiers. It replaces the grass and sand ground maps
with magenta, verifies that dry contacts change, and checks that deep submerged
pixels remain identical. This exercises production clipping and shader material
selection together. It also checks for OpenGL errors and verifies texture-array
disposal. The captures are under `megamek/build/gpu-board-review/terrain-contact`.

[Gaea 3](https://www.quadspinner.com/Gaea3) and an imagegen study informed the
broken soil/sand/turf contact and exposed-rock treatment. The generated study and
prompt are review artifacts under `build/terrain-contact-review`; they are not
screenshots of the implementation or new runtime textures.

Local review captures live under `megamek/build/gpu-board-review/terrain-blend`
and `terrain-detail`. The frozen pre-change comparison is under
`build/terrain-transition-review/before`. These generated images are review
artifacts, not runtime assets.

## Remaining limits and handoff

- Natural boundaries still express the board's placement of terrain. The pass
  does not erase hex identity or change a tile's gameplay surface.
- Cross-family mixing follows the board's natural material columns, including
  steep faces. It is a bounded visual treatment, not a geological transport or
  rock-strata simulation. Buildings, roads, construction and water optics retain
  their own rules.
- Grass placement follows broad coverage; sub-metre shader details are not a
  second CPU texture-reading system. Tufts are conservatively suppressed near
  those contacts.
- The cliff-foot material is less geometric, but the existing apron shape and
  procedural rock positions remain. Improving their common deposition pattern
  belongs with the planned geology changes.
- Push/pull this verified transition milestone before starting the later
  `megamek_gaea` work. Preserve it as the reference for material, water and unit
  support while adding prominent formations. Other renderer work may share the
  checkout; review the complete commit contents before pushing.
