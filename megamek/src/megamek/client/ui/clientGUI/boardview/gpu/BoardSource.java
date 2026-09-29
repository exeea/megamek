/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Rectangle;
import java.util.List;
import java.util.function.Consumer;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.overlay.OverlayImage;
import megamek.common.board.Coords;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.preference.PreferenceManager;

/** Renderer input boundary. Only immutable snapshots cross from the EDT to the GL thread. */
interface BoardSource extends AutoCloseable {
    /** Immutable preference snapshot published to the render thread. */
    public record UiPreferences(float scale, String reportKeywords, String reportFilterKeywords) {
        static UiPreferences capture() {
            var preferences = PreferenceManager.getClientPreferences();
            return new UiPreferences(GUIPreferences.getInstance().getGUIScale(),
                  preferences.getReportKeywords(), preferences.getReportFilterKeywords());
        }
    }
    record HudLayer(BoardScene.Pixels pixels, int x, int y, OverlayImage.Fade fade, OverlayImage.Transition shiftY) {
        public HudLayer(BoardScene.Pixels pixels, int x, int y, OverlayImage.Fade fade) {
            this(pixels, x, y, fade, OverlayImage.Transition.ZERO);
        }
    }
    /** Immutable Swing layout snapshot; the GL thread scales the sidebar reservation with its HUD artwork. */
    record Hud(int width, int height, List<HudLayer> layers, float sidePanelInset, float leftPanelInset) {
        public Hud(int width, int height, List<HudLayer> layers) {
            this(width, height, layers, 0, 0);
        }
        public Hud(int width, int height, List<HudLayer> layers, float sidePanelInset) {
            this(width, height, layers, sidePanelInset, 0);
        }
    }
    public record Frame(BoardScene scene, List<BoardScene.Animation> timeline, BoardScene.Context context,
          List<BoardScene.Command> globalCommands, Hud hud, String tooltip,
          BoardFocus centerRequest, long boardGeneration, String actorName,
          BoardAtmosphere.Settings scenarioAtmosphere, BoardScene.Attack attack, GpuReportLog.Snapshot reports,
          boolean keepSelectionCamera) {
        public Frame(BoardScene scene, List<BoardScene.Animation> animations, BoardScene.Context context,
              List<BoardScene.Command> globalCommands, Hud hud, String tooltip,
              BoardFocus centerRequest, long boardGeneration, String actorName,
              BoardAtmosphere.Settings scenarioAtmosphere, BoardScene.Attack attack, GpuReportLog.Snapshot reports) {
            this(scene, animations, context, globalCommands, hud, tooltip, centerRequest, boardGeneration, actorName,
                  scenarioAtmosphere, attack, reports, false);
        }

        public Frame(BoardScene scene, List<BoardScene.Animation> animations, BoardScene.Context context,
              List<BoardScene.Command> globalCommands, Hud hud, String tooltip,
              BoardFocus centerRequest, long boardGeneration, String actorName,
              BoardAtmosphere.Settings scenarioAtmosphere) {
            this(scene, animations, context, globalCommands, hud, tooltip, centerRequest, boardGeneration, actorName,
                  scenarioAtmosphere, null, GpuReportLog.Snapshot.EMPTY);
        }

        public Frame(BoardScene scene, List<BoardScene.Animation> animations, BoardScene.Context context,
              List<BoardScene.Command> globalCommands, Hud hud, String tooltip,
              BoardFocus centerRequest, long boardGeneration, String actorName) {
            this(scene, animations, context, globalCommands, hud, tooltip, centerRequest, boardGeneration, actorName,
                  BoardAtmosphere.DEFAULTS);
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
    PhaseStatus phaseStatus();
    GpuAtmosphereControls atmosphere();
    boolean isClosed();
    void close();
    void setHover(Coords coords);
    void inspect(Coords coords);

    // A map preview has no gameplay overlays, chat, selection, or editing input.
    default void setVisibleArea(Rectangle area) { }
    default void setViewport(int width, int height, int pixelWidth, int pixelHeight) { }
    default void setPointer(int x, int y) { }
    default void overlayInput(int event, int x, int y, Runnable unhandled) { unhandled.run(); }
    default void primaryClick(Coords coords, int entityId, int modifiers, long generation) { }
    default void key(int keyCode, boolean down, int modifiers) { }
    default void keyTyped(char character) { }
    default boolean chatActive() { return false; }
    default void stopKeys() { }
    default void reportUnit(int entityId) { }
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
