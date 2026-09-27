# Terrain fire and smoke

Persistent fire and smoke now use a volumetric shader in the GPU board view. They retain the original ground below them.
The classic Swing board still uses its tileset artwork.

## Map data and ownership

Maps store fire and smoke alongside other terrain entries, plus an optional theme. Shipped examples include:

- `SOLARIS ARENA - The Maze.board`: `pavement:1;smoke:1`.
- `SOLARIS ARENA - The Pit.board`: `woods:2;fire:1;pavement:1;foliage_elev:2`.
- `SOLARIS ARENA - Jailbreak Fixed.board`: buildings with `snow:1:0;smoke:1:0`.
- `32x17 Holth Forest (CGB).board`: burning hexes with the `desert` theme.

`BoardFeatures.surface()` remains the single material classification: explicit terrain and the theme select grass, dirt,
sand, rock, concrete or snow. Grass is the default for an otherwise unclassified clear hex; the format does not always
specify a literal ground material. Fire and smoke do not replace this classification or the captured ground artwork.

`GpuBoardSource` captures the existing `FIRE` and `SMOKE` levels into immutable `BoardFireSmoke` data in each scene tile.
Tactical repainting, visible-area changes and playback checkpoints preserve those fields. Removing an effect from the
game removes its render source. The GL thread never reads or changes `Hex`, smoke clouds, fire spread, LOS or damage rules.

Normal and all three inferno fire types are retained. Light/heavy and laser-inhibiting light/heavy smoke have distinct
density, with separate palettes for laser-inhibiting, green and chaff smoke. These are visual choices, not new game rules.

## Rendering

`GpuTerrainEffects` owns a cached noise texture, reusable streaming mesh, shaders and a half-resolution RGBA target.
Each affected hex contributes one analytic volume. Its base comes from the existing `BoardSurface` triangle sampler
(or the water surface), and its height includes burning tree/building height. A widening frustum is only rasterization coverage;
the shader determines the visible flames and billows. On a bare hex the flame height limit is three terrain levels
(3.75 for inferno); the smoke limit is eight. Both add the captured height of burning trees/buildings. Flames vary below
their limit with turbulence, independently of the smoke column. Smoke-only hexes use the eight-level column too.

- Each source has a compact density kernel, and overlapping smoke accumulates optical depth. Shared world-space
  turbulence continues across hex boundaries, including three-hex corners and different LODs. The previous normalized
  neighbor field and its minimum density are gone: they flattened out variation and kept the plume looking solid.
  No neighbor attributes or six-neighbor searches are required by the renderer.
- Every source retains its ground height, plume height, fire intensity, smoke density and palette. The next board
  snapshot removes extinguished sources. A clear hex never acquires a ground source through a join, although rising
  or wind-blown smoke can pass over it. Expanded plumes can also meet beyond their immediate board neighbors.
- Smoke's support radius grows from 0.6 hex widths at the base to 2.4 times that radius at its eight-level height limit.
  Density falls as `(1 - z)^2 / (1 + 1.4z)^2`, with normalized height `z`, and turbulent erosion opens gaps between
  billows. There is no positive density floor. The visible plume therefore gets wider and thinner well before the cap.
- Fire has its own narrower, tapered field with upward advection, warped noise, smaller turbulent detail and a varying
  hot core. Extending the smoke no longer stretches the flames or fills the whole base with one uniform yellow band.
- Soot from burning tiles uses 35% of the corresponding smoke-only density. This is a presentation choice, independent
  of camera angle, so the flames remain readable from above without changing game smoke/LOS rules. Standalone smoke
  retains the light/heavy density distinction. Both kinds spread and dissipate with height.
- Short light samples shade the smoke locally; scene atmospheric lighting also affects it.
- Wind uses the same travel bearing and normalized strength as board weather. The plume starts more upright and bends
  farther downwind with height. Its frustum includes the curved trajectory; wind also advects the turbulent detail.
- Advection is integrated and periodic; changing wind does not teleport the noise field. Plume bending turns smoothly.
- The existing playback pause/speed supplies animation time. Camera changes do not reset it.
- Both orthographic and perspective cameras reconstruct rays from the actual inverse projection, clipped at the near
  plane, far plane and opaque scene depth. This also supports a camera inside the volume.
- Premultiplied alpha lets smoke darken the background and fire emit light. Depth-aware upsampling preserves foreground
  silhouettes. The pass runs before the board atmosphere composite and tactical overlays.
- The atmosphere composite continuously replaces uncovered sky color and grades partial coverage. Faint smoke against
  the sky therefore fades cleanly instead of switching the entire pixel to the opaque-scene sky color.

The existing unit-outline pass also uses this frame's clipped effect opacity. Dense smoke/flame can trigger the same
team-colored outline and faint fill as a terrain obstruction. The existing See-through strength setting still controls
it. Only units/contacts already eligible for the normal outline capture are considered; this adds no visibility rules
or hidden-unit models. A depth-matched opacity sample prevents smoky background beside a thin limb from falsely
outlining a foreground unit. Removing or culling every effect exposes no stale mask, and no extra unit geometry capture
or full-resolution effect buffer is added.

`GpuEffectDepth` provides one lazy depth copy shared with combat explosion volumes in a production frame. The copy is
safe to sample while drawing into the scene framebuffer. A frame without visible volumes does not allocate or copy depth.
Resources are disposed with the board view; target resizing recreates only the affected textures/framebuffer.

