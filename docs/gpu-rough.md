# Rough terrain variants

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardFeatures` | Capture rough/fluff appearance and remove duplicate legacy decals when native geometry represents it. |
| `BoardRough` | Choose deterministic variant placements; supply the same occupied geometry to drawing, picking and support. |
| `BoardRelief / BoardRocks` | Build ordinary rough boulders, zero-gravity outcrops and their terrain contact. |
| `BoardScene.Tile.lunar` | At zero gravity, turn rough boulders into bedrock outcrops; in vacuum with gravity, keep them. |
| `GpuAssets` | Own the variant GLBs and shared textures; snapshots retain only placement data. |

The 3D board reads the existing rough terrain and its `fluff` value:

| Terrain | Appearance |
| --- | --- |
| `rough:*` | Existing geology-dependent boulders |
| `rough:*,fluff:1` | Staggered rows of flat-topped concrete Dragon's Teeth |
| `rough:*,fluff:2` | Charred snags, snapped trunks lying on the ground, broken branches |

Both rough levels support these appearances. Ultra rough has denser cover.
Adjacent rows of Dragon's Teeth alternate by half a tooth spacing. Every tooth
has the same width and height, across rows, hexes and both rough levels.
The charred standing trunks reach 1.8–2.1 elevation levels and have enlarged
footprints, comparable in height to the existing desert dead tree. Large pieces
alternate between snags standing on their roots and thick trunks lying on the ground,
each snapped into a crown of splinters. Smaller broken branches remain between them.
This is visual scale only; rough terrain does not gain woods rules.
Placement remains deterministic across snapshots and leaves road and bridge
approaches clear. Unsupported fluff values retain ordinary rough boulders and
the existing artwork fallback. Fluff alone does not create rough terrain.
Bridge clearance follows the complete shared road footprint, including dead
ends and the solid islands inside bridge roundabouts.
Cover displaced by a route moves onto the verges, keeping the hex's count where it fits.

`HexTile.roughVariant` (common code) supplies the appearance decision to scene capture and artwork
filtering. The two modeled variants keep detailed ground shading and remove the
consumed rough fluff decal. The board's terrain and movement rules are unchanged.

`BoardRough.place` grounds the authored meshes and supplies the same transform to
rendering, terrain picking, camera collision and unit footing. Ground sampling
without rough cover still excludes these obstacles. A model spanning a receding
edge or a slope steeper than 80% of its own height is omitted rather than left
floating. Both camera modes use the same meshes. Water geometry snapshots retain their obstacle placements.

The assets live in `mm-data/data/models/board/rough`. The dragon tooth (28 triangles) uses the shared concrete
texture. The timber (`felled-trunk`, `charred-stump`, `fallen-stump`, and the saw clearing's `sawn-stump` and
`sawn-log`) are game meshes of at most 500 triangles with two coarser levels, each with its own albedo, normal and
occlusion maps (`<name>_albedo/_normal/_occlusion.png`) baked from a dense sculpt in the timber photo materials
(`textures/scenery/timber-bark`, `timber-char`, `timber-endgrain`). A ROUGH feature of height 0 keeps its model's own
proportions: the sawn timber is modelled at real size. The rough pieces' maximum
footprint radii are three board units for standing stumps and six for the other pieces
before placement scaling, matching capture's route clearance. No GL resources
are retained in scene snapshots; GPU models and
textures remain owned by `GpuAssets`.

## Zero gravity

Without gravity nothing lies loose. The lunar presentation (`BoardScene.Tile.lunar`, see
[water](gpu-water.md)) keeps every third rough boulder of the hex's spiral as a bedrock outcrop
three times its size; the captured height stays its bound for headroom and picking. Dragon's
Teeth remain; felled timber goes with the other vegetation. In vacuum with gravity the same
presentation keeps every rough boulder as it is (in the LUNAR rock kit) and also drops felled
timber.

`BoardRelief.boulders` places outcrops through the boulders' own path, so they share the terrain
mesh, picking, unit support and road, bridge and tunnel clearance. They keep their authored
proportions, steepened by 30% to read from above, within the captured height. Their strike follows
a slowly varying board-wide field, so neighbouring formations align. A fifth of each lies below the
plain and its root is never above the downhill side; one near the hex edge shrinks to fit.

At zero gravity `terrain-sculpt.frag` shades the dry rock kit with the ground's own
`cliffMaterial` as fully exposed rock: bedding planes and skirts take the plain's cover in the
same world projection, scarps its wall. Occlusion toward the root remains only on scarps. Rim
formations and fallen blocks below cliffs share this shading but keep their placement.

The eight formations live in `mm-data/data/models/board/rocks/outcrop-N.glb`.