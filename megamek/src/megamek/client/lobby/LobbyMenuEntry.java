/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.List;
import java.util.Objects;

import jakarta.annotation.Nullable;

/**
 * One entry of a menu built from the game, such as the unit menu, for a view that draws its own menus: an item,
 * a submenu with its {@link #children()}, or a separator. The view hands the chosen item back by its path of indices.
 *
 * @param label     the entry's text; empty for a separator
 * @param enabled   whether the entry may be chosen
 * @param checked   whether a check item is ticked, or null for an entry that is not one
 * @param tooltip   the entry's tooltip, or empty
 * @param children  a submenu's entries; empty for an item
 * @param separator whether the entry is a separator
 */
public record LobbyMenuEntry(String label, boolean enabled, @Nullable Boolean checked, String tooltip,
      List<LobbyMenuEntry> children, boolean separator) {

    /** A separator. */
    public static final LobbyMenuEntry SEPARATOR = new LobbyMenuEntry("", false, null, "", List.of(), true);

    public LobbyMenuEntry {
        Objects.requireNonNull(label);
        Objects.requireNonNull(tooltip);
        children = List.copyOf(children);
    }

    /** @return an item that may be chosen when {@code enabled} */
    public static LobbyMenuEntry item(String label, boolean enabled) {
        return new LobbyMenuEntry(label, enabled, null, "", List.of(), false);
    }

    /** @return a submenu of {@code children} */
    public static LobbyMenuEntry submenu(String label, boolean enabled, List<LobbyMenuEntry> children) {
        return new LobbyMenuEntry(label, enabled, null, "", children, false);
    }

    /** @return whether the entry opens a submenu */
    public boolean submenu() {
        return !children.isEmpty();
    }
}
