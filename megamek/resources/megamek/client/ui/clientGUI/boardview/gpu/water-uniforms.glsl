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
uniform vec3 u_wind;
uniform int u_splashCount;
uniform vec4 u_splashLines[12];  // where falls land nearby: from XY, to XY, the pool to the right; hex widths
uniform float u_splashRadii[12]; // radius of each landing's boil, hex widths
uniform sampler2D u_waterOcean;  // GpuOcean: RG wave slope, B crest compression, A foam; tiles
uniform float u_waterOceanScale; // world XY to ocean UV; zero without the simulation
uniform float u_metre;           // world units per metre
uniform float u_levelHeight;     // world units per level
uniform float u_waterLine;       // water surface inset below its game level, shared with the bed shader
uniform int u_waderCount;
uniform vec4 u_waders[12];       // GpuWaders: centre XY, radius at the waterline, water level; world units
uniform vec4 u_waderMotion[12];  // velocity XY, world units per second
const float SHORE_RANGE = 0.4;   // hex widths encoded by the field's red channel
const float FLOW_CYCLE = 2.4;    // seconds per two-phase advection cycle
const float OCEAN_SIZE = 128.0;  // texels along a side of the ocean texture
// A fixed turn of the finer layer, so its wave trains cross the swell's. Being constant, it cannot shear.
const mat2 CROSSING = mat2(0.52, 0.85, -0.85, 0.52);
const mat2 CROSSING2 = mat2(-0.46, 0.888, -0.888, -0.46);

