/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The step chart's scale and labels, without a GL context (LobbyDossierTabsSmokeTest draws one). */
class UiStepChartTest {

    @Test
    void theCeilingRoundsUpToOneTwoOrFiveTimesAPowerOfTen() {
        assertEquals(1, UiStepChart.ceiling(0));
        assertEquals(1, UiStepChart.ceiling(0.7f));
        assertEquals(20, UiStepChart.ceiling(12));
        assertEquals(50, UiStepChart.ceiling(45.6f));
        assertEquals(100, UiStepChart.ceiling(85));
        assertEquals(100, UiStepChart.ceiling(100));
        assertEquals(200, UiStepChart.ceiling(101));
    }

    @Test
    void theBandsStartsAndTheLastStepAreLabelled() {
        assertArrayEquals(new int[] { 1, 4, 7, 21 }, UiStepChart.labelledSteps(21, new int[] { 1, 4, 7 }));
        assertArrayEquals(new int[] { 1, 4, 6 }, UiStepChart.labelledSteps(6, new int[] { 1, 4, 7 }),
              "a band past the reach has no label");
        assertArrayEquals(new int[] { 1, 6, 11 }, UiStepChart.labelledSteps(11, new int[0]),
              "without bands: the first, the middle and the last");
        assertArrayEquals(new int[] { 1 }, UiStepChart.labelledSteps(1, new int[] { 1 }));
        assertArrayEquals(new int[0], UiStepChart.labelledSteps(0, new int[] { 1 }));
    }
}
