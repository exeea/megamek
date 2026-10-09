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
| `GpuOcean` | Evolves the shared wind-wave cascades on the GPU. |
| `GpuWaterWaves` | Resamples level water on a world-aligned lattice by LOD, without changing the canonical bed or picking surface. |
| `GpuWaterExposure` | Derives a coarse directional wind-fetch map; water running off the board edge continues beyond it. |
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
so direction changes remain continuous across hex boundaries. Only water open on
five or more sides at its own level is a lake's still body, together with the water
beside it; a river that widens to two hexes or runs through shallows stays open on
fewer sides and keeps its current.
Current direction uses height first: direct descents take priority, and higher
inlets feed the branches of a level receiving reach. A branch can continue off
the board while another drains into a lower pool. Without height evidence, a
narrow channel entering at the board edge feeds a wider lake/ocean; an equally
narrow level channel with no other evidence stays directionless. Lake bodies
keep their local waves rather than translating as a whole. Water and lava use
this same routing and the same continuous current-field sampler.
Graded streams keep a rounded descent along their flow; flattening it to a straight
interpolation removes the flowing bulge. Across the channel the water lies about
level. Mouth/corner heights remain fixed, and bank, bed and surface share the same
descent correction. A bank eases away from each corner's shared height over the
same share of its length (`PLATEAU`) that the surface eases inward from its rim.
Easing over only a third of it beside a descent's mouth tilted the water sideways
by up to about 60 degrees, and sky reflections traced pale lines along its sides.

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
an off-board fall has no invented receiving pool or landing spray. The cut shares
the surface's wave vertex program: its top vertices (colour G zero) follow the
waves at the same rest positions as the surface's border samples, so the section
shows the wave profile without a gap. Its bottom stays on the bed.

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

