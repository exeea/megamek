/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class LobbySpriteWorkerTest {
    @Test
    void theThreadDrawsWhatIsAskedThenEndsOnceStopped() throws Exception {
        List<String> drawn = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        LobbySpriteWorker<String> worker = new LobbySpriteWorker<>("test sprites", item -> {
            drawn.add(item);
            done.countDown();
        });

        worker.request(List.of("Atlas", "Locust"));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        Thread thread = worker.thread();
        assertNotNull(thread);
        assertEquals(2, drawn.size());

        worker.stop();
        thread.join(5000);
        assertFalse(thread.isAlive(), "the idle worker must end once the lobby stops it");
    }

    @Test
    void aStoppedWorkerStartsNoThread() {
        LobbySpriteWorker<String> worker = new LobbySpriteWorker<>("test sprites", item -> { });
        worker.stop();
        worker.request(List.of("Atlas"));
        assertNull(worker.thread());
    }
}
