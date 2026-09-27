# Terrain decal remake checklist

Audited 2026-09-26 against `gpu-special-terrain-audit.md`, the current capture code, and the assets on disk. These are proposed runtime resolutions, not measured performance results. No artwork or renderer code was changed.

**Recommendation: 256 × 256 for ordinary terrain splats; 128 × 128 for simple marks and masks; 512 × 512 only for a detailed feature spanning several hexes.** Keep the underlying terrain visible outside the feature, with no baked grass, concrete, surrounding water, hex border, or large directional shadow.

## What was checked

- Recursively resolved all 38 tileset/include files used by the GPU's `saxarba.tileset`: **7,226 referenced image files**. None was missing. 7,225 are 84 × 72; the ultra-sublevel image is 78 × 71.
- Compared those images in `mm-data/data/models/board/tileset`, the original `mm-data/data/images/hexes`, and the application's `megamek/data/models/board/tileset`: all three copies matched byte for byte for these referenced files.
- Measured image dimensions and alpha coverage across the complete referenced set. Visually inspected the core special-terrain set and contact sheets of landing pads, urban decoration, markings, representative roads/transitions and symbols. Also inspected representative legacy mud/swamp and nuclear/orbital-impact assets outside the GPU tileset.
- All referenced GPU images have some non-opaque alpha; 7,224 also have fully transparent pixels. This does **not** establish that they are feature-only cutouts: many are still filled hexes with just the corners transparent, or contain an intentional concrete/soil surface beneath their feature.

The tables below cover the relevant GPU asset families and identify selected additional impact art. They are not an inventory of every alternative 2D tileset, unit sprite, portrait or UI image. File counts describe existing source files, not how many new textures should be made.

GPU asset root: [mm-data/data/models/board/tileset](C:/Projects/megamek/mm-data/data/models/board/tileset). Source patterns in the tables are relative to that root, unless stated otherwise.

## Extract the marking from its surface

These families contain paint or decoration together with a surface that the terrain/structure renderer should supply separately. Some already have transparency around the complete object; the task is to separate the layers inside that object.

| Family and existing sources | Current files | Transparent output to make | Runtime size |
| --- | ---: | --- | --- |
| VTOL pads: `saxarba/SMV_LandingPads/SpaceportSystem-01-LandingPad-01-VTOL-*.gif` | 6 | H and yellow ring only; remove the concrete/asphalt disk. One orientation-independent master can cover the rotations and both slab colours. | **256 × 256** |
| Medical pads: same directory, `SpaceportSystem-01-LandingPad-02-Medic-*.gif` | 6 | Medical landing symbol and yellow ring only. | **256 × 256** |
| Aerospace pads: same directory, `SpaceportSystem-01-LandingPad-03-Aero-*.gif` | 12 | Yellow perimeter, white guide lines and the authored marker details; no pavement. Preserve the two marking designs. | **256 × 256** per complete pad |
| Dropship pads: same directory, `SpaceportSystem-01-LandingPad-04-Dropship-*.gif` | 28 | Connected rings, guides and centre marks, separated from the slab. Preserve the two designs and their multi-hex placement. | **256 × 256** per reusable segment; **512 × 512** if represented by one complete multi-hex decal |
| Older helipads: `fluff/heli1.gif`, `heli2.gif`, `heli3.gif` | 3 | H and ring only. The deck and beacon lights are separate features. Reuse the new pad marking where the appearance matches. | **128 × 128**; 256 only for a large pad |
| Paved road paint: `saxarba/roads/road00–63.png`, `roadF*.png`, `roadH*.png`, `road_trees00–63.png` | 162 | Centre dashes, edge lines and junction markings separated from asphalt, kerbs and trees. Reuse strips and junction pieces with the authoritative exits. | **128 × 256** per strip; **256 × 256** per junction |
| Railway crossings: `hq_fluff/rail_road01–06.gif` | 6 | Separate rail/sleeper/crossing detail from the baked road surface. The track itself is legitimate feature content. | **256 × 256**, or **128 × 256** for reusable track strips |
| Sports-court markings: `saxarba/SMV_Fluff/FluffSystem-02-Sport-01-TennisCourt-*.png` and `...02-BasketBallCourt-*.png` | 12 | If the court surface is supplied separately: lines and painted key areas only. Two designs can replace colour/rotation variants. A complete coloured court is already a valid cutout if its surface belongs to the decal. | **256 × 256** per complete court; **128 × 256** when tightly fitted to its aspect |
| Elevator paint: `saxarba/misc/elevator_-10.png` through `elevator_10.png` | 21 | Hazard edging and direction symbol, with the displayed number derived from existing game state. Remove the concrete hex. Apply to the elevator platform, following its elevation. | **128 × 128** symbol; **64 × 128** repeating hazard strip |

