# Native roadside slopes and tunnel portals

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardRoad` | Share cosmetic road paths and crossing tangents between the rendered footprint and roadside clearance. |
| `BoardSurface / BoardRelief` | Join Enhanced road grades to the terrain and cliff grid; use nominal columns for Classic route support. |
| `ConcreteFit / BoardConcrete` | Share fitted concrete material and elevation boundaries, with structure, object and explicit Hex-edge protection. |
| `GpuTilesetTerrain / BoardRim` | Draw Classic columns; select their wall textures without displacing their geometry. |
| `BoardRampMesh` | Simplify interior ramp samples while preserving shared boundaries and height error constraints. |
| `BoardTunnel` | Detect eligible blocked exits and define portal placement/clipping. |
| `GpuAssets / GpuRoads` | Load the portal kit and continue the road material through its lining. |
| `mm-data/tools/board-models/road-tunnel.md` | Portal GLB dimensions and contract. |

In Enhanced, roads beside elevation changes retain the native terrain slope and cliff
profiles outside their carriageway. Actual graded approaches still use the
existing road profile within the two connected hexes. Concrete retains its
separate cast geometry.

Ramp heights cover the full carriageway and shoulder, including vertices from
the terrain bank's sampling partition. Adjacent strips retain enough samples
for that grade at distant detail. A valid pair of triangles keeps its diagonal
so terrain repair cannot change the road's height interpolation. Bank vertices
moved into a curved carriageway take the road height at their final position.

Plain roads with opposite exits keep a straight centreline. Where one meets a turn
at the same elevation, both hexes derive the same tangent normal to their shared
edge; the turn uses a broad approach without pulling the straight road sideways.
Grades, bridges and junctions retain their straight approach corridors. These
cosmetic paths preserve the authored exits and the full carriageway width.

Two-exit routes rising or falling through successive elevations carry their grade
along the existing road course, including curves and unequal steps. Two monotone
Hermite halves retain the authored middle elevation and share endpoint slopes with
adjoining approaches. Equal-step straight routes retain their linear grade. This
avoids flat landings at each hex centre and crests between adjoining ramps. Lower
and level built-deck connections use the bridge profile's actual mouth slope;
higher decks retain their footing-owned approach. A missing neighbour at the board
boundary is not a connected approach and cannot supply a ramp or corner height.

Straight sections store their endpoints and explicit border anchors, which keep
centre-line markings aligned, instead of adding redundant collinear samples.
Constant-width footprints use one round `BasicStroke` per path, followed by the
existing flat terminal cuts; variable-width paths retain their segment envelopes.
The rendered road and roadside clearance share these paths, and callers receive
independent copies of cached footprint areas.

## Gates, cuts and retaining walls

`BoardRelief` is the source of Enhanced slopes and cliffs, including beside roads:

- **Gate.** Where two hexes are graded to each other (a road, or ground a road only
  approaches), their banks meet along the whole mouth. `BoardRelief.gateCorner` gives
  both the same corner height. An existing level through-route keeps its elevation;
  otherwise a shared climb can use its mouth height instead of leaving an isolated
  high point between ramps. Other corners retain the middle of the three levels.
  A natural third bank at an intermediate elevation follows that shared corner and
  grades smoothly back to its unchanged plateau, rather than closing the difference
  with a vertical triangle. If the third hex instead equals the lower road hex,
  their uncovered common corner stays at that lower level. Only the actual road/rail
  footprint can require a raised corner there; a road mouth must not create a ridge
  across an otherwise level plateau. This decision is local to the corner. Natural approaches
  to lower concrete also lower isolated corners while the slab stays fixed. The gate
  edge itself has no cliff profile: it keeps its lattice line under the carriageway and
  only its corners move, as the steps through them do. Ground a road merely approaches
  is ordinary sculpted terrain, so it no longer pins the corners of the cliffs around it.
  Beside a descending bridge approach, an isolated bank corner also lowers toward
  the bridge gate. Corners covered by the widened pavement meet the deck grade;
  uncovered corners can retain higher ground in the third hex.
- **Rim.** A graded top takes the relief's rim exactly on its outline. Inside, each
  edge's move reaches in along that edge's normal and fades out before the centre;
  edges blend by inverse square distance. A receding rim then compresses the ground
  without shearing it. Only the graded strips within the actual road course stay put.
  Banks beyond a bend can follow the rim even if they lie inside an approach's
  rectangular sampling domain. Where a rim bends faster
  than the lattice is fine, a few small triangles flip; their free corners then move to
  the middle of their neighbours. If that mean remains outside a concave fan, a bounded
  correction moves the flipped triangles' free corners together to increase their
  signed area, preserving the outline, curved pavement and heights. Slivers can remain
  where two rims cross at a corner.
  On Fire and Ice 2 all stay under 0.3 m, which its test checks; Mines 1 keeps one
  0.6 m sliver, and MesaCity 2721 (a gate beside a five-level cliff) a cluster up to
  0.9 m tall but under 0.3 m² each.
- **Cut.** `roadClearance` cuts back only rock that would enter a road's shoulder and a
  one-unit verge. The cut continues below the rim until the natural profile clears
  that footprint, avoiding an undercut beneath the road. Everything clear of it keeps
  its full natural relief. Fitted concrete boundaries remain the shared baseline;
  clearance trims only natural displacement beyond that baseline. At a tunnel portal
  the face it is set into stays flat on the lattice line up to the portal's top, where
  its wing walls stand, so no sky shows under them.
  Built bridge decks also clear higher neighbouring hillsides at deck height,
  including bridge-only hexes. Where the widened road/rail slab overlaps a nominal
  hex corner, the shared boundary itself retreats beyond the full slab and its
  shoulder before the natural profile is trimmed. All incident edges use that
  same contact. The landing's own level bank retains its natural support profile;
  the toe of a higher hillside must be cut back with its face so no sloping triangle
  crosses the carriageway. A descending curved bridge approach reserves additional
  space for a 1:1 cut above its actual road/rail course, derived from the local drop.
  This lets the inside plateau recede gradually instead of crowding the shoulder.
  Straight and level bridge approaches do not receive that additional setback.
- **Wall.** Where a concrete road beside a hex cuts the toe of an earth slope rising above it
  (up to two levels), `BoardRelief.retainingWalls` builds straight precast panels on a
  footing, each capped in half-metre steps just above the cut, standing in front of the
  cut face. At most a level is walled; a taller cliff stays a rock cut. A slope beneath a
  road never gets a wall. A graded rim that has moved away from the original level toe
  keeps its earthwork without unsupported flat panels. Panels start at 1 m overall,
  including their 0.25 m cap, and increase in half-metre steps when the retained cut
  needs more height. Heights stay relative to the local footing, including below
  elevation zero. The panels, caps and footings have closed backs and undersides,
  so exposed portions remain solid from either side. They use the shared
  fine-grain concrete texture. Natural roads keep cut banks and embankments instead
  of receiving these automatic concrete panels.

The road's longitudinal grade is resolved before blending the adjoining bank.
The bank blends once and matches the finished boundary samples, including widened
road/rail corridors. Where junction mouths meet at different heights, both owners
share a lateral profile from each canonical corner through the exact mouth centre.
Level bridge entrances also constrain the full passage width, so adjoining lower
roads cannot pull their shared mouth below the deck. Graded concrete banks already
own their road slab and do not receive a second, buried landing surface. Natural
landings retain the overlap needed to cover a receding bank.
The surface blends smoothly toward that profile over the available approach run,
instead of forcing its height change through a narrow shoulder strip. Concrete
grades the same road carrier before its existing clipping restores the outer slab.
Ordinary approaches and uniform-height junctions retain their existing profiles.
Steep dry natural support faces use the existing cliff texture
projection and normal relief; horizontal ground projection would stretch on these
faces. Concrete retains its own material and geometry.

Enhanced concrete uses the existing contour fitter at dry concrete-to-concrete
height boundaries too. The higher plateau owns each boundary, and all adjoining
hexes read the same corner shifts. The fitted runs reserve road/rail clearance;
an actual road or ground-rail ramp opening is not treated as a retaining wall.
Graded hexes retain their connected mouth endpoints, including their level arms,
so fitting cannot move the boundary away from the existing road carrier.
Flat concrete roads use the full fitted slab even beside unrelated higher land.
Level-contour candidates must also keep every affected hex edge facing around its
own centre, preventing a moved wall corner from folding a neighbouring top.
Side-wall collision checks, structural foundations and explicit
`HexAppearance.Edges.HEX` overrides still pin the outline. Existing material/water
fits have priority, and three-height corners remain fixed, so a short joining
bevel can remain at those protected corners and ramp openings. The fit straightens
exposed wall runs between these constraints; it does not promise one uninterrupted
line through a graded junction.

Shared cliff samples use the same canonical floating-point parameter on both
sides. Ramp simplification preserves boundary segments. Native ground adjoining
a uniformly flat road uses the road's unsplit edge: this avoids GPU raster cracks
from collinear T junctions without adding triangles to the six-face road carrier.
Interior rings cannot request more samples than their boundary edge provides.

The finished road top publishes its actual rim positions and area-weighted
normals to the cliff mesh. Both surfaces therefore share lighting at their contact;
the wall does not recompute a different normal from an earlier terrain profile.

## Graphics modes

Classic uses sharp nominal hex columns: flat tops and vertical side walls outside
the actual road/rail ramp footprint. `BoardSurface.columns` keeps route grades
inside their corridor and closes the cut against the remaining flat column.
A nearby or terminating road does not bring Enhanced banks into an unrelated
hex. `BoardRim` chooses the Classic wall texture only; it does not move corners,
round cliffs or stitch an Enhanced rim back to the column.

Classic artwork uses nominal hex UV coordinates. Actual route ramps use their
shared surface normals; the untouched top stays horizontal. Drawing, picking,
tactical overlays and unit placement use the selected mode's support, and their
surface caches are invalidated when that mode changes. Enhanced concrete fitting
does not replace Classic's nominal outer hex outline.

Built bridge landings and piers sample the same mode's ground, including support
borrowed from another chunk, so their contact does not depend on chunk load order.
Classic natural bridges retain a normal full hex cap with the carried terrain's
artwork and graded entry/exit approaches; the arch leaves an underpass below it.
Enhanced natural bridges retain their sculpted span.

## Fixtures and verification

The editable `mm-data/data/boards/fixtures/Road Grades - Grassland.board2` and
`Road Grades - Concrete.board2` maps cover 36 layouts in road-only, centered-rail
and beside-rail panels (216 cases total: 36 × 3 × 2). Their adjacent README and case
CSV give coordinates and regeneration instructions. `BoardRoadGradeFixtureTest` checks
shared seams, support and authored heights; `GpuRoadGradeFixtureSmokeTest` renders
the saved maps in both graphics modes.

Six ordinary cases cover continuous straight and curved inclines, higher/lower
inside banks, a centered/beside rail transition and a graded bent bridge.
The focused CPU suite rotates those six cases through six directions and both
column parities, with both surfaces and all three panels: 432 specimens. It checks
rail support along the course, shared mouth heights and the grade through each
interior straight-route centre, rather than only matching the endpoints.
Two additional layouts cover equal lower plateaus beside straight and curved
inclines, with 144 rotated/material/corridor combinations. Actual-map regressions
include Mines 1, City Hills Residential 1 at 1506, and Airport 3000 at 1513 and its
two curved approaches. Unequal-step course tests cover both modes, rotations,
column parities and all three road/rail panels.

## Measured geometry work

Two local optimizations were independently reviewed before implementation.
`GpuRoads.keepClear` generates only lattice strokes that can reach the inverse-
rotated keep-clear box bounds, including stroke width and a numeric margin. It
preserves the original 8-pixel phase, ±128 line endpoints, transform, stroke and
final clipping. Alternating before/after blocks in one JVM gave these median
times and per-invocation allocations for mask 27 with gantry signals. The local
Windows/JDK 25 probe used three warm-up blocks and six measured blocks of eight
calls per version, with alternating order:

| Corridor | Before | After |
| --- | --- | --- |
| Road only | 28.952 ms / 21.507 MiB | 2.593 ms / 1.342 MiB |
| Centered rail | 45.729 ms / 22.995 MiB | 6.497 ms / 2.697 MiB |
| Beside rail, first side | 103.153 ms / 56.268 MiB | 10.688 ms / 2.823 MiB |
| Beside rail, other side | 91.653 ms / 56.667 MiB | 7.843 ms / 2.902 MiB |

Geometric comparisons covered all 15 four-arm masks, all four corridor variants
and both signal modes (120 configurations). Bounds were unchanged and XOR area
was at most 6 × 10⁻¹² model-pixel². Java2D `Area` resegmentation changed antialiasing
in 3,530 of 6,590,592 raster pixels, all within 0.25 model pixels of the original
paint boundary; the largest alpha difference was 23/255. These are geometrically
equivalent masks, not byte-identical raster images.

`BoardSurface.retainingEdge` also avoids a temporary three-element vertex array
per visited face while retaining its operation order. All 18 sampled ground and
retaining-wall meshes were byte-identical. Selected concrete surface builds used
26–60% less allocation; their wall-clock timings were too noisy for a speed claim.
These measurements concern the two local geometry operations, not frame rate or
whole-board load time. Probe reports are under the ignored
`megamek/build/geometry-perf-audit` directory.

## Tunnel portals

Authored road exits facing a dry wall more than two levels higher receive a
cosmetic tunnel entrance if they have no existing graded road approach. The
opening is clipped from the native cliff. Portals do not add game connections,
change elevations, or introduce tunnel movement rules.

The maintained asset is in the adjacent mm-data repository:

- `data/models/board/road-tunnel.glb`: winged ground portal.
- `data/models/board/road-tunnel-bridge.glb`: wingless bridge portal.
- `tools/board-models/road-tunnel.md`: the portal's dimensions and contract.
- `tools/materials/scenery/gallery-concrete/bake_normal.py`: normal-map bake for
  the continuous concrete textures embedded in both portal GLBs.

External walls and roof extend the full 18-unit depth. The lining and pier have
no overlapping inner faces. Vertex colour fades continuously toward the dark
end cap. Whole decorative rocks are excluded from the portal shell and approach,
including rocks generated by neighbouring hexes; surrounding formations remain.

Only bridges with an attached road anywhere along their connected span receive portals; natural rock
formations without road approaches receive none. Eligible bridge exits use the deck's actual elevation,
inherited road material and a wingless portal. All six
terrain families share this detection and clipping path. Connections between
bridge decks remain open. Portal models are installed before the ground rendering
branches, including space and sky hexes: suspended bridge entrances must close
their cliff openings even when the ground is transparent (Quatre Belle).
Bridge elevation/CF/repair metadata must not replace
the ground beneath the span with a legacy tile: the deck has its own model and
repairs have a tactical marker. Authored water and unsupported ground overlays
retain their existing handling.
The tunnel floor continues the full 30-unit road width into the lining, using
the approach's material, normal/surface maps and world-space texture phase.
Road/rail portals expand to their shared corridor width. Only the lateral scale
changes; the opening cut uses the same transform, and height and depth stay fixed.
The visible road stays bright at the entrance, then fades deeper inside. A tiny
recessed underlap at the hex edge prevents cracks on the slightly oblique lattice.

The runtime bridge builder must omit zero-width shared boundaries when extruding
rail outlines; otherwise coincident clipped loops create vertical faces across
an open carriageway.
