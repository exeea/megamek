/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPlaybackHistory.Step;
import megamek.client.ui.clientGUI.boardview.gpu.GpuReportLog.Entry;
import megamek.client.ui.dialogs.RoundsInAirDialog;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.enums.GamePhase;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;

/**
 * The combat log and round report in the right column (C.1 G11; r1 3.15, r2 6; shots 08, 10): the round's events as
 * cards in report order, linked to their units and to the playback history, with the Summary and Full log tabs, the
 * All events, My force and Critical filters, search and the totals, and MegaMek's own report tools (earlier rounds, the
 * report keywords, copy) and the artillery in flight. Its cards review their step (K8), locate a unit and replay.
 */
final class GpuLogPanel implements GpuHud.Component {
    private static final String SEPARATOR = " \u00B7 ";
    private static final String KEYWORD_FILTER = "MiniReportDisplay.KeywordFilter";
    /** The prototype's line height, 1.35 times the font size (proto3.css body). */
    private static final float LINE = 1.35f;
    /** The tabs (.ltabs) and the filters (.seg), by their index. */
    private static final int SUMMARY = 0;
    private static final int FULL_LOG = 1;
    private static final int ALL_EVENTS = 0;
    private static final int MY_FORCE = 1;
    private static final int CRITICAL = 2;

    /**
     * One card (.ev) as shown: its source (a log entry, a move or an artillery round in the air), disc, who, what and
     * roll lines, the current card's extras and MegaMek's own report text, its steps, the unit Locate centres on, and
     * its state. Cards compare by value, so a card that did not change keeps its actor.
     */
    private record Card(Object source, String disc, boolean walk, boolean foe, String who,
          List<GpuEventLine.Part> what, String roll, List<String> extras, String report, List<Step> steps, int unit,
          boolean current, boolean matched) {
        /** The card's text for the search, the keywords and the clipboard. */
        String plain() {
            StringBuilder plain = new StringBuilder(who);
            what.forEach(part -> plain.append(' ').append(part.text()));
            return plain.append(' ').append(roll).append(' ').append(source instanceof Entry entry ? entry.text() : "")
                  .toString();
        }
    }

    /** Everything the list shows; the snapshots keep their identity while unchanged, so comparing it is cheap. */
    private record View(GpuReportLog.Snapshot log, List<Step> played, Step cursor, Object opened, int tab, int filter,
          boolean searching, String query, int round, Set<Integer> friendly, boolean keywords, String keyword,
          String filterKeyword, Object match, boolean narrow) { }

    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final Table root;
    private final Cell<Actor> headerCell;
    private final UiButton roundSelect;
    private final UiButton searchToggle;
    private final UiButton close;
    private final TextTooltip closeTip;
    private final UiKit.Segmented tabs;
    private final UiKit.Segmented filters;
    private final Cell<Actor> searchCell;
    private final Table searchArea = new Table();
    private final UiKit.SearchField search;
    private final Cell<Actor> keywordCell;
    private final Table keywordRow = new Table();
    private final UiButton keywordSelect;
    private final UiButton filterSelect;
    private final Cell<Actor> totalsCell;
    private final Table totals = new Table();
    private final List<Label> totalNumbers = new ArrayList<>();
    private final List<Label> totalCaptions = new ArrayList<>();
    private final Table list = new Table();
    private final ScrollPane scroll;
    /** The actors of the shown cards, kept while their card is unchanged. */
    private final Map<Card, UiButton> cardActors = new HashMap<>();
    private GpuHud.Inputs inputs;
    private View shown;
    private List<Card> shownCards = List.of();
    private String header = "";
    private int tab = SUMMARY;
    private int filter = ALL_EVENTS;
    private boolean searching;
    /** An earlier round the player picked (K13), or 0 for the current one; a new round shows itself again. */
    private int pickedRound;
    private int currentRound;
    /** A card of no step that the player opened (it shows Locate), until the cursor moves; or null. */
    private Object opened;
    private Step openedAt;
    /** The units ever seen on the local player's side, so the cards of a destroyed own unit stay in My force. */
    private Set<Integer> friendly = Set.of();
    private List<GpuBattleStatus.UnitStatus> friendlyFrom;
    private GpuBoardSource.UiPreferences preferences;
    private List<String> keywords = List.of();
    private List<String> filterKeywords = List.of();
    private int keyword;
    /** The selected filter keyword, applied while {@link #filtering} (REPORT_KEY_FILTER). */
    private int filterKeyword;
    private boolean filtering;
    /** The source of the card a keyword search found; the next list scrolls to it once. */
    private Object match;
    private boolean scrollToMatch;
    private Actor scrollTarget;

