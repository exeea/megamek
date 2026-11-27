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

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import megamek.common.Player;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.compute.Compute;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMinefields;
import megamek.common.strategicBattleSystems.SBFMinefields.Construction;
import megamek.common.strategicBattleSystems.SBFMinefields.Field;
import megamek.common.strategicBattleSystems.SBFMinefields.Type;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFReportEntry;

/** One entry point for scenario, dispenser and Thunder minefields (IO:BF pp. 206, 214–215, 266). */
final class SBFMinefieldControl {
    static void thunder(SBFGame game, BoardLocation site, int owner, boolean active) {
        var fields = game.minefields().fields();
        int size = fields.containsKey(site) ? fields.get(site).size() : 1;
        fields.put(site, new Field(owner, active ? Type.ACTIVE : Type.CONVENTIONAL, size, 2, false));
    }

    static boolean lay(SBFGameManager manager, int owner, int formation, Type type, int size, int density, boolean command) {
        var game = manager.getGame();
        var layer = game.getFormation(formation).orElse(null);
        if (!game.getPhase().isMovement() || game.getTurn() == null || layer == null
              || !game.getTurn().isValid(owner, layer, game) || !layer.occupiesGroundHex()
              || !layer.getEngagedOpponents().isEmpty() || game.engagementOrders().pendingMove() != null
              || game.minefields().fields().containsKey(layer.getPosition()) || game.minefields().construction().containsKey(formation)
              || SBFMinefields.threshold(size) == 0 || density < 1 || density > 5) {
            return false;
        }
        int capacity = 0;
        int elements = 0;
        for (var unit : layer.getUnits()) {
            var layers = unit.getElements().stream().filter(e -> e.getSUA(BattleForceSUA.MDS) instanceof Number).toList();
            int dispensers = layers.stream().mapToInt(e -> ((Number) e.getSUA(BattleForceSUA.MDS)).intValue()).sum();
            if (layers.isEmpty() && unit.getSUA(BattleForceSUA.MDS) instanceof Number summary) {
                // A single legacy numeric rating identifies one minelayer with that many dispensers.
                elements++;
                capacity += summary.intValue();
            } else {
                elements += layers.size();
                capacity += dispensers * layers.size();
            }
        }
        if (elements == 0 || size * density > capacity) {
            return false;
        }
        // The worked p. 214 example multiplies the sum of size and density by 17.
        int turns = Math.max(1, Math.round((size + density) * 17f / (6 * elements)));
        game.minefields().construction().put(formation, new Construction(owner, layer.getPosition(),
              new Field(owner, type, size, density, command), game.getCurrentRound() + turns - 1));
        layer.setDone(true);
        manager.sendUnitUpdate(layer);
        manager.endCurrentTurn(layer);
        return true;
    }

    static void finishConstruction(SBFGameManager manager) {
        var game = manager.getGame();
        var work = game.minefields().construction();
        for (var entry : new ArrayList<>(work.entrySet())) {
            var layer = game.getFormation(entry.getKey()).orElse(null);
            var project = entry.getValue();
            if (layer == null || !layer.occupiesGroundHex() || !project.location().equals(layer.getPosition())
                  || layer.getMpSpentThisTurn() > 0) {
                work.remove(entry.getKey());
            } else if (project.completion() <= game.getCurrentRound()) {
                game.minefields().fields().put(project.location(), project.field());
                work.remove(entry.getKey());
                manager.sendServerChat(project.owner(), "Minefield completed at " + project.location().coords().getBoardNum() + ".");
            }
        }
    }

    static void enter(SBFGameManager manager, SBFFormation mover, BoardLocation site) {
        enter(manager, mover, site, () -> Compute.d6(2), Compute::d6);
    }

    static void enter(SBFGameManager manager, SBFFormation mover, BoardLocation site, IntSupplier check, IntSupplier damageDie) {
        SBFGame game = manager.getGame();
        if (mover.isOnRadarMap()) {
            return;
        }
        if (game.getHex(site).hasStrategicInferno()) {
            damageSusceptible(manager, mover, game.minefields().infernoOwners().getOrDefault(site, Player.PLAYER_NONE));
        }
        if (game.getFormation(mover.getId()).isEmpty()) {
            return;
        }
        Field field = game.minefields().fields().get(site);
        if (field == null || field.command() || !game.areHostile(mover, game.getPlayer(field.owner()))) {
            return;
        }
        int modifier = 0;
        if (field.type() == Type.ACTIVE && mover.getJumpUsedThisTurn() > 0) {
            modifier = Math.min(0, -3 + mover.getJumpUsedThisTurn() / 3);
        } else if (field.type() == Type.CONVENTIONAL) {
            if (skims(mover.getMovementMode())) {
                modifier = -2;
            } else if (mover.getUnits().stream().anyMatch(u -> skims(u.getMovementMode()))
                  || mover.getUnits().stream().filter(u -> u.isAnyTypeOf(SBFElementType.CI, SBFElementType.BA)).count() >= 2) {
                modifier = -1;
            }
        }
        int threshold = SBFMinefields.threshold(field.size());
        boolean primary = check.getAsInt() + modifier >= threshold;
        boolean secondary = check.getAsInt() + modifier >= threshold;
        if (primary || secondary) {
            trigger(manager, site, field, mover, primary, secondary, damageDie);
        }
    }

