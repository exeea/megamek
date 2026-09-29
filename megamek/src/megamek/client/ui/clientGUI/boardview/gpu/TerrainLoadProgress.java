/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.concurrent.atomic.AtomicInteger;

import megamek.client.ui.Messages;

/** Job-owned diagnostic progress; workers publish immutable snapshots for the loading screen. */
final class TerrainLoadProgress {
    record Step(String task, int completed, int total, long started) {
        int percent() { return total == 0 ? 100 : (int) (100L * completed / total); }

        String text() {
            long seconds = Math.max(0, (System.nanoTime() - started) / 1_000_000_000L);
            return Messages.getString("GpuBoard.loadingTask", Messages.getString("GpuBoard.loading." + task),
                  percent(), completed, total, seconds);
        }
    }

    record Status(int section, int sections, Step step) {
        String text() {
            return section == 0 ? step.text()
                  : Messages.getString("GpuBoard.loadingSection", section, sections, step.text());
        }
    }

    private record Task(String name, int total, long started, AtomicInteger completed) { }
    private volatile Task task = new Task("queued", 1, System.nanoTime(), new AtomicInteger());

    void begin(String name, int total) {
        task = new Task(name, total, System.nanoTime(), new AtomicInteger());
    }

    /** Parallel tile completions share this counter; stage changes happen after their join. */
    void advance() { task.completed().incrementAndGet(); }

    Step snapshot() {
        Task current = task;
        return new Step(current.name(), current.completed().get(), current.total(), current.started());
    }
}
