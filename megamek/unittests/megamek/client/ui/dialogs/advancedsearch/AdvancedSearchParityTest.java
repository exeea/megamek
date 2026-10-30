/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.dialogs.advancedsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import megamek.client.lobby.RequisitionAdvancedSearch;
import megamek.client.lobby.RequisitionAdvancedSearch.Field;
import megamek.client.lobby.RequisitionAdvancedSearch.Flag;
import megamek.client.lobby.RequisitionAdvancedSearch.Minimums;
import megamek.client.lobby.RequisitionAdvancedSearch.Movement;
import megamek.client.lobby.RequisitionAdvancedSearch.Picks;
import megamek.client.lobby.RequisitionAdvancedSearch.Range;
import megamek.common.alphaStrike.ASDamage;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.battlefieldSupport.BFSArtilleryType;
import megamek.common.battlefieldSupport.BFSAssetType;
import megamek.common.battlefieldSupport.BFSDamage;
import megamek.common.battlefieldSupport.BFSSpecial;
import megamek.common.battlefieldSupport.BFSSpecialType;
import megamek.common.loaders.MekSummary;
import megamek.common.units.Entity;
import megamek.common.units.UnitRole;
import org.junit.jupiter.api.Test;

/**
 * The Alpha Strike and Battlefield Support filters taken out of the dialog's panels, and the lobby's advanced search
 * built on them, must pass exactly the units the panels pass.
 */
class AdvancedSearchParityTest {

    private static final List<MekSummary> AS_UNITS = asUnits();

    @Test
    void inactivePanelsMatchEverythingAsTheirFiltersDo() {
        ASAdvancedSearchPanel asPanel = new ASAdvancedSearchPanel();
        assertFalse(asPanel.isActive());
        assertFalse(asPanel.getFilter().isActive());
        BFSAdvancedSearchPanel bfsPanel = new BFSAdvancedSearchPanel();
        assertFalse(bfsPanel.isActive());
        assertFalse(bfsPanel.getFilter().isActive());
        for (MekSummary unit : AS_UNITS) {
            assertTrue(asPanel.matches(unit));
            assertTrue(RequisitionAdvancedSearch.NONE.matches(unit, null));
        }
    }

    @Test
    void alphaStrikeTypeSizeAndArmorMatchThePanel() {
        ASAdvancedSearchPanel panel = asPanel(state -> {
            state.unitTypeUse = true;
            state.unitTypeSelected = List.of(ASUnitType.BM);
            state.sizeUse = true;
            state.sizeSelected = List.of(2, 3);
            state.armorUse = true;
            state.armorFromText = "4";
        });
        RequisitionAdvancedSearch search = RequisitionAdvancedSearch.NONE
              .with(Field.AS_TYPE, picks(ASUnitType.BM))
              .with(Field.AS_SIZE, picks(2, 3))
              .with(Field.AS_ARMOR, new Range(4, null));

        assertSameMatches(panel, search, 2);
    }

    @Test
    void alphaStrikeDamageMovementAndPointValueMatchThePanel() {
        ASAdvancedSearchPanel panel = asPanel(state -> {
            state.damageMUse = true;
            state.mvUse = true;
            state.mvModeText = "j";
            state.mvFromText = "4";
            state.pvUse = true;
            state.pvToText = "40";
        });
        // ASDamage has no value equality, so the saved state cannot select these; pick them as a user would
        panel.damageMFrom.setSelectedIndex(damageIndex(2));
        panel.damageMTo.setSelectedIndex(damageIndex(4));
        RequisitionAdvancedSearch search = RequisitionAdvancedSearch.NONE
              .with(Field.AS_DAMAGE_M, new Range(damageIndex(2), damageIndex(4)))
              .with(Field.AS_MOVE, new Movement("j", new Range(4, null)))
              .with(Field.AS_PV, new Range(null, 40));

        assertSameMatches(panel, search, 2);
    }

    @Test
    void alphaStrikeRoleAndTmmMatchThePanel() {
        ASAdvancedSearchPanel panel = asPanel(state -> {
            state.unitRoleUse = true;
            state.unitRoleSelected = List.of(UnitRole.SKIRMISHER, UnitRole.STRIKER);
            state.tmmUse = true;
            state.tmmSelected = List.of(2);
        });
        RequisitionAdvancedSearch search = RequisitionAdvancedSearch.NONE
              .with(Field.AS_ROLE, picks(UnitRole.SKIRMISHER, UnitRole.STRIKER))
              .with(Field.AS_TMM, picks(2));

        assertSameMatches(panel, search, 1);
    }

