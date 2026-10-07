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
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.util.UIUtil;
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
    /** Only Swing owns the active brush stroke; render input carries the board generation it picked. */
    private Board editorStrokeBoard;
    /** Swing combines wheel ticks between captures; releasing Ctrl commits the shared editor undo entry. */
    private final Map<Coords, Integer> pendingElevation = new HashMap<>();
    private boolean editorElevationStroke;
    /** Swing-owned brush settings are published only as a preview, never read directly by the render thread. */
    private record EditorBrush(Coords center, long generation, List<Coords> hexes) { }
    private volatile EditorBrush editorBrush = new EditorBrush(null, -1, List.of());

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
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        if (editor != null && editor.game() != game) { throw new IllegalArgumentException("The editor owns its Game"); }
        this.game = game;
        this.owner = owner;
        this.editor = editor;
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
            images.retain(tiles);
            terrainDirty = false;
            dirtyHexes = null;

        }
        Coords hover = hoverCoords;
        if (editor != null) { editorBrush = new EditorBrush(hover, boardGeneration, editor.brush(hover)); }
        RulerModel.Snapshot measurement = ruler == null ? RulerModel.Snapshot.NONE : ruler.capture();
        phaseStatus = new PhaseStatus(editor == null ? measuringStatus(measurement) : editor.title(), false);
        Coords inspected = contextCoords == null ? hover : contextCoords;
        String tooltip = inspected == null || !board.contains(inspected) ? "" : hexCard(board.getHex(inspected));
        BoardScene scene = new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(),
              Entity.NONE, "", List.of(), null, List.of(), List.of(), List.of(),
              editorTerrain.withRuler(measurement.ruler()));
        Frame nextFrame = new Frame(scene, List.of(), contextCoords == null ? null : new BoardScene.Context(contextCoords, List.of()),
              List.of(), tooltip,
              new BoardFocus(0, null), boardGeneration, "",
              atmosphere.settings(game.getPlanetaryConditions(), board.isSpace()),
              GpuReportLog.Snapshot.EMPTY, GpuBattleStatus.Snapshot.EMPTY, GpuHudData.EMPTY.withLos(measurement));
        publication = new Publication(nextFrame, editor == null ? null : editor.snapshot());
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
            if (!closed && editor != null && down
                  && keyCode != KeyEvent.VK_SHIFT && keyCode != KeyEvent.VK_CONTROL
                  && keyCode != KeyEvent.VK_ALT && keyCode != KeyEvent.VK_META) {
                finishEditorStroke();
                editor.key(keyCode, modifiers, owner);
                refresh();
            }
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
        onSwing(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()
                  && coords != null && board.contains(coords)) {
                if (editorElevationStroke) { finishEditorStroke(); }
                editorStrokeBoard = board;
                editor.pointer(coords, x, y, drag, object, additive, receiver);
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

    public void adjustEditorElevation(Coords coords, int levels, long generation) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()
                  && coords != null && board.contains(coords) && levels != 0) {
                List<Coords> brush = editor.brush(coords);
                if (brush.isEmpty()) {
                    return;
                }
                if (!editorElevationStroke) {
                    finishEditorStroke();
                    editorStrokeBoard = board;
                    editorElevationStroke = true;
                }
                // Capture the brush now, so a later palette change cannot retarget accepted wheel input.
                for (Coords hex : brush) {
                    pendingElevation.merge(hex, levels, Integer::sum);
                }
            }
        });
    }

    private void flushEditorElevation() {
        if (editorStrokeBoard != null && editorStrokeBoard == game.getBoard()) {
            editor.adjustElevation(pendingElevation);
        }
        pendingElevation.clear();
    }

    public void endEditorStroke() {
        onSwing(() -> {
            finishEditorStroke();
            if (!closed) {
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
