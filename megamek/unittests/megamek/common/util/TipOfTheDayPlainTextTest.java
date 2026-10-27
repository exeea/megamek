/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TipOfTheDayPlainTextTest {
    @Test
    void tagsAreDroppedAndLineBreaksKept() {
        assertEquals("Hold Ctrl to fire\nall weapons", TipOfTheDay.plainText("<b>Hold Ctrl</b> to fire<br>all weapons"));
        assertEquals("One\nTwo", TipOfTheDay.plainText("<html>One<BR/>Two</html>"));
    }

    @Test
    void entitiesAreDecodedAndSpacingTidied() {
        assertEquals("Heat & ammo", TipOfTheDay.plainText("Heat&nbsp;&amp;   ammo"));
        assertEquals("First\nSecond", TipOfTheDay.plainText("  First  <br>  <br>  Second  "));
    }
}
