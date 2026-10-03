/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Rectangle;
import java.util.List;

import org.junit.jupiter.api.Test;

class ScreenFitTest {
    /** A 1920x1080 primary monitor with a 40-high taskbar, and a second 1920x1080 monitor to its right. */
    private static final Rectangle PRIMARY = new Rectangle(0, 0, 1920, 1040);
    private static final Rectangle SECOND = new Rectangle(1920, 0, 1920, 1040);
    private static final List<Rectangle> BOTH = List.of(PRIMARY, SECOND);

    @Test
    void aWindowOnScreenStaysWhereItIs() {
        Rectangle window = new Rectangle(200, 150, 1280, 800);
        assertEquals(window, ScreenFit.fit(window, BOTH));
    }

    @Test
    void aWindowOnTheSecondMonitorStaysThere() {
        Rectangle window = new Rectangle(2100, 100, 1280, 800);
        assertEquals(window, ScreenFit.fit(window, BOTH));
    }

    @Test
    void aWindowHangingPartlyOffTheSideStaysWhileItsTitleBarCanBeGrabbed() {
        Rectangle window = new Rectangle(-900, 100, 1280, 800);
        assertEquals(window, ScreenFit.fit(window, BOTH));
    }

    @Test
    void aWindowLeftOnAnUnpluggedMonitorComesBackToTheRemainingOne() {
        Rectangle window = new Rectangle(2100, 100, 1280, 800);
        assertEquals(new Rectangle(640, 100, 1280, 800), ScreenFit.fit(window, List.of(PRIMARY)));
    }

    @Test
    void aTitleBarUnderTheTaskbarMovesUpIntoTheWorkArea() {
        Rectangle window = new Rectangle(300, 1045, 1280, 800);
        assertEquals(new Rectangle(300, 240, 1280, 800), ScreenFit.fit(window, List.of(PRIMARY)));
    }

    @Test
    void aTitleBarAboveTheTopEdgeMovesDown() {
        Rectangle window = new Rectangle(300, -500, 1280, 800);
        assertEquals(new Rectangle(300, 0, 1280, 800), ScreenFit.fit(window, List.of(PRIMARY)));
    }

    @Test
    void aWindowBiggerThanItsMonitorShrinksToFit() {
        Rectangle window = new Rectangle(0, 0, 2560, 1440);
        assertEquals(new Rectangle(0, 0, 1920, 1040), ScreenFit.fit(window, List.of(PRIMARY)));
    }

    @Test
    void mirroredMonitorsOnEitherSideTreatTheWindowAlike() {
        Rectangle left = new Rectangle(-1920, 0, 1920, 1040);
        Rectangle offLeft = new Rectangle(-5000, 100, 1280, 800);
        Rectangle offRight = new Rectangle(1920 + 5000 - 1280, 100, 1280, 800);
        Rectangle right = new Rectangle(1920, 0, 1920, 1040);
        assertEquals(new Rectangle(-1920, 100, 1280, 800), ScreenFit.fit(offLeft, List.of(left, PRIMARY, right)));
        assertEquals(new Rectangle(3840 - 1280, 100, 1280, 800), ScreenFit.fit(offRight, List.of(left, PRIMARY, right)));
    }

    @Test
    void withNoMonitorsReportedTheWindowIsLeftAlone() {
        Rectangle window = new Rectangle(-9000, -9000, 1280, 800);
        assertEquals(window, ScreenFit.fit(window, List.of()));
    }
}
