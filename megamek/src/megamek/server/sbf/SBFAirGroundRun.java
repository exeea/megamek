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
import java.util.HashSet;
import java.util.List;

import megamek.common.Player;
import megamek.common.actions.sbf.SBFAirGroundAttack;
import megamek.common.actions.sbf.SBFAirGroundAttack.Release;
import megamek.common.actions.sbf.SBFAirGroundAttack.Type;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.compute.Compute;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;
import megamek.common.strategicBattleSystems.SBFToHitData;
import megamek.common.strategicBattleSystems.SBFUnit;

/** Air-to-ground strike, strafe and conventional bombing (Strategic Aerospace, IO:BF p. 178). */
public class SBFAirGroundRun extends AbstractSBFActionHandler {
    private final SBFAirGroundAttack run;
    /** Keep original Flight participants even if immediate flak removes an earlier index (IO:BF p. 205). */
    private List<SBFUnit> declaredFlights;


    public SBFAirGroundRun(SBFAirGroundAttack run, SBFGameManager manager) {
        super(run, manager);
        this.run = run;
        manager.getGame().getFormation(run.getEntityId()).ifPresent(this::captureFlights);
    }

    private void captureFlights(SBFFormation squadron) {
        var flights = run.flights();
        if (flights.isEmpty() && !run.releases().isEmpty() && !squadron.getUnits().isEmpty()) {
            declaredFlights = java.util.Collections.nCopies(run.releases().size(), squadron.getUnits().getFirst());
        } else {
            declaredFlights = flights.stream().map(i -> i >= 0 && i < squadron.getUnits().size()
                  ? squadron.getUnits().get(i) : null).toList();
        }
    }

    static boolean declare(SBFGameManager manager, int player, SBFAirGroundAttack submitted) {
        SBFGame game = manager.getGame();
        SBFFormation squadron = game.getFormation(submitted.getEntityId()).orElse(null);
        boolean baDive = (squadron != null) && isBombingBattleArmor(squadron) && (submitted.type() == Type.DIVE);
        if (!game.getPhase().isFiring() || (squadron == null) || (game.getTurn() == null)
              || !game.getTurn().isValid(player, squadron, game) || (!baDive && (squadron.getRadarZone() != SBFRadarZone.CENTRAL))
              || game.combatOrders().spottedTarget(squadron.getId()).isPresent()
              || squadron.isMoraleRestricted() || squadron.hasSprintedThisTurn() || !squadron.canAttackAfterEngagement()
              || (!baDive && squadron.getEngagedOpponents().stream().anyMatch(id -> !squadron.reservedAirReturn(id)))
              || game.getActionsVector().stream().anyMatch(action -> action.getEntityId() == squadron.getId())) {
            return false;
        }
        boolean bombs = (submitted.type() == Type.DIVE) || (submitted.type() == Type.ALTITUDE);
        List<Integer> targets = submitted.targets();
        int maxTargets = submitted.type() == Type.STRAFE ? 4 : 1;
        if ((submitted.type() != Type.ALTITUDE) && (targets.isEmpty() || (targets.size() > maxTargets)
              || (new HashSet<>(targets).size() != targets.size()))) {
            return false;
        }
        if ((submitted.type() == Type.ALTITUDE) && !targets.isEmpty()) {
            return false;
        }
        for (int targetId : targets) {
            SBFFormation target = game.getFormation(targetId).orElse(null);
            if ((target == null) || target.isOnRadarMap() || !game.areHostile(target, game.getPlayer(player))
                  || !game.getVisibility(game.getPlayer(player), targetId).isLocated()
                  || !onRun(squadron, target.getPosition())
                  || (baDive && !squadron.getEngagedOpponents().isEmpty() && !squadron.isEngagedWith(targetId))
                  || (!target.getUnits().isEmpty()
                  && target.getUnits().stream().allMatch(unit -> unit.isUnderwater(game, target)))) {
                return false;
            }
        }
        boolean mixed = squadron.getUnits().stream().anyMatch(unit -> unit.getMovementMode() == SBFMovementMode.AERODYNE)
              && squadron.getUnits().stream().anyMatch(unit -> unit.getMovementMode() == SBFMovementMode.SPHEROID);
        if (mixed && ((submitted.type() == Type.STRAFE) || (submitted.type() == Type.DIVE))) {
            return false;
        }
        List<Integer> flights = new ArrayList<>(submitted.flights());
        if (bombs) {
            if (!validReleases(squadron, submitted, game) || !selectBombFlights(squadron, submitted, flights)) {
                return false;
            }
            int he = submitted.releases().stream().mapToInt(Release::highExplosive).sum();
            int cluster = submitted.releases().stream().mapToInt(Release::cluster).sum();
            squadron.setBombLoad(squadron.getHighExplosiveBombs() - he, squadron.getClusterBombs() - cluster);
        } else {
            if (flights.isEmpty()) {
                for (int i = 0; i < squadron.getUnits().size(); i++) {
                    SBFUnit unit = squadron.getUnits().get(i);
                    if (unit.canFire() && (unit.getCurrentArmor() > 0) && (unit.getCurrentDamage().S().damage > 0)) {
                        flights.add(i);
                    }
                }
            }
            if (flights.isEmpty() || !submitted.releases().isEmpty() || (new HashSet<>(flights).size() != flights.size())
                  || (flights.size() > game.combatOrders().standardAttackLimit(squadron))) {
                return false;
            }
            for (int index : flights) {
                if ((index < 0) || (index >= squadron.getUnits().size())) {
                    return false;
                }
                SBFUnit unit = squadron.getUnits().get(index);
                if (!unit.canFire() || (unit.getCurrentArmor() <= 0) || (unit.getCurrentDamage().S().damage == 0)) {
                    return false;
                }
            }
        }
        game.addAction(new SBFAirGroundAttack(squadron.getId(), submitted.type(), targets, flights, submitted.releases()));
        squadron.setDone(true);
        manager.sendUnitUpdate(squadron);
        manager.sendPendingActions();
        manager.endCurrentTurn(squadron);
        return true;
    }

