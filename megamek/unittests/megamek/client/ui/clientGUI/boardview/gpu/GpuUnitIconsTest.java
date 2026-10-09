/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** Tactical sprites preserve their aspect ratio and board-space size while turning with the unit. */
class GpuUnitIconsTest {
    @Test
    void spritesKeepTheirAspectAndHexShareAtEveryFacing() {
        Coords hex = new Coords(5, 5);
        Vector3 center = BoardGeometry.center(hex, 0);
        for (int[] pixels : new int[][] { { 84, 72 }, { 40, 80 }, { 120, 40 } }) {
            float scale = GpuUnitIcons.spriteScale(pixels[0], pixels[1]);
            float halfWidth = pixels[0] * scale / 2, halfHeight = pixels[1] * scale / 2;
            for (int facing = 0; facing < 360; facing += 15) {
                Matrix4 icon = GpuUnitIcons.place(new Matrix4(), center, 0, facing);
                float width = new Vector3(-halfWidth, 0, 0).mul(icon)
                      .dst(new Vector3(halfWidth, 0, 0).mul(icon));
                float height = new Vector3(0, -halfHeight, 0).mul(icon)
                      .dst(new Vector3(0, halfHeight, 0).mul(icon));
                assertEquals(.9f, Math.max(width / BoardGeometry.width(), height / BoardGeometry.height()), .0001f,
                      "The limiting sprite dimension fills 90% of the hex");
                assertEquals(pixels[0] / (float) pixels[1], width / height, .0001f,
                      "Rotating the sprite preserves its aspect ratio");
            }
        }
    }
}
