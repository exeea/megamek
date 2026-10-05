# Roads and ramps

[Docs index](README.md) · [Terrain geometry](gpu-terrain-materials.md)

Roads have two cooperating parts: a footprint/material overlay and the ground that
supports it. A painted road does not own a second height field. Rendering, picking and
unit support must continue to agree on the underlying surface.

## What does what

| Code | Responsibility |
| --- | --- |
| `BoardScene.Tile.road / roadExits` | Carry captured road kind and exit connectivity through scene updates. |
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

`BoardRoad` is also the clearance source for boulders, trees and vegetation. The
relief's own rim, talus and field rocks and shrubs may stand beside a road but never
on its carriageway or shoulder (`BoardRelief.place`).
Changing width or a junction therefore affects more than its rendered mask.
Markings follow the main paved route through unmarked branches. Wheel wear follows
fixed lateral offsets through bends, endings and material changes.

Roads run like real roads through plain two-way hexes at one level: `BoardRoad.bends`
crosses each shared border along the line from the border before it to the border after
it, so a zigzag of hexes becomes one straight road and a turn one smooth arc instead of
a kink at every centre. Crossings stay within 40 degrees of square. Junctions, dead
ends, bridges and climbs keep square crossings with a straight corridor into the hex,
which holds a climb's ramp and a bridge deck's bank end. Next to such a crossing, the
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

Coats merge as well: all road coats of a chunk share one material and one draw.
`GpuAssets.roadArray` holds the colour, normal and surface maps of asphalt, dirt and
gravel in one texture array; markings and sand/snow verges sample the existing sculpt
array. Each coat's maps, finish and wet response travel in its vertex colour
(`GpuRoads.coat`), which coats did not otherwise use. A chunk emits its coats in lift
order, so its one draw blends base coats first and wear and paint over them. A draw
that overflows a 16-bit mesh continues with that coat's lift, which the sorter orders
after the first. Data sets without complete road maps, and coats whose sculpt material
is not in the array, keep separate materials. Bridge decks and tunnel floors keep their
own road textures.

The 2026-09-29 MesaCity comparison used the real 96x51 scene, a 1280x960 framebuffer,
terrain LoD enabled, RTX 4070 Laptop GPU, 60 warm-up and 240 measured frames per view.
Two sequential before/after pairs used a frozen source snapshot to exclude concurrent
terrain and water work. [All seven views and both pairs](benchmarks/mesa-road-batching.csv)
retain the raw timings and counts.

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
`BoardBridgeSlope` omits the authored slab inside that block to avoid overlapping faces.
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

`BoardBridge` follows reciprocal span connections and connected approaches. Manufactured
spans inherit the best connected road surface: marked asphalt, unmarked asphalt, gravel,
then dirt. A co-located road below a deck is not an explicit deck-surface override.
Spans with no attached road use [natural bridge geometry](gpu-natural-bridges.md).

`BoardBridgeFooting` extends terminal decks to actual bank support. Road-connected
ends retain the ordinary material transition. At a bare bank, rails taper after they
are supported and the material apron blends into the existing bank triangles.
The physical span remains opaque.

Changing a road's kind, exits or supporting geometry must invalidate the ground and
road masks together. A changed approach can affect the inherited surface of a connected
span across chunk boundaries. Reused road materials must bind the replacement chunk's
atlas before the previous atlas is disposed.

## Assets and fallback

Road maps live in `mm-data/data/models/board/textures/roads`.
Each material has albedo, tangent normals and a packed surface map:
R height, G roughness, B cavity occlusion, A relief range. Texture height affects fine
coverage/wetting; the ground mesh continues to own actual height and support.
`tools/prepare_road_materials.py` in mm-data bakes aligned maps.

Unsupported road levels, custom artwork combinations and submerged roads keep the
existing artwork fallback. Ice on dry ground supports native roads. Supported road
fluff uses the native material and geometry, including bent and horizontal routes.
Roadside-tree and parking-car models contain only their objects. Parking sprites can
provide cosmetic exits when the hex has no gameplay ROAD; existing ROAD takes
precedence. See [Tile scenery and decals](gpu-scenery.md) for source selection and coverage.
