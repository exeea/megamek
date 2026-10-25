/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.clientGUI.boardview.TacticalStyle;
import megamek.common.Facing;
import megamek.common.board.Coords;
import megamek.common.enums.MoveStepType;
import megamek.common.moves.MovePath;
import megamek.common.units.EntityMovementType;
import megamek.common.units.VTOL;
import org.junit.jupiter.api.Test;

class GpuTacticalTest {
    @Test
    void flatSpritePaintersShareTheOwnerPlaneWithoutTerrainClipping() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Coords coords = new Coords(4, 4);
                var path = new MovePath(fixture.game, fixture.entity);
                path.addStep(MoveStepType.FORWARDS);
                var vtol = new VTOL();
                vtol.setOwner(fixture.player);
                vtol.getStrafingCoords().addAll(List.of(coords, coords.translated(3)));
                List<TacticalMark> marks = List.of(new TacticalMark.Envelope(coords, 63, Color.MAGENTA),
                      new TacticalMark.FacingModifier(coords, Facing.N, "+1", Color.CYAN),
                      new TacticalMark.Step(path.getStepVector().getFirst(), true),
                      new TacticalMark.FlightPath(path.getStepVector(), 0, true), new TacticalMark.LosEnd(coords),
                      new TacticalMark.SensorRange(coords, 63, TacticalStyle.SENSORS),
                      new TacticalMark.ControlZone(coords, 63, Color.GREEN), new TacticalMark.VtolAttack(vtol),
                      new TacticalMark.CraneTarget(coords, Color.ORANGE), new TacticalMark.HexText(coords, "7", Color.WHITE),
                      new TacticalMark.BridgeBuild(coords, 1, 2, 9, 1, true));
                for (TacticalMark mark : marks) {
                    fixture.view.addMarks(List.of(mark));
                    fixture.source.refresh();
                    BoardScene scene = fixture.source.takeFrame().scene();
                    String name = mark.getClass().getSimpleName();
                    assertFalse(scene.tactical().fills().isEmpty(), name);
                    for (var fill : scene.tactical().fills()) {
                        assertNotNull(fill.planeAnchor(), name);
                        Coords owner = BoardTacticalGeometry.anchorCoords(scene, fill.planeAnchor());
                        assertNotNull(owner);
                        float z = BoardTacticalGeometry.floatingZ(scene, owner);
                        List<BoardTacticalGeometry.Triangle> triangles = new ArrayList<>();
                        BoardTacticalGeometry.drape(scene, fill, 10000, triangles::add, queried -> {
                            throw new AssertionError("A flat sprite must not query terrain at " + queried);
                        }, new BoardTacticalGeometry.Clipper());
                        assertEquals(BoardTacticalGeometry.flat(fill).size(), triangles.size());
                        for (var triangle : triangles) {
                            for (Vector3 point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                                assertEquals(z, point.z, .00001f);
                            }
                        }
                    }
                    fixture.view.removeMarks(List.of(mark));
                }
            });
        }
    }

    @Test
    void visualRangeUsesStraightWallsAndRetainsSpriteVisibilityAndPlayback() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                var before = marks(fixture.view);
                var first = new TacticalMark.SensorRange(new Coords(2, 4), 48, TacticalStyle.VISUAL);
                var second = new TacticalMark.SensorRange(new Coords(2, 5), 48, TacticalStyle.VISUAL_DARK);
                fixture.view.addMarks(List.of(first, second));
                fixture.source.refresh();
                var captured = fixture.source.takeFrame().scene().tactical();
                assertEquals(before.fills(), captured.fills(),
                      "The flat band and dashed line must be replaced");
                assertEquals(2, captured.walls().size());
                assertEquals(2, captured.flatWalls().size());
                captured.flatWalls().forEach(fill -> assertNull(fill.planeAnchor(),
                      "The top-view presentation of upright walls keeps its terrain-following footprint"));
                for (var wall : captured.walls()) {
                    // The run across the hex: its west corners' x, the centre's less a quarter hex.
                    assertEquals(1.75f * BoardGeometry.TILE_WIDTH, wall.a().x(), 0.001f);
                    assertEquals(1.75f * BoardGeometry.TILE_WIDTH, wall.b().x(), 0.001f);
                    assertEquals(72, Math.abs(wall.b().y() - wall.a().y()), 0.001f);
                    assertTrue(wall.height() > 0);
                    assertTrue((wall.argb() >>> 24) > 0 && (wall.argb() >>> 24) < 255);
                    assertEquals(TacticalStyle.sensorColor(TacticalStyle.VISUAL).getRGB() & 0xFFFFFF,
                          wall.argb() & 0xFFFFFF);
                }
                assertTrue(captured.duringPlayback(captured, true).walls().isEmpty());
                assertTrue(captured.duringPlayback(captured, true).flatWalls().isEmpty());
                assertEquals(captured.walls(), captured.duringPlayback(captured, false).walls());
                assertEquals(captured.flatWalls(), captured.duringPlayback(captured, false).flatWalls());
                fixture.view.removeMarks(List.of(first, second));
                fixture.source.refresh();
                assertEquals(before, fixture.source.takeFrame().scene().tactical());
                assertEquals(2, captured.walls().size(), "Published geometry remains immutable");

                // Exercise every shared border layout, including lone sides and closed corner paths.
                for (int mode = TacticalStyle.SENSORS; mode <= TacticalStyle.VISUAL_DARK; mode++) {
                    for (int mask = 1; mask < 64; mask++) {
                        var range = captureRange(fixture.view, new TacticalMark.SensorRange(new Coords(4, 4), mask, mode),
                              false);
                        assertEquals(mode >= TacticalStyle.VISUAL, range.fills().isEmpty());
                        assertEquals(mode < TacticalStyle.VISUAL, range.walls().isEmpty());
                        assertEquals(mode < TacticalStyle.VISUAL, range.flatWalls().isEmpty());
                    }
                }
                var preferences = GUIPreferences.getInstance();
                Color original = preferences.getVisualRangeColor();
                try {
                    Color configured = new Color(35, 150, 200);
                    preferences.setVisualRangeColor(configured);
                    fixture.view.addMarks(List.of(first));
                    fixture.source.refresh();
                    var recolored = fixture.source.takeFrame().scene().tactical().walls().getFirst();
                    assertEquals(configured.getRGB() & 0xFFFFFF, recolored.argb() & 0xFFFFFF);
                    assertEquals(captured.walls().getFirst().argb() >>> 24, recolored.argb() >>> 24);
                } finally {
                    preferences.setVisualRangeColor(original);
                }
            });
        }
    }

    @Test
    void gpuMapBorderCanBeHiddenWithoutRemovingInteriorRangeBoundaries() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                var board = fixture.view.getBoard();
                for (int mode = TacticalStyle.SENSORS; mode <= TacticalStyle.VISUAL_DARK; mode++) {
                    for (int x = 0; x < board.getWidth(); x++) {
                        for (int y = 0; y < board.getHeight(); y++) {
                            Coords coords = new Coords(x, y);
                            int mapBorder = 0, interior = 0;
                            for (int direction = 0; direction < 6; direction++) {
                                if (!board.contains(coords.translated(direction))) {
                                    mapBorder |= 1 << direction;
                                } else {
                                    interior = 1 << direction;
                                }
                            }
                            if (mapBorder == 0) {
                                continue;
                            }
                            var perimeter = new TacticalMark.SensorRange(coords, mapBorder, mode);
                            assertEquals(BoardTactical.EMPTY, captureRange(fixture.view, perimeter, false),
                                  "The default must omit forced map-edge outlines, including at corners");
                            var mixed = new TacticalMark.SensorRange(coords, mapBorder | interior, mode);
                            var genuine = new TacticalMark.SensorRange(coords, interior, mode);
                            assertNotEquals(BoardTactical.EMPTY, captureRange(fixture.view, genuine, false));
                            assertEquals(captureRange(fixture.view, genuine, false),
                                  captureRange(fixture.view, mixed, false),
                                  "Keep a real range boundary in the same hex as a suppressed map edge");
                            assertNotEquals(BoardTactical.EMPTY, captureRange(fixture.view, perimeter, true),
                                  "Enabling the option must restore the map perimeter for every range mode");
                        }
                    }
                }
            });
        }
    }

    private static BoardTactical captureRange(BoardClientState view, TacticalMark.SensorRange range,
          boolean mapBorder) {
        var graphics = new BoardTacticalGraphics();
        try {
            BoardTacticalMarks.sensorRange(graphics, view.getBoard(), range, mapBorder);
            return graphics.snapshot();
        } finally {
            graphics.dispose();
        }
    }

    /** The board's markings now, with the path's steps. */
    static BoardTactical marks(BoardClientState view) {
        return new BoardTacticalMarks().capture(view, false);
    }

    @Test
    void playbackKeepsObjectiveBoundariesAndLiveMeasurementsWhileHidingUnitOverlays() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            var second = GpuUnitHudTest.addUnits(fixture, 1).getFirst();
            SwingUtilities.invokeAndWait(() -> {
                var zone = new TacticalMark.ControlZone(new Coords(2, 4), 63, Color.GREEN);
                var cursor = new TacticalMark.LosEnd(new Coords(3, 2));
                fixture.view.addMarks(List.of(zone, cursor));
                fixture.source.refresh();
                var playback = new UnitPlayback();
                playback.present(fixture.source.takeFrame().scene());

                var marks = overlays(fixture);
                marks.add(new TacticalMark.C3Link(fixture.entity, second));
                fixture.view.addMarks(List.of(marks.getLast()));
                fixture.view.removeMarks(marks);
                fixture.view.removeMarks(List.of(cursor));
                var moved = new TacticalMark.LosEnd(new Coords(3, 3));
                fixture.view.addMarks(List.of(moved));
                BoardTactical expected = marks(fixture.view);
                fixture.view.addMarks(marks);
                fixture.view.removeMarks(List.of(zone));
                fixture.view.addMarks(List.of(new TacticalMark.ControlZone(new Coords(7, 4), 63, Color.ORANGE)));
                fixture.source.refresh();
                var latest = fixture.source.takeFrame().scene();
                var unit = latest.units().getFirst();
                playback.accept(List.of(new BoardScene.Movement(unit.id(), latest.boardId(),
                      List.of(new BoardScene.Waypoint(new Coords(4, 5), 0, 0), unit.location()),
                      EntityMovementType.MOVE_WALK, 0, 1, unit)), latest, ignored -> false);
                var shown = playback.present(latest).tactical();
                assertFalse(expected.fills().isEmpty());
                assertEquals(expected.fills().size(), shown.fills().size());
                assertTrue(shown.fills().containsAll(expected.fills()), "Keep the old zone and current ruler/LOS geometry");
                assertEquals(expected.labels(), shown.labels(), "Movement costs and flight labels must disappear");
                assertTrue(latest.tactical().fills().size() > shown.fills().size());
                assertFalse(latest.tactical().labels().isEmpty());
                playback.advance(0, UnitMotion.Speed.NORMAL);
                playback.advance(playback.motions.get(unit.id()).remainingSeconds() / UnitMotion.Speed.NORMAL.rate,
                      UnitMotion.Speed.NORMAL);
                assertEquals(latest.tactical(), playback.present(latest).tactical());
            });
        }
    }

    static List<TacticalMark> overlays(GpuBoardFixture fixture) {
        var path = new MovePath(fixture.game, fixture.entity);
        path.addStep(MoveStepType.FORWARDS);
        path.addStep(MoveStepType.TURN_RIGHT);
        List<TacticalMark> marks = new ArrayList<>();
        marks.add(new TacticalMark.Envelope(new Coords(4, 4), 63, Color.MAGENTA));
        marks.add(new TacticalMark.SensorRange(new Coords(6, 4), 63, TacticalStyle.SENSORS));
        marks.add(new TacticalMark.Step(path.getStepVector().getFirst(), false));
        marks.add(new TacticalMark.Step(path.getStepVector().getLast(), true));
        marks.add(new TacticalMark.FlightPath(path.getStepVector(), 0, true));
        fixture.view.addMarks(marks);
        fixture.view.drawRuler(new Coords(3, 5), new Coords(8, 6), Color.CYAN, Color.YELLOW);
        return marks;
    }

    @Test
    void shapesAndTextLeaveTheRasterLayerAndRestoreLegacy2DZoom() throws Exception {
        int originalZoom = GUIPreferences.getInstance().getMapZoomIndex();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            var second = GpuUnitHudTest.addUnits(fixture, 1).getFirst();
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                BoardScene before = fixture.source.takeFrame().scene();
                var marks = overlays(fixture);
                marks.add(new TacticalMark.C3Link(fixture.entity, second));
                fixture.view.addMarks(List.of(marks.getLast()));
                fixture.source.refresh();
                BoardScene captured = fixture.source.takeFrame().scene();
                assertFalse(captured.tactical().fills().isEmpty());
                assertFalse(captured.tactical().labels().isEmpty(), "Movement costs must remain readable labels");
                assertEquals(before.tiles().stream().map(BoardScene.Tile::tactical).toList(),
                      captured.tiles().stream().map(BoardScene.Tile::tactical).toList());
                BoardView legacy2D = fixture.legacy2DView();
                try {
                    legacy2D.getComponent();
                    legacy2D.zoomOut();
                    float zoom = legacy2D.getScale();
                    assertEquals(captured.tactical(), marks(fixture.view),
                          "The Legacy 2D zoom leaves the markings; the native frame takes its ruler from the native LOS model");
                    assertNull(captured.tactical().ruler(), "No native measurement was started");
                    assertEquals(zoom, legacy2D.getScale());
                } finally {
                    legacy2D.dispose();
                }
                fixture.view.removeMarks(marks);
                BoardTactical hidden = marks(fixture.view);
                assertNotEquals(captured.tactical(), hidden);
                fixture.view.drawRuler(null, null, Color.CYAN, Color.YELLOW);
                assertTrue(marks(fixture.view).fills().isEmpty());
                assertFalse(captured.tactical().fills().isEmpty(), "Published geometry must remain immutable");
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> GUIPreferences.getInstance().setMapZoomIndex(originalZoom));
        }
    }
}
