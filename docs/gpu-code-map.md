# GPU code responsibilities

[Docs index](README.md) · [Architecture and data flow](gpu-board.md)

This is the implementation map: each row identifies the code that owns a job and the
state it is allowed to change. Names below are Java files in
[boardview/gpu](../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/).
Use the feature guides for algorithms, asset contracts and coupled changes.

## Scene, window and capture

| Code | What it does |
| --- | --- |
| `GpuBoardWindow` | Creates and closes the native application/window; coordinates renderer switching and window reuse. |
| `GpuBattleView` | Orchestrates each frame: consumes snapshots, advances playback, places units and submits scene/UI passes. |
| `BoardSource` | Defines captured frame data and the input callbacks crossing between the Swing and GL threads. |
| `BoardScene` | Stores terrain, visible units, annotations and resolved animation events as presentation snapshots. |
| `GpuBoardSource` | Captures gameplay from shared client state, applies visibility filtering and returns commands to phase controllers. |
| `GpuMapSource` | Captures preview/editor boards, listens for local edits and routes editor input with generation checks. |
| `TerrainSettings`, `GpuBoardTuning` | Hold immutable build settings and expose live controls; a build must use the same settings as its displayed result. |
| `GpuDisplayScale`, `GpuWindowBounds` | Convert logical UI/framebuffer sizes and retain native window placement. |
| `GpuGraphicsCard` | Handles the Windows NVIDIA Optimus graphics-card preference at native startup. |

## Camera and geometric queries

| Code | What it does |
| --- | --- |
| `BoardCamera` | Owns tactical orbit/Free Flight controls, framing, zoom and camera transitions. |
| `BoardProjectionCamera` | Changes perspective/orthographic projection while retaining a common camera pose. |
| `BoardCameraCollision` | Sweeps the Free Flight eye against finished terrain triangles. |
| `BoardGeometry` | Defines board/world coordinate conversion, shared geometry queries and picking bounds. |
| `BoardSurface` | Owns a hex's finished ground, ramps, banks and beds used by rendering and support queries. |
| `BoardRelief` | Computes canonical shared corners, cliff profiles, transition bands, shores and decorative rock placement. |
| `BoardConcrete` | Fits constructed slabs and their boundary joins to adjoining terrain. |
| `BoardRampMesh` | Removes redundant ramp samples while retaining boundary vertices and bounded height error. |
| `BoardRim` | Builds CPU cliff-rim shading masks during bounded terrain preparation. |

## Roads, bridges and authored terrain

| Code | What it does |
| --- | --- |
| `BoardRoad` | Computes road footprints, joins, material-transition controls and clearance; physical heights come from the supporting surface. |
| `GpuRoads` | Turns footprints into material masks/atlases carried by existing terrain or bridge triangles. |
| `BoardBridge` | Follows span connectivity and approach roads to select deck shape and surface material. |
| `BoardBridgeFooting` | Extends manufactured slab/rails until their bank ends have actual terrain support. |
| `BoardNaturalBridge` | Builds hollow rock spans, bank contacts and underpass geometry. |
| `BoardTunnel` | Detects cosmetic portals at eligible cliff-facing road/deck exits and supplies their placement. |
| `BoardRocks`, `BoardShape` | Load reusable rock/scatter CPU mesh data; shape loading is separate from placement policy. |
| `BoardRough` | Places authored rough variants and supplies matching occupied geometry for drawing, picking and support. |
| `BoardScatter`, `GpuScatter` | Choose stable small-prop positions, then bake their textured drawing data into chunk batches. |
| `GpuBuilding` | Loads modular kits, validates module roles, stores assembly recipes and exposes module picking data. |
| `GpuBuildingInterior` | Builds floor sheets and columns clipped to the roof's actual occupied footprint. |

## Surface fields and vegetation

