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

import megamek.common.Player;
import megamek.common.actions.sbf.SBFAirDefenseArtilleryAttack;
import megamek.common.actions.sbf.SBFArtilleryAttack;
import megamek.common.actions.sbf.SBFCounterBatteryAttack;
import megamek.common.actions.sbf.SBFDirectArtilleryAttack;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.compute.Compute;
import megamek.common.options.SBFRuleOptions;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.strategicBattleSystems.SBFArtilleryAmmo;
import megamek.common.strategicBattleSystems.SBFArtilleryState.Battery;
import megamek.common.strategicBattleSystems.SBFArtilleryState.Flight;
import megamek.common.strategicBattleSystems.SBFArtilleryState.Mission;
import megamek.common.strategicBattleSystems.SBFCombatOrders;
import megamek.common.strategicBattleSystems.SBFDesignQuirks;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFScenarioProgress;
import megamek.common.strategicBattleSystems.SBFToHitData;

/** On-map artillery, IO:BF pp. 169, 203-204. Declarations launch before standard attacks; rolls occur on arrival. */
public final class SBFArtillery extends AbstractSBFActionHandler {
    private final SBFArtilleryAttack shot;

    public SBFArtillery(SBFArtilleryAttack shot, SBFGameManager manager) {
        super(shot, manager);
        this.shot = shot;
    }

    public static boolean isArtilleryWeapon(BattleForceSUA type) {
        return (type != null) && type.isArtillery() && !type.isArtilleryCannon();
    }

    static int maxRange(BattleForceSUA type) {
        return switch (type) {
            case ARTAIS -> 8;
            case ARTAC -> 9;
            case ARTT -> 21;
            case ARTS -> 18;
            case ARTLT -> 31;
            case ARTCM5 -> 51;
            case ARTCM7 -> 92;
            case ARTCM9 -> 122;
            case ARTCM12 -> 153;
            case ARTBA -> 2;
            default -> 0;
        };
    }

    static boolean cruise(BattleForceSUA type) {
        return type.isAnyOf(BattleForceSUA.ARTCM5, BattleForceSUA.ARTCM7, BattleForceSUA.ARTCM9, BattleForceSUA.ARTCM12);
    }

    static int travelTurns(BattleForceSUA type, int hexes) {
        if (cruise(type)) {
            return Math.max(0, (hexes - 1) / 34);
        }
        return hexes <= 18 ? 0 : (hexes - 1) / 17;
    }

    static int damage(SBFFormation attacker, BattleForceSUA type) {
        if (attacker.constructionUnit() != null) {
            return attacker.getUnits().stream().filter(u -> !u.isConstructing() && u.getSUA(type) instanceof Number)
                  .mapToInt(u -> ((Number) u.getSUA(type)).intValue()).sum();
        }
        if (attacker.getSUA(type) instanceof Number rating) {
            return rating.intValue();
        }
        // Older converted formations have the weapon only on their component Units.
        return attacker.getUnits().stream().filter(u -> u.getSUA(type) instanceof Number)
              .mapToInt(u -> ((Number) u.getSUA(type)).intValue()).sum();
    }

    static int damage(SBFFormation attacker, BattleForceSUA type, SBFArtilleryAmmo ammo) {
        var armed = attacker.getUnits().stream().filter(u -> u.getSUA(type) instanceof Number).toList();
        if (armed.isEmpty()) {
            return (ammo == SBFArtilleryAmmo.STANDARD || ammo == SBFArtilleryAmmo.HOMING) ? damage(attacker, type) : 0;
        }
        return armed.stream().filter(u -> u.canFire() && u.carriesArtillery(type, ammo))
              .mapToInt(u -> ((Number) u.getSUA(type)).intValue()).sum();
    }

