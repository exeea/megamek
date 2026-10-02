/*
 * Copyright (C) 2025 The MegaMek Team. All Rights Reserved.
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

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import javax.swing.*;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.util.UIUtil;
import megamek.common.annotations.Nullable;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * Dialog for managing Nova CEWS networks during End Phase. Per IO: Alternate Eras p.60: "A unit wishing to link with
 * another unit must declare the connection in the End Phase. Beginning in the next turn, the two units are linked and
 * operate per the rules for C3i."
 *
 * @author MegaMek Team (with Claude Code assistance)
 */
public class NovaNetworkDialog extends JDialog implements ActionListener {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final MMLogger logger = MMLogger.create(NovaNetworkDialog.class);
    private static final int PADDING = UIUtil.scaleForGUI(10);
    private static final int PADDING_SMALL = UIUtil.scaleForGUI(5);

    private final ClientGUI clientGUI;
    private final Game game;
    private final int localPlayerId;

    // UI Components
    private JList<String> unitList;
    private DefaultListModel<String> unitListModel;
    private JTextArea pendingChangesArea;
    private JButton btnLink;
    private JButton btnUnlink;
    private JButton btnApply;
    private JButton btnRevert;
    private JButton btnCancel;

    // Data structures
    private List<Entity> playerNovaUnits;
    private List<Entity> alliedNovaUnits;
    private Map<Integer, Entity> entityMap; // Index to Entity mapping
    private Map<Integer, String> pendingChanges = new HashMap<>(); // Entity ID -> target network ID

