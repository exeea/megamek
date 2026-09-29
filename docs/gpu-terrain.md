# Terrain: from board data to a frame

The terrain renderer converts captured hex data into shared surfaces, placed
features and material inputs. Rules for movement, elevation, hazards and line of
sight remain in the game model. The classes here are in
`client/ui/clientGUI/boardview/gpu`; use the
[code responsibilities](gpu-code-map.md) for the full class map.

## Capture, geometry and installation

`BoardFeatures` captures terrain appearance, exits and feature heights.
`BoardArtwork` resolves tileset images and model references.
`BoardLiquid`, `BoardFireSmoke` and `BoardBiome` provide liquid, hazard and
biome inputs derived from the same scene.

`BoardGeometry` defines coordinates and scale. `BoardSurface` describes finished
topography, including road ramps, shores and beds. `BoardRelief` constructs the
shared natural tops, corners, cliffs and rims; `BoardConcrete` handles slabs and
foundations. Drawing, picking and unit support must agree on these surfaces.
A gap or misplaced unit therefore starts with geometric queries, rather than a
camera-specific visual offset.

`GpuTerrain` prepares terrain chunks from captured data on CPU workers, then
installs GPU resources on the render thread. Request generations reject old work.
Local edits invalidate the changed area plus neighbors needed by shared geometry
and fields. Camera detail changes select a representation of the same scene.

[Materials and geometry](gpu-terrain-materials.md) explains corners, cliffs,
textures and projections. [Updates and caches](gpu-board-performance.md) explains
the worker pipeline, detail selection, opaque batches and resource lifetime.

## Roads and structures

`BoardRoad` computes the road footprint and clearance mask.
`BoardSurface` owns physical approach heights; `BoardRampMesh` simplifies their
render mesh while preserving boundaries. `GpuRoads` carries road material masks
on the supporting triangles. Change the footprint, grade or appearance at its
respective owner so road drawing and ground contact continue to agree.

`BoardBridge` follows connected spans and classifies their deck material.
`BoardNaturalBridge` constructs natural arches; `BoardBridgeFooting` fits
manufactured ends onto banks. `BoardTunnel` supplies cosmetic cliff portals.
[Roads](gpu-roads.md), [slopes and tunnels](gpu-road-slopes-tunnels.md) and
[natural bridges](gpu-natural-bridges.md) describe the connection contracts.

For buildings, `GpuAssets` finds a custom kit or legacy model selected by the
tileset. `GpuBuilding` assembles seeded floor/roof recipes to the game's height;
`GpuBuildingInterior` derives slabs and supports from the occupied footprint.
Use [modular buildings](gpu-modular-buildings.md) for kit assembly and
[asset proportions](board-asset-proportions.md) for legacy models and bridge assets.

## Ground appearance and vegetation

`BoardSurfaceBlend` supplies a continuous material-contact field.
`GpuSurfaceBlend` refines shading attributes without changing the supporting
planes. The same family weights must reach color, normal, height and occlusion;
a color-only fade will not hide mismatched relief. See
[terrain contacts](gpu-terrain-contacts.md).

`BoardBiome` shares world-space rows, wet patches and growth patterns between
ground shading and plant placement. `GpuBiomeSurface` uploads per-hex attributes;
`GpuBiomeVegetation` prepares crops/reeds; `GpuGroundCover` prepares grass roots;
`GpuTreeInstances` draws repeated trees. Stable seeds and support identities keep
plants from moving when snapshots or detail levels change.
[Fields and wetlands](gpu-fields-marsh.md) and [grass](gpu-grass-rendering.md)
explain these paths. Small props use `BoardScatter`/`GpuScatter`; rough terrain
uses `BoardRough` where its geometry also matters to support and picking.

## Liquids and atmosphere

`BoardSurface` constructs liquid boundaries and beds. `BoardRiver` and
`BoardFlow` derive stream paths and visual currents.
`GpuWaterShader`, `GpuIceShader` and `GpuMagmaShader` bind the materials,
while `GpuOcean` supplies shared wave fields. Follow [water](gpu-water.md),
[ice](gpu-ice.md) or [magma](gpu-magma.md) when changing those surfaces.

`BoardAtmosphere` maps scenario conditions to visual lighting/weather.
`GpuAtmosphere` captures and composes scene color/depth, fog, exposure and shafts.
`GpuClouds`, `GpuCloudShadow` and `GpuWeatherParticles` provide cloud
attenuation and precipitation. `GpuTerrainEffects` renders captured terrain fire
and smoke before atmosphere composition. Their feature guides explain pass order
and ownership; [shaders](gpu-shaders.md) maps each GLSL family to its Java assembler.

Runtime board assets and their formats are documented with the assets in
[mm-data/data/models/board](../../mm-data/data/models/board/README.md).
