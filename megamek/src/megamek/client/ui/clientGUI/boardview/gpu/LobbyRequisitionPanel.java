/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyRequisition;
import megamek.client.lobby.LobbyUnit;
import megamek.client.lobby.RequisitionCatalog;
import megamek.client.lobby.RequisitionCatalog.Category;
import megamek.client.lobby.RequisitionCatalog.Either;
import megamek.client.lobby.RequisitionCatalog.ExactType;
import megamek.client.lobby.RequisitionCatalog.Filters;
import megamek.client.lobby.RequisitionCatalog.TechBase;
import megamek.client.lobby.RequisitionCatalog.Weight;
import megamek.client.lobby.RequisitionDossier;
import megamek.client.lobby.RequisitionRow;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.lobby.RequisitionVisit;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFilterChip;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTabs;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiVirtualList;
import megamek.common.TechConstants;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitRole;
import megamek.logging.MMLogger;

/**
 * The lobby's Requisition view (gap analysis 7.1 to 7.5): the unit selector in the forces' place. Category tabs over
 * the search, the weight chips and "Filters", which opens a drawer in place with the rest of the filters, or shows
 * those at work as tags; the results, one slim row per unit, only those in view drawn; a dossier of the picked unit
 * beside them, sliding over them in a narrow window; and the adding strip: who the units are for, their skills and how
 * many, Add, the running Battle Value and tonnage of what this visit added, each with × to take it back, and the other
 * sources of units. Searches run away from the GPU thread ({@link RequisitionSearch}); every change to the game goes
 * to the window ({@link Actions}), which adds units as the unit selector dialog does.
 */
final class LobbyRequisitionPanel {
    private static final MMLogger LOGGER = MMLogger.create(LobbyRequisitionPanel.class);
    /** The pause after typing before the search runs. */
    static final float SEARCH_DELAY = .15f;
    /** How long Add reads "Added ✓". */
    private static final float ADDED_FOR = 1.2f;

    /** What the Requisition view asks of the screen and window. */
    interface Actions {
        /** Runs {@code query} away from the GPU thread and hands its result back on it. */
        void search(RequisitionSearch.Query query, Consumer<RequisitionSearch.Result> done);

        /** Adds {@code quantity} copies of {@code unit} for the player {@code ownerId} with {@code skills}. */
        void add(MekSummary unit, int quantity, int ownerId, RequisitionSkills skills);

        /** Takes the unit {@code id} back out of the game. */
        void remove(int id);

        /** Draws the sprites of {@code units} in the background. */
        void sprites(List<MekSummary> units);

        /** Runs one of the forces' other sources of units: a unit list file or the random army. */
        void source(LobbyForcesPanel.Command command);
    }

    private final UiKit kit;
    private final Actions actions;
    private final Function<String, Drawable> sprites;
    private final boolean motion;
    private final Table body = new Table() {
        @Override
        public void act(float delta) {
            super.act(delta);
            tick(delta);
        }
    };
    private final UiTabs tabs;
    private final UiTextField search;
    private final List<UiFilterChip> weightChips = new ArrayList<>();
    private final UiButton filtersButton;
    private final Container<Actor> drawerHolder = new Container<>();
    private final Label count;
    private final UiSegmented valueToggle;
    private final UiVirtualList<RequisitionRow> list;
    private final ScrollPane results;
    private final Label resultsNote;
    private final Table dossier = new Table();
    private final Split split;
    private final Table ownerGroup = new Table();
    private UiSelect owner;
    private final UiSegmented skillsMode;
    private final UiStepper gunnery;
    private final UiStepper piloting;
    private final Table fixedSkills = new Table();
    private final UiStepper quantity;
    private final UiButton add;
    private final Label totals;
    private final HorizontalGroup addedChips = new HorizontalGroup();

    private LobbyRequisition requisition = LobbyRequisition.EMPTY;
    private LobbyForces forces = LobbyForces.EMPTY;
    private Category category = Category.ALL;
    private Filters filters = Filters.NONE;
    private RequisitionSkills skills = RequisitionSkills.initial(false);
    private boolean skillsChosen;
    private RequisitionSearch.Query asked;
    private RequisitionSearch.Result result;
    private List<String> sources = List.of();
    private boolean drawerOpen;
    private boolean dossierOpen;
    private @Nullable MekSummary picked;
    private boolean showPv;
    private RequisitionVisit visit;
    private List<LobbyUnit> added = List.of();
    private int ownerId = -1;
    private float searchDue = -1;
    private float addedShown = -1;
    private float clock;
    private long openedAt;
    private boolean pickFirst;
    private final Set<MekSummary> spriteWanted = new LinkedHashSet<>();
    private final Set<String> spriteAsked = new java.util.HashSet<>();

