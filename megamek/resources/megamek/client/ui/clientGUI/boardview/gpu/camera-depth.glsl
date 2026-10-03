// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Recover positive camera-space distance for either projection, so depth tolerances stay in world units.
uniform vec4 u_projectionDepth; // Projection matrix M22, M23, M32, M33.

float cameraDepth(float depth) {
    float ndc = depth * 2.0 - 1.0;
    return (ndc * u_projectionDepth.w - u_projectionDepth.y)
          / (ndc * u_projectionDepth.z - u_projectionDepth.x);
}

// 1 where depth farther lies behind depth nearer by more than tolerance world units and by more than a few steps of the
// 24-bit depth buffer: in perspective one step grows with the square of the distance, and separately rendered surfaces
// round differently, so a world tolerance alone would mark a visible surface as hidden far from the camera.
float behind(float nearer, float farther, float tolerance) {
    return step(nearer + 8.0 / 16777216.0, farther) * step(cameraDepth(nearer) + tolerance, cameraDepth(farther));
}
