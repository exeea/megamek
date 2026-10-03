# Cloud shadows and wet terrain

`GpuClouds` produces an animated sunlight-transmission field.
`GpuCloudShadow` binds it to receiving surfaces, and `GpuAtmosphere` uses it
for sun shafts and the sky veil. Scenario mapping and visual override behavior
are explained in [atmosphere](gpu-atmosphere.md).

## Field, controls and motion

The atlas is 192×192 with 12 samples through the cloud layer and a fixed optical
density multiplier of 0.25. Cloud cover changes the footprint and openings;
it is separate from the opacity limits. There are no rendered sky-cloud meshes
or per-camera cloud-view buffers.

`GpuClouds.MIN_SHADOW_STRENGTH` and `MAX_SHADOW_STRENGTH` default to 0.20
and 0.85. For nonzero cover the cap interpolates between them; zero cover removes
cloud shadows. Transmission is
`1 - strength * (1 - exp(-opticalDepth))`. Surfaces and shafts sample the same
result. Increasing the minimum above the maximum raises the maximum too.

Wind strength maps continuously from calm drift to full speed. Displacement is
integrated, including negative directions through the periodic noise boundary,
so changing wind does not teleport the shadows. An unresolved scenario bearing
uses the visual fallback without consuming a game random roll.

The backdrop uses sky/horizon colors and a cloud-colored veil strongest overhead.
Dawn/dusk retain warm horizons; airless skies are dark. Cover does not generate
separate sky-cloud geometry or alter the game's weather rules.

## Scenario conditions and wetness

`BoardAtmosphere` captures pressure and temperature. Space/vacuum disables
clouds, fog, haze, precipitation, wind and liquid wetness, including previews.
Trace air disables clouds; thin air permits lighter cloud shade but no
precipitation, fog or wetness. Lunar artwork does not override an explicitly
atmospheric scenario.

Weather and pressure shape the layer: snow, fog and heavy cover flatten it;
rain, hail and lightning deepen it; higher pressure increases optical depth.
These are visual mappings rather than a weather simulation.

Liquid rain wets eligible ground immediately above 0°C. Freezing, snow,
frozen surfaces and existing water do not receive that wet-ground coat.
`rain-surface.glsl` darkens albedo and adds normal-aware sheen, respecting
geometry and cloud shadows. Concrete and rock respond more strongly than porous
soil. `GpuTerrain` owns the reusable noise inputs. There is no accumulation or
drying simulation, and no separate wetness pass.

## Light and shafts

Terrain and units sample the atlas at their world positions. It attenuates direct
diffuse/specular light and sunlit-ground bounce, while preserving sky ambient and
emission. Cloud cover must not also dim the global light, which would count the
same shade twice.

With ordinary world lighting the projection stays fixed as the camera moves.
Fixed sun/moon resolves a camera-relative direction that must be shared by
geometry shadows, cloud projection and shafts. Moonless/Pitch Black skip their
directional shadow passes. Wind/cloud motion does not invalidate terrain meshes
or the geometry shadow map.

Shaft integration runs at quarter width/height with 12 samples. Geometry depth
stops shafts at opaque surfaces; cloud transmission shapes the openings.
Fog, haze and shafts share a capped opacity budget and depth-aware upsampling.
Fog's ground layer remains independent of cloud altitude, so raising the cloud
layer does not also create a taller fog column. The light color already includes
its horizon fade; shafts must not apply that fade again.

## Resource and source ownership

`GpuAtmosphere` lazily owns `GpuClouds`; the latter owns its transmission
program, noise texture and fixed atlas. Viewport resizing reuses that atlas.
Environment texture/projection references borrow the owned resource, and closing
the view disposes it.

`cloud-transmission.frag` computes density and the Beer–Lambert integral from a
repeatable two-channel noise texture. `cloud-shadow.glsl` samples the result
in surface programs. Change cloud shape/opacity in the first path, projection or
surface application in `GpuCloudShadow`, and shaft composition in
`GpuAtmosphere`. Scene color and hardware depth are captured together and reused
through the final composite.
