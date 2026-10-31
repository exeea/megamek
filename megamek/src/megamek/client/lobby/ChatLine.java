/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbySnapshot.Relation;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.server.Server;

/**
 * One line of the chat box read apart, so that a view can set the sender off from what they said.
 *
 * @param kind     a player's message, a notice from the server or MegaMek, or a line of unknown form
 * @param sender   the speaking player's name; empty unless a player spoke
 * @param text     what was said, without the sender
 * @param relation how the sender stands to the local player; {@link Relation#NONE} unless a player spoke
 */
public record ChatLine(Kind kind, String sender, String text, Relation relation) {

    /** What kind of line it is. */
    public enum Kind {
        /** A player's message. */
        PLAYER,
        /** A notice from the server or from MegaMek itself. */
        NOTICE,
        /** A line of unknown form, shown as it came. */
        PLAIN
    }

    public ChatLine {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(sender);
        Objects.requireNonNull(text);
        Objects.requireNonNull(relation);
    }

    /**
     * Reads a chat box line: "***Server: ..." and the chat box's "MegaMek: ..." are notices, a known player's name before
     * ": " makes them the sender (the longest name that fits, so "Bob: " never takes "Bob Jr: ..."), and anything else
     * stays as it came.
     *
     * @param line    the line as the chat box shows it
     * @param players the players who may have spoken
     * @param local   the local player, or null when not yet known
     */
    public static ChatLine parse(String line, List<Player> players, @Nullable Player local) {
        String server = Server.ORIGIN + ": ";
        if (line.startsWith(server)) {
            return new ChatLine(Kind.NOTICE, "", line.substring(server.length()), Relation.NONE);
        }
        String megamek = Messages.getString("ChatterBox.MegaMek") + " ";
        if (line.startsWith(megamek)) {
            return new ChatLine(Kind.NOTICE, "", line.substring(megamek.length()), Relation.NONE);
        }
        Player sender = players.stream()
              .filter(player -> line.startsWith(player.getName() + ": "))
              .max(Comparator.comparingInt(player -> player.getName().length()))
              .orElse(null);
        if (sender == null) {
            return new ChatLine(Kind.PLAIN, "", line, Relation.NONE);
        }
        return new ChatLine(Kind.PLAYER, sender.getName(), line.substring(sender.getName().length() + 2),
              relation(sender, local));
    }

    /** How {@code sender} stands to {@code local}: themselves, a teammate, an enemy, or on no team. */
    static Relation relation(Player sender, @Nullable Player local) {
        if (local == null || sender.getId() == local.getId()) {
            return Relation.OWN;
        } else if (sender.getTeam() <= Player.TEAM_NONE) {
            return Relation.NONE;
        }
        return sender.isEnemyOf(local) ? Relation.ENEMY : Relation.ALLY;
    }
}
