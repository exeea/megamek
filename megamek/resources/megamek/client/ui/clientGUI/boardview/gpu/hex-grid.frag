#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// GpuHexGrid: the top view's grid, or the Tactical View's ground layer (b11 design 4.6). Each hex draws its own half
// of every hexside mark: the upper hex strokes its cliffs and steps, the lower one carries a cliff's hachures. The
// codes come from the captured tiles and the battle status; nothing here decides a rule. Patterns lie in board space,
// so they hold still while the view pans.
layout(location = 0) out vec4 fragColor;
in vec2 v_board;
in vec2 v_local;          // tile pixels from the hex centre, y north; the hex spans 84 x 72
flat in ivec3 v_style;    // x: cliffs topped (bits 0-5), cliffs below (6-11), 1-level (12-17) and 2-level steps (18-23)
                          // y: area outline (0-5), fill code (6-11); z: smoke (0-1), fire (2), on a liquid (3)
flat in vec4 v_overlay;   // the enemy's arc code (1 rear, 2 side, 3 front), distance, rear rays, side dashes
flat in float v_level;    // the hex's level
flat in float v_ink;
flat in float v_space;
uniform int u_ground;     // 1: the Tactical View's ground layer; 0: the grid alone
uniform int u_spaceOnly;  // Isometric Enhanced draws only the faint borders of transparent SPACE hexes.
uniform vec3 u_levels;    // level shading: the board's lowest level, the lightening per level above it (0: off), its cap
// LIGHT_MODEL
// SCENE_RADIANCE
// TERRAIN_PATTERNS

const float APOTHEM = 36.0;
// The hex corners counterclockwise from east, as BoardGeometry.corner; edge e joins corners e and e + 1.
const vec2 CORNERS[6] = vec2[6](vec2(42.0, 0.0), vec2(21.0, 36.0), vec2(-21.0, 36.0), vec2(-42.0, 0.0),
      vec2(-21.0, -36.0), vec2(21.0, -36.0));
const vec3 INK = vec3(.085, .1, .105);
const vec3 AMBER = vec3(1.0, .769, .329);
const vec3 LIGHT = vec3(1.0, .980, .910);
const float REACH = 5.0;

float pixel;   // apothems per screen pixel

vec4 over(vec4 below, vec3 rgb, float a) {
    float alpha = a + below.a * (1.0 - a);
    return alpha <= 0.0 ? below : vec4((rgb * a + below.rgb * below.a * (1.0 - a)) / alpha, alpha);
}

/** The edge toward MegaMek direction d: north 0, then clockwise. */
int edgeOf(int d) { return (7 - d) % 6; }

/** Distance from the hexside toward direction d, in apothems: 0 on the side, about 1 at the centre. */
float side(int d) {
    int edge = edgeOf(d);
    vec2 a = CORNERS[edge];
    vec2 b = CORNERS[(edge + 1) % 6];
    vec2 n = normalize(vec2(b.y - a.y, a.x - b.x));
    return (dot(a, n) - dot(v_local, n)) / APOTHEM;
}

/** Position along the hexside toward direction d, from -1 at one corner to 1 at the other. */
float along(int d) {
    int edge = edgeOf(d);
    vec2 a = CORNERS[edge];
    vec2 b = CORNERS[(edge + 1) % 6];
    return dot(v_local - (a + b) * .5, normalize(b - a)) / (length(b - a) * .5);
}

/** A line of the given width in apothems, but never thinner than {@code minimum} screen pixels, along a hexside. */
float band(float distance, float width, float minimum) {
    float w = max(width, minimum * pixel);
    return 1.0 - smoothstep(w - pixel * .5, w + pixel * .5, distance);
}

/** Dots on a staggered lattice in board space: crowns of woods. */
float dots(float spacing, float radius) {
    vec2 cell = vec2(spacing, spacing * .866);
    vec2 g = v_board / cell;
    g.x += mod(floor(g.y), 2.0) * .5;
    float d = length((fract(g) - .5) * cell);
    float aa = fwidth(d);
    return 1.0 - smoothstep(radius - aa, radius + aa, d);
}

/** Diagonal hatch lines in board space. */
float hatch(float spacing, float share) {
    float h = fract(dot(v_board, vec2(.7071, .7071)) / spacing);
    float aa = fwidth(h) * 1.5;
    return smoothstep(0.0, aa, h) * (1.0 - smoothstep(share - aa, share, h));
}

/** A fill's colour and opacity; codes as GpuHexGrid's. */
vec4 fill(int code) {
    if (code == 1) { return vec4(.439, .659, .306, .30); }    // light woods
    if (code == 2) { return vec4(.251, .486, .204, .44); }    // heavy woods
    if (code == 3) { return vec4(.173, .361, .149, .52); }    // ultra-heavy woods
    if (code == 4) { return vec4(.353, .659, .400, .32); }    // light jungle
    if (code == 5) { return vec4(.180, .502, .282, .46); }    // heavy jungle
    if (code == 6) { return vec4(.118, .369, .212, .54); }    // ultra-heavy jungle
    if (code == 7) { return vec4(.604, .800, .918, .80); }    // water depth 0
    if (code == 8) { return vec4(.431, .714, .894, .86); }    // depth 1
    if (code == 9) { return vec4(.227, .502, .784, .86); }    // depth 2
    if (code == 10) { return vec4(.118, .337, .635, .86); }   // depth 3 and more
    if (code == 11) { return vec4(.839, .918, .949, .82); }   // ice
    if (code == 12) { return vec4(.886, .349, .110, .84); }   // magma
    if (code == 13) { return vec4(.494, .745, .235, .84); }   // hazardous liquid
    if (code == 14) { return vec4(.478, .231, .133, .55); }   // magma crust
    if (code == 16) { return vec4(.698, .722, .753, .93); }   // light building
    if (code == 17) { return vec4(.588, .620, .667, .93); }   // medium
    if (code == 18) { return vec4(.494, .529, .580, .93); }   // heavy
    if (code == 19) { return vec4(.392, .424, .471, .93); }   // hardened
    if (code == 20) { return vec4(.353, .369, .392, .93); }   // wall
    if (code == 21) { return vec4(.655, .612, .510, .93); }   // fuel tank
    if (code == 22) { return vec4(.549, .529, .486, .93); }   // industrial
    return vec4(0.0);
}

