/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.List;

import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbySnapshot.Relation;

/**
 * Whether the lobby's chat dock is open or folded to its header and input line, and how many lines arrived while it was
 * folded. As the briefing room's chat dock, every new line counts but the local player's own: notices from the server
 * or MegaMek count as lines from others. Opening the dock clears the count. Not thread-safe; use it from the view's
 * thread.
 */
public final class ChatFold {
    private boolean open;
    private int unread;
    private @Nullable List<ChatLine> shown;

    /** @param open whether the dock starts open, as the client last left it */
    public ChatFold(boolean open) {
        this.open = open;
    }

    public boolean open() {
        return open;
    }

    /** How many lines from others arrived while the dock was folded. */
    public int unread() {
        return unread;
    }

    /** Opens the dock, which clears the unread count, or folds it. */
    public void open(boolean value) {
        open = value;
        if (open) {
            unread = 0;
        }
    }

    /** The newest line shown, for the folded header; null when there is none. */
    public @Nullable ChatLine latest() {
        return (shown == null || shown.isEmpty()) ? null : shown.get(shown.size() - 1);
    }

    /**
     * Takes the chat's recent {@code lines}, the newest last; while folded, each line from others that wasn't shown
     * before counts as unread. With {@code baseline}, as when the lobby first shows its chat, the lines are taken as
     * read.
     */
    public void show(List<ChatLine> lines, boolean baseline) {
        if (!baseline && !open && shown != null) {
            List<ChatLine> added = lines.subList(lines.size() - added(shown, lines), lines.size());
            unread += (int) added.stream().filter(ChatFold::counts).count();
        }
        shown = List.copyOf(lines);
    }

    /** Whether {@code line} counts as unread: anything but the local player's own words. */
    static boolean counts(ChatLine line) {
        return line.kind() != ChatLine.Kind.PLAYER || line.relation() != Relation.OWN;
    }

    /**
     * How many lines at the end of {@code after} are new since {@code before}: the fewest such that the rest of
     * {@code after} is the end of {@code before}, as the chat only appends and drops its oldest lines past its cap.
     */
    static int added(List<ChatLine> before, List<ChatLine> after) {
        for (int news = 0; news < after.size(); news++) {
            int kept = after.size() - news;
            if (kept <= before.size() && before.subList(before.size() - kept, before.size())
                  .equals(after.subList(0, kept))) {
                return news;
            }
        }
        return after.size();
    }
}
