/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.CellRendererPane;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolTip;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.ui.FlatButtonUI;
import com.formdev.flatlaf.ui.FlatLineBorder;
import com.formdev.flatlaf.ui.FlatStylingSupport;
import com.formdev.flatlaf.util.UIScale;
import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.util.UIUtil;
import org.junit.jupiter.api.Test;

class TokenLafDefaultsTest {
    /** Keys FlatLaf styles through the component's own setters rather than the UI delegate. */
    private static final Set<String> COMPONENT_KEYS = Set.of("background", "foreground", "margin", "font");
    /** Washes painted over whatever content lies under them rather than as a component's background. */
    private static final Set<String> TRANSLUCENT_KEYS = Set.of("ScrollBar.hoverTrackColor", TokenTables.HOVER_WASH,
          TokenSections.HOVER_BACKGROUND);

    private final DesignTokens tokens = DesignTokens.shipped();

    @Test
    void darkLookAndFeelUsesTheTokens() {
        assertUsesTokens(new MegaMekDarkLaf(), Mode.DARK);
    }

    @Test
    void lightLookAndFeelUsesTheTokens() {
        assertUsesTokens(new MegaMekLightLaf(), Mode.LIGHT);
    }

    @Test
    void everyColorValueIsOpaque() {
        for (Mode mode : Mode.values()) {
            for (Map.Entry<String, String> entry : TokenLafDefaults.of(mode).entrySet()) {
                if (entry.getValue().startsWith("#") && !TRANSLUCENT_KEYS.contains(entry.getKey())) {
                    assertEquals(7, entry.getValue().length(), entry.getKey() + " must be opaque");
                }
            }
        }
    }

    @Test
    void buttonStyleClassesParseAndApply() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                for (AbstractButton button : List.of(new JButton(), new JToggleButton())) {
                    Set<String> styleable = ((FlatButtonUI) button.getUI()).getStyleableInfos(button).keySet();
                    String type = button instanceof JToggleButton ? "ToggleButton" : "Button";
                    for (String styleClass : List.of("primary", "danger", "quiet", "icon", "regular", "compact")) {
                        Object style = UIManager.get("[style]" + type + "." + styleClass);
                        if (style == null && button instanceof JToggleButton) {
                            continue;
                        }
                        assertNotNull(style, type + "." + styleClass);
                        for (String key : FlatStylingSupport.parse((String) style).keySet()) {
                            assertTrue(styleable.contains(key) || COMPONENT_KEYS.contains(key), type + "." + key);
                        }
                    }
                }
                JButton compact = TokenButtons.button(TokenButtons.Kind.DANGER, "Delete", TokenButtons.Size.COMPACT);
                int side = Math.round(tokens.space().get("md"));
                assertEquals(new Insets(0, side, 0, side), compact.getMargin());
                assertTrue(compact.getPreferredSize().height >= UIUtil.scaleForGUI(TokenButtons.Size.COMPACT.height()));
                JToggleButton regular = TokenButtons.toggle("Show arcs", TokenButtons.Size.REGULAR);
                assertTrue(regular.getPreferredSize().height >= UIUtil.scaleForGUI(TokenButtons.Size.REGULAR.height()));
                Dimension icon = TokenButtons.icon(0xE8B6, "Search", false, TokenButtons.Size.REGULAR)
                      .getPreferredSize();
                assertEquals(icon.width, icon.height);
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void inputsMatchButtonHeightsAndShowHoverAndReadOnlyEdges() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        // The edges ease between states; with reduce motion each one shows at once
        TokenMotion.setReduced(true);
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                int compact = UIUtil.scaleForGUI(TokenButtons.Size.COMPACT.height());
                int regular = UIUtil.scaleForGUI(TokenButtons.Size.REGULAR.height());
                // Plain Swing inputs are compact; the factory makes regular ones
                assertTrue(new JTextField().getPreferredSize().height >= compact, "plain text field");
                assertTrue(new JPasswordField().getPreferredSize().height >= compact, "plain password field");
                assertTrue(new JSpinner().getPreferredSize().height >= compact, "plain spinner");
                assertTrue(new JComboBox<>(new String[] { "A" }).getPreferredSize().height >= compact, "plain combo");
                assertEquals(regular, TokenFields.text(TokenButtons.Size.REGULAR).getPreferredSize().height);
                assertEquals(regular, TokenFields.number(new SpinnerNumberModel(4, 0, 8, 1),
                      TokenButtons.Size.REGULAR).getPreferredSize().height);
                // The spinner's own editor keeps its natural height
                JSpinner spinner = TokenFields.number(new SpinnerNumberModel(4, 0, 8, 1), TokenButtons.Size.REGULAR);
                JTextField editor = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
                assertTrue(editor.getPreferredSize().height < regular, "spinner editor");

