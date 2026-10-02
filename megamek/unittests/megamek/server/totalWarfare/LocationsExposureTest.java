/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import static megamek.common.interfaces.ILocationExposureStatus.BREACHED;
import static megamek.common.interfaces.ILocationExposureStatus.NORMAL;
import static megamek.common.interfaces.ILocationExposureStatus.VACUUM;
import static megamek.common.interfaces.ILocationExposureStatus.WET;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.util.List;
import java.util.Vector;

import megamek.common.Hex;
import megamek.common.MMRandom;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.net.packets.Packet;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.units.WaterExposure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The exposure status the server gives every location of a unit entering a hex (the water cases of the movement fire
 * preview), and the order of the breach checks it makes. During movement a breach check rolls no dice.
 */
class LocationsExposureTest {
    private static final String UNITS = "testresources/megamek/common/units/";
    private static final int BREACH_REPORT = 6350;

    private TWGameManager manager;
    private Game game;

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        manager = spy(new TWGameManager());
        doNothing().when(manager).send(any(Packet.class));
        doNothing().when(manager).sendServerChat(anyString());
        game = manager.getGame();
        game.addPlayer(0, new Player(0, "Player"));
        game.setPhase(GamePhase.MOVEMENT);
    }

    /** Head, CT, RT, LT, RA, LA, RL, LL. */
    @Test
    void aStandingBipedInPartialWaterWetsItsLegs() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf", -1);

        manager.doSetLocationsExposure(atlas, water(1), false, -1);

        assertArrayEquals(new int[] { NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, WET, WET }, statuses(atlas));
    }

    @Test
    void aStandingQuadInPartialWaterWetsAllFourLegsRearFirst() throws Exception {
        Entity quad = unit("Barghest BGS-1T.mtf", -1);
        quad.initializeArmor(0, Mek.LOC_RIGHT_ARM);
        quad.initializeArmor(0, Mek.LOC_LEFT_LEG);

        Vector<Report> reports = manager.doSetLocationsExposure(quad, water(1), false, -1);

        assertArrayEquals(new int[] { NORMAL, NORMAL, NORMAL, NORMAL, BREACHED, WET, WET, BREACHED },
              statuses(quad));
        assertEquals(List.of(quad.getLocationAbbr(Mek.LOC_LEFT_LEG), quad.getLocationAbbr(Mek.LOC_RIGHT_ARM)),
              breachedInReportOrder(quad, reports), "rear legs are checked before front legs");
    }

    /** Head, CT, RT, LT, RA, LA, RL, LL, CL. */
    @Test
    void aStandingTripodInPartialWaterWetsItsThreeLegs() throws Exception {
        Entity tripod = unit("Triskelion TRK-4V.mtf", -1);

        manager.doSetLocationsExposure(tripod, water(1), false, -1);

        assertArrayEquals(new int[] { NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, WET, WET, WET },
              statuses(tripod));
    }

    @Test
    void aStandingSuperheavyMekStandsInDepthTwoOnItsLegs() throws Exception {
        Entity superheavy = unit("Atlas AS7-D.mtf", -2);
        superheavy.setWeight(150);

        manager.doSetLocationsExposure(superheavy, water(2), false, -2);

        assertArrayEquals(new int[] { NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, WET, WET },
              statuses(superheavy));
    }

    @Test
    void aSuperheavyMekInDepthThreeIsUnderwater() throws Exception {
        Entity superheavy = unit("Atlas AS7-D.mtf", -3);
        superheavy.setWeight(150);

        manager.doSetLocationsExposure(superheavy, water(3), false, -3);

        assertArrayEquals(new int[] { WET, WET, WET, WET, WET, WET, WET, WET }, statuses(superheavy));
    }

    @Test
    void aProneMekInPartialWaterIsUnderwater() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf", -1);
        atlas.setProne(true);

        manager.doSetLocationsExposure(atlas, water(1), false, -1);

        assertArrayEquals(new int[] { WET, WET, WET, WET, WET, WET, WET, WET }, statuses(atlas));
    }

    @Test
    void aMekInDepthTwoIsUnderwaterAndItsLocationsAreCheckedInOrder() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf", -2);
        atlas.initializeArmor(0, Mek.LOC_RIGHT_ARM);
        atlas.initializeArmor(0, Mek.LOC_RIGHT_LEG);

        Vector<Report> reports = manager.doSetLocationsExposure(atlas, water(2), false, -2);

        assertArrayEquals(new int[] { WET, WET, WET, WET, BREACHED, WET, BREACHED, WET }, statuses(atlas));
        assertEquals(List.of(atlas.getLocationAbbr(Mek.LOC_RIGHT_ARM), atlas.getLocationAbbr(Mek.LOC_RIGHT_LEG)),
              breachedInReportOrder(atlas, reports));
    }

    /** A large support vehicle is one level tall, so at elevation -1 its top is at the surface. */
    @Test
    void aUnitWhoseTopIsAtTheSurfaceKeepsItsAirStatus() throws Exception {
        Entity vehicle = unit("Dromedary Water Transport.blk", -1);
        assertEquals(0, vehicle.relHeight());

        manager.doSetLocationsExposure(vehicle, water(1), false, -1);

        for (int status : statuses(vehicle)) {
            assertEquals(NORMAL, status);
        }
    }

    @Test
    void aJumpLandingInWaterKeepsTheAirStatus() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf", -2);

        manager.doSetLocationsExposure(atlas, water(2), true, -2);

        assertArrayEquals(new int[] { NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL },
              statuses(atlas));
    }

    @Test
    void aBreachedLocationStaysBreachedInAndOutOfTheWater() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf", -1);
        atlas.setLocationStatus(Mek.LOC_LEFT_ARM, BREACHED);
        atlas.setLocationStatus(Mek.LOC_RIGHT_LEG, BREACHED);

        Vector<Report> partial = manager.doSetLocationsExposure(atlas, water(1), false, -1);
        assertArrayEquals(new int[] { NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, BREACHED, BREACHED, WET },
              statuses(atlas));

        atlas.setElevation(-2);
        Vector<Report> deep = manager.doSetLocationsExposure(atlas, water(2), false, -2);
        assertArrayEquals(new int[] { WET, WET, WET, WET, WET, BREACHED, BREACHED, WET }, statuses(atlas));

        atlas.setElevation(0);
        manager.doSetLocationsExposure(atlas, new Hex(), false, 0);
        assertArrayEquals(new int[] { NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, BREACHED, BREACHED, NORMAL },
              statuses(atlas));
        assertEquals(List.of(), breachedInReportOrder(atlas, partial));
        assertEquals(List.of(), breachedInReportOrder(atlas, deep));
    }

    @Test
    void theAirStatusOfTheDryLocationsFollowsTheAtmosphere() throws Exception {
        game.getPlanetaryConditions().setAtmosphere(Atmosphere.VACUUM);
        Entity atlas = unit("Atlas AS7-D.mtf", -1);

        manager.doSetLocationsExposure(atlas, water(1), false, -1);

        assertArrayEquals(new int[] { VACUUM, VACUUM, VACUUM, VACUUM, VACUUM, VACUUM, WET, WET }, statuses(atlas));
    }

    /** The shared decision sets the statuses and names the checks; the breach itself stays with the server. */
    @Test
    void theDecisionAloneRollsNothingAndBreachesNothing() throws Exception {
        Entity quad = unit("Barghest BGS-1T.mtf", -1);
        quad.initializeArmor(0, Mek.LOC_LEFT_LEG);
        Compute.setRNG(new NoDice());
        try {
            List<Integer> checks = WaterExposure.apply(quad, water(1), false, -1, game.getPlanetaryConditions());

            assertEquals(List.of(Mek.LOC_RIGHT_LEG, Mek.LOC_LEFT_LEG, Mek.LOC_RIGHT_ARM, Mek.LOC_LEFT_ARM), checks);
            assertArrayEquals(new int[] { NORMAL, NORMAL, NORMAL, NORMAL, WET, WET, WET, WET }, statuses(quad));
            assertEquals(List.of(), WaterExposure.apply(quad, null, false, -1, game.getPlanetaryConditions()));
            assertArrayEquals(new int[] { NORMAL, NORMAL, NORMAL, NORMAL, WET, WET, WET, WET }, statuses(quad));
        } finally {
            Compute.setRNG(MMRandom.R_DEFAULT);
        }
    }

    /** A breached leg in partial water is not named for a check, as in deep water. */
    @Test
    void theDecisionLeavesOutABreachedLeg() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf", -1);
        atlas.setLocationStatus(Mek.LOC_RIGHT_LEG, BREACHED);

        List<Integer> checks = WaterExposure.apply(atlas, water(1), false, -1, game.getPlanetaryConditions());

        assertEquals(List.of(Mek.LOC_LEFT_LEG), checks);
        assertArrayEquals(new int[] { NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, BREACHED, WET }, statuses(atlas));
    }

    private Entity unit(String file, int elevation) throws Exception {
        Entity unit = new MekFileParser(new File(UNITS + file)).getEntity();
        unit.setId(1);
        unit.setOwner(game.getPlayer(0));
        game.addEntity(unit);
        unit.setElevation(elevation);
        return unit;
    }

    private static Hex water(int depth) {
        Hex hex = new Hex();
        hex.addTerrain(new Terrain(Terrains.WATER, depth));
        return hex;
    }

    private static int[] statuses(Entity unit) {
        int[] statuses = new int[unit.locations()];
        for (int location = 0; location < statuses.length; location++) {
            statuses[location] = unit.getLocationStatus(location);
        }
        return statuses;
    }

    /** The abbreviations of the locations the breach reports name, in report order. */
    private static List<String> breachedInReportOrder(Entity unit, Vector<Report> reports) {
        return reports.stream().filter(report -> report.messageId == BREACH_REPORT).map(Report::text)
              .map(text -> abbreviationIn(unit, text)).toList();
    }

    private static String abbreviationIn(Entity unit, String text) {
        for (int location = 0; location < unit.locations(); location++) {
            if (text.contains(" " + unit.getLocationAbbr(location) + " BREACHED")) {
                return unit.getLocationAbbr(location);
            }
        }
        throw new AssertionError("no location named in " + text);
    }

    /** Fails the test on any die. */
    private static final class NoDice extends MMRandom {
        @Override
        public int randomInt(int maxValue) {
            throw new AssertionError("no die expected");
        }

        @Override
        public float randomFloat() {
            throw new AssertionError("no die expected");
        }
    }
}
