/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.AbstractButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;
import megamek.client.ui.util.KeyCommandBind;
import org.apache.commons.text.StringEscapeUtils;

/** Describes and dispatches existing menus; both native map tools and gameplay use the same actions. */
final class GpuMenuCommands {
    private static final Set<String> MOVEMENT_COMMANDS = java.util.Arrays.stream(MoveCommand.values())
          .map(MoveCommand::getCmd).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final BooleanSupplier closed;
    private final Runnable changed;
    GpuMenuCommands(BooleanSupplier closed, Runnable changed) {
        this.closed = closed;
        this.changed = changed;
    }
    List<BoardScene.Command> capture(Container menu, Supplier<Container> refresh, BooleanSupplier current) {
        return capture(menu, refresh, current, List.of());
    }
    static boolean menuShortcut(Container menu, KeyStroke key) {
        for (Component component : menu.getComponents()) {
            if (!(component instanceof JMenuItem item) || !item.isVisible() || !item.isEnabled()
                  || ClientGUI.VIEW_UNIT_OVERVIEW.equals(item.getActionCommand())) {
                continue;
            }
            if (item instanceof JMenu group) {
                if (menuShortcut(group.getPopupMenu(), key)) {
                    return true;
                }
            } else if (key.equals(item.getAccelerator())) {
                item.doClick(0);
                return true;
            }
        }
        return false;
    }

    private List<BoardScene.Command> capture(Container menu, Supplier<Container> refresh, BooleanSupplier current,
          List<String> parents) {
        List<BoardScene.Command> result = new ArrayList<>();
        for (Component component : menu.getComponents()) {
            if (!(component instanceof JMenuItem item) || !item.isVisible()
                  || ClientGUI.VIEW_UNIT_OVERVIEW.equals(item.getActionCommand())) {
                continue;
            }
            String key = menuKey(item);
            List<String> path = new ArrayList<>(parents);
            path.add(key);
            List<BoardScene.Command> children = item instanceof JMenu group
                  ? capture(group.getPopupMenu(), refresh, current, path) : List.of();
            result.add(describe(String.join("/", path), item, false, children, () -> {
                if (!current.getAsBoolean()) {
                    return;
                }
                // Rebuild contextual choices to check visibility, targets and availability at execution time.
                JMenuItem action = findItem(refresh.get(), path);
                if (action != null && action.isEnabled()) {
                    action.doClick(0);
                }
            }));
        }
        return result;
    }

    /** A menu item's key, "{action command}:{text}"; a menu command's id is its menu path of keys joined by "/". */
    private static String menuKey(JMenuItem item) {
        return Objects.toString(item.getActionCommand(), "") + ":" + item.getText();
    }

    private static JMenuItem findItem(Container menu, List<String> path) {
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item && item.isVisible() && item.isEnabled()
                  && menuKey(item).equals(path.getFirst())) {
                if (path.size() == 1) {
                    return item;
                }
                return item instanceof JMenu group ? findItem(group.getPopupMenu(), path.subList(1, path.size())) : null;
            }
        }
        return null;
    }

    /** A menu item also carries its accelerator text and, for a check or radio item, its selection. */
    BoardScene.Command describe(String id, AbstractButton button, boolean commit,
          List<BoardScene.Command> children, Runnable action) {
        KeyStroke accelerator = button instanceof JMenuItem item ? item.getAccelerator() : null;
        Boolean selected = button instanceof JCheckBoxMenuItem || button instanceof JRadioButtonMenuItem
              ? button.isSelected() : null;
        return new BoardScene.Command(id, plainText(button.getText()), plainText(button.getToolTipText()),
              button.isEnabled(), commit, MOVEMENT_COMMANDS.contains(button.getActionCommand())
                    || Set.of("fireTwist", "fireStrafe").contains(button.getActionCommand()), children,
              dispatch(action),
              accelerator == null ? "" : KeyCommandBind.getDesc(accelerator.getKeyCode(), accelerator.getModifiers()),
              selected);
    }

    Runnable dispatch(Runnable action) {
        return () -> SwingUtilities.invokeLater(() -> {
            if (!closed.getAsBoolean()) {
                action.run();
                changed.run();
            }
        });
    }

    static String plainText(String text) {
        String stripped = text == null ? "" : text.replaceAll("(?is)<head>.*?</head>", "")
              .replaceAll("(?i)<(?:br\\s*/?|/tr|/p|/div)>", "\n")
              .replaceAll("(?i)</t[dh]>", "  ").replaceAll("<[^>]*>", "")
              .replace("&apos;", "'");
        return StringEscapeUtils.unescapeHtml4(stripped).replace('\u00A0', ' ').strip();
    }
}