    public NovaNetworkDialog(JFrame parent, ClientGUI clientGUI) {
        super(parent, Messages.getString("NovaNetworkDialog.title"), true);
        logger.debug("Opening Nova CEWS Network Management Dialog");
        this.clientGUI = clientGUI;
        this.game = clientGUI.getClient().getGame();
        this.localPlayerId = clientGUI.getClient().getLocalPlayer().getId();

        this.entityMap = new HashMap<>();

        initializeData();
        initializeUI();
        updatePendingChanges();

        pack();
        setLocationRelativeTo(parent);

        // Add window listener to clear highlighting when dialog closes
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                clearHighlighting();
            }
        });

        logger.debug("Nova CEWS Dialog initialized: {} player units, {} allied units",
              playerNovaUnits.size(), alliedNovaUnits.size());
    }

    /**
     * Initializes the data structures with player's and allied Nova CEWS units.
     */
    private void initializeData() {
        playerNovaUnits = new ArrayList<>();
        alliedNovaUnits = new ArrayList<>();

        for (Entity entity : game.getEntitiesVector()) {
            // A Nova switched to "Off" cannot be reconfigured, so it is not offered for selection (it keeps its
            // network membership and rejoins when switched back on)
            if (!entity.hasActiveNovaCEWS()) {
                continue;
            }

            if (entity.getOwnerId() == localPlayerId) {
                playerNovaUnits.add(entity);
            } else if (!entity.getOwner().isEnemyOf(game.getPlayer(localPlayerId))) {
                // Allied units (can link with teammates per /nova command)
                alliedNovaUnits.add(entity);
            }
        }
    }

    /**
     * Initializes the UI components.
     */
    private void initializeUI() {
        setLayout(new BorderLayout(PADDING, PADDING));
        setResizable(false);

        // Main panel with padding
        JPanel mainPanel = new JPanel(new BorderLayout(PADDING, PADDING));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING));

        // Top: Instructions
        JLabel instructions = new JLabel(Messages.getString("NovaNetworkDialog.instructions"));
        instructions.setBorder(BorderFactory.createEmptyBorder(0, 0, PADDING, 0));
        mainPanel.add(instructions, BorderLayout.NORTH);

        // Center: Unit list
        JPanel centerPanel = new JPanel(new BorderLayout(PADDING_SMALL, PADDING_SMALL));

        JLabel unitListLabel = new JLabel(Messages.getString("NovaNetworkDialog.unitListLabel"));
        centerPanel.add(unitListLabel, BorderLayout.NORTH);

        unitListModel = new DefaultListModel<>();
        populateUnitList();

        unitList = new JList<>(unitListModel);
        unitList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        unitList.setVisibleRowCount(10);
        unitList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateEntityHighlighting();
            }
        });

        JScrollPane scrollPane = new JScrollPane(unitList);
        scrollPane.setPreferredSize(UIUtil.scaleForGUI(500, 200));
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        mainPanel.add(centerPanel, BorderLayout.CENTER);

        // Bottom: Pending changes and buttons
        JPanel bottomPanel = new JPanel(new BorderLayout(PADDING_SMALL, PADDING_SMALL));

        // Pending changes area
        JLabel pendingLabel = new JLabel(Messages.getString("NovaNetworkDialog.pendingLabel"));
        bottomPanel.add(pendingLabel, BorderLayout.NORTH);

        pendingChangesArea = new JTextArea(4, 50);
        pendingChangesArea.setEditable(false);
        pendingChangesArea.setLineWrap(true);
        pendingChangesArea.setWrapStyleWord(true);
        pendingChangesArea.setBackground(getBackground());

        JScrollPane pendingScrollPane = new JScrollPane(pendingChangesArea);
        bottomPanel.add(pendingScrollPane, BorderLayout.CENTER);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, PADDING, PADDING));

        btnLink = new JButton(Messages.getString("NovaNetworkDialog.btnLink"));
        btnLink.addActionListener(this);
        btnLink.setToolTipText(Messages.getString("NovaNetworkDialog.btnLink.tooltip"));

        btnUnlink = new JButton(Messages.getString("NovaNetworkDialog.btnUnlink"));
        btnUnlink.addActionListener(this);
        btnUnlink.setToolTipText(Messages.getString("NovaNetworkDialog.btnUnlink.tooltip"));

        btnApply = new JButton(Messages.getString("NovaNetworkDialog.btnApply"));
        btnApply.addActionListener(this);
        btnApply.setToolTipText(Messages.getString("NovaNetworkDialog.btnApply.tooltip"));

        btnRevert = new JButton(Messages.getString("NovaNetworkDialog.btnRevert"));
        btnRevert.addActionListener(this);
        btnRevert.setToolTipText(Messages.getString("NovaNetworkDialog.btnRevert.tooltip"));

        btnCancel = new JButton(Messages.getString("NovaNetworkDialog.btnCancel"));
        btnCancel.addActionListener(this);

        buttonPanel.add(btnLink);
        buttonPanel.add(btnUnlink);
        buttonPanel.add(btnApply);
        buttonPanel.add(btnRevert);
        buttonPanel.add(btnCancel);

        bottomPanel.add(buttonPanel, BorderLayout.SOUTH);

        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        add(mainPanel);
    }

    /**
     * Populates the unit list with player's and allied Nova CEWS units.
     */
    private void populateUnitList() {
        unitListModel.clear();
        entityMap.clear();

        List<UnitRow> rows = unitRows();
        for (int index = 0; index < rows.size(); index++) {
            unitListModel.addElement(rows.get(index).text());
            entityMap.put(index, rows.get(index).entity());
        }
    }

    /**
     * One row of the unit list: a unit, or a section header or spacer without one.
     *
     * @param text   what the row shows
     * @param entity the unit, or null
     */
    private record UnitRow(String text, @Nullable Entity entity) {}

    /**
     * @return the unit list's rows, here and in the native battle window: the player's units under their header, then
     *       the allied units under theirs
     */
    private List<UnitRow> unitRows() {
        List<UnitRow> rows = new ArrayList<>();

        // Add player's units
        if (!playerNovaUnits.isEmpty()) {
            rows.add(new UnitRow(Messages.getString("NovaNetworkDialog.sectionHeader",
                  Messages.getString("NovaNetworkDialog.yourUnits")), null)); // Header, no entity

            for (Entity entity : playerNovaUnits) {
                rows.add(new UnitRow(formatEntityDisplay(entity), entity));
            }
        }

        // Add allied units
        if (!alliedNovaUnits.isEmpty()) {
            if (!playerNovaUnits.isEmpty()) {
                rows.add(new UnitRow("", null)); // Spacer
            }

            rows.add(new UnitRow(Messages.getString("NovaNetworkDialog.sectionHeader",
                  Messages.getString("NovaNetworkDialog.alliedUnits")), null)); // Header, no entity

            for (Entity entity : alliedNovaUnits) {
                rows.add(new UnitRow(formatEntityDisplay(entity), entity));
            }
        }
        return rows;
    }

    /**
     * Formats an entity for display in the list.
     */
    private String formatEntityDisplay(Entity entity) {
        StringBuilder entityDisplayText = new StringBuilder();

        // ID and name
        entityDisplayText.append(Messages.getString("NovaNetworkDialog.entityIdFormat",
              entity.getId(), entity.getShortName()));

        // Current network
        String currentNetwork = entity.getC3NetId();
        String originalNetwork = entity.getOriginalNovaC3NetId();
        String pendingNetwork = entity.getNewRoundNovaNetworkString();

        logger.debug("Entity {}: currentNetwork={}, originalNetwork={}, pendingNetwork={}, isNetworked={}",
              entity.getId(), currentNetwork, originalNetwork, pendingNetwork, isEntityNetworked(entity));

        if (isEntityNetworked(entity)) {
            int networkSize = getNetworkSize(currentNetwork);
            String networkMembers = getNetworkMembersDisplay(entity, currentNetwork);
            int freeNodes = 3 - networkSize;
            String availability = (freeNodes == 0)
                  ? Messages.getString("NovaNetworkDialog.networkFull")
                  : Messages.getString("NovaNetworkDialog.networkAvailable");

            entityDisplayText.append(Messages.getString("NovaNetworkDialog.networkStatus",
                  networkMembers, networkSize, availability));
        } else {
            entityDisplayText.append(Messages.getString("NovaNetworkDialog.unlinked"));
        }

        // Show if pending change
        if (pendingNetwork != null && !pendingNetwork.equals(currentNetwork)) {
            String pendingDisplay;
            if (pendingNetwork.equals(entity.getOriginalNovaC3NetId())) {
                pendingDisplay = Messages.getString("NovaNetworkDialog.pendingUnlinked");
            } else {
                pendingDisplay = getNetworkMembersDisplay(entity, pendingNetwork);
            }
            entityDisplayText.append(Messages.getString("NovaNetworkDialog.pendingChange", pendingDisplay));
        }

        // Owner info for allied units
        if (entity.getOwnerId() != localPlayerId) {
            entityDisplayText.append(Messages.getString("NovaNetworkDialog.ownerSuffix",
                  game.getPlayer(entity.getOwnerId()).getName()));
        }

        return entityDisplayText.toString();
    }

    /**
     * Checks if an entity is currently networked with other units. An entity is considered networked if at least one
     * other Nova CEWS unit shares its network ID.
     */
    private boolean isEntityNetworked(Entity entity) {
        String networkId = entity.getC3NetId();
        if (networkId == null) {
            return false;
        }

        // Entity is networked if at least one other unit shares this network ID
        return game.getEntitiesVector().stream()
              .filter(Entity::hasActiveNovaCEWS)
              .filter(e -> e.getId() != entity.getId())
              .anyMatch(e -> networkId.equals(e.getC3NetId()));
    }

    /**
     * Gets the number of units in a network.
     */
    private int getNetworkSize(String networkId) {
        return (int) game.getEntitiesVector().stream()
              .filter(Entity::hasActiveNovaCEWS)
              .filter(e -> networkId.equals(e.getC3NetId()))
              .count();
    }

    /**
     * Gets a human-readable display of network members (excluding the current entity). Returns a comma-separated list
     * of IDs like "ID2, ID3" or "No other members".
     */
    private String getNetworkMembersDisplay(Entity currentEntity, String networkId) {
        if (networkId == null) {
            return Messages.getString("NovaNetworkDialog.unknownNetwork");
        }

        // Find all OTHER entities in the same network
        List<String> memberIds = new ArrayList<>();
        for (Entity entity : game.getEntitiesVector()) {
            // Skip self
            if (entity.getId() == currentEntity.getId()) {
                continue;
            }
            // Check if in same network
            if (entity.hasNovaCEWS() && networkId.equals(entity.getC3NetId())) {
                memberIds.add(Messages.getString("NovaNetworkDialog.memberId", entity.getId()));
            }
        }

        if (memberIds.isEmpty()) {
            return Messages.getString("NovaNetworkDialog.noOtherMembers");
        } else {
            return String.join(", ", memberIds);
        }
    }

    /**
     * Updates the pending changes display area.
     */
    private void updatePendingChanges() {
        pendingChangesArea.setText(pendingText());
    }

    /**
     * @return the pending changes, one per line, or the text saying there are none
     */
    private String pendingText() {
        StringBuilder pendingChangesText = new StringBuilder();
        boolean hasPending = false;

        for (Map.Entry<Integer, String> entry : pendingChanges.entrySet()) {
            Entity entity = game.getEntity(entry.getKey());
            if (entity != null) {
                String currentNetwork = entity.getC3NetId();
                String targetNetwork = entry.getValue();

                if (!currentNetwork.equals(targetNetwork)) {
                    hasPending = true;
                    pendingChangesText.append(Messages.getString("NovaNetworkDialog.pendingChangeFormat",
                          entity.getShortName(),
                          getNetworkDisplayName(currentNetwork),
                          getNetworkDisplayName(targetNetwork)));
                    pendingChangesText.append("\n");
                }
            }
        }

        return hasPending ? pendingChangesText.toString() : Messages.getString("NovaNetworkDialog.noPendingChanges");
    }

    /**
     * Gets a user-friendly display name for a network ID.
     *
     * @param networkId The network ID (e.g., "C3Nova.5")
     *
     * @return User-friendly display name
     */
    private String getNetworkDisplayName(String networkId) {
        if (networkId.contains(".")) {
            String idPart = networkId.substring(networkId.indexOf('.') + 1);
            try {
                int entityId = Integer.parseInt(idPart);
                Entity entity = game.getEntity(entityId);
                if (entity != null) {
                    return Messages.getString("NovaNetworkDialog.networkOf", entity.getShortName());
                }
            } catch (NumberFormatException ignored) {
                // Fall through to default
            }
        }
        return networkId;
    }

    /**
     * Handles button clicks.
     */
    @Override
    public void actionPerformed(ActionEvent actionEvent) {
        if (actionEvent.getSource() == btnLink) {
            linkSelectedUnits();
        } else if (actionEvent.getSource() == btnUnlink) {
            unlinkSelectedUnits();
        } else if (actionEvent.getSource() == btnApply) {
            applyPendingChanges();
        } else if (actionEvent.getSource() == btnRevert) {
            revertPendingChanges();
        } else if (actionEvent.getSource() == btnCancel) {
            if (!pendingChanges.isEmpty()) {
                int result = JOptionPane.showConfirmDialog(this,
                      Messages.getString("NovaNetworkDialog.discardChanges"),
                      Messages.getString("NovaNetworkDialog.title"),
                      JOptionPane.YES_NO_OPTION,
                      JOptionPane.WARNING_MESSAGE);

                if (result != JOptionPane.YES_OPTION) {
                    return;  // Don't close if user cancels
                }
            }

            pendingChanges.clear();
            clearHighlighting();
            dispose();
        }
    }

    /**
     * Links the selected units into a network.
     */
    private void linkSelectedUnits() {
        show(link(getSelectedEntities()));
    }

    /**
     * Queues the link of the given units into one network, here or in the native battle window.
     *
     * @return null when the link was queued, otherwise what tells the player why not
     */
    private @Nullable Notice link(List<Entity> selectedEntities) {
        logger.debug("Link action: {} units selected", selectedEntities.size());

        if (selectedEntities.isEmpty()) {
            logger.warn("Link action failed: No units selected");
            return new Notice(Messages.getString("NovaNetworkDialog.error.noSelection"), true);
        }

        // Validate: can only link units owned by local player
        boolean canModify = selectedEntities.stream().anyMatch(e -> e.getOwnerId() == localPlayerId);
        if (!canModify) {
            logger.warn("Link action failed: No units owned by local player");
            return new Notice(Messages.getString("NovaNetworkDialog.error.notYourUnits"), true);
        }

        // Determine target network based on selection:
        // - If ALL selected units share the same network (all networked, same network): Keep that network
        // - Otherwise (mixed networked/unlinked, different networks, all unlinked): Create new network
        logger.debug("Link action: Analyzing {} selected units", selectedEntities.size());

        String targetNetworkId;
        List<String> existingNetworks = selectedEntities.stream()
              .filter(this::isEntityNetworked)
              .map(Entity::getC3NetId)
              .distinct()
              .collect(Collectors.toList());

        long networkedCount = selectedEntities.stream()
              .filter(this::isEntityNetworked)
              .count();

        logger.debug("Selected units breakdown: {} networked, {} unlinked",
              networkedCount, selectedEntities.size() - networkedCount);
        logger.debug("Unique networks in selection: {}", existingNetworks);

        // Check if ALL selected units are networked AND in the same network
        if ((networkedCount == selectedEntities.size()) && (existingNetworks.size() == 1)) {
            // All selected units are in the same network - preserve it
            targetNetworkId = existingNetworks.getFirst();
            logger.debug("Decision: Preserving existing network (all units from same network): {}", targetNetworkId);

            // Check if this is a no-op (all units already in target network)
            boolean anyChanges = selectedEntities.stream()
                  .anyMatch(e -> !targetNetworkId.equals(e.getC3NetId()));

            if (!anyChanges) {
                logger.debug("No action needed: All selected units already in network {}", targetNetworkId);
                // Exit without making changes
                return new Notice(Messages.getString("NovaNetworkDialog.info.alreadyNetworked", targetNetworkId),
                      false);
            }
        } else {
            // Mixed selection or different networks - create new network
            // Find a network ID from selected units that won't include unintended units
            targetNetworkId = selectedEntities.stream()
                  .map(Entity::getOriginalNovaC3NetId)
                  .filter(netId -> {
                      // Count how many units (not in selection) currently use this network
                      long othersUsingNetwork = game.getEntitiesVector().stream()
                            .filter(Entity::hasActiveNovaCEWS)
                            .filter(entity -> netId.equals(entity.getC3NetId()))
                            .filter(entity -> selectedEntities.stream()
                                  .noneMatch(sel -> sel.getId() == entity.getId()))
                            .count();
                      return othersUsingNetwork == 0; // No other units using it
                  })
                  .findFirst()
                  .orElse(selectedEntities.getFirst().getOriginalNovaC3NetId()); // Fallback

            logger.debug("Decision: Creating new network from available ID (mixed selection): {}", targetNetworkId);
            logger.debug("Selected network ID {} to avoid including unintended units", targetNetworkId);
        }

        // Calculate resulting network size
        // Start with units already in the target network (excluding selected units that will be moved)
        long existingUnitsInNetwork = game.getEntitiesVector().stream()
              .filter(Entity::hasActiveNovaCEWS)
              .filter(e -> targetNetworkId.equals(e.getC3NetId()))
              .filter(e -> selectedEntities.stream().noneMatch(sel -> sel.getId() == e.getId()))
              .count();

        // Add the selected units
        int resultingNetworkSize = (int) existingUnitsInNetwork + selectedEntities.size();

        logger.debug("Existing units in network: {}, Selected units: {}, Resulting size: {}",
              existingUnitsInNetwork, selectedEntities.size(), resultingNetworkSize);

        // IO: Alternate Eras p.60: "link up to two other units" = max 3 total
        if (resultingNetworkSize > 3) {
            logger.warn("Link action failed: Resulting network would have {} units (max 3)", resultingNetworkSize);
            return new Notice(Messages.getString("NovaNetworkDialog.error.tooManyUnitsResult", resultingNetworkSize),
                  true);
        }

        // Queue link actions for all selected units
        for (Entity entity : selectedEntities) {
            logger.debug("Queuing link for entity {} ({}) to network {}",
                  entity.getId(), entity.getShortName(), targetNetworkId);
            pendingChanges.put(entity.getId(), targetNetworkId);
        }

        logger.debug("Link action completed successfully: {} units now in network", resultingNetworkSize);
        return null;
    }

    /**
     * Unlinks the selected units from their networks.
     */
    private void unlinkSelectedUnits() {
        show(unlink(getSelectedEntities()));
    }

    /**
     * Queues the unlink of the given units, each back to its own network, here or in the native battle window.
     *
     * @return null when the unlink was queued, otherwise what tells the player why not
     */
    private @Nullable Notice unlink(List<Entity> selectedEntities) {
        logger.debug("Unlink action: {} units selected", selectedEntities.size());

        if (selectedEntities.isEmpty()) {
            logger.warn("Unlink action failed: No units selected");
            return new Notice(Messages.getString("NovaNetworkDialog.error.noSelection"), true);
        }

        // Validate: can only modify units owned by local player
        boolean canModify = selectedEntities.stream().anyMatch(e -> e.getOwnerId() == localPlayerId);
        if (!canModify) {
            logger.warn("Unlink action failed: No units owned by local player");
            return new Notice(Messages.getString("NovaNetworkDialog.error.notYourUnits"), true);
        }

        // Queue unlink actions for all selected units
        // Each unit reverts to its own network (C3Nova.X based on unit ID)
        for (Entity entity : selectedEntities) {
            String currentNetworkId = entity.getC3NetId();
            String targetNetworkId = entity.getOriginalNovaC3NetId();

            logger.debug("Queuing unlink for entity {} ({}) from network {} to original network {}",
                  entity.getId(), entity.getShortName(), currentNetworkId, targetNetworkId);

            pendingChanges.put(entity.getId(), targetNetworkId);
        }

        logger.debug("Unlink action completed successfully");
        return null;
    }

    /**
     * What a link or unlink tells the player instead of queuing a change.
     *
     * @param text  the message
     * @param error {@code true} for an error, {@code false} for a piece of information
     */
    private record Notice(String text, boolean error) {
        String title() {
            return Messages.getString(error ? "NovaNetworkDialog.error.title" : "NovaNetworkDialog.info.title");
        }

        int messageType() {
            return error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE;
        }
    }

    /**
     * After a link or unlink here: the refreshed list and pending changes, or the notice why nothing changed.
     */
    private void show(@Nullable Notice notice) {
        if (notice == null) {
            populateUnitList();
            updatePendingChanges();
        } else {
            JOptionPane.showMessageDialog(this, notice.text(), notice.title(), notice.messageType());
        }
    }

    /** {@code true} once Apply sent at least one network change, so the caller can confirm a declaration was made. */
    private boolean applied;

    /**
     * @return {@code true} if Apply sent at least one Nova network change this time the dialog was shown
     */
    public boolean wasApplied() {
        return applied;
    }

    /**
     * Applies all pending network changes by sending them to the server.
     */
    private void applyPendingChanges() {
        if (!sendPendingChanges()) {
            JOptionPane.showMessageDialog(this,
                  Messages.getString("NovaNetworkDialog.noPendingChanges"),
                  Messages.getString("NovaNetworkDialog.title"),
                  JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        clearHighlighting();
        dispose();
    }

    /**
     * Sends every pending network change to the server, here or from the native battle window.
     *
     * @return {@code false} when there was nothing to send
     */
    private boolean sendPendingChanges() {
        if (pendingChanges.isEmpty()) {
            return false;
        }

        // Send all pending changes to server
        for (Map.Entry<Integer, String> entry : pendingChanges.entrySet()) {
            int entityId = entry.getKey();
            String targetNetwork = entry.getValue();
            Entity entity = game.getEntity(entityId);

            if (entity != null) {
                logger.info("Applying network change for entity {} ({}): {} -> {}",
                      entityId, entity.getShortName(),
                      entity.getC3NetId(), targetNetwork);

                entity.setNewRoundNovaNetworkString(targetNetwork);
                clientGUI.getClient().sendNovaChange(entityId, targetNetwork);
                applied = true;
            }
        }

        pendingChanges.clear();
        return true;
    }

    /**
     * Clears all pending network changes without applying them.
     */
    private void revertPendingChanges() {
        pendingChanges.clear();
        populateUnitList();
        updatePendingChanges();
        // Visual feedback in UI is sufficient - no dialog needed
    }

    /**
     * Gets the entities corresponding to the selected list items.
     */
    private List<Entity> getSelectedEntities() {
        int[] selectedIndices = unitList.getSelectedIndices();
        List<Entity> entities = new ArrayList<>();

        for (int index : selectedIndices) {
            Entity entity = entityMap.get(index);
            if (entity != null) {
                entities.add(entity);
            }
        }

        return entities;
    }

    /**
     * Updates the board view to highlight the currently selected entities. Highlights both the entity name tags and hex
     * borders.
     */
    private void updateEntityHighlighting() {
        List<Entity> selectedEntities = getSelectedEntities();
        BoardClientState boardView = clientGUI.getBoardState();

        // Highlight entity name tags
        boardView.highlightSelectedEntities(selectedEntities);

        // Highlight hex borders
        List<Coords> hexesToHighlight = selectedEntities.stream()
              .map(Entity::getPosition)
              .collect(Collectors.toList());
        boardView.setHighlightedEntityHexes(hexesToHighlight);

        boardView.repaint();
    }

    /**
     * Clears all entity highlighting on the board view. Clears both entity name tag highlights and hex border
     * highlights.
     */
    private void clearHighlighting() {
        BoardClientState boardView = clientGUI.getBoardState();

        // Clear entity name tag highlights
        boardView.highlightSelectedEntities(new ArrayList<>());

        // Clear hex border highlights
        boardView.setHighlightedEntityHexes(new ArrayList<>());

        boardView.repaint();
    }

    /**
     * Showing the dialog manages the networks in the client's native battle window instead when that draws dialogs:
     * the units and the pending changes as text, with Link..., Unlink..., Apply Changes, Revert All and Close. Link...
     * and Unlink... ask for the units to act on; a refusal is shown and the same units are asked for again. Apply,
     * Revert and Close act as they do here; Esc, like the close box, closes without asking.
     */
    @Override
    public void setVisible(boolean visible) {
        if (!visible || !managedNatively()) {
            super.setVisible(visible);
        }
    }

    private boolean managedNatively() {
        Object[] actions = { Messages.getString("NovaNetworkDialog.link"),
              Messages.getString("NovaNetworkDialog.unlink"), btnApply.getText(), btnRevert.getText(),
              btnCancel.getText() };
        while (true) {
            Integer action = clientGUI.askNative(overview(), getTitle(), JOptionPane.DEFAULT_OPTION, actions, null,
                  false);
            boolean drawn = (action != null) && ((action < 0) || (action > 1) || pickNatively(action == 0));
            if (!drawn) {
                // The native window stopped drawing dialogs: the Swing dialog goes on from here
                populateUnitList();
                updatePendingChanges();
                return false;
            }
            if (action == 2) {
                if (sendPendingChanges()) {
                    dispose();
                    return true;
                }
                clientGUI.message(Messages.getString("NovaNetworkDialog.noPendingChanges"), getTitle(),
                      JOptionPane.INFORMATION_MESSAGE);
            } else if (action == 3) {
                pendingChanges.clear();
            } else if ((action == 4) || (action < 0)) {
                boolean keep = (action == 4) && !pendingChanges.isEmpty() && (clientGUI.confirm(
                      Messages.getString("NovaNetworkDialog.discardChanges"), getTitle(), JOptionPane.YES_NO_OPTION,
                      JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION);
                if (!keep) {
                    pendingChanges.clear();
                    dispose();
                    return true;
                }
            }
        }
    }

    /**
     * @return what the native form shows above its buttons: the instructions, the unit list and the pending changes
     */
    private String overview() {
        StringJoiner text = new StringJoiner("\n");
        text.add(Messages.getString("NovaNetworkDialog.instructions")).add("");
        text.add(Messages.getString("NovaNetworkDialog.unitListLabel"));
        unitRows().forEach(row -> text.add(row.text()));
        text.add("").add(Messages.getString("NovaNetworkDialog.pendingLabel")).add(pendingText().stripTrailing());
        return text.toString();
    }

    /**
     * Asks for the units to link or unlink and queues the change; a refusal is shown and the same units are asked for
     * again. Cancel or Esc returns to the overview.
     *
     * @return {@code false} when the native window stopped drawing dialogs
     */
    private boolean pickNatively(boolean link) {
        List<Integer> ticked = List.of();
        while (true) {
            List<UnitRow> rows = unitRows();
            DialogAnswer answer = clientGUI.askRows(Messages.getString("NovaNetworkDialog.instructions"), getTitle(),
                  rows.stream().map(row -> new DialogRow(row.text(), "", null, row.entity() != null)).toList(), true,
                  ticked, null, List.of((link ? btnLink : btnUnlink).getText(), Messages.getString("Cancel")), 1);
            if (answer == null) {
                return false;
            }
            if (answer.button() != 0) {
                return true;
            }
            List<Entity> selected = answer.selected().stream().map(row -> rows.get(row).entity()).toList();
            Notice notice = link ? link(selected) : unlink(selected);
            if (notice == null) {
                return true;
            }
            clientGUI.message(notice.text(), notice.title(), notice.messageType());
            ticked = answer.selected();
        }
    }
}
