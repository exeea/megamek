/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ground pick (GpuTerrain#selectionHit, the hover's and the click's) lands on the hex under the pointer, where the
 * pointer meets the board, on real boards' cliffs, with the height field on or off (megamek.gpu.terrainField is read
 * once a JVM: run the class both ways). The height field's walls hold zero-area triangles, which libGDX takes as hit at
 * the ray's own origin (BoardGeometry#crosses).
 */
@Tag("on-demand")
class GpuGroundPickSmokeTest {
    /** The iso view's rays, all parallel: the direction of the 4K closer view of Park District's portal below. */
    private static final Vector3 ISO = new Vector3(-5797.3f, 5795.0f, -5794.2f).nor();

    /**
     * Ten pixels of that view (Park District, 4K, the portal at 1311 in the middle): each ray's origin, then where the
     * pointer meets the board with the height field off (none: on the portal's wing, a model of the road hex). The
     * height field meets it within a few metres of there.
     */
    private static final float[][] PARK = {
          { 6553.9f, -6606.1f, 5808.7f, 756.6f, -811.1f, 14.5f }, { 6559.7f, -6599.5f, 5809.4f, 761.4f, -803.5f, 14.2f },
          { 6566.5f, -6596.5f, 5805.7f, 765.5f, -797.7f, 7.7f }, { 6560.2f, -6606.6f, 5801.9f, 759.5f, -808.1f, 4.3f },
          { 6567.2f, -6600.4f, 5801.1f, 764.4f, -799.9f, 1.4f }, { 6554.2f, -6600.5f, 5813.9f, 757.7f, -806.3f, 20.5f },
          { 6559.6f, -6592.9f, 5816.2f, 761.7f, -797.2f, 21.4f }, { 6564.8f, -6591.5f, 5812.4f, 765.4f, -794.4f, 16.2f },
          { 6570.8f, -6593.0f, 5804.9f }, { 6574.0f, -6593.6f, 5801.1f } };

    /**
     * The hex under each of those pixels: 1211's cliff (its wall; 1311 the portal's wing), and with the height field
     * three of them on the talus apron at the cliff's foot in 1312, whose ground it is.
     */
    private static final String[] PARK_TODAY = { "1211", "1211", "1211", "1211", "1211", "1211", "1211", "1211", "1311", "1311" };
    private static final String[] PARK_FIELD = { "1211", "1211", "1312", "1312", "1312", "1211", "1211", "1211", "1311", "1311" };

    @Test
    void parkDistrictPortalPixelsPickTheHexUnderThePointer() throws Exception {
        Coords road = new Coords(12, 10);
        run("Map Pack City/City Districts/16x17 Park District.board", road, (scene, terrain) -> {
            for (int i = 0; i < PARK.length; i++) {
                float[] pixel = PARK[i];
                Ray ray = new Ray(new Vector3(pixel[0], pixel[1], pixel[2]), ISO);
                Vector3 board = pixel.length > 3 ? new Vector3(pixel[3], pixel[4], pixel[5]) : null;
                String hex = (TerrainField.ON ? PARK_FIELD : PARK_TODAY)[i];
                check(scene, terrain, ray, new Coords(Integer.parseInt(hex.substring(0, 2)) - 1, Integer.parseInt(hex.substring(2)) - 1), board);
            }
        });
    }

