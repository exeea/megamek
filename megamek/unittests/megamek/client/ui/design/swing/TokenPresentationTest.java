/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Canvas;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import javax.swing.JLabel;
import javax.swing.JPanel;

import megamek.MMConstants;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Presentation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TokenPresentationTest {
    private static final String CONDENSED = "Roboto Condensed Medium";

    private static final File FONTS = new File(MMConstants.FONT_DIRECTORY, "Roboto");

    /**
     * Registers the condensed face as FontHandler does at startup. Only this face: registering the whole folder would
     * change the default font, and so the metrics, of the other Swing tests in this JVM.
     */
    @BeforeAll
    static void registerCondensedFace() throws Exception {
        assertTrue(GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(face("Roboto-CondensedMedium.ttf"))
              || Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
              .contains(CONDENSED));
    }

    private static Font face(String file) throws Exception {
        try (InputStream stream = new FileInputStream(new File(FONTS, file))) {
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        }
    }

    @Test
    void theStaticCondensedFaceResolvesByFamily() throws Exception {
        Font condensed = new Font(CONDENSED, Font.PLAIN, 13);
        Font medium = face("Roboto-Medium.ttf").deriveFont(13f);
        assertEquals(CONDENSED, condensed.getFamily());
        assertEquals(CONDENSED, condensed.getFontName());
        Canvas canvas = new Canvas();
        String sample = "ATLAS AS7-D";
        assertTrue(canvas.getFontMetrics(condensed).stringWidth(sample)
              < 0.9 * canvas.getFontMetrics(medium).stringWidth(sample), "Condensed is narrower");
        assertEquals(CONDENSED, TokenFonts.font("title", true, true).getFamily());
        assertThrows(IllegalArgumentException.class, () -> TokenFonts.font("body", false, true));
    }

    @Test
    void gameModeSetsFramingLabelsAndNamesCondensedAndAppModePutsThemBack() {
        DesignTokens tokens = DesignTokens.shipped();
        JPanel root = new JPanel();
        JLabel title = TokenPresentation.role(new JLabel("Unit status"), "title");
        title.setFont(TokenFonts.font("title"));
        JLabel name = TokenPresentation.role(new JLabel("Atlas AS7-D"), "name");
        name.setFont(TokenFonts.font("body.strong"));
        JLabel body = new JLabel("Closes to 4 hexes");
        body.setFont(TokenFonts.font("body"));
        root.add(title);
        root.add(name);
        root.add(body);
        Font titleFont = title.getFont();
        Font nameFont = name.getFont();
        Font bodyFont = body.getFont();
        assertNotEquals(CONDENSED, titleFont.getFamily(), "App mode is regular width");

        TokenPresentation.setMode(root, Presentation.GAME);
        assertEquals("UNIT STATUS", title.getText());
        assertEquals(CONDENSED, title.getFont().getFamily());
        assertEquals(titleFont.getSize2D(), title.getFont().getSize2D(), 0.01);
        assertEquals(tokens.roleStyle("title").capsTracking(), tracking(title.getFont()), 1e-6);
        assertEquals("Atlas AS7-D", name.getText(), "Names keep their case");
        assertEquals(CONDENSED, name.getFont().getFamily());
        assertEquals(0, tracking(name.getFont()), 1e-6);
        assertEquals(bodyFont, body.getFont(), "Body text stays regular width");

        TokenPresentation.setMode(root, Presentation.APP);
        assertEquals("Unit status", title.getText());
        assertEquals(titleFont, title.getFont());
        assertEquals(nameFont, name.getFont());
    }

    /** A font's tracking; Java2D leaves the default, 0, out of the attributes. */
    private static float tracking(Font font) {
        Object tracking = font.getAttributes().get(TextAttribute.TRACKING);
        return tracking == null ? 0 : ((Number) tracking).floatValue();
    }
}