    /** {@code menu} opens this component's select lists (C.3 SelectField): the rounds and the keywords. */
    GpuLogPanel(GpuHudKit kit, GpuBoardSource source, GpuHudState state, GpuContextMenu menu) {
        ui = kit.ui;
        this.source = source;
        this.state = state;
        this.menu = menu;
        root = ui.panel();
        root.setName("log-panel");

        // .phd: the title, MegaMek's earlier rounds (K13), the search toggle and the close button.
        roundSelect = ui.select("", true);
        roundSelect.setName("log-round");
        onChange(roundSelect, this::pickRound);
        searchToggle = ui.button("hud-icon", "search", null, null);
        searchToggle.setName("log-search");
        ui.tip(searchToggle).getActor().setText(text("GpuBoard.hud.log.searchTip"));
        onChange(searchToggle, () -> search(!searching));
        close = ui.closeButton(() -> {
            if (state.logOpen()) {
                state.toggleLog();
            }
        });
        close.setName("log-close");
        closeTip = ui.tip(close);
        headerCell = root.add((Actor) null).growX();
        root.row();

        tabs = ui.segmented("hud-tab-caps", true, text("GpuBoard.hud.log.summary"), text("GpuBoard.hud.log.fullLog"));
        tabs.pad(0, 14, 0, 14);
        tabs.setName("log-tabs");
        tabs.buttons.get(SUMMARY).setName("log-tab-summary");
        tabs.buttons.get(FULL_LOG).setName("log-tab-full");
        onChange(tabs.buttons.get(SUMMARY), () -> tab = SUMMARY);
        onChange(tabs.buttons.get(FULL_LOG), () -> tab = FULL_LOG);
        root.add(tabs).growX().row();

        filters = ui.segmented("hud-seg", true, text("GpuBoard.hud.log.allEvents"), text("GpuBoard.hud.log.myForce"),
              text("GpuBoard.hud.log.critical"));
        filters.pad(8, 14, 0, 14);
        filters.setName("log-filters");
        for (int index = 0; index < filters.buttons.size(); index++) {
            int choice = index;
            filters.buttons.get(index).setName("log-filter-" + index);
            onChange(filters.buttons.get(index), () -> filter = choice);
        }
        root.add(filters).growX().row();

        // .fq, and below it MegaMek's report keywords: find (N / Shift+N) and the keyword filter (Shift+F).
        search = ui.search(text("GpuBoard.hud.log.searchPlaceholder"));
        search.field.setName("log-search-field");
        searchArea.setName("log-search-area");
        keywordRow.setName("log-keywords");
        searchArea.add(search).growX().pad(8, 14, 0, 14).row();
        keywordCell = searchArea.add((Actor) null).growX().pad(8, 14, 0, 14);
        keywordSelect = ui.select("", true);
        keywordSelect.setName("log-keyword");
        ui.tip(keywordSelect).getActor().setText(text("MiniReportDisplay.tooltip.ComboQuickInfo"));
        onChange(keywordSelect, () -> menu.list(keywordSelect, keywords, keyword, index -> keyword = index));
        UiButton previous = ui.button("hud-mini", "chevron-up", null, null);
        previous.setName("log-keyword-previous");
        ui.tip(previous).getActor().setText(text("MiniReportDisplay.tooltip.ArrowUp"));
        onChange(previous, () -> find(-1));
        UiButton next = ui.button("hud-mini", "chevron-down", null, null);
        next.setName("log-keyword-next");
        ui.tip(next).getActor().setText(text("MiniReportDisplay.tooltip.ArrowDown"));
        onChange(next, () -> find(1));
        filterSelect = ui.select("", true);
        filterSelect.setName("log-keyword-filter");
        ui.tip(filterSelect).getActor().setText(text("MiniReportDisplay.tooltip.KeywordFilter"));
        onChange(filterSelect, this::pickFilter);
        keywordRow.add(ui.caption(text("GpuBoard.hud.log.keywords"))).padRight(8);
        keywordRow.add(keywordSelect).growX().minWidth(0);
        keywordRow.add(previous).padLeft(6);
        keywordRow.add(next).padLeft(4);
        keywordRow.add(filterSelect).minWidth(0).padLeft(10);
        searchCell = root.add((Actor) null).growX();
        root.row();

        // .totals: three cells of a number beside its caption, divided by hairlines.
        Texture white = ui.skin.get("white", Texture.class);
        totals.setName("log-totals");
        totals.setBackground(new UiTheme.EdgeBox(white, null, UiTheme.alpha(UiTheme.QUIET, .2f), 1, 1, 1, 1));
        for (int index = 0; index < 3; index++) {
            Table cell = new Table();
            cell.setName("log-total-" + index);
            cell.pad(7, 4, 7, 4);
            if (index > 0) {
                cell.setBackground(new UiTheme.EdgeBox(white, null, UiTheme.alpha(UiTheme.QUIET, .15f), 0, 0, 0, 1));
            }
            totals.add(cell).growX().uniformX();
            totalNumbers.add(ui.label("", "hud-name", 18, Color.WHITE));
            totalCaptions.add(ui.label("", "hud-caption", 10.5f, UiTheme.MUTED));
        }
        totalsCell = root.add((Actor) null).growX().pad(8, 14, 6, 14);
        root.row();

        list.top();
        list.setName("log-cards");
        scroll = ui.scrollList(list);
        scroll.setName("log-list");
        root.add(scroll).growX().row();

        Table foot = ui.footer(root);
        foot.setName("log-footer");
        Label footText = ui.label(text("GpuBoard.hud.log.footer"), "hud-small", 11.5f, UiTheme.MUTED);
        footText.setWrap(true);
        foot.add(footText).growX().minWidth(0).left();
        UiButton copy = ui.button("hud-mini", null, text("GpuBoard.hud.log.copy"), null);
        copy.setName("log-copy");
        onChange(copy, () -> Gdx.app.getClipboard().setContents(copyText()));
        foot.add(copy).padLeft(10).top();
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        GpuPlaybackHistory history = state.history;
        GpuReportLog.Snapshot log = inputs.frame().reports();
        if (log.round() != currentRound) {
            currentRound = log.round();
            pickedRound = 0;
        }
        friendly();
        preferences(inputs.preferences());
        if (!Objects.equals(history.current(), openedAt)) {
            // The card of the step the cursor moved to is the current card again.
            opened = null;
            openedAt = history.current();
        }
        View view = new View(log, history.played(), history.current(), opened, tab, filter, searching,
              searching ? search.field.getText().strip().toLowerCase(Locale.ROOT) : "", round(log), friendly,
              !keywords.isEmpty() || !filterKeywords.isEmpty(), keywords.isEmpty() ? "" : keywords.get(keyword),
              filtering && !filterKeywords.isEmpty() ? filterKeywords.get(filterKeyword) : "", match,
              inputs.metrics().narrow());
        if (!view.equals(shown)) {
            shown = view;
            rebuild();
        }
        if (scrollTarget != null && scrollTarget.getStage() != null) {
            // The card a keyword search found, once a layout placed it.
            root.validate();
            Vector2 corner = scrollTarget.localToAscendantCoordinates(list, new Vector2());
            scroll.scrollTo(corner.x, corner.y, scrollTarget.getWidth(), scrollTarget.getHeight(), false, true);
            scrollTarget = null;
        }
    }

