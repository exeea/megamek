/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.dialogs.MMAboutDialog;
import megamek.client.ui.dialogs.buttonDialogs.CommonSettingsDialog;
import megamek.client.ui.util.UIUtil;
import megamek.client.ui.util.KeyCommandBind;
import megamek.codeUtilities.StringUtility;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.board.BoardEvent;
import megamek.common.event.board.BoardListenerAdapter;
import megamek.common.event.board.GameBoardNewEvent;
import megamek.common.game.Game;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Entity;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.logging.MMLogger;

/** Native preview/editor input. The Game/Board and editor operations are the only authoritative state. */
final class GpuMapSource implements BoardSource {
    private static final MMLogger LOGGER = MMLogger.create(GpuMapSource.class);
    /** A tab separates a row of the hex card from its detail ({@link #hexCard}). */
    static final char COLUMN = '\t';

    private final Game game;
    private final Window owner;
    /** EDT-owned measurement; map previews and battles share the same model and native panel. */
    private RulerModel ruler;
    private final BoardEditorSession editor;
    private final Runnable requestClose;
    private final Runnable requestClassic;
    /** Only Swing owns the active brush stroke; render input carries the board generation it picked. */
    private Board editorStrokeBoard;
    /** Swing queues wheel notches (hex, levels) in arrival order until a capture; releasing Ctrl commits the undo entry. */
    private final List<Map.Entry<Coords, Integer>> pendingElevation = new ArrayList<>();
    private boolean editorElevationStroke;
    /** Swing-owned brush settings are published only as a preview, never read directly by the render thread. */
    private record EditorBrush(Coords center, long generation, List<Coords> hexes, String hint) { }
    private volatile EditorBrush editorBrush = new EditorBrush(null, -1, List.of(), "");

    private final BoardArtwork artwork = new BoardArtwork();
    private volatile List<String> editorThemes = artwork.themes();
    private final BoardScene.PixelPool images = new BoardScene.PixelPool();
    private final GpuAtmosphereControls atmosphere;
    private final Timer timer;
    private final BoardListenerAdapter boardListener = new BoardListenerAdapter() {
        @Override public void boardNewBoard(BoardEvent event) {
            onSwing(() -> { if (!closed && event.getSource() == board) { dirtyAll(); } });
        }
        @Override public void boardChangedAllHexes(BoardEvent event) { boardNewBoard(event); }
        @Override public void boardChangedHex(BoardEvent event) {
            onSwing(() -> {
                if (closed || event.getSource() != board) { return; }
                Coords c = event.getCoords();
                if (c == null) { dirtyAll(); return; }
                Rectangle local = new Rectangle(c.getX() - 1, c.getY() - 1, 3, 3);
                dirtyHexes = dirtyHexes == null ? local : dirtyHexes.union(local);
                for (int x = local.x; x < local.x + local.width; x++) {
                    for (int y = local.y; y < local.y + local.height; y++) { artwork.invalidate(new Coords(x, y)); }
                }
            });
        }
    };
    private final GameListenerAdapter gameListener = new GameListenerAdapter() {
        @Override public void gameBoardNew(GameBoardNewEvent event) { onSwing(() -> refresh()); }
    };
    private final IPreferenceChangeListener preferences = event -> onSwing(() -> {
        if (isClosed()) { return; }
        uiPreferences = UiPreferences.capture();
        // Label/color preferences affect presentation only; interned terrain pixels and feature geometry are reused.
        dirtyAll();
    });
    private Board board;
    private List<BoardScene.Tile> tiles = List.of();
    /** EDT-owned derived editor annotations; never supplied to map previews or gameplay. */
    private final Map<Coords, BoardTactical> terrainMarkers = new HashMap<>();
    private BoardTactical editorTerrain = BoardTactical.EMPTY;
    private boolean terrainDirty = true;
    private Rectangle dirtyHexes;
    private long boardGeneration;
    private record Publication(Frame frame, BoardEditorSession.Snapshot editor) { }
    private volatile Publication publication;
    /** Render-thread latch: selection and geometry must describe the same EDT capture for the entire frame. */
    private Publication rendering;
    private volatile UiPreferences uiPreferences = UiPreferences.capture();
    private volatile PhaseStatus phaseStatus = new PhaseStatus("", false);
    private volatile Coords hoverCoords;
    private Coords contextCoords;
    private volatile boolean closed;
    GpuMapSource(Game game, Window owner, BoardEditorSession editor) {
        this(game, owner, editor, null);
    }

