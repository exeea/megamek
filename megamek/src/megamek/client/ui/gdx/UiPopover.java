/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;

/**
 * A popover (.panel.pop with its .hd): the opaque frame, at least 240 units wide, with an optional header of an
 * upper-case title over a muted subtitle, and its content. It opens at a point or above an anchor and stays 10
 * units inside its parent; a content taller than that scrolls. While open it gives its content the
 * stage's keyboard focus, and any press outside it closes it, as {@link #cancel} does. Its view adds it to a layer
 * once; it starts closed. As in a desktop menu, it can open another popover as a submenu beside one of its items
 * ({@link #cascade}): the chain stays open while presses land in it.
 */
public final class UiPopover extends Table {
    private static final float MIN_WIDTH = 240;
    /** The prototype keeps a popover this far inside the window. */
    private static final float MARGIN = 10;
    /** A popover opened above its anchor ends this far above the anchor's top. */
    private static final float ABOVE = 8;
    /** A submenu's frame lies over its menu's 2-unit side border, as a desktop submenu touches its menu. */
    private static final float OVERLAP = 2;
    /** A submenu without a header opens this far above its item's top, so its first row is level with the item. */
    private static final float FIRST_ROW = 8;
    /** The title's line: 13 units at the face's normal line height, Roboto's 2400/2048 em (.pop .hd b). */
    private static final float TITLE_LINE = 13 * 2400 / 2048f;
    /** The subtitle's line: the 13-unit body text at its 1.35 line height (.pop .hd span sits on that line). */
    private static final float BODY_LINE = 13 * 1.35f;
    private final Table head = new Table();
    private final Label title;
    private final Label subtitle;
    private final Image headRule;
    private final ScrollPane scroll;
    private final Cell<Table> headCell;
    private final Cell<Image> headRuleCell;
    /** Closes the popover on a press outside it and its submenus; on the stage's root while the popover is open. */
    private final InputListener outside = new InputListener() {
        @Override
        public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
            if (!holds(event.getTarget())) {
                cancel();
            }
            return false;
        }
    };
    /** The submenu last opened beside one of its items; open or closed. */
    private UiPopover submenu;
    /** The item whose submenu shows, pressed meanwhile, as a desktop menu keeps it lit. */
    private UiButton opener;
    /** The popover this one last opened beside as a submenu. */
    private UiPopover owner;
    /** Where it opened in its parent: its left edge, and its top, or its bottom when it opened above an anchor. */
    private float left;
    private float edge;
    private boolean above;

    /** A closed popover in the kit's style, without header or content. */
    public UiPopover(UiKit kit) {
        setBackground(kit.skin.getDrawable("panel-pop"));
        // The 2-unit rails and the popover's own 6 above and below; the side borders are 2 transparent units.
        pad(8, 2, 8, 2);
        top();
        // Fractional line heights add up as in the prototype's CSS; the frame snaps its own edges to pixels.
        setRound(false);
        head.setRound(false);
        setTouchable(Touchable.enabled);
        setVisible(false);
        // .pop .hd: the title (700 13 condensed, .08em) over the subtitle, padding 6 14 8, a rule and 4 below it.
        title = kit.label("", "hud-title", 13, UiTheme.TEXT);
        subtitle = kit.label("", "hud-small", 11.5f, UiTheme.MUTED);
        head.pad(6, 14, 8, 14);
        head.left().defaults().left();
        head.add(title).height(TITLE_LINE).row();
        head.add(subtitle);
        headRule = new Image(kit.skin.getDrawable("rule"));
        scroll = kit.scrollList(null);
        // The scroll pane holding the content stays in its cell, so the content keeps the keyboard focus.
        headCell = add(head).growX();
        row();
        headRuleCell = add(headRule).growX();
        row();
        add(scroll).growX();
        header(null, null);
    }

    /**
     * The header's title, upper-cased, over the subtitle; a null title removes the header and a null subtitle its
     * line. An open popover keeps the corner it opened at.
     */
    public UiPopover header(String text, String detail) {
        boolean shown = text != null;
        title.setText(shown ? UiTheme.upper(text) : "");
        subtitle.setText(detail == null ? "" : detail);
        head.getCell(subtitle).height(detail == null ? 0 : BODY_LINE);
        head.invalidate();
        headCell.setActor(shown ? head : null);
        headRuleCell.setActor(shown ? headRule : null).height(shown ? 1 : 0).padBottom(shown ? 4 : 0);
        return relayout();
    }

    /** The popover's content, such as a {@link UiMenuList}; it gets the keyboard focus while the popover is open. */
    public UiPopover content(Actor actor) {
        scroll.setActor(actor);
        scroll.setScrollY(0);
        if (isVisible() && getStage() != null) {
            getStage().setKeyboardFocus(actor);
        }
        return relayout();
    }

    private UiPopover relayout() {
        invalidate();
        if (isVisible()) {
            place();
        }
        return this;
    }

    @Override
    public float getPrefWidth() {
        return Math.max(MIN_WIDTH, super.getPrefWidth());
    }

    /**
     * Opens the popover with its top-left corner at stage point ({@code x}, {@code y}), such as the pointer, moved as
     * little as needed to stay 10 units inside its parent.
     */
    public void showAt(float x, float y) {
        Vector2 corner = getParent().stageToLocalCoordinates(new Vector2(x, y));
        show(corner.x, corner.y, false);
    }

    /**
     * Opens the popover above {@code anchor}: its bottom 8 units above the anchor's top and its left edge {@code dx}
     * units right of the anchor's (the prototype's More opens 150 to the left), kept 10 units inside its parent.
     */
    public void showAbove(Actor anchor, float dx) {
        Vector2 corner = anchor.localToActorCoordinates(getParent(), new Vector2(0, anchor.getHeight()));
        show(corner.x + dx, corner.y + ABOVE, true);
    }

    /**
     * Opens {@code menu} as this open popover's submenu beside {@code item}, one of its rows, as a desktop menu does:
     * right of this popover, or left of it where the parent has no room, its first row level with the item. This
     * popover stays open, and a submenu it opened before closes first, with its own.
     */
    public void cascade(UiPopover menu, Actor item) {
        if (submenu != null) {
            submenu.cancel();
        }
        submenu = menu;
        menu.owner = this;
        opener = item instanceof UiButton button ? button.pressed(true) : null;
        Vector2 top = item.localToActorCoordinates(menu.getParent(), new Vector2(0, item.getHeight()));
        float width = menu.getPrefWidth();
        float x = getX() + getWidth() - OVERLAP;
        if (x + width > menu.getParent().getWidth() - MARGIN) {
            x = getX() - width + OVERLAP;
        }
        menu.show(x, top.y + FIRST_ROW, false);
    }

    /** Whether {@code actor} lies in this popover or in its open submenus. */
    private boolean holds(Actor actor) {
        return actor.isDescendantOf(this) || submenu != null && submenu.isVisible() && submenu.holds(actor);
    }

    private void show(float x, float y, boolean fromBelow) {
        left = x;
        edge = y;
        above = fromBelow;
        setVisible(true);
        toFront();
        place();
        Stage stage = getStage();
        stage.removeCaptureListener(outside);
        stage.addCaptureListener(outside);
        stage.setKeyboardFocus(scroll.getActor());
    }

    /**
     * Sizes the popover to its content, at most the parent's height less the margins, and moves it inside them. As in
     * the prototype, one wider than the parent keeps its left edge in.
     */
    private void place() {
        Group parent = getParent();
        setSize(getPrefWidth(), Math.min(getPrefHeight(), Math.max(0, parent.getHeight() - 2 * MARGIN)));
        validate();
        float bottom = above ? edge : edge - getHeight();
        setPosition(Math.max(MARGIN, Math.min(parent.getWidth() - getWidth() - MARGIN, left)),
              MathUtils.clamp(bottom, MARGIN, Math.max(MARGIN, parent.getHeight() - getHeight() - MARGIN)));
    }

    /**
     * One Esc step: closes the deepest open submenu, whose menu takes the keyboard back, or this popover without one;
     * true when one closed.
     */
    public boolean back() {
        if (submenu == null || !submenu.isVisible()) {
            return cancel();
        }
        submenu.back();
        if (!submenu.isVisible() && getStage() != null) {
            getStage().setKeyboardFocus(scroll.getActor());
        }
        return true;
    }

    /**
     * Closes the popover and its submenus; true when it was open. The stage's keyboard and scroll focus leave it, and
     * an outside press no longer reaches it.
     */
    public boolean cancel() {
        if (submenu != null) {
            submenu.cancel();
        }
        if (!isVisible()) {
            return false;
        }
        if (owner != null && owner.submenu == this && owner.opener != null) {
            owner.opener.pressed(false);
            owner.opener = null;
        }
        setVisible(false);
        Stage stage = getStage();
        if (stage != null) {
            stage.removeCaptureListener(outside);
            if (stage.getKeyboardFocus() != null && stage.getKeyboardFocus().isDescendantOf(this)) {
                stage.setKeyboardFocus(null);
            }
            if (stage.getScrollFocus() != null && stage.getScrollFocus().isDescendantOf(this)) {
                stage.setScrollFocus(null);
            }
        }
        return true;
    }
}
