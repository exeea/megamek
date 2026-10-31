/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import megamek.client.lobby.LobbySnapshot.Relation;
import org.junit.jupiter.api.Test;

class ChatFoldTest {

    private static ChatLine said(String sender, String text, Relation relation) {
        return new ChatLine(ChatLine.Kind.PLAYER, sender, text, relation);
    }

    private static ChatLine notice(String text) {
        return new ChatLine(ChatLine.Kind.NOTICE, "", text, Relation.NONE);
    }

    private static List<ChatLine> plus(List<ChatLine> lines, ChatLine... more) {
        List<ChatLine> all = new ArrayList<>(lines);
        all.addAll(List.of(more));
        return all;
    }

    @Test
    void linesFromOthersCountWhileFoldedAndOpeningClearsThem() {
        ChatFold fold = new ChatFold(false);
        List<ChatLine> lines = List.of(notice("Welcome"));
        fold.show(lines, true);
        assertEquals(0, fold.unread());
        assertEquals("Welcome", fold.latest().text());

        lines = plus(lines, said("Me", "hello", Relation.OWN), said("Bot", "hi", Relation.ENEMY),
              notice("Bot is ready"), said("Pal", "o7", Relation.ALLY));
        fold.show(lines, false);
        // The local player's own line doesn't count; the notice does, as in the briefing room
        assertEquals(3, fold.unread());
        assertEquals("o7", fold.latest().text());

        fold.open(true);
        assertTrue(fold.open());
        assertEquals(0, fold.unread());
        fold.show(plus(lines, said("Bot", "again", Relation.ENEMY)), false);
        assertEquals(0, fold.unread());
    }

    @Test
    void theSameLinesShownAgainAddNothing() {
        ChatFold fold = new ChatFold(false);
        List<ChatLine> lines = List.of(said("Bot", "hi", Relation.ENEMY));
        fold.show(List.of(), true);
        fold.show(lines, false);
        fold.show(lines, false);
        assertEquals(1, fold.unread());
        assertFalse(fold.open());
    }

    @Test
    void theBaselineIsTakenAsRead() {
        ChatFold fold = new ChatFold(false);
        assertNull(fold.latest());
        fold.show(List.of(said("Bot", "hi", Relation.ENEMY), notice("x")), true);
        assertEquals(0, fold.unread());
    }

    @Test
    void addedCountsNewLinesPastTheCap() {
        ChatLine a = notice("a");
        ChatLine b = notice("b");
        ChatLine c = notice("c");
        ChatLine d = notice("d");
        assertEquals(0, ChatFold.added(List.of(a, b, c), List.of(a, b, c)));
        assertEquals(1, ChatFold.added(List.of(a, b, c), List.of(a, b, c, d)));
        // The oldest line dropped past the cap as a new one came
        assertEquals(1, ChatFold.added(List.of(a, b, c), List.of(b, c, d)));
        assertEquals(2, ChatFold.added(List.of(a, b, c), List.of(c, d, a)));
        assertEquals(2, ChatFold.added(List.of(), List.of(a, b)));
        assertEquals(2, ChatFold.added(List.of(a), List.of(c, d)));
        // A repeated line still reads as one more
        assertEquals(1, ChatFold.added(List.of(a, a), List.of(a, a, a)));
    }
}
