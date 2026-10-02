/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.gdx.UiTestStage;

/**
 * The component harness (rebuild plan E rule 6): a hidden 1920 x 1080 window with the HUD skin, the kit and a Stage
 * of one unit per back-buffer pixel, as the prototype's CSS pixels. Components go into {@link #window}, a group of the
 * emulated window's size at the stage's lower left, so the stage and its pixel snapping stay full-window at every
 * size. With the toolkit's {@link UiTestStage} helpers it writes {name}.png and {name}-vs-mock.png and fails when a GL
 * texture outlives the test or a label shows a missing message key. All calls run on the GL thread.
 */
final class GpuHudTestStage {
    /** The hud-v3 screenshots, by default from the sibling checkout; comparisons are skipped without them. */
    static final File MOCK = UiTestStage.MOCK;

    /** The test body; it runs on the GL thread with the harness it receives. */
    interface Body {
        void run(GpuHudTestStage hud) throws Exception;
    }

    final GpuBoardSkin theme = new GpuBoardSkin();
    final GpuHudKit kit = new GpuHudKit(theme.skin);
    final Stage stage = new Stage(new ScreenViewport());
    /** The emulated window; its size is set by {@link #size}. */
    final Group window = new Group();
    /** The unit images the test's snapshots reference; each frame hands them to the kit, as GpuHud will. */
    final Set<BoardScene.Pixels> sprites = new HashSet<>();

    private GpuHudTestStage() {
        // Units per logical pixel = back-buffer pixels per logical pixel, so one unit is one back-buffer pixel.
        float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(density);
        stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        window.setTransform(false);
        stage.addActor(window);
        size(1920, 1080);
    }

    /**
     * Opens the battle window's configuration, hidden, at 1920 x 1080, runs {@code body}, disposes the stage, kit and
     * skin, and checks that every GL texture made in between was deleted.
     */
    static void run(Body body) {
        Lwjgl3ApplicationConfiguration configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1920, 1080);
        UiTestStage.onGl(configuration, () -> {
            GpuHudTestStage hud = new GpuHudTestStage();
            try {
                body.run(hud);
            } finally {
                hud.stage.dispose();
                hud.kit.dispose();
                hud.theme.dispose();
            }
        });
    }

    /**
     * Emulates a window of {@code width} x {@code height} units (back-buffer pixels) in the lower left corner, which
     * must fit in the real window.
     */
    void size(int width, int height) {
        assertTrue(width <= stage.getWidth() && height <= stage.getHeight(), "A " + width + " x " + height
              + " window does not fit in the " + stage.getWidth() + " x " + stage.getHeight() + " stage");
        window.setSize(width, height);
    }

    int width() {
        return Math.round(window.getWidth());
    }

    int height() {
        return Math.round(window.getHeight());
    }

    /** Clears to a mid-tone olive, roughly the grass behind the prototype's panels, and draws one frame. */
    void draw() {
        ScreenUtils.clear(.42f, .5f, .3f, 1, true);
        drawStage();
    }

    /** Draws one frame of the stage over whatever the back buffer holds, e.g. a board-space harness. */
    void drawStage() {
        stage.getViewport().apply(true);
        stage.act(0);
        kit.update(sprites);
        stage.draw();
    }

    /**
     * Writes the emulated window's area of the back buffer to {name}.png after checking that no label shows a
     * missing message key; the caller disposes the returned image.
     */
    Pixmap capture(String name) {
        UiTestStage.assertTexts(stage.getRoot());
        return UiTestStage.capture(name, width(), height());
    }

    /** Writes the whole back buffer, which a board-space harness fills, to {name}.png. */
    Pixmap captureBackBuffer(String name) {
        return UiTestStage.capture(name, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    /** Writes {name}-vs-mock.png: the actor's area of {@code captured} beside the same area of the mock. */
    void compare(String name, Pixmap captured, Actor actor, String mock, int x, int y) {
        UiTestStage.compare(name, captured, actor, mock, x, y);
    }

    /** The actor's bounds in stage units, y up; the window's lower left corner is the stage's. */
    static Rectangle bounds(Actor actor) {
        return UiTestStage.bounds(actor);
    }

    /**
     * The E rule 6 layout checks: the actor lies inside the emulated window, overlaps none of the other slots'
     * rectangles and has no child wider than itself.
     */
    void assertLayout(Actor actor, Rectangle... slots) {
        Rectangle area = bounds(actor);
        assertTrue(area.x >= -.5f && area.y >= -.5f && area.x + area.width <= width() + .5f
              && area.y + area.height <= height() + .5f, actor.getName() + " at " + area + " leaves the "
              + width() + " x " + height() + " window");
        for (Rectangle slot : slots) {
            Rectangle overlap = new Rectangle();
            boolean overlaps = Intersector.intersectRectangles(area, slot, overlap)
                  && overlap.width > .5f && overlap.height > .5f;
            assertTrue(!overlaps, actor.getName() + " at " + area + " overlaps " + slot);
        }
        if (actor instanceof Group group) {
            GpuBoardTestUi.assertHorizontalBounds(group, actor);
        }
    }
}