    /**
     * MegaMek's report keys while the log is open (K13): find the next or previous card with the selected keyword
     * (REPORT_KEY_NEXT, REPORT_KEY_PREV), select another keyword (REPORT_KEY_SELECT_NEXT, REPORT_KEY_SELECT_PREVIOUS)
     * or filter keyword (REPORT_FILTER_KEY_SELECT_NEXT), and toggle the keyword filter (REPORT_KEY_FILTER). Each opens
     * the search row, where the keywords show. True when it handled one.
     */
    boolean key(Set<KeyCommandBind> binds) {
        if (binds.contains(KeyCommandBind.REPORT_KEY_NEXT) || binds.contains(KeyCommandBind.REPORT_KEY_PREV)) {
            find(binds.contains(KeyCommandBind.REPORT_KEY_NEXT) ? 1 : -1);
        } else if (binds.contains(KeyCommandBind.REPORT_KEY_SELECT_NEXT)
              || binds.contains(KeyCommandBind.REPORT_KEY_SELECT_PREVIOUS)) {
            int step = binds.contains(KeyCommandBind.REPORT_KEY_SELECT_NEXT) ? 1 : -1;
            keyword = keywords.isEmpty() ? 0 : Math.floorMod(keyword + step, keywords.size());
        } else if (binds.contains(KeyCommandBind.REPORT_FILTER_KEY_SELECT_NEXT)) {
            filterKeyword = filterKeywords.isEmpty() ? 0 : Math.floorMod(filterKeyword + 1, filterKeywords.size());
            filtering = !filterKeywords.isEmpty();
        } else if (binds.contains(KeyCommandBind.REPORT_KEY_FILTER)) {
            filtering = !filtering && !filterKeywords.isEmpty();
        } else {
            return false;
        }
        search(true);
        return true;
    }

