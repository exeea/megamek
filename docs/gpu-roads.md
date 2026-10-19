# Roads and ramps

[Docs index](README.md) · [Terrain geometry](gpu-terrain-materials.md)

Roads have two cooperating parts: a footprint/material overlay and the ground that
supports it. A painted road does not own a second height field. Rendering, picking and
unit support must continue to agree on the underlying surface.

## What does what

| Code | Responsibility |
| --- | --- |
| `BoardScene.Tile.road / roadExits` | Carry captured ground road kind and exits. `railBed` comes from ground rail terrain; `wayExits` (road or rail) shapes the carriageway. `deckRoad / deckRoadExits` and `corridor / deckCorridor` keep the two layers separate. |
| `BoardRoad` | Build carriageway/junction footprints, end shapes, wear coordinates and obstacle-clearance queries. |
| `BoardSurface` | Shape the physical approach between plateau, neighboring road and bridge deck. |
| `BoardRelief / BoardConcrete` | Join the approach to natural cut/fill or engineered retaining walls. |
| `BoardRampMesh` | Simplify interior ramp geometry while preserving boundary joins and height constraints. |
| `GpuRoads` | Rasterize coverage/control masks and draw them on existing supporting triangles. |
| `GpuAssets / terrain-road.frag` | Supply material maps and shade asphalt, dirt, gravel, paint, deposits and wear. |

## Footprints and materials

Normal roads use marked asphalt; alleys use unmarked asphalt. Dirt and gravel have
porous margins exposing the local ground. All use a common carriageway width, so a
material change must not pinch the route. Two exits form a bend, three/four form a
junction, and five/six form a roundabout. Dead ends extend through the center but retain
a ground margin before the opposite hex; an exit-free road uses a short isolated stretch.

At a 120-degree connection with square border crossings, roads and bridges use
`RailRoute.course` across the whole hex. The shared broad curve keeps the deck and
its side structure centered around the track. Oblique crossings keep their shared
neighbor-derived tangents, and tighter adjacent-exit turns keep their own layout.
Three/four-way junctions retain their radial approaches and add the same broad curve
at full carriageway width for every legal gentle pair of exits, including pairs used
only by explicit rail layouts. Their tyre wear retains the radial approach courses.
Rail and road use that same course before applying an authored beside-corridor offset.

Roundabout approaches use a two-pixel fillet where they meet the circulating road.
The closing operation erodes only exposed boundaries; internal seams between filled
`Area` subpaths must not cut into the road and leave pointed kerbs. Generated bridge
outlines follow this same footprint.

### Junction controls

Three/four-way paved junctions use solid crossing hubs and rounded inside corners.
White stop bars cross each incoming right-hand lane before the shared turning area;
yellow centre dashes stop before those bars. Unmarked roads and five/six-way
roundabouts retain their existing marking policy. Road/rail corridors keep the bars
out of the central railway median.

The road brush and the ground/deck road inspector offer **Junction controls: Stops**,
**Stops + traffic lights**, or **Stops + overhead traffic lights**. Stops uses an
octagonal roadside STOP sign; traffic lights replace the sign with a three-aspect
signal. The overhead option uses a curved metal mast arm carrying two overhead
heads and a lower roadside repeater. All modes keep the stop bars.
The choice belongs to the `road` or `bridgeRoad` appearance. Overhead traffic lights
are the default and need no stored field; explicit alternatives store
`trafficControl: stops` or `trafficControl: signals`. Material
changes, copy/paste, layer moves, save/load and undo preserve that choice.

Legacy `.board` crossings use the same gantry default. Native boards retain explicit
authored choices; roads without one inherit the default. Controls are derived from the same
approach geometry as the paint, use the existing ground/deck support and GLB prop
pipeline, and disappear when a junction is removed. They are cosmetic terrain
decorations, not stored or independently selectable objects.
At a crowded road/rail junction, a pole is omitted if its roadside position has
no room inside the owning hex; the stop bar remains. This prevents controls from
standing beyond a bridge deck or on an unrelated neighbour's terrain.

The Blender-authored assets are `scenery/road/traffic-signal.glb` and
`scenery/road/stop-sign.glb` in mm-data. Each contains the existing `-lod0` and
`-lod1` groups: detailed nearby models (416/96 triangles) and simple distant
models (36/40 triangles), selected by the normal prop LOD system. Both signal
levels flash yellow in out-of-service mode, with red and green off. This
animation is cosmetic and does not change movement rules.