    static boolean isBombingBattleArmor(SBFFormation formation) {
        return formation.isType(SBFElementType.BA) && (formation.getMovementMode() == SBFMovementMode.VTOL)
              && (formation.getMovement() > 0) && !formation.isAirVehicleLanded() && formation.hasSUA(BattleForceSUA.BOMB);
    }

    private static boolean onRun(SBFFormation formation, BoardLocation hex) {
        return formation.isOnRadarMap() ? formation.getGroundFlightPath().contains(hex)
              : ((hex != null) && hex.equals(formation.getPosition()));
    }

    private static boolean selectBombFlights(SBFFormation squadron, SBFAirGroundAttack attack, List<Integer> flights) {
        boolean individualRatings = squadron.getUnits().stream().anyMatch(u -> u.hasSUA(BattleForceSUA.BOMB));
        List<Integer> available = new ArrayList<>();
        for (int i = 0; i < squadron.getUnits().size(); i++) {
            SBFUnit unit = squadron.getUnits().get(i);
            if (unit.canFire() && (unit.getCurrentArmor() > 0) && (!individualRatings || unit.hasSUA(BattleForceSUA.BOMB))) {
                available.add(i);
            }
        }
        if (available.isEmpty()) {
            return false;
        }
        if (flights.isEmpty()) {
            attack.releases().forEach(unused -> flights.add(available.getFirst()));
        }
        return (flights.size() == attack.releases().size()) && available.containsAll(flights);
    }

    private static boolean validReleases(SBFFormation squadron, SBFAirGroundAttack run, SBFGame game) {
        List<Release> releases = run.releases();
        if (!squadron.hasSUA(BattleForceSUA.BOMB) || (run.type() == Type.ALTITUDE ? releases.size() < 2 : releases.size() != 1)
              || (releases.stream().map(Release::hex).distinct().count() != releases.size())) {
            return false;
        }
        long he = 0;
        long cluster = 0;
        for (Release release : releases) {
            if (!onRun(squadron, release.hex()) || (release.highExplosive() < 0)
                  || (release.cluster() < 0) || (((long) release.highExplosive() + release.cluster()) < 1)) {
                return false;
            }
            he += release.highExplosive();
            cluster += release.cluster();
        }
        return (he <= squadron.getHighExplosiveBombs()) && (cluster <= squadron.getClusterBombs())
              && ((run.type() != Type.DIVE) || releases.getFirst().hex().equals(
              game.getFormation(run.targets().getFirst()).orElseThrow().getPosition()));
    }

    @Override
    public boolean cares() {
        return game().getPhase().isFiring();
    }

    @Override
    public void handle() {
        SBFFormation squadron = game().getFormation(run.getEntityId()).orElse(null);
        if (squadron != null) {
            if (declaredFlights == null) {
                captureFlights(squadron); // Saves created before Flight identities were retained.
            }
            if ((run.type() == Type.STRIKE) || (run.type() == Type.STRAFE)) {
                for (int targetId : run.targets()) {
                    game().getFormation(targetId).ifPresent(target -> attackGround(squadron, target));
                }
            } else {
                for (int i = 0; i < run.releases().size(); i++) {
                    SBFUnit flight = i < declaredFlights.size() ? declaredFlights.get(i) : null;
                    if (flight != null && squadron.getUnits().contains(flight) && flight.getCurrentArmor() > 0 && flight.canFire()) {
                        releaseBombs(squadron, flight, run.releases().get(i));
                    }
                }
            }
        }
        setFinished();
    }

