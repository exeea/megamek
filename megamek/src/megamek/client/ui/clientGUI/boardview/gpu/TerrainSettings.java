/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.function.Supplier;

/** Immutable presentation settings shared by a terrain build and the frames displaying its result. */
record TerrainSettings(BoardGeometry.Tuning geometry, BoardRelief.Tuning relief, BoardSurface.Tuning water,
      List<BoardRelief.Geology> geology, BoardConcrete.Mode concrete, int revision, int terrainRevision) {
    private static final ThreadLocal<TerrainSettings> CURRENT = new ThreadLocal<>();

    static TerrainSettings current() { return CURRENT.get(); }

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
        private final TerrainSettings previous = CURRENT.get();

        private Scope(TerrainSettings settings) { set(settings); }

        void set(TerrainSettings settings) {
            if (settings == null) { CURRENT.remove(); } else { CURRENT.set(settings); }
        }

        @Override
        public void close() { set(previous); }
    }
}
