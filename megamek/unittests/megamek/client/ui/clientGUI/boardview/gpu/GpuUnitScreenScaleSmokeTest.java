/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Zoom-out unit scaling (user decision 26) in the running 3D view and on the board-space harness: at or above the
 * threshold a unit keeps its size; zoomed out further it grows on every axis and keeps its size on screen, up to the
 * maximum; picking and its anchor follow the grown unit, the tuning controls change the growth live, and the Tactical
 * View's icons keep their size in the hex.
 */
@Tag("on-demand")
class GpuUnitScreenScaleSmokeTest {
    /** The fixture's own Atlas. */
    private static final int ATLAS = 1;
    private static final Coords OWN = new Coords(5, 5);
    private static final float NEAR = .0001f;

    @Test
    void zoomedOutUnitsGrowUniformlyKeepTheirSizeOnScreenAndStayPickable() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        GUIPreferences preferences = GUIPreferences.getInstance();
        float originalScale = preferences.getGUIScale();
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                fixture.source.refresh();
            });
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1920, 1080);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    GpuBattleView view = null;
                    try {
                        // One logical HUD unit per window pixel, as in the 1920x1080 prototype captures.
                        float preference = .1f / DisplayScale.read(.1f, new GpuDisplayScale().contentScale());
                        SwingUtilities.invokeAndWait(() -> preferences.setValue(GUIPreferences.GUI_SCALE, preference));
                        view = new GpuBattleView(fixture.source);
                        view.create();
                        // The board arrives once its terrain is built behind the loading screen.
                        GpuBoardTestUi.present(view);
                        verify(view);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        if (view != null) { view.dispose(); }
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setValue(GUIPreferences.GUI_SCALE, originalScale));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    @SuppressWarnings("unchecked")
    private static void verify(GpuBattleView view) throws Exception {
        render(view);
        var instances = (Map<String, ModelInstance>) field(view, "unitInstances");
        var scene = (BoardScene) field(view, "scene");
        ModelInstance model = instances.get(ATLAS + ":-1");
        assertNotNull(model, "The own Atlas is a 3D model");
        // The board view's tuning model, whose controls the panel mirrors; the growth on, from its constants.
        CheckBox scaling = GpuBoardTestUi.tuning(view, "tuning-zoom-scaling");
        Slider from = GpuBoardTestUi.tuning(view, "Threshold hex px");
        Slider max = GpuBoardTestUi.tuning(view, "Max zoom-out scale");
        scaling.setChecked(true);
        float threshold = UnitScreenScale.THRESHOLD;
        float scale = (float) field(view, "layoutScale");
        view.boardCamera.center(BoardGeometry.center(OWN, scene.tile(OWN).elevation()));

        // At and above the threshold the Atlas keeps its size.
        zoomTo(view, 2 * threshold, scale);
        Vector3 size = model.transform.getScale(new Vector3());
        zoomTo(view, threshold, scale);
        assertEquals(size, model.transform.getScale(new Vector3()), "At the threshold the Atlas keeps its size");
        Vector3 feet = model.transform.getTranslation(new Vector3());
        float height = UnitBounds.world(model).max.z - feet.z;
        float tall = screenHeight(view, model);
        float head = screen(view, feet).y - screen(view, anchor(view)).y;
        System.out.printf("Hex %.0f HUD pixels wide: the Atlas is %.1f pixels tall, its anchor %.1f above its feet%n",
              threshold, tall, head);
        capture("unit-scale-threshold.png");

        // Zoomed out, it grows on every axis about its feet and keeps the size it had on screen, up to the maximum.
        for (float hexPixels : new float[] { threshold / 1.25f, threshold / 1.5f, threshold / UnitScreenScale.MAX,
              threshold / 3 }) {
            zoomTo(view, hexPixels, scale);
            float growth = Math.min(threshold / hexPixels, UnitScreenScale.MAX);
            Vector3 grown = model.transform.getScale(new Vector3());
            System.out.printf("Hex %.1f HUD pixels wide: the Atlas grows %.3f times and is %.1f pixels tall%n",
                  hexPixels, grown.x / size.x, screenHeight(view, model));
            assertEquals(growth * size.x, grown.x, NEAR * size.x, "Grown " + growth + " times");
            assertEquals(growth * size.y, grown.y, NEAR * size.y);
            assertEquals(growth * size.z, grown.z, NEAR * size.z, "on every axis: its proportions stay");
            assertTrue(feet.epsilonEquals(model.transform.getTranslation(new Vector3()), .001f), "about its feet");
            float onScreen = growth * hexPixels / threshold;
            assertEquals(onScreen * tall, screenHeight(view, model), .02f * tall, "As tall on screen as at the "
                  + "threshold until the maximum, then shrinking with the hex");
            assertEquals(onScreen * head, screen(view, feet).y - screen(view, anchor(view)).y, .02f * head,
                  "Its anchor, where labels hang, follows");
        }

        // Grown to the maximum, a point above the head the Atlas has at its size picks it; at its size, the ground.
        zoomTo(view, threshold / UnitScreenScale.MAX, scale);
        capture("unit-scale-zoomed-out.png");
        Vector3 above = screen(view, new Vector3(feet).add(0, 0, 1.6f * height));
        assertEquals(OWN, pick(view, above), "The grown Atlas takes the pick above its own size's head");
        scaling.setChecked(false);
        render(view);
        assertEquals(size, model.transform.getScale(new Vector3()), "Switched off, the Atlas keeps its size");
        capture("unit-scale-zoomed-out-off.png");
        assertNotEquals(OWN, pick(view, above), "At its size the Atlas is not under that point");
        scaling.setChecked(true);

        // The tuning controls change the growth on the next frame; Defaults restores the constants.
        zoomTo(view, threshold / 1.5f, scale);
        from.setValue(threshold / 2);
        render(view);
        assertEquals(size.x, model.transform.getScaleX(), NEAR * size.x, "Below a lower threshold: no growth yet");
        from.setValue(2 * threshold);
        render(view);
        assertEquals(UnitScreenScale.MAX * size.x, model.transform.getScaleX(), NEAR * size.x, "A higher one");
        max.setValue(1.25f);
        render(view);
        assertEquals(1.25f * size.x, model.transform.getScaleX(), NEAR * size.x, "A lower maximum");
        GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(view, "tuning-defaults"));
        render(view);
        assertEquals(1.5f * size.x, model.transform.getScaleX(), NEAR * size.x, "Defaults restore the constants");

        // The Tactical View's icons keep their share of the hex where the 3D view's units grow.
        zoomTo(view, threshold / 3, scale);
        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        render(view);
        var icons = (GpuUnitIcons) field(view, "unitIcons");
        assertTrue(icons.active());
        ModelInstance icon = icons.instance(scene.units().stream().filter(unit -> unit.id() == ATLAS).findFirst()
              .orElseThrow());
        float side = screen(view, new Vector3(-.5f, 0, 0).mul(icon.transform))
              .dst(screen(view, new Vector3(.5f, 0, 0).mul(icon.transform)));
        float hex = screen(view, BoardGeometry.corner(OWN, 0, 3)).dst(screen(view, BoardGeometry.corner(OWN, 0, 0)));
        float share = GpuUnitIcons.SIZE_IN_HEXES * BoardGeometry.HEIGHT / BoardGeometry.WIDTH;
        assertEquals(share, side / hex, .01f * share, "The icon keeps its share of the hex");
        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        render(view);
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    @Test
    void theBoardSpaceHarnessGrowsItsUnitsAsTheViewDoesAndPickingHitsTheGrownUnit() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            UnitPicking picking = new UnitPicking();
            try {
                UnitScreenScale.enabled = true;
                UnitScreenScale.threshold = UnitScreenScale.THRESHOLD;
                UnitScreenScale.max = UnitScreenScale.MAX;
                board.view(false);
                var atlas = scene.units().stream().filter(unit -> unit.id() == GpuHudFixtures.ATLAS).findFirst()
                      .orElseThrow();
                BoardProjectionCamera camera = board.camera.camera;
                board.camera.center(board.poses.get(atlas).position());
                board.camera.zoom(BoardGeometry.WIDTH / (UnitScreenScale.THRESHOLD / 1.5f) / camera.zoom);
                board.draw(view -> { });
                ModelInstance sprite = board.instances.get(atlas);
                Vector3 centre = sprite.transform.getTranslation(new Vector3());
                float anchor = board.anchors.get(atlas).dst(centre);
                hud.captureBackBuffer("unit-scale-harness").dispose();
                // A point of the grown sprite .45 of its width from its centre: .675 of its width at its size.
                Vector3 edge = new Vector3(.45f * atlas.image().width(), 0, 0).mul(sprite.transform);
                Ray ray = new Ray(new Vector3(edge).mulAdd(camera.direction, -10000), camera.direction);
                assertTrue(picking.distance(sprite, ray) < Float.POSITIVE_INFINITY, "The grown sprite takes the pick");

                UnitScreenScale.enabled = false;
                board.draw(view -> { });
                assertEquals(Float.POSITIVE_INFINITY, picking.distance(sprite, ray), "At its size, it does not");
                assertEquals(anchor, 1.5f * board.anchors.get(atlas).dst(centre), .01f * anchor,
                      "The harness's anchor follows the growth, as the view's does");
            } finally {
                UnitScreenScale.enabled = UnitScreenScale.ENABLED;
                board.dispose();
            }
        });
    }

    /** Zooms the 3D view so that a hex is {@code hexPixels} HUD pixels wide, then renders. */
    private static void zoomTo(GpuBattleView view, float hexPixels, float scale) {
        view.boardCamera.zoom(BoardGeometry.WIDTH / (hexPixels * scale) / view.boardCamera.camera.zoom);
        render(view);
    }

    /** The Atlas's anchor in the view's last frame. */
    @SuppressWarnings("unchecked")
    private static Vector3 anchor(GpuBattleView view) throws Exception {
        var anchors = (Map<BoardScene.Unit, Vector3>) field(view, "unitAnchors");
        return anchors.entrySet().stream().filter(entry -> entry.getKey().id() == ATLAS).findFirst().orElseThrow()
              .getValue();
    }

    /** The height on screen, in window pixels, of the instance's posed bounds. */
    private static float screenHeight(GpuBattleView view, ModelInstance instance) throws Exception {
        BoundingBox bounds = UnitBounds.world(instance);
        float low = Float.POSITIVE_INFINITY;
        float high = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            float y = screen(view, new Vector3((corner & 1) == 0 ? bounds.min.x : bounds.max.x,
                  (corner & 2) == 0 ? bounds.min.y : bounds.max.y, (corner & 4) == 0 ? bounds.min.z : bounds.max.z)).y;
            low = Math.min(low, y);
            high = Math.max(high, y);
        }
        return high - low;
    }

    /** Window coordinates (y down) of a world point. */
    private static Vector3 screen(GpuBattleView view, Vector3 world) {
        BoardProjectionCamera camera = view.boardCamera.camera;
        Vector3 point = camera.project(world.cpy(), 0, 0, camera.viewportWidth, camera.viewportHeight);
        point.y = Gdx.graphics.getHeight() - point.y;
        return point;
    }

    /** The coordinates the view's own board input picks at a window point. */
    private static Coords pick(GpuBattleView view, Vector3 point) throws Exception {
        Object input = field(view, "boardInput");
        var pick = input.getClass().getDeclaredMethod("pick", int.class, int.class);
        pick.setAccessible(true);
        return (Coords) pick.invoke(input, Math.round(point.x), Math.round(point.y));
    }

    private static void render(GpuBattleView view) {
        for (int frame = 0; frame < 3; frame++) {
            view.render();
        }
    }

    private static void capture(String name) {
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        GpuBoardTestUi.capture(new File(directory, name));
    }
}