## Three LOD levels

Selection reuses the existing three-level board-prop size thresholds and 10% hysteresis (`TreeLod.level`), in framebuffer
pixels. The same volume shape, wind, type and optical-density integration remain at every level; effects are not deleted
at a distance.

| Level | Nominal projected diameter | Maximum ray samples | Detail |
|---|---:|---:|---|
| `lod0` | 80 px and above | 24 | Detailed integration and local smoke shading |
| `lod1` | 24–80 px | 12 | Coarser integration and local smoke shading |
| `lod2` | Below 24 px | 6 | Coarse integration and inexpensive smoke lighting |

All levels evaluate the same density and flame fields; only integration and lighting cost
change. LOD uses the core diameter, excluding the soft joining margin, so adding a neighbor does not change the LOD.
Rays crossing both the lower and upper plume divide their existing sample budget between the two. This preserves
flames under a taller smoke column even in distant top-down views; the maximum budgets remain 24/12/6.

Volumes outside the frustum are skipped before GL resource preparation. Visible volumes are sorted back to front and
uploaded in batches of up to 256, with one final composite draw. There is no hard source limit that would silently hide
hazardous hexes. A small periodic two-channel noise lookup replaces repeated arithmetic noise evaluation; opacity can
terminate a ray early. There is no per-particle simulation or per-frame mesh construction/allocation.

## Verification

`BoardFireSmokeTest` covers all fire/smoke encodings, wind integration and pause, LOD hysteresis, and real Swing capture
over pavement, snow, sand, mud and woods. It verifies that ground/artwork is preserved, duplicate flat effect artwork is
absent, and tactical repaint/removal does not lose or retain stale effects.

`GpuTerrainEffectsSmokeTest` compiles/renders on a native OpenGL context and checks both cameras, all LODs, deterministic
paused output, wind direction, background absorption, full/partial opaque occlusion, depth-aware upsampling, camera
planes inside a volume, offscreen culling and effect removal. It also renders all six ground materials through the
production terrain/atmosphere path and writes a GPU timing report. Joining checks cover both column parities, all six
directions at every LOD, three-hex corners, a ring with a clear center, removing a connecting source, four wind bearings,
mixed fire/smoke types, a perspective field spanning LODs, and fields crossing the 256-source batch boundary.
Side-view probes verify height, increasing horizontal spread and progressive dilution at every LOD. Top-view checks
verify that heavy smoke over a burning tile preserves visible flame pixels. Opposite wind bearings move the upper plume
in opposite directions. Real Atlas geometry exercises smoke/fire outlines, foreground rejection, absence from the
visible-unit list and clearing the effect mask. An animated board capture checks that advancing time changes the plume.
`GpuExplosionSmokeTest` previously verified the shared depth refactor against existing combat effects.
`GpuAtmosphereSmokeTest` passes all three tests after the partial-sky-coverage correction.
The separate `GpuUnitVisibilitySmokeTest` still fails its own-hex ground/feet exemption assertion (366 summed RGB
difference). Running its original renderer and shader without the smoke-outline changes produces the identical failure;
this is a verified baseline issue, not a passing check. The new smoke/flame outline, foreground rejection and stale-mask
checks in `GpuTerrainEffectsSmokeTest` pass. Main and test checkstyle checks pass.

Review artifacts: `megamek/build/gpu-board-review/fire-smoke/`, including `board-surfaces.png`, `board-top.png`, wind/camera/LOD captures,
`partial-occlusion.png`, `joined-*.png`, `rising-*.png`, `rising-fire-smoke.gif`, unit-outline captures and `timing.txt`.
The GIF is an actual engine render; its four-second preview repeats, rather than representing a seamless simulation loop.
The timing test uses 1920×1080, 20 warmup frames and 80 measured GPU timestamp
intervals per case. It includes the depth copy, half-resolution volume pass and upsampling; it excludes terrain, units,
UI and readback. Results are hardware- and coverage-dependent, not a frame-rate guarantee.

Measured on an NVIDIA GeForce RTX 4070 Laptop GPU with expanding, overlapping fields:

| Level | Visible burning/smoking hexes | Draw calls | GPU median | GPU p95 | CPU median |
|---|---:|---:|---:|---:|---:|
| `lod0` | 96 | 2 | 2.45 ms | 2.46 ms | 0.12 ms |
| `lod1` | 384 | 3 | 1.24 ms | 1.25 ms | 0.14 ms |
| `lod2` | 1,536 | 7 | 0.49 ms | 0.50 ms | 0.25 ms |

Other development processes were active during the run. These measurements demonstrate this workload on the available
GPU, not performance on integrated GPUs or other systems; they are not a controlled comparison against earlier versions.

## Limits

This is procedural volume rendering, not a fluid or combustion simulation. Wind changes appearance while the game alone
moves or spreads terrain hazards. The source height is sampled at the hex centre; depth clipping handles terrain and
opaque objects within the volume. It does not simulate surface-specific fuel chemistry, melting snow, or persistent
scorch damage. Smoke has local shading from its own field, but does not cast scene shadows;
fire does not add dynamic lights to terrain or units. Intersecting transparent volumes use centre-depth sorting,
and other transparent effect systems retain their existing draw order. Fine detail is intentionally reduced at distance.
