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
porous margins exposing the local ground. All use a common 30-pixel carriageway
(`HexTile.ROAD_HALF_WIDTH` is 15), twice the original width, so a material change
must not pinch the route. Two exits form a bend, three/four form a
junction, and five/six form a roundabout unless ground rails require a solid hub. Dead ends extend through the center but retain
a ground margin before the opposite hex; an exit-free road uses a short isolated stretch.

At a 120-degree connection with square border crossings, roads and bridges use
`RailRoute.course` across the whole hex. The shared broad curve keeps the deck and
its side structure centered around the track. Oblique crossings keep their shared
neighbor-derived tangents, and tighter adjacent-exit turns keep their own layout.
Three/four-way junctions retain their radial approaches and add the same broad curve
at full carriageway width for every legal gentle pair of exits, including pairs used
only by explicit rail layouts. Their tyre wear retains the radial approach courses.
Rail and road use that same course before applying an authored beside-corridor offset.

Single-hex roundabouts have one circulating lane. Eligible level, paved three-hex
triangles become one shared two-lane roundabout; ground rails, bridges and grade
changes prevent that conversion. Both circulate counter-clockwise. Entries have
broken give-way bars following the outside of the ring, clipped to each incoming
lane, with an inverted triangle aligned to that lane. Oblique entries keep the
same setback from circulating traffic. Only the
two-lane ring has a broken lane divider; neither ring has repeated direction arrows.
Tangent fillets join the approaches to the ring; exits use twice the entry turning
radius so departing traffic follows a gentler curve.
All members derive the same world-space circle, and a canonical owner prevents
duplicate paint. Road paint and clearance may extend into adjoining dry, level
hexes; the hex grid does not trim the outside of a bend or roundabout.

### Junction controls

Three/four-way paved junctions use solid crossing hubs and rounded inside corners.
Rail-bearing five/six-way junctions also use controlled solid hubs instead of roundabouts.
White stop bars cross incoming right-hand lanes immediately before their conflict
with the other approaches. A T junction's opposite pair is the dominant through
road, with a stop only on the secondary approach; ambiguous crossings use all-way
controls. Centre dividers stop before the conflict area on every junction arm,
including the dominant through road, leaving turning movements clear; their final
carriageway width before that boundary is continuous. Unmarked roads omit
these markings. Road/rail corridors keep the bars out of the central railway median.

Ordinary two-exit bends use continuous white centre lines through the bend and for
one carriageway width on their connected approaches. Remote straights retain dashed
dividers, and shared-rail medians retain their boundary lines. Roundabout entry and
circulation markings keep their own geometry. These are conservative visual choices;
real-world bend markings also depend on visibility and the country's conventions.

Signal-controlled four-arm junctions use a yellow keep-clear box: an outline and
open diagonal lattice fitted inside the four stop planes, clipped to the actual
carriageway and clear of railway reservations. Stop-only junctions and roundabouts
omit the box. This is the project's selected box-junction convention, not a universal
US/EU requirement at every traffic light. Geometry and paint are shared in Classic,
Enhanced and either tactical view.

The road brush and the ground/deck road inspector offer **Junction controls: Stops**,
**Stops + traffic lights**, or **Stops + overhead traffic lights**. Stops uses an
octagonal roadside STOP sign; traffic lights replace the sign with a three-aspect
signal. The overhead option uses a curved metal mast arm carrying two overhead
heads and a lower roadside repeater. All modes keep the stop bars.
The choice belongs to the `road` or `bridgeRoad` appearance. With no stored choice,
three-arm junctions use STOP signs and larger junctions use overhead traffic lights.
An explicitly signalled T controls every approach. Explicit choices store
`trafficControl: stops`, `signals`, or `gantry`, including a gantry override at a T. Material
changes, copy/paste, layer moves, save/load and undo preserve that choice.

Legacy `.board` X crossings use the same gantry default. Native boards retain explicit
authored choices; roads without one inherit the default. Controls are derived from the same
approach geometry as the paint, use the existing ground/deck support and GLB prop
pipeline, and disappear when a junction is removed. They are cosmetic terrain
decorations, not stored or independently selectable objects.
Controls may extend into a dry, level neighbour when their whole footing is
supported. Ground poles and gantry footings also clear the neighbouring road and
rail courses, including overlapping junction pavement. Bridge controls must fit
the actual deck shoulder. A crowded approach falls back to a roadside signal only
where that pole also fits; an unsupported pole is omitted while its stop bar remains.
If a sideways search crosses another branch, placement retries from the incoming
lane edge to find the small island it skipped. Gantries and single poles may move
up to two pixels upstream and 1.5 pixels outward there, with the same road clearance
and ground support checks. Existing valid placements are kept.

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
uses the ordinary roadside light. The mast may move up to two pixels toward waiting
traffic to clear an acute junction without moving its stop bar. The four-metre arm
and both LODs share the same lamp positions and supported roadside origin.

