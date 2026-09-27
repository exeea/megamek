# GPU board lighting study

On 2026-09-24 every lit surface moved to one linear light model; the table below
describes it, [One light model](#one-light-model-2026-09-24) records its first measured
results and [Dawn, dusk and the handover](#dawn-dusk-and-the-handover-2026-09-24) the look
pass that followed. Clouds are described in [cloud rendering](gpu-clouds.md) and the sky,
fog and time controls in [gpu-atmosphere.md](gpu-atmosphere.md).

The board uses a single shadowed directional light (the sun or the full moon) plus a
hemisphere of fill: the sky from above and the sunlit ground from below.

## Where illumination is controlled

| Location | Responsibility and current values |
| --- | --- |
| `BoardAtmosphere.lighting()` | Derives source identity, direction, direct/ambient RGB, fog and grading from time, as linear light already exposed for the view (unit: a white level surface in clear noon light receives luminance 1). The sun dims and warms through extinction; a neutral sky fills the shade, 1:4 against the sun at clear noon, and turns deep blue around sunset; the full moon lights level ground two stops below noon. The view adapts to 60% of every change in light. The sun and moon hand the one shadow-casting light over at a sun altitude of -0.09, each fading over 40–45 minutes. See [gpu-atmosphere.md](gpu-atmosphere.md). |
| Cloud response | Coverage affects the sky/fog palette and interpolates cloud-patch opacity between the 0.20 / 0.85 limits. The shared density integral attenuates direct diffuse/specular light spatially, retaining ambient fill. No global cover-dependent light reduction or desaturation remains. |
| `GpuAtmosphere.exposure()` | Only the user/scenario compensation, 2^EV. The view's adaptation to day, dusk and night is applied to the light in `lighting()`. Exposure scales the whole scene and does not independently control cast shadows. |
| `BoardAtmosphere.fromScenario()` and `GpuBoardTuning` | Choose the initial clock, weather and exposure and publish visual previews. Glare/solar flare add +0.6/+1.2 EV; moonless/pitch-black categories add -0.6/-1.0 EV. These are presentation values, not changes to game visibility. |
| `GpuBattleView` → `GpuAtmosphere.configure()` → `GpuTerrain.setAtmosphere()` | Derives lighting when settings change and supplies it to the shared terrain, prop and unit environment before rendering. Both camera presets use this path. |
| `GpuTerrain.applyLight()` | Applies the direct and ambient RGB as they are to the existing `DirectionalShadowLight` and `ColorAttribute.AmbientLight`. Without an atmosphere, standalone scenes use `BoardAtmosphere.lighting(DEFAULTS)` with the scene's own light direction, or an ambient-only linear `0.7` (display `0.85`) when no light is supplied. The regular battle view supplies an atmosphere. |
| `light-model.glsl`, `surface-lighting.glsl`, the terrain shaders and `GpuUnitShader.linearVertex/linearFragment` around libGDX `DefaultShader` | Every lit surface linearises its display-authored color, multiplies it by the light and encodes it for display. Ambient is a hemisphere: the sky from above, the sunlit ground below (the family's color under sculpted terrain, a neutral 20% elsewhere). Direct light depends on the surface normal and shadow-map visibility. Emission stays display-encoded; water multiplies its display colors by the light encoded for display. |
| `GpuAtmosphere` and `atmosphere-composite.frag` | Capture RGBA8 scene color and apply fog, tint, exposure, lightning, saturation, one highlight shoulder and vignette. The shoulder is the identity up to linear 0.6 and then rolls each channel towards white; the RGBA8 target clips values above 1 before exposure, so at exposure one the brightest surface shows display 237. |
| `atmosphere-fog.frag` | Fog/haze scatter 80% of the linear light on level ground into the scene with a shared 25% opacity cap, naturally reducing visible contrast. This is separate from surface lighting. |
| `GpuAtmosphere.renderWeather()` / `GpuWeatherParticles` | Precipitation is drawn after scene grading, lit by 40% of the light on level ground encoded for display; weapon smoke uses the same light, flames and jets stay emissive. The native weather checks cover visibility and sand coverage. |
| Lightning and tactical overlays | Lightning is a temporary whole-scene linear-light multiplier of up to `1 + 1.1 * intensity`, shaped by its animation envelope. Tactical annotations render after atmosphere grading and retain their established readability. |

The native diffuse calculation is supported by the pinned libGDX 1.14.2
[vertex shader](https://github.com/libgdx/libgdx/blob/1.14.2/gdx/res/com/badlogic/gdx/graphics/g3d/shaders/default.vertex.glsl)
and [fragment shader](https://github.com/libgdx/libgdx/blob/1.14.2/gdx/res/com/badlogic/gdx/graphics/g3d/shaders/default.fragment.glsl).

## One light model (2026-09-24)

Measured on the NVIDIA GeForce RTX 4070 Laptop GPU (driver 610.88, shaders compiled as
GLSL 460, and once capped at GLSL 330). The Intel Iris Xe and macOS are unmeasured. The
SAND and GRASS showcase boards were captured through the production composite
(`capture-hours.sh`) before the rework (`light/before-production/`) and after it
(`light/after-model/`), both under `.work/claude-code/terrain/`. Values are Rec. 709 luma
of display codes; medians are over the centre half of each frame.

| Median luma, before → after | 06:30 | 09:00 | 13:00 | 15:30 | 17:30 | 18:30 | 00:00 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SAND oblique | 152 → 96 | 161 → 136 | 169 → 147 | 154 → 127 | 137 → 70 | 140 → 65 | 133 → 81 |
| SAND overview | 152 → 95 | 159 → 134 | 169 → 147 | 156 → 128 | 140 → 73 | 131 → 61 | 131 → 79 |
| SAND rim | 142 → 96 | 158 → 134 | 151 → 128 | 105 → 75 | 127 → 64 | 145 → 69 | 127 → 77 |
| GRASS oblique | 90 → 53 | 89 → 74 | 91 → 77 | 82 → 66 | 78 → 42 | 79 → 37 | 76 → 45 |
| GRASS overview | 84 → 50 | 84 → 71 | 89 → 77 | 81 → 67 | 74 → 41 | 70 → 33 | 71 → 43 |
| GRASS rim | 98 → 56 | 97 → 82 | 90 → 75 | 75 → 54 | 80 → 41 | 93 → 44 | 77 → 46 |

Patches on the SAND oblique view (fixed pixel rectangles; a shadow patch per hour):

| Patch | Before | After | Design prediction |
| --- | --- | --- | --- |
| Lit open sand, 13:00 | L172 (218,164,121) | L149 (195,141,98), S .50, hue 26 | L153 (202,145,102), S .49, hue 26 |
| Second lit patch, 13:00 | L156 | L135 (184,126,78), S .58 | |
| Shade (palm shadow), 13:00 | L117 | L88 (116,83,54) | L74 |
| Pale mesa-top sand, 13:00 | L193 (238,186,132) | L178 (224,170,124), S .45, hue 28 | L188–190, S ≤ .40, hue ≥ 31 |
| Front wall, away from the sun, 13:00 | L75 | L58 | L61 |
| Lit sand / shade, 09:00 | L166 / L126 | L141 / L95 | L141 / L73 |
| Lit sand, 15:30 | L158 | L129 | L135 |
| Lit sand / shade, 17:30 | L148 (233,129,79) / L124 | L94 (135,87,51) / L62 | L103 (139,96,66) / L68 |
| Lit sand / shade, 00:00 | L134 / L60 | L81 / L40 | L82 / L27 |

On the synthetic board of `GpuAtmosphereSmokeTest`, gray-155 tileset ground renders
(157,154,147), luma 154, at 13:00 and (80,82,85), S .06, under the full moon: 2.01 stops
darker, against (88,100,123), S .28, before the rework (`light/before-other/`). The dusk
medians are well below the design's estimates (92 at 17:30): at a low sun most of the frame
lies in long shadows or faces away, and those estimates did not model that. Dawn and dusk
are now dark and brown rather than bright orange. The RGBA8 clip that the composite's
shoulder cannot recover (display 237 at exposure one) is reached by 0.06–0.07% of the SAND
oblique and rim pixels at 13:00 and by none of the GRASS ones.

## Dawn, dusk and the handover (2026-09-24)

The user's feedback on the first model: the day had improved, dawn and dusk still
needed a lot of work, and at one-minute slider steps the sun/moon handover jumped from
the moon's long shadows (05:30) through a shadowless moment (05:45) to the sun's long
shadows (06:00), and back at dusk (18:00, 18:15, 18:30). The cause: the moon faded out
over sun altitudes -0.18 to -0.08 while its fill transfer kept its shadows strong, and
the sun faded in over -0.08 to 0, about 18 minutes each, with the direction flipping in
between. Level ground was darkest in that gap, -2.82 EV at 18:21, below the full moon.

Trials, one change at a time, rendered with the SAND and GRASS showcase oblique views
at 06:00–07:00, 09:00, 13:00, 16:30–19:30 and 00:00 (`light/look-trials/` under
`.work/claude-code/terrain/`; RTX 4070, driver 610.88, GLSL 460):

| Trial | Change | Result |
| --- | --- | --- |
| H1 | Handover: flip at altitude -0.09, sun fades over 0.17 of altitude, moon over 0.19, moon fill kept through the flip; the moon's contrast transfer takes only the night sky's fill | Gradual in the renders; SAND oblique median at 17:30 70 |
| D1 | Twilight sky light down to altitude -0.30 (was -0.14) | 18:30 median 67 → 70 |
| D2 | `ADAPTATION` 0.5 → 0.6 (the full moon stays at -2 EV) | 17:30 median 72 → 78; lit sand L109 |
| W1 | `WARM_SHARE` 0.5 → 0.25 | Shade saturation at 17:30 .56 → .53 |
| W2 | Deep blue twilight fill, `TWILIGHT_SKY` (0.80, 0.95, 1.25) | Fill B/R at 17:30 0.84 → 1.21; shade S .48 |
| W3 | `TWILIGHT_SKY` (0.70, 0.92, 1.40) | Shade S .44, but a pinkish cast on sandstone at 18:30 |
| W4 | `TWILIGHT_SKY` (0.68, 0.96, 1.32), greener | Ground hue at 18:30 26° → 28°, away from pink |
| W5 | Twilight sky endpoints: overhead (0.22, 0.13, 0.31) → (0.14, 0.20, 0.42), horizon (0.40, 0.23, 0.41) → (0.44, 0.30, 0.36) | Blue-hour sky deep blue instead of violet |
| G1 | Grass albedo to the olive of Grassland #3 (see [gpu-terrain-materials.md](gpu-terrain-materials.md)) | Lit grass (86, 87, 44), S .49, hue 60, against the print's (92–109, 92–108, 54–63), S .40–.43, hue 55–62 |
| H2 | The sun's fade as a smoothstep instead of an ease-out | Per-minute change of the shadow-casting share 0.058 → 0.024 |

Not changed, after inspection: `KEY_FILL` 4, `SKY_TINT`, `NIGHT_TINT`, `FOG_ALBEDO` 0.8,
the composite's `KNEE` 0.6, the saturation ends (0.78 at night, 1 by day) and
`GROUND_ALBEDO` 0.2. Lit sand at 13:00 already matched the desert mats (L148, S .50,
hue 26 against the mats' L141–155, S .55–.57, hue 25–26), and the night renders a
neutral brown-grey (B/R 1.18). The level grade was retuned in the same pass (see
[gpu-terrain-materials.md](gpu-terrain-materials.md)).

Median luma of the centre half of each frame, before the rework
(`light/before-production/`), after the first model (`light/after-model/`) and now
(`light/after-look/`):

| Median luma | 06:30 | 09:00 | 13:00 | 15:30 | 17:30 | 18:30 | 00:00 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SAND oblique | 152 → 96 → 110 | 161 → 136 → 142 | 169 → 147 → 149 | 154 → 127 → 132 | 137 → 70 → 81 | 140 → 65 → 78 | 133 → 81 → 82 |
| SAND overview | 152 → 95 → 104 | 159 → 134 → 137 | 169 → 147 → 148 | 156 → 128 → 132 | 140 → 73 → 83 | 131 → 61 → 74 | 131 → 79 → 80 |
| SAND rim | 142 → 96 → 117 | 158 → 134 → 151 | 151 → 128 → 139 | 105 → 75 → 82 | 127 → 64 → 76 | 145 → 69 → 82 | 127 → 77 → 83 |
| GRASS oblique | 90 → 53 → 69 | 89 → 74 → 94 | 91 → 77 → 92 | 82 → 66 → 78 | 78 → 42 → 57 | 79 → 37 → 54 | 76 → 45 → 55 |
| GRASS overview | 84 → 50 → 62 | 84 → 71 → 82 | 89 → 77 → 87 | 81 → 67 → 78 | 74 → 41 → 54 | 70 → 33 → 43 | 71 → 43 → 48 |
| GRASS rim | 98 → 56 → 82 | 97 → 82 → 113 | 90 → 75 → 96 | 75 → 54 → 65 | 80 → 41 → 56 | 93 → 44 → 61 | 77 → 46 → 60 |

The added hours, now: SAND oblique 06:00 93, 07:00 117, 16:30 106, 17:00 86, 18:00 80,
19:00 78, 19:30 79; GRASS oblique 60, 74, 67, 61, 53, 53 and 54. The SAND medians
from 17:30 to 19:30 stay within 78–81 although level ground dims by 1.4 EV: at 17:30
much of the frame lies in long shadows, at 18:30 none of it does. On the SAND oblique
view at 17:30, lit sand is L108 (149, 101, 64), S .57, and shade L68 (86, 65, 47), S .45;
before the look pass the shade kept most of the lit sand's saturation (.54 against .61).
At 18:30 open grass is (39, 43, 29), hue 75°, S .33, and trees hue 107°, S .28: olive
and green, not mint.

## Review fixes (2026-09-24, evening)

A review of the look pass found that a low moon lit walls, rocks and plants brighter than
daylight: the moon stood on the sun's 0.24 altitude floor, so its beam, which also carries
the night fill that the moon contrast moves into it, reached level ground at incidence 0.22,
and the view adapted to that dim ground. The moon now keeps its own floor,
`MIN_MOON_ALTITUDE` 0.6 (about 28 degrees; shadows about two caster heights), and the
moon's fill uses the moon's own incidence, so nothing jumps at the handover. From
`lighting()`: level ground at 19:00 -2.43 → -2.24 EV, a wall turned to the light -0.65 →
-1.52 EV (a wall turned to the 13:00 sun: -1.02), midnight unchanged, twilight at 06:00 and
18:00 -1.60 → -1.57 EV. The same pass dims the sunlit ground's bounce under cloud shadows,
gives blowing sand one dust albedo (`SAND_DUST`) and reworks the level grade (see
[gpu-terrain-materials.md](gpu-terrain-materials.md)).

Median luma of the centre half, before and after, RTX 4070, driver 610.88, GLSL 460
(`light/fix-before/` and `light/fix-after/` under `.work/claude-code/terrain/`):

| Median luma | 05:00 | 13:00 | 19:00 | 00:00 |
| --- | ---: | ---: | ---: | ---: |
| SAND oblique | 62 → 75 | 149 → 150 | 78 → 80 | 82 → 83 |
| SAND overview | 62 → 73 | 147 → 147 | 67 → 73 | 80 → 79 |
| SAND rim | 40 → 44 | 139 → 140 | 90 → 85 | 83 → 85 |
| GRASS oblique | 40 → 46 | 92 → 90 | 53 → 52 | 55 → 54 |
| GRASS overview | 38 → 43 | 87 → 87 | 41 → 44 | 47 → 47 |
| GRASS rim | 30 → 34 | 96 → 92 | 67 → 62 | 60 → 57 |

Moonlit samples at 19:00 (luma, 13-pixel squares): a sand cliff face 106 → 85 (67 at 13:00),
a grass rock face 105 → 84 (79 at 13:00), the grass rock pillar in the oblique view
112 → 99 (73 at 13:00), a cactus 81 → 79 (59 at 13:00). Faces turned to the moon still
read brighter than the same faces at noon, when the sun stands high and they get little of
it; the grazing lumps on level ground are gone, and the 19:00 frame's linear p90/p10 spread
(3.2) now matches midnight's (3.7) instead of 10.1.

## History

Before 2026-09-24 the board lit display-encoded colors with a display-space palette:
an ambient fill that kept about 69% of level-ground light at 13:00, a fixed transfer of
part of it into the sun, a 0.95 bound on the direct light and an exposure lift in the
composite. The one light model replaced all of that; its constants and calibration are
in [gpu-atmosphere.md](gpu-atmosphere.md) and the tables above.

## Realism and limits

This is readable, art-directed lighting, not a physical daylight simulation. The
constants set a mid-key day with deep shadows (1:4 fill at clear noon), a golden dusk,
a blue hour between sunset and the full moon, and a full moon two stops below noon;
the view's 60% adaptation compresses the real range of light far more than a camera
would. The sun follows a representative daily arc without latitude or season, and the
full moon always stands opposite it.

One directional light casts shadows, so the sun and the moon cannot both shade the
board: around the handover the shadows fade out and back in, and for a few minutes at
the flip there are almost none, as in real civil twilight. The ambient fill is uniform
across the sky, with no local sky occlusion beyond vertex and texture occlusion. A cloud's
shadow dims the direct light and the sunlit ground's bounce by the transmission at the
lit surface itself, not at the ground it faces. Shadow filtering and the resolution of the board-wide shadow map are
unchanged. The Intel Iris Xe and macOS are unmeasured, and no frame-rate claim is made:
the look pass changes CPU light values, one grade and one texture, not the passes.
