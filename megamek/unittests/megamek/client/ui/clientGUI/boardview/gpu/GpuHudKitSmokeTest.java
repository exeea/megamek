/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiTestStage.place;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.gdx.UiTestStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The battle HUD's sprite mask ownership, and the roster over the shipped board as sprite models in the 3D view and
 * as meeples on their GpuUnitBases in the Tactical View.
 */
@Tag("on-demand")
class GpuHudKitSmokeTest {
    @Test
    void masksLiveWhileReferenced() {
        GpuHudTestStage.run(hud -> {
            // Sprite masks share one atlas that lives while the snapshots reference them, drawn or not (C.4).
            Set<Integer> live = UiTestStage.liveTextures();
            BoardScene.Pixels atlas = GpuHudFixtures.sprite("Atlas");
            GpuHudKit.UnitSprite sprite = hud.kit.sprite(40, 34).set(atlas, GpuHudKit.FRIEND_SPRITE);
            place(hud.window, sprite, 100, 300);
            hud.sprites.add(atlas);
            hud.sprites.add(GpuHudFixtures.sprite("Warhammer"));
            hud.draw();
            assertEquals(1, added(live).size(), "One atlas page holds both referenced masks");
            sprite.setVisible(false);
            hud.draw();
            assertEquals(1, added(live).size(), "A mask outlives a frame that does not draw it");
            hud.sprites.clear();
            sprite.remove();
            hud.draw();
            assertTrue(added(live).isEmpty(), "Masks are freed once nothing references them");
        });
    }

    /** The GL textures alive now that were not alive {@code before}. */
    private static Set<Integer> added(Set<Integer> before) {
        Set<Integer> now = UiTestStage.liveTextures();
        now.removeAll(before);
        return now;
    }

    @Test
    void boardSpaceHarnessDrawsTheRosterInTheThreeDimensionalAndTacticalViews() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        assertEquals(10, scene.units().size());
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene, hud.theme.skin);
            try {
                for (boolean tactical : new boolean[] { false, true }) {
                    board.view(tactical);
                    assertEquals(tactical, board.tactical());
                    String view = tactical ? "tactical" : "3d";
                    board.units = false;
                    board.draw(camera -> { });
                    Pixmap bare = hud.captureBackBuffer("board-space-" + view + "-bare");
                    try {
                        // The 3D view's units are GpuBattleView's sprite models, the Tactical View's its meeples on
                        // their bases; the contact is its marker in both.
                        board.units = true;
                        board.draw(camera -> { });
                        board.units = false;
                        Pixmap units = hud.captureBackBuffer("board-space-units-" + view);
                        try {
                            assertEquals(scene.units().size(), board.anchors.size(), "An anchor for every unit");
                            for (BoardScene.Unit unit : scene.units()) {
                                Vector2 center = board.screen(
                                      board.instances.get(unit).transform.getTranslation(new Vector3()));
                                // A 13 x 13 window: the woods' canopies may hide a unit's own anchor.
                                assertTrue(changedTexels(bare, units, center, 6) >= 13, unit.name()
                                      + " is drawn at " + center + " in the " + view + " view");
                                if (tactical) {
                                    assertNotNull(board.bases().base(unit), "A base for " + unit.name());
                                }
                            }
                        } finally {
                            units.dispose();
                        }
                    } finally {
                        bare.dispose();
                    }
                }
            } finally {
                board.dispose();
            }
        });
    }
    /** Texels within the radius of the point (back-buffer pixels, y up) whose color changed by more than 30. */
    private static int changedTexels(Pixmap before, Pixmap after, Vector2 point, int radius) {
        int count = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int x = Math.round(point.x) + dx;
                int y = Math.round(point.y) + dy;
                int a = before.getPixel(x, y);
                int b = after.getPixel(x, y);
                for (int shift = 8; shift < 32; shift += 8) {
                    if (Math.abs((a >>> shift & 0xFF) - (b >>> shift & 0xFF)) > 30) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }
}