    static boolean declare(SBFGameManager manager, int player, SBFArtilleryAttack shot) {
        SBFGame game = manager.getGame();
        SBFFormation attacker = game.getFormation(shot.getEntityId()).orElse(null);
        if (!game.getPhase().isFiring() || !game.getOptions().booleanOption(SBFRuleOptions.ADVANCED_ARTILLERY)
              || (game.getTurn() == null) || (attacker == null) || !game.getTurn().isValid(player, attacker, game)
              || (!attacker.occupiesGroundHex() && !attacker.isOffMapArtillery()) || !attacker.canFireArtilleryAfterEngagement()
              || !isArtilleryWeapon(shot.artilleryType()) || (damage(attacker, shot.artilleryType(), shot.ammo()) <= 0)) {
            return false;
        }
        List<SBFArtilleryAttack> artillery = game.getActionsVector().stream().filter(SBFArtilleryAttack.class::isInstance)
              .map(SBFArtilleryAttack.class::cast).toList();
        if (artillery.stream().anyMatch(a -> (a.getEntityId() == attacker.getId()) && a.artilleryType() == shot.artilleryType())) {
            return false;
        }
        if ((shot instanceof SBFCounterBatteryAttack) || (shot instanceof SBFAirDefenseArtilleryAttack)) {
            boolean valid = shot instanceof SBFCounterBatteryAttack counter ? SBFCounterBattery.canFire(game, attacker, counter)
                  : SBFArtilleryAirDefense.canFire(game, attacker, (SBFAirDefenseArtilleryAttack) shot);
            if (!valid) {
                return false;
            }
            game.addAction(shot);
            manager.sendPendingActions();
            manager.sendPendingPackets();
            return true;
        }
        BoardLocation aim = shot.aim(game);
        if ((aim == null) || !game.hasBoardLocation(aim) || !attacker.getArtilleryPosition().isSameBoardAs(aim)) {
            return false;
        }
        var target = game.getFormation(shot.getTargetId()).orElse(null);
        if ((shot.getTargetId() != Player.PLAYER_NONE) && ((target == null) || !target.occupiesGroundHex()
              || !aim.equals(target.getPosition()) || !game.areHostile(target, game.getPlayer(player))
              || !game.getVisibility(game.getPlayer(player), target.getId()).isLocated())) {
            return false;
        }
        if ((shot.ammo() == SBFArtilleryAmmo.AIR_DEFENSE) || (shot.homing() && (target == null || shot.spotterId() != null))) {
            return false;
        }
        if ((attacker.isMoraleRestricted() || !attacker.getEngagedOpponents().isEmpty())
              && ((target == null) || !attacker.isEngagedWith(target.getId()))) {
            return false;
        }
        int distance = attacker.getArtilleryPosition().coords().distance(aim.coords());
        boolean direct = shot instanceof SBFDirectArtilleryAttack;
        if ((distance > maxRange(shot.artilleryType())) || (direct && (attacker.isOffMapArtillery() || (distance > 1) || shot.spotterId() != null
              || !game.combatOrders().hasTerrainSight(game, attacker.getPosition(), aim, true)))) {
            return false;
        }
        if (shot.spotterId() != null) {
            var spotter = game.getFormation(shot.spotterId()).orElse(null);
            if ((spotter == null) || !spotter.occupiesGroundHex() || game.areHostile(spotter, game.getPlayer(player))
                  || !spotter.getPosition().isSameBoardAs(aim)
                  || (spotter.getPosition().coords().distance(aim.coords()) > 2)
                  || !game.combatOrders().hasTerrainSight(game, spotter.getPosition(), aim, false)
                  || artillery.stream().anyMatch(a -> shot.spotterId().equals(a.spotterId()))) {
                return false;
            }
        }
        if (direct && (target != null) && SBFCombatOrders.controlsSight(game, attacker, target)) {
            game.combatOrders().setSight(attacker, target, true, true);
            new SBFCombatControl(manager).publish();
        }
        game.addAction(shot);
        manager.sendPendingActions();
        manager.sendPendingPackets();
        return true;
    }

    @Override
    public boolean cares() {
        return game().getPhase().isFiring();
    }