    private static boolean skims(SBFMovementMode mode) {
        return mode == SBFMovementMode.HOVER || mode == SBFMovementMode.WIGE;
    }

    static boolean detonate(SBFGameManager manager, int owner, int formation, BoardLocation site) {
        var game = manager.getGame();
        var observer = game.getFormation(formation).orElse(null);
        var field = game.minefields().fields().get(site);
        if ((!game.getPhase().isMovementReport() && !game.getPhase().isFiring()) || field == null || !field.command()
              || observer == null || observer.getOwnerId() != owner || !observer.occupiesGroundHex()
              || game.areHostile(observer, game.getPlayer(field.owner()))
              || !game.minefields().canDetonate(formation, game.getCurrentRound())
              || !game.combatOrders().hasLineOfSight(game, observer.getPosition(), site, false)
              || (game.usesDoubleBlind() && observer.getPosition().coords().distance(site.coords()) > SBFDetectionHelper.visualRange(game, observer, site))) {
            return false;
        }
        game.minefields().detonated(formation, game.getCurrentRound());
        trigger(manager, site, field, null, true, true, Compute::d6);
        return true;
    }

    private static void trigger(SBFGameManager manager, BoardLocation site, Field field, SBFFormation mover,
          boolean primary, boolean secondary, IntSupplier damageDie) {
        var game = manager.getGame();
        List<SBFFormation> occupants = new ArrayList<>(game.getActiveFormationsAt(site).stream().filter(SBFFormation::occupiesGroundHex).toList());
        if (mover != null && !occupants.contains(mover)) {
            occupants.add(mover);
        }
        for (var target : occupants) {
            manager.addReportFor(new SBFReportEntry(3135).add(target.generalName()).add(field.type().name()).add(field.density()), target);
            if (field.type() == Type.INFERNO) {
                target.hitByInferno(game.getCurrentRound());
                damageSusceptible(manager, target, field.owner());
            } else if (field.type() == Type.EMP) {
                target.hitByEmp(game.getCurrentRound());
                damageSusceptible(manager, target, field.owner());
            } else if (field.command() || target == mover) {
                conventional(manager, target, field.owner(), field.density(), primary, secondary, damageDie);
            }
            if (game.getFormation(target.getId()).isPresent()) {
                manager.sendUnitUpdate(target);
            }
        }
        if (field.type() == Type.INFERNO) {
            ignite(manager, site, field.owner());
        } else if (field.type() == Type.EMP) {
            game.minefields().igniteEmp(site, game.getCurrentRound());
        }
        if (field.density() == 1 || (!field.command() && (field.type() == Type.INFERNO || field.type() == Type.EMP))) {
            game.minefields().fields().remove(site);
        } else {
            game.minefields().fields().put(site, new Field(field.owner(), field.type(), field.size(), field.density() - 1, field.command()));
        }
    }

    private static void conventional(SBFGameManager manager, SBFFormation target, int owner, int density,
          boolean primary, boolean secondary, IntSupplier damageDie) {
        var eligible = new ArrayList<>(java.util.stream.IntStream.range(0, target.getUnits().size()).boxed().toList());
        int[] damage = new int[eligible.size()];
        if (primary && !eligible.isEmpty()) {
            damage[eligible.remove(Compute.randomInt(eligible.size())).intValue()] = density;
        }
        if (secondary && !eligible.isEmpty()) {
            damage[eligible.get(Compute.randomInt(eligible.size()))] = density / (damageDie.getAsInt() <= 3 ? 4 : 2);
        }
        SBFCombatDamage.applyHazardHits(manager, target, owner, damage);
    }

    static void damageSusceptible(SBFGameManager manager, SBFFormation target, int owner) {
        int[] damage = target.getUnits().stream().mapToInt(u -> u.isAnyTypeOf(SBFElementType.V, SBFElementType.CI, SBFElementType.BA) ? 1 : 0).toArray();
        SBFCombatDamage.applyHazardHits(manager, target, owner, damage);
    }

    static void ignite(SBFGameManager manager, BoardLocation site, int owner) {
        var game = manager.getGame();
        game.minefields().infernoOwners().put(site, owner);
        if (!game.getHex(site).hasStrategicInferno()) {
            game.getHex(site).igniteStrategicInferno();
            manager.send(manager.getPacketHelper().createBoardsPacket());
        }
    }
}
