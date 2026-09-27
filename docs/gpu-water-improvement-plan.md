# GPU board water improvement pass

Analysis and proposal, 26 September 2026. The broader wave improvement pass has not started. Separate corrections
to the current engine's rapids foam and sloped-water geometry are recorded below.

The recommended pass extends the existing FFT water into a surface with visible wave height, then makes its
amplitude, lighting and foam respond coherently to the surrounding terrain. Large connected lakes should show
travelling wave forms without requiring a river current. Sheltered bays, shallow shelves and narrow passages
should have visibly different water behaviour.

Sea of Thieves is the visual target. Following the user's clarification, the AI-game screenshot and first video
are excluded from the target. This is an improvement to the existing board renderer and its shared scene, not a
new water engine or a change to game rules.

**Reference review.** The two Sea of Thieves recordings are approximately 2.60 and 18.41 seconds long. Extracted
frames were reviewed throughout both clips, including closer samples of the short clip. The most useful qualities
are broad irregular crests, smaller waves riding across their faces, dark troughs, light passing through raised
crests, and foam gathering in broken streaks around active crests. Broad forms remain readable outside the sun's
bright reflection. These relationships matter more than copying the ocean's wave height or saturation. Ship and
camera motion prevent reliable measurements of wave speed or physical height from these recordings.

