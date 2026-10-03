// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Water and lava share their advection. Each phase resets only at zero weight; material layers use the same UVs.
struct LiquidFlow {
    vec2 phase;
    vec2 weight;
    vec2 generation;
    float duration;
};

LiquidFlow liquidFlow(float time, float duration, float stagger) {
    vec2 cycles = vec2(time / duration + stagger) + vec2(0.0, .5);
    vec2 phase = fract(cycles);
    float second = abs(phase.x * 2.0 - 1.0);
    return LiquidFlow(phase, vec2(1.0 - second, second), floor(cycles), duration);
}

vec2 liquidFlowUv(vec2 position, vec2 velocity, LiquidFlow flow, int phase, vec2 sourceStep, float refresh) {
    // Fresh source windows prevent a static material repeating the exact same short animation forever.
    // Bound the source offset independently of velocity, so bends cannot accumulate deformation over time.
    vec2 source = fract(flow.generation[phase] * sourceStep) * refresh;
    return position - velocity * ((flow.phase[phase] - .5) * flow.duration) + source;
}

float liquidFlowVariance(LiquidFlow flow) { return inversesqrt(dot(flow.weight, flow.weight)); }
