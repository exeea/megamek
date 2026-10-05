/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real GL checks for the shared range-band elevation connectors. */
@Tag("on-demand")
class GpuFlatPlanesSmokeTest {
    @Test
    void rangeBandsJoinAcrossElevationSteps() {
        onGlThread(GpuFlatPlanesSmokeTest::checkConnectors);
    }

    private static void onGlThread(Runnable check) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(960, 640);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { check.run(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Flat annotation planes", failure.get()); }
    }

    private static void checkConnectors() {
        Coords high = new Coords(2, 2);
        BoardScene ground = BoardPlaneConnectionsTest.scene(high, 2);
        var markings = BoardPlaneConnectionsTest.range(Set.of(high, high.translated(3), high.translated(4)),
              false, java.awt.Color.CYAN);
        BoardScene scene = new BoardScene(0, ground.width(), ground.height(), ground.tiles(), List.of(), List.of(), -1,
              "", List.of(), null, List.of(), List.of(), List.of(), markings);
        GpuTerrain terrain = new GpuTerrain();
        GpuAtmosphere atmosphere = new GpuAtmosphere();
        GpuTactical tactical = new GpuTactical();
        BoardCamera camera = camera(scene);
        camera.zoom(.6f);
        try {
            terrain.update(scene);
            tactical.update(scene);
            atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 1.5f, 0, 0));
            terrain.setAtmosphere(atmosphere.lighting());
            int changed = 0;
            for (int angle = 0; angle < 4; angle++) {
                camera.orbit(90, 0);
                ScreenUtils.clear(0, 0, 0, 1, true);
                atmosphere.begin(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0);
                terrain.render(camera.camera, false);
                atmosphere.end(camera.camera, terrain, scene, 0);
                Pixmap before = pixels();
                tactical.render(camera.camera, 0);
                Pixmap after = pixels();
                try {
                    for (var triangle : BoardPlaneConnectionsTest.connections(scene, markings.fills())) {
                        Vector3 middle = new Vector3(triangle.a()).add(triangle.b()).add(triangle.c()).scl(1f / 3);
                        camera.camera.project(middle);
                        if (sample(before, middle) != sample(after, middle)) { changed++; }
                    }
                    capture("range-plane-connectors-" + angle);
                } finally { before.dispose(); after.dispose(); }
            }
            assertTrue(changed > 4, "Vertical connector interiors must reach the final image");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            tactical.dispose();
            atmosphere.dispose();
            terrain.dispose();
        }
    }

    private static BoardCamera camera(BoardScene scene) {
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.fit(scene);
        return camera;
    }

    private static Pixmap pixels() {
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static int sample(Pixmap pixels, Vector3 point) {
        int x = Math.round(point.x * pixels.getWidth() / Gdx.graphics.getWidth());
        int y = Math.round(point.y * pixels.getHeight() / Gdx.graphics.getHeight());
        assertTrue(x >= 0 && y >= 0 && x < pixels.getWidth() && y < pixels.getHeight());
        return pixels.getPixel(x, y);
    }

    private static void capture(String name) {
        GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), name + ".png"));
    }
}
