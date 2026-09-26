/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.awt.Color;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

class BoardTacticalGraphicsTest {
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
