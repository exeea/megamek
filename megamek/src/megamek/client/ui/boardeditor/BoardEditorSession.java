/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.function.Consumer;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

import megamek.client.ui.clientGUI.BoardFileFilter;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** Standalone editor authority, owned by the EDT. No BoardView, Swing editor panel or client is created. */
public final class BoardEditorSession {
    /** In toolbar, menu and key order: shape the ground, paint it, then clean up. */
    public enum Tool { SELECT, SCULPT, PAINT, ERASE }
    public enum Action {
        TOOL, COMPONENT, BRUSH, ADD_COMPONENT, REMOVE_COMPONENT, TERRAIN, REMOVE_TERRAIN, EDGE, AUTO_EDGES,
        ELEVATION, ELEVATOR, THEME, VARIANT, BLEND, ASSET, SELECT_OBJECT, OBJECT_VALUE, DUPLICATE_OBJECT, REMOVE_OBJECT, REORDER_OBJECT,
        COPY, PASTE, UNDO, REDO, NEW, OPEN, SAVE, SAVE_AS, SELECT_AT, MAP_THEME,
        CHOOSE_BRUSH, BRUSH_VALUE, SAMPLE, CLEAR_SELECTION, DELETE_SELECTION, GROUP, UNGROUP, GROUP_VALUE,
        /** Moves a {@link Level} of the inspected hex (target: its key) to an absolute level (value). */
        HEIGHT,
        /**
         * Puts the armed object brush in the inspected hex at "x,y,level" (value): on the target support's surface, or at
         * that fixed level when the target is empty.
         */
        PLACE,
        /**
         * Turns the selected bridge's piers ({@link HexAppearance#PILLARS}) on or off (value "true"/"false") for its whole
         * {@link megamek.common.board.BridgeSpan}, in one undo step; off leaves it a built bridge.
         */
        PILLARS,
        /**
         * Makes the selected bridge's whole {@link megamek.common.board.BridgeSpan} built or natural (value "built" or
         * "natural") in one undo step; a natural bridge has no piers, a built one keeps its Pillars toggle.
         */
        BRIDGE_TYPE
    }
    /** Text values are parsed and validated here, never by a view that also owns game state. */
    public record Command(Action action, String target, String value) {
        public Command(Action action) { this(action, "", ""); }
        public Command(Action action, String value) { this(action, "", value); }
    }
    public record Property(String terrain, int value, int exits, boolean explicit) { }
    public record Selection(Coords coords, String object) { }
    /** How a box or a Ctrl-click changes the selection: replace it, add to it (Shift) or remove from it (Ctrl). */
    public enum SelectMode { REPLACE, ADD, REMOVE }
    /** A rules or placement problem of one hex; {@code object} is the placed object it concerns, or "". */
    public record Issue(Coords coords, String object, String text) { }
    /**
     * One height of the inspected hex, at its nominal level by the shared support rule ({@link BoardEditorSnapping#level}).
     * {@code key} is "ground" (the hex level, the water surface on a water hex), "water" (its bed), a blueprint component
     * with a height field (a bridge's deck, a structure's top, a canopy) or, when {@code object}, a placed object's id.
     * {@code receiver} is the support an object placed at this level uses, or "" when nothing is placed on it. A level
     * whose {@code min} equals its {@code max} cannot be moved.
     */
    public record Level(String key, boolean object, double level, double min, double max, String receiver) { }
    public record Brush(String key, String label, String component, int elevation, String theme,
          List<Property> properties, Map<String, HexAppearance> appearance, List<BoardDecoration> objects, Coords sampledHex,
          boolean elevationEnabled, boolean themeEnabled, boolean precision, boolean magnetic,
          LevelSculpt.Mode sculpt, boolean slope) {
        /** A hex stamp's objects are contents of the stamp, not an individually placeable object brush. */
        public BoardDecoration object() { return sampledHex == null && !objects.isEmpty() ? objects.getFirst() : null; }
    }
    /**
     * Immutable data published at the EDT/render boundary; it contains no mutable Hex or Board. {@code groups} lists
     * the distinct groups of the selected objects, with "" for an ungrouped selected object. {@code groupSizes} gives
     * the board-wide member count of each group with a member in the selected hex. {@code issues} lists the
     * document's rules and placement problems in hex order, as of the last committed edit. {@code levels} are the
     * selected hex's heights, bottom to top.
     */
    public record Snapshot(long revision, String title, boolean dirty, boolean canUndo, boolean canRedo,
          Tool tool, String component, String asset, int brush, Coords selected, int elevation, String theme,
          List<Property> properties, Map<String, HexAppearance> appearance, List<BoardDecoration> objects,
          String object, String message, int width, int height, Brush activeBrush, List<Selection> selection,
          List<Coords> movePreview, List<String> groups, Map<String, Integer> groupSizes, List<Issue> issues,
          List<Level> levels) {
        public Property property(String name) { return properties.stream().filter(p -> p.terrain().equals(name)).findFirst().orElse(null); }
        /** The group the whole selection forms, or "" when it is not exactly one group. */
        public String group() { return wholeGroup(groups, selection.size()); }
        /** Whether the object belongs to a group that still has another member; a lone member acts ungrouped. */
        public boolean grouped(BoardDecoration object) { return object.group() != null && groupSizes.getOrDefault(object.group(), 0) > 1; }
    }

    /** Selections always hold whole groups, except one drilled-in member, so several objects of one group are all of it. */
    private static String wholeGroup(List<String> groups, int selected) {
        return groups.size() == 1 && selected > 1 ? groups.getFirst() : "";
    }

    /**
     * Turns legacy scenery into objects. The 3D board supplies it; this session stays free of the renderer and its
     * tileset, so its own default decodes nothing.
     */
    public interface LegacyDecoder {
        /**
         * Replaces a legacy board's decodable scenery tokens with objects and groups, and marks it native. Returns what it
         * could not decode exactly (an emblem set with missing pieces), as issues on the objects it placed.
         */
        List<Issue> importBoard(Board board);
        /** The members of a stamp painted on {@code hex} at {@code at}, with fresh ids and one fresh group. */
        List<BoardDecoration> stamp(String layout, Hex hex, Coords at);
        /**
         * Gives every bridge hex that stores no type ({@link HexAppearance#bridgeBuilt}) the type the 3D board's legacy
         * decode draws it with, so an opened board stores the type of each of its bridges.
         */
        default void typeBridges(Board board) { }
    }
    private static final LegacyDecoder NO_DECODER = new LegacyDecoder() {
        @Override public List<Issue> importBoard(Board board) { return List.of(); }
        @Override public List<BoardDecoration> stamp(String layout, Hex hex, Coords at) {
            throw new IllegalArgumentException("Stamps need the 3D board.");
        }
    };
    private record Edit(Map<Coords, Hex> before, Map<Coords, Hex> after, long beforeId, long afterId) { }
    private final Game game = new Game();
    private final BoardEditorBlueprint blueprint;
    private final LegacyDecoder decoder;
    private final ArrayDeque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final Map<Coords, Hex> stroke = new LinkedHashMap<>();
    private final java.util.Set<Coords> painted = new java.util.HashSet<>();
    private Tool tool = Tool.SELECT;
    private String component = "ground", selectedObject = "", message = "Select a hex to edit it.";
    private String brushComponent = "ground", brushKey = "ground/clear", brushLabel = "Clear ground";
    private Hex paintTemplate = new Hex(0);
    /** Non-null for a complete hex stamp; the captured template remains independent of this source hex. */
    private Coords sampledHex;
    private boolean brushElevation, brushTheme, precision;
    private boolean magnetic = true;
    private final Set<Selection> selection = new LinkedHashSet<>();
    private final Map<Selection, BoardDecoration> movingObjects = new LinkedHashMap<>();
    /** A press on a member of a wholly selected group; the pointer release selects it alone unless the press dragged. */
    private List<Selection> pendingDrill;
    /** Members per group, derived from the board for the committed document version {@code groupsId}. */
    private Map<String, List<Selection>> groups = Map.of();
    private long groupsId = -1;
    private Coords dragOrigin, moveTarget, routePaintFrom;
    private double dragX, dragY;
    private Coords selected;
    private int brush = 1;
    private Path path;
    /** The .board2 next to an imported legacy file: the import's name and Save's default while {@code path} is null. */
    private Path suggested;
    private Hex clipboard;
    private long revision, currentId, savedId, nextId;
    private Set<String> savedTags = Set.of();
    private boolean savedRoadsAutoExit;
    private boolean placed;
    private boolean continuousEdit;
    private LevelSculpt.Mode sculpt = LevelSculpt.Mode.RAISE;
    private boolean slope = true;
    /** Fixed by the first event of a sculpt stroke: the level-mode target and the (Ctrl-inverted) step. */
    private Integer sculptLevel;
    private int sculptStep;
    /** A whole group's members as a relative group edit found them; a live value drag transforms from them. */
    private Map<Selection, BoardDecoration> groupBase = Map.of();
    /** Problems per hex, re-validated for the hexes of every committed edit, undo and redo; never during a stroke. */
    private final Map<Coords, List<Issue>> issues = new java.util.HashMap<>();
    private List<Issue> issueList = List.of();
    /** What the last legacy import could not decode exactly; each stays listed while its object stays in its hex. */
    private List<Issue> importIssues = List.of();
    /** Whether an object model file exists, per asset, so validation reads the disk once per asset. */
    private final Map<String, Boolean> modelFiles = new java.util.HashMap<>();

    public BoardEditorSession() { this(BoardEditorBlueprint.get()); }
    public BoardEditorSession(BoardEditorBlueprint blueprint) { this(blueprint, NO_DECODER); }
    public BoardEditorSession(BoardEditorBlueprint blueprint, LegacyDecoder decoder) {
        this.blueprint = blueprint;
        this.decoder = decoder;
        game.setPhase(GamePhase.LOUNGE);
        replace(Board.createEmptyBoard(16, 17), null);
    }
    public Game game() { return game; }
    public Board board() { return game.getBoard(); }
    public Path path() { return path; }
    public boolean dirty() {
        return currentId != savedId || !stroke.isEmpty() || !savedTags.equals(board().getTags())
              || savedRoadsAutoExit != board().getRoadsAutoExit();
    }
    public String title() {
        return (dirty() ? "* " : "") + (path != null ? path.getFileName().toString()
              : suggested != null ? suggested.getFileName() + " (imported, unsaved)" : "Untitled board");
    }

    public Snapshot snapshot() {
        Hex hex = selected == null ? null : board().getHex(selected);
        Brush active = new Brush(brushKey, brushLabel, brushComponent, paintTemplate.getLevel(),
              paintTemplate.getTheme() == null ? "" : paintTemplate.getTheme(), properties(paintTemplate),
              paintTemplate.getAppearance(), paintTemplate.getDecorations(), sampledHex,
              brushElevation, brushTheme, precision, magnetic, sculpt, slope);
        BoardDecoration prototype = active.object();
        return new Snapshot(revision, title(), dirty(), !undo.isEmpty(), !redo.isEmpty(), tool, component,
              prototype == null ? "" : prototype.asset(), brush,
              selected, hex == null ? 0 : hex.getLevel(), hex == null || hex.getTheme() == null ? "" : hex.getTheme(),
              properties(hex), hex == null ? Map.of() : hex.getAppearance(), hex == null ? List.of() : contentsOrder(hex),
              selectedObject, message, board().getWidth(), board().getHeight(), active, List.copyOf(selection), movePreview(),
              selectionGroups(), groupSizes(hex), issueList, levels(hex));
    }