    private void attackGround(SBFFormation squadron, SBFFormation target) {
        boolean strafe = run.type() == Type.STRAFE;
        target.recordAttack(squadron);
        gameManager().addReportFor(new SBFReportEntry(2001).add(squadron.generalName()).add(target.generalName()),
              squadron, target);
        for (SBFUnit unit : declaredFlights) {
            if (unit == null || !squadron.getUnits().contains(unit) || unit.getCurrentArmor() <= 0 || !unit.canFire()) {
                continue;
            }
            SBFToHitData tn = new SBFToHitData(squadron.getSkill(), "Skill");
            SBFToHitData.processDefensiveMovement(tn, target);
            tn.addModifier(strafe ? 4 : 2, strafe ? "strafing" : "striking");
            tn.addModifier(2 * unit.getTargetingCrits(), "targeting damage");
            SBFToHitData.aerospaceFireControl(tn, unit);
            SBFToHitData.processTargetTerrain(tn, game(), game().getHex(target.getPosition()), target);
            if (target.isAerospace()) {
                tn.addModifier(-4, "grounded aerospace");
            } else if (!target.isAirVehicleLanded() && ((target.getMovementMode() == SBFMovementMode.VTOL)
                  || ((target.getMovementMode() == SBFMovementMode.WIGE) && (target.getMpSpentThisTurn() > 0)))) {
                tn.addModifier(1, "airborne VTOL or WiGE");
            }
            int roll = Compute.d6(2);
            report(squadron, roll, tn.getValue());
            if (roll >= tn.getValue()) {
                int damage = unit.getCurrentDamage().getDamage(ASRange.SHORT).damage;
                SBFCombatDamage.recordStandardHit(gameManager(), squadron, target, strafe ? (damage + 3) / 4 : damage);
            }
        }
    }

    private void releaseBombs(SBFFormation squadron, SBFUnit flight, Release release) {
        boolean dive = run.type() == Type.DIVE;
        int remainingHE = release.highExplosive();
        int remainingCluster = release.cluster();
        do {
            int he = dive ? Math.min(5, remainingHE) : remainingHE;
            int cluster = dive ? Math.min(5 - he, remainingCluster) : remainingCluster;
            remainingHE -= he;
            remainingCluster -= cluster;
            int roll = Compute.d6(2);
            SBFToHitData toHit = new SBFToHitData(squadron.getSkill(), "Skill");
            toHit.addModifier(dive ? 2 : 3, dive ? "dive bombing" : "altitude bombing");
            toHit.addModifier(2 * flight.getTargetingCrits(), "targeting damage");
            SBFToHitData.aerospaceFireControl(toHit, flight);
            if (dive) {
                game().getFormation(run.getTargetId()).ifPresent(target -> SBFToHitData.processDefensiveMovement(toHit, target));
            }
            int tn = toHit.getValue();
            report(squadron, roll, tn);
            int damage = (roll >= tn ? 2 * he : 0) + (roll >= tn - 1 ? cluster : 0);
            List<SBFFormation> occupants = game().getActiveFormationsAt(release.hex());
            Player owner = game().getPlayer(squadron.getOwnerId());
            for (SBFFormation target : occupants) {
                if (game().areHostile(target, owner) && (!dive || run.targets().contains(target.getId()))) {
                    target.recordAttack(squadron);
                    if (damage > 0) {
                        SBFCombatDamage.recordStandardHit(gameManager(), squadron, target, damage, LossCause.ARTILLERY_OR_BOMB);
                    }
                }
            }
            int missed = 2 * he + cluster - damage;
            if ((missed > 0) && (Compute.d6() == 6)) {
                List<SBFFormation> friendlies = occupants.stream().filter(f -> !game().areHostile(f, owner)).toList();
                if (!friendlies.isEmpty()) {
                    SBFFormation friendly = friendlies.get(Compute.randomInt(friendlies.size()));
                    friendly.recordAttack(squadron);
                    SBFCombatDamage.recordRandomHit(gameManager(), squadron, friendly, missed, LossCause.ARTILLERY_OR_BOMB);
                }
            }
        } while ((remainingHE > 0) || (remainingCluster > 0));
    }

    private void report(SBFFormation squadron, int roll, int targetNumber) {
        gameManager().addReportFor(new SBFReportEntry(2003).add(targetNumber).noNL(), squadron);
        gameManager().addReportFor(new SBFReportEntry(2020).add(squadron.generalName()).add(roll), squadron);
    }
}
