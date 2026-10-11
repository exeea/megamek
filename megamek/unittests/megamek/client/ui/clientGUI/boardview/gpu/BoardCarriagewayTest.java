/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

/**
 * Ground rails follow the ground as roads do, through the road engine itself: a rail-only hex is shaped as the road hex
 * with the same exits (ramps, hub, deck approach) and the track is draped on that ground, at the heights the road engine
 * gives the same points; on a deck it rides the drawn deck; on a drawn road only its rails show.
 */
class BoardCarriagewayTest {
    private static final Coords AT = BoardSurfaceBlendTest.CENTER;

    private static BoardScene.Feature rail(Coords at, int sides, BoardDecoration.Placement placement) {
        var marker = new BoardDecoration("rail-" + at, "prop", RailRoute.Kind.RAIL.asset, null, 0, 0, 0, false, 1, placement, 0)
              .withConnections(sides);
        return new BoardScene.Feature(marker.asset(), 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, marker);
    }

    /** A tile with a road of these exits, or rails on its ground with these sides, at {@code level}. */
    private static BoardScene.Tile tile(Coords at, int level, int sides, boolean road, List<BoardScene.Feature> more) {
        var base = BoardRoadTest.tile(at, road ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, road ? sides : 0, level,
              BoardScene.Surface.GRASS);
        List<BoardScene.Feature> features = new ArrayList<>(more);
        if (!road && sides != 0) { features.add(rail(at, sides, BoardDecoration.Placement.ground())); }
        return new BoardScene.Tile(at, level, -1, false, base.roadExits(), base.surface(), base.ground(), null, null, null,
              null, features, List.of(), BoardLiquid.NONE, null, true, base.road());
    }

    /**
     * A N-S line through {@link #AT}: its north neighbour {@code rise} levels up, as roads or as rails, with a branch
     * to the SE neighbour (a turnout) on level ground.
     */
    private static BoardScene line(boolean road, int rise) {
        Coords north = AT.translated(0), south = AT.translated(3), east = AT.translated(2);
        return BoardSurfaceBlendTest.scene(at -> at.equals(AT) ? tile(at, 0, 1 | 8 | 4, road, List.of())
              : at.equals(north) ? tile(at, rise, 8, road, List.of())
              : at.equals(south) ? tile(at, 0, 1, road, List.of())
              : at.equals(east) ? tile(at, 0, RailRoute.side(5), road, List.of())
              : tile(at, at.getX() == north.getX() && at.getY() < north.getY() ? rise : 0, 0, road, List.of()));
    }

    @Test
    void aRailOnlyHexIsShapedExactlyAsTheRoadHexWithTheSameExits() {
        for (int rise : new int[] { 0, 1, 2 }) {
            var roads = line(true, rise);
            var rails = line(false, rise);
            for (Coords at : List.of(AT, AT.translated(0), AT.translated(3), AT.translated(2))) {
                var road = roads.tile(at);
                var rail = rails.tile(at);
                assertEquals(road.roadExits(), rail.wayExits(), "rise " + rise + " " + at);
                assertEquals(0, rail.roadExits(), "no road paint under a rail");
                assertTrue(BoardRoad.carriageway(rail) && !BoardRoad.rendered(rail));
                assertEquals(BoardSurface.ramps(roads, road), BoardSurface.ramps(rails, rail), "rise " + rise + " " + at);
                assertEquals(faces(new BoardSurface(roads, road)), faces(new BoardSurface(rails, rail)), "rise " + rise + " " + at);
            }
        }
        assertNotEquals(0, BoardSurface.ramps(line(false, 1), line(false, 1).tile(AT)), "a rail climbs to its neighbour on a ramp");
    }

