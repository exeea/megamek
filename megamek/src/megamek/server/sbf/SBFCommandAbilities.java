/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import megamek.common.Player;
import megamek.common.compute.Compute;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFUnit.InitiativeAbility;

/** Special Command Abilities, IO:BF pp. 223–226, checked when the game starts. */
final class SBFCommandAbilities {

    private SBFCommandAbilities() {
    }

    /**
     * Sharp Shooters and Brawlers, IO:BF p. 225: "No more than one-third (rounding down) of all Formations may have this
     * ability." Abilities beyond the limit are removed, lowest Formation IDs keeping theirs, and the owner is told. The
     * two replace the same range modifiers, so a Formation authored with both keeps Sharp Shooters.
     */
    static void validateSetup(SBFGameManager manager) {
        Map<Integer, List<SBFFormation>> forces = manager.getGame().getActiveFormations().stream()
              .collect(Collectors.groupingBy(SBFFormation::getOwnerId));
        for (List<SBFFormation> force : forces.values()) {
            force.stream().filter(f -> f.isSharpShooters() && f.isBrawlers()).forEach(both -> {
                both.setBrawlers(false);
                manager.sendServerChat(both.getOwnerId(), "Formation " + both.getId()
                      + " loses Brawlers: it already has Sharp Shooters.");
            });
            limit(manager, force, SBFFormation::isSharpShooters, f -> f.setSharpShooters(false), "Sharp Shooters");
            limit(manager, force, SBFFormation::isBrawlers, f -> f.setBrawlers(false), "Brawlers");
        }
    }

    /**
     * Communications Disruption, IO:BF p. 226: "At the beginning of each turn, the player controlling a Force with the
     * Communications Disruption command ability rolls 1D6. If the result of this roll is 6, one random Formation of the
     * opposing force is affected ... The disruptive effects pass by the end of the turn." With Battlefield Intelligence
     * in play the Force needs "a BI ratio of 2:1 (or higher) over its opponent".
     */
    static void disruptCommunications(SBFGameManager manager) {
        disruptCommunications(manager, Compute::d6, Compute::randomInt);
    }

    static void disruptCommunications(SBFGameManager manager, IntSupplier d6, IntUnaryOperator pick) {
        SBFGame game = manager.getGame();
        game.getActiveFormations().forEach(formation -> formation.setCommunicationsDisrupted(false));
        if (game.getCurrentRound() < 1) {
            return;
        }
        List<Player> sides = SBFScenarioResolution.sides(game);
        for (Player side : sides) {
            var force = new SBFInitiativeModifiers(game, side);
            if (!force.hasAbility(InitiativeAbility.COMMUNICATIONS_DISRUPTION) || !intelligenceAllows(game, force, side, sides)
                  || (d6.getAsInt() != 6)) {
                continue;
            }
            List<SBFFormation> opponents = game.getActiveFormations().stream().filter(f -> game.areHostile(f, side))
                  .sorted(Comparator.comparingInt(SBFFormation::getId)).toList();
            if (!opponents.isEmpty()) {
                SBFFormation affected = opponents.get(pick.applyAsInt(opponents.size()));
                affected.setCommunicationsDisrupted(true);
                manager.sendServerChat(side.getId(), "Communications Disruption: " + affected.generalName()
                      + " has 4 MP less this turn.");
                manager.sendServerChat(affected.getOwnerId(), "Enemy Communications Disruption: " + affected.generalName()
                      + " has 4 MP less this turn.");
            }
        }
        manager.entityAllUpdate();
    }

    private static boolean intelligenceAllows(SBFGame game, SBFInitiativeModifiers force, Player side, List<Player> sides) {
        if (!game.getOptions().booleanOption(SBFRuleOptions.INIT_BATTLEFIELD_INT)) {
            return true;
        }
        int own = force.intelligenceRating();
        return (own > 0) && sides.stream().filter(side::isEnemyOf)
              .allMatch(other -> own >= 2 * new SBFInitiativeModifiers(game, other).intelligenceRating());
    }

    private static void limit(SBFGameManager manager, List<SBFFormation> force, Predicate<SBFFormation> has,
          Consumer<SBFFormation> remove, String ability) {
        List<SBFFormation> holders = force.stream().filter(has).sorted(Comparator.comparingInt(SBFFormation::getId))
              .toList();
        for (SBFFormation extra : holders.subList(Math.min(force.size() / 3, holders.size()), holders.size())) {
            remove.accept(extra);
            manager.sendServerChat(extra.getOwnerId(), "Formation " + extra.getId() + " loses " + ability
                  + ": no more than one-third of a Force's Formations may have it.");
        }
    }
}
