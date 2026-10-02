/*
 * Copyright (C) 2015-2025 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.dialogs.unitDisplay;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import javax.swing.AbstractListModel;

import megamek.client.ui.Messages;
import megamek.common.annotations.Nullable;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;

/**
 * ListModel implementation that supports keeping track of a list of Mounted instantiations, how to display them in the
 * JList, and an ability to sort the Mounted given WeaponComparators.
 *
 * @author arlith
 */
public class WeaponListModel extends AbstractListModel<String> {
    @Serial
    private static final long serialVersionUID = 6312003196674512339L;

    private final WeaponPanel weaponPanel;
    /**
     * A collection of Mounted instantiations.
     */
    private final List<WeaponMounted> weapons;

    /**
     * The Entity that owns the collection of Mounted.
     */
    private final Entity entity;

    WeaponListModel(WeaponPanel weaponPanel, Entity entity) {
        this.weaponPanel = weaponPanel;
        this.entity = entity;
        weapons = new ArrayList<>();
    }

    /**
     * Add a new weapon to the list.
     *
     */
    public void addWeapon(WeaponMounted weaponMounted) {
        weapons.add(weaponMounted);
        fireIntervalAdded(this, weapons.size() - 1, weapons.size() - 1);
    }

    /**
     * Given the equipment (weapon) id, return the index in the (possibly sorted) list of Mounted.
     *
     */
    public int getIndex(int weaponId) {
        Mounted<?> mount = entity.getEquipment(weaponId);
        for (int i = 0; i < weapons.size(); i++) {
            if (weapons.get(i).equals(mount)) {
                return i;
            }
        }
        return -1;
    }

    public int getIndex(WeaponMounted weapon) {
        for (int i = 0; i < weapons.size(); i++) {
            if (weapons.get(i).equals(weapon)) {
                return i;
            }
        }
        return -1;
    }

    public void removeAllElements() {
        int numWeapons = weapons.size() - 1;
        weapons.clear();
        fireIntervalRemoved(this, 0, numWeapons);
    }

    /**
     * Swap the Mounted at the two specified index values.
     *
     */
    public void swapIdx(int idx1, int idx2) {
        // Bounds checking
        if ((idx1 >= weapons.size()) || (idx2 >= weapons.size())
              || (idx1 < 0) || (idx2 < 0)) {
            return;
        }
        WeaponMounted m1 = weapons.get(idx1);
        weapons.set(idx1, weapons.get(idx2));
        weapons.set(idx2, m1);
        fireContentsChanged(this, idx1, idx1);
        fireContentsChanged(this, idx2, idx2);
    }

    public WeaponMounted getWeaponAt(int index) {
        if (index < 0 || index >= weapons.size()) {
            return null;
        }
        return weapons.get(index);
    }

    /**
     * Given an index into the (possibly sorted) list of Mounted, return a text description. This consists of the
     * Mounted's description, as well as additional information like location, whether the Mounted is
     * shot/jammed/destroyed, etc. This is what the JList will display.
     */
    @Override
    public String getElementAt(int index) {
        Game game = null;
        if (weaponPanel.unitDisplayPanel.getClientGUI() != null) {
            game = weaponPanel.unitDisplayPanel.getClientGUI().getClient().getGame();
        }
        return rowParts(game, weapons.get(index)).text();
    }

    /**
     * The parts of one weapon's row in the weapon list, in their order.
     *
     * @param techTag     "(C) " or "(IS) " on a mixed-tech unit, else ""
     * @param desc        the weapon's description with its state mark ({@link Mounted#getDesc()})
     * @param riscModule  the short name of a linked RISC laser pulse module, else ""
     * @param location    the location, with the second location of a split weapon ("LT/CT")
     * @param loadedShots the shots of the loaded ammunition (0 while it dumps; a double one-shot launcher's usable
     *                    shots of its loaded munition), -1 when the row shows no shots
     * @param totalShots  the usable shots of every bin the weapon can switch to (a double one-shot launcher's original
     *                    shots of its loaded munition), -1 when the row shows no shots
     * @param rapidFire   whether a machine gun fires rapidly
     * @param hotLoaded   whether a launcher is hot-loaded
     * @param mode        the state name of the current mode, null for a weapon without modes
     * @param pendingMode the state name of the mode queued for the end of the turn, null when none is queued
     * @param chargeState a Bombast laser's charge state, null for other weapons
     * @param calledShot  the weapon's called shot, null while the TacOps called shots option is off
     */
    public record RowParts(String techTag, String desc, String riscModule, String location, int loadedShots,
          int totalShots, boolean rapidFire, boolean hotLoaded, @Nullable String mode, @Nullable String pendingMode,
          @Nullable String chargeState, @Nullable String calledShot) {

        /** The row's text in the weapon list. */
        public String text() {
            StringBuilder row = new StringBuilder(techTag).append(desc);
            if (!riscModule.isEmpty()) {
                row.append('+').append(riscModule);
            }
            row.append(" [").append(location).append(']');
            if (totalShots >= 0) {
                row.append(" (").append(loadedShots).append('/').append(totalShots).append(')');
            }
            if (rapidFire) {
                row.append(Messages.getString("MekDisplay.rapidFire"));
            }
            if (hotLoaded) {
                row.append(Messages.getString("MekDisplay.isHotLoaded"));
            }
            // Both describe what the weapon is set to, or will be set to, so they take the state label
            if (mode != null) {
                row.append(' ').append(mode);
                if (pendingMode != null) {
                    row.append(" (next turn, ").append(pendingMode).append(')');
                }
            }
            if (chargeState != null) {
                row.append(' ').append(chargeState);
            }
            if (calledShot != null) {
                row.append(' ').append(calledShot);
            }
            return row.toString();
        }
    }