void main() {
    if (u_spaceOnly == 1 && v_space < .5) discard;
    float grid = 1.0 - terrainGrid(v_board);
    if (u_ground == 0) {
        if (grid <= 0.0) discard;
        fragColor = vec4(vec3(v_ink), grid * (v_space > .5 ? .3 : 1.0));
        return;
    }
    pixel = max(fwidth(v_local.x), fwidth(v_local.y)) / APOTHEM;
    int edges = v_style.x;
    int outline = v_style.y & 63;
    int code = (v_style.y >> 6) & 63;
    int smoke = v_style.z & 3;
    bool fire = (v_style.z & 4) != 0;
    vec4 color = vec4(0.0);

    // Level shading under every mark: a warm light wash by the hex's level, so heights read from above.
    if (v_space < .5) color = over(color, LIGHT, min(u_levels.z, u_levels.y * max(0.0, v_level - u_levels.x)));

    // Woods, liquids and roofs: a fill, the woods' crowns, and the area's outline.
    vec4 tint = fill(code);
    color = over(color, tint.rgb, tint.a);
    if (code >= 1 && code <= 6) {
        int density = (code - 1) % 3;
        float spacing = density == 0 ? .1286 : density == 1 ? .0921 : .0729;
        color = over(color, tint.rgb * .45, .55 * dots(spacing, .024));
    }
    color = over(color, v_space > .5 ? vec3(.6, .7, .85) : INK, grid * (v_space > .5 ? .3 : 1.0));
    if (smoke > 0) {
        color = over(color, vec3(.86, .87, .88), .16 * float(smoke));
        color = over(color, vec3(.92, .93, .94), .5 * hatch(smoke == 2 ? .05 : .085, .3));
    }
    if (fire) {
        color = over(color, vec3(1.0, .416, .122), .28);
    }
    for (int d = 0; d < 6; d++) {
        float distance = side(d);
        if (fire) {
            color = over(color, vec3(1.0, .541, .188), .7 * band(distance, .05, 1.5));
        }
        if ((outline & (1 << d)) != 0) {
            float width = code >= 16 ? 0.0 : .03;
            float strength = code >= 16 ? .95 : .7;
            vec3 ink = code >= 16 ? INK : tint.rgb * .4;
            color = over(color, ink, strength * band(distance, width, code >= 16 ? 2.6 : 1.0));
        }
        if ((edges & (1 << (d + 12))) != 0) {
            color = over(color, INK, .6 * band(distance, .035, 1.0));
        }
        if ((edges & (1 << (d + 18))) != 0) {
            float w = max(.03, pixel);
            color = over(color, INK, .6 * band(distance, w, 1.0));
            color = over(color, INK, .6 * band(abs(distance - 2.6 * w), .5 * w, .5));
        }
        if ((edges & (1 << d)) != 0) {
            color = over(color, INK, .88 * band(distance, .11, 2.0));
        }
        if ((edges & (1 << (d + 6))) != 0) {
            // Four hachures from the cliff's foot into this, the lower hex.
            float t = along(d);
            float tick = 0.0;
            for (int i = 0; i < 4; i++) {
                tick = max(tick, 1.0 - smoothstep(.05, .05 + pixel * 1.5, abs(t - (-.6 + .4 * float(i)))));
            }
            color = over(color, INK, .8 * tick * band(distance, .22, 3.0));
        }
    }

    // The hovered or focused enemy's arcs: its rear shaded, fading with distance, between solid rays, and the dashed
    // border of its side arcs near it.
    int arc = int(v_overlay.x + .5);
    if (arc == 1) {
        color = over(color, AMBER, mix(.32, .10, clamp((v_overlay.y - 1.0) / (REACH - 1.0), 0.0, 1.0)));
    }
    int rays = int(v_overlay.z + .5);
    int dashes = int(v_overlay.w + .5);
    for (int d = 0; d < 6; d++) {
        if ((rays & (1 << d)) != 0) {
            color = over(color, AMBER * .92, .95 * band(side(d), 0.0, 2.2));
        }
        if ((dashes & (1 << d)) != 0 && fract(along(d) * 1.5 + .25) < .5) {
            color = over(color, AMBER * .92, .85 * band(side(d), 0.0, 1.6));
        }
    }
    // The tactical ground palette is authored in display colors, but blends into the linear scene.
    // The grid-only path above is drawn after the scene's final display conversion.
    fragColor = vec4(storeScene(toLinear(color.rgb)), color.a);
}