    /** The search toggle (.fq): opens the field with the keyboard focus, or closes and clears it. */
    private void search(boolean open) {
        if (open != searching) {
            searching = open;
            search.field.setText("");
            if (searching && root.getStage() != null) {
                root.getStage().setKeyboardFocus(search.field);
            }
        }
        searchToggle.pressed(searching);
    }

    /** The round the log shows: the current one, or an earlier round the player picked (K13). */
    private int round(GpuReportLog.Snapshot log) {
        int current = Math.max(1, log.round());
        return pickedRound > 0 && pickedRound < current ? pickedRound : current;
    }

    private void pickRound() {
        GpuReportLog.Snapshot log = inputs.frame().reports();
        int current = Math.max(1, log.round());
        List<Integer> rounds = rounds(log);
        menu.list(roundSelect, rounds.stream().map(round -> text("GpuBoard.hud.log.roundSelect", round)).toList(),
              rounds.indexOf(round(log)), index -> pickedRound = rounds.get(index) == current ? 0 : rounds.get(index));
    }

    /** The log's rounds, newest first; the current round is one of them. */
    private static List<Integer> rounds(GpuReportLog.Snapshot log) {
        Set<Integer> rounds = new HashSet<>();
        rounds.add(Math.max(1, log.round()));
        log.entries().forEach(entry -> rounds.add(entry.round()));
        return rounds.stream().sorted((first, second) -> second - first).toList();
    }

    /** The keyword filter's list: no filter, then the configured filter keywords. */
    private void pickFilter() {
        List<String> choices = new ArrayList<>(List.of(text(KEYWORD_FILTER)));
        choices.addAll(filterKeywords);
        menu.list(filterSelect, choices, filtering ? filterKeyword + 1 : 0, index -> {
            filtering = index > 0;
            filterKeyword = Math.max(0, index - 1);
        });
    }

    /** Takes the configured keywords (Client Settings, Report) and the close key's text when the preferences change. */
    private void preferences(GpuBoardSource.UiPreferences next) {
        if (next != preferences) {
            preferences = next;
            keywords = lines(next.reportKeywords());
            filterKeywords = lines(next.reportFilterKeywords());
            keyword = Math.min(keyword, Math.max(0, keywords.size() - 1));
            filterKeyword = Math.min(filterKeyword, Math.max(0, filterKeywords.size() - 1));
            filtering &= !filterKeywords.isEmpty();
            closeTip.getActor().setText(text("GpuBoard.hud.common.closeTip",
                  GpuHintLine.key(next, KeyCommandBind.ROUND_REPORT)));
        }
    }

    private static List<String> lines(String preference) {
        return preference.lines().map(String::strip).filter(line -> !line.isEmpty()).distinct().toList();
    }

    /** The local player's units and allies, as presented; a unit once seen on that side stays on it. */
    private void friendly() {
        List<GpuBattleStatus.UnitStatus> units = state.presentedUnits();
        if (units == friendlyFrom) {
            return;
        }
        friendlyFrom = units;
        Set<Integer> next = new HashSet<>(friendly);
        for (GpuBattleStatus.UnitStatus unit : units) {
            if (unit.side() == GpuBattleStatus.Side.ENEMY) {
                next.remove(unit.id());
            } else {
                next.add(unit.id());
            }
        }
        if (!next.equals(friendly)) {
            friendly = Set.copyOf(next);
        }
    }

    // ------------------------------------------------------------------ the list