    /**
     * The parts of one weapon's row in the weapon list; the game is the client's, null without a client. The Unit
     * Display and the GPU unit record both use it.
     */
    public static RowParts rowParts(@Nullable Game game, WeaponMounted mounted) {
        WeaponType weaponType = mounted.getType();
        Entity entityMounted = mounted.getEntity();
        Mounted<?> linkedBy = mounted.getLinkedBy();
        String riscModule = ((linkedBy != null) && (linkedBy.getType() instanceof MiscType)
              && linkedBy.getType().hasFlag(MiscType.F_RISC_LASER_PULSE_MODULE)) ? linkedBy.getShortName() : "";
        String location = entityMounted.getLocationAbbr(mounted.getLocation());
        if (mounted.isSplit()) {
            location += "/" + entityMounted.getLocationAbbr(mounted.getSecondLocation());
        }
        String techTag = entityMounted.isMixedTech() ? (weaponType.isClan() ? "(C) " : "(IS) ") : "";
        int loadedShots = -1;
        int totalShots = -1;
        if ((weaponType.getAmmoType() != AmmoType.AmmoTypeEnum.NA)
              && (!weaponType.hasFlag(WeaponType.F_ONE_SHOT) || weaponType.hasFlag(WeaponType.F_BA_INDIVIDUAL))
              && (weaponType.getAmmoType() != AmmoType.AmmoTypeEnum.INFANTRY)) {
            loadedShots = ((mounted.getLinked() != null) && !mounted.getLinked().isDumping())
                  ? mounted.getLinked().getUsableShotsLeft() : 0;
            totalShots = entityMounted.getTotalMunitionsOfType(mounted);
        } else if (weaponType.hasFlag(WeaponType.F_DOUBLE_ONE_SHOT)
              || (entityMounted.isSupportVehicle() && (weaponType.getAmmoType() == AmmoType.AmmoTypeEnum.INFANTRY))) {
            loadedShots = 0;
            totalShots = 0;
            EnumSet<AmmoType.Munitions> munition = ((AmmoType) mounted.getLinked().getType()).getMunitionType();
            for (Mounted<?> current = mounted.getLinked(); current != null; current = current.getLinked()) {
                if (((AmmoType) current.getType()).getMunitionType().equals(munition)) {
                    loadedShots += current.getUsableShotsLeft();
                    totalShots += current.getOriginalShots();
                }
            }
        }
        boolean modes = mounted.hasModes();
        String mode = modes ? mounted.curMode().getStateName(weaponType) : null;
        String pendingMode = (modes && !mounted.pendingMode().equals(Mounted.MODE_NONE))
              ? mounted.pendingMode().getStateName(weaponType) : null;
        String chargeState = weaponType.hasFlag(WeaponType.F_BOMBAST_LASER)
              ? mounted.getChargeState().getDescription() : null;
        String calledShot = ((game != null)
              && game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_CALLED_SHOTS))
              ? mounted.getCalledShot().getDisplayableName() : null;
        return new RowParts(techTag, mounted.getDesc(), riscModule, location, loadedShots, totalShots,
              mounted.isRapidFire(), mounted.isHotLoaded(), mode, pendingMode, chargeState, calledShot);
    }

    /**
     * Returns the number of Mounted in the list.
     */
    @Override
    public int getSize() {
        return weapons.size();
    }

    /**
     * Sort the Mounted, generally using a WeaponComparator.
     *
     */
    public void sort(Comparator<WeaponMounted> comparator) {
        weapons.sort(comparator);
        fireContentsChanged(this, 0, weapons.size() - 1);
    }
}
