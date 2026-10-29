/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSeparator;

import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyMenuEntry;

/**
 * Reads a Swing menu, such as the Swing lobby's unit menu, into {@link LobbyMenuEntry}s that the native lobby draws
 * itself, and chooses an item of it by its path of indices, so that the Swing menu's own action runs. Swing thread only.
 * Empty, disabled items (the Swing lobby's spacers) read as separators; doubled separators and those at either end are
 * left out.
 */
final class LobbySwingMenus {

    private LobbySwingMenus() { }

    /** An entry and the Swing item behind it. */
    private record Node(LobbyMenuEntry entry, @Nullable JMenuItem item, List<Node> children) { }

    /** @return the entries of {@code menu} */
    static List<LobbyMenuEntry> read(JPopupMenu menu) {
        return nodes(menu.getComponents()).stream().map(Node::entry).toList();
    }

    /**
     * Chooses the item at {@code path}, indices into {@link #read}'s entries and their children, as a click would.
     *
     * @return whether an enabled item was there
     */
    static boolean choose(JPopupMenu menu, List<Integer> path) {
        List<Node> level = nodes(menu.getComponents());
        Node node = null;
        for (int index : path) {
            if (index < 0 || index >= level.size()) {
                return false;
            }
            node = level.get(index);
            level = node.children();
        }
        if (node == null || node.item() == null || !node.entry().enabled() || node.entry().submenu()) {
            return false;
        }
        node.item().doClick(0);
        return true;
    }

    private static List<Node> nodes(Component[] components) {
        List<Node> nodes = new ArrayList<>();
        for (Component component : components) {
            if (!component.isVisible()) {
                continue;
            }
            if (component instanceof JSeparator || (component instanceof JMenuItem item && !(item instanceof JMenu)
                  && !item.isEnabled() && text(item).isBlank())) {
                if (!nodes.isEmpty() && !nodes.getLast().entry().separator()) {
                    nodes.add(new Node(LobbyMenuEntry.SEPARATOR, null, List.of()));
                }
            } else if (component instanceof JMenu menu) {
                List<Node> children = nodes(menu.getMenuComponents());
                List<LobbyMenuEntry> entries = children.stream().map(Node::entry).toList();
                boolean enabled = menu.isEnabled() && !children.isEmpty();
                nodes.add(new Node(new LobbyMenuEntry(text(menu), enabled, null, tooltip(menu), entries, false),
                      menu, children));
            } else if (component instanceof JMenuItem item) {
                Boolean checked = item instanceof JCheckBoxMenuItem || item instanceof JRadioButtonMenuItem
                      ? item.isSelected() : null;
                nodes.add(new Node(new LobbyMenuEntry(text(item), item.isEnabled(), checked, tooltip(item), List.of(),
                      false), item, List.of()));
            }
        }
        if (!nodes.isEmpty() && nodes.getLast().entry().separator()) {
            nodes.removeLast();
        }
        return nodes;
    }

    /** The item's text without HTML markup. */
    static String text(JMenuItem item) {
        return plain(item.getText());
    }

    private static String tooltip(JMenuItem item) {
        return plain(item.getToolTipText());
    }

    private static String plain(@Nullable String text) {
        if (text == null) {
            return "";
        }
        if (!text.regionMatches(true, 0, "<html>", 0, 6)) {
            return text.strip();
        }
        return text.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]*>", "").replace("&nbsp;", " ")
              .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").strip();
    }
}