Rare's published account confirms an FFT ocean, a wave-peak mask derived from choppy vertex offsets, approximate
scattering, and foam with temporal feedback and dispersion. Those are useful implementation principles; they do
not establish how every effect visible in the supplied version is implemented.
[Rare, The Technical Art of Sea of Thieves, SIGGRAPH 2018](https://history.siggraph.org/wp-content/uploads/2022/09/2018-Talks-Ang_The-Technical-Art-of-Sea-of-Thieves.pdf).

**Evidence boundary.** The findings below come from the current working-tree source and the supplied references.
The recordings show reference games, not the current MegaMek build. No current-build animation capture or GPU
benchmark was run for this analysis. The absence of animated lake geometry is confirmed in code; the relative
visual contributions of wind settings, shading and fallback behaviour still need the baseline capture in stage 0.
Existing source changes, including water, terrain and performance work, were left in place during that analysis.
The subsequent Jungle River 1 investigation added current-build captures and a focused foam correction.

The current implementation already has substantial useful infrastructure:

| Existing behaviour | Evidence and implication |
| --- | --- |
| GPU FFT wind waves | `GpuOcean` runs a 128 by 128 simulation over a 64 m patch. Its final texture contains slopes, crest compression and persistent foam. Reuse this simulation. |
| No animated lake height | `ocean-spectrum.frag` computes spectral height but transforms slopes and horizontal offsets. `ocean-finish.frag` exposes neither height nor offsets to the water mesh. `GpuWaterfall.vertex()` moves spray only. Lake geometry therefore has no travelling crests or troughs. |
| Static water topology | `BoardSurface.river()` triangulates ordinary water polygons. Graded river surfaces already have static slopes. Neither is a mesh designed to resolve animated broad waves. |
| Several scales of surface shading | `water-surface.frag` samples the same ocean at 0.43, 1, 2.37 and 5.3 scales. These add normal detail, but they are not independent physical wave bands: spatial rescaling also changes their apparent travel speed. |
| A local water field | `GpuWaterShader.Field` stores bank distance, optical depth and current on a world-aligned lattice. At the default scale it samples every 4.5 world units. Bank distance saturates at 0.4 hex widths, and optical depth at four levels. It cannot describe basin-scale exposure. |
| Local shoreline approximation | The shader's `open` factor uses depth and bank distance. Its `fetch` looks only 0.35 hex widths beyond the sample. This helps suppress surf in channels but cannot describe a large lake, an island's lee or the connection between two basins. |
| Existing current inference | `BoardFlow` uses connected surface elevations and channel topology; broad bays generally have no current away from outlets. Depth changes alone correctly do not cause flow. Wind waves must work independently of this current. |
| Existing foam history | The FFT finish pass keeps the larger of current breaking and previous foam minus a fixed decay. This preserves foam at a texture location but does not transport or spread that history. Surface noise does use existing two-phase current advection. |
| Existing optical model | Shared absorption, bed tint and caustics already provide depth cues. Reflection uses an approximate sky/cloud environment; there is no scene reflection or refraction capture. Body opacity is capped at 0.5 for unit readability; reflection and foam can raise total coverage. |
| Existing integration and tests | Water shares atmosphere, shadows, rain, wader effects, chunk ownership and the animation clock. Existing smoke tests cover water appearance and integration, but do not establish convincing motion on a large, varied-depth lake with constrictions. |

The primary structural problem is therefore missing animated surface shape. Material tuning remains necessary:
adding height without coherent normals, crest lighting and foam would simply turn a flat glossy surface into a
wavy glossy surface. Conversely, increasing specular brightness or normal strength cannot provide wave volume.

Two smaller diagnostic points belong in the baseline. `GpuOcean` normalizes RMS slope to `0.07 + 0.2 * wind` and
changes spectral shape with wind. `BoardAtmosphere` maps calm or unresolved random wind direction to zero visual
wind. Also, a failed FFT initialization falls back to the ripple texture. None of these conditions has been
confirmed as the cause of the user's current view.

The intended behaviour across common map features is:

| Situation | Intended result |
| --- | --- |
| Large exposed lake | Coherent travelling wave groups, visible shape and directional variation even with zero river current. |
| Small pond or sheltered bay | Lower, shorter waves and quiet regions; still visibly water without a constant white foam rim. |
| Deep water to a shallow shelf | Smooth changes in colour and wave energy; modest steepening/breaking only where incoming waves justify it. No surface step caused by bed depth. |
| Two lakes joined by a narrow neck | Smoothly reduced transmitted wave energy and restrained local chop. A narrow passage alone does not create rapids or a permanent current. |
| Channel with an existing downhill current | Existing flow remains the source for drift; localized shear and foam may intensify where the actual passage narrows. |
| Island or peninsula | Directional shelter and calmer water behind the obstacle, with continuous transitions around its ends. |
| Sloped reach or waterfall | Existing mean surface and flow survive; added swell eases down before constrained joins and waterfall lips. |
| Board edge | Existing open-edge/cut-face convention remains intact; no invented beach or surf along the rectangular board boundary. |

The implementation should proceed in the following order. Each stage ends with a source review and visual check
before its changes become the basis for the next.

0. **Establish the baseline and a repeatable review scene.**

   Extend the existing on-demand water review harness with a large basin, several depth bands, an island, a shallow
   bar, a sheltered bay, and a one-hex neck leading into a second basin. Include a separate downhill channel and
   waterfall. Use the same saved board, camera positions, wind and animation time for comparisons.

   Capture short animations from the normal tactical view, a close oblique view and a low perspective view.
   Include calm, moderate and strong wind; sunny and overcast lighting; and a distant overview. Log actual wind,
   effects state and whether the FFT is active. Check the raw wave result so a disabled simulation cannot be
   mistaken for an artistic tuning problem.

   Add only the diagnostic views needed in the existing tuning/review tools: wave height, normals, bank/depth,
   exposure and foam. Record CPU submission time, GPU water time, draw count, vertices, GPU memory and terrain
   rebuild time. Recent full-board hardware measurements in `gpu-board-performance.md` are useful context, but
   neither those nor older software-renderer water timings provide a current isolated water budget.

   Completion criterion: a reproducible current-build clip and measured baseline showing exactly which layer
   contributes to the flat appearance.

1. **Make the existing waves deform the water surface.**

   Extend `GpuOcean` to expose height and, subsequently, restrained horizontal displacement. Keep one simulation
   owned by `GpuTerrain` and one shared clock. Do not introduce a separate ocean simulation for each lake or hex.

   First investigate repacking height and horizontal offsets into the existing transform's four real channels,
   deriving slopes in the finish stage. Keep the current shading output and add a displacement output. A second
   small finish pass fits the present single-output shader convention and avoids requiring a new MRT framework.
   Verify finite-difference normals against the previous spectral slopes before adopting that packing. The plan
   does not assume that increasing FFT resolution or adding another full transform is necessary.

   Refine only the water render mesh, using the current `BoardSurface` faces and contour. Subdivision must agree
   along shared edges and across chunks. Set maximum edge length from the shortest geometrically represented
   wavelength; leave shorter ripples in shading. Preserve mesh index/page limits and measure vertex growth on
   water-heavy boards. Do not rebuild CPU meshes per frame or tessellate every terrain surface more densely.

   Introduce one small shared wave-sampling GLSL include if vertex and fragment evaluation require it. Height,
   displaced normals and crest information must refer to the same world coordinates, coefficients and instant.
   Account for the gradient of spatial amplitude masks; multiplying height by a shoreline mask while leaving
   its normal unchanged causes lighting seams. Avoid adding the same macro-wave slope twice through vertex and
   fragment normals. Keep fine FFT/detail normals as a separate, smaller contribution.

   Begin with vertical displacement. Add limited horizontal displacement only after shoreline and triangle-fold
   checks pass. Apply it around the existing mean surface, including graded water, rather than replacing every
   body with one horizontal ocean plane. Pin displacement smoothly at shoreline contacts, waterfall lips and
   board cut-face joins. Exclude ice, magma, spray and falling sheets from the lake displacement path.

   Use `BoardRelief.metres()` for horizontal wave scale, and bound height against actual rendered bed clearance
   and bank/lip constraints. Ordinary lake waves should be much smaller than the ocean swells in the reference.
   Tune wave length, amplitude and timing together; upscaling a texture without adjusting its evolution is not
   equivalent to generating a longer physical wave. Start with one coherent primary wave band and existing fine
   detail; add independent bands only if animation review exposes repetition or inadequate scale separation.

   Completion criterion: the lake reads as moving water with fine normals and foam disabled. Crest silhouettes
   change at low angles, broad wave faces read from the tactical view, and no seams or detached lips appear.

2. **Make wave energy respond to basins, depth and narrow passages.**

   Derive continuous wave-support data from the same `BoardScene`, actual `BoardSurface` contours and existing
   current map. The useful inputs are directional open-water exposure, local passage width, true bed clearance,
   and attenuation near constrained joins. Component area alone is insufficient: two large lakes connected by
   a narrow neck must not behave like one fully exposed basin everywhere.

   Keep the current field's shore, optical-depth and current meanings. Add a compact wave-support texture only
   for data that cannot fit there. In particular, the four-level optical encoding must not become the physical
   depth used to prevent displaced troughs crossing the bed. Optical saturation in deep water can remain if it
   looks correct; different deep depths do not inherently need different colours.

   Build geometric support when relevant terrain changes. Compute bounded directional exposure against the
   surrounding scene, including across chunk borders. Update exposure only on meaningful wind-direction changes
   and blend the transition; do not rebuild terrain meshes for wind changes. Test that edits to an island or neck
   invalidate the affected exposure beyond the immediately edited chunk. Exclude ice and incompatible liquids
   from open-water connections, and retain the existing treatment of off-board water.

   Use this data to reduce unsuitable long waves in confined/shallow regions while retaining smaller motion.
   Existing currents drive river drift and current-related agitation. Where a known current passes a neck, a
   bounded width-dependent visual enhancement can reuse it; this is not a conservation-law fluid solver. Keep
   a level, still-water neck without an invented net flow or waterfall.

   This first pass approximates shelter and attenuation. It will not physically solve diffraction, reflected
   basin waves or finite-depth refraction. Do not simulate those by rotating UVs differently at each pixel or
   multiplying time by local depth: both approaches can shear or break phase continuity. A local wave solver
   should be considered only if a demonstrated reference case requires it after the simpler pass.

   Completion criterion: an exposed lake, sheltered bay and neck look distinct in the same shot; transitions
   follow terrain rather than hex outlines; depth changes and wind updates introduce no phase jumps.

3. **Retune light and colour around the actual wave shape.**

   Keep the existing shared optical functions as the basis. Use actual wave height/shape and crest compression
   to concentrate transmitted light near raised crests, with sun/view dependence and deeper troughs. Local wave
   attenuation must also attenuate crest glow. A sheltered, nearly flat surface should not inherit a stormy
   ocean's bright crest mask.

   Balance sky reflection, body scattering, crest light and sun glitter so the water retains broad tonal forms
   rather than covering them with fine bright facets. Revisit the current cubic Fresnel approximation and added
   face/sky terms together. Preserve filtered roughness at distance; unresolved waves should form a soft highlight
   region instead of flickering lines. Reduce fine-detail dominance before simply raising roughness everywhere.

   Audit additive display-space colour terms within the existing lighting conventions. The current conversion
   identity for multiplying colour by light does not make all additive reflection/scattering terms physically
   equivalent. Keep this a focused water-material change, not a global lighting-pipeline rewrite.

   Preserve the deliberate submerged-unit readability treatment, with the bed handling its existing share of
   absorption. Do not remove the body-opacity cap to obtain dark water. Confirm terrain depth, units and palette
   variants together. Keep caustics strongest in clear shallow water and suppressed under deep or strongly
   disturbed water, without replacing the existing bed shaders.

   Completion criterion: wave volume remains legible under overcast light and away from the glitter path, crests
   appear translucent rather than emissive, and submerged units remain identifiable.

4. **Make foam follow breaking and dispersing water.**

   Build on existing crest foam, surf, waterfall impacts and wader effects. Distinguish their causes without
   creating separate rendering systems: wind whitecaps belong to active crests; surf belongs to exposed banks
   receiving waves; river foam belongs to existing current/agitation; impact foam belongs to falls and units.

   Couple shoreline pulses and breaking bands to the incoming wave phase and local exposure instead of allowing
   the current independent shoreline sine to dominate. Break up continuous white borders; calm sheltered shores
   need only restrained contact detail. Fade displacement at the fixed shoreline while suggesting wash through
   foam and wetness, preserving the board's terrain footprint.

   Improve the FFT foam history with restrained transport/spreading and tune its lifetime so crests leave
   fragments that disperse. Check temporal evolution at fixed world points, since foam persistence at an
   unchanged texture coordinate can look painted on. Mask whitecaps using the local wave regime.

   Keep current-driven local foam on the existing two-phase advection path initially. A repeating FFT texture
   cannot store arbitrary board-local currents or shore history. Add a separate local history buffer only if
   clips demonstrate that convincing neck/shore foam requires it, with its update cost and chunk-edge handling
   assessed before inclusion. Reuse the current waterfall and wader integration throughout.

   Completion criterion: foam appears where water breaks, leaves broken trails and fades; sheltered water stays
   quiet, and motion does not show a periodic refresh or a permanent white shoreline outline.

5. **Verify integration, then decide whether further optics are warranted.**

   Both camera modes must use the same scene, water data, clock and picking implementation. The game continues
   to use its existing levels, movement, LOS, visibility and unit placement. Cosmetic waves do not make grounded
   units bob or alter terrain rules.

   The existing terrain picker intersects static mean-surface triangles. Retaining it is the simplest starting
   point, but animated water introduces a deliberate visual/hit-surface difference. Test pointer ownership beside
   visible hex boundaries at the lowest supported camera pitch. Keep amplitudes bounded and reference-coordinate
   grid/selection presentation consistent. If visible disagreement remains, shared water hit evaluation using the
   same displacement data becomes a prerequisite for enabling that displacement level. Do not silently ship the
   mismatch, duplicate the FFT on the CPU, or introduce synchronous per-frame GPU readback.

   Expand culling bounds for the maximum permitted displacement. Review world-position varyings, lighting,
   cloud/geometry shadows, transparent sorting, fog/atmosphere depth, grid overlays, waterfall contacts, bridge
   clearance, unit intersections and board-edge cut faces. A vertex displacement inserted only before
   `gl_Position`, as the spray insertion currently is, is insufficient for all water-surface consumers.

   `GpuTerrain` owns simulation resources; chunks own their fields and render meshes. Release build-only CPU
   data as `Field.finish()` currently does. Dispose new GPU outputs with their owner and preserve framebuffer,
   viewport and texture-binding state. Use the existing OpenGL 3.3/GLSL-prefix path and working fallback. Avoid
   compute shaders, mandatory hardware tessellation and global render-pipeline changes for this pass.

   After the core result is convincing, evaluate subtle scene-colour refraction or improved scene reflection as
   a separately measured extension. They are lower priority than shape, shading and foam, and would need correct
   foreground rejection, displaced-depth handling and protection for submerged-unit readability. Do not begin
   with a reflection render for every lake or promise that an extra capture is free.

   Completion criterion: camera changes preserve the animation, selection remains predictable, affected
   integration checks pass, and measured cost fits the water budget established from stage 0.

**Validation should emphasize animation and boundary cases.** Extend existing fixtures where useful rather than
adding tests that assert constants or reproduce shader arithmetic. Use a small visual matrix and targeted checks:

| Check | Evidence required |
| --- | --- |
| Broad waves without a current | Fixed-camera lake animation, plus a height-only/normal-only comparison. |
| Depths 0, 1, 2, 4 and deeper | Smooth optical transitions, no bed penetration, no false current from a depth step. |
| Neck, island, bay and shallow bar | Coherent wave attenuation and foam; no per-hex patches or water crossing dry ground. |
| Chunk seams and board edits | Identical shared-edge displacement; island/neck edits update distant affected exposure without rebuilding unrelated geometry. |
| Camera and scale changes | Orthographic and perspective, overview and low angle, altered board geometry scale; no phase reset, cracks, selection mismatch or aliasing bands. |
| Other water features | Existing sloped reaches, falls, impacts, units, bridges, ice, cut faces, rain, palettes and GIF fallback remain correct. |
| Time and wind changes | Clips long enough to reveal repetition; stable wind transitions, pause/resume and existing clock-wrap behaviour. |
| Resources and performance | Before/after CPU and GPU timings, median/p95 frame intervals, memory, draw/vertex counts and rebuild latency on the same saved water-heavy boards. |

Useful existing checks include `GpuWaterShaderSmokeTest`, `GpuLiquidSmokeTest`, `GpuRiverTerrainSmokeTest`,
`GpuRiverbankSmokeTest`, `GpuResourcesSmokeTest`, `BoardFlowTest`, `GpuWaterFlowTest`, `BoardWaterSlopeTest`,
`BoardWaterfallTest`, `BoardGeometryPickingTest` and `GpuShaderSourceTest`. Add direct FFT displacement/normal and
native shared-edge coverage where the new behaviour crosses those boundaries. Run appropriate checks at each
stage and expand only when a change exposes another integration risk.

Hardware measurements should distinguish CPU submission from actual GPU work, and an isolated water measurement
from total displayed frame rate. Test the available discrete GPU and integrated GPU where practical; compatibility
and performance on an untested driver remain unverified. No speedup or fixed frame-time cost is claimed here.

The first implementation milestone should combine stages 0 and 1: a captured baseline followed by one convincing
large lake with geometric waves, stable joins and preserved selection/readability. Stage 2 then makes those waves
fit varied-depth and constrained water bodies; stages 3 and 4 supply the material finish needed to reach the visual
target. Tuning should stay in the existing tools, with a few useful wave/foam controls rather than a new settings
system.

The existing FFT implementation is the main reason to extend this renderer rather than replace it. The general
separation between large geometric waves and smaller shading detail is also described in
[GPU Gems 2, Using Vertex Texture Displacement for Realistic Water Rendering](https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-18-using-vertex-texture-displacement).

**Jungle River 1: current-engine foam correction.** The supplied screenshot revealed an almost continuous layer of
fine white foam in the upper channel. The shipped `unofficial/Drewbacca/16x17 Jungle River 1.board` explicitly sets
the upper 20 channel hexes to `rapids:2;water:1`; the deeper reach drops the rapids flag. All 101 water hexes have
surface elevation zero. Consequently this is an authored torrent-to-ordinary-water boundary as well as a depth
transition, and the existing flow inference has no downhill direction for the map.

The focused shader fix keeps the terrain flags and current calculation. Rapids now use larger, faster-moving
detail for their local churn and broken foam patches; fine grain only breaks up patch edges. Foam coverage no
longer receives the old positive caustic contribution, and pale aeration is concentrated around those patches
instead of washing out the whole reach. The existing two-phase current advection and shared clock are reused.

Where a current direction exists, `BoardFlow` already gives ordinary water, rapids and torrents relative base
speeds of 1, 1.4 and 1.8. On this completely level map the correction improves local turbulent motion; it does not
invent a net downstream direction. A future change to direction inference needs its own terrain-based design.

Native captures on the RTX 4070 Laptop GPU show bright-pixel coverage in six sampled channel-interior patches
falling from 33.5% to 13.5% in the top view and from 29.7% to 14.0% in perspective. These are fixed-camera image
measurements, not the fraction of the whole map covered by physical foam. `GpuRapidsFoamSmokeTest` reproduces the
shipped map, compares ordinary water/rapids/torrents, and checks visible motion between frames. Its density check
fails with the original shader and passes with the correction. Captures and reports are in
`.work/water-foam/verified`; the original comparison is in `.work/water-foam/before-regression`.

Verification passed: four native GPU tests covering the new rapids regression and existing water/rain paths,
11 shader-source and flow tests, and Checkstyle for the added test. Both camera modes were exercised on the
RTX 4070 Laptop GPU; no cross-device or performance claim is made.

**Mountain Lake: rounded sloping water.** Preserve the original flowing bulge while softening the pinched bank
joins. Fully levelling the channel's cross-section removed too much of the water's volume. `BoardSurface` keeps
the rounded radial descent and applies a light, 20% correction toward the connected water levels. The banks,
surface and riverbed share this correction. Existing mouth and corner heights remain the seam constraints, and
the centre stays at its game height. Water mesh connectivity and vertex counts, picking ownership and the rule
distinguishing slopes from free falls are unchanged. Animated displacement remains part of the planned wave pass.

`BoardWaterSlopeTest.aDescendingStreamKeepsItsRoundedBulgeBetweenSofterBanks` requires a raised central curve in
the lower descent and bounds the bank shoulders, for one- and two-level drops in all six directions.
`GpuWaterSlopeSmokeTest` reuses the existing drop scenes and the shipped Mountain Lake (Savannah) geometry,
capturing both camera projections from above and from either bank. The review includes grass and sand materials.
Original captures are in `.work/water-slope/before`; the restored bulge is in `.work/water-slope/bulge`.

Focused verification passed: nine slope and flow tests, Checkstyle, and the native GPU review in both projections
and three angles on the RTX 4070 Laptop GPU. The bulge regression rejects the previous flattened geometry.
A small black bank artifact in the synthetic two-level scene also reproduces
with the original `BoardSurface` against the current terrain code; that separate finding is recorded in
`.work/water-slope/baseline-current-terrain` and is not resolved by this change.

**Mountain Lake: submerged cliff and rock continuity.** The reported missing bed at the 0910/1010 descent was
reproduced with sand materials, including the low oblique view. Water-hidden captures established that solid bed
geometry was present. The initial diagnosis caught dry shading on the submerged cliff and projecting rocks, but
missed a separate gap between the water surface and that solid wall. Drake's subsequent screenshot exposed this
remaining geometry defect. The terrain session's `SUBMERGED_CLIFF` and shared wall projection correction supplies the cliff's water
optics. This pass extends the same absorption and caustics to water-hex rocks in `GpuTerrain.sculptVertex` and
`terrain-sculpt.frag`, preserving their rock projection. The fractional surface height uses the unused rock-kind
colour range (blue bytes 224–254); ordinary dry rock stays at 255. Rock variation moves into occlusion so alpha can
carry the existing water palette. Mesh layout and draw passes do not change. The water's bulge is retained.

`GpuWaterSlopeSmokeTest` now captures five angles in both projections, including low views, and can hide the water
through `GpuReviewFrame` to expose the bed. Its native regression samples the submerged cliff, projecting rock and
central boulder, and checks that exposed sandstone remains dry. The previous rock shading fails that check; the
rock-optics correction passes. Before, intermediate cliff-only, and rock-optics captures are in `.work/water-slope/bed-before`,
`bed-verified`, and `bed-complete`, respectively. The intermediate `bed-regression/rock-limitation.xml` records the
remaining rock failure before the final correction.

Verified together on the RTX 4070 Laptop GPU: 13 geometry, wet-cliff, flow and shader-source tests; three native GPU
tests covering this regression, water/rain/splashes, and shader fast-path parity; and Checkstyle. Build outputs are
isolated in `.work/water-bed/build`. Performance and other GPUs were not measured in this pass.

**Water meeting the actual cliff.** The wet-cliff outline inherited the mouth anchors' beach setback. Following
the cliff's curvature alone left the surface above the inset bed edge, several world units away from the wall
at water height. `BoardSurface.meetWetCliffs` now places the surface rim on the drawn rock: it intersects the
existing submerged faces below the nominal bank level and samples the dry cliff triangles above it. The original
bed, shared mouth endpoints, surface heights and interior water rings are retained, including the rounded bulge.

The deep custom `test_board.board` revealed a further corner case. The wall's diagonal edges introduce bends in
its water-level intersection, especially where its bed rises into the dry bank. Connecting only the original
rim samples skipped those bends and left triangular gaps. The surface now traces those triangle intersections,
retains the intermediate contact vertices, and retriangulates flat pools. Graded water refines the affected level
rim intervals while keeping its interior rings. `waterBoundary(edge)` publishes this derived contour to the
water field, so foam, coverage and the rendered mesh use the same shoreline. Near-duplicate section vertices are
merged to avoid tiny reversed triangles. Rendering and picking continue to share the resulting water faces.

Final captures are in `.work/water-slope/contact-final`: Mountain Lake with grass and sand, five views in each
camera projection, plus four views of the custom deep pool with water and bed-only variants. Ray checks at both
reported custom-pool end wedges now encounter a water face before the submerged rock or bed; the previous mesh
had no water face there. The small square recess at the cliff end follows the existing solid terrain shape.

Verified together: 20 CPU tests covering contact, cliff joins, shared mouths, bulge preservation, flow and shader
sources; four native GPU tests covering the two map reviews, water effects and shader parity; and scoped
Checkstyle. The contact regression covers all six flow orientations, the shipped Mountain Lake descent, mixed
depth pools, and all three terrain detail levels, checking contour vertices and level-segment midpoints against
the actual wall triangles. The initial contact check failed on the previous geometry. No frame-rate or other
GPU compatibility claim is made. Large-body animated waves remain part of the improvement plan above.
