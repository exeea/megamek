/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.clientGUI;

import java.awt.BorderLayout;
import java.awt.event.ActionListener;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import jakarta.annotation.Nullable;
import megamek.client.SBFClient;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.spriteHandler.BoardViewSpriteHandler;
import megamek.client.ui.clientGUI.boardview.spriteHandler.MovePathSpriteHandler;
import megamek.client.ui.clientGUI.boardview.spriteHandler.MovementEnvelopeSpriteHandler;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.panels.ReceivingGameDataPanel;
import megamek.client.ui.panels.StartingScenarioPanel;
import megamek.client.ui.panels.WaitingForServerPanel;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.panels.phaseDisplay.SBFFiringDisplay;
import megamek.client.ui.panels.phaseDisplay.SBFMovementDisplay;
import megamek.client.ui.panels.phaseDisplay.SBFReportDisplay;
import megamek.client.ui.panels.phaseDisplay.StatusBarPhaseDisplay;
import megamek.client.ui.util.MegaMekController;
import megamek.common.KeyBindParser;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameListener;
import megamek.common.game.Game;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.util.Distractable;
import megamek.logging.MMLogger;

public class SBFClientGUI extends AbstractClientGUI {
    private static final MMLogger logger = MMLogger.create(SBFClientGUI.class);

    private final SBFClient client;
    private final MegaMekController controller;
    private final Map<GamePhase, JComponent> phaseComponents = new EnumMap<>(GamePhase.class);
    private final GameListener gameListener = new SBFClientGUIGameListener(this);
    protected final CommonMenuBar menuBar = CommonMenuBar.getMenuBarForGame();
    protected final Game bvGame = new Game();

    protected JComponent curPanel;
    private SBFReportDisplay reportDisplay;
    private boolean boardWindowOpened;
    private MovementEnvelopeSpriteHandler movementEnvelopeHandler;
    private MovePathSpriteHandler movePathSpriteHandler;

    public SBFClientGUI(SBFClient client, MegaMekController megaMekController) {
        super(client);
        this.client = client;
        controller = megaMekController;
        frame.getContentPane().setLayout(new BorderLayout());
        frame.setJMenuBar(menuBar);
        frame.setTitle(client.getName() + Messages.getString("ClientGUI.clientTitleSuffix"));
    }

    @Override
    public void initialize() {
        super.initialize();
        try {
            client.getGame().addGameListener(gameListener);
            boardStates.put(0, new BoardClientState(bvGame, MegaMekGUI.getKeyDispatcher(), null, 0, null));
            initializeSpriteHandlers();
        } catch (Exception ex) {
            logger.fatal(ex, "");
            die();
        }

        client.changePhase(GamePhase.UNKNOWN);
        frame.setVisible(true);
    }

    private void initializeSpriteHandlers() {
        movementEnvelopeHandler = new MovementEnvelopeSpriteHandler(this, client.getGame());
        movePathSpriteHandler = new MovePathSpriteHandler(this);
        spriteHandlers.addAll(List.of(movementEnvelopeHandler, movePathSpriteHandler));
        spriteHandlers.forEach(BoardViewSpriteHandler::initialize);
    }

    @Override
    public SBFClient getClient() {
        return client;
    }

    @Override
    public JComponent turnTimerComponent() {
        return menuBar;
    }

    @Override
    public void setChatBoxActive(boolean active) {
        // TODO
    }

    @Override
    public void clearChatBox() {
        // TODO
    }

    @Override
    protected boolean saveGame() {
        // TODO
        // client.setAwaitingSave(true) // necessary to avoid exit/save race condition
        return true;
    }

    @Override
    public void die() {
        // Will prevent race condition once saveGame() is implemented
        if (client.isAwaitingSave()) {
            SwingUtilities.invokeLater(this::die);
            return;
        }
        client.getGame().removeGameListener(gameListener);
        GpuBoardWindow.closeFor(this);
        phaseComponents.values().stream().distinct().filter(StatusBarPhaseDisplay.class::isInstance)
              .map(StatusBarPhaseDisplay.class::cast).forEach(display -> {
                  display.setIgnoringEvents(true);
                  display.stopTimer();
                  display.removeAllListeners();
                  GUIP.removePreferenceChangeListener(display);
                  KeyBindParser.removePreferenceChangeListener(display);
              });
        boardStates.values().forEach(BoardClientState::close);
        boardStates.clear();
        phaseComponents.clear();
        if (controller != null && controller.clientGUI == this) {
            controller.removeAllActions();
            controller.clientGUI = null;
        }
        menuBar.die();
        client.die();
        super.die();
    }