`GpuTrafficSignal` selects the amber materials; `GpuModelLight` supplies reusable
emission intensity, blink period (zero for steady light), and optional diffuse
dimming while off. Its immutable attributes survive prop LOD switches and instance
batching, while draw setters sample the existing terrain clock. `model.frag` only
scales the material's authored emission and diffuse colour through `u_modelLight`;
it contains no traffic-specific timing or colours. Ordinary materials retain their
authored emission. These controls affect the visible material, not illumination of
neighbouring surfaces.

The overhead option adds `scenery/road/traffic-signal-gantry.glb`, also with native
`-lod0`/`-lod1` groups (1,800/212 triangles) and the same animated `Amber` material. It is a single-footing
cantilever: the origin is the supported roadside base, +Y faces waiting traffic,
and +X reaches over the incoming lane. The near model includes the curved steel
tube, base fixings, brackets and lamp visors; the far model keeps the arch and
all three signal heads. The existing prop system owns placement, LOD and blinking.
Placement keeps the authored proportions wherever both overhead heads fit the
incoming lane. Limited horizontal fitting preserves the vertical vehicle clearance;
if the lane or shoulder cannot accommodate the complete assembly, that approach
uses the ordinary roadside light. Gantry footings must fit wholly on the owning
hex's road shoulder, including on bridge decks.

Rails share the carriageway (batch 10, docs/3d-board-editor.md): every shape and
clearance site reads `wayExits` and `BoardRoad.carriageway` (a drawn road or a ground
rail on detailed, dry ground), so a rail-only hex is shaped exactly as the road hex
with the same exits (hub, ramps of one or two levels, concrete cuts, retaining walls,
the ramp onto a deck), and the rail's track is draped on the faces road paint lies on.
Paint, the natural blend gate, tunnels and the tactical art stay road-only, and only a
road votes a bridge deck's material. A board without rails renders unchanged
(`wayExits == roadExits`).

`BoardRoad` is also the clearance source for boulders, trees and vegetation. The
relief's own rim, talus and field rocks and shrubs may stand beside a road but never
on its carriageway or shoulder (`BoardRelief.place`).
Changing width or a junction therefore affects more than its rendered mask.
Markings follow the main paved route through unmarked branches. Wheel wear follows
the lane centres through bends, endings and material changes.

## Road and rail corridors

A corridor exists only on reciprocal road/rail sides that share the same reachable
layer. Ground roads and traditional rails use their shared, non-stranded sides;
submerged ground and five/six-way road roundabouts do not form corridors. On a deck,
the candidates are the intersection of `bridge_road`, `bridge_rail:1` and bridge
exits. Deck type and distant bank connectivity do not gate a corridor. Ground and
deck values are captured independently, and off-board shared exits retain full width.

The default **Middle** layout has a central ballast strip and a 2.8-metre lane on
either side: road half-width 13.4 model pixels, ballast half-width 5.6, and lane-centre
wear at ±9.5. Yellow median lines border the ballast; centre dashes stop on the shared
arms. Ordinary side roads keep their 7.5-pixel half-width. Where one crosses the track,
the rails are set into the carriageway, the median lines stop and the existing
level-crossing decal marks the crossing. Crossings are derived terrain decorations,
never separate stored or pickable objects.

**Beside** keeps the ordinary road width and centre dashes, nudges its centre 6.35 pixels
to one side, and puts the rail centre 8.25 pixels to the other. Its side is stored in
the `rail` or `bridgeRail` appearance's `beside` bits. The editor changes a connected
corridor run together; Swap side reverses it, and Middle clears it. The one shared
`RailRoute` course drives track meshes, buffer stops, train snapping/following and
clearance. Authored junction layouts also determine the median rather than inventing
a different crossing underneath the rails.

The conservative clearance half-width is 13.9 pixels on shared exits. Road masks,
scatter, bridge slabs, bridge mouths and terminal support consume that corridor.
Sloped ground retains the existing 9-pixel ramp mouth, so the widened road accepts
the adjoining cross-slope. These are CPU footprint and mesh changes using the existing
road material and rail vertex colours; there is no new shader program or per-hex variant.

`RailCorridorTest` covers layer matching, persistence, flips, run propagation, stops
and riders. `BoardRoadRailTest` covers widths, markings, crossings, wheel wear and
the shared height at the shipped Trainjob approach. The on-demand
`GpuRoadRailSmokeTest` renders Middle and Beside on Trainjob, Depot and Ocoee from
above and at an angle. These focused CPU and native checks passed on 2026-10-18;
the reviewed corridor captures are in
`build/shader-audit/handoff-20261018/final/road-rail/`. The later Depot42 mask and
draw-order regression also passed in the grouped run recorded by
`build/handoff-multihex-verify.log`, with its corrected capture in
`build/shader-audit/handoff-20261018/multihex/depot42/`. No corridor timing
improvement is claimed.

