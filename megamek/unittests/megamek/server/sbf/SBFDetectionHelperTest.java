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

package megamek.server.sbf;

import static megamek.common.alphaStrike.BattleForceSUA.BH;
import static megamek.common.alphaStrike.BattleForceSUA.LPRB;
import static megamek.common.alphaStrike.BattleForceSUA.PRB;
import static megamek.common.alphaStrike.BattleForceSUA.RCN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import megamek.common.Player;
import megamek.common.actions.sbf.SBFAirGroundAttack;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.force.Force;
import megamek.common.force.Forces;
import megamek.common.options.OptionsConstants;
import megamek.common.options.SBFRuleOptions;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.Weather;
import megamek.common.rolls.Roll;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SBFUnitPlaceHolder;
import megamek.common.strategicBattleSystems.SBFVisibilityStatus;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SBFDetectionHelperTest {

    @Test
    void mixedAndProtoMekObserversUseTheBattleMekVehicleVisualColumn() {
        var game = new SBFGame();
        var observer = deploy(game, 0, 20);
        for (var type : List.of(SBFElementType.MX, SBFElementType.PM, SBFElementType.CI, SBFElementType.BA)) {
            observer.setType(type);
            game.getPlanetaryConditions().setLight(Light.DAY);
            game.getPlanetaryConditions().setWeather(Weather.CLEAR);
            boolean infantry = type == SBFElementType.CI || type == SBFElementType.BA;
            assertEquals(infantry ? 2 : 4, SBFDetectionHelper.visualRange(game, observer));
            game.getPlanetaryConditions().setWeather(Weather.LIGHT_RAIN);
            assertEquals(infantry ? 1 : 2, SBFDetectionHelper.visualRange(game, observer));
            game.getPlanetaryConditions().setWeather(Weather.SLEET);
            assertEquals(1, SBFDetectionHelper.visualRange(game, observer));
            game.getPlanetaryConditions().setLight(Light.PITCH_BLACK);
            assertEquals(0, SBFDetectionHelper.visualRange(game, observer));
        }
    }

    @Test
    void observersOnTheRoofsSeeFartherAndInfantryUseTheBattleMekVehicleColumn() {
        // Urban Hexes, IO:BF p. 217: "For every 4 levels of building height, Visual Range ... is increased by 1 hex to a
        // maximum of 2 hexes. Additionally, infantry units use the visual ranges for BattleMechs and Vehicles."
        var game = new SBFGame();
        game.setBoard(0, Board.createEmptyBoard(2, 2));
        game.getPlanetaryConditions().setLight(Light.DAY);
        game.getPlanetaryConditions().setWeather(Weather.CLEAR);
        var observer = deploy(game, 0, 20);
        observer.setType(SBFElementType.CI);
        var hex = game.getHex(observer.getPosition());
        hex.addTerrain(new Terrain(Terrains.BUILDING, 2));
        hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, 9));
        game.getOptions().getOption(SBFRuleOptions.URBAN_COMBAT).setValue(true);
        assertEquals(2, SBFDetectionHelper.visualRange(game, observer));
        observer.setOnTopOfBuildings(true);
        assertEquals(6, SBFDetectionHelper.visualRange(game, observer));
        hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, 5));
        assertEquals(5, SBFDetectionHelper.visualRange(game, observer));
    }

    @Test
    void hailLightningAndIceStormsUseVisualThreeWithNormalLightingAndSearchlightRules() {
        var game = new SBFGame();
        var observer = deploy(game, 0, 20);
        observer.getSpecialAbilities().setSUA(megamek.common.alphaStrike.BattleForceSUA.SRCH);
        for (Weather storm : List.of(Weather.LIGHT_HAIL, Weather.HEAVY_HAIL, Weather.LIGHTNING_STORM,
              Weather.ICE_STORM)) {
            game.getPlanetaryConditions().setWeather(storm);
            game.getPlanetaryConditions().setLight(Light.DAY);
            assertEquals(1, SBFDetectionHelper.visualRange(game, observer), storm.name());
            game.getPlanetaryConditions().setLight(Light.DUSK_DAWN);
            assertEquals(2, SBFDetectionHelper.visualRange(game, observer), storm.name());
            game.getPlanetaryConditions().setLight(Light.PITCH_BLACK);
            assertEquals(1, SBFDetectionHelper.visualRange(game, observer), storm.name());
        }
    }

    @Test
    void bestProbeOrBasicSensorsWinAndReconOnlyExtendsBasicSensors() {
        var game = new SBFGame();
        SBFFormation infantry = new SBFFormation();
        infantry.setType(SBFElementType.BA);
        assertEquals(5, SBFDetectionModifiers.sensorRange(game, infantry));
        infantry.setType(SBFElementType.MX);
        SBFUnit mek = new SBFUnit();
        mek.setType(SBFElementType.BM);
        infantry.addUnit(mek);
        assertEquals(6, SBFDetectionModifiers.sensorRange(game, infantry));
        infantry.getSpecialAbilities().setSUA(LPRB);
        assertEquals(7, SBFDetectionModifiers.sensorRange(game, infantry));
        var element = new megamek.common.alphaStrike.AlphaStrikeElement();
        element.getSpecialAbilities().setSUA(PRB);
        mek.setElements(List.of(element));
        assertEquals(8, SBFDetectionModifiers.sensorRange(game, infantry));
        element.setClanActiveProbe(true);
        assertEquals(9, SBFDetectionModifiers.sensorRange(game, infantry));
        mek.setElements(List.of());
        infantry.setType(SBFElementType.BM);
        infantry.getSpecialAbilities().setSUA(RCN);
        assertEquals(8, SBFDetectionModifiers.sensorRange(game, infantry));
        infantry.getSpecialAbilities().setSUA(PRB);
        infantry.getSpecialAbilities().setSUA(BH);
        assertEquals(10, SBFDetectionModifiers.sensorRange(game, infantry));
    }

    @Test
    void improvedSensorsAddTwoHexesWhenHalfTheUnitsElementsHaveThem() {
        // Design Quirks Table, IO:BF p. 228: Improved Sensors "If 50% of Elements in Unit have PQIS, then increase
        // detection range by 2 hexes"; only with the optional Design Quirks rules (p. 226)
        var game = new SBFGame();
        var formation = new SBFFormation();
        formation.setType(SBFElementType.BM);
        var unit = new SBFUnit();
        unit.setType(SBFElementType.BM);
        var sensors = new AlphaStrikeElement();
        sensors.setType(ASUnitType.BM);
        sensors.getQuirks().getOption(OptionsConstants.QUIRK_POS_IMPROVED_SENSORS).setValue(true);
        var plain = new AlphaStrikeElement();
        plain.setType(ASUnitType.BM);
        unit.setElements(List.of(sensors, plain));
        formation.addUnit(unit);
        assertEquals(6, SBFDetectionModifiers.sensorRange(game, formation));
        game.getOptions().getOption(SBFRuleOptions.DESIGN_QUIRKS).setValue(true);
        assertEquals(8, SBFDetectionModifiers.sensorRange(game, formation));
        unit.setElements(List.of(sensors, plain, plain));
        assertEquals(6, SBFDetectionModifiers.sensorRange(game, formation));
    }

    @Test
    void sameHexGivesEyesOnTargetAndVoluntaryScansUseRealMovementPoints() {
        SBFGame game = new SBFGame();
        SBFGameManager manager = new SBFGameManager();
        manager.setGame(game);
        game.setBoard(0, Board.createEmptyBoard(8, 8));
        game.setPhase(GamePhase.SBF_DETECTION_REPORT);
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        SBFFormation viewer = deploy(game, 0, 20);
        SBFFormation target = deploy(game, 1, 21);
        game.getTeamForPlayer(game.getPlayer(0)).getInitiative().addRoll(20, "");
        game.getTeamForPlayer(game.getPlayer(1)).getInitiative().addRoll(-20, "");
        assertFalse(manager.initiativeHelper.hasReconPriority(1));
        assertEquals(SBFVisibilityStatus.EYES_ON_TARGET, SBFDetectionHelper.identifyVisually(game, viewer, target,
              () -> { throw new AssertionError("A shared hex needs no dice"); }));
        target.setPosition(BoardLocation.of(new Coords(6, 0), 0));
        game.visibilityHelper().setVisibility(0, 21, SBFVisibilityStatus.BLIP);
        viewer.setMovement(2);
        assertFalse(manager.detectionHelper.scan(1, viewer, target, () -> 12));
        assertTrue(manager.detectionHelper.scan(0, viewer, target, () -> 2));
        assertEquals(SBFVisibilityStatus.VISIBLE, game.getVisibility(game.getPlayer(1), 20));
        assertEquals(SBFVisibilityStatus.BLIP, game.getVisibility(game.getPlayer(0), 21));
        assertEquals(1, SBFMovePath.normalMovementPoints(viewer, game));
        assertTrue(manager.detectionHelper.scan(0, viewer, target, () -> 12));
        assertEquals(0, SBFMovePath.normalMovementPoints(viewer, game));
        assertEquals(SBFVisibilityStatus.VISIBLE, game.getVisibility(game.getPlayer(0), 21));
        assertFalse(manager.detectionHelper.scan(0, viewer, target, () -> 12));
        game.getPlayer(0).setDone(true);
        assertTrue(manager.initiativeHelper.hasReconPriority(1));
    }

    @Test
    void simpleTablesUseModifiedSevenAndKeepDetectionSeparateFromIdentification() {
        assertEquals(SBFVisibilityStatus.INVISIBLE, SBFDetectionHelper.sensorDetectionResult(6, true));
        assertEquals(SBFVisibilityStatus.BLIP, SBFDetectionHelper.sensorDetectionResult(7, true));
        assertEquals(SBFVisibilityStatus.BLIP, SBFDetectionHelper.sensorDetectionResult(12, true));
        SBFGame game = new SBFGame();
        SBFGameManager manager = new SBFGameManager();
        manager.setGame(game);
        game.setBoard(0, Board.createEmptyBoard(8, 8));
        game.setPhase(GamePhase.SBF_DETECTION_REPORT);
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        game.getOptions().getOption(SBFRuleOptions.SIMPLE_RECON).setValue(true);
        SBFFormation viewer = deploy(game, 0, 20);
        SBFFormation target = deploy(game, 1, 21);
        game.getTeamForPlayer(game.getPlayer(0)).getInitiative().addRoll(20, "");
        game.getTeamForPlayer(game.getPlayer(1)).getInitiative().addRoll(-20, "");
        viewer.setMovement(2);
        target.setPosition(BoardLocation.of(new Coords(4, 0), 0));
        assertEquals(SBFVisibilityStatus.BLIP, SBFDetectionHelper.identifyVisually(game, viewer, target, () -> 6));
        assertEquals(SBFVisibilityStatus.VISIBLE, SBFDetectionHelper.identifyVisually(game, viewer, target, () -> 7));
        target.setPosition(BoardLocation.of(new Coords(6, 0), 0));
        game.visibilityHelper().setVisibility(0, 21, SBFVisibilityStatus.BLIP);
        assertTrue(manager.detectionHelper.scan(0, viewer, target, () -> 6));
        assertEquals(SBFVisibilityStatus.BLIP, game.getVisibility(game.getPlayer(0), 21));
        assertTrue(manager.detectionHelper.scan(0, viewer, target, () -> 7));
        assertEquals(SBFVisibilityStatus.VISIBLE, game.getVisibility(game.getPlayer(0), 21));
    }

    @Test
    void urbanContactUsesEachEnteredHexAndTheModifiedRollOfSixLosesContact() {
        SBFGame game = new SBFGame();
        SBFGameManager manager = new SBFGameManager();
        manager.setGame(game);
        game.setBoard(0, Board.createEmptyBoard(14, 8));
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        SBFFormation viewer = deploy(game, 0, 20);
        SBFFormation target = deploy(game, 1, 21);
        BoardLocation urban = BoardLocation.of(new Coords(0, 3), 0);
        game.getHex(urban).addTerrain(new Terrain(Terrains.BUILDING, 1));
        game.visibilityHelper().setVisible(0, 21);
        manager.detectionHelper.enterUrban(target, urban, () -> 12);
        assertEquals(SBFVisibilityStatus.VISIBLE, game.getVisibility(game.getPlayer(0), 21));
        manager.detectionHelper.enterUrban(target, urban, () -> 7);
        assertEquals(SBFVisibilityStatus.BLIP, game.getVisibility(game.getPlayer(0), 21));
        manager.detectionHelper.enterUrban(target, urban, () -> 4);
        assertEquals(SBFVisibilityStatus.BLIP, game.getVisibility(game.getPlayer(0), 21));
        manager.detectionHelper.enterUrban(target, urban, () -> 3);
        assertEquals(SBFVisibilityStatus.INVISIBLE, game.getVisibility(game.getPlayer(0), 21));
        game.getHex(urban).addTerrain(new Terrain(Terrains.BUILDING, 4));
        game.visibilityHelper().setVisible(0, 21);
        manager.detectionHelper.enterUrban(target, urban, () -> 12);
        assertEquals(SBFVisibilityStatus.BLIP, game.getVisibility(game.getPlayer(0), 21));
        assertEquals(0, viewer.getReconMpSpent());
    }

    @Test
    void groundContactIdentifiesAMovingBlipAndCombatOpensHardenedCoverOnlyWhileOccupied() {
        SBFGame game = new SBFGame();
        SBFGameManager manager = new SBFGameManager();
        manager.setGame(game);
        game.setBoard(0, Board.createEmptyBoard(4, 4));
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        SBFFormation viewer = deploy(game, 0, 20);
        SBFFormation mover = deploy(game, 1, 21);
        BoardLocation start = BoardLocation.of(new Coords(1, 0), 0);
        mover.setPosition(start);
        game.visibilityHelper().setVisibility(0, 21, SBFVisibilityStatus.BLIP);
        manager.detectionHelper.enterGroundHex(mover, start, viewer.getPosition(), () -> 12);
        assertEquals(SBFVisibilityStatus.EYES_ON_TARGET, game.getVisibility(game.getPlayer(0), 21));
        mover.setPosition(viewer.getPosition());
        game.getHex(mover.getPosition()).addTerrain(new Terrain(Terrains.BUILDING, 4));
        game.visibilityHelper().setVisibility(0, 21, SBFVisibilityStatus.BLIP);
        assertEquals(SBFVisibilityStatus.BLIP, SBFDetectionHelper.identifyVisually(game, viewer, mover, () -> 12));
        game.addAction(new SBFStandardUnitAttack(21, 0, 20, ASRange.LONG));
        manager.detectionHelper.finishCombatRecon(() -> 12);
        assertEquals(SBFVisibilityStatus.EYES_ON_TARGET, SBFDetectionHelper.identifyVisually(game, viewer, mover, () -> 12));
        manager.detectionHelper.enterGroundHex(mover, mover.getPosition(), start, () -> 12);
        assertEquals(SBFVisibilityStatus.BLIP, SBFDetectionHelper.identifyVisually(game, viewer, mover, () -> 12));
    }

    @Test
    void sharedIntelPersistsUntilTheLastAlliedSensorLeavesRange() {
        SBFGame game = new SBFGame();
        SBFGameManager manager = new SBFGameManager();
        manager.setGame(game);
        game.setBoard(0, Board.createEmptyBoard(15, 2));
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        game.getOptions().getOption(SBFRuleOptions.BASE_TEAM_VISION).setValue(true);
        SBFFormation viewer = deploy(game, 0, 20);
        deploy(game, 1, 21);
        SBFFormation ally = deploy(game, 2, 22);
        game.getPlayer(0).setTeam(1);
        game.getPlayer(2).setTeam(1);
        game.setupTeams();
        game.visibilityHelper().setVisible(0, 21);
        game.visibilityHelper().setVisibility(2, 21, SBFVisibilityStatus.BLIP);
        viewer.setPosition(BoardLocation.of(new Coords(14, 0), 0));
        manager.detectionHelper.endMovement();
        assertEquals(SBFVisibilityStatus.VISIBLE, game.getVisibility(game.getPlayer(2), 21));
        ally.setPosition(viewer.getPosition());
        manager.detectionHelper.endMovement();
        assertEquals(SBFVisibilityStatus.INVISIBLE, game.getVisibility(game.getPlayer(2), 21));
    }

    @Test
    void airStrafeAndIndirectOrderCannotDiscloseAnUnseenSecondaryTargetOrSpotter() {
        SBFGameManager manager = new SBFGameManager();
        SBFGame game = new SBFGame();
        manager.setGame(game);
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        deploy(game, 0, 20);
        deploy(game, 1, 21);
        deploy(game, 2, 22);
        deploy(game, 3, 23);
        Player owner = game.getPlayer(0);
        Player observer = game.getPlayer(1);
        game.visibilityHelper().setVisible(1, 20);
        game.visibilityHelper().setVisible(0, 21);
        game.visibilityHelper().setVisibility(0, 22, SBFVisibilityStatus.BLIP);
        game.visibilityHelper().setVisible(0, 23);
        var strafe = new SBFAirGroundAttack(20, SBFAirGroundAttack.Type.STRAFE, List.of(21, 22), List.of(0), List.of());
        var indirect = new SBFStandardUnitAttack(20, 0, 21, ASRange.LONG, SBFStandardUnitAttack.WeaponMode.INDIRECT, 23);
        game.addAction(strafe);
        game.addAction(indirect);
        assertEquals(List.of(strafe, indirect), manager.createActionsPacket(owner).getObject(0));
        assertEquals(List.of(), manager.createActionsPacket(observer).getObject(0));
        game.visibilityHelper().setVisible(1, 22);
        assertEquals(List.of(strafe), manager.createActionsPacket(observer).getObject(0));
        game.visibilityHelper().setVisible(1, 23);
        assertEquals(List.of(strafe, indirect), manager.createActionsPacket(observer).getObject(0));
    }

    @Test
    void aQuietFlyoverLocatesExposedGroundForcesAndScansFromItsNearestFlightPathHex() {
        SBFGame game = new SBFGame();
        SBFGameManager manager = new SBFGameManager();
        manager.setGame(game);
        game.setBoard(0, Board.createEmptyBoard(20, 5));
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        game.setPhase(GamePhase.FIRING);
        SBFFormation scout = deploy(game, 0, 20);
        scout.setType(SBFElementType.AS);
        scout.getSpecialAbilities().setSUA(LPRB);
        SBFFormation first = deploy(game, 1, 21);
        first.addUnit(new SBFUnit());
        SBFFormation second = deploy(game, 2, 22);
        second.addUnit(new SBFUnit());
        second.setPosition(BoardLocation.of(new Coords(7, 0), 0));
        SBFFormation concealed = deploy(game, 3, 23);
        concealed.addUnit(new SBFUnit());
        concealed.setPosition(BoardLocation.of(new Coords(9, 2), 0));
        game.getHex(concealed.getPosition()).addTerrain(new Terrain(Terrains.BUILDING, 1));
        var path = IntStream.range(0, 8).mapToObj(x -> BoardLocation.of(new Coords(x, 0), 0)).toList();
        scout.setRadarPosition(SBFRadarZone.CENTRAL, path);
        game.setTurns(List.of(new SBFFormationTurn(0)));
        game.changeToNextTurn();
        game.visibilityHelper().setVisibility(0, 21, SBFVisibilityStatus.BLIP);
        game.visibilityHelper().setVisibility(0, 22, SBFVisibilityStatus.BLIP);
        assertFalse(manager.detectionHelper.orderAerialScan(0, scout, first));
        game.visibilityHelper().enteredCentral(20, path);
        scout.setRadarPosition(SBFRadarZone.INNER, List.of());
        assertTrue(manager.detectionHelper.orderAerialScan(0, scout, first));
        assertFalse(manager.detectionHelper.orderAerialScan(0, scout, second));
        scout.getSpecialAbilities().setSUA(RCN);
        assertFalse(manager.detectionHelper.orderAerialScan(0, scout, first));
        assertTrue(manager.detectionHelper.orderAerialScan(0, scout, second));
        manager.detectionHelper.finishAerialRecon(() -> 5);
        assertEquals(SBFVisibilityStatus.VISIBLE, game.getVisibility(game.getPlayer(0), 21));
        assertEquals(SBFVisibilityStatus.VISIBLE, game.getVisibility(game.getPlayer(0), 22));
        assertEquals(SBFVisibilityStatus.INVISIBLE, game.getVisibility(game.getPlayer(0), 23));

        game.visibilityHelper().setInvisible(0, 21);
        game.visibilityHelper().setVisibility(0, 22, SBFVisibilityStatus.BLIP);
        game.addAction(new SBFStandardUnitAttack(21, 0, 20, ASRange.LONG));
        manager.detectionHelper.finishAerialRecon(() -> { throw new AssertionError("A Squadron under attack cannot scan"); });
        assertEquals(SBFVisibilityStatus.INVISIBLE, game.getVisibility(game.getPlayer(0), 21));
        game.clearActions();
        scout.setRadarPosition(SBFRadarZone.INNER, List.of());
        manager.detectionHelper.finishAerialRecon(() -> { throw new AssertionError("Passive detection uses no dice"); });
        assertEquals(SBFVisibilityStatus.BLIP, game.getVisibility(game.getPlayer(0), 21));
    }

    private static SBFFormation deploy(SBFGame game, int owner, int id) {
        game.addPlayer(owner, new Player(owner, "Player " + owner));
        SBFFormation formation = new SBFFormation();
        formation.setOwnerId(owner);
        formation.setId(id);
        formation.setType(SBFElementType.BM);
        formation.setDeployed(true);
        formation.setPosition(BoardLocation.of(new Coords(0, 0), 0));
        game.addUnit(formation);
        return formation;
    }

    @Test
    void destroyedSoleBloodhoundNoLongerExtendsTheSurvivorsRange() {
        var game = new SBFGame();
        var f = new SBFFormation();
        f.setType(SBFElementType.BM);
        f.getSpecialAbilities().setSUA(BH);
        var survivor = new SBFUnit();
        survivor.setType(SBFElementType.BM);
        survivor.setArmor(8);
        var element = new megamek.common.alphaStrike.AlphaStrikeElement();
        element.setType(megamek.common.alphaStrike.ASUnitType.BM);
        survivor.setElements(List.of(element));
        f.addUnit(survivor);
        var scout = new SBFUnit();
        scout.setType(SBFElementType.BM);
        scout.setArmor(5);
        var probe = new megamek.common.alphaStrike.AlphaStrikeElement();
        probe.setType(megamek.common.alphaStrike.ASUnitType.BM);
        probe.getSpecialAbilities().setSUA(BH);
        scout.setElements(List.of(probe));
        f.addUnit(scout);
        assertEquals(10, SBFDetectionModifiers.sensorRange(game, f));
        f.removeUnit(scout);
        assertEquals(6, SBFDetectionModifiers.sensorRange(game, f));
    }

    @Test
    void sensorPingNamesTheObserverAndTheSideOrVertexSeenFromItsHex() {
        var observer = new SBFFormation();
        observer.setName("Scout");
        var sides = List.of("N", "NE", "SE", "S", "SW", "NW");
        for (var origin : List.of(new Coords(6, 6), new Coords(7, 6))) {
            observer.setPosition(BoardLocation.of(origin, 0));
            for (int side = 0; side < sides.size(); side++) {
                int next = (side + 1) % sides.size();
                var across = origin.translated(side);
                assertTrue(SBFDetectionHelper.sensorPing(observer, BoardLocation.of(across, 0)).text()
                      .contains("Scout detects a sensor ping toward the " + sides.get(side) + " hexside."));
                assertTrue(SBFDetectionHelper.sensorPing(observer, BoardLocation.of(across.translated(next), 0)).text()
                      .contains("Scout detects a sensor ping toward the point between the " + sides.get(side)
                            + " and " + sides.get(next) + " hexsides."));
            }
        }
    }

    @Test
    void onlyFreshPingsReportBearingsToTheDetectingPlayer() {
        var game = new SBFGame();
        var manager = new SBFGameManager();
        manager.setGame(game);
        game.setBoard(0, Board.createEmptyBoard(18, 18));
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        var targetHex = new Coords(8, 8);
        var observer = deploy(game, 0, 20);
        observer.setName("North scout");
        observer.setPosition(BoardLocation.of(targetHex.translated(0, 6), 0));
        var second = deploy(game, 0, 21);
        second.setName("South scout");
        second.setPosition(BoardLocation.of(targetHex.translated(3, 6), 0));
        var target = deploy(game, 1, 22);
        target.setPosition(BoardLocation.of(targetHex, 0));
        target.setType(SBFElementType.CI);
        var owner = game.getPlayer(0);
        var roll = Mockito.mock(Roll.class);
        try (var dice = Mockito.mockStatic(Compute.class, Mockito.CALLS_REAL_METHODS)) {
            dice.when(() -> Compute.rollD6(2)).thenReturn(roll);
            Mockito.when(roll.getIntValue()).thenReturn(6);
            manager.detectionHelper.performSensorDetection();
            assertEquals(SBFVisibilityStatus.SENSOR_PING, game.getVisibility(owner, 22));
            var reports = manager.getPendingReports();
            var visible = game.getGameReport().visibleTo(owner, reports);
            assertEquals(2, visible.size());
            assertTrue(visible.stream().anyMatch(r -> r.text().contains("North scout detects a sensor ping toward the S hexside.")));
            assertTrue(visible.stream().anyMatch(r -> r.text().contains("South scout detects a sensor ping toward the N hexside.")));
            assertTrue(game.getGameReport().visibleTo(game.getPlayer(1), reports).isEmpty());
            assertNull(manager.unitUpdateHelper.representationFor(owner, target));

            manager.clearPendingReports();
            Mockito.when(roll.getIntValue()).thenReturn(2);
            manager.detectionHelper.performSensorDetection();
            assertEquals(SBFVisibilityStatus.SENSOR_PING, game.getVisibility(owner, 22));
            assertTrue(manager.getPendingReports().isEmpty());

            // At night, only the closer scout's +3 range modifier converts a roll of 2 into a Ping.
            game.getPlanetaryConditions().setLight(Light.PITCH_BLACK);
            second.setPosition(BoardLocation.of(targetHex.translated(3, 3), 0));
            manager.detectionHelper.performSensorDetection();
            visible = game.getGameReport().visibleTo(owner, manager.getPendingReports());
            assertEquals(1, visible.size());
            assertTrue(visible.getFirst().text().contains("South scout detects a sensor ping toward the N hexside."));

            // A roll of 6 gives the northern scout a Ping and the southern scout a Solid Lock.
            manager.clearPendingReports();
            Mockito.when(roll.getIntValue()).thenReturn(6);
            manager.detectionHelper.performSensorDetection();
            assertEquals(SBFVisibilityStatus.BLIP, game.getVisibility(owner, 22));
            assertTrue(manager.getPendingReports().isEmpty());
        }
    }

    @Test
    void sensorGhostReportsOnlyWhichOfTheTwoThreeHexsideArcsContainsTheContact() {
        var viewer = new SBFFormation();
        Coords center = new Coords(4, 4);
        viewer.setPosition(BoardLocation.of(center, 0));
        for (int front = 0; front < 6; front++) {
            viewer.setReconFacing(front);
            for (int offset = 0; offset < 6; offset++) {
                var target = BoardLocation.of(center.translated((front + offset) % 6), 0);
                assertEquals(offset <= 1 || offset == 5, SBFDetectionHelper.inFrontOf(viewer, target));
            }
        }
    }

    @Test
    void aSolidLockShowsOnlyTheHexAndWeakerContactsNeverTransmitItsCoordinates() {
        SBFGame game = new SBFGame();
        SBFGameManager manager = new SBFGameManager();
        manager.setGame(game);
        Player viewer = new Player(0, "Viewer");
        game.addPlayer(0, viewer);
        game.addPlayer(1, new Player(1, "Other"));
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        SBFFormation target = new SBFFormation();
        target.setId(42);
        target.setOwnerId(1);
        target.setPosition(BoardLocation.of(new Coords(3, 2), 0));
        game.addUnit(target);

        game.getForces().addTopLevelForce(new Force("Friendly", 0, viewer.getCamouflage()), viewer);
        game.getForces().addTopLevelForce(new Force("Hostile", 0, viewer.getCamouflage()), game.getPlayer(1));
        var friendlyRoot = game.getForces().getTopLevelForces().stream().filter(f -> f.getOwnerId() == 0).findFirst().orElseThrow();
        game.getForces().addEntity(target, friendlyRoot.getId());
        var forces = assertInstanceOf(Forces.class, manager.createGameStartUnitPacket(viewer).getObject(2));
        assertTrue(forces.getForce(friendlyRoot.getId()).getEntities().isEmpty());
        assertEquals(List.of("Friendly"), forces.getAllForces().stream().map(Force::getName).toList());
        assertEquals(2, game.getForces().getTopLevelForces().size());
        var hostileRoot = game.getForces().getTopLevelForces().stream().filter(f -> f.getOwnerId() == 1).findFirst().orElseThrow();
        int child = game.getForces().addSubForce(new Force("Detached ally", 0, viewer.getCamouflage()), hostileRoot);
        // Forming a combined allied force is legal. Losing the alliance later must protect both rosters.
        viewer.setTeam(1);
        game.getPlayer(1).setTeam(1);
        game.getForces().assignForceOnly(game.getForces().getForce(child), viewer);
        game.getPlayer(1).setTeam(2);
        var rebuilt = assertInstanceOf(Forces.class, manager.createGameStartUnitPacket(viewer).getObject(2));
        assertEquals(2, rebuilt.getTopLevelForces().size());
        assertTrue(rebuilt.getForce(child).isTopLevel());
        assertTrue(hostileRoot.getSubForces().contains(child));

        for (int roll : List.of(2, 4, 6)) {
            game.visibilityHelper().setVisibility(0, 42, SBFDetectionHelper.sensorDetectionResult(roll, false));
            assertNull(manager.unitUpdateHelper.representationFor(viewer, target));
            assertEquals(List.of(), manager.createGameStartUnitPacket(viewer).getObject(0));
        }
        assertEquals(SBFVisibilityStatus.BLIP, SBFDetectionHelper.sensorDetectionResult(9, false));
        game.visibilityHelper().setVisibility(0, 42, SBFDetectionHelper.sensorDetectionResult(7, false));
        var packet = manager.createGameStartUnitPacket(viewer);
        var contact = assertInstanceOf(SBFUnitPlaceHolder.class, ((List<?>) packet.getObject(0)).getFirst());
        assertEquals(SBFUnitPlaceHolder.class, contact.getClass());
        assertEquals(target.getPosition(), contact.getPosition());
    }
}
