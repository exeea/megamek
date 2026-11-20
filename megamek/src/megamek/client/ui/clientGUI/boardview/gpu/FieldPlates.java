/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/**
 * Paved hexes on the height field ({@link TerrainField}): level plates on the pavement's fitted outline (ConcreteFit
 * through {@link BoardConcrete}), with a wall in the board cut's form wherever a plate stands over its neighbour
 * (ChunkField). Ramped plates and pavement gaps stay today's for now. {@link FieldBoard} keeps which hexes are plates.
 */
final class FieldPlates {
    private FieldPlates() { }

    /** Whether the field takes a paved hex as a plate: dry and whole (no gap); its roads ramp on it (FieldRoads#surface). */
    static boolean takes(BoardScene scene, BoardScene.Tile t) {
        return t.surface() == BoardScene.Surface.CONCRETE && !t.liquid().present() && !t.ultraSublevel()
              && !t.backdrop().present() && !BoardFeatures.shaft(t) && BoardBridge.feature(t) == null && !parted(scene, t);
    }

    /** Whether a side the author separated parts the hex's pavement from its neighbour's (BoardSurface#gapStrips). */
    static boolean parted(BoardScene scene, BoardScene.Tile t) {
        for (int e = 0; e < 6; e++) {
            int direction = BoardGeometry.edgeDirection(e);
            BoardScene.Tile next = scene.tile(t.coords().translated(direction));
            if (next != null && t.pavementApart(next, direction)) { return true; }
        }
        return false;
    }

    /** Metres: a drop or a rise this far off shades nothing, so a sample with none near has it (ChunkField.Block#rimMetres). */
    private static final float NONE_NEAR = 99;

    /**
     * A plate's sample, or natural ground on its outline at its level: level at {@code z}, no step near. "None near" is
     * the farthest a sample's shading reads (ChunkField.Block#rimMetres), a number: a sample cut in between two of them
     * (FieldClip, a tunnel's portal on a paved hex) takes their share of it, and of infinity there is none.
     */
    static void level(float z, int level, float[] out) {
        out[0] = z;
        out[1] = 0;
        out[2] = 0;
        out[3] = BoardRelief.metres(NONE_NEAR);
        out[4] = out[3];
        out[5] = 0;
        out[6] = level;
    }

    /** Corner k of h where the fit puts it, as a move from the regular corner (x, y): {dx, dy, 1 (held)}. */
    static float[] corner(BoardConcrete concrete, Coords h, int k, float x, float y) {
        Vector3 fitted = concrete.corner(h, k);
        return new float[] { fitted.x - x, fitted.y - y, 1 };
    }

    /** Whether (x, y) lies inside the polygon (px, py) or within {@code tolerance} of its outline. */
    static boolean covers(float[] px, float[] py, float x, float y, float tolerance) {
        boolean in = false;
        int n = px.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float ax = px[j], ay = py[j], bx = px[i], by = py[i];
            if ((by > y) != (ay > y) && x < (ax - bx) * (y - by) / (ay - by) + bx) { in = !in; }
            float dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
            float t = length == 0 ? 0 : Math.clamp(((x - ax) * dx + (y - ay) * dy) / length, 0, 1);
            float ex = ax + dx * t - x, ey = ay + dy * t - y;
            if (ex * ex + ey * ey <= tolerance * tolerance) { return true; }
        }
        return in;
    }
}