| Code | What it does |
| --- | --- |
| `BoardFeatures` | Captures terrain appearance, feature sizes, species and deterministic prop placement from live hexes. |
| `BoardLiquid`, `BoardFireSmoke` | Capture liquid/crust classification and existing fire/smoke facts without retaining mutable game objects. |
| `BoardBiome` | Provides world-space visual fields shared by ground shading and vegetation. |
| `BoardVegetation` | Supplies placement inputs; roots are supported by the installed finished terrain. |
| `BoardSurfaceBlend` | Evaluates cross-family material coverage at a world position, including height-limited cliff contacts. |
| `GpuSurfaceBlend` | Refines shading attributes on boundary faces and groups their material palettes while preserving surface planes. |
| `GpuBiomeSurface` | Packs per-hex biome data into a texture consumed by the terrain shaders. |
| `GpuBiomeVegetation` | Prepares field/marsh roots and draws crop/reed tiers with shared instanced buffers. |
| `GpuGroundCover` | Prepares deterministic grass roots, retains supported surfaces and draws GPU-shaped grass blades. |
| `GpuTreeInstances` | Instances trees and modular building parts; reuses buffers when placements and pass selections agree. |
| `TerrainLod`, `TreeLod` | Choose terrain sampling and tree mesh detail from projected size, with stable transitions. |

## Terrain installation, batching and lifetime

| Code | What it does |
| --- | --- |
| `GpuTerrain` | Owns chunk preparation/install/replacement, terrain resources, queries and terrain render passes. |
| `GpuAssets` | Owns shared board models, material maps and liquid assets; instances borrow these resources. |
| `GpuTextures` | Owns shared atlas allocation and texture lifetime. |
| `GpuTerrainPages`, `GpuMeshPage` | Pack nearby static ranges into spatial pages; visibility changes select ranges rather than rebuilding the page. |
| `GpuTerrainBatch` | Reuses compatible terrain material state between consecutive draws. |
| `GpuPropBatch` | Combines opaque prop ranges from visible chunks. |
| `GpuTerrainDepth` | Exposes depth-only ranges over installed opaque meshes for depth/shadow passes. |
| `GpuOpaqueSorter` | Orders opaque submissions while preserving required grass/transparent ordering. |
| `GpuInstancedMesh` | Owns the instanced-mesh binding behavior, including core-profile instance-attribute cleanup. |

## Atmosphere, weather and visibility

| Code | What it does |
| --- | --- |
| `BoardAtmosphere` | Maps settings to linear light, sun/moon direction, fog/sky colors, exposure and visual condition parameters. |
| `AtmospherePreset`, `GpuAtmosphereControls` | Describe complete condition previews and manage their window-local overrides/default reset. |
| `GpuAtmosphere` | Owns scene color/depth targets, fog/shafts, grading, glare and final composition. |
| `GpuClouds` | Generates and advects the common cloud-transmission atlas. |
| `GpuCloudShadow` | Shares the cloud atlas and its coordinates with every lit material. |
| `GpuWeatherParticles` | Maintains the bounded precipitation pool and supplies weather-specific particle programs. |
| `GpuFieldOfView` | Uploads client-derived visibility per hex; it shades visibility already decided by game/client rules. |

## Water, ice and lava

| Code | What it does |
| --- | --- |
| `BoardRiver`, `BoardFlow` | Derive shared stream paths and visual current directions from connected liquid surfaces. |
| `GpuWaterShader` | Binds water appearance and flow/depth fields, including receiving-pool interaction inputs. |
| `GpuMagmaShader` | Chooses solid/flowing magma programs and binds their common volcanic material inputs. |
| `GpuIceShader` | Applies the optical ice coat; hidden black ice must stay absent from its visible material data. |
| `GpuLiquidShader` | Animates authored liquid-frame textures for the artwork fallback path. |
| `GpuOcean` | Runs shared GPU FFT stages with separate water/lava spectra and finish programs. |
| `GpuWaterfall` | Builds/draws falling sheets, exposed cuts and landing spray from captured liquid geometry. |
| `GpuWaders` | Describes where posed units intersect water, supplying collars, ripples and wakes. |
| `GpuWaterImpacts` | Produces bounded spray and foam-ring effects for observed unit impacts. |

