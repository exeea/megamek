# Water, shores and flow

Water consists of solid bank/bed geometry, a transparent surface, and effects at
falls or moving units. The board supplies liquid type, elevation and depth;
the renderer derives appearance and currents from those facts.

## Where to change what

All Java classes below are in `client/ui/clientGUI/boardview/gpu`.

| Owner | Responsibility |
| --- | --- |
| `BoardLiquid` | Captures liquid family, theme and appearance from a rules hex. |
| `BoardSurface` | Constructs the shore, bed, sloping streams and boundaries used by rendering and support queries. |
| `BoardRelief` | Supplies the adjoining land/cliff geometry and shared corner positions. |
| `BoardRiver`, `BoardFlow` | Derive connected stream paths and continuous visual current directions. |
| `GpuWaterShader.Field` | Packs depth, shore distance, flow and agitation into a chunk-owned texture. |
| `GpuWaterfall` | Builds falling sheets, their receiving-pool contacts and spray geometry. |
| `GpuOcean` | Evolves the shared wind-wave texture on the GPU. |
| `GpuWaterWaves` | Samples render-only wave geometry by LOD without changing the canonical bed or picking surface. |
| `GpuWaterExposure` | Derives a coarse directional wind-fetch map from water coverage and elevations. |
| `GpuWaterPages` | Shares field atlases and indexed meshes across fixed spatial pages. |
| `GpuWaders`, `GpuWaterImpacts` | Add posed-unit water contacts, wakes and entry spray. |
| `GpuTerrain` | Prepares and owns meshes/fields, assembles water programs and orders their rendering. |

Change a bank silhouette in `BoardSurface`/`BoardRelief`; change color,
transparency or foam in the water shaders. Changing shader depth cannot fix
incorrect bed geometry. [Ice](gpu-ice.md) and [magma](gpu-magma.md) have separate
material and visibility contracts.

## Shore and bed geometry

The nominal water level follows the hex elevation with a small rendering
clearance. Positive game depth lowers the bed by that many terrain levels;
zero-depth water has a shallow visual recess. The center's depth anchor remains
fixed when shaping a bank.

`BoardSurface.shore` derives a continuous boundary from nearby land, connected
water and hard construction. Its `SHORE_*`, `BEACH`, `HUG` and `PLUNGE_POOL`
constants control boundary shape and bank profile. Shared mouths and corner
positions must agree between neighboring hexes, including across chunks. Shore
queries can depend on the second ring of neighbors: an edit must invalidate
those dependents as well as the changed hex.

Cliff classification includes the depth beneath the waterline. A bank two levels
above water with a one-level-deep bed presents a three-level wall.
`BoardRelief.DEFAULT_CLIFFS_INTO_WATER` controls whether detailed natural cliffs
meet the water directly or retain the normal beach/setback. When enabled, the
cliff-foot contour must feed both the surface mesh and the shore-distance field.

`BoardSurface.meetWetCliffs` intersects the actual submerged/dry wall triangles
at water height. It retains intermediate bends in that contact contour, rather
than connecting only the original rim samples. Flat pools are retriangulated;
graded surfaces refine affected rim intervals while retaining their interior rings.
`waterBoundary(edge)` publishes the derived contour to the field, so foam and
coverage follow the drawn shoreline. Near-duplicate vertices are merged to avoid
tiny reversed faces.

Submerged cliffs and projecting rocks also need the shared water optics.
`GpuTerrain.sculptVertex` and `terrain-sculpt.frag` encode the water palette
and fractional surface height while retaining the rock projection. Dry portions
must remain dry; a material correction cannot cover a geometric shoreline gap.

Adjacent water hexes at the same surface level can have different depths. They
share mouth profiles and slope beneath the surface toward their own depth anchors.
Picking and unit support use the canonical surface; `BoardGeometry.footprint`
also accounts for displaced shore corners. A visible waterline change therefore
needs corresponding footprint, support and field updates.

## Streams, falls and board edges

Connected water descends continuously over one- and two-level steps; larger drops
form falls. The stream surface, its bed and the adjoining banks share the same
mouth geometry. `BoardRiver` supplies the path and `BoardFlow` supplies currents,
so direction changes remain continuous across hex boundaries.
Graded streams retain a rounded cross-section; flattening it to a straight
interpolation removes the flowing bulge. Mouth/corner heights remain fixed,
and bank, bed and surface share the same descent correction.

Rapids/torrents increase current speed where a direction exists and add local
churn and broken foam through the shaders. A completely level reach without an
inferred outlet can churn without a net downstream direction; the rapids flag
alone does not specify a flow bearing.

`GpuWaterfall` shapes a sheet between the upper crest and its landing. Receiving
pools use nearby landing segments to place foam and agitation, including a fall
that lands beside a hex corner. Water surface, fall, spray and cut faces use
separate shader modes because they have different opacity and lighting needs.

