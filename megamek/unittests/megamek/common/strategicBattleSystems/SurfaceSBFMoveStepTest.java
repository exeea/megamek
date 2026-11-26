/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
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

import static megamek.common.alphaStrike.BattleForceSUA.AMP;
import static megamek.common.alphaStrike.BattleForceSUA.SEAL;
import static megamek.common.alphaStrike.BattleForceSUA.LG;
import static megamek.common.alphaStrike.BattleForceSUA.UMU;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import megamek.client.ui.clientGUI.calculationReport.DummyCalculationReport;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.options.SBFRuleOptions;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SurfaceSBFMoveStepTest {
    private final SBFGame game = new SBFGame();
    private final SBFFormation formation = new SBFFormation();
    private final BoardLocation start = BoardLocation.of(new Coords(0, 0), 0);
    private final BoardLocation end = BoardLocation.of(new Coords(0, 1), 0);
    private final Hex from = new Hex();
    private final Hex to = new Hex();

    @BeforeEach
    void setUp() {
        Board board = new Board(1, 2);
        board.setHex(0, 0, from);
        board.setHex(0, 1, to);
        game.setBoard(0, board);
        game.addPlayer(1, new Player(1, "Owner"));
        formation.setOwnerId(1);
        formation.setId(10);
        formation.setPosition(start);
        formation.setDeployed(true);
        game.addUnit(formation);
    }

    @Test
    void mappedWaterRequiresTheRightMovementModeOrEquipment() {
        setMover(SBFElementType.V, SBFMovementMode.NAVAL);
        assertTrue(step().isIllegal());
        to.addTerrain(new Terrain(Terrains.WATER, 1));
        assertEquals(1, step().getMpUsed());
        setMover(SBFElementType.V, SBFMovementMode.TRACKED);
        assertTrue(step().isIllegal());
        formation.getSpecialAbilities().setSUA(AMP);
        assertEquals(2, step().getMpUsed());
    }

    @Test
    void infantryAndIndustrialMeksNeedUnderwaterEquipment() {
        to.addTerrain(new Terrain(Terrains.WATER, 2));
        setMover(SBFElementType.CI, SBFMovementMode.INFANTRY_FOOT);
        assertTrue(step().isIllegal());
        formation.getSpecialAbilities().setSUA(UMU);
        assertEquals(9, step().getMpUsed());

        AlphaStrikeElement industrial = new AlphaStrikeElement();
        industrial.setType(ASUnitType.IM);
        industrial.setPrimaryMovementMode("");
        SBFUnit unit = new SBFUnit();
        unit.setType(SBFElementType.BM);
        unit.setElements(List.of(industrial));
        formation.addUnit(unit);
        assertTrue(step().isIllegal());
        industrial.getSpecialAbilities().setSUA(SEAL);
        assertFalse(step().isIllegal());
        assertEquals(9, step().getMpUsed());
    }

    @Test
    void battleArmorUsesInfantryCostsForWoodsElevationAndUnderwaterMovement() {
        var squad = new AlphaStrikeElement();
        squad.setType(ASUnitType.BA);
        squad.setPrimaryMovementMode("");
        var unit = new SBFUnit();
        unit.setType(SBFElementType.BA);
        unit.setElements(List.of(squad));
        formation.setUnits(List.of(unit));
        setMover(SBFElementType.BA, SBFMovementMode.BA_WALK);

        to.addTerrain(new Terrain(Terrains.WOODS, 1));
        assertEquals(1, step().getMpUsed());
        to.removeTerrain(Terrains.WOODS);
        to.setLevel(1);
        assertEquals(3, step().getMpUsed());
        to.setLevel(0);
        from.setLevel(1);
        assertEquals(3, step().getMpUsed());
        from.setLevel(0);

        to.addTerrain(new Terrain(Terrains.WATER, 1));
        assertTrue(step().isIllegal());
        to.addTerrain(new Terrain(Terrains.WATER, 2));
        assertTrue(step().isIllegal());
        squad.getSpecialAbilities().setSUA(UMU);
        assertFalse(step().isIllegal());
        assertEquals(9, step().getMpUsed());
    }

    @Test
    void convertedBattleArmorRetainsUnderwaterEligibilityOnItsSummarySheet() {
        var diver = new AlphaStrikeElement();
        diver.setName("Diver");
        diver.setType(ASUnitType.BA);
        diver.setMovement(Map.of("", 4, "s", 2));
        diver.getSpecialAbilities().setSUA(UMU);
        var walker = new AlphaStrikeElement();
        walker.setName("Walker");
        walker.setType(ASUnitType.BA);
        walker.setMovement(Map.of("", 4));
        var report = new DummyCalculationReport();
        var mixed = new SBFUnitConverter(List.of(diver, walker), "Mixed squads", report).createSbfUnit();
        assertFalse(mixed.hasSUA(UMU));
        var equipped = new SBFUnitConverter(List.of(diver), "Divers", report).createSbfUnit();
        assertTrue(equipped.hasSUA(UMU));
        formation.setUnits(List.of(equipped));
        SBFFormationConverter.calculateStatsFromUnits(formation);
        assertTrue(formation.hasSUA(UMU));

        to.addTerrain(new Terrain(Terrains.WATER, 1));
        equipped.setElements(List.of());
        assertFalse(step().isIllegal());
        formation.setUnits(List.of());
        assertFalse(step().isIllegal());
    }

    @Test
    void aMixedMekAndBattleArmorFormationPaysBothTheWoodsAndElevationModifiers() {
        var battleArmor = new SBFUnit();
        battleArmor.setType(SBFElementType.BA);
        battleArmor.setMovementMode(SBFMovementMode.BA_WALK);
        var mek = new SBFUnit();
        mek.setType(SBFElementType.BM);
        mek.setMovementMode(SBFMovementMode.MEK_WALK);
        formation.setType(SBFElementType.MX);
        formation.setUnits(List.of(battleArmor, mek));
        to.addTerrain(new Terrain(Terrains.WOODS, 1));
        to.setLevel(1);
        assertEquals(4, step().getMpUsed());
        to.setLevel(0);
        from.setLevel(1);
        assertEquals(4, step().getMpUsed());
    }

    @Test
    void waterWithGroundTerrainCostsItsRolledWidth() {
        // Water, IO:BF p. 166: "For 2-6 TW Hexes, add +1 MP, and for 7-12 TW hexes add +2 MP"; water only adds +2
        setMover(SBFElementType.BM, SBFMovementMode.MEK_WALK);
        to.addTerrain(new Terrain(Terrains.WATER, 1));
        to.setStrategicWaterWidth(4);
        assertEquals(4, step().getMpUsed());
        to.addTerrain(new Terrain(Terrains.ROUGH, 1));
        assertEquals(3, step().getMpUsed());
        to.setStrategicWaterWidth(7);
        assertEquals(4, step().getMpUsed());
        to.setStrategicWaterWidth(0);
        assertEquals(4, step().getMpUsed());
    }

    @Test
    void aMixedFormationGainsNoBonusThatOnlySomeOfItsMoversHave() {
        // Terrain Modifiers on Movement, p. 166: "all Elements in the Formation must have this mode or type ... to gain
        // the bonus". Infantry and an LG 'Mech each reduce woods by 1 under advanced terrain; together neither does.
        game.getOptions().getOption(SBFRuleOptions.MOVE_ADVANCED_TERRAIN).setValue(true);
        var infantry = new SBFUnit();
        infantry.setType(SBFElementType.CI);
        infantry.setMovementMode(SBFMovementMode.INFANTRY_FOOT);
        var largeMek = new SBFUnit();
        largeMek.setType(SBFElementType.BM);
        largeMek.setMovementMode(SBFMovementMode.MEK_WALK);
        largeMek.getSpecialAbilities().setSUA(LG);
        formation.setType(SBFElementType.MX);
        formation.setUnits(List.of(infantry, largeMek));
        to.addTerrain(new Terrain(Terrains.WOODS, 1));
        assertEquals(2, step().getMpUsed());
        formation.setUnits(List.of(largeMek));
        assertEquals(1, step().getMpUsed());
    }

    @Test
    void eachTerrainModifierIsPaidAtItsMostRestrictiveMover() {
        // p. 166: "If any Element in a Formation must pay additional MP to cross a given terrain, then treat the entire
        // Formation as having to pay that cost": infantry pays for the sand, the hovercraft for the rough
        game.getOptions().getOption(SBFRuleOptions.MOVE_ADVANCED_TERRAIN).setValue(true);
        var infantry = new SBFUnit();
        infantry.setType(SBFElementType.CI);
        infantry.setMovementMode(SBFMovementMode.INFANTRY_FOOT);
        var hover = new SBFUnit();
        hover.setType(SBFElementType.V);
        hover.setMovementMode(SBFMovementMode.HOVER);
        formation.setType(SBFElementType.MX);
        formation.setUnits(List.of(infantry, hover));
        to.addTerrain(new Terrain(Terrains.SAND, 1));
        to.addTerrain(new Terrain(Terrains.ROUGH, 1));
        assertEquals(4, step().getMpUsed());
    }

    @Test
    void exclusivelyWheeledFormationsPayOneLessInUrbanHexes() {
        // Urban table note *, IO:BF p. 216: "Formations made up exclusively of wheeled Elements may reduce all movement
        // costs by 1 MP ... the movement penalty for moving through a Medium Urban hex would be +1, not +2."
        game.getOptions().getOption(SBFRuleOptions.MOVE_ADVANCED_TERRAIN).setValue(true);
        to.addTerrain(new Terrain(Terrains.BUILDING, 2));
        setMover(SBFElementType.V, SBFMovementMode.TRACKED);
        assertEquals(3, step().getMpUsed());
        setMover(SBFElementType.V, SBFMovementMode.WHEELED);
        assertEquals(2, step().getMpUsed());
    }

    @Test
    void pavedCrossingBypassesWaterAndObstructingTerrain() {
        setMover(SBFElementType.V, SBFMovementMode.WHEELED);
        to.addTerrain(new Terrain(Terrains.WATER, 2));
        to.addTerrain(new Terrain(Terrains.WOODS, 2));
        assertTrue(step().isIllegal());
        to.addTerrain(new Terrain(Terrains.BRIDGE, 1));
        assertFalse(step().isIllegal());
        assertEquals(1, step().getMpUsed());
    }

    @Test
    void urbanTerrainAndHillsUseInfantryAndVehicleCosts() {
        to.setLevel(1);
        to.addTerrain(new Terrain(Terrains.BUILDING, 4));
        setMover(SBFElementType.V, SBFMovementMode.TRACKED);
        assertEquals(7, step().getMpUsed());
        setMover(SBFElementType.BA, SBFMovementMode.BA_WALK);
        assertEquals(5, step().getMpUsed());
        setMover(SBFElementType.BM, SBFMovementMode.MEK_WALK);
        assertEquals(6, step().getMpUsed());
        to.setLevel(2);
        setMover(SBFElementType.PM, SBFMovementMode.MEK_WALK);
        assertTrue(step().isIllegal());
    }

    @Test
    void everyConstituentMustBeAbleToFlyOverWoods() {
        setMover(SBFElementType.V, SBFMovementMode.VTOL);
        to.addTerrain(new Terrain(Terrains.WOODS, 2));
        assertFalse(step().isIllegal());
        assertEquals(1, step().getMpUsed());
        SBFUnit flyer = new SBFUnit();
        flyer.setType(SBFElementType.V);
        flyer.setMovementMode(SBFMovementMode.VTOL);
        SBFUnit tank = new SBFUnit();
        tank.setType(SBFElementType.V);
        tank.setMovementMode(SBFMovementMode.TRACKED);
        tank.setArmor(4);
        formation.setUnits(List.of(flyer, tank));
        assertFalse(step().isIllegal());
        assertEquals(2, step().getMpUsed());
        game.getOptions().getOption(SBFRuleOptions.MOVE_EXPANDED_WOODS).setValue(true);
        assertTrue(step().isIllegal());
        tank.setCurrentArmor(0);
        assertFalse(step().isIllegal());
    }

    @Test
    void vtolIgnoresElevationAndStationaryWiGESpendsOneMp() {
        setMover(SBFElementType.V, SBFMovementMode.VTOL);
        to.setLevel(4);
        to.addTerrain(new Terrain(Terrains.WOODS, 3));
        assertFalse(step().isIllegal());
        assertEquals(1, step().getMpUsed());
        setMover(SBFElementType.V, SBFMovementMode.WIGE);
        formation.setMovement(5);
        assertEquals(1, new SBFMovePath(formation.getId(), start, game).getMpUsed());
    }

    @Test
    void surfacedSubmarineCanEnterMixedWaterAndSubmergingRequiresOpenWater() {
        setMover(SBFElementType.V, SBFMovementMode.SUBMARINE);
        from.addTerrain(new Terrain(Terrains.WATER, 2));
        to.addTerrain(new Terrain(Terrains.WATER, 2));
        to.addTerrain(new Terrain(Terrains.ROUGH, 1));
        assertTrue(step().isIllegal());
        assertFalse(formation.canChangeSubmarineDepth(to, false));
        assertTrue(formation.canChangeSubmarineDepth(from, false));
        formation.setSubmarineSurfaced(true);
        assertFalse(step().isIllegal());
        to.removeTerrain(Terrains.ROUGH);
        formation.setSubmarineSurfaced(false);
        assertFalse(step().isIllegal());
    }

    @Test
    void connectedRailCannotCrossRubbleOrWoods() {
        setMover(SBFElementType.V, SBFMovementMode.RAIL);
        from.addTerrain(new Terrain(Terrains.RAIL, 1, true, 63));
        to.addTerrain(new Terrain(Terrains.RAIL, 1, true, 63));
        assertFalse(step().isIllegal());
        to.addTerrain(new Terrain(Terrains.RUBBLE, 1));
        assertTrue(step().isIllegal());
        to.removeTerrain(Terrains.RUBBLE);
        to.addTerrain(new Terrain(Terrains.WOODS, 1));
        assertTrue(step().isIllegal());
    }

    @Test
    void aPassageConnectsOnlyItsChosenHexsidesAndNeverEarnsARoadBonus() {
        game.setBoard(0, Board.createEmptyBoard(6, 6));
        var woods = BoardLocation.of(new Coords(2, 2), 0);
        var north = woods.translated(0);
        var south = woods.translated(3);
        var west = woods.translated(5);
        var trees = game.getHex(woods);
        trees.addTerrain(new Terrain(Terrains.WOODS, 2));
        trees.addStrategicPassage(Hex.strategicPassage(0, 3));
        game.getOptions().getOption(SBFRuleOptions.MOVE_EXPANDED_WOODS).setValue(true);
        setMover(SBFElementType.V, SBFMovementMode.TRACKED);
        formation.setMovement(6);
        formation.setPosition(north);
        var path = new SBFMovePath(formation.getId(), north, game);
        path.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), north, woods));
        var turning = SBFMovePath.createMovePathShallow(path);
        path.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), woods, south));
        assertFalse(path.isIllegal());
        assertEquals(2, path.getTerrainCost());
        assertFalse(path.earnsRoadBonus());
        turning.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), woods, west));
        assertTrue(turning.isIllegal());
        assertTrue(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), west, woods).isIllegal());

        // A vehicle that stopped on the passage still cannot turn sideways into prohibited woods next turn.
        formation.setPosition(woods);
        formation.setPassageEntrance(0);
        var nextTurn = new SBFMovePath(formation.getId(), woods, game);
        nextTurn.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), woods, west));
        assertTrue(nextTurn.isIllegal());
    }

    private void setMover(SBFElementType type, SBFMovementMode mode) {
        formation.setType(type);
        formation.setMovementMode(mode);
    }

    private SBFMoveStep step() {
        return SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), start, end);
    }
}