Rails share the carriageway (batch 10, docs/3d-board-editor.md): every shape and
clearance site reads `wayExits` and `BoardRoad.carriageway` (a drawn road or a ground
rail on dry ground), so a rail-only hex is shaped exactly as the road hex
with the same exits (hub, ramps of one or two levels, concrete cuts, retaining walls,
the ramp onto a deck), and the rail's track is draped on the faces road paint lies on.
Only roads receive carriageway paint and vote a bridge deck's road material.
Classic, Enhanced and either Tactical View share the infrastructure preparation,
road paint, rail meshes, built bridge decks, tunnel openings and supported controls.
Classic natural bridges use a normal full hex cap over the hollow arch; Enhanced
retains its eroded span outline. Both use the same road and rail courses, and each
mode draws and picks its installed shell. Classic tileset art omits baked roads
and flat bridge sprites.

`BoardRoad` is also the clearance source for boulders, trees and vegetation. The
relief's own rim, talus and field rocks and shrubs may stand beside a road but never
on its carriageway or shoulder (`BoardRelief.place`).
Changing width or a junction therefore affects more than its rendered mask.
Authored roadside tree rows sit near the plain-road verge with canopy clearance.
Only hexes carrying conventional railway tracks receive the additional rail clearance,
using their existing rail courses and the chosen trees' model footprints.
Markings follow the main paved route through unmarked branches. Wheel wear follows
the lane centres through bends, endings and material changes.

## Road and rail corridors

A corridor exists only on reciprocal road/rail sides that share the same reachable
layer. Ground roads and traditional rails use their shared, non-stranded sides;
submerged ground and five/six-way road roundabouts do not form corridors. On a deck,
the candidates are the intersection of `bridge_road`, `bridge_rail:1` and bridge
exits. Deck type and distant bank connectivity do not gate a corridor. Ground and
deck values are captured independently, and off-board shared exits retain full width.

The default **Middle** layout has a central ballast strip and a full-width traffic
lane on either side. Its half-width derives from the ordinary road half-width,
`RailRoute.BALLAST_HALF_WIDTH` and a small median margin. White median lines border
the ballast; centre dashes stop on shared arms. Ordinary side roads keep their
15-pixel half-width. Where one crosses the track, the rails are set into dark,
track-aligned infill with recessed flangeways, and the median lines stop. No automatic
concrete diamond decal is placed. This crossing is derived from the live road and
rail geometry, never a separate stored or pickable object.

Transverse, same-level conventional tracks also derive crossing-signal poles, incoming
stop bars and continuous approach dividers from one shared crossing description.
Road paint clears the track band. A lane approaching both a road junction and a railway
uses one stop bar before the first conflict. Parallel corridors, diverging turnouts,
maglev and separate ground/deck levels do not acquire level-crossing controls.
Existing authored crossing poles suppress nearby generated duplicates; generated
deck poles must fit the deck shoulder. The signals remain cosmetic, like the road lights.

**Beside** keeps the ordinary road width and centre dashes. The shared road and
ballast widths plus `RailRoute.CORRIDOR_GAP` determine their opposite offsets. Its side is stored in
the `rail` or `bridgeRail` appearance's `beside` bits. The editor changes a connected
corridor run together; Swap side reverses it, and Middle clears it. The one shared
`RailRoute` course drives track meshes, buffer stops, train snapping/following and
clearance. Authored junction layouts also determine the median rather than inventing
a different crossing underneath the rails.

At a shared terminal, the rail course, ballast and median continue to the road's
end plane (`HexTile.ROAD_END_REACH`). Buffer stops and train snapping read that same
extended course. Middle keeps its full width to the end; Beside keeps its lateral
offset there. Standalone rail endings retain their existing reach.

Road masks, scatter, bridge slabs, bridge mouths, tunnel floors and terminal support
consume the same corridor width. Ramp grading spans the carried width while retaining
shared edge heights. Conventional railway cross-sections and conventional trains are
scaled by 1.3; route centre paths, maglev guideways and maglev vehicles are unchanged.
These changes use the existing road material and rail vertex colours.

