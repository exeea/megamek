/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardRoadControlTest {
    private static final Coords AT = new Coords(3, 3);

    private static List<BoardScene.Feature> controls(Hex hex) {
        return BoardFeatures.roadControls(hex, AT, at -> at.equals(AT) ? hex : null, true);
    }

    private static boolean northernControl(Hex hex, Hex north) {
        return BoardFeatures.roadControls(hex, AT, at -> at.equals(AT) ? hex : at.equals(AT.translated(0)) ? north : null, true)
              .stream().anyMatch(feature -> feature.decoration().id().endsWith(":stop:0"));
    }

    private static void stops(Hex hex, boolean deck) {
        var appearance = new java.util.HashMap<>(hex.getAppearance());
        String owner = deck ? "bridgeRoad" : "road";
        appearance.put(owner, HexAppearance.withTrafficControl(appearance.get(owner), HexAppearance.TrafficControl.STOPS));
        hex.setAppearance(appearance);
    }

    @Test
    void roadsAndBridgeDecksDefaultToNativeGantrySignalsWithoutStoredAppearance() {
        for (String terrain : List.of("road:1:27", "bridge:1:27;bridge_cf:40;bridge_elev:2;bridge_road:1:27")) {
            Hex hex = new Hex(0, terrain, "");
            assertTrue(hex.getAppearance().isEmpty());
            var controls = controls(hex);
            assertEquals(4, controls.size());
            for (var control : controls) {
                assertEquals("scenery/road/traffic-signal-gantry", control.asset());
                assertEquals(megamek.common.board.BoardDecoration.Stretch.NONE, control.stretch(),
                      "The ordinary X crossing uses the authored mast and lamp proportions by default");
            }
            assertTrue(hex.getAppearance().isEmpty(), "Derived defaults do not add stored overrides");
        }
    }

    @Test
    void signsFaceWaitingTrafficAndBelongToTheRoad() {
        Hex hex = new Hex(0, "road:1:27", "");
        stops(hex, false);
        var signs = controls(hex);
        assertEquals(4, signs.size());
        assertTrue(hex.getDecorations().isEmpty(), "Derived roadside furniture is never saved as placed objects");
        var road = BoardRoad.layout(AT, 27, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.NONE);
        for (var sign : signs) {
            assertEquals("scenery/road/stop-sign", sign.asset());
            assertTrue(BoardFeatures.terrainOwned(sign.decoration()), "Controls cannot be selected as loose props");
            assertEquals("ground", sign.decoration().placement().receiver().terrain());
            assertFalse(road.footprint(0).contains(sign.x(), sign.y()), "The pole belongs beside the carriageway");
            double angle = Math.toRadians(sign.rotation());
            double facingX = -Math.sin(angle), facingY = Math.cos(angle);
            assertTrue(facingX * sign.x() + facingY * sign.y() > 20, "Faces point away from the junction");
            assertTrue(facingX * sign.y() - facingY * sign.x() > 7, "Signs stand on the incoming right-hand side");
        }
        assertEquals(signs, controls(hex), "Recapture keeps stable ids and transforms");
        hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, 9));
        assertTrue(controls(hex).isEmpty(), "Removing a branch removes its junction controls");
    }

    @Test
    void controlsStandOnTheCompleteJunctionShoulderAtTheirStopPlane() {
        for (int exits = 0; exits < 64; exits++) {
            if (Integer.bitCount(exits) < 3 || Integer.bitCount(exits) > 4) { continue; }
            for (boolean deck : new boolean[] { false, true }) {
                for (int rails = 0; rails < 3; rails++) {
                    String roadTerrain = deck ? "bridge:1:" + exits + ";bridge_cf:40;bridge_elev:2;bridge_road:1:" : "road:1:";
                    Hex hex = new Hex(0, roadTerrain + exits, "");
                    if (rails != 0) {
                        hex.addTerrain(new Terrain(deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL, 1, true, exits));
                        if (rails == 2) {
                            hex.setAppearance(Map.of(deck ? "bridgeRail" : "rail", HexAppearance.rail(0, 64 | exits)));
                        }
                    }
                    stops(hex, deck);
                    var corridor = RailRoute.corridor(hex, AT, at -> at.equals(AT) ? hex : null, deck);
                    var road = BoardRoad.layout(AT, exits, BoardRoad.Kind.PAVED, direction -> BoardRoad.Kind.NONE).corridor(corridor);
                    var signs = controls(hex);
                    if (rails != 2) { assertEquals(Integer.bitCount(exits), signs.size()); }
                    for (var sign : signs) {
                        assertFalse(road.footprint(0).contains(sign.x(), sign.y()), "The full turn envelope stays free of poles");
                        assertTrue(road.footprint(BoardRoad.SHOULDER).contains(sign.x(), sign.y()),
                              "The pole remains on the road/deck shoulder");
                        assertTrue(HexTile.contains(0, 0, sign.x() + HexTile.WIDTH / 2f,
                              sign.y() - HexTile.HEIGHT / 2f, HexTile.WIDTH, HexTile.HEIGHT),
                              "The support stays in its hex: exits=" + exits + " deck=" + deck + " rails=" + rails
                                    + " sign=" + sign.x() + "," + sign.y());
                        int direction = Integer.parseInt(sign.decoration().id().substring(sign.decoration().id().lastIndexOf(':') + 1));
                        var approach = road.stopApproaches(exits).stream().filter(a -> a.direction() == direction).findFirst().orElseThrow();
                        assertEquals(0, (sign.x() - approach.x()) * approach.nx() + (sign.y() - approach.y()) * approach.ny(),
                              .0001f, "The control remains directly beside its stop bar");
                    }
                }
            }
        }
    }

    @Test
    void crowdedBesideRailJunctionKeepsItsStopBarsWhenAShoulderHasNoSupportForAPole() {
        for (boolean deck : new boolean[] { false, true }) {
            Hex hex = new Hex(0, deck ? "bridge:1:39;bridge_cf:40;bridge_elev:2;bridge_road:1:39;bridge_rail:1:39"
                  : "road:1:39;rail:1:39", "");
            hex.setAppearance(Map.of(deck ? "bridgeRail" : "rail", HexAppearance.rail(0, 64 | 39)));
            stops(hex, deck);
            var signs = controls(hex);
            assertTrue(!signs.isEmpty() && signs.size() < 4, "Available shoulders keep their controls; unsupported ones are omitted");
            var corridor = RailRoute.corridor(hex, AT, at -> at.equals(AT) ? hex : null, deck);
            var road = BoardRoad.layout(AT, 39, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.NONE).corridor(corridor);
            var paint = road.stopLines(39);
            assertEquals(4, road.stopApproaches(39).size(), "Limited pole support does not change the junction's stop planes");
            for (var approach : road.stopApproaches(39)) {
                float across = (approach.inner() + approach.outer()) / 2;
                assertTrue(paint.contains(approach.x() - approach.ny() * across, approach.y() + approach.nx() * across),
                      "Every approach retains its stop bar");
            }
        }
    }

    @Test
    void appearanceChangesOnlyTheFurnitureAndDeckControlsStayOnDeck() {
        Hex hex = new Hex(0, "road:1:27;bridge:1:27;bridge_cf:40;bridge_elev:2;bridge_road:1:27", "");
        hex.setAppearance(Map.of("bridgeRoad", HexAppearance.withTrafficControl(null, HexAppearance.TrafficControl.SIGNALS)));
        stops(hex, false);
        var features = controls(hex);
        assertEquals(8, features.size());
        assertEquals(4, features.stream().filter(f -> f.asset().equals("scenery/road/stop-sign")).count());
        var signals = features.stream().filter(f -> f.asset().equals("scenery/road/traffic-signal")).toList();
        assertEquals(4, signals.size());
        assertTrue(signals.stream().allMatch(f -> f.decoration().placement().receiver().terrain().equals("bridge")));
        hex.addTerrain(new Terrain(Terrains.WATER, 1));
        assertEquals(signals, controls(hex), "Submerged ground roads must not plant signals beneath the bridge");
        assertEquals(signals, BoardFeatures.roadControls(hex, AT, at -> null, false));
    }

    @Test
    void gantryHeadsFaceTheirIncomingLaneAndKeepTheirAuthoredHeight() {
        for (int exits : new int[] { 7, 11, 21, 27, 39, 45, 54 }) {
            for (boolean deck : new boolean[] { false, true }) {
                for (int rails = 0; rails < 3; rails++) {
                    Hex hex = new Hex(0, (deck ? "bridge:1:" + exits + ";bridge_cf:40;bridge_elev:2;bridge_road:1:" : "road:1:") + exits, "");
                    var appearance = new java.util.HashMap<String, HexAppearance>();
                    if (rails != 0) {
                        hex.addTerrain(new Terrain(deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL, 1, true, exits));
                        if (rails == 2) { appearance.put(deck ? "bridgeRail" : "rail", HexAppearance.rail(0, 64 | exits)); }
                    }
                    hex.setAppearance(appearance);
                    var corridor = RailRoute.corridor(hex, AT, at -> at.equals(AT) ? hex : null, deck);
                    var road = BoardRoad.layout(AT, exits, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.NONE).corridor(corridor);
                    int gantries = 0;
                    for (var control : controls(hex)) {
                        assertEquals(1, control.stretch().y());
                        assertEquals(1, control.stretch().z(), "Gantry clearance keeps its authored real-world height");
                        if (!control.asset().equals("scenery/road/traffic-signal-gantry")) {
                            assertEquals("scenery/road/traffic-signal", control.asset(), "A cramped shoulder falls back to its roadside signal");
                            continue;
                        }
                        gantries++;
                        int direction = Integer.parseInt(control.decoration().id().substring(control.decoration().id().lastIndexOf(':') + 1));
                        var approach = road.stopApproaches(exits).stream().filter(a -> a.direction() == direction).findFirst().orElseThrow();
                        double stretch = control.stretch().x();
                        assertTrue(stretch >= .85 && stretch <= 1.25);
                        double angle = Math.toRadians(control.rotation()), rightX = Math.cos(angle), rightY = Math.sin(angle);
                        assertEquals(-1, rightX * -approach.ny() + rightY * approach.nx(), .0001,
                              "The cantilever extends inward from the incoming shoulder");
                        for (float head : new float[] { 3.36f, 6.72f }) {
                            double x = control.x() + rightX * head * stretch, y = control.y() + rightY * head * stretch;
                            double across = -(x - approach.x()) * approach.ny() + (y - approach.y()) * approach.nx();
                            assertTrue(across - .7 * stretch >= approach.inner() - .001
                                  && across + .7 * stretch <= approach.outer() + .001, "The full lamp stays above its incoming lane");
                            assertTrue(road.footprint(0).contains(x, y));
                        }
                        for (int side : new int[] { -1, 1 }) {
                            for (int along : new int[] { -1, 1 }) {
                                double x = control.x() + rightX * side * .588 * stretch - rightY * along * .588;
                                double y = control.y() + rightY * side * .588 * stretch + rightX * along * .588;
                                assertFalse(road.footprint(0).contains(x, y), "The footing does not intrude into asphalt");
                                assertTrue(road.footprint(BoardRoad.SHOULDER).contains(x, y), "The full footing stands on the shoulder");
                                assertTrue(HexTile.contains(0, 0, (float) x + HexTile.WIDTH / 2f,
                                      (float) y - HexTile.HEIGHT / 2f, HexTile.WIDTH, HexTile.HEIGHT));
                            }
                        }
                        if (exits == 27 && rails == 0) { assertEquals(1, stretch, "The ordinary X crossing keeps native mast and lamp proportions"); }
                    }
                    if (exits == 27 && rails == 0) { assertEquals(4, gantries, "An ordinary X crossing supports overhead controls on every approach"); }
                }
            }
        }
    }

    @Test
    void unmarkedRoadsRoundaboutsAndUnsupportedGroundDoNotGrowStopFurniture() {
        for (String terrain : List.of("road:2:27", "road:3:27", "road:4:27", "road:1:9", "road:1:63",
              "road:1:27;water:1", "road:1:27;hazardous_liquid:1", "road:1:27;magma:2")) {
            assertTrue(controls(new Hex(0, terrain, "")).isEmpty(), terrain);
        }
        Hex hex = new Hex(0, "road:1:27", "");
        assertTrue(BoardFeatures.roadControls(hex, AT, at -> null, false).isEmpty());
    }

    @Test
    void groundControlsFollowTheChosenBridgeRoadInsteadOfTheRoadBelowIt() {
        Hex road = new Hex(2, "road:1:27", "");
        assertFalse(northernControl(road, new Hex(0,
              "bridge:1:8;bridge_cf:40;bridge_elev:2;bridge_road:2:8;road:1:8", "")),
              "An approaching alley deck has neither stop bar nor control despite a paved road below it");
        assertTrue(northernControl(road, new Hex(0,
              "bridge:1:8;bridge_cf:40;bridge_elev:2;bridge_road:1:8;road:2:8", "")),
              "The paved deck wins over an unrelated alley below it");
        assertFalse(northernControl(road, new Hex(2,
              "bridge:1:8;bridge_cf:40;bridge_elev:2;bridge_road:1:8;road:2:8", "")),
              "A level ground continuation wins over a bridge above it");
        assertTrue(northernControl(road, new Hex(1,
              "bridge:1:8;bridge_cf:40;bridge_elev:1;bridge_road:1:8;road:2:8", "")),
              "A level deck wins over a sloped ground continuation");
    }

    @Test
    void deckControlsFollowJoinedDecksAndGroundLandingsOnly() {
        Hex deck = new Hex(0, "bridge:1:27;bridge_cf:40;bridge_elev:2;bridge_road:1:27", "");
        assertFalse(northernControl(deck, new Hex(2, "road:2:8", "")), "The deck's alley landing is unmarked");
        assertTrue(northernControl(deck, new Hex(2, "road:1:8", "")), "A paved landing retains its deck control");
        assertTrue(northernControl(deck, new Hex(0, "road:2:8", "")), "An alley too far below cannot suppress a deck control");
        assertFalse(northernControl(deck, new Hex(0,
              "bridge:1:8;bridge_cf:40;bridge_elev:2;bridge_road:2:8;road:1:8", "")),
              "Joined deck paint follows the neighboring deck's material");
        assertTrue(northernControl(deck, new Hex(0,
              "bridge:1:8;bridge_cf:40;bridge_elev:5;bridge_road:2:8", "")),
              "An unconnected bridge above the junction cannot suppress its control");
    }
}
