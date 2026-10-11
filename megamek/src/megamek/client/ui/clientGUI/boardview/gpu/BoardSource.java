/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Rectangle;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.preference.PreferenceManager;

/** Renderer input boundary. Only immutable snapshots cross from the EDT to the GL thread. */
interface BoardSource extends AutoCloseable {
    /** One key binding with its display text ({@code KeyCommandBind.getDesc}), copied on the Swing thread. */
    public record Bind(KeyCommandBind command, int keyCode, int modifiers, String text) { }

    /**
     * Immutable preference snapshot published to the render thread: the existing preferences the native views read,
     * whether the battle HUD shows its contacts panel, every key binding, MegaMek's sprint colour, the 3D board's
     * graphics, MegaMek's jump colour and the overlay switches.
     */
    public record UiPreferences(float scale, String reportKeywords, String reportFilterKeywords,
          boolean minimapEnabled, boolean contactsEnabled, boolean conditionsVisible, boolean turnDetails,
          List<Bind> binds, int moveSprintRgb, BoardGraphics graphics, int moveJumpRgb, Overlays overlays) {
        public UiPreferences {
            binds = List.copyOf(binds);
        }

        /**
         * The client settings of the Tactical View's Overlays panel (GpuTacticalOverlays), View > Unit Links, View >
         * Hex Types and Client Settings > Hex label outline, the one switch of each overlay for the panel and for the
         * passes that draw it: the panel is expanded, the bases show their status badges, the enemy's rear arcs are
         * shaded, the E(C)CM fields are outlined and the jammed hexes hatched, the ground layer is lightened by level,
         * the lines between units are drawn, the hexes' captured labels carry their type block (so the Tactical View
         * adds no depth of its own) and the hex labels are drawn on their outline (GpuHexText).
         */
        public record Overlays(boolean panelOpen, boolean statusBadges, boolean rearArcs, boolean ecmFields,
              boolean ecmHatch, boolean levelShading, boolean unitLinks, boolean hexTypes, boolean hexLabelOutline) {
            /** The settings' defaults. */
            public static final Overlays DEFAULTS = new Overlays(true, true, true, true, true, false, true, false,
                  false);

            static Overlays capture(GUIPreferences gui) {
                return new Overlays(gui.getTacticalOverlaysOpen(), gui.getTacticalStatusBadges(),
                      gui.getTacticalRearArcs(), gui.getEcmTacticalFields(), gui.getEcmTacticalHatch(),
                      gui.getTacticalLevelShading(), gui.getShowUnitLinks(), gui.getHexTypesEnabled(),
                      gui.getHexLabelOutline());
            }
        }

        /** Preferences with the overlays' defaults, as the tests that switch no overlay build them. */
        public UiPreferences(float scale, String reportKeywords, String reportFilterKeywords,
              boolean minimapEnabled, boolean contactsEnabled, boolean conditionsVisible, boolean turnDetails,
              List<Bind> binds, int moveSprintRgb, BoardGraphics graphics, int moveJumpRgb) {
            this(scale, reportKeywords, reportFilterKeywords, minimapEnabled, contactsEnabled, conditionsVisible,
                  turnDetails, binds, moveSprintRgb, graphics, moveJumpRgb, Overlays.DEFAULTS);
        }

        /** Preferences with MegaMek's default jump colour, as the tests that draw no jump build them. */
        public UiPreferences(float scale, String reportKeywords, String reportFilterKeywords,
              boolean minimapEnabled, boolean contactsEnabled, boolean conditionsVisible, boolean turnDetails,
              List<Bind> binds, int moveSprintRgb, BoardGraphics graphics) {
            this(scale, reportKeywords, reportFilterKeywords, minimapEnabled, contactsEnabled, conditionsVisible,
                  turnDetails, binds, moveSprintRgb, graphics, java.awt.Color.RED.getRGB());
        }

        static UiPreferences capture() {
            var preferences = PreferenceManager.getClientPreferences();
            GUIPreferences gui = GUIPreferences.getInstance();
            return new UiPreferences(gui.getGUIScale(), preferences.getReportKeywords(),
                  preferences.getReportFilterKeywords(), gui.getMinimapEnabled(), gui.getGpuContactsEnabled(),
                  gui.getShowPlanetaryConditionsOverlay(), gui.getTurnDetailsOverlay(),
                  Stream.of(KeyCommandBind.values()).map(bind -> new Bind(bind, bind.key, bind.modifiers,
                        KeyCommandBind.getDesc(bind))).toList(),
                  gui.getMoveSprintColor().getRGB(), gui.getBoardGraphics(), gui.getMoveJumpColor().getRGB(),
                  Overlays.capture(gui));
        }
    }

