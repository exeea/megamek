/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;

import megamek.client.ui.util.UIUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TokenDialogPaneTest {
    private static String previous;

    @BeforeAll
    static void installTheme() throws Exception {
        previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        UIManager.setLookAndFeel(new MegaMekDarkLaf());
    }

    @AfterAll
    static void restoreTheme() throws Exception {
        if (previous != null) {
            UIManager.setLookAndFeel(previous);
        }
    }

    @Test
    void mainActionShowsFirst() {
        TokenDialogPane pane = new TokenDialogPane("Title");
        JButton go = pane.primary("Go");
        JButton cancel = pane.cancel("Cancel");
        JButton later = pane.secondary("Later");
        assertEquals(List.of(go, cancel, later), pane.buttons());
        assertSame(go, pane.defaultButton());
        assertThrows(IllegalStateException.class, () -> pane.danger("Delete"));
    }

    @Test
    void dangerStartsOnCancelAndIsNeverTheDefault() {
        TokenDialogPane pane = new TokenDialogPane("Delete?").message("Gone for good.");
        JButton cancel = pane.cancel("Cancel");
        pane.danger("Delete");
        assertNull(pane.defaultButton());
        assertSame(cancel, pane.initialFocus());
    }

    @Test
    void focusStartsOnTheFirstInputThenTheMainAction() {
        JPanel form = new JPanel();
        JTextField name = new JTextField();
        form.add(new JLabel("Name"));
        form.add(name);
        TokenDialogPane formPane = new TokenDialogPane("Form").body(form);
        formPane.cancel("Cancel");
        formPane.primary("Create");
        assertSame(name, formPane.initialFocus());

        TokenDialogPane message = new TokenDialogPane("Note").message("Read me.");
        JButton okay = message.primary("Okay");
        assertSame(okay, message.initialFocus());
    }

    @Test
    void buttonsAndKeysReportTheChoiceOnce() {
        TokenDialogPane pane = new TokenDialogPane("Confirm").message("Sure?");
        JButton cancel = pane.cancel("Cancel");
        JButton go = pane.primary("Go");
        List<JButton> closes = new ArrayList<>();
        pane.assemble(true, closes::add);
        pane.assemble(false, closes::add);

        go.doClick();
        cancel.doClick();
        pane.getActionMap().get("cancel").actionPerformed(new ActionEvent(pane, ActionEvent.ACTION_PERFORMED, ""));
        pane.getActionMap().get("accept").actionPerformed(new ActionEvent(pane, ActionEvent.ACTION_PERFORMED, ""));
        assertEquals(4, closes.size());
        assertSame(go, closes.get(0));
        assertNull(closes.get(1));
        assertNull(closes.get(2));
        assertSame(go, closes.get(3));
    }

    @Test
    void footerButtonsShareOneCentredWidth() {
        int minWidth = UIUtil.scaleForGUI(TokenDialogPane.BUTTON_MIN_WIDTH);
        JPanel row = new JPanel(new TokenDialogPane.EqualRow(8, minWidth));
        JButton shortButton = new JButton("OK");
        JButton longButton = new JButton("Delete this whole force forever");
        row.add(shortButton);
        row.add(longButton);
        row.setSize(row.getPreferredSize().width + 100, row.getPreferredSize().height);
        row.doLayout();

        assertEquals(shortButton.getWidth(), longButton.getWidth());
        assertTrue(longButton.getWidth() >= longButton.getPreferredSize().width);
        assertTrue(shortButton.getWidth() >= minWidth);
        assertEquals(8, longButton.getX() - shortButton.getX() - shortButton.getWidth());
        int left = shortButton.getX();
        int right = row.getWidth() - longButton.getX() - longButton.getWidth();
        assertTrue(Math.abs(left - right) <= 1, "row is centred");
    }
}