    /**
     * The heights of {@code hex} bottom to top: its ground, the water bed, each height field of a present component
     * (bounded by the field's range) and each placed object; a decal lies on its support and cannot be moved.
     */
    private List<Level> levels(Hex hex) {
        if (hex == null) { return List.of(); }
        int ground = hex.getLevel();
        List<Level> levels = new ArrayList<>();
        levels.add(new Level("ground", false, ground, -Double.MAX_VALUE, Double.MAX_VALUE, "ground"));
        if (hex.containsTerrain(Terrains.WATER)) {
            levels.add(new Level("water", false, ground - hex.terrainLevel(Terrains.WATER), -Double.MAX_VALUE, ground, ""));
        }
        for (var component : blueprint.components()) {
            for (var field : component.fields()) {
                Terrain value = hex.getTerrain(Terrains.getType(field.terrain()));
                if (!field.height() || value == null) { continue; }
                // The structures objects stand on; a canopy holds none.
                String receiver = Set.of("bridge", "building", "industrial", "fuelTank").contains(component.receiver())
                      ? component.receiver() : "";
                levels.add(new Level(component.id(), false, ground + value.getLevel(), ground + field.min(),
                      ground + Math.max(field.max(), value.getLevel()), receiver));
            }
        }
        for (BoardDecoration object : hex.getDecorations()) {
            double at = BoardEditorSnapping.level(hex, object.placement());
            // An object whose support is missing shows at the ground, where the editor draws it.
            if (Double.isNaN(at)) { at = ground + object.placement().offset(); }
            boolean fixed = object.kind().equals("decal");
            levels.add(new Level(object.id(), true, at, fixed ? at : Math.min(at, BoardEditorSnapping.lowestLevel(hex)),
                  fixed ? at : Double.MAX_VALUE, ""));
        }
        levels.sort(Comparator.comparingDouble(Level::level));
        return List.copyOf(levels);
    }

    /**
     * Board-wide member counts of the groups in {@code hex}. A stroke in progress reuses the last committed index, since
     * moves keep membership and a drag's events should not rescan the board.
     */
    private Map<String, Integer> groupSizes(Hex hex) {
        if (hex == null || hex.getDecorations().stream().allMatch(object -> object.group() == null)) { return Map.of(); }
        var index = stroke.isEmpty() ? groups() : groups;
        Map<String, Integer> sizes = new LinkedHashMap<>();
        hex.getDecorations().stream().map(BoardDecoration::group).filter(java.util.Objects::nonNull)
              .forEach(group -> sizes.put(group, index.getOrDefault(group, List.of()).size()));
        return Map.copyOf(sizes);
    }

    private static List<Property> properties(Hex hex) {
        List<Property> properties = new ArrayList<>();
        if (hex != null) {
            for (int type : hex.getTerrainTypes()) {
                Terrain terrain = hex.getTerrain(type);
                properties.add(new Property(Terrains.getName(type), terrain.getLevel(), terrain.getExits(), terrain.hasExitsSpecified()));
            }
            properties.sort(Comparator.comparing(Property::terrain));
        }
        return List.copyOf(properties);
    }

    private void replace(Board board, Path path) { replace(board, path, List.of()); }

    private void replace(Board board, Path path, List<Issue> imported) {
        this.path = path; suggested = null; importIssues = imported;
        undo.clear(); redo.clear(); stroke.clear(); painted.clear();
        selected = null; selectedObject = ""; placed = false;
        selection.clear(); movingObjects.clear(); dragOrigin = moveTarget = null; pendingDrill = null;
        currentId = savedId = ++nextId;
        // Every editor document is a .board2 document, so road level 2 is the Alley finish everywhere in it.
        board.setNativeFormat(true);
        game.setBoard(board);
        savedTags = Set.copyOf(board.getTags());
        savedRoadsAutoExit = board.getRoadsAutoExit();
        validateAll();
        revision++;
    }

    public void open(Path file) throws IOException {
        Board loaded = BoardFile.read(file); // Failure leaves the document, history and path intact.
        boolean legacy = !BoardFile.isNativeName(file.toString());
        // Import types every bridge; a native board's untyped bridges take the type they are drawn with.
        if (!legacy) { decoder.typeBridges(loaded); }
        replace(loaded, legacy ? null : file, legacy ? List.copyOf(decoder.importBoard(loaded)) : List.of());
        if (!legacy) { message = "Opened " + file.getFileName(); return; }
        suggested = file.resolveSibling(BoardFile.withoutExtension(file.getFileName().toString()) + BoardFile.EXTENSION);
        long objects = 0;
        Set<String> groups = new java.util.HashSet<>();
        for (int y = 0; y < loaded.getHeight(); y++) {
            for (int x = 0; x < loaded.getWidth(); x++) {
                for (BoardDecoration object : loaded.getHex(x, y).getDecorations()) {
                    objects++;
                    if (object.group() != null) { groups.add(object.group()); }
                }
            }
        }
        message = "Imported " + file.getFileName() + ": " + objects + (objects == 1 ? " object" : " objects") + " in "
              + groups.size() + (groups.size() == 1 ? " group" : " groups")
              + ". Save creates a .board2 file; the original is preserved.";
    }

    public void save(Path file) throws IOException {
        finishStroke();
        BoardFile.save(board(), file);
        path = file;
        savedId = currentId;
        savedTags = Set.copyOf(board().getTags());
        savedRoadsAutoExit = board().getRoadsAutoExit();
        message = "Saved " + file.getFileName();
        revision++;
    }

    public void command(Command command, Window owner) {
        command(command, owner, false);
    }

    /** A slider or section drag shares the same undo boundary as a board brush stroke. */
    public void command(Command command, Window owner, boolean continuous) {
        if (!continuous) { finishStroke(); }
        continuousEdit = continuous;
        try {
            switch (command.action()) {
                case TOOL -> tool = Tool.valueOf(command.value());
                case COMPONENT -> {
                    blueprint.component(command.value()); component = command.value(); selectedObject = "";
                    selection.clear(); if (selected != null) { selection.add(new Selection(selected, "")); }
                }
                case BRUSH -> brush = Math.max(1, Math.min(20, Integer.parseInt(command.value())));
                case CHOOSE_BRUSH -> chooseBrush(command.target(), command.value());
                case BRUSH_VALUE -> configureBrush(command.target(), command.value());
                case SAMPLE -> sampleBrush();
                case CLEAR_SELECTION -> { selection.clear(); selectedObject = ""; }
                case DELETE_SELECTION -> deleteSelection();
                case ADD_COMPONENT -> edit(hex -> addComponent(hex, blueprint.component(command.value())));
                case REMOVE_COMPONENT -> edit(hex -> {
                    var definition = blueprint.component(command.value());
                    definition.fields().forEach(field -> hex.removeTerrain(Terrains.getType(field.terrain())));
                    var appearance = new LinkedHashMap<>(hex.getAppearance()); appearance.remove(definition.id()); hex.setAppearance(appearance);
                });
                case TERRAIN -> edit(hex -> setTerrain(hex, command.target(), Integer.parseInt(command.value())));
                case REMOVE_TERRAIN -> edit(hex -> {
                    hex.removeTerrain(Terrains.getType(command.value()));
                    if (!hex.containsAnyTerrainOf(Terrains.WOODS, Terrains.JUNGLE)) { hex.removeTerrain(Terrains.FOLIAGE_ELEV); }
                });
                case EDGE -> edit(hex -> edge(hex, command.target(), Integer.parseInt(command.value())));
                case AUTO_EDGES -> edit(hex -> {
                    Terrain old = hex.getTerrain(Terrains.getType(command.value()));
                    require(old != null && old.getExits() <= 63, "This legacy design also uses the exit field; preserve it or choose a new design first.");
                    hex.addTerrain(new Terrain(old.getType(), old.getLevel()));
                });
                case ELEVATION -> edit(hex -> hex.setLevel(Integer.parseInt(command.value())));
                case ELEVATOR -> edit(hex -> {
                    Terrain old = hex.getTerrain(Terrains.INDUSTRIAL_ELEVATOR);
                    require(old != null, "Add an industrial elevator first.");
                    int top = (old.getExits() >> megamek.common.IndustrialElevator.SHAFT_TOP_SHIFT) & megamek.common.IndustrialElevator.CAPACITY_MASK;
                    int capacity = (old.getExits() & megamek.common.IndustrialElevator.CAPACITY_MASK) * megamek.common.IndustrialElevator.CAPACITY_MULTIPLIER;
                    if (command.target().equals("top")) { top = Integer.parseInt(command.value()); }
                    else if (command.target().equals("capacity")) { capacity = Integer.parseInt(command.value()); }
                    else { throw new IllegalArgumentException("Unknown elevator property"); }
                    require(top >= 0 && top <= megamek.common.IndustrialElevator.CAPACITY_MASK, "Shaft top must be between 0 and 255.");
                    require(capacity >= 0 && capacity <= 2550 && capacity % 10 == 0, "Capacity must be in tens of tons, between 0 and 2550.");
                    var elevator = new megamek.common.IndustrialElevator(megamek.common.board.BoardLocation.of(selected, board().getBoardId()),
                          old.getLevel(), top, capacity);
                    hex.addTerrain(new Terrain(Terrains.INDUSTRIAL_ELEVATOR, old.getLevel(), true, elevator.encodeExits()));
                });
                case THEME -> edit(hex -> hex.setTheme(command.value()));
                case MAP_THEME -> {
                    for (int x = 0; x < board().getWidth(); x++) {
                        for (int y = 0; y < board().getHeight(); y++) {
                            change(new Coords(x, y), hex -> hex.setTheme(command.value()));
                        }
                    }
                    finishStroke();
                    message = "Applied theme to every hex. Undo restores the previous themes.";
                }
                case VARIANT -> edit(hex -> setVariant(hex, command.target(), command.value()));
                case BLEND -> edit(hex -> {
                    var styles = new LinkedHashMap<>(hex.getAppearance()); var current = styles.get("ground");
                    require(current != null && blueprint.variant(current.variant()) != null
                          && blueprint.variant(current.variant()).blend(), "Choose a ground blend first.");
                    styles.put("ground", new HexAppearance(current.variant(), null, null, Double.parseDouble(command.value())));
                    hex.setAppearance(styles);
                });
                case ASSET -> {
                    var design = blueprint.asset(command.value()); require(design != null, "Unknown object design");
                    paintTemplate = new Hex(0); sampledHex = null;
                    // A stamp brush keeps only its id; each press asks the decoder for the layout's members.
                    if (design.layout() == null) {
                        // An alias entry (a pond drawn as a pool model) keeps its palette label as the object's name.
                        paintTemplate.setDecorations(List.of(new BoardDecoration("brush", design.kind(), design.model(),
                              design.model().equals(design.id()) ? null : design.label(),
                              0, 0, 0, false, 1, BoardDecoration.Placement.surface("ground", "top",
                                    design.id().equals(megamek.common.board.MaglevRoute.ASSET) ? 1 : 0), 0, false)
                              .withColours(design.colours())));
                    }
                    brushComponent = ""; brushKey = design.id(); brushLabel = design.label(); tool = Tool.PAINT;
                    message = "";
                }
                case SELECT_OBJECT -> {
                    tool = Tool.SELECT;
                    if (command.target().isEmpty()) {
                        selectedObject = command.value(); selection.clear(); selection.add(new Selection(selected, selectedObject));
                    } else {
                        // A group target selects the whole group; the inspected hex's first member stays the primary.
                        var members = groups().getOrDefault(command.target(), List.of());
                        require(!members.isEmpty(), "The group is no longer on this board.");
                        selection.clear(); selection.addAll(members);
                        var primary = members.stream().filter(item -> item.coords().equals(selected)).findFirst().orElse(members.getFirst());
                        selected = primary.coords(); selectedObject = primary.object();
                    }
                }
                case OBJECT_VALUE -> editObject(command.target(), command.value());
                case HEIGHT -> height(command.target(), Double.parseDouble(command.value()));
                case PLACE -> place(command.target(), command.value());
                case PILLARS -> {
                    requireBridge();
                    var type = Boolean.parseBoolean(command.value()) ? HexAppearance.PILLARS : HexAppearance.BUILT_BRIDGE;
                    bridgeSpan(selected, current -> type);
                    if (!continuousEdit) { finishStroke(); }
                }
                case BRIDGE_TYPE -> {
                    requireBridge();
                    require(command.value().equals("built") || command.value().equals("natural"), "Choose built or natural.");
                    typeSpan(selected, command.value().equals("built"));
                    if (!continuousEdit) { finishStroke(); }
                }
                case GROUP -> group();
                case UNGROUP -> ungroup();
                case GROUP_VALUE -> editGroup(command.target(), command.value());
                case REORDER_OBJECT -> edit(hex -> reorderObject(hex, command.target(), command.value()));
                case DUPLICATE_OBJECT -> {
                    if (!wholeGroup().isEmpty()) { duplicateGroup(); }
                    else {
                        edit(hex -> {
                            require(!megamek.common.board.MaglevRoute.isRoute(object(hex)), "Paint another hex to extend the maglev route.");
                            BoardDecoration copy = object(hex).duplicate();
                            var objects = new ArrayList<>(hex.getDecorations()); objects.add(copy); hex.setDecorations(objects); selectedObject = copy.id();
                        });
                    }
                }
                case REMOVE_OBJECT -> {
                    String id = command.target().isEmpty() ? selectedObject : command.target();
                    require(selected != null, "Select a hex first.");
                    change(selected, hex -> hex.setDecorations(hex.getDecorations().stream().filter(d -> !d.id().equals(id)).toList()));
                    unlinkRoutes(List.of(selected));
                    if (!continuousEdit) { finishStroke(); }
                    selection.removeIf(item -> item.object().equals(id));
                    if (selectedObject.equals(id)) { selectedObject = ""; }
                }
                case COPY -> { require(selected != null, "Select a hex first"); clipboard = board().getHex(selected).duplicate(); message = "Copied hex, including appearance and objects."; }
                case PASTE -> {
                    require(clipboard != null, "Copy a hex first");
                    require(selected != null, "Select a hex first.");
                    change(selected, hex -> copyInto(clipboard, hex));
                    keepJoinedSides(List.of(selected));
                    if (!continuousEdit) { finishStroke(); }
                }
                case UNDO -> history(undo, redo, false);
                case REDO -> history(redo, undo, true);
                case OPEN -> { if (confirmDiscard(owner)) { chooseOpen(owner); } }
                case SAVE, SAVE_AS -> chooseSave(owner, command.action() == Action.SAVE_AS);
                case NEW -> {
                    String[] size = command.value().split("[,x ]+");
                    require(size.length == 2, "Enter width × height, for example 16 × 17.");
                    int width = Integer.parseInt(size[0]), height = Integer.parseInt(size[1]);
                    require(width > 0 && height > 0 && (long) width * height <= 1_000_000, "Enter positive dimensions, at most one million hexes.");
                    if (confirmDiscard(owner)) { replace(Board.createEmptyBoard(width, height), null); message = "New " + width + " × " + height + " board"; }
                }
                case SELECT_AT -> {
                    // An issue's hex, and its object when the issue concerns one that is still there.
                    String[] cell = command.target().split(",");
                    require(cell.length == 2, "Choose a hex as x,y.");
                    Coords at = new Coords(Integer.parseInt(cell[0].trim()), Integer.parseInt(cell[1].trim()));
                    require(board().contains(at), "That hex is outside the board.");
                    boolean present = board().getHex(at).getDecorations().stream().anyMatch(d -> d.id().equals(command.value()));
                    tool = Tool.SELECT; selected = at; selectedObject = present ? command.value() : ""; pendingDrill = null;
                    selection.clear(); selection.add(new Selection(at, selectedObject));
                }
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            message = failure.getMessage() == null ? "Invalid editor value" : failure.getMessage();
        } finally { continuousEdit = false; }
        revision++;
    }

    public void key(int key, int modifiers, Window owner) {
        boolean control = (modifiers & InputEvent.CTRL_DOWN_MASK) != 0;
        Action action = control ? switch (key) {
            case KeyEvent.VK_Z -> (modifiers & InputEvent.SHIFT_DOWN_MASK) == 0 ? Action.UNDO : Action.REDO;
            case KeyEvent.VK_Y -> Action.REDO;
            case KeyEvent.VK_C -> Action.COPY;
            case KeyEvent.VK_V -> Action.PASTE;
            case KeyEvent.VK_S -> Action.SAVE;
            case KeyEvent.VK_O -> Action.OPEN;
            case KeyEvent.VK_G -> modifiers == InputEvent.CTRL_DOWN_MASK ? Action.GROUP
                  : modifiers == (InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK) ? Action.UNGROUP : null;
            default -> null;
        } : key == KeyEvent.VK_DELETE && !selection.isEmpty() ? Action.DELETE_SELECTION : null;
        if (action != null) { command(new Command(action), owner); }
    }

    public boolean confirmDiscard(Window owner) {
        finishStroke();
        if (!dirty()) { return true; }
        int answer = JOptionPane.showConfirmDialog(owner, "Save changes to this board?", "Unsaved board",
              JOptionPane.YES_NO_CANCEL_OPTION);
        return answer == JOptionPane.NO_OPTION || answer == JOptionPane.YES_OPTION && chooseSave(owner, false);
    }

    public boolean chooseOpen(Window owner) throws IOException {
        JFileChooser chooser = new JFileChooser(Configuration.boardsDir()); chooser.setFileFilter(new BoardFileFilter());
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) { return false; }
        open(chooser.getSelectedFile().toPath()); return true;
    }

