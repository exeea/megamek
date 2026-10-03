/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.dialogs.phaseDisplay;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.io.Serial;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.IntStream;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogField;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.FieldKind;
import megamek.client.ui.dialogs.buttonDialogs.AbstractButtonDialog;
import megamek.client.ui.enums.DialogResult;
import megamek.client.ui.util.UIUtil;
import megamek.common.InfantryActionDeclaration;
import megamek.common.Player;
import megamek.common.annotations.Nullable;
import megamek.common.compute.InfantryActionStrengths;
import megamek.common.game.Game;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.Entity;
import megamek.common.units.Infantry;

/**
 * One player's declaration for an infantry vs. infantry action in one building (TO:AR pp. 169 to 172). The
 * attacker ticks the units to commit, or withdraws the force; the defender ticks the infantry to field and sets the
 * crew to commit, seeing the Marine Points it adds and the crew hits it costs. Each side sees its own strength unit
 * by unit and the other side as known. The dialog resizes freely and its text reflows to the width it is given.
 */
public class InfantryActionDeclarationDialog extends AbstractButtonDialog {

    /** How much larger than its content the dialog opens, so wrapped text is never cut off. */
    private static final double CONTENT_MARGIN = 1.15;
    /** The largest share of its screen the dialog grows to, as the base dialog allows. */
    private static final double MOST_OF_THE_SCREEN = 0.8;
    private static final int MINIMUM_WIDTH = 414;
    private static final int MINIMUM_HEIGHT = 276;

    private final Game game;
    private final Player player;
    private final AbstractBuildingEntity building;
    private final boolean defends;
    private final List<JCheckBox> unitBoxes = new ArrayList<>();
    private final List<Infantry> offeredUnits = new ArrayList<>();
    private JTextArea ownTotal;
    private JCheckBox withdrawBox;
    private JSpinner crewSpinner;
    private JTextArea crewEffect;
    /** What the dialog shows, as data: the Swing dialog and the native form show the same rows. */
    private Content content;

    /**
     * @param frame    the parent frame
     * @param game     the game
     * @param player   the declaring player
     * @param building the building the action is in, or would be in
     */
    public InfantryActionDeclarationDialog(JFrame frame, Game game, Player player, AbstractBuildingEntity building) {
        super(frame, "InfantryActionDeclarationDialog", InfantryActionStrengths.defends(player, building)
              ? "InfantryActionDeclarationDialog.title.defend" : "InfantryActionDeclarationDialog.title.attack");
        this.game = game;
        this.player = player;
        this.building = building;
        this.defends = InfantryActionStrengths.defends(player, building);
        initialize();
        setTitle(Messages.getString(titleKey(), building.getDisplayName()));
        setMinimumSize(new Dimension(UIUtil.scaleForGUI(MINIMUM_WIDTH), UIUtil.scaleForGUI(MINIMUM_HEIGHT)));
        growToFitContent();
    }

    /**
     * Opens the dialog with room to spare around its text. The packed size is exactly what the content asks for,
     * and wrapped text asks for too little height, so the last lines were cut off; a size the player saved earlier
     * can be smaller still. Either is grown to the content's size plus a margin, and a larger saved size is kept.
     */
    private void growToFitContent() {
        Dimension contentSize = getPreferredSize();
        int roomyWidth = (int) Math.ceil(contentSize.width * CONTENT_MARGIN);
        int roomyHeight = (int) Math.ceil(contentSize.height * CONTENT_MARGIN);
        Dimension currentSize = getSize();
        setSize(Math.max(currentSize.width, roomyWidth), Math.max(currentSize.height, roomyHeight));
        keepOnScreen();
    }

