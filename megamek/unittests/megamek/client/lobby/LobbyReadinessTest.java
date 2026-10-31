/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import megamek.client.lobby.LobbyReadiness.Kind;
import megamek.client.lobby.LobbyReadiness.Side;
import megamek.client.lobby.LobbyReadiness.Target;
import megamek.client.lobby.LobbyReadiness.Warning;
import megamek.client.lobby.LobbySnapshot.Relation;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LobbyReadinessTest {

    private Game game;
    private GameOptions options;
    private Player local;
    private Player bot;
    private Player enemy;
    private Player loner;
    private Player watcher;
    private MapSettings map;

    @BeforeEach
    void setUp() {
        game = mock(Game.class);
        options = mock(GameOptions.class);
        when(game.getOptions()).thenReturn(options);
        local = player(1, "Local", 2, 1000);
        bot = player(2, "Bot", 2, 500);
        enemy = player(3, "Enemy", 1, 3000);
        loner = player(4, "Loner", Player.TEAM_NONE, 700);
        watcher = player(5, "Watcher", 3, 0);
        watcher.setObserver(true);
        when(game.getPlayersList()).thenReturn(List.of(enemy, loner, local, watcher, bot));
        for (Player player : List.of(local, bot, enemy, loner)) {
            units(player, 1);
        }
        map = mock(MapSettings.class);
        when(map.getBoardsSelectedVector()).thenReturn(List.of("a"));
        when(map.getBoardWidth()).thenReturn(16);
        when(map.getMapWidth()).thenReturn(2);
    }

    private static Player player(int id, String name, int team, int bv) {
        Player player = spy(new Player(id, name));
        player.setTeam(team);
        doReturn(bv).when(player).getBV();
        return player;
    }

    private void units(Player player, int count) {
        ArrayList<Entity> entities = new ArrayList<>();
        IntStream.range(0, count).forEach(i -> entities.add(mock(Entity.class)));
        when(game.getPlayerEntities(player, false)).thenReturn(entities);
    }

    private LobbyReadiness readiness() {
        return readiness(LobbyForces.EMPTY);
    }

    private LobbyReadiness readiness(LobbyForces forces) {
        return LobbyReadiness.of(game, local, List.of(bot), forces, map);
    }

    private static LobbyUnit unit(int id, int owner, String name, LobbyUnit.Tone nameTone, LobbyUnit.Tag... tags) {
        return new LobbyUnit(id, owner, name, "", "", 0, 0, "", "", "", Arrays.asList(tags), 0, LobbyUnit.Link.NONE,
              false, nameTone, Force.NO_FORCE, true, false, null);
    }

    private static LobbyForces forces(LobbyUnit... units) {
        return new LobbyForces(List.of(units), List.of(), List.of(), List.of(), List.of(), -1);
    }

    @Test
    void listsTheOwnSideFirstThenTheOtherTeamsThenTheLonersWithoutObservers() {
        assertEquals(List.of(new Side(LobbySnapshot.teamName(2), Relation.OWN, false, 1500),
              new Side(LobbySnapshot.teamName(1), Relation.ENEMY, false, 3000),
              new Side("Loner", Relation.NONE, false, 700)), readiness().sides());
    }

    @Test
    void aLocalPlayerOnNoTeamIsTheirOwnSide() {
        local.setTeam(Player.TEAM_NONE);

        List<Side> sides = readiness().sides();

        assertEquals(new Side("Local", Relation.OWN, false, 1000), sides.get(0));
        assertEquals(new Side("Loner", Relation.NONE, false, 700), sides.get(sides.size() - 1));
    }

    @Test
    void aPlainBlindDropShowsEverySidesBattleValue() {
        when(options.booleanOption(OptionsConstants.BASE_BLIND_DROP)).thenReturn(true);

        assertTrue(readiness().sides().stream().noneMatch(Side::hidden));
    }

    @Test
    void aRealBlindDropHidesTheOtherSidesButNotFromTheGameMaster() {
        when(options.booleanOption(OptionsConstants.BASE_REAL_BLIND_DROP)).thenReturn(true);

        assertEquals(List.of(new Side(LobbySnapshot.teamName(2), Relation.OWN, false, 1500),
              new Side(LobbySnapshot.teamName(1), Relation.ENEMY, true, 0),
              new Side("Loner", Relation.NONE, true, 0)), readiness().sides());

        local.setGameMaster(true);
        assertTrue(readiness().sides().stream().noneMatch(Side::hidden));
    }

    @Test
    void noWarningsWhenAllIsWell() {
        LobbyReadiness readiness = readiness();

        assertTrue(readiness.warnings().isEmpty());
        assertFalse(readiness.blocked());
    }

    @Test
    void overlappingDeploymentBlocksAndNamesThePlayers() {
        when(options.booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND)).thenReturn(true);
        when(options.booleanOption(OptionsConstants.BASE_EXCLUSIVE_DB_DEPLOYMENT)).thenReturn(true);
        local.setStartingPos(1);
        enemy.setStartingPos(1);
        bot.setStartingPos(3);
        loner.setStartingPos(5);
        watcher.setStartingPos(7);

        LobbyReadiness readiness = readiness();
        Warning warning = readiness.warnings().get(0);

        assertTrue(readiness.blocked());
        assertEquals(Kind.OVERLAPPING_DEPLOYMENT, warning.kind());
        assertEquals(Target.PLAYER, warning.kind().target());
        assertEquals(List.of(3, 1), warning.players());
        assertEquals(Messages.getString("Lobby.warning.zones.many", 2), warning.text());
        assertEquals(Messages.getString("Lobby.warning.zones.tooltip", "Enemy, Local"), warning.detail());
    }

    @Test
    void localForcesWithoutCommanderBlock() {
        when(options.booleanOption(OptionsConstants.VICTORY_COMMANDER_KILLED)).thenReturn(true);
        when(game.getEntitiesOwnedBy(local)).thenReturn(1);
        when(game.getEntitiesOwnedBy(bot)).thenReturn(1);
        when(game.getLiveCommandersOwnedBy(bot)).thenReturn(1);
        when(game.getEntitiesOwnedBy(enemy)).thenReturn(1);

        Warning warning = readiness().warnings().get(0);

        assertTrue(warning.blocking());
        assertEquals(Kind.NO_COMMANDER, warning.kind());
        assertEquals(List.of(1), warning.players());
        assertEquals(Messages.getString("Lobby.warning.commander", "Local"), warning.text());
    }

    @Test
    void theBlockingWarningsAreTheReadyCheckBlockers() {
        when(options.booleanOption(OptionsConstants.VICTORY_COMMANDER_KILLED)).thenReturn(true);
        when(game.getEntitiesOwnedBy(bot)).thenReturn(1);

        List<Kind> blocking = readiness().warnings().stream().filter(Warning::blocking).map(Warning::kind).toList();

        assertEquals(List.of(Kind.NO_COMMANDER), blocking);
        assertEquals(List.of(ReadyCheck.Reason.NO_COMMANDER), ReadyCheck.blockers(game, local, List.of(bot)).stream()
              .map(ReadyCheck.Blocker::reason).toList());
    }

    @Test
    void warnsOfLocalForcesWithoutUnitsButNotOfOthers() {
        units(bot, 0);
        units(enemy, 0);

        Warning warning = readiness().warnings().get(0);

        assertFalse(warning.blocking());
        assertEquals(Kind.NO_UNITS, warning.kind());
        assertEquals(List.of(2), warning.players());
        assertEquals(Messages.getString("Lobby.warning.noUnits", "Bot"), warning.text());

        local.setGameMaster(true);
        units(local, 0);
        assertEquals(List.of(2), readiness().warnings().get(0).players());
    }

    @Test
    void warnsOfLocalUnitsWithProblemsAndOfThoseNeedingSetup() {
        LobbyUnit.Tag invalid = new LobbyUnit.Tag("Invalid", LobbyUnit.Tone.DANGER);
        LobbyUnit.Tag unlinked = new LobbyUnit.Tag("Unlinked", LobbyUnit.Tone.WARNING);
        LobbyUnit.Tag heat = new LobbyUnit.Tag("Heat", LobbyUnit.Tone.WARNING);
        LobbyForces forces = forces(unit(10, 1, "Atlas", LobbyUnit.Tone.DANGER, invalid, unlinked),
              unit(11, 2, "Locust", LobbyUnit.Tone.WARNING, unlinked),
              unit(12, 1, "Hot", null, heat),
              unit(13, 3, "Foreign", LobbyUnit.Tone.DANGER, invalid));

        List<Warning> warnings = readiness(forces).warnings();

        assertEquals(List.of(Kind.UNIT_PROBLEMS, Kind.UNITS_NEED_SETUP), warnings.stream().map(Warning::kind).toList());
        assertEquals(List.of(10), warnings.get(0).units());
        assertEquals(Target.UNIT, warnings.get(0).kind().target());
        assertEquals(Messages.getString("Lobby.warning.unitProblems.tooltip", "Atlas (Invalid)"),
              warnings.get(0).detail());
        assertEquals(List.of(11), warnings.get(1).units());
        assertEquals(Messages.getString("Lobby.warning.unitSetup", "Locust"), warnings.get(1).text());
    }

    @Test
    void warnsOfAnIncompleteMapAndOfBoardsThatDoNotTile() {
        when(map.getBoardsSelectedVector()).thenReturn(Arrays.asList("a", null));
        when(map.getBoardWidth()).thenReturn(15);

        List<Warning> warnings = readiness().warnings();

        assertEquals(List.of(Kind.MAP_INCOMPLETE, Kind.MAP_NOT_TILEABLE), warnings.stream().map(Warning::kind)
              .toList());
        assertEquals(Target.MAP, warnings.get(0).kind().target());

        when(map.getMapWidth()).thenReturn(1);
        assertEquals(List.of(Kind.MAP_INCOMPLETE), readiness().warnings().stream().map(Warning::kind).toList());
    }

    @Test
    void foldsLongNameListsIntoACount() {
        List<String> names = IntStream.range(0, LobbyReadiness.MAX_NAMES + 2).mapToObj(i -> "P" + i).toList();

        String joined = LobbyReadiness.names(names);

        assertTrue(joined.startsWith("P0, P1"));
        assertTrue(joined.endsWith(Messages.getString("Lobby.warning.more", 2)));
        assertFalse(joined.contains("P" + LobbyReadiness.MAX_NAMES));
    }

    @Test
    void nothingBeforeTheLocalPlayerIsKnown() {
        assertEquals(LobbyReadiness.EMPTY, LobbyReadiness.of(game, null, List.of(), LobbyForces.EMPTY, map));
    }
}