    public boolean chooseSave(Window owner, boolean saveAs) {
        Path target = path;
        if (saveAs || target == null) {
            Path initial = path != null ? path : suggested;
            JFileChooser chooser = new JFileChooser(initial == null ? Configuration.boardsDir() : initial.toFile().getParentFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("3D board (*.board2)", "board2"));
            chooser.setSelectedFile(initial == null ? new java.io.File("Untitled.board2") : initial.toFile());
            if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) { return false; }
            target = chooser.getSelectedFile().toPath();
            if (!BoardFile.isNativeName(target.toString())) {
                target = target.resolveSibling(BoardFile.withoutExtension(target.getFileName().toString()) + BoardFile.EXTENSION);
            }
            if (java.nio.file.Files.exists(target) && JOptionPane.showConfirmDialog(owner,
                  "Replace " + target.getFileName() + "?", "Save board", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return false; }
        }
        try { save(target); return true; }
        catch (IOException failure) { message = "Save failed: " + failure.getMessage(); revision++; return false; }
    }

    public List<Coords> brush(Coords center) {
        if (center == null || !board().contains(center)) { return List.of(); }
        List<Coords> result = new ArrayList<>();
        for (int x = Math.max(0, center.getX() - brush + 1); x <= Math.min(board().getWidth() - 1, center.getX() + brush - 1); x++) {
            for (int y = Math.max(0, center.getY() - brush + 1); y <= Math.min(board().getHeight() - 1, center.getY() + brush - 1); y++) {
                Coords at = new Coords(x, y); if (center.distance(at) < brush) { result.add(at); }
            }
        }
        return List.copyOf(result);
    }

    /** What a Sculpt click on {@code hexes} footprint hexes at {@code hover} does without Ctrl, e.g. "Raise · 7 hexes". */
    public String sculptHint(Coords hover, int hexes) {
        if (tool != Tool.SCULPT || hover == null || !board().contains(hover)) { return ""; }
        if (sculpt == LevelSculpt.Mode.LEVEL) {
            return "Level to L" + (sculptLevel == null ? board().getHex(hover).getLevel() : sculptLevel);
        }
        return (sculpt == LevelSculpt.Mode.RAISE ? "Raise" : "Lower") + " · " + hexes + (hexes == 1 ? " hex" : " hexes");
    }

    /** Pointer coordinates are normalized against the hex footprint by the shared renderer's picking geometry. */
    public void pointer(Coords at, double x, double y, boolean drag) {
        pointer(at, x, y, drag, null);
    }

    public void pointer(Coords at, double x, double y, boolean drag, String pickedObject) {
        pointer(at, x, y, drag, pickedObject, false, "ground");
    }

    public void pointer(Coords at, double x, double y, boolean drag, String pickedObject, boolean additive) {
        pointer(at, x, y, drag, pickedObject, additive, "ground");
    }

    public void pointer(Coords at, double x, double y, boolean drag, String pickedObject, boolean additive, String receiver) {
        pointer(at, x, y, drag, pickedObject, additive, receiver, false);
    }