    private void rebuild() {
        GpuReportLog.Snapshot log = shown.log();
        boolean earlier = shown.round() < Math.max(1, log.round());
        GamePhase phase = log.phase();
        String title = text(earlier || phase.isEnd() || phase.isEndReport() || phase.isVictory()
              ? "GpuBoard.hud.log.battleReport" : "GpuBoard.hud.log.combatLog", shown.round());
        boolean rounds = rounds(log).size() > 1;
        roundSelect.setText(text("GpuBoard.hud.log.roundSelect", shown.round()));
        if (!header.equals(title + rounds)) {
            header = title + rounds;
            Table next = rounds ? ui.header(title, null, roundSelect, searchToggle, close)
                  : ui.header(title, null, searchToggle, close);
            next.setName("log-header");
            headerCell.setActor(next);
        }
        tabs.select(shown.tab());
        filters.select(shown.filter());
        searchCell.setActor(shown.searching() ? searchArea : null);
        keywordCell.setActor(shown.keywords() ? keywordRow : null);
        keywordSelect.setText(shown.keyword());
        filterSelect.setText(shown.filterKeyword().isEmpty() ? text(KEYWORD_FILTER) : shown.filterKeyword());
        totalsCell.setActor(earlier ? null : totals);
        if (!earlier) {
            totals(log);
        }

        List<Card> all = cards(log, shown.round(), earlier);
        List<Card> listed = all.stream().filter(card -> shown.tab() == FULL_LOG
              || !(card.source() instanceof GpuReportLog.MoveEvent)).filter(this::passes).toList();
        List<Card> artillery = earlier || shown.tab() == FULL_LOG ? List.of()
              : log.inFlight().stream().map(this::card).filter(this::passes).toList();
        List<Card> cards = new ArrayList<>();
        list.clearChildren();
        if (listed.isEmpty() && artillery.isEmpty()) {
            ui.empty(list, text(all.isEmpty() ? "GpuBoard.hud.log.noEvents" : "GpuBoard.hud.log.noMatch"));
        } else if (shown.tab() == FULL_LOG) {
            listed.forEach(card -> add(card, cards));
        } else {
            group("GpuBoard.hud.log.weaponAttacks", listed, GpuReportLog.Kind.WEAPON, cards);
            group("GpuBoard.hud.log.physical", listed, GpuReportLog.Kind.PHYSICAL, cards);
            group("GpuBoard.hud.log.pilotingRolls", listed, GpuReportLog.Kind.PSR, cards);
            group("GpuBoard.hud.log.heatStatus", listed, GpuReportLog.Kind.HEAT, cards);
            if (!artillery.isEmpty()) {
                section(text("GpuBoard.hud.log.artilleryInFlight"), String.valueOf(artillery.size()));
                artillery.forEach(card -> add(card, cards));
            }
            if (cards.isEmpty()) {
                ui.empty(list, text("GpuBoard.hud.log.nothingToReport"));
            }
        }
        shownCards = List.copyOf(cards);
        cardActors.keySet().retainAll(new HashSet<>(cards));
        scrollToMatch = false;
    }

    /** Whether a card passes My force, Critical, the search and the keyword filter. */
    private boolean passes(Card card) {
        Object source = card.source();
        boolean mine = source instanceof Entry entry ? entry.involves(shown.friendly())
              : !(source instanceof GpuReportLog.MoveEvent move) || shown.friendly().contains(move.entityId());
        boolean critical = source instanceof Entry entry && entry.critical();
        String plain = card.plain().toLowerCase(Locale.ROOT);
        return (shown.filter() != MY_FORCE || mine) && (shown.filter() != CRITICAL || critical)
              && (shown.query().isEmpty() || plain.contains(shown.query()))
              && (shown.filterKeyword().isEmpty() || List.of(shown.filterKeyword().split("\\s+")).stream()
                    .anyMatch(word -> plain.contains(word.toLowerCase(Locale.ROOT))));
    }

    /** A Summary group (.sech and its cards): the entries of one kind; heat only with heat, a check or an alert. */
    private void group(String key, List<Card> listed, GpuReportLog.Kind kind, List<Card> cards) {
        List<Card> members = listed.stream().filter(card -> card.source() instanceof Entry entry && entry.kind() == kind
              && (kind != GpuReportLog.Kind.HEAT || entry.heatAlert() || entry.targetNumber() != null
                    || entry.heat() != null && entry.heat() > 0)).toList();
        if (!members.isEmpty()) {
            section(text(key), text("GpuBoard.hud.log.events", members.size()));
            members.forEach(card -> add(card, cards));
        }
    }

    /** A section header (.sech): the upper-case title and its muted count. */
    private void section(String title, String count) {
        Table head = new Table();
        head.setName("log-section");
        head.add(ui.label(UiTheme.upper(title), "hud-caption", 12, Color.WHITE)).left().bottom();
        head.add().growX();
        head.add(ui.label(count, "hud-small", 10.5f, UiTheme.MUTED)).bottom();
        list.add(head).growX().pad(10, 14, 5, 14).row();
    }

    private void add(Card card, List<Card> cards) {
        cards.add(card);
        UiButton actor = cardActors.computeIfAbsent(card, this::cardActor);
        list.add(actor).growX().minWidth(0).pad(0, 10, 6, 10).row();
        if (card.matched() && scrollToMatch) {
            scrollTarget = actor;
        }
    }

