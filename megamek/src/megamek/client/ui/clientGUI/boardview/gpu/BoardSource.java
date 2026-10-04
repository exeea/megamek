/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Rectangle;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
     * whether the battle HUD shows its contacts panel, every key binding and MegaMek's sprint colour.
     */
    public record UiPreferences(float scale, String reportKeywords, String reportFilterKeywords,
          boolean minimapEnabled, boolean contactsEnabled, boolean conditionsVisible, boolean turnDetails,
          List<Bind> binds, int moveSprintRgb) {
        public UiPreferences {
            binds = List.copyOf(binds);
        }

        static UiPreferences capture() {
            var preferences = PreferenceManager.getClientPreferences();
            GUIPreferences gui = GUIPreferences.getInstance();
            return new UiPreferences(gui.getGUIScale(), preferences.getReportKeywords(),
                  preferences.getReportFilterKeywords(), gui.getMinimapEnabled(), gui.getGpuContactsEnabled(),
                  gui.getShowPlanetaryConditionsOverlay(), gui.getTurnDetailsOverlay(),
                  Stream.of(KeyCommandBind.values()).map(bind -> new Bind(bind, bind.key, bind.modifiers,
                        KeyCommandBind.getDesc(bind))).toList(),
                  gui.getMoveSprintColor().getRGB());
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
    void refresh();
    /** EDT-only: discard file-backed artwork caches before publishing a fresh snapshot. */
    default void reloadAssets() throws java.io.IOException { refresh(); }
    UiPreferences uiPreferences();
    /** The map tools' status line (the board editor's title); a game shows its phase in the battle HUD instead. */
    default PhaseStatus phaseStatus() { return new PhaseStatus("", false); }
    GpuAtmosphereControls atmosphere();
    boolean isClosed();
    void close();
    void setHover(Coords coords);
    void inspect(Coords coords);
    /**
     * A measurement with MegaMek's ruler: Ctrl for a line of sight, Alt for a distance; in a map preview a plain click
     * ends one that waits for its second point. The point lies at the height the pointer shows there
     * ({@code pointedZ}, the terrain hit's world height; NaN for none, so a unit's own counts).
     */
    default void measure(Coords coords, int modifiers, float pointedZ) { }

    // A map preview has no gameplay overlays, chat, selection, or editing input.
    default void setVisibleArea(Rectangle area) { }
    /** The native window's size in its own units and in pixels; the board editor measures its tools against it. */
    default void setViewport(int width, int height, int pixelWidth, int pixelHeight) { }
    /**
     * The part of the window's width, from its right edge, that a Swing tool window covers (the board editor's
     * tools), as a fraction; the camera centres the board beside it. Negative while the window size is unknown.
     */
    default float toolsInset() { return 0; }
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
    default void showEditorTools() { }
    default void showClassicEditor() { }
    default void editPlanetaryConditions(Consumer<BoardAtmosphere.Settings> completed) { atmosphere().edit(completed); }
    default BoardAtmosphere.Settings atmosphereFor(PlanetaryConditions conditions, boolean inSpace) {
        return atmosphere().settings(conditions, inSpace);
    }
    default BoardAtmosphere.Settings atmosphereFor(AtmospherePreset preset) { return atmosphere().settings(preset); }
    default BoardAtmosphere.Settings preview(AtmospherePreset preset) { return atmosphere().preview(preset); }
    default void resetConditionsPreview() { atmosphere().reset(); }
}
