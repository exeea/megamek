/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Input;
import megamek.client.ui.gdx.UiVirtualList.Range;
import org.junit.jupiter.api.Test;

class UiVirtualListTest {

    @Test
    void onlyTheRowsInViewAreBound() {
        // 100 rows of 20 in a list 2000 high; y runs up from the bottom, so the first rows sit at the top.
        assertEquals(new Range(0, 4), UiVirtualList.visible(2000, 20, 100, 1900, 2000));
        assertEquals(new Range(50, 54), UiVirtualList.visible(2000, 20, 100, 900, 1000));
        assertEquals(new Range(95, 99), UiVirtualList.visible(2000, 20, 100, -50, 100));
    }

    @Test
    void aRowPartlyInViewIsBound() {
        assertEquals(new Range(0, 5), UiVirtualList.visible(2000, 20, 100, 1890, 2000));
        assertEquals(new Range(1, 5), UiVirtualList.visible(2000, 20, 100, 1890, 1975));
    }

    @Test
    void nothingIsBoundWithoutRowsOrView() {
        assertEquals(Range.NONE, UiVirtualList.visible(0, 20, 0, 0, 100));
        assertEquals(Range.NONE, UiVirtualList.visible(2000, 0, 100, 0, 100));
        assertEquals(Range.NONE, UiVirtualList.visible(2000, 20, 100, 100, 100));
        assertEquals(0, Range.NONE.count());
        assertFalse(Range.NONE.contains(0));
        assertTrue(new Range(2, 4).contains(4));
    }

    @Test
    void keysMoveTheSelectionWithinTheList() {
        assertEquals(0, UiVirtualList.step(-1, 10, Input.Keys.DOWN, 5));
        assertEquals(9, UiVirtualList.step(-1, 10, Input.Keys.UP, 5));
        assertEquals(9, UiVirtualList.step(9, 10, Input.Keys.DOWN, 5));
        assertEquals(0, UiVirtualList.step(0, 10, Input.Keys.UP, 5));
        assertEquals(7, UiVirtualList.step(2, 10, Input.Keys.PAGE_DOWN, 5));
        assertEquals(0, UiVirtualList.step(2, 10, Input.Keys.PAGE_UP, 5));
        assertEquals(9, UiVirtualList.step(2, 10, Input.Keys.END, 5));
        assertEquals(0, UiVirtualList.step(6, 10, Input.Keys.HOME, 5));
        assertEquals(-1, UiVirtualList.step(3, 0, Input.Keys.DOWN, 5));
    }

    @Test
    void theStepperKeepsItsValueInRange() {
        assertEquals(1, UiStepper.clamp(-2, 1, 12));
        assertEquals(12, UiStepper.clamp(13, 1, 12));
        assertEquals(6, UiStepper.clamp(6, 1, 12));
    }
}