Pads and courts still need their actual surface when that surface is part of the authored feature. Separating paint does not justify deleting the pad or drawing the markings directly over arbitrary grass. Likewise, road surfaces must remain connected and distinct by road type.

## Rebuild terrain artwork as reusable splats

Most of these already use alpha. Their remake is about a useful feature footprint, detail and material separation, rather than merely enabling transparency. Keep the original fallback until the new representation exists.

| Family and existing sources | Current files | What the splat should contain | Runtime size |
| --- | ---: | --- | --- |
| Rubble: `saxarba/misc/rubble_{light,medium,heavy,hardened,wall}.png` | 5 (4 unique images) | Small broken masonry, shards, dust and local contact shading; transparent gaps between clusters. Hardened and wall currently have identical files; their game states remain distinct. Large piles/slabs still need the geometry described in the terrain audit. Preserve rubble type; ultra-rubble can use greater coverage/density. | **256 × 256** debris clusters; **128 × 128** dust masks |
| Cleared rubble: `saxarba/rubble_{light,medium,heavy,hardened,wall}_path.png` and `saxarba/cleared_rubble_gravel.png` | 6 | Debris margins and gravel, retaining the clear route. Prefer reusing rubble clusters instead of five new full-hex images. | **256 × 256**; **128 × 128** gravel mask |
| Mud: `saxarba/misc/mud.png`, `deep_mud.png` | 2 | Irregular wet soil, shallow puddle margins and ruts; transparent exterior, without the current hex-shaped extent. Distinguish ordinary/deep mud through the terrain state. | **256 × 256** |
| Swamp: `saxarba/misc/swamp_{a,b,c,d}.png` and `saxarba/theme_{lunar,mars,volcano}/swamp_<theme>_{a,b,c,d}.png` | 16 | Wet patches, algae/reed litter and puddle margins. Avoid painting rocky ridges or an entire replacement ground tile. Actual wetness/liquid appearance needs the material treatment in the audit. | **256 × 256**; four reusable shapes are a reasonable starting set |
| Quicksand: corresponding `quicksand_{a,b,c,d}.png` and themed variants | 16 | Distinct sinking-soil patches, soft rims and subdued concentric disturbance. Keep separate from ordinary swamp. Theme tint can reuse compatible neutral art. | **256 × 256**; four reusable shapes initially |
| Fields: `saxarba/misc/field_{a,b,c,d,e,f}.png`, `saxarba/theme_lunar/field_lunar_{a,b,c}.png`, `saxarba/theme_volcano/field_volcano_{a,b,c}.png` | 12 | Soil furrows and exposed earth between rows. Remove painted crops where the crop model supplies them. Lunar/volcanic authored objects need separate treatment, not a recolour assumption. | **128 × 256** repeating row strip; **256 × 256** for a bounded patch |
| Ice: `saxarba/misc/ice.png` | 1 | Cracks, frost and broken edge detail with an irregular mask. The current translucent blue hex is a surface overlay, not an isolated crack decal. The ice/water surface remains a material/geometry task. | **256 × 256** cracks; **128 × 128** frost mask |
| Magma crust: `saxarba/base/base_crust_-3.png` through `base_crust_10.png` | 14 | Optional fissure/emission mask over the dark rock material. Do not recreate every elevation tint, or use a decal as the complete molten surface. | **256 × 256** fissure mask |
| Burned/felled woods: `UlyssesSprites/Rough/Rough-Fluff-BurnedFelledWoods1–3.png` | 3 | Ash, char, small splinters and ground litter. Existing images already have transparent surroundings. Preserve the authored fallen-wood feature; sizeable logs need props. | **256 × 256** |
| Geysers: `saxarba/misc/geyser_water_off.png`, `geyser_water_on.png`, `geyser_magma.png` | 3 | Mineral deposits, wet ring or char around the vent. Keep the vent and erupting steam/water/lava separate; never flatten the plume into a ground splat. | **256 × 256**, or 128 for a simple deposit mask |
| Fortified: `saxarba/misc/fortified.png` | 1 | Disturbed soil and contact dirt around an emplacement. The existing sandbag ring is already transparent; trench/sandbag shape is a geometry task. | **256 × 256** disturbed-ground patch |
| Thin snow and tundra: `saxarba/base/base_snow_light_*.png`, `base_tundra_*.png` | 28 + 14 | Optional sparse frost/snow coverage masks, using existing terrain materials. These are currently ground-family images, not individual objects to cut out. | **128 × 128** masks |

