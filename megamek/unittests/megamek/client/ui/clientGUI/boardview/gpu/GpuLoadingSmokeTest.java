/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Checks the worker-to-screen boundary in the real loading pipeline, including a superseded build. */
@Tag("on-demand")
class GpuLoadingSmokeTest {
    @Test
    void loadingShowsConcurrentTasksAndClearsSupersededProgress() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(900, 600);
        var terrainField = GpuBattleView.class.getDeclaredField("terrain");
        var loadingField = GpuBattleView.class.getDeclaredField("loadingStage");
        terrainField.setAccessible(true);
        loadingField.setAccessible(true);
        try (var fixture = GpuBoardFixture.create()) {
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                final Map<String, Integer> completions = new HashMap<>();
                final Set<String> tasks = new HashSet<>();
                boolean restarted, concurrent, partial, captured;

                @Override
                public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Terrain loading must finish");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        GpuTerrain terrain = (GpuTerrain) terrainField.get(this);
                        Stage loading = (Stage) loadingField.get(this);
                        if (loading != null) {
                            var statuses = terrain.buildDetails();
                            concurrent |= statuses.size() > 1;
                            int previousSection = 0;
                            for (var status : statuses) {
                                var step = status.step();
                                assertTrue(step.completed() >= 0 && step.completed() <= step.total());
                                assertTrue(step.percent() >= 0 && step.percent() <= 100);
                                String key = status.section() + ":" + step.task() + ":" + step.started();
                                assertTrue(step.completed() >= completions.getOrDefault(key, 0));
                                completions.put(key, step.completed());
                                tasks.add(step.task());
                                partial |= step.completed() > 0 && step.completed() < step.total();
                                if (status.section() > 0) {
                                    assertTrue(status.section() > previousSection && status.section() <= status.sections());
                                    previousSection = status.section();
                                }
                            }
                            Label details = loading.getRoot().findActor("board-loading-details");
                            Label overall = loading.getRoot().findActor("board-loading-message");
                            assertTrue(overall.getText().toString().contains("%"));
                            assertFalse(details.getText().toString().contains("GpuBoard.loading"));
                            Group content = (Group) loading.getRoot().getChildren().first();
                            assertEquals(loading.getWidth(), content.getWidth(), .1f);
                            GpuBoardTestUi.assertHorizontalBounds(content, content);
                            assertTrue(details.getY() >= 0 && details.getTop() <= loading.getHeight());
                            if (statuses.size() > 1 && !details.getText().isEmpty() && !captured) {
                                assertTrue(details.getText().toString().contains("%"));
                                File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                                assertTrue(output.isDirectory() || output.mkdirs());
                                GpuReviewFrame.save(new File(output, "loading-progress.png"));
                                captured = true;
                            }
                            if (statuses.size() > 1 && !restarted) {
                                terrain.boardChanged();
                                assertTrue(terrain.buildDetails().isEmpty(), "Cancelled workers cannot remain on screen");
                                restarted = true;
                            }
                        } else if (frames() > 0 && !terrain.busy()) {
                            assertTrue(restarted && concurrent && partial && captured);
                            assertTrue(tasks.contains("surfaces") || tasks.contains("topography"), tasks.toString());
                            assertTrue(terrain.buildDetails().isEmpty());
                            assertEquals(-1, terrain.buildProgress());
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        }
        if (failure.get() != null) { throw new AssertionError("Loading progress review failed", failure.get()); }
    }
}