    /** {@code invert} (Ctrl at the press) swaps Sculpt's raise and lower; other tools ignore it. */
    public void pointer(Coords at, double x, double y, boolean drag, String pickedObject, boolean additive, String receiver,
          boolean invert) {
        if (at == null || !board().contains(at)) { return; }
        try {
            if (tool == Tool.SELECT) {
                selectPointer(at, x, y, drag, pickedObject, additive);
            } else if (tool == Tool.SCULPT) {
                // A fast drag also covers the hexes it skipped since the stroke's previous event.
                var centres = drag && sculptLevel != null && selected != null ? Coords.intervening(selected, at) : List.of(at);
                selected = at; selectedObject = "";
                if (sculptLevel == null) {
                    sculptLevel = board().getHex(at).getLevel();
                    sculptStep = (sculpt == LevelSculpt.Mode.LOWER) != invert ? -1 : 1;
                }
                // Each footprint hex moves once per stroke, when the stroke first enters it.
                List<Coords> cells = centres.stream().flatMap(centre -> brush(centre).stream()).filter(painted::add).toList();
                setLevels(sculpt == LevelSculpt.Mode.LEVEL ? LevelSculpt.level(board(), cells, sculptLevel)
                      : LevelSculpt.raise(board(), cells, sculptStep, slope));
            } else if (sampledHex != null) {
                selected = at; selectedObject = ""; component = brushComponent;
                Hex sample = tool == Tool.ERASE ? new Hex(0) : paintTemplate;
                for (Coords spot : brush(at)) {
                    if (!painted.add(spot)) { continue; }
                    change(spot, hex -> {
                        int level = hex.getLevel(); String theme = hex.getTheme();
                        copyInto(sample, hex);
                        if (tool == Tool.PAINT) {
                            if (!brushElevation) { hex.setLevel(level); }
                            if (!brushTheme) { hex.setTheme(theme); }
                        }
                    });
                }
            } else if (!paintTemplate.getDecorations().isEmpty()) {
                BoardDecoration prototype = paintTemplate.getDecorations().getFirst();
                if (megamek.common.board.MaglevRoute.isRoute(prototype)) {
                    // Routes paint continuously, with one centred marker per hex and ordinary stroke history. Consecutive
                    // hexes of one stroke join, so a parallel stroke stays apart; a stroke may start on a route to extend it.
                    selected = at; selectedObject = "";
                    var routeCells = drag && routePaintFrom != null ? Coords.intervening(routePaintFrom, at) : List.of(at);
                    Coords previousCell = null;
                    for (Coords cell : routeCells) {
                        if (!board().contains(cell)) { continue; }
                        if (tool == Tool.ERASE) {
                            if (painted.add(cell)) {
                                change(cell, hex -> hex.setDecorations(hex.getDecorations().stream()
                                      .filter(d -> !megamek.common.board.MaglevRoute.isRoute(d)).toList()));
                                unlinkRoutes(List.of(cell));
                            }
                            continue;
                        }
                        if (painted.add(cell)) {
                            change(cell, hex -> {
                                var previous = megamek.common.board.MaglevRoute.find(hex);
                                var objects = new ArrayList<>(hex.getDecorations());
                                objects.removeIf(megamek.common.board.MaglevRoute::isRoute);
                                // A new marker starts without sides, even from a sampled route; the stroke adds them.
                                var route = previous == null ? prototype.duplicate().withConnections(0) : previous;
                                objects.add(route.transform(0, 0, 0, false, 1, prototype.placement()));
                                hex.setDecorations(objects);
                            });
                        }
                        if (previousCell != null && previousCell.distance(cell) == 1) {
                            int direction = previousCell.direction(cell);
                            routeSide(previousCell, direction, true);
                            routeSide(cell, (direction + 3) % 6, true);
                        }
                        previousCell = cell;
                    }
                    routePaintFrom = at;
                    var route = megamek.common.board.MaglevRoute.find(board().getHex(at));
                    selectedObject = route == null ? "" : route.id();
                } else if (tool == Tool.ERASE) {
                    selected = at; selectedObject = "";
                    for (Coords spot : brush(at)) {
                        if (painted.add(spot)) { change(spot, hex -> hex.setDecorations(hex.getDecorations().stream()
                              .filter(d -> !d.asset().equals(prototype.asset())).toList())); }
                    }
                } else if (!placed) {
                    selected = at;
                    BoardDecoration object = brushCopy(prototype, precision ? x : 0, precision ? y : 0, onSupport(board().getHex(at), receiver, prototype));
                    if (precision && magnetic) { object = BoardEditorSnapping.snap(board(), blueprint, at, object, Set.of()); }
                    addObject(at, object); placed = true;
                }
            } else if (stampLayout() != null) {
                require(tool == Tool.PAINT, "Select a stamp's group to delete it.");
                if (!placed) {
                    selected = at;
                    List<BoardDecoration> members = decoder.stamp(stampLayout(), board().getHex(at), at);
                    change(at, hex -> { var objects = new ArrayList<>(hex.getDecorations()); objects.addAll(members); hex.setDecorations(objects); });
                    selectedObject = members.isEmpty() ? "" : members.getFirst().id(); placed = true;
                    // The new stamp is selected as its whole group, so a later Select click and drag moves all of it.
                    selection.clear();
                    if (members.isEmpty()) { selection.add(new Selection(at, "")); }
                    members.forEach(member -> selection.add(new Selection(at, member.id())));
                }
            } else {
                selected = at; selectedObject = ""; component = brushComponent;
                var definition = blueprint.component(brushComponent);
                List<Coords> spots = new ArrayList<>();
                for (Coords spot : brush(at)) {
                    if (!painted.add(spot)) { continue; }
                    spots.add(spot);
                    change(spot, hex -> {
                        for (var field : definition.fields()) {
                            int type = Terrains.getType(field.terrain());
                            Terrain value = paintTemplate.getTerrain(type);
                            if (!definition.replaceExisting() && value == null) { continue; }
                            if (tool == Tool.ERASE || value == null) { hex.removeTerrain(type); }
                            else { hex.addTerrain(new Terrain(value)); }
                        }
                        var styles = new LinkedHashMap<>(hex.getAppearance());
                        if (tool != Tool.ERASE && paintTemplate.getAppearance().containsKey(brushComponent)) {
                            // A Built bridge card keeps the hex's piers.
                            var style = paintTemplate.getAppearance().get(brushComponent);
                            styles.put(brushComponent, brushComponent.equals("bridge") ? retype(style).apply(styles.get("bridge")) : style);
                        } else { styles.remove(brushComponent); }
                        hex.setAppearance(styles);
                        if (tool != Tool.ERASE && brushComponent.equals("ground")) {
                            if (brushTheme) { hex.setTheme(paintTemplate.getTheme()); }
                            if (brushElevation) { hex.setLevel(paintTemplate.getLevel()); }
                        }
                    });
                }
                // A bridge card's type is the choice for each whole span its stroke paints or joins.
                Boolean built = HexAppearance.bridgeBuilt(paintTemplate.getAppearance());
                if (tool == Tool.PAINT && brushComponent.equals("bridge") && built != null) {
                    spots.forEach(spot -> typeSpan(spot, built));
                }
            }
            // A stamp keeps the whole group it selected above.
            if (tool != Tool.SELECT && (tool == Tool.SCULPT || stampLayout() == null)) {
                selection.clear();
                if (selected != null) { selection.add(new Selection(selected, selectedObject)); }
            }
            message = "";
        } catch (IllegalArgumentException failure) { message = failure.getMessage(); }
        revision++;
    }

    /**
     * A press on an already selected item grabs the whole selection, and its drag moves it (whole hexes commit
     * atomically on release); a press elsewhere selects without grabbing, so only selected hexes or objects ever move.
     * A grouped object selects its whole group; pressing an item of a larger selection and releasing without a drag
     * selects that item (with its group) alone, pressing a member of a wholly selected group that way selects the member
     * alone, and while drilled in, a sibling's click selects only the sibling. Shift-click
     * toggles; Shift drags do nothing here (the view boxes them).
     */
    private void selectPointer(Coords at, double x, double y, boolean drag, String object, boolean additive) {
        Selection hit = new Selection(at, object == null ? "" : object);
        if (drag) { pendingDrill = null; }
        if (additive && drag) { return; }
        // Only presses need the group, so a drag's move events never consult the board's groups.
        List<Selection> items = drag || hit.object().isEmpty() ? List.of(hit) : expand(hit);
        if (additive) {
            if (!selection.isEmpty() && selection.iterator().next().object().isEmpty() != hit.object().isEmpty()) { selection.clear(); }
            if (selection.containsAll(items)) { items.forEach(selection::remove); }
            else { selection.addAll(items); }
            selected = at; selectedObject = hit.object(); return;
        }
        if (!drag) {
            pendingDrill = null;
            boolean grabbed = selection.contains(hit);
            // A click (no drag) on part of a larger selection narrows it: to the item's group, or within a wholly
            // selected group to the member.
            if (grabbed && selection.size() > items.size()) { pendingDrill = items; }
            else if (grabbed && items.size() > 1 && selection.containsAll(items)) { pendingDrill = List.of(hit); }
            else if (!grabbed && items.size() > 1 && selection.size() == 1 && items.contains(selection.iterator().next())) {
                selection.clear(); selection.add(hit);
            } else if (!grabbed) { selection.clear(); selection.addAll(items); }
            selected = at; selectedObject = hit.object(); dragOrigin = grabbed ? at : null; dragX = x; dragY = y;
            movingObjects.clear();
            // Connected routes are edited by painting/erasing hexes, so a moving selection leaves its route markers in place.
            for (Selection item : selection) {
                if (grabbed && !item.object().isEmpty()) {
                    decoration(item).filter(d -> !megamek.common.board.MaglevRoute.isRoute(d)).ifPresent(d -> movingObjects.put(item, d));
                }
            }
        } else if (dragOrigin != null) {
            require(!movingObjects.isEmpty() || selectedObject.isEmpty(), "Paint or erase hexes to change a maglev route.");
            if (movingObjects.isEmpty()) { moveTarget = at; }
            else {
                double dx = x - dragX, dy = y - dragY;
                if (magnetic) {
                    var grabbed = movingObjects.entrySet().stream().filter(entry -> entry.getValue().id().equals(selectedObject)).findFirst()
                          .orElse(movingObjects.entrySet().iterator().next());
                    var original = grabbed.getValue();
                    var candidate = original.transform(original.x() + dx, original.y() + dy, original.rotation(), original.mirror(), original.scale(), original.placement());
                    var snapped = BoardEditorSnapping.snap(board(), blueprint, grabbed.getKey().coords(), candidate,
                          movingObjects.values().stream().map(BoardDecoration::id).collect(java.util.stream.Collectors.toSet()));
                    dx += snapped.x() - candidate.x(); dy += snapped.y() - candidate.y();
                }
                Map<Selection, BoardDecoration> moved = new LinkedHashMap<>();
                for (var entry : movingObjects.entrySet()) {
                    BoardDecoration original = entry.getValue();
                    BoardDecoration next = original.transform(original.x() + dx, original.y() + dy, original.rotation(),
                          original.mirror(), original.scale(), original.placement());
                    // Resolve support at the future owner, while keeping ownership stable until release.
                    var destination = positioned(entry.getKey().coords(), next);
                    next = next.transform(next.x(), next.y(), next.rotation(), next.mirror(), next.scale(), destination.getValue().placement());
                    moved.put(entry.getKey(), next);
                }
                moveObjects(moved);
            }
        }
    }

    /**
     * On {@code receiver}'s surface of {@code hex} at the brush's offset. Ice lies on its hex's ground or water; an object
     * put down there stands on the ice.
     */
    private static BoardDecoration.Placement onSupport(Hex hex, String receiver, BoardDecoration prototype) {
        String support = receiver.equals("ground") && hex.containsTerrain(Terrains.ICE) ? "ice" : receiver;
        double offset = prototype.placement().offset() == null ? 0 : prototype.placement().offset();
        return BoardDecoration.Placement.on(support, offset);
    }

    /** A new copy of the brush's object at {@code x, y} on {@code placement}. */
    private static BoardDecoration brushCopy(BoardDecoration prototype, double x, double y, BoardDecoration.Placement placement) {
        return prototype.duplicate().transform(x, y, prototype.rotation(), prototype.mirror(), prototype.scale(), placement);
    }

    /** Adds a new object to the hex its anchor lies in, seen from {@code owner}, and makes it the selected object. */
    private void addObject(Coords owner, BoardDecoration object) {
        var destination = positioned(owner, object);
        selected = destination.getKey().coords();
        BoardDecoration placedObject = destination.getValue();
        change(selected, hex -> { var objects = new ArrayList<>(hex.getDecorations()); objects.add(placedObject); hex.setDecorations(objects); });
        selectedObject = object.id();
    }

    /**
     * Puts a copy of the armed object brush at {@code value} "x,y,level" of the inspected hex (x and y in hex widths and
     * heights): on {@code receiver}'s surface, or at the fixed level when {@code receiver} is empty. One undo step; the
     * new object becomes the selection.
     */
    private void place(String receiver, String value) {
        require(selected != null, "Select a hex first.");
        require(sampledHex == null, "Choose a prop or decal card first.");
        BoardDecoration prototype = paintTemplate.getDecorations().stream().findFirst().orElse(null);
        require(prototype != null && !megamek.common.board.MaglevRoute.isRoute(prototype), "Choose a prop or decal card first.");
        String[] spot = value.split(",");
        require(spot.length == 3, "Place at x,y,level.");
        Hex hex = board().getHex(selected);
        BoardDecoration.Placement placement;
        if (receiver.isEmpty()) {
            require(prototype.kind().equals("prop"), "A decal lies on a surface: click the ground, a deck or a roof.");
            placement = BoardDecoration.Placement.absolute(Double.parseDouble(spot[2]));
        } else {
            require(supports(hex, receiver), "This hex has nothing to place on there.");
            placement = onSupport(hex, receiver, prototype);
        }
        addObject(selected, brushCopy(prototype, Double.parseDouble(spot[0]), Double.parseDouble(spot[1]), placement));
        selection.clear(); selection.add(new Selection(selected, selectedObject));
        finishStroke();
    }

