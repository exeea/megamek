/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector2;
import org.junit.jupiter.api.Test;

/**
 * The target cards' placement against the prototype's own: each example below ran through the hud-v3 mock's layout.js
 * (placeCards, unchanged, in node), and its results are the expected rectangles. The examples are written as the mock
 * sees them, in CSS pixels of a 1920 x 1080 window with y down, and turned into stage units with y up. The HUD panels
 * are shot 05's, inflated by 6 as the mock's refreshHudRects does; the window bounds are 8 inside it.
 */
class GpuCardPlacementTest {
    private static final int HEIGHT = 1080;
    /** Shot 05's panels (r1 section 2): phase, forces, unit card, utilities, minimap, weapons, solution, dock, hint. */
    private static final int[][] PANELS = { { 20, 20, 300, 84 }, { 20, 116, 300, 507 }, { 20, 808, 300, 252 },
          { 1535, 18, 365, 56 }, { 1590, 90, 310, 208 }, { 1590, 312, 310, 556 }, { 1590, 880, 310, 130 },
          { 670, 892, 580, 158 }, { 722, 1056, 476, 17 }, { 1740, 1026, 160, 36 } };
    private static final Rectangle BOUNDS = new Rectangle(8, 8, 1920 - 16, HEIGHT - 16);
    /** The acting Atlas, a soft block in every example with a shooter. */
    private static final Rectangle ATLAS = css(900, 740, 60, 110);

    @Test
    void shot05sTwoCardsCostTheSameInBothOrdersSoTheGivenOrderStays() {
        // The Timber Wolf's card (A) takes the place above it; the BattleMaster's (B) moves 137 left of its own.
        // Reversed, B would stay and A move 137: 18769 either way, and the mock keeps the given order.
        Rectangle wolf = css(930, 300, 60, 110);
        Rectangle master = css(760, 220, 50, 90);
        Map<Integer, Rectangle> placed = GpuCardPlacement.place(List.of(card(6, 300, 266, wolf),
              card(8, 300, 134, master)), BOUNDS, hud(), List.of(ATLAS, wolf, master));
        assertEquals(Map.of(6, css(810, 8, 300, 266), 8, css(498, 60, 300, 134)), placed);
    }

    @Test
    void theCheaperOfTheTwoOrdersWins() {
        // Given order: the first card sits above its target (780, 92) and pushes the second to (1092, 200), 46628.8 in
        // all. Reversed: the second stays above its target and the first moves to (568, 128), 44944, which wins.
        Rectangle left = css(900, 420, 60, 110);
        Rectangle right = css(1000, 360, 60, 110);
        Map<Integer, Rectangle> placed = GpuCardPlacement.place(List.of(card(1, 300, 266, left),
              card(2, 300, 134, right)), BOUNDS, hud(), List.of(ATLAS, left, right));
        assertEquals(Map.of(1, css(568, 128, 300, 266), 2, css(880, 200, 300, 134)), placed);
    }

    @Test
    void shot06sThreeCardsAnExpandedOneAndTwoCollapsed() {
        Rectangle wolf = css(980, 300, 60, 110);
        Rectangle crab = css(790, 190, 60, 110);
        Rectangle master = css(700, 400, 50, 90);
        Map<Integer, Rectangle> placed = GpuCardPlacement.place(List.of(card(6, 300, 218, wolf),
              card(8, 260, 96, master), card(7, 260, 96, crab)), BOUNDS, hud(), List.of(ATLAS, wolf, master, crab));
        assertEquals(Map.of(6, css(860, 56, 300, 218), 8, css(518, 278, 260, 96), 7, css(588, 68, 260, 96)), placed);
        assertEquals(List.of(6, 8, 7), List.copyOf(placed.keySet()), "in the order of the cards");
    }

    @Test
    void onATieTheGivenOrderKeepsTheBestPlaceForTheFirstCard() {
        // Two equal cards over one target: either order costs 15163.2, so the first card gets the place above it.
        Rectangle target = css(1000, 500, 40, 60);
        Map<Integer, Rectangle> placed = GpuCardPlacement.place(List.of(card(1, 260, 96, target),
              card(2, 260, 96, target)), BOUNDS, hud(), List.of(target));
        assertEquals(Map.of(1, css(890, 378, 260, 96), 2, css(890, 270, 260, 96)), placed);
    }

