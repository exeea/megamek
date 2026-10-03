# Developer docs

MegaMek's shared game model and server own rules and resolved state. Clients
present that state and submit orders. This checkout includes a native libGDX board
alongside the classic Swing view.

Start with [Codebase](codebase.md) for the application and action flow.
For native work, read [GPU board](gpu-board.md), then use the
[GPU code responsibilities](gpu-code-map.md) to find the owner of a behavior.
The feature guides explain the implementation boundaries and asset contracts.

## Application, units and interaction

| Guide | What it explains |
| --- | --- |
| [Codebase](codebase.md) | Main packages, authoritative state, networking, rules, bots and data loading. |
| [GPU board](gpu-board.md) | Capture-to-frame flow, renderer lifecycle, cameras, threads and resource ownership. |
| [Unit models](unit-models.md) | Model selection, rigid rigs, equipment, animation, damage, support and detail. |
| [Battle Armor attachments](battle-armor-attachments.md) | Carrier grips, boarding/release and packet/playback ordering. |
| [UI and controls](controls-and-interaction.md) | Phase commands, input routing, native panels and tactical overlays. |
| [Overlays and occlusion](gpu-overlays.md) | Tactical geometry, field of view, point markers, labels, cutaways and see-through outlines. |
| [Attack controls](gpu-attack-controls.md) | Weapon/target selection, pending orders, range walls and attack arrows. |
| [Map editor](map-editor-3d.md) | Shared tools/undo, renderer switching, gestures and terrain annotations. |

## Terrain and rendering

| Guide | What it explains |
| --- | --- |
| [Terrain overview](gpu-terrain.md) | How captured hexes become surfaces, features and render passes. |
| [Materials and geometry](gpu-terrain-materials.md) | Shared corners, cliffs, slopes, concrete, texture channels and world projection. |
| [Terrain contacts](gpu-terrain-contacts.md) | Cross-material boundaries, cliff-foot deposits and shading-only refinement. |
| [Roads](gpu-roads.md) | Footprints, masks, graded approaches, bridge inheritance and chunk invalidation. |
| [Slopes and tunnels](gpu-road-slopes-tunnels.md) | Road grades, solid cliff boundaries and cosmetic tunnel portals. |
| [Natural bridges](gpu-natural-bridges.md) | Span classification, arches, bank contact and manufactured bridge footings. |
| [Water](gpu-water.md) | Shores, beds, streams, falls, waves, optical fields and unit interactions. |
| [Ice](gpu-ice.md) / [Magma](gpu-magma.md) | Material inputs, terrain meaning and special visibility/flow behavior. |
| [Fields and wetlands](gpu-fields-marsh.md) | Ground patterns, planted rows, reeds, support and liquid mixing. |
| [Grass](gpu-grass-rendering.md) | Root preparation, instancing, projected density, cache reuse and shared wind. |
| [Orchards](gpu-orchards.md) / [Understory](gpu-understory-foliage.md) / [Rough](gpu-rough.md) | Feature selection, placement, authored variants and clearance. |
| [Modular buildings](gpu-modular-buildings.md) / [Asset proportions](board-asset-proportions.md) | Module contracts, interior footprints, legacy dimensions and bridge assets. |
| [Fire and smoke](gpu-terrain-fire-smoke.md) | Terrain hazard capture, volumetric drawing, lighting and detail. |
| [Atmosphere](gpu-atmosphere.md) / [Clouds](gpu-clouds.md) | Scenario mapping, light, fog/composition, cloud shadows and wetness. |
| [Shaders](gpu-shaders.md) | Source families, program assembly, live drafts, inputs and saving. |
| [Updates and caches](gpu-board-performance.md) | Worker/install boundaries, detail, page batching, support and shadow invalidation. |

## Build and run

Use JDK 21 and keep `mm-data` beside this checkout.
`settings.gradle` includes `../mm-data`, which supplies maps, unit definitions,
images, models and their authoring tools.

From the repository root on Windows:

```powershell
.\gradlew.bat :megamek:compileJava
.\gradlew.bat :megamek:run
```

Use `./gradlew` on other systems. The `run` task stages assets from `mm-data/data`
into `megamek/data`. Make durable asset edits in `mm-data`, because staging
refreshes the runtime copy. Tasks are defined in `megamek/build.gradle`.

Java source starts at `megamek/src/megamek`; native rendering is under
`client/ui/clientGUI/boardview/gpu`. Locate a named class with
`rg --files megamek/src | rg 'ClassName'`, then use
`rg -n 'methodName' megamek/src` to follow its callers.
