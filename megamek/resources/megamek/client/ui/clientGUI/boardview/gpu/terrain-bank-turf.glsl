// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// World-space curved cutouts follow the installed support. Only their free ends flutter.
out vec2 v_coverData;
out vec2 v_coverRoot;
in vec4 a_turfWeights;
in vec4 a_turfOthers;
in vec2 a_turfArid;
out vec4 v_turfWeights;
out vec4 v_turfOthers;
out vec2 v_turfArid;
vec3 turfPosition() {
    float tip = smoothstep(.4, .95, fract(a_texCoord0.y * 2.0));
    vec3 position = a_position;
    position.xy += u_wind.xy * u_wind.z * u_worldMetre * .035 * tip * vegetationGust(position.xy);
    return position;
}
