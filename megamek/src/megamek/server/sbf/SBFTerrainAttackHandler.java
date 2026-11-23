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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.actions.sbf.SBFTerrainAttack;
import megamek.common.alphaStrike.ASRange;
import megamek.common.board.BoardLocation;
import megamek.common.compute.Compute;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFToHitData;

/** Normal weapon attacks on terrain use the standard unengaged range and remove terrain factor, IO:BF pp. 169, 221. */
public final class SBFTerrainAttackHandler extends AbstractSBFActionHandler {
    private final SBFTerrainAttack declaration;

    public SBFTerrainAttackHandler(SBFTerrainAttack action, SBFGameManager manager) {
        super(action, manager);
        declaration = action;
    }

    static boolean declare(SBFGameManager manager, int owner, SBFTerrainAttack shot) {
        var game = manager.getGame();
        var f = game.getFormation(shot.getEntityId()).orElse(null);
        if (!game.getPhase().isFiring() || game.getTurn() == null || f == null || !game.getTurn().isValid(owner, f, game)
              || !shot.isDataValid(game) || !f.occupiesGroundHex() || !f.getPosition().isSameBoardAs(shot.terrain())
              || f.isMoraleRestricted() || !f.canAttackAfterEngagement() || !f.getEngagedOpponents().isEmpty()
              || game.getActionsVector().stream().anyMatch(a -> a.getEntityId() == f.getId())
              || game.combatOrders().spottedTarget(f.getId()).isPresent()) {
            return false;
        }
        if (shot.clearingPassage() != null) {
            if (!f.getPosition().equals(shot.terrain()) || f.woodlandClearingRating() == 0
                  || !SBFTerrainConversion.canClear(game.getHex(shot.terrain()), shot.clearingPassage())) {
                return false;
            }
        } else if (!SBFTerrainConversion.canDamage(game.getHex(shot.terrain()))
              || !game.combatOrders().hasTerrainSight(game, f.getPosition(), shot.terrain(), true)
              || f.getPosition().coords().distance(shot.terrain().coords()) > 2
              || f.getUnits().stream().noneMatch(u -> u.canFire() && SBFStandardUnitAttack.standardWeaponDamage(game, f, u, range(f, shot.terrain())) > 0)) {
            return false;
        }
        game.addAction(shot);
        f.setDone(true);
        manager.sendUnitUpdate(f);
        manager.sendPendingActions();
        manager.endCurrentTurn(f);
        manager.sendPendingPackets();
        return true;
    }

    /** Pool the work declared this turn before rounding, so declaration order cannot change the duration. */
    static void resolveClearing(SBFGameManager manager) {
        record Project(BoardLocation site, int passage) { }
        var game = manager.getGame();
        Map<Project, List<SBFFormation>> workers = new LinkedHashMap<>();
        for (var action : game.getActionsVector()) {
            if (action instanceof SBFTerrainAttack order && order.clearingPassage() != null) {
                game.getFormation(order.getEntityId()).filter(f -> order.terrain().equals(f.getPosition()))
                      .ifPresent(f -> workers.computeIfAbsent(new Project(order.terrain(), order.clearingPassage()),
                            unused -> new ArrayList<>()).add(f));
            }
        }
        boolean changed = false;
        for (var entry : workers.entrySet()) {
            var project = entry.getKey();
            int rating = entry.getValue().stream().mapToInt(SBFFormation::woodlandClearingRating).sum();
            if (rating > 0 && SBFTerrainConversion.canClear(game.getHex(project.site()), project.passage())) {
                boolean complete = SBFTerrainConversion.clear(game, project.site(), project.passage(), rating);
                changed |= complete;
                for (var formation : entry.getValue()) {
                    manager.addReportFor(new SBFReportEntry(complete ? 3138 : 3137).add(formation.generalName())
                          .add(project.site().coords().getBoardNum()), formation);
                }
            }
        }
        if (changed) {
            manager.send(manager.getPacketHelper().createBoardsPacket());
        }
    }

    private static ASRange range(SBFFormation f, BoardLocation target) {
        return f.getPosition().coords().distance(target.coords()) == 2 ? ASRange.EXTREME : ASRange.LONG;
    }

    @Override
    public boolean cares() {
        return game().getPhase().isFiring();
    }

    @Override
    public void handle() {
        var attacker = game().getFormation(declaration.getEntityId()).orElse(null);
        var site = declaration.terrain();
        if (attacker == null || !attacker.occupiesGroundHex() || !declaration.isDataValid(game())) {
            setFinished();
            return;
        }
        ASRange range = range(attacker, site);
        int damage = 0;
        for (var unit : attacker.getUnits()) {
            int weapon = SBFStandardUnitAttack.standardWeaponDamage(game(), attacker, unit, range);
            if (!unit.canFire() || weapon <= 0 || unit.isUnderwater(game(), attacker) && !attacker.getPosition().equals(site)) {
                continue;
            }
            var tn = new SBFToHitData(attacker.getSkill() + unit.getTargetingCrits(), "Skill and targeting criticals");
            tn.addModifier(range == ASRange.LONG ? 2 : 3, "unengaged range");
            SBFToHitData.processAttackerStatus(tn, game(), attacker);
            SBFToHitData.processGroundAttacker(tn, game(), attacker, unit);
            if (unit.isUnderwater(game(), attacker)) {
                tn.addModifier(1, "underwater");
            }
            SBFToHitData.applyDarkness(tn, game(), attacker, site);
            SBFToHitData.processTargetTerrain(tn, game(), game().getHex(site), null);
            int roll = Compute.d6(2);
            if (roll >= tn.getValue()) {
                damage += weapon;
            }
            gameManager().addReportFor(new SBFReportEntry(3136).add(unit.getName()).add(site.coords().getBoardNum())
                  .add(tn.getValue()).add(roll).add(roll >= tn.getValue() ? weapon : 0), attacker);
        }
        if (SBFTerrainConversion.damage(game(), site, damage)) {
            gameManager().send(gameManager().getPacketHelper().createBoardsPacket());
        }
        setFinished();
    }
}
