# Ice and black ice

Ice shades the existing lake, road or ground surface. `GpuIceShader` owns the
optical material; `BoardLiquid` and the captured terrain flags decide where it
is present. Rules for discovery, movement and breaking ice stay in the game code.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `GpuIceShader`, `terrain-ice.glsl` | Floes, snow, joints, transmission, reflection and the jagged land-ice edge. |
| `BoardSurface.freeze`, `GpuTerrain` | Lake slab, its edges, ragged margin and floes; iced banks; mesh batching. |
| `GpuBiomeSurface` | Per-hex ice flags (texture B channel) for the land-ice edge. |
| `Terrain`, `ServerHelper.checkEnteringBlackIce`, `MovePathHandler` | Store discovery, reveal black ice during movement and publish the changed hex. |
| `BoardEditorTerrain` | Authoring markers for hidden flags, independently of gameplay appearance. |
| `data/models/board/textures/terrain/ice` | Chromatic scattering-tint input, normal and packed optical controls. |

The Material Maker project `mm-data/tools/materials/terrain/ice/sheet` exports the
`terrain/ice/sheet` Godot 4 Standard material ([board materials](gpu-board-materials.md)):
`sheet_albedo`, `_normal`, `_heightmap`, `_orm` (roughness), `_sss` (cloudiness) and the
extra `sheet_cracks` map. Run `python tools/materials/export.py` from the `mm-data` root.
Exported wider than its catalogued 1024 pixels, the sheet covers proportionally more
ground before it repeats (`u_iceCoverage`).

The albedo is a chromatic input to the optical coat, not a final diffuse albedo
or measured transmission coefficient. The shader blends two filtered RGB samples,
divides by their mean (with a .05 floor), and uses 35% of that tint around its
bare-ice scattering color. Changing hue affects the coat; uniformly scaling RGB
largely cancels above the floor. Snow, cloudy ice, cracks and joints have separate
optical controls. Their light response and transmission still depend on the coat
and the surface beneath it.

This normalization happens after filtering. Pre-normalizing the PNG would change
mip and repeat blending and is not an equivalent optimization. The generator's
separate mean subtraction centers the engineered height field; it does not
normalize color brightness. The renderer packs height, roughness, cracks and
cloudiness into one surface map, and uploads it with the aligned normal as the
layers of one array (`u_iceMaps`). These
are artistic control maps, not measured ice samples. Rebuilding from a changed
source can also change those numeric maps; a color-only revision must explicitly
preserve and verify them.

## Lake and ground surfaces

A frozen lake is a slab whose top is the level units stand on, one pixel above
open water. It runs on into frozen neighbours and up banks below frozen land. It
breaks raggedly out over open water, shedding floes, and breaks off short of
ice-free shores, leaving a lead with rubble. All of that is drawn only; support
and picking keep the hex's waterline. Ice changes no bed: frozen and open water
at one level share the depths of their mouths and corners
(`BoardSurface.cornerDepth`), so no slit opens between their beds.

All ice shares world-anchored floes: snow drifts, bare grey ice and refrozen
joints, so lake and land ice continue across each other. Land ice meets dry
ground at its level along a shattered border straddling their hex edge: the dry
tile draws its side (`GpuIceShader` modes 4 and 5). Toward water or another
level it breaks off short. Ice never coats ground under water.

Costs: lake ice stays one transparent draw per chunk, blended after open water;
land ice is a shader variant of existing ground draws, and so is its border on
dry neighbours. Detail, cracks and joints fade with projected size; there is no
geometric LOD or reflection capture. The October 12 paired review at 1200×900
on Intel Iris Xe measured the lake transparent stage at 1.33–2.05 ms after unused
base/floe work was removed, versus 1.71–2.67 ms before, depending on view. Those
are scene-stage measurements, not the marginal cost of one ice fragment or a
complete-game frame rate. Paired images differed by at most one display code;
black-ice opaque timings did not establish a gain. See the
[complete shader audit](gpu-shader-audit.md) for later experiments and limitations.

Fixed material modes now specialize only the programs actually drawn. A second
eight-view comparison against the deferred-work implementation was byte-exact;
the perspective transparent stage measured 1.366/1.387 ms versus 1.995/2.006 ms
in alternating blocks. Other views had substantial clock variation, and the
overview's opaque stage slightly regressed. This fixture used five distinct ice
programs, compared with four distinct source pairs after removing the mode prefix.
That source count is not a measurement of the former provider's compilation cost.
The first source replacements took 35–38 seconds on this driver; later reloads
took about .33 seconds. Driver-cache state was not controlled. These preparation
costs are outside the reported GPU timings.

Cut faces use their authored clear/cloudy ice roughness and skip floe and snow
work that cannot contribute to an exposed edge. Offline sheet-color resizing
and periodic seam filtering operate in linear RGB and encode once on output;
the aligned normal and surface maps remain byte-identical.

The final original/current comparison includes those cut-face changes while
holding texture assets and shared lighting fixed. Its stable overhead transparent
stage measured 2.4386/2.4346 ms before and 1.7957/1.7890 ms after, about 26.4%
lower. Opaque terrain and the unchanged composite remained stable in that view.
Every block collected 20 GPU samples without drops; all 16 frozen repeats across
eight views were byte-exact. Other views have substantial clock variation, so
this is not a universal speedup. The current edge material intentionally differs
from the saved shader. First source replacements took 19.4–22.2 seconds; later
rebuilds .195–.733 seconds, with uncontrolled driver-cache state. Per-view
preparation excludes the initial terrain update and is not total startup time.
Evidence: `build/shader-audit/final-benchmarks-10/ice-benchmark/`.

## Discovery and editor visibility

Black ice remains `BLACK_ICE` in the rules model after discovery. A discovery bit
on `Terrain` survives snapshots and game serialization; text board files start
hidden for a new game. The server reveals it through its movement-entry check
and sends the changed hex to clients.

Undiscovered black ice has no gameplay surface graphic or terrain-tooltip entry.
The Legacy 2D tile matcher also avoids letting invisible black-ice or metal metadata
select an unintended base pavement image. Revealing black ice must not replace its
rules type with ordinary lake ice.

The 3D editor receives explicit annotations for hidden terrain flags through
`BoardEditorTerrain`. Gameplay views do not receive those authoring markers.
See [map markers](map-editor-3d.md) for these annotations and [the 3D board editor](3d-board-editor.md) for editing.

## Frozen waterfalls

A frozen source pool now grows an ice cascade across its level drop, including
when the receiving pool is unfrozen. An unfrozen source retains its flowing
waterfall when it falls onto ice. `BoardSurface` supplies the shared crests and
receiving heights; the drawn slab meets the cascade without changing the level
where units stand. Freezing or thawing either endpoint invalidates that geometry.

`GpuFrozenWaterfall` builds deterministic fluted columns, a closed body and
hanging icicles. Rendering and picking use the same triangles. Ice material mode
6 reuses the existing ice maps and optical lighting for blue ice, frosted ribs,
trapped bubbles and reflections. It has no scrolling, spray or animated water
impacts. The existing straight cut at the board edge is unchanged.

`BoardFrozenWaterfallTest` covers mixed endpoints, slab joins, cache invalidation
and picking. `GpuFrozenWaterfallSmokeTest` checks both camera projections,
stationary ice, normal detail and thawing, and saves a 3200-by-1800 comparison of
a large flowing waterfall beside a frozen waterfall. Run the native check with
`gradlew :megamek:gpuBoardSmoke --tests '*GpuFrozenWaterfallSmokeTest'`.
