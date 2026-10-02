# Native roadside slopes and tunnel portals

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardRoad` | Share cosmetic road paths and crossing tangents between the rendered footprint and roadside clearance. |
| `BoardSurface / BoardRelief` | Keep graded road approaches joined to the canonical terrain and cliff grid. |
| `BoardRampMesh` | Simplify interior ramp samples while preserving shared boundaries and height error constraints. |
| `BoardTunnel` | Detect eligible blocked exits and define portal placement/clipping. |
| `GpuAssets / GpuRoads` | Load the portal kit and continue the road material through its lining. |
| `mm-data/tools/build_tunnel_asset.py` | Author portal geometry and textures offline. |

Roads beside elevation changes retain the native terrain slope and cliff
profiles outside their carriageway. Actual graded approaches still use the
existing road profile within the two connected hexes. Concrete retains its
separate cast geometry.

Plain roads with opposite exits keep a straight centreline. Where one meets a turn
at the same elevation, both hexes derive the same tangent normal to their shared
edge; the turn uses a broad approach without pulling the straight road sideways.
Grades, bridges and junctions retain their straight approach corridors. These
cosmetic paths preserve the authored exits and the full carriageway width.

Straight sections store their endpoints and explicit border anchors, which keep
centre-line markings aligned, instead of adding redundant collinear samples.
Constant-width footprints use one round `BasicStroke` per path, followed by the
existing flat terminal cuts; variable-width paths retain their segment envelopes.
The rendered road and roadside clearance share these paths, and callers receive
independent copies of cached footprint areas.

## Gates, cuts and retaining walls

`BoardRelief` is the only source of slopes and cliffs, including beside roads:

- **Gate.** Where two hexes are graded to each other (a road, or ground a road only
  approaches), their banks meet along the whole mouth. `BoardRelief.gateCorner` gives
  both the same corner height: the middle of the three levels there, which stays inside
  every step through the corner, so each keeps its natural slope down to it. The gate
  edge itself has no cliff profile: it keeps its lattice line under the carriageway and
  only its corners move, as the steps through them do. Ground a road merely approaches
  is ordinary sculpted terrain, so it no longer pins the corners of the cliffs around it.
- **Rim.** A graded top takes the relief's rim exactly on its outline. Inside, each
  edge's move reaches in along that edge's normal and fades out before the centre;
  edges blend by inverse square distance. A receding rim then compresses the ground
  without shearing it. Only the graded ramp strips stay put. Where a rim bends faster
  than the lattice is fine, a few small triangles flip; their free corners then move to
  the middle of their neighbours. Slivers can remain where two rims cross at a corner.
  On Fire and Ice 2 all stay under 0.3 m, which its test checks; Mines 1 keeps one
  0.6 m sliver, and MesaCity 2721 (a gate beside a five-level cliff) a cluster up to
  0.9 m tall but under 0.3 m² each.
- **Cut.** `roadClearance` cuts back only rock that would enter a road's shoulder and a
  one-unit verge. Everything clear of it keeps its full natural relief. At a tunnel portal
  the face it is set into stays flat on the lattice line up to the portal's top, where
  its wing walls stand, so no sky shows under them.
- **Wall.** Where a road beside a hex cuts the toe of an earth slope rising above it
  (up to two levels), `BoardRelief.retainingWalls` builds straight precast panels on a
  footing, each capped in half-metre steps just above the cut, standing in front of the
  cut face. At most a level is walled; a taller cliff stays a rock cut. A slope beneath a
  road never gets a wall. The panels use the tunnel portals' concrete texture.

Shared cliff samples use the same canonical floating-point parameter on both
sides. Ramp simplification preserves boundary segments. Native ground adjoining
a uniformly flat road uses the road's unsplit edge: this avoids GPU raster cracks
from collinear T junctions without adding triangles to the six-face road carrier.
Interior rings cannot request more samples than their boundary edge provides.

Authored road exits facing a dry wall more than two levels higher receive a
cosmetic tunnel entrance if they have no existing graded road approach. The
opening is clipped from the native cliff. Portals do not add game connections,
change elevations, or introduce tunnel movement rules.

The maintained asset is in the adjacent mm-data repository:

- `data/models/board/road-tunnel.glb`: winged ground portal.
- `data/models/board/road-tunnel-bridge.glb`: wingless bridge portal.
- `tools/board-models/road-tunnel.blend`: editable Blender source.
- `tools/build_tunnel_asset.py`: reproducible geometry, materials and GLB export.
- `data/models/board/textures/tunnel-concrete.png`: dedicated fine-grain albedo.

External walls and roof extend the full 18-unit depth. The lining and pier have
no overlapping inner faces. Vertex colour fades continuously toward the dark
end cap. Whole decorative rocks are excluded from the portal shell and approach,
including rocks generated by neighbouring hexes; surrounding formations remain.

Only bridges with an attached road anywhere along their connected span receive portals; natural rock
formations without road approaches receive none. Eligible bridge exits use the deck's actual elevation,
inherited road material and a wingless portal. All six
terrain families share this detection and clipping path. Connections between
bridge decks remain open. Bridge elevation/CF/repair metadata must not replace
the ground beneath the span with a legacy tile: the deck has its own model and
repairs have a tactical marker. Authored water and unsupported ground overlays
retain their existing handling.
The tunnel floor continues the full 15-unit road width into the lining, using
the approach's material, normal/surface maps and world-space texture phase.
The visible road stays bright at the entrance, then fades deeper inside. A tiny
recessed underlap at the hex edge prevents cracks on the slightly oblique lattice.

The bridge asset exporter must omit zero-width shared boundaries when extruding
rail outlines; otherwise coincident clipped loops create vertical faces across
an open carriageway.
