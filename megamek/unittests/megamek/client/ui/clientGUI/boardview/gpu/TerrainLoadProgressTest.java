/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class TerrainLoadProgressTest {
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
