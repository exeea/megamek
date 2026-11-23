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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import megamek.common.Player;
import megamek.common.actions.sbf.SBFCounterBatteryAttack;
import megamek.common.board.BoardLocation;
import megamek.common.compute.Compute;
import megamek.common.strategicBattleSystems.SBFArtilleryState.Battery;
import megamek.common.strategicBattleSystems.SBFArtilleryState.CounterContact;
import megamek.common.strategicBattleSystems.SBFArtilleryState.Flight;
import megamek.common.strategicBattleSystems.SBFArtilleryState.ReturnFlight;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFScenarioProgress;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;

/** Counter-battery acquisition and return fire, IO:BF pp. 204–205. */
final class SBFCounterBattery {
    private static int side(SBFGame game, int owner) {
        return SBFScenarioProgress.sideId(game, game.getPlayer(owner));
    }

    static void observeImpact(SBFGameManager manager, SBFFormation shooter, Flight flight) {
        SBFGame game = manager.getGame();
        BoardLocation origin = flight.origin() == null ? shooter.getArtilleryPosition() : flight.origin();
        if ((origin == null) || !origin.isSameBoardAs(flight.aim())) {
            return;
        }
        Set<Integer> observed = new HashSet<>();
        int distance = origin.coords().distance(flight.aim().coords());
        for (Player viewer : game.getPlayersList()) {
            int side = side(game, viewer.getId());
            if (viewer.isEnemyOf(game.getPlayer(shooter.getOwnerId())) && observed.add(side)) {
                List<Player> allies = game.getPlayersList().stream().filter(p -> !p.isEnemyOf(viewer)).toList();
                if (!shooter.isOffMapArtillery() && allies.stream().anyMatch(p -> game.getVisibility(p, shooter.getId()).isLocated())) {
                    continue;
                }
                List<SBFFormation> observers = game.getActiveFormations().stream().filter(SBFFormation::occupiesGroundHex)
                      .filter(f -> !game.areHostile(f, viewer)).toList();
                if (observers.stream().noneMatch(f -> f.getPosition().isSameBoardAs(flight.aim())
                      && (!game.usesDoubleBlind() || f.getPosition().coords().distance(flight.aim().coords()) <= SBFDetectionHelper.visualRange(game, f, flight.aim()))
                      && game.combatOrders().hasLineOfSight(game, f.getPosition(), flight.aim(), false))) {
                    continue;
                }
                boolean occupied = observers.stream().anyMatch(f -> flight.aim().equals(f.getPosition()));
                CounterContact contact = game.artillery().observe(side, flight.battery(), game.getCurrentRound(),
                      flight.aim().coords().direction(origin.coords()), distance,
                      SBFArtillery.travelTurns(flight.battery().type(), distance), occupied);
                for (Player ally : allies) {
                    manager.sendServerChat(ally.getId(), describe(contact));
                }
            }
        }
    }

    private static String describe(CounterContact contact) {
        String bearing = List.of("N", "NE", "SE", "S", "SW", "NW").get(contact.direction());
        int start = (contact.distance() / 5) * 5;
        return "Artillery contact " + contact.number() + ", " + contact.source().type() + ", " + bearing
              + ", estimated " + start + "–" + (start + 4) + " hexes from impact. Counter-fire from round "
              + (contact.firstRound() + 1) + ", observed impact modifier −" + contact.subsequentOccupiedImpacts() + ".";
    }

    static void list(SBFGameManager manager, int owner) {
        for (CounterContact contact : manager.getGame().artillery().contacts(side(manager.getGame(), owner))) {
            manager.sendServerChat(owner, describe(contact));
        }
    }

    private static CounterContact contact(SBFGame game, int owner, int number) {
        return game.artillery().contacts(side(game, owner)).stream().filter(c -> c.number() == number).findFirst().orElse(null);
    }

    static boolean canFire(SBFGame game, SBFFormation attacker, SBFCounterBatteryAttack shot) {
        CounterContact contact = contact(game, attacker.getOwnerId(), shot.contact());
        if ((contact == null) || (contact.firstRound() >= game.getCurrentRound()) || attacker.isMoraleRestricted()
              || !attacker.getEngagedOpponents().isEmpty()
              || (SBFArtillery.maxRange(shot.artilleryType()) < SBFArtillery.maxRange(contact.source().type()))) {
            return false;
        }
        SBFFormation target = game.getFormation(contact.source().formation()).orElse(null);
        return (target != null) && (target.isOffMapArtillery() || !game.getVisibility(game.getPlayer(attacker.getOwnerId()),
              target.getId()).isLocated());
    }

    static void launch(SBFGame game, SBFFormation attacker, SBFCounterBatteryAttack shot) {
        CounterContact contact = contact(game, attacker.getOwnerId(), shot.contact());
        if (contact == null) {
            return;
        }
        game.artillery().returnFlights().add(new ReturnFlight(new Battery(attacker.getId(), shot.artilleryType()), contact.source(),
              game.getCurrentRound() + contact.flightTurns(), attacker.getSkill() + 7 + (attacker.ambushedIn(game.getCurrentRound()) ? 2 : 0) + (attacker.disruptedByEmp(game.getCurrentRound()) ? 2 : 0) - contact.subsequentOccupiedImpacts(),
              SBFArtillery.damage(attacker, shot.artilleryType())));
    }

    static void resolveArrivals(SBFGameManager manager) {
        SBFGame game = manager.getGame();
        for (ReturnFlight shell : List.copyOf(game.artillery().returnFlights())) {
            if (shell.arrival() > game.getCurrentRound()) {
                continue;
            }
            game.artillery().returnFlights().remove(shell);
            var target = game.getFormation(shell.target().formation()).orElse(null);
            var attacker = SBFScenarioResolution.roster(game).stream().filter(f -> f.getId() == shell.shooter().formation())
                  .findFirst().orElse(null);
            if (target == null || attacker == null) {
                continue;
            }
            int roll = Compute.d6(2);
            int damage = roll > shell.targetNumber() ? shell.damage() : roll == shell.targetNumber() ? shell.damage() / 2 : 0;
            manager.addReportFor(new SBFReportEntry(3133).add(shell.targetNumber()).add(roll).add(damage), attacker);
            target.recordArtilleryAttack(attacker, game.getCurrentRound(), SBFArtillery.cruise(shell.shooter().type()));
            if (damage > 0) {
                // The identified battery is hit. Older rosters may store ratings only at Formation level.
                List<Integer> units = java.util.stream.IntStream.range(0, target.getUnits().size())
                      .filter(i -> target.getUnits().get(i).hasSUA(shell.target().type())).boxed().toList();
                if (!units.isEmpty()) {
                    SBFCombatDamage.recordHit(manager, attacker, target, units.get(Compute.randomInt(units.size())), damage,
                          LossCause.ARTILLERY_OR_BOMB);
                } else if (!target.getUnits().isEmpty()) {
                    SBFCombatDamage.recordRandomHit(manager, attacker, target, damage, LossCause.ARTILLERY_OR_BOMB);
                }
            }
        }
    }
}