    @Test
    void theTrackLiesOnTheGroundTheRoadEngineGivesTheSamePoints() {
        for (int rise : new int[] { 0, 1, 2 }) {
            var roads = line(true, rise);
            var rails = line(false, rise);
            for (Coords at : List.of(AT, AT.translated(0))) {
                var tile = rails.tile(at);
                var feature = BoardRail.route(tile);
                var layout = BoardRail.layout(rails, tile, feature, new BoardSurface(rails, tile), null);
                var road = new BoardSurface(roads, roads.tile(at));
                float base = BoardRail.level(tile, feature) * BoardGeometry.level(), scale = BoardGeometry.hexScale();
                int points = 0;
                for (var path : layout.paths()) {
                    for (Vector3 point : path) {
                        float x = BoardGeometry.centerX(at) + point.x * scale, y = BoardGeometry.centerY(at) + point.y * scale;
                        assertEquals(BoardSurface.sampleHeight(road.groundFaces(), x, y, Float.NaN), base + point.z * scale,
                              .001f * scale, "rise " + rise + " " + at + " " + point);
                        points++;
                    }
                }
                assertTrue(points >= 2);
                if (rise > 0 && at.equals(AT)) {
                    assertTrue(layout.paths().stream().flatMap(List::stream).anyMatch(p -> p.z > .5f), "the track climbs the ramp");
                }
            }
        }
        // Flat ground: every point on the plane, and every flat straight of a kind shares one layout.
        var flat = line(false, 0);
        var south = flat.tile(AT.translated(3));
        var layout = BoardRail.layout(flat, south, BoardRail.route(south), new BoardSurface(flat, south), null);
        assertTrue(layout.paths().stream().flatMap(List::stream).allMatch(p -> Math.abs(p.z) < 1e-4f));
    }

