# Rough terrain variants

The 3D board reads the existing rough terrain and its `fluff` value:

| Terrain | Appearance |
| --- | --- |
| `rough:*` | Existing geology-dependent boulders |
| `rough:*,fluff:1` | Staggered rows of flat-topped concrete Dragon's Teeth |
| `rough:*,fluff:2` | Standing and toppled charred stumps, fallen trunks and broken branches |

Both rough levels support these appearances. Ultra rough has denser cover.
Adjacent rows of Dragon's Teeth alternate by half a tooth spacing. Every tooth
has the same width and height, across rows, hexes and both rough levels.
The charred standing trunks reach 1.8–2.1 elevation levels and have enlarged
footprints, comparable in height to the existing desert dead tree. Large stumps
alternate between standing and lying on the ground, with the toppled version
reusing the same broken rim, bark and roots. Smaller logs remain between them.
This is visual scale only; rough terrain does not gain woods rules.
Placement remains deterministic across snapshots and leaves road and bridge
approaches clear. Unsupported fluff values retain ordinary rough boulders and
the existing artwork fallback. Fluff alone does not create rough terrain.
Bridge clearance follows the complete shared road footprint, including dead
ends and the solid islands inside bridge roundabouts.

`BoardRough.variant` supplies the appearance decision to scene capture and artwork
filtering. The two modeled variants keep detailed ground shading and remove the
consumed rough fluff decal. The board's terrain and movement rules are unchanged.

`BoardRough.place` grounds the authored meshes and supplies the same transform to
rendering, terrain picking, camera collision and unit footing. Ground sampling
without rough cover still excludes these obstacles. A model spanning a receding
edge or a slope steeper than 80% of its own height is omitted rather than left
floating. Both camera modes and terrain detail levels use these same low-poly
meshes. Water geometry snapshots retain their obstacle placements.

The four assets live in `mm-data/data/models/board/rough`. Rebuild them with
`python tools/build_rough_assets.py` from the mm-data checkout. The script uses
the existing rigid mesh writer and concrete/bark textures, and updates the board
asset manifest. The tooth has 28 triangles, trunk 91, and each stump pose 112. Their maximum
footprint radii are three board units for standing stumps and six for the other pieces
before placement scaling, matching capture's route clearance. No GL resources
are retained in scene snapshots; GPU models and
textures remain owned by `GpuAssets`.

`BoardRoughTest` checks appearance, uniform tooth dimensions, mixed stump poses on
level ground, deterministic placement, clearance, exact mesh agreement with
picking/footing, and water snapshot reuse. `GpuRoughCaptureTest`
checks live source edits and duplicate decal removal. `GpuRoughSmokeTest` renders
ordinary rough beside both variants, captures top/oblique and close views, checks
picking, and replaces a variant through the terrain update path.

Verified on 2026-09-28: all 57 selected tests in `BoardRoughTest`,
`GpuRoughCaptureTest`, `BoardFeaturesTest`, `BoardRocksTest`, `RigidGlbTest` and
`BoardGeometryPickingTest` passed, along with `checkstyleMain`. The native
`GpuRoughSmokeTest` passed on this Windows machine; its top, oblique and close
renders were inspected with uniformly sized, staggered teeth and the large
standing/fallen stump mixture.
The asset generator's triangle counts and footprint bounds were also checked.
Verification used an isolated Gradle project cache and build directory because
other builds shared this checkout. Existing staged assets were verified against
their sources and reused after an open application locked a staged font file.