Roads run like real roads through plain two-way hexes at one level: `BoardRoad.bends`
crosses each shared border along the line from the border before it to the border after
it, so a zigzag of hexes becomes one straight road and a turn one smooth arc instead of
a kink at every centre. Crossings stay within 40 degrees of square. Junctions, dead
ends, bridges and climbs keep square crossings. Except for the shared broad 120-degree
bend, their straight corridor holds a climb's ramp and a bridge deck's bank end. Next to such a crossing, the
bent crossing is chosen so that the hex's whole course into the corridor is one circular
arc; otherwise the turn would be left to the corridor's mouth, where a deck reaching
into the bank hides its start. Both hexes of a border derive the same crossing from the
same exits around them. Capture uses the same function with the board's hexes (`BoardFeatures`), so
trees and rough stay clear of the bent course.

At fitted concrete edges, level approaches extend to the actual boundary across their
full width; the existing ground triangles trim the paint into the diagonal join. Road
masks cover their complete footprint instead of cropping to the original hex box, so
shifted terrain boundaries cannot leave strips of bare ground between connected roads.

Centre-line dashes are anchored at the borders: every border a road crosses sits in the
middle of a gap, seen from both hexes, and a road between two borders fits whole dashes.
A junction arm fits the period of the straight road through it. The line therefore
continues across hex borders, through bends and onto bridge decks.

At mixed-material joins, the same priority and coordinates must be used on both hexes:
dirt covers asphalt, and gravel covers either. Dust and aggregate deposits lead into
the seam without changing carriageway width. World-space variation keeps borders and
wear continuous when adjacent paths run in opposite directions.

`GpuRoads` prepares cropped masks on CPU workers and uploads a chunk-owned atlas on the
GL thread. Coverage, transition coordinates and wheel-direction controls are distinct
inputs. The masks modulate repeating textures; they are not the road's albedo image.
Existing terrain triangles carry the overlay with a small depth offset, so the overlay
still has draw/fill cost even though it adds no road-shaped terrain tessellation.

## Batching and measured cost

Road mask atlas regions travel in the vertex stream. Draw materials retain the atlas
page and surface properties, allowing roads with different masks to share a draw within
their existing chunk. The tile ranges retain their original mask data: when an edit
replaces an atlas, reused vertices receive the new region before the old atlas is disposed.
The local mask coordinates and sampling calculation are unchanged. The two generic
vertex attributes use distinct units so libGDX binds both correctly.

Asphalt, wear and paint retain their explicit surface-lift order. Chunk centers cannot
reliably order overlapping coats after batching. Roads render before the other blended
overlays, whose normal depth ordering is retained. This adds 16 bytes per road vertex
for atlas placement, with the existing CPU and GPU copies; it adds no triangles or masks.

Coats merge as well: road coats of a chunk using one mask-atlas page share one material and one draw.
`GpuAssets.roadArray` holds the colour, normal and surface maps of asphalt, dirt and
gravel in one texture array; markings and sand/snow verges sample the existing sculpt
array. Each coat's maps, finish and wet response travel in its vertex colour
(`GpuRoads.coat`), which coats did not otherwise use. A chunk emits its coats in lift
order, so its one draw blends base coats first and wear and paint over them. A draw
that overflows a 16-bit mesh continues with that coat's lift, which the sorter orders
after the first. If a chunk's coats span multiple pages, draws retain their coat lift:
a gravel/markings page cannot safely draw wholly before or after an asphalt page.
This fixes the Depot42 junction's atlas-dependent verge ordering without changing
its geometry or coverage masks. Single-page batching is unchanged; this correction
has no new timing claim. Data sets without complete road maps, and coats whose sculpt material
is not in the array, keep separate materials. Bridge decks and tunnel floors keep their
own road textures.

The 2026-09-29 MesaCity comparison used the real 96x51 scene, a 1280x960 framebuffer,
terrain LoD enabled, RTX 4070 Laptop GPU, 60 warm-up and 240 measured frames in each
of seven views. Two sequential before/after pairs used a frozen source snapshot to
exclude concurrent terrain and water work.

| Overview metric | Before | Batched |
| --- | ---: | ---: |
| Road draws | 940 | 180 |
| Cover/overlay pass draws, including other overlays | 971 | 211 |
| Pass shader switches | 458 | 31 |
| Pass GL calls | 23,486 | 5,096 |
| Pass submitted indices | 560,373 | 560,373 |
| Pass CPU median, first / second pair | 2.593 / 1.892 ms | 0.810 / 0.519 ms |