    @Test
    void battlefieldSupportCriteriaMatchThePanel() {
        List<MekSummary> assets = List.of(
              asset(BFSAssetType.VEHICLE, new BFSDamage(5, 4), artillery(BFSArtilleryType.THUMPER),
                    BFSSpecial.of(BFSSpecialType.NIMBLE.canonicalCode())),
              asset(BFSAssetType.VEHICLE, new BFSDamage(3, 2), BFSSpecial.of(BFSSpecialType.ECM.canonicalCode(), 4)),
              asset(BFSAssetType.BATTLE_ARMOR, new BFSDamage(4, 4), BFSSpecial.of(BFSSpecialType.ECM.canonicalCode(), 2),
                    BFSSpecial.of(BFSSpecialType.NIMBLE.canonicalCode())),
              asset(BFSAssetType.EMPLACEMENT, BFSDamage.NONE, artillery(BFSArtilleryType.LONG_TOM)));

        checkBfs(assets, state -> {
            state.assetTypeUse = true;
            state.assetTypeSelected = List.of(BFSAssetType.VEHICLE);
        }, RequisitionAdvancedSearch.NONE.with(Field.BF_TYPE, picks(BFSAssetType.VEHICLE)), 2);
        checkBfs(assets, state -> {
            state.damageHitsUse = true;
            state.damageHitsFromText = "4";
        }, RequisitionAdvancedSearch.NONE.with(Field.BF_DAMAGE_HITS, new Range(4, null)), 2);
        checkBfs(assets, state -> {
            state.numericSpecialSelected = List.of(BFSSpecialType.ECM);
            state.numericSpecialMinTexts = Map.of(BFSSpecialType.ECM, "3");
        }, RequisitionAdvancedSearch.NONE.with(Field.BF_NUMERIC_SPECIALS,
              new Minimums(Map.of(BFSSpecialType.ECM, 3))), 1);
        checkBfs(assets, state -> state.artilleryTypeUse = true,
              RequisitionAdvancedSearch.NONE.with(Field.BF_ARTILLERY, picks()), 2);
        checkBfs(assets, state -> {
            state.specialsUse = true;
            state.specialsSelected = List.of(BFSSpecialType.NIMBLE);
        }, RequisitionAdvancedSearch.NONE.with(Field.BF_SPECIALS, picks(BFSSpecialType.NIMBLE)), 2);
    }

    @Test
    void totalWarfareFlagsAndRangesMatchMekSearchFilter() {
        List<MekSummary> units = new ArrayList<>();
        for (int walk = 2; walk <= 6; walk++) {
            for (boolean omni : new boolean[] { false, true }) {
                MekSummary unit = new MekSummary();
                unit.setEntityType(Entity.ETYPE_MEK | Entity.ETYPE_BIPED_MEK);
                unit.setWalkMp(walk);
                unit.setOmni(omni);
                unit.setTons(walk * 10);
                unit.setYear(3000 + walk * 10);
                unit.setEngineName("");
                units.add(unit);
            }
        }
        // The dialog's filter as its Total Warfare tab leaves it with empty fields, then the criteria
        MekSearchFilter dialog = MekSearchFilter.allowingAll();
        dialog.sStartWalk = "3";
        dialog.sEndWalk = "5";
        dialog.iOmni = 1;
        dialog.sStartYear = "3040";
        RequisitionAdvancedSearch search = RequisitionAdvancedSearch.NONE
              .with(Field.WALK, new Range(3, 5))
              .with(Field.OMNI, new Flag(true))
              .with(Field.YEAR, new Range(3040, null));

        int passing = 0;
        for (MekSummary unit : units) {
            boolean expected = MekSearchFilter.isMatch(unit, dialog);
            assertEquals(expected, search.matches(unit, null), "walk " + unit.getWalkMp());
            passing += expected ? 1 : 0;
        }
        assertEquals(2, passing);
    }

