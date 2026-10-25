/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The editor drawing rails as a user does, by strokes: a main line, a turnout to a siding, a line crossing it (a
 * diamond, one of the junctions with a choice of tracks) and a sharp step that ends in buffer stops. The diamond's
 * junction card opens its gallery and a pick lays the double slip. A second elevated maglev crossing is refused;
 * the accepted deck is selected as bridge terrain in Layers and the side view.
 */
@Tag("on-demand")
class GpuEditorRailSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "b10-s3");

    @Test void drawsRailsWithAJunctionAndPicksItsTracksOnTheJunctionCard() throws Exception {
        var editor = new AtomicReference<BoardEditorSession>();
        Coords diamond = new Coords(4, 5), crossing = new Coords(9, 3);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var session = new BoardEditorSession(); editor.set(session);
            var board = Board.createEmptyBoard(12, 9);
            session.game().setBoard(board);
            session.command(new Command(Action.ASSET, "", RailRoute.Kind.RAIL.asset), null);
            // The main line north to south, a turnout at (4, 2) onto a siding that curves away and runs parallel.
            stroke(session, run(new Coords(4, 0), 3, 8));
            stroke(session, line(new Coords(4, 2), 2, 2, 3, 3, 3));
            // A line from the south-west crossing the main line at (4, 5) (a diamond), ending at a buffer stop.
            stroke(session, run(diamond.translated(4).translated(4).translated(4), 1, 4));
            // A sharp step (S, then SE) in one hex: no 120-degree turn, two stubs with buffer stops.
            stroke(session, line(new Coords(7, 8), 0, 0, 2));
            // A maglev at level 2; another at level 4 is refused at their crossing.
            session.command(new Command(Action.ASSET, "", RailRoute.Kind.MAGLEV.asset), null);
            session.command(new Command(Action.BRUSH_VALUE, "wayOffset", "2"), null);
            stroke(session, run(crossing.translated(0).translated(0), 3, 4));
            session.command(new Command(Action.BRUSH_VALUE, "wayOffset", "4"), null);
            stroke(session, run(crossing.translated(1).translated(1), 4, 4));
            // Trains stamped on the tracks ride them: a freight train round the siding's curve, an express on the main
            // line through the diamond, a city maglev on the lower beam.
            for (var stamp : List.of(java.util.Map.entry("stamp/rail-train", new Coords(6, 4)),
                  java.util.Map.entry("stamp/express-train", new Coords(4, 6)), java.util.Map.entry("stamp/maglev-train", crossing.translated(3)))) {
                session.command(new Command(Action.ASSET, "", stamp.getKey()), null);
                session.pointer(stamp.getValue(), 0, 0, false); session.finishStroke();
            }
            assertEquals(27, RailRoute.find(board.getHex(diamond), RailRoute.Kind.RAIL).connections(), "The drawn diamond");
            assertEquals(1, RailRoute.markers(board.getHex(crossing), RailRoute.Kind.MAGLEV).size());
            assertEquals(2, BridgeSpan.deck(board.getHex(crossing)), "The existing deck survives the refused crossing");
            session.command(new Command(Action.TOOL, "", "SELECT"), null);
            session.pointer(diamond, 0, 0, false, RailRoute.find(board.getHex(diamond), RailRoute.Kind.RAIL).id());
            session.finishStroke(); session.release();
            return new GpuMapSource(session.game(), null, session);
        });
        SwingUtilities.invokeAndWait(setup);
        var source = setup.get();
        var failure = new AtomicReference<Throwable>();
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 150_000_000_000L;
                int step;
                long after;
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Rail editor stalled at " + step);
                        if (GpuBoardTestUi.loading(this) || frames() < after) { return; }
                        var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
                        if (((GpuTerrain) field.get(this)).busy()) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        var root = GpuBoardTestUi.stage().getRoot();
                        switch (step) {
                            case 0 -> {
                                boardCamera.center(BoardGeometry.center(new Coords(5, 4), 0)); boardCamera.zoom(.6f);
                            }
                            case 1 -> {
                                assertNotNull(root.findActor("editor-junction"), "A diamond has a choice of tracks");
                                assertNotNull(root.findActor("editor-edges-rail"));
                                if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-junction"))) { return; }
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-rail-drawn.png"));
                                GpuBoardTestUi.click("editor-junction");
                            }
                            case 2 -> {
                                assertTrue(root.findActor("editor-design-gallery").isVisible());
                                assertNotNull(root.findActor("editor-junction-1"), "Crossing and double slip");
                                assertNull(root.findActor("editor-junction-2"));
                                if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-design-gallery"))) { return; }
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-rail-junction-card.png"));
                                GpuBoardTestUi.click("editor-junction-1");
                            }
                            case 3 -> {
                                assertFalse(root.findActor("editor-design-gallery").isVisible());
                                var marker = RailRoute.find(editor.get().board().getHex(diamond), RailRoute.Kind.RAIL);
                                assertEquals(RailRoute.variants(27).get(1), marker.tracks(), "The double slip is stored");
                                if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-junction"))) { return; }
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-rail-double-slip.png"));
                                SwingUtilities.invokeAndWait(() -> editor.get().command(new Command(Action.UNDO, "", ""), null));
                            }
                            case 4 -> {
                                assertEquals(0, RailRoute.find(editor.get().board().getHex(diamond), RailRoute.Kind.RAIL).tracks(),
                                      "One undo step back to the crossing");
                                var markers = RailRoute.markers(editor.get().board().getHex(crossing), RailRoute.Kind.MAGLEV);
                                SwingUtilities.invokeAndWait(() -> {
                                    editor.get().pointer(crossing, 0, 0, false, markers.getLast().id());
                                    editor.get().finishStroke(); editor.get().release();
                                });
                                boardCamera.center(BoardGeometry.center(crossing, 3)); boardCamera.zoom(.8f);
                                if (root.findActor("editor-hex-section") == null) { GpuBoardTestUi.click("editor-side-view-toggle"); }
                            }
                            case 5 -> {
                                assertEquals("bridge", source.editorState().component());
                                assertTrue(source.editorState().object().isEmpty());
                                assertNotNull(root.findActor("editor-component-bridge"), "The way belongs to its deck");
                                assertNotNull(root.findActor("editor-edges-bridge_rail"));
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-maglev-one-deck.png"));
                                Gdx.app.exit(); return;
                            }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = frames() + 12;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Rail editor", failure.get()); }
    }

    /**
     * A maglev deck's Pillars button gives the whole connected bridge its piers in one undo step and shows it pressed;
     * captured in {@code build/gpu-board-review/b10-s4/}.
     */
    @Test void thePillarsButtonGivesTheSelectedMaglevRouteItsPiers() throws Exception {
        var editor = new AtomicReference<BoardEditorSession>();
        var route = line(new Coords(1, 3), 2, 1, 2, 1, 2, 1);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var session = new BoardEditorSession(); editor.set(session);
            var board = Board.createEmptyBoard(9, 7);
            session.game().setBoard(board);
            session.command(new Command(Action.ASSET, "", RailRoute.Kind.MAGLEV.asset), null);
            session.command(new Command(Action.BRUSH_VALUE, "wayOffset", "2"), null);
            session.command(new Command(Action.BRUSH_VALUE, "wayPillars", "false"), null);
            stroke(session, route);
            session.command(new Command(Action.TOOL, "", "SELECT"), null);
            session.pointer(route.get(3), 0, 0, false, RailRoute.find(board.getHex(route.get(3)), RailRoute.Kind.MAGLEV).id());
            session.finishStroke(); session.release();
            return new GpuMapSource(session.game(), null, session);
        });
        SwingUtilities.invokeAndWait(setup);
        var source = setup.get();
        var failure = new AtomicReference<Throwable>();
        File output = new File(OUTPUT.getParentFile(), "b10-s4");
        assertTrue(output.isDirectory() || output.mkdirs());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                int step;
                long after;
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Maglev pillars stalled at " + step);
                        if (GpuBoardTestUi.loading(this) || frames() < after) { return; }
                        var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
                        if (((GpuTerrain) field.get(this)).busy()) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        var root = GpuBoardTestUi.stage().getRoot();
                        var board = editor.get().board();
                        switch (step) {
                            case 0 -> { boardCamera.center(BoardGeometry.center(route.get(3), 1)); boardCamera.zoom(.45f); }
                            case 1 -> {
                                megamek.client.ui.gdx.UiButton pillars = root.findActor("editor-bridge-pillars");
                                assertFalse(pillars.isChecked() || pillars.isDisabled());
                                GpuBoardTestUi.click("editor-bridge-pillars");
                            }
                            case 2 -> {
                                for (Coords at : route) {
                                    assertTrue(HexAppearance.pillars(board.getHex(at).getAppearance()), at + ": the whole route");
                                }
                                assertTrue(((megamek.client.ui.gdx.UiButton) root.findActor("editor-bridge-pillars")).isChecked());
                                GpuBoardTestUi.capture(new File(output, "editor-maglev-pillars.png"));
                                SwingUtilities.invokeAndWait(() -> editor.get().command(new Command(Action.UNDO, "", ""), null));
                            }
                            case 3 -> {
                                for (Coords at : route) {
                                    assertFalse(HexAppearance.pillars(board.getHex(at).getAppearance()), "One undo step");
                                }
                                Gdx.app.exit(); return;
                            }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = frames() + 12;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Maglev pillars", failure.get()); }
    }

    /** The cells from {@code start} stepping in each direction in turn. */
    static List<Coords> line(Coords start, int... directions) {
        List<Coords> cells = new ArrayList<>(List.of(start));
        for (int direction : directions) { cells.add(cells.getLast().translated(direction)); }
        return cells;
    }

    /** The cells from {@code start} stepping {@code count} times in {@code direction}. */
    static List<Coords> run(Coords start, int direction, int count) {
        int[] steps = new int[count];
        java.util.Arrays.fill(steps, direction);
        return line(start, steps);
    }

    static void stroke(BoardEditorSession session, List<Coords> cells) {
        for (int i = 0; i < cells.size(); i++) { session.pointer(cells.get(i), 0, 0, i > 0); }
        session.finishStroke();
    }
}
