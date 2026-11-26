/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.Mek;

/** Shared integration fixture with the shipped board and unit artwork; no Legacy 2D renderer is constructed. */
final class GpuBoardFixture implements AutoCloseable {
    final Game game = new Game();
    final Player player = new Player(0, "GPU review");
    final AtomicInteger clicks = new AtomicInteger();
    final JButton button = new JButton("Hold position");
    BoardClientState view;
    final Entity entity;
    final GpuBoardSource source;
    JComponent panel = new JPanel();

    private GpuBoardFixture() throws Exception {
        this(loadBoard());
    }

    private static Board loadBoard() {
        return BoardFile.loadSheet(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"), false, true);
    }

    private GpuBoardFixture(Board board) throws Exception {
        // Match the game load boundary for in-memory legacy fixtures as well as files; native boards retain their art.
        BoardSceneryLayouts.convert(board);
        game.setBoard(board);
        game.setPhase(GamePhase.MOVEMENT);
        player.setTeam(1);
        game.addPlayer(player.getId(), player);
        entity = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        entity.setId(1);
        entity.setOwner(player);
        entity.setPosition(new Coords(5, 5));
        entity.setDeployed(true);
        game.addEntity(entity, false);
        view = new BoardClientState(game, null, null, 0, null);
        view.setLocalPlayer(player.getId());
        button.addActionListener(event -> clicks.incrementAndGet());
        panel.add(button);
        source = new GpuBoardSource(view, () -> panel);
    }

    static GpuBoardFixture create() throws Exception {
        FutureTask<GpuBoardFixture> task = new FutureTask<>(GpuBoardFixture::new);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    static GpuBoardFixture create(Board board) throws Exception {
        FutureTask<GpuBoardFixture> task = new FutureTask<>(() -> new GpuBoardFixture(board));
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    void addEcm() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            entity.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Mek.LOC_LEFT_ARM);
            view.updateEcmList();
            return null;
        });
        SwingUtilities.invokeAndWait(task);
        task.get();
    }

    @Override
    public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            source.close();
            view.close();
        });
    }

    /** A client GUI of {@code fixture}'s game whose local player acts now. */
    static ClientGUI gui(GpuBoardFixture fixture) {
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(fixture.game);
        when(client.getLocalPlayer()).thenReturn(fixture.player);
        when(client.isMyTurn()).thenReturn(true);
        ClientGUI gui = mock(ClientGUI.class);
        when(gui.getClient()).thenReturn(client);
        when(gui.getMainPanel()).thenReturn(new JPanel());
        when(gui.getCurrentBoardState()).thenReturn(Optional.of(fixture.view));
        when(gui.getBoardState()).thenReturn(fixture.view);
        return gui;
    }

    /** EDT: {@code count} more Atlases of the fixture's player, ids from 2, in rows of four from 0708. */
    static List<Entity> addUnits(GpuBoardFixture fixture, int count) throws Exception {
        FutureTask<List<Entity>> task = new FutureTask<>(() -> {
            List<Entity> units = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                Entity unit = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf"))
                      .getEntity();
                unit.setId(index + 2);
                unit.setOwner(fixture.player);
                unit.setPosition(new Coords(7 + index % 4, 5 + index / 4));
                unit.setDeployed(true);
                fixture.game.addEntity(unit, false);
                units.add(unit);
            }
            return units;
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
