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
    public enum Tool { SELECT, PAINT, ERASE }
    public enum Action {
        TOOL, COMPONENT, BRUSH, ADD_COMPONENT, REMOVE_COMPONENT, TERRAIN, REMOVE_TERRAIN, EDGE, AUTO_EDGES,
        ELEVATION, ELEVATOR, THEME, VARIANT, BLEND, ASSET, SELECT_OBJECT, OBJECT_VALUE, DUPLICATE_OBJECT, REMOVE_OBJECT, REORDER_OBJECT,
        COPY, PASTE, UNDO, REDO, NEW, OPEN, SAVE, SAVE_AS, VALIDATE, MAP_THEME,
        CHOOSE_BRUSH, BRUSH_VALUE, SAMPLE, CLEAR_SELECTION, DELETE_SELECTION
    }
    /** Text values are parsed and validated here, never by a view that also owns game state. */
    public record Command(Action action, String target, String value) {
        public Command(Action action) { this(action, "", ""); }
        public Command(Action action, String value) { this(action, "", value); }
    }
    public record Property(String terrain, int value, int exits, boolean explicit) { }
    public record Selection(Coords coords, String object) { }
    public record Brush(String key, String label, String component, int elevation, String theme,
          List<Property> properties, Map<String, HexAppearance> appearance, BoardDecoration object,
          boolean elevationEnabled, boolean themeEnabled, boolean precision, boolean magnetic) { }
    /** Immutable data published at the EDT/render boundary; it contains no mutable Hex or Board. */
    public record Snapshot(long revision, String title, boolean dirty, boolean canUndo, boolean canRedo,
          Tool tool, String component, String asset, int brush, Coords selected, int elevation, String theme,
          List<Property> properties, Map<String, HexAppearance> appearance, List<BoardDecoration> objects,
          String object, String message, int width, int height, Brush activeBrush, List<Selection> selection,
          List<Coords> movePreview) {
        public Property property(String name) { return properties.stream().filter(p -> p.terrain().equals(name)).findFirst().orElse(null); }
    }

    private record Edit(Map<Coords, Hex> before, Map<Coords, Hex> after, long beforeId, long afterId) { }
    private final Game game = new Game();
    private final BoardEditorBlueprint blueprint;
    private final ArrayDeque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final Map<Coords, Hex> stroke = new LinkedHashMap<>();
    private final java.util.Set<Coords> painted = new java.util.HashSet<>();
    private Tool tool = Tool.SELECT;
    private String component = "ground", selectedObject = "", message = "Select a hex to edit it.";
    private String brushComponent = "ground", brushKey = "ground/clear", brushLabel = "Clear ground";
    private Hex paintTemplate = new Hex(0);
    private boolean brushElevation, brushTheme, precision;
    private boolean magnetic = true;
    private final Set<Selection> selection = new LinkedHashSet<>();
    private final Map<Selection, BoardDecoration> movingObjects = new LinkedHashMap<>();
    private Coords dragOrigin, moveTarget;
    private double dragX, dragY;
    private Coords selected;
    private int brush = 1;
    private Path path;
    private Hex clipboard;
    private long revision, currentId, savedId, nextId;
    private boolean placed;
    private boolean continuousEdit;

    public BoardEditorSession() { this(BoardEditorBlueprint.get()); }
    public BoardEditorSession(BoardEditorBlueprint blueprint) {
        this.blueprint = blueprint;
        game.setPhase(GamePhase.LOUNGE);
        replace(Board.createEmptyBoard(16, 17), null);
    }
    public Game game() { return game; }
    public Board board() { return game.getBoard(); }
    public Path path() { return path; }
    public boolean dirty() { return currentId != savedId || !stroke.isEmpty(); }
    public String title() { return (dirty() ? "* " : "") + (path == null ? "Untitled board" : path.getFileName()); }

    public Snapshot snapshot() {
        Hex hex = selected == null ? null : board().getHex(selected);
        BoardDecoration prototype = paintTemplate.getDecorations().stream().findFirst().orElse(null);
        Brush active = new Brush(brushKey, brushLabel, brushComponent, paintTemplate.getLevel(),
              paintTemplate.getTheme() == null ? "" : paintTemplate.getTheme(), properties(paintTemplate),
              paintTemplate.getAppearance(), prototype, brushElevation, brushTheme, precision, magnetic);
        return new Snapshot(revision, title(), dirty(), !undo.isEmpty(), !redo.isEmpty(), tool, component,
              prototype == null ? "" : prototype.asset(), brush,
              selected, hex == null ? 0 : hex.getLevel(), hex == null || hex.getTheme() == null ? "" : hex.getTheme(),
              properties(hex), hex == null ? Map.of() : hex.getAppearance(), hex == null ? List.of() : contentsOrder(hex),
              selectedObject, message, board().getWidth(), board().getHeight(), active, List.copyOf(selection), movePreview());
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

    private void replace(Board board, Path path) {
        this.path = path;
        undo.clear(); redo.clear(); stroke.clear(); painted.clear();
        selected = null; selectedObject = ""; placed = false;
        selection.clear(); movingObjects.clear(); dragOrigin = moveTarget = null;
        currentId = savedId = ++nextId;
        game.setBoard(board);
        revision++;
    }

    public void open(Path file) throws IOException {
        Board loaded = BoardFile.read(file); // Failure leaves the document, history and path intact.
        replace(loaded, BoardFile.isNativeName(file.toString()) ? file : null);
        message = BoardFile.isNativeName(file.toString()) ? "Opened " + file.getFileName()
              : "Imported " + file.getFileName() + ". Save creates a .board2 file; the original is preserved.";
    }

    public void save(Path file) throws IOException {
        finishStroke();
        BoardFile.save(board(), file);
        path = file;
        savedId = currentId;
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
                    paintTemplate = new Hex(0);
                    paintTemplate.setDecorations(List.of(new BoardDecoration("brush", design.kind(), design.id(), null,
                          0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0, false)));
                    brushComponent = ""; brushKey = design.id(); brushLabel = design.label(); tool = Tool.PAINT;
                    message = "";
                }
                case SELECT_OBJECT -> {
                    selectedObject = command.value(); tool = Tool.SELECT;
                    selection.clear(); selection.add(new Selection(selected, selectedObject));
                }
                case OBJECT_VALUE -> editObject(command.target(), command.value());
                case REORDER_OBJECT -> edit(hex -> reorderObject(hex, command.target(), command.value()));
                case DUPLICATE_OBJECT -> edit(hex -> {
                    BoardDecoration copy = object(hex).duplicate();
                    var objects = new ArrayList<>(hex.getDecorations()); objects.add(copy); hex.setDecorations(objects); selectedObject = copy.id();
                });
                case REMOVE_OBJECT -> {
                    String id = command.target().isEmpty() ? selectedObject : command.target();
                    edit(hex -> hex.setDecorations(hex.getDecorations().stream().filter(d -> !d.id().equals(id)).toList()));
                    selection.removeIf(item -> item.object().equals(id));
                    if (selectedObject.equals(id)) { selectedObject = ""; }
                }
                case COPY -> { require(selected != null, "Select a hex first"); clipboard = board().getHex(selected).duplicate(); message = "Copied hex, including appearance and objects."; }
                case PASTE -> { require(clipboard != null, "Copy a hex first"); edit(hex -> copyInto(clipboard, hex)); }
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
                case VALIDATE -> {
                    List<String> errors = new ArrayList<>(); board().isValid(errors);
                    message = errors.isEmpty() ? "Board rules validation passed." : String.join("\n", errors.subList(0, Math.min(12, errors.size())));
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

    private boolean chooseSave(Window owner, boolean saveAs) {
        Path target = path;
        if (saveAs || target == null) {
            JFileChooser chooser = new JFileChooser(path == null ? Configuration.boardsDir() : path.toFile().getParentFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("3D board (*.board2)", "board2"));
            chooser.setSelectedFile(path == null ? new java.io.File("Untitled.board2") : path.toFile());
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
        if (at == null || !board().contains(at)) { return; }
        try {
            if (tool == Tool.SELECT) {
                selectPointer(at, x, y, drag, pickedObject, additive);
            } else if (!paintTemplate.getDecorations().isEmpty()) {
                BoardDecoration prototype = paintTemplate.getDecorations().getFirst();
                if (tool == Tool.ERASE) {
                    selected = at; selectedObject = "";
                    for (Coords spot : brush(at)) {
                        if (painted.add(spot)) { change(spot, hex -> hex.setDecorations(hex.getDecorations().stream()
                              .filter(d -> !d.asset().equals(prototype.asset())).toList())); }
                    }
                } else if (!placed) {
                    selected = at;
                    String surface = receiver.equals("bridge") ? "deck" : receiver.equals("building") ? "roof" : "top";
                    double offset = prototype.placement().offset() == null ? 0 : prototype.placement().offset();
                    BoardDecoration object = prototype.duplicate().transform(precision ? x : 0, precision ? y : 0, prototype.rotation(),
                          prototype.mirror(), prototype.scale(), BoardDecoration.Placement.surface(receiver, surface, offset));
                    if (precision && magnetic) { object = BoardEditorSnapping.snap(board(), blueprint, at, object, Set.of()); }
                    var destination = positioned(at, object);
                    selected = destination.getKey().coords();
                    BoardDecoration placedObject = destination.getValue();
                    change(selected, hex -> { var objects = new ArrayList<>(hex.getDecorations()); objects.add(placedObject); hex.setDecorations(objects); });
                    selectedObject = object.id(); placed = true;
                }
            } else {
                selected = at; selectedObject = ""; component = brushComponent;
                var definition = blueprint.component(brushComponent);
                for (Coords spot : brush(at)) {
                    if (!painted.add(spot)) { continue; }
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
                            styles.put(brushComponent, paintTemplate.getAppearance().get(brushComponent));
                        } else { styles.remove(brushComponent); }
                        hex.setAppearance(styles);
                        if (tool != Tool.ERASE && brushComponent.equals("ground")) {
                            if (brushTheme) { hex.setTheme(paintTemplate.getTheme()); }
                            if (brushElevation) { hex.setLevel(paintTemplate.getLevel()); }
                        }
                    });
                }
            }
            if (tool != Tool.SELECT) {
                selection.clear();
                if (selected != null) { selection.add(new Selection(selected, selectedObject)); }
            }
            message = "";
        } catch (IllegalArgumentException failure) { message = failure.getMessage(); }
        revision++;
    }

    /** Shift selects a group; ordinary dragging moves it. Whole-hex moves commit atomically on release. */
    private void selectPointer(Coords at, double x, double y, boolean drag, String object, boolean additive) {
        Selection hit = new Selection(at, object == null ? "" : object);
        if (additive) {
            if (!selection.isEmpty() && selection.iterator().next().object().isEmpty() != hit.object().isEmpty()) {
                if (drag) { return; }
                selection.clear();
            }
            if (!drag && selection.contains(hit)) { selection.remove(hit); }
            else { selection.add(hit); }
            selected = at; selectedObject = hit.object(); return;
        }
        if (!drag) {
            if (!selection.contains(hit)) { selection.clear(); selection.add(hit); }
            selected = at; selectedObject = hit.object(); dragOrigin = at; dragX = x; dragY = y;
            movingObjects.clear();
            for (Selection item : selection) {
                if (!item.object().isEmpty()) {
                    board().getHex(item.coords()).getDecorations().stream().filter(d -> d.id().equals(item.object())).findFirst()
                          .ifPresent(d -> movingObjects.put(item, d));
                }
            }
        } else if (dragOrigin != null) {
            if (movingObjects.isEmpty()) { moveTarget = at; }
            else {
                double dx = x - dragX, dy = y - dragY;
                if (magnetic) {
                    var grabbed = movingObjects.entrySet().stream().filter(entry -> entry.getValue().id().equals(selectedObject)).findFirst().orElseThrow();
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
        selected = moveTarget; selection.clear(); targets.forEach(at -> selection.add(new Selection(at, "")));
    }

    private void deleteSelection() {
        for (Selection item : selection) {
            change(item.coords(), hex -> {
                if (item.object().isEmpty()) { copyInto(new Hex(0), hex); }
                else { hex.setDecorations(hex.getDecorations().stream().filter(d -> !d.id().equals(item.object())).toList()); }
            });
        }
        selectedObject = ""; selection.clear(); finishStroke();
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
        if (placement.receiver() != null && !placement.receiver().terrain().equals("ground")) {
            String receiver = placement.receiver().terrain();
            Hex receiving = board().getHex(target);
            if (blueprint.components().stream().filter(definition -> definition.receiver().equals(receiver))
                  .noneMatch(definition -> definition.isPresent(name -> receiving.containsTerrain(Terrains.getType(name))))) {
                placement = BoardDecoration.Placement.surface("ground", "top", placement.offset());
            }
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
        BoardDecoration next = objectValue(object(board().getHex(selected)), property, value);
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
        paintTemplate = next; brushComponent = owner; brushElevation = false; brushTheme = false;
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
        else { throw new IllegalArgumentException("Unknown brush setting " + target); }
        paintTemplate = next;
    }

    private void sampleBrush() {
        require(selected != null, "Select a hex first.");
        Hex hex = board().getHex(selected);
        if (!selectedObject.isEmpty()) {
            BoardDecoration object = object(hex);
            paintTemplate = new Hex(0); paintTemplate.setDecorations(List.of(object));
            brushComponent = ""; brushKey = object.asset();
            var asset = blueprint.asset(object.asset()); brushLabel = asset == null ? object.asset() : asset.label();
        } else {
            paintTemplate = hex.duplicate(); paintTemplate.setDecorations(List.of());
            applyChoiceMaterials(paintTemplate, blueprint.component(component));
            brushComponent = component; brushKey = component + "/sample"; brushLabel = blueprint.component(component).label() + " sample";
        }
        tool = Tool.PAINT;
    }

    public void adjustElevation(Map<Coords, Integer> amounts) {
        if (amounts.values().stream().allMatch(amount -> amount == 0)) { return; }
        amounts.forEach((at, amount) -> change(at, hex -> hex.setLevel(Math.addExact(hex.getLevel(), amount))));
        revision++;
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
        stroke.putIfAbsent(at, original.duplicate());
        for (int direction = 0; direction < 6; direction++) {
            Coords neighbor = at.translated(direction);
            if (board().contains(neighbor)) { stroke.putIfAbsent(neighbor, board().getHex(neighbor).duplicate()); }
        }
        board().setHex(at, next);
    }

    public void finishStroke() {
        if (!movingObjects.isEmpty()) {
            Map<Selection, BoardDecoration> destinations = new LinkedHashMap<>();
            for (Selection item : selection) {
                board().getHex(item.coords()).getDecorations().stream().filter(object -> object.id().equals(item.object())).findFirst().ifPresent(object -> {
                    var destination = positioned(item.coords(), object);
                    destinations.put(destination.getKey(), destination.getValue());
                });
            }
            moveObjects(destinations);
        }
        moveHexes(); dragOrigin = moveTarget = null; movingObjects.clear();
        if (!stroke.isEmpty()) {
            Map<Coords, Hex> after = new LinkedHashMap<>();
            stroke.keySet().forEach(at -> after.put(at, board().getHex(at).duplicate()));
            long id = ++nextId;
            undo.push(new Edit(Map.copyOf(stroke), Map.copyOf(after), currentId, id));
            currentId = id; redo.clear(); stroke.clear();
            // Bound retained stroke history, not the number of cells a legitimate stroke can edit.
            while (undo.size() > 200) { undo.removeLast(); }
            revision++;
        }
        painted.clear(); placed = false;
    }

    private void history(ArrayDeque<Edit> from, ArrayDeque<Edit> to, boolean forward) {
        if (from.isEmpty()) { return; }
        Edit edit = from.pop();
        Map<megamek.common.board.BoardLocation, Hex> restored = new LinkedHashMap<>();
        (forward ? edit.after() : edit.before()).forEach((at, hex) -> restored.put(
              megamek.common.board.BoardLocation.of(at, board().getBoardId()), hex.duplicate()));
        board().setHexes(restored);
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
        double x = d.x(), y = d.y(), rotation = d.rotation(), scale = d.scale();
        boolean clipToHex = d.clipToHex();
        boolean mirror = d.mirror(); var placement = d.placement(); int order = d.drawOrder(); String name = d.name();
        switch (property) {
            case "x" -> x = Double.parseDouble(value);
            case "y" -> y = Double.parseDouble(value);
            case "rotation" -> rotation = Double.parseDouble(value);
            case "scale" -> scale = Double.parseDouble(value);
            case "clipToHex" -> clipToHex = Boolean.parseBoolean(value);
            case "mirror" -> mirror = Boolean.parseBoolean(value);
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
        return new BoardDecoration(d.id(), d.kind(), d.asset(), name, x, y, rotation, mirror, scale, placement, order, clipToHex);
    }

    private static void copyInto(Hex source, Hex target) {
        target.removeAllTerrains(); for (int type : source.getTerrainTypes()) { target.addTerrain(new Terrain(source.getTerrain(type))); }
        target.setLevel(source.getLevel()); target.setTheme(source.getTheme()); target.setAppearance(source.getAppearance());
        target.setDecorations(source.getDecorations().stream().map(BoardDecoration::duplicate).toList());
    }
    private static void require(boolean valid, String message) { if (!valid) { throw new IllegalArgumentException(message); } }
}
