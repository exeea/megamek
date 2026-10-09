# Modular buildings

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardArtwork.customBuildingFile` | Map selected tileset artwork to the custom building catalog. |
| `GpuAssets.building` | Choose custom kit or legacy fallback and own shared loaded assets. |
| `GpuBuilding` | Validate named modules, choose stable base/floor/roof recipes, assemble them and expose picking geometry. |
| `GpuBuildingInterior` | Generate shared floor sheets and supports from the roof's occupied footprint. |
| `BoardIndustrial` | Generate deterministic machinery with independent equipment heights and neighbor pipe joints. |
| `GpuTreeInstances / GpuTerrain` | Instance module ranges; install chunks and retire unused recipes/interior buffers. |

The board's tileset still chooses building artwork. `BoardArtwork.customBuildingFile` maps the selected legacy
asset to `data/models/buildings/<family>/<name>.glb`, removing the `saxarba/` provenance directory. For example,
`buildings/saxarba/fortress_light/fortress_light_a_52` selects
`data/models/buildings/fortress_light/fortress_light_a_52.glb`.

`GpuAssets.building` tries that file first for building, fuel-tank and industrial terrain. Missing kits keep the existing
`data/models/board/buildings/...` GLB/G3DJ loader, height fitting and wall textures.
Discovery accepts a custom kit even if there is no legacy model.
Malformed custom kits fail with an authoring error rather than silently concealing invalid assets.

## Asset contract

One building kit per GLB, with identity root groups:

```
fortress_light_a_52-lod0
  fortress_light_a_52-lod0-base0
  fortress_light_a_52-lod0-floor0
  fortress_light_a_52-lod0-floor1
  fortress_light_a_52-lod0-roof0
fortress_light_a_52-lod1     (optional; same module roles)
  ...
```

Author buildings with `lod0` and, when useful, `lod1`. Consider `lod0` alone for a simple kit, especially below
200 triangles. The fortress ships with two levels. This is an authoring budget, not a loader rejection rule:
if a GLB also supplies `lod2`, the loader uses it. Other asset types retain their existing LOD rules.
Each child is a rigid mesh node; multiple material primitives in that mesh are fine. Variant numbers may be
sparse. At least one `base#`, one `floor#`, and one `roof#` are required; each role supports multiple variants,
including variant 0. Do not add helper mesh nodes.

Opaque materials may set glTF `doubleSided: true`. The shared rigid importer adds reverse-facing triangles with
reversed normals, preserving colours and UVs, so both sides use the existing lighting, shadow and cutaway paths.
Only double-sided primitives gain reverse faces; their additional vertices count toward the rigid asset's 65,535
vertex limit. Single-sided materials remain unchanged. Explicit inner walls remain useful when the inside needs
different geometry or textures. Alpha-blended and alpha-masked rigid materials remain unsupported.

Author each base and floor exactly one model level (18 units) high in Blender's Z-up space. The GLB exporter converts to
standard glTF Y-up. For enterable buildings, LOD0 has hollow walls with inward-facing geometry; runtime generates the interior slabs and
struts. LOD1 floor walls are flat, with oppositely wound faces so both sides remain visible, without thickness or
edge caps. The inside retains the shared concrete texture. Outside, a padded 2048x256 atlas combines concrete,
windows, doors and slab bands on the same surface, avoiding nearly coplanar accent meshes and their z-fighting.
The three small rooftop vents are omitted from LOD1. The fortress uses 32 triangles per distant floor and 262
for the complete distant kit. The atlas adds a shared texture; lower triangle counts alone do not establish an FPS gain.
Author the modules as a readable stack: this kit's base0, floor0, floor1 and roof0 stand at Z=0,18,36,54.
The second LOD stands alongside at X=110, also assembled. Thus a viewer shows the building, not overlapping pieces.

The loader computes each module's transformed geometry bounds and removes its horizontal center and minimum Z.
Saved object origins, baked vertex translations and display offsets therefore do not determine placement.
Modules must share a centered horizontal envelope and footprint, including across LODs; asymmetric protrusions
must not shift one variant's bounding center. Group transforms remain identity, as required by the rigid importer.

For N levels, select one random `base#`, then N-1 random `floor#` modules (including `floor0`), then one random
`roof#`. A single variant is always used when it is the only choice for that role. A placement seed derived from
the hex and selected artwork makes choices stable across camera movement, LOD changes and reloads. Base and roof
selection are independent of height, and a height edit preserves existing lower-floor choices. Offsets are `index * 18`.
Scale the assembled stack only by the board's configured level size; the roof is an additional cap, not a reason
to compress the floors. A five-level building has one base, four floor modules and its roof; a one-level building
has only a base and roof.

## Interiors and picking

Only `FeatureKind.BUILDING` requests an interior. Industrial terrain is captured separately as
`FeatureKind.INDUSTRIAL`, using the authoritative `Terrains.INDUSTRIAL` height. Its base/floor/roof names are
assembly roles for machinery, not occupiable storeys. Industrial and fuel-tank assemblies allocate no interior
and do not require closed wall outlines. Wall footprints are computed lazily when a real building requests them.

