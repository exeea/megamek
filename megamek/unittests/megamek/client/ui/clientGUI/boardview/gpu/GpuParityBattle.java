/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import megamek.client.ui.util.PlayerColour;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.board.Coords;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.icons.Camouflage;
import megamek.common.interfaces.IEntityRemovalConditions;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;

/**
 * The hud-v3 mock's battle (shots 01-16) as real units of a board fixture's game, for GpuHudParitySmokeTest: the
 * roster of GpuHudFixtures (its pilots, skills, armor shares, heat, formations and facings) as official mm-data units
 * on the fixture's 16 x 17 board, the local player's five against Princess's five in round 3, their turns alternating
 * with the local side first (shot 01). The fixture's own Atlas is the roster's Atlas. These are display values copied
 * from the mock's pictures; every rule stays MegaMek's. The mock's Archer is a sensor contact, which MegaMek has only
 * under double blind, which also hides the turn order (plan D4): here it is an identified enemy.
 */
final class GpuParityBattle {
    static final int ATLAS = 1;
    static final int WARHAMMER = 2;
    static final int MARAUDER = 3;
    static final int PANTHER = 4;
    static final int LOCUST = 5;
    static final int TIMBER_WOLF = 6;
    static final int KING_CRAB = 7;
    static final int BATTLEMASTER = 8;
    static final int ARCHER = 9;
    static final int ENEMY_LOCUST = 10;
    static final int ROUND = 3;
    /** The official unit file of each roster unit but the fixture's own Atlas. */
    private static final Map<Integer, String> FILES = Map.of(
          WARHAMMER, "meks/3039u/Warhammer WHM-6R.mtf",
          MARAUDER, "meks/3039u/Marauder MAD-3R.mtf",
          PANTHER, "meks/3039u/Panther PNT-9R.mtf",
          LOCUST, "meks/3039u/Locust LCT-1V.mtf",
          TIMBER_WOLF, "meks/3050U/Mad Cat (Timber Wolf) Prime.mtf",
          KING_CRAB, "meks/3050U/King Crab KGC-000.mtf",
          BATTLEMASTER, "meks/3039u/BattleMaster BLR-1G.mtf",
          ARCHER, "meks/3039u/Archer ARC-2K.mtf",
          ENEMY_LOCUST, "meks/3039u/Locust LCT-1M.mtf");
    /**
     * Each unit's hex at the start of the movement phase, on clear ground of the 16 x 17 board: the local side in the
     * south, Princess's in the north, the Timber Wolf 9 hexes north of the Atlas (7 from its walk's end, shot 02).
     */
    static final Map<Integer, Coords> HEXES = Map.of(
          ATLAS, new Coords(13, 15), WARHAMMER, new Coords(15, 14), MARAUDER, new Coords(12, 16),
          PANTHER, new Coords(14, 16), LOCUST, new Coords(10, 16), TIMBER_WOLF, new Coords(13, 6),
          KING_CRAB, new Coords(12, 5), BATTLEMASTER, new Coords(15, 4), ARCHER, new Coords(8, 4),
          ENEMY_LOCUST, new Coords(15, 8));
    /** The Atlas's hex after its walk of shot 02, where it declares its attacks (shots 05-10). */
    static final Coords ATLAS_MOVED = new Coords(13, 13);

    private GpuParityBattle() { }