    @Test
    void koziceCliffsFacingTheViewPickTheirWall() throws Exception {
        String path = "Battle of Tukayyid Pack/32x17 Kozice Valley (CDS).board";
        Board board = load(path);
        // Cliffs of two levels or more whose wall faces the iso view (the high hex's south-east or south edge), with
        // nothing higher on the low side the pointer looks across.
        List<Coords[]> cliffs = new ArrayList<>();
        for (int y = 0; y < board.getHeight() && cliffs.size() < 8; y++) {
            for (int x = 0; x < board.getWidth() && cliffs.size() < 8; x++) {
                Coords high = new Coords(x, y);
                for (int direction : new int[] { 2, 3 }) {
                    Coords low = high.translated(direction);
                    if (!plain(board, high) || !plain(board, low)
                          || board.getHex(high).getLevel() - board.getHex(low).getLevel() < 2) { continue; }
                    int level = board.getHex(low).getLevel();
                    boolean open = true;
                    for (Coords before : List.of(low.translated(2), low.translated(3), low.translated(2).translated(3))) {
                        open &= !board.contains(before) || plain(board, before) && board.getHex(before).getLevel() <= level;
                    }
                    if (open) { cliffs.add(new Coords[] { high, low }); break; }
                }
            }
        }
        assertTrue(cliffs.size() >= 3, "Kozice Valley must hold cliffs facing the iso view: " + cliffs.size());
        run(path, new Coords(board.getWidth() / 2, board.getHeight() / 2), (scene, terrain) -> {
            for (Coords[] cliff : cliffs) {
                // Three quarters up the wall, on the hexes' shared edge: above the scree at its foot, which is the low hex's.
                Vector3 top = BoardGeometry.center(cliff[0], scene.tile(cliff[0]).elevation());
                Vector3 foot = BoardGeometry.center(cliff[1], scene.tile(cliff[1]).elevation());
                Vector3 aim = new Vector3(top).add(foot).scl(.5f);
                aim.z = foot.z + (top.z - foot.z) * .75f;
                check(scene, terrain, new Ray(new Vector3(ISO).scl(-6000).add(aim), ISO), cliff[0], aim);
            }
        });
    }

    /**
     * The pick of {@code ray} is {@code expected}'s hex and, given where the pointer meets the board ({@code board}),
     * lies within a quarter of a hex of it along the ray (the field's and today's walls differ by their relief).
     */
    private static void check(BoardScene scene, GpuTerrain terrain, Ray ray, Coords expected, Vector3 board) {
        BoardGeometry.Hit hit = terrain.selectionHit(scene, ray);
        assertNotNull(hit, "The pointer must meet the board at " + ray);
        float distance = (float) Math.sqrt(hit.distance());
        String where = expected.getBoardNum() + " along " + ray + ": picked " + hit.coords().getBoardNum() + " at "
              + ray.getEndPoint(new Vector3(), distance) + (TerrainField.ON ? " (height field)" : " (today's terrain)");
        assertEquals(expected, hit.coords(), "The hex under the pointer, " + where);
        if (board != null) {
            assertEquals(ray.origin.dst(board), distance, BoardGeometry.width() / 4, "Where the pointer meets the board, " + where);
        }
    }

    private static boolean plain(Board board, Coords c) {
        var hex = board.getHex(c);
        return hex != null && !hex.containsTerrain(Terrains.WATER) && !hex.containsTerrain(Terrains.BUILDING)
              && !hex.containsTerrain(Terrains.BRIDGE);
    }

    private static Board load(String path) {
        return BoardFile.loadSheet(new File("data/boards/" + path), false, true);
    }

    /** Opens the board in the battle view close over {@code focus}; once its terrain is built, runs {@code checks}. */
    private static void run(String path, Coords focus, BiConsumer<BoardScene, GpuTerrain> checks) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> checked = new AtomicReference<>(false);
        try (var fixture = GpuBoardFixture.create(load(path))) {
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1200, 900);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private int tick;
                private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3);

                @Override
                public void render() {
                    try {
                        assertTrue(System.nanoTime() < deadline, "The board must load within three minutes: " + path);
                        super.render();
                        if (frames() == 0) { return; }
                        GpuTerrain terrain = field(this, "terrain");
                        BoardScene scene = field(this, "scene");
                        if (++tick == 1) {
                            boardCamera.setPerspective(false);
                            boardCamera.setIsometric(true);
                            boardCamera.camera.zoom = .05f;
                            boardCamera.center(BoardGeometry.center(focus, scene.tile(focus).elevation()));
                        } else if (tick > 3 && !terrain.busy()) {
                            checks.accept(scene, terrain);
                            checked.set(true);
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Ground pick on " + path, failure.get()); }
        assertTrue(checked.get(), "The checks must run: " + path);
    }
}