`BoardSurface.FALLS_OFF_THE_BOARD` optionally extends outgoing rivers below the
board edge. It is disabled by default. Ordinary exposed edges show a cut surface;
an off-board fall has no invented receiving pool or landing spray.

## Color, waves and interaction

`water-surface.frag` handles open water. `water-optics.glsl` supplies the common
depth-dependent absorption/scattering used by the surface and submerged ground,
while `water-lighting.glsl` supplies reflection and light response.
`water-interactions.glsl` and `water-pool.glsl` add local contacts and pool motion.
`water-fall.frag`, `water-spray.frag` and `water-cut.frag` handle their named forms.

Clear, Mars, volcanic and hazardous water use palettes selected by
`GpuWaterShader`. Compatible neighboring water at the same level blends optical
inputs; ice and magma do not become ordinary water through that blend.
`USE_PROCEDURAL_WATER` selects depth-derived appearance; the authored animation
path is retained through `GpuLiquidShader`.

`GpuOcean` runs one 128×128 inverse FFT over a 64-metre patch. A single finish
pass writes two mipmapped outputs: slope/height/foam and horizontal
displacement/height/compression. Geometry, crest light and foam follow the same
evolving surface. A weaker, rotated long swell samples that same simulation to
break visible repetition; it adds no simulation passes. This is a visual
superposition, not a second ocean simulation.

Wind changes the spectrum, with gentle residual motion in calm exposed water.
`GpuWaterExposure` measures fetch in four directions on a coarse board lattice;
depth and shore distance further damp waves in shallow water and narrows.
Waves fade before elevation transitions, retaining the existing rounded stream
descent. Board-edge sections remain fixed. This is a rendering approximation,
not a hydraulic solver or a change to movement rules.

Breaking crests seed foam which drifts, disperses and decays. Rapids keep their
existing current-driven patches. Foam does not cover all shallow water merely
because its bed changes depth. Air/water Fresnel uses a 0.02037 normal-incidence
reflectance and a fifth-power angular response; filtered specular highlights
and restrained crest transmission replace the previous amplified facets.

The FFT still uses 16 small full-screen draws, shared across the entire board.
Its targets belong to the renderer, rather than individual hexes. Disabled water
effects skip the simulation. If setup fails, water keeps its static ripple
fallback. Game elevation, picking and unit support remain canonical.

Water first establishes the nearest displaced surface in a depth-only pass,
then blends its colour over the already-rendered bed and units. Both passes
use the same displacement program and cached mesh ranges, preventing rear
waves from blending through foreground crests at grazing angles. Reflections
still sample sky/cloud lighting; this pass does not add screen-space scene
reflections, refracted scene captures, wave collision or buoyancy.

Unit contacts follow the animated model, not just the unit's hex center.
`GpuWaders` supplies waterline interaction for posed units; `GpuWaterImpacts`
observes falls and attached-suit releases to emit bounded spray and foam. Frozen
and molten terrain are excluded from ordinary water-entry effects.

## Detail and resource ownership

`BoardSurface.renderBed` can omit intermediate radial rings at lower terrain
detail when the new planes stay within the height-error bound. Shorelines,
shared mouths, material boundaries and the center anchor remain fixed.
Shallow exposed bars, descending streams, ice and molten terrain retain their
complete bed. Normals are computed from the canonical bed before material splits,
so changing render detail does not create lighting seams.

Each chunk owns its water field. Reused meshes in a replacement chunk must bind
the replacement field through `GpuWaterShader.withField`; retaining the retired
chunk's texture causes stale flow or disposed-resource errors. Water and lava
have separate fields. Terrain changes require geometry/field invalidation;
wind and time normally update uniforms and the wave texture.

Compatible procedural surfaces share a world-aligned field atlas within each
4×4-chunk page. A page borrows source fields, retains compact source pixels for
repacking, and owns its copied mesh and atlas. It drops atlas CPU pixels after
upload. Lava does not retain these pixels. Authored GIF water, waterfall sheets,
spray, board-edge sections and local landing impacts keep their own materials.

Pages include invisible source chunks. Camera movement selects existing index
ranges and does not repack fields or rebuild geometry. Replacing a source chunk
through edits or LOD invalidates its page. At most one page is built per frame;
the original meshes draw while others await preparation. Partially visible
pages preserve chunk culling and may require several contiguous draw ranges.

Flat convex pools use a regular interior point grid, retaining their boundary
samples through full/medium/coarse LOD. Interior spacing is 4/6/12 metres.
Sloping and concave surfaces retain their canonical triangulation. Distant
convex pools remove redundant collinear boundary samples after displacement
has faded; an open hex then needs four triangles. The fade honours configured
LOD thresholds and hysteresis. Shared indexed vertices reduce vertex traffic,
and water shading uses each pixel's projected scale rather than a page centre.

See [the water quality measurements](benchmarks/water-quality-2026-09-29.md)
for native captures, draw counts, measured costs and validation limits.
