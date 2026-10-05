/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.unitDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.tooltip.UnitToolTip;
import megamek.common.ToHitData;
import megamek.common.annotations.Nullable;
import megamek.common.compute.Compute;
import megamek.common.enums.WeaponSortOrder;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;

/**
 * Client-thread-owned inspected unit, weapon selection and firing presentation. Both board interfaces use this
 * state; it has no Swing controls. Game rules and equipment changes remain with the entity and existing commands.
 */
public final class UnitDisplayState {
    private final ClientGUI gui;
    private final List<Runnable> listeners = new ArrayList<>();
    private final List<BiConsumer<WeaponMounted, WeaponMounted>> weaponListeners = new ArrayList<>();
    private Entity entity;
    private Entity weaponEntity;
    private WeaponMounted selectedWeapon;
    private WeaponMounted bayWeapon;
    private AmmoMounted selectedAmmo;
    private Targetable target;
    private Targetable prevTarget;
    private String targetExtra = "";
    private String range = "---";
    private String toHit = "---";

    public UnitDisplayState(@Nullable ClientGUI gui) {
        this.gui = gui;
    }

    public Entity getCurrentEntity() { return entity; }
    public Entity getWeaponEntity() { return weaponEntity; }
    public int getSelectedEntityId() { return weaponEntity == null ? Entity.NONE : weaponEntity.getId(); }
    public WeaponMounted getSelectedWeapon() { return selectedWeapon; }
    public WeaponMounted getBayWeapon() { return bayWeapon; }
    public Optional<AmmoMounted> getSelectedAmmo() { return Optional.ofNullable(selectedAmmo); }
    public Targetable getPrevTarget() { return prevTarget; }
    public void setPrevTarget(Targetable previous) { prevTarget = previous; }

    public void addChangeListener(Runnable listener) { listeners.add(listener); }
    public void removeChangeListener(Runnable listener) { listeners.remove(listener); }
    public void addWeaponSelectionListener(BiConsumer<WeaponMounted, WeaponMounted> listener) {
        weaponListeners.add(listener);
    }
    public void removeWeaponSelectionListener(BiConsumer<WeaponMounted, WeaponMounted> listener) {
        weaponListeners.remove(listener);
    }

    public void displayEntity(@Nullable Entity next) {
        if (next == null || next == entity) { return; }
        entity = next;
        displayMek(next);
        if (gui != null) {
            gui.clearFieldOfFire();
            gui.hideFleeZone();
        }
    }

    /** Refreshes the equipment list, clearing selection as the phase controllers expect. */
    public void displayMek(Entity next) {
        WeaponMounted previous = selectedWeapon;
        weaponEntity = next;
        selectedWeapon = null;
        bayWeapon = null;
        selectedAmmo = null;
        if (previous != null) { weaponSelectionChanged(previous); }
        changed();
    }

    public List<WeaponMounted> weapons() {
        return weaponEntity == null ? List.of() : WeaponDisplayData.listedWeapons(weaponEntity);
    }

    public int getSelectedWeaponNum() {
        return selectedWeapon == null ? -1 : selectedWeapon.getEntity().getEquipmentNum(selectedWeapon);
    }

    public void selectWeapon(int equipmentId) {
        selectWeapon(weaponEntity != null && equipmentId >= 0
              && weaponEntity.getEquipment(equipmentId) instanceof WeaponMounted weapon ? weapon : null);
    }

    public void selectWeapon(@Nullable WeaponMounted weapon) {
        WeaponMounted previous = selectedWeapon;
        selectedWeapon = weapon != null && weapons().contains(weapon) ? weapon : null;
        bayWeapon = selectedWeapon == null || selectedWeapon.getBayWeapons().isEmpty()
              ? null : selectedWeapon.getBayWeapons().getFirst();
        refreshAmmo();
        if (gui != null && weaponEntity != null) {
            if (selectedWeapon != null) {
                gui.showSensorRanges(weaponEntity);
                gui.setSelectedEntityNum(weaponEntity.getId());
            } else {
                gui.clearTemporarySprites();
            }
            gui.updateFiringArc(weaponEntity);
        }
        if (previous != selectedWeapon) { weaponSelectionChanged(previous); }
        changed();
    }

    public void selectFirstWeapon() {
        if (weaponEntity != null) {
            selectWeapon(weapons().stream().filter(weaponEntity::isWeaponValidForPhase).findFirst().orElse(null));
        }
    }

    private WeaponMounted adjacentWeapon(int direction) {
        List<WeaponMounted> weapons = weapons();
        int size = weapons.size();
        if (size == 0) { return null; }
        int selected = selectedWeapon == null ? -1 : weapons.indexOf(selectedWeapon);
        int start = selected < 0 ? (direction > 0 ? size - 1 : 0) : selected;
        for (int step = 1; step <= size; step++) {
            int index = Math.floorMod(start + direction * step, size);
            if (selected >= 0 && index == selected) { break; }
            WeaponMounted weapon = weapons.get(index);
            if (weaponEntity.isWeaponValidForPhase(weapon)) { return weapon; }
        }
        return null;
    }

    public WeaponMounted getNextWeapon() { return adjacentWeapon(1); }
    public WeaponMounted getPreviousWeapon() { return adjacentWeapon(-1); }
    public int getNextWeaponNum() {
        WeaponMounted weapon = getNextWeapon();
        return weapon == null ? -1 : weapon.getEntity().getEquipmentNum(weapon);
    }
    public int selectNextWeapon() { selectWeapon(adjacentWeapon(1)); return getSelectedWeaponNum(); }
    public int selectPrevWeapon() { selectWeapon(adjacentWeapon(-1)); return getSelectedWeaponNum(); }