For long roads, rails and furrows, a narrow reusable strip preserves more useful detail per texel than an almost-empty square. If a uniform square-only authoring format is preferable, use 256 × 256 and pack tightly.

## Mixed custom decoration

These are conditional decomposition tasks when moving the authored feature onto engine terrain. They are not all broken alpha assets. Preserve the complete original appearance until its pieces have replacements.

| Existing family | Current files | Proposed treatment | Size if a surface decal is needed |
| --- | ---: | --- | --- |
| `fluff/cars_{1–8,2b,3b}.gif` | 10 | Separate vehicles/markings from the baked road, bay or platform. Cars should preferably become props; an interim vehicle cutout needs no road underneath. | **128 × 128** per vehicle; **256 × 256** parking marks |
| `fluff/construction1–3.gif`, `fluff/suburb1–3.gif` | 6 | Separate disturbed ground, paths and paving from cranes, buildings, pools and trees. | **256 × 256** ground patches |
| `fluff/garden1–6.gif`, `fluff/square1–6.gif`, `saxarba/SMV_Fluff/FluffSystem-07-Garden-03-Landscape-1-01–04.png` | 16 | Paths, beds and borders may become decals. Grass belongs to the ground material; trees/pillars/fountains retain their physical representation. A purposely coloured garden bed is feature content and need not be erased. | **256 × 256** |
| `fluff/pool1.gif`, `saxarba/SMV_Fluff/FluffSystem-02-Sport-03-SwimmingPool-1-01–05.png`, `...FluffSystem-07-Garden-04-Lake-1-01–05.png` | 1 + 5 + 5 | Already bounded cutouts. Separate any coping/shore detail only when replacing the authored pool/lake with corresponding water and structure geometry. Preserve its actual outline. | **256 × 256** edging, if needed |

## Already transparent: reuse or redraw for quality

These do not need a background-removal pass. Higher-resolution redraws are optional; enlarging the current 84 × 72 file does not create new detail.

| Asset family | Current files | Suggested size for a future master |
| --- | ---: | --- |
| `runway/runway_number_*.png`, `runway_middle_*.png`, `runway_start_*.png` | 30 | **128 × 128** numbers; **128 × 256** threshold/centre strips; 256 square only for larger detail |
| `saxarba/SMV_Letters/FluffSystem-04-Alphanumeric-*.png` | 216 | **64–128 × 64–128** per glyph, preferably sharing a glyph atlas and rotations |
| `saxarba/SMV_GroundSymbols/*.png` | 169 | **256 × 256** per distinct emblem; **512 × 512** only for a large multi-hex emblem. There are 13 designs with 13 variants each. Solid colours within the emblem are not unwanted ground backgrounds. |
| `hq_fluff/rail00–63.gif` | 64 | **128 × 256** track strip or **256 × 256** junction. Existing rails/sleepers are already isolated. |
| `unpaved_roads/dirtroad/`, `dirtroad_old/`, `gravel_road/`; `saxarba/misc/legacy_road0.png` | 98 + 62 + 98 + 1 referenced images | **128 × 256** reusable dirt/gravel strip if upgraded. The road material itself is intended feature content. |

Roof glass, skylights, vents, beacons, maglev trains/stations, livestock, seaport containers/cranes, dragon's teeth and orbital guns are objects or structure details. Their presence in `fluff` does not make them ground coatings. Buildings, tanks, trees, rough boulders and bridges likewise belong to their existing/planned model path. Do not remake all of them into flattened terrain decals.

The 250 `saxarba/Transitions/*.png` ground-fluff images are full-hex material blends. The eight `Structured_Pavement/Fluff/quay_fluff*.png` images paint concrete wedges. Use the shared terrain/material and constructed-shore systems for these, retaining any necessary fallback until the authored meaning is supported. Do not commission 258 separate high-resolution splats.

## New or separately sourced effects

