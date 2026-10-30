/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import megamek.client.lobby.RequisitionAdvancedSearch.Equipment;
import megamek.client.lobby.RequisitionAdvancedSearch.Field;
import megamek.client.lobby.RequisitionAdvancedSearch.Flag;
import megamek.client.lobby.RequisitionAdvancedSearch.Kind;
import megamek.client.lobby.RequisitionAdvancedSearch.Level;
import megamek.client.lobby.RequisitionAdvancedSearch.Minimums;
import megamek.client.lobby.RequisitionAdvancedSearch.Picks;
import megamek.client.lobby.RequisitionAdvancedSearch.Range;
import megamek.client.lobby.RequisitionAdvancedSearch.Section;
import megamek.client.lobby.RequisitionAdvancedSearch.Term;
import megamek.client.lobby.RequisitionCatalog.Category;
import megamek.client.lobby.RequisitionCatalog.Filters;
import megamek.client.lobby.RequisitionCatalog.Rules;
import megamek.common.TechConstants;
import megamek.common.battlefieldSupport.BFSAssetType;
import megamek.common.battlefieldSupport.BFSSpecialType;
import megamek.common.equipment.EquipmentType;
import megamek.common.loaders.MekSummary;
import megamek.common.units.Entity;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RequisitionAdvancedSearchTest {

    private static final Rules OPEN = new Rules(3150, false, TechConstants.T_SIMPLE_UNOFFICIAL, false, true);

    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    private static MekSummary mek(String name, int walk, int run, int jump) {
        MekSummary unit = RequisitionCatalogTest.unit(name, "", UnitType.MEK, 3025, TechConstants.T_INTRO_BOX_SET);
        unit.setEntityType(Entity.ETYPE_MEK | Entity.ETYPE_BIPED_MEK);
        unit.setWalkMp(walk);
        unit.setRunMp(run);
        unit.setJumpMp(jump);
        unit.setEngineName("");
        unit.setEquipment(List.of());
        return unit;
    }

    @Test
    void noCriteriaLetEveryUnitThrough() {
        RequisitionAdvancedSearch none = RequisitionAdvancedSearch.NONE;
        assertTrue(none.isEmpty());
        assertEquals(0, none.count());
        assertTrue(none.matches(mek("Atlas", 3, 5, 0), null));
        assertTrue(none.matches(new MekSummary(), null));
    }

    @Test
    void openOrEmptyValuesTakeTheFieldOut() {
        RequisitionAdvancedSearch search = RequisitionAdvancedSearch.NONE.with(Field.WALK, new Range(4, null));
        assertEquals(1, search.count());
        assertEquals(1, search.count(Section.TOTAL_WARFARE));
        assertSame(RequisitionAdvancedSearch.NONE.values(), RequisitionAdvancedSearch.NONE.values());
        assertEquals(0, search.with(Field.WALK, new Range(null, null)).count());
        assertEquals(0, search.with(Field.ARMOR, new Level(0)).without(Field.WALK).count());
        assertEquals(0, search.with(Field.AS_SIZE, new Picks(List.of(), List.of(), false)).without(Field.WALK).count());
        assertEquals(1, RequisitionAdvancedSearch.NONE.with(Field.BF_ARTILLERY, new Picks(List.of(), List.of(), false))
              .count(), "Artillery with no type picked still asks for artillery");
        assertSame(search, search.with(Field.WALK, new Range(4, null)));
        assertEquals(search, RequisitionAdvancedSearch.NONE.with(Field.WALK, new Range(4, null)));
        assertNull(search.value(Field.JUMP));
    }

    @Test
    void sectionClearKeepsTheOtherTabs() {
        RequisitionAdvancedSearch search = RequisitionAdvancedSearch.NONE
              .with(Field.WALK, new Range(4, null))
              .with(Field.AS_PV, new Range(null, 30))
              .with(Field.BF_TMM, new Range(2, 3));
        assertEquals(3, search.count());
        RequisitionAdvancedSearch cleared = search.without(Section.ALPHA_STRIKE);
        assertEquals(2, cleared.count());
        assertNull(cleared.value(Field.AS_PV));
    }

    @Test
    void movementRangesReadWalkRunAndJump() {
        MekSummary fast = mek("Fast", 6, 9, 6);
        MekSummary slow = mek("Slow", 3, 5, 0);
        RequisitionAdvancedSearch walk = RequisitionAdvancedSearch.NONE.with(Field.WALK, new Range(5, null));
        assertTrue(walk.matches(fast, null));
        assertFalse(walk.matches(slow, null));
        RequisitionAdvancedSearch run = RequisitionAdvancedSearch.NONE.with(Field.RUN, new Range(null, 5));
        assertFalse(run.matches(fast, null));
        assertTrue(run.matches(slow, null));
        RequisitionAdvancedSearch jump = RequisitionAdvancedSearch.NONE.with(Field.JUMP, new Range(1, null));
        assertTrue(jump.matches(fast, null));
        assertFalse(jump.matches(slow, null));
    }

    @Test
    void picksIncludeAndExcludeLikeTheTriStateLists() {
        MekSummary omni = mek("Omni", 5, 8, 0);
        omni.setOmni(true);
        MekSummary plain = mek("Plain", 5, 8, 0);
        RequisitionAdvancedSearch notOmni = RequisitionAdvancedSearch.NONE.with(Field.OMNI, new Flag(false));
        assertFalse(notOmni.matches(omni, null));
        assertTrue(notOmni.matches(plain, null));

        Picks base = new Picks(List.of(), List.of(), false).with("Clan", Boolean.TRUE);
        assertEquals(Boolean.TRUE, base.state("Clan"));
        Picks excluded = base.with("Clan", Boolean.FALSE);
        assertEquals(List.of("Clan"), excluded.out());
        assertTrue(excluded.in().isEmpty());
        assertNull(excluded.with("Clan", null).state("Clan"));

        RequisitionAdvancedSearch clan = RequisitionAdvancedSearch.NONE.with(Field.TECH_BASE, base);
        assertFalse(clan.matches(plain, null));
        plain.setTechBase("Clan");
        assertTrue(clan.matches(plain, null));
        assertFalse(RequisitionAdvancedSearch.NONE.with(Field.TECH_BASE, excluded).matches(plain, null));

        RequisitionAdvancedSearch notMek = RequisitionAdvancedSearch.NONE.with(Field.UNIT_TYPE,
              new Picks(List.of(), List.of("Mek"), false));
        assertFalse(notMek.matches(plain, null));
    }

    @Test
    void equipmentExpressionAcceptsOnlyValidTermsAndAppliesWhenComplete() {
        Term laser = Term.item("Medium Laser", "Medium Laser", 1, true);
        Equipment equipment = Equipment.EMPTY.plus(Term.AND);
        assertTrue(equipment.terms().isEmpty(), "No operator at the start");
        equipment = equipment.plus(Term.OPEN).plus(laser);
        assertFalse(equipment.complete());
        assertTrue(equipment.canClose());
        assertSame(equipment, equipment.plus(laser), "Two items need an operator between them");
        equipment = equipment.plus(Term.OR).plus(Term.item("ISERLargeLaser", "ER Large Laser", 2, true));
        assertFalse(equipment.complete());
        equipment = equipment.plus(Term.CLOSE);
        assertTrue(equipment.complete());
        assertEquals(5, equipment.terms().size());
        assertEquals(4, equipment.back().terms().size());

        MekSummary bare = mek("Bare", 4, 6, 0);
        RequisitionAdvancedSearch search = RequisitionAdvancedSearch.NONE.with(Field.EQUIPMENT, equipment);
        assertFalse(search.matches(bare, null), "A unit without lasers fails the complete expression");
        RequisitionAdvancedSearch unfinished = RequisitionAdvancedSearch.NONE.with(Field.EQUIPMENT,
              equipment.back());
        assertTrue(unfinished.matches(bare, null), "An unfinished expression does not filter yet");
        RequisitionAdvancedSearch fewer = RequisitionAdvancedSearch.NONE.with(Field.EQUIPMENT,
              Equipment.EMPTY.plus(Term.item("Medium Laser", "Medium Laser", 1, false)));
        assertTrue(fewer.matches(bare, null));
    }

    @Test
    void battlefieldSupportCriteriaReadTheAssetForm() {
        MekSummary unit = mek("Base", 4, 6, 0);
        MekSummary asset = new MekSummary();
        asset.setEntityType(Entity.ETYPE_BATTLEFIELD_SUPPORT_ASSET);
        asset.setBfsAssetType(BFSAssetType.VEHICLE);
        asset.setBfsTmm(2);
        RequisitionAdvancedSearch vehicle = RequisitionAdvancedSearch.NONE.with(Field.BF_TYPE,
              new Picks(List.of(BFSAssetType.VEHICLE), List.of(), false));
        assertTrue(vehicle.matches(unit, asset));
        assertFalse(vehicle.matches(unit, null), "No asset form, no Battlefield Support match");
        assertFalse(RequisitionAdvancedSearch.NONE.with(Field.BF_TMM, new Range(3, null)).matches(unit, asset));
        assertFalse(RequisitionAdvancedSearch.NONE.with(Field.BF_NUMERIC_SPECIALS,
              new Minimums(Map.of(BFSSpecialType.ECM, 1))).matches(unit, asset));
    }

    @Test
    void everyFieldHasALabelAndChoicesWhereItPicks() {
        for (Field field : Field.values()) {
            String label = RequisitionAdvancedSearch.label(field);
            assertFalse(label.startsWith("!"), field + " has no label");
            if ((field.kind() == Kind.PICKS) || (field.kind() == Kind.LEVEL) || (field.kind() == Kind.MOVEMENT)
                  || (field.kind() == Kind.MINIMUMS)) {
                assertFalse(RequisitionAdvancedSearch.choices(field).isEmpty(), field + " has no choices");
            }
        }
        assertFalse(RequisitionAdvancedSearch.equipmentChoices().isEmpty());
        assertTrue(RequisitionAdvancedSearch.equipmentChoices().stream()
              .anyMatch(choice -> choice.key().equals("Medium Laser")));
    }

    @Test
    void tagsDescribeTheCriteria() {
        RequisitionAdvancedSearch search = RequisitionAdvancedSearch.NONE
              .with(Field.WALK, new Range(4, 6))
              .with(Field.JUMP, new Range(3, null))
              .with(Field.OMNI, new Flag(true))
              .with(Field.TECH_BASE, new Picks(List.of("Clan"), List.of("Mixed (IS Chassis)"), false));
        assertEquals("Walk: 4\u20136", search.describe(Field.WALK));
        assertEquals("Jump: \u2265 3", search.describe(Field.JUMP));
        assertEquals("Omni: Yes", search.describe(Field.OMNI));
        assertTrue(search.describe(Field.TECH_BASE).contains("not "));
        assertEquals("Damage M: 2\u20134", RequisitionAdvancedSearch.NONE.with(Field.AS_DAMAGE_M,
              new Range(3, 5)).describe(Field.AS_DAMAGE_M));
    }

    @Test
    void catalogOfferHonoursTheAdvancedSearchAndCountsItInTheDrawer() {
        MekSummary fast = mek("Fast", 6, 9, 6);
        MekSummary slow = mek("Slow", 3, 5, 0);
        RequisitionCatalog.Index index = RequisitionCatalog.index(new MekSummary[] { fast, slow }, unit -> false,
              unit -> null);
        Filters filters = Filters.NONE.withAdvanced(RequisitionAdvancedSearch.NONE.with(Field.JUMP,
              new Range(1, null)).with(Field.WALK, new Range(5, null)));
        assertEquals(List.of(fast), RequisitionCatalog.offer(index, OPEN, Category.MEKS, filters, null));
        assertEquals(2, filters.drawerCount());
        assertEquals(RequisitionAdvancedSearch.NONE, filters.withoutDrawer().advanced());
        assertEquals(2, RequisitionCatalog.offer(index, OPEN, Category.MEKS, filters.withoutDrawer(), null).size());
    }
}
