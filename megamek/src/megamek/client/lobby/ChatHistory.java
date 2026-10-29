/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.LinkedList;

/**
 * The lines a chat input sent, to call back with the up and down keys as the chat box's input line does: up steps to
 * older lines, down to newer ones and past the newest back to an empty line. It keeps the chat box's
 * {@link #MAX_HISTORY} lines. Not thread-safe; use it from the input's thread.
 */
public final class ChatHistory {
    /** How many sent lines are kept, as in the chat box. */
    public static final int MAX_HISTORY = 10;

    private final LinkedList<String> lines = new LinkedList<>();
    /** The line shown, 0 the newest; -1 when none is. */
    private int bookmark = -1;

    /** Records a sent line as the newest and starts over from it; blank lines are not kept. */
    public void sent(String line) {
        bookmark = -1;
        if (line.isBlank()) {
            return;
        }
        lines.addFirst(line);
        if (lines.size() > MAX_HISTORY) {
            lines.removeLast();
        }
    }

    /** @return the next older line, or the oldest when there is none older, or null when nothing was sent */
    public String older() {
        if (lines.isEmpty()) {
            return null;
        }
        bookmark = Math.min(bookmark + 1, lines.size() - 1);
        return lines.get(bookmark);
    }

    /** @return the next newer line, or an empty line after the newest */
    public String newer() {
        bookmark = Math.max(bookmark - 1, -1);
        return bookmark < 0 ? "" : lines.get(bookmark);
    }
}