    LobbyRequisitionPanel(UiKit kit, Actions actions, Function<String, Drawable> sprites, boolean motion) {
        this.kit = kit;
        this.actions = actions;
        this.sprites = sprites;
        this.motion = motion;
        body.top();
        body.setName("lobby-requisition");

        String[] categories = new String[Category.values().length];
        for (Category each : Category.values()) {
            categories[each.ordinal()] = Messages.getString(each.messageKey());
        }
        tabs = kit.tokenTabs(true, categories);
        UiKit.onChange(tabs, () -> category(Category.values()[tabs.getSelectedIndex()]));
        body.add(kit.scrollStrip(tabs)).growX().minWidth(0).pad(0, space("md"), space("sm"), space("md")).row();

        UiKit.TokenSearch searchBox = kit.tokenSearch(Messages.getString("Lobby.requisition.search"), true);
        search = searchBox.field;
        search.setFocusTraversal(false);
        UiKit.onChange(search, () -> searchDue = clock + SEARCH_DELAY);
        search.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.DOWN) {
                    enterResults();
                    return true;
                }
                return false;
            }
        });
        Table weights = new Table();
        for (Weight weight : Weight.values()) {
            UiFilterChip chip = kit.filterChip(Messages.getString(weight.messageKey()));
            UiKit.onChange(chip, this::weightsChanged);
            weightChips.add(chip);
            weights.add(chip).padLeft(space("xs"));
        }
        UiFilterChip.sameWidth(weightChips);
        filtersButton = kit.button("button-toggle" + UiTheme.COMPACT, "tune",
              Messages.getString("Lobby.requisition.filters"), null);
        filtersButton.setProgrammaticChangeEvents(false);
        kit.tip(filtersButton, true).getActor().setText(Messages.getString("Lobby.requisition.filtersTip"));
        UiKit.onChange(filtersButton, () -> drawer(filtersButton.isChecked()));
        Table toolbar = new Table();
        toolbar.add(searchBox).growX().minWidth(0);
        toolbar.add(weights).padLeft(space("sm"));
        toolbar.add(filtersButton).padLeft(space("sm"));
        body.add(toolbar).growX().minWidth(0).pad(0, space("md"), 0, space("md")).row();
        body.add(drawerHolder).growX().minWidth(0).pad(space("sm"), space("md"), 0, space("md")).row();
        drawerHolder.fill();

        count = kit.typeLabel("", "small", "text.secondary");
        valueToggle = kit.tokenSegmented(true, Messages.getString("Lobby.requisition.bv"),
              Messages.getString("Lobby.requisition.pv"));
        kit.tip(valueToggle, true).getActor().setText(Messages.getString("Lobby.requisition.valueTip"));
        UiKit.onChange(valueToggle, this::valueToggled);
        Table status = new Table();
        status.add(count).left().growX().minWidth(0);
        status.add(valueToggle).right();
        body.add(status).growX().pad(space("sm"), space("md"), space("xs"), space("md")).row();

        float rowHeight = size("control.lg");
        list = new UiVirtualList<>(kit, rowHeight, ResultRow::new, (row, item) -> ((ResultRow) row).bind(item));
        list.setName("lobby-requisition-results");
        list.onSelect((row, index) -> pick(row.unit(), true));
        list.onActivate((row, index) -> {
            pick(row.unit(), true);
            add();
        });
        list.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.RIGHT && picked != null) {
                    openDossier(true);
                    return true;
                }
                if (keycode == Input.Keys.LEFT && dossierOpen) {
                    openDossier(false);
                    return true;
                }
                return false;
            }
        });
        results = kit.scrollList(list, true, false);
        results.setFadeScrollBars(false);
        resultsNote = kit.typeLabel(Messages.getString("Lobby.requisition.loading"), "body", "text.muted");
        resultsNote.setAlignment(Align.center);
        resultsNote.setWrap(true);
        Stack resultsStack = new Stack();
        resultsStack.add(results);
        Container<Label> note = new Container<>(resultsNote);
        note.top().fillX().padTop(space("xl"));
        resultsStack.add(note);
        dossier.setBackground(kit.skin.getDrawable(UiTheme.TOKEN_PANEL));
        dossier.setName("lobby-requisition-dossier");
        dossier.setVisible(false);
        split = new Split(resultsStack, dossier);
        body.add(split).grow().minHeight(0).pad(0, space("md"), 0, space("md")).row();

        skillsMode = kit.tokenSegmented(true, Messages.getString("Lobby.requisition.skills.default"),
              Messages.getString("Lobby.requisition.skills.random"),
              Messages.getString("Lobby.requisition.skills.fixed"));
        kit.tip(skillsMode, true).getActor().setText(Messages.getString("Lobby.requisition.skillsTip"));
        UiKit.onChange(skillsMode, () -> {
            skillsChosen = true;
            skills(skills.withMode(RequisitionSkills.Mode.values()[skillsMode.getSelectedIndex()]));
        });
        gunnery = kit.stepper(RequisitionSkills.DEFAULT_GUNNERY, RequisitionSkills.MIN_SKILL,
              RequisitionSkills.MAX_SKILL);
        kit.tip(gunnery, true).getActor().setText(Messages.getString("Lobby.requisition.gunneryTip"));
        UiKit.onChange(gunnery, () -> skills(skills.withGunnery(gunnery.value())));
        piloting = kit.stepper(RequisitionSkills.DEFAULT_PILOTING, RequisitionSkills.MIN_SKILL,
              RequisitionSkills.MAX_SKILL);
        kit.tip(piloting, true).getActor().setText(Messages.getString("Lobby.requisition.pilotingTip"));
        UiKit.onChange(piloting, () -> skills(skills.withPiloting(piloting.value())));
        fixedSkills.add(caption("Lobby.requisition.gunnery")).padLeft(space("sm"));
        fixedSkills.add(gunnery);
        fixedSkills.add(caption("Lobby.requisition.piloting")).padLeft(space("xs"));
        fixedSkills.add(piloting);
        quantity = kit.stepper(1, 1, LobbyRequisitionController.MAX_QUANTITY);
        kit.tip(quantity, true).getActor().setText(Messages.getString("Lobby.requisition.quantityTip"));
        UiKit.onChange(quantity, this::refreshAdd);
        add = kit.button("button-primary" + UiTheme.COMPACT, "add", Messages.getString("Lobby.requisition.add"),
              null);
        add.setName("lobby-requisition-add");
        UiKit.onChange(add, this::add);
        totals = kit.typeLabel("", "small", "text.secondary");
        addedChips.space(space("xs")).wrap(false);
        body.add(strip()).growX().pad(space("sm"), space("md"), space("md"), space("md")).row();
        refreshFilters();
        refreshAdd();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    private Label caption(String key) {
        return kit.typeLabel(Messages.getString(key), "small", "text.secondary");
    }

    private void valueToggled() {
        showPv = valueToggle.getSelectedIndex() == 1;
        list.rebind();
        showDossier();
    }

    /** The view's body, which the centre shows under its header with Back. */
    Table body() {
        return body;
    }

    // ---- Opening and the lobby ----

    /** Opens the view afresh on the lobby's {@code current} forces: a new visit, the search field ready. */
    void opened(LobbyForces current) {
        openedAt = System.nanoTime();
        forces = current;
        visit = RequisitionVisit.of(current);
        added = List.of();
        refreshAdded();
        runSearch();
        Stage stage = body.getStage();
        if (stage != null) {
            stage.setKeyboardFocus(search);
        }
    }

    /** Shows the lobby's {@code next} rules and recipients, and the units this visit added among {@code current}. */
    void show(LobbyRequisition next, LobbyForces current) {
        boolean rulesChanged = !next.rules().equals(requisition.rules());
        boolean recipientsChanged = !next.recipients().equals(requisition.recipients());
        requisition = next;
        if (!skillsChosen) {
            skills = RequisitionSkills.initial(next.randomSkills()).withGunnery(skills.gunnery())
                  .withPiloting(skills.piloting());
            skillsMode.select(skills.mode().ordinal());
            fixedSkills.setVisible(skills.mode() == RequisitionSkills.Mode.FIXED);
        }
        if (recipientsChanged) {
            refreshOwners();
        }
        if (!current.equals(forces)) {
            forces = current;
            if (visit != null) {
                added = visit.added(current);
                refreshAdded();
            }
        }
        if (rulesChanged) {
            filters = filters.withLevels(Set.of());
            refreshFilters();
            if (visit != null) {
                runSearch();
            }
        }
    }

    /** For the developer switch: types {@code text} into the search. */
    void probeSearch(String text) {
        search.setText(text);
        searchDue = clock;
    }

    /** For the developer switch: opens the Filters drawer. */
    void probeFilters() {
        filtersButton.setChecked(true);
        drawer(true);
    }

    /** For the developer switch: picks the first result once there are results. */
    void probePick() {
        pickFirst = true;
        showResult();
    }

    /** One Escape step: shuts the drawer, then the dossier; true when one was open. */
    boolean back() {
        if (drawerOpen) {
            filtersButton.setChecked(false);
            drawer(false);
            return true;
        }
        if (dossierOpen) {
            openDossier(false);
            return true;
        }
        return false;
    }

    /** Draws the rows again, as after the sprite of {@code key} arrived. */
    void spriteArrived(String key) {
        list.rebind();
        if (dossierOpen && picked != null && spriteKey(picked).equals(key)) {
            showDossier();
        }
    }

    /** The sprite of {@code key} was let go, so it is asked for again when its row shows. */
    void spriteForgotten(String key) {
        spriteAsked.remove(key);
    }

    // ---- Searching ----

    private RequisitionSearch.Query query() {
        return new RequisitionSearch.Query(requisition.rules(), category, filters, search.getText());
    }

    private void runSearch() {
        searchDue = -1;
        RequisitionSearch.Query query = query();
        asked = query;
        actions.search(query, this::results);
    }

    /** GPU thread: shows a search's result, unless a newer search was asked for since. */
    private void results(RequisitionSearch.Result found) {
        if (!Objects.equals(found.query(), asked)) {
            return;
        }
        boolean first = result == null;
        result = found;
        if (first || found.loadMillis() > 0) {
            LOGGER.info("Requisition: the catalogue of {} units was read in {} ms; the view opened in {} ms",
                  found.total(), found.loadMillis(), (System.nanoTime() - openedAt) / 1_000_000);
        }
        LOGGER.info("Requisition: {} of {} units found in {} ms for \"{}\"", found.rows().size(), found.total(),
              found.millis(), found.query().search());
        if (!found.sources().equals(sources)) {
            sources = found.sources();
            if (drawerOpen) {
                fillDrawer();
            }
        }
        showResult();
    }

    private void showResult() {
        if (result == null) {
            return;
        }
        list.setItems(result.rows());
        results.setScrollY(0);
        results.layout();
        boolean none = result.rows().isEmpty();
        resultsNote.setText(Messages.getString(result.total() == 0 ? "Lobby.requisition.loading"
              : "Lobby.requisition.none"));
        resultsNote.setVisible(none);
        count.setText(Messages.getString("Lobby.requisition.count",
              NumberFormat.getIntegerInstance().format(result.rows().size())));
        if (picked != null) {
            int index = indexOf(picked);
            if (index >= 0) {
                list.select(index, false);
            }
        }
        if (pickFirst && !none) {
            pickFirst = false;
            list.select(0, true);
        }
    }

    private int indexOf(MekSummary unit) {
        List<RequisitionRow> rows = list.items();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).unit() == unit) {
                return i;
            }
        }
        return -1;
    }

    private void enterResults() {
        Stage stage = body.getStage();
        if (stage == null || list.items().isEmpty()) {
            return;
        }
        stage.setKeyboardFocus(list);
        if (list.selectedIndex() < 0) {
            list.select(0, true);
        }
    }

    private void tick(float delta) {
        clock += delta;
        if (searchDue >= 0 && clock >= searchDue) {
            runSearch();
        }
        if (addedShown >= 0 && clock >= addedShown) {
            addedShown = -1;
            refreshAdd();
        }
        for (RequisitionRow row : list.inView()) {
            String key = spriteKey(row.unit());
            if (sprites.apply(key) == null && spriteAsked.add(key)) {
                spriteWanted.add(row.unit());
            }
        }
        if (!spriteWanted.isEmpty()) {
            actions.sprites(List.copyOf(spriteWanted));
            spriteWanted.clear();
        }
    }

    /** The key of a catalogue unit's sprite. */
    static String spriteKey(MekSummary unit) {
        return unit.getName();
    }

    // ---- Filters ----

    private void category(Category next) {
        category = next;
        if (filters.type() != null && filters.type().category() != next) {
            filters = filters.withType(null);
        }
        refreshFilters();
        runSearch();
    }

    private void weightsChanged() {
        Set<Weight> lit = EnumSet.noneOf(Weight.class);
        for (int i = 0; i < weightChips.size(); i++) {
            if (weightChips.get(i).isChecked()) {
                lit.add(Weight.values()[i]);
            }
        }
        setFilters(filters.withWeights(lit));
    }

    private void setFilters(Filters next) {
        filters = next;
        refreshFilters();
        runSearch();
    }

    private void drawer(boolean open) {
        drawerOpen = open;
        refreshFilters();
    }

    /** Shows the filters: the weight chips, the button's count, and the drawer or the tags of those at work. */
    private void refreshFilters() {
        boolean weighs = Filters.weighs(category);
        for (int i = 0; i < weightChips.size(); i++) {
            UiFilterChip chip = weightChips.get(i);
            chip.setChecked(filters.weights().contains(Weight.values()[i]));
            chip.setDisabled(!weighs);
            chip.getColor().a = weighs ? 1 : .4f;
        }
        int at = filters.drawerCount();
        String label = Messages.getString("Lobby.requisition.filters");
        filtersButton.setText(kit.cased("button", (at > 0) ? label + " · " + at : label));
        filtersButton.setChecked(drawerOpen);
        if (drawerOpen) {
            fillDrawer();
        } else if (at > 0) {
            drawerHolder.setActor(tags());
        } else {
            drawerHolder.setActor(null);
        }
    }

    private void fillDrawer() {
        Table drawer = new Table();
        drawer.setName("lobby-requisition-drawer");
        drawer.top().left();
        drawer.defaults().left();
        List<UiFilterChip> chips = new ArrayList<>();

        List<ExactType> types = ExactType.of(category);
        if (types.size() > 1) {
            HorizontalGroup group = chipGroup();
            for (ExactType type : types) {
                UiFilterChip chip = chip(Messages.getString(type.messageKey()), filters.type() == type, chips);
                UiKit.onChange(chip, () -> setFilters(filters.withType(chip.isChecked() ? type : null)));
                group.addActor(chip);
            }
            drawerRow(drawer, "Lobby.requisition.drawer.type", group);
        }

        HorizontalGroup bases = chipGroup();
        for (TechBase base : TechBase.values()) {
            UiFilterChip chip = chip(Messages.getString(base.messageKey()), filters.bases().contains(base), chips);
            UiKit.onChange(chip, () -> setFilters(filters.withBases(toggled(filters.bases(), base,
                  chip.isChecked()))));
            bases.addActor(chip);
        }
        drawerRow(drawer, "Lobby.requisition.drawer.tech", bases);

        HorizontalGroup levels = chipGroup();
        for (int level : requisition.rules().levels()) {
            UiFilterChip chip = chip(TechConstants.getSimpleLevelName(level), filters.levels().contains(level), chips);
            UiKit.onChange(chip, () -> setFilters(filters.withLevels(toggled(filters.levels(), level,
                  chip.isChecked()))));
            levels.addActor(chip);
        }
        drawerRow(drawer, "Lobby.requisition.drawer.level", levels);

        if (!requisition.rules().canonOnly()) {
            drawerRow(drawer, "Lobby.requisition.drawer.canon", either(filters.canon(),
                  "Lobby.requisition.canon.yes", "Lobby.requisition.canon.no", chips,
                  next -> setFilters(filters.withCanon(next))));
        }
        drawerRow(drawer, "Lobby.requisition.drawer.recordSheet", either(filters.recordSheet(),
              "Lobby.requisition.recordSheet.yes", "Lobby.requisition.recordSheet.no", chips,
              next -> setFilters(filters.withRecordSheet(next))));

        List<UnitRole> roles = new ArrayList<>();
        for (UnitRole role : UnitRole.values()) {
            if (role.hasRole()) {
                roles.add(role);
            }
        }
        List<String> roleNames = new ArrayList<>();
        roleNames.add(Messages.getString("Lobby.requisition.any"));
        roles.forEach(role -> roleNames.add(role.toString()));
        UiSelect roleSelect = kit.tokenSelect(true, roleNames.toArray(String[]::new));
        UnitRole role = filters.roles().isEmpty() ? null : filters.roles().iterator().next();
        roleSelect.select(role == null ? 0 : roles.indexOf(role) + 1);
        UiKit.onChange(roleSelect, () -> {
            int index = roleSelect.getSelectedIndex();
            setFilters(filters.withRoles(index <= 0 ? Set.of() : Set.of(roles.get(index - 1))));
        });

        List<RequisitionCatalog.YearSpan> eras = requisition.eras();
        List<String> eraNames = new ArrayList<>();
        eraNames.add(Messages.getString("Lobby.requisition.any"));
        eras.forEach(era -> eraNames.add(era.name()));
        UiSelect eraSelect = kit.tokenSelect(true, eraNames.toArray(String[]::new));
        eraSelect.select(filters.era() == null ? 0 : Math.max(0, eras.indexOf(filters.era()) + 1));
        UiKit.onChange(eraSelect, () -> {
            int index = eraSelect.getSelectedIndex();
            setFilters(filters.withEra(index <= 0 ? null : eras.get(index - 1)));
        });

        List<String> sourceNames = new ArrayList<>();
        sourceNames.add(Messages.getString("Lobby.requisition.any"));
        sourceNames.addAll(sources);
        UiSelect sourceSelect = kit.tokenSelect(true, sourceNames.toArray(String[]::new));
        sourceSelect.select(filters.source() == null ? 0 : Math.max(0, sources.indexOf(filters.source()) + 1));
        UiKit.onChange(sourceSelect, () -> {
            int index = sourceSelect.getSelectedIndex();
            setFilters(filters.withSource(index <= 0 ? null : sources.get(index - 1)));
        });
        HorizontalGroup selects = chipGroup();
        selects.addActor(labelled("Lobby.requisition.drawer.role", roleSelect));
        selects.addActor(labelled("Lobby.requisition.drawer.era", eraSelect));
        selects.addActor(labelled("Lobby.requisition.drawer.source", sourceSelect));
        selects.space(space("lg"));
        drawer.add(selects).growX().minWidth(0).colspan(2).padTop(space("sm")).row();

        UiButton reset = kit.button("button-quiet" + UiTheme.COMPACT, "undo",
              Messages.getString("Lobby.requisition.reset"), null);
        reset.setDisabled(filters.drawerCount() == 0);
        UiKit.onChange(reset, () -> setFilters(filters.withoutDrawer()));
        drawer.add(reset).colspan(2).left().padTop(space("sm"));
        UiFilterChip.sameWidth(chips);
        drawerHolder.setActor(drawer);
    }

    private Table labelled(String key, UiSelect select) {
        Table cell = new Table();
        cell.add(kit.roleTypeLabel("overline", Messages.getString(key), "text.muted")).padRight(space("sm"));
        cell.add(select).minWidth(size("dialog.sm") / 3);
        return cell;
    }

    private HorizontalGroup chipGroup() {
        HorizontalGroup group = new HorizontalGroup();
        group.wrap(true).space(space("sm")).wrapSpace(space("xs")).rowLeft();
        return group;
    }

    private void drawerRow(Table drawer, String key, Actor content) {
        drawer.add(kit.roleTypeLabel("overline", Messages.getString(key), "text.muted")).padRight(space("md"))
              .padTop(space("xs")).top().minWidth(size("control.lg") * 2);
        drawer.add(content).growX().minWidth(0).padTop(space("xs")).row();
    }

    private UiFilterChip chip(String text, boolean on, List<UiFilterChip> group) {
        UiFilterChip chip = kit.filterChip(text);
        chip.setChecked(on);
        group.add(chip);
        return chip;
    }

    private HorizontalGroup either(Either value, String yesKey, String noKey, List<UiFilterChip> group,
          Consumer<Either> changed) {
        HorizontalGroup row = chipGroup();
        UiFilterChip yes = chip(Messages.getString(yesKey), value == Either.YES, group);
        UiFilterChip no = chip(Messages.getString(noKey), value == Either.NO, group);
        UiKit.onChange(yes, () -> changed.accept(yes.isChecked() ? Either.YES : Either.ANY));
        UiKit.onChange(no, () -> changed.accept(no.isChecked() ? Either.NO : Either.ANY));
        row.addActor(yes);
        row.addActor(no);
        return row;
    }

    private static <T> Set<T> toggled(Set<T> set, T value, boolean on) {
        Set<T> next = new LinkedHashSet<>(set);
        if (on) {
            next.add(value);
        } else {
            next.remove(value);
        }
        return next;
    }

    /** The drawer's filters at work as tags, each removed by a click, and Clear filters. */
    private Actor tags() {
        HorizontalGroup row = chipGroup();
        row.setName("lobby-requisition-tags");
        if (filters.type() != null) {
            row.addActor(tag(Messages.getString(filters.type().messageKey()), filters.withType(null)));
        }
        for (TechBase base : filters.bases()) {
            row.addActor(tag(Messages.getString(base.messageKey()), filters.withBases(toggled(filters.bases(), base,
                  false))));
        }
        for (int level : filters.levels()) {
            row.addActor(tag(TechConstants.getSimpleLevelName(level), filters.withLevels(toggled(filters.levels(),
                  level, false))));
        }
        for (UnitRole role : filters.roles()) {
            row.addActor(tag(role.toString(), filters.withRoles(Set.of())));
        }
        if (filters.canon() != Either.ANY) {
            row.addActor(tag(Messages.getString(filters.canon() == Either.YES ? "Lobby.requisition.canon.yes"
                  : "Lobby.requisition.canon.no"), filters.withCanon(Either.ANY)));
        }
        if (filters.recordSheet() != Either.ANY) {
            row.addActor(tag(Messages.getString(filters.recordSheet() == Either.YES
                  ? "Lobby.requisition.recordSheet.yes" : "Lobby.requisition.recordSheet.no"),
                  filters.withRecordSheet(Either.ANY)));
        }
        if (filters.era() != null) {
            row.addActor(tag(filters.era().name(), filters.withEra(null)));
        }
        if (filters.source() != null) {
            row.addActor(tag(filters.source(), filters.withSource(null)));
        }
        UiButton clear = kit.button("button-quiet" + UiTheme.COMPACT, null,
              Messages.getString("Lobby.requisition.clear"), null);
        UiKit.onChange(clear, () -> setFilters(filters.withoutDrawer()));
        row.addActor(clear);
        return row;
    }

    private Button tag(String text, Filters without) {
        Button tag = kit.actingChip(text, UiKit.Tone.NORMAL);
        float icon = size("icon.sm");
        tag.add(kit.icon("close", icon, UiTheme.tokenColor("text.secondary"))).size(icon).padLeft(space("xs"));
        kit.tip(tag, true).getActor().setText(Messages.getString("Lobby.requisition.removeFilter"));
        UiKit.onChange(tag, () -> setFilters(without));
        return tag;
    }

    // ---- The dossier ----

    private void pick(MekSummary unit, boolean open) {
        picked = unit;
        refreshAdd();
        if (open) {
            openDossier(true);
        } else {
            showDossier();
        }
    }

    private void openDossier(boolean open) {
        boolean opening = open && !dossierOpen;
        dossierOpen = open && picked != null;
        showDossier();
        split.opened(opening);
    }

    private void showDossier() {
        dossier.clearChildren();
        dossier.setVisible(dossierOpen && picked != null);
        if (!dossier.isVisible()) {
            return;
        }
        RequisitionDossier shown = RequisitionDossier.of(picked);
        dossier.top().pad(space("md"));

        Table head = new Table();
        Table names = new Table();
        names.left().defaults().left();
        Label name = kit.roleTypeLabel("title", shown.name(), "text.primary");
        name.setEllipsis(true);
        kit.tip(name, true).getActor().setText(shown.name());
        names.add(name).growX().minWidth(0).row();
        Label details = kit.typeLabel(shown.details(), "small", "text.secondary");
        details.setEllipsis(true);
        names.add(details).growX().minWidth(0).padTop(space("xxs"));
        head.add(names).growX().minWidth(0);
        Label value = kit.typeLabel(valueText(shown.bv(), shown.pv()), "title", "text.primary");
        head.add(value).right().padLeft(space("md"));
        UiButton close = kit.button("button-icon" + UiTheme.COMPACT, "close", null, null);
        kit.tip(close, true).getActor().setText(Messages.getString("Lobby.requisition.closeDossier"));
        UiKit.onChange(close, () -> openDossier(false));
        head.add(close).top().padLeft(space("xs"));
        dossier.add(head).growX().minWidth(0).row();

        UiButton addToForce = kit.button("button-primary" + UiTheme.COMPACT, "add",
              Messages.getString("Lobby.requisition.addToForce"), null);
        UiKit.onChange(addToForce, this::add);
        dossier.add(addToForce).left().padTop(space("sm")).row();

        Table content = new Table();
        content.top().left();
        Table overview = new Table();
        float spriteSize = size("control.lg") * 2;
        Drawable drawable = sprites.apply(spriteKey(picked));
        Actor sprite = (drawable == null) ? kit.icon("unit", size("icon.lg"), UiTheme.tokenColor("text.muted"))
              : new Image(drawable, Scaling.fit);
        overview.add(sprite).size(spriteSize).top().padRight(space("md"));
        HorizontalGroup stats = new HorizontalGroup();
        stats.wrap(true).space(space("lg")).wrapSpace(space("sm")).rowLeft();
        stats.addActor(stat("Lobby.requisition.stat.move", shown.movement()));
        stats.addActor(stat("Lobby.requisition.stat.armor", String.valueOf(shown.armor())));
        stats.addActor(stat("Lobby.requisition.stat.structure", String.valueOf(shown.structure())));
        stats.addActor(stat("Lobby.requisition.bv", NumberFormat.getIntegerInstance().format(skills.bv(shown.bv()))));
        stats.addActor(stat("Lobby.requisition.pv", String.valueOf(shown.pv())));
        if (!shown.cost().isEmpty()) {
            stats.addActor(stat("Lobby.requisition.stat.cost", shown.cost()));
        }
        overview.add(stats).growX().minWidth(0).top();
        content.add(overview).growX().minWidth(0).row();

        content.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.requisition.armament"), "text.muted"))
              .left().padTop(space("lg")).row();
        if (shown.weapons().isEmpty()) {
            content.add(kit.typeLabel(Messages.getString("Lobby.requisition.noWeapons"), "body", "text.muted"))
                  .left().padTop(space("xs")).row();
        }
        for (RequisitionDossier.Line line : shown.weapons()) {
            Table row = new Table();
            row.left();
            row.add(kit.typeLabel(line.count() + " ×", "body", "text.secondary")).width(size("control.md")).left();
            Label weapon = kit.typeLabel(line.name(), "body", "text.primary");
            weapon.setEllipsis(true);
            row.add(weapon).growX().minWidth(0);
            content.add(row).growX().minWidth(0).padTop(space("xxs")).row();
        }

        content.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.requisition.facts"), "text.muted"))
              .left().padTop(space("lg")).row();
        Table facts = new Table();
        facts.left().defaults().left();
        for (RequisitionDossier.Fact fact : shown.facts()) {
            facts.add(kit.typeLabel(fact.label(), "body", "text.secondary")).padRight(space("md")).top()
                  .padTop(space("xxs"));
            Label factValue = kit.typeLabel(fact.value(), "body", "text.primary");
            factValue.setWrap(true);
            facts.add(factValue).growX().minWidth(0).padTop(space("xxs")).row();
        }
        content.add(facts).growX().minWidth(0).padTop(space("xs")).row();
        ScrollPane scroll = kit.scrollList(content, true, false);
        dossier.add(scroll).grow().minHeight(0).padTop(space("md"));
    }

    private Table stat(String key, String value) {
        Table stat = new Table();
        stat.left().defaults().left();
        stat.add(kit.roleTypeLabel("overline", Messages.getString(key), "text.muted")).row();
        stat.add(kit.typeLabel(value, "body.strong", "text.primary"));
        return stat;
    }

    private String valueText(int bv, int pv) {
        return showPv ? Messages.getString("Lobby.requisition.pvValue", pv)
              : Messages.getString("Lobby.requisition.bvValue", NumberFormat.getIntegerInstance()
              .format(skills.bv(bv)));
    }

    // ---- The adding strip ----

    private Table strip() {
        Table strip = new Table();
        strip.setName("lobby-requisition-strip");
        strip.add(kit.solid("border.subtle")).growX().height(size("border")).colspan(2).padBottom(space("sm")).row();
        HorizontalGroup controls = chipGroup();
        controls.space(space("lg"));
        controls.addActor(ownerGroup);
        Table skillsGroup = new Table();
        skillsGroup.add(caption("Lobby.requisition.skills")).padRight(space("sm"));
        skillsGroup.add(skillsMode);
        skillsGroup.add(fixedSkills);
        fixedSkills.setVisible(false);
        controls.addActor(skillsGroup);
        Table quantityGroup = new Table();
        quantityGroup.add(caption("Lobby.requisition.quantity")).padRight(space("xs"));
        quantityGroup.add(quantity);
        controls.addActor(quantityGroup);
        strip.add(controls).growX().minWidth(0).left();
        strip.add(add).right().bottom().width(kit.faceWidth(add, Messages.getString("Lobby.requisition.add"),
              Messages.getString("Lobby.requisition.addMany", LobbyRequisitionController.MAX_QUANTITY),
              Messages.getString("Lobby.requisition.added"))).padLeft(space("md")).row();

        Table addedRow = new Table();
        addedRow.add(totals).left().padRight(space("sm"));
        ScrollPane chips = kit.scrollStrip(addedChips);
        addedRow.add(chips).growX().minWidth(0);
        UiButton loadList = kit.button("button-quiet" + UiTheme.COMPACT, null,
              Messages.getString("Lobby.requisition.source.list"), null);
        kit.tip(loadList, true).getActor().setText(Messages.getString("Lobby.requisition.source.listTip"));
        UiKit.onChange(loadList, () -> actions.source(LobbyForcesPanel.Command.LOAD_LIST));
        UiButton randomArmy = kit.button("button-quiet" + UiTheme.COMPACT, null,
              Messages.getString("Lobby.requisition.source.random"), null);
        kit.tip(randomArmy, true).getActor().setText(Messages.getString("Lobby.forces.randomArmyTip"));
        UiKit.onChange(randomArmy, () -> actions.source(LobbyForcesPanel.Command.RANDOM_ARMY));
        addedRow.add(caption("Lobby.requisition.sources")).padLeft(space("md"));
        addedRow.add(loadList).padLeft(space("xs"));
        addedRow.add(randomArmy).padLeft(space("xs"));
        strip.add(addedRow).growX().minWidth(0).colspan(2).padTop(space("sm"));
        return strip;
    }

    private void refreshOwners() {
        ownerGroup.clearChildren();
        owner = null;
        List<LobbyRequisition.Recipient> recipients = requisition.recipients();
        if (recipients.stream().noneMatch(recipient -> recipient.id() == ownerId)) {
            ownerId = recipients.isEmpty() ? -1 : recipients.get(0).id();
        }
        if (recipients.size() < 2) {
            return;
        }
        String[] names = recipients.stream().map(recipient -> recipient.self()
              ? Messages.getString("Lobby.requisition.you", recipient.name()) : recipient.name())
              .toArray(String[]::new);
        UiSelect select = kit.tokenSelect(true, names);
        int index = 0;
        for (int i = 0; i < recipients.size(); i++) {
            if (recipients.get(i).id() == ownerId) {
                index = i;
            }
        }
        select.select(index);
        UiKit.onChange(select, () -> ownerId = recipients.get(Math.max(0, select.getSelectedIndex())).id());
        kit.tip(select, true).getActor().setText(Messages.getString("Lobby.requisition.ownerTip"));
        ownerGroup.add(caption("Lobby.requisition.owner")).padRight(space("sm"));
        ownerGroup.add(select).minWidth(size("dialog.sm") / 3);
        owner = select;
    }

    private void skills(RequisitionSkills next) {
        skills = next;
        fixedSkills.setVisible(skills.mode() == RequisitionSkills.Mode.FIXED);
        list.rebind();
        showDossier();
    }

    private void refreshAdd() {
        int copies = quantity.value();
        if (addedShown < 0) {
            add.setText(kit.cased("button", copies > 1
                  ? Messages.getString("Lobby.requisition.addMany", copies)
                  : Messages.getString("Lobby.requisition.add")));
        }
        add.setDisabled(picked == null || requisition.recipients().isEmpty());
    }

    private void add() {
        if (picked == null || requisition.recipients().isEmpty() || visit == null) {
            return;
        }
        int to = (ownerId < 0) ? requisition.recipients().get(0).id() : ownerId;
        visit.sentFor(to);
        requisition.recipients().stream().filter(LobbyRequisition.Recipient::self)
              .forEach(self -> visit.sentFor(self.id()));
        actions.add(picked, quantity.value(), to, skills);
        add.setText(kit.cased("button", Messages.getString("Lobby.requisition.added")));
        addedShown = clock + ADDED_FOR;
    }

    private void refreshAdded() {
        RequisitionVisit.Totals sum = RequisitionVisit.totals(added);
        totals.setText(sum.units() == 0 ? Messages.getString("Lobby.requisition.nothingAdded")
              : Messages.getString("Lobby.requisition.totals", sum.units(),
              NumberFormat.getIntegerInstance().format(sum.bv()), RequisitionRow.tons(sum.tons())));
        addedChips.clearChildren();
        for (LobbyUnit unit : added) {
            Button chip = kit.actingChip(unit.name(), UiKit.Tone.NORMAL);
            float icon = size("icon.sm");
            chip.add(kit.icon("close", icon, UiTheme.tokenColor("text.secondary"))).size(icon).padLeft(space("xs"));
            kit.tip(chip, true).getActor().setText(Messages.getString("Lobby.requisition.undo", unit.name()));
            UiKit.onChange(chip, () -> {
                visit.forget(unit.id());
                actions.remove(unit.id());
                added = visit.added(forces);
                refreshAdded();
            });
            addedChips.addActor(chip);
        }
    }

    // ---- Rows ----

    /** One result: the sprite, the name over tonnage · type · tech · year, and the Battle or Point Value. */
    private final class ResultRow extends Table {
        private final Container<Actor> sprite = new Container<>();
        private final Label name = kit.roleTypeLabel("name", "", "text.primary");
        private final Label details = kit.typeLabel("", "small", "text.secondary");
        private final Label value = kit.typeLabel("", "body.strong", "text.primary");
        private final Actor placeholder = kit.icon("unit", size("icon.md"), UiTheme.tokenColor("text.muted"));
        private final Image image = new Image((Drawable) null, Scaling.fit);

        ResultRow() {
            float spriteSize = size("control.md");
            pad(0, space("md"), 0, space("md"));
            add(sprite).size(spriteSize).padRight(space("sm"));
            Table lines = new Table();
            lines.left().defaults().left();
            name.setEllipsis(true);
            details.setEllipsis(true);
            lines.add(name).growX().minWidth(0).row();
            lines.add(details).growX().minWidth(0);
            add(lines).growX().minWidth(0);
            add(value).right().padLeft(space("sm"));
        }

        void bind(RequisitionRow row) {
            name.setText(row.name());
            details.setText(row.details());
            value.setText(showPv ? String.valueOf(row.pv())
                  : NumberFormat.getIntegerInstance().format(skills.bv(row.bv())));
            Drawable drawable = sprites.apply(spriteKey(row.unit()));
            if (drawable == null) {
                sprite.setActor(placeholder);
                sprite.size(size("icon.md"));
            } else {
                image.setDrawable(drawable);
                sprite.setActor(image);
                sprite.fill();
                sprite.size(size("control.md"));
            }
        }
    }

    // ---- The results and the dossier ----

    /**
     * The results with the dossier beside them, or over all but their leading edge when the two do not fit
     * ({@link RequisitionLayout}); the dossier slides in over them.
     */
    private final class Split extends WidgetGroup {
        private final Actor resultsSide;
        private final Table dossierSide;
        private final Image shadow;
        private boolean slideIn;

        Split(Actor resultsSide, Table dossierSide) {
            this.resultsSide = resultsSide;
            this.dossierSide = dossierSide;
            shadow = new Image(kit.skin.getDrawable("shadow"));
            shadow.setVisible(false);
            addActor(resultsSide);
            addActor(shadow);
            addActor(dossierSide);
        }

        void opened(boolean opening) {
            slideIn = opening;
            invalidate();
        }

        @Override
        public float getPrefWidth() {
            return 0;
        }

        @Override
        public float getPrefHeight() {
            return 0;
        }

        @Override
        public void layout() {
            float gap = space("md");
            RequisitionLayout.Dock dock = RequisitionLayout.dock(getWidth(), dossierSide.isVisible(), gap);
            resultsSide.setBounds(0, 0, dock.resultsWidth(), getHeight());
            if (!dossierSide.isVisible()) {
                shadow.setVisible(false);
                return;
            }
            float target = dock.dossierX();
            boolean sliding = dossierSide.hasActions();
            dossierSide.setSize(dock.dossierWidth(), getHeight());
            if (slideIn && dock.overlay() && motion) {
                dossierSide.setPosition(getWidth(), 0);
                dossierSide.addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.moveTo(target, 0,
                      DesignTokens.shipped().duration("medium") / 1000f, Interpolation.pow2Out));
            } else if (!sliding) {
                dossierSide.setPosition(target, 0);
            }
            slideIn = false;
            dossierSide.validate();
            shadow.setVisible(dock.overlay());
            float blur = space("md");
            shadow.setBounds(target - blur, -blur, dock.dossierWidth() + 2 * blur, getHeight() + 2 * blur);
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            if (shadow.isVisible()) {
                float blur = space("md");
                shadow.setX(dossierSide.getX() - blur);
            }
        }
    }
}
