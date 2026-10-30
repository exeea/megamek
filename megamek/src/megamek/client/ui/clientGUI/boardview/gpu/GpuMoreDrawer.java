/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiTheme;
import megamek.logging.MMLogger;

/**
 * The dock's More as a panel that slides out of the dock's right side, in the dock's own look: its frame, an upper-case
 * title with a close button, and the usable commands as dock buttons in a grid. The HUD sizes the panel to the
 * columns and rows its commands need, so it does not scroll unless the window has no room for them.
 * Unavailable commands are left out. Choosing a command runs it and closes the panel, as the More popover did. The
 * HUD places it beside the dock, at least the dock's height and taller where its columns need more rows, or above
 * the dock where the window leaves no room for a column beside it; above the dock it slides up out of the dock's top.
 */
final class GpuMoreDrawer {
    private static final MMLogger LOGGER = MMLogger.create(GpuMoreDrawer.class);
    /** A grid button's width, the gap between buttons and their height (the dock's plain .b and GAP). */
    private static final float CELL = 150;
    private static final float GAP = 6;
    private static final float BUTTON = 36;
    /** The panel's padding inside its frame: 14 at each side, 11 above and 10 below (#dock padding 9 12 8). */
    private static final float PAD_X = 14;
    private static final float PAD_TOP = 11;
    private static final float PAD_BOTTOM = 10;
    /** The slide's length in seconds. */
    private static final float SLIDE = 0.18f;

    private final UiKit ui;
    private final Slide slide = new Slide();
    private final Table panel;
    private final Label title;
    private final Table head = new Table();
    private final UiMenuList grid;
    private final ScrollPane scroll;
    /** The commands the grid shows, by id, label and shortcut: a frame that changes none of them keeps the buttons. */
    private List<String> shownKeys = List.of();
    private List<BoardScene.Command> commands = List.of();
    private int columns;
    private boolean open;
    /** Whether the HUD placed the panel above the dock rather than beside it. */
    private boolean above;

    GpuMoreDrawer(UiKit ui) {
        this.ui = ui;
        grid = new UiMenuList(ui);
        grid.setName("dock-more-commands");
        slide.setName("dock-more-drawer");
        // The dock's frame and padding (#dock padding 9 12 8, inside the rails and side borders).
        panel = ui.panel();
        panel.pad(PAD_TOP, PAD_X, PAD_BOTTOM, PAD_X);
        title = ui.label("", "hud-title", 13, Color.WHITE);
        title.setEllipsis(true);
        UiButton close = ui.button("hud-mini", null, "X", null);
        close.setName("dock-more-close");
        onChange(close, this::close);
        head.pad(0, 2, 0, 2);
        head.add(title).left().minWidth(0);
        head.add().expandX();
        head.add(close).padLeft(8);
        panel.add(head).growX().minHeight(26).padBottom(GAP).row();
        grid.top().left();
        scroll = ui.scrollList(grid);
        scroll.setName("dock-more-scroll");
        panel.add(scroll).grow().minHeight(0);
        slide.addActor(panel);
        slide.setVisible(false);
    }

    Actor actor() {
        return slide;
    }

    boolean isOpen() {
        return open;
    }

    /** The columns the usable commands need at {@code height}: as many rows as it holds. One while it is closed. */
    int columnsAt(float height) {
        float rowsRoom = height - chrome();
        int rows = Math.max(1, (int) Math.floor((rowsRoom + GAP) / (BUTTON + GAP)));
        return open ? Math.max(1, (commands.size() + rows - 1) / rows) : 1;
    }

    /** The columns {@code width} holds, zero when it is too narrow for one. */
    static int columnsIn(float width) {
        return Math.max(0, (int) Math.floor((width - 2 * PAD_X + GAP + 0.5f) / (CELL + GAP)));
    }

    /** The panel's width with {@code columns} columns. */
    static float width(int columns) {
        return columns * CELL + (columns - 1) * GAP + 2 * PAD_X;
    }

    /** The panel's height with the usable commands in {@code columns} columns. */
    float height(int columns) {
        int rows = Math.max(1, (commands.size() + columns - 1) / columns);
        return chrome() + rows * (BUTTON + GAP) - GAP;
    }

    /** The height of the panel's padding, head and the gap below the head. */
    private float chrome() {
        return PAD_TOP + PAD_BOTTOM + head.getPrefHeight() + GAP;
    }

    /** Set by the HUD's layout: the panel lies above the dock and slides up, rather than beside it sliding right. */
    void above(boolean value) {
        if (above != value) {
            above = value;
            slide.invalidate();
        }
    }

    /** Opens the panel with {@code more}, or closes it while it is open. */
    void toggle(GpuCommandDock.More more) {
        if (open) {
            close();
            return;
        }
        open = true;
        slide.reveal = 0;
        slide.setVisible(true);
        show(more);
        grid.highlight(null);
        scroll.setScrollY(0);
        if (slide.getStage() != null) {
            slide.getStage().setKeyboardFocus(grid);
        }
        LOGGER.info("More panel opened {} the dock: {}x{} units, {} usable commands in {} columns",
              above ? "above" : "beside", Math.round(slide.getWidth()), Math.round(slide.getHeight()),
              commands.size(), columns);
    }