    GpuMapSource(Game game, Window owner, BoardEditorSession editor, Runnable requestClose) {
        this(game, owner, editor, requestClose, null);
    }

    GpuMapSource(Game game, Window owner, BoardEditorSession editor, Runnable requestClose, Runnable requestClassic) {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        if (editor != null && editor.game() != game) { throw new IllegalArgumentException("The editor owns its Game"); }
        this.game = game;
        this.owner = owner;
        this.editor = editor;
        this.requestClose = requestClose;
        this.requestClassic = requestClassic;
        atmosphere = new GpuAtmosphereControls(() -> owner, game::getBoard, game::getPlanetaryConditions, () -> closed);
        timer = new Timer(33, event -> refresh());
        try {
            refresh();
            game.addGameListener(gameListener);
            PreferenceManager.getClientPreferences().addPreferenceChangeListener(preferences);
            GUIPreferences.getInstance().addPreferenceChangeListener(preferences);
            timer.start();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    public void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        if (closed) { return; }
        flushEditorElevation();
        Board current = game.getBoard();
        if (current != board) {
            finishEditorStroke();
            if (board != null) { board.removeBoardListener(boardListener); }
            board = current;
            board.addBoardListener(boardListener);
            boardGeneration++;
            closeMeasuring();
            contextCoords = null;
            images.clear();
            terrainMarkers.clear();
            dirtyAll();
            atmosphere.reset();
        }
        if (terrainDirty || dirtyHexes != null) {
            if (terrainDirty) { terrainMarkers.clear(); }
            Rectangle area = terrainDirty ? new Rectangle(0, 0, board.getWidth(), board.getHeight())
                  : dirtyHexes.intersection(new Rectangle(0, 0, board.getWidth(), board.getHeight()));
            List<BoardScene.Tile> next = terrainDirty
                  ? new ArrayList<>(Collections.nCopies(board.getWidth() * board.getHeight(), null)) : new ArrayList<>(tiles);
            for (int x = area.x; x < area.x + area.width; x++) {
                for (int y = area.y; y < area.y + area.height; y++) {
                    Coords coords = new Coords(x, y);
                    int index = x * board.getHeight() + y;
                    BoardScene.Tile previous = tiles.size() == next.size() ? tiles.get(index) : null;
                    boolean changed = terrainDirty || dirtyHexes != null && dirtyHexes.contains(x, y);
                    BoardScene.Tile tile = previous;
                    if (changed) {
                        var pixels = artwork.capture(board, coords, true);
                        tile = BoardScene.captureTile(board.getHex(coords), pixels, previous, images, board::getHex);
                        if (editor != null) {
                            var markers = BoardEditorTerrain.capture(board.getHex(coords), coords, pixels.blankTerrains());
                            if (markers == BoardTactical.EMPTY) { terrainMarkers.remove(coords); }
                            else { terrainMarkers.put(coords, markers); }
                        }
                    }
                    next.set(index, tile.equals(previous) ? previous : tile);
                }
            }
            tiles = List.copyOf(next);
            editorTerrain = editor == null ? BoardTactical.EMPTY : new BoardTactical(
                  terrainMarkers.values().stream().flatMap(value -> value.fills().stream()).toList(),
                  terrainMarkers.values().stream().flatMap(value -> value.labels().stream()).toList());
            if (editor != null) {
                // Recompute boundaries only when their terrain changes, not on every hover/inspector refresh.
                editorTerrain = BoardDeploymentGeometry.editorOutlines(mapScene(editorTerrain));
            }
            images.retain(tiles);
            terrainDirty = false;
            dirtyHexes = null;

        }
        Coords hover = hoverCoords;
        if (editor != null) {
            List<Coords> footprint = editor.brush(hover);
            editorBrush = new EditorBrush(hover, boardGeneration, footprint, editor.sculptHint(hover, footprint.size()));
        }
        RulerModel.Snapshot measurement = ruler == null ? RulerModel.Snapshot.NONE : ruler.capture();
        phaseStatus = new PhaseStatus(editor == null ? measuringStatus(measurement) : editor.title(), false);
        Coords inspected = contextCoords == null ? hover : contextCoords;
        String tooltip = inspected == null || !board.contains(inspected) ? "" : hexCard(board.getHex(inspected));
        BoardScene scene = mapScene(editorTerrain.withRuler(measurement.ruler()));
        Frame nextFrame = new Frame(scene, List.of(), contextCoords == null ? null : new BoardScene.Context(contextCoords, List.of()),
              menuCommands(), tooltip,
              new BoardFocus(0, null), boardGeneration, "",
              atmosphere.settings(game.getPlanetaryConditions(), board.isSpace()),
              GpuReportLog.Snapshot.EMPTY, GpuBattleStatus.Snapshot.EMPTY, GpuHudData.EMPTY.withLos(measurement));
        // An unchanged editor state keeps its instance, so the views' identity checks mean a real change.
        var previous = publication == null ? null : publication.editor();
        var state = editor == null ? null : editor.snapshot();
        publication = new Publication(nextFrame, state != null && state.equals(previous) ? previous : state);
    }

    /** Map workspaces expose their existing document and application actions without requiring a game client. */
    private List<BoardScene.Command> menuCommands() {
        List<BoardScene.Command> commands = new ArrayList<>();
        if (requestClassic != null) {
            commands.add(menuCommand("viewClassicBoard", editor == null ? "CommonMenuBar.viewClassicBoard"
                  : "BoardEditor.edit2D", requestClassic));
        }
        if (editor != null) {
            long generation = boardGeneration;
            for (var action : List.of(BoardEditorSession.Action.OPEN, BoardEditorSession.Action.SAVE,
                  BoardEditorSession.Action.SAVE_AS)) {
                String key = switch (action) {
                    case OPEN -> "fileBoardOpen";
                    case SAVE -> "fileBoardSave";
                    default -> "fileBoardSaveAs";
                };
                commands.add(menuCommand(key, "CommonMenuBar." + key,
                      () -> editorCommand(new BoardEditorSession.Command(action), generation)));
            }
        }
        GUIPreferences gui = GUIPreferences.getInstance();
        List<BoardScene.Command> view = List.of(
              viewCommand(ClientGUI.VIEW_TOGGLE_HEX_COORDS, editor == null ? KeyCommandBind.HEX_COORDS : null,
                    gui.getCoordsEnabled(), gui::toggleCoords),
              viewCommand(ClientGUI.VIEW_INC_GUI_SCALE, KeyCommandBind.INC_GUI_SCALE, null,
                    () -> CommonMenuBar.changeGUIScale(true)),
              viewCommand(ClientGUI.VIEW_DEC_GUI_SCALE, KeyCommandBind.DEC_GUI_SCALE, null,
                    () -> CommonMenuBar.changeGUIScale(false)));
        commands.add(new BoardScene.Command("view", Messages.getString("CommonMenuBar.ViewMenu"), "", true,
              false, view, () -> { }));
        commands.add(menuCommand("viewClientSettings", "CommonMenuBar.viewClientSettings", () -> {
            Window parent = owner;
            while (parent != null && !(parent instanceof JFrame)) { parent = parent.getOwner(); }
            CommonSettingsDialog settings = new CommonSettingsDialog((JFrame) parent);
            try { settings.setVisible(true); } finally { settings.dispose(); }
        }));
        commands.add(menuCommand("helpAbout", "CommonMenuBar.helpAbout", () -> new MMAboutDialog(owner).show()));
        if (requestClose != null) { commands.add(menuCommand("close", "Close", requestClose)); }
        return List.copyOf(commands);
    }

    private BoardScene.Command menuCommand(String id, String label, Runnable action) {
        return new BoardScene.Command(id, Messages.getString(label), "", true, false, false, List.of(),
              () -> onSwing(() -> { if (!closed) { action.run(); } }));
    }

    /** Preference state is captured on the EDT; native menu clicks return here to invoke the existing action. */
    private BoardScene.Command viewCommand(String id, KeyCommandBind shortcut, Boolean selected, Runnable action) {
        BoardScene.Command command = menuCommand(id, "CommonMenuBar." + id, action);
        return new BoardScene.Command(id, command.label(), "", true, false, false, List.of(), command.action(),
              shortcut == null ? "" : KeyCommandBind.getDesc(shortcut), selected);
    }

    private BoardScene mapScene(BoardTactical tactical) {
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(),
              Entity.NONE, "", List.of(), null, List.of(), List.of(), List.of(), tactical);
    }