## Unit identity, assets and assembly

| Code | What it does |
| --- | --- |
| `UnitModelSelection` | Uses the tileset's model identity and captures the appropriate variant/figure count; sensor contacts expose no model identity. |
| `UnitModelState` | Captures immutable loadout, anatomy, surviving members, appearance and pose state after visibility filtering. |
| `UnitModelDescriptor` | Reads/validates the JSON body, rig, mount, emitter and recipe contract. |
| `RigidGlb` | Decodes rigid GLB geometry on the CPU and converts coordinates/colors while preserving named parts. |
| `MeshLod` | Resolves asset node names and missing-level fallback at load time. |
| `GpuUnitModels`, `GpuUnitModel` | Own the shared unit asset library and each loaded model's geometry/placement metadata. |
| `ModelTextures` | Caches textures for its owning asset library; borrowed models do not independently dispose them. |
| `GpuUnitInstance` | Holds one unit's posed nodes/material state; depth and outline views borrow the same buffers. |
| `UnitRig` | Resolves authored joint roles for a body or formation member. |
| `UnitEquipmentModels`, `UnitEquipmentAssembly` | Resolve equipment art and fit the captured loadout to every supported body family. |
| `UnitModelAttachment`, `UnitModelMountArea` | Hold per-instance equipment attachments and deterministically pack them onto mounting faces. |

## Unit shapes, support and appearance

| Code | What it does |
| --- | --- |
| `MekVisual`, `FamilyVisual` | Apply Mek anatomy or the common vehicle/aircraft/naval/ProtoMek/static-body presentation. |
| `InfantryVisual`, `BattleArmorVisual`, `SquadronVisual` | Compose troop formations, living suits or actual fighter members using shared body/equipment assets. |
| `InfantryMotion`, `InfantryFootprint` | Place cosmetic formation members and fit their positions inside the owning hex footprint. |
| `UnitFootprint`, `UnitBounds` | Translate game occupancy to model axes and compute bounds from posed imported parts. |
| `UnitFamilyScale` | Applies family-specific visual scale adjustments on top of board/world dimensions. |
| `UnitGroundContact`, `UnitLandingSupports` | Fit the placed body and landing gear to shared terrain support surfaces. |
| `UnitPicking` | Intersects the current posed rigid parts, including attachments. |
| `UnitDamageDisplay` | Hides lost locations or applies damage artwork to one unit instance. |
| `UnitCamouflage`, `GpuUnitCamouflage` | Resolve visible camouflage images on Swing, then own native texture/per-instance paint data. |
| `GpuUnitShader` | Shades unit paint/damage with the common lighting and shadows. |
| `GpuUnitIcons` | Shows optional flat unit artwork near overhead, following the existing animated poses and shared picking. |
| `GpuCutout` | Extrudes the alpha silhouette of raised sensor and location-symbol artwork. |
| `GpuUnitVisibility` | Draws occluded portions of authorized visible units/markers; it does not discover hidden entities. |
| `FormationLod` | Chooses body/figure mesh detail from projected size while preserving the instance's animation state. |

## Playback and effects

| Code | What it does |
| --- | --- |
| `UnitPlayback` | Orders resolved movement, attacks, scene checkpoints, concealment and attachment events on one GL-owned timeline. |
| `UnitMotion` | Interpolates a captured movement route and jump/fall motion without writing back to game paths. |
| `UnitAnimator` | Evaluates rigid poses from authored rest transforms, captured state and the playback clock. |
| `UnitAttack`, `UnitVolley` | Track attack timing and grouped target passes within that timeline. |
| `UnitConversion` | Orders fold/form-change/deploy presentation for transforming units. |
| `ArmFlip`, `UpperBodyTurn` | Track the displayed arm-flip and torso-turn interpolation. |
| `UnitAttachments` | Places exterior riders/swarmers after the carrier's final pose, shared by drawing, picking and effects. |
| `UnitAttachmentMotion` | Tracks boarding/release transforms and motion on the same playback clock. |
| `GpuAttackEffects` | Chooses visual attacks and their emitters from the posed loadout. |
| `GpuMissileEffects`, `GpuExplosionEffects`, `GpuJumpJets` | Render batched missiles, bounded fire/smoke volumes and visible jump exhaust. |
| `GpuTerrainEffects` | Maintains board fire/smoke emitters and their projected-size detail. |
| `GpuEffectBatch`, `GpuEffectDepth` | Batch effect geometry and lazily provide opaque scene depth for soft intersections. |