    @Test
    void aDeckRailRidesTheDrawnDeckAndOnlyAlongTheBridge() {
        var bridge = new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, 1, BoardScene.FeatureKind.PROP, 9);
        var deck = rail(AT, 9 | 18, BoardDecoration.Placement.on("bridge", 0));
        var scene = BoardSurfaceBlendTest.scene(at -> at.equals(AT) ? tile(at, 0, 0, false, List.of(bridge, deck))
              : tile(at, 0, 0, false, List.of()));
        var tile = scene.tile(AT);
        assertEquals(0, tile.railExits(), "a deck rail does not shape the ground beneath");
        var layout = BoardRail.layout(scene, tile, deck, new BoardSurface(scene, tile), null);
        float expected = (BoardBridge.deckZ(tile) - BoardRail.level(tile, deck) * BoardGeometry.level()) / BoardGeometry.hexScale();
        assertEquals(1, layout.paths().size(), "only the bridge's N-S exits carry track");
        for (var point : layout.paths().getFirst()) { assertEquals(expected, point.z, 1e-4f); }
        assertEquals(1 + 8, BoardRail.drawnSides(tile, deck));
    }

    @Test
    void onADrawnRoadOnlyTheRailsShowSetIntoIt() {
        // A NE-SW road with a N-S rail across it: street track over the carriageway, full track beyond it.
        var scene = BoardSurfaceBlendTest.scene(at -> at.equals(AT)
              ? new BoardScene.Tile(at, 0, -1, false, 18, BoardScene.Surface.GRASS, BoardRoadTest.tile(at, BoardRoad.Kind.PAVED,
                    18, 0, BoardScene.Surface.GRASS).ground(), null, null, null, null, List.of(rail(at, 9, BoardDecoration.Placement.ground())),
                    List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.PAVED)
              : tile(at, 0, 0, false, List.of()));
        var tile = scene.tile(AT);
        assertEquals(18 | 9, tile.wayExits());
        var layout = BoardRail.layout(scene, tile, BoardRail.route(tile), new BoardSurface(scene, tile), null);
        var street = layout.streets().getFirst();
        assertTrue(street.contains(true) && street.contains(false));
        assertFalse(street.getFirst() && street.getLast(), "the ends beyond the road keep their ballast");
        var data = BoardRail.model(layout).meshes.first();
        var embedded = BoardRail.model(new BoardRail.Layout(layout.kind(), layout.ends(), layout.paths(),
              List.of(street.stream().map(flag -> false).toList()))).meshes.first();
        assertTrue(data.vertices.length < embedded.vertices.length, "no ballast or sleepers on the road");
        // A rail rider stands at the rail top the track draws under it (model px, as the track at any level height):
        // set into the road there, on the ballast beyond it. Other objects, or a rider on another support, get none.
        var path = layout.paths().getFirst();
        var wagon = rider("scenery/rail/box-car", BoardDecoration.Placement.ground());
        for (int i = 0; i < path.size(); i++) {
            assertEquals(street.get(i) ? BoardRail.STREET_TOP : RailRoute.RAIL_TOP,
                  BoardRail.railTop(scene, tile, wagon, path.get(i).x, path.get(i).y), 1e-6f);
        }
        assertEquals(0, BoardRail.railTop(scene, tile, rider("scenery/rail/crossing-signal", BoardDecoration.Placement.ground()), 0, 0));
        assertEquals(0, BoardRail.railTop(scene, tile, rider("scenery/rail/box-car", BoardDecoration.Placement.on("bridge", 0)), 0, 0));
    }

    private static BoardScene.Feature rider(String asset, BoardDecoration.Placement placement) {
        var object = new BoardDecoration(asset, "prop", asset, null, 0, 0, 0, false, 1, placement, 0);
        return new BoardScene.Feature(asset, 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, object);
    }

    @Test
    void groundRailsKeepCoverOffTheirTrackAndOwnTheirBufferStops() {
        var scene = line(false, 0);
        var south = scene.tile(AT.translated(3));
        var obstacles = new BoardObstacles(scene, south);
        var centre = BoardGeometry.center(south.coords(), 0);
        assertTrue(obstacles.obstructs(new Vector3(centre).add(0, 20 * BoardGeometry.hexScale(), 0), 0, 1),
              "the track's ballast band");
        assertFalse(obstacles.obstructs(new Vector3(centre).add(20 * BoardGeometry.hexScale(), 0, 0), 0, 1));
        // A dead end's stop is the rail's, at its place, riding the rail top; the hex never stores it.
        Hex hex = new Hex(0);
        var marker = rail(AT, 1, BoardDecoration.Placement.ground()).decoration();
        hex.setDecorations(List.of(marker));
        var features = BoardFeatures.capture(hex, AT, Map.of());
        var stops = features.stream().filter(feature -> feature.asset().equals(RailRoute.BUFFER_STOP)).toList();
        assertEquals(1, stops.size());
        var stop = stops.getFirst().decoration();
        assertTrue(BoardFeatures.terrainOwned(stop), "never picked or stored as an object");
        assertEquals(RailRoute.ride(marker), stop.placement());
        assertEquals(RailRoute.STOP_DEPTH / BoardGeometry.TILE_HEIGHT, stop.y(), 1e-6);
        assertEquals(1, hex.getDecorations().size());
    }

    @Test
    void railMeshesAreBoundedVertexColouredAndSharedByFlatHexes() {
        for (int mask = 0; mask < 64; mask++) {
            var nodes = new java.util.HashMap<Coords, Double>(); nodes.put(AT, 0.0);
            for (int d = 0; d < 6; d++) { if ((mask & 1 << d) != 0) { nodes.put(AT.translated(d), 0.0); } }
            var sides = new java.util.HashMap<Coords, Integer>();
            nodes.keySet().forEach(at -> {
                int m = 0;
                for (int d = 0; d < 6; d++) { if (nodes.containsKey(at.translated(d))) { m |= 1 << d; } }
                sides.put(at, m);
            });
            var scene = BoardRailTest.scene(nodes, sides, RailRoute.Kind.RAIL);
            var tile = scene.tile(AT);
            var layout = BoardRail.layout(scene, tile, BoardRail.route(tile), new BoardSurface(scene, tile), null);
            var mesh = BoardRail.model(layout).meshes.first();
            assertTrue(mesh.vertices.length / 7 < 65_536, "mask " + mask);
            for (int i = 0; i < mesh.vertices.length; i += 7) {
                assertTrue(Math.abs(mesh.vertices[i]) <= 44 && Math.abs(mesh.vertices[i + 1]) <= 40, "within its hex: " + mask);
                assertTrue(mesh.vertices[i + 2] <= RailRoute.RAIL_TOP + 1e-4f, "nothing above the rail top");
                assertEquals(1, new Vector3(mesh.vertices[i + 3], mesh.vertices[i + 4], mesh.vertices[i + 5]).len(), .001f);
            }
            // The same mask on another flat hex is the same layout, one shared mesh.
            var other = BoardRailTest.scene(Map.of(AT.translated(3), 0.0), Map.of(AT.translated(3), mask), RailRoute.Kind.RAIL);
            var otherTile = other.tile(AT.translated(3));
            if (Integer.bitCount(mask) < 2) { continue; }
            var shared = BoardRail.layout(other, otherTile, BoardRail.route(otherTile), new BoardSurface(other, otherTile), null);
            assertEquals(layout.paths(), shared.paths(), "mask " + mask);
        }
    }

    private static List<List<Vector3>> faces(BoardSurface surface) {
        return surface.faces.stream().map(face -> List.of(face.a(), face.b(), face.c())).toList();
    }
}