The full roof projection preserves notches, courtyards and disconnected wings; its lowest underside alone may
be only a border around an inset panel. The generated interior is clipped against closed mid-storey wall
sections of the selected base and floor modules, using the external wall boundary so wall thickness remains included.
Sections come from the simplest authored wall LOD (all LODs must share the same envelope), independently of
camera distance. This keeps detailed window recesses and facade seams out of the floor geometry.
Roof overhangs, sidewalks and horizontal decorative ledges do not enlarge those wall sections. The common
footprint is extruded through the stack height and passed to `GpuBuildingInterior`; differing floor outlines
therefore use their intersection rather than allowing full-height columns to extend outside any storey.
Supports require their center and all four corners to lie inside the footprint. The roof cap's furniture does
not increase occupied floor height. Generated floors and struts enter the render/depth passes only while that
building is faded because of an occupant or an interior-floor hover. Leaving, or disabling transparency, hides them
again. They do not enter the exterior shadow pass, which retains the authored opaque shell. Existing occupancy cutaways
fade walls and upper slabs while the occupied/lower floors and struts remain opaque. Hover cutaways reveal the selected
interior floor and fade its walls; the top storey's cutaway also fades the entire roof cap and trim. Hovering the roof
itself does not start a cutaway.

Picking keeps one triangle set per LOD0 module and offsets the ray for each story. It does not retain expanded
triangles for every possible building combination. Picking is independent of the displayed LOD.

Industrial pointer selection passes through equipment to the underlying terrain in both camera presentations,
as it does for foliage. Physical effects still hit the equipment's authored triangles. Industrial shells never
receive occupant fading or hover cutouts, including when a real building coexists in the same hex. The renderer
does not create or modify movement/LOS rules: industrial height already supplies cover without granting floors.

### Heavy industrial kits

The four standard `misc/heavy_industrial_a` through `d` assets use `BoardIndustrial` instead of a whole-hex GLB stack.
The tileset identifier chooses the family: cooling tower with generators; a farm of differently sized silos;
a pressure reactor with horizontal exchangers; or a flare stack with shorter distillation equipment. A seed
derived from coordinates and artwork chooses equipment details, distinct cap shapes and the smaller machines'
heights. Domes, pointed cones and flat tops with offset vents have different silhouettes. The tallest equipment,
including its cap, reaches the terrain's cover height; the other machines end below it. The central ground lane
remains open. There are no rooms, stairs or full-width decks.

Only adjacent generated industrial installations receive border connections. Both sides independently derive
the same edge midpoint, pipe radius and perpendicular approach. An unordered pair seed chooses connection
heights inside their overlapping vertical range. Tall neighbors have two distinct pipe heights, varied per
neighbor pair; short installations use one. Internal equipment also connects at different elevations, with
exchanger risers attached to actual drums. On uneven ground, connections pass above the higher ground and below
both installations' cover heights; steep differences omit them. Pipes route around the ground passage and
have supports. Isolated sides keep ground-mounted service valves
inside their hex rather than outgoing stubs. Neighbor edits use the existing two-hex terrain invalidation, and
remove or rebuild the connection without moving the remaining machinery.

`BoardIndustrial.model` supplies the same CPU triangles to rendering, physical picking and cosmetic obstacle
clearance. `GpuAssets` owns the generated models and retires layouts unused by installed/cached chunks at scene
commit. There is no generation during animation or camera changes. The current generator uses one bounded
mesh with two material ranges per layout, without a distance-dependent machinery LOD.

Generator fans sit in real roof openings with recessed blades. Parent shells omit end caps covered by another
cap, exchanger posts end below their top beams, and shared pipe supports emit one footing per location.
These avoid coplanar top faces that would flicker while orbiting. The geometry regression scans the uppermost
surface across all four families; native captures inspect the caps from four azimuths.

The two photographic IMAGEN materials are `models/board/textures/industrial/paint.png` and `steel.png`, with
different texture scales and specular settings, circumferential tank/pipe UVs, full-resolution mip levels and
anisotropic filtering. These are diffuse/specular materials; the shader does not implement a full metalness
workflow. Concept, saved prompts and Blender review files live in mm-data's
`tools/buildings/heavy-industrial/`. Its original whole-hex modular GLBs are authoring prototypes; the runtime
generator supersedes them for these four terrain identifiers. Ordinary buildings keep the base/floor/roof contract.

## Cache and ownership

- A kit owns its shared module models and one normalized triangle set per module. Textures use the existing
  `ModelTextures`/`GpuAssets` cache. Concrete trim and wall tints use vertex colors, sharing one embedded concrete
  image; the roof uses the second image, and LOD1 exteriors use the padded facade atlas.
- An assembly cache contains a compact string of module indices, its kit reference and shared interior reference.
  Its key also includes whether an interior was requested; the same kit can serve both terrain semantics safely.
  Offsets are implicit. There is no cached expanded Model per combination. The common four-module kit needs six
  index bytes for a five-level recipe on the JVM's compact-string representation, plus object/map overhead.
- Live scene instances are constructed during terrain preparation/load/edit. LOD transitions replace lightweight
  node/material instances while borrowing the same GPU buffers. A single-LOD kit never switches models.
- Floors and struts are shared by roof footprint and level count. Scene commits discard recipes and interior
  buffers no installed or cached chunk uses. Native resources have explicit GL-thread disposal.
- New buildings bypass the whole-building chunk mesh merger. The existing `GpuTreeInstances` backend also draws
  building module ranges, sharing one compact range buffer per LOD/material/pass across all placements. The GPU
  stores ordinary instance transforms in addition to the small recipe; total rendering memory is not a few bytes.
- A weak map caches persistent renderable-to-batch lookups without retaining its keys. Weak references never own
  GPU resource disposal. Unchanged frames reuse meshes and upload no new instance data; visibility/LOD changes
  can update instance buffers. There is no per-frame building assembly or interior generation.

Adding or replacing a GLB in an already-open viewport requires the existing asset reload or reopening the view;
missing kits and loaded kits are cached for that renderer's lifetime. Editing a building's height uses the cache.