    /**
     * One published snapshot. {@code status} and {@code panels} are always the newest capture; {@code panels} bundles
     * the HUD services' snapshots. A map preview or the board editor publishes empty ones.
     */
    public record Frame(BoardScene scene, List<BoardScene.Animation> timeline, BoardScene.Context context,
          List<BoardScene.Command> globalCommands, String tooltip,
          BoardFocus centerRequest, long boardGeneration, String actorName,
          BoardAtmosphere.Settings scenarioAtmosphere, GpuReportLog.Snapshot reports,
          GpuBattleStatus.Snapshot status, GpuHudData panels) {
        /** A map's frame: no reports, battle status or HUD panels. */
        public Frame(BoardScene scene, List<BoardScene.Animation> timeline, BoardScene.Context context,
              List<BoardScene.Command> globalCommands, String tooltip, BoardFocus centerRequest, long boardGeneration,
              String actorName, BoardAtmosphere.Settings scenarioAtmosphere) {
            this(scene, timeline, context, globalCommands, tooltip, centerRequest, boardGeneration, actorName,
                  scenarioAtmosphere, GpuReportLog.Snapshot.EMPTY, GpuBattleStatus.Snapshot.EMPTY, GpuHudData.EMPTY);
        }

        /** This frame with the animation events queued since the previous frame was taken. */
        Frame withTimeline(List<BoardScene.Animation> events) {
            return new Frame(scene, events, context, globalCommands, tooltip, centerRequest, boardGeneration,
                  actorName, scenarioAtmosphere, reports, status, panels);
        }

        /** This frame showing {@code shown}, the scene the playback presents; this frame when it is its own. */
        Frame withScene(BoardScene shown) {
            return shown == scene ? this : new Frame(shown, timeline, context, globalCommands, tooltip,
                  centerRequest, boardGeneration, actorName, scenarioAtmosphere, reports, status, panels);
        }

        List<BoardScene.Movement> movements() {
            return timeline.stream().filter(BoardScene.Movement.class::isInstance).map(BoardScene.Movement.class::cast).toList();
        }

        List<BoardScene.Animation> animations() {
            return timeline.stream().filter(event -> !(event instanceof BoardScene.SceneUpdate)
                  && !(event instanceof BoardScene.Concealed)).toList();
        }
    }

    record PhaseStatus(String text, boolean blocking) { }
    Frame takeFrame();
    /** A printable snapshot queued by the EDT, consumed once by the render thread; none for game views. */
    default GpuBoardPrint.Request takePrintRequest() { return null; }
    void refresh();
    /** EDT-only: discard file-backed artwork caches before publishing a fresh snapshot. */
    default void reloadAssets() throws java.io.IOException { refresh(); }
    UiPreferences uiPreferences();
    /** The board editor's trace overlay settings ({@link GpuTraceOverlay}); every other view has none. */
    default GpuTraceOverlay.Settings trace() { return GpuTraceOverlay.Settings.NONE; }
    /** The map tools' status line (the board editor's title); a game shows its phase in the battle HUD instead. */
    default PhaseStatus phaseStatus() { return new PhaseStatus("", false); }
    GpuAtmosphereControls atmosphere();
    boolean isClosed();
    void close();
    void setHover(Coords coords);
    /** Walkable surface under the pointer in world units; NaN when no surface height was picked. */
    default void setHover(Coords coords, float pointedZ) { setHover(coords); }
    void inspect(Coords coords);
    /**
     * Alt-click starts the native LOS ruler (including range); a plain click
     * ends one that waits for its second point. The point lies at the height the pointer shows there
     * ({@code pointedZ}, the terrain hit's world height; NaN for none, so a unit's own counts).
     */
    default void measure(Coords coords, int modifiers, float pointedZ) { }
    /** Commands from a panel are valid only on the board generation that supplied its snapshot. */
    default void changeRuler(long generation, Consumer<RulerModel> action) { }

