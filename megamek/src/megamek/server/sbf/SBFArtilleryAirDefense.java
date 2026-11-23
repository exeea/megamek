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

import megamek.common.actions.sbf.SBFAirDefenseArtilleryAttack;
import megamek.common.actions.sbf.SBFAirGroundAttack;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.compute.Compute;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFArtilleryAmmo;
import megamek.common.strategicBattleSystems.SBFArtilleryState.AirHit;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.ContactHit;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;
import megamek.common.strategicBattleSystems.SBFToHitData;
import megamek.common.strategicBattleSystems.SBFUnit;

/** Artillery flak and air-defense Arrow IV resolve before standard weapon attacks, IO:BF pp. 205–206. */
final class SBFArtilleryAirDefense {
    private static int arrowElements(SBFFormation attacker) {
        return (int) attacker.getUnits().stream().filter(u -> u.carriesArtillery(BattleForceSUA.ARTAIS, SBFArtilleryAmmo.AIR_DEFENSE)
                    || u.carriesArtillery(BattleForceSUA.ARTAC, SBFArtilleryAmmo.AIR_DEFENSE))
              .flatMap(u -> u.getElements().stream()).filter(e -> e.hasAnySUAOf(BattleForceSUA.ARTAIS, BattleForceSUA.ARTAC)).count();
    }

    static boolean canFire(SBFGame game, SBFFormation attacker, SBFAirDefenseArtilleryAttack shot) {
        var target = game.getFormation(shot.getTargetId()).orElse(null);
        if (!attacker.occupiesGroundHex() || attacker.isMoraleRestricted() || !attacker.getEngagedOpponents().isEmpty()
              || target == null || !target.isAerospace() || !game.areHostile(target, game.getPlayer(attacker.getOwnerId()))
              || !game.getVisibility(game.getPlayer(attacker.getOwnerId()), target.getId()).isLocated()
              || attacker.getUnits().stream().noneMatch(u -> u.canFire() && u.carriesArtillery(shot.artilleryType(), shot.ammo()))) {
            return false;
        }
        if (shot.ammo() == SBFArtilleryAmmo.CLUSTER) {
            return (target.getRadarZone() == SBFRadarZone.CENTRAL) && target.getGroundFlightPath().stream()
                  .anyMatch(p -> p.isSameBoardAs(attacker.getPosition()));
        }
        return (shot.ammo() == SBFArtilleryAmmo.AIR_DEFENSE) && (arrowElements(attacker) >= 2)
              && ((target.getRadarZone() == SBFRadarZone.CENTRAL) || (target.getRadarZone() == SBFRadarZone.INNER));
    }

    static int targetNumber(SBFGame game, SBFFormation attacker, SBFFormation target, SBFAirDefenseArtilleryAttack shot) {
        boolean striking = game.getActionsVector().stream().anyMatch(a -> a instanceof SBFAirGroundAttack
              && a.getEntityId() == target.getId());
        var toHit = new SBFToHitData(attacker.getSkill(), "Skill");
        SBFToHitData.processAttackerStatus(toHit, game, attacker);
        if (shot.ammo() == SBFArtilleryAmmo.CLUSTER) {
            toHit.addModifier(striking ? 3 : 5, "artillery flak");
        } else {
            SBFUnit unit = attacker.getUnits().stream().filter(u -> u.canFire() && u.carriesArtillery(shot.artilleryType(),
                  SBFArtilleryAmmo.AIR_DEFENSE)).findFirst().orElseThrow();
            int range = target.getRadarZone() == SBFRadarZone.INNER ? 2 : striking ? 0 : 1;
            toHit.addModifier(range, "range");
            SBFToHitData.processGroundToAir(toHit, attacker, unit, target);
            toHit.addModifier(2 * unit.getTargetingCrits(), "targeting damage");
            toHit.addModifier(-2, "Arrow IV homing");
        }
        return toHit.getValue();
    }

    static void resolve(SBFGameManager manager, SBFFormation attacker, SBFAirDefenseArtilleryAttack shot) {
        SBFGame game = manager.getGame();
        var target = game.getFormation(shot.getTargetId()).orElse(null);
        if (target == null || !canFire(game, attacker, shot)) {
            return;
        }
        int tn = targetNumber(game, attacker, target, shot);
        int roll = Compute.d6(2);
        int damage = shot.ammo() == SBFArtilleryAmmo.CLUSTER ? SBFArtillery.damage(attacker, shot.artilleryType(), shot.ammo()) - 1
              : Math.round(arrowElements(attacker) / 3f);
        manager.addReportFor(new SBFReportEntry(3134).add(attacker.generalName()).add(target.generalName()).add(tn).add(roll),
              attacker, target);
        target.recordArtilleryAttack(attacker, game.getCurrentRound(), false);
        if ((roll < tn) || (damage <= 0) || target.getUnits().isEmpty()) {
            return;
        }
        if ((target.getUnits().size() == 1) || game.getOptions().booleanOption(SBFRuleOptions.BASE_SIMPLIFIED_DAMAGE)) {
            apply(manager, attacker, target, Compute.randomInt(target.getUnits().size()), damage);
        } else {
            int chooser = SBFCombatDamage.tacticsWinner(manager, attacker, target, false);
            game.artillery().airHits().add(new AirHit(attacker.getId(), target.getId(), chooser, damage));
            remind(manager, chooser);
        }
    }

    static void remind(SBFGameManager manager, int player) {
        var choice = manager.getGame().artillery().airHits().stream().filter(h -> h.chooser() == player).findFirst();
        choice.ifPresent(hit -> manager.getGame().getFormation(hit.target()).ifPresent(target -> manager.sendServerChat(player,
              "Choose a Flight of contact " + target.getId() + " for " + hit.damage() + " anti-air damage: /sbfcombat aa-hit <1–"
                    + target.getUnits().size() + ">.")));
    }

    static boolean choose(SBFGameManager manager, int player, int flight) {
        var game = manager.getGame();
        var choices = game.artillery().airHits();
        if (!game.getPhase().isFiring() || choices.isEmpty() || choices.getFirst().chooser() != player) {
            return false;
        }
        var hit = choices.getFirst();
        var target = game.getFormation(hit.target()).orElse(null);
        var attacker = game.getFormation(hit.attacker()).orElse(null);
        if (target == null || attacker == null || flight < 0 || flight >= target.getUnits().size()) {
            return false;
        }
        choices.removeFirst();
        apply(manager, attacker, target, flight, hit.damage());
        manager.endCurrentPhase();
        return true;
    }

    private static void apply(SBFGameManager manager, SBFFormation attacker, SBFFormation target, int flight, int damage) {
        var hit = new ContactHit(attacker.getId(), target.getId(), damage, -1, flight);
        SBFCombatDamage.applyImmediateHits(manager, attacker, target, List.of(hit), LossCause.ARTILLERY_OR_BOMB);
    }
}
