/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static megamek.client.ui.util.UIUtil.scaleForGUI;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayer;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;
import javax.swing.text.PlainDocument;

import com.formdev.flatlaf.FlatClientProperties;
import megamek.client.ui.Messages;
import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.ColorToken;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.TypeStyle;
import megamek.client.ui.design.TokenChecks;
import megamek.client.ui.design.Vision;
import megamek.client.ui.util.FontHandler;
import megamek.client.ui.util.UIUtil;
import megamek.logging.MMLogger;

/**
 * The developer gallery of the Swing side of the design system, opened from Help in the lobby: every color role in
 * both modes, the accessibility checks, the text styles, spacing and shape, all painted from {@link DesignTokens}
 * rather than from the active look and feel, and viewable through a color vision deficiency simulation. The Controls tab
 * shows real Swing controls drawn by {@link MegaMekDarkLaf} or {@link MegaMekLightLaf}, which the gallery applies to
 * the app while it is open. The Swing control library grows here, control by control.
 */
public final class SwingUiLibrary extends JFrame {
    private static SwingUiLibrary open;
    private static final MMLogger LOGGER = MMLogger.create(SwingUiLibrary.class);

    private static final int ICON_ERROR = 0xE000;
    private static final int ICON_WARNING = 0xE002;
    private static final int ICON_CHECK_CIRCLE = 0xE86C;
    private static final int ICON_INFO = 0xE88E;
    private static final int ICON_RADIO_CHECKED = 0xE837;

    private final DesignTokens tokens = DesignTokens.shipped();
    private final VisionLayerUI visionLayer = new VisionLayerUI();
    private final JPanel content = new JPanel(new BorderLayout());
    private final JLayer<JComponent> layer = new JLayer<>(content, visionLayer);
    private final Map<String, Font> fonts = new HashMap<>();
    private Mode mode = Mode.DARK;
    private int selectedTab;
    private final String previousLookAndFeel;

    /** Shows the gallery, or brings the open one to the front. */
    public static void showLibrary(Component owner) {
        if (open == null || !open.isDisplayable()) {
            open = new SwingUiLibrary();
            open.setLocationRelativeTo(owner);
        }
        open.setVisible(true);
        open.toFront();
    }

