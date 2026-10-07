/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Rectangle;

/**
 * Where the target cards float over the board (C.1 G9, r2 section 5): the prototype's layout.js, in stage units with
 * y up. A card prefers the place centred {@link #ABOVE} units above its target. Its candidates are that place and
 * every position that keeps {@link #GAP} units from the target, from a block (a HUD panel, a card placed before it, a
 * unit) or touches the bounds. A candidate costs its overlap with the panels and the placed cards times 1e6, its
 * overlap with the units times 40, and its squared distance from the ideal place, the vertical part 1.3 times as dear;
 * the cheapest wins, the first on a tie. The cards are placed one after the other in the given order and in the
 * reverse order, and the order of the lower total cost is kept, the given one on a tie. Pure: no state, no GL.
 */
final class GpuCardPlacement {
    /** The ideal place's distance above the target. */
    static final float ABOVE = 26;
    /** The room a candidate keeps from the target and from each block. */
    static final float GAP = 12;
    /** The cost of one square unit of overlap with a panel or a placed card, and with a unit. */
    private static final double HARD = 1e6;
    private static final double SOFT = 40;
    /** How much dearer a vertical step from the ideal place is than a horizontal one. */
    private static final double VERTICAL = 1.3;

    /** A card to place: its id, its size and its target's screen rectangle. */
    record Card(int id, float width, float height, Rectangle target) { }

    /** A screen-space ruler and its clearance, including its endpoint markers. */
    record Line(float x1, float y1, float x2, float y2, float clearance) {
        boolean overlaps(Rectangle rectangle) { return overlaps(Box.of(rectangle)); }

        private boolean overlaps(Box box) {
            return new Rectangle2D.Double(box.x() - clearance, box.y() - clearance,
                  box.width() + 2 * clearance, box.height() + 2 * clearance).intersectsLine(x1, y1, x2, y2);
        }
    }

    /** A rectangle in double precision, as the prototype's numbers: every candidate is computed from these. */
    private record Box(double x, double y, double width, double height) {
        static Box of(Rectangle rectangle) {
            return new Box(rectangle.x, rectangle.y, rectangle.width, rectangle.height);
        }

        double top() {
            return y + height;
        }

        double right() {
            return x + width;
        }
    }

    /** One order's placements by card id and its total cost. */
    private record Arrangement(Map<Integer, Box> placed, double total) { }

    private GpuCardPlacement() { }

    /**
     * Places {@code cards} inside {@code bounds}, keeping clear of {@code panels} (hard) and {@code units} (soft), and
     * returns each card's rectangle by its id, in the order of {@code cards}.
     */
    static Map<Integer, Rectangle> place(List<Card> cards, Rectangle bounds, List<Rectangle> panels,
          List<Rectangle> units) {
        return place(cards, bounds, panels, units, null);
    }

    /** The same placement with a ruler to keep visible. Diagonal lines leave their two sides available. */
    static Map<Integer, Rectangle> place(List<Card> cards, Rectangle bounds, List<Rectangle> panels,
          List<Rectangle> units, Line line) {
        Box area = Box.of(bounds);
        List<Box> hard = panels.stream().map(Box::of).toList();
        List<Box> soft = units.stream().map(Box::of).toList();
        Arrangement chosen = arrange(cards, area, hard, soft, line);
        if (cards.size() > 1) {
            Arrangement reverse = arrange(cards.reversed(), area, hard, soft, line);
            if (reverse.total() < chosen.total()) {
                chosen = reverse;
            }
        }
        Map<Integer, Rectangle> placed = new LinkedHashMap<>();
        for (Card card : cards) {
            Box box = chosen.placed().get(card.id());
            placed.put(card.id(), new Rectangle((float) box.x(), (float) box.y(), (float) box.width(),
                  (float) box.height()));
        }
        return placed;
    }

