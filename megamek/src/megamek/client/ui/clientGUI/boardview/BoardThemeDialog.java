/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import java.awt.Component;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.JOptionPane;

import megamek.common.board.Board;

/** The shared theme action edits the board; views only observe the resulting board event. */
public final class BoardThemeDialog {
    private BoardThemeDialog() { }
    public static String choose(Component owner, Board board, Set<String> available, String selected) {
        if (board.isSpace()) { return null; }
        Set<String> themes = new TreeSet<>(available);
        if (themes.remove("")) { themes.add("(No Theme)"); }
        themes.add("(Original Theme)");
        String choice = (String) JOptionPane.showInputDialog(owner, "Choose the desired theme:", "Theme Selection",
              JOptionPane.PLAIN_MESSAGE, null, themes.toArray(), selected);
        if (choice == null) { return null; }
        String theme = choice.equals("(Original Theme)") ? null : choice.equals("(No Theme)") ? "" : choice;
        board.setTheme(theme);
        return theme;
    }
}
