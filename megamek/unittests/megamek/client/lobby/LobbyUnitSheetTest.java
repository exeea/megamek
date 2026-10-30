/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import org.junit.jupiter.api.Test;

class LobbyUnitSheetTest {

    private static RequisitionDossier.Line line(String name, int count) {
        return new RequisitionDossier.Line(name, count);
    }

    @Test
    void theSheetReadsMovementProtectionAndHeat() {
        LobbyUnit.Sheet sheet = new LobbyUnit.Sheet(4, 6, 4, 1304, 152, 10, 20, List.of());
        assertEquals(Messages.getString("Lobby.requisition.moveJump", 4, 6, 4), sheet.move());
        assertEquals(Messages.getString("Lobby.card.protection", LobbyUnit.number(1304), "152"), sheet.protection());
        assertEquals(Messages.getString("Lobby.card.heatSinks", 10, 20), sheet.heat());

        LobbyUnit.Sheet tank = new LobbyUnit.Sheet(5, 8, 0, 80, 20, LobbyUnit.Sheet.NO_HEAT, 0, List.of());
        assertEquals(Messages.getString("Lobby.requisition.move", 5, 8), tank.move());
        assertEquals("", tank.heat());
        assertEquals("", tank.weaponsSummary(3));
    }

    @Test
    void theWeaponsSummaryPutsTheMostNumerousFirstAndCountsTheRest() {
        LobbyUnit.Sheet sheet = new LobbyUnit.Sheet(3, 5, 0, 0, 0, 10, 10, List.of(line("ER Small Laser", 1),
              line("LRM 5", 2), line("Medium Laser", 1), line("AC/20", 1)));
        String twoLrms = Messages.getString("Lobby.card.weaponCount", 2, "LRM 5");
        assertEquals(twoLrms + ", ER Small Laser " + Messages.getString("Lobby.card.more", 2),
              sheet.weaponsSummary(2));
        assertEquals(twoLrms + ", ER Small Laser, Medium Laser, AC/20", sheet.weaponsSummary(4));
        assertEquals(twoLrms + "\nER Small Laser\nMedium Laser\nAC/20", sheet.weaponsAll());
    }

    @Test
    void aMeksSheetCountsItsWeaponsAndHeatSinks() {
        Mek mek = mock(Mek.class);
        List<WeaponMounted> weapons = new ArrayList<>(List.of(weapon("Medium Laser"), weapon("PPC"),
              weapon("Medium Laser")));
        when(mek.getWeaponList()).thenReturn(weapons);
        when(mek.heatSinks()).thenReturn(12);
        when(mek.getHeatCapacity()).thenReturn(24);
        when(mek.getOriginalWalkMP()).thenReturn(4);
        when(mek.getOriginalRunMP()).thenReturn(6);
        when(mek.getTotalOArmor()).thenReturn(208);
        when(mek.getTotalOInternal()).thenReturn(107);
        LobbyUnit.Sheet sheet = LobbyUnit.Sheet.of(mek);
        assertEquals(List.of(line("Medium Laser", 2), line("PPC", 1)), sheet.weapons());
        assertEquals(12, sheet.heatSinks());
        assertEquals(24, sheet.heatCapacity());
        assertEquals(208, sheet.armor());
        assertEquals(107, sheet.structure());
        assertEquals(0, sheet.jump());

        Tank tank = mock(Tank.class);
        when(tank.getWeaponList()).thenReturn(new ArrayList<>());
        assertEquals(LobbyUnit.Sheet.NO_HEAT, LobbyUnit.Sheet.of(tank).heatSinks());
    }

    private static WeaponMounted weapon(String name) {
        WeaponType type = mock(WeaponType.class);
        when(type.getName()).thenReturn(name);
        WeaponMounted weapon = mock(WeaponMounted.class);
        when(weapon.getType()).thenReturn(type);
        return weapon;
    }
}
