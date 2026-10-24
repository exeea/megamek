/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The hover card's head breaks only between its terms, into the fewest lines that fit and as even as they come.
 * Character counts stand in for the font's widths.
 */
class GpuHexTipTest {
    private static final List<String> TERMS = List.of("Hex 1222", "Light woods (TF: 50)", "Woods/Jungle elevation: 2",
          "level 0");

    @Test
    void theHeadBreaksBetweenTermsIntoEvenLines() {
        // 69 characters in all: two lines, the first ending after the woods rather than one word short of the end.
        assertEquals(List.of("Hex 1222 · Light woods (TF: 50)", "Woods/Jungle elevation: 2 · level 0"),
              GpuHexTip.balance(TERMS, String::length, 60));
        assertEquals(List.of(String.join(" · ", TERMS)), GpuHexTip.balance(TERMS, String::length, 69));
    }

    @Test
    void aTermWiderThanTheCardKeepsALineOfItsOwn() {
        assertEquals(List.of("Hex 1222", "Woods/Jungle elevation: 2", "level 0"),
              GpuHexTip.balance(List.of("Hex 1222", "Woods/Jungle elevation: 2", "level 0"), String::length, 12));
    }
}