    /**
     * EDT: replaces every unit of the fixture's game but its Atlas with the roster, the local player's and
     * {@code enemy}'s, named Princess, added to the game when it is not in it yet; every enemy unit is seen by the
     * local player. Sets round 3 and the alternating turns of the {@code phase}'s first turn.
     */
    static void populate(GpuBoardFixture board, Player enemy) throws Exception {
        Game game = board.game;
        for (Entity entity : List.copyOf(game.getEntitiesVector())) {
            if (entity.getId() != ATLAS) {
                game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_NEVER_JOINED);
            }
        }
        enemy.setName("Princess");
        enemy.setTeam(2);
        // A player's own colour camouflage, as the lobby gives each player one; the default is blue for both.
        enemy.setColour(PlayerColour.RED);
        enemy.setCamouflage(Camouflage.of(PlayerColour.RED));
        if (game.getPlayer(enemy.getId()) == null) {
            game.addPlayer(enemy.getId(), enemy);
        }
        board.player.setColour(PlayerColour.BLUE);
        game.setRoundCount(ROUND);
        Map<String, Integer> forces = new HashMap<>();
        for (GpuHudFixtures.Roster unit : GpuHudFixtures.ROSTER) {
            boolean own = unit.side() == GpuBattleStatus.Side.OWN;
            Player owner = own ? board.player : enemy;
            Entity entity = unit.id() == ATLAS ? board.entity : GpuUnitPanelFixture.official(FILES.get(unit.id()));
            entity.setId(unit.id());
            entity.setOwner(owner);
            entity.setFacing(unit.facing());
            entity.setSecondaryFacing(unit.facing());
            entity.setDeployed(true);
            if (unit.id() != ATLAS) {
                game.addEntity(entity, false);
            }
            entity.setPosition(HEXES.get(unit.id()));
            entity.getCrew().setName(unit.pilot(), 0);
            entity.getCrew().setGunnery(unit.gunnery(), 0);
            entity.getCrew().setPiloting(unit.piloting(), 0);
            wear(entity, unit.armor());
            entity.heat = unit.heat();
            if (!own) {
                entity.addBeenSeenBy(board.player);
            }
            int force = forces.computeIfAbsent(unit.formation(), name ->
                  game.getForces().addTopLevelForce(new Force(name, -1, new Camouflage()), owner));
            game.getForces().addEntity(entity, force);
        }
        turns(game, board.player, enemy, 0);
    }

    /**
     * EDT: the movement phase as it ended before shot 05: the Atlas walked 2 hexes north, the Warhammer held its
     * position, the Marauder ran 5 hexes, the Panther and the Locust walked 3 and 8; the Timber Wolf walked 3 hexes to
     * 5 hexes ahead of the Atlas, the King Crab and the BattleMaster closed in to 7 and 6.
     */
    static void afterMovement(Game game) {
        moved(game, ATLAS, ATLAS_MOVED, EntityMovementType.MOVE_WALK, 2);
        moved(game, MARAUDER, new Coords(12, 11), EntityMovementType.MOVE_RUN, 5);
        moved(game, PANTHER, new Coords(14, 13), EntityMovementType.MOVE_WALK, 3);
        moved(game, LOCUST, new Coords(10, 8), EntityMovementType.MOVE_WALK, 8);
        moved(game, TIMBER_WOLF, new Coords(13, 8), EntityMovementType.MOVE_WALK, 3);
        moved(game, KING_CRAB, new Coords(11, 7), EntityMovementType.MOVE_WALK, 2);
        moved(game, BATTLEMASTER, new Coords(11, 8), EntityMovementType.MOVE_WALK, 4);
    }

    /** EDT: the unit moved {@code hexes} hexes to {@code hex} this round, keeping its facing. */
    static void moved(Game game, int id, Coords hex, EntityMovementType type, int hexes) {
        Entity entity = game.getEntity(id);
        entity.setPosition(hex);
        entity.moved = type;
        entity.mpUsed = hexes;
        entity.delta_distance = hexes;
    }

    /** EDT: ten turns alternating from the local player's first, the {@code index}th of them current. */
    static void turns(Game game, Player local, Player enemy, int index) {
        List<GameTurn> turns = new ArrayList<>();
        for (int turn = 0; turn < 10; turn++) {
            turns.add(new GameTurn((turn % 2 == 0 ? local : enemy).getId()));
        }
        game.setTurnVector(turns);
        game.setTurnIndex(index, Player.PLAYER_NONE);
    }

    /**
     * EDT: the report history of rounds 1 to 3, in which only round 3's initiative is reported, as the server reports
     * it to the clients (report 1015 per side, "total[roll+bonus]", TWGameManager.writeInitiativeReport): the local
     * side's 3 against Princess's 10, as in shots 01 and 16.
     */
    static void initiative(Game game, Player local, Player enemy) {
        List<Report> reports = new ArrayList<>();
        for (Player player : List.of(local, enemy)) {
            Report report = new Report(1015, Report.PUBLIC);
            report.add(player.getColorForPlayer());
            report.add(player == local ? "3[3+0]" : "10[10+0]");
            reports.add(report);
        }
        game.setAllReports(new ArrayList<>(List.of(new ArrayList<>(), new ArrayList<>(), reports)));
    }

    /** Takes armor from the arms, then from the other locations, until the unit keeps {@code share} of it. */
    private static void wear(Entity entity, double share) {
        int total = entity.getTotalOArmor();
        int[] order = { Mek.LOC_LEFT_ARM, Mek.LOC_RIGHT_ARM, Mek.LOC_LEFT_LEG, Mek.LOC_RIGHT_LEG,
                        Mek.LOC_LEFT_TORSO, Mek.LOC_RIGHT_TORSO };
        for (int location : order) {
            while (entity.getArmor(location) > 0 && entity.getTotalArmor() > Math.round(share * total)) {
                entity.setArmor(entity.getArmor(location) - 1, location);
            }
        }
    }
}