    /**
     * Leaves the dialog where it was placed, centred the first time and where the player saved it after that, and
     * only moves it back onto its screen if growing it pushed an edge off. Re-centring every time would throw away a
     * position the player chose. The screen is the one the dialog is on, so a saved spot on a second monitor stays
     * there.
     */
    private void keepOnScreen() {
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int width = Math.min(getWidth(), (int) (screen.width * MOST_OF_THE_SCREEN));
        int height = Math.min(getHeight(), (int) (screen.height * MOST_OF_THE_SCREEN));
        setSize(width, height);
        int x = Math.clamp(getX(), screen.x, (screen.x + screen.width) - width);
        int y = Math.clamp(getY(), screen.y, (screen.y + screen.height) - height);
        setLocation(x, y);
    }

    /** Defend, attack, reinforce a running attack, or, with nothing left to add, continue or withdraw from it. */
    private String titleKey() {
        if (defends) {
            boolean nothingToCommit = InfantryActionStrengths.unengagedFriendlyInfantryInside(game, player, building)
                  .isEmpty() && (InfantryActionStrengths.crewAvailableToCommit(building) <= 0);
            boolean onlyWithdrawalLeft = nothingToCommit
                  && InfantryActionStrengths.canWithdrawDefence(game, player, building);
            return onlyWithdrawalLeft ? "InfantryActionDeclarationDialog.title.holdOrWithdraw"
                  : "InfantryActionDeclarationDialog.title.defend";
        }
        if (!InfantryActionStrengths.hasActionRunning(game, building)) {
            return "InfantryActionDeclarationDialog.title.attack";
        }
        boolean somethingToAdd = !InfantryActionStrengths.unengagedFriendlyInfantryInside(game, player, building)
              .isEmpty();
        return somethingToAdd ? "InfantryActionDeclarationDialog.title.reinforce"
              : "InfantryActionDeclarationDialog.title.continue";
    }

