/*
 * Copyright (C) 2020-2025 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.clientGUI.boardview.overlay;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.dialogs.phaseDisplay.TargetChoiceDialog;
import megamek.client.ui.panels.phaseDisplay.TargetingPhaseDisplay;
import megamek.common.HexTarget;
import megamek.common.OffBoardDirection;
import megamek.common.Player;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Coords;
import megamek.common.compute.ComputeArc;
import megamek.common.equipment.Mounted;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;
import megamek.common.weapons.bayWeapons.BayWeapon;

/**
 * The rules of off-board artillery targets: which board edges offer an attack of the selected artillery weapon on an
 * observed enemy beyond them, and that attack. The 3D board's dock offers the edges as commands.
 */
public class OffBoardTargetOverlay {
    private final ClientGUI clientGUI;
    private static final GUIPreferences GUIP = GUIPreferences.getInstance();

    private Game game() {
        return clientGUI.getClient().getGame();
    }

    private Player getCurrentPlayer() {
        return clientGUI.getClient().getLocalPlayer();
    }

    public OffBoardTargetOverlay(ClientGUI clientGUI) {
        this.clientGUI = clientGUI;
    }

    /**
     * Logic that determines if this overlay should be visible.
     */
    private boolean shouldBeVisible() {
        // only relevant if it's our turn in the targeting phase
        boolean visible = clientGUI.getClient().isMyTurn() && game().getPhase().isTargeting();

        if (!visible) {
            return false;
        }

        Mounted<?> selectedArtilleryWeapon = clientGUI.getCurrentBoardState()
              .map(bv -> bv.getSelectedArtilleryWeapon())
              .orElse(null);

        // only relevant if we've got an artillery weapon selected for one of our own
        // units
        if (selectedArtilleryWeapon == null) {
            return false;
        }
        // Bay weapons cannot fire homing artillery rounds (currently)
        // TODO: revisit when aero errata is implemented
        if (selectedArtilleryWeapon.getType() instanceof BayWeapon) {
            return false;
        }

        // the artillery weapon needs to be using non-homing ammo
        Mounted<?> ammo = selectedArtilleryWeapon.getLinked();
        if (!(ammo == null) && ammo.isHomingAmmoInHomingMode()) {
            return false;
        }

        // only show these if there are any actual enemy units eligible for off board
        // targeting
        for (OffBoardDirection direction : OffBoardDirection.values()) {
            if (showDirectionalElement(direction, selectedArtilleryWeapon)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Logic that determines whether to show a specific directional indicator
     */
    private boolean showDirectionalElement(OffBoardDirection direction, Mounted<?> selectedArtilleryWeapon) {
        if (!(clientGUI.getCurrentPanel() instanceof TargetingPhaseDisplay targetingPhaseDisplay)) {
            return false;
        }

        for (Entity entity : game().getAllOffboardEnemyEntities(getCurrentPlayer())) {
            if (entity.isOffBoardObserved(getCurrentPlayer().getTeam()) &&
                  (entity.getOffBoardDirection() == direction) &&
                  (targetingPhaseDisplay.currentEntity() != null &&
                        targetingPhaseDisplay.currentEntity().isOffBoard() ||
                        weaponFacingInDirection(selectedArtilleryWeapon, direction))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Worker function that determines if the given weapon is facing in the correct off-board direction.
     */
    private boolean weaponFacingInDirection(Mounted<?> artilleryWeapon, OffBoardDirection direction) {
        Coords checkCoords = artilleryWeapon.getEntity().getPosition();
        int translationDistance;

        // little hack: we project a point 10 hexes off board to the
        // north/south/east/west and declare a hex target with it
        // then use Compute.isInArc, as that takes into account all the logic including
        // torso/turret twists.
        switch (direction) {
            case NORTH:
                checkCoords = checkCoords.translated(0, checkCoords.getY() + 10);
                break;
            case SOUTH:
                checkCoords = checkCoords.translated(3,
                      game().getBoard().getHeight() - checkCoords.getY() + 10);
                break;
            case EAST:
                translationDistance = ((game().getBoard().getWidth() - checkCoords.getX()) / 2) + 5;
                checkCoords = checkCoords.translated(1, translationDistance).translated(2, translationDistance);
                break;
            case WEST:
                translationDistance = checkCoords.getX() + 5;
                checkCoords = checkCoords.translated(4, translationDistance).translated(5, translationDistance);
                break;
            default:
                return false;
        }

        Targetable checkTarget = new HexTarget(checkCoords, Targetable.TYPE_HEX_ARTILLERY);

        return ComputeArc.isInArc(game(), artilleryWeapon.getEntity().getId(),
              artilleryWeapon.getEntity().getEquipmentNum(artilleryWeapon), checkTarget);
    }

    /**
     * The board edges whose arrows show now: in the local player's targeting turn, for the selected artillery weapon,
     * each edge with an observed enemy unit beyond it that the weapon can fire at.
     */
    public List<OffBoardDirection> shownDirections() {
        return shouldBeVisible() ? directions(clientGUI.getBoardState().getSelectedArtilleryWeapon()) : List.of();
    }

    /**
     * A click on the arrow of {@code direction} while it shows, as a press on that arrow runs it: an attack of the
     * selected artillery weapon on the unit beyond that edge, chosen from a list when there are several.
     */
    public void target(OffBoardDirection direction) {
        if (shownDirections().contains(direction)) {
            handleButtonClick(direction);
        }
    }

    /** The edges whose arrows show for this artillery weapon. */
    private List<OffBoardDirection> directions(Mounted<?> weapon) {
        return Stream.of(OffBoardDirection.values())
              .filter(direction -> direction != OffBoardDirection.NONE && showDirectionalElement(direction, weapon))
              .toList();
    }

    /**
     * Runs the attack beyond {@code direction}: shows a target choice when several units are there, then feeds an
     * artillery attack action back to the targeting display.
     */
    private void handleButtonClick(OffBoardDirection direction) {
        if (!(clientGUI.getCurrentPanel() instanceof TargetingPhaseDisplay targetingPhaseDisplay)) {
            return;
        }

        List<Targetable> eligibleTargets = new ArrayList<>();

        for (Entity ent : this.game().getAllOffboardEnemyEntities(getCurrentPlayer())) {
            if (ent.getOffBoardDirection() == direction &&
                  ent.isOffBoardObserved(getCurrentPlayer().getTeam())) {
                eligibleTargets.add(ent);
            }
        }

        Targetable choice;

        if (eligibleTargets.size() > 1) {
            // If we have multiple choices, display a selection dialog.
            choice = TargetChoiceDialog.showSingleChoiceDialog(clientGUI.getFrame(),
                  "FiringDisplay.ChooseTargetDialog.title",
                  Messages.getString("FiringDisplay.ChooseCounterbatteryTargetDialog.message"),
                  eligibleTargets, clientGUI, null);
        } else if ((eligibleTargets.size() == 1) && (eligibleTargets.getFirst() != null)) {
            choice = eligibleTargets.getFirst();
        } else {
            return;
        }

        if (choice != null) {
            // display dropdown containing all observed offboard enemy entities in given direction upon selection,
            // generate an ArtilleryAttackAction vs selected entity as per TargetingPhaseDisplay, like so:
            WeaponAttackAction weaponAttackAction = new ArtilleryAttackAction(targetingPhaseDisplay.currentEntity()
                  .getId(),
                  choice.getTargetType(),
                  choice.getId(),
                  targetingPhaseDisplay.currentEntity()
                        .getEquipmentNum(clientGUI.getBoardState().getSelectedArtilleryWeapon()),
                  clientGUI.getClient().getGame());

            // Only add if chance of success.
            // TODO: properly display any toHit "IMPOSSIBLE" reasons
            if (!weaponAttackAction.toHit(game(), true).cannotSucceed()) {
                Mounted<?> selectedArtilleryWeapon = clientGUI.getBoardState().getSelectedArtilleryWeapon();

                if (selectedArtilleryWeapon != null) {
                    targetingPhaseDisplay.updateDisplayForPendingAttack(selectedArtilleryWeapon, weaponAttackAction);
                }
            }
        }
    }
}