    @Override
    public void reloadAssets() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        if (closed) { return; }
        megamek.common.util.ImageUtil.reloadImages();
        artwork.reload();
        editorThemes = artwork.themes();
        images.clear();
        dirtyAll();
        refresh();
    }

    @Override public List<String> editorThemes() { return editorThemes; }
    private void dirtyAll() { terrainDirty = true; artwork.clear(); }
    private static void onSwing(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) { action.run(); } else { SwingUtilities.invokeLater(action); }
    }
    public Frame takeFrame() { rendering = publication; return rendering.frame(); }
    public UiPreferences uiPreferences() { return uiPreferences; }
    public PhaseStatus phaseStatus() { return phaseStatus; }
    public GpuAtmosphereControls atmosphere() { return atmosphere; }
    public boolean isClosed() { return closed; }
    public void setHover(Coords coords) { hoverCoords = coords; }
    public void inspect(Coords coords) { onSwing(() -> { contextCoords = coords; refresh(); }); }

    public void key(int keyCode, boolean down, int modifiers) {
        onSwing(() -> {
            if (closed || !down) { return; }
            var binds = KeyCommandBind.getAllBindsByKey(keyCode, modifiers);
            // Ctrl+G belongs to Group in the editor; the preview keeps the gameplay Hex Coords shortcut.
            if (editor == null && binds.contains(KeyCommandBind.HEX_COORDS)) {
                GUIPreferences.getInstance().toggleCoords();
            } else if (binds.contains(KeyCommandBind.LOS_SETTING)) {
                changeRuler(boardGeneration, RulerModel::open);
            } else if (binds.contains(KeyCommandBind.INC_GUI_SCALE)) {
                CommonMenuBar.changeGUIScale(true);
            } else if (binds.contains(KeyCommandBind.DEC_GUI_SCALE)) {
                CommonMenuBar.changeGUIScale(false);
            } else if (editor != null && keyCode != KeyEvent.VK_SHIFT && keyCode != KeyEvent.VK_CONTROL
                  && keyCode != KeyEvent.VK_ALT && keyCode != KeyEvent.VK_META) {
                finishEditorStroke();
                editor.key(keyCode, modifiers, owner);
            }
            refresh();
        });
    }
    public void stopKeys() { endEditorStroke(); }

    public boolean isEditor() {
        return editor != null;
    }

    /**
     * The hovered or inspected hex as the map tools' card lists it, one row a line, a {@link #COLUMN} before a row's
     * detail: its number with its level and theme; each terrain a player sees with its terrain factor, by MegaMek's
     * display names as the battle's hex tooltip lists them, so automated and cosmetic terrains, which have none, stay
     * out (the user's decision of 2026-10-03); then why an invalid hex is invalid.
     */
    static String hexCard(Hex hex) {
        String level = Messages.getString("GpuBoard.map.level", hex.getLevel());
        List<String> rows = new ArrayList<>(List.of(Messages.getString("GpuBoard.hud.context.hex",
              hex.getCoords().getBoardNum()) + COLUMN
              + (StringUtility.isNullOrBlank(hex.getTheme()) ? level : level + " \u00B7 " + hex.getTheme())));
        for (int type : hex.getTerrainTypes()) {
            Terrain terrain = hex.getTerrain(type);
            String name = Terrains.getDisplayName(type, terrain.getLevel());
            if (name != null) {
                int factor = terrain.getTerrainFactor();
                rows.add(name + COLUMN + (factor > 0 ? Messages.getString("GpuBoard.map.terrainFactor", factor) : ""));
            }
        }
        if (rows.size() == 1) {
            rows.add(Messages.getString("GpuBoard.hud.context.clearTerrain") + COLUMN);
        }
        List<String> errors = new ArrayList<>();
        if (!hex.isValid(errors)) {
            rows.add(UIUtil.WARNING_SIGN.strip() + " " + Messages.getString("BoardView1.invalidHex") + COLUMN);
            errors.forEach(error -> rows.add(error + COLUMN));
        }
        return String.join("\n", rows);
    }

    @Override
    public void measure(Coords coords, int modifiers, float pointedZ) {
        onSwing(() -> {
            if (closed || coords == null || !board.contains(coords)) {
                return;
            }
            if (ruler == null) { ruler = new RulerModel(game, 0, null); }
            int measurement = GpuBoardSource.isMeasurement(modifiers) ? modifiers : ruler.capture().pending();
            if (measurement != 0) {
                finishEditorStroke();
                ruler.addPoint(coords, Float.isNaN(pointedZ) ? null
                      : GpuLosResult.pointedHeight(board.getHex(coords), pointedZ));
                refresh();
            }
        });
    }

    @Override
    public void changeRuler(long generation, java.util.function.Consumer<RulerModel> action) {
        onSwing(() -> {
            if (closed || generation != boardGeneration || board != game.getBoard()) { return; }
            if (ruler == null) { ruler = new RulerModel(game, 0, null); }
            action.accept(ruler);
            refresh();
        });
    }

    private String measuringStatus(RulerModel.Snapshot measurement) {
        return measurement.pending() == 0 ? "" : Messages.getString("GpuBoard.hud.hint.completeLos");
    }

    private void closeMeasuring() { ruler = null; }

    public BoardEditorSession.Snapshot editorState() { return (rendering == null ? publication : rendering).editor(); }

    public void editorCommand(BoardEditorSession.Command command, long generation) {
        onSwing(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()) {
                finishEditorStroke();
                editor.command(command, owner);
                refresh();
            }
        });
    }

    public void editorValue(BoardEditorSession.Command command, boolean finished, long generation) {
        onSwing(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()) {
                if (editorElevationStroke) { finishEditorStroke(); }
                editorStrokeBoard = board;
                editor.command(command, owner, true);
                // The existing capture timer publishes drag previews at most once per tick. Capturing every
                // mouse event backs up the EDT with intermediate values the renderer cannot display anyway.
                if (finished) { finishEditorStroke(); refresh(); }
            }
        });
    }

    /** Reject pointer input from a board that has been replaced since the render frame was captured. */
    public void editorPointer(Coords coords, double x, double y, boolean drag, long generation) {
        editorPointer(coords, x, y, drag, null, generation);
    }

    public void editorPointer(Coords coords, double x, double y, boolean drag, String object, long generation) {
        editorPointer(coords, x, y, drag, object, false, generation);
    }

    public void editorPointer(Coords coords, double x, double y, boolean drag, String object, boolean additive, long generation) {
        editorPointer(coords, x, y, drag, object, additive, "ground", generation);
    }

    public void editorPointer(Coords coords, double x, double y, boolean drag, String object, boolean additive, String receiver, long generation) {
        editorPointer(coords, x, y, drag, object, additive, receiver, false, generation);
    }

    @Override
    public void editorPointer(Coords coords, double x, double y, boolean drag, String object, boolean additive, String receiver,
          boolean invert, long generation) {
        onSwing(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()
                  && coords != null && board.contains(coords)) {
                if (editorElevationStroke) { finishEditorStroke(); }
                editorStrokeBoard = board;
                editor.pointer(coords, x, y, drag, object, additive, receiver, invert);
            }
        });
    }

    @Override
    public void editorSelect(List<BoardEditorSession.Selection> items, BoardEditorSession.SelectMode mode, long generation) {
        onSwing(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()) {
                finishEditorStroke();
                editor.select(items, mode);
                refresh();
            }
        });
    }

    public void paintEditor(Coords coords, int modifiers, long generation) {
        editorPointer(coords, 0, 0, false, generation);
    }

    public List<Coords> editorBrush(Coords center, long generation) {
        EditorBrush preview = editorBrush;
        return generation == preview.generation() && java.util.Objects.equals(center, preview.center())
              ? preview.hexes() : List.of();
    }

    @Override public String editorHint() { return editorBrush.hint(); }

    public void adjustEditorElevation(Coords coords, int levels, long generation) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()
                  && coords != null && board.contains(coords) && levels != 0) {
                if (!editorElevationStroke) {
                    finishEditorStroke();
                    editorStrokeBoard = board;
                    editorElevationStroke = true;
                }
                pendingElevation.add(Map.entry(coords, levels));
            }
        });
    }

    private void flushEditorElevation() {
        if (editorStrokeBoard != null && editorStrokeBoard == game.getBoard()) {
            pendingElevation.forEach(notch -> editor.adjustElevation(notch.getKey(), notch.getValue()));
        }
        pendingElevation.clear();
    }

    public void endEditorStroke() {
        onSwing(() -> {
            finishEditorStroke();
            if (!closed) {
                // The pointer's release, not an earlier key that finished the stroke, completes a click's drill-down.
                if (editor != null) { editor.release(); }
                refresh();
            }
        });
    }

    private void finishEditorStroke() {
        flushEditorElevation();
        editorElevationStroke = false;
        if (editorStrokeBoard != null) {
            if (editorStrokeBoard == game.getBoard()) {
                editor.finishStroke();
            }
            editorStrokeBoard = null;
        }
    }

    public void close() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::close); return; }
        if (closed) { return; }
        closed = true;
        finishEditorStroke();
        timer.stop();
        closeMeasuring();
        atmosphere.close();
        game.removeGameListener(gameListener);
        if (board != null) { board.removeBoardListener(boardListener); }
        PreferenceManager.getClientPreferences().removePreferenceChangeListener(preferences);
        GUIPreferences.getInstance().removePreferenceChangeListener(preferences);
        artwork.close();
        images.clear();
        tiles = List.of();
    }
}
