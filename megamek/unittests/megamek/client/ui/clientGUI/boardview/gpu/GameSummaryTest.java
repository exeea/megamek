/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.ui.clientGUI.UnitOwnerColors;
import megamek.common.MMRandom;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;
import megamek.server.Server;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

/**
 * The summary records what a turn did from the client's own game, as a real server tells it: at the end of a firing
 * phase both units on their hexes with their facings and owners' colours, and the local unit's attack as one line
 * from it to its target's hex, however many weapons fired.
 */
class GameSummaryTest {
    private static final int LOCAL = 0, OPPONENT = 1;
    private static final int SHOOTER = 1, TARGET = 2;

    @Test
    void theEndOfAFiringPhaseRecordsTheUnitsAndTheAttack() throws Exception {
        Compute.setRNG(new MMRandom() {
            private final java.util.Random others = new java.util.Random(7);
            @Override public int randomInt(int maxValue) { return maxValue == 6 ? 3 : others.nextInt(maxValue); }
            @Override public float randomFloat() { return .5f; }
        });
        TWGameManager manager = mock(TWGameManager.class, withSettings().useConstructor()
              .defaultAnswer(Answers.CALLS_REAL_METHODS));
        doNothing().when(manager).autoSave();
        Game game = manager.getGame();
        game.getOptions().getOption(OptionsConstants.BASE_MAX_NUMBER_ROUND_SAVES).setValue(0);
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        game.setBoard(board);
        Client client = new Client("Irregulars", "localhost", 0);
        client.setLocalPlayerNumber(LOCAL);
        for (Player player : List.of(new Player(LOCAL, "Irregulars"), new Player(OPPONENT, "Princess"))) {
            player.setTeam(player.getId() + 1);
            game.addPlayer(player.getId(), player);
            client.getGame().addPlayer(player.getId(), player.copy());
        }
        Entity shooter = add(game, LOCAL, SHOOTER, new Coords(5, 10), 0);
        Entity target = add(game, OPPONENT, TARGET, new Coords(5, 6), 3);
        game.setupTeams();
        game.createVictoryConditions();
        game.setCurrentRound(1);
        game.addReports(new ArrayList<>(List.of(new Report(2000))));
        game.setPhase(GamePhase.FIRING);
        // Two weapons at one target: one line in the summary
        for (int weapon = 0; weapon < 2; weapon++) {
            game.addAction(new WeaponAttackAction(SHOOTER, Targetable.TYPE_ENTITY, TARGET,
                  shooter.getEquipmentNum(shooter.getWeaponList().get(weapon))));
        }
        shooter.setDone(true);
        target.setDone(true);

        GameSummary summary = new GameSummary();
        client.getGame().addGameListener(new GameListenerAdapter() {
            @Override
            public void gamePhaseChange(GamePhaseChangeEvent event) {
                summary.phaseEnded(client.getGame(), client.getLocalPlayer(), event.getOldPhase());
            }
        });
        Method handle = AbstractClient.class.getDeclaredMethod("handlePacket", Packet.class);
        handle.setAccessible(true);
        Server server = mock(Server.class, invocation -> {
            Object[] arguments = invocation.getArguments();
            if (invocation.getMethod().getName().equals("getGame")) { return game; }
            if (invocation.getMethod().getName().equals("send") && (arguments.length == 1 || arguments[0].equals(LOCAL))) {
                Packet received = received((Packet) arguments[arguments.length - 1]);
                SwingUtilities.invokeAndWait(() -> {
                    try { handle.invoke(client, received); } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        try (var servers = mockStatic(Server.class)) {
            servers.when(Server::getServerInstance).thenReturn(server);
            manager.sendCurrentInfo(LOCAL);
            Method endPhase = TWGameManager.class.getDeclaredMethod("endCurrentPhase");
            endPhase.setAccessible(true);
            endPhase.invoke(manager);
            SwingUtilities.invokeAndWait(() -> { });
        } finally {
            Compute.setRNG(MMRandom.R_DEFAULT);
        }

        List<GameSummary.Frame> frames = summary.frames();
        GameSummary.Frame firing = frames.stream().filter(frame -> frame.phase().isFiring()).findFirst().orElseThrow();
        assertEquals(1, firing.round());
        GameSummary.Unit own = unit(firing, SHOOTER);
        GameSummary.Unit enemy = unit(firing, TARGET);
        assertEquals(List.of(5, 10, 0), List.of(own.x(), own.y(), own.facing()));
        assertEquals(List.of(5, 6, 3), List.of(enemy.x(), enemy.y(), enemy.facing()));
        Player local = client.getLocalPlayer();
        assertEquals(UnitOwnerColors.colorFor(local, local, client.getGame()).getRGB(), own.colour());
        assertFalse(own.destroyed());
        assertEquals(List.of(new GameSummary.Attack(SHOOTER, 5, 6, false)), firing.attacks(),
              "the volley of two weapons is one line to the target's hex");
        assertTrue(frames.stream().noneMatch(frame -> frame.phase().isFiringReport()),
              "a report phase records nothing");
    }

    private static GameSummary.Unit unit(GameSummary.Frame frame, int id) {
        return frame.units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
    }

    /** The packet as the client receives it: a copy, never the server's own objects. */
    private static Packet received(Packet packet) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) { out.writeObject(packet); }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Packet) in.readObject(); }
    }

    private static Entity add(Game game, int owner, int id, Coords position, int facing) throws Exception {
        Entity entity = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        entity.setId(id);
        entity.setOwner(game.getPlayer(owner));
        entity.setPosition(position);
        entity.setFacing(facing);
        entity.setSecondaryFacing(facing);
        entity.setDeployed(true);
        game.addEntity(entity, false);
        return entity;
    }
}
