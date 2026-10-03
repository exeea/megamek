/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.UnitRow;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.units.Entity;

/**
 * The forces overview over the board (C.1 G13; r1 3.18, r2 8.3, shot 12): a card for every presented unit, the own and
 * allied units under "Your force" and the enemies under "Contacts", grouped by formation, weight class or status, with
 * a side filter and a search. A card locates its unit and hands it to the HUD's selection rule; its right click opens
 * the unit menu. The HUD opens and closes it ({@link GpuHudState#overview}); every rule stays with the client.
 */
final class GpuForceOverview implements GpuHud.Component {
    /** Cards are at least 230 units wide and 10 apart (.grid: repeat(auto-fill, minmax(230px, 1fr)), gap 10). */
    private static final float CARD_MIN = 230;
    private static final float CARD_GAP = 10;
    /** The panel's 2-unit side borders and the body's padding (#overview .body: 0 16 14). */
    private static final float BODY_INSET = 2 * 2 + 2 * 16;
    private static final float SEARCH_WIDTH = 280;
    /**
     * The heat bar's length in heat points: 30, as the prototype's card (ui.js overviewBody) and MegaMek's own unit
     * overview (UnitOverviewOverlay.heat) draw it. That overview runs to 50 under TacOps extended heat, an option the
     * status does not carry, so heat above 30 fills the bar.
     */
    private static final int HEAT_SCALE = 30;
    /** A card's fill at rest, under the pointer and selected (.ucard, .ucard:hover, .ucard.sel). */
    private static final Color FILL = new Color(1, 1, 1, .03f);
    private static final Color FILL_OVER = new Color(1, 1, 1, .07f);
    private static final Color FILL_SELECTED = UiTheme.alpha(UiTheme.MINT, .1f);
    /** The text of a value the status does not know (ARMOR_NA, no hex). */
    private static final String UNKNOWN = "—";
    private static final String SEPARATOR = " · ";

    /** The bar's groupings (ovGroup), in the order of their segments. */
    private enum Grouping { FORMATION, WEIGHT, STATUS }

    /** The bar's sides (ovSide), in the order of their segments: both, the own and allied units, the enemies. */
    private enum Show { BOTH, MINE, CONTACTS }

    /** The unit statuses (plan A.14 N6, ui.js unitStatus) in the order of their groups, with their texts and colors. */
    private enum Status {
        NEEDS_ORDERS("GpuBoard.hud.state.needsOrders", Tone.WARN),
        RUNNING_HOT("GpuBoard.hud.state.runningHot", Tone.WARN),
        DAMAGED("GpuBoard.hud.state.damaged", Tone.WARN),
        PRONE("GpuBoard.hud.state.prone", Tone.BAD),
        SHUT_DOWN("GpuBoard.hud.state.shutDown", Tone.BAD),
        ACTED("GpuBoard.hud.state.acted", Tone.OK),
        OPERATIONAL("GpuBoard.hud.state.operational", Tone.NORMAL),
        UNIDENTIFIED("GpuBoard.hud.common.unidentified", Tone.NORMAL),
        DESTROYED("GpuBoard.hud.state.destroyed", Tone.BAD);

        private final String key;
        private final Tone tone;

        Status(String key, Tone tone) {
            this.key = key;
            this.tone = tone;
        }
    }

    /**
     * Everything the overview shows. The snapshots keep their identity while unchanged, so comparing this each frame is
     * cheap, and the cards are shown again only when it differs. {@code width} is the panel's.
     */
    private record View(List<UnitStatus> units, GpuBattleStatus.Snapshot status, int focus, Grouping grouping,
          Show show, String query, float width, GpuPlayers.Snapshot players) { }

    private final GpuHudKit kit;
    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final IntConsumer select;
    private final Table root;
    private final Cell<Table> headerCell;
    private final UiButton close;
    private final UiKit.Segmented groupings;
    private final UiKit.Segmented sides;
    private final UiKit.SearchField search;
    private final Table body = new Table();
    /** The units' cards, kept across layouts so that a press and the pointer state stay with them. */
    private final Map<Integer, Card> cards = new HashMap<>();
    private Grouping grouping = Grouping.FORMATION;
    private Show show = Show.BOTH;
    private String count;
    private View shown;
    /** What the body was laid out for: the width, the query and each side's groups with their units. */
    private List<Object> shownLayout;

