// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Material detail follows projected surface area, independently of cached mesh LOD. Evaluate before branching
// on material coverage, including volcanic contacts, so derivatives remain defined across those boundaries.
float terrainNormalDetail;
float terrainSurfaceDetail;
const float TERRAIN_DISTANT_CAVITY = .9;

void terrainMaterialLod(vec3 world, vec3 face) {
    float pixel = sqrt(length(cross(dFdx(world), dFdy(world))));
    // A 30 m hex loses fine ground normals around LOD1 (48 px); cliff relief survives to LOD2 (24 px).
    // The short fade reaches exactly zero: callers then skip the texture fetches, not just their contribution.
    float cutoff = mix(1.25, .625, smoothstep(.75, .95, abs(face.z)));
    terrainNormalDetail = 1.0 - smoothstep(cutoff * .7, cutoff, pixel);
    terrainSurfaceDetail = 1.0 - smoothstep(.875, 1.25, pixel);
}
