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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultButtonModel;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayer;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainDocument;
import javax.swing.tree.DefaultMutableTreeNode;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
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
    private static final int ICON_SEARCH = 0xE8B6;
    /** Masked in the samples; not a real password. */
    private static final String SAMPLE_PASSWORD = "atlas-as7d";

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
        page.add(paragraph(Messages.getString("UiLibrary.buttons.note"), "small", "text.muted"));
        for (TokenButtons.Size size : TokenButtons.Size.values()) {
            JComponent gap = (JComponent) Box.createVerticalStrut(scaleForGUI(12));
            gap.setAlignmentX(LEFT_ALIGNMENT);
            page.add(gap);
            page.add(text(Messages.getString("UiLibrary.buttons." + size.name().toLowerCase(Locale.ROOT)), "label",
                  "text.secondary"));
            page.add(buttonMatrix(size));
        }

        page.add(heading(Messages.getString("UiLibrary.controls.choices")));
        page.add(paragraph(Messages.getString("UiLibrary.choices.note"), "small", "text.muted"));
        page.add(choiceMatrix());
        JPanel live = row(color("surface.base"));
        live.add(text(Messages.getString("UiLibrary.choices.live"), "label", "text.secondary"));
        live.add(TokenChoices.check(Messages.getString("UiLibrary.controls.checked"), true));
        live.add(TokenChoices.mixed(TokenChoices.check(Messages.getString("UiLibrary.choices.selectAll"), false),
              true));
        JRadioButton standard = TokenChoices.radio(Messages.getString("UiLibrary.controls.optionA"), true);
        JRadioButton total = TokenChoices.radio(Messages.getString("UiLibrary.controls.optionB"), false);
        TokenChoices.group(standard, total);
        live.add(standard);
        live.add(total);
        live.add(TokenChoices.toggleSwitch(Messages.getString("UiLibrary.choices.autosave"), true));
        page.add(live);
        JPanel choices = row(color("surface.base"));
        choices.add(new JComboBox<>(new String[] { Messages.getString("UiLibrary.controls.optionA"),
              Messages.getString("UiLibrary.controls.optionB") }));
        choices.add(new JSpinner(new SpinnerNumberModel(4, 0, 12, 1)));
        page.add(choices);

        page.add(heading(Messages.getString("UiLibrary.controls.inputs")));
        page.add(paragraph(Messages.getString("UiLibrary.inputs.note"), "small", "text.muted"));
        for (TokenButtons.Size size : TokenButtons.Size.values()) {
            JComponent gap = (JComponent) Box.createVerticalStrut(scaleForGUI(12));
            gap.setAlignmentX(LEFT_ALIGNMENT);
            page.add(gap);
            page.add(text(Messages.getString("UiLibrary.buttons." + size.name().toLowerCase(Locale.ROOT)), "label",
                  "text.secondary"));
            page.add(inputMatrix(size));
        }
        page.add(heading(Messages.getString("UiLibrary.inputs.forms")));
        page.add(paragraph(Messages.getString("UiLibrary.inputs.forms.note"), "small", "text.muted"));
        page.add(formSamples());

        page.add(heading(Messages.getString("UiLibrary.controls.ranges")));
        JPanel ranges = row(color("surface.base"));
        ranges.add(new JSlider(0, 100, 60));
        JProgressBar progress = new JProgressBar(0, 100);
        progress.setValue(60);
        progress.setStringPainted(true);
        ranges.add(progress);
        page.add(ranges);

        page.add(heading(Messages.getString("UiLibrary.controls.tabs")));
        page.add(paragraph(Messages.getString("UiLibrary.tabs.note"), "small", "text.muted"));
        for (TokenButtons.Size size : TokenButtons.Size.values()) {
            JComponent gap = (JComponent) Box.createVerticalStrut(scaleForGUI(12));
            gap.setAlignmentX(LEFT_ALIGNMENT);
            page.add(gap);
            page.add(text(Messages.getString("UiLibrary.buttons." + size.name().toLowerCase(Locale.ROOT)), "label",
                  "text.secondary"));
            page.add(tabSamples(size));
        }

        page.add(heading(Messages.getString("UiLibrary.controls.segmented")));
        page.add(paragraph(Messages.getString("UiLibrary.segmented.note"), "small", "text.muted"));
        page.add(segmentedMatrix());

        page.add(heading(Messages.getString("UiLibrary.controls.select")));
        page.add(paragraph(Messages.getString("UiLibrary.select.note"), "small", "text.muted"));
        page.add(selectMatrix());
        page.add(selectPopupSample());

        page.add(heading(Messages.getString("UiLibrary.controls.lists")));
        page.add(paragraph(Messages.getString("UiLibrary.lists.note"), "small", "text.muted"));
        page.add(listSamples());

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

        page.add(heading(Messages.getString("UiLibrary.controls.focus")));
        page.add(paragraph(Messages.getString("UiLibrary.focus.note"), "small", "text.muted"));
        page.add(focusSamples());
        return page;
    }

    private JTextField field(String value, TokenFields.Status status) {
        JTextField field = TokenFields.status(TokenFields.text(TokenButtons.Size.COMPACT), status);
        field.setText(value);
        field.setColumns(14);
        return field;
    }

    /**
     * Every input kind at {@code size} in every state. "At rest" is live; hover and focus are frozen, and the rest are
     * real states that stay interactive.
     */
    private JComponent inputMatrix(TokenButtons.Size size) {
        List<String> states = List.of("rest", "hover", "focus", "placeholder", "error", "warning", "readOnly",
              "disabled");
        JPanel matrix = new JPanel(new GridBagLayout());
        matrix.setBackground(color("surface.base"));
        matrix.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints cell = new GridBagConstraints();
        cell.anchor = GridBagConstraints.WEST;
        cell.insets = new Insets(scaleForGUI(4), scaleForGUI(6), scaleForGUI(4), scaleForGUI(6));
        cell.gridy = 0;
        matrix.add(text(Messages.getString("UiLibrary.buttons.kind"), "caption", "text.muted"), cell);
        for (String state : states) {
            matrix.add(text(Messages.getString("UiLibrary.inputs.state." + state), "caption", "text.muted"), cell);
        }
        for (String kind : List.of("TEXT", "PASSWORD", "SEARCH", "NUMBER")) {
            cell.gridy++;
            matrix.add(text(Messages.getString("UiLibrary.inputs.kind." + kind), "label", "text.primary"), cell);
            for (String state : states) {
                boolean number = kind.equals("NUMBER");
                // A number has no placeholder, and a read-only one would still step; show it as text instead
                if (number && (state.equals("placeholder") || state.equals("readOnly"))) {
                    matrix.add(Box.createHorizontalStrut(0), cell);
                    continue;
                }
                JComponent input = sampleInput(kind, size, state.equals("placeholder"));
                JComponent text = input instanceof JSpinner spinner
                      && spinner.getEditor() instanceof JSpinner.DefaultEditor editor ? editor.getTextField() : input;
                switch (state) {
                    case "hover" -> input.putClientProperty(FieldEdge.FROZEN_HOVER, true);
                    case "focus" -> {
                        focused(text);
                        focused(input);
                    }
                    case "error" -> TokenFields.status(input, TokenFields.Status.ERROR);
                    case "warning" -> TokenFields.status(input, TokenFields.Status.WARNING);
                    case "readOnly" -> ((JTextComponent) text).setEditable(false);
                    case "disabled" -> input.setEnabled(false);
                    default -> { }
                }
                matrix.add(input, cell);
            }
        }
        cell.gridx = states.size() + 1;
        cell.weightx = 1;
        matrix.add(Box.createHorizontalGlue(), cell);
        return matrix;
    }

    private static JComponent sampleInput(String kind, TokenButtons.Size size, boolean empty) {
        String placeholder = kind.equals("NUMBER") ? null
              : Messages.getString("UiLibrary.inputs.placeholder." + kind);
        JComponent input = switch (kind) {
            case "PASSWORD" -> {
                JPasswordField field = TokenFields.password(size);
                field.setText(empty ? "" : SAMPLE_PASSWORD);
                yield field;
            }
            case "SEARCH" -> {
                JTextField field = TokenFields.search(placeholder, size);
                field.setText(empty ? "" : Messages.getString("UiLibrary.inputs.value.SEARCH"));
                yield field;
            }
            case "NUMBER" -> TokenFields.number(new SpinnerNumberModel(4, 0, 8, 1), size);
            default -> {
                JTextField field = TokenFields.text(size);
                field.setText(empty ? "" : Messages.getString("UiLibrary.inputs.value.TEXT"));
                yield field;
            }
        };
        if (input instanceof JTextField field) {
            field.setColumns(8);
            field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        }
        return input;
    }

    /** Form fields: a label above every input, a helper or a status message below. */
    private JComponent formSamples() {
        JPanel samples = column(color("surface.base"));
        JPanel singleLine = formRow();
        samples.add(singleLine);
        JTextField name = TokenFields.text(TokenButtons.Size.REGULAR);
        name.setColumns(16);
        name.setText(Messages.getString("UiLibrary.inputs.value.TEXT"));
        singleLine.add(new TokenFormField(Messages.getString("UiLibrary.inputs.form.name"), name)
              .setHelper(Messages.getString("UiLibrary.inputs.form.nameHelp")));
        singleLine.add(new TokenFormField(Messages.getString("UiLibrary.inputs.form.gunnery"),
              TokenFields.number(new SpinnerNumberModel(9, 0, 12, 1), TokenButtons.Size.REGULAR))
              .setError(Messages.getString("UiLibrary.inputs.form.gunneryError")));
        singleLine.add(new TokenFormField(Messages.getString("UiLibrary.inputs.form.piloting"),
              TokenFields.number(new SpinnerNumberModel(7, 0, 12, 1), TokenButtons.Size.REGULAR))
              .setWarning(Messages.getString("UiLibrary.inputs.form.pilotingWarning")));

        JPanel areas = formRow();
        samples.add(areas);
        String notes = Messages.getString("UiLibrary.inputs.form.notesText");
        areas.add(new TokenFormField(Messages.getString("UiLibrary.inputs.form.notes"),
              TokenFields.area(new JTextArea(notes, 3, 22)))
              .setHelper(Messages.getString("UiLibrary.inputs.form.notesHelp")));
        JTextArea briefing = new JTextArea(notes, 3, 22);
        briefing.setEditable(false);
        areas.add(new TokenFormField(Messages.getString("UiLibrary.inputs.form.briefing"),
              TokenFields.area(briefing)));
        JTextArea disabled = new JTextArea(notes, 3, 22);
        disabled.setEnabled(false);
        JScrollPane disabledScroll = TokenFields.area(disabled);
        disabledScroll.setEnabled(false);
        areas.add(new TokenFormField(Messages.getString("UiLibrary.inputs.form.disabled"), disabledScroll));
        singleLine.add(Box.createHorizontalGlue());
        areas.add(Box.createHorizontalGlue());
        return samples;
    }

    /** A row of form fields lined up at their tops, so labels align whatever messages sit below. */
    private JPanel formRow() {
        JPanel row = new JPanel(new GridBagLayout()) {
            @Override
            public Component add(Component field) {
                GridBagConstraints cell = new GridBagConstraints();
                cell.anchor = GridBagConstraints.NORTHWEST;
                cell.insets = new Insets(scaleForGUI(6), scaleForGUI(8), scaleForGUI(6), scaleForGUI(8));
                // The trailing glue takes the spare width, so the fields stay at the left
                cell.weightx = field instanceof TokenFormField ? 0 : 1;
                super.add(field, cell);
                return field;
            }
        };
        row.setBackground(color("surface.base"));
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    /** Controls frozen in keyboard focus, which the live samples above only show after Tab. */
    private JComponent focusSamples() {
        JPanel samples = column(color("surface.base"));
        JPanel controls = row(color("surface.base"));
        samples.add(controls);
        controls.add(focused(field(Messages.getString("UiLibrary.controls.value"), TokenFields.Status.NONE)));
        controls.add(focused(field(Messages.getString("UiLibrary.controls.error"),
              TokenFields.Status.ERROR)));
        controls.add(focused(new JComboBox<>(new String[] { Messages.getString("UiLibrary.controls.optionA") })));
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(4, 0, 12, 1));
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            focused(editor.getTextField());
        }
        controls.add(focused(spinner));
        JPanel indicators = row(color("surface.base"));
        samples.add(indicators);
        JCheckBox checked = new JCheckBox(Messages.getString("UiLibrary.controls.checked"));
        checked.setModel(new FrozenModel(false, false, true));
        indicators.add(focused(checked));
        indicators.add(focused(new JCheckBox(Messages.getString("UiLibrary.controls.unchecked"))));
        JRadioButton radio = new JRadioButton(Messages.getString("UiLibrary.controls.optionA"));
        radio.setModel(new FrozenModel(false, false, true));
        indicators.add(focused(radio));
        indicators.add(focused(new JSlider(0, 100, 60)));
        JTabbedPane tabs = new JTabbedPane();
        for (String key : List.of("UiLibrary.controls.tabA", "UiLibrary.controls.tabB", "UiLibrary.controls.tabC")) {
            tabs.addTab(Messages.getString(key), Box.createVerticalStrut(scaleForGUI(4)));
        }
        tabs.setAlignmentX(LEFT_ALIGNMENT);
        samples.add(focused(tabs));
        return samples;
    }

    /** Freezes {@code component} in the keyboard-focused look; it can't take real focus. */
    private static <T extends JComponent> T focused(T component) {
        component.setFocusable(false);
        component.putClientProperty(FlatClientProperties.COMPONENT_FOCUS_OWNER,
              (Predicate<JComponent>) c -> true);
        return component;
    }

    /** Tab strips at {@code size}: a live one, one frozen in focus and a disabled one. */
    private JComponent tabSamples(TokenButtons.Size size) {
        JPanel samples = row(color("surface.base"));
        for (String state : List.of("rest", "focus", "disabled")) {
            JPanel sample = new JPanel(new BorderLayout());
            sample.setOpaque(false);
            sample.add(text(Messages.getString("UiLibrary.buttons.state." + state), "caption", "text.muted"),
                  BorderLayout.NORTH);
            JTabbedPane tabs = TokenTabs.tabs(size);
            for (String key : List.of("UiLibrary.controls.tabA", "UiLibrary.controls.tabB",
                  "UiLibrary.controls.tabC")) {
                JPanel body = new JPanel(new BorderLayout());
                body.add(new JLabel(Messages.getString(key)), BorderLayout.CENTER);
                body.setBorder(BorderFactory.createEmptyBorder(scaleForGUI(8), scaleForGUI(8), scaleForGUI(8),
                      scaleForGUI(8)));
                tabs.addTab(Messages.getString(key), body);
            }
            switch (state) {
                case "focus" -> focused(tabs);
                case "disabled" -> tabs.setEnabled(false);
                default -> { }
            }
            // One strip, wide enough for its tabs; a wrapping tab pane in a flow row stacks them
            tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            tabs.setPreferredSize(new Dimension(scaleForGUI(280), tabs.getPreferredSize().height));
            sample.add(tabs, BorderLayout.CENTER);
            samples.add(sample);
        }
        return samples;
    }

    /**
     * Segmented controls in every state, one column per size; the second section carries the frozen state and the "at
     * rest" sample is live.
     */
    private JComponent segmentedMatrix() {
        List<String> states = List.of("rest", "hover", "pressed", "focus", "disabled");
        JPanel matrix = new JPanel(new GridBagLayout());
        matrix.setBackground(color("surface.base"));
        matrix.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints cell = new GridBagConstraints();
        cell.anchor = GridBagConstraints.WEST;
        cell.insets = new Insets(scaleForGUI(4), scaleForGUI(8), scaleForGUI(4), scaleForGUI(8));
        cell.gridy = 0;
        matrix.add(Box.createHorizontalStrut(0), cell);
        for (TokenButtons.Size size : TokenButtons.Size.values()) {
            matrix.add(text(Messages.getString("UiLibrary.buttons." + size.name().toLowerCase(Locale.ROOT)),
                  "caption", "text.muted"), cell);
        }
        for (String state : states) {
            cell.gridy++;
            matrix.add(text(Messages.getString("UiLibrary.buttons.state." + state), "label", "text.primary"), cell);
            for (TokenButtons.Size size : TokenButtons.Size.values()) {
                TokenSegmented segmented = new TokenSegmented(size,
                      Messages.getString("UiLibrary.segmented.optionA"),
                      Messages.getString("UiLibrary.segmented.optionB"),
                      Messages.getString("UiLibrary.segmented.optionC"));
                switch (state) {
                    case "hover" -> segmented.segment(1).setModel(new FrozenModel(true, false, false));
                    case "pressed" -> segmented.segment(1).setModel(new FrozenModel(true, true, false));
                    case "focus" -> focused(segmented.segment(1));
                    case "disabled" -> segmented.setEnabled(false);
                    default -> { }
                }
                matrix.add(segmented, cell);
            }
        }
        cell.gridx = TokenButtons.Size.values().length + 1;
        cell.weightx = 1;
        matrix.add(Box.createHorizontalGlue(), cell);
        return matrix;
    }

    /**
     * Lists and a tree side by side, each with a caption: hover is frozen on a row of the first list and the tree, the
     * rest stay live.
     */
    private JComponent listSamples() {
        String[] units = { "Atlas AS7-D", "Marauder MAD-3R", "Phoenix Hawk PXH-1", "Locust LCT-1V", "Wasp WSP-1A" };
        JPanel samples = new JPanel(new GridBagLayout());
        samples.setBackground(color("surface.base"));
        samples.setAlignmentX(LEFT_ALIGNMENT);

        JList<String> states = new JList<>(units);
        states.setSelectedIndex(2);
        states.putClientProperty(TokenLists.FROZEN_HOVER, 0);
        addListSample(samples, 0, 0, "UiLibrary.lists.states", states);

        JList<String> multi = new JList<>(units);
        multi.setSelectedIndices(new int[] { 1, 2, 4 });
        addListSample(samples, 1, 0, "UiLibrary.lists.multi", multi);

        JList<String[]> twoLine = TokenLists.twoLine(new JList<>(new String[][] {
              { "Atlas AS7-D", "100", "3" }, { "Marauder MAD-3R", "75", "4" }, { "Locust LCT-1V", "20", "8" } }),
              unit -> unit[0], unit -> Messages.getString("UiLibrary.lists.detail", unit[1], unit[2]));
        twoLine.setSelectedIndex(1);
        addListSample(samples, 2, 0, "UiLibrary.lists.twoLine", twoLine);

        DefaultMutableTreeNode company = new DefaultMutableTreeNode(Messages.getString("UiLibrary.lists.company"));
        DefaultMutableTreeNode command = new DefaultMutableTreeNode(Messages.getString("UiLibrary.lists.commandLance"));
        command.add(new DefaultMutableTreeNode(units[0]));
        command.add(new DefaultMutableTreeNode(units[1]));
        DefaultMutableTreeNode fire = new DefaultMutableTreeNode(Messages.getString("UiLibrary.lists.fireLance"));
        fire.add(new DefaultMutableTreeNode(units[2]));
        fire.add(new DefaultMutableTreeNode(units[3]));
        company.add(command);
        company.add(fire);
        JTree tree = new JTree(company);
        for (int row = 0; row < tree.getRowCount(); row++) {
            tree.expandRow(row);
        }
        tree.setSelectionRow(3);
        tree.putClientProperty(TokenLists.FROZEN_HOVER, 2);
        addListSample(samples, 0, 1, "UiLibrary.lists.tree", tree);

        JList<String> empty = TokenLists.emptyText(new JList<>(), Messages.getString("UiLibrary.lists.emptyText"));
        addListSample(samples, 1, 1, "UiLibrary.lists.empty", empty);

        JList<String> disabled = new JList<>(units);
        disabled.setSelectedIndex(1);
        disabled.setEnabled(false);
        addListSample(samples, 2, 1, "UiLibrary.lists.disabled", disabled);

        GridBagConstraints glue = new GridBagConstraints();
        glue.gridx = 3;
        glue.weightx = 1;
        samples.add(Box.createHorizontalGlue(), glue);
        return samples;
    }

    /** Adds {@code view} in its scroll pane under a caption, at {@code column} of caption row {@code row}. */
    private void addListSample(JPanel samples, int column, int row, String captionKey, JComponent view) {
        GridBagConstraints cell = new GridBagConstraints();
        cell.anchor = GridBagConstraints.NORTHWEST;
        cell.insets = new Insets(scaleForGUI(4), scaleForGUI(6), scaleForGUI(4), scaleForGUI(6));
        cell.gridx = column;
        cell.gridy = row * 2;
        samples.add(text(Messages.getString(captionKey), "caption", "text.muted"), cell);
        cell.gridy++;
        JScrollPane scroll = TokenLists.scroll(view);
        scroll.setPreferredSize(new Dimension(scaleForGUI(210), scaleForGUI(170)));
        samples.add(scroll, cell);
    }

    /** Selects of each kind in each state; hover and focus are frozen, the rest stay live. */
    private JComponent selectMatrix() {
        List<String> states = List.of("rest", "hover", "focus", "placeholder", "disabled");
        JPanel matrix = new JPanel(new GridBagLayout());
        matrix.setBackground(color("surface.base"));
        matrix.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints cell = new GridBagConstraints();
        cell.anchor = GridBagConstraints.WEST;
        cell.insets = new Insets(scaleForGUI(4), scaleForGUI(6), scaleForGUI(4), scaleForGUI(6));
        cell.gridy = 0;
        matrix.add(text(Messages.getString("UiLibrary.buttons.kind"), "caption", "text.muted"), cell);
        for (String state : states) {
            matrix.add(text(Messages.getString("UiLibrary.inputs.state." + state), "caption", "text.muted"), cell);
        }
        for (String kind : List.of("REGULAR", "COMPACT", "INLINE")) {
            cell.gridy++;
            matrix.add(text(Messages.getString("UiLibrary.select.kind." + kind), "label", "text.primary"), cell);
            for (String state : states) {
                JComboBox<String> select = sampleSelect(kind);
                switch (state) {
                    case "hover" -> select.putClientProperty(FieldEdge.FROZEN_HOVER, true);
                    case "focus" -> focused(select);
                    case "placeholder" -> {
                        TokenSelects.placeholder(select, Messages.getString("UiLibrary.select.placeholder"));
                        select.setSelectedIndex(-1);
                    }
                    case "disabled" -> select.setEnabled(false);
                    default -> { }
                }
                matrix.add(select, cell);
            }
        }
        cell.gridx = states.size() + 1;
        cell.weightx = 1;
        matrix.add(Box.createHorizontalGlue(), cell);
        return matrix;
    }

    private static JComboBox<String> sampleSelect(String kind) {
        String[] items = { Messages.getString("UiLibrary.controls.optionA"),
                           Messages.getString("UiLibrary.controls.optionB"),
                           Messages.getString("UiLibrary.select.optionC"),
                           Messages.getString("UiLibrary.select.optionD") };
        return switch (kind) {
            case "INLINE" -> TokenSelects.inline(items);
            case "COMPACT" -> TokenSelects.select(TokenButtons.Size.COMPACT, items);
            default -> TokenSelects.select(TokenButtons.Size.REGULAR, items);
        };
    }

    /**
     * The popup list drawn in place, as the open select shows it: the current value checked and the second row under
     * the pointer. A live select sits beside it.
     */
    private JComponent selectPopupSample() {
        JPanel samples = row(color("surface.base"));
        JComboBox<String> owner = sampleSelect("REGULAR");
        // The list and its frame take the popup colors again whenever the look and feel changes, as a real popup does
        JList<String> list = new JList<>(owner.getModel()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(UIManager.getColor("ComboBox.popupBackground"));
                setSelectionBackground(UIManager.getColor("ComboBox.selectionBackground"));
                setSelectionForeground(UIManager.getColor("ComboBox.selectionForeground"));
            }
        };
        int padY = scaleForGUI(2);
        int padX = scaleForGUI(8);
        list.setCellRenderer((l, value, index, selected, focus) -> {
            Component c = owner.getRenderer().getListCellRendererComponent(l, value, index, selected, focus);
            if (c instanceof JComponent component) {
                component.setBorder(BorderFactory.createEmptyBorder(padY, padX, padY, padX));
            }
            return c;
        });
        Insets rowInsets = UIManager.getInsets("ComboBox.selectionInsets");
        list.putClientProperty(FlatClientProperties.STYLE, "selectionArc: " + UIManager.getInt("ComboBox.selectionArc")
              + "; selectionInsets: " + rowInsets.top + "," + rowInsets.left + "," + rowInsets.bottom + ","
              + rowInsets.right);
        list.setSelectedIndex(1);
        list.setVisibleRowCount(owner.getItemCount());
        list.setFocusable(false);
        // Named like a real popup list, so it keeps the select's rows rather than the plain list's
        list.setName(TokenLists.POPUP_LIST);
        JScrollPane scroller = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
              ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(null);
                Insets pad = UIManager.getInsets("ComboBox.popupInsets");
                setViewportBorder(pad == null ? null : new FlatEmptyBorder(pad));
                setOpaque(false);
                getViewport().setOpaque(false);
                list.setOpaque(false);
            }
        };
        // Built like the real popup: the scroll pane first makes PopupMenu.border a hairline, the width leaves room for
        // the scroll bar as FlatLaf's popup does, and the corners round as the popup window's do
        JPanel popup = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                size.width += new JScrollBar(JScrollBar.VERTICAL).getPreferredSize().width;
                size.width = Math.max(size.width, owner.getPreferredSize().width);
                return size;
            }

            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(UIManager.getBorder("PopupMenu.border"));
                setBackground(UIManager.getColor("ComboBox.popupBackground"));
                setOpaque(false);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    FlatUIUtils.setRenderingHints(g2);
                    g2.setColor(getBackground());
                    FlatUIUtils.paintComponentBackground(g2, 0, 0, getWidth(), getHeight(), 0, arc());
                } finally {
                    g2.dispose();
                }
            }

            @Override
            protected void paintBorder(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    FlatUIUtils.setRenderingHints(g2);
                    g2.setColor(UIManager.getColor("PopupMenu.borderColor"));
                    FlatUIUtils.paintOutline(g2, 0, 0, getWidth(), getHeight(), UIScale.scale(1f), arc());
                } finally {
                    g2.dispose();
                }
            }

            private float arc() {
                return UIScale.scale(UIManager.getInt("Popup.borderCornerRadius") * 2f);
            }
        };
        popup.add(scroller);

        JPanel open = new JPanel(new GridBagLayout());
        open.setOpaque(false);
        GridBagConstraints place = new GridBagConstraints();
        place.gridx = 0;
        place.anchor = GridBagConstraints.WEST;
        open.add(text(Messages.getString("UiLibrary.select.popup"), "caption", "text.muted"), place);
        open.add(owner, place);
        place.insets = new Insets(scaleForGUI(4), 0, 0, 0);
        open.add(popup, place);
        samples.add(open);

        JPanel live = new JPanel();
        live.setOpaque(false);
        live.setLayout(new BoxLayout(live, BoxLayout.Y_AXIS));
        JComponent caption = text(Messages.getString("UiLibrary.select.live"), "caption", "text.muted");
        caption.setAlignmentX(LEFT_ALIGNMENT);
        live.add(caption);
        JPanel controls = row(color("surface.base"));
        controls.setAlignmentX(LEFT_ALIGNMENT);
        controls.add(sampleSelect("REGULAR"));
        controls.add(sampleSelect("COMPACT"));
        controls.add(sampleSelect("INLINE"));
        live.add(controls);
        samples.add(live);
        return samples;
    }

    /**
     * Every choice kind in every state, frozen through the button model; the live row under it can be clicked.
     */
    private JComponent choiceMatrix() {
        List<String> states = List.of("off", "on", "mixed", "hover", "hoverOn", "focus", "disabled", "disabledOn");
        JPanel matrix = new JPanel(new GridBagLayout());
        matrix.setBackground(color("surface.base"));
        matrix.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints cell = new GridBagConstraints();
        cell.anchor = GridBagConstraints.WEST;
        cell.insets = new Insets(scaleForGUI(4), scaleForGUI(6), scaleForGUI(4), scaleForGUI(6));
        cell.gridy = 0;
        matrix.add(text(Messages.getString("UiLibrary.buttons.kind"), "caption", "text.muted"), cell);
        for (String state : states) {
            matrix.add(text(Messages.getString("UiLibrary.choices.state." + state), "caption", "text.muted"), cell);
        }
        for (String kind : List.of("CHECK", "RADIO", "SWITCH")) {
            cell.gridy++;
            matrix.add(text(Messages.getString("UiLibrary.choices.kind." + kind), "label", "text.primary"), cell);
            for (String state : states) {
                if (state.equals("mixed") && !kind.equals("CHECK")) {
                    matrix.add(Box.createHorizontalStrut(0), cell);
                    continue;
                }
                String label = Messages.getString("UiLibrary.choices.sample");
                AbstractButton choice = switch (kind) {
                    case "CHECK" -> TokenChoices.check(label, false);
                    case "RADIO" -> TokenChoices.radio(label, false);
                    default -> TokenChoices.toggleSwitch(label, false);
                };
                boolean on = state.equals("on") || state.endsWith("On");
                choice.setModel(new FrozenModel(state.startsWith("hover"), false, on));
                switch (state) {
                    case "mixed" -> TokenChoices.mixed((JCheckBox) choice, true);
                    case "focus" -> focused(choice);
                    case "disabled", "disabledOn" -> choice.setEnabled(false);
                    default -> { }
                }
                choice.setFocusable(false);
                matrix.add(choice, cell);
            }
        }
        cell.gridx = states.size() + 1;
        cell.weightx = 1;
        matrix.add(Box.createHorizontalGlue(), cell);
        return matrix;
    }

    /** Every button kind at {@code size} in every state, the transient states frozen through the button model. */
    private JComponent buttonMatrix(TokenButtons.Size size) {
        List<String> states = List.of("rest", "hover", "pressed", "focus", "selected", "disabled");
        JPanel matrix = new JPanel(new GridBagLayout());
        matrix.setBackground(color("surface.base"));
        matrix.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints cell = new GridBagConstraints();
        cell.anchor = GridBagConstraints.WEST;
        cell.insets = new Insets(scaleForGUI(4), scaleForGUI(8), scaleForGUI(4), scaleForGUI(8));
        cell.gridy = 0;
        matrix.add(text(Messages.getString("UiLibrary.buttons.kind"), "caption", "text.muted"), cell);
        for (String state : states) {
            matrix.add(text(Messages.getString("UiLibrary.buttons.state." + state), "caption", "text.muted"), cell);
        }
        for (TokenButtons.Kind kind : TokenButtons.Kind.values()) {
            cell.gridy++;
            matrix.add(text(Messages.getString("UiLibrary.buttons.kind." + kind.name()), "label", "text.primary"),
                  cell);
            boolean selectable = kind == TokenButtons.Kind.TOGGLE || kind == TokenButtons.Kind.ICON
                  || kind == TokenButtons.Kind.SPECIAL;
            for (String state : states) {
                if (state.equals("selected") && !selectable) {
                    matrix.add(Box.createHorizontalStrut(0), cell);
                    continue;
                }
                AbstractButton button = sampleButton(kind, size);
                // The "at rest" sample is live; the others keep their column's state whatever the pointer does
                switch (state) {
                    case "hover" -> button.setModel(new FrozenModel(true, false, false));
                    case "pressed" -> button.setModel(new FrozenModel(true, true, false));
                    case "focus" -> {
                        button.setModel(new FrozenModel(false, false, false));
                        button.putClientProperty(FlatClientProperties.COMPONENT_FOCUS_OWNER,
                              (Predicate<JComponent>) component -> true);
                    }
                    case "selected" -> button.setModel(new FrozenModel(false, false, true));
                    case "disabled" -> {
                        button.setModel(new FrozenModel(false, false, false));
                        button.setEnabled(false);
                    }
                    default -> { }
                }
                matrix.add(button, cell);
            }
        }
        cell.gridx = states.size() + 1;
        cell.weightx = 1;
        matrix.add(Box.createHorizontalGlue(), cell);
        return matrix;
    }

    private static AbstractButton sampleButton(TokenButtons.Kind kind, TokenButtons.Size size) {
        String label = Messages.getString("UiLibrary.buttons.label." + kind.name());
        return switch (kind) {
            case TOGGLE -> TokenButtons.toggle(label, size);
            case ICON -> TokenButtons.icon(ICON_SEARCH, label, true, size);
            case SPECIAL -> TokenButtons.special(label, size);
            default -> TokenButtons.button(kind, label, size);
        };
    }

    /** A button model that always reports one state, so a gallery sample keeps showing it under the pointer. */
    private static final class FrozenModel extends DefaultButtonModel {
        private final boolean rollover;
        private final boolean pressed;
        private final boolean selected;

        FrozenModel(boolean rollover, boolean pressed, boolean selected) {
            this.rollover = rollover;
            this.pressed = pressed;
            this.selected = selected;
        }

        @Override
        public boolean isRollover() {
            return rollover;
        }

        @Override
        public boolean isArmed() {
            return pressed;
        }

        @Override
        public boolean isPressed() {
            return pressed;
        }

        @Override
        public boolean isSelected() {
            return selected;
        }
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
        return fonts.computeIfAbsent(styleName, TokenFonts::font);
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
                        // The ring replaces the edge, drawn inside it
                        float ring = scaleForGUI(tokens.size().get("focus.width"));
                        g.setColor(color("focus.ring"));
                        g.setStroke(new BasicStroke(ring));
                        float half = ring / 2;
                        g.draw(new RoundRectangle2D.Float(half, half, width - ring + 1, height - ring + 1,
                              2 * radius - half, 2 * radius - half));
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
