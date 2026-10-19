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
| `<name>_emission.png` | Emission colour. |
| `<name>_sss.png` | Subsurface scattering strength. |

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
| `metallic`, `metallic_texture`, `metallic_texture_channel` | Metallic, for models' metallic-roughness maps. |
| `heightmap_enabled`, `heightmap_texture` (or `depth_texture`), `heightmap_flip_texture` | Height. |
| `heightmap_scale` | Relief range of roads and magma (below). |
| `emission_enabled`, `emission_texture`, `emission_energy` (or `emission_energy_multiplier`) | Lava heat. |
| `subsurf_scatter_enabled`, `subsurf_scatter_texture`, `subsurf_scatter_strength` | Ice cloudiness, foliage transmission. |

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
| `METALLIC_ROUGHNESS` | –, roughness, metallic | Models' glTF metallic-roughness map |
| `MOLTEN_HEAT` | Emission RGB, coverage | Molten lava |
| `CRUST_HEAT` | Heat, –, –, glow | Solid crust |

For roads and magma, `heightmap_scale` is the relief depth in texture coordinates
times 100: 10 is the full range, 0.1 of a repeat. For sculpted terrain it is
informational (100 × relief metres ÷ repeat metres): their relief comes from the
shared terrain shading.

Models (`.glb`) name these maps directly: a base colour `<name>_albedo.png`, a normal
`<name>_normal.png`, and an occlusion or metallic-roughness `<name>_orm.png`.
`ModelTextures` recognises a board material's map by its `.tres` beside it. The model
then draws it in the layout its glTF role takes, sharing the board's own texture.

### Sculpted terrain roughness

Sculpted terrain normally takes its roughness from context: its role, hardness and
distance. A material replaces it with authored roughness, following Godot's meaning:

- A roughness channel that varies is a roughness map (times `roughness`).
- A uniform channel, or none, with `roughness` below 1 is that scalar.
- Godot's default (factor 1 over a white map, which Material Maker writes while
  the roughness input is unconnected) keeps the contextual roughness.

Mud and scorch keep their roughness in their own normal maps instead.

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
