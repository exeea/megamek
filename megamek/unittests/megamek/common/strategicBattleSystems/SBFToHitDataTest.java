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

package megamek.common.strategicBattleSystems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import megamek.client.ui.clientGUI.calculationReport.DummyCalculationReport;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.TargetRollModifier;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the to-hit modifiers of SBF standard attacks against the To-Hit Modifiers Table (IO:BF p.170). */
class SBFToHitDataTest {

    private SBFGame game;
    private SBFFormation attacker;
    private SBFFormation target;

    @BeforeEach
    void setUp() {
        game = new SBFGame();
        game.setPhase(GamePhase.FIRING);
        game.addPlayer(1, new Player(1, "Owner"));
        game.addPlayer(2, new Player(2, "Enemy"));
        attacker = formation(17, 1, new Coords(1, 1));
        target = formation(20, 2, new Coords(2, 1));
        game.addUnit(attacker);
        game.addUnit(target);
    }

    @Test
    void sameHexAttackIsAtLongRange() {
        target.setPosition(attacker.getPosition());

        assertEquals(List.of(2), modifiers("long range"));
    }

    @Test
    void adjacentHexAttackIsAtLongRange() {
        assertEquals(List.of(2), modifiers("long range"));
    }

    @Test
    void sharpShootersReplaceTheRangeModifiers() {
        // Sharp Shooters, IO:BF p. 225: "Short Range +2; Medium Range +1; Long Range +1; Extreme Range +2."
        attacker.setSharpShooters(true);
        assertEquals(List.of(1), modifiers("sharp shooters range"));
        assertEquals(List.of(), modifiers("long range"));
        target.setPosition(BoardLocation.of(new Coords(3, 1), 0));
        assertEquals(List.of(2), modifiers("sharp shooters range"));
    }

    @Test
    void brawlersReplaceTheRangeModifiers() {
        // Brawlers, IO:BF p. 225: "Short Range -1; Medium Range +2; Long Range +3; Extreme Range +5."
        attacker.setBrawlers(true);
        assertEquals(List.of(3), modifiers("brawlers range"));
        assertEquals(List.of(), modifiers("long range"));
        target.setPosition(BoardLocation.of(new Coords(3, 1), 0));
        assertEquals(List.of(5), modifiers("brawlers range"));
    }

    @Test
    void attackTwoHexesAwayIsAtExtremeRange() {
        target.setPosition(BoardLocation.of(new Coords(3, 1), 0));

        assertEquals(List.of(3), modifiers("extreme range"));
    }

    @Test
    void attackThreeHexesAwayIsImpossible() {
        target.setPosition(BoardLocation.of(new Coords(4, 1), 0));

        assertTrue(SBFToHitData.compileToHit(game, attack()).cannotSucceed());
    }

    @Test
    void jumpModifiersUseEachSidesOwnJump() {
        attacker.setJumpUsedThisTurn(1);
        target.setJumpUsedThisTurn(3);

        assertEquals(List.of(1), modifiers("attacker JUMP"));
        assertEquals(List.of(3), modifiers("target JUMP"));
    }

    @Test
    void engineeringUnitsBuildingAFortificationCannotFire() {
        // Fortified Positions, IO:BF p. 220: "the Units with the TRN or ENG special ability may not make weapon
        // attacks of any kind. The other Units in the Formation may attack as normal."
        var builder = attacker.getUnits().getFirst();
        builder.getSpecialAbilities().setSUA(BattleForceSUA.TRN);
        attacker.addUnit(new SBFUnit());
        var otherUnit = new SBFStandardUnitAttack(attacker.getId(), 1, target.getId(), ASRange.LONG);
        attacker.beginFieldConstruction(builder, 1);

        var building = SBFToHitData.compileToHit(game, attack());
        assertTrue(building.cannotSucceed());
        // The firing choice shows this reason to the player
        assertEquals("building a fortification", building.getDesc());
        assertFalse(SBFToHitData.compileToHit(game, otherUnit).cannotSucceed());
        attacker.finishFieldConstruction();
        assertFalse(SBFToHitData.compileToHit(game, attack()).cannotSucceed());
    }

