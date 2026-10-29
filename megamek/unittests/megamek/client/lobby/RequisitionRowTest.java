/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import megamek.common.TechConstants;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponType;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Test;

class RequisitionRowTest {

    @Test
    void tonsDropAWholeFraction() {
        assertEquals("70", RequisitionRow.tons(70));
        assertEquals("0.5", RequisitionRow.tons(.5));
        assertEquals("1.25", RequisitionRow.tons(1.25));
    }

    @Test
    void aRowCarriesTheUnitsNumbers() {
        MekSummary atlas = RequisitionCatalogTest.mek("Atlas", "AS7-D");
        atlas.setTons(100);
        atlas.setBV(1897);
        atlas.setPointValue(52);
        RequisitionRow row = RequisitionRow.of(atlas);
        assertEquals("Atlas AS7-D", row.name());
        assertEquals(1897, row.bv());
        assertEquals(52, row.pv());
        assertEquals(100, row.tons());
        assertTrue(row.details().contains("100"));
        assertTrue(row.details().contains("3025"));
    }

    @Test
    void aSupportVehicleSaysSo() {
        MekSummary truck = RequisitionCatalogTest.unit("Truck", "", UnitType.TANK, 3000, TechConstants.T_INTRO_BOX_SET);
        String tank = RequisitionRow.typeName(truck);
        truck.setSupport(true);
        assertEquals(megamek.client.ui.Messages.getString("Lobby.requisition.type.support_vehicle"),
              RequisitionRow.typeName(truck));
        assertEquals(UnitType.getTypeDisplayableName(UnitType.TANK), tank);
    }

    @Test
    void theDossierMergesAWeaponMountedTwiceAndLeavesOutTheRest() {
        Map<String, EquipmentType> types = new HashMap<>();
        types.put("ISMediumLaser", weapon("Medium Laser"));
        types.put("ISMediumLaserRear", weapon("Medium Laser"));
        types.put("ISAC20", weapon("AC/20"));
        EquipmentType heatSink = mock(EquipmentType.class);
        when(heatSink.getName()).thenReturn("Heat Sink");
        types.put("Heat Sink", heatSink);

        List<RequisitionDossier.Line> lines = RequisitionDossier.weapons(
              List.of("ISMediumLaser", "Heat Sink", "ISAC20", "ISMediumLaserRear", "Unknown"),
              List.of(2, 10, 1, 2, 1), types::get);

        assertEquals(List.of(new RequisitionDossier.Line("Medium Laser", 4), new RequisitionDossier.Line("AC/20", 1)),
              lines);
        assertTrue(RequisitionDossier.weapons(null, List.of(), types::get).isEmpty());
    }

    @Test
    void theDossierListsTheFactsTheUnitHas() {
        MekSummary atlas = RequisitionCatalogTest.mek("Atlas", "AS7-D");
        atlas.setWalkMp(3);
        atlas.setRunMp(5);
        atlas.setTotalArmor(304);
        atlas.setCost(9_626_000);
        RequisitionDossier dossier = RequisitionDossier.of(atlas, name -> null);
        assertEquals(304, dossier.armor());
        assertTrue(dossier.movement().contains("3"));
        assertTrue(dossier.cost().contains("9"));
        List<String> values = dossier.facts().stream().map(RequisitionDossier.Fact::value).toList();
        assertTrue(values.contains("3025"));
        assertEquals(3, dossier.facts().size(), "weight, tech and year; no role, level or source");

        atlas.setSource("TRO:3025");
        assertEquals(4, RequisitionDossier.facts(atlas).size());
    }

    private static WeaponType weapon(String name) {
        WeaponType weapon = mock(WeaponType.class);
        when(weapon.getName()).thenReturn(name);
        return weapon;
    }
}