    public void updateForEntity(@Nullable Entity next) {
        if (next == null) { return; }
        WeaponMounted previous = selectedWeapon;
        int equipmentId = getSelectedWeaponNum();
        weaponEntity = next;
        if (entity != null && entity.getId() == next.getId()) { entity = next; }
        // Server updates can replace an entity and its mounts. Handheld weapons have their own equipment IDs.
        WeaponMounted replacement = previous == null ? null : weapons().stream()
              .filter(weapon -> weapon.getEntity().getId() == previous.getEntity().getId()
                    && weapon.getEntity().getEquipmentNum(weapon) == equipmentId)
              .findFirst().orElse(null);
        selectedWeapon = null;
        selectWeapon(replacement);
    }

    public void setWeaponSortOrder(WeaponSortOrder order) {
        if (weaponEntity == null || order == null) { return; }
        weaponEntity.setWeaponSortOrder(order);
        if (client() != null) { WeaponDisplayData.sendWeaponOrder(client(), weaponEntity); }
        changed();
    }

    public void setCustomWeaponOrder(List<WeaponMounted> order) {
        if (weaponEntity == null) { return; }
        WeaponDisplayData.setCustomWeaponOrder(weaponEntity, order);
        changed();
    }

    public void selectBayWeapon(@Nullable WeaponMounted weapon) {
        if (weapon == null || selectedWeapon == null || !selectedWeapon.getBayWeapons().contains(weapon)) { return; }
        bayWeapon = weapon;
        refreshAmmo();
        changed();
    }

    public WeaponDisplayData.AmmoChoices ammoChoices() {
        return selectedWeapon == null ? new WeaponDisplayData.AmmoChoices(WeaponDisplayData.AmmoChoices.Feed.NONE,
              List.of()) : WeaponDisplayData.ammoChoices(weaponEntity, selectedWeapon,
                    bayWeapon == null ? selectedWeapon : bayWeapon);
    }

    private void refreshAmmo() {
        List<AmmoMounted> choices = ammoChoices().ammo();
        WeaponMounted weapon = bayWeapon == null ? selectedWeapon : bayWeapon;
        AmmoMounted linked = weapon == null ? null : weapon.getLinkedAmmo();
        selectedAmmo = linked != null && choices.contains(linked) ? linked : choices.isEmpty() ? null : choices.getFirst();
    }

    /** The same choice drives preview ranges for other units and changes ammunition only for the local owner. */
    public void selectAmmo(AmmoMounted ammo) {
        if (ammo == null || !ammoChoices().ammo().contains(ammo)) { return; }
        selectedAmmo = ammo;
        Client client = client();
        if (client != null && Objects.equals(client.getLocalPlayer(), weaponEntity.getOwner())) {
            WeaponDisplayData.loadAmmo(client, weaponEntity, selectedWeapon, bayWeapon, ammo);
        }
        weaponSelectionChanged(selectedWeapon);
        if (gui != null) { gui.updateFiringArc(weaponEntity); }
        changed();
    }

    public void clearToHit() { toHit = "---"; changed(); }
    public void setToHit(ToHitData data) { setToHit(data, false); }
    public void setToHit(ToHitData data, boolean naturalAptitude) {
        toHit = switch (data.getValue()) {
            case TargetRoll.IMPOSSIBLE, TargetRoll.AUTOMATIC_FAIL -> String.format("To Hit: (0%%) %s", data.getDesc());
            case TargetRoll.AUTOMATIC_SUCCESS -> String.format("To Hit: (100%%) %s", data.getDesc());
            default -> String.format("<font color=\"%s\">To Hit: <b>%2d (%2.0f%%)</b></font> = %s",
                  GUIPreferences.hexColor(GUIPreferences.getInstance().getUnitToolTipHighlightColor()), data.getValue(),
                  Compute.oddsAbove(data.getValue(), naturalAptitude), data.getDesc());
        };
        changed();
    }
    public void setToHit(String message) { toHit = Objects.toString(message, ""); changed(); }
    public void setTarget(@Nullable Targetable next, @Nullable String extra) {
        target = next;
        targetExtra = Objects.toString(extra, "");
        changed();
    }
    public void setRange(String value) { range = Objects.toString(value, ""); changed(); }
    public String getRange() { return range; }
    public String getToHit() { return toHit; }
    public String getTargetExtra() { return targetExtra; }
    public String getTargetText() {
        return target == null ? Messages.getString("MekDisplay.NoTarget") : UnitToolTip.getTargetTipDetail(target, client());
    }
    public String getTargetName() { return target == null ? Messages.getString("MekDisplay.NoTarget") : target.getDisplayName(); }
    public String getFiringSolution() {
        return Messages.getString("MekDisplay.Range") + " " + range + "<br>" + toHit + "<br>" + targetExtra;
    }
    public String getTargetSummary() { return UnitToolTip.wrapWithHTML(getTargetText() + "<br>" + getFiringSolution()); }

    private Client client() { return gui == null ? null : gui.getClient(); }
    private void weaponSelectionChanged(WeaponMounted previous) {
        for (var listener : List.copyOf(weaponListeners)) { listener.accept(previous, selectedWeapon); }
    }
    private void changed() { List.copyOf(listeners).forEach(Runnable::run); }
}