Draw reduction was repeatable. Whole-frame timing remained variable under concurrent
work; this is not a general FPS guarantee. The second pair's GPU pass time rose from
0.262 to 0.322 ms, while the first pair fell from 0.979 to 0.415 ms. Explicit coat order
also increased texture bindings from 60 to 188. Terrain shading and long-frame stalls
remain separate costs; reducing road submissions does not solve them.

Merging the coats was measured on the 96x51 MesaCity overview at 1280x960 on an Intel
Iris Xe, one run each, with the same tree built with and without the merge:

| Overview metric | Separate coats | Merged coats |
| --- | ---: | ---: |
| Road draws | 175 | 44 |
| Cover/overlay pass draws, including other overlays | 189 | 58 |
| Frame median / p95 | 19.3 / 23.4 ms | 17.4 / 21.5 ms |

The 44 draws are the board's 41 road chunks plus mesh overflows. The road array takes
48 MiB of GPU memory (nine RGBA8 1024-pixel layers with mipmaps), against about 41 MiB
for the separate maps, which now load only for the kinds that decks and tunnel floors use.

## Physical ramps and joins

`BoardSurface` rounds the change from a flat center into a graded approach, then meets
the neighbor at a shared height and tangent. Road-to-bridge approaches use the same
planar inset as stepped bridges: each hex carries half the rise, meeting halfway in
height at the shared edge. Tile centers retain their game elevation and grades stay within the connected
hexes; they do not spread into an arbitrary chain of tiles.

Across a dry difference of up to two levels, an exit can form an approach onto unpaved
ground. Greater differences require connected exits on both sides. An aligned bridge
approach uses its deck height and the existing connection checks. Unconnected roads
retain the cliff instead of inventing movement connections.

Natural cut/fill uses the shared cliff profile and lateral shoulders. Concrete keeps
engineered slabs and retaining walls. `BoardRampMesh` can remove redundant interior
samples, but must retain shared boundaries, the center and the curved carriageway.
Contact queries choose a containing triangle before using an edge-tolerance fallback.
Raised bridge approaches are one closed concrete block spanning both insets, built by
`BoardBridgeFooting`. The underlying road bank and shoreline retain their own geometry.
The block owns the ramp's carriageway and straight retaining sides, and the bridge
retains its authored rails above it. Those rails continue to the road inset, with the
existing `bridge-terminal` GLB wedges on the flat road beyond the base. The level centre
of the bridge remains open below. The block's faces serve both road paint and picking;
`BoardBridgeSlope` omits the generated slab inside that block to avoid overlapping faces.
Descending approaches still cut the road bank, with concrete on the cut faces and
ground cover excluded from those faces.

At shifted shorelines, walls compare road and bank heights at corresponding edge
parameters in each surface's original profile. The wall then joins the ramp's emitted
rim to the bank's sampled boundary. Sampling the road at the shifted shore instead
leaves the wall short beneath graded road approaches. Graded wall endpoints stay
exact even beside water, so the joins do not leave pinholes.

At concrete cuts, the wall endpoints use the roof triangle selected inside each edge
interval. Sampling the highest roof at a discontinuous shoulder can otherwise bridge
the opening with a wall. The cliff builder preserves incomplete edge coverage, and
nearly coincident cut points merge into the next interval instead of leaving slits.
Regression checks ray-test the carriageway in all six directions at all four LoDs and
inspect five/six-way native junction captures for open wall seams. Batching checks also
compare separate tile submissions against shared draws before an edit, after it, and
after reverting it, across both projections and three camera tilts.

For cracks beside a road, inspect shared surface/corner samples before changing the
road mask. For a bright seam with continuous geometry, inspect mask coordinates,
material weights and normals instead. [Tunnel and roadside details](gpu-road-slopes-tunnels.md)
cover portals and cliff clipping.

## Bridges and invalidation

`BoardBridge` follows reciprocal span connections and connected approaches. Deck road
material and exits come directly from `bridge_road`; a clean manufactured deck is
bare concrete. A ground road below it does not paint its deck. Legacy conversion writes
the old connected-road material choice into deck terrain once when loading old boards.
The stored type selects manufactured, natural or implicit geometry; natural spans use
[natural bridge geometry](gpu-natural-bridges.md).

`BoardBridgeMesh` generates the built slab and kerbs from the road footprint and the
actual on-deck rail courses, including explicit junction choices and unfinished tracks.
`BoardBridgeTruss` follows that boundary with continuous chords and fitted support panels.
Members stop at the exact local hex boundary, with caps closing any cuts. Steel fascia
replaces the concrete side faces it covers so the two materials do not fight at one plane.
Road-only five/six-way spans keep their raised roundabout island; a span carrying rail has
an open center. The rail curves themselves remain the `RailRoute` courses.

