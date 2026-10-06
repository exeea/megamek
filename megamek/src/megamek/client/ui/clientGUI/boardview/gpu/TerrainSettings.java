/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Immutable presentation settings shared by a terrain build and the frames displaying its result. */
record TerrainSettings(BoardGeometry.Tuning geometry, BoardRelief.Tuning relief, BoardSurface.Tuning water,
      List<BoardRelief.Geology> geology, BoardConcrete.Mode concrete, int revision, int terrainRevision) {
    private static final ThreadLocal<TerrainSettings> CURRENT = new ThreadLocal<>();
    private static final AtomicInteger WORKERS = new AtomicInteger();

    /**
     * A terrain worker keeps its active settings in a field. The board geometry reads them for every vertex it
     * places, and the thread-local lookup was a measurable share of a chunk's build. Other threads keep the
     * thread-local scope.
     */
    static final class Worker extends ForkJoinWorkerThread {
        private TerrainSettings settings;

        private Worker(ForkJoinPool pool) {
            super(pool);
            setName("terrain-detail-" + WORKERS.getAndIncrement());
        }
    }

    static TerrainSettings current() {
        return Thread.currentThread() instanceof Worker worker ? worker.settings : CURRENT.get();
    }

    /** Terrain worker threads, numbered as they start so a profile can tell them apart. */
    static ForkJoinPool workers(int parallelism) {
        return new ForkJoinPool(parallelism, Worker::new, null, false);
    }

    /** Capture the controls, even when the caller is displaying an older, completed terrain revision. */
    static TerrainSettings capture() {
        try (Scope ignored = use(null)) {
            return new TerrainSettings(BoardGeometry.tuning(), BoardRelief.tuning(), BoardSurface.tuning(),
                  BoardRelief.geology(), BoardConcrete.mode(), BoardGeometry.revision(), BoardGeometry.terrainRevision());
        }
    }

    static Scope use(TerrainSettings settings) { return new Scope(settings); }

    <T> T call(Supplier<T> work) {
        try (Scope ignored = use(this)) { return work.get(); }
    }

    void run(Runnable work) {
        try (Scope ignored = use(this)) { work.run(); }
    }

    /** A scope is local to its thread; parallel tile work enters its own scope explicitly. */
    static final class Scope implements AutoCloseable {
        private final TerrainSettings previous = current();

        private Scope(TerrainSettings settings) { set(settings); }

        void set(TerrainSettings settings) {
            if (Thread.currentThread() instanceof Worker worker) { worker.settings = settings; }
            else if (settings == null) { CURRENT.remove(); } else { CURRENT.set(settings); }
        }

        @Override
        public void close() { set(previous); }
    }
}