`GpuOcean` runs three 128×128 cascades over 250, 55 and 12.5 metre patches, side
by side in one texture, so each transform stage is one draw for all of them: 16
draws a frame. Each cascade owns one band of a fetch-limited JONSWAP spectrum
with Donelan-Banner spreading, cross-faded in power with its neighbours, plus a
low, narrow swell so calm open water keeps moving. Amplitudes are in metres: the
spectrum of a full gale (wind 1, about 20 m/s) gives roughly 3 m significant
height with 70 m crests, a moderate wind about 1.7 m, calm about 0.3 m of swell.
As art direction, a full gale is drawn so its tallest waves, crest to trough, reach
`GpuOcean.MAX_WAVE_HEIGHT`, so its crests fold and foam visibly from a tactical
camera. The gain grows with the square of the wind (`GpuOcean.storm`, `u_storm` in
`ocean-initial.frag`); calm water stays physical. Running water divides its
ripple band by the current gain (`GpuOcean.gain`, `u_waterStorm`), so a river's
short chop keeps its real height whatever the configured maximum. Scenario gravity sets both the spectrum's
amplitudes and its wave speeds. At zero gravity, `GpuTerrain.update` derives a
lunar presentation from the source tiles: every surface uses ROCK, vegetation
and scatter disappear (the tiles are `bare`, which `BoardScatter.allowed` also
denies the relief's field stones and shrubs), rough boulders give way to bedrock outcrops
([Rough terrain variants](gpu-rough.md#zero-gravity)), and all liquids, magma crust and ice are removed. Each
hex's positive water depth is subtracted from its elevation, and its depth/level
labels become the resulting ground level. Bridges gain that same depth in relative
height, including their height labels, so their decks stay at the original absolute
level. Rendering, picking and support use
this same snapshot after the existing terrain rebuild publishes it. Returning
to positive gravity restores the source terrain; game hexes remain unchanged.
Waves travel downwind at their deep-water speed; the smoke test checks
the direction. The choppy displacement gathers the surface towards each crest, the
more so the stronger the wind, so crests are sharp and troughs broad; the smoke
test checks that compression, and therefore foam, sits on crests and that a gale
reaches the configured height.

A change of wind never replaces the sea. `ocean-initial.frag` generates the new
spectrum on the GPU in one draw, from normal deviates fixed for the session, and
the spectrum pass blends from the old spectrum towards it over
`GpuOcean.TRANSITION_SECONDS` (8 s). Every wave keeps its phase and keeps
travelling: waves along the new wind grow, the others die away, and foam already
on the water drifts on and thins. A further change during a transition first
freezes the blend where it is, in one more draw, so the sea continues from how it
looks. Nothing is computed on the CPU per change, so dragging the wind sliders
costs two small draws a frame and no hitch. `GpuOcean.wind()` is the eased wind
the sea currently answers to; water shaders read it as `u_waterWind`, and
`u_waterDrift` integrates its travel, so ripples, gust patches, fetch weighting
and foam turn with the waves instead of snapping. Other wind-driven effects keep
the raw `u_wind`.

One finish pass writes, per cascade, slope, height and persistent foam, and for
the longest cascade the displacement and crest compression that move the mesh.
Foam seeds where the steepest crests fold, drifts downwind and thins over a few
seconds. It spreads mostly along the wind, so the simulation itself draws it out
into streaks, which follow the wind as it turns. How much of the sea shows whitecaps is
set directly, not left to how steep the configured sea is: `GpuOcean.foamCover`
gives the share of open sea showing whitecaps up close for a wind, none up to
`FOAM_ONSET`, then rising along `FOAM_CURVE` to `FOAM_COVERAGE` in a full gale.
The defaults keep the gale's foam as the fixed seeding they replaced drew it at
12 m and 1 g (26 %), and give some at half wind (about 3 %) where that had
almost none. The finish pass records how white each texel of open sea would
show, the surface's lace averaged over its noise, in the ripple result's alpha;
each frame a one-pixel pass (`ocean-foam.frag`) compares its mean, the smallest
mipmap level, with the target and moves the crest compression at which the
swell's and the chop's foam seed, both together. The share follows a change of
wind within about twenty seconds and holds whatever `MAX_WAVE_HEIGHT`, gravity
or choppiness; the smoke test checks it at half and full wind. The shader draws
foam dense where it is fresh and frays it into lace; from afar the lace fades
rather than hardening into patches. Detail maps stay fixed in the world: turning them with the wind would
sweep them across the whole board whenever it changed.

`waterWaveEnergy` (`water-wave.glsl`) gives each cascade's share at a point and is
used by the vertex and fragment stages alike. Long waves need depth, room across
the water and open water upwind (`GpuWaterExposure`); bays, narrows and shallows
keep only chop and ripples, and every cascade dies out at a real bank. Water that
reaches the board edge continues past it: the exposure map repeats the edge hexes
beyond the board, so a lake or sea that touches the edge has unlimited fetch across
it while a river leaving the board stays narrow. The shore field already treats an
off-board edge as a mouth, not a bank. Descents, falls and ice have no fetch, so
they keep their own current-driven surface. Lighting over the shallows keeps part of the swell and all of the chop
even where the geometry is flat, so shallow water never turns to glass. Waves fade
before elevation transitions, retaining the existing rounded stream descent. This
is a rendering approximation, not a hydraulic solver or a change to movement rules.

Running water shows its current in any light. A coarse detail layer carried by the
current alone (no world drift) shades broad, light and dark faces on level running water
deeper than the shallows, and drifts clearer and siltier patches of the water's own
colour past, so a river reads as flowing even in calm air and without a sun glint.
Its banks take no lake surf, surge or wind-drifted foam band: only the band's grain
drifts downstream. The depth-0 shallows, descents, lakes and falls keep their own
look. Running water is too narrow for the wind to raise a sea on it. Above a light breeze
(`waterWindShare`) a level reach takes only the ripple band, the short steep chop
of a short fetch, read where its current has carried it (the same two restarting
phases as its detail maps), so the chop travels with the flow plus the wind; in a
gale its highest crests break into small flecks. In calm air a river shows only its
current's own ripples, so an opposing wind never makes it appear to run backwards.
A descent keeps its own current-driven surface: its mesh normal, exactly up on
level water, tilts from the lip, and the tilt both removes the wind's chop and
broadens the sun's glint, since water running down a slope is broken. A mirror-like
glint there would trace a thin static line along the descent's curve. Bed caustics
and the refraction sway of a submerged bed follow neither wind nor current.

Only the longest cascade displaces geometry; shorter ones exist in the normals.
Shading separates a broad normal (swell and some chop), which lights the waves so
they read from a tactical height, from the full normal used for reflection and
glitter. Crests glow turquoise with light scattered through them, strongest when
looking towards the sun. Air/water Fresnel uses a 0.02037 normal-incidence
reflectance and a fifth-power angular response.

The FFT targets belong to the renderer, rather than individual hexes. Rapids keep
their existing current-driven patches. The **Water effects** control under Tuning →
Terrain (on by default) disables water effects: the simulation stops, waves no
longer displace the surface, and currents, foam, ripples, glints, wakes and bed
caustics no longer draw. If setup fails, water keeps its static ripple fallback. Game
elevation, picking and unit support remain canonical.

Water first establishes its nearest boundary, displaced surface or board-edge
section, in a depth-only pass into a target of its own (`GpuWaterDepth`), then
blends its colour over the already-rendered bed and units, discarding any fragment
behind that nearest boundary (`waterHidden` in `water-uniforms.glsl`). A section
therefore shows only where it is the first water a ray meets: the far side of a
zig-zag board edge faces the camera too, but is seen through the surface or the
near section. Both passes use the same displacement program (`invariant gl_Position`)
and cached mesh ranges, preventing rear waves from blending through foreground
crests at grazing angles. Fog stops at the nearer of the scene depth and that
boundary. The scene depth itself keeps the bed and units, so unit outlines,
tactical overlays and weather never see the moving waves. Reflections still sample sky/cloud lighting; this pass does not add
screen-space scene reflections, refracted scene captures, wave collision or
buoyancy.

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

Level pools are resampled on a world-aligned triangular lattice, 3/6/12 metres
at full/medium/coarse LOD, retaining every boundary sample so neighbours share
their edges. Concave bays keep only triangles inside their outline and fall back
to the canonical faces if the triangulation does not follow it. Sloping surfaces
retain their canonical triangulation. Distant convex pools remove redundant
collinear boundary samples after displacement has faded; an open hex then needs
four triangles. The fade honours configured
LOD thresholds and hysteresis. Shared indexed vertices reduce vertex traffic,
and water shading uses each pixel's projected scale rather than a page centre.