    @Override
    public void handle() {
        SBFGame game = game();
        SBFFormation attacker = game.getFormation(shot.getEntityId()).orElse(null);
        if (shot instanceof SBFAirDefenseArtilleryAttack antiAir) {
            if (attacker != null) {
                SBFArtilleryAirDefense.resolve(gameManager(), attacker, antiAir);
            }
            setFinished();
            return;
        }
        if (shot instanceof SBFCounterBatteryAttack counter) {
            if (attacker != null) {
                SBFCounterBattery.launch(game, attacker, counter);
            }
            setFinished();
            return;
        }
        var aim = shot.aim(game);
        if ((attacker == null) || (aim == null)) {
            setFinished();
            return;
        }
        var battery = new Battery(attacker.getId(), shot.artilleryType());
        var previous = game.artillery().mission(battery);
        boolean repeated = (previous != null) && previous.aim().equals(aim);
        boolean sameMission = repeated && (previous.target() == shot.getTargetId());
        int sequence = previous == null ? 1 : previous.sequence() + (sameMission ? 0 : 1);
        var spotter = shot.spotterId() == null ? null : game.getFormation(shot.spotterId()).orElse(null);
        int consecutive = repeated ? previous.consecutive() + 1 : 0;
        boolean direct = shot instanceof SBFDirectArtilleryAttack;
        SBFToHitData tn = new SBFToHitData(attacker.getSkill(), "Skill");
        SBFToHitData.processAttackerStatus(tn, game, attacker);
        int side = SBFScenarioProgress.sideId(game, game.getPlayer(attacker.getOwnerId()));
        boolean preplotted = game.artillery().scenarioPreplotted(side, aim);
        tn.addModifier(direct ? 4 : preplotted && (shot.getTargetId() != Player.PLAYER_NONE) ? 5 : 7, "artillery");
        if (direct) {
            SBFToHitData.applyDarkness(tn, game, attacker, aim);
            SBFToHitData.processTargetTerrain(tn, game, game.getHex(aim), game.getFormation(shot.getTargetId()).orElse(null));
            game.getFormation(shot.getTargetId()).ifPresent(t -> SBFToHitData.processGroundTarget(tn, t,
                  SBFToHitData.standardAttackRange(game, attacker, t).orElse(ASRange.LONG), true));
        } else if (spotter != null) {
            tn.addModifier(-1, "spotter");
            if (spotter.hasAnySUAOf(BattleForceSUA.LPRB, BattleForceSUA.BH)
                  || SBFDesignQuirks.formationHas(game, spotter, BattleForceSUA.PRB)) {
                tn.addModifier(-2, "probe");
            } else if (spotter.hasSUA(BattleForceSUA.RCN)) {
                tn.addModifier(-1, "recon");
            }
            if (spotter.getPosition().coords().distance(aim.coords()) <= 1) {
                tn.addModifier(-consecutive, "successive shots");
            }
            if (game.getActionsVector().stream().anyMatch(a -> a.getEntityId() == spotter.getId())) {
                tn.addModifier(1, "spotter attacked");
            }
        }
        if (game.scenarioProgress().isPreplotted(game, attacker.getOwnerId(), aim)) {
            tn.addModifier(-1, "battlefield intelligence pre-plot");
        }
        boolean locked = sameMission && previous.locked();
        game.artillery().setMission(battery, new Mission(aim, shot.getTargetId(), sequence, consecutive, locked));
        int distance = attacker.getArtilleryPosition().coords().distance(aim.coords());
        int arrival = game.getCurrentRound() + travelTurns(shot.artilleryType(), distance);
        int damage = damage(attacker, shot.artilleryType(), shot.ammo());
        if (shot.ammo() == SBFArtilleryAmmo.COPPERHEAD) {
            int base = 0;
            for (var unit : attacker.getUnits()) {
                for (var element : unit.getElements()) {
                    for (var tube : List.of(BattleForceSUA.ARTLT, BattleForceSUA.ARTS, BattleForceSUA.ARTT)) {
                        if (unit.carriesArtillery(tube, SBFArtilleryAmmo.COPPERHEAD) && element.hasSUA(tube)) {
                            base += tube == BattleForceSUA.ARTLT ? 3 : tube == BattleForceSUA.ARTS ? 2 : 1;
                        }
                    }
                }
            }
            damage = (base + 2) / 3;
        } else if (shot.ammo() == SBFArtilleryAmmo.HOMING) {
            damage = Math.round(damage * 2f / 3);
        } else if (shot.ammo() == SBFArtilleryAmmo.FLECHETTE) {
            damage *= 2;
        } else if (shot.ammo() == SBFArtilleryAmmo.CLUSTER) {
            damage = Math.max(0, damage - 1);
        }
        game.artillery().flights().add(new Flight(battery, aim, shot.getTargetId(), arrival, shot.homing() ? 4 : tn.getValue(),
              damage, (locked || preplotted) && (shot.getTargetId() == Player.PLAYER_NONE), sequence, shot.homing(),
              attacker.getArtilleryPosition(), shot.ammo()));
        gameManager().addReportFor(new SBFReportEntry(3130).add(attacker.generalName()).add(shot.artilleryType().toString())
              .add(aim.coords().getBoardNum()).add(arrival), attacker);
        setFinished();
    }

    static void resolveArrivals(SBFGameManager manager) {
        SBFGame game = manager.getGame();
        var flights = game.artillery().flights();
        for (Flight flight : List.copyOf(flights)) {
            if (flight.arrival() > game.getCurrentRound()) {
                continue;
            }
            SBFFormation attacker = SBFScenarioResolution.roster(game).stream()
                  .filter(f -> f.getId() == flight.battery().formation()).findFirst().orElse(null);
            if (attacker != null) {
                impact(manager, attacker, flight);
            }
            flights.remove(flight);
        }
        SBFCounterBattery.resolveArrivals(manager);
    }

