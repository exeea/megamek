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

import java.util.function.Predicate;

import megamek.common.actions.EntityAction;
import megamek.common.actions.sbf.SBFArtilleryAttack;
import megamek.common.actions.sbf.SBFAttackAction;
import megamek.common.actions.sbf.SBFStandardUnitAttack;

public record SBFActionsProcessor(SBFGameManager gameManager) implements SBFGameManagerHelper {

    boolean handleActions() {
        if (game().getActionHandlers().isEmpty()) {
            addNewHandlers();
        }
        processHandlers(action -> action instanceof SBFStandardUnitAttack shot
              && shot.getWeaponMode() == SBFStandardUnitAttack.WeaponMode.TAG);
        processHandlers(SBFArtilleryAttack.class::isInstance);
        if (!game().artillery().airHits().isEmpty()) {
            return false;
        }
        SBFArtillery.resolveArrivals(gameManager);
        processHandlers(action -> true);
        SBFTerrainAttackHandler.resolveClearing(gameManager);
        removeFinishedHandlers();
        return true;
    }

    private void addNewHandlers() {
        for (EntityAction action : game().getActionsVector()) {
            if (action instanceof SBFAttackAction attack) {
                var handler = attack.getHandler(gameManager);
                if (handler != null) {
                    game().addActionHandler(handler);
                }
            }
            // Actions that aren't attacks are currently not processed through handlers but maybe in the future?
        }
    }

    private void processHandlers(Predicate<EntityAction> select) {
        for (SBFActionHandler handler : game().getActionHandlers()) {
            if (!game().artillery().airHits().isEmpty()) {
                return;
            }
            if (!handler.isFinished() && select.test(handler.getAction()) && handler.cares()) {
                handler.handle();
            }
        }
    }

    private void removeFinishedHandlers() {
        game().getActionHandlers().removeIf(SBFActionHandler::isFinished);
    }
}