    /**
     * Moves the inspected hex's {@link Level} {@code key} to {@code value}, an absolute level clamped to the level's range:
     * the ground (the hex level), the water bed (the depth), a bridge's deck or a structure's top over the whole
     * connected structure (each hex at its own height for the same top), a canopy, or a placed object (its offset above
     * its support, or its fixed level). A live drag is one undo step.
     */
    private void height(String key, double value) {
        require(selected != null, "Select a hex first.");
        Hex hex = board().getHex(selected);
        Level level = levels(hex).stream().filter(l -> l.key().equals(key)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException("This hex has no " + key + " to move."));
        require(level.min() < level.max(), "A decal lies on its surface; move the surface instead.");
        double target = Math.max(level.min(), Math.min(level.max(), value));
        int whole = (int) Math.round(target);
        if (level.object()) {
            BoardDecoration object = hex.getDecorations().stream().filter(d -> d.id().equals(key)).findFirst().orElseThrow();
            boolean fixed = object.placement().mode().equals("absolute");
            BoardDecoration next = objectValue(object, fixed ? "level" : "offset",
                  Double.toString(fixed ? target : target - (level.level() - object.placement().offset())));
            change(selected, h -> h.setDecorations(h.getDecorations().stream().map(d -> d.id().equals(key) ? next : d).toList()));
        } else if (key.equals("ground")) {
            change(selected, h -> h.setLevel(whole));
        } else if (key.equals("water")) {
            change(selected, h -> setTerrain(h, "water", hex.getLevel() - whole));
        } else {
            var component = blueprint.component(key);
            var field = component.fields().stream().filter(BoardEditorBlueprint.Field::height).findFirst().orElseThrow();
            // A structure whose own terrain differs from its height field joins its neighbours through that terrain's exits.
            String structure = component.fields().getFirst().terrain();
            List<Coords> cells = component.category().equals("Structures") && !structure.equals(field.terrain())
                  ? structure(selected, Terrains.getType(structure)) : List.of(selected);
            for (Coords cell : cells) {
                change(cell, h -> setTerrain(h, field.terrain(), (int) Math.max(field.min(),
                      Math.min(Math.max(field.max(), h.terrainLevel(Terrains.getType(field.terrain()))), whole - h.getLevel()))));
            }
        }
        if (!continuousEdit) { finishStroke(); }
    }

    private void requireBridge() {
        require(selected != null && board().getHex(selected).containsTerrain(Terrains.BRIDGE), "Select a bridge first.");
    }

    /** Retypes each hex of the bridge span at {@code start} ({@link #structure}) from its stored type ({@code type}). */
    private void bridgeSpan(Coords start, java.util.function.UnaryOperator<HexAppearance> type) {
        for (Coords cell : structure(start, Terrains.BRIDGE)) {
            change(cell, hex -> {
                if (!hex.containsTerrain(Terrains.BRIDGE)) { return; }
                var styles = new LinkedHashMap<>(hex.getAppearance());
                styles.put("bridge", type.apply(styles.get("bridge")));
                hex.setAppearance(styles);
            });
        }
    }

    /**
     * Makes the bridge span at {@code start} built or natural, as the Edit panel's type and a bridge card's stroke do: a
     * built span gets piers ({@link HexAppearance#PILLARS}) on every hex when any of its hexes has them, so its Pillars
     * toggle stays uniform.
     */
    private void typeSpan(Coords start, boolean built) {
        boolean piers = built && structure(start, Terrains.BRIDGE).stream()
              .anyMatch(at -> HexAppearance.pillars(board().getHex(at).getAppearance()));
        var type = piers ? HexAppearance.PILLARS : built ? HexAppearance.BUILT_BRIDGE : HexAppearance.NATURAL_BRIDGE;
        bridgeSpan(start, current -> type);
    }

    /** A bridge type as a choice for a whole span: built keeps a hex's piers ({@link HexAppearance#PILLARS}). */
    private static java.util.function.UnaryOperator<HexAppearance> retype(HexAppearance type) {
        return current -> type.equals(HexAppearance.BUILT_BRIDGE) && HexAppearance.PILLARS.equals(current) ? current : type;
    }

    /**
     * Before a stroke is committed, every bridge span it touched gets one type again ({@link HexAppearance#bridgeBuilt}):
     * an added, pasted or joined hex takes its span's, built where the span stores both or none (as the 3D board draws
     * it).
     */
    private void typeBridges() {
        Set<Coords> typed = new java.util.HashSet<>();
        for (Coords at : List.copyOf(stroke.keySet())) {
            if (typed.contains(at) || !board().getHex(at).containsTerrain(Terrains.BRIDGE)) { continue; }
            List<Coords> span = structure(at, Terrains.BRIDGE);
            typed.addAll(span);
            boolean natural = Boolean.FALSE.equals(megamek.common.board.BridgeSpan.type(board()::getHex, span));
            bridgeSpan(at, retype(natural ? HexAppearance.NATURAL_BRIDGE : HexAppearance.BUILT_BRIDGE));
        }
    }

    /**
     * The hexes of the structure at {@code start}: a bridge's {@link megamek.common.board.BridgeSpan}, the one span of
     * its type, piers and deck height; another structure's hexes reached through the exits of its {@code type} terrain.
     */
    private List<Coords> structure(Coords start, int type) {
        if (type == Terrains.BRIDGE) { return megamek.common.board.BridgeSpan.of(board()::getHex, start); }
        Set<Coords> found = new LinkedHashSet<>(List.of(start));
        ArrayDeque<Coords> open = new ArrayDeque<>(found);
        while (!open.isEmpty()) {
            Coords at = open.pop();
            for (int direction = 0; direction < 6; direction++) {
                Coords next = at.translated(direction);
                if (board().getHex(at).containsTerrainExit(type, direction) && board().contains(next)
                      && board().getHex(next).containsTerrain(type) && found.add(next)) { open.add(next); }
            }
        }
        return List.copyOf(found);
    }

    /** The layout of the chosen stamp brush, or null when the brush is not a stamp. */
    private String stampLayout() {
        var asset = brushComponent.isEmpty() && paintTemplate.getDecorations().isEmpty() ? blueprint.asset(brushKey) : null;
        return asset == null ? null : asset.layout();
    }

    private List<Coords> movePreview() {
        if (!movingObjects.isEmpty()) {
            return selection.stream().filter(item -> !item.object().isEmpty()).map(item -> board().getHex(item.coords()).getDecorations().stream()
                  .filter(object -> object.id().equals(item.object())).findFirst().map(object -> positioned(item.coords(), object).getKey().coords()).orElse(item.coords())).toList();
        }
        if (dragOrigin == null || moveTarget == null) { return List.of(); }
        var delta = moveTarget.toCube().subtract(dragOrigin.toCube());
        return selection.stream().map(item -> item.coords().toCube().add(delta).toOffset()).toList();
    }

    private void moveHexes() {
        if (moveTarget == null || dragOrigin == null || moveTarget.equals(dragOrigin)) { return; }
        var delta = moveTarget.toCube().subtract(dragOrigin.toCube());
        List<Coords> targets = movePreview();
        if (targets.stream().anyMatch(at -> !board().contains(at))) { message = "The whole selection must fit inside the board."; return; }
        Map<Coords, Hex> moved = new LinkedHashMap<>();
        for (Selection item : selection) { moved.put(item.coords().toCube().add(delta).toOffset(), board().getHex(item.coords()).duplicate()); }
        for (Selection item : selection) {
            if (!moved.containsKey(item.coords())) {
                Hex empty = new Hex(0); empty.setTheme(board().getHex(item.coords()).getTheme());
                change(item.coords(), hex -> copyInto(empty, hex));
            }
        }
        moved.forEach((at, source) -> change(at, hex -> { copyInto(source, hex); hex.setDecorations(source.getDecorations()); }));
        Set<Coords> touched = new java.util.HashSet<>(moved.keySet());
        selection.forEach(item -> touched.add(item.coords()));
        keepJoinedSides(touched);
        selected = moveTarget; selection.clear(); targets.forEach(at -> selection.add(new Selection(at, "")));
    }

    private void deleteSelection() {
        for (Selection item : selection) {
            change(item.coords(), hex -> {
                if (item.object().isEmpty()) { copyInto(new Hex(0), hex); }
                else { hex.setDecorations(hex.getDecorations().stream().filter(d -> !d.id().equals(item.object())).toList()); }
            });
        }
        unlinkRoutes(selection.stream().map(Selection::coords).toList());
        selectedObject = ""; selection.clear(); finishStroke();
    }

    /** Sets or clears one side of the route marker at {@code at}; a hex without a marker is left alone. */
    private void routeSide(Coords at, int direction, boolean on) {
        change(at, hex -> {
            var route = megamek.common.board.MaglevRoute.find(hex);
            if (route == null) { return; }
            int side = megamek.common.board.MaglevRoute.side(direction);
            var next = route.withConnections(on ? route.connections() | side : route.connections() & ~side);
            hex.setDecorations(hex.getDecorations().stream().map(d -> d.id().equals(route.id()) ? next : d).toList());
        });
    }

    /** Clears the sides that neighbouring markers keep towards those of {@code cells} that no longer have a route. */
    private void unlinkRoutes(List<Coords> cells) {
        for (Coords at : cells) {
            if (at == null || !board().contains(at) || megamek.common.board.MaglevRoute.find(board().getHex(at)) != null) { continue; }
            for (int direction = 0; direction < 6; direction++) {
                Coords neighbor = at.translated(direction);
                if (board().contains(neighbor)) { routeSide(neighbor, (direction + 3) % 6, false); }
            }
        }
    }

    /**
     * Clears every route side across the edges of {@code cells} that the marker on the other side does not return, so a
     * moved, pasted or vacated hex neither keeps open ends nor leaves its old neighbours pointing at it.
     */
    private void keepJoinedSides(java.util.Collection<Coords> cells) {
        for (Coords at : cells) {
            for (int direction = 0; direction < 6; direction++) {
                Coords neighbor = at.translated(direction);
                if (!board().contains(neighbor) || megamek.common.board.MaglevRoute.joined(megamek.common.board.MaglevRoute.find(board().getHex(at)),
                      direction, megamek.common.board.MaglevRoute.find(board().getHex(neighbor)))) { continue; }
                routeSide(at, direction, false);
                routeSide(neighbor, (direction + 3) % 6, false);
            }
        }
    }

    /**
     * Ends a pointer gesture after its stroke: a press on part of a larger selection that never dragged narrows the
     * selection to that item (its group), or drills into a wholly selected group's member.
     */
    public void release() {
        if (pendingDrill != null && selection.containsAll(pendingDrill)) {
            selection.clear(); selection.addAll(pendingDrill);
            selected = pendingDrill.getFirst().coords(); selectedObject = pendingDrill.getFirst().object();
            revision++;
        }
        pendingDrill = null;
    }

