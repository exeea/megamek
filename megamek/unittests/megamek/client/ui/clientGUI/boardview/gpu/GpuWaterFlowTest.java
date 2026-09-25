/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuWaterFlowTest {
    private static final Coords HIGH = new Coords(3, 3);

    @Test
    void theAnimatedCurrentAcceleratesDownBothSlopesAndSettlesAfterTheFoot() {
        for (int direction = 0; direction < 6; direction++) {
            float gentle = 0;
            for (int drop : new int[] { 1, 2 }) {
                BoardScene scene = scene(direction, drop, 1);
                var pools = new GpuWaterShader.Pools(scene, BoardFlow.calculate(scene), Map.of());
                Vector2 heading = heading(direction);
                float ordinary = current(pools, direction, -.3f).dot(heading);
                assertTrue(ordinary > .05f, "The level approach has an ordinary downstream current");
                float previous = ordinary;
                for (float along : new float[] { .25f, .5f, .75f, .9f }) {
                    Vector2 velocity = current(pools, direction, along);
                    float speed = velocity.dot(heading);
                    assertTrue(speed > previous + .005f,
                          "Gain speed during descent: drop=" + drop + ", direction=" + direction + ", at=" + along
                                + ", previous=" + previous + ", speed=" + speed);
                    assertTrue(new Vector2(velocity).nor().dot(heading) > .98f, "The added current travels downhill");
                    previous = speed;
                }
                assertTrue(previous > 2 * ordinary, "Acceleration is visible, not just a tiny change");
                if (drop == 1) { gentle = previous; }
                else { assertTrue(previous > gentle * 1.2f, "A two-level chute accelerates more than a gentle slope"); }
                float foot = current(pools, direction, 1).dot(heading);
                float leaving = current(pools, direction, 1.3f).dot(heading);
                float settled = current(pools, direction, 1.45f).dot(heading);
                assertTrue(foot > leaving && leaving > settled, "Momentum eases away downstream of the foot");
                assertEquals(ordinary, settled, .001f, "The downstream flat reach returns to its ordinary current");
                assertTrue(current(pools, direction, .4999f).dst(current(pools, direction, .5001f)) < .001f,
                      "Acceleration stays continuous across the shared hex edge");
            }
        }
    }

    @Test
    void riverbedDepthDoesNotAccelerateTheWaterSurface() {
        for (int drop : new int[] { 0, 1, 2 }) {
            Vector2 first = null;
            for (int depth : new int[] { 0, 1, 4 }) {
                BoardScene scene = scene(3, drop, depth);
                var pools = new GpuWaterShader.Pools(scene, BoardFlow.calculate(scene), Map.of());
                Vector2 middle = current(pools, 3, .5f);
                if (first == null) { first = middle; }
                else { assertTrue(first.epsilonEquals(middle, .0001f), "Only surface levels affect acceleration"); }
                if (drop == 0) { assertEquals(0, middle.len(), .0001f, "A depth step in level water creates no flow"); }
            }
        }
    }

    @Test
    void adjacentChunksSampleTheSameAccelerationAndALiveEditRemovesIt() {
        int direction = 3;
        Coords low = HIGH.translated(direction);
        BoardScene scene = scene(direction, 2, 1);
        var currents = BoardFlow.calculate(scene);
        var upstream = new GpuWaterShader.Pools(scene, currents,
              Map.of(HIGH, new BoardSurface(scene, scene.tile(HIGH))));
        var downstream = new GpuWaterShader.Pools(scene, currents,
              Map.of(low, new BoardSurface(scene, scene.tile(low))));
        for (float along : new float[] { .25f, .4999f, .5f, .5001f, .75f, 1, 1.3f }) {
            assertTrue(current(upstream, direction, along).epsilonEquals(current(downstream, direction, along), .000001f),
                  "The field cannot depend on which chunk owns the surface");
        }
        BoardScene flat = scene(direction, 0, 1);
        var edited = new GpuWaterShader.Pools(flat, BoardFlow.calculate(flat), Map.of());
        assertTrue(current(upstream, direction, .75f).len() > .2f);
        assertEquals(0, current(edited, direction, .75f).len(), .0001f, "Removing the surface drop removes its acceleration");
    }

    private static Vector2 current(GpuWaterShader.Pools pools, int direction, float along) {
        Vector3 point = BoardGeometry.center(HIGH, 0).lerp(BoardGeometry.center(HIGH.translated(direction), 0), along);
        float[] sample = new float[4];
        pools.sample(point.x, point.y, sample);
        return new Vector2(sample[1], sample[2]);
    }

    private static Vector2 heading(int direction) {
        Coords low = HIGH.translated(direction);
        return new Vector2(BoardGeometry.centerX(low) - BoardGeometry.centerX(HIGH),
              BoardGeometry.centerY(low) - BoardGeometry.centerY(HIGH)).nor();
    }

    /** A stream reaching both board edges, with a level approach, one descent, and a level outlet. */
    private static BoardScene scene(int direction, int drop, int depth) {
        Map<Coords, Integer> water = new HashMap<>();
        for (int step = -7; step <= 7; step++) {
            Coords coords = HIGH.translated(step < 0 ? (direction + 3) % 6 : direction, Math.abs(step));
            water.put(coords, step <= 0 ? drop : 0);
        }
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean wet = water.containsKey(coords);
                // Give the lower reach a different bed depth even when the surface is entirely level.
                int bed = wet ? depth + (water.get(coords) == 0 && !coords.equals(HIGH) ? 1 : 0) : -1;
                tiles.add(new BoardScene.Tile(coords, water.getOrDefault(coords, drop + 1), bed, false, 0,
                      BoardScene.Surface.SAND, null, null, null, null, null, List.of(), List.of(),
                      wet ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
