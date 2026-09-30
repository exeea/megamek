// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared interface; unused samplers are eliminated from spray and cut programs.
in vec2 v_diffuseUV;
in vec3 v_normal;
in vec4 v_color;
in float v_opacity;
uniform sampler2D u_diffuseTexture;
#ifdef diffuseColorFlag
uniform vec4 u_diffuseColor;
#endif
uniform sampler2D u_waterField;  // R signed bank distance, G optical depth, BA current
uniform vec4 u_waterFieldMap;    // world XY to field UV: scale XY, offset XY
uniform vec2 u_waterMaterial;    // palette, procedural color; the program selects the geometry mode
uniform float u_waterEffects;
uniform float u_wavePixels;
uniform int u_splashCount;
uniform vec4 u_splashLines[12];  // where falls land nearby: from XY, to XY, the pool to the right; hex widths
uniform float u_splashRadii[12]; // radius of each landing's boil, hex widths
uniform float u_metre;           // world units per metre
uniform float u_levelHeight;     // world units per level
uniform float u_waterLine;       // water surface inset below its game level, shared with the bed shader
uniform int u_waderCount;
uniform vec4 u_waders[12];       // GpuWaders: centre XY, radius at the waterline, water level; world units
uniform vec4 u_waderMotion[12];  // velocity XY, world units per second
const float FLOW_CYCLE = 2.4;    // seconds per two-phase advection cycle