    // A map preview has no gameplay overlays, chat, selection, or editing input.
    default void setVisibleArea(Rectangle area) { }
    /** The native window's size in its own units and in pixels. */
    default void setViewport(int width, int height, int pixelWidth, int pixelHeight) { }
    default void key(int keyCode, boolean down, int modifiers) { }
    default void stopKeys() { }
    /** Full play needs its tactical artwork prepared before interactive frames; map-only views do not. */
    default boolean isGameplay() { return false; }
    /** Render-thread report about the timeline consumed with this frame; never a second animation clock. */
    default void playbackState(Frame frame, boolean busy) { }
    default boolean isEditor() { return false; }
    default void paintEditor(Coords coords, int modifiers, long generation) { }
    default List<Coords> editorBrush(Coords coords, long generation) { return List.of(); }
    default void adjustEditorElevation(Coords coords, int levels, long generation) { }
    default void endEditorStroke() { }
    /** Drops the route the editor's drag draws by hand, unbuilt (Esc or a right click during the drag). */
    default void cancelEditorStroke() { }
    default megamek.client.ui.boardeditor.BoardEditorSession.Snapshot editorState() { return null; }
    default List<String> editorThemes() { return List.of(); }
    default void editorCommand(megamek.client.ui.boardeditor.BoardEditorSession.Command command, long generation) { }
    default void editorValue(megamek.client.ui.boardeditor.BoardEditorSession.Command command, boolean finished, long generation) { }
    default void editorPointer(Coords coords, double x, double y, boolean drag, long generation) { }
    default void editorPointer(Coords coords, double x, double y, boolean drag, String object, long generation) {
        editorPointer(coords, x, y, drag, generation);
    }
    default void editorPointer(Coords coords, double x, double y, boolean drag, String object, boolean additive, long generation) {
        editorPointer(coords, x, y, drag, object, generation);
    }
    default void editorPointer(Coords coords, double x, double y, boolean drag, String object, boolean additive,
          String receiver, long generation) {
        editorPointer(coords, x, y, drag, object, additive, generation);
    }
    /** {@code invert}: Ctrl at the press, which swaps the Sculpt tool's raise and lower. */
    default void editorPointer(Coords coords, double x, double y, boolean drag, String object, boolean additive,
          String receiver, boolean invert, long generation) {
        editorPointer(coords, x, y, drag, object, additive, receiver, generation);
    }
    /** A box selection of the objects and hexes the rectangle covered, or a Ctrl-click's item; the session chooses. */
    default void editorSelect(List<megamek.client.ui.boardeditor.BoardEditorSession.Selection> items,
          megamek.client.ui.boardeditor.BoardEditorSession.SelectMode mode, long generation) { }
    /** The active tool's hint for the hovered hex, such as "Raise · 7 hexes"; empty when it has none. */
    default String editorHint() { return ""; }
    /**
     * Where the next Paint or Erase pointer event would land, as {@link #editorPointer} takes it (null: off the board),
     * for the editor's preview; the render thread reports it on every move and drag.
     */
    default void editorHover(Coords coords, double x, double y, String receiver) { }
    /** The editor's preview of that event (GpuEditorPreview), published with the frame; none outside the editor. */
    default GpuEditorPreview.Data editorPreview() { return GpuEditorPreview.Data.NONE; }

    default void editPlanetaryConditions(Consumer<BoardAtmosphere.Settings> completed) { atmosphere().edit(completed); }
    default BoardAtmosphere.Settings atmosphereFor(PlanetaryConditions conditions, boolean inSpace) {
        return atmosphere().settings(conditions, inSpace);
    }
    default BoardAtmosphere.Settings atmosphereFor(AtmospherePreset preset) { return atmosphere().settings(preset); }
    default BoardAtmosphere.Settings preview(AtmospherePreset preset) { return atmosphere().preview(preset); }
    default void resetConditionsPreview() { atmosphere().reset(); }
}