    /**
     * {@code menu} opens this component's unit menus and select lists (C13, SelectField). {@code select} selects or
     * inspects a unit ({@link GpuHud#select}).
     */
    GpuForceOverview(GpuHudKit kit, GpuBoardSource source, GpuHudState state, GpuContextMenu menu, IntConsumer select) {
        this.kit = kit;
        ui = kit.ui;
        this.source = source;
        this.state = state;
        this.menu = menu;
        this.select = select;
        root = ui.panel();
        root.setName("force-overview");
        close = ui.closeButton(() -> state.overview = false);
        close.setName("force-overview-close");
        headerCell = root.add((Table) null).growX();
        root.row();

        // The bar (.ovbar): the grouping and side segments at their natural widths, the search at the right.
        groupings = ui.segmented("hud-seg", false, text("GpuBoard.hud.overview.formation"),
              text("GpuBoard.hud.overview.weight"), text("GpuBoard.hud.overview.status"));
        sides = ui.segmented("hud-seg", false, text("GpuBoard.hud.overview.both"), text("GpuBoard.hud.overview.mine"),
              text("GpuBoard.hud.common.contacts"));
        for (int index = 0; index < Grouping.values().length; index++) {
            Grouping by = Grouping.values()[index];
            Show side = Show.values()[index];
            groupings.buttons.get(index).setName("force-overview-grouping-" + by.name().toLowerCase(Locale.ROOT));
            sides.buttons.get(index).setName("force-overview-show-" + side.name().toLowerCase(Locale.ROOT));
            onChange(groupings.buttons.get(index), () -> grouping = by);
            onChange(sides.buttons.get(index), () -> show = side);
        }
        search = ui.search(text("GpuBoard.hud.overview.searchPlaceholder"));
        search.field.setName("force-overview-search");
        Table bar = new Table();
        bar.setName("force-overview-bar");
        bar.pad(2, 16, 10, 16);
        bar.add(caption("GpuBoard.hud.overview.group")).padRight(8);
        bar.add(groupings);
        bar.add(caption("GpuBoard.hud.overview.show")).padLeft(16).padRight(8);
        bar.add(sides);
        bar.add().expandX();
        // The bar's tallest item (.field). It gives way first when a narrow window lacks the room.
        bar.add(search).prefWidth(SEARCH_WIDTH).minWidth(SEARCH_WIDTH / 2).padLeft(8);
        root.add(bar).growX().row();
        root.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();

        body.top().left();
        body.pad(0, 16, 14, 16);
        ScrollPane scroll = ui.scrollList(body);
        scroll.setName("force-overview-list");
        root.add(scroll).grow().minHeight(0).row();
        Table foot = ui.footer(root);
        // .ovfoot pads its line 9 16 11.
        foot.pad(9, 16, 11, 16);
        foot.setName("force-overview-footer");
        Label footer = ui.label(text("GpuBoard.hud.overview.footer"), "hud-small", 11.5f, UiTheme.MUTED);
        footer.setEllipsis(true);
        // Its CSS line box: 11.5 units at 1.35.
        foot.add(footer).growX().minWidth(0).height(15.5f).left();
    }