    private SwingUiLibrary() {
        super(Messages.getString("UiLibrary.swing.title"));
        FontHandler.initialize();
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        previousLookAndFeel = UIManager.getLookAndFeel() == null ? null
              : UIManager.getLookAndFeel().getClass().getName();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                restoreLookAndFeel();
            }
        });

        JToggleButton light = new JToggleButton(Messages.getString("UiLibrary.mode.light"));
        light.addActionListener(event -> {
            mode = light.isSelected() ? Mode.LIGHT : Mode.DARK;
            applyLookAndFeel();
        });
        JComboBox<Vision> vision = new JComboBox<>(Vision.values());
        vision.setRenderer((list, value, index, selected, focused) -> new JLabel(
              Messages.getString("UiLibrary.vision." + value.name())));
        vision.addActionListener(event -> visionLayer.setVision((Vision) vision.getSelectedItem(), layer));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEADING, scaleForGUI(8), scaleForGUI(6)));
        toolbar.add(light);
        toolbar.add(new JLabel(Messages.getString("UiLibrary.vision")));
        toolbar.add(vision);
        toolbar.add(new JLabel(Messages.getString("UiLibrary.version", tokens.version())));

        getContentPane().add(toolbar, BorderLayout.NORTH);
        getContentPane().add(layer, BorderLayout.CENTER);
        applyLookAndFeel();
        setSize(scaleForGUI(1100), scaleForGUI(820));
    }

    /**
     * The controls are real Swing components, so they need the MegaMek look and feel of the current mode. A look and
     * feel is global: the whole app uses it while the gallery is open, and closing the gallery puts the previous one
     * back.
     */
    private void applyLookAndFeel() {
        try {
            UIManager.setLookAndFeel(mode == Mode.DARK ? new MegaMekDarkLaf() : new MegaMekLightLaf());
        } catch (UnsupportedLookAndFeelException ex) {
            LOGGER.error("Could not apply the MegaMek look and feel", ex);
        }
        rebuild();
        UIUtil.updateAfterUiChange();
    }

    private void restoreLookAndFeel() {
        if (previousLookAndFeel == null || previousLookAndFeel.equals(UIManager.getLookAndFeel().getClass().getName())) {
            return;
        }
        try {
            UIManager.setLookAndFeel(previousLookAndFeel);
            UIUtil.updateAfterUiChange();
        } catch (ReflectiveOperationException | UnsupportedLookAndFeelException ex) {
            LOGGER.error("Could not restore the look and feel {}", previousLookAndFeel, ex);
        }
    }

    private void rebuild() {
        if (content.getComponentCount() > 0 && content.getComponent(0) instanceof JTabbedPane tabs) {
            selectedTab = tabs.getSelectedIndex();
        }
        content.removeAll();
        fonts.clear();
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(Messages.getString("UiLibrary.tab.colors"), page(colorsPage()));
        tabs.addTab(Messages.getString("UiLibrary.tab.checks"), page(checksPage()));
        tabs.addTab(Messages.getString("UiLibrary.tab.type"), page(typePage()));
        tabs.addTab(Messages.getString("UiLibrary.tab.shape"), page(shapePage()));
        tabs.addTab(Messages.getString("UiLibrary.tab.controls"), page(controlsPage()));
        tabs.setSelectedIndex(Math.min(selectedTab, tabs.getTabCount() - 1));
        content.add(tabs, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
    }

    // ---- Pages

    private JComponent colorsPage() {
        JPanel page = column(color("surface.base"));
        page.add(heading(Messages.getString("UiLibrary.colors.inUse")));
        page.add(statusStrip());
        page.add(Box.createVerticalStrut(scaleForGUI(4)));
        page.add(sampleRows());
        String group = "";
        for (ColorToken token : tokens.colors().values()) {
            String tokenGroup = token.name().substring(0, token.name().indexOf('.'));
            if (!tokenGroup.equals(group)) {
                group = tokenGroup;
                page.add(heading(group));
            }
            page.add(colorRow(token));
        }
        return page;
    }

    private JComponent checksPage() {
        JPanel page = column(color("surface.base"));
        List<TokenChecks.Result> results = TokenChecks.run(tokens);
        long passing = results.stream().filter(TokenChecks.Result::passes).count();
        page.add(heading(Messages.getString("UiLibrary.checks.summary", passing, results.size())));
        page.add(paragraph(Messages.getString("UiLibrary.checks.explain"), "small", "text.muted"));
        for (Mode checkMode : Mode.values()) {
            page.add(heading(Messages.getString("UiLibrary.mode." + checkMode.name())));
            for (TokenChecks.Result result : results) {
                if (result.mode() == checkMode) {
                    page.add(checkRow(result));
                }
            }
        }
        return page;
    }

    private JComponent typePage() {
        JPanel page = column(color("surface.base"));
        page.add(heading(Messages.getString("UiLibrary.type.rule")));
        page.add(paragraph(tokens.caseRule(), "body", "text.secondary"));
        page.add(paragraph(Messages.getString("UiLibrary.type.weightNote"), "small", "text.muted"));
        for (TypeStyle style : tokens.typeStyles().values()) {
            JPanel row = row(color("surface.panel"));
            row.setLayout(new BorderLayout(scaleForGUI(16), 0));
            JLabel spec = text(String.format(Locale.ROOT, "%s  %s / %d%s", style.name(), trim(style.size()),
                  style.weight(), style.upperCase() ? " / upper" : ""), "caption", "text.muted");
            spec.setPreferredSize(new Dimension(scaleForGUI(200), spec.getPreferredSize().height));
            row.add(spec, BorderLayout.WEST);
            String sample = Messages.getString("UiLibrary.type.sample");
            row.add(text(style.upperCase() ? sample.toUpperCase(Locale.getDefault()) : sample, style.name(),
                  "text.primary"), BorderLayout.CENTER);
            page.add(row);
        }
        return page;
    }

    private JComponent shapePage() {
        JPanel page = column(color("surface.base"));
        page.add(heading(Messages.getString("UiLibrary.shape.space")));
        for (Map.Entry<String, Float> entry : tokens.space().entrySet()) {
            page.add(bar(entry.getKey(), entry.getValue()));
        }
        page.add(heading(Messages.getString("UiLibrary.shape.controls")));
        JPanel controls = row(color("surface.base"));
        for (String size : List.of("control.sm", "control.md", "control.lg")) {
            controls.add(new Swatch(size, scaleForGUI(110), Math.round(scaleForGUI(tokens.size().get(size))),
                  Swatch.Kind.CONTROL));
        }
        controls.add(new Swatch(Messages.getString("UiLibrary.shape.focus"), scaleForGUI(140),
              Math.round(scaleForGUI(tokens.size().get("control.md"))), Swatch.Kind.FOCUS));
        controls.add(new Swatch(Messages.getString("UiLibrary.shape.ticks"), scaleForGUI(140),
              Math.round(scaleForGUI(tokens.size().get("control.md"))), Swatch.Kind.TICKS));
        controls.add(new Swatch(Messages.getString("UiLibrary.shape.frame"), scaleForGUI(140),
              Math.round(scaleForGUI(tokens.size().get("control.md"))), Swatch.Kind.FRAME));
        page.add(controls);
        page.add(heading(Messages.getString("UiLibrary.shape.motion")));
        for (Map.Entry<String, Float> entry : tokens.motion().entrySet()) {
            page.add(text(entry.getKey() + "  " + trim(entry.getValue()) + " ms", "body", "text.secondary"));
        }
        return page;
    }

    // ---- Samples

    private JComponent controlsPage() {
        JPanel page = column(color("surface.base"));
        page.add(paragraph(Messages.getString("UiLibrary.controls.intro",
              mode == Mode.DARK ? MegaMekDarkLaf.NAME : MegaMekLightLaf.NAME), "small", "text.muted"));

        page.add(heading(Messages.getString("UiLibrary.controls.buttons")));
        JPanel buttons = row(color("surface.base"));
        JButton primary = new JButton(Messages.getString("UiLibrary.controls.primary"));
        primary.putClientProperty(FlatClientProperties.STYLE_CLASS, "primary");
        primary.setToolTipText(Messages.getString("UiLibrary.controls.tooltip"));
        buttons.add(primary);
        JButton secondary = new JButton(Messages.getString("UiLibrary.controls.secondary"));
        secondary.setToolTipText(Messages.getString("UiLibrary.controls.tooltip"));
        buttons.add(secondary);
        JToggleButton toggle = new JToggleButton(Messages.getString("UiLibrary.controls.toggle"), true);
        buttons.add(toggle);
        buttons.add(new JToggleButton(Messages.getString("UiLibrary.controls.toggle")));
        JButton disabledPrimary = new JButton(Messages.getString("UiLibrary.controls.primary"));
        disabledPrimary.putClientProperty(FlatClientProperties.STYLE_CLASS, "primary");
        disabledPrimary.setEnabled(false);
        buttons.add(disabledPrimary);
        JButton disabled = new JButton(Messages.getString("UiLibrary.controls.disabled"));
        disabled.setEnabled(false);
        buttons.add(disabled);
        page.add(buttons);

        page.add(heading(Messages.getString("UiLibrary.controls.choices")));
        JPanel choices = row(color("surface.base"));
        choices.add(new JCheckBox(Messages.getString("UiLibrary.controls.checked"), true));
        choices.add(new JCheckBox(Messages.getString("UiLibrary.controls.unchecked")));
        JCheckBox disabledCheck = new JCheckBox(Messages.getString("UiLibrary.controls.disabled"), true);
        disabledCheck.setEnabled(false);
        choices.add(disabledCheck);
        ButtonGroup group = new ButtonGroup();
        for (String key : List.of("UiLibrary.controls.optionA", "UiLibrary.controls.optionB")) {
            JRadioButton radio = new JRadioButton(Messages.getString(key), group.getButtonCount() == 0);
            group.add(radio);
            choices.add(radio);
        }
        choices.add(new JComboBox<>(new String[] { Messages.getString("UiLibrary.controls.optionA"),
              Messages.getString("UiLibrary.controls.optionB") }));
        choices.add(new JSpinner(new SpinnerNumberModel(4, 0, 12, 1)));
        page.add(choices);

        page.add(heading(Messages.getString("UiLibrary.controls.text")));
        JPanel fields = row(color("surface.base"));
        fields.add(field(Messages.getString("UiLibrary.controls.value"), null, true));
        JTextField placeholder = field("", null, true);
        placeholder.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
              Messages.getString("UiLibrary.controls.placeholder"));
        fields.add(placeholder);
        fields.add(field(Messages.getString("UiLibrary.controls.error"), FlatClientProperties.OUTLINE_ERROR, true));
        fields.add(field(Messages.getString("UiLibrary.controls.warning"), FlatClientProperties.OUTLINE_WARNING,
              true));
        fields.add(field(Messages.getString("UiLibrary.controls.disabled"), null, false));
        page.add(fields);

        page.add(heading(Messages.getString("UiLibrary.controls.ranges")));
        JPanel ranges = row(color("surface.base"));
        ranges.add(new JSlider(0, 100, 60));
        JProgressBar progress = new JProgressBar(0, 100);
        progress.setValue(60);
        progress.setStringPainted(true);
        ranges.add(progress);
        page.add(ranges);

        page.add(heading(Messages.getString("UiLibrary.controls.tabs")));
        JTabbedPane sampleTabs = new JTabbedPane();
        for (String key : List.of("UiLibrary.controls.tabA", "UiLibrary.controls.tabB", "UiLibrary.controls.tabC")) {
            JPanel tabBody = new JPanel(new BorderLayout());
            tabBody.add(new JLabel(Messages.getString(key)), BorderLayout.CENTER);
            tabBody.setBorder(BorderFactory.createEmptyBorder(scaleForGUI(8), scaleForGUI(8), scaleForGUI(8),
                  scaleForGUI(8)));
            sampleTabs.addTab(Messages.getString(key), tabBody);
        }
        sampleTabs.setAlignmentX(LEFT_ALIGNMENT);
        page.add(sampleTabs);

        page.add(heading(Messages.getString("UiLibrary.controls.table")));
        JTable table = new JTable(new Object[][] {
              { "Atlas AS7-D", "100", "3/5/0", "54" },
              { "Marauder MAD-3R", "75", "4/6/0", "42" },
              { "Phoenix Hawk PXH-1", "45", "6/9/6", "38" },
              { "Locust LCT-1V", "20", "8/12/0", "21" },
        }, new Object[] { Messages.getString("UiLibrary.controls.unit"),
              Messages.getString("UiLibrary.controls.tons"), Messages.getString("UiLibrary.controls.movement"),
              Messages.getString("UiLibrary.controls.armor") });
        table.setRowSelectionInterval(1, 1);
        table.setPreferredScrollableViewportSize(new Dimension(scaleForGUI(520), table.getRowHeight() * 4));
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setAlignmentX(LEFT_ALIGNMENT);
        page.add(tableScroll);
        return page;
    }

    private JTextField field(String value, String outline, boolean enabled) {
        JTextField field = new JTextField(value, 14);
        field.putClientProperty(FlatClientProperties.OUTLINE, outline);
        field.setEnabled(enabled);
        return field;
    }
    private JComponent statusStrip() {
        JPanel strip = row(color("surface.panel"));
        strip.add(chip(ICON_ERROR, "status.danger", "UiLibrary.status.danger"));
        strip.add(chip(ICON_WARNING, "status.warning", "UiLibrary.status.warning"));
        strip.add(chip(ICON_CHECK_CIRCLE, "status.success", "UiLibrary.status.success"));
        strip.add(chip(ICON_INFO, "status.info", "UiLibrary.status.info"));
        strip.add(chip(ICON_RADIO_CHECKED, "selected.primary", "UiLibrary.status.selected"));
        JLabel accent = text(Messages.getString("UiLibrary.status.accent"), "label", "text.onAccent");
        accent.setOpaque(true);
        accent.setBackground(color("accent.primary"));
        accent.setBorder(BorderFactory.createEmptyBorder(scaleForGUI(6), scaleForGUI(12), scaleForGUI(6),
              scaleForGUI(12)));
        strip.add(accent);
        JLabel inverse = text(Messages.getString("UiLibrary.status.inverse"), "label", "text.onInverse");
        inverse.setOpaque(true);
        inverse.setBackground(color("surface.inverse"));
        inverse.setBorder(accent.getBorder());
        strip.add(inverse);
        return strip;
    }

    private JComponent chip(int codePoint, String role, String key) {
        JLabel label = text(Messages.getString(key), "label", "text.primary");
        Icon icon = FontHandler.symbolIcon(codePoint, scaleForGUI(18), color(role));
        label.setIcon(icon);
        label.setIconTextGap(scaleForGUI(6));
        label.setBorder(BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(color("border.subtle")),
              BorderFactory.createEmptyBorder(scaleForGUI(4), scaleForGUI(8), scaleForGUI(4), scaleForGUI(10))));
        return label;
    }

    private JComponent sampleRows() {
        JPanel list = column(color("surface.panel"));
        list.setBorder(BorderFactory.createLineBorder(color("border.subtle")));
        list.add(sampleRow("UiLibrary.sample.row", false));
        list.add(sampleRow("UiLibrary.sample.selectedRow", true));
        list.add(sampleRow("UiLibrary.sample.row", false));
        return list;
    }

    private JComponent sampleRow(String key, boolean selected) {
        int panel = tokens.color("surface.panel", mode);
        JPanel row = new JPanel(new BorderLayout(scaleForGUI(8), 0));
        row.setBackground(selected
              ? new Color(ColorMath.over(tokens.color("selected.background", mode), panel)) : new Color(panel));
        row.setBorder(BorderFactory.createCompoundBorder(
              BorderFactory.createMatteBorder(0, scaleForGUI(3), 0, 0,
                    selected ? color("selected.primary") : new Color(panel)),
              BorderFactory.createEmptyBorder(scaleForGUI(6), scaleForGUI(10), scaleForGUI(6), scaleForGUI(10))));
        row.add(text(Messages.getString(key), "body", "text.primary"), BorderLayout.CENTER);
        row.add(text(Messages.getString("UiLibrary.sample.detail"), "small", "text.muted"), BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    private JComponent colorRow(ColorToken token) {
        JPanel row = row(color("surface.panel"));
        row.setLayout(new BorderLayout(scaleForGUI(12), 0));
        row.add(new Swatch(token.name(), scaleForGUI(56), scaleForGUI(28), Swatch.Kind.COLOR), BorderLayout.WEST);
        JPanel labels = column(color("surface.panel"));
        labels.add(text(token.name(), "body.strong", "text.primary"));
        labels.add(text(token.use(), "small", "text.secondary"));
        row.add(labels, BorderLayout.CENTER);
        String values = Messages.getString("UiLibrary.colors.values", ColorMath.hex(token.dark()),
              ColorMath.hex(token.light()), token.overlay() == null ? "-" : ColorMath.hex(token.overlay()));
        int effective = ColorMath.alpha(token.value(mode)) == 0xFF ? token.value(mode)
              : ColorMath.over(token.value(mode), tokens.color("surface.panel", mode));
        String onPanel = String.format(Locale.ROOT, "%.2f:1",
              ColorMath.contrast(effective, tokens.color("surface.panel", mode)));
        JPanel right = column(color("surface.panel"));
        right.add(text(values, "small", "text.muted"));
        right.add(text(Messages.getString("UiLibrary.colors.onPanel", onPanel), "small", "text.muted"));
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private JComponent checkRow(TokenChecks.Result result) {
        JPanel row = row(color("surface.panel"));
        row.setLayout(new BorderLayout(scaleForGUI(12), 0));
        JLabel status = text(Messages.getString(result.passes() ? "UiLibrary.checks.pass" : "UiLibrary.checks.fail"),
              "label", "text.primary");
        status.setIcon(FontHandler.symbolIcon(result.passes() ? ICON_CHECK_CIRCLE : ICON_ERROR, scaleForGUI(16),
              color(result.passes() ? "status.success" : "status.danger")));
        status.setPreferredSize(new Dimension(scaleForGUI(80), status.getPreferredSize().height));
        row.add(status, BorderLayout.WEST);
        row.add(text(result.subject(), "body", "text.primary"), BorderLayout.CENTER);
        String measure = result.vision() == null
              ? String.format(Locale.ROOT, "%.2f:1  (min %.1f)", result.value(), result.min())
              : String.format(Locale.ROOT, "dE %.1f  (min %.0f, worst %s)", result.value(), result.min(),
                    result.vision().name().toLowerCase(Locale.ROOT));
        row.add(text(measure, "small", "text.muted"), BorderLayout.EAST);
        return row;
    }

    private JComponent bar(String name, float value) {
        JPanel row = row(color("surface.base"));
        row.setLayout(new FlowLayout(FlowLayout.LEADING, scaleForGUI(8), 0));
        JLabel label = text(name + "  " + trim(value), "small", "text.muted");
        label.setPreferredSize(new Dimension(scaleForGUI(80), label.getPreferredSize().height));
        row.add(label);
        JPanel bar = new JPanel();
        bar.setBackground(color("accent.primary"));
        bar.setPreferredSize(new Dimension(Math.round(scaleForGUI(value)), scaleForGUI(10)));
        row.add(bar);
        return row;
    }

    // ---- Building blocks

    private Color color(String role) {
        return new Color(tokens.color(role, mode), true);
    }

    private Font font(String styleName) {
        return fonts.computeIfAbsent(styleName, name -> {
            TypeStyle style = tokens.typeStyle(name);
            Map<TextAttribute, Object> attributes = new HashMap<>();
            // Java2D only reaches the variable font's default (regular) instance, so medium comes from the static
            // "Roboto Medium" face generated from it in mm-data.
            boolean medium = style.weight() >= 500 && style.weight() < 600;
            attributes.put(TextAttribute.FAMILY, medium ? tokens.family() + " Medium" : tokens.family());
            attributes.put(TextAttribute.SIZE, scaleForGUI(style.size()));
            attributes.put(TextAttribute.TRACKING, style.tracking());
            attributes.put(TextAttribute.WEIGHT,
                  style.weight() >= 600 ? TextAttribute.WEIGHT_BOLD : TextAttribute.WEIGHT_REGULAR);
            return Font.getFont(attributes);
        });
    }

    private JLabel text(String value, String style, String role) {
        JLabel label = new JLabel(value);
        label.setFont(font(style));
        label.setForeground(color(role));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    /** Text that wraps at the page width instead of widening the page. */
    private JTextArea paragraph(String value, String style, String role) {
        // The i18n views measure fractional glyph advances; the plain ones round them and wrap too late.
        PlainDocument document = new PlainDocument();
        document.putProperty("i18n", Boolean.TRUE);
        JTextArea area = new JTextArea(document, value, 0, 0);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(font(style));
        area.setForeground(color(role));
        area.setAlignmentX(LEFT_ALIGNMENT);
        return area;
    }

    private JLabel heading(String value) {
        JLabel label = text(value, "title", "text.primary");
        label.setBorder(BorderFactory.createEmptyBorder(scaleForGUI(16), 0, scaleForGUI(6), 0));
        return label;
    }

    private static JPanel column(Color background) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(background);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private static JPanel row(Color background) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, scaleForGUI(8), scaleForGUI(4)));
        panel.setBackground(background);
        panel.setBorder(BorderFactory.createEmptyBorder(scaleForGUI(4), scaleForGUI(8), scaleForGUI(4),
              scaleForGUI(8)));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private JComponent page(JComponent body) {
        body.setBorder(BorderFactory.createEmptyBorder(scaleForGUI(8), scaleForGUI(16), scaleForGUI(16),
              scaleForGUI(16)));
        // Rows stretch to the page width but keep their own height.
        for (Component child : body.getComponents()) {
            if (child instanceof JComponent component && !(child instanceof JTextArea)) {
                component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
            }
        }
        JPanel holder = new WidthTrackingPanel();
        holder.setBackground(body.getBackground());
        holder.add(body, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(holder);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(scaleForGUI(24));
        return scroll;
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }

    /** Holds a page in its scroll pane: it fits the viewport width so long rows wrap or stretch, never scroll. */
    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel() {
            super(new BorderLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return scaleForGUI(24);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            // Short pages still fill the viewport with their background.
            return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
        }
    }

    /** A painted sample: a color swatch, a control box, a focus ring, corner ticks or a full frame. */
    private final class Swatch extends JComponent {
        enum Kind { COLOR, CONTROL, FOCUS, TICKS, FRAME }

        private final String name;
        private final Kind kind;

        Swatch(String name, int width, int height, Kind kind) {
            this.name = name;
            this.kind = kind;
            setPreferredSize(new Dimension(width, height + (kind == Kind.COLOR ? 0 : scaleForGUI(8))));
            setMinimumSize(getPreferredSize());
            setToolTipText(name);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int inset = kind == Kind.COLOR ? 0 : scaleForGUI(4);
                int width = getWidth() - 2 * inset - 1;
                int height = getHeight() - 2 * inset - 1;
                if (kind == Kind.COLOR) {
                    g.setColor(color("surface.panel"));
                    g.fillRect(0, 0, width, height);
                    g.setColor(color(name));
                    g.fillRect(0, 0, width, height);
                    g.setColor(color("border.subtle"));
                    g.drawRect(0, 0, width, height);
                    return;
                }
                float radius = scaleForGUI(tokens.radius().get("md"));
                g.translate(inset, inset);
                g.setColor(color("surface.input"));
                g.fillRoundRect(0, 0, width, height, Math.round(2 * radius), Math.round(2 * radius));
                switch (kind) {
                    case CONTROL -> {
                        g.setColor(color("border.control"));
                        g.drawRoundRect(0, 0, width, height, Math.round(2 * radius), Math.round(2 * radius));
                    }
                    case FOCUS -> {
                        g.setColor(color("border.control"));
                        g.drawRoundRect(0, 0, width, height, Math.round(2 * radius), Math.round(2 * radius));
                        float ring = scaleForGUI(tokens.size().get("focus.width"));
                        float offset = scaleForGUI(tokens.size().get("focus.offset"));
                        g.setColor(color("focus.ring"));
                        g.setStroke(new BasicStroke(ring));
                        float grow = offset + ring / 2;
                        g.drawRoundRect(Math.round(-grow), Math.round(-grow), Math.round(width + 2 * grow),
                              Math.round(height + 2 * grow), Math.round(2 * (radius + grow)),
                              Math.round(2 * (radius + grow)));
                    }
                    case TICKS -> ticks(g, width, height, color("border.control"));
                    case FRAME -> {
                        g.setColor(color("border.strong"));
                        g.drawRect(0, 0, width, height);
                    }
                    default -> {
                    }
                }
                g.setFont(font("label"));
                g.setColor(color("text.secondary"));
                int textWidth = g.getFontMetrics().stringWidth(name);
                g.drawString(name, (width - textWidth) / 2,
                      (height + g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2);
            } finally {
                g.dispose();
            }
        }

        private void ticks(Graphics2D g, int width, int height, Color tick) {
            int length = Math.round(scaleForGUI(tokens.size().get("tick.control")));
            g.setColor(tick);
            g.drawLine(0, 0, length, 0);
            g.drawLine(0, 0, 0, length);
            g.drawLine(width - length, 0, width, 0);
            g.drawLine(width, 0, width, length);
            g.drawLine(0, height, length, height);
            g.drawLine(0, height - length, 0, height);
            g.drawLine(width - length, height, width, height);
            g.drawLine(width, height - length, width, height);
        }
    }
}