Middle and beside road/rail corridors share one centered, wide structural footprint.
Moving the track beside the road changes the surface courses, not the position of the
slab or its outside barriers. Ending a track beside a continuing road retains its buffer
stop without building a separate enclosure. Wide ramp profiles include the outer
shoulders; road paint, central ballast and yellow separators continue onto the bank ramp.
At a taper, truss chords and braces follow the exposed boundary while posts retain
structural panel spacing. Curvature subdivisions must not each add a full-height post:
that closes the open truss into a dense red wall.
Validated on 2026-10-19 with 137 focused geometry/paint tests and a native render/picking
check covering concrete and truss ramps, both track placements, a rail terminus, and a
narrow-road control. Captures and test reports are in
`build/shader-audit/wide-bridge-20261019/`.
The subsequent recheck passed 33 focused corridor, slope, footing and natural-bridge
tests, plus three native smoke tests. Its nine wide-bridge views include reverse low
angles, a diagonal beside-track ramp with swapped sides, a rail terminus and a bare
roadless ramp. Natural mud joins and truss junctions were also recaptured under
`build/shader-audit/bridge-recheck-20261019/`.

`BoardBridgeSlope` places these local facets on the shared grade profile. Flat and graded
spans both retain one complete `BoardBridge.Shape` in the prepared terrain snapshot;
drawing, picking, rail placement and road paint consume those same facets. Geometry is
built during chunk preparation and retained between edits, without a separate per-frame
bridge builder or a second camera-specific mesh. Shape-specific bridge GLBs are no longer
used for spans; terminal and pier GLBs remain reusable details. An implicit deck
carrying only `bridge_rail:2` uses the existing maglev guideway beam instead of the
ordinary slab. Its exact beam triangles provide bridge picking and support, and it
has no road landing or material apron.

`BoardBridgeFooting` extends terminal decks to actual bank support. Road-connected
ends retain the ordinary material transition. At a bare bank, rails taper after they
are supported and the material apron blends into the existing bank triangles, except on
a bank drawn as pavement.
The physical span remains opaque.

A built bridge meeting a dry bank one level below its deck uses the same full solid
ramp with or without a road or rail. The slope spans both hex insets; adding or
removing road paint does not change its shape, picking or support. The bank terrain
underneath stays intact. Natural spans and maglev guideways retain their own geometry.
Ground-cover clearance includes the solid ramp from its inner inset through the bank
landing, including the terminal model's actual outward width. It leaves the unsupported
middle of a raised span available for vegetation underneath.
This clearance fix passed 27 obstacle/footing tests and a native bare-ramp render/picking
check on 2026-10-19. The final capture without vegetation penetrating the ramp is in
`build/shader-audit/bridge-recheck-final-20261019/wide-bridges/`.
The bridge regression suite (139 cases) and the native bare/road/bare edit check passed
on 2026-10-19; the two bare captures were pixel-identical. Captures are under
`build/shader-audit/bare-bridge-ramp-20261019/verified/bridge-slopes/`.

Changing a road's kind, exits or supporting geometry must invalidate the ground and
road masks together. Rail layout edits also rebuild the containing bridge's footprint and
side structure. Corridor edits recapture the local neighbourhood and invalidate both
ground and deck inputs; their eligibility has no span-wide gate. Reused road materials must bind the replacement chunk's
atlas before the previous atlas is disposed.

## Assets and fallback

Road materials live in `mm-data/data/models/board/textures/roads` as Godot 4 Standard
materials ([board materials](gpu-board-materials.md)). The GPU receives albedo, tangent
normals and a packed surface map: R height, G roughness, B cavity occlusion, A relief
range (from `heightmap_scale`). Texture height affects fine
coverage/wetting; the ground mesh continues to own actual height and support.
The Material Maker projects under mm-data's `tools/materials/roads/` export the aligned maps.
A road texture exported wider than its catalogued 1024 pixels covers proportionally more
road before it repeats.

Unsupported road levels, custom artwork combinations and submerged roads keep the
existing artwork fallback. Ice on dry ground supports native roads. Supported road
fluff uses the native material and geometry, including bent and horizontal routes.
Roadside-tree and parking-car models contain only their objects. Parking sprites can
provide cosmetic exits when the hex has no gameplay ROAD; existing ROAD takes
precedence. See [legacy scenery conversion](3d-board-editor.md#native-files-and-compatibility) for source selection and coverage.