    /**
     * A box (or a Ctrl-click) over {@code items}, the objects and hexes it covers. It takes the objects, or the hexes
     * when it covers no object; adding to or removing from a selection takes the selection's own kind. Unknown objects
     * are ignored, and an object counts with its whole group.
     */
    public void select(List<Selection> items, SelectMode mode) {
        pendingDrill = null;
        // A box leaves route markers out: they are edited by painting, and would otherwise pin every box it touches.
        List<Selection> objects = items.stream().filter(item -> !item.object().isEmpty() && board().contains(item.coords())
              && decoration(item).filter(d -> mode == SelectMode.REMOVE || !megamek.common.board.MaglevRoute.isRoute(d)).isPresent())
              .flatMap(item -> expand(item).stream()).distinct().toList();
        List<Selection> hexes = items.stream().filter(item -> item.object().isEmpty() && board().contains(item.coords())).distinct().toList();
        boolean kept = mode != SelectMode.REPLACE && !selection.isEmpty();
        List<Selection> found = kept ? (selection.iterator().next().object().isEmpty() ? hexes : objects)
              : objects.isEmpty() ? hexes : objects;
        if (!kept) { selection.clear(); }
        if (mode == SelectMode.REMOVE) { found.forEach(selection::remove); } else { selection.addAll(found); }
        if (mode != SelectMode.REMOVE && !found.isEmpty()) { selected = found.getFirst().coords(); selectedObject = found.getFirst().object(); }
        else if (!selection.isEmpty() && !selection.contains(new Selection(selected, selectedObject))) {
            selected = selection.iterator().next().coords(); selectedObject = selection.iterator().next().object();
        } else if (selection.isEmpty()) { selectedObject = ""; }
        tool = Tool.SELECT; message = "";
        revision++;
    }

    private java.util.Optional<BoardDecoration> decoration(Selection item) {
        return board().getHex(item.coords()).getDecorations().stream().filter(d -> d.id().equals(item.object())).findFirst();
    }

    /** The whole group of a grouped object, or the object alone; a group with one remaining member acts ungrouped. */
    private List<Selection> expand(Selection item) {
        String group = decoration(item).map(BoardDecoration::group).orElse(null);
        List<Selection> members = group == null ? List.of() : groups().getOrDefault(group, List.of());
        return members.size() > 1 ? members : List.of(item);
    }

    /**
     * Members per group, from one board scan per committed document version: edits, undo, redo and replace change
     * {@code currentId}, pointer events do not. An unfinished stroke may have moved members, so it is not cached.
     */
    private Map<String, List<Selection>> groups() {
        if (groupsId != currentId || !stroke.isEmpty()) {
            Map<String, List<Selection>> index = new LinkedHashMap<>();
            for (int y = 0; y < board().getHeight(); y++) {
                for (int x = 0; x < board().getWidth(); x++) {
                    Coords at = new Coords(x, y);
                    for (BoardDecoration object : board().getHex(at).getDecorations()) {
                        if (object.group() != null) {
                            index.computeIfAbsent(object.group(), ignored -> new ArrayList<>()).add(new Selection(at, object.id()));
                        }
                    }
                }
            }
            groups = index; groupsId = stroke.isEmpty() ? currentId : -1;
        }
        return groups;
    }

    /** The distinct groups of the selected objects, with "" for an ungrouped object; O(selection). */
    private List<String> selectionGroups() {
        return selection.stream().filter(item -> !item.object().isEmpty()).map(this::decoration).flatMap(java.util.Optional::stream)
              .map(object -> object.group() == null ? "" : object.group()).distinct().toList();
    }

    private String wholeGroup() { return wholeGroup(selectionGroups(), selection.size()); }

    /** One new group for the selected objects and every member of their groups, as one undo step. */
    private void group() {
        long objects = selection.stream().filter(item -> !item.object().isEmpty()).count();
        require(objects >= 2, "Select two or more objects to group them.");
        require(wholeGroup().isEmpty(), "The selection is already one group.");
        Set<Selection> members = new LinkedHashSet<>();
        selection.stream().filter(item -> !item.object().isEmpty()).forEach(item -> members.addAll(expand(item)));
        // A route marker belongs to its hexes' route, never to a group.
        members.removeIf(item -> decoration(item).filter(megamek.common.board.MaglevRoute::isRoute).isPresent());
        require(members.size() >= 2, "Maglev routes stay out of groups; select two or more other objects.");
        setGroup(members, UUID.randomUUID().toString());
        // Merged groups may add members beyond the selection; the selection becomes the whole new group.
        selection.clear(); selection.addAll(members);
        message = "Grouped " + members.size() + " objects.";
    }

    private void ungroup() {
        var chosen = selectionGroups().stream().filter(group -> !group.isEmpty()).toList();
        require(!chosen.isEmpty(), "Select a group to ungroup it.");
        List<Selection> members = chosen.stream().flatMap(group -> groups().getOrDefault(group, List.of()).stream()).toList();
        setGroup(members, null);
        message = "Ungrouped " + members.size() + " objects.";
    }

    private void setGroup(java.util.Collection<Selection> members, String group) {
        for (Selection item : members) {
            change(item.coords(), hex -> hex.setDecorations(hex.getDecorations().stream()
                  .map(object -> object.id().equals(item.object()) ? object.withGroup(group) : object).toList()));
        }
        finishStroke();
    }

    /** Copies of every member in one new group, overlapping the originals; the copies become the selection. */
    private void duplicateGroup() {
        List<Selection> members = List.copyOf(selection);
        List<BoardDecoration> originals = members.stream().map(item -> decoration(item).orElseThrow()).toList();
        require(originals.stream().noneMatch(megamek.common.board.MaglevRoute::isRoute), "Paint another hex to extend the maglev route.");
        List<BoardDecoration> copies = BoardDecoration.copies(originals);
        selection.clear();
        for (int i = 0; i < members.size(); i++) {
            Selection member = members.get(i);
            BoardDecoration copy = copies.get(i);
            change(member.coords(), hex -> { var objects = new ArrayList<>(hex.getDecorations()); objects.add(copy); hex.setDecorations(objects); });
            selection.add(new Selection(member.coords(), copy.id()));
            if (member.object().equals(selectedObject)) { selected = member.coords(); selectedObject = copy.id(); }
        }
        finishStroke();
    }

    /**
     * Moves, turns, scales or mirrors a whole group as one transform relative to the group as the edit found it: x and y
     * move by hex widths and heights, rotation turns by degrees, scale multiplies and mirror (true) reflects; turns,
     * scales and mirrors pivot about the members' centroid in metric space. A live value drag keeps transforming the
     * members it started from, so each of its values is the whole change so far.
     */
    private void editGroup(String property, String value) {
        require(Set.of("x", "y", "rotation", "scale", "mirror").contains(property), "Unknown group property " + property);
        require(!wholeGroup().isEmpty(), "Select a whole group first.");
        if (groupBase.isEmpty()) {
            Map<Selection, BoardDecoration> found = new LinkedHashMap<>();
            selection.forEach(item -> found.put(item, decoration(item).orElseThrow()));
            require(found.values().stream().noneMatch(megamek.common.board.MaglevRoute::isRoute), "A maglev route stays centred on its hexes; paint, erase or toggle its sides to change it.");
            groupBase = found;
        }
        Map<Selection, BoardDecoration> members = groupBase;
        double centreX = 0, centreY = 0;
        for (var entry : members.entrySet()) {
            centreX += BoardEditorSnapping.globalX(entry.getKey().coords(), entry.getValue().x()) / members.size();
            centreY += BoardEditorSnapping.globalY(entry.getKey().coords(), entry.getValue().y()) / members.size();
        }
        double number = property.equals("mirror") ? 0 : Double.parseDouble(value);
        boolean mirror = property.equals("mirror") && Boolean.parseBoolean(value);
        double turn = property.equals("rotation") ? number : 0;
        double factor = property.equals("scale") ? number : 1;
        require(factor > 0 && Double.isFinite(factor), "Scale must be positive.");
        double cos = Math.cos(Math.toRadians(turn)), sin = Math.sin(Math.toRadians(turn));
        double width = megamek.client.ui.tileset.HexTileset.HEX_W, height = megamek.client.ui.tileset.HexTileset.HEX_H;
        Map<Selection, BoardDecoration> moved = new LinkedHashMap<>();
        for (var entry : members.entrySet()) {
            Coords owner = entry.getKey().coords();
            BoardDecoration object = mirror ? entry.getValue().flip(true, false) : entry.getValue();
            double dx = (BoardEditorSnapping.globalX(owner, entry.getValue().x()) - centreX) * width;
            double dy = (BoardEditorSnapping.globalY(owner, entry.getValue().y()) - centreY) * height;
            double gx = centreX + (mirror ? -dx : (dx * cos - dy * sin) * factor) / width;
            double gy = centreY + (mirror ? dy : (dx * sin + dy * cos) * factor) / height;
            if (property.equals("x")) { gx += number; }
            if (property.equals("y")) { gy += number; }
            BoardDecoration next = object.transform(gx - BoardEditorSnapping.globalX(owner, 0), gy - BoardEditorSnapping.globalY(owner, 0),
                  turn == 0 ? object.rotation() : Math.IEEEremainder(object.rotation() + turn, 360), object.mirror(),
                  object.scale() * factor, object.placement());
            var destination = positioned(owner, next);
            moved.put(destination.getKey(), destination.getValue());
        }
        moveObjects(moved);
        if (!continuousEdit) { finishStroke(); }
    }

    /**
     * Whether {@code hex} offers the {@code receiver} surface: the ground always does, ice needs ice, others need their
     * component.
     */
    private boolean supports(Hex hex, String receiver) {
        if (receiver.equals("ice")) { return hex.containsTerrain(Terrains.ICE); }
        return receiver.equals("ground") || blueprint.components().stream().filter(definition -> definition.receiver().equals(receiver))
              .anyMatch(definition -> definition.isPresent(name -> hex.containsTerrain(Terrains.getType(name))));
    }

    /** Re-validates every hex, as after loading or creating a document. */
    void validateAll() {
        issues.clear();
        List<Coords> cells = new ArrayList<>(board().getWidth() * board().getHeight());
        for (int y = 0; y < board().getHeight(); y++) {
            for (int x = 0; x < board().getWidth(); x++) { cells.add(new Coords(x, y)); }
        }
        validate(cells);
    }

    /**
     * Re-validates {@code cells}: the game's own hex and building-exit rules ({@link Board#hexErrors}, the per-hex step
     * of the board validation) and each placed object's art and support. Issues never block saving.
     */
    private void validate(java.util.Collection<Coords> cells) {
        for (Coords at : cells) {
            if (!board().contains(at)) { continue; }
            List<Issue> found = new ArrayList<>();
            board().hexErrors(at).forEach(text -> found.add(new Issue(at, "", text)));
            Hex hex = board().getHex(at);
            for (BoardDecoration object : hex.getDecorations()) {
                if (!assetExists(object)) { found.add(new Issue(at, object.id(), "Object art not found: " + object.asset())); }
                var receiver = object.placement().receiver();
                if (receiver != null && !supports(hex, receiver.terrain())) {
                    String support = blueprint.components().stream().filter(definition -> definition.receiver().equals(receiver.terrain()))
                          .map(definition -> definition.label().toLowerCase(java.util.Locale.ROOT)).findFirst().orElse(receiver.terrain());
                    found.add(new Issue(at, object.id(), "Object on " + receiver.surface() + " but no " + support));
                }
            }
            if (found.isEmpty()) { issues.remove(at); } else { issues.put(at, List.copyOf(found)); }
        }
        var imported = importIssues.stream().filter(issue -> board().contains(issue.coords()) && board().getHex(issue.coords())
              .getDecorations().stream().anyMatch(object -> object.id().equals(issue.object())));
        issueList = java.util.stream.Stream.concat(issues.values().stream().flatMap(List::stream), imported)
              .sorted(Comparator.comparingInt((Issue issue) -> issue.coords().getX()).thenComparingInt(issue -> issue.coords().getY()))
              .toList();
    }

