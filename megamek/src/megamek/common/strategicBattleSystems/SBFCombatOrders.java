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
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;

import megamek.common.Hex;
import megamek.common.alphaStrike.ASRange;
import megamek.common.board.BoardLocation;
import megamek.common.units.Terrains;

/** The opposed Maneuver results and occupant LOS decisions for the current combat turn (IO:BF p. 169). */
public final class SBFCombatOrders implements Serializable {
    public record Maneuver(int first, int second, int winner, int outmaneuvered, ASRange range) implements Serializable {
        boolean matches(int a, int b) {
            return ((first == a) && (second == b)) || ((first == b) && (second == a));
        }
    }

    private record Sight(BoardLocation from, BoardLocation to, int occupant, int opponent, boolean open,
          boolean fired) implements Serializable { }

    private record Designation(int spotter, int target) implements Serializable { }

    private final List<Designation> designations = new ArrayList<>();

    public Optional<Integer> spottedTarget(int spotter) {
        return designations.stream().filter(d -> d.spotter() == spotter).map(Designation::target).findFirst();
    }

    public void setSpotter(int spotter, int target) {
        designations.removeIf(d -> d.spotter() == spotter);
        designations.add(new Designation(spotter, target));
    }

    private final int round;
    private final List<Maneuver> maneuvers = new ArrayList<>();
    private final List<Sight> sight = new ArrayList<>();

    public SBFCombatOrders(int round) {
        this.round = round;
    }

    public int round() {
        return round;
    }

    public Optional<Maneuver> maneuver(int a, int b) {
        return maneuvers.stream().filter(m -> m.matches(a, b)).findFirst();
    }

    public void setManeuver(Maneuver maneuver) {
        maneuvers.removeIf(m -> m.matches(maneuver.first(), maneuver.second()));
        maneuvers.add(maneuver);
    }

    public boolean isBehind(SBFFormation attacker, SBFFormation target) {
        return maneuver(attacker.getId(), target.getId()).map(m -> m.outmaneuvered() == target.getId()).orElse(false);
    }

    public int standardAttackLimit(SBFFormation formation) {
        int count = formation.getUnits().size();
        return isOutmaneuvered(formation.getId()) ? (count + 1) / 2 : count;
    }

    public boolean isOutmaneuvered(int formation) {
        return maneuvers.stream().anyMatch(m -> m.outmaneuvered() == formation);
    }

    public static boolean sameEngagementArea(SBFGame game, SBFFormation first, SBFFormation second) {
        if (first.isOnRadarMap() || second.isOnRadarMap()) {
            // A defender may pass through the contested zone. Resolve the attack at the recorded encounter (p. 177).
            return first.isOnRadarMap() && second.isOnRadarMap() && ((first.getRadarZone() == second.getRadarZone())
                  || first.passesAirOpponent(second.getId()) || second.passesAirOpponent(first.getId()));
        }
        return game.onSameBoard(first, second) && first.getPosition().equals(second.getPosition());
    }

    /** IO:BF p. 169. Evaluate terrain at the proposed hexes, including movement destinations not yet occupied. */
    private enum SightControl {
        OPEN, BLOCKED, OBSERVER, TARGET, BOTH
    }

    private static SightControl sightControl(SBFGame game, BoardLocation observer, BoardLocation target) {
        if ((observer == null) || !observer.isSameBoardAs(target)) {
            return SightControl.BLOCKED;
        }
        if (observer.coords().distance(target.coords()) != 1) {
            return SightControl.OPEN;
        }
        Hex from = game.getHex(observer);
        Hex to = game.getHex(target);
        if ((from == null) || (to == null)) {
            return SightControl.OPEN;
        }
        boolean fromCover = SBFToHitData.isLosCover(from);
        boolean toCover = SBFToHitData.isLosCover(to);
        if (fromCover && toCover) {
            return SightControl.BLOCKED;
        }
        boolean fromControls = (from.getLevel() >= to.getLevel() + 2) || (fromCover && clearForSight(to));
        boolean toControls = (to.getLevel() >= from.getLevel() + 2) || (toCover && clearForSight(from));
        if (fromControls && toControls) {
            return SightControl.BOTH;
        }
        return fromControls ? SightControl.OBSERVER : toControls ? SightControl.TARGET : SightControl.OPEN;
    }

    private static boolean clearForSight(Hex hex) {
        return hex.isClear() || hex.containsTerrain(Terrains.WATER) || hex.hasPavement();
    }

    public static boolean controlsSight(SBFGame game, SBFFormation occupant, SBFFormation other) {
        SightControl control = sightControl(game, occupant.getPosition(), other.getPosition());
        return (control == SightControl.OBSERVER) || (control == SightControl.BOTH);
    }

    /** Declaring fire establishes the firing occupant's own LOS decision. Passive observation uses existing decisions. */
    public boolean hasLineOfSight(SBFGame game, BoardLocation observer, BoardLocation target, boolean declaringFire) {
        return switch (sightControl(game, observer, target)) {
            case OPEN -> true;
            case BLOCKED -> false;
            case OBSERVER -> declaringFire || grantsSight(observer, target);
            case TARGET -> grantsSight(target, observer);
            case BOTH -> (declaringFire || grantsSight(observer, target)) && grantsSight(target, observer);
        };
    }

    public boolean hasSightDecision(SBFFormation occupant, SBFFormation other) {
        return sight.stream().anyMatch(s -> s.from().equals(occupant.getPosition()) && s.to().equals(other.getPosition()));
    }

    private boolean grantsSight(BoardLocation occupant, BoardLocation other) {
        return sight.stream().anyMatch(s -> s.from().equals(occupant) && s.to().equals(other) && s.open());
    }

    public boolean setSight(SBFFormation occupant, SBFFormation other, boolean open, boolean fired) {
        var from = occupant.getPosition();
        var to = other.getPosition();
        if (sight.stream().anyMatch(s -> s.from().equals(from) && s.to().equals(to) && s.fired())) {
            return open;
        }
        sight.removeIf(s -> s.from().equals(from) && s.to().equals(to));
        sight.add(new Sight(from, to, occupant.getId(), other.getId(), open, fired));
        return true;
    }

    /** Only send decisions for contacts already known to the recipient. */
    public SBFCombatOrders visibleTo(IntPredicate located, IntPredicate fullyVisible) {
        SBFCombatOrders view = new SBFCombatOrders(round);
        maneuvers.stream().filter(m -> located.test(m.first()) && located.test(m.second())).forEach(view.maneuvers::add);
        sight.stream().filter(s -> located.test(s.occupant()) && located.test(s.opponent())).forEach(view.sight::add);
        designations.stream().filter(d -> fullyVisible.test(d.spotter()) && located.test(d.target()))
              .forEach(view.designations::add);
        return view;
    }
}
