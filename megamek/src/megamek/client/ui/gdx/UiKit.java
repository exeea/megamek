/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Pools;
import megamek.client.ui.Messages;

/**
 * The hud-v3 widgets that native views build from a UiTheme's skin (rebuild plan C.3), and the helpers they all use: a
 * message text, a change listener, a tooltip and a scrolling list. A widget shows what its view sets and reports a
 * click as a ChangeEvent; the kit only reads the skin, so it owns nothing to dispose.
 */
public final class UiKit {
    /** A dialog's greatest width, in stage units (.dlg: width 620). */
    public static final float DIALOG_WIDTH = 620;
    /** A dialog is at most this much less high than the window (.dlg: max-height calc(100vh - 140px)). */
    public static final float DIALOG_MARGIN = 140;
    /** The name of a header's count, for a view that keeps it current. */
    public static final String HEADER_COUNT = "header-count";
    /** Styles whose captions the prototype upper-cases (.b, .b.main, .b.mini, .b.brk.sm, .ltabs). */
    private static final Set<String> UPPER_CASE = Set.of("hud", "hud-main", "hud-mini", "hud-utility-small",
          "hud-tab-caps");
    /** CSS's normal line height of Roboto and Roboto Condensed, their ascent plus descent, in ems. */
    private static final float NORMAL_LINE = 1.172f;

    /** Status-line colors: plain (ink2), .ok, .w and .x. */
    public enum Tone {
        NORMAL(UiTheme.ACCENT), OK(UiTheme.MINT), WARN(UiTheme.AMBER), BAD(UiTheme.CORAL);

        /** The tone's text color, a theme token. */
        public final Color color;

        Tone(Color color) {
            this.color = color;
        }
    }

    /** The skin the widgets are built from: a UiTheme's, or one that a view's own skin composes with it. */
    public final Skin skin;
    private final Texture white;

    /** A kit over {@code skin}, which holds a UiTheme's styles; the kit only reads it. */
    public UiKit(Skin skin) {
        this.skin = skin;
        white = skin.get("white", Texture.class);
    }

    /** A message; a pattern only with arguments, so that plain texts keep their apostrophes. */
    public static String text(String key, Object... arguments) {
        return arguments.length == 0 ? Messages.getString(key) : Messages.getString(key, arguments);
    }

