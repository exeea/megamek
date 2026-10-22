/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardRoadRampTest {
    @Test
    void cityDescendingForkKeepsTheLowerThroughRoadLevel() {
        var scene = BoardCliffSeamTest.scene(new File(
              "data/boards/unofficial/Lanceman/16x17 City Hills Residential 2 (with Structures).board"));
        var south = new Coords(13, 11);
        var north = new Coords(13, 10);
        var gate = BoardGeometry.center(south, 0).lerp(BoardGeometry.center(north, 0), .5f);
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
            for (var at : List.of(south, north)) {
                var surface = new BoardSurface(scene, scene.tile(at), lod);
                // Both level-zero roads also climb to 1312. That higher third hex must not lift
                // their shared, level mouth into an asphalt ridge across the through lanes.
                for (float offset : new float[] { -12, 0, 12 }) {
                    assertEquals(0, surface.height(gate.x + offset * BoardGeometry.hexScale(), gate.y), .002f,
                          "The lower through-road mouth stays level across both lanes: " + at + "/" + lod);
                }
                var road = BoardRoad.way(scene, scene.tile(at));
                var center = BoardGeometry.center(at, 0);
                for (var face : surface.groundFaces()) {
                    if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                    var point = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                    if (road.distance((point.x - center.x) / BoardGeometry.hexScale(),
                          (point.y - center.y) / BoardGeometry.hexScale()) >= -.5f) { continue; }
                    var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    if (normal.len2() < .000001f) { continue; }
                    assertTrue(normal.nor().z > .6f,
                          "The fork must not place a steep bank through the road: " + at + "/" + lod + " " + face);
                }
            }
        }
    }

    @Test
    void fireAndIceInsideBendSharesItsRoadMouthHeightAcrossAllThreeHexes() throws Exception {
        var scene = GpuRoadSourceTest.scene("unofficial/Drewbacca/16x17 Fire And Ice 2.board");
        var at = new Coords(0, 6);
        float mouth = .5f * BoardGeometry.level();
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
            var bend = new BoardSurface(scene, scene.tile(at), lod);
            var corner = bend.relief.rim(5).getFirst().point();
            assertEquals(mouth, corner.z, .001f,
                  "The two climbs at 0107 must not keep an isolated level-one peak between their mouths");
            for (var owner : List.of(at, new Coords(1, 6), new Coords(0, 7))) {
                var surface = owner.equals(at) ? bend : new BoardSurface(scene, scene.tile(owner), lod);
                assertEquals(mouth, BoardSurface.sampleHeight(surface.groundFaces(), corner.x, corner.y, Float.NaN),
                      .002f, "All three emitted banks must support their common corner: " + owner + "/" + lod);
            }
            for (int direction : new int[] { 2, 3 }) {
                var next = scene.tile(at.translated(direction));
                var gate = BoardGeometry.center(at, scene.tile(at).elevation())
                      .lerp(BoardGeometry.center(next.coords(), next.elevation()), .5f);
                assertEquals(mouth, bend.height(gate.x, gate.y), .002f,
                      "The inside corner meets the actual road-mouth grade");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void aSingleRoadGateGradesItsCornerAndClosesTheUntouchedBank(int direction) {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            var next = at.translated(direction);
            var bank = at.translated((direction + 1) % 6);
            for (int bankLevel : new int[] { 0, 1, 2 }) {
                var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
                      c.equals(at) || c.equals(next) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                      c.equals(at) ? 1 << direction : c.equals(next) ? 1 << ((direction + 3) % 6) : 0,
                      c.equals(next) ? 1 : c.equals(bank) ? bankLevel : 0, BoardScene.Surface.GRASS));
                for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                    var road = new BoardSurface(scene, scene.tile(at), lod);
                    var corner = road.relief.rim(Math.floorMod(1 - direction, 6)).getFirst().point();
                    assertEquals((bankLevel == 0 ? 0 : bankLevel == 1 ? .5f : 1) * BoardGeometry.level(), corner.z, .001f,
                          "Equal lower ground stays flat outside the road; a higher bank shares its cut");
                    var ground = new BoardSurface(scene, scene.tile(bank), lod);
                    var center = BoardGeometry.center(bank, bankLevel);
                    assertEquals(center.z, ground.height(center.x, center.y), .002f,
                          "The adjoining plateau keeps its own elevation");
                    if (bankLevel <= 1) {
                        float nearest = Float.POSITIVE_INFINITY;
                        for (var face : ground.groundFaces()) {
                            for (var point : List.of(face.a(), face.b(), face.c())) {
                                nearest = Math.min(nearest, point.dst(corner));
                            }
                        }
                        assertTrue(nearest < .003f,
                              "The natural bank must meet the road corner, without a vertical soil fence: " + lod);
                    }
                    BoardRoadSlopeTest.assertClosed(scene, at, lod);
                }
            }
        }
    }

    @Test
    void citySingleClimbLowersItsShoulderWithoutLoweringThePlateau() {
        var scene = BoardCliffSeamTest.scene(new File(
              "data/boards/unofficial/Lanceman/16x17 City Hills Residential 2 (with Structures).board"));
        var upper = new Coords(11, 13);
        var lower = new Coords(12, 14);
        var plateau = new Coords(12, 13);
        for (var lod : TerrainLod.values()) {
            var road = new BoardSurface(scene, scene.tile(upper), lod);
            for (int edge : new int[] { 0, 5 }) {
                assertEquals((edge == 0 ? .5f : 0) * BoardGeometry.level(), road.relief.rim(edge).getFirst().point().z, .002f,
                      "The high-side bank shares the cut while the two lower plateaus keep their common corner flat");
            }
            var ground = new BoardSurface(scene, scene.tile(plateau), lod);
            var center = BoardGeometry.center(plateau, scene.tile(plateau).elevation());
            assertEquals(center.z, ground.height(center.x, center.y), .002f,
                  "The level-one plateau at 1314 must remain at its authored elevation");
            for (var at : List.of(upper, lower, plateau)) { BoardRoadSlopeTest.assertClosed(scene, at, lod); }
        }
    }

    @Test
    void cityRoadCornersGradeTheirAdjoiningGroundWithoutTriangularSoilFences() throws Exception {
        var scene = BoardCliffSeamTest.capturedScene(
              "unofficial/Lanceman/16x17 City Hills Residential 2 (with Structures).board");
        var centers = List.of(new Coords(7, 13), new Coords(2, 5));
        for (var lod : TerrainLod.values()) {
            var surfaces = new HashMap<Coords, BoardSurface>();
            for (var tile : scene.tiles()) {
                if (centers.stream().anyMatch(at -> at.distance(tile.coords()) <= 2)) {
                    surfaces.put(tile.coords(), new BoardSurface(scene, tile, lod));
                }
            }
            int checked = 0;
            for (var surface : surfaces.values()) {
                var tile = surface.tile;
                if (centers.stream().noneMatch(at -> at.distance(tile.coords()) <= 1)) { continue; }
                if (tile.liquid().present() || tile.surface() == BoardScene.Surface.CONCRETE) { continue; }
                for (var face : surface.walls(scene, BoardGeometry.floor(scene), surfaces)) {
                    var next = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(face.landEdge())));
                    if (next == null || next.elevation() != tile.elevation() || next.liquid().present()
                          || next.surface() == BoardScene.Surface.CONCRETE) { continue; }
                    float high = Math.max(face.a().z, Math.max(face.b().z, face.c().z));
                    float low = Math.min(face.a().z, Math.min(face.b().z, face.c().z));
                    assertTrue(high - low < .05f * BoardGeometry.hexScale(),
                          "A lowered road corner must grade the adjoining ground instead of exposing a soil fence: "
                                + tile.coords() + "/" + lod + " " + face);
                }
                checked++;
            }
            assertTrue(checked >= 6, "Exercise both reported junctions and their adjoining natural ground");
            for (var at : centers) { BoardRoadSlopeTest.assertClosed(scene, at, lod); }
        }
    }

    @Test
    void mesaClimbPreservesTheLevelThroughRoadAcrossBothLanesAndClosesItsBanks() {
        var scene = BoardCliffSeamTest.scene(new File(
              "data/boards/unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board"));
        var bend = new Coords(26, 21);
        var through = new Coords(27, 20);
        var a = BoardGeometry.center(bend, scene.tile(bend).elevation());
        var b = BoardGeometry.center(through, scene.tile(through).elevation());
        var mouth = new Vector3(a).lerp(b, .5f);
        var across = new Vector3(b.y - a.y, a.x - b.x, 0).nor();
        for (var lod : TerrainLod.values()) {
            for (var at : List.of(bend, through)) {
                var surface = new BoardSurface(scene, scene.tile(at), lod);
                for (float offset : new float[] { -12, 0, 12 }) {
                    var point = new Vector3(mouth).mulAdd(across, offset * BoardGeometry.hexScale());
                    assertEquals(mouth.z, surface.height(point.x, point.y), .002f,
                          "The climb from 2721 must not pull either lane of the 2722–2821 level road down: " + lod);
                }
                BoardRoadSlopeTest.assertClosed(scene, at, lod);
            }
        }
    }

    @Test
    void widenedCarriagewaysKeepTheirBanksOutsideThePavementOnReportedBoards() {
        record Example(String path, Coords at, float minimumUp) {
            Example(String path, Coords at) { this(path, at, .6f); }
        }
        String fireAndIce = "unofficial/Drewbacca/16x17 Fire And Ice 2.board";
        for (var example : List.of(
              new Example("unofficial/Cartes/16x17 Plaine 1.board", new Coords(9, 11)),
              new Example("Map Set 3/16x17 City Hills Residential 1.board", new Coords(6, 7)),
              new Example(fireAndIce, new Coords(0, 7)),
              new Example(fireAndIce, new Coords(1, 6)),
              new Example(fireAndIce, new Coords(2, 7)),
              new Example(fireAndIce, new Coords(7, 7)),
              // This two-level bend has a steeper original grade; relief must not compress it into a vertical fin.
              new Example(fireAndIce, new Coords(13, 6), .4f))) {
            var scene = BoardCliffSeamTest.scene(new File("data/boards/" + example.path()));
            var tile = scene.tile(example.at());
            var road = BoardRoad.of(scene, tile);
            var center = BoardGeometry.center(example.at(), tile.elevation());
            float scale = BoardGeometry.hexScale();
            for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                var surface = new BoardSurface(scene, tile, lod);
                int checked = 0;
                for (var face : surface.groundFaces()) {
                    if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                    var point = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                    if (road.distance((point.x - center.x) / scale, (point.y - center.y) / scale) >= -.5f) { continue; }
                    float rise = Math.max(face.a().z, Math.max(face.b().z, face.c().z))
                          - Math.min(face.a().z, Math.min(face.b().z, face.c().z));
                    if (rise < BoardRelief.metres(.3f)) { continue; }
                    var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    // A level bridge landing covers intentionally recessed terrain (Fire and Ice 1407).
                    // Check exposed road grades, not a hidden bank beneath the concrete road carrier.
                    if (normal.z <= example.minimumUp() && coveredByBridgeLanding(scene, tile, lod, surface, face)) {
                        continue;
                    }
                    assertTrue(normal.z > example.minimumUp(),
                          "A road climb must not contain a near-vertical bank or folded fin: "
                                + example.path() + " " + example.at().getBoardNum() + " " + lod + " " + face);
                    checked++;
                }
                assertTrue(checked > 0, "Exercise actual graded pavement on " + example.path());
            }
        }
    }

    private static boolean coveredByBridgeLanding(BoardScene scene, BoardScene.Tile tile, TerrainLod lod,
          BoardSurface surface, BoardSurface.Face face) {
        var surfaces = new HashMap<Coords, BoardSurface>();
        surfaces.put(tile.coords(), surface);
        var roofs = new java.util.ArrayList<BoardBridge.Facet>();
        for (int direction = 0; direction < 6; direction++) {
            var next = scene.tile(tile.coords().translated(direction));
            if (BoardBridge.feature(next) == null) { continue; }
            for (var facet : BoardBridgeFooting.build(scene, next, lod, surfaces).shape().facets()) {
                if (facet.normal().z > .5f) { roofs.add(facet); }
            }
        }
        var middle = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
        for (var point : List.of(face.a(), face.b(), face.c(), middle)) {
            var ray = new Ray(new Vector3(point.x, point.y, point.z + 100 * BoardGeometry.hexScale()),
                  new Vector3(0, 0, -1));
            boolean covered = false;
            for (var roof : roofs) {
                var hit = new Vector3();
                if (Intersector.intersectRayTriangle(ray, roof.a(), roof.b(), roof.c(), hit)
                      && hit.z >= point.z) {
                    covered = true;
                    break;
                }
            }
            if (!covered) { return false; }
        }
        return true;
    }

    @Test
    void cityBridgeApproachSpreadsItsNaturalBankWithoutLoweringTheHigherNeighbor() throws Exception {
        var scene = GpuRoadSourceTest.scene("Map Set 3/16x17 City Hills Residential 2.board");
        var at = new Coords(7, 9);
        var tile = scene.tile(at);
        var bridge = scene.tile(new Coords(6, 9));
        int direction = at.direction(bridge.coords()), edge = 0;
        while (BoardGeometry.edgeDirection(edge) != direction) { edge++; }
        var corner = BoardGeometry.corner(at, tile.elevation(), (edge + 1) % 6);
        var mouth = BoardGeometry.corner(at, tile.elevation(), edge).lerp(corner, .5f);
        var across = new Vector3(corner).sub(mouth);
        across.z = 0;
        var shoulder = new Vector3(mouth).mulAdd(across.nor(),
              BoardSurface.roadMouth(tile, bridge, direction) * BoardGeometry.hexScale());
        float low = BoardSurface.roadEdgeElevation(tile, bridge, direction) * BoardGeometry.level();
        float high = tile.elevation() * BoardGeometry.level();
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
            var surface = new BoardSurface(scene, tile, lod);
            var neighbor = new BoardSurface(scene, scene.tile(new Coords(6, 10)), lod);
            assertEquals(low, BoardSurface.sampleHeight(surface.groundFaces(), shoulder.x, shoulder.y, Float.NaN),
                  .002f, "Keep the bridge-mouth road grade at the shoulder");
            assertEquals(high, BoardSurface.sampleHeight(neighbor.groundFaces(), corner.x, corner.y, Float.NaN),
                  .002f, "The higher 0711 land retains its shared corner");
            float previous = low;
            for (int step = 1; step <= 4; step++) {
                var point = new Vector3(shoulder).lerp(corner, step / 4f);
                float height = BoardSurface.sampleHeight(surface.groundFaces(), point.x, point.y, Float.NaN);
                assertTrue(Float.isFinite(height) && height >= previous - .002f && height <= high + .002f,
                      "The exposed natural bank rises continuously towards the higher neighbor: " + lod);
                assertTrue(height - previous < .5f * (high - low),
                      "A bridge connection must not concentrate the whole bank rise in one narrow retaining strip");
                previous = height;
            }
            assertEquals(high, previous, .002f, "Keep the higher land instead of lowering its corner");
        }
    }

    @Test
    void plaineBridgeApproachBanksStayBelowTheirConcreteParapets() throws Exception {
        var scene = GpuRoadSourceTest.scene("unofficial/Cartes/16x17 Plaine 1.board");
        var at = new Coords(9, 11);
        var bridge = scene.tile(at.translated(0));
        var center = BoardGeometry.center(at, scene.tile(at).elevation());
        float scale = BoardGeometry.hexScale();
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
            var surfaces = new HashMap<Coords, BoardSurface>();
            var ground = new BoardSurface(scene, scene.tile(at), lod);
            surfaces.put(at, ground);
            var footing = BoardBridgeFooting.build(scene, bridge, lod, surfaces);
            int checked = 0;
            for (int side : new int[] { -1, 1 }) {
                for (float across : new float[] { .6f, .7f, .8f, .9f }) {
                    for (int along = 30; along <= 34; along++) {
                        float x = center.x + side * (BoardRoad.Kind.PAVED.halfWidth
                              + across * BoardBridge.SHOULDER) * scale;
                        float y = center.y + along * scale;
                        float height = BoardSurface.sampleHeight(ground.groundFaces(), x, y, Float.NaN);
                        if (!Float.isFinite(height)) { continue; }
                        var ray = new Ray(new Vector3(x, y, center.z + 100 * scale), new Vector3(0, 0, -1));
                        float parapet = Float.NEGATIVE_INFINITY;
                        for (var face : footing.shape().facets()) {
                            if (face.normal().z <= .5f) { continue; }
                            var hit = new Vector3();
                            if (Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit)) {
                                parapet = Math.max(parapet, hit.z);
                            }
                        }
                        assertTrue(Float.isFinite(parapet), "Sample the installed bridge's concrete shoulder");
                        assertTrue(height <= parapet + .01f * scale,
                              "The road bank must not rise through the bridge parapet: " + lod + " "
                                    + x + "," + y + " ground=" + height + " parapet=" + parapet);
                        checked++;
                    }
                }
            }
            assertTrue(checked >= 20, "Exercise both approach banks outside the pavement");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 9, 31, 63 })
    void concreteRoadGatesHaveNoWallAcrossTheCarriageway(int exits) {
        var scene = concreteJunction(exits);
        var at = BoardRoadTest.CENTER;
        var outline = BoardConcrete.of(scene);
        for (var lod : TerrainLod.values()) {
            var surfaces = new HashMap<Coords, BoardSurface>();
            for (var tile : scene.tiles()) { surfaces.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
            var surface = surfaces.get(at);
            for (int d = 0; d < 6; d++) {
                if ((exits & 1 << d) == 0) { continue; }
                var adjacent = surfaces.get(at.translated(d));
                Vector3 center = BoardGeometry.center(at, 0), next = BoardGeometry.center(at.translated(d), 0);
                Vector3 along = new Vector3(next).sub(center).nor();
                Vector3 across = new Vector3(-along.y, along.x, 0);
                int edge = Math.floorMod(1 - d, 6);
                Vector3 gate = outline.corner(at, edge).lerp(outline.corner(at, (edge + 1) % 6), .5f);
                Vector3 boundary = outline.corner(at, (edge + 1) % 6).sub(outline.corner(at, edge)).nor();
                for (float offset : new float[] { -14, -7, 0, 7, 14 }) {
                    Vector3 contact = new Vector3(gate).mulAdd(boundary, offset * BoardGeometry.hexScale());
                    float roof = BoardSurface.sampleHeight(surface.faces, contact.x, contact.y, Float.NaN);
                    assertTrue(Float.isFinite(roof), "The fitted opening has supporting road geometry");
                    assertEquals(roof, BoardSurface.sampleHeight(adjacent.faces, contact.x, contact.y, Float.NaN),
                          .002f, "Both sides meet at the fitted road opening");
                    Vector3 a = new Vector3(gate).mulAdd(across, offset * BoardGeometry.hexScale())
                          .mulAdd(along, -BoardGeometry.hexScale());
                    Vector3 b = new Vector3(gate).mulAdd(across, offset * BoardGeometry.hexScale())
                          .mulAdd(along, BoardGeometry.hexScale());
                    a.z = surface.height(a.x, a.y) + BoardGeometry.hexScale();
                    b.z = adjacent.height(b.x, b.y) + BoardGeometry.hexScale();
                    Ray ray = new Ray(a, new Vector3(b).sub(a).nor());
                    Vector3 hit = new Vector3();
                    for (var owner : List.of(surface, adjacent)) {
                        for (var wall : owner.walls(scene, BoardGeometry.floor(scene), surfaces)) {
                            boolean blocked = Intersector.intersectRayTriangle(ray, wall.a(), wall.b(), wall.c(), hit)
                                  && hit.dst2(a) < a.dst2(b);
                            assertTrue(!blocked, lod + " exit " + d + " wall blocks the road at " + hit);
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 9, 31, 63 })
    void concreteKeepsLevelSlabsAndVerticalRetainingWallsOutsideItsRoads(int exits) {
        var at = BoardRoadTest.CENTER;
        var scene = concreteJunction(exits);
        var surface = new BoardSurface(scene, scene.tile(at));
        var center = BoardGeometry.center(at, 2);
        var road = BoardRoad.clearance(at, exits);
        var outline = BoardConcrete.of(scene);
        float scale = BoardGeometry.hexScale();
        for (float x = -35; x <= 35; x += 2) {
            for (float y = -30; y <= 30; y += 2) {
                float px = center.x + x * scale, py = center.y + y * scale;
                float distance = outline.distance(at, px, py);
                if ((Float.isNaN(distance) ? !BoardGeometry.contains(at, px, py) : distance > 0)
                      || road.distance(x, y) < BoardRoad.SHOULDER + .5f) { continue; }
                assertEquals(center.z, BoardSurface.sampleHeight(surface.faces, px, py, Float.NaN), .001f,
                      "Concrete outside the ramp stays flat and supported");
            }
        }
        double area = 0, fittedArea = 0;
        for (int e = 0; e < 6; e++) {
            var a = outline.corner(at, e).sub(center);
            var b = outline.corner(at, (e + 1) % 6).sub(center);
            fittedArea += ((double) a.x * b.y - (double) a.y * b.x) / 2;
        }
        for (var face : surface.faces) {
            float cross = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
            assertTrue(cross > 0, "Concrete top triangles cannot fold");
            area += cross / 2;
        }
        assertEquals(Math.abs(fittedArea), area, .1,
              "Flat slab and ramp must cover the fitted outline exactly once");
        assertTrue(!surface.retainingWalls.isEmpty());
        for (var face : surface.retainingWalls) {
            var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            assertEquals(0, normal.z, .0001f, "An engineered retaining wall stands vertically");
            for (var p : List.of(face.a(), face.b(), face.c())) {
                assertEquals(BoardRelief.Kind.CLIFF, surface.relief.shade(p).kind());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 3, 6, 12, 24, 48, 33, 31, 63 })
    void roadlessConcreteApronsKeepEveryInclineOpenAcrossItsFullWidth(int exits) {
        var at = BoardRoadTest.CENTER;
        float scale = BoardGeometry.hexScale();
        for (int rise : new int[] { -1, 1 }) {
            var scene = BoardSurfaceBlendTest.scene(c -> {
                if (c.equals(at)) {
                    return BoardRoadTest.tile(c, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.CONCRETE);
                }
                for (int d = 0; d < 6; d++) {
                    if ((exits & 1 << d) != 0 && c.equals(at.translated(d))) {
                        return BoardRoadTest.tile(c, BoardRoad.Kind.PAVED, 1 << ((d + 3) % 6), rise,
                              BoardScene.Surface.GRASS);
                    }
                }
                return BoardRoadTest.tile(c, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS);
            });
            for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                var surface = new BoardSurface(scene, scene.tile(at), lod);
                var center = BoardGeometry.center(at, 0);
                for (int d = 0; d < 6; d++) {
                    if ((exits & 1 << d) == 0) { continue; }
                    var gate = BoardGeometry.center(at.translated(d), 0).sub(center).scl(.5f);
                    float length = gate.len();
                    var along = new Vector3(gate).nor();
                    var across = new Vector3(-along.y, along.x, 0);
                    for (float side : new float[] { -14, -7, 0, 7, 14 }) {
                        for (int step = 0; step < 48; step++) {
                            var a = new Vector3(center).mulAdd(along, length * (.1f + .85f * step / 48))
                                  .mulAdd(across, side * scale);
                            var b = new Vector3(center).mulAdd(along, length * (.1f + .85f * (step + 1) / 48))
                                  .mulAdd(across, side * scale);
                            a.z = BoardSurface.sampleHeight(surface.faces, a.x, a.y, Float.NaN) + .05f * scale;
                            b.z = BoardSurface.sampleHeight(surface.faces, b.x, b.y, Float.NaN) + .05f * scale;
                            assertTrue(Float.isFinite(a.z) && Float.isFinite(b.z), "The whole ramp needs a carrier");
                            var ray = new Ray(a, new Vector3(b).sub(a).nor());
                            var hit = new Vector3();
                            for (var wall : surface.retainingWalls) {
                                boolean blocked = Intersector.intersectRayTriangle(ray, wall.a(), wall.b(), wall.c(), hit)
                                      && hit.dst2(a) < a.dst2(b);
                                assertTrue(!blocked, "A free apron must not bend its retaining wall across an incline: "
                                      + exits + " " + lod + " rise=" + rise + " direction=" + d + " at " + hit);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void militaryConcreteApproachesDoNotLeaveAnIsolatedHighRoadCorner() throws Exception {
        var scene = GpuRoadSourceTest.scene("buildingsnobasement/militarybase2.board");
        var at = new Coords(9, 12);
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
            var surface = new BoardSurface(scene, scene.tile(at), lod);
            var lowCorner = BoardGeometry.corner(at, 0, 3);
            assertEquals(0, surface.relief.rim(3).getFirst().point().z, .01f * BoardGeometry.hexScale(),
                  "The bank crest must not leave a raised pillar between the low aprons");
            assertEquals(0, BoardSurface.sampleHeight(surface.groundFaces(), lowCorner.x, lowCorner.y, Float.NaN),
                  .01f * BoardGeometry.hexScale(), "The corner between two low concrete aprons must meet their level");
            for (var adjacent : List.of(new Coords(8, 12), new Coords(8, 13))) {
                var apron = new BoardSurface(scene, scene.tile(adjacent), lod);
                assertEquals(0, BoardSurface.sampleHeight(apron.groundFaces(), lowCorner.x, lowCorner.y, Float.NaN),
                      .01f * BoardGeometry.hexScale(), "The fixed concrete slabs stay level at the shared corner");
            }
            var highCorner = BoardGeometry.corner(at, 0, 4);
            assertEquals(BoardGeometry.level(), BoardSurface.sampleHeight(surface.groundFaces(), highCorner.x,
                  highCorner.y, Float.NaN), .01f * BoardGeometry.hexScale(), "The bank beside higher ground remains supported");
        }
    }

    static BoardScene concreteJunction(int exits) {
        var at = BoardRoadTest.CENTER;
        int[] rises = { -2, 1, 2, -1, 0, 2 };
        return BoardSurfaceBlendTest.scene(c -> {
            for (int d = 0; d < 6; d++) {
                if (c.equals(at.translated(d))) {
                    return BoardRoadTest.tile(c, BoardRoad.Kind.PAVED, (exits & 1 << d) == 0 ? 0 : 1 << ((d + 3) % 6),
                          2 + rises[d], BoardScene.Surface.CONCRETE);
                }
            }
            return BoardRoadTest.tile(c, c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  c.equals(at) ? exits : 0, 2, BoardScene.Surface.CONCRETE);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 6 })
    void rampsStayWithinTheirTerrainTriangleBudgets(int layout) {
        var at = BoardRoadTest.CENTER;
        var scene = layout < 6 ? BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              c.getX() == at.getX() ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.getX() == at.getX() ? 9 : 0,
              c.getY() >= at.getY() ? layout : 0, BoardScene.Surface.GRASS)) : junction(at, new int[] { -2, 1, 2, -1, 0, 2 });
        var surface = new BoardSurface(scene, scene.tile(at));
        // Mixed junctions retain mouth midpoint columns through the core and hub, adding up to 384 triangles here.
        int budget = layout == 1 ? 1000 : layout == 2 ? 2000 : 4400;
        assertTrue(surface.faces.size() < budget, "Count the entire terrain roof, including the earthworks: " + surface.faces.size());
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3 })
    void retainingWallsBesideRampsKeepWallMaterialsAcrossEveryTriangle(int rise) {
        for (var family : List.of(BoardScene.Surface.CONCRETE, BoardScene.Surface.GRASS)) {
            var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
                  c.getX() == 4 ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.getX() == 4 ? 9 : 0,
                  c.getY() >= 4 ? rise : 0, family));
            int checked = 0;
            for (int x = 3; x <= 5; x++) {
                var surface = new BoardSurface(scene, scene.tile(new Coords(x, 4)));
                for (var face : surface.walls(scene, -BoardGeometry.level())) {
                    if (face.landEdge() < 0 || !surface.relief.naturalEdge(face.landEdge())) { continue; }
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        var shade = surface.relief.shade(p);
                        assertNotNull(shade, "Every retaining-wall vertex needs its own wall material data");
                        assertEquals(BoardRelief.Kind.CLIFF, shade.kind(), "Do not project horizontal ground onto a wall");
                        assertTrue(shade.rim() >= -.001f && shade.foot() >= -.001f);
                        assertEquals(1, shade.normal().len(), .001f);
                        checked++;
                    }
                }
            }
            assertTrue(checked > 0, "Exercise the " + family + " retaining walls at rise " + rise);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void roadMeshRoundsBothFlatJoinsAndKeepsAContinuousGradeAcrossTheHexEdge(int direction) {
        for (int x : new int[] { 3, 4 }) {
            Coords at = new Coords(x, 4), next = at.translated(direction);
            for (int rise : new int[] { -2, -1, 1, 2 }) {
                var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c, BoardRoad.Kind.PAVED,
                      c.equals(at) ? 1 << direction : c.equals(next) ? 1 << ((direction + 3) % 6) : 0,
                      c.equals(at) ? rise : 0, BoardScene.Surface.GRASS));
                var a = new BoardSurface(scene, scene.tile(at));
                var b = new BoardSurface(scene, scene.tile(next));
                Vector3 start = BoardGeometry.center(at, rise), end = BoardGeometry.center(next, 0);
                Vector3 gate = new Vector3(start).lerp(end, .5f);
                assertEquals(gate.z, a.height(gate.x, gate.y), .001f);
                assertEquals(a.height(gate.x, gate.y), b.height(gate.x, gate.y), .001f);
                int edge = Math.floorMod(1 - direction, 6);
                Vector3 across = BoardGeometry.corner(at, 0, edge + 1).sub(BoardGeometry.corner(at, 0, edge)).nor();
                for (float offset : new float[] { -14, -7, 7, 14 }) {
                    Vector3 lane = new Vector3(gate).mulAdd(across, offset * BoardGeometry.hexScale());
                    assertEquals(gate.z, a.height(lane.x, lane.y), .002f,
                          "The widened carriageway reaches the gate at one grade across both lanes");
                    assertEquals(a.height(lane.x, lane.y), b.height(lane.x, lane.y), .002f,
                          "Both widened shoulders share their neighbor's exact support height");
                }
                assertTrue(a.roadNormal(gate).epsilonEquals(b.roadNormal(gate), .0001f),
                      "Lighting must also be continuous at the shared gate");
                assertEquals(grade(a, start, end, .48f, .5f), grade(b, start, end, .5f, .52f), .002f,
                      "The gate is the middle of the incline, not another crest or dip");

                float previous = 0;
                float steepest = 0;
                for (int i = 1; i <= 96; i++) {
                    float from = (i - 1) / 96f, to = i / 96f;
                    float slope = grade(i <= 48 ? a : b, start, end, from, to);
                    assertTrue(slope * rise <= .001f, "No hump or depression in a monotonic ramp");
                    assertTrue(Math.abs(slope - previous) < .14f,
                          "Actual triangle grades must change gradually, even without smooth shading: " + previous + " -> " + slope);
                    previous = slope;
                    steepest = Math.max(steepest, Math.abs(slope));
                }
                assertTrue(steepest < (Math.abs(rise) == 2 ? .8f : .6f), "The climb must use enough of these two hexes");
                float departure = Math.abs(rise) == 2 ? .04f : .19f;
                assertTrue(Math.abs(grade(a, start, end, departure, departure + .01f)) < .075f, "Gentle departure from the plateau");
                assertTrue(Math.abs(grade(b, start, end, 1 - departure - .01f, 1 - departure)) < .075f, "Gentle arrival on the plateau");
                for (float t : new float[] { 0, departure, 1 - departure, 1 }) {
                    Vector3 point = new Vector3(start).lerp(end, t);
                    var surface = t < .5f ? a : b;
                    point.z = surface.height(point.x, point.y);
                    assertTrue(surface.roadNormal(point).epsilonEquals(Vector3.Z, .0001f), "Level plateau tangent");
                }
            }
        }
    }

    private static float grade(BoardSurface surface, Vector3 a, Vector3 b, float from, float to) {
        Vector3 p = new Vector3(a).lerp(b, from), q = new Vector3(a).lerp(b, to);
        return (surface.height(q.x, q.y) - surface.height(p.x, p.y)) / (float) Math.hypot(q.x - p.x, q.y - p.y);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void shouldersGradeIntoTheHexWithoutVerticalWallsAndPickingUsesTheBank(int direction) {
        for (int x : new int[] { 3, 4 }) {
            Coords at = new Coords(x, 4), next = at.translated(direction);
            for (int rise : new int[] { -2, -1, 1, 2 }) {
                var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c, BoardRoad.Kind.DIRT,
                      c.equals(at) ? 1 << direction : c.equals(next) ? 1 << ((direction + 3) % 6) : 0,
                      c.equals(at) ? rise : 0, BoardScene.Surface.GRASS));
                var surface = new BoardSurface(scene, scene.tile(at));
                Vector3 center = BoardGeometry.center(at, rise);
                Vector3 gate = new Vector3(center).lerp(BoardGeometry.center(next, 0), .5f);
                Vector3 along = new Vector3(gate.x - center.x, gate.y - center.y, 0).nor();
                Vector3 across = new Vector3(-along.y, along.x, 0);
                Vector3 station = new Vector3(center).lerp(gate, .85f);
                float road = surface.height(station.x, station.y);
                assertTrue(surface.faces.stream().allMatch(f -> f.finish() == BoardSurface.Finish.TOP),
                      "A dry road has graded ground, not vertical bank panels");
                for (int side : new int[] { -1, 1 }) {
                    // Sample immediately around the shoulder. Wider roads leave a shorter, legitimately steeper
                    // bank, so measuring a broad part of that slope would test its steepness rather than continuity.
                    Vector3 onRoad = new Vector3(station).mulAdd(across, side * (BoardSurface.ROAD_MOUTH - .02f) * BoardGeometry.hexScale());
                    Vector3 offRoad = new Vector3(station).mulAdd(across, side * (BoardSurface.ROAD_MOUTH + .02f) * BoardGeometry.hexScale());
                    assertEquals(surface.height(onRoad.x, onRoad.y), surface.height(offRoad.x, offRoad.y),
                          .13f * BoardGeometry.hexScale(), "No sheer cut or fill along the shoulder");
                    // Follow the whole cut/fill transition instead of requiring a steep drop immediately
                    // beside the road. Small weathered folds may vary by 20 cm, but cannot build a crest.
                    float allowance = BoardRelief.metres(.2f), previous = Math.abs(road);
                    for (float offset : new float[] { 0, 1.5f, 3, 4.5f, 6 }) {
                        Vector3 sample = new Vector3(station).mulAdd(across,
                              side * (BoardSurface.ROAD_MOUTH + offset) * BoardGeometry.hexScale());
                        float height = BoardSurface.sampleHeight(surface.faces, sample.x, sample.y, Float.NaN);
                        assertTrue(Float.isFinite(height), "The bank continuously supports the shoulder");
                        assertTrue(height >= Math.min(0, center.z) - .01f
                                    && height <= Math.max(0, center.z) + .01f,
                              "The cut/fill stays between the road's plateau and the surrounding land");
                        float towardsGround = height * Math.signum(rise);
                        assertTrue(towardsGround <= previous + allowance
                                    && towardsGround <= Math.abs(road) + allowance,
                              "The shoulder grades towards the land without rebounding into a ridge");
                        previous = towardsGround;
                    }
                    assertTrue(previous < Math.abs(road) - allowance,
                          "The bank returns towards the surrounding land beyond the gentle shoulder");
                    Vector3 bank = new Vector3(station).mulAdd(across, side * (BoardSurface.ROAD_MOUTH + 1.5f) * BoardGeometry.hexScale());
                    assertEquals(at, BoardGeometry.pick(scene, new Ray(new Vector3(bank.x, bank.y, 200),
                          new Vector3(0, 0, -1))), "The visible earthwork belongs to the same pickable hex");
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1 })
    void sixConnectionsShareOneSurfaceWithMatchingGatesAndUnchangedCentres(int layout) {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            int[] rises = layout == 0 ? new int[] { 2, 2, 2, 2, 2, 2 } : new int[] { -2, -2, -2, -2, -2, -2 };
            var scene = junction(at, rises);
            var surface = new BoardSurface(scene, scene.tile(at));
            unfolded(surface);
            var center = BoardGeometry.center(at, 2);
            assertEquals(center.z, surface.height(center.x, center.y), .001f, "Keep the unit anchor level");
            for (int d = 0; d < 6; d++) {
                var next = at.translated(d);
                var other = new BoardSurface(scene, scene.tile(next));
                unfolded(other);
                var end = BoardGeometry.center(next, 2 + rises[d]);
                var gate = new Vector3(center).lerp(end, .5f);
                assertEquals(gate.z, surface.height(gate.x, gate.y), .001f);
                assertEquals(gate.z, other.height(gate.x, gate.y), .001f);
                assertEquals(end.z, other.height(end.x, end.y), .001f, "The other hex centre keeps its elevation");
                assertEquals(grade(surface, center, end, .48f, .5f), grade(other, center, end, .5f, .52f), .003f);
                for (int i = 1; i < 40; i++) {
                    var p = new Vector3(center).lerp(gate, i / 40f);
                    float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                    for (var face : surface.faces) {
                        float z = face.height(p.x, p.y);
                        if (Float.isFinite(z)) { min = Math.min(min, z); max = Math.max(max, z); }
                    }
                    assertTrue(Float.isFinite(max), "Every arm has continuous supporting ground");
                    assertEquals(min, max, .01f, "Overlapping arm meshes must not create different ground heights");
                }
            }
        }
    }

    static BoardScene junction(Coords at, int[] rises) {
        return BoardSurfaceBlendTest.scene(c -> {
            for (int d = 0; d < 6; d++) {
                if (c.equals(at.translated(d))) {
                    return BoardRoadTest.tile(c, BoardRoad.Kind.PAVED, 1 << ((d + 3) % 6), 2 + rises[d], BoardScene.Surface.SAND);
                }
            }
            return BoardRoadTest.tile(c, c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  c.equals(at) ? 63 : 0, 2, BoardScene.Surface.SAND);
        });
    }

    private static void unfolded(BoardSurface surface) {
        for (var face : surface.faces) {
            if (face.finish() != BoardSurface.Finish.TOP) { continue; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            float rise = Math.max(face.a().z, Math.max(face.b().z, face.c().z))
                  - Math.min(face.a().z, Math.min(face.b().z, face.c().z));
            // A folded slope would stand up as a fin. Small slivers can still fold where two rims cross at a corner;
            // docs/gpu-road-slopes-tunnels.md bounds them at 0.9 m tall and under 0.3 m² (shipped maps stay lower).
            float area = normal.len() / 2, metre = BoardRelief.metres(1);
            assertTrue(normal.z > 0 || rise < BoardRelief.metres(.9f) && area < .3f * metre * metre,
                  "Every earthwork triangle has a positive footprint: " + surface.tile.coords() + " " + face);
        }
        for (int corner = 0; corner < 6; corner++) {
            // A gate has no wall; its corner only has to meet the others where both roads' banks meet it.
            float gate = surface.relief.gateCorner(corner);
            for (float level = 0; level <= 4; level += .25f) {
                float z = Float.isNaN(gate) ? level * BoardGeometry.level() : gate;
                var a = surface.relief.roadPoint(corner, 0, z);
                var b = surface.relief.roadPoint(Math.floorMod(corner - 1, 6), 1, z);
                assertTrue(a.epsilonEquals(b, .0001f), "The walls beside an earthwork must close at shared corners");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void theRoadMouthBanksMeetWithoutLeavingCliffSpikesAtTheirCorners(int direction) {
        var at = BoardRoadTest.CENTER;
        var next = at.translated(direction);
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c, BoardRoad.Kind.PAVED,
              c.equals(at) ? 1 << direction : c.equals(next) ? 1 << ((direction + 3) % 6) : 0,
              c.equals(at) ? 2 : 0, BoardScene.Surface.GRASS));
        var high = new BoardSurface(scene, scene.tile(at));
        for (var side : high.sides(scene, BoardGeometry.floor(scene))) {
            if (BoardGeometry.edgeDirection(side.edge()) == direction) {
                assertTrue(Math.max(side.a().z - side.lowA(), side.b().z - side.lowB()) < .025f,
                      "The two banks must meet along the whole mouth, including both corners");
            }
        }
    }
}
