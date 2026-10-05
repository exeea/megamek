/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.unitDisplay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.List;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.common.Player;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

/** Selection and equipment behavior without any inspector, Swing list, or dialog. */
class UnitDisplayStateTest {
    @Test
    void cyclingUsesTheGameRulesAndClearsSelectionWhenNoOtherWeaponIsAvailable() throws Exception {
        UnitDisplayState state = new UnitDisplayState(null);
        assertEquals(-1, state.selectNextWeapon());
        assertEquals(-1, state.selectPrevWeapon());
        state.selectFirstWeapon();
        assertNull(state.getSelectedWeapon());

        Entity unit = atlas(1, new Player(0, "Local"));
        state.displayEntity(unit);
        List<WeaponMounted> weapons = state.weapons();
        for (WeaponMounted weapon : weapons) { weapon.setUsedThisRound(true); }
        WeaponMounted available = weapons.get(2);
        available.setUsedThisRound(false);
        state.selectFirstWeapon();
        assertSame(available, state.getSelectedWeapon());
        assertEquals(-1, state.selectNextWeapon());
        assertNull(state.getSelectedWeapon());
        state.selectPrevWeapon();
        assertSame(available, state.getSelectedWeapon());
        available.setUsedThisRound(true);
        state.selectFirstWeapon();
        assertNull(state.getSelectedWeapon());
        assertTrue(state.getSelectedAmmo().isEmpty());
    }

    @Test
    void aReplacementEntityRetainsTheSelectedEquipmentAndUsesItsNewMount() throws Exception {
        Player owner = new Player(0, "Local");
        Entity original = atlas(1, owner);
        UnitDisplayState state = new UnitDisplayState(null);
        state.displayEntity(original);
        WeaponMounted weapon = original.getWeaponList().get(2);
        state.selectWeapon(weapon);
        int equipmentId = original.getEquipmentNum(weapon);

        Entity replacement = atlas(1, owner);
        state.updateForEntity(replacement);
        assertSame(replacement, state.getCurrentEntity());
        assertSame(replacement.getEquipment(equipmentId), state.getSelectedWeapon());
        assertSame(state.getSelectedWeapon().getLinkedAmmo(), state.getSelectedAmmo().orElseThrow());
        state.displayEntity(replacement);
        assertEquals(equipmentId, state.getSelectedWeaponNum(), "Redisplaying the same unit keeps selection");
    }

    @Test
    void ammoSelectionSendsTheOwnersChangeButOnlyPreviewsAnEnemyBin() throws Exception {
        Player local = new Player(0, "Local");
        Client client = mock(Client.class);
        ClientGUI gui = mock(ClientGUI.class);
        when(gui.getClient()).thenReturn(client);
        when(client.getLocalPlayer()).thenReturn(local);
        UnitDisplayState state = new UnitDisplayState(gui);
        Entity own = atlas(1, local);
        state.displayEntity(own);
        WeaponMounted weapon = own.getWeaponList().get(2);
        state.selectWeapon(weapon);
        AmmoMounted selected = state.ammoChoices().ammo().get(1);
        state.selectAmmo(selected);
        assertSame(selected, weapon.getLinkedAmmo());
        verify(client).sendAmmoChange(own.getId(), own.getEquipmentNum(weapon), own.getEquipmentNum(selected),
              own.getId(), 0);

        Entity enemy = atlas(2, new Player(1, "Enemy"));
        state.displayEntity(enemy);
        weapon = enemy.getWeaponList().get(2);
        state.selectWeapon(weapon);
        AmmoMounted loaded = weapon.getLinkedAmmo();
        selected = state.ammoChoices().ammo().get(1);
        state.selectAmmo(selected);
        assertSame(selected, state.getSelectedAmmo().orElseThrow());
        assertSame(loaded, weapon.getLinkedAmmo());
        verify(client, times(1)).sendAmmoChange(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    private static Entity atlas(int id, Player owner) throws Exception {
        Entity unit = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        Game game = new Game();
        game.setPhase(GamePhase.FIRING);
        game.addPlayer(owner.getId(), owner);
        unit.setId(id);
        unit.setOwner(owner);
        game.addEntity(unit, false);
        unit.loadAllWeapons();
        return unit;
    }
}
