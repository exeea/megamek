/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.server.sbf;

import java.util.List;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovePathFinder;
import megamek.common.strategicBattleSystems.SBFMoveStep;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;

/** Ground withdrawal and shaken movement (IO:BF, Forced Withdrawal and Morale, pp. 172-173). */
final class SBFWithdrawal {
    private SBFWithdrawal() {
    }

    static boolean permitsMove(SBFGame game, SBFFormation formation, SBFMovePath path) {
        if (!(formation.mustWithdraw() || formation.isShaken()) || formation.isAerospace()) {
            return true;
        }
        ToIntFunction<BoardLocation> score;
        if (formation.mustWithdraw()) {
            List<Coords> exits = homeEdge(game, formation);
            if (exits.isEmpty()) {
                return true; // Report this missing scenario information in the End Phase.
            }
            score = location -> exits.stream().mapToInt(location.coords()::distance).min().orElseThrow();
        } else {
            List<SBFFormation> enemies = enemiesOnBoard(game, formation);
            score = location -> exposure(game, location, enemies);
            if (score.applyAsInt(formation.getPosition()) == 0) {
                return path.getSteps().isEmpty();
            }
        }
        SBFMovePathFinder finder = SBFMovePathFinder.moveEnvelopeFinder(
              SBFMovePath.maximumMovementPoints(formation, game), game);
        finder.run(new SBFMovePath(formation.getId(), formation.getPosition(), game));
        int best = finder.getAllComputedPaths().keySet().stream().mapToInt(score).min()
              .orElseGet(() -> score.applyAsInt(formation.getPosition()));
        return score.applyAsInt(path.getLastPosition()) <= best;
    }

    static void recordHostileEntries(SBFGame game, SBFFormation mover, SBFMovePath path) {
        Player owner = game.getPlayer(mover.getOwnerId());
        for (SBFMoveStep step : path.getSteps()) {
            game.getActiveFormationsAt(step.getDestination()).stream()
                  .filter(other -> game.areHostile(other, owner)).forEach(SBFFormation::recordHostileEntry);
        }
    }

    static boolean withdrawIfAtHomeEdge(SBFGameManager manager, SBFFormation formation) {
        SBFGame game = manager.getGame();
        if (!formation.mustWithdraw() || !atHomeEdge(game, formation)) {
            return false;
        }
        leaveMap(manager, formation, true);
        return true;
    }

    static void leaveMap(SBFGameManager manager, SBFFormation carrier, boolean forced) {
        SBFGame game = manager.getGame();
        manager.addReportFor(new SBFReportEntry(forced ? 3404 : 3518).add(carrier.generalName()), carrier);
        Stream.concat(game.passengersOf(carrier).stream(), Stream.of(carrier)).forEach(formation -> {
            LossCause cause = forced ? LossCause.FORCED_WITHDRAWAL : LossCause.VOLUNTARY_WITHDRAWAL;
            formation.getUnits().forEach(u -> game.scenarioProgress().recordLoss(game.getCurrentRound(), formation, u, Player.PLAYER_NONE, cause));
            formation.markWithdrawn();
            game.removeFormationFromPlay(formation);
        });
        if (game.getPhase().isMovement()) {
            manager.entityAllUpdate();
        }
    }

    static boolean atHomeEdge(SBFGame game, SBFFormation formation) {
        return homeEdge(game, formation).contains(formation.getPosition().coords());
    }

    static List<Coords> homeEdge(SBFGame game, SBFFormation formation) {
        Player player = game.getPlayer(formation.getOwnerId());
        Board board = game.getBoard(formation.getPosition().boardId());
        // Only a cardinal deployment specifies a single home edge. Honor scenario flee-area restrictions on that edge.
        var edge = switch (player.getStartingPos()) {
            case Board.START_N, Board.START_S -> IntStream.range(0, board.getWidth())
                  .mapToObj(x -> new Coords(x, player.getStartingPos() == Board.START_N ? 0 : board.getHeight() - 1));
            case Board.START_W, Board.START_E -> IntStream.range(0, board.getHeight())
                  .mapToObj(y -> new Coords(player.getStartingPos() == Board.START_W ? 0 : board.getWidth() - 1, y));
            default -> Stream.<Coords>empty();
        };
        return edge.filter(c -> player.getFleeZone().containsCoords(c, board)).toList();
    }

    static List<SBFFormation> enemiesOnBoard(SBFGame game, SBFFormation formation) {
        return game.getActiveFormations().stream().filter(SBFFormation::isDeployed)
              .filter(enemy -> game.onSameBoard(formation, enemy))
              .filter(enemy -> game.areHostile(enemy, game.getPlayer(formation.getOwnerId()))).toList();
    }

    /** Zero means the formation has escaped visual contact. Otherwise favor getting beyond the nearest threat. */
    static int exposure(SBFGame game, BoardLocation location, List<SBFFormation> enemies) {
        return enemies.stream().mapToInt(enemy -> visualExposure(game, enemy, location)).max().orElse(0);
    }

    static int visualExposure(SBFGame game, SBFFormation viewer, BoardLocation location) {
        if ((location == null) || (viewer.getPosition() == null) || !location.isSameBoardAs(viewer.getPosition())) {
            return 0;
        }
        int range = game.usesDoubleBlind() ? SBFDetectionHelper.visualRange(game, viewer) : 2;
        int distance = location.coords().distance(viewer.getPosition().coords());
        if ((distance > range) || !game.combatOrders().hasLineOfSight(game, viewer.getPosition(), location, false)) {
            return 0;
        }
        return range + 1 - distance;
    }
}
