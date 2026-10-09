# Magma crust and lava

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardLiquid` | Classify crust versus molten lava from captured terrain; preserve the game's level/depth. |
| `BoardSurface / BoardRelief` | Construct beds, banks and shared contacts. |
| `GpuMagmaShader` | Select solid or flowing terrain material programs and their textures/uniforms. |
| `GpuOcean` | Generate the separate lava wave field using the shared wave implementation. |
| `liquid-flow.glsl` | Shared water/lava advection phases, weights and material coordinates. |
| `magma-solid.glsl / magma-flow.glsl / magma-lighting.glsl` | Own crust relief, molten advection and heat/lighting; terrain boundaries reuse these evaluations. |
| `GpuAtmosphere / GpuHeatGlow` | Preserve molten HDR energy, select visible hot highlights and composite a reduced-resolution camera halo. |
| `GpuLavaLighting / lava-lighting.glsl` | Derive bounded local diffuse emitters from installed volcanic tiles for terrain, units and props. |

MAGMA level 1 is solid basalt crust with incandescent fissures. Level 2 is opaque
flowing lava, including falling sheets and exposed board-edge cuts. The captured
`BoardLiquid` distinguishes the states; `present()` is false for crust. Neither
material supplies movement rules, water depth, support heights or new picking
geometry. Existing terrain edits rebuild the affected surfaces. Crust, lava banks
and cliffs use the shared sculpted terrain. Connected lava uses the same graded
surface as water for one/two-level descents, and falling sheets from three levels.
Volcanic banks and crust join ordinary ground through the shared
[terrain contact field](gpu-terrain-contacts.md). Marsh plants stop at volcanic ground.

`GpuMagmaShader` selects `terrain-magma-solid.frag` for crust/banks and
`terrain-magma-flow.frag` for pools/falls in the existing terrain pass. Edit solid
relief and fissures in `magma-solid.glsl`, and advection, convection and molten heat
in `magma-flow.glsl`. Both use `terrain-magma.glsl` for sampling/projection and
`magma-lighting.glsl` for reflected light and emission. Terrain-boundary palettes
use the same solid evaluation. World-space coordinates continue across adjoining
hexes. Each active triplanar axis evaluates one coherent material projection.
Flowing lava uses two modest, successive world-space shears to vary the source's
alignment without additional texture fetches or folded UVs; cooled crust retains
its gentle warp. Colour, heat, height and normals use the same coordinates. Normal gradients,
view directions and flow directions include the warp's Jacobian; mip derivatives are
taken after the warp. Smooth triplanar weights cover tops, slopes, curved fall lips
and vertical cuts without switching projection abruptly.

A flat flowing surface uses ten material-map reads at close range: five in each
of the two advection phases. Material LOD reduces this to six after fine normals
and relief disappear, then four when the surface map is no longer sampled; colour
and heat remain. These counts exclude bank/current fields, the wave field, lighting
and shadows; surfaces using multiple projection axes cost more. Solid crust retains
its relief trace only at close range. See [material LOD](gpu-terrain-materials.md#material-detail-at-distance).
These counts describe material sampling work, not an FPS multiplier.

Lava reuses `GpuOcean`'s actual 128-square GPU inverse FFT with a separate, slow,
isotropic spectrum that suppresses short waves. `ocean-lava-finish.frag` retains
displacement; `ocean-water-finish.frag` computes water's compression and foam. Their
centering helper, spectrum and FFT stages stay shared. FFT horizontal displacement deforms
the cooling skin and hot channels together; FFT slopes light the folds. It is
independent of wind and only runs while loaded chunks contain lava. The same
`BoardFlow` currents and `GpuWaterShader.Field` sampler supply downhill flow, bank
distance and approach/junction currents. Lava and water have separate fields, so
currents cannot cross between them. Rebuilt chunks rebind reused materials to their
new field and dispose the old texture. Water and lava use `liquid-flow.glsl` for
their two staggered material advections, hiding each reset at zero weight; solid
crust remains stationary. Lava advances its source window at these hidden resets
so it does not repeat the same short loop. Source offsets stay bounded and do not
accumulate distortion around bends. Broad spatial staggering avoids synchronized
crossfades across the river, without another texture read.
Both phases use the local world-space current, transformed through the same warp
and projection as every material map. There is no fixed texture-axis scroll or
independent heat-map flow that could override a bend or run uphill after rotation.
On steep sheets, the downward gravity tangent replaces the horizontal current;
this is unchanged when viewing the back of a sheet. Ordinary lava reaches move at
approximately 2.4 metres per second at 1 g, with a four-second advection cycle.
The existing gravity-scaled clock controls this transport. Closed pools use the
existing FFT height gradient, rotated by 90 degrees, to circulate the complete
material locally. This is capped at 1.2 metres per second and fades out at banks,
on falls and where an established current takes over. It shares the same material
advection and adds no simulation pass or texture fetch. The small FFT displacement
continues to deform the skin alongside this transport; it is no longer the only
visible motion in zero-current hexes.
Each molten phase converts heat to emitted RGB before the two phases blend. This
avoids the brightness pulse produced by applying a nonlinear heat ramp to their
averaged temperature. Both phases carry colour, normals, surface and heat together;
restrained FFT displacement and slopes keep the liquid from becoming knotted.
The existing shore-distance field supplies a wider red thermal margin around the
hot interior, without adding geometry or texture reads.
This is a viscous surface approximation, not a volume-conserving or thermal fluid
solver. It introduces no separate board scene, animation thread or game state.

Basalt uses the board's linear lighting, shadows, normal-map toggle and GGX
roughness response. Heat and the local glow on fissure walls are added after
ambient, sunlight and shadowing. The molten heat ramp rises from red cooling skin
through orange melt to bright yellow cores; exposed hot melt is smoother than its
cooling rafts. Solid basalt retains its dimmer red fissures.

All main 3D boards capture the scene in RGBA16F, preserving radiance above diffuse
white through the existing display-encoded material outputs. Only boards containing
molten tiles run heat passes. The atmosphere composite separates
strong warm HDR highlights from ordinary surface lighting, so their energy
does not inherit the night surface tint/desaturation. Fog, sand and exposure still
affect their appearance before the common display shoulder and tactical FoV.

`GpuHeatGlow` extracts visible hot radiance at quarter resolution and blurs it in
two separable five-fetch passes using two reusable targets. It reads the completed
scene, so foreground objects occlude the source without another terrain draw.
Fog attenuates the source before blur; sand also attenuates the halo in the final
composite. With tactical FoV active, each source texel is attenuated by the shared
visibility opacity before downsampling, preventing hidden heat from bleeding into
a visible neighbour. The final composite retains its normal FoV treatment.

The halo models camera glare. Its warm-radiance selector is an approximation rather than a material
mask: sufficiently bright warm fire may also glow on a molten board, while ordinary
white reflections are excluded. Ordinary reflected highlights also retain values
above one until shared exposure and tone mapping, whether or not lava is present.
Scene alpha blending remains display-encoded; see the
[scene color contract](gpu-atmosphere.md#scene-depth-fog-and-composition).

Local illumination is a separate diffuse contribution in the existing lit shaders.
`GpuLavaLighting` derives up to 32 disk-like emitters from the installed terrain
snapshot each frame, culling by camera and the captured client LOS. Molten tiles
emit strongly; cooling crust retains 3.5% source power. The camera-centred budget
fades its outer sources by distance when full. The same environment attribute reaches
terrain, units, buildings and props without extra textures, render passes or game state.
Changing terrain or board scale refreshes positions; removing lava clears the count.
This is a short-range, bounded lighting approximation, with no obstacle shadow rays
or global illumination. Light can leak through nearby geometry, and a very large
lava field exceeds the local emitter budget. The camera halo and local lighting
both respect captured visibility, through their own existing render paths.

Relief uses normals and bounded parallax, not geometric displacement, so silhouettes
and picking remain the existing board geometry. Crust traces up to twelve depth
layers with a refined intersection to preserve its occluding slab edges; lava keeps
its thin-skin offset. The crust bake concentrates relief in the thick plates and
gives fine rock grain only shallow relief. Fissure-wall glow stays close to the gaps.

## Assets

`mm-data/tools/build_magma_materials.py` reuses the existing periodic terrain and
normal-map baker. It separates neutral basalt reflectance from heat and estimates
recessed fissure height. This is artistic relief, not measured photogrammetry.
The generated molten-interior source, its generation prompt and provenance live in
`mm-data/tools/magma-reference/lava-source.png`, `lava-source-prompt.txt` and
`lava-source.json`. The source contains connected melt with disconnected cooling
fragments; riverbanks come from the board geometry and shore field. Painting long
rock-separated channels into a repeating material made obvious diagonal bands.

Each material (`crust` and `lava`) has four aligned, repeating 1024px maps in
`mm-data/data/models/board/textures/magma/`. The bake's reference scale is 12 metres.
Solid crust retains that repeat; flowing lava uses 24 metres for larger readable
rafts, with parallax, normal gradients and motion adjusted to retain their physical scale;
the shared world warp softens the regular grid, but recognisable motifs can still
repeat. The maps are artistic materials, not a physical thermal simulation.

| Map | Channels |
| --- | --- |
| `NAME.png` | sRGB basalt albedo, with heat removed |
| `NAME-normal.png` | Linear tangent normal, U right and V down |
| `NAME-surface.png` | R height, G perceptual roughness, B cavity AO, A relief UV / 0.1 |
| `NAME-heat.png` | R emission intensity, GB legacy authored flow (unused by transport), A local fissure glow |

Metalness is zero and opacity is one. Height, normals and the shader's relief range
use the same quantized field. The shader samples every material map at the same
advected/parallax coordinates. Mipmaps and supported anisotropic filtering handle
distance. `GpuAssets.magma` uploads each set as the four layers of one texture array
(colour, normal, surface, heat), so it takes one texture unit; `GpuMagmaShader`
binds it as `u_magmaMaps`. `GpuAssets` owns and disposes the arrays while materials
borrow them. Missing or partial volcanic sets retain the existing crust artwork or
lava GIF rendering.
Gradle's existing `stageDataFiles` copies the runtime maps into MegaMek's data folder.