    /**
     * Whether the renderer has the object's art: a decal's image, a stamp's catalog entry, or else the model file the
     * renderer draws, the object's own asset (a palette alias such as a pond has no art of its own).
     */
    private boolean assetExists(BoardDecoration object) {
        if (object.kind().equals("decal")) {
            return modelFiles.computeIfAbsent(object.asset(), name -> megamek.common.board.BoardDecalArt.image(name).isFile());
        }
        var entry = blueprint.asset(object.asset());
        return entry != null && entry.layout() != null || modelFiles.computeIfAbsent(object.asset(),
              name -> new java.io.File(Configuration.dataDir(), "models/board/" + name + ".glb").isFile());
    }

    /** XY is normalized to the same odd-column lattice as the board renderer, with north-positive Y. */
    private Map.Entry<Selection, BoardDecoration> positioned(Coords owner, BoardDecoration object) {
        var cube = owner.toCube();
        double q = cube.q() + object.x() / .75, r = cube.r() - object.y() - object.x() / 1.5;
        Coords target = new megamek.common.board.CubeCoords(q, r, -q - r).roundToNearestHex().toOffset();
        require(board().contains(target), "Keep every object centre inside the board.");
        double x = object.x() + (owner.getX() - target.getX()) * .75;
        double y = object.y() + (target.getY() - owner.getY()) + ((target.getX() & 1) - (owner.getX() & 1)) * .5;
        var placement = object.placement();
        if (placement.receiver() != null && !supports(board().getHex(target), placement.receiver().terrain())) {
            placement = BoardDecoration.Placement.surface("ground", "top", placement.offset());
        }
        return Map.entry(new Selection(target, object.id()), object.transform(x, y, object.rotation(), object.mirror(), object.scale(), placement));
    }

    /** All destinations are validated before any change; a drag retains IDs and is one undoable stroke. */
    private void moveObjects(Map<Selection, BoardDecoration> moved) {
        Set<String> ids = moved.values().stream().map(BoardDecoration::id).collect(java.util.stream.Collectors.toSet());
        Map<Coords, List<BoardDecoration>> contents = new LinkedHashMap<>();
        for (Coords at : selection.stream().filter(s -> ids.contains(s.object())).map(Selection::coords).distinct().toList()) {
            contents.put(at, new ArrayList<>(board().getHex(at).getDecorations().stream()
                  .filter(d -> !ids.contains(d.id()) || moved.containsKey(new Selection(at, d.id()))).toList()));
        }
        selection.removeIf(item -> ids.contains(item.object()));
        moved.forEach((item, object) -> {
            var objects = contents.computeIfAbsent(item.coords(), at -> new ArrayList<>(board().getHex(at).getDecorations()));
            int index = -1;
            for (int i = 0; i < objects.size(); i++) { if (objects.get(i).id().equals(object.id())) { index = i; break; } }
            // Transforming an existing object must not change its authored contents position.
            if (index < 0) { objects.add(object); } else { objects.set(index, object); }
            selection.add(item);
            if (object.id().equals(selectedObject)) { selected = item.coords(); }
        });
        contents.forEach((at, objects) -> change(at, hex -> hex.setDecorations(objects)));
    }

    private void editObject(String property, String value) {
        require(selected != null, "Select an object first.");
        if (property.equals("side")) {
            // A route side toggles together with the neighbouring marker's opposite side, so the two join or part.
            int direction = Integer.parseInt(value);
            require(direction >= 0 && direction < 6, "Choose one of the six sides.");
            var route = object(board().getHex(selected));
            require(megamek.common.board.MaglevRoute.isRoute(route), "Only a maglev route has sides.");
            boolean on = (route.connections() & megamek.common.board.MaglevRoute.side(direction)) == 0;
            routeSide(selected, direction, on);
            Coords neighbor = selected.translated(direction);
            if (board().contains(neighbor)) { routeSide(neighbor, (direction + 3) % 6, on); }
            if (!continuousEdit) { finishStroke(); }
            return;
        }
        BoardDecoration next = objectValue(object(board().getHex(selected)), property, value);
        if (property.equals("offset") || property.equals("level")) {
            next = next.transform(next.x(), next.y(), next.rotation(), next.mirror(), next.scale(),
                  BoardEditorSnapping.clamp(board().getHex(selected), next.placement()));
        }
        var destination = positioned(selected, next);
        selection.add(new Selection(selected, selectedObject));
        moveObjects(Map.of(destination.getKey(), destination.getValue()));
        if (!continuousEdit) { finishStroke(); }
    }

    private void setVariant(Hex hex, String owner, String value) {
        Map<String, HexAppearance> styles = new LinkedHashMap<>(hex.getAppearance());
        if (value.isEmpty()) { styles.remove(owner); }
        else {
            var variant = blueprint.variant(value);
            require(variant != null && variant.owner().equals(owner), "Unknown appearance choice");
            var ownerComponent = blueprint.component(variant.owner());
            require(ownerComponent.isPresent(name -> hex.containsTerrain(Terrains.getType(name))),
                  "Add " + ownerComponent.label() + " to this hex before choosing its design.");
            HexAppearance current = styles.get(owner);
            if (!variant.material().isEmpty()) {
                // Material-bearing choices define the road rule and its finish together. Preserve an already
                // compatible choice (for example Alley) when an older caller chooses its matching finish.
                for (var field : ownerComponent.fields()) {
                    var compatible = field.choices().stream().filter(choice -> variant.material().equals(choice.material())).toList();
                    if (!compatible.isEmpty() && compatible.stream().noneMatch(choice -> choice.value() == hex.terrainLevel(Terrains.getType(field.terrain())))) {
                        setTerrain(hex, field.terrain(), compatible.getFirst().value());
                    }
                }
                styles.put(owner, new HexAppearance(current == null ? null : current.variant(),
                      null, variant.material(), null));
            } else {
                styles.put(owner, new HexAppearance(variant.id(), null,
                      current == null ? null : current.material(), variant.blend() ? .5 : null));
            }
        }
        hex.setAppearance(styles);
    }

    private void chooseBrush(String owner, String key) {
        var definition = blueprint.component(owner);
        var preset = definition.presets().stream().filter(p -> p.id().equals(key)).findFirst().orElse(null);
        var variant = blueprint.variant(key);
        require(preset != null || variant != null && variant.owner().equals(owner) || key.isEmpty(), "Unknown brush choice");
        Hex next = new Hex(0, preset == null ? definition.defaults() : preset.terrain(), paintTemplate.getTheme());
        if (variant != null) { setVariant(next, owner, key); }
        applyChoiceMaterials(next, definition);
        paintTemplate = next; sampledHex = null; brushComponent = owner; brushElevation = false; brushTheme = false;
        brushKey = variant == null ? owner + "/" + key : key;
        brushLabel = preset != null ? preset.label() : variant != null ? variant.label() : definition.label();
        tool = Tool.PAINT; message = "";
    }

    private void configureBrush(String target, String value) {
        Hex next = paintTemplate.duplicate();
        if (target.startsWith("terrain:")) { setTerrain(next, target.substring(8), Integer.parseInt(value)); }
        else if (target.startsWith("variant:")) { setVariant(next, target.substring(8), value); }
        else if (target.startsWith("object:")) {
            require(!next.getDecorations().isEmpty(), "Choose an object brush first.");
            next.setDecorations(List.of(objectValue(next.getDecorations().getFirst(), target.substring(7), value)));
        } else if (target.equals("theme")) { next.setTheme(value); brushTheme = true; }
        else if (target.equals("elevation")) { next.setLevel(Integer.parseInt(value)); brushElevation = true; }
        else if (target.equals("applyElevation")) { brushElevation = Boolean.parseBoolean(value); }
        else if (target.equals("applyTheme")) { brushTheme = Boolean.parseBoolean(value); }
        else if (target.equals("precision")) { precision = Boolean.parseBoolean(value); }
        else if (target.equals("magnetic")) { magnetic = Boolean.parseBoolean(value); }
        else if (target.equals("sculpt")) { sculpt = LevelSculpt.Mode.valueOf(value); }
        else if (target.equals("slope")) { slope = Boolean.parseBoolean(value); }
        else { throw new IllegalArgumentException("Unknown brush setting " + target); }
        paintTemplate = next;
    }

    private void sampleBrush() {
        require(selected != null, "Select a hex first.");
        Hex hex = board().getHex(selected);
        if (!selectedObject.isEmpty()) {
            BoardDecoration object = object(hex);
            paintTemplate = new Hex(0); paintTemplate.setDecorations(List.of(object));
            sampledHex = null;
            brushComponent = ""; brushKey = object.asset();
            var asset = blueprint.asset(object.asset()); brushLabel = asset == null ? object.asset() : asset.label();
        } else {
            paintTemplate = hex.duplicate(); sampledHex = selected;
            brushElevation = true; brushTheme = true;
            brushComponent = component; brushKey = "hex/sample"; brushLabel = "Hex stamp · " + selected.getBoardNum();
        }
        tool = Tool.PAINT;
    }

    /** Ctrl+wheel directly adjusts the hovered hex in every tool, independently of brush and Sculpt settings. */
    public void adjustElevation(Coords at, int levels) {
        if (levels == 0 || at == null || !board().contains(at)) { return; }
        change(at, hex -> hex.setLevel(hex.getLevel() + levels));
        revision++;
    }

    private void setLevels(Map<Coords, Integer> levels) {
        levels.forEach((at, level) -> change(at, hex -> hex.setLevel(level)));
    }

    private void edit(Consumer<Hex> action) {
        require(selected != null, "Select a hex first.");
        change(selected, action);
        if (!continuousEdit) { finishStroke(); }
    }

    private void change(Coords at, Consumer<Hex> action) {
        Hex original = board().getHex(at), next = original.duplicate();
        action.accept(next);
        if (BoardFile.hexNode(original).equals(BoardFile.hexNode(next))) { return; }
        beginHexEdit(at);
        board().setHex(at, next);
    }

    /** The classic editor records its existing paint commands in this document's shared undo history. */
    public void beginHexEdit(Coords at) {
        stroke.putIfAbsent(at, board().getHex(at).duplicate());
        for (int direction = 0; direction < 6; direction++) {
            Coords neighbor = at.translated(direction);
            if (board().contains(neighbor)) { stroke.putIfAbsent(neighbor, board().getHex(neighbor).duplicate()); }
        }
    }

    /** Classic brushes may visit unchanged hexes; those visits must not create undo entries. */
    public void finishClassicStroke() {
        if (stroke.entrySet().stream().allMatch(entry -> BoardFile.hexNode(entry.getValue())
              .equals(BoardFile.hexNode(board().getHex(entry.getKey()))))) { stroke.clear(); }
        finishStroke();
    }

    /** A new or resized board from the classic editor becomes this same session's current document. */
    public void replaceBoard(Board board) {
        replace(board, null);
    }

    public void finishStroke() {
        if (!movingObjects.isEmpty()) {
            Map<Selection, BoardDecoration> destinations = new LinkedHashMap<>();
            for (Selection item : movingObjects.keySet()) {
                board().getHex(item.coords()).getDecorations().stream().filter(object -> object.id().equals(item.object())).findFirst().ifPresent(object -> {
                    var destination = positioned(item.coords(), object);
                    destinations.put(destination.getKey(), destination.getValue());
                });
            }
            moveObjects(destinations);
        }
        moveHexes(); dragOrigin = moveTarget = null; movingObjects.clear();
        if (!stroke.isEmpty()) {
            // Reconcile after all stamped hexes are installed, so adjacent copies can keep their joined route sides.
            if (sampledHex != null && (tool == Tool.PAINT || tool == Tool.ERASE)) { keepJoinedSides(painted); }
            typeBridges();
            Map<Coords, Hex> after = new LinkedHashMap<>();
            stroke.keySet().forEach(at -> after.put(at, board().getHex(at).duplicate()));
            long id = ++nextId;
            undo.push(new Edit(Map.copyOf(stroke), Map.copyOf(after), currentId, id));
            // The stroke holds every changed hex and its neighbours: exactly the hexes whose checks can change.
            validate(stroke.keySet());
            currentId = id; redo.clear(); stroke.clear();
            // Bound retained stroke history, not the number of cells a legitimate stroke can edit.
            while (undo.size() > 200) { undo.removeLast(); }
            revision++;
        }
        painted.clear(); placed = false; routePaintFrom = null; sculptLevel = null; groupBase = Map.of();
    }

