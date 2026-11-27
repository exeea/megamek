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

package megamek.server.trigger;

import megamek.common.game.IGame;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFScenarioProgress;

/** Evaluates a named Strategic BattleForce objective after capture (IO:BF p. 174). */
public record SBFObjectiveCapturedTrigger(String objective, String player) implements Trigger {
    public SBFObjectiveCapturedTrigger {
        if (objective.isBlank()) {
            throw new IllegalArgumentException("An objective marker name is required.");
        }
    }

    @Override
    public boolean isTriggered(IGame game, TriggerSituation situation) {
        if (!(game instanceof SBFGame sbf)) {
            return false;
        }
        if (player.isBlank()) {
            return sbf.scenarioProgress().hasCaptured(objective, null);
        }
        return sbf.getPlayersList().stream().filter(p -> player.equals(p.getName()))
              .anyMatch(p -> sbf.scenarioProgress().hasCaptured(objective, SBFScenarioProgress.sideId(sbf, p)));
    }
}
