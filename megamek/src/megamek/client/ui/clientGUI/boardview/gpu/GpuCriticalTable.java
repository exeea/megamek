/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;
import static megamek.client.ui.gdx.UiTheme.alpha;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.clientGUI.boardview.gpu.GpuRecordSheet.Density;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * The Mek critical table (unit panel design 5.6; user corrections 14c, 15 and 17): the record sheet's slots of every
 * location in blocks of six under a bold heading with its CASE tag, each slot struck, dimmed and railed as the record
 * says, with ammunition dots and a count badge, and the hit box of the engine, gyro, sensors and life support. Its slot
 * line and hit box are the styles the SYSTEMS tab of every other unit shares. It shows a record only; a click on a slot
 * goes to the sheet, which opens the slot's popover.
 */
final class GpuCriticalTable {
    /** A slot line (.cs: 15 units), a block's slots and the gaps between blocks and between locations. */
    static final float LINE = 15;
    private static final int BLOCK = 6;
    private static final float BLOCK_GAP = 6;
    private static final float LOCATION_GAP = 14;
    /** The rail's room at the left of a line (.cs padding-left 7) and the gap between a line's parts. */
    private static final float RAIL_ROOM = 7;
    private static final float PART_GAP = 5;
    /** Ammunition dots (.pips i): 5 units, 2 apart; a bin of up to five shots has one per shot. */
    private static final float DOT = 5;
    private static final float DOT_GAP = 2;
    private static final int MAX_DOTS = 5;
    // Strikes (G2): coral text under a coral line for destroyed or hit, muted text under a grey line for unavailable.
    private static final Color RED_LINE = alpha(UiTheme.CORAL, .9f);
    private static final Color GREY_LINE = alpha(UiTheme.ACCENT, .75f);
    private static final Color RAIL = alpha(UiTheme.ACCENT, .55f);
    /** The hit box's circles (.hc i): 1.5-unit rings at 55 % ink2, filled coral per hit. */
    private static final Color RING = alpha(UiTheme.ACCENT, .55f);
    /** An empty ammunition dot's 1-unit ring (.pips i). */
    private static final Color EMPTY_DOT = alpha(UiTheme.ACCENT, .7f);
    /** The Mek locations of each column, left side, centre and right side; the first one a Mek has is drawn. */
    private static final List<List<List<String>>> COLUMNS = List.of(
          List.of(List.of("LA", "FLL"), List.of("LT"), List.of("LL", "RLL")),
          List.of(List.of("HD"), List.of("CT")),
          List.of(List.of("RA", "FRL"), List.of("RT"), List.of("RL", "RRL")));
    /** A tripod's centre leg, under the hit box. */
    private static final String CENTER_LEG = "CL";

    /** How a slot or item line is struck: red when destroyed or hit, grey when unavailable, or not at all. */
    enum Strike { NONE, RED, GREY }

    /** A slot of the table: its location's abbreviation and its index there. */
    record SlotKey(String location, int index) { }

    /**
     * One row of a hit box: its label, its hits of its capacity as circles (filled coral per hit), or a word in its
     * colour in place of the circles (a vehicle's motive damage tier), and its tooltip ("" for none).
     */
    record HitRow(String label, int hits, int capacity, String word, Color wordColor, String tip) { }

    private final UiKit ui;
    private final Texture white;
    private final Drawable selectedSlot;

    GpuCriticalTable(UiKit ui) {
        this.ui = ui;
        white = ui.skin.get("white", Texture.class);
        // .cs.pk: a faint mint fill with a 2-unit mint edge at the left.
        selectedSlot = new UiTheme.EdgeBox(white, alpha(UiTheme.MINT, .09f), UiTheme.MINT, 0, 0, 0, 2);
    }

    /** The strike of a critical slot: red for a hit or destroyed slot, else grey for a blown-off or breached one. */
    static Strike strike(GpuUnitRecord.Slot slot) {
        if (slot.hit() || slot.destroyed()) {
            return Strike.RED;
        }
        return slot.missing() || slot.breached() ? Strike.GREY : Strike.NONE;
    }