    @Test
    void aCardWithoutRoomAboveItsTargetGoesBesideIt() {
        Rectangle target = css(600, 60, 40, 60);
        Map<Integer, Rectangle> placed = GpuCardPlacement.place(List.of(card(3, 300, 122, target)), BOUNDS, hud(),
              List.of(target));
        assertEquals(Map.of(3, css(652, 8, 300, 122)), placed);
    }

    @Test
    void measuredSizesAndProjectedUnitsKeepTheirFractions() {
        Rectangle wolf = css(931.5f, 301.25f, 61, 109.5f);
        Rectangle master = css(760.75f, 221.5f, 49, 90.25f);
        Map<Integer, Rectangle> placed = GpuCardPlacement.place(List.of(card(6, 300, 265.75f, wolf),
              card(8, 270, 121.5f, master)), BOUNDS, hud(), List.of(css(900.5f, 740.25f, 61, 110.5f), wolf, master));
        assertEquals(Map.of(6, css(812, 9.5f, 300, 265.75f), 8, css(530, 74, 270, 121.5f)), placed);
    }

    @Test
    void rulerCardsKeepClearOfHorizontalVerticalDiagonalAndPartlyOffscreenLines() {
        Rectangle area = new Rectangle(0, 0, 1000, 700);
        for (float[] ends : new float[][] { { 80, 350, 920, 350 }, { 500, 30, 500, 680 },
              { 20, 20, 980, 680 }, { 20, 680, 980, 20 }, { -500, 20, 1500, 680 },
              { 500, 350, 500, 350 }, { 15, 5, 15, 690 } }) {
            var line = new GpuCardPlacement.Line(ends[0], ends[1], ends[2], ends[3], 24);
            Rectangle point = new Rectangle((ends[0] + ends[2]) / 2, (ends[1] + ends[3]) / 2, 0, 0);
            Rectangle card = GpuCardPlacement.place(List.of(card(0, 320, 280, point)), area, List.of(), List.of(), line).get(0);
            assertTrue(card.x >= 0 && card.y >= 0 && card.x + card.width <= area.width
                  && card.y + card.height <= area.height, "Card stays on screen: " + card);
            Rectangle clearance = new Rectangle(card.x - 24, card.y - 24, card.width + 48, card.height + 48);
            assertFalse(Intersector.intersectSegmentRectangle(new Vector2(ends[0], ends[1]),
                  new Vector2(ends[2], ends[3]), clearance), "Card leaves clearance along the full ruler: " + line);
        }
    }

    @Test
    void rulerCardUsesTheFreeSideWhenAnEditorPanelOccupiesTheOtherSide() {
        Rectangle panel = new Rectangle(0, 0, 480, 700), area = new Rectangle(0, 0, 1000, 700);
        var line = new GpuCardPlacement.Line(500, 20, 500, 680, 24);
        Rectangle card = GpuCardPlacement.place(List.of(card(0, 320, 400, new Rectangle(500, 350, 0, 0))),
              area, List.of(panel), List.of(), line).get(0);
        assertTrue(card.x > 524, "Use the free right side, including a gap");
        assertFalse(card.overlaps(panel));
        assertTrue(card.x >= 0 && card.y >= 0 && card.x + card.width <= area.width
              && card.y + card.height <= area.height, "Touching a viewport edge is allowed: " + card);
    }

    private static GpuCardPlacement.Card card(int id, float width, float height, Rectangle target) {
        return new GpuCardPlacement.Card(id, width, height, target);
    }

    /** Shot 05's panels inflated by 6, in stage units. */
    private static List<Rectangle> hud() {
        List<Rectangle> panels = new ArrayList<>();
        for (int[] panel : PANELS) {
            panels.add(css(panel[0] - 6, panel[1] - 6, panel[2] + 12, panel[3] + 12));
        }
        return panels;
    }

    /** A rectangle given as the mock's CSS does, from the window's top, in stage units with y up. */
    private static Rectangle css(float x, float top, float width, float height) {
        return new Rectangle(x, HEIGHT - top - height, width, height);
    }
}