    /**
     * The round's cards in report order: the log's entries, but not yet those of a step the playback has not shown,
     * and in the current round the moves the playback showed, after the entries up to the movement phase.
     */
    private List<Card> cards(GpuReportLog.Snapshot log, int round, boolean earlier) {
        GpuPlaybackHistory history = state.history;
        List<Card> cards = new ArrayList<>();
        List<GpuReportLog.MoveEvent> moves = earlier ? List.of() : log.moves().stream()
              .filter(move -> move.round() == round).toList();
        boolean movesAdded = moves.isEmpty();
        for (Entry entry : log.entries()) {
            if (entry.round() != round) {
                continue;
            }
            if (!movesAdded && entry.gamePhase().ordinal() > GamePhase.MOVEMENT.ordinal()) {
                moves.forEach(move -> cards.add(card(move)));
                movesAdded = true;
            }
            List<Step> steps = history.steps(entry);
            if (steps.isEmpty() || steps.stream().anyMatch(history::played)) {
                cards.add(card(entry, steps));
            }
        }
        if (!movesAdded) {
            moves.forEach(move -> cards.add(card(move)));
        }
        return cards;
    }

    /** An entry's card (K7, K9). */
    private Card card(Entry entry, List<Step> steps) {
        // An entry of two piloting rolls shows the one under the cursor, else its first.
        Step step = steps.isEmpty() ? null : steps.contains(shown.cursor()) ? shown.cursor() : steps.getFirst();
        int subject = entry.attackerId() != Entity.NONE ? entry.attackerId()
              : entry.units().isEmpty() ? Entity.NONE : entry.units().getFirst().id();
        int actor = step != null ? step.actorId() : subject;
        String disc = step != null ? String.format(Locale.ROOT, "%02d", state.history.number(step))
              : entry.kind() == GpuReportLog.Kind.HEAT && entry.heat() != null ? String.valueOf(entry.heat()) : "";
        boolean current = opened == null ? steps.contains(shown.cursor()) : opened.equals(entry);
        return new Card(entry, disc, false, actor != Entity.NONE && !shown.friendly().contains(actor),
              GpuEventLine.who(entry, actor), GpuEventLine.what(entry, step), roll(entry, step),
              current ? extras(entry, step) : List.of(), current ? entry.text() : "", steps,
              step != null ? step.unitId() : subject, current, entry.equals(shown.match()));
    }

    /** The roll line: the target number and the roll of the attack, the piloting roll or the first heat check. */
    private static String roll(Entry entry, Step step) {
        if (step != null && step.roll() != null) {
            return step.roll().targetNumber() == TargetRoll.AUTOMATIC_FAIL ? ""
                  : text("GpuBoard.hud.log.roll", step.roll().targetNumber(), step.roll().roll());
        }
        return entry.targetNumber() == null || entry.roll() == null ? ""
              : text("GpuBoard.hud.log.roll", entry.targetNumber(), entry.roll());
    }

    /** The current attack card's extras (.ex): damage by location, missiles and ammunition. */
    private static List<String> extras(Entry entry, Step step) {
        List<String> extras = new ArrayList<>();
        if (step != null && step.attack() != null) {
            GpuReportLog.CombatEvent attack = step.attack();
            attack.impacts().forEach(impact -> extras.add(text("GpuBoard.hud.log.impact", impact.weight(),
                  impact.rear() ? impact.location() + "R" : impact.location())));
            if (attack.missileHits() != null && attack.missiles() > 0) {
                extras.add(text("GpuBoard.hud.log.missiles", attack.missileHits(), attack.missiles()));
            }
        }
        if (entry.ammo() != null) {
            extras.add(text("GpuBoard.hud.common.ammo", entry.ammo()));
        }
        return List.copyOf(extras);
    }

    /** A move the playback showed (Full log): its unit, its movement, MP and hexes, its start and end hex. */
    private Card card(GpuReportLog.MoveEvent move) {
        GpuBattleStatus.UnitStatus unit = GpuHudState.unit(inputs.frame().status(), move.entityId());
        String name = unit == null || unit.sensorContact() ? text("GpuBoard.hud.common.unidentified") : unit.name();
        String what = move.hexes() == 0 && move.mpUsed() == 0 ? text("GpuBoard.hud.common.heldPosition")
              : text("GpuBoard.hud.log.moveLine", move.type(), move.mpUsed(), move.hexes());
        String roll = move.from() == null || move.to() == null ? ""
              : text("GpuBoard.hud.log.moveHexes", move.from().getBoardNum(), move.to().getBoardNum(),
                    GpuHudKit.facing(move.facing()));
        return new Card(move, "", true, !shown.friendly().contains(move.entityId()),
              text("GpuBoard.hud.log.movementTitle", name), List.of(GpuEventLine.Part.plain(what)), roll, List.of(), "",
              List.of(), move.entityId(), move.equals(opened), move.equals(shown.match()));
    }

