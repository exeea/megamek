/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.awt.Color;
import java.awt.event.InputEvent;
import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.common.Configuration;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.utils.BoardLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Line of sight stays MegaMek's Swing ruler (the user's decision of 2026-10-03): the menu's line of sight from an own
 * unit measures with it, and a measurement waiting for its second point is published for the plain second click.
 */
@Timeout(120)
class GpuLosResultTest {
    private static final Coords OWN = new Coords(5, 5);
    private static final Coords ENEMY = new Coords(5, 3);
    private static File originalDataDir;

    /** Earlier test classes can leave the data folder on testresources; the fixture needs the staged data. */
    @BeforeAll
    static void useStagedData() {
        originalDataDir = Configuration.dataDir();
        Configuration.setDataDir(null);
    }

    @AfterAll
    static void restoreDataDir() {
        Configuration.setDataDir(originalDataDir);
    }

    /** A clear 8 x 8 board with the fixture's own Atlas at 0606 and an enemy Atlas at 0604. */
    private static GpuBoardFixture battle() throws Exception {
        StringBuilder data = new StringBuilder("size 8 8\n");
        for (int y = 1; y <= 8; y++) {
            for (int x = 1; x <= 8; x++) {
                data.append(String.format("hex %02d%02d 0 \"\" \"\"%n", x, y));
            }
        }
        GpuBoardFixture fixture = GpuBoardFixture.create(BoardLoader.initializeBoard(data.append("end").toString()));
        SwingUtilities.invokeAndWait(() -> {
            Player enemy = new Player(1, "Opponent");
            enemy.setTeam(2);
            fixture.game.addPlayer(enemy.getId(), enemy);
            Entity atlas = unit("Atlas AS7-D.mtf", 2, enemy);
            atlas.setPosition(ENEMY);
            fixture.game.addEntity(atlas, false);
        });
        return fixture;
    }

    /**
     * The menu's line of sight from the own Atlas to a hex is the ruler's measurement between them, its target at the
     * height the pointer showed when one did; none into the unit's own hex, from an enemy or off the board.
     */
    @Test
    void theMenusLineOfSightMeasuresWithTheRulerFromAnOwnUnit() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        try (GpuBoardFixture fixture = battle()) {
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            GpuBoardSource source = onSwing(() -> new GpuBoardSource(view, () -> fixture.panel));
            try {
                GpuLosResult los = source.los();
                los.lineOfSight(1, ENEMY, Float.NaN);
                los.lineOfSight(1, OWN, Float.NaN);
                los.lineOfSight(2, OWN, Float.NaN);
                los.lineOfSight(1, new Coords(20, 20), Float.NaN);
                SwingUtilities.invokeAndWait(() -> { });
                verify(gui).measureLineOfSight(view.getBoardId(), OWN, ENEMY);
                verify(gui, times(1)).measureLineOfSight(anyInt(), any(), any());
                verify(gui, never()).setRulerHeight(anyInt(), any(), anyInt());
                // A floor two levels up, where the pointer hit a building.
                Coords floor = new Coords(5, 1);
                los.lineOfSight(1, floor, 2 * BoardGeometry.level() + .1f);
                SwingUtilities.invokeAndWait(() -> { });
                verify(gui).measureLineOfSight(view.getBoardId(), OWN, floor);
                verify(gui).setRulerHeight(view.getBoardId(), floor, 2);
            } finally {
                SwingUtilities.invokeAndWait(source::close);
            }
        }
    }

    /**
     * A measurement waiting for its second point is published with its modifier, Ctrl for MegaMek's line of sight and
     * Alt for its ruler, so that a plain left click can end it (rimshaderv1's board, GpuHud.boardClick).
     */
    @Test
    void aMeasurementWaitingForItsSecondPointIsPublishedWithItsModifier() throws Exception {
        try (GpuBoardFixture fixture = battle()) {
            assertEquals(0, pending(fixture));
            SwingUtilities.invokeAndWait(() -> fixture.view.checkLOS(OWN));
            assertEquals(InputEvent.CTRL_DOWN_MASK, pending(fixture), "A line of sight from its first point");
            SwingUtilities.invokeAndWait(() -> fixture.view.checkLOS(ENEMY));
            assertEquals(0, pending(fixture), "Its second point ends it");
            ruler(fixture, OWN, null);
            assertEquals(InputEvent.ALT_DOWN_MASK, pending(fixture), "A ruler with its start alone");
            ruler(fixture, OWN, ENEMY);
            assertEquals(0, pending(fixture), "A complete ruler waits for nothing");
        }
    }

    /** Draws MegaMek's ruler as the Swing ruler dialog does after a Ctrl or Alt click. */
    private static void ruler(GpuBoardFixture fixture, Coords start, Coords end) throws Exception {
        SwingUtilities.invokeAndWait(() -> fixture.view.drawRuler(start, end, Color.CYAN, Color.PINK));
    }

    private static Entity unit(String file, int id, Player owner) {
        try {
            Entity unit = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
            unit.setId(id);
            unit.setOwner(owner);
            unit.setDeployed(true);
            return unit;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** The published modifier of the measurement waiting for its second point, after a capture. */
    private static int pending(GpuBoardFixture fixture) throws Exception {
        SwingUtilities.invokeAndWait(fixture.source::refresh);
        return fixture.source.takeFrame().panels().los().pending();
    }

    private static <T> T onSwing(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
