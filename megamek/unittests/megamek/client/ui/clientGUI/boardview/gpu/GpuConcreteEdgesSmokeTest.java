/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Set;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.ConcreteFit;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A pavement hex's Edges in the editor (b12): a raised quay over water with a car on a wall the fit moves into it. The
 * Edit panel's Auto keeps the car's hex outline, Fitted fits it through the car and lists "stands inside a concrete
 * wall" under Issues (the issue selects the car), Hex keeps plain hex steps; the pavement brush has its Edges row.
 * Captures in {@code build/gpu-board-review/concrete-edges/}.
 */
@Tag("on-demand")
class GpuConcreteEdgesSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "concrete-edges");
    private static final int WIDTH = 12, HEIGHT = 10;

    /** The quay: rows 0 to 4 paved a level above the water of the rows south of it. */
    private static Board quay() {
        Board board = Board.createEmptyBoard(WIDTH, HEIGHT);
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                board.setHex(new Coords(x, y), y <= 4 ? new Hex(1, "pavement:1", "") : new Hex(0, "water:1", ""));
            }
        }
        board.setNativeFormat(true);
        return board;
    }

    /** A quay hex and the middle of the band its wall toward the water sweeps when the fit moves both its corners in. */
    private record Spot(Coords at, double x, double y) { }

    private static Spot spot(Board board) {
        ConcreteFit fit = new ConcreteFit(WIDTH, HEIGHT, ConcreteFit.cells(board), 1, true, Set.of());
        for (int x = 2; x < WIDTH - 2; x++) {
            Coords at = new Coords(x, 4);
            for (int k = 0; k < 6; k++) {
                if (!board.contains(at.translated(HexTile.edgeDirection(k)))
                      || !board.getHex(at.translated(HexTile.edgeDirection(k))).containsTerrain(megamek.common.units.Terrains.WATER)) {
                    continue;
                }
                float ax = HexTile.cornerX(at, k, HexTile.WIDTH), ay = HexTile.cornerY(at, k, HexTile.HEIGHT);
                float bx = HexTile.cornerX(at, (k + 1) % 6, HexTile.WIDTH), by = HexTile.cornerY(at, (k + 1) % 6, HexTile.HEIGHT);
                var a = fit.corner(at, k);
                var b = fit.corner(at, (k + 1) % 6);
                float first = (bx - ax) * (a.y() - ay) - (by - ay) * (a.x() - ax);
                float second = (bx - ax) * (b.y() - ay) - (by - ay) * (b.x() - ax);
                if (first > 1 && second > 1) {
                    double mx = ((ax + bx) / 2 + (a.x() + b.x()) / 2) / 2 - HexTile.centerX(at, HexTile.WIDTH);
                    double my = ((ay + by) / 2 + (a.y() + b.y()) / 2) / 2 - HexTile.centerY(at, HexTile.HEIGHT);
                    return new Spot(at, mx / HexTile.WIDTH, my / HexTile.HEIGHT);
                }
            }
        }
        throw new AssertionError("The quay has no wall the fit moves in");
    }

    private static void capture(String name) {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        GpuBoardTestUi.capture(new File(OUTPUT, name));
    }

    private static List<ConcreteFit.Shift> corners(GpuMapSource source, Coords at) {
        return BoardConcrete.of(source.takeFrame().scene()).corners(at);
    }

    private static boolean plain(List<ConcreteFit.Shift> corners) {
        return corners.stream().allMatch(shift -> shift.equals(ConcreteFit.Shift.ZERO));
    }

    @Test
    void theEditRowSwitchesAutoFittedAndHexAndFittedListsTheCarInsideTheWall() throws Exception {
        Board board = quay();
        Spot spot = spot(board);
        Coords at = spot.at();
        List<ConcreteFit.Shift> fitted = new ConcreteFit(WIDTH, HEIGHT, ConcreteFit.cells(board), 1, true, Set.of()).corners(at);
        board.getHex(at).setDecorations(List.of(new BoardDecoration("car", "prop", "scenery/vehicles/car", null, spot.x(), spot.y(),
              30, false, 1, BoardDecoration.Placement.ground(), 0)));
        GpuEditorPaletteSmokeTest.run(1366, 768, () -> board, List.of(
              (view, source, root) -> {
                  if (GpuBoardTestUi.busy(view)) { return false; }
                  long generation = source.takeFrame().boardGeneration();
                  source.editorCommand(new Command(Action.TOOL, "", "SELECT"), generation);
                  source.editorCommand(new Command(Action.SELECT_AT, at.getX() + "," + at.getY(), ""), generation);
                  source.editorCommand(new Command(Action.COMPONENT, "", "pavement"), generation);
                  view.boardCamera.orbit(30 - view.boardCamera.azimuth(), 45 - view.boardCamera.tilt());
                  view.boardCamera.center(BoardGeometry.center(at, 1));
                  view.boardCamera.zoom(.3f / view.boardCamera.camera.zoom);
                  return true;
              },
              (view, source, root) -> {
                  if (GpuBoardTestUi.busy(view) || root.findActor("editor-pavement-edges-auto") == null) { return false; }
                  assertTrue(plain(corners(source, at)), "Auto: the car keeps its hex outline");
                  assertTrue(source.editorState().issues().isEmpty());
                  capture("edges-auto.png");
                  GpuBoardTestUi.click("editor-pavement-edges-fitted");
                  return true;
              },
              (view, source, root) -> {
                  if (GpuBoardTestUi.busy(view) || !GpuBoardTestUi.shown(root.findActor("editor-issues"))) { return false; }
                  assertEquals(fitted, corners(source, at), "Fitted: the plain fit, through the car");
                  assertNotEquals(List.of(), fitted.stream().filter(shift -> !shift.equals(ConcreteFit.Shift.ZERO)).toList());
                  capture("edges-fitted.png");
                  GpuBoardTestUi.click("editor-issues");
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-issue-list"));
                  assertTrue(GpuBoardTestUi.texts(root.findActor("editor-issue-list")).stream()
                        .anyMatch(text -> text.contains("stands inside a concrete wall")), "The issue names the wall");
                  capture("edges-issues.png");
                  GpuBoardTestUi.click("editor-issue-" + at.getBoardNum());
                  return true;
              },
              (view, source, root) -> {
                  assertEquals("car", source.editorState().object(), "The issue selects the car");
                  long generation = source.takeFrame().boardGeneration();
                  source.editorCommand(new Command(Action.SELECT_AT, at.getX() + "," + at.getY(), ""), generation);
                  source.editorCommand(new Command(Action.COMPONENT, "", "pavement"), generation);
                  return true;
              },
              (view, source, root) -> {
                  if (root.findActor("editor-pavement-edges-hex") == null) { return false; }
                  GpuBoardTestUi.click("editor-pavement-edges-hex");
                  return true;
              },
              (view, source, root) -> {
                  if (GpuBoardTestUi.busy(view) || GpuBoardTestUi.shown(root.findActor("editor-issues"))) { return false; }
                  assertTrue(plain(corners(source, at)), "Hex: plain hex steps");
                  capture("edges-hex.png");
                  source.editorCommand(new Command(Action.CHOOSE_BRUSH, "pavement", "1"), source.takeFrame().boardGeneration());
                  return true;
              },
              (view, source, root) -> {
                  if (root.findActor("editor-brush-edges") == null) { return false; }
                  capture("edges-brush.png");
                  return true;
              }));
    }
}
