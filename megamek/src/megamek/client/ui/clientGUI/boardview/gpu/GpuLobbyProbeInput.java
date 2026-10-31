/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import megamek.logging.MMLogger;

/**
 * The lobby probe's pointer (GPU thread): moves, presses and releases the mouse through the {@link Stage} at screen
 * coordinates, as the window's input would, so a probed click runs the same listeners as the Commander's.
 */
final class GpuLobbyProbeInput {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyProbeInput.class);

    private GpuLobbyProbeInput() {
    }

    /** Clicks the centre of {@code target} with the left button; false when something else lies over that point. */
    static boolean click(Stage stage, Actor target) {
        int[] at = hover(stage, target, target.getWidth() / 2, target.getHeight() / 2);
        if (at == null) {
            return false;
        }
        stage.touchDown(at[0], at[1], 0, Input.Buttons.LEFT);
        stage.touchUp(at[0], at[1], 0, Input.Buttons.LEFT);
        LOGGER.info("The lobby probe clicked {} at {},{}", target.getName(), at[0], at[1]);
        return true;
    }

    /**
     * Moves the pointer to {@code x}, {@code y} in {@code target}; returns that point in screen coordinates, or null
     * when another actor, not inside {@code target}, takes the pointer there.
     */
    static int[] hover(Stage stage, Actor target, float x, float y) {
        Vector2 point = target.localToStageCoordinates(new Vector2(x, y));
        Actor hit = stage.hit(point.x, point.y, true);
        if (hit == null || !(hit == target || hit.isDescendantOf(target))) {
            LOGGER.warn("The lobby probe found {} over {} at {},{}", hit == null ? null : hit.getClass().getName(),
                  target.getName(), point.x, point.y);
            return null;
        }
        stage.stageToScreenCoordinates(point);
        int[] screen = { Math.round(point.x), Math.round(point.y) };
        stage.mouseMoved(screen[0], screen[1]);
        return screen;
    }
}
