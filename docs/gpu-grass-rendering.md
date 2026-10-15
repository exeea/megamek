# Grass and shared vegetation wind

`GpuGroundCover` draws low grass over finished terrain. Crops and wetland plants
belong to `GpuBiomeVegetation`; trees belong to `GpuTreeInstances`. Grass, crops
and reeds follow one ground-cover contract, described in
[fields and marsh](gpu-fields-marsh.md#one-pipeline-for-ground-cover): terrain
workers plant them with each chunk, chunks keep persistent instance buffers, and
the shaders share wind, wetness and lighting. Each kind keeps its own placement,
density and mesh rules.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `BoardVegetation`, `BoardBiome` | Terrain eligibility, deterministic placement inputs and shared growth fields. |
| `BoardPlants` | What each terrain detail level plants; grass and bank turf on medium and full detail. |
| `GpuGroundCover` | Root planting for terrain workers, projected density, blade templates and per-chunk submission. |
| `GpuBankTurf` | Six curved turf-edge cutouts, fitted to finished bank contours and retained with the grass chunk. |
| `terrain-grass.glsl` | Blade shape, root-fixed bending and per-root detail selection. |
| `terrain-meadow.glsl` | Growth variation shared by ground color and grass shape. |
| `terrain-vegetation-wind.glsl` | World-space gust used by grass, crops and marsh plants. |
| `terrain-vegetation.frag` | Plant surface lighting, root darkening and wetness. |
| `terrain-ground-color.glsl`, `terrain-bank-color.glsl` | Terrain pigment and elevation grading shared with the turf; the atlas contributes neutral blade detail. |
| `GpuTerrain` | Wind phase integration, finished support surfaces and pass ordering. |

The **Grass blades** control under Tuning → Terrain skips grass preparation and
drawing when disabled. Cached roots remain available when it is re-enabled.
Ground shading, crops, marsh plants and trees have separate controls/owners.

## Roots and drawing

Roots are deterministic positions on finished terrain triangles, filtered by
existing surface and road rules. Terrain workers plant them for every hex of a
full-detail chunk (`GpuGroundCover.plant`), and the installed tile keeps them.
Each instance stores position plus a stable sample rank. Density selects an
ordered prefix by rank: existing roots do not move when density changes.

No cover grows through paint. `BoardPlants` builds `BoardDecals.Opacity` once
per hex from the decals painting its ground (each image's alpha at the decal's
drawn transform, so a round emblem leaves no square bald patch) and from its
legacy paint overlay, and every planter multiplies its coverage by one minus that
opacity before its usual threshold: blades, bank turf, reeds and crops alike, in
every 3D view. Cover vanishes where paint is more than about 45 % opaque and thins
under soft paint such as the rubble path; each paint keeps the one-model-px margin
a road's shoulder keeps. A candidate's place and chance are drawn before any
rejection, so the roots outside the paint keep their places and ranks, and
removing a decal restores them exactly. Roofs, decks, industrial tops, fuel tanks
and ice grow no cover, so only paint on the ground counts. Not covered yet: the
dressing baked into the solid terrain (`BoardScatter` pebbles, tufts and bushes,
`BoardRelief.field` stones and shrubs) still ignores paint until it is planted by
the workers too (the pending scatter consolidation); see
[3D board editor](3d-board-editor.md) for the decal-side limitations.

Nothing grows through stationary map content either. Blades, bank turf, reeds,
crops and the baked dressing reject a root that `BoardObstacles` reports for its
hex: structures, placed objects, bridge pier footings, and each bridge deck's
passage from the slab's underside to its top. Cover tall enough to reach a deck
at or near the ground is left out there, while the ground under a raised deck
keeps its grass. This is decided when the hex is built, never per frame: units
and meeples stand in the grass and never clear or bend it.

Two-level grassy banks include a shallow intermediate shoulder in their canonical
terrain profile. It admits roots through the existing slope filter, using the same
finished triangles as support and picking. Its ground material remains visible when
blade density fades at a distance, so both earth faces still read from above.

At visible grass scales, `GpuBankTurf` adds dense crowns and hanging fringes to both
contours. Its six authored silhouettes occupy a 3-by-2 transparent atlas at
`mm-data/data/models/board/textures/vegetation/bank-turf.png`. This atlas is the
authoring input; there is no verified regeneration command.
Its current Git blob is `3b49c883552538a9190d09d7dfdbab1d7866be39`, recorded in
mm-data commit `3d469cb44960722c8530fe25bc7066982f79fdb6`. Stable placement varies
the silhouette, mirroring, width, length and gaps, without repeating the immediately
previous variant. Curved crowns are visible overhead as well as from the side.

The terrain worker finds each contour on the installed triangles and fits the crown
to that carrier. Unsupported crowns and abrupt cliff-junction height changes reject
the entire clump, preventing stretched curtains. These are cosmetic foliage surfaces;
support and picking still use the underlying canonical terrain. Each grass chunk owns
one extra opaque cutout mesh, rebuilt only when its source geometry changes, with one
shared mipmapped atlas. The free ends use the existing vegetation wind. Turf receives
scene shadows but has no separate per-strand shadow pass.

The atlas's original olive color is not a fixed tint. Each clump carries the local
`BoardSurfaceBlend` proportions, samples the corresponding ground materials, and uses
the same world color fields and elevation grade as terrain. Atlas RGB supplies
linear-luminance detail in the .62–1.08 range, and alpha supplies the silhouette.
It is therefore a detail/coverage atlas, not authoritative turf
albedo: changing its hue does not recolor the crown independently of the ground.
Seeded per-clump vertex tone (.86–1 in authored display units) adds cosmetic
variation; the existing vegetation height gain is .995 for these crowns.
These pigment controls are separate from normals, wetness and lighting. Ground
matching avoids pale halos on raised tiles or green strips across authored
grass-to-sand gradients. The Grass blades control also hides these crowns.

Two shared blade templates contain three or seven triangles. The vertex shader
derives curvature, orientation, color variation and wind from the root; the
fragment shader adds lighting and wetness. The grass color pass has no draws
below its visible scale, one at a uniform orthographic scale, and at most two in
perspective. Grass receives shadows but does not cast a separate shadow per blade.

Density rises smoothly with projected hex size, currently between 120 and
500 pixels with up to 4,096 candidates per hex before filtering. Each chunk's
buffer holds all of its roots, bound once, and draws the prefix by rank that
each hex's own projected size needs as one range per hex
(`GpuInstancedMesh.drawRanges`): the instance attributes are pointed at the
range's first instance and a plain instanced draw follows, which works on any
OpenGL 3.3 and stays visible to libGDX's profiler. This submits about a seventh
of the blades a chunk-wide prefix would. Multi-draw-indirect would issue the
ranges in a single call, but the Intel driver tested rejects it intermittently.
Hexes out of view draw nothing. The shader evaluates density again at each
actual root. Blade width introduces fractional density continuously. The two blade
topologies switch at their detail threshold without a silhouette cross-fade.
Grass needs at least 120 projected pixels per hex. Full terrain detail is
requested from 64 pixels, but a full-detail chunk takes from a few hundred
milliseconds to seconds to build and arrives one chunk at a time, so medium
detail chunks carry grass roots as well (`BoardPlants`): a view that closes in
finds the blades already installed while the finer ground is still being built.
A chunk refined from coarse detail passes through medium detail first for the
same reason (`GpuTerrain.nextDetail`). Roots are sampled in the hex's plane and
dropped onto whichever ground is installed, so a root keeps its place and rank
at every detail level; when the full-detail chunk lands, only the heights change
with the ground under them, and the chunk uploads once without a blade moving.
Roots that do arrive while their hex is already on screen without any, the only
case left, grow in over half a second instead of appearing at once
(`GpuGroundCover.visible`); roots a chunk brought while off screen show
complete.

The meadow field varies blade height and thickness continuously, keeping shorter
growth in sparse patches. Blades full-coverage proportions:
height varies linearly from .025 to .07 of hex width before the meadow factor,
and width from .0015 to .0028. The realism pass's shorter, narrower distribution
was reverted at the user's request; root count and projected-density LOD were
unchanged. The shared maximum height also controls shrub sizing; altering blade
proportions must not unintentionally admit more shrub geometry through placement checks.
The meadow field is also used for ground color. Avoid introducing a
separate fragment cutoff or procedural field that makes bare ground disagree with
root placement. Grass is drawn after opaque terrain so its instances do not split
terrain material batches.

## Wind

`GpuTerrain` integrates a wrapped gust phase; changing strength changes its speed
without jumping its position. This phase is separate from water and rain clocks.
Zero strength leaves the plants still. Increasing strength increases both the
bend and the gust's travel rate.

Grass blends angular poses around fixed roots so slider steps remain useful
throughout the range. Crops keep leaves attached to their leaning stalks; marsh
plants form a deeper arch. Detailed, simplified and distant plant meshes use the
same world-space gust. Wind changes update shader inputs without regenerating
roots or uploading unchanged instance buffers.

## Cache and update boundaries

No grass is prepared on the render thread. A chunk's roots are planted by the
terrain worker that builds it at medium or full detail and replaced with it;
tiles an edit reuses keep their roots, except a hex whose paint changed: a
paint-only edit re-plants its cover on the worker from its installed support and
installs it with the new paint meshes, leaving its solid terrain untouched. An
unpainted hex pays one null check per candidate root. Planting costs about 0.4 ms per hex,
against several milliseconds of surface work per hex at either level, and a
grass hex holds about 65 KB of roots, so a medium chunk's grass is cheap to
plant but not free to keep; coarse and distant chunks carry none. A chunk's
instance buffer uploads again only when the installed roots of one of its hexes
change. Zooming changes the drawn prefix, panning changes which chunks draw, and
neither uploads anything.

The selected submission is reusable while the camera matrix, physical viewport,
scene tile list, ordered candidates and the installed roots of the drawn hexes
match. Camera movement, resizing and chunk replacement trigger selection again;
wind and lighting continue to update every frame.

Change placement or clearance in `plant`, appearance in the shader, and what a
detail level carries in `BoardPlants`.