    /**
     * The ammunition dots of a bin (U-16, R3-13) as {dots, filled}: one per shot for a bin of up to five shots, else
     * five filled to the nearest fifth, never all five unless the bin is full and never none while a shot is left.
     */
    static int[] dots(int shots, int fullShots) {
        if (fullShots <= MAX_DOTS) {
            return new int[] { Math.max(0, fullShots), Math.clamp(shots, 0, Math.max(0, fullShots)) };
        }
        int filled = shots >= fullShots ? MAX_DOTS : shots <= 0 ? 0
              : Math.clamp(Math.round(shots * (float) MAX_DOTS / fullShots), 1, MAX_DOTS - 1);
        return new int[] { MAX_DOTS, filled };
    }

    /**
     * The Mek's critical table at the sheet's content {@code width}: three columns (left side; head, centre torso, the
     * hit box and a tripod's centre leg; right side), or at narrow density two (head and hit box beside the centre
     * torso, then the left side beside the right side). {@code selected} is the slot the keyboard selects (null for
     * none); {@code click} receives a clicked slot and its line.
     */
    Table table(GpuUnitRecord.Snapshot record, Density density, float width, SlotKey selected,
          BiConsumer<SlotKey, Actor> click) {
        Map<String, GpuUnitRecord.Location> locations = new HashMap<>();
        record.locations().forEach(location -> locations.put(location.abbr(), location));
        Table table = new Table();
        table.top().left();
        if (density == Density.NARROW) {
            float column = (width - LOCATION_GAP) / 2;
            Table head = column(column);
            add(head, locations.get("HD"), density, column, selected, click);
            head.add(hitBox(hitRows(record), density)).left().padTop(LOCATION_GAP).row();
            Table torso = column(column);
            add(torso, locations.get("CT"), density, column, selected, click);
            add(torso, locations.get(CENTER_LEG), density, column, selected, click);
            table.add(head).width(column).top().padRight(LOCATION_GAP);
            table.add(torso).width(column).top().row();
            for (int side = 0; side < 3; side += 2) {
                Table sideColumn = column(column);
                for (List<String> slot : COLUMNS.get(side)) {
                    add(sideColumn, first(locations, slot), density, column, selected, click);
                }
                table.add(sideColumn).width(column).top().padTop(LOCATION_GAP).padRight(side == 0 ? LOCATION_GAP : 0);
            }
            return table;
        }
        float gap = density == Density.WIDE ? 20 : LOCATION_GAP;
        float column = (width - 2 * gap) / 3;
        for (int index = 0; index < COLUMNS.size(); index++) {
            Table cells = column(column);
            for (List<String> slot : COLUMNS.get(index)) {
                add(cells, first(locations, slot), density, column, selected, click);
            }
            if (index == 1) {
                cells.add(hitBox(hitRows(record), density)).left().padTop(cells.hasChildren() ? LOCATION_GAP : 0)
                      .row();
                add(cells, locations.get(CENTER_LEG), density, column, selected, click);
            }
            table.add(cells).width(column).top().padRight(index < 2 ? gap : 0);
        }
        return table;
    }

    /** The name of a location's block, by which the sheet links it (graft 6). */
    static String blockName(String location) {
        return "crit-block-" + location;
    }

    /** The location whose block a doll's location code names: a shield's ("DC" + arm) is its arm's. */
    static String blockLocation(String code) {
        return code.startsWith("DC") ? code.substring(2) : code;
    }

    private static Table column(float width) {
        Table column = new Table();
        column.top().left();
        column.defaults().left().width(width);
        return column;
    }

    private static GpuUnitRecord.Location first(Map<String, GpuUnitRecord.Location> locations, List<String> codes) {
        return codes.stream().map(locations::get).filter(location -> location != null).findFirst().orElse(null);
    }

