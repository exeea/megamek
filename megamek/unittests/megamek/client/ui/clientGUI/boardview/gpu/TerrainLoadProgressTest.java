/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class TerrainLoadProgressTest {
    @Test
    void loadingOrderCrossesEachDisplayedRowBeforeMovingDown() {
        assertEquals(List.of(0, 2, 4, 6, 1, 3, 5, 7), displayOrder(4, 2));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), displayOrder(2, 4));
        assertEquals(List.of(0, 3, 6, 1, 4, 7, 2, 5, 8), displayOrder(3, 3));
    }

    private static List<Integer> displayOrder(int columns, int rows) {
        return IntStream.range(0, columns * rows).boxed()
              .sorted(Comparator.comparingInt(index -> TerrainLoadProgress.displayIndex(index, columns, rows))).toList();
    }

    @Test
    void parallelCompletionsPublishConsistentSnapshotsWithoutLosingWork() {
        var progress = new TerrainLoadProgress();
        progress.begin("surfaces", 100_000);
        var before = progress.snapshot();
        var workers = CompletableFuture.runAsync(() -> IntStream.range(0, 100_000).parallel()
              .forEach(ignored -> progress.advance()));
        int completed = 0;
        do {
            var current = progress.snapshot();
            assertTrue(current.completed() >= completed && current.completed() <= current.total());
            assertEquals(before.started(), current.started());
            completed = current.completed();
        } while (!workers.isDone());
        workers.join();
        assertEquals(100_000, progress.snapshot().completed());
        assertEquals(100, progress.snapshot().percent());
        progress.begin("upload", 3);
        progress.advance();
        assertEquals(33, progress.snapshot().percent());
        assertEquals(0, before.completed(), "A worker must not mutate a snapshot already held by the UI");
        assertEquals("surfaces", before.task());
    }
}