    @Override
    protected Container createCenterPane() {
        JPanel column = new WidthTrackingPanel(new GridBagLayout());
        int padding = UIUtil.scaleForGUI(6);
        column.setBorder(javax.swing.BorderFactory.createEmptyBorder(padding, padding * 2, padding, padding * 2));
        content = defends ? defenceRows() : attackRows();
        addRows(column, content.own());
        addRows(column, content.against());
        refreshTotals();
        // A filler row takes the spare height, so the rows stay at the top when the dialog is tall
        GridBagConstraints filler = rowConstraints();
        filler.weighty = 1;
        column.add(new JPanel(), filler);
        JScrollPane scroller = new JScrollPane(column, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
              ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.setBorder(null);
        return scroller;
    }

    // ---------------------------------------------------------------- rows as data

    /** What a row of the declaration is. */
    private enum RowKind {
        HEADING,
        TEXT,
        /** A unit the player may commit, ticked at first. */
        UNIT,
        /** The crew the defender commits. */
        CREW,
        /** The box that withdraws the player's force. */
        WITHDRAW,
        /** The player's total, which follows the choices. */
        OWN_TOTAL,
        /** What the crew to commit adds and costs, which follows the choice. */
        CREW_EFFECT
    }

    /**
     * One row of the declaration as data.
     *
     * @param kind what the row is
     * @param text its text: the label of a unit, the crew or the withdrawal; empty for a total
     * @param unit the infantry of a UNIT row, otherwise null
     */
    private record Row(RowKind kind, String text, @Nullable Infantry unit) {
        Row(RowKind kind, String text) {
            this(kind, text, null);
        }
    }

    /**
     * The declaration as data: the player's own side, whose rows hold the choices, then the side it faces.
     *
     * @param own     the player's side
     * @param against the other side, from its heading on
     */
    private record Content(List<Row> own, List<Row> against) {}

    // ---------------------------------------------------------------- attacker

    private Content attackRows() {
        List<Row> own = new ArrayList<>();
        List<Entity> engaged = new ArrayList<>();
        for (Entity entity : InfantryActionStrengths.engaged(game, building, true)) {
            if (entity.getOwnerId() == player.getId()) {
                engaged.add(entity);
            }
        }
        own.add(new Row(RowKind.HEADING, Messages.getString(engaged.isEmpty()
              ? "InfantryActionDeclarationDialog.attackingWith" : "InfantryActionDeclarationDialog.reinforcingWith")));
        for (Entity unit : engaged) {
            own.add(new Row(RowKind.TEXT, Messages.getString("InfantryActionDeclarationDialog.alreadyIn",
                  unit.getDisplayName(), number(InfantryActionStrengths.points(unit, null)))));
        }
        for (Infantry unit : InfantryActionStrengths.unengagedFriendlyInfantryInside(game, player, building)) {
            own.add(new Row(RowKind.UNIT, unitLine(unit, null), unit));
        }
        own.add(new Row(RowKind.OWN_TOTAL, ""));
        if (!engaged.isEmpty()) {
            own.add(new Row(RowKind.WITHDRAW, Messages.getString("InfantryActionDeclarationDialog.withdraw")));
            own.add(new Row(RowKind.TEXT, Messages.getString("InfantryActionDeclarationDialog.withdrawExplained")));
        }
        List<Row> against = new ArrayList<>();
        against.add(new Row(RowKind.HEADING, Messages.getString("InfantryActionDeclarationDialog.against")));
        double known = 0;
        for (Infantry enemy : InfantryActionStrengths.enemyInfantryInside(game, player, building)) {
            against.add(new Row(RowKind.TEXT, unitLine(enemy, building)));
            known += InfantryActionStrengths.points(enemy, building);
        }
        double crewPoints = InfantryActionStrengths.hasCrewToDefend(building)
              ? InfantryActionStrengths.crewPointsIfAllCommitted(building) : 0;
        if (crewPoints > 0) {
            against.add(new Row(RowKind.TEXT, Messages.getString("InfantryActionDeclarationDialog.crewUpTo",
                  building.getDisplayName(), number(crewPoints))));
        }
        if ((known <= 0) && (crewPoints <= 0)) {
            against.add(new Row(RowKind.TEXT, Messages.getString("InfantryActionDeclarationDialog.nobodyDefends")));
        }
        against.add(new Row(RowKind.TEXT, Messages.getString("InfantryActionDeclarationDialog.defenderTotal",
              number(known), number(known + crewPoints))));
        return new Content(own, against);
    }

    // ---------------------------------------------------------------- defender

    private Content defenceRows() {
        List<Row> own = new ArrayList<>();
        List<Entity> engaged = new ArrayList<>();
        for (Entity entity : InfantryActionStrengths.engaged(game, building, false)) {
            boolean ownInfantry = (entity.getOwnerId() == player.getId()) && (entity != building);
            if (ownInfantry) {
                engaged.add(entity);
            }
        }
        own.add(new Row(RowKind.HEADING, Messages.getString("InfantryActionDeclarationDialog.defendingWith")));
        for (Entity unit : engaged) {
            own.add(new Row(RowKind.TEXT, Messages.getString("InfantryActionDeclarationDialog.alreadyIn",
                  unit.getDisplayName(), number(InfantryActionStrengths.points(unit, building)))));
        }
        for (Infantry unit : InfantryActionStrengths.unengagedFriendlyInfantryInside(game, player, building)) {
            own.add(new Row(RowKind.UNIT, unitLine(unit, building), unit));
        }
        if (InfantryActionStrengths.hasCrewToDefend(building)) {
            own.add(new Row(RowKind.TEXT, Messages.getString("InfantryActionDeclarationDialog.crewState",
                  building.getCommittedCrew(), building.getCrew().getCurrentSize())));
            own.add(new Row(RowKind.CREW, Messages.getString("InfantryActionDeclarationDialog.commitCrew")));
            own.add(new Row(RowKind.CREW_EFFECT, ""));
        }
        own.add(new Row(RowKind.OWN_TOTAL, ""));
        if (InfantryActionStrengths.canWithdrawDefence(game, player, building)) {
            own.add(new Row(RowKind.WITHDRAW, Messages.getString("InfantryActionDeclarationDialog.withdrawDefence")));
            own.add(new Row(RowKind.TEXT,
                  Messages.getString("InfantryActionDeclarationDialog.withdrawDefenceExplained")));
        }
        List<Row> against = new ArrayList<>();
        against.add(new Row(RowKind.HEADING, Messages.getString("InfantryActionDeclarationDialog.against")));
        double attackers = 0;
        for (Entity attacker : InfantryActionStrengths.engaged(game, building, true)) {
            against.add(new Row(RowKind.TEXT, unitLine(attacker, null)));
            attackers += InfantryActionStrengths.points(attacker, null);
        }
        for (Infantry enemy : InfantryActionStrengths.enemyInfantryInside(game, player, building)) {
            if (enemy.getInfantryCombatTargetId() == Entity.NONE) {
                against.add(new Row(RowKind.TEXT, Messages.getString("InfantryActionDeclarationDialog.couldAttack",
                      enemy.getDisplayName(), number(InfantryActionStrengths.points(enemy, null)))));
                attackers += InfantryActionStrengths.points(enemy, null);
            }
        }
        against.add(new Row(RowKind.TEXT, Messages.getString("InfantryActionDeclarationDialog.attackerTotal",
              number(attackers), InfantryActionStrengths.roundedUp(attackers))));
        return new Content(own, against);
    }

    // ---------------------------------------------------------------- shared rows

    private void addRows(JPanel column, List<Row> rows) {
        for (Row row : rows) {
            switch (row.kind()) {
                case HEADING -> addHeading(column, row.text());
                case TEXT -> addText(column, row.text());
                case UNIT -> addUnitBox(column, row);
                case CREW -> addCrewSpinner(column, row.text());
                case WITHDRAW -> {
                    withdrawBox = new JCheckBox(row.text());
                    withdrawBox.addActionListener(event -> refreshTotals());
                    column.add(withdrawBox, rowConstraints());
                }
                case OWN_TOTAL -> ownTotal = addText(column, "");
                case CREW_EFFECT -> crewEffect = addText(column, "");
            }
        }
    }

    private void addUnitBox(JPanel column, Row row) {
        JCheckBox box = new JCheckBox(row.text(), true);
        box.addActionListener(event -> refreshTotals());
        unitBoxes.add(box);
        offeredUnits.add(row.unit());
        column.add(box, rowConstraints());
    }

    private void addCrewSpinner(JPanel column, String label) {
        int available = InfantryActionStrengths.crewAvailableToCommit(building);
        JPanel spinnerRow = new JPanel(new GridBagLayout());
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.insets = new Insets(0, 0, 0, UIUtil.scaleForGUI(6));
        spinnerRow.add(new JLabel(label), labelConstraints);
        crewSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Math.max(0, available), 1));
        crewSpinner.setEnabled(available > 0);
        crewSpinner.addChangeListener(event -> refreshTotals());
        spinnerRow.add(crewSpinner, new GridBagConstraints());
        column.add(spinnerRow, rowConstraints());
    }

    private void refreshTotals() {
        double units = 0;
        for (Infantry unit : getCommittedUnits()) {
            units += InfantryActionStrengths.points(unit, defends ? building : null);
        }
        for (Entity engaged : InfantryActionStrengths.engaged(game, building, !defends)) {
            boolean own = (engaged.getOwnerId() == player.getId()) && (engaged != building);
            if (own) {
                units += InfantryActionStrengths.points(engaged, defends ? building : null);
            }
        }
        boolean withdrawing = (withdrawBox != null) && withdrawBox.isSelected();
        for (JCheckBox box : unitBoxes) {
            box.setEnabled(!withdrawing);
        }
        if (defends && (crewSpinner != null)) {
            crewSpinner.setEnabled(!withdrawing && (InfantryActionStrengths.crewAvailableToCommit(building) > 0));
            int extra = (Integer) crewSpinner.getValue();
            double crewPoints = InfantryActionStrengths.crewPointsIfCommitted(building,
                  building.getCommittedCrew() + extra);
            int hits = InfantryActionStrengths.crewHitsIfCommitted(building, extra);
            crewEffect.setText(Messages.getString("InfantryActionDeclarationDialog.crewEffect", extra,
                  number(crewPoints), hits));
            units += crewPoints;
        }
        ownTotal.setText(Messages.getString(withdrawing ? "InfantryActionDeclarationDialog.withdrawing"
              : "InfantryActionDeclarationDialog.ownTotal", number(units), InfantryActionStrengths.roundedUp(units)));
    }

    private String unitLine(Entity unit, AbstractBuildingEntity defended) {
        return Messages.getString("InfantryActionDeclarationDialog.unitPoints", unit.getDisplayName(),
              number(InfantryActionStrengths.points(unit, defended)));
    }

    private static GridBagConstraints rowConstraints() {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = GridBagConstraints.RELATIVE;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(UIUtil.scaleForGUI(2), 0, UIUtil.scaleForGUI(2), 0);
        return constraints;
    }

    private static void addHeading(JPanel column, String text) {
        JLabel heading = new JLabel("<html><b>" + text + "</b></html>");
        GridBagConstraints constraints = rowConstraints();
        constraints.insets = new Insets(UIUtil.scaleForGUI(8), 0, UIUtil.scaleForGUI(2), 0);
        column.add(heading, constraints);
    }

    /** A line of text that wraps to whatever width the column has, so the dialog can be any size. */
    private static JTextArea addText(JPanel column, String text) {
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(null);
        area.setFont(UIManager.getFont("Label.font"));
        area.setForeground(UIManager.getColor("Label.foreground"));
        column.add(area, rowConstraints());
        return area;
    }

    /**
     * The scroll pane's view, which takes the viewport's width rather than its own preferred width. A plain panel
     * inside a scroll pane is laid out as wide as its longest line, so the text areas wrap at a width the dialog
     * cannot show and their ends are cut off; tracking the viewport width makes them wrap at the dialog's edge.
     * Height stays free, so the dialog still scrolls vertically when it is short.
     */
    private static class WidthTrackingPanel extends JPanel implements Scrollable {
        @Serial
        private static final long serialVersionUID = 6098141276423589341L;

        private static final int SCROLL_UNIT_INCREMENT = 16;

        WidthTrackingPanel(LayoutManager layoutManager) {
            super(layoutManager);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return SCROLL_UNIT_INCREMENT;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return (orientation == SwingConstants.VERTICAL) ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** A Marine Points figure: whole numbers plain, fractions to two places. */
    private static String number(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------------- results

    /**
     * @return the units the player ticked
     */
    public List<Infantry> getCommittedUnits() {
        List<Infantry> committed = new ArrayList<>();
        for (int index = 0; index < unitBoxes.size(); index++) {
            if (unitBoxes.get(index).isSelected()) {
                committed.add(offeredUnits.get(index));
            }
        }
        return committed;
    }

    /**
     * @return the declaration the player made, for the server
     */
    public InfantryActionDeclaration getDeclaration() {
        List<Integer> unitIds = getCommittedUnits().stream().map(Entity::getId).toList();
        boolean withdrawing = (withdrawBox != null) && withdrawBox.isSelected();
        if (defends) {
            int crew = (crewSpinner == null) ? 0 : (Integer) crewSpinner.getValue();
            return InfantryActionDeclaration.defending(player.getId(), building.getId(), unitIds, crew, withdrawing);
        }
        return InfantryActionDeclaration.attacking(player.getId(), building.getId(),
              withdrawing ? List.of() : unitIds, withdrawing);
    }

    /**
     * @return {@code true} when the declaration commits something or withdraws, so there is something to send
     */
    public boolean declaresAnything() {
        InfantryActionDeclaration declaration = getDeclaration();
        return declaration.withdraw() || !declaration.committedUnitIds().isEmpty()
              || (declaration.committedCrew() > 0);
    }

    // ---------------------------------------------------------------- native form

    /**
     * Showing the dialog asks in the client's native battle window instead when that draws dialogs: the side faced and
     * the player's side as text, then the units to commit, the crew and the withdrawal. Each change answers the form
     * at once and it is asked again with the totals the change makes, as they follow it here. Ok declares as here;
     * Cancel and Esc, like the close box, do not.
     */
    @Override
    public void setVisible(boolean visible) {
        if (!visible || !answeredNatively()) {
            super.setVisible(visible);
        }
    }

    private boolean answeredNatively() {
        ClientGUI gui = ClientGUI.forFrame(getFrame());
        if (gui == null) {
            return false;
        }
        List<String> buttons = List.of(resources.getString("Ok.text"), resources.getString("Cancel.text"));
        while (true) {
            DialogAnswer answer = gui.askForm(nativeMessage(), getTitle(), nativeFields(), buttons, 1);
            if (answer == null) {
                return false;
            }
            choose(answer.values());
            if (answer.button() != DialogAnswer.CHANGED) {
                if (answer.button() == 0) {
                    setResult(DialogResult.CONFIRMED);
                }
                return true;
            }
        }
    }

    /**
     * @return the native form's text: the side faced, then the player's side with its totals as the choices make them
     */
    private String nativeMessage() {
        StringJoiner text = new StringJoiner("\n");
        content.against().forEach(row -> text.add(row.text()));
        text.add("");
        for (Row row : content.own()) {
            switch (row.kind()) {
                case HEADING, TEXT -> text.add(row.text());
                case OWN_TOTAL -> text.add(ownTotal.getText());
                case CREW_EFFECT -> text.add(crewEffect.getText());
                default -> {
                    // the choices are the form's fields
                }
            }
        }
        return text.toString();
    }

    /**
     * @return the native form's fields as the player left them: each unit to commit, the crew and the withdrawal, each
     *       answering the form when it changes
     */
    private List<DialogField> nativeFields() {
        List<DialogField> fields = new ArrayList<>();
        int unit = 0;
        for (Row row : content.own()) {
            switch (row.kind()) {
                case UNIT -> fields.add(new DialogField(row.text(), FieldKind.CHECKBOX, List.of(), 0, 0,
                      Boolean.toString(unitBoxes.get(unit++).isSelected()), true));
                case CREW -> fields.add(new DialogField(row.text(), FieldKind.CHOICE, crewChoices(), 0, 0,
                      crewSpinner.getValue().toString(), true));
                case WITHDRAW -> fields.add(new DialogField(row.text(), FieldKind.CHECKBOX, List.of(), 0, 0,
                      Boolean.toString(withdrawBox.isSelected()), true));
                default -> {
                    // text rows are the form's message
                }
            }
        }
        return fields;
    }

    /**
     * @return the crew numbers the spinner allows, from none to every crew member available
     */
    private List<String> crewChoices() {
        int most = (Integer) ((SpinnerNumberModel) crewSpinner.getModel()).getMaximum();
        return IntStream.rangeClosed(0, most).mapToObj(Integer::toString).toList();
    }

    /**
     * Sets the controls as the native form left them, one value per field in order, and refreshes the totals; values
     * that do not fit the fields (no answer to a change) change nothing.
     */
    private void choose(List<String> values) {
        if (values.size() != nativeFields().size()) {
            return;
        }
        int field = 0;
        int unit = 0;
        for (Row row : content.own()) {
            switch (row.kind()) {
                case UNIT -> unitBoxes.get(unit++).setSelected(Boolean.parseBoolean(values.get(field++)));
                case CREW -> {
                    int crew = crewChoices().indexOf(values.get(field++));
                    if (crew >= 0) {
                        crewSpinner.setValue(crew);
                    }
                }
                case WITHDRAW -> withdrawBox.setSelected(Boolean.parseBoolean(values.get(field++)));
                default -> {
                    // text rows hold no value
                }
            }
        }
        refreshTotals();
    }
}
