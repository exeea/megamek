/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.collision.BoundingBox;

/**
 * Units standing partly in open water, as the water shader sees them each frame: where each crosses the surface, how
 * wide it is there and how fast it moves, so the water laps against it in a broken collar of foam, sends ripples out
 * and trails a wake. A unit wholly under the surface or clear above it stirs nothing.
 */
final class GpuWaders {
    /** Most units the water reacts to at once; the shader's arrays hold this many. */
    static final int MAX = 12;
    /** Share of a unit's horizontal extent that meets the water: legs and hulls, not arms and turrets. */
    private static final float BODY = .4f;
    /** How quickly the measured motion follows the unit, per second; it smooths away per-frame jitter. */
    private static final float SMOOTHING = 6;

    /** Per unit: centre x and y, radius at the waterline and water level, in world units. */
    final float[] bodies = new float[MAX * 4];
    /** Per unit: velocity x and y in world units per second. */
    final float[] motion = new float[MAX * 4];
    int count;
    private final Map<Object, float[]> previous = new IdentityHashMap<>();
    private final Map<Object, float[]> next = new IdentityHashMap<>();

    /**
     * Finds the units in open water. {@code keys} identify the same unit from frame to frame, in step with
     * {@code bounds}, its world bounds this frame.
     */
    void update(BoardScene scene, List<?> keys, List<BoundingBox> bounds, float delta) {
        count = 0;
        next.clear();
        for (int i = 0; i < keys.size() && scene != null; i++) {
            BoundingBox box = bounds.get(i);
            float x = box.getCenterX(), y = box.getCenterY();
            BoardScene.Tile tile = BoardGeometry.tile(scene, x, y);
            if (tile == null || !tile.liquid().present() || tile.liquid().molten() || tile.frozen()) { continue; }
            float level = BoardGeometry.waterZ(tile);
            if (box.min.z >= level || box.max.z <= level) { continue; }
            float[] state = previous.get(keys.get(i));
            float vx = 0, vy = 0;
            if (state != null && delta > 0) {
                float follow = Math.min(1, SMOOTHING * delta);
                vx = state[2] + ((x - state[0]) / delta - state[2]) * follow;
                vy = state[3] + ((y - state[1]) / delta - state[3]) * follow;
            } else if (state != null) {
                vx = state[2];
                vy = state[3];
            }
            next.put(keys.get(i), new float[] { x, y, vx, vy });
            if (count == MAX) { continue; }
            float radius = BODY * (box.getWidth() + box.getHeight()) / 2;
            bodies[count * 4] = x;
            bodies[count * 4 + 1] = y;
            bodies[count * 4 + 2] = Math.max(radius, 2 * BoardGeometry.HEX_SCALE);
            bodies[count * 4 + 3] = level;
            motion[count * 4] = vx;
            motion[count * 4 + 1] = vy;
            count++;
        }
        previous.clear();
        previous.putAll(next);
    }
}
