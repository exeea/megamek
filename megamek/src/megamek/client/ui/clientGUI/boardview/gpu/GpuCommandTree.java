/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;

/**
 * The scrolling menu list of a dialog that shows MegaMek's command trees (C.2 G14: the Menu and Players panels).
 * Every group is a row that expands in place, its rows indented below it; an item runs its command. While the dialog
 * is open it takes the keyboard, so Up, Down, Home, End and Enter move through the list.
 */
final class GpuCommandTree {
    /** A row between two parts of the list: the menu list's hairline. */
    static final Object SEPARATOR = new Object();
    /** The menu row's own left padding (.pop .it). */
    private static final float PADDING = 14;
    /** One level of indent: a check mark and its gap. */
    private static final float INDENT = 26;

    /**
     * One row of a tree as shown: its key (the parent's key and the command's id), its depth, the label, the shortcut,
     * the command's own detail as the tooltip, and its states; {@code checked} is null when none of its siblings is a
     * toggle, so their labels need no check mark's column. Equal rows draw alike.
     */
    record Row(String key, int depth, String text, String shortcut, String tip, Boolean checked, boolean group,
          boolean expanded, boolean enabled) { }

    /** The list's scroll pane, the dialog's body. */
    final ScrollPane body;
    private final UiKit ui;
    private final Actor dialog;
    /** The keys of the expanded groups; they stay expanded while the dialog closes and opens again. */
    private final Set<String> expanded = new HashSet<>();
    /** The newest command of each key, so an item runs what the latest frame captured. */
    private final Map<String, BoardScene.Command> commands = new HashMap<>();
    /** The current list's item of each key. */
    private final Map<String, UiButton> items = new HashMap<>();
    private UiMenuList list;
    private List<Object> shown;
    private boolean open;

    /** A tree list in {@code dialog}, which forwards its keys to the list while it holds the keyboard focus. */
    GpuCommandTree(UiKit ui, Actor dialog) {
        this.ui = ui;
        this.dialog = dialog;
        list = new UiMenuList(ui);
        body = ui.scrollList(list);
        dialog.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return list.keyDown(keycode);
            }
        });
    }

    /**
     * The dialog opens or closes. Opening takes the keyboard focus for the list and starts without a highlight at the
     * list's top; closing gives back a focus held inside the dialog.
     */
    void open(boolean open) {
        if (open == this.open) {
            return;
        }
        this.open = open;
        Stage stage = dialog.getStage();
        if (open) {
            shown = null;
            if (stage != null) {
                stage.setKeyboardFocus(dialog);
            }
        } else if (stage != null && stage.getKeyboardFocus() != null
              && stage.getKeyboardFocus().isDescendantOf(dialog)) {
            stage.setKeyboardFocus(null);
        }
    }

    /**
     * Appends the rows of {@code commands} under {@code parent} at {@code depth} to {@code rows}: each command, and an
     * enabled group's rows below it while it is expanded.
     */
    void rows(List<BoardScene.Command> commands, String parent, int depth, List<Object> rows) {
        // As in a Swing menu, the items of a list with a toggle line their labels up after the check marks.
        boolean toggles = commands.stream().anyMatch(command -> command.selected() != null);
        for (BoardScene.Command command : commands) {
            String key = parent + "/" + command.id();
            boolean group = !command.children().isEmpty();
            boolean open = group && command.enabled() && expanded.contains(key);
            this.commands.put(key, command);
            rows.add(new Row(key, depth, command.label(), command.shortcut(), command.detail(),
                  toggles ? Boolean.TRUE.equals(command.selected()) : null, group, open, command.enabled()));
            if (open) {
                rows(command.children(), key, depth + 1, rows);
            }
        }
    }

    /**
     * Shows {@code rows} when they differ from the shown ones, in a new list: a {@link Row} as an item, which expands
     * or collapses a group and passes an item's command to {@code run}; {@link #SEPARATOR} as a hairline; any other
     * row as the actor {@code other} builds for it (null when there are none). The highlight and the scroll position
     * stay where they were.
     */
    void show(List<Object> rows, Function<Object, Actor> other, Consumer<BoardScene.Command> run) {
        if (rows.equals(shown)) {
            return;
        }
        boolean fresh = shown == null;
        shown = rows;
        String highlighted = fresh ? null : items.entrySet().stream().filter(item -> item.getValue().isChecked())
              .map(Map.Entry::getKey).findFirst().orElse(null);
        float scrollY = fresh ? 0 : body.getScrollY();
        list = new UiMenuList(ui);
        items.clear();
        for (Object row : rows) {
            if (row instanceof Row item) {
                items.put(item.key(), add(item, run));
            } else if (row == SEPARATOR) {
                list.separator();
            } else {
                list.add(other.apply(row)).growX().row();
            }
        }
        body.setActor(list);
        body.validate();
        body.setScrollY(scrollY);
        body.updateVisualScroll();
        if (highlighted != null && items.containsKey(highlighted)) {
            list.highlight(items.get(highlighted));
        }
    }

    private UiButton add(Row row, Consumer<BoardScene.Command> run) {
        UiButton item = list.item(row.text(), row.shortcut(), row.checked(), row.group(), row.enabled(), () -> {
            if (row.group()) {
                // The next frame's rows differ in this group's state and show it.
                if (!expanded.remove(row.key())) {
                    expanded.add(row.key());
                }
            } else {
                run.accept(commands.get(row.key()));
            }
        });
        item.setName(row.key());
        item.padLeft(PADDING + row.depth() * INDENT);
        if (row.expanded()) {
            ((Image) item.icons.getLast()).setDrawable(ui.skin.getDrawable("icon-chevron-down"));
        }
        if (!row.tip().isEmpty()) {
            ui.tip(item).getActor().setText(row.tip());
        }
        return item;
    }
}