    /** Closes the panel; true when it was open. */
    boolean close() {
        if (!open) {
            return false;
        }
        open = false;
        Stage stage = slide.getStage();
        if (stage != null) {
            if (stage.getKeyboardFocus() != null && stage.getKeyboardFocus().isDescendantOf(slide)) {
                stage.setKeyboardFocus(null);
            }
            if (stage.getScrollFocus() != null && stage.getScrollFocus().isDescendantOf(slide)) {
                stage.setScrollFocus(null);
            }
        }
        return true;
    }

    /** One frame's More while the panel is open: null closes it, changed commands rebuild the grid. */
    void update(GpuCommandDock.More more) {
        if (!open) {
            return;
        }
        if (more == null) {
            close();
        } else {
            show(more);
        }
    }

    private void show(GpuCommandDock.More more) {
        title.setText(UiTheme.upper(more.title()));
        List<BoardScene.Command> usable = new ArrayList<>();
        for (List<BoardScene.Command> group : List.of(more.items(), more.commands())) {
            for (BoardScene.Command command : group) {
                if (command.enabled() && command.children().isEmpty()) {
                    usable.add(command);
                }
            }
        }
        List<String> keys = new ArrayList<>();
        for (BoardScene.Command command : usable) {
            keys.add(command.id() + '\n' + command.label() + '\n' + command.shortcut());
        }
        commands = usable;
        if (!keys.equals(shownKeys)) {
            shownKeys = keys;
            rebuild();
        }
    }

    /** The grid's buttons in as many columns as the panel's width holds. */
    private void rebuild() {
        String highlighted = grid.highlighted() == null ? null : grid.highlighted().getName();
        grid.clearChildren();
        columns = columns(slide.getWidth());
        if (commands.isEmpty()) {
            Label empty = ui.label(text("GpuBoard.hud.dock.moreNone"), "hud-small", 11.5f, Color.WHITE);
            empty.setName("dock-more-empty");
            empty.setWrap(true);
            grid.add(empty).growX().minWidth(0).left();
            return;
        }
        for (int index = 0; index < commands.size(); index++) {
            BoardScene.Command command = commands.get(index);
            String id = command.id();
            UiButton button = ui.button("hud", null, command.label(), null);
            button.setName("dock-more-" + command.id());
            button.getLabel().setEllipsis(true);
            button.getCell(button.getLabel()).minWidth(0);
            if (command.shortcut() != null && !command.shortcut().isEmpty()) {
                ui.tip(button).getActor().setText(command.shortcut());
            }
            onChange(button, () -> choose(id));
            boolean first = index % columns == 0;
            if (first && index > 0) {
                grid.row();
            }
            grid.item(button).growX().uniformX().minWidth(0).height(BUTTON).padLeft(first ? 0 : GAP)
                  .padTop(index < columns ? 0 : GAP);
        }
        // A short last row keeps its buttons at the grid's column width.
        for (int index = commands.size() % columns; index > 0 && index < columns; index++) {
            grid.add().growX().uniformX().padLeft(GAP);
        }
        if (highlighted != null) {
            grid.highlight(grid.findActor(highlighted));
        }
    }

    /** The grid's columns at {@code width}, at least one; half a unit spare absorbs the layout's rounding. */
    private static int columns(float width) {
        return Math.max(1, columnsIn(width));
    }

    /** Buttons may outlive a unit or turn; resolve their action from the latest snapshot when invoked. */
    private void choose(String id) {
        if (!open) {
            return;
        }
        BoardScene.Command command = commands.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
        if (command != null && command.enabled()) {
            close();
            command.action().run();
        }
    }

    /**
     * The panel's clip: the panel slides in from its left edge beside the dock, or from its bottom edge above it, and
     * out again before the panel hides. A new width with another column count rebuilds the grid.
     */
    private final class Slide extends Table {
        private float reveal;

        Slide() {
            setClip(true);
            // A Table clips only while it draws with its own transform.
            setTransform(true);
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            float target = open ? 1 : 0;
            if (reveal != target) {
                float step = delta / SLIDE;
                reveal = open ? Math.min(1, reveal + step) : Math.max(0, reveal - step);
                invalidate();
            }
            if (!open && reveal == 0) {
                setVisible(false);
            }
        }

        @Override
        public void layout() {
            super.layout();
            if (open && columns(getWidth()) != columns) {
                rebuild();
            }
            float hidden = 1 - Interpolation.pow2Out.apply(reveal);
            if (above) {
                panel.setBounds(0, -hidden * getHeight(), getWidth(), getHeight());
            } else {
                panel.setBounds(-hidden * getWidth(), 0, getWidth(), getHeight());
            }
            if (open && panel.needsLayout()) {
                // Reflow changes the viewport and scroll limits. Restore the selection only after they settle.
                panel.validate();
                grid.highlight(grid.highlighted());
                scroll.updateVisualScroll();
            }
        }

    }
}
