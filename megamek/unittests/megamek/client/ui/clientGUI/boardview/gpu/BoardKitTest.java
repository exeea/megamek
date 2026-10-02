/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Closing a board interrupts the terrain worker that is reading a kit; later boards must still get the kit. */
class BoardKitTest {
    private static final String ROCK = "rocks/boulder-0";

    @Test
    void interruptedFirstReadIsRetriedByTheNextUse() throws Exception {
        var reads = new AtomicInteger();
        var kit = new BoardKit<>(() -> {
            // Disposal interrupts the worker after its read has begun.
            if (reads.getAndIncrement() == 0) { Thread.currentThread().interrupt(); }
            return BoardShape.loadKit(ROCK);
        });
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        var worker = new Thread(() -> {
            try {
                kit.get();
            } catch (Throwable error) {
                failure.set(error);
            }
            interrupted.set(Thread.currentThread().isInterrupted());
        }, "terrain-detail-test");
        worker.start();
        worker.join();

        assertNotNull(failure.get(), "The interrupted read fails on its own worker");
        assertTrue(failure.get().getMessage().contains("Interrupted"), failure.get().toString());
        assertTrue(interrupted.get(), "The worker keeps its interrupt for its own cancellation");
        var shapes = kit.get();
        assertEquals(BoardShape.loadKit(ROCK).keySet(), shapes.keySet());
        assertSame(shapes, kit.get(), "A successful read is shared for the session");
        assertEquals(2, reads.get());
    }

    @Test
    void pendingInterruptDoesNotFailTheRead() {
        var kit = new BoardKit<>(() -> BoardShape.loadKit(ROCK));
        Thread.currentThread().interrupt();
        try {
            assertFalse(kit.get().isEmpty());
            assertTrue(Thread.currentThread().isInterrupted(), "The caller's interrupt is restored");
        } finally {
            Thread.interrupted();
        }
    }
}