                Color control = new Color(tokens.color("border.control", mode));
                JTextField field = new JTextField();
                assertEquals(control, FieldEdge.color(field, control, true, false));
                field.putClientProperty(FieldEdge.FROZEN_HOVER, true);
                assertEquals(new Color(tokens.color("border.strong", mode)),
                      new Color(((Color) FieldEdge.color(field, control, true, false)).getRGB()));
                assertEquals(control, FieldEdge.color(field, control, true, true), "focus wins over hover");
                assertEquals(control, FieldEdge.color(field, control, false, false), "disabled");
                field.setEditable(false);
                assertEquals(new Color(tokens.color("border.subtle", mode)),
                      new Color(((Color) FieldEdge.color(field, control, true, false)).getRGB()), "read-only");
                // A scroll pane only gets input edges around text
                JScrollPane table = new JScrollPane(new JTable());
                table.putClientProperty(FieldEdge.FROZEN_HOVER, true);
                assertEquals(control, FieldEdge.color(table, control, true, false));
                assertEquals(spinner, FieldEdge.edgeOf(editor));
            }
        } finally {
            TokenMotion.setReduced(false);
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void choicesAreIconSizedAndShowMixedAndSwitchStates() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                int size = Math.round(tokens.size().get("icon.sm"));
                // The 14-unit box sits in a 15-unit icon; scaled to the token, the box is icon.sm
                assertEquals(Math.round(UIScale.scale(15) * size / 14f), new TokenCheckBoxIcon().getIconWidth());
                assertEquals(Math.round(UIScale.scale(15) * size / 15f), new TokenRadioButtonIcon().getIconWidth());
                TokenSwitchIcon track = new TokenSwitchIcon();
                assertEquals(UIScale.scale(size), track.getIconHeight());
                assertEquals(UIScale.scale(Math.round(size * TokenSwitchIcon.LENGTH)), track.getIconWidth());
                assertColor(lookAndFeel.getDefaults(), "CheckBox.icon.switchThumbColor", "text.secondary", mode);
                assertColor(lookAndFeel.getDefaults(), "CheckBox.icon.hoverSelectedBorderColor", "border.strong", mode);

                // A mixed box reads as unchecked, and a click checks it and drops the mixed look
                JCheckBox all = TokenChoices.mixed(TokenChoices.check("All", true), true);
                assertTrue(TokenChoices.isMixed(all));
                assertFalse(all.isSelected());
                all.doClick();
                assertTrue(all.isSelected());
                assertFalse(TokenChoices.isMixed(all));

                TokenChoices.Switch autosave = TokenChoices.toggleSwitch("Autosave", false);
                assertTrue(autosave.getIcon() instanceof TokenSwitchIcon);
                assertEquals(AccessibleRole.TOGGLE_BUTTON, autosave.getAccessibleContext().getAccessibleRole());
                autosave.doClick();
                assertTrue(autosave.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.PRESSED));
                // Switching the theme rebuilds the icon with the new colors
                Icon before = autosave.getIcon();
                SwingUtilities.updateComponentTreeUI(autosave);
                assertNotSame(before, autosave.getIcon());

                JRadioButton first = TokenChoices.radio("A", true);
                JRadioButton second = TokenChoices.radio("B", false);
                TokenChoices.group(first, second);
                second.doClick();
                assertFalse(first.isSelected());
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void tabsAndSegmentedControlsFollowTheTokens() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                UIDefaults defaults = lookAndFeel.getDefaults();
                // The underline stays violet when the strip loses focus
                assertColor(defaults, "TabbedPane.underlineColor", "selected.primary", mode);
                assertColor(defaults, "TabbedPane.inactiveUnderlineColor", "selected.primary", mode);
                assertColor(defaults, "TabbedPane.disabledUnderlineColor", "border.subtle", mode);
                assertColor(defaults, "TabbedPane.foreground", "text.secondary", mode);
                assertColor(defaults, "TabbedPane.hoverForeground", "text.primary", mode);
                assertColor(defaults, "TabbedPane.selectedForeground", "text.primary", mode);
                assertColor(defaults, "TabbedPane.disabledForeground", "text.disabled", mode);
                assertEquals(Math.round(tokens.size().get("selection.width")),
                      defaults.getInt("TabbedPane.tabSelectionHeight"));
                assertEquals(TokenButtons.Size.COMPACT.height(), defaults.getInt("TabbedPane.tabHeight"));
                JTabbedPane regular = TokenTabs.tabs(TokenButtons.Size.REGULAR);
                assertEquals(TokenButtons.Size.REGULAR.height(),
                      regular.getClientProperty(FlatClientProperties.TABBED_PANE_TAB_HEIGHT));
                int side = Math.round(tokens.space().get("lg"));
                assertEquals(new Insets(0, side, 0, side),
                      regular.getClientProperty(FlatClientProperties.TABBED_PANE_TAB_INSETS));

                for (TokenButtons.Size size : TokenButtons.Size.values()) {
                    TokenSegmented segmented = new TokenSegmented(size, "Map", "List", "Cards");
                    Dimension preferred = segmented.getPreferredSize();
                    assertEquals(UIUtil.scaleForGUI(size.height()), preferred.height, size.name());
                    segmented.setSize(preferred);
                    segmented.doLayout();
                    assertEquals(segmented.segment(0).getWidth(), segmented.segment(2).getWidth(), "equal widths");
                }

                TokenSegmented segmented = new TokenSegmented(TokenButtons.Size.COMPACT, "Map", "List", "Cards");
                assertEquals(AccessibleRole.RADIO_BUTTON,
                      segmented.segment(1).getAccessibleContext().getAccessibleRole());
                assertTrue(segmented.isFocusTraversalPolicyProvider(), "one Tab stop, the selected section");
                assertEquals(0, segmented.getSelectedIndex());
                List<String> heard = new ArrayList<>();
                segmented.addActionListener(event -> heard.add(event.getActionCommand()));
                segmented.setSelectedIndex(2);
                assertEquals(2, segmented.getSelectedIndex());
                assertTrue(heard.isEmpty(), "setSelectedIndex is silent");
                segmented.segment(0).doClick();
                assertEquals(List.of("Map"), heard);
                segmented.segment(0).doClick();
                assertEquals(1, heard.size(), "clicking the selected section changes nothing");
                assertTrue(segmented.segment(0).isSelected(), "the selection can't be cleared");

                // Left and Right move the selection; there is nothing past either end
                JToggleButton first = segmented.segment(0);
                assertEquals("nextSegment", first.getInputMap(JComponent.WHEN_FOCUSED)
                      .get(KeyStroke.getKeyStroke("RIGHT")));
                press(first, "previousSegment");
                assertEquals(0, segmented.getSelectedIndex());
                press(first, "nextSegment");
                assertEquals(1, segmented.getSelectedIndex());
                assertEquals(List.of("Map", "List"), heard);
                segmented.setEnabled(false);
                assertFalse(segmented.segment(2).isEnabled());
                press(segmented.segment(1), "nextSegment");
                assertEquals(1, segmented.getSelectedIndex(), "a disabled control ignores keys");
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void listsAndTreesFollowTheTokens() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                UIDefaults defaults = lookAndFeel.getDefaults();
                assertColor(defaults, TokenLists.MARKER_COLOR, "selected.primary", mode);
                assertColor(defaults, TokenLists.EMPTY_FOREGROUND, "text.muted", mode);
                int hover = ColorMath.over(tokens.color("surface.hover", mode), tokens.color("surface.input", mode));
                assertEquals(new Color(hover), new Color(defaults.getColor(TokenLists.HOVER_BACKGROUND).getRGB()));
                int rowHeight = UIScale.scale(Math.round(tokens.size().get("control.sm")));

                JList<String> list = new JList<>(new String[] { "Atlas", "Marauder" });
                assertTrue(list.getUI() instanceof TokenListUI);
                assertTrue(list.getCellBounds(0, 0).height >= rowHeight, "rows are at least control.sm high");
                Insets margins = ((JComponent) list.getCellRenderer()
                      .getListCellRendererComponent(list, "Atlas", 0, false, false)).getInsets();
                int markerSpace = Math.round(tokens.space().get("xs") + tokens.size().get("selection.width")
                      + tokens.space().get("sm"));
                assertEquals(UIScale.scale(markerSpace), margins.left, "the text starts past the marker");

                // The select's popup list keeps its own rows
                JList<String> popup = new JList<>(new String[] { "Atlas" });
                popup.setName(TokenLists.POPUP_LIST);
                popup.getPreferredSize();
                Insets popupMargins = ((JComponent) popup.getCellRenderer()
                      .getListCellRendererComponent(popup, "Atlas", 0, false, false)).getInsets();
                assertTrue(popupMargins.left < margins.left, "popup rows have no marker space");

                JList<String> twoLine = TokenLists.twoLine(new JList<>(new String[] { "Atlas AS7-D" }), s -> s,
                      s -> "100 t");
                assertTrue(twoLine.getCellBounds(0, 0).height
                      >= UIScale.scale(Math.round(tokens.size().get("control.lg"))), "two-line rows are control.lg");
                assertEquals("Nothing here", TokenLists.emptyText(new JList<String>(), "Nothing here")
                      .getClientProperty(TokenLists.EMPTY_TEXT));

                JTree tree = new JTree();
                assertTrue(tree.getUI() instanceof TokenTreeUI);
                assertEquals(rowHeight, tree.getRowHeight());
                int indent = Math.round(tokens.space().get("md"));
                assertEquals(UIScale.scale(indent * 2), ((TokenTreeUI) tree.getUI()).getLeftChildIndent()
                      + ((TokenTreeUI) tree.getUI()).getRightChildIndent());
                assertEquals(UIScale.scale(markerSpace), tree.getInsets().left, "chevrons clear the marker");
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void tablesFollowTheListRows() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                UIDefaults defaults = lookAndFeel.getDefaults();

                JTable table = new JTable(new Object[][] { { "Atlas", 100 } }, new Object[] { "Unit", "Tons" });
                assertTrue(table.getUI() instanceof TokenTableUI);
                assertTrue(table.getTableHeader().getUI() instanceof TokenTableHeaderUI);
                assertEquals(UIScale.scale(Math.round(tokens.size().get("control.sm"))), table.getRowHeight());
                assertEquals(defaults.get("List.selectionArc"), defaults.get("Table.selectionArc"));
                assertEquals(defaults.get("List.selectionInsets"), defaults.get("Table.selectionInsets"));
                assertNotNull(defaults.getColor("Table.alternateRowColor"));
                assertFalse(new Color(defaults.getColor("Table.alternateRowColor").getRGB())
                      .equals(new Color(table.getBackground().getRGB())), "zebra rows differ from the others");
                assertColor(defaults, "TableHeader.foreground", "text.secondary", mode);
                assertEquals(TokenFonts.font("overline").getSize(), table.getTableHeader().getFont().getSize());
                assertEquals(Math.round(tokens.space().get("sm")), UIManager.getInt(TokenTables.SORT_ICON_GAP));

                int markerSpace = Math.round(tokens.space().get("xs") + tokens.size().get("selection.width")
                      + tokens.space().get("sm"));
                table.setSize(table.getPreferredSize());
                table.doLayout();
                // Renderers sit in the table's renderer pane while they paint
                CellRendererPane rendererPane = new CellRendererPane();
                table.add(rendererPane);
                Component cell = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
                rendererPane.add(cell);
                cell.setBounds(table.getCellRect(0, 0, false));
                assertEquals(UIScale.scale(markerSpace), ((JComponent) cell).getInsets().left,
                      "the first column clears the marker");
                cell.setBounds(table.getCellRect(0, 1, false));
                assertEquals(UIScale.scale(Math.round(tokens.space().get("xs") + tokens.space().get("sm"))),
                      ((JComponent) cell).getInsets().right, "the last column clears the row's inset");
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void menusShareTheSelectPopup() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                UIDefaults defaults = lookAndFeel.getDefaults();
                // The hover wash, never the selection violet, on every kind of menu row
                Color wash = defaults.getColor("ComboBox.selectionBackground");
                for (String item : List.of("Menu", "MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem")) {
                    assertEquals(new Color(wash.getRGB()),
                          new Color(defaults.getColor(item + ".selectionBackground").getRGB()), item);
                }
                assertColor(defaults, "CheckBoxMenuItem.icon.checkmarkColor", "selected.primary", mode);
                assertColor(defaults, "MenuItem.acceleratorForeground", "text.muted", mode);
                assertEquals(defaults.getInsets("ComboBox.selectionInsets"), defaults.getInsets("MenuItem.selectionInsets"));
                assertEquals(defaults.getInt("ComboBox.selectionArc"), defaults.getInt("MenuItem.selectionArc"));

                JPopupMenu menu = new JPopupMenu();
                JMenuItem item = menu.add(new JMenuItem("Load game"));
                assertEquals(UIScale.scale(Math.round(tokens.size().get("control.sm"))), item.getPreferredSize().height,
                      "menu rows are control.sm high");
                int pad = Math.round(tokens.space().get("xs") + tokens.size().get("border"));
                assertEquals(UIScale.scale(pad), menu.getInsets().top, "the popup is padded like the select's");
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void tooltipsAreWrappedCards() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                UIDefaults defaults = lookAndFeel.getDefaults();
                assertColor(defaults, "ToolTip.background", "surface.popover", mode);
                // The popup's edge, which Windows 11 also takes for the native border
                FlatLineBorder edge = assertInstanceOf(FlatLineBorder.class, defaults.getBorder("ToolTip.border"));
                assertEquals(new Color(tokens.color("border.control", mode)), new Color(edge.getLineColor().getRGB()));

                JLabel owner = new JLabel();
                String text = "Units that moved this turn cannot be deployed again until the next round";
                owner.setToolTipText(text);
                JToolTip tip = owner.createToolTip();
                tip.setTipText(text);
                assertInstanceOf(TokenToolTipUI.class, tip.getUI());
                Dimension wrapped = tip.getPreferredSize();
                Insets insets = tip.getInsets();
                assertTrue(wrapped.width - insets.left - insets.right <= UIUtil.scaleForGUI(280),
                      "long text wraps at the maximum width");
                tip.setTipText("Fire");
                assertTrue(wrapped.height > tip.getPreferredSize().height, "the wrapped text takes more lines");

                TokenTooltips.card(owner, "Medium laser", "Damage 5, heat 3", "F");
                tip.setTipText(owner.getToolTipText());
                Dimension card = tip.getPreferredSize();
                tip.setTipText("Medium laser");
                assertTrue(card.height > tip.getPreferredSize().height, "a card shows its detail under the title");

                tip.setTipText("<html>Atlas<br>AS7-D</html>");
                assertTrue(tip.getPreferredSize().width > 0, "HTML tooltips keep FlatLaf's layout");
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> TokenTooltips.card(new JLabel(), " ", null, "F"));
    }

    @Test
    void tooltipTextWrapsAtSpacesAndBreaksLongWords() {
        JLabel label = new JLabel();
        FontMetrics metrics = label.getFontMetrics(TokenFonts.font("small"));
        int width = metrics.stringWidth("Units that moved");
        List<String> lines = TokenToolTipUI.wrap("Units that moved this turn\nstay", metrics, width);
        assertEquals(List.of("Units that moved", "this turn", "stay"), lines);
        for (String line : TokenToolTipUI.wrap("Supercalifragilistic", metrics, metrics.stringWidth("Super"))) {
            assertTrue(metrics.stringWidth(line) <= metrics.stringWidth("Super"), line);
        }
    }

    @Test
    void scrollBarsSitOnTheContentTheyScroll() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                UIDefaults defaults = lookAndFeel.getDefaults();
                assertColor(defaults, "ScrollBar.thumb", "border.control", mode);
                assertColor(defaults, "ScrollBar.hoverThumbColor", "text.muted", mode);
                assertColor(defaults, "ScrollBar.pressedThumbColor", "text.secondary", mode);
                assertColor(defaults, "ScrollBar.hoverTrackColor", "surface.hover", mode);
                assertFalse(defaults.getBoolean("ScrollBar.showButtons"), "no arrow buttons");
                // The thumb stays visible on every surface a scrolled view uses
                int thumb = tokens.color("border.control", mode);
                for (String surface : List.of("surface.base", "surface.panel", "surface.input", "surface.popover")) {
                    double contrast = ColorMath.contrast(thumb, tokens.color(surface, mode));
                    assertTrue(contrast >= 3, mode + " thumb on " + surface + " " + contrast);
                }

                JList<String> list = new JList<>(new String[] { "Atlas AS7-D" });
                JScrollPane scroll = new JScrollPane(list);
                assertInstanceOf(TokenScrollPaneUI.class, scroll.getUI());
                JScrollBar bar = scroll.getVerticalScrollBar();
                assertInstanceOf(TokenScrollBarUI.class, bar.getUI());
                assertFalse(bar.isOpaque(), "the scroll pane paints behind the bar");
                assertEquals(list.getBackground(), TokenScrollBarUI.contentBackground(scroll));
                assertEquals(list.getBackground(), TokenScrollBarUI.contentBackground(bar));
                int width = Math.round(tokens.space().get("sm") + 2 * tokens.space().get("xxs"));
                assertEquals(UIScale.scale(width), bar.getPreferredSize().width);
                list.setOpaque(false);
                assertEquals(scroll.getViewport().getBackground(), TokenScrollBarUI.contentBackground(scroll));
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void slidersKeepTheRoundThumbAndShowTheirValue() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                UIDefaults defaults = lookAndFeel.getDefaults();
                assertColor(defaults, "Slider.trackColor", "border.control", mode);
                assertColor(defaults, "Slider.trackValueColor", "accent.primary", mode);
                assertColor(defaults, "Slider.thumbColor", "surface.inverse", mode);
                assertColor(defaults, "Slider.barThumbColor", "surface.inverse", mode);
                assertColor(defaults, "Slider.hoverThumbBorderColor", "border.strong", mode);
                assertColor(defaults, "Slider.tickColor", "border.control", mode);
                assertColor(defaults, "Slider.disabledTickColor", "border.subtle", mode);
                assertColor(defaults, "Slider.foreground", "text.muted", mode);
                // The thumb and the value fill stand out from the window and the panels
                int accent = tokens.color("accent.primary", mode);
                for (String surface : List.of("surface.base", "surface.panel", "surface.raised")) {
                    double contrast = ColorMath.contrast(accent, tokens.color(surface, mode));
                    assertTrue(contrast >= 3, mode + " thumb on " + surface + " " + contrast);
                }

                JSlider ticked = new JSlider(0, 10, 6);
                ticked.setMajorTickSpacing(5);
                ticked.setMinorTickSpacing(1);
                ticked.setPaintTicks(true);
                ticked.setPaintLabels(true);
                TokenSliderUI ui = assertInstanceOf(TokenSliderUI.class, ticked.getUI());
                assertTrue(ui.isRoundThumb(), "ticks and labels keep the round thumb");
                assertEquals(UIScale.scale(Math.round(tokens.space().get("sm"))), ui.getTickLength());
                assertEquals(TokenFonts.font("small").getSize2D(), ticked.getFont().getSize2D(), 0.01);

                JSlider slider = new JSlider(0, 255, 5);
                JComponent row = TokenSliders.withReadout(slider, value -> value + "%");
                JLabel readout = (JLabel) row.getComponent(1);
                assertEquals("5%", readout.getText());
                int width = readout.getPreferredSize().width;
                slider.setValue(255);
                assertEquals("255%", readout.getText());
                assertEquals(width, readout.getPreferredSize().width, "the readout keeps its width");
                Dimension size = row.getPreferredSize();
                assertTrue(row.getBaseline(size.width, size.height) > 0, "the row has the slider's baseline");
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void progressBarsPutTheirTextAboveAThinTrack() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                UIDefaults defaults = lookAndFeel.getDefaults();
                assertColor(defaults, "ProgressBar.background", "border.control", mode);
                assertColor(defaults, TokenProgressBarUI.TEXT_FOREGROUND, "text.secondary", mode);
                assertColor(defaults, TokenProgressBarUI.DISABLED_TRACK, "border.subtle", mode);
                assertColor(defaults, TokenProgressBarUI.VALUE_FOREGROUND, "text.primary", mode);

                JProgressBar plain = new JProgressBar(0, 100);
                assertInstanceOf(TokenProgressBarUI.class, plain.getUI());
                assertEquals(TokenFonts.font("small").getSize2D(), plain.getFont().getSize2D(), 0.01);
                int track = UIScale.scale(Math.round(tokens.space().get("xs")));
                assertEquals(track, plain.getPreferredSize().height);

                JProgressBar texted = new JProgressBar(0, 100);
                texted.setString("Round 3: waiting for the other players to finish deploying their units");
                texted.setStringPainted(true);
                FontMetrics metrics = texted.getFontMetrics(texted.getFont());
                Dimension size = texted.getPreferredSize();
                assertEquals(metrics.getHeight() + UIScale.scale(Math.round(tokens.space().get("xs"))) + track,
                      size.height, "text above the track");
                assertTrue(size.width >= metrics.stringWidth(texted.getString()), "the text fits");
                assertEquals(size.height, texted.getMinimumSize().height);

                texted.setIndeterminate(true);
                texted.setSize(size);
                BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                try {
                    texted.paint(g);
                } finally {
                    g.dispose();
                }

                JProgressBar percent = new JProgressBar(0, 1000);
                percent.setValue(259);
                TokenProgressBarUI ui = (TokenProgressBarUI) percent.getUI();
                assertNull(ui.percentText(), "off unless asked for");
                int withoutPercent = percent.getPreferredSize().height;
                percent.putClientProperty(TokenProgressBarUI.SHOW_PERCENT, Boolean.TRUE);
                assertEquals("25%", ui.percentText(), "rounds down, so 100% means done");
                assertEquals(size.height, percent.getPreferredSize().height, "a text line even without text");
                assertTrue(percent.getPreferredSize().height > withoutPercent);
                percent.setIndeterminate(true);
                assertNull(ui.percentText(), "no percentage while the length is unknown");
                assertEquals(size.height, percent.getPreferredSize().height, "the line keeps its height");
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void sectionsShareOneHeader() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                UIDefaults defaults = lookAndFeel.getDefaults();
                assertColor(defaults, TokenSections.TITLE_FOREGROUND, "text.secondary", mode);
                assertColor(defaults, TokenSections.LINE_COLOR, "border.subtle", mode);
                assertColor(defaults, "TitledBorder.titleColor", "text.secondary", mode);
                int height = UIScale.scale(Math.round(tokens.size().get("control.sm")));

                JLabel header = TokenSections.header("Movement");
                assertEquals(height, header.getPreferredSize().height);
                assertEquals("Movement", header.getAccessibleContext().getAccessibleName(),
                      "screen readers get the title as written");

                JPanel titled = new JPanel();
                titled.setBorder(TokenSections.titled("Deployment"));
                assertEquals(new Insets(height, 0, 0, 0), titled.getInsets(), "content flush with the title");

                TitledBorder legacy = (TitledBorder) BorderFactory.createTitledBorder("Deployment");
                assertInstanceOf(TokenTitledBorder.LegacyLine.class, legacy.getBorder());
                assertEquals(TokenFonts.font("label").getSize2D(), legacy.getTitleFont().getSize2D(), 0.01);

                JLabel content = new JLabel("Zone");
                TokenCollapsibleSection section = TokenSections.collapsible("Advanced rules", content, false);
                assertFalse(content.isVisible());
                List<Object> changes = new ArrayList<>();
                section.addPropertyChangeListener(TokenCollapsibleSection.EXPANDED,
                      event -> changes.add(event.getNewValue()));
                JComponent row = section.getHeader();
                assertTrue(row.isFocusable());
                assertEquals(height, row.getPreferredSize().height);
                assertEquals(AccessibleRole.TOGGLE_BUTTON, row.getAccessibleContext().getAccessibleRole());
                assertEquals("Advanced rules", row.getAccessibleContext().getAccessibleName());
                assertTrue(row.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.COLLAPSED));
                press(row, "toggle");
                assertTrue(section.isExpanded());
                assertTrue(content.isVisible());
                assertEquals(List.of(true), changes);
                assertTrue(row.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.EXPANDED));
                section.setEnabled(false);
                press(row, "toggle");
                assertTrue(section.isExpanded(), "a disabled section stays as it is");
                assertFalse(row.isEnabled());
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void splitPaneDividersAreLines() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                UIDefaults defaults = lookAndFeel.getDefaults();
                assertColor(defaults, TokenSplitPaneUI.LINE_COLOR, "border.subtle", mode);
                assertColor(defaults, TokenSplitPaneUI.ACTIVE_COLOR, "accent.primary", mode);
                JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JLabel("A"), new JLabel("B"));
                TokenSplitPaneUI ui = assertInstanceOf(TokenSplitPaneUI.class, split.getUI());
                assertInstanceOf(TokenSplitPaneUI.LineDivider.class, ui.getDivider());
                assertEquals(UIScale.scale(Math.round(tokens.space().get("md"))), split.getDividerSize());
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void optionPanesCentreTheirButtonsWithTokenIcons() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                UIDefaults defaults = UIManager.getDefaults();
                assertEquals(0, defaults.getInt("OptionPane.buttonOrientation"));
                assertFalse(defaults.getBoolean("OptionPane.isYesLast"));
                assertTrue(defaults.getBoolean("OptionPane.sameSizeButtons"));
                List<TokenDialogPane.Status> statuses = List.of(TokenDialogPane.Status.ERROR,
                      TokenDialogPane.Status.WARNING, TokenDialogPane.Status.INFO, TokenDialogPane.Status.QUESTION);
                List<String> keys = List.of("OptionPane.errorIcon", "OptionPane.warningIcon",
                      "OptionPane.informationIcon", "OptionPane.questionIcon");
                for (int i = 0; i < keys.size(); i++) {
                    Icon icon = assertInstanceOf(Icon.class, defaults.get(keys.get(i)), keys.get(i));
                    assertEquals(statuses.get(i).icon().getIconWidth(), icon.getIconWidth(), keys.get(i));
                }
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    private static void press(JComponent component, String action) {
        component.getActionMap().get(action).actionPerformed(new ActionEvent(component,
              ActionEvent.ACTION_PERFORMED, action));
    }

    private void assertUsesTokens(FlatLaf lookAndFeel, Mode mode) {
        UIDefaults defaults = lookAndFeel.getDefaults();
        assertColor(defaults, "Panel.background", "surface.base", mode);
        assertColor(defaults, "Label.foreground", "text.primary", mode);
        assertColor(defaults, "TextField.background", "surface.input", mode);
        assertColor(defaults, "Component.borderColor", "border.control", mode);
        assertColor(defaults, "Component.focusColor", "focus.ring", mode);
        assertColor(defaults, "Button.default.background", "surface.inverse", mode);
        assertColor(defaults, "Button.default.foreground", "text.onInverse", mode);
        assertColor(defaults, "ProgressBar.foreground", "accent.primary", mode);
        assertColor(defaults, "Component.error.borderColor", "status.danger", mode);
        // Focus wins: one ring for every state, while a focused checked box keeps its checked look
        assertColor(defaults, "Component.error.focusedBorderColor", "focus.ring", mode);
        assertColor(defaults, "Component.warning.focusedBorderColor", "focus.ring", mode);
        assertColor(defaults, "CheckBox.icon.focusColor", "focus.ring", mode);
        assertColor(defaults, "CheckBox.icon.focusedSelectedBackground", "surface.inverse", mode);
        // On is cream: a checked box and a selected toggle take the inverse face with the inverse label
        assertColor(defaults, "CheckBox.icon.selectedBackground", "surface.inverse", mode);
        assertColor(defaults, "CheckBox.icon.checkmarkColor", "text.onInverse", mode);
        assertColor(defaults, TokenCheckBoxIcon.DISABLED_SELECTED_CHECKMARK_COLOR, "text.onInverse", mode);
        assertColor(defaults, "ToggleButton.selectedBackground", "surface.inverse", mode);
        assertColor(defaults, "ToggleButton.selectedForeground", "text.onInverse", mode);
        assertColor(defaults, "ToggleButton.disabledSelectedForeground", "text.onInverse", mode);
        assertColor(defaults, "ToggleButton.toolbar.selectedBackground", "surface.inverse", mode);
        assertColor(defaults, TokenButtonUI.INVERSE_FOCUS_COLOR, "text.onInverse", mode);
        int faint = ColorMath.over(tokens.color("surface.inverseDisabled", mode), tokens.color("surface.raised", mode));
        assertEquals(new Color(faint), new Color(defaults.getColor("ToggleButton.disabledSelectedBackground").getRGB()));
        assertEquals(255, defaults.getColor("CheckBox.icon.disabledSelectedBackground").getAlpha(),
              "FlatLaf fills a translucent disabled fill over the full selected fill");
        assertColor(defaults, "CheckBox.icon.focusedBackground", "surface.input", mode);
        assertColor(defaults, "Slider.focusedColor", "focus.ring", mode);
        assertTrue(defaults.getIcon("CheckBox.icon") instanceof TokenCheckBoxIcon, "CheckBox.icon");
        assertTrue(defaults.getIcon("RadioButton.icon") instanceof TokenRadioButtonIcon, "RadioButton.icon");
        assertEquals(TokenTabbedPaneUI.class.getName(), defaults.getString("TabbedPaneUI"));
        // Buttons ease between their states
        assertEquals(TokenButtonUI.class.getName(), defaults.getString("ButtonUI"));
        assertEquals(TokenButtonUI.Toggle.class.getName(), defaults.getString("ToggleButtonUI"));
        // Inputs: control.sm height by default, a strong edge on hover, no fill and a subtle edge when read-only
        assertEquals(Math.round(tokens.size().get("control.sm")), defaults.getInt(FieldEdge.HEIGHT));
        assertColor(defaults, FieldEdge.HOVER_COLOR, "border.strong", mode);
        assertColor(defaults, FieldEdge.READ_ONLY_COLOR, "border.subtle", mode);
        assertColor(defaults, "TextField.inactiveBackground", "surface.base", mode);
        assertColor(defaults, "TextArea.inactiveBackground", "surface.base", mode);
        assertColor(defaults, "TextField.placeholderForeground", "text.muted", mode);
        assertColor(defaults, "PasswordField.placeholderForeground", "text.muted", mode);
        assertColor(defaults, TokenFormField.LABEL_COLOR, "text.secondary", mode);
        assertColor(defaults, TokenFormField.HELPER_COLOR, "text.muted", mode);
        assertEquals(FieldUIs.TextField.class.getName(), defaults.getString("TextFieldUI"));
        assertEquals(FieldUIs.Spinner.class.getName(), defaults.getString("SpinnerUI"));
        assertEquals(FieldUIs.ComboBox.class.getName(), defaults.getString("ComboBoxUI"));
        int selection = ColorMath.over(tokens.color("selected.background", mode), tokens.color("surface.input", mode));
        assertEquals(new Color(selection), new Color(defaults.getColor("Table.selectionBackground").getRGB()));
        assertNotNull(defaults.get("[style]Button.primary"), "primary button style class");
        assertColor(defaults, "Button.selectedBorderColor", "surface.inverse", mode);
        assertColor(defaults, "Button.foreground", "text.secondary", mode);
        assertColor(defaults, "Button.hoverForeground", "text.primary", mode);
        assertColor(defaults, "Button.default.focusedBackground", "surface.inverse", mode);
        int label = tokens.color("text.onInverse", mode);
        for (String key : List.of("Button.default.hoverBackground", "Button.default.pressedBackground")) {
            double contrast = ColorMath.contrast(label, defaults.getColor(key).getRGB());
            assertTrue(contrast >= 4.5, key + " label contrast " + contrast);
        }
    }

    private void assertColor(UIDefaults defaults, String key, String role, Mode mode) {
        Color actual = defaults.getColor(key);
        assertNotNull(actual, key);
        assertEquals(new Color(tokens.color(role, mode), true), new Color(actual.getRGB(), true), key);
    }
}