| Feature | Existing coverage | Recommendation |
| --- | --- | --- |
| Scorch/ash beneath terrain fire | `saxarba/misc/fire.png` and `inferno.png` are already transparent flame sprites; they are not ground scorch assets. | Make **128 × 128** small soot and **256 × 256** broad char splats; retain flame/ember effects separately. |
| Black ice | No dedicated image/rule was found in the loaded GPU tileset. | New **128 × 128** coverage/roughness mask; a material glaze over the existing surface, rather than a blue opaque tile. |
| Collapsed basement | The loaded tileset maps `bldg_base_collapsed:*` to `saxarba/misc/blank.png`. | New **256 × 256** dust/debris edge only as a companion to the bounded cavity geometry. There is no meaningful existing collapse picture to extract. |
| Nuclear and orbital impact scars | Outside the GPU tileset: `mm-data/data/images/hexes/nuke/hit/*.png` and `orbital_bombardment/hit/*.png`, **162 images each**, all 84 × 72. They are slices of large impact artwork, not 324 independent crater designs. | If ported to physical terrain splats, use **512 × 512** per complete large scar plus **128–256** repeatable soot/detail. Rebuild the nuclear scar's background and extreme baked lighting; the orbital set already includes substantial transparency. Preserve existing effect visibility and placement. |

Smoke, screen and erupting geyser plumes are volume/particle work. Impassable, deployment, repair and metal-content information belong to the appropriate tactical layer. Do not create terrain textures that invent visible geology or reveal hidden state.

## Lightweight export specification

- Deliver **PNG with real alpha** for colour decals. Use a true overhead view, no hex silhouette except where the feature itself is hexagonal, no backdrop, and no baked directional light. Soft edges should fade to alpha zero, not to white/black.
- Fill most of the useful texture area while retaining a small transparent margin. Preserve colour beneath transparent edge pixels so filtering does not add dark/white fringes. Atlas gutters and mip generation must suit the sampled mip levels.
- Use one shared source for rotations and matching colour variations. Reuse the same loaded texture for many placements. Preserve original game terrain identifiers/exits and intentional art differences.
- Start with albedo + alpha. Add normal/roughness data only where close-up inspection justifies the extra storage. A shader mask can be 128 square when existing terrain materials supply the surface detail, but the current RGBA atlas does not automatically save GPU memory for a grayscale PNG.
- Use mipmaps for the future splat path, with suitable atlas padding. Transparent empty pixels still consume texture memory; PNG file size does not determine GPU allocation.

Calculated texel storage for one uncompressed RGBA8 image (four bytes per pixel):

| Size | Base level | Complete mip chain, approximately |
| --- | ---: | ---: |
| 128 × 128 | 64 KiB | 85.3 KiB |
| 256 × 256 | 256 KiB | 341.3 KiB |
| 512 × 512 | 1 MiB | 1.33 MiB |

512 square costs **four times** as much as 256 square. Sixty-four unique 256-square RGBA8 decals are about **21.3 MiB with full mip chains**, before atlas padding, CPU copies and additional maps. Repeated placements need not add texture copies. These are storage calculations, not a frame-rate benchmark. Format reference: [Khronos texture image formats](https://wikis.khronos.org/opengl/Image_Format).

## Renderer constraint to address before judging the remakes

[BoardView.captureGroundArtwork](C:/Projects/megamek/megamek_temp/megamek/src/megamek/client/ui/clientGUI/boardview/BoardView.java:5265) draws the base terrain and then its overlays into an **84 × 72** image. Roads, mud, swamp, ice, fields and rubble go through that ground capture. [drawDecals](C:/Projects/megamek/megamek_temp/megamek/src/megamek/client/ui/clientGUI/boardview/BoardView.java:5358) also captures into 84 × 72. Merely replacing the source PNG with a 256/512 image therefore does not preserve that resolution in the GPU result or remove a base that capture adds back.

The new surface-following splat path must preserve each source decal's alpha and useful resolution separately from the ground material. Reuse existing atlas/resource ownership and terrain state. [GpuTerrain](C:/Projects/megamek/megamek_temp/megamek/src/megamek/client/ui/clientGUI/boardview/gpu/GpuTerrain.java:90) currently enables mipmaps for ground artwork, but not for its `decals` atlas. [GpuTextures](C:/Projects/megamek/megamek_temp/megamek/src/megamek/client/ui/clientGUI/boardview/gpu/GpuTextures.java:133) packs RGBA8888; switching to a compressed GPU texture format would be additional implementation, not a PNG export setting.

Recommended first batch: rubble and cleared-path detail, ordinary/deep mud, swamp and quicksand, field furrows, road paint, and pad markings. Keep ice/magma masks alongside their material work, and use 512 only after a close-up comparison shows 256 is inadequate.