    private Label caption(String key) {
        return ui.label(UiTheme.upper(text(key)), "hud-caption", 11, UiTheme.MUTED);
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** Shows the presented units while the overview is open; a closed one catches up when it opens. */
    @Override
    public void update(GpuHud.Inputs inputs) {
        if (!state.overview) {
            return;
        }
        GpuHud.Metrics metrics = inputs.metrics();
        View view = new View(state.presentedUnits(), inputs.frame().status(), state.focus(), grouping, show,
              search.field.getText().strip().toLowerCase(Locale.ROOT), metrics.width() - 2 * metrics.gap(),
              inputs.frame().panels().players());
        if (!view.equals(shown)) {
            shown = view;
            refresh();
        }
    }

    private void refresh() {
        String units = text("GpuBoard.hud.overview.count", shown.units().size());
        if (!units.equals(count)) {
            count = units;
            Table header = ui.header(text("GpuBoard.hud.overview.title"), units, close);
            header.setName("force-overview-header");
            headerCell.setActor(header);
        }
        groupings.select(shown.grouping().ordinal());
        sides.select(shown.show().ordinal());

        List<UnitStatus> own = shown.show() == Show.CONTACTS ? List.of() : listed(false);
        List<UnitStatus> enemies = shown.show() == Show.MINE ? List.of() : listed(true);
        Map<String, List<UnitStatus>> ownGroups = groups(own);
        Map<String, List<UnitStatus>> enemyGroups = groups(enemies);
        // While the same groups list the same units, only the cards' contents change; a new layout rebuilds every
        // heading, section and grid, which statuses changing during the enemy's turn need not pay for.
        List<Object> layout = List.of(shown.width(), shown.query(), ids(ownGroups), ids(enemyGroups));
        if (layout.equals(shownLayout)) {
            Stream.concat(own.stream(), enemies.stream()).forEach(this::card);
            return;
        }
        shownLayout = layout;
        body.clearChildren();
        float inner = shown.width() - BODY_INSET;
        int columns = Math.max(1, (int) ((inner + CARD_GAP) / (CARD_MIN + CARD_GAP)));
        float width = (inner - CARD_GAP * (columns - 1)) / columns;
        if (own.isEmpty() && enemies.isEmpty()) {
            ui.empty(body, shown.query().isEmpty() ? text("GpuBoard.hud.forces.noUnits")
                  : text("GpuBoard.hud.forces.noMatch", shown.query()));
        }
        side(false, ownGroups, own.size(), columns, width);
        side(true, enemyGroups, enemies.size(), columns, width);
        Set<Integer> kept = new HashSet<>();
        Stream.concat(own.stream(), enemies.stream()).forEach(unit -> kept.add(unit.id()));
        cards.keySet().retainAll(kept);
    }

    /** The units of one side, own and allied or enemy, that the search finds. */
    private List<UnitStatus> listed(boolean enemies) {
        return shown.units().stream().filter(unit -> (unit.side() == Side.ENEMY) == enemies)
              .filter(unit -> GpuHudKit.matches(unit, formation(unit), shown.query())).toList();
    }

    /**
     * One side's groups (#overview .sect): by formation in the order the units come, the unidentified last; by weight
     * class in the order of their heaviest units ({@link GpuHudKit#HEAVIEST_FIRST}); by status in the order of the
     * taxonomy.
     */
    private Map<String, List<UnitStatus>> groups(List<UnitStatus> units) {
        String unidentified = text(Status.UNIDENTIFIED.key);
        Map<Integer, String> keys = new HashMap<>();
        for (UnitStatus unit : units) {
            keys.put(unit.id(), switch (shown.grouping()) {
                case FORMATION -> formation(unit);
                case WEIGHT -> unit.sensorContact() ? unidentified : unit.weightClass();
                case STATUS -> text(status(unit).key);
            });
        }
        Comparator<UnitStatus> order = switch (shown.grouping()) {
            case FORMATION -> Comparator.comparing(unit -> keys.get(unit.id()).equals(unidentified));
            case WEIGHT -> GpuHudKit.HEAVIEST_FIRST;
            case STATUS -> Comparator.comparing(this::status);
        };
        return GpuHudKit.groups(units, unit -> keys.get(unit.id()), order);
    }

    private static List<Object> ids(Map<String, List<UnitStatus>> groups) {
        return groups.entrySet().stream().map(group -> (Object) List.of(group.getKey(),
              group.getValue().stream().map(UnitStatus::id).toList())).toList();
    }

    private String formation(UnitStatus unit) {
        return GpuHudKit.formation(unit, shown.status(), shown.players());
    }

    /**
     * A unit's status (N6, ui.js unitStatus): destroyed, unidentified, shut down or prone first; an own unit that has
     * acted in the phase, or that a remaining turn of it accepts and so needs orders; then damaged from a light damage
     * level up, running hot while its heat has effects (the status carries the heat table's text only then), and
     * otherwise operational.
     */
    private Status status(UnitStatus unit) {
        boolean own = unit.side() == Side.OWN;
        if (unit.destroyed()) {
            return Status.DESTROYED;
        } else if (unit.sensorContact()) {
            return Status.UNIDENTIFIED;
        } else if (UnitRow.shutDown(unit)) {
            return Status.SHUT_DOWN;
        } else if (UnitRow.prone(unit)) {
            return Status.PRONE;
        } else if (own && UnitRow.acted(unit, shown.status().phase())) {
            return Status.ACTED;
        } else if (own && unit.pending()) {
            return Status.NEEDS_ORDERS;
        } else if (unit.damageLevel() >= Entity.DMG_LIGHT) {
            return Status.DAMAGED;
        } else if (!unit.heatEffects().isEmpty()) {
            return Status.RUNNING_HOT;
        }
        return Status.OPERATIONAL;
    }

    /**
     * One side (#overview .side): its heading, "Your force" in mint or "Contacts" in coral, with the number shown, then
     * each group's title and count over its grid of cards; nothing when no unit of the side is listed.
     */
    private void side(boolean enemies, Map<String, List<UnitStatus>> groups, int listed, int columns, float width) {
        if (groups.isEmpty()) {
            return;
        }
        // The headings' font shorthand resets their line height to the font's normal one, 1.17 of 17 and 12 units: 20
        // and 14 whole units, as Table layout rounds sizes up.
        Table heading = new Table();
        heading.setName(enemies ? "force-overview-contacts" : "force-overview-own");
        heading.add(ui.label(UiTheme.upper(text(enemies ? "GpuBoard.hud.common.contacts"
              : "GpuBoard.hud.common.yourForce")), "hud-main", 17, enemies ? UiTheme.CORAL : UiTheme.MINT))
              .height(20).padRight(10);
        heading.add(ui.label(UiTheme.upper(text("GpuBoard.hud.overview.shown", listed)), "hud-medium", 11.5f,
              UiTheme.MUTED)).bottom();
        body.add(heading).left().padTop(14).row();
        groups.forEach((name, members) -> {
            Table section = new Table();
            section.setName("force-overview-section-" + name);
            section.defaults().height(14);
            section.add(ui.label(UiTheme.upper(name), "hud-main", 12, UiTheme.ACCENT)).padRight(8);
            section.add(ui.label(String.valueOf(members.size()), "hud-main", 12, UiTheme.MUTED));
            body.add(section).left().pad(12, 0, 7, 0).row();
            Table grid = new Table();
            grid.top().left();
            grid.defaults().space(CARD_GAP).width(width).fill();
            for (int index = 0; index < members.size(); index++) {
                grid.add(card(members.get(index)));
                if (index % columns == columns - 1) {
                    grid.row();
                }
            }
            body.add(grid).left().row();
        });
    }

    private Card card(UnitStatus unit) {
        Card card = cards.computeIfAbsent(unit.id(), Card::new);
        card.show(unit);
        return card;
    }

    private UnitStatus unit(int id) {
        return shown.units().stream().filter(unit -> unit.id() == id).findFirst().orElse(null);
    }

    /** A meter's share of the unit, or "—" over an empty bar where the status knows none (ARMOR_NA). */
    private static void share(UiKit.Meter meter, double share, Color fill) {
        boolean known = share >= 0;
        meter.set(known ? String.valueOf(Math.round(share * 100)) : UNKNOWN, known ? (float) share : 0, fill);
    }

    private static String hex(UnitStatus unit) {
        return unit.position() == null ? UNKNOWN : unit.position().getBoardNum();
    }

    /**
     * A unit card (.ucard): the sprite, the upper-case name and the model, weight and hex; the armor, structure and
     * heat bars (enemies as the client discloses them, plan D3; heat only for a unit that tracks it); the status, the
     * movement points and the skills. A sensor contact's card shows the "?", its hex and "Sensor return only". A click
     * locates the unit and selects or inspects it (app.js ovGo); a right click opens its menu.
     */
    private final class Card extends Table {
        private final GpuHudKit.UnitSprite sprite = kit.sprite(36, 30);
        private final Label name = ui.label("", "hud-name", 15, UiTheme.TEXT);
        private final Label detail = ui.label("", "hud-small", 11, UiTheme.MUTED);
        private final UiKit.Meter armor = ui.meter(text("GpuBoard.hud.common.armor"), true);
        private final UiKit.Meter structure = ui.meter(text("GpuBoard.hud.overview.structureShort"), true);
        private final UiKit.Meter heat = ui.meter(text("GpuBoard.hud.common.heat"), true);
        private final Label status = ui.label("", "hud-main", 10.5f, Color.WHITE);
        private final Label movement = ui.label("", "hud-small", 11, UiTheme.MUTED);
        private final Label skills = ui.label("", "hud-small", 11, UiTheme.MUTED);
        private final ClickListener pointer;
        private Cell<Actor> heatCell;
        /** The form laid out: a sensor contact's or an identified unit's; null before the first. */
        private Boolean contact;
        private List<Object> shownKey;
        private boolean enemy;
        private boolean selected;
        private Drawable rest;
        private Drawable over;
        private Drawable picked;

        private Card(int id) {
            setName("force-overview-card-" + id);
            setTouchable(Touchable.enabled);
            // .ucard .h span outranks .q: a contact's "?" is 11 units.
            sprite.setActor(ui.label("?", "hud-heading", 11, Color.WHITE));
            name.setEllipsis(true);
            detail.setEllipsis(true);
            pointer = new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    source.locateUnit(id);
                    select.accept(id);
                }
            };
            addListener(pointer);
            addListener(UnitRow.menuOpener(menu, id, GpuForceOverview.this::unit));
        }