    /** Runs {@code action} on every ChangeEvent of {@code actor}, such as a click on a hud button. */
    public static void onChange(Actor actor, Runnable action) {
        actor.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor target) {
                action.run();
            }
        });
    }

    /**
     * A tooltip on {@code actor} in the theme's "hud" style, empty until its view sets the text. It goes when the actor
     * hides: libGDX hides a tooltip whose actor leaves the stage, but not one whose actor or panel hides under a resting
     * pointer, which then stays stuck. Its label acts only while it shows.
     */
    public TextTooltip tip(Actor actor) {
        TextTooltip tip = new TextTooltip("", skin, "hud");
        actor.addListener(tip);
        tip.getActor().addAction(Actions.forever(Actions.run(() -> {
            if (!actor.ascendantsVisible()) {
                tip.hide();
            }
        })));
        return tip;
    }

    /**
     * A list that scrolls vertically in the "hud-list" style: a thin bar over the list, no flick and no overscroll. It
     * takes the wheel while the pointer is over it ({@link #wheelWhileHovered}); a press takes it too (ScrollPane).
     */
    public ScrollPane scrollList(Actor list) {
        ScrollPane scroll = new ScrollPane(list, skin, "hud-list");
        scroll.setScrollingDisabled(true, false);
        scroll.setScrollbarsOnTop(true);
        scroll.setOverscroll(false, false);
        scroll.setFlickScroll(false);
        wheelWhileHovered(scroll);
        return scroll;
    }

    /**
     * Gives {@code actor} the stage's scroll focus while the pointer is over it, so the wheel reaches it without a
     * click, and gives the focus back when the pointer leaves it and its children, so the wheel then reaches what lies
     * there, such as the view's camera.
     */
    public static void wheelWhileHovered(Actor actor) {
        actor.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (actor.getStage() != null) {
                    actor.getStage().setScrollFocus(actor);
                }
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                Stage stage = actor.getStage();
                if (stage != null && stage.getScrollFocus() == actor
                      && (toActor == null || !toActor.isDescendantOf(actor))) {
                    stage.setScrollFocus(null);
                }
            }
        });
    }

    /** A label in a hud font at another size and color. */
    public Label label(String text, String font, float size, Color color) {
        Label label = new Label(text, new Label.LabelStyle(skin.getFont(font), color));
        size(label, font, size);
        return label;
    }

    /**
     * Draws a label in hud font {@code font} at {@code size} stage units. Label.setFontScale replaces the font's own
     * quarter scale (S1), so the font's scale is multiplied in.
     */
    public static void size(Label label, String font, float size) {
        float tableSize = UiTheme.HUD_FONTS.stream().filter(entry -> entry.name().equals(font)).findFirst()
              .orElseThrow().size();
        label.setFontScale(label.getStyle().font.getScaleX() * size / tableSize);
    }

    /** The prototype's caption (.cap): upper-case condensed muted text. */
    public Label caption(String text) {
        return new Label(UiTheme.upper(text), skin, "hud-caption");
    }

    /**
     * A theme icon (a Material symbol, or an image icon a view added) at a CSS size; inside a button it takes the
     * button's text color.
     */
    public Icon icon(String name, float size, Color color) {
        Icon icon = new Icon(skin.getDrawable("icon-" + name), size);
        icon.setColor(color);
        return icon;
    }

    /**
     * A panel (.panel): the frame, padded only by its 2-unit rails and transparent side borders, with its rows from the
     * top; headers, lists and footers bring their own padding. A panel that pads its content itself sets its padding.
     */
    public Table panel() {
        return panel(new Table());
    }

    /** The panel's look on {@code panel}, a table that lays out more than its cells. */
    public Table panel(Table panel) {
        panel.setBackground(skin.getDrawable("panel"));
        panel.pad(2);
        panel.top();
        return panel;
    }

    /**
     * A panel header (.phd): the upper-case title, an optional muted count (.n) beside it, and tools at the right:
     * buttons, or a count that a panel shows there.
     */
    public Table header(String title, String count, Actor... tools) {
        Table header = new Table();
        header.pad(11, 14, 8, 14);
        // .phd is at least 44 units tall; a title too long for the panel ends in an ellipsis (.phd .t).
        Label heading = new Label(UiTheme.upper(title), skin, "hud-title");
        heading.setEllipsis(true);
        header.add(heading).minHeight(25).minWidth(0);
        if (count != null) {
            Label counted = label(count, "hud-medium", 12, UiTheme.MUTED);
            counted.setName(HEADER_COUNT);
            header.add(counted).padLeft(8);
        }
        header.add().expandX();
        for (Actor tool : tools) {
            header.add(tool).padLeft(8);
        }
        return header;
    }

    /** A close button (.b.ib with the close icon), as a header's tool, which runs {@code action}. */
    public UiButton closeButton(Runnable action) {
        UiButton close = button("hud-icon", "close", null, null);
        onChange(close, action);
        return close;
    }

    /**
     * A dialog (.panel.dlg): a panel padded 0 18 16 18 inside its rails and side borders, and its header (.dlg .phd,
     * padded 12 0 8) with the title and a close button that runs {@code close}. The view adds its rows below the
     * header; its owner centres it, at most {@link #DIALOG_WIDTH} wide and {@link #DIALOG_MARGIN} less than the window
     * high, with a body that scrolls.
     */
    public Table dialog(String title, Runnable close) {
        Table dialog = panel();
        dialog.pad(2, 20, 18, 20);
        Table header = header(title, null, closeButton(close));
        header.pad(12, 0, 8, 0);
        dialog.add(header).growX().row();
        return dialog;
    }

    /**
     * Ends {@code panel} with its footer (.pfoot): the rule, then a foot row padded 8 14 9 14, which it returns for the
     * panel's line and actions.
     */
    public Table footer(Table panel) {
        panel.add(new Image(skin.getDrawable("rule"))).growX().height(1).row();
        Table foot = new Table();
        foot.pad(8, 14, 9, 14);
        panel.add(foot).growX();
        return foot;
    }

    /** A list's empty state (.empty): {@code text}, wrapped and muted, as the list's only row. */
    public Label empty(Table list, String text) {
        Label empty = label(text, "hud-body", 12.5f, UiTheme.MUTED);
        empty.setWrap(true);
        list.add(empty).growX().pad(16, 14, 16, 14);
        return empty;
    }

    /**
     * A button in a hud style with an optional icon, caption and sub-label (.b small); captions are upper-cased where
     * the prototype does it. The parts stack in a top-right utility and above a sub-label; otherwise the icon leads the
     * caption on one line. Styles: hud (quiet, .b), hud-plain (normal-case .brow .b), hud-main (commit), hud-mini,
     * hud-icon (30-unit .b.ib), hud-seg, hud-utility (icon over label), hud-utility-row (.b.util.row) and
     * hud-utility-small (.b.brk.sm).
     */
    public UiButton button(String style, String icon, String text, String sub) {
        UiButton button = new UiButton(this, style);
        boolean column = style.equals("hud-utility") || sub != null;
        float gap = gap(style, column);
        if (icon != null) {
            Icon image = icon(icon, iconSize(style), Color.WHITE);
            button.icons.add(image);
            button.add(image);
            if (column) {
                button.row();
            }
        }
        if (text != null) {
            button.setText(UPPER_CASE.contains(style) ? UiTheme.upper(text) : text);
            if (style.equals("hud-plain")) {
                // .brow .b is 12 units, half a unit smaller than hud-medium.
                size(button.getLabel(), "hud-medium", 12);
            }
            button.add(button.getLabel()).padLeft(icon != null && !column ? gap : 0)
                  .padTop(icon != null && column ? gap : 0);
        }
        if (sub != null) {
            Label small = label(sub, "hud-sub", 10, Color.WHITE);
            button.details.add(small);
            button.row();
            button.add(small).padTop(gap);
        }
        return button;
    }

    /** The gap between a button's parts: .b's 1 unit when they stack, a utility's 4, and the row gaps per style. */
    private static float gap(String style, boolean column) {
        if (column) {
            return style.equals("hud-utility") ? 4 : 1;
        }
        return switch (style) {
            case "hud-mini" -> 5;
            case "hud-plain" -> 7;
            default -> 6;
        };
    }

    private static float iconSize(String style) {
        return switch (style) {
            case "hud-icon" -> 16;
            case "hud-mini" -> 13;
            case "hud-utility", "hud-utility-row" -> 19;
            default -> 17;
        };
    }

    /**
     * Segment buttons (.seg, style hud-seg) or underline tabs (.tabs: hud-tab; the log's .ltabs: hud-tab-caps); flex
     * ones share the width, natural ones keep theirs (.ovbar .seg button).
     */
    public Segmented segmented(String style, boolean flex, String... labels) {
        return new Segmented(style, flex, labels);
    }

    /** A drop-down's face (.fsel; inline: .amsel): the current choice and a chevron. Its view opens the list. */
    public UiButton select(String text, boolean inline) {
        UiButton select = new UiButton(this, inline ? "hud-select-inline" : "hud-select");
        select.setText(text);
        size(select.getLabel(), inline ? "hud-small" : "hud-body", inline ? 11 : 12);
        select.getLabel().setAlignment(Align.left);
        select.getLabel().setEllipsis(true);
        select.add(select.getLabel()).growX().minWidth(0);
        Icon chevron = icon("chevron-down", 12, Color.WHITE);
        select.icons.add(chevron);
        select.add(chevron).padLeft(inline ? 2 : 6);
        return select;
    }

    /** A checkbox (.tog): the box, mint while ticked, and its label; a click on either flips it. */
    public Checkbox checkbox(String text, boolean ticked) {
        return new Checkbox(text, ticked);
    }

    /**
     * A menu or popover item (.pop .it): an optional check mark (null: the item is no toggle), the label, a detail such
     * as a shortcut, and a chevron when the item opens a group. The keyboard highlight is the pressed state.
     */
    public UiButton menuRow(String text, String detail, Boolean checked, boolean group) {
        UiButton row = new UiButton(this, "hud-menu-row");
        if (checked != null) {
            Icon mark = icon("check", 16, Color.WHITE);
            mark.setVisible(checked);
            row.icons.add(mark);
            row.add(mark).padRight(10);
        }
        row.setText(text);
        row.getLabel().setAlignment(Align.left);
        row.add(row.getLabel()).growX();
        if (detail != null && !detail.isEmpty()) {
            Label shortcut = label(detail, "hud-small", 11, Color.WHITE);
            row.details.add(shortcut);
            row.add(shortcut).padLeft(10);
        }
        if (group) {
            Icon chevron = icon("chevron-right", 12, Color.WHITE);
            row.icons.add(chevron);
            row.add(chevron).padLeft(10);
        }
        return row;
    }

    /** A chip (.chip, .chip.w, .chip.x). */
    public Label chip(String text, Tone tone) {
        Label chip = label(UiTheme.upper(text), "hud-button", 10.5f, tone.color);
        Label.LabelStyle style = chip.getStyle();
        style.background = skin.getDrawable(tone == Tone.WARN ? "chip-warn" : tone == Tone.BAD ? "chip-bad" : "chip");
        chip.setStyle(style);
        return chip;
    }

    /** A search field (.field) with its placeholder, and a clear button while it holds text. */
    public SearchField search(String placeholder) {
        return new SearchField(placeholder);
    }

    /** A labelled bar (.meter; compact: the overview card's .bars2). */
    public Meter meter(String caption, boolean compact) {
        return new Meter(caption, compact, new Bar(compact ? 3 : 4));
    }

    /** A meter's caption and value over a bar that its view draws, such as a segmented or hatched bar. */
    public Meter meter(String caption, Widget bar) {
        return new Meter(caption, false, bar);
    }

    /** A rectangle of the white texel in a color, at the widget's alpha. */
    public void fill(Batch batch, Color color, float alpha, float x, float y, float width, float height) {
        if (width > 0) {
            batch.setColor(color.r, color.g, color.b, color.a * alpha);
            batch.draw(white, x, y, width, height);
        }
    }

    /** An icon Image whose preferred size is its CSS size rather than its 48-texel cell. */
    public static final class Icon extends Image {
        private final float size;

        private Icon(Drawable drawable, float size) {
            super(drawable);
            this.size = size;
        }

        @Override
        public float getPrefWidth() {
            return size;
        }

        @Override
        public float getPrefHeight() {
            return size;
        }
    }

    /** One row of segment buttons or underline tabs; its view shows the selection with {@link #select}. */
    public final class Segmented extends Table {
        /** The segments or tabs, in the order of their labels. */
        public final List<UiButton> buttons = new ArrayList<>();

        private Segmented(String style, boolean flex, String... labels) {
            boolean tabs = style.startsWith("hud-tab");
            // A pill ("hud-pill"): one frame whose sections touch, a hairline between two of them.
            boolean pill = style.equals("hud-pill");
            if (tabs) {
                setBackground(skin.getDrawable("tabs"));
            } else if (pill) {
                setBackground(skin.getDrawable("pill-frame"));
                pad(1);
            }
            for (String text : labels) {
                if (pill && !buttons.isEmpty()) {
                    add(new Image(skin.getDrawable("rule"))).width(1).fillY();
                }
                UiButton button = button(style, null, text, null);
                Cell<UiButton> cell = add(button).padLeft(buttons.isEmpty() || pill ? 0 : tabs ? 18 : 6);
                if (flex) {
                    cell.growX().uniformX();
                } else if (!tabs) {
                    button.pad(6, 12, 6, 12);
                }
                buttons.add(button);
            }
        }

        /** Presses the segment at {@code index} and releases the others; -1 releases all. */
        public Segmented select(int index) {
            for (int i = 0; i < buttons.size(); i++) {
                buttons.get(i).pressed(i == index);
            }
            return this;
        }
    }

    /**
     * A checkbox (.tog): the box icon, mint while ticked, and its label. A click on the box or the label flips it and
     * fires a ChangeEvent; its view's own change ({@link #ticked}) fires none.
     */
    public final class Checkbox extends Table {
        private final Icon box = icon("checkbox-off", 18, UiTheme.MUTED);
        private boolean ticked;

        private Checkbox(String text, boolean initial) {
            add(box).padRight(8);
            add(label(text, "hud-body", 13, UiTheme.TEXT));
            setTouchable(Touchable.enabled);
            addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    toggle();
                }
            });
            ticked(initial);
        }

        /** Whether the box is ticked. */
        public boolean isTicked() {
            return ticked;
        }

        /** Ticks or clears the box without an event. */
        public Checkbox ticked(boolean value) {
            ticked = value;
            box.setDrawable(skin.getDrawable(value ? "icon-checkbox-on" : "icon-checkbox-off"));
            box.setColor(value ? UiTheme.MINT : UiTheme.MUTED);
            return this;
        }

        /** Flips the box as a click does, with a ChangeEvent. */
        public void toggle() {
            ticked(!ticked);
            ChangeListener.ChangeEvent event = Pools.obtain(ChangeListener.ChangeEvent.class);
            fire(event);
            Pools.free(event);
        }
    }

    /** The search field and its clear button, which empties the field with a ChangeEvent from the field. */
    public final class SearchField extends Stack {
        /** The text field; its ChangeEvents report every edit, the clear button's included. */
        public final TextField field;
        /** The clear button, shown while the field holds text. */
        public final UiButton clear;

        private SearchField(String placeholder) {
            field = new TextField("", skin, "hud-search");
            field.setMessageText(placeholder);
            field.setProgrammaticChangeEvents(true);
            clear = closeButton(() -> field.setText(""));
            Table overlay = new Table();
            overlay.right().add(clear).size(26).padRight(3);
            add(field);
            add(overlay);
        }

        @Override
        public void act(float delta) {
            clear.setVisible(!field.getText().isEmpty());
            super.act(delta);
        }
    }

    /** A caption and value over a bar; the overview card's compact form uses smaller type and a 3-unit bar. */
    public final class Meter extends Table {
        private final Label value;
        private final Cell<Label> valueCell;
        private final Widget bar;

        private Meter(String caption, boolean compact, Widget bar) {
            float size = compact ? 9.5f : 10.5f;
            value = label("", compact ? "hud-caption" : "hud-button", compact ? size : 13, Color.WHITE);
            this.bar = bar;
            Cell<Label> captionCell = add(label(UiTheme.upper(caption), "hud-caption", size, UiTheme.MUTED)).left();
            valueCell = add(value).right().expandX();
            if (compact) {
                // .bars2's line box, lower than the labels' cap height and descent: 18.1 units with the bar
                captionCell.height(size * NORMAL_LINE);
                valueCell.height(size * NORMAL_LINE);
            }
            row();
            add(bar).colspan(2).growX().padTop(4);
        }

        /** The value at the right of the caption, upper-cased as the caption (.meter .l). */
        public Meter value(String text) {
            valueCell.setActor(value);
            value.setText(UiTheme.upper(text));
            return this;
        }

        /**
         * A value of several parts that the view builds, such as a white value, an amber forecast and a muted note
         * (.meter .l b, .warn, .dim).
         */
        public Meter value(Actor parts) {
            valueCell.setActor(parts);
            return this;
        }

        /** The value and a plain bar's filled share; a bar of the view's own is set through that bar. */
        public Meter set(String text, float fraction, Color fill) {
            value(text);
            if (bar instanceof Bar plain) {
                plain.fraction = MathUtils.clamp(fraction, 0, 1);
                plain.fillColor = fill;
            }
            return this;
        }
    }

    /** A meter's bar: a faint track and a filled share. */
    private final class Bar extends Widget {
        private final float height;
        private float fraction;
        private Color fillColor = UiTheme.MINT;

        private Bar(float height) {
            this.height = height;
        }

        @Override
        public float getPrefHeight() {
            return height;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            fill(batch, UiTheme.TRACK, alpha, getX(), getY(), getWidth(), getHeight());
            fill(batch, fillColor, alpha, getX(), getY(), getWidth() * fraction, getHeight());
            batch.setPackedColor(previous);
        }
    }
}
