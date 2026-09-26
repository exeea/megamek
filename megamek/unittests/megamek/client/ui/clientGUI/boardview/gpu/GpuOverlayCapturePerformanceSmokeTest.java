/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Color;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CPU-only capture benchmark; deliberately uses APIs present in both frozen and optimized production classes. */
@Tag("on-demand")
class GpuOverlayCapturePerformanceSmokeTest {
    @Test
    void measuresDeploymentAndIncrementalCoverageCapture() throws Exception {
        int size = Integer.getInteger("megamek.gpu.performanceSize", 200);
        Hex[] hexes = new Hex[size * size];
        Arrays.setAll(hexes, index -> new Hex(0));
        Board board = new Board();
        board.newData(size, size, hexes, null);
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                GUIPreferences preferences = GUIPreferences.getInstance();
                boolean sheets = preferences.getShowMapSheets();
                try {
                    var timer = GpuBoardSource.class.getDeclaredField("timer");
                    timer.setAccessible(true);
                    ((Timer) timer.get(fixture.source)).stop();
                    preferences.setShowMapSheets(false);
                    fixture.player.setStartingPos(Board.START_ANY);
                    fixture.entity.setDeployed(false);
                    fixture.game.setPhase(GamePhase.DEPLOYMENT);
                    fixture.view.markDeploymentHexesFor(fixture.entity);
                    System.out.printf("OVERLAY-CAPTURE size=%d warmup=3 samples=9 JVM=%s%n", size,
                          System.getProperty("java.version"));
                    measure("deployment", fixture.view, index -> { });

                    fixture.view.markDeploymentHexesFor(null);
                    fixture.entity.setDeployed(true);
                    fixture.game.setPhase(GamePhase.MOVEMENT);
                    Map<Coords, Color> ecm = new HashMap<>(), eccm = new HashMap<>();
                    Color red = new Color(190, 45, 55, 90), blue = new Color(40, 90, 210, 75);
                    for (int x = 0; x < size; x++) {
                        for (int y = 0; y < size; y++) {
                            Coords coords = new Coords(x, y);
                            ecm.put(coords, red);
                            eccm.put(coords, blue);
                        }
                    }
                    set(fixture.view, "ecmHexes", ecm);
                    set(fixture.view, "eccmHexes", eccm);
                    set(fixture.view, "ecmCenters", Map.of());
                    set(fixture.view, "eccmCenters", Map.of());
                    measure("dense-coverage", fixture.view, index -> { });
                    Coords edited = new Coords(size / 2, size / 2);
                    measure("one-hex-recolor", fixture.view, index -> ecm.put(edited, index % 2 == 0 ? blue : red));
                    if (Boolean.parseBoolean(System.getProperty("megamek.gpu.performanceMapSheets", "true"))) {
                        preferences.setShowMapSheets(true);
                        measure("coverage-and-sheets", fixture.view,
                              index -> ecm.put(edited, index % 2 == 0 ? blue : red));
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                } finally {
                    preferences.setShowMapSheets(sheets);
                }
            });
        }
    }

    private static void measure(String name, BoardView view, IntConsumer change) {
        var standard = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean allocation = standard instanceof com.sun.management.ThreadMXBean extended
              && extended.isThreadAllocatedMemorySupported() ? extended : null;
        if (allocation != null && !allocation.isThreadAllocatedMemoryEnabled()) {
            allocation.setThreadAllocatedMemoryEnabled(true);
        }
        long thread = Thread.currentThread().threadId();
        long[] elapsed = new long[9], allocated = new long[9];
        BoardTactical result = BoardTactical.EMPTY;
        for (int sample = -3; sample < elapsed.length; sample++) {
            change.accept(sample + 3);
            long bytes = allocation == null ? 0 : allocation.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            result = view.captureTacticalGeometry();
            long duration = System.nanoTime() - start;
            if (sample >= 0) {
                elapsed[sample] = duration;
                allocated[sample] = allocation == null ? -1 : allocation.getThreadAllocatedBytes(thread) - bytes;
            }
        }
        Arrays.sort(elapsed);
        Arrays.sort(allocated);
        System.out.printf("OVERLAY-CAPTURE %s median=%.3fms p95=%.3fms allocated-median=%.3fMiB"
                    + " allocated-p95=%.3fMiB fills=%d labels=%d walls=%d flatWalls=%d%n",
              name, elapsed[4] / 1e6, elapsed[8] / 1e6,
              allocation == null ? -1 : allocated[4] / 1048576.0,
              allocation == null ? -1 : allocated[8] / 1048576.0,
              result.fills().size(), result.labels().size(), result.walls().size(), result.flatWalls().size());
    }

    private static void set(BoardView view, String name, Object value) throws ReflectiveOperationException {
        var field = BoardView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(view, value);
    }
}