        void show(UnitStatus unit) {
            boolean acting = unit.id() == UnitRow.acting(shown.status(), shown.focus());
            // What it shows follows the unit, the phase (its status) and the selection only.
            List<Object> key = List.of(unit, shown.status().phase(), acting);
            if (key.equals(shownKey)) {
                return;
            }
            shownKey = key;
            selected = acting;
            boolean foe = unit.side() == Side.ENEMY;
            if (rest == null || foe != enemy) {
                enemy = foe;
                Color edge = foe ? UiTheme.CORAL : UiTheme.MINT;
                rest = surface(FILL, edge);
                over = surface(FILL_OVER, edge);
                picked = surface(FILL_SELECTED, edge);
            }
            getColor().a = unit.destroyed() ? .45f : 1;
            if (contact == null || contact != unit.sensorContact()) {
                contact = unit.sensorContact();
                build();
            }
            // Shot 12 draws every card's sprite, and a contact's "?", in the muted color: the mock's ".ucard .h span"
            // rule outranks its ".ucard .spr" colors.
            sprite.set(unit.sensorContact() ? null : unit.icon(), UiTheme.MUTED);
            if (unit.sensorContact()) {
                name.setText(UiTheme.upper(text("GpuBoard.hud.common.sensorContact")));
                detail.setText(text("GpuBoard.hud.common.identityUnknown", hex(unit)));
                return;
            }
            name.setText(UiTheme.upper(unit.chassis()));
            String line = text("GpuBoard.hud.overview.cardDetail", unit.model(), unit.tons(), hex(unit));
            // A unit without a model name starts with its weight.
            detail.setText(unit.model().isBlank() ? line.substring(line.indexOf(SEPARATOR) + SEPARATOR.length())
                  : line);
            Color fill = foe ? UiTheme.CORAL : UiTheme.MINT;
            share(armor, unit.armor(), fill);
            share(structure, unit.structure(), fill);
            heat.set(String.valueOf(unit.heat()), Math.min(unit.heat(), HEAT_SCALE) / (float) HEAT_SCALE,
                  UiTheme.AMBER);
            heatCell.setActor(unit.heatCapacity().isEmpty() ? null : heat);
            Status word = status(unit);
            status.setText(UiTheme.upper(text(word.key)));
            status.setColor(word.tone.color);
            movement.setText(unit.jump() > 0
                  ? text("GpuBoard.hud.overview.mpJump", unit.walk(), unit.run(), unit.jump())
                  : text("GpuBoard.hud.overview.mp", unit.walk(), unit.run()));
            skills.setText(text("GpuBoard.hud.overview.skills", unit.gunnery(), unit.piloting()));
        }