## Native controls and tactical graphics

| Code | What it does |
| --- | --- |
| `GpuBoardActions`, `GpuMenuCommands` | Describe existing phase/global commands and dispatch them after live-state checks. |
| `GpuBoardUi`, `GpuAttackPanel` | Present contextual actions, general controls and phase-owned weapon/physical-attack state. |
| `GpuBoardSkin`, `GpuPanelDock` | Own native skin/font assets and shared dock layout/visibility. |
| `GpuReportLog`, `GpuReportPanel`, `GpuReportText` | Capture already-filtered reports on Swing, display them and retain glyph-based link hit areas. |
| `BoardTacticalGeometry` | Tessellates/clips client-supplied tactical shapes and defines common hex-marker placement. |
| `BoardFiringGeometry`, `BoardDeploymentGeometry` | Build visual range/attack/deployment geometry from captured legality and boundaries. |
| `BoardEditorTerrain`, `BoardImpassable` | Describe editor/restriction markers without changing physical terrain or legality. |
| `GpuTactical`, `GpuFireControl` | Own native tactical meshes/text and depth-tested firing/range volumes. |
| `GpuMarkers`, `GpuHexText` | Place shared marker artwork and readable captured hex labels over the scene. |
| `GpuHexMasks`, `GpuHexSurface` | Apply regular hex masks to shared terrain carriers without duplicating clipped geometry per command. |

## Shader editing and compilation

| Code | What it does |
| --- | --- |
| `GpuShaderSource` | Resolves draft/disk sources and save destinations: installed overrides, checkout files, bundled resources. |
| `GpuShaderManager` | Tracks drafts/dependencies and prepares/commits live shader changes on the GL thread. |
| `GpuShaderProvider` | Keeps material shader handles stable while replacing compiled programs and their uniform caches. |
| `GpuShaderInputs`, `GpuShaderUniforms`, `GpuShaderValue` | Validate and apply typed uniform overrides through the shared upload path. |
| `GpuShaderEditor`, `GpuShaderInputPanel` | Provide Swing source editing and uniform-inspection controls. |
| `GpuShaderPreview`, `GpuShaderPreviewPanel` | Render isolated samples and exchange immutable preview requests/images with Swing. |
| `GpuGlsl` | Controls shader-language setup and the compatibility adapter for libGDX's built-in programs. |
| `GpuGlslTokenMaker` | Supplies GLSL syntax classification to the editor lexer. |

## Shared code outside the GPU package

| Code | What it does |
| --- | --- |
| `boardview/BoardClientState` | Owns client presentation state shared by renderers: tactical state, annotations, focus and client integration. |
| `boardview/BoardView` | Paints the classic board and bridges its input to the shared client state. |
| `boardview/BoardArtwork` | Selects/composes terrain artwork and maps selected tileset art to native assets; also serves printable board output. |
| `boardview/BoardTactical`, `BoardGlyphContext`, `UnitAnnotations` | Carry client-derived tactical shapes and annotation content into renderer-specific presentation. |
| `clientGUI/MapMenu`, `panels/phaseDisplay/*` | Own real contextual commands, orders and phase-specific availability. |
| `boardeditor/BoardEditorPanel` | Owns editable board state, brushes, file actions and undo transactions. |

Rules changes belong in the game/client/server owners described in [Codebase](codebase.md).
The GPU classes above receive the resulting state or execute the existing command.
