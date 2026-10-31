/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs work on an executor where only the newest asked for counts: work asked for before newer work and not yet
 * started is skipped, and the result of work overtaken while it ran is dropped. The Requisition dossier loads the
 * picked unit's record through it, so a Commander running down the list waits only for the unit they stop on.
 */
public final class RequisitionLoader {
    private final Executor executor;
    private final AtomicLong latest = new AtomicLong();

    public RequisitionLoader(Executor executor) {
        this.executor = executor;
    }

    /** Runs {@code work} and hands its result to {@code done}, on the executor's thread, unless newer work came. */
    public <T> void load(Supplier<T> work, Consumer<T> done) {
        long ticket = latest.incrementAndGet();
        try {
            executor.execute(() -> {
                if (latest.get() != ticket) {
                    return;
                }
                T result = work.get();
                if (latest.get() == ticket) {
                    done.accept(result);
                }
            });
        } catch (RejectedExecutionException shutDown) {
            // The view has closed
        }
    }

    /** Drops whatever was asked for, as when the view closes. */
    public void cancel() {
        latest.incrementAndGet();
    }
}
