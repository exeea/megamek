/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardTacticalGraphicsTest {
    @Test
    void joiningUsesOnlyMatchingBoundaryOwnersAndMembershipInvalidatesTheCommand() {
        var yellow = new BoardRangeBorder(.5f, Color.YELLOW.getRGB(), null);
        var cyan = new BoardRangeBorder(.5f, Color.CYAN.getRGB(), null);
        Coords first = new Coords(0, 0), second = new Coords(0, 1), outside = new Coords(1, 0);
        var a = new BoardTactical.Wall(first, new BoardTactical.Point(21, 0), new BoardTactical.Point(21, 72),
              yellow, 0, BoardTactical.Playback.LIVE);
        var b = new BoardTactical.Wall(second, new BoardTactical.Point(21.00001f, 72), new BoardTactical.Point(21, 144),
              yellow, 0, BoardTactical.Playback.LIVE);
        var unrelated = new BoardTactical.Wall(outside, a.b(), new BoardTactical.Point(84, 108),
              cyan, 0, BoardTactical.Playback.LIVE);
        var joined = BoardRangeBorder.join(List.of(a, b, unrelated));
        assertTrue(joined.getFirst().aNeighbors().isEmpty());
        assertEquals(List.of(second), joined.getFirst().bNeighbors());
        assertEquals(List.of(first), joined.get(1).aNeighbors());
        assertTrue(joined.getLast().aNeighbors().isEmpty(), "An unrelated overlay cannot raise this boundary");
        var removed = BoardRangeBorder.join(List.of(a, unrelated)).getFirst();
        assertEquals(a, removed, "Removing the adjoining interior panel returns this wall to its own support");
        assertNotEquals(joined.getFirst(), removed, "Retained GPU geometry must invalidate even without a terrain edit");
        assertEquals(joined.getFirst(), BoardRangeBorder.join(List.of(unrelated, b, a)).getLast(),
              "Painter reordering does not change joint ownership");
    }

    @Test
    void sharedRangePropertiesPreserveClosedPathsDashDistancesTransformsAndPlayback() {
        var graphics = new BoardTacticalGraphics();
        try {
            graphics.setColor(Color.CYAN);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .2f));
            graphics.translate(100, 200);
            graphics.scale(2, 2);
            var outline = new BoardTactical.Outline(Color.WHITE.getRGB(), new BasicStroke(2,
                  BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] { 5, 3 }, 0));
            var border = new BoardRangeBorder(.5f, 0x80FFCC00, .75f, outline);
            Path2D path = new Path2D.Float();
            path.moveTo(1, 2); path.lineTo(4, 2); path.lineTo(4, 6); path.closePath();
            path.moveTo(10, 1); path.lineTo(10, 3);
            BoardTacticalGraphics.draw(graphics, BoardTactical.Playback.HOLD_DURING_PLAYBACK,
                  local -> ((BoardTacticalGraphics) local).wall(path, new Rectangle2D.Float(0, 0, 20, 20),
                        new Coords(0, 0), border));
            var captured = graphics.snapshot();
            assertEquals(4, captured.walls().size());
            assertEquals(List.of(0f, 6f, 14f, 0f),
                  captured.walls().stream().map(BoardTactical.Wall::outlineDistance).toList());
            assertEquals(new BoardTactical.Point(102, 204), captured.walls().getFirst().a());
            assertEquals(new BoardTactical.Point(102, 204), captured.walls().get(2).b());
            assertEquals(new BoardTactical.Point(120, 206), captured.walls().getLast().b());
            for (var wall : captured.walls()) {
                assertEquals(border, wall.border());
                assertEquals(BoardTactical.Playback.HOLD_DURING_PLAYBACK, wall.playback());
            }
            assertEquals(0x60FFCC00, captured.flatWalls().getFirst().argb(),
                  "Explicit border opacity applies once to both wall and overhead band");
            assertEquals(Color.CYAN, graphics.getColor());
            assertEquals(.2f, ((AlphaComposite) graphics.getComposite()).getAlpha());
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void hexBorderMetadataPreservesTheCapturedShapeAndComposedScaleColorAndPlayback() {
        AffineTransform transform = new AffineTransform(1.5, 0, 0, 1.5, 126, 108);
        var expected = capturedBorder(transform, null, false);
        var actual = capturedBorder(transform, null, true);
        assertNotNull(actual.border());
        assertEquals(new BoardTactical.Point(215.25f, 189), actual.border().anchor());
        assertEquals(1.875f, actual.border().scale());
        assertEquals(1.5, actual.border().padding());
        assertEquals(3.25, actual.border().width());
        assertTrue(actual.border().floating());
        assertEquals(expected, new BoardTactical.Fill(actual.contours(), actual.winding(), actual.argb(), actual.playback()));
        assertEquals(93, actual.argb() >>> 24, "The painter's composite alpha remains part of the command");
        assertEquals(BoardTactical.Playback.HOLD_DURING_PLAYBACK, actual.playback());

        var graphics = new BoardTacticalGraphics();
        try {
            graphics.fillHexBorder(new Point(0, 0), 1, 0, 1);
            assertNotNull(graphics.snapshot().fills().getFirst().border());
            assertFalse(graphics.snapshot().fills().getFirst().border().floating(),
                  "Existing callers retain terrain-following presentation unless explicitly changed");
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void transformedAndPartiallyClippedBordersRetainTheExactGenericPath() {
        for (AffineTransform transform : List.of(AffineTransform.getRotateInstance(.15),
              new AffineTransform(1, .25, 0, 1, 0, 0), new AffineTransform(-1, 0, 0, 1, 150, 0),
              AffineTransform.getScaleInstance(2, 1))) {
            var actual = capturedBorder(transform, null, true);
            assertNull(actual.border(), "Rotation, shear, reflection and nonuniform scaling need generic geometry");
            assertEquals(capturedBorder(transform, null, false), actual);
        }
        Area holes = new Area(new Rectangle2D.Float(0, 0, 200, 200));
        holes.subtract(new Area(new Rectangle2D.Float(20, 8, 20, 10)));
        for (Shape clip : List.of(new Rectangle2D.Float(0, 0, 50, 120), holes)) {
            var actual = capturedBorder(new AffineTransform(), clip, true);
            assertNotNull(actual.border(), "Clipping must not turn a floating border back into draped geometry");
            assertTrue(actual.border().floating());
            var expected = capturedBorder(new AffineTransform(), clip, false);
            assertEquals(expected,
                  new BoardTactical.Fill(actual.contours(), actual.winding(), actual.argb(), actual.playback()),
                  "The floating plane must still use the exact clipped outline");
            var draped = capturedBorder(new AffineTransform(), clip, true, false);
            assertNull(draped.border(), "An analytic terrain mask cannot restore pixels removed by the painter's clip");
            assertEquals(expected, draped);
        }
    }

    private static BoardTactical.Fill capturedBorder(AffineTransform transform, Shape clip, boolean specialized) {
        return capturedBorder(transform, clip, specialized, true);
    }

    private static BoardTactical.Fill capturedBorder(AffineTransform transform, Shape clip, boolean specialized,
          boolean floating) {
        var graphics = new BoardTacticalGraphics();
        try {
            graphics.setClip(clip);
            graphics.transform(transform);
            graphics.setColor(new Color(120, 50, 220, 155));
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .6f));
            BoardTacticalGraphics.draw(graphics, BoardTactical.Playback.HOLD_DURING_PLAYBACK, local -> {
                Point point = new Point(7, 9);
                if (specialized) {
                    ((BoardTacticalGraphics) local).fillHexBorder(point, 1.25f, 1.5, 3.25, floating);
                } else {
                    local.fill(AffineTransform.getTranslateInstance(point.x, point.y)
                          .createTransformedShape(AffineTransform.getScaleInstance(1.25f, 1.25f)
                                .createTransformedShape(HexDrawUtilities.getHexFullBorderArea(3.25, 1.5))));
                }
            });
            return graphics.snapshot().fills().getFirst();
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void cachedHexBordersPreserveTransformsClippingColorAndPlaybackAcrossGraphicsCopies() {
        Area clip = new Area(new Rectangle2D.Float(0, 0, 150, 150));
        clip.subtract(new Area(new Rectangle2D.Float(25, 25, 30, 35)));
        var reference = new BoardTacticalGraphics();
        var capture = new BoardTacticalGraphics();
        try {
            reference.setClip(clip);
            capture.setClip(clip);
            for (Point origin : List.of(new Point(0, 0), new Point(23, 17))) {
                var expected = BoardTacticalGraphics.at(reference, origin);
                var actual = BoardTacticalGraphics.at(capture, origin);
                try {
                    expected.rotate(.15);
                    actual.rotate(.15);
                    expected.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .6f));
                    actual.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .6f));
                    for (Point point : List.of(new Point(7, 9), new Point(-35, 42), new Point(98, 85))) {
                        for (Color color : List.of(Color.CYAN, new Color(210, 85, 120, 117))) {
                            expected.setColor(color);
                            actual.setColor(color);
                            BoardTacticalGraphics.draw(expected, BoardTactical.Playback.HOLD_DURING_PLAYBACK,
                                  graphics -> graphics.fill(AffineTransform.getTranslateInstance(point.x, point.y)
                                        .createTransformedShape(AffineTransform.getScaleInstance(1.25f, 1.25f)
                                              .createTransformedShape(HexDrawUtilities.getHexFullBorderArea(3.25, 1.5)))));
                            BoardTacticalGraphics.draw(actual, BoardTactical.Playback.HOLD_DURING_PLAYBACK,
                                  graphics -> ((BoardTacticalGraphics) graphics).fillHexBorder(point, 1.25f, 1.5, 3.25));
                        }
                    }
                } finally {
                    expected.dispose();
                    actual.dispose();
                }
            }
            assertEquals(reference.snapshot(), capture.snapshot());
        } finally {
            reference.dispose();
            capture.dispose();
        }
    }

    @Test
    void containedFillsRetainBoundariesHolesAndWinding() {
        Shape clip = new Rectangle2D.Float(0, 0, 96, 96);
        Path2D holes = new Path2D.Float(Path2D.WIND_EVEN_ODD);
        holes.append(new Rectangle2D.Float(10, 10, 70, 70), false);
        holes.append(new Rectangle2D.Float(25, 25, 40, 40), false);
        Path2D overlapping = new Path2D.Float(Path2D.WIND_NON_ZERO);
        overlapping.append(new Rectangle2D.Float(10, 10, 40, 40), false);
        overlapping.append(new Rectangle2D.Float(30, 30, 40, 40), false);
        for (Shape shape : List.of(new Rectangle2D.Float(0, 0, 96, 96), holes, overlapping,
              new Polygon(new int[] { 8, 88, 8, 88 }, new int[] { 8, 88, 88, 8 }, 4))) {
            assertCaptureMatches(clip, new AffineTransform(), shape);
        }
    }

    @Test
    void complexClipHolesAndPartiallyCoveredFillsStillClip() {
        Area clip = new Area(new Rectangle2D.Float(8, 8, 80, 80));
        clip.subtract(new Area(new Rectangle2D.Float(32, 32, 32, 32)));
        for (Shape shape : List.of(new Rectangle2D.Float(12, 12, 12, 12),
              new Rectangle2D.Float(16, 16, 64, 64), new Rectangle2D.Float(40, 40, 8, 8),
              new Rectangle2D.Float(0, 0, 30, 30), new Rectangle2D.Float(0, 0, 4, 4))) {
            assertCaptureMatches(clip, new AffineTransform(), shape);
        }
    }

    @Test
    void clipContainmentUsesTheCurrentTranslatedScaledAndRotatedCoordinates() {
        Shape clip = new Rectangle2D.Float(8, 8, 80, 80);
        for (AffineTransform transform : List.of(AffineTransform.getTranslateInstance(20, 12),
              new AffineTransform(2, 0, 0, 2, 8, 8), new AffineTransform(0, 1, -1, 0, 80, 8),
              new AffineTransform(1, .5, .25, 1, 12, 12))) {
            for (Shape shape : List.of(new Rectangle2D.Float(8, 8, 16, 16),
                  new Rectangle2D.Float(-12, -12, 80, 80))) {
                assertCaptureMatches(clip, transform, shape);
            }
        }
    }

    /** Compare the published vectors with ordinary Graphics2D under the same clip and transform. */
    private static void assertCaptureMatches(Shape clip, AffineTransform transform, Shape shape) {
        BufferedImage expected = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
        BufferedImage actual = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
        var reference = expected.createGraphics();
        var replay = actual.createGraphics();
        var capture = new BoardTacticalGraphics();
        try {
            Color ink = new Color(35, 150, 200, 128);
            reference.setClip(clip);
            reference.transform(transform);
            reference.setColor(ink);
            reference.fill(shape);
            capture.setClip(clip);
            capture.transform(transform);
            capture.setColor(ink);
            capture.fill(shape);
            for (BoardTactical.Fill fill : capture.snapshot().fills()) {
                replay.setColor(new Color(fill.argb(), true));
                replay.fill(fill.shape());
            }
            assertArrayEquals(expected.getRGB(0, 0, 96, 96, null, 0, 96),
                  actual.getRGB(0, 0, 96, 96, null, 0, 96));
        } finally {
            reference.dispose();
            replay.dispose();
            capture.dispose();
        }
    }
}