    @Test
    void secondaryPenaltyAppliesToTheSecondDeclaredTargetOnly() {
        var secondary = formation(21, 2, target.getPosition().coords());
        game.addUnit(secondary);
        var secondAttack = new SBFStandardUnitAttack(attacker.getId(), 0, secondary.getId(), ASRange.LONG);
        int base = SBFToHitData.compileToHit(game, attack()).getValue();

        assertEquals(base + 1, SBFToHitData.compileToHit(game, secondAttack, List.of(attack())).getValue());
        game.addAction(attack());
        game.addAction(secondAttack);
        assertEquals(base, SBFToHitData.compileToHit(game, attack()).getValue());
        assertEquals(base + 1, SBFToHitData.compileToHit(game, secondAttack).getValue());
    }

    @Test
    void withheldBonusCountsOnlyUnitsAbleToDamageThisTargetThatHaveNoDeclaredShot() {
        for (int i = 0; i < 3; i++) {
            SBFUnit unit = new SBFUnit();
            unit.setDamage(ASDamageVector.createNormRndDmg(2, 2, 1));
            attacker.addUnit(unit);
        }
        assertEquals(List.of(-2), modifiers("withheld fire"));
        game.addAction(new SBFStandardUnitAttack(attacker.getId(), 1, target.getId(), ASRange.LONG));
        var disabled = attacker.getUnits().get(2);
        disabled.addTargetingCrit();
        disabled.addTargetingCrit();
        disabled.addTargetingCrit();
        assertEquals(List.of(-1), modifiers("withheld fire"));

        target.setPosition(BoardLocation.of(new Coords(3, 1), 0));
        assertTrue(modifiers("withheld fire").isEmpty());
    }

    @Test
    void outmaneuveredFormationCanOnlyWithholdItsRemainingLegalShots() {
        for (int i = 0; i < 3; i++) {
            SBFUnit unit = new SBFUnit();
            unit.setDamage(ASDamageVector.createNormRndDmg(2, 2, 2));
            attacker.addUnit(unit);
        }
        game.combatOrders().setManeuver(new SBFCombatOrders.Maneuver(attacker.getId(), target.getId(),
              target.getId(), attacker.getId(), ASRange.LONG));
        assertEquals(List.of(-1), modifiers("withheld fire"));

        game.addAction(new SBFStandardUnitAttack(attacker.getId(), 1, target.getId(), ASRange.LONG));
        assertTrue(modifiers("withheld fire").isEmpty());
        game.addAction(new SBFStandardUnitAttack(attacker.getId(), 2, target.getId(), ASRange.LONG));
        assertTrue(SBFToHitData.compileToHit(game, attack()).cannotSucceed());
    }

    @Test
    void unplacedFormationIsImpossibleAndNeverDereferencesANullPosition() {
        attacker.setPosition(null);
        assertTrue(SBFToHitData.compileToHit(game, attack()).cannotSucceed());
    }

    @Test
    void urbanCombatLetsOnlyTheSameHexAttackAFormationInAnUrbanHex() {
        // Urban Hexes, IO:BF p. 216: "Formations in an urban hex may only be attacked by another Formation in the same
        // hex or by artillery or aerospace attacks." The occupant fired first, so standard LOS lets the attacker answer.
        Board board = Board.createEmptyBoard(4, 4);
        game.setBoard(0, board);
        board.setHex(target.getPosition().coords(), new Hex(0, "building:1", ""));
        target.setDeployed(true);
        game.combatOrders().setSight(target, attacker, true, true);
        assertFalse(SBFToHitData.compileToHit(game, attack()).cannotSucceed());

        game.getOptions().getOption(SBFRuleOptions.URBAN_COMBAT).setValue(true);
        assertTrue(SBFToHitData.compileToHit(game, attack()).cannotSucceed());
        attacker.setPosition(target.getPosition());
        assertFalse(SBFToHitData.compileToHit(game, attack()).cannotSucceed());
    }