    /** A location's block in a column, 14 units below the block before it; nothing for a location the Mek lacks. */
    private void add(Table column, GpuUnitRecord.Location location, Density density, float width, SlotKey selected,
          BiConsumer<SlotKey, Actor> click) {
        if (location == null) {
            return;
        }
        Table block = location(location, density, width, selected, click);
        column.add(block).padTop(column.hasChildren() ? LOCATION_GAP : 0).row();
    }

    /**
     * A location's block: its heading in ink (a destroyed location's too: its red-struck slots say it), a CASE tag
     * left of it, then its slots in blocks of six; no dice numbers.
     */
    private Table location(GpuUnitRecord.Location location, Density density, float width, SlotKey selected,
          BiConsumer<SlotKey, Actor> click) {
        boolean medium = density == Density.MEDIUM;
        Table block = new Table();
        block.top().left();
        block.setName(blockName(location.abbr()));
        Table heading = new Table();
        heading.left();
        if (!location.caseTag().isEmpty()) {
            heading.add(caseTag(location.caseTag())).padRight(6);
        }
        Label name = ui.label(UiTheme.upper(location.name()), "hud-name", medium ? 12 : 12.5f, UiTheme.TEXT);
        name.setEllipsis(true);
        heading.add(name).minWidth(0).growX();
        block.add(heading).growX().height(18).padBottom(4).row();
        List<GpuUnitRecord.Slot> slots = location.slots();
        for (int index = 0; index < slots.size(); index++) {
            GpuUnitRecord.Slot slot = slots.get(index);
            int start = index - index % BLOCK;
            int end = Math.min(slots.size(), start + BLOCK);
            // A rail joins the slots of one item; the filler that takes no critical hit (Endo Steel ...) has none.
            boolean railed = slot.eqNum() >= 0 && !slot.filler()
                  && (same(slots, index - 1, slot) || same(slots, index + 1, slot));
            Line line = line(slot.text(), strike(slot), slot.empty() || slot.filler(), slot.armored(),
                  slot.armorHit(), slot.shots(), slot.fullShots(), slot.hotLoaded(), slot.dumping(), density, width);
            line.rail(railed, railed && (index == start || !same(slots, index - 1, slot)),
                  railed && (index == end - 1 || !same(slots, index + 1, slot)));
            SlotKey key = new SlotKey(location.abbr(), slot.index());
            if (key.equals(selected)) {
                line.setBackground(selectedSlot);
            }
            line.setTouchable(Touchable.enabled);
            line.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    click.accept(key, line);
                }
            });
            line.setName("crit-" + location.abbr() + "-" + slot.index());
            block.add(line).growX().height(LINE).padTop(index > 0 && index % BLOCK == 0 ? BLOCK_GAP : 0).row();
        }
        return block;
    }

    /** Whether the slot at {@code index} belongs to the same mount as {@code slot}. */
    private static boolean same(List<GpuUnitRecord.Slot> slots, int index, GpuUnitRecord.Slot slot) {
        return index >= 0 && index < slots.size() && slots.get(index).eqNum() == slot.eqNum();
    }

    /** A location tag (.case): "CASE" or "CASE II" in mint. */
    private Label caseTag(String tag) {
        Label label = ui.label(tag, "hud-name", 9, UiTheme.MINT);
        Label.LabelStyle style = label.getStyle();
        style.background = ui.skin.getDrawable("unit-case");
        label.setStyle(style);
        return label;
    }

    /**
     * A slot or item line (.cs) for a column of {@code width}: the name in its strike or dimmed, an armored
     * component's ring (filled once its armor was hit), and for ammunition ({@code shots} >= 0) the dots and the count
     * badge (dim when empty, amber while dumping, with an amber "HL" when hot-loaded). A name that does not fit takes
     * the condensed face, then the dots go, then it ends in an ellipsis with the whole name as its tooltip (3.4).
     */
    Line line(String name, Strike strike, boolean dim, boolean armored, boolean armorHit, int shots, int fullShots,
          boolean hotLoaded, boolean dumping, Density density, float width) {
        float size = density == Density.MEDIUM ? 11.5f : 12;
        Color color = switch (strike) {
            case RED -> UiTheme.CORAL;
            case GREY -> dim ? UiTheme.DISABLED : UiTheme.MUTED;
            case NONE -> dim ? UiTheme.DISABLED : UiTheme.ACCENT;
        };
        Line line = new Line();
        float room = width - RAIL_ROOM;
        Image ring = null;
        if (armored) {
            ring = tinted(armorHit ? "unit-disc-5" : "unit-ring-5", UiTheme.ACCENT);
            room -= DOT + 3;
        }
        Label badge = null;
        Table hot = null;
        int[] dots = null;
        if (shots >= 0) {
            badge = badge(Integer.toString(shots), shots <= 0 ? UiTheme.DISABLED : dumping ? UiTheme.AMBER
                  : UiTheme.TEXT);
            room -= badge.getPrefWidth() + PART_GAP;
            if (hotLoaded) {
                hot = new Table();
                hot.add(ui.label(text("GpuBoard.hud.unit.hotLoadedTag"), "hud-name", 9, UiTheme.AMBER));
                room -= hot.getPrefWidth() + PART_GAP;
            }
            // Narrow density shows the badge only.
            dots = density == Density.NARROW ? null : dots(shots, fullShots);
        }
        float dotsWidth = dots == null ? 0 : dots[0] * DOT + Math.max(0, dots[0] - 1) * DOT_GAP + PART_GAP;
        StruckLabel label = new StruckLabel(name, ui, "hud-body", size, color, strike);
        if (label.getPrefWidth() > room - dotsWidth) {
            label.face(ui, "hud-sub", size);
            if (label.getPrefWidth() > room - dotsWidth && dots != null) {
                dots = null;
                dotsWidth = 0;
            }
            if (label.getPrefWidth() > room - dotsWidth) {
                label.setEllipsis(true);
                ui.tip(label).getActor().setText(name);
            }
        }
        line.left().padLeft(RAIL_ROOM);
        if (ring != null) {
            line.add(ring).size(DOT).padRight(3);
        }
        line.add(label).minWidth(0).growX().left();
        if (dots != null) {
            Table pips = new Table();
            for (int dot = 0; dot < dots[0]; dot++) {
                pips.add(tinted(dot < dots[1] ? "unit-disc-5" : "unit-ring-5",
                      dot < dots[1] ? UiTheme.ACCENT : EMPTY_DOT)).size(DOT).padLeft(dot > 0 ? DOT_GAP : 0);
            }
            line.add(pips).padLeft(PART_GAP);
        }
        if (hot != null) {
            line.add(hot).padLeft(PART_GAP);
        }
        if (badge != null) {
            line.add(badge).padLeft(PART_GAP).height(13);
        }
        return line;
    }

    /** A count badge (.badge): the number in its colour within a quiet rounded border. */
    private Label badge(String count, Color color) {
        Label badge = ui.label(count, "hud-name", 11, color);
        Label.LabelStyle style = badge.getStyle();
        style.background = ui.skin.getDrawable("unit-badge");
        badge.setStyle(style);
        badge.setAlignment(Align.center);
        return badge;
    }

    private Image tinted(String drawable, Color color) {
        return new Image(ui.skin.newDrawable(drawable, color));
    }

    /** The Mek's hit box rows: one per system it has, labelled by its track (R3-8). */
    private static List<HitRow> hitRows(GpuUnitRecord.Snapshot record) {
        List<HitRow> rows = new ArrayList<>();
        for (GpuUnitRecord.HitTrack track : record.hitTracks()) {
            rows.add(new HitRow(text("GpuBoard.hud.unit.track." + track.track().name()), track.hits(),
                  track.capacity(), "", null, ""));
        }
        return rows;
    }

    /**
     * The hit box (.hitbox): a rounded frame of rows, each an upper-case muted label right-aligned before its circles
     * (10 units, 5 apart, filled coral per hit) or its word.
     */
    Table hitBox(List<HitRow> rows, Density density) {
        Table box = new Table();
        box.setBackground(ui.skin.getDrawable("unit-hitbox"));
        boolean narrow = density == Density.NARROW;
        if (narrow) {
            box.pad(8, 9, 8, 9);
        }
        for (int index = 0; index < rows.size(); index++) {
            HitRow row = rows.get(index);
            float top = index > 0 ? 7 : 0;
            Label label = ui.label(UiTheme.upper(row.label()), "hud-caption", 10.5f, UiTheme.MUTED);
            box.add(label).right().padTop(top).padRight(narrow ? 8 : 12);
            Table marks = new Table();
            marks.left();
            if (!row.word().isEmpty()) {
                marks.add(ui.label(row.word(), "hud-body", 12, row.wordColor()));
            } else {
                for (int circle = 0; circle < row.capacity(); circle++) {
                    boolean hit = circle < row.hits();
                    marks.add(tinted(hit ? "unit-disc-10" : "unit-ring-10", hit ? UiTheme.CORAL : RING)).size(10)
                          .padLeft(circle > 0 ? (narrow ? 4 : PART_GAP) : 0);
                }
            }
            box.add(marks).left().growX().padTop(top).row();
            if (!row.tip().isEmpty()) {
                ui.tip(label).getActor().setText(row.tip());
                ui.tip(marks).getActor().setText(row.tip());
            }
        }
        return box;
    }

    /**
     * A label struck through (G2): in red or grey over the text it draws, or plain. The line runs through the middle
     * of the lower-case letters.
     */
    static final class StruckLabel extends Label {
        private final Strike strike;
        private final Texture white;

        StruckLabel(String text, UiKit ui, String font, float size, Color color, Strike strike) {
            super(text, new LabelStyle(ui.skin.getFont(font), color));
            this.strike = strike;
            white = ui.skin.get("white", Texture.class);
            UiKit.size(this, font, size);
        }

        /** How the label is struck. */
        Strike strike() {
            return strike;
        }

        /** Draws the text in another hud font at {@code size} units. */
        void face(UiKit ui, String font, float size) {
            LabelStyle style = new LabelStyle(getStyle());
            style.font = ui.skin.getFont(font);
            setStyle(style);
            UiKit.size(this, font, size);
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            super.draw(batch, parentAlpha);
            if (strike == Strike.NONE) {
                return;
            }
            Color line = strike == Strike.RED ? RED_LINE : GREY_LINE;
            float previous = batch.getPackedColor();
            batch.setColor(line.r, line.g, line.b, line.a * getColor().a * parentAlpha);
            float width = Math.min(getGlyphLayout().width, getWidth());
            float y = UiTheme.snap(getY() + getHeight() / 2 - 1, UiTheme.pixelScale(batch));
            batch.draw(white, getX(), y, width, 1);
            batch.setPackedColor(previous);
        }
    }

    /**
     * A slot or item line with its rail (.cs.rl): a 1-unit line at the left of the slots one mount fills, with a short
     * cap at the first and the last of its slots in a block of six (.rs, .re).
     */
    final class Line extends Table {
        private boolean rail;
        private boolean railStart;
        private boolean railEnd;

        void rail(boolean shown, boolean start, boolean end) {
            rail = shown;
            railStart = start;
            railEnd = end;
        }

        /** {rail, its cap at the top, its cap at the bottom}. */
        boolean[] rails() {
            return new boolean[] { rail, railStart, railEnd };
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            super.draw(batch, parentAlpha);
            if (!rail) {
                return;
            }
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            float top = getHeight() - (railStart ? 3 : 0);
            float bottom = railEnd ? 3 : 0;
            ui.fill(batch, RAIL, alpha, getX() + 1, getY() + bottom, 1, top - bottom);
            if (railStart) {
                ui.fill(batch, RAIL, alpha, getX() + 1, getY() + top - 1, 3, 1);
            }
            if (railEnd) {
                ui.fill(batch, RAIL, alpha, getX() + 1, getY() + bottom, 3, 1);
            }
            batch.setPackedColor(previous);
        }
    }
}