The editable `data/boards/examples/Road and Rail Junctions.board2` fixture contains
all 64 exit masks for roads, rails, Middle and Beside corridors, plus mixed crossings,
triangle layouts, rotated Seaport junctions, parking, trees, bridges and tunnels.
`GpuRoadJunctionFixtureSmokeTest` captures each group and the original Seaport board;
`BoardInfrastructureModeTest` checks shared geometry across graphics modes.
For a focused native review, `-Dmegamek.gpu.fixtureGroups=seaport-board,width-oblique`
selects capture groups; omitting it renders the complete fixture.

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
Roads entering a roadless concrete apron end flush at its fitted edge. Through-roads
and roundabouts retain their full width when their sides cross a neighbouring concrete
edge, and explicitly authored roads on concrete keep their paint. Road clearance
preserves the fitted boundary shared by both surfaces.
At a roadless concrete entrance, markings stop one lane width plus the shoulder
before that boundary. A stop bar intersecting this clearance is omitted whole rather
than leaving a short fragment. This also applies to graded concrete entrances.
Apron entrances do not count as road junction arms for either markings or physical
stop signs and signals; a genuine junction of three remaining roads keeps its controls.
Connected bridge decks above concrete remain road continuations. Once the scene's
concrete fit is available, generated ground controls use the same final stop approaches
as the paint, including skewed entrances and rail crossings. Authored objects and deck
controls keep their existing ownership.

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
engineered slabs and retaining walls. A roadless concrete apron receives each incline
directly, without inventing connecting bends that could put a retaining wall across
an approach. `BoardRampMesh` can remove redundant interior
samples, but must retain shared boundaries, the center and the curved carriageway.
Contact queries choose a containing triangle before using an edge-tolerance fallback.
Raised bridge approaches form a closed concrete support spanning both insets, built by
`BoardBridgeFooting`. The underlying road bank and shoreline retain their own geometry.
The deck half uses `BoardBridgeMesh`'s actual carriageway and slab outlines, clipped
to its approach grade. Its retaining walls follow the curve, so the ramp cannot
cut across the road or protrude beyond the parapets. The bank half meets the same
mouth and the bridge retains its generated rails above the support. Those rails continue to the road inset, with the
existing `bridge-terminal` GLB wedges on the flat road beyond the base. The level centre
of the bridge remains open below. The block's faces serve both road paint and picking;
`BoardBridgeSlope` omits the generated slab inside that block to avoid overlapping faces.
Descending approaches cut the natural road bank. Their exposed terrain keeps its
native material; the bridge footing owns the actual concrete structure.

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
Members meet at connected exit planes, with caps closing cuts; outside shoulders may
extend across inactive hex edges instead of pinching the deck. Steel fascia
replaces the concrete side faces it covers so the two materials do not fight at one plane.
Road-only five/six-way spans keep their raised roundabout island; a span carrying rail has
an open center. The rail curves themselves remain the `RailRoute` courses.

Middle and beside road/rail corridors share one centered, wide structural footprint.
Moving the track beside the road changes the surface courses, not the position of the
slab or its outside barriers. Ending a track beside a continuing road retains its buffer
stop without building a separate enclosure. Wide ramp profiles include the outer
shoulders; road paint, central ballast and white separators continue onto the bank ramp.
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

At a curved road connection, the landing, parapets and extended paint follow the
bank road's existing course. Parapet offsets interpolate the course tangents, while
the terminal GLBs rotate as rigid blocks instead of bending with the slab. The
level road beyond a curved ramp remains its sole asphalt carrier; adding another
flat slab there would overlap its markings and tyre wear. A built parapet also
replaces an ordinary retaining panel when both occupy the same landing.

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

Road maps live in `mm-data/data/models/board/textures/roads`.
Each material has albedo, tangent normals and a packed surface map:
R height, G roughness, B cavity occlusion, A relief range. Texture height affects fine
coverage/wetting; the ground mesh continues to own actual height and support.
The Material Maker projects under mm-data's `tools/materials/roads/` export the aligned maps.

Unsupported road levels, custom artwork combinations and submerged roads keep the
existing artwork fallback. Ice on dry ground supports native roads. Supported road
fluff uses the native material and geometry, including bent and horizontal routes.
Roadside-tree and parking-car models contain only their objects. Parking sprites can
provide cosmetic exits when the hex has no gameplay ROAD; existing ROAD takes
precedence. See [legacy scenery conversion](3d-board-editor.md#native-files-and-compatibility) for source selection and coverage.
