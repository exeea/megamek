/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import megamek.client.ui.design.DesignTokens.ContrastCheck;
import megamek.client.ui.design.DesignTokens.DistinctCheck;
import megamek.client.ui.design.DesignTokens.Mode;
import org.junit.jupiter.api.Test;

class DesignTokensTest {
    private static final Pattern ROLE_NAME = Pattern.compile("[a-z]+(\\.[a-z][a-zA-Z]*)+");

    @Test
    void contrastMatchesWcag() {
        assertEquals(21, ColorMath.contrast(0xFF000000, 0xFFFFFFFF), 1e-9);
        assertEquals(1, ColorMath.contrast(0xFF777777, 0xFF777777), 1e-9);
        assertEquals(4.48, ColorMath.contrast(0xFF777777, 0xFFFFFFFF), 0.01);
    }

    @Test
    void deltaEMatchesTheCiede2000ReferenceData() {
        // Sharma, Wu and Dalal (2005), table 1, pairs 1 and 7.
        assertEquals(2.0425, ColorMath.deltaE2000(new double[] { 50, 2.6772, -79.7751 },
              new double[] { 50, 0, -82.7485 }), 1e-4);
        assertEquals(2.3669, ColorMath.deltaE2000(new double[] { 50, 0, 0 }, new double[] { 50, -1, 2 }), 1e-4);
    }

    @Test
    void colorsParseAndPrintBothForms() {
        assertEquals(0xFF82E2CE, ColorMath.parse("#82E2CE"));
        assertEquals(0x3D9982D3, ColorMath.parse("#9982D33D"));
        assertEquals("#9982D33D", ColorMath.hex(0x3D9982D3));
        assertEquals("#82E2CE", ColorMath.hex(0xFF82E2CE));
        assertEquals(0xFF808080, ColorMath.over(0x80FFFFFF, 0xFF000000));
    }

    @Test
    void greysLookTheSameToEveryone() {
        for (Vision vision : Vision.values()) {
            assertEquals(0, ColorMath.deltaE(0xFF808080, ColorMath.simulate(0xFF808080, vision), Vision.NORMAL), 1.0,
                  vision.name());
        }
    }

    @Test
    void everyTokenIsWellFormedAndEveryCheckNamesARealToken() {
        DesignTokens tokens = DesignTokens.shipped();
        assertFalse(tokens.colors().isEmpty());
        for (String name : tokens.colors().keySet()) {
            assertTrue(ROLE_NAME.matcher(name).matches(), "Role names are group.role: " + name);
            assertFalse(tokens.colorToken(name).use().isBlank(), name + " says what it is for");
        }
        for (ContrastCheck check : tokens.contrastChecks()) {
            check.foregrounds().forEach(tokens::colorToken);
            check.backgrounds().forEach(tokens::colorToken);
            if (check.over() != null) {
                tokens.colorToken(check.over());
            }
        }
        for (DistinctCheck check : tokens.distinctChecks()) {
            check.colors().forEach(tokens::colorToken);
        }
        assertTrue(tokens.typeStyles().containsKey("body"));
        assertEquals(1, tokens.typeStyles().values().stream().filter(DesignTokens.TypeStyle::upperCase).count(),
              "Only one upper-case text style");
    }

    @Test
    void everyAccessibilityCheckPassesInBothModes() {
        List<String> failures = new ArrayList<>();
        List<TokenChecks.Result> results = TokenChecks.run(DesignTokens.shipped());
        for (TokenChecks.Result result : results) {
            if (!result.passes()) {
                failures.add(String.format("%s %s: %.2f < %.2f%s (%s)", result.mode(), result.subject(),
                      result.value(), result.min(), result.vision() == null ? "" : " for " + result.vision(),
                      result.why()));
            }
        }
        assertTrue(results.stream().anyMatch(result -> result.mode() == Mode.LIGHT));
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