        private Drawable surface(Color fill, Color edge) {
            return new UiTheme.EdgeBox(ui.skin.get("white", Texture.class), fill, edge, 2, 0, 0, 0);
        }

        /**
         * Lays out the card's form: the head (.h: the sprite, then the name and the line, each on the card's 13-unit
         * strut at 1.35), then a contact's note, or the bars (.bars2) and the meta row (.meta), 9 units apart. The
         * rows are the CSS line boxes in whole units, as Table layout rounds sizes up: 17.55 for each head line, 11.1 +
         * 4 + 3 for the bars, 14.85 for the meta row (11 units at 1.35).
         */
        private void build() {
            clearChildren();
            // .ucard pads 11 13 10 below its 2-unit top edge.
            pad(13, 13, 10, 13);
            Table head = new Table();
            head.add(sprite).padRight(10);
            Table lines = new Table();
            lines.defaults().left().growX().minWidth(0).height(18);
            lines.add(name).row();
            lines.add(detail);
            head.add(lines).growX().minWidth(0);
            add(head).growX().minWidth(0).row();
            if (contact) {
                add(ui.label(text("GpuBoard.hud.overview.sensorReturnOnly"), "hud-small", 11, UiTheme.MUTED))
                      .left().height(15).padTop(9);
                return;
            }
            Table bars = new Table();
            bars.defaults().growX().uniformX().minWidth(0);
            bars.add(armor);
            bars.add(structure).padLeft(10);
            heatCell = bars.add((Actor) heat).padLeft(10);
            add(bars).growX().minWidth(0).padTop(9).row();
            // .st sets its own font, so its text sits at the top of the meta row, as the others do on their line.
            Table meta = new Table();
            meta.top();
            meta.defaults().top();
            meta.add(status).left();
            meta.add().expandX();
            meta.add(movement);
            meta.add().expandX();
            meta.add(skills).right();
            add(meta).growX().height(15).padTop(9);
        }

        @Override
        public void act(float delta) {
            setBackground(selected ? picked : pointer.isOver() ? over : rest);
            super.act(delta);
        }
    }
}