    private static void checkBfs(List<MekSummary> assets, Consumer<AdvSearchState.BfsState> configure,
          RequisitionAdvancedSearch search, int expected) {
        AdvSearchState.BfsState state = new AdvSearchState.BfsState();
        configure.accept(state);
        BFSAdvancedSearchPanel panel = new BFSAdvancedSearchPanel();
        panel.applyState(state);
        int passing = 0;
        for (MekSummary asset : assets) {
            boolean byPanel = panel.matches(asset);
            assertEquals(byPanel, search.matches(new MekSummary(), asset), search.toString());
            passing += byPanel ? 1 : 0;
        }
        assertEquals(expected, passing, search.toString());
    }

    private static void assertSameMatches(ASAdvancedSearchPanel panel, RequisitionAdvancedSearch search,
          int expected) {
        int passing = 0;
        for (MekSummary unit : AS_UNITS) {
            boolean byPanel = panel.matches(unit);
            assertEquals(byPanel, search.matches(unit, null), unit.getName());
            passing += byPanel ? 1 : 0;
        }
        assertEquals(expected, passing);
    }

    private static ASAdvancedSearchPanel asPanel(Consumer<AdvSearchState.AsState> configure) {
        AdvSearchState.AsState state = new AdvSearchState.AsState();
        configure.accept(state);
        ASAdvancedSearchPanel panel = new ASAdvancedSearchPanel();
        panel.applyState(state);
        assertTrue(panel.isActive());
        return panel;
    }

    private static Picks picks(Object... values) {
        return new Picks(Arrays.asList(values), List.of(), false);
    }

    private static int damageIndex(int damage) {
        List<ASDamage> values = ASSearchFilter.DAMAGE_VALUES;
        for (int i = 0; i < values.size(); i++) {
            if (!values.get(i).minimal && (values.get(i).damage == damage)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No damage value " + damage);
    }

    private static List<MekSummary> asUnits() {
        return List.of(
              asUnit("Light Mek", ASUnitType.BM, 1, 2, 3, ASDamageVector.createNormRndDmg(2, 2, 1), 20,
                    UnitRole.SCOUT, Map.of("", 12, "j", 12)),
              asUnit("Medium Mek", ASUnitType.BM, 2, 2, 5, ASDamageVector.createNormRndDmg(3, 3, 1), 30,
                    UnitRole.SKIRMISHER, Map.of("", 10, "j", 10)),
              asUnit("Heavy Mek", ASUnitType.BM, 3, 1, 6, ASDamageVector.createNormRndDmg(4, 4, 2), 42,
                    UnitRole.BRAWLER, Map.of("", 8)),
              asUnit("Assault Mek", ASUnitType.BM, 4, 1, 8, ASDamageVector.createNormRndDmg(5, 5, 4), 52,
                    UnitRole.JUGGERNAUT, Map.of("", 6)),
              asUnit("Tank", ASUnitType.CV, 2, 1, 4, ASDamageVector.createNormRndDmg(3, 3, 0), 25,
                    UnitRole.STRIKER, Map.of("t", 8)),
              new MekSummary());
    }

    private static MekSummary asUnit(String name, ASUnitType type, int size, int tmm, int armor,
          ASDamageVector damage, int pointValue, UnitRole role, Map<String, Integer> movement) {
        MekSummary unit = new MekSummary();
        unit.setName(name);
        unit.setAsUnitType(type);
        unit.setSize(size);
        unit.setTmm(tmm);
        unit.setFullArmor(armor);
        unit.setFullStructure(armor / 2);
        unit.setStandardDamage(damage);
        unit.setPointValue(pointValue);
        unit.setUnitRole(role);
        unit.setMovement(movement);
        unit.setPrimaryMovementMode(movement.keySet().iterator().next());
        return unit;
    }

    private static MekSummary asset(BFSAssetType type, BFSDamage damage, BFSSpecial... specials) {
        MekSummary summary = new MekSummary();
        summary.setEntityType(Entity.ETYPE_BATTLEFIELD_SUPPORT_ASSET);
        summary.setBfsAssetType(type);
        summary.setBfsDamage(damage);
        summary.setBfsSpecialDetails(List.of(specials));
        summary.setBfsSpecials(Arrays.stream(specials)
              .map(BFSSpecial::knownType)
              .flatMap(java.util.Optional::stream)
              .distinct()
              .toList());
        return summary;
    }

    private static BFSSpecial artillery(BFSArtilleryType type) {
        return BFSSpecial.of(BFSSpecialType.ARTILLERY.canonicalCode(), type.code());
    }

    @SuppressWarnings("unused")
    private static Set<Object> unused() {
        return Set.of();
    }
}
