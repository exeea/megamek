/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.Coords;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.EntityMovementType;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The actual shared board, its input bridge and movement timeline with an imported and a native chassis. */
@Tag("on-demand")
class GpuHbsBoardSmokeTest {
    @Test
    void playsTheSameBoardWithMixedModelSources() throws Exception {
        Path cache = Path.of(System.getProperty(HbsUnitCatalog.CACHE_PROPERTY, "../.work/hbs-cache"));
        assumeTrue(Files.isRegularFile(cache.resolve("catalog.json")), "Import local HBS assets first");
        assumeTrue(new HbsUnitCatalog(cache).descriptor("Bushwacker") != null);
        String enabled = System.getProperty(HbsUnitCatalog.ENABLED_PROPERTY);
        System.setProperty(HbsUnitCatalog.ENABLED_PROPERTY, "true");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Path output = Path.of(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        Files.createDirectories(output);
        try (var fixture = GpuBoardFixture.create()) {
            var bushwacker = new MekFileParser(new File("data/mekfiles/unit_files.zip"),
                  "meks/3058Uu/Bushwacker BSW-X1.mtf").getEntity();
            SwingUtilities.invokeAndWait(() -> {
                bushwacker.setId(2);
                bushwacker.setOwner(fixture.player);
                bushwacker.setPosition(new Coords(6, 5));
                bushwacker.setDeployed(true);
                fixture.game.addEntity(bushwacker, false);
                fixture.source.refresh();
            });
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private long isometric;

                @Override
                public void render() {
                    try {
                        super.render();
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (frames() == 70) {
                            var scene = fixture.source.takeFrame().scene();
                            assertTrue(scene.units().stream().anyMatch(unit -> unit.model() != null
                                  && "Bushwacker".equals(unit.model().chassis())));
                            boardCamera.center(BoardGeometry.center(new Coords(6, 5), 0));
                            boardCamera.zoom(.15f);
                        } else if (frames() == 85) {
                            isometric = GpuBoardTestUi.capture(output.resolve("hbs-board-isometric.png").toFile());
                            GpuBoardTestUi.click("all-actions");
                        } else if (frames() == 90) {
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.DOWN);
                            Gdx.input.getInputProcessor().keyUp(Input.Keys.DOWN);
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.ENTER);
                            Gdx.input.getInputProcessor().keyUp(Input.Keys.ENTER);
                            SwingUtilities.invokeAndWait(() -> { });
                            assertEquals(1, fixture.clicks.get(), "Native controls must still invoke the existing game command");
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE);
                            var path = new Vector<UnitLocation>();
                            path.add(new UnitLocation(2, new Coords(6, 5), 0, 0, 0));
                            path.add(new UnitLocation(2, new Coords(7, 5), 1, 0, 0));
                            SwingUtilities.invokeAndWait(() -> {
                                bushwacker.moved = EntityMovementType.MOVE_WALK;
                                bushwacker.setPosition(new Coords(7, 5));
                                bushwacker.setFacing(1);
                                bushwacker.setSecondaryFacing(1);
                                fixture.game.fireGameEvent(new GameEntityChangeEvent(fixture.game, bushwacker, path));
                            });
                        } else if (frames() == 96) {
                            assertTrue(isMoving(), "Imported models must use the existing movement timeline");
                            GpuBoardTestUi.capture(output.resolve("hbs-board-moving.png").toFile());
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.SPACE);
                            Gdx.input.getInputProcessor().keyUp(Input.Keys.SPACE);
                            assertEquals(new Coords(7, 5), bushwacker.getPosition());
                            boardCamera.setIsometric(false);
                        } else if (frames() == 110) {
                            assertNotEquals(isometric, GpuBoardTestUi.capture(output.resolve("hbs-board-top.png").toFile()));
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        } finally {
            if (enabled == null) { System.clearProperty(HbsUnitCatalog.ENABLED_PROPERTY); }
            else { System.setProperty(HbsUnitCatalog.ENABLED_PROPERTY, enabled); }
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }
}
