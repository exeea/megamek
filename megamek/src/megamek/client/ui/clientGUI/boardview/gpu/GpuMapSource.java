/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.ui.boardeditor.BoardEditorPanel;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.toolTip.BoardEditorTooltipContent;
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

/** Native preview/editor input. The Game/Board and editor operations are the only authoritative state. */
final class GpuMapSource implements BoardSource {
    private final Game game;
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
    public void setViewport(int width, int height, int pixelWidth, int pixelHeight) {
        viewport = new java.awt.Dimension(Math.max(1, width), Math.max(1, height));
    }

    GpuMapSource(Game game, Window owner, BoardEditorPanel editor) {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        this.game = game;
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
            contextCoords = null;
            images.clear();
            dirtyAll();
            atmosphere.reset();
        }
        boolean overlaysChanged = editor != null && overlayRevision != editor.overlayRevision();
        if (terrainDirty || dirtyHexes != null || overlaysChanged) {
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
                    BoardScene.Tile tile = changed
                          ? BoardScene.captureTile(board.getHex(coords), artwork.capture(board, coords, true), previous, images)
                          : previous;
                    if (editor != null) {
                        tile = tile.withTactical(images.captureOverlay(editor.captureOverlay(coords),
                              previous == null ? null : previous.tactical()));
                    }
                    next.set(index, tile.equals(previous) ? previous : tile);
                }
            }
            tiles = List.copyOf(next);
            images.retain(tiles);
            terrainDirty = false;
            dirtyHexes = null;
            overlayRevision = editor == null ? -1 : editor.overlayRevision();
        }
        Coords hover = hoverCoords;
        if (editor != null) { editorBrush = new EditorBrush(hover, boardGeneration, editor.elevationBrush(hover)); }
        phaseStatus = new PhaseStatus(editor == null ? "" : editor.getFrame().getTitle(), false);
        Coords inspected = contextCoords == null ? hover : contextCoords;
        String tooltip = inspected == null || !board.contains(inspected) ? ""
              : GpuMenuCommands.plainText(BoardEditorTooltipContent.format(board, inspected));
        BoardScene scene = new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(),
              Entity.NONE, "", List.of());
        java.awt.Dimension size = viewport;
        frame = new Frame(scene, List.of(), contextCoords == null ? null : new BoardScene.Context(contextCoords, List.of()),
              editor == null ? List.of() : menus.capture(editor.getMenuBar(), editor::getMenuBar, () -> true),
              new Hud(size.width, size.height, List.of(), editor == null ? 0 : editor.tools3DWidth()), tooltip,
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