    /** An artillery round in the air (K13, as the Rounds in the Air window lists it): its turns left in the disc. */
    private Card card(RoundsInAirDialog.Row row) {
        return new Card(row, String.valueOf(row.turnsTilHit()), false, false, row.firedBy(),
              List.of(GpuEventLine.Part.plain(row.targetHex() + SEPARATOR + row.warhead())),
              row.landsIn() + SEPARATOR + row.player(), List.of(), "", List.of(), Entity.NONE, false,
              row.equals(shown.match()));
    }

    /** A card's actor (.ev, .ev.cur): the disc, the lines, the chevron, and the current card's extras and actions. */
    private UiButton cardActor(Card card) {
        UiButton button = new UiButton(ui, "hud-row");
        button.setName("log-card");
        button.pressed(card.current());
        button.pad(8, 10, 8, 8);
        Color side = card.foe() ? UiTheme.CORAL : UiTheme.MINT;
        Label number = ui.label(card.disc(), "hud-name", 11, side);
        number.setAlignment(Align.center);
        Container<Actor> disc = new Container<>(card.walk() ? ui.icon("walk", 13, side) : number);
        disc.setName("log-card-disc");
        disc.setBackground(ui.skin.getDrawable(card.foe() ? "disc-foe" : "disc"));
        button.add(disc).size(28).top();

        Table lines = new Table();
        lines.setName("log-card-lines");
        lines.top().left();
        boolean move = card.source() instanceof GpuReportLog.MoveEvent;
        Label who = ui.label(card.who(), "hud-name", 12.5f,
              card.matched() ? UiTheme.AMBER : move ? UiTheme.ACCENT : UiTheme.TEXT);
        who.setEllipsis(true);
        lines.add(who).growX().minWidth(0).left().minHeight(12.5f * LINE).row();
        lines.add(parts(card.what())).growX().minWidth(0).left().minHeight(11.5f * LINE).row();
        if (!card.roll().isEmpty()) {
            Label roll = ui.label(card.roll(), "hud-small", 11, UiTheme.MUTED);
            roll.setEllipsis(true);
            lines.add(roll).growX().minWidth(0).left().minHeight(11 * LINE).row();
        }
        if (card.current()) {
            current(card, lines);
        }
        button.add(lines).growX().minWidth(0).padLeft(10);
        button.add(ui.icon(card.current() ? "chevron-up" : "chevron-right", 12, UiTheme.MUTED)).padLeft(6);
        onChange(button, () -> click(card));
        return button;
    }

    /** The current card's extras (.ex), MegaMek's report text and its actions (.acts): Locate, and Replay. */
    private void current(Card card, Table lines) {
        if (!card.extras().isEmpty()) {
            // .ex wraps whole items: gaps of 10 between them and of 4 between their rows.
            HorizontalGroup extras = new HorizontalGroup().wrap().rowLeft().space(10).wrapSpace(4);
            extras.setName("log-card-extras");
            card.extras().forEach(extra -> extras.addActor(ui.label(extra, "hud-small", 11, UiTheme.ACCENT)));
            lines.add(extras).growX().minWidth(0).left().padTop(5).row();
        }
        if (!card.report().isBlank()) {
            Label report = ui.label(card.report(), "hud-small", 11, UiTheme.MUTED);
            report.setWrap(true);
            lines.add(report).growX().minWidth(0).left().padTop(5).row();
        }
        Table actions = new Table();
        actions.setName("log-card-actions");
        actions.right();
        if (card.unit() != Entity.NONE) {
            actions.add(action("locate", "GpuBoard.hud.common.locate", () -> locate(card.unit())));
        }
        Step step = card.steps().isEmpty() ? null : card.steps().getFirst();
        if (step != null && step.attack() != null) {
            actions.add(action("play", "GpuBoard.hud.common.replay", () -> state.history.replay(step))).padLeft(6);
        }
        if (actions.hasChildren()) {
            lines.add(actions).growX().right().padTop(6).row();
        }
    }

    /** A what line of parts in their own sizes and colors; the last ends in an ellipsis. */
    private Table parts(List<GpuEventLine.Part> parts) {
        Table line = new Table();
        line.setName("log-card-what");
        line.left();
        for (int index = 0; index < parts.size(); index++) {
            GpuEventLine.Part part = parts.get(index);
            Label label = ui.label(part.text(), "hud-small", part.size(), part.color());
            // The location is set apart as its small text is in the prototype (.ev .tx > span small).
            Cell<Label> cell = line.add(label).bottom().padLeft(part.apart() ? 3 : 0);
            if (index == parts.size() - 1) {
                label.setEllipsis(true);
                cell.growX().minWidth(0);
            }
        }
        return line;
    }

