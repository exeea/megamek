/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.Mek;

/** Shared integration fixture with the shipped board and unit artwork; no classic renderer is constructed. */
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
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        return board;
    }

    private GpuBoardFixture(Board board) throws Exception {
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

    /** A classic Swing viewport over the shared state, only for tests that exercise both renderers together. */
    BoardView classicView() {
        try {
            return new BoardView(view, null, null);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
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
}
