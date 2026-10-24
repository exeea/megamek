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
package megamek.client.ui.panels.phaseDisplay;

import java.util.List;

import jakarta.annotation.Nullable;
import megamek.client.ui.clientGUI.IClientGUI;
import megamek.client.ui.widget.MegaMekButton;
import megamek.common.ToHitData;
import megamek.common.actions.EntityAction;
import megamek.common.board.Coords;
import megamek.common.equipment.WeaponMounted;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;

/**
 * A phase display in which the local player declares weapon attacks for the selected unit: the Firing phase's
 * {@link FiringDisplay} and the Targeting and Off-board phases' {@link TargetingPhaseDisplay}. Other parts of the
 * client, such as the 3D board's weapons panel, read and change the attack queue through it, so a declaration made
 * there goes through the display's own rules, prompts and Fire button state, whichever phase it is.
 */
public interface WeaponDeclarationDisplay {

    /** @return the selected unit, or {@code null} without one */
    @Nullable
    Entity currentEntity();

    /** @return the client GUI the display belongs to */
    IClientGUI getClientGUI();

    /** @return {@code true} while the display ignores input, for example during its own updates */
    boolean isIgnoringEvents();

    /** @return the queued actions in fire order: a new list of the queue's own action objects */
    List<EntityAction> getAttacks();

    /**
     * Replaces the queue with the given actions in the given order, keeping each action object with its settings. No
     * prompt opens and the turn does not end. Does nothing without a selected unit.
     *
     * @param actions actions of the selected unit in the new fire order, each at most once
     */
    void replaceAttacks(List<EntityAction> actions);

    /**
     * Removes the queued action; a weapon attack makes its weapon usable again.
     *
     * @param action an action in the queue
     */
    void removeAttack(EntityAction action);

    /** Removes the last action when it is a weapon attack. */
    void removeLastFiring();

    /** @return the current target, or {@code null} without one */
    @Nullable
    Targetable getTarget();

    /**
     * Makes the target the current target and rolls the selected weapon against it.
     *
     * @param target the new target, or {@code null} for none
     */
    void target(@Nullable Targetable target);

    /** Rolls the selected weapon against the current target again and updates the Fire button. */
    void updateTarget();

    /**
     * @param weapon a weapon of the selected unit
     * @param target a target
     *
     * @return the to-hit; its description is the reason when the shot cannot be declared
     */
    ToHitData toHitFor(WeaponMounted weapon, Targetable target);

    /** @return {@code true} if Fire may declare the selected weapon at the current target now */
    boolean isFireAllowed();

    /** Declares the selected weapon at the current target, as the Fire button does. */
    void fire();

    /**
     * Selects the own unit that takes the turn, as a click on it does.
     *
     * @param entityId the unit's id
     */
    void selectEntity(int entityId);

    /**
     * Switches the selected weapon to its next or previous mode.
     *
     * @param forward {@code true} for the next mode
     */
    void changeMode(boolean forward);

    /**
     * Flips or unflips the selected unit's arms.
     *
     * @param armsFlipped {@code true} to flip them
     */
    void updateFlipArms(boolean armsFlipped);

    /**
     * Twists the selected unit's torso towards the hex.
     *
     * @param twistTarget the hex to twist towards
     */
    void torsoTwist(Coords twistTarget);

    /**
     * Twists the selected unit's torso one hex side.
     *
     * @param twistDirection 0 to twist left, 1 to twist right
     */
    void torsoTwist(int twistDirection);

    /** Clears the queue and every setting the turn made. */
    void clear();

    /** @return the display's Done button */
    MegaMekButton getButDone();

    /** @return the display's action buttons, each enabled while its command may run */
    List<MegaMekButton> getActionButtons();

    /**
     * @return the aimed shot handler, or {@code null} for a display that offers no aimed shots, as the Targeting phase
     *       does not
     */
    @Nullable
    AimedShotHandler getAimedShotHandler();
}