    private void history(ArrayDeque<Edit> from, ArrayDeque<Edit> to, boolean forward) {
        if (from.isEmpty()) { return; }
        Edit edit = from.pop();
        Map<megamek.common.board.BoardLocation, Hex> restored = new LinkedHashMap<>();
        (forward ? edit.after() : edit.before()).forEach((at, hex) -> restored.put(
              megamek.common.board.BoardLocation.of(at, board().getBoardId()), hex.duplicate()));
        board().setHexes(restored);
        validate(edit.before().keySet());
        currentId = forward ? edit.afterId() : edit.beforeId(); to.push(edit);
        selection.clear();
        Hex selectedHex = selected == null ? null : board().getHex(selected);
        if (selectedHex == null || selectedHex.getDecorations().stream().noneMatch(object -> object.id().equals(selectedObject))) {
            selectedObject = "";
        }
    }

    private void addComponent(Hex hex, BoardEditorBlueprint.Component definition) {
        if (!definition.present().isEmpty() && definition.isPresent(name -> hex.containsTerrain(Terrains.getType(name)))) { return; }
        for (String token : definition.defaults().split(";")) {
            if (!token.isBlank()) { Terrain terrain = new Terrain(token); if (!hex.containsTerrain(terrain.getType())) { hex.addTerrain(terrain); } }
        }
        applyChoiceMaterials(hex, definition);
    }

    private void setTerrain(Hex hex, String name, int value) {
        int type = Terrains.getType(name); require(type > 0, "Unknown terrain " + name);
        Terrain old = hex.getTerrain(type);
        Terrain next = old == null ? new Terrain(type, value) : new Terrain(type, value, old.hasExitsSpecified(), old.getExits());
        List<String> errors = new ArrayList<>(); require(next.isValid(errors), String.join("\n", errors));
        if (type == Terrains.FOLIAGE_ELEV) {
            Hex candidate = hex.duplicate(); candidate.addTerrain(next);
            require(candidate.isValid(errors), String.join("\n", errors));
        }
        hex.addTerrain(next);
        if (type == Terrains.INDUSTRIAL_ELEVATOR && old == null) {
            var elevator = new megamek.common.IndustrialElevator(megamek.common.board.BoardLocation.of(hex.getCoords(), 0),
                  value, Math.max(0, value), 100);
            hex.addTerrain(new Terrain(type, value, true, elevator.encodeExits()));
        }
        if (type == Terrains.WOODS || type == Terrains.JUNGLE) {
            hex.removeTerrain(type == Terrains.WOODS ? Terrains.JUNGLE : Terrains.WOODS);
            // Keep the low-canopy option; choose a valid normal canopy through the shared Hex rules.
            if (hex.terrainLevel(Terrains.FOLIAGE_ELEV) != 1) {
                for (int candidate : new int[] { 2, 3 }) {
                    hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, candidate));
                    if (hex.isValid(null)) { break; }
                }
            }
        }
        blueprint.components().stream().filter(owner -> owner.fields().stream().anyMatch(field -> field.terrain().equals(name)))
              .findFirst().ifPresent(owner -> applyChoiceMaterials(hex, owner));
    }

    private static void applyChoiceMaterials(Hex hex, BoardEditorBlueprint.Component owner) {
        for (var field : owner.fields()) {
            for (var choice : field.choices()) {
                if (choice.material().isEmpty() || hex.terrainLevel(Terrains.getType(field.terrain())) != choice.value()) { continue; }
                var styles = new LinkedHashMap<>(hex.getAppearance());
                var old = styles.get(owner.id());
                styles.put(owner.id(), new HexAppearance(old == null ? null : old.variant(), old == null ? null : old.asset(),
                      choice.material(), old == null ? null : old.strength()));
                hex.setAppearance(styles);
            }
        }
    }

    private void edge(Hex hex, String name, int direction) {
        require(direction >= 0 && direction < 6, "A hex has six sides.");
        int type = Terrains.getType(name); Terrain old = hex.getTerrain(type);
        if (old == null && type == Terrains.CLIFF_TOP) { old = new Terrain(type, 1, true, 0); }
        require(old != null, "Add this terrain first.");
        require(old.getExits() >= 0 && old.getExits() <= 63, "This legacy value selects artwork; it cannot be edited as an edge mask.");
        int exits = old.getExits() ^ (1 << direction);
        if (type == Terrains.CLIFF_TOP && (exits & (1 << direction)) != 0) {
            Hex neighbor = board().getHex(selected.translated(direction));
            require(hex.canHaveCliffTopTowards(neighbor), "A manual cliff edge needs a drop of one or two levels; larger cliffs are automatic.");
        }
        if (type == Terrains.CLIFF_TOP && exits == 0) { hex.removeTerrain(type); }
        else { hex.addTerrain(new Terrain(type, old.getLevel(), true, exits)); }
    }

    private BoardDecoration object(Hex hex) {
        return hex.getDecorations().stream().filter(d -> d.id().equals(selectedObject)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Select an object first."));
    }

    /** Contents keep authored list positions; decal slots show the same top-to-bottom order as the renderer. */
    private static List<BoardDecoration> contentsOrder(Hex hex) {
        var paint = hex.getDecorations().stream().filter(d -> d.kind().equals("decal"))
              .sorted(Comparator.comparingInt(BoardDecoration::drawOrder).thenComparing(BoardDecoration::id).reversed()).iterator();
        return hex.getDecorations().stream().map(d -> d.kind().equals("decal") ? paint.next() : d).toList();
    }

    private void reorderObject(Hex hex, String id, String destination) {
        int split = destination.indexOf(':');
        require(split > 0, "Choose a position in the contents list.");
        boolean before = destination.substring(0, split).equals("before");
        require(before || destination.substring(0, split).equals("after"), "Choose before or after the target.");
        String targetId = destination.substring(split + 1);
        if (id.equals(targetId)) { return; }
        var objects = new ArrayList<>(contentsOrder(hex));
        var moving = objects.stream().filter(d -> d.id().equals(id)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException("The dragged object is no longer in this hex."));
        var target = objects.stream().filter(d -> d.id().equals(targetId)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException("The target object is no longer in this hex."));
        require(Objects.equals(moving.placement().receiver(), target.placement().receiver()), "Reorder objects on the same surface.");
        objects.remove(moving); objects.add(objects.indexOf(target) + (before ? 0 : 1), moving);
        if (moving.kind().equals("decal")) {
            var paint = objects.stream().filter(d -> d.kind().equals("decal")
                  && Objects.equals(d.placement().receiver(), moving.placement().receiver())).toList();
            long order = Math.max(paint.stream().mapToInt(BoardDecoration::drawOrder).max().orElse(0),
                  (long) Integer.MIN_VALUE + paint.size() - 1);
            for (var decal : paint) {
                objects.set(objects.indexOf(decal), objectValue(decal, "order", Long.toString(order--)));
            }
        }
        hex.setDecorations(objects);
    }
    private void replaceObject(Hex hex, java.util.function.UnaryOperator<BoardDecoration> transform) {
        BoardDecoration object = object(hex), next = transform.apply(object);
        hex.setDecorations(hex.getDecorations().stream().map(d -> d.id().equals(object.id()) ? next : d).toList());
    }

    private BoardDecoration objectValue(BoardDecoration d, String property, String value) {
        if (megamek.common.board.MaglevRoute.isRoute(d)) {
            require(Set.of("level", "offset", "receiver", "name").contains(property), "A maglev route stays centred on its hexes; paint, erase or toggle its sides to change it.");
            if (property.equals("receiver")) {
                require(value.equals("absolute") || value.equals("ground/top"), "Choose fixed elevation or height above ground.");
            }
        }
        double x = d.x(), y = d.y(), rotation = d.rotation(), scale = d.scale();
        double rotationX = d.rotationX(), rotationY = d.rotationY();
        boolean clipToHex = d.clipToHex();
        boolean mirror = d.mirror(), bare = d.bare(); var placement = d.placement(); int order = d.drawOrder(); String name = d.name();
        var stretch = d.stretch();
        var colours = d.colours();
        switch (property) {
            case "x" -> x = Double.parseDouble(value);
            case "y" -> y = Double.parseDouble(value);
            case "rotation" -> rotation = Double.parseDouble(value);
            case "rotationX" -> rotationX = Double.parseDouble(value);
            case "rotationY" -> rotationY = Double.parseDouble(value);
            case "scale" -> scale = Double.parseDouble(value);
            // Width, length and height factors in the object's own axes; the inspector shows them beside the scale.
            case "stretchX" -> stretch = new BoardDecoration.Stretch(Double.parseDouble(value), stretch.y(), stretch.z());
            case "stretchY" -> stretch = new BoardDecoration.Stretch(stretch.x(), Double.parseDouble(value), stretch.z());
            case "stretchZ" -> stretch = new BoardDecoration.Stretch(stretch.x(), stretch.y(), Double.parseDouble(value));
            // A colour slot's replacement (#rrggbb); empty returns the slot to the model's own colour.
            case "colour0", "colour1", "colour2", "colour3" ->
                  colours = colours.with(property.charAt(6) - '0', value.isEmpty() ? null : value);
            case "clipToHex" -> clipToHex = Boolean.parseBoolean(value);
            case "mirror" -> mirror = Boolean.parseBoolean(value);
            case "bare" -> bare = Boolean.parseBoolean(value);
            case "name" -> name = value;
            case "order" -> order = Integer.parseInt(value);
            case "level" -> placement = BoardDecoration.Placement.absolute(Double.parseDouble(value));
            case "offset" -> placement = BoardDecoration.Placement.surface(placement.receiver() == null ? "ground" : placement.receiver().terrain(),
                  placement.receiver() == null ? "top" : placement.receiver().surface(), Double.parseDouble(value));
            case "receiver" -> {
                if (value.equals("absolute")) { placement = BoardDecoration.Placement.absolute(selected == null ? 0 : board().getHex(selected).getLevel()); }
                else {
                    String[] parts = value.split("/");
                    require(parts.length == 2, "Choose a valid supporting surface.");
                    placement = BoardDecoration.Placement.surface(parts[0], parts[1], 0);
                }
            }
            default -> throw new IllegalArgumentException("Unknown object property " + property);
        }
        return new BoardDecoration(d.id(), d.kind(), d.asset(), name, x, y, rotation, mirror, scale, placement, order, clipToHex,
              rotationX, rotationY, d.group(), bare, d.connections(), stretch, colours);
    }

    private static void copyInto(Hex source, Hex target) {
        target.removeAllTerrains(); for (int type : source.getTerrainTypes()) { target.addTerrain(new Terrain(source.getTerrain(type))); }
        target.setLevel(source.getLevel()); target.setTheme(source.getTheme()); target.setAppearance(source.getAppearance());
        target.setDecorations(BoardDecoration.copies(source.getDecorations()));
    }
    private static void require(boolean valid, String message) { if (!valid) { throw new IllegalArgumentException(message); } }
}
