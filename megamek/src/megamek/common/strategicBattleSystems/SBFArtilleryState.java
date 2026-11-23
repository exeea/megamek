/*
 * Copyright (C) 2021-2025 The MegaMek Team. All Rights Reserved.
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

package megamek.common.strategicBattleSystems;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;

/** Artillery fire missions and ammunition still in flight across turns and save/load, IO:BF pp. 203-204. */
public final class SBFArtilleryState implements Serializable {
    public record Battery(int formation, BattleForceSUA type) implements Serializable { }
    public record Mission(BoardLocation aim, int target, int sequence, int consecutive, boolean locked) implements Serializable { }
    public record Flight(Battery battery, BoardLocation aim, int target, int arrival, int targetNumber,
          int damage, boolean automatic, int sequence, boolean homing, BoardLocation origin, SBFArtilleryAmmo ammo) implements Serializable {
        public Flight(Battery battery, BoardLocation aim, int target, int arrival, int targetNumber,
              int damage, boolean automatic, int sequence, boolean homing, BoardLocation origin) {
            this(battery, aim, target, arrival, targetNumber, damage, automatic, sequence, homing, origin, null);
        }

        public SBFArtilleryAmmo ammunition() {
            return ammo == null ? (homing ? SBFArtilleryAmmo.HOMING : SBFArtilleryAmmo.STANDARD) : ammo;
        }
    }

    public record CounterContact(int number, Battery source, int firstRound, int direction, int distance,
          int flightTurns, int subsequentOccupiedImpacts) implements Serializable { }
    public record ReturnFlight(Battery shooter, Battery target, int arrival, int targetNumber, int damage)
          implements Serializable { }
    private Map<Integer, List<CounterContact>> counterContacts = new HashMap<>();
    private List<ReturnFlight> returns = new ArrayList<>();

    public List<ReturnFlight> returnFlights() {
        if (returns == null) {
            returns = new ArrayList<>();
        }
        return returns;
    }

    public List<CounterContact> contacts(int side) {
        return counterContacts == null ? List.of() : List.copyOf(counterContacts.getOrDefault(side, List.of()));
    }

    /** Contacts are private to the observing Force. The public number never identifies the hidden Formation. */
    public CounterContact observe(int side, Battery source, int round, int direction, int distance, int turns, boolean occupied) {
        if (counterContacts == null) {
            counterContacts = new HashMap<>();
        }
        List<CounterContact> records = counterContacts.computeIfAbsent(side, ignored -> new ArrayList<>());
        for (int i = 0; i < records.size(); i++) {
            CounterContact old = records.get(i);
            if (old.source().equals(source)) {
                CounterContact updated = new CounterContact(old.number(), source, old.firstRound(), direction, distance,
                      turns, Math.min(4, old.subsequentOccupiedImpacts() + (occupied ? 1 : 0)));
                records.set(i, updated);
                return updated;
            }
        }
        CounterContact created = new CounterContact(records.size() + 1, source, round, direction, distance, turns, 0);
        records.add(created);
        return created;
    }

    /** An actual successful anti-air hit pauses Combat until its Tactics winner chooses a Flight. */
    public record AirHit(int attacker, int target, int chooser, int damage) implements Serializable { }
    private List<AirHit> airHits = new ArrayList<>();

    public List<AirHit> airHits() {
        if (airHits == null) {
            airHits = new ArrayList<>();
        }
        return airHits;
    }

    private Map<Integer, Set<BoardLocation>> scenarioPreplots = new HashMap<>();

    public void setScenarioPreplot(int side, BoardLocation location) {
        if (scenarioPreplots == null) {
            scenarioPreplots = new HashMap<>();
        }
        scenarioPreplots.computeIfAbsent(side, ignored -> new HashSet<>()).add(location);
    }

    public boolean scenarioPreplotted(int side, BoardLocation location) {
        return scenarioPreplots != null && scenarioPreplots.getOrDefault(side, Set.of()).contains(location);
    }

    private final List<Flight> flights = new ArrayList<>();
    private final Map<Battery, Mission> missions = new HashMap<>();

    private final Map<BoardLocation, Integer> terrainDamage = new HashMap<>();

    private Map<BoardLocation, Map<Integer, Integer>> passageWork;

    public int addPassageWork(BoardLocation site, int passage, int amount) {
        if (passageWork == null) {
            passageWork = new HashMap<>();
        }
        return passageWork.computeIfAbsent(site, unused -> new HashMap<>()).merge(passage, amount, Integer::sum);
    }

    public int terrainDamage(BoardLocation hex) {
        return terrainDamage.getOrDefault(hex, 0);
    }

    /** A converted feature yields a fresh terrain factor for the next exposed layer (IO:BF p. 221). */
    public int damageTerrain(BoardLocation hex, int damage) {
        return terrainDamage.merge(hex, damage, Integer::sum);
    }

    public void convertedTerrain(BoardLocation hex) {
        terrainDamage.remove(hex);
        if (passageWork != null) {
            passageWork.remove(hex);
        }
    }

    private record Paint(int owner, int target) implements Serializable { }
    private Set<Paint> painted = new HashSet<>();
    private int paintRound;

    public void paint(int round, int owner, int target) {
        if ((painted == null) || (round != paintRound)) {
            painted = new HashSet<>();
            paintRound = round;
        }
        painted.add(new Paint(owner, target));
    }

    public boolean paintedByFriendly(SBFGame game, int owner, int target) {
        return (game.getCurrentRound() == paintRound) && (painted != null) && painted.stream().anyMatch(mark ->
              (mark.target() == target) && !game.getPlayer(owner).isEnemyOf(game.getPlayer(mark.owner())));
    }

    public List<Flight> flights() {
        return flights;
    }

    public Mission mission(Battery battery) {
        return missions.get(battery);
    }

    public void setMission(Battery battery, Mission mission) {
        missions.put(battery, mission);
    }
}
