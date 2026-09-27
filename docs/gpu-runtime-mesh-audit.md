# Runtime mesh export audit

The deployed and authoring/reference G3DJ inventory is now migrated to GLB:
4,056 files under `mm-data/data/` and 475 under `mm-data/tools/`.
This audit covers geometry
still constructed by Java, separately from loading or instancing those assets.
Following the audit, the fixed missile body was exported. Attack glows and
explosions now use shaders, so their former sphere asset has been removed.

| Geometry | Current source | Assessment |
|---|---|---|
| Explosion and smoke coverage | `GpuExplosionEffects`, unit cube with eight vertices and twelve triangles | Generated and uploaded once per renderer, reused for every volume, and disposed with the renderer. Position, radius and wind stretch are uniforms; the visible shape comes from the volumetric shader. |
| Tracers, energy balls, flares, spray, screens and contact glows | `GpuEffectBatch` with `projectiles.frag` | Shader ribbons and billboards share a bounded batch. Its mesh, index data and CPU vertex buffer are allocated once and reused; moving positions and attack phases are updated each frame. |
| Missile body | `models/effects/missile-body.glb`, four triangles, one `missile-body-lod0` group | Exported with the original local coordinates and face colors. `GpuMissileEffects` loads the template once; placement and shared dynamic batching remain runtime. |
| Grass blades | `GpuGroundCover`, three independently bent blades per sampled tuft | Positions, blade dimensions, colors and wind attributes vary with terrain and deterministic sampling. A reusable blade template is possible, but the assembled mesh should stay runtime. |
| Building interiors | `GpuBuildingInterior`, columns and floors clipped to the building footprint and scaled to its story count | Keep generated. They depend on the selected building and game height; exporting all combinations would duplicate geometry. |
| Sprite cutouts and spinning map symbols | `GpuCutout`, `GpuUnitModel`, `GpuMarkers` | Keep generated. Geometry follows image alpha, selected artwork and atlas UVs, including user-provided art. |
| Ground, cliffs, rims, slopes, roads, water and tactical surfaces | `BoardSurface`, `GpuTerrain`, `GpuRoads`, `GpuWaterfall`, `GpuHexSurface`, `GpuFireControl` | Keep generated. Geometry depends on the board, elevation, adjacency, roads, selections and animation state. |
| Particle, beam and exhaust quads; full-screen passes | `GpuEffectBatch`, atmospheric/weather/water rendering | Keep generated. These are tiny render primitives with per-frame positions or shader inputs, rather than authored scenery. |

Rocks, bushes, scatter stones, scatter grass/plant templates and complete plants
already load their authored geometry from GLBs. Runtime scatter still transforms
and batches those templates to fit the board; that is placement, not a competing
source of rock shapes. Small scatter stones now use sixteen distinct
eight-triangle meshes with broad crowns or ridges and open bases.

Recommendation: keep the remaining generated meshes. Their geometry depends on
board state, artwork, placement or animation rather than an independently
editable object. The missile body remains an editable asset. Fixed coverage
geometry and batch storage are reused; no frame-time improvement is claimed.

The explosion shader integrates rolling density, localized hot emission and
locally self-shadowed smoke along a view ray. Destruction, munition impacts and
interception bursts share one expanding, rising fire-to-smoke volume per blast;
cooling and dissipation follow the existing attack clock. Dissipation reduces
optical density, and smoke absorbs the background instead of adding gray light.
The cached cube provides conservative raster coverage. The same
scenario/tuning wind used by board weather drives downwind drift, stretching,
internal advection and thinning. Drift grows as the blast cools; zero wind leaves
only buoyant rise. This is a normalized visual response scaled to the blast's
radius and age, not a physical wind-speed or fluid solver. It uses no independent
wall clock or accumulated particle state.

The renderer copies scene depth once per active frame for opaque clipping and
reconstructs rays from the near plane for both camera types, including when the
camera is inside a volume or its far plane intersects the coverage mesh.
Smoke uses atmosphere lighting; fire remains emissive.
Its 128-volume limit and 32 integration steps bound the work, but frame-time
impact on large battles has not been benchmarked. It is a procedural visual
effect, without fluid simulation, inter-volume self-shadowing or lighting cast
onto surrounding models. Overlapping volumes use back-to-front alpha blending.
Missile trails retain their existing bounded shader-driven billboard batch.
Tracers use soft ribbons, energy balls use animated plasma cores and halos, and
flares use warm radial glows. Their flight paths and timing still come from
the existing attack playback; rendering does not alter weapon resolution.

Ballistic impacts produce only spark streaks and a contact glint, without
explosion fire or smoke. Spark count grows with rack size (AC/2: 6, AC/5: 9,
AC/10: 14, AC/20: 24), capped at 32 per contact. Wider streaks and their outward
travel scale with the square root of rack size, using the same reusable
projectile batch. Each MG round gets its own 130 ms pulse at arrival:
the existing normal and rapid-fire playback still emits six and eighteen
cosmetic rounds, respectively. Missiles retain their explosions. The visible
combat event captures the game's conventional-infantry classification; those
troopers do not spark, while Battle Armor does. Terrain impacts use the existing picker's
struck surface: rock, concrete, exposed rock faces and boulders can spark.
Detached mech parts are also identified as hard surfaces. Grass, soil, sand,
snow, ice, water, trees and unidentified props do not produce terrain sparks.
Each attack caches its impact material after the first lookup, so playback
does not repeat terrain picking every frame. This uses existing surface
families and feature types, not a general material analysis of custom assets.

`GpuExplosionSmokeTest` renders a labeled lifecycle sheet and verifies both
camera types, full/partial opaque occlusion, near/far-plane clipping, background
absorption, density fade, paused playback and destruction/cancellation cleanup
in a native OpenGL context. A second sheet compares calm/light/strong wind and
opposite travel directions; assertions check drift strength, bearing, zero-wind
invariance and deterministic paused rendering.

`GpuProjectileSmokeTest` renders all eight projectile shader kinds in both
camera types and checks opaque occlusion, paused repeatability, plasma phase,
color, end-on tracer visibility and clearing. It also checks rack-size scaling
of submitted spark count and visible size, sparks-only ballistic contacts,
successive contact pulses in both MG modes, retained missile explosions, conventional-infantry suppression,
Battle Armor, and hard versus soft terrain misses. Geometry and event-capture
tests verify the picked material and target classification. Existing volley,
machine-gun and defensive playback tests
exercise the production attack paths.