    /**
     * Places the cards one after the other: the panels and the cards placed before are hard blocks, the units soft
     * ones. The candidates and their order are the prototype's, mirrored for y up (its "above" is a greater y here).
     */
    private static Arrangement arrange(List<Card> order, Box bounds, List<Box> panels, List<Box> units, Line line) {
        Map<Integer, Box> placed = new LinkedHashMap<>();
        double total = 0;
        for (Card card : order) {
            double width = card.width();
            double height = card.height();
            Box target = Box.of(card.target());
            double idealX = target.x() + target.width() / 2 - width / 2;
            double idealY = target.top() + ABOVE;
            List<Box> hard = new ArrayList<>(panels);
            hard.addAll(placed.values());
            List<Box> blocks = new ArrayList<>(hard);
            blocks.addAll(units);
            Set<Double> xs = new LinkedHashSet<>();
            Set<Double> ys = new LinkedHashSet<>();
            for (double x : new double[] { idealX, target.right() + GAP, target.x() - width - GAP, bounds.x(),
                  bounds.right() - width }) {
                xs.add(clampX(x, width, bounds));
            }
            for (double y : new double[] { idealY, target.y() + target.height() / 2 - height / 2,
                  target.y() - GAP - height, bounds.top() - height, bounds.y() }) {
                ys.add(clampY(y, height, bounds));
            }
            for (Box block : blocks) {
                xs.add(clampX(block.x() - width - GAP, width, bounds));
                xs.add(clampX(block.right() + GAP, width, bounds));
                ys.add(clampY(block.top() + GAP, height, bounds));
                ys.add(clampY(block.y() - GAP - height, height, bounds));
            }
            if (line != null) {
                // Positions on either side of the actual segment, including near its endpoints. A bounding box
                // would wrongly occupy most of the viewport for a long diagonal ruler.
                double dx = line.x2() - line.x1(), dy = line.y2() - line.y1();
                double length = Math.hypot(dx, dy);
                double nx = length == 0 ? 1 : -dy / length, ny = length == 0 ? 0 : dx / length;
                double offset = Math.abs(nx) * (width / 2 + line.clearance())
                      + Math.abs(ny) * (height / 2 + line.clearance()) + 1;
                for (double along : new double[] { 0, .5, 1 }) {
                    for (int side : new int[] { -1, 1 }) {
                        xs.add(clampX(line.x1() + dx * along + nx * offset * side - width / 2, width, bounds));
                        ys.add(clampY(line.y1() + dy * along + ny * offset * side - height / 2, height, bounds));
                    }
                }
                xs.add(clampX(Math.min(line.x1(), line.x2()) - line.clearance() - width - 1, width, bounds));
                xs.add(clampX(Math.max(line.x1(), line.x2()) + line.clearance() + 1, width, bounds));
                ys.add(clampY(Math.min(line.y1(), line.y2()) - line.clearance() - height - 1, height, bounds));
                ys.add(clampY(Math.max(line.y1(), line.y2()) + line.clearance() + 1, height, bounds));
            }
            Box best = null;
            double score = Double.POSITIVE_INFINITY;
            for (double x : xs) {
                for (double y : ys) {
                    // Every term is at least 0 and rounding keeps sums in order, so a candidate whose distance, or
                    // overlap with the hard blocks so far, already costs the best score cannot be cheaper: skipping
                    // it changes nothing but the time (many cards and panels make thousands of candidates).
                    double distance = (x - idealX) * (x - idealX) + (y - idealY) * (y - idealY) * VERTICAL;
                    if (distance >= score) {
                        continue;
                    }
                    double hardOverlap = line != null && line.overlaps(new Box(x, y, width, height))
                          ? width * height + 1 : 0;
                    for (int index = 0; index < hard.size() && hardOverlap * HARD < score; index++) {
                        hardOverlap += overlap(x, y, width, height, hard.get(index));
                    }
                    if (hardOverlap * HARD >= score) {
                        continue;
                    }
                    double softOverlap = 0;
                    for (Box block : units) {
                        softOverlap += overlap(x, y, width, height, block);
                    }
                    double cost = hardOverlap * HARD + softOverlap * SOFT + (x - idealX) * (x - idealX)
                          + (y - idealY) * (y - idealY) * VERTICAL;
                    if (cost < score) {
                        score = cost;
                        best = new Box(x, y, width, height);
                    }
                }
            }
            total += score;
            placed.put(card.id(), best);
        }
        return new Arrangement(placed, total);
    }

    /** Inside the bounds' width; a card wider than them keeps to their left edge. */
    private static double clampX(double x, double width, Box bounds) {
        return Math.max(bounds.x(), Math.min(bounds.right() - width, x));
    }

    /** Inside the bounds' height; a card taller than them keeps to their top edge, as the prototype's. */
    private static double clampY(double y, double height, Box bounds) {
        return Math.min(bounds.top() - height, Math.max(bounds.y(), y));
    }

    private static double overlap(double x, double y, double width, double height, Box block) {
        return Math.max(0, Math.min(x + width, block.right()) - Math.max(x, block.x()))
              * Math.max(0, Math.min(y + height, block.top()) - Math.max(y, block.y()));
    }
}
