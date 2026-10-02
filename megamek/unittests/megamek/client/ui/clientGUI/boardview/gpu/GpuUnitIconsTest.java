/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** A Tactical View icon is sized in board units: a fixed share of its hex, inside the hex at every facing. */
class GpuUnitIconsTest {
    @Test
    void iconsFillTheSameShareOfTheirHexAndStayInsideItAtEveryFacing() {
        Coords hex = new Coords(5, 5);
        Vector3 center = BoardGeometry.center(hex, 0);
        // 0.7 hex heights (0.6 hex widths), as the classic 2D board sizes its units against the hex (user
        // correction; the mock's 24-58 pixel screen clamp is not used). The camera never enters the size, so the
        // zoom invariance through the real render is left to GpuTacticalViewSmokeTest.verifyScreenSize.
        Matrix4 north = GpuUnitIcons.place(new Matrix4(), center, 0, 0);
        float side = new Vector3(-.5f, 0, 0).mul(north).dst(new Vector3(.5f, 0, 0).mul(north));
        assertEquals(.6f, side / BoardGeometry.WIDTH, .006f, "Icon side per hex width");
        for (int facing = 0; facing < 360; facing += 15) {
            Matrix4 icon = GpuUnitIcons.place(new Matrix4(), center, 0, facing);
            for (float x : new float[] { -.5f, .5f }) {
                for (float y : new float[] { -.5f, .5f }) {
                    Vector3 corner = new Vector3(x, y, 0).mul(icon);
                    assertTrue(BoardGeometry.contains(hex, corner.x, corner.y),
                          "Corner " + corner + " of the icon turned to " + facing + " degrees lies inside its hex");
                }
            }
        }
    }
}
