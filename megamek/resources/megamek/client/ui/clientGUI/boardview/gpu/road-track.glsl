// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Where a loose road's one coat (GpuRoads#patches) shows its stone or soil across the road, for the coat's own shader
// (terrain-road.frag looseTrack, dirtTrack) and for the ground map's cover (ground-map-cover.frag), so that grass stands
// where the coat shows ground. The coat reaches a shoulder further than it shows. Sizes are metres.
const float TRACK_HALF = 5.96;  // HexTile.ROAD_HALF_WIDTH plus BoardRoad.SHOULDER in metres: gravel's B range
const float GRAVEL_EDGE = 3.4;  // a gravel road's graded edge (BoardRoad.GRAVEL_EDGE)
const float RUT_OFFSET = .9;    // BoardRoad.RUT_OFFSET in metres: a dirt track's ruts either side of its course
const float RUT_RANGE = 2.9;    // BoardRoad.RUT_OFFSET + RUT_REACH in metres: dirt's B range
const float RUT_HALF = .3;      // a rut's half width

// A rut's packed soil, `off` metres from its centre line.
float rutPressed(float off) { return 1.0 - smoothstep(RUT_HALF - .08, RUT_HALF + .02, off); }

// A dirt track's soil at a point `rut` metres from the nearest rut's centre line, outward positive: over 0 its loose
// soil lies, in a band outside the ruts and in bare patches on the crown between them, most beside the ruts. `clump`,
// `tuft` and `grain` break its edge up (.5 each: the edge unbroken).
float dirtSoil(float rut, vec2 clump, vec2 tuft, float grain) {
    float band = rut - RUT_HALF - .2 - .5 * clump.r - (tuft.g - .5) * .25 - (grain - .5) * .1;
    float crown = -rut - RUT_HALF;
    float bare = .55 * clump.g + .3 * tuft.r + .15 * grain - .62 - 1.1 * crown;
    return rut > 0.0 ? -band : bare;
}

// A track's fringe: 1 where its stone or soil lies (`inside` metres within its edge) and it has not ended (`ends` 1 or
// more), thinning out into the grass over most of a metre past its edge, and with its end.
float trackFringe(float inside, float ends) { return smoothstep(-.8, 0.0, min(inside, ends - 1.0)); }
