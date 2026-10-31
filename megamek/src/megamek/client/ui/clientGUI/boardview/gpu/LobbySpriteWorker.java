/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import jakarta.annotation.Nullable;

/**
 * Draws queued items on a daemon thread of its own, the latest asked first, until stopped. The thread starts with the
 * first request and ends once {@link #stop} is called, after the item it may be drawing, so a closed lobby leaves no
 * thread behind holding it.
 */
final class LobbySpriteWorker<T> {
    private final String name;
    private final Consumer<T> draw;
    private final Deque<T> queue = new ArrayDeque<>();
    private Thread thread;
    private boolean stopped;

    LobbySpriteWorker(String name, Consumer<T> draw) {
        this.name = name;
        this.draw = draw;
    }

    /** Any thread: queues {@code items} ahead of earlier ones, starting the thread if needed; nothing once stopped. */
    void request(List<T> items) {
        synchronized (queue) {
            if (stopped) {
                return;
            }
            for (T item : items) {
                queue.remove(item);
                queue.addFirst(item);
            }
            if (thread == null) {
                thread = new Thread(this::run, name);
                thread.setDaemon(true);
                thread.start();
            }
            queue.notifyAll();
        }
    }

    /** Any thread: drops the queue and lets the thread end. */
    void stop() {
        synchronized (queue) {
            stopped = true;
            queue.clear();
            queue.notifyAll();
        }
    }

    /** The thread, once a request started it. */
    @Nullable Thread thread() {
        synchronized (queue) {
            return thread;
        }
    }

    private void run() {
        while (true) {
            T item;
            synchronized (queue) {
                while (queue.isEmpty() && !stopped) {
                    try {
                        queue.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (stopped) {
                    return;
                }
                item = queue.pollFirst();
            }
            draw.accept(item);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }
}
