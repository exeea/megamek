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
import java.util.Objects;
import java.util.stream.Stream;

import megamek.common.Player;
import megamek.common.game.InGameObject;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.server.scriptedEvents.DrawTriggeredEvent;
import megamek.server.scriptedEvents.TriggeredEvent;
import megamek.server.scriptedEvents.VictoryTriggeredEvent;
import megamek.server.trigger.TriggerSituation;
import megamek.server.victory.VictoryResult;

/** Ground pickup elimination and scenario-defined endings, IO:BF “Victory Conditions”, p. 174. */
final class SBFVictory {
    private SBFVictory() {
    }

    static VictoryResult atRoundEnd(SBFGame game) {
        List<TriggeredEvent> endings = game.scriptedEvents().stream().filter(TriggeredEvent::isGameEnding).toList();
        List<TriggeredEvent> met = endings.stream()
              .filter(event -> event.trigger().isTriggered(game, TriggerSituation.ROUND_END)).toList();
        VictoryResult fallback = endings.isEmpty() ? pickupResult(game)
              : met.isEmpty() ? VictoryResult.noResult() : VictoryResult.drawResult();
        if (!fallback.isVictory()) {
            return fallback;
        }
        for (TriggeredEvent event : game.scriptedEvents()) {
            if (!(event instanceof VictoryTriggeredEvent || event instanceof DrawTriggeredEvent)) {
                continue;
            }
            boolean triggered = event.isGameEnding() ? met.contains(event)
                  : event.trigger().isTriggered(game, TriggerSituation.ROUND_END);
            if (triggered) {
                if (event instanceof VictoryTriggeredEvent victory) {
                    VictoryResult result = victory.winner(game);
                    if (result.isVictory()) {
                        return result;
                    }
                } else {
                    return VictoryResult.drawResult();
                }
            }
        }
        return SBFScenarioResolution.pointsResult(game, fallback);
    }

    private static VictoryResult pickupResult(SBFGame game) {
        List<Player> participants = Stream.concat(game.getActiveFormations().stream(),
                    game.getGraveyard().stream().filter(SBFFormation.class::isInstance))
              .map(InGameObject::getOwnerId).distinct().map(game::getPlayer).filter(Objects::nonNull).toList();
        if (participants.isEmpty() || participants.stream().noneMatch(participants.getFirst()::isEnemyOf)) {
            return VictoryResult.noResult();
        }
        List<Player> survivors = game.getActiveFormations().stream().map(SBFFormation::getOwnerId).distinct()
              .map(game::getPlayer).filter(Objects::nonNull).toList();
        if (survivors.isEmpty()) {
            return VictoryResult.drawResult();
        }
        Player winner = survivors.getFirst();
        return survivors.stream().anyMatch(winner::isEnemyOf) ? VictoryResult.noResult()
              : VictoryResult.forPlayerOrTeam(winner);
    }
}