    private static void impact(SBFGameManager manager, SBFFormation attacker, Flight flight) {
        SBFGame game = manager.getGame();
        if (flight.homing() && !game.artillery().paintedByFriendly(game, attacker.getOwnerId(), flight.target())) {
            manager.addReportFor(new SBFReportEntry(3132).add(flight.aim().coords().getBoardNum()), attacker);
            return;
        }
        SBFCounterBattery.observeImpact(manager, attacker, flight);
        int roll = flight.automatic() ? 0 : Compute.d6(2);
        boolean hit = flight.automatic() || (roll >= flight.targetNumber());
        manager.addReportFor(new SBFReportEntry(3131).add(flight.aim().coords().getBoardNum()).add(flight.targetNumber())
              .add(roll).add(hit ? "Hit" : "Miss"), attacker);
        SBFFormation target = game.getFormation(flight.target()).filter(f -> (flight.homing() && f.occupiesGroundHex()) || flight.aim().equals(f.getPosition())).orElse(null);
        if (target != null) {
            target.recordArtilleryAttack(attacker, game.getCurrentRound(), cruise(flight.battery().type()));
        }
        if (hit && (target != null)) {
            if (!applyAreaEffect(manager, attacker, flight.ammunition(), target.getPosition(), target)) {
                SBFCombatDamage.recordArtilleryHit(manager, attacker, target, flight.damage(), flight.ammunition() == SBFArtilleryAmmo.FLECHETTE, false);
            }
        } else if (hit && (flight.target() == Player.PLAYER_NONE)) {
            var mission = game.artillery().mission(flight.battery());
            if ((mission != null) && (mission.sequence() == flight.sequence())) {
                game.artillery().setMission(flight.battery(), new Mission(flight.aim(), mission.target(), mission.sequence(), mission.consecutive(), true));
            }
            if (!applyAreaEffect(manager, attacker, flight.ammunition(), flight.aim(), null)
                  && (flight.ammunition() != SBFArtilleryAmmo.FLECHETTE || game.getHex(flight.aim()).containsAnyTerrainOf(Terrains.JUNGLE, Terrains.WOODS))
                  && SBFTerrainConversion.damage(game, flight.aim(), flight.damage())) {
                manager.send(manager.getPacketHelper().createBoardsPacket());
            }
        } else if (!hit && !flight.homing()) {
            List<SBFFormation> friendly = game.getActiveFormations().stream().filter(SBFFormation::occupiesGroundHex)
                  .filter(f -> !game.areHostile(f, game.getPlayer(attacker.getOwnerId())))
                  .filter(f -> f.getPosition().isSameBoardAs(flight.aim())).toList();
            List<SBFFormation> nearby = friendly.stream().filter(f -> f.getPosition().equals(flight.aim())).toList();
            int threshold = 5;
            int damage = flight.damage();
            if (nearby.isEmpty()) {
                nearby = friendly.stream().filter(f -> f.getPosition().coords().distance(flight.aim().coords()) == 1).toList();
                threshold = 6;
                damage = (damage + 1) / 2;
            }
            if (!nearby.isEmpty() && (Compute.d6() >= threshold)) {
                SBFFormation struck = nearby.get(Compute.randomInt(nearby.size()));
                struck.recordArtilleryAttack(attacker, game.getCurrentRound(), cruise(flight.battery().type()));
                if (!applyAreaEffect(manager, attacker, flight.ammunition(), struck.getPosition(), struck)) {
                    SBFCombatDamage.recordArtilleryHit(manager, attacker, struck, damage, flight.ammunition() == SBFArtilleryAmmo.FLECHETTE, true);
                }
            }
        }
    }
    private static boolean applyAreaEffect(SBFGameManager manager, SBFFormation shooter, SBFArtilleryAmmo ammo,
          BoardLocation hex, SBFFormation target) {
        var game = manager.getGame();
        switch (ammo) {
            case THUNDER, THUNDER_ACTIVE -> SBFMinefieldControl.thunder(game, hex, shooter.getOwnerId(), ammo == SBFArtilleryAmmo.THUNDER_ACTIVE);
            case ILLUMINATION -> {
                game.getHex(hex).illuminateStrategic(game.getCurrentRound());
                manager.send(manager.getPacketHelper().createBoardsPacket());
            }
            case SMOKE -> {
                game.getHex(hex).addTerrain(new Terrain(Terrains.SMOKE, megamek.server.SmokeCloud.SMOKE_HEAVY));
                manager.send(manager.getPacketHelper().createBoardsPacket());
            }
            case INFERNO -> {
                if (target != null) {
                    target.hitByInferno(game.getCurrentRound());
                    manager.sendUnitUpdate(target);
                }
                for (var occupant : game.getActiveFormationsAt(hex)) {
                    SBFMinefieldControl.damageSusceptible(manager, occupant, shooter.getOwnerId());
                }
                SBFMinefieldControl.ignite(manager, hex, shooter.getOwnerId());
            }
            default -> { return false; }
        }
        return true;
    }
}