    /**
     * A mini button inside a card (.acts .b.mini). Its press and its change stay with it: neither bubbles up to the
     * card around it, whose click would review or collapse the card.
     */
    private UiButton action(String icon, String key, Runnable run) {
        UiButton button = ui.button("hud-mini", icon, text(key), null);
        button.setName("log-" + icon);
        onChange(button, run);
        button.addListener(event -> {
            if (event instanceof ChangeListener.ChangeEvent
                  || event instanceof InputEvent input && input.getType() == InputEvent.Type.touchDown) {
                event.stop();
            }
            return false;
        });
        return button;
    }

    /**
     * A card's click (K8): the card of a step reviews it, or collapses when it is the current card; any other card
     * opens to show Locate, or closes again.
     */
    private void click(Card card) {
        GpuPlaybackHistory history = state.history;
        if (!card.steps().isEmpty()) {
            opened = null;
            history.reviewTo(card.steps().contains(history.current()) ? history.current() : card.steps().getFirst());
            openedAt = history.current();
        } else if (!(card.source() instanceof RoundsInAirDialog.Row)) {
            opened = card.source().equals(opened) ? null : card.source();
        }
    }

    /** Locate (K9): centres the unit as its key does; an enemy is also inspected. */
    private void locate(int unit) {
        source.locateUnit(unit);
        GpuBattleStatus.UnitStatus status = GpuHudState.unit(inputs.frame().status(), unit);
        if (status != null && status.side() == GpuBattleStatus.Side.ENEMY) {
            state.inspected = unit;
        }
    }

    /** Moves the keyword match to the next or previous shown card with the selected keyword (N / Shift+N). */
    private void find(int direction) {
        if (keywords.isEmpty()) {
            return;
        }
        String word = keywords.get(keyword).toLowerCase(Locale.ROOT);
        List<Object> found = shownCards.stream().filter(card -> card.plain().toLowerCase(Locale.ROOT).contains(word))
              .map(Card::source).toList();
        int at = found.indexOf(match);
        match = found.isEmpty() ? null : found.get(at < 0 ? (direction > 0 ? 0 : found.size() - 1)
              : Math.floorMod(at + direction, found.size()));
        scrollToMatch = match != null;
    }

    /** The totals (K5): hits and misses of the attacks presented so far, and the round's heat alerts. */
    private void totals(GpuReportLog.Snapshot log) {
        int round = Math.max(1, log.round());
        List<GpuReportLog.CombatEvent> attacks = state.history.playedAttacks().stream()
              .filter(attack -> attack.round() == round
                    && (shown.filter() != MY_FORCE || shown.friendly().contains(attack.attackerId()))).toList();
        int hits = (int) attacks.stream().filter(GpuReportLog.CombatEvent::hit).count();
        int alerts = (int) log.entries().stream().filter(entry -> entry.round() == round && entry.heatAlert())
              .count();
        int[] values = { hits, attacks.size() - hits, alerts };
        String[] keys = { "GpuBoard.hud.log.hits", "GpuBoard.hud.log.misses", "GpuBoard.hud.log.heatAlerts" };
        for (int index = 0; index < values.length; index++) {
            totalNumbers.get(index).setText(String.valueOf(values[index]));
            totalCaptions.get(index).setText(UiTheme.upper(text(keys[index], values[index])));
            // The narrow log (310 at W <= 1350) has no room for "0 HEAT ALERTS" on one line, where the prototype's
            // inline text wraps: each caption goes below its number.
            Table cell = (Table) totals.getChildren().get(index);
            cell.clearChildren();
            cell.add(totalNumbers.get(index)).padRight(shown.narrow() ? 0 : 6).bottom();
            if (shown.narrow()) {
                cell.row();
            }
            cell.add(totalCaptions.get(index)).padBottom(shown.narrow() ? 0 : 3).bottom();
        }
        totalNumbers.get(2).setColor(alerts > 0 ? UiTheme.AMBER : Color.WHITE);
    }

    /** The shown cards for the clipboard: MegaMek's report text of each entry, the lines of the other cards. */
    private String copyText() {
        StringBuilder copy = new StringBuilder();
        for (Card card : shownCards) {
            if (card.source() instanceof Entry entry) {
                copy.append(entry.heading().isBlank() ? "" : entry.heading() + "\n").append(entry.text());
            } else {
                copy.append(card.who()).append(SEPARATOR).append(card.what().getFirst().text()).append(SEPARATOR)
                      .append(card.roll());
            }
            copy.append("\n\n");
        }
        return copy.toString();
    }
}
