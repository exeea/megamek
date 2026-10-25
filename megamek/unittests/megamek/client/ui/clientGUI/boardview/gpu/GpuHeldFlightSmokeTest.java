/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Renders a held aircraft for review: a fighter that flew two hexes east waits at the start of its move, a faded copy in
 * its owner's colour stands at its destination, and a dashed line joins them on the overlay plane. Both views are
 * written to {@code megamek.gpu.screenshots}/held-flight on the review's dark background.
 */
@Tag("on-demand")
class GpuHeldFlightSmokeTest {
    private static final int FIGHTER = 9901;
    private static final int OWNER_RGB = 0x3399ff;
    private static final int ALTITUDE_LEVELS = 3;

    @Test
    void aHeldAircraftWaitsAtItsStartWithAFadedCopyAtItsDestination() throws Exception {
        BoardScene board = GpuBoardSpaceHarness.scene();
        Coords start = new Coords(2, 3);
        Coords middle = new Coords(3, 3);
        Coords destination = new Coords(4, 3);
        assertNotNull(board.tile(start), "the review board has the start hex");
        assertNotNull(board.tile(destination), "the review board has the destination hex");
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var library = new GpuUnitModels();
                var flightModels = new GpuFlightModels();
                var paths = new ShapeRenderer();
                try (var renderer = new GpuPlaybackReview.ReviewRenderer()) {
                    renderer.camera.viewportWidth = 320;
                    renderer.camera.viewportHeight = 240;
                    var selection = GpuFamilyAssemblyReview.selection("fighter", List.of());
                    var model = library.get(selection, FIGHTER);
                    assertNotNull(model, "the fighter family has a model");
                    float elevation = board.tile(destination).elevation() + ALTITUDE_LEVELS;
                    var arrival = new BoardScene.Waypoint(destination, elevation, 2);
                    var unit = new BoardScene.Unit(FIGHTER, -1, "Held fighter", arrival, null, false, null, 2, true,
                          selection, OWNER_RGB);
                    List<BoardScene.Waypoint> path = new ArrayList<>();
                    for (Coords hex : List.of(start, middle, destination)) {
                        path.add(new BoardScene.Waypoint(hex, board.tile(hex).elevation() + ALTITUDE_LEVELS, 2));
                    }
                    BoardScene scene = board.withUnits(List.of(unit)).withAirspace(new BoardAirspace(List.of(
                          new HeldFlight(FIGHTER, 1, path, true, EntityMovementType.MOVE_FLYING, 0, 6))));
                    var shown = new ModelInstance(model.instance.model);
                    model.place(shown, renderer.camera, BoardGeometry.center(destination, elevation), 120, unit);

                    flightModels.update(scene, Map.of(FIGHTER + ":-1", shown));
                    ModelInstance waiting = flightModels.drawn(shown);
                    assertNotSame(shown, waiting, "the held fighter is drawn from a copy at its start");

                    Vector3 origin = BoardGeometry.center(middle, elevation);
                    for (boolean top : List.of(false, true)) {
                        renderer.topView = top;
                        Pixmap image = frame(renderer, flightModels, paths, scene, waiting, origin,
                              top ? "top" : "iso");
                        try {
                            assertTrue(drawn(image, renderer, UnitBounds.world(waiting).getCenter(new Vector3())),
                                  "the fighter's model at the start of its move");
                            assertTrue(drawn(image, renderer, UnitBounds.world(shown).getCenter(new Vector3())),
                                  "the faded copy at the destination");
                        } finally {
                            image.dispose();
                        }
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    paths.dispose();
                    flightModels.dispose();
                    library.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError("Held flight review failed", failure.get());
        }
    }

    /** The review renderer's frame with the held flight's two extra passes: the shadow and the path line. */
    private static Pixmap frame(GpuPlaybackReview.ReviewRenderer renderer, GpuFlightModels flightModels,
          ShapeRenderer paths, BoardScene scene, ModelInstance waiting, Vector3 origin, String name) {
        renderer.begin();
        var camera = renderer.camera;
        camera.position.set(origin);
        if (renderer.topView) {
            camera.position.add(0, 0, 260);
        } else {
            camera.position.add(renderer.viewOffset);
        }
        camera.up.set(renderer.topView ? Vector3.Y : Vector3.Z);
        camera.lookAt(origin.x, origin.y, origin.z);
        camera.update();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        renderer.batch.begin(camera);
        renderer.batch.render(waiting, renderer.light);
        renderer.batch.end();
        // As on the board, the overlay's marks and the shadows go over the displayed scene, in display colour.
        renderer.output.display();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDepthMask(false);
        paths.setProjectionMatrix(camera.combined);
        paths.begin(ShapeRenderer.ShapeType.Filled);
        GpuFlightPaths.draw(scene, 1, (first, second, third, colour) -> {
            ImmediateModeRenderer immediate = paths.getRenderer();
            immediate.color(colour);
            immediate.vertex(first.x, first.y, first.z);
            immediate.color(colour);
            immediate.vertex(second.x, second.y, second.z);
            immediate.color(colour);
            immediate.vertex(third.x, third.y, third.z);
        });
        paths.end();
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        flightModels.renderShadows(camera);
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "held-flight");
        assertTrue(output.isDirectory() || output.mkdirs());
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, 640, 480);
        PixmapIO.writePNG(new FileHandle(new File(output, name + ".png")), pixels, -1, true);
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        renderer.output.pixels().end();
        return pixels;
    }

    /** Whether anything but the review's background was drawn near this board point. */
    private static boolean drawn(Pixmap image, GpuPlaybackReview.ReviewRenderer renderer, Vector3 point) {
        Vector3 screen = renderer.camera.project(point.cpy(), 0, 0, 640, 480);
        int centreX = Math.round(screen.x);
        // A framebuffer readback keeps GL's bottom-up rows; only the written PNG is flipped.
        int centreY = Math.round(screen.y);
        for (int dx = -12; dx <= 12; dx += 3) {
            for (int dy = -12; dy <= 12; dy += 3) {
                int x = centreX + dx;
                int y = centreY + dy;
                if ((x < 0) || (y < 0) || (x >= 640) || (y >= 480)) {
                    continue;
                }
                int rgba = image.getPixel(x, y);
                int red = (rgba >>> 24) & 0xff;
                int green = (rgba >>> 16) & 0xff;
                int blue = (rgba >>> 8) & 0xff;
                if ((Math.abs(red - 38) > 20) || (Math.abs(green - 48) > 20) || (Math.abs(blue - 59) > 20)) {
                    return true;
                }
            }
        }
        return false;
    }
}
