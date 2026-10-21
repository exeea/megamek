# Board materials

Every board material is a Godot 4 material resource. Almost all are a `StandardMaterial3D`,
exactly as Material Maker's **Godot/Godot 4 Standard** export writes it. The three with a
map that a standard material has no slot for (lava, crust and ice) are a `ShaderMaterial`,
which is how Godot itself adds maps to a material ([below](#shadermaterials)). Artists who
know Godot, or any engine that reads a standard PBR material, can read and replace these
files. No custom export target or packed files remain.

## Files

A material named `terrain/surfaces/grass` is
`mm-data/data/models/board/textures/terrain/surfaces/grass.tres` plus its maps beside it:

| File | Content |
| --- | --- |
| `<name>.tres` | The `StandardMaterial3D` resource: which maps it uses, and its factors. |
| `<name>_albedo.png` | sRGB colour; alpha is opacity where the material is transparent. |
| `<name>_orm.png` | Linear R ambient occlusion, G roughness, B metallic. |
| `<name>_normal.png` | Tangent-space normal, OpenGL convention (+Y up the image), as Godot and glTF store it. |
| `<name>_heightmap.png` | Grey height, white highest (Godot's `heightmap_texture`). |
| `<name>_emission.png` | Emission colour (sRGB). |
| `<name>_sss.png` | Subsurface scattering strength (R). |

A material uses only the maps its resource names. `GodotMaterial` reads the resource:
Material Maker's `format=2` text and the Godot 4 editor's `format=3` text (quoted ids,
`uid`, `res://` paths) both work. A `res://` path is relative to the textures folder, so open
`mm-data/data/models/board/textures` itself as the Godot project to edit them there; its
`project.godot`, `.godot/` and `.import` files are git-ignored. Map paths must stay inside the
textures folder. The game reads these `StandardMaterial3D` properties and ignores the rest:

| Property | Use |
| --- | --- |
| `albedo_texture`, `albedo_color` | Colour, times the tint. |
| `normal_enabled`, `normal_texture`, `normal_scale` | Normal; a scale other than 1 scales its slope. |
| `ao_enabled`, `ao_texture`, `ao_texture_channel` | Ambient occlusion. |
| `roughness`, `roughness_texture`, `roughness_texture_channel` | Roughness: the factor times the channel. |
| `metallic`, `metallic_texture`, `metallic_texture_channel` | Metallic: the factor times the channel. |
| `heightmap_enabled`, `heightmap_texture` (or `depth_texture`), `heightmap_flip_texture` | Height, white highest. |
| `heightmap_scale`, `heightmap_deep_parallax`, `heightmap_min_layers`, `heightmap_max_layers`, `heightmap_flip_tangent`, `heightmap_flip_binormal` | Parallax ([below](#parallax)); for magma the relief range too. |
| `emission_enabled`, `emission`, `emission_operator`, `emission_texture`, `emission_energy` (or `emission_energy_multiplier`) | Emission. |
| `subsurf_scatter_enabled`, `subsurf_scatter_texture`, `subsurf_scatter_strength` | Subsurface scattering. |

## Finish: one meaning on every surface

Whatever draws a material (the sculpted terrain and its covers, turf and meadow blades, roads,
buildings, props and scenery, classic tiles, industrial plants, trees, shrubs and fungus), it
reads the same channels from the material's `.tres`, with Godot's meaning, so a new export is
all a change needs: grass that glows, metallic sand or shrubs that scatter light.

| Channel | Godot's rule, as the game reads it |
| --- | --- |
| Roughness | `roughness` times the roughness map's channel; the factor alone without a map. |
| Metallic | `metallic` times the metallic map's channel; the factor alone without a map. The metal share reflects the albedo and diffuses none. |
| Subsurface scattering | Only where enabled: `subsurf_scatter_strength` times the scattering map's red. Thick bodies wrap the light past the terminator by that share; leaves, which are thin tissue, pass it through to their far side. |
| Emission | Only where enabled: (`emission` + map) × energy under the add operator (Godot's default), `emission` × map × energy under multiply. `emission` is an sRGB colour, black by default. A uniform map counts as a constant. |
| Ambient occlusion | The occlusion map's channel. |
| Normal | The normal map, scaled by `normal_scale`. |
| Height | Parallax where the surface draws one material through its own UVs ([below](#parallax)). |

Covers that bury a material keep none of its metal, scattering or glow: churned and charred
ground, scorch deposits, mud, marsh peat and tundra mats (which bring their own finish). A
material's own wear, wetness and the distance fade of its grain (to the map's means, which costs
no texture read) change its roughness only.

`material-finish.glsl` holds the shared record (`Finish`) and its blending;
`surface-shading.glsl` lights it (`metallicShare`, `Surface.scatter`, `Surface.emission`).
Two contracts carry it to the shaders:

- Array-drawn materials, the sculpted surfaces and the road materials, have their finish in the
  terrain's finish arrays (`finish-arrays.glsl`, `GpuAssets.finishArrays`): an RGBA8 layer of
  roughness, metallic and scattering for each material whose finish varies, an sRGB layer for each
  emission map, and per material its values or means, emission tint and constant glow. Sculpt
  materials sit at their sculpt layer's index, road materials from `ROAD_FINISH`.
- A draw of one material, a model's or the board's own (`GpuModelMaterial`, `GpuModelRelief`):
  roughness, metallic and scattering factors over a `METALLIC_ROUGHNESS` map, which carries the
  factors itself, the emission map as the emissive texture times its tint, plus the glow, and the
  heightmap parallax. A GLB material whose base colour is a board material's `_albedo.png` *is*
  that board material: its `.tres` replaces the GLB's own metallic-roughness and adds every map
  the GLB leaves out (normal, occlusion, emission, height). `GpuAssets.finish` gives the board's
  own single-material draws (classic tiles, industrial plants) the same.

What keeps its own shading:

- Lava, crust and ice are `ShaderMaterial`s with shaders of their own ([above](#shadermaterials)).
- A GLB material with no board texture is the GLB's own glTF material: its roughness, metallic and
  emissive factors. On a plant it keeps its role's response (bark .90, snow .95, leaves passing
  .35 of their light), and untextured mycelium glows its own vertex colour.
- Impostor cards draw their plant's baked look; fungal glow breathes slowly across a colony.
- Crop rows and marsh sedge, composited at run time from their artwork, keep their blades'
  shared response.

### Parallax

Godot's heightmap parallax, where a surface draws one material through its own UVs: models
(`model-surface.glsl`) and roads (`terrain-road.frag`). The view, in the UV frame, moves every map
read of the material by up to `heightmap_scale` / 100 in UV, white high; with
`heightmap_deep_parallax` it marches from `heightmap_max_layers` at grazing views to
`heightmap_min_layers` head-on and interpolates the crossing, as Godot does, otherwise it takes
one step. A relief shallower than half a pixel moves nothing visible, so its reads are skipped:
parallax costs nothing at board distances. The sculpted terrain blends several materials by their
heights in one pixel and keeps their relief in its normals and height blending.

### ShaderMaterials

Three materials need one map that a standard material has no slot for, and colour data a
standard material would misread. Each is a `ShaderMaterial` of the `<name>.gdshader` beside it:

| Material | Extra parameter (map) | Colour space |
| --- | --- | --- |
| `terrain/lava/lava` | `texture_coverage` (`lava_coverage.png`): exposed-melt coverage | `texture_emission` is linear radiance, times `emission_energy` (5) |
| `terrain/lava/crust` | `texture_glow` (`crust_glow.png`): fissure glow | `texture_emission` is linear heat in R, which the shader colours |
| `terrain/ice/sheet` | `texture_cracks` (`sheet_cracks.png`): cracks and bubbles | `texture_albedo` is a tint whose hue alone matters |

The standard maps and factors take the names of Godot's own converted shaders: `albedo`,
`texture_albedo`, `roughness`, `metallic`, `texture_normal`, `normal_scale`,
`texture_heightmap`, `heightmap_scale`, `texture_emission`, `emission_energy`,
`texture_subsurface_scattering` and `subsurface_scattering_strength`. One `texture_orm`
holds occlusion, roughness and metallic in R, G and B, as Godot's ORMMaterial3D packs them.
As in Godot, a parameter the resource omits takes the default the shader declares, and a
parameter the shader does not declare has no effect.

The `.gdshader`s are previews for Godot only: each declares the parameters, with hints that
give each map's colour space, and draws a static surface close to the game's look. The game
reads the parameters and renders these materials with its own shaders (`terrain-magma.glsl`,
`magma-flow.glsl`, `magma-solid.glsl`, `terrain-ice.glsl`), which add the board's flow, banks,
floes and edges.

Material Maker cannot write a `ShaderMaterial`. Its projects export the standard maps, plus
the extra map from a stock Export node, and `export.py` turns Material Maker's
`StandardMaterial3D` into the `ShaderMaterial` (see `mm-data/tools/materials/README.md`). A
`StandardMaterial3D` with a `<name>.gdshader` beside it, which an editor export leaves, is
refused with an error naming `export.py --wrap`.

## How the renderer packs them

`GpuMaterialMaps` repacks the files on the CPU into the GPU layouts the shaders have
always read, so the shaders and the texture-unit budget are unchanged. Packing moves
bytes between channels unchanged. Two exceptions: the normal's green is flipped from
Godot's "up the image" to the board's "down the image" (+V), and Godot's factors apply.
Converting the previous packed maps into this format and packing them again gives
the previous bytes exactly.

| Layout | Channels | Used by |
| --- | --- | --- |
| `COLOR` | Albedo RGBA | Everything else's colour |
| `COLOR_HEIGHT` | Albedo RGB, height | Sculpted terrain (`terrain/surfaces`) |
| `NORMAL` | Normal XYZ, opaque | Roads, magma, ice, models |
| `NORMAL_OCCLUSION` | Normal XYZ, occlusion | Sculpted terrain |
| `NORMAL_ROUGHNESS` | Normal XY, roughness, occlusion | Mud and scorch, whose shaders rebuild Z |
| `RELIEF` | Height, roughness, occlusion, `heightmap_scale` × 25.5 | Roads and magma |
| `ICE` | Height, roughness, cracks, cloudiness | Ice |
| `OCCLUSION` | Occlusion, roughness, transmission | Models' glTF occlusion map |
| `METALLIC_ROUGHNESS` | Scattering, roughness, metallic | A model's or single draw's finish map |
| `HEIGHT` | Height, white high | A model's parallax |
| `MOLTEN_HEAT` | Emission RGB, coverage | Molten lava |
| `CRUST_HEAT` | Heat, –, –, glow | Solid crust |

`heightmap_scale` is Godot's parallax depth in texture coordinates times 100: 10 is the
full range, 0.1 of a repeat. Magma's relief takes it too. For sculpted terrain it is
informational (100 × relief metres ÷ repeat metres): their relief comes from the
shared terrain shading.

Models (`.glb`) name these maps directly: a base colour `<name>_albedo.png`, a normal
`<name>_normal.png`, and an occlusion or metallic-roughness `<name>_orm.png`.
`ModelTextures` recognises a board material's map by its `.tres` beside it. The model
then draws it in the layout its glTF role takes, sharing the board's own texture.

### Sculpted terrain finish

Sculpted terrain takes each material's whole finish from the finish arrays. A uniform
finish is sent as its values; one that varies is a layer, resampled to the largest map
there, as the colour arrays are. Roughness 1 is fully rough: the game estimates no
roughness of its own for any material.

Every role of a natural surface (cover, mantle, debris and rock, on tops and walls)
reads its material's finish with the same projection as its colour, and so do tundra
mats, turf and meadow blades (their ground's or their grass's), road coats, pavement,
tree pits and rock kits. On natural surfaces and tundra mats, where the normal detail
fades out with distance, roughness, metallic and scattering fade to their means, which
costs no texture read; emission keeps its map at every distance. Mud and scorch also
keep their roughness in their own normal maps, which their shaders read.

## Loose and constrained materials

`textures/materials.json` sorts every material into one of two kinds:

- **Loose** materials repeat across the board in world space: terrain surfaces, roads,
  lava, ice and industrial plating. Each entry gives the width (`pixels`) of the texture
  it was catalogued at. Terrain surfaces also give the metres one repeat spans at that
  width (`metres`); for the others, the renderer fixes the repeat.
- **Constrained** materials fit a footprint: facades, classic tiles, scars, scenery and
  plant cutouts. Their size never changes what they cover.

A loose material keeps its texel density. Exported wider than catalogued, it covers
proportionally more ground before it repeats: grass catalogued at 512 pixels and 4 m
covers 8 m at 1024 pixels. `GpuAssets.coverage` gives that factor (texture width ÷
`pixels`), and each world-space renderer multiplies its repeat by it, including the
enlarged secondary sample that breaks up terrain repetition. A loose material's maps
must be square. A terrain array holding a wider material takes its grid, and the
smaller materials are resampled up to it. To change a density instead, change
`metres` (or `pixels`) in the catalogue. *Reload assets* in the Tuning panel re-reads the
catalogue with the maps and rebuilds the board, so the new density shows at once.

### Loose (47)

| Material | Catalogued pixels | Metres |
| --- | --- | --- |
| `roads/asphalt`, `roads/dirt`, `roads/gravel` | 1024 | – |
| `structures/industrial/paint`, `structures/industrial/steel` | 1024 | – |
| `terrain/ice/sheet` | 1024 | – |
| `terrain/lava/crust`, `terrain/lava/lava` | 1024 | – |
| `terrain/surfaces/tree-pit-mulch` | 512 | 1 |
| `terrain/surfaces/gravel`, `pavement` | 512 | 3 |
| `terrain/surfaces/grass`, `lunar-scree`, `scree`, `soil-contact`, `tropical-ground` | 512 | 4 |
| `terrain/surfaces/dirt` | 512 | 5 |
| `terrain/surfaces/concrete`, `desert-hardpan`, `mars-hardpan`, `sand`, `volcano-ground` | 512 | 6 |
| `terrain/surfaces/basalt-cap`, `cast`, `fungus-mat`, `granite-contact`, `lunar`, `mars-cap`, `rock`, `sandstone-cap`, `snow` | 512 | 8 |
| `terrain/surfaces/fungus-fibres`, `granite`, `mars-bedrock` | 512 | 10 |
| `terrain/surfaces/fungus-cliff`, `loose-sand`, `lunar-cliff`, `sandstone`, `volcano-basalt` | 512 | 12 |
| `terrain/surfaces/earth` | 512 | 20 |
| `terrain/surfaces/fungus-ground` | 512 | 22 |
| `terrain/surfaces/tundra-earth`, `tundra-earth-variant`, `tundra-fungus` | 512 | 24 |
| `terrain/surfaces/tundra-crust` | 512 | 28 |
| `terrain/surfaces/mud`, `scorched` | 512 | 64 |

### Constrained (71)

- `scenery/`: `demolition-fines`, `demolition-materials`, `geyser-travertine`, `pool-water`, `tunnel-concrete`
- `structures/facades/`: `fortress`, `hangar`, `hard`, `heavy`, `industrial`, `light`, `medium`, `tank`
- `structures/industrial/`: `fan`
- `terrain/classic/`: `concrete`, `dirt`, `fungus`, `high_incline_dark`, `incline_dark`, `lunar`, `mars`, `rock`, `sand`, `snow`, `volcano`, `water_bed`
- `terrain/damage/`: `ballistic-blast`, `ballistic-gouge`, `blast`, `burn`, `laser-blast`, `laser-gouge`, `plasma-splash`, `ppc-blast`, `ppc-gouge`
- `vegetation/`: `bank-turf`, `bark`, `bark-birch`, `bark-palm`, `broadleaf-cutout`, `broadleaf-snow-cutout`, `cactus-flower-cutout`, `cactus-skin`, `conifer-cutout`, `conifer-snow-cutout`, `crop-row-front`, `crop-row-side`, `crop-row-top`, `fern-cutout`, `fronds-palm`, `marsh-sedge`, `palm-frond-cutout`, `shrub-cutout`, `snow`
- `vegetation/fungus/`: `fungus-organic-atlas`
- `vegetation/mars/`: `coral-mineral-atlas`
- `vegetation/orchard/`: `orchard-leaning`, `orchard-round`, `orchard-spreading`, `orchard-upright`, `orchard-vase`, `orchard-young`
- `vegetation/shrubs/`: `barren`, `desert`, `highland`, `rocky`, `snow`, `temperate`, `wetland`
- `vegetation/volcano/`: `cinder-bark`, `cinder-cutout`

The three crop-row views keep their plants' stem and crown anchors in
`textures/vegetation/crop-row.json`. Tree impostor atlases under
`textures/vegetation/impostors` are baked renders of the models, not materials.

## Authoring and export

Each material's Material Maker project is
`mm-data/tools/materials/<folder>/<name>/<name>.ptex`. Its stock **Material** node is
the material, and its Size is the export size. Export with *File > Export material >
Godot > Godot 4 Standard* into `data/models/board/textures/<folder>`. Alternatively, run
`python tools/materials/export.py [relative/path.ptex]` from the mm-data root, which
exports with Material Maker's command line (see `mm-data/tools/materials/README.md`).
The command line renders every map at 2048 pixels whatever the node's Size, so the
script resamples each map to its node's Size. *Reload assets* in the Tuning panel picks
up a new export, and any catalogue change, without restarting.

`GpuBoardMaterialsTest` checks that every shipped material reads, that its maps exist,
and that it is catalogued exactly once, with square maps if it is loose. A `ShaderMaterial`
must assign every map its shader declares, set only parameters it declares, and be exactly
the materials with a `<name>.gdshader`.