    @Test
    void adjacentCoverBlocksLosButSameHexRetainsTerrainAndUnitFireControlModifiers() {
        Board board = Board.createEmptyBoard(4, 4);
        game.setBoard(0, board);
        board.setHex(attacker.getPosition().coords(), new Hex(0, "woods:1", ""));
        board.setHex(target.getPosition().coords(), new Hex(0, "woods:2", ""));
        assertTrue(SBFToHitData.compileToHit(game, attack()).cannotSucceed());

        attacker.setPosition(target.getPosition());
        attacker.getUnits().getFirst().getSpecialAbilities().setSUA(BattleForceSUA.BFC);
        attacker.getUnits().getFirst().getSpecialAbilities().setSUA(BattleForceSUA.DRO);
        assertEquals(List.of(2), modifiers("target woods"));
        assertEquals(List.of(1), modifiers("basic fire control"));
        assertEquals(List.of(1), modifiers("drone"));
    }

    @Test
    void convertedStealthArmorRetainsItsTypeAndMimeticUsesActualMovement() {
        AlphaStrikeElement stealth = new AlphaStrikeElement();
        stealth.setType(ASUnitType.BM);
        stealth.setMovement(Map.of("", 16));
        stealth.getSpecialAbilities().setSUA(BattleForceSUA.STL);
        var report = new DummyCalculationReport();
        SBFUnit pure = new SBFUnitConverter(List.of(stealth), "pure", report).createSbfUnit();
        assertTrue(pure.hasSUA(BattleForceSUA.STL));
        assertFalse(pure.hasMimeticArmor());
        AlphaStrikeElement mimetic = new AlphaStrikeElement();
        mimetic.setType(ASUnitType.BM);
        mimetic.setMovement(Map.of("", 16));
        SBFUnit plain = new SBFUnitConverter(List.of(mimetic), "plain", report).createSbfUnit();
        assertEquals(plain.getTmm() + 2, pure.getTmm());
        mimetic.getSpecialAbilities().setSUA(BattleForceSUA.LMAS);
        SBFUnit mixed = new SBFUnitConverter(List.of(stealth, mimetic), "mixed", report).createSbfUnit();
        assertTrue(mixed.hasSUA(BattleForceSUA.LMAS));
        assertFalse(mixed.hasSUA(BattleForceSUA.STL));
        target.removeUnit(target.getUnits().getFirst());
        target.addUnit(mixed);
        target.setMovement(8);
        target.setMpSpentThisTurn(1);
        assertEquals(List.of(1), modifiers("target stealth"));
        target.setMpSpentThisTurn(2);
        assertEquals(List.of(), modifiers("target stealth"));
        // Old saves from the earlier converter carry all three flags; a plain STL Element still grants its full bonus.
        pure.getSpecialAbilities().setSUA(BattleForceSUA.MAS);
        pure.getSpecialAbilities().setSUA(BattleForceSUA.LMAS);
        target.removeUnit(mixed);
        target.addUnit(pure);
        assertEquals(List.of(1), modifiers("target stealth"));
    }

    private List<Integer> modifiers(String description) {
        return SBFToHitData.compileToHit(game, attack()).getModifiers().stream()
              .filter(modifier -> modifier.getDesc().equals(description))
              .map(TargetRollModifier::value)
              .toList();
    }

    private SBFStandardUnitAttack attack() {
        return new SBFStandardUnitAttack(attacker.getId(), 0, target.getId(), ASRange.LONG);
    }

    private SBFFormation formation(int id, int ownerId, Coords coords) {
        SBFFormation result = new SBFFormation();
        result.setId(id);
        result.setOwnerId(ownerId);
        result.setPosition(BoardLocation.of(coords, 0));
        result.addUnit(new SBFUnit());
        return result;
    }
}
