/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.function.Supplier;

/**
 * A board model kit read on first use and then shared by every terrain worker for the session. A failed read is not
 * kept: closing a board interrupts the worker that is reading, and the next board simply reads the kit again.
 */
final class BoardKit<T> {
    private final Supplier<T> loader;
    private volatile T kit;

    BoardKit(Supplier<T> loader) { this.loader = loader; }

    T get() {
        T result = kit;
        if (result == null) {
            synchronized (this) {
                result = kit;
                if (result == null) { kit = result = load(); }
            }
        }
        return result;
    }

    /** Publish a complete replacement; workers keep the snapshot they already hold. */
    synchronized void reload() { kit = load(); }

    private T load() {
        // The glTF reader fails on any pending interrupt. Read without it, then hand it back to the caller.
        boolean interrupted = Thread.interrupted();
        try {
            return loader.get();
        } finally {
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
