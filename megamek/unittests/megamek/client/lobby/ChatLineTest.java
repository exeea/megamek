/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import megamek.client.lobby.ChatLine.Kind;
import megamek.client.lobby.LobbySnapshot.Relation;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.server.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatLineTest {

    private Player local;
    private Player ally;
    private Player enemy;
    private Player loner;
    private List<Player> players;

    @BeforeEach
    void setUp() {
        local = player(1, "Bob", 1);
        ally = player(2, "Bob Jr", 1);
        enemy = player(3, "Alice", 2);
        loner = player(4, "Carol", Player.TEAM_NONE);
        players = List.of(local, ally, enemy, loner);
    }

    private static Player player(int id, String name, int team) {
        Player player = new Player(id, name);
        player.setTeam(team);
        return player;
    }

    @Test
    void readsTheSenderAndTheirSide() {
        assertEquals(new ChatLine(Kind.PLAYER, "Bob", "hello", Relation.OWN),
              ChatLine.parse("Bob: hello", players, local));
        assertEquals(new ChatLine(Kind.PLAYER, "Alice", "hi: there", Relation.ENEMY),
              ChatLine.parse("Alice: hi: there", players, local));
        assertEquals(Relation.NONE, ChatLine.parse("Carol: me too", players, local).relation());
    }

    @Test
    void theLongestMatchingNameWins() {
        assertEquals(new ChatLine(Kind.PLAYER, "Bob Jr", "ready", Relation.ALLY),
              ChatLine.parse("Bob Jr: ready", players, local));
    }

    @Test
    void serverAndMegaMekLinesAreNotices() {
        assertEquals(new ChatLine(Kind.NOTICE, "", "Alice connected.", Relation.NONE),
              ChatLine.parse(Server.ORIGIN + ": Alice connected.", players, local));
        assertEquals(new ChatLine(Kind.NOTICE, "", "Players: 4", Relation.NONE),
              ChatLine.parse(Messages.getString("ChatterBox.MegaMek") + " Players: 4", players, local));
    }

    @Test
    void unknownLinesStayAsTheyCame() {
        assertEquals(new ChatLine(Kind.PLAIN, "", "Dave: who am I", Relation.NONE),
              ChatLine.parse("Dave: who am I", players, local));
    }

    @Test
    void withoutALocalPlayerEverySenderIsOwnSide() {
        assertEquals(Relation.OWN, ChatLine.parse("Alice: hi", players, null).relation());
    }

    @Test
    void historyStepsThroughSentLines() {
        ChatHistory history = new ChatHistory();
        assertNull(history.older());
        assertEquals("", history.newer());

        history.sent("first");
        history.sent("  ");
        history.sent("second");

        assertEquals("second", history.older());
        assertEquals("first", history.older());
        assertEquals("first", history.older());
        assertEquals("second", history.newer());
        assertEquals("", history.newer());
        assertEquals("", history.newer());
        assertEquals("second", history.older());
    }

    @Test
    void historyKeepsTheChatBoxsNumberOfLines() {
        ChatHistory history = new ChatHistory();
        for (int line = 0; line < ChatHistory.MAX_HISTORY + 3; line++) {
            history.sent("line " + line);
        }
        String oldest = null;
        for (int step = 0; step < ChatHistory.MAX_HISTORY + 3; step++) {
            oldest = history.older();
        }
        assertEquals("line 3", oldest);
    }
}
