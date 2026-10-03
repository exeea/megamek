/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorPanel;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
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
    /**
     * A preview's line of sight instrument, made at its first measurement: a board state over the preview's game, whose
     * ruler line the scene draws, and MegaMek's ruler on it (the user's decision of 2026-10-03).
     */
    private BoardClientState measuring;
    private RulerDialog ruler;
    /** The measurement the scene's ruler line shows, and that line. */
    private List<Coords> measured = List.of();
    private BoardTactical rulerLine = BoardTactical.EMPTY;
    private final BoardEditorPanel editor;
    /** Only Swing owns the active brush stroke; render input carries the board generation it picked. */
    private Board editorStrokeBoard;
    /** Swing combines wheel ticks between captures; releasing Ctrl commits the shared editor undo entry. */
    private final Map<Coords, Integer> pendingElevation = new HashMap<>();
    private boolean editorElevationStroke;
    /** Swing-owned brush settings are published only as a preview, never read directly by the render thread. */
    private record EditorBrush(Coords center, long generation, List<Coords> hexes) { }
    private volatile EditorBrush editorBrush = new EditorBrush(null, -1, List.of());

    private final BoardArtwork artwork = new BoardArtwork();
    private final BoardScene.PixelPool images = new BoardScene.PixelPool();
    private final GpuAtmosphereControls atmosphere;
    private final GpuMenuCommands menus;
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
    private long overlayRevision = -1;
    private long boardGeneration;
    private volatile Frame frame;
    private volatile UiPreferences uiPreferences = UiPreferences.capture();
    private volatile PhaseStatus phaseStatus = new PhaseStatus("", false);
    private volatile Coords hoverCoords;
    private Coords contextCoords;
    private volatile boolean closed;
    private volatile java.awt.Dimension viewport = new java.awt.Dimension(1, 1);
    /** EDT-measured width of the editor's Swing tools over the native window's right edge. */
    private volatile int toolsWidth;
    public void setViewport(int width, int height, int pixelWidth, int pixelHeight) {
        viewport = new java.awt.Dimension(Math.max(1, width), Math.max(1, height));
    }

    @Override
    public float toolsInset() {
        java.awt.Dimension size = viewport;
        return size.width <= 1 ? -1 : toolsWidth / (float) size.width;
    }

    GpuMapSource(Game game, Window owner, BoardEditorPanel editor) {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        this.game = game;
        this.owner = owner;
        this.editor = editor;
        atmosphere = new GpuAtmosphereControls(() -> owner, game::getBoard, game::getPlanetaryConditions, () -> closed);
        menus = new GpuMenuCommands(() -> closed, this::refresh);
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
        boolean overlaysChanged = editor != null && overlayRevision != editor.overlayRevision();
        if (terrainDirty || dirtyHexes != null || overlaysChanged) {
            if (terrainDirty) { terrainMarkers.clear(); }
            Rectangle area = terrainDirty || overlaysChanged ? new Rectangle(0, 0, board.getWidth(), board.getHeight())
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
                    if (editor != null) {
                        tile = tile.withTactical(images.captureOverlay(editor.captureOverlay(coords),
                              previous == null ? null : previous.tactical()));
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
            overlayRevision = editor == null ? -1 : editor.overlayRevision();
        }
        Coords hover = hoverCoords;
        if (editor != null) { editorBrush = new EditorBrush(hover, boardGeneration, editor.elevationBrush(hover)); }
        phaseStatus = new PhaseStatus(editor == null ? measuringStatus() : editor.getFrame().getTitle(), false);
        Coords inspected = contextCoords == null ? hover : contextCoords;
        String tooltip = inspected == null || !board.contains(inspected) ? "" : hexCard(board.getHex(inspected));
        BoardScene scene = new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(),
              Entity.NONE, "", List.of(), null, List.of(), List.of(), List.of(),
              measuring == null ? editorTerrain : rulerLine());
        toolsWidth = editor == null ? 0 : editor.tools3DWidth();
        frame = new Frame(scene, List.of(), contextCoords == null ? null : new BoardScene.Context(contextCoords, List.of()),
              editor == null ? List.of() : menus.capture(editor.getMenuBar(), editor::getMenuBar, () -> true), tooltip,
              editor == null ? new BoardFocus(0, null) : editor.focusRequest(), boardGeneration, "",
              atmosphere.settings(game.getPlanetaryConditions(), board.isSpace()));
    }

    @Override
    public void reloadAssets() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        if (closed) { return; }
        megamek.common.util.ImageUtil.reloadImages();
        artwork.reload();
        images.clear();
        dirtyAll();
        refresh();
    }

    private void dirtyAll() { terrainDirty = true; artwork.clear(); }
    private static void onSwing(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) { action.run(); } else { SwingUtilities.invokeLater(action); }
    }
    public Frame takeFrame() { return frame; }
    public UiPreferences uiPreferences() { return uiPreferences; }
    public PhaseStatus phaseStatus() { return phaseStatus; }
    public GpuAtmosphereControls atmosphere() { return atmosphere; }
    public boolean isClosed() { return closed; }
    public void setHover(Coords coords) { hoverCoords = coords; }
    public void inspect(Coords coords) { onSwing(() -> { contextCoords = coords; refresh(); }); }

    public void key(int keyCode, boolean down, int modifiers) {
        onSwing(() -> {
            if (!closed && editor != null && down && !editor.shouldIgnoreHotKeys()
                  && keyCode != KeyEvent.VK_SHIFT && keyCode != KeyEvent.VK_CONTROL
                  && keyCode != KeyEvent.VK_ALT && keyCode != KeyEvent.VK_META) {
                finishEditorStroke();
                GpuMenuCommands.menuShortcut(editor.getMenuBar(), KeyStroke.getKeyStroke(keyCode, modifiers));
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
            if (closed || editor != null || coords == null || !board.contains(coords)) {
                return;
            }
            int measurement = GpuBoardSource.isMeasurement(modifiers) ? modifiers
                  : measuring == null ? 0 : GpuLosResult.pending(measuring);
            if (measurement != 0 && measuring() != null) {
                measuring.mouseAction(coords, BoardClientState.BOARD_HEX_CLICK, measurement, 1);
                if (!Float.isNaN(pointedZ)) {
                    ruler.setHeight(coords, GpuLosResult.pointedHeight(board.getHex(coords), pointedZ));
                }
                refresh();
            }
        });
    }

    /** EDT: the preview's measuring board state with its ruler, made the first time; null when it cannot be made. */
    private BoardClientState measuring() {
        if (measuring == null) {
            try {
                measuring = new BoardClientState(game, null, null, 0, null);
            } catch (IOException failure) {
                LOGGER.error("The preview's line of sight could not start", failure);
                return null;
            }
            JFrame frame = null;
            for (Window window = owner; window != null && frame == null; window = window.getOwner()) {
                frame = window instanceof JFrame classic ? classic : null;
            }
            ruler = new RulerDialog(frame, measuring, game);
            // The native preview window would cover it; the battle window raises its client's ruler the same way.
            ruler.setAlwaysOnTop(true);
        }
        return measuring;
    }

    /** The ruler's line, crosshairs and line of sight hexes as the scene draws them, captured when they change. */
    private BoardTactical rulerLine() {
        List<Coords> now = Arrays.asList(measuring.getRulerStart(), measuring.getRulerEnd(),
              measuring.getFirstLOS());
        if (!now.equals(measured)) {
            measured = now;
            rulerLine = measuring.captureTacticalGeometry();
        }
        return rulerLine;
    }

    /** The preview's status line: what a measurement waiting for its second point needs, else nothing. */
    private String measuringStatus() {
        int pending = measuring == null ? 0 : GpuLosResult.pending(measuring);
        return pending == 0 ? "" : Messages.getString(pending == InputEvent.CTRL_DOWN_MASK
              ? "GpuBoard.hud.hint.completeLos" : "GpuBoard.hud.hint.completeDistance");
    }

    /** Ends the preview's measurement with its ruler; the next one starts afresh. */
    private void closeMeasuring() {
        if (measuring != null) {
            ruler.dispose();
            measuring.close();
            measuring = null;
            ruler = null;
            measured = List.of();
            rulerLine = BoardTactical.EMPTY;
        }
    }

    public void showEditorTools() {
        SwingUtilities.invokeLater(() -> {
            if (!closed && editor != null) {
                editor.show3DTools();
            }
        });
    }

    public void showClassicEditor() {
        SwingUtilities.invokeLater(() -> {
            if (!closed && editor != null) {
                GpuBoardWindow.toggleEditor(editor);
            }
        });
    }

    /** Route picked hexes to the shared editor operation, rejecting input from a replaced board. */
    public void paintEditor(Coords coords, int modifiers, long generation) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()
                  && coords != null && board.contains(coords) && !editor.shouldIgnoreHotKeys()) {
                if (editorElevationStroke) {
                    finishEditorStroke();
                }
                editorStrokeBoard = board;
                editor.paintIn3D(coords, modifiers);
            }
        });
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
                List<Coords> brush = editor.elevationBrush(coords);
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
                editor.finishBrushStroke();
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
        artwork.clear();
        images.clear();
        tiles = List.of();
    }
}