    protected void switchPanel(GamePhase phase) {
        // Clear the old panel's listeners.

        if (curPanel instanceof ActionListener listener) {
            menuBar.removeActionListener(listener);
        }

        if (curPanel instanceof Distractable) {
            ((Distractable) curPanel).setIgnoringEvents(true);
        }

        if (curPanel != null) {
            frame.getContentPane().remove(curPanel);
        }
        curPanel = phaseComponents.computeIfAbsent(phase, this::createPhasePanel);
        frame.getContentPane().add(curPanel, BorderLayout.CENTER);

        if (curPanel instanceof ActionListener listener) {
            menuBar.addActionListener(listener);
        }

        if (curPanel instanceof Distractable) {
            ((Distractable) curPanel).setIgnoringEvents(false);
        }

        frame.getContentPane().validate();
        openBoardWindow();
    }

    /** Opens the native board when the scenario and its map have arrived. */
    void openBoardWindow() {
        GamePhase phase = client.getGame().getPhase();
        if (!boardWindowOpened && !phase.isUnknown() && !phase.isLounge()
              && (bvGame.getBoard(0).getWidth() > 0)) {
            boardWindowOpened = true;
            GpuBoardWindow.open(this, getBoardState(0), () -> curPanel);
        }
    }

    private JComponent createPhasePanel(GamePhase phase) {
        if (phase.isReport()) {
            if (reportDisplay == null) {
                reportDisplay = new SBFReportDisplay(this);
            }
            return reportDisplay;
        }
        return switch (phase) {
            case MOVEMENT -> new SBFMovementDisplay(this);
            case FIRING -> new SBFFiringDisplay(this);
            case STARTING_SCENARIO -> new StartingScenarioPanel();
            case EXCHANGE -> new ReceivingGameDataPanel();
            default -> new WaitingForServerPanel();
        };
    }

    public void selectForAction(@Nullable SBFFormation formation) {
        if (GUIP.getAutoCenter() && (formation != null) && (formation.getPosition() != null)) {
            BoardClientState state = getBoardState(formation.getPosition().boardId());
            if (state != null) {
                state.centerOnHex(formation.getPosition().coords());
            }
        }
    }

    /**
     * Shows the movement envelope in the BoardView for the given entity. The movement envelope data is a map of move
     * end Coords to movement points used.
     *
     * @param formation The entity for which the movement envelope is
     * @param mvEnvData The movement envelope data
     */
    public void showMovementEnvelope(SBFFormation formation, Map<Coords, Integer> mvEnvData) {
        movementEnvelopeHandler.setMovementEnvelope(mvEnvData, 0, formation.getMovement(),
              formation.getMovement(), formation.getMovement(), MovementDisplay.GEAR_JUMP);
    }

    public void clearMovementEnvelope() {
        movementEnvelopeHandler.clear();
    }

    /**
     * On the EDT, asks for one of {@code rows} in the 3D board window and waits for the answer, as a
     * modal dialog does. The first enabled row is highlighted.
     *
     * @return the chosen row; null when cancelled or when no board window asks it
     */
    public @Nullable Integer askChoice(String title, String message, List<DialogRow> rows) {
        List<Integer> first = java.util.stream.IntStream.range(0, rows.size()).filter(row -> rows.get(row).enabled())
              .limit(1).boxed().toList();
        DialogRequest request = new DialogRequest(0, DialogKind.CHOICE, title, message, false,
              List.of(Messages.getString("Okay"), Messages.getString("Cancel")), 0, 1, rows, first, "", false,
              null, null,
              null, List.of());
        DialogAnswer answer = GpuBoardWindow.route(this, request);
        return (answer == null) || (answer.button() != 0) || answer.selected().isEmpty() ? null
              : answer.selected().getFirst();
    }

    public void showMovePath(@Nullable SBFMovePath movePath) {
        movePathSpriteHandler.update(movePath);
    }

}
