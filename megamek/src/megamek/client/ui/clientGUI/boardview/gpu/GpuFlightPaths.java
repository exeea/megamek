/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import megamek.common.annotations.Nullable;

/**
 * The flight path of each aircraft whose move is held back ({@link BoardAirspace}): a dashed line in its owner's
 * colour along the hexes it flies through, from where its model waits to its faded copy at the destination. It lies on
 * the shared flat overlay plane with the other hex marks, and is brighter for the selected unit. It adds no draw call:
 * the overlay's mark pass draws it.
 */
final class GpuFlightPaths {
    /** A dash and the gap after it, and the line's half width, in hexes. */
    private static final float DASH_HEXES = .24f;
    private static final float GAP_HEXES = .14f;
    private static final float HALF_WIDTH_HEXES = .03f;
    /** Every visible aircraft's line is faint; the selected one's stands out. */
    private static final float OPACITY = .35f;
    private static final float SELECTED_OPACITY = .85f;

    /** Takes the overlay's filled triangles, three corners and a packed colour. */
    interface Triangles {
        void triangle(Vector3 first, Vector3 second, Vector3 third, float colour);
    }

    private GpuFlightPaths() {
    }

    /**
     * Adds every held aircraft's flight path to the mark pass.
     *
     * @param scene     the frame's scene
     * @param opacity   the pass's opacity: full, or the share for marks hidden by scenery
     * @param triangles the overlay's filled triangles
     */
    static void draw(BoardScene scene, float opacity, Triangles triangles) {
        for (HeldFlight flight : scene.airspace().flights()) {
            BoardScene.Unit unit = unit(scene, flight.entityId());
            if (unit == null) {
                continue;
            }
            Color owner = new Color();
            Color.rgb888ToColor(owner, unit.outlineRgb());
            float share = (scene.selectedId() == flight.entityId()) ? SELECTED_OPACITY : OPACITY;
            float colour = Color.toFloatBits(owner.r, owner.g, owner.b, share * opacity);
            drawPath(scene, flight, colour, triangles);
        }
    }

    /** Dashes run on across the path's turns, so the line reads as one route. */
    private static void drawPath(BoardScene scene, HeldFlight flight, float colour, Triangles triangles) {
        float hex = BoardGeometry.height();
        float period = hex * (DASH_HEXES + GAP_HEXES);
        float halfWidth = hex * HALF_WIDTH_HEXES;
        float travelled = 0;
        Vector3 from = null;
        for (BoardScene.Waypoint point : flight.path()) {
            // A hex off the board (an aircraft entering or leaving over its edge) has no plane to draw on.
            if (scene.tile(point.coords()) == null) {
                continue;
            }
            Vector3 to = onPlane(scene, point);
            if (from == null) {
                from = to;
                continue;
            }
            float length = Vector3.len(to.x - from.x, to.y - from.y, 0);
            if (length <= 0) {
                continue;
            }
            Vector3 along = new Vector3(to).sub(from).scl(1 / length);
            Vector3 side = new Vector3(-along.y, along.x, 0).nor();
            for (float start = -(travelled % period); start < length; start += period) {
                float dashStart = Math.max(0, start);
                float dashEnd = Math.min(length, start + hex * DASH_HEXES);
                if (dashEnd > dashStart) {
                    dash(from, along, side, dashStart, dashEnd, halfWidth, colour, triangles);
                }
            }
            travelled += length;
            from = to;
        }
    }

    private static void dash(Vector3 from, Vector3 along, Vector3 side, float start, float end, float halfWidth,
          float colour, Triangles triangles) {
        Vector3 dashStart = new Vector3(from).mulAdd(along, start);
        Vector3 dashEnd = new Vector3(from).mulAdd(along, end);
        Vector3 startLeft = new Vector3(dashStart).mulAdd(side, halfWidth);
        Vector3 startRight = new Vector3(dashStart).mulAdd(side, -halfWidth);
        Vector3 endLeft = new Vector3(dashEnd).mulAdd(side, halfWidth);
        Vector3 endRight = new Vector3(dashEnd).mulAdd(side, -halfWidth);
        triangles.triangle(startLeft, startRight, endRight, colour);
        triangles.triangle(startLeft, endRight, endLeft, colour);
    }

    /** A path point's hex centre on the shared flat overlay plane. */
    private static Vector3 onPlane(BoardScene scene, BoardScene.Waypoint point) {
        return BoardGeometry.center(point.coords(), 0)
              .add(0, 0, BoardTacticalGeometry.floatingZ(scene, point.coords()));
    }

    private static @Nullable BoardScene.Unit unit(BoardScene scene, int entityId) {
        for (BoardScene.Unit unit : scene.units()) {
            if ((unit.id() == entityId) && !unit.sensorContact()) {
                return unit;
            }
        }
        return null;
    }
}
