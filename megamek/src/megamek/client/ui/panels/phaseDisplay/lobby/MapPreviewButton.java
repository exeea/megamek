/*
 * Copyright (C) 2021-2025 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.panels.phaseDisplay.lobby;

import static megamek.client.ui.panels.phaseDisplay.lobby.LobbyUtility.cleanBoardName;
import static megamek.client.ui.panels.phaseDisplay.lobby.LobbyUtility.drawMinimapLabel;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.Serial;
import java.text.ParseException;
import java.util.Objects;
import java.util.TreeSet;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.TransferHandler;

import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.util.ScalingPopup;
import megamek.client.ui.util.UIUtil;
import megamek.common.board.Board;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.loaders.MapSettings;
import megamek.common.util.ImageUtil;
import megamek.logging.MMLogger;

/** A specialized JButton for the map preview panel of the map setup. */
public class MapPreviewButton extends JButton {
    private static final MMLogger logger = MMLogger.create(MapPreviewButton.class);

    @Serial
    private static final long serialVersionUID = -80635203255671654L;

    private final static Color INDEX_COLOR = new Color(100, 100, 100, 180);
    private Dimension currentPreviewSize;
    private Image scaledImage;
    private Image baseImage;
    private final MapSetupDialog lobby;
    private int index;
    private boolean isExample = false;
    private String boardName = "";

    /** A specialized JButton for the map preview panel of the map setup. */
    public MapPreviewButton(MapSetupDialog cl, int nr) {
        super("");
        lobby = cl;
        MapButtonTransferHandler dndHandler = new MapButtonTransferHandler(lobby, this);
        index = nr;
        setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
        setTransferHandler(dndHandler);
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                JButton button = (JButton) e.getSource();
                TransferHandler handle = button.getTransferHandler();
                handle.exportAsDrag(button, e, TransferHandler.COPY);
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        });
        currentPreviewSize = lobby.maxMapButtonSize();
    }

    /**
     * Shows the button's right-click menu (checked on both press and release, as the popup trigger is
     * platform-dependent). Rotation, theme and level offset apply to this board slot.
     */
    private void maybeShowPopup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        createPopup().show(this, event.getX(), event.getY());
    }

    ScalingPopup createPopup() {
        boolean isRotated = boardName.startsWith(Board.BOARD_REQUEST_ROTATION);
        boolean isFixedBoard = !boardName.isBlank()
              && !boardName.startsWith(MapSettings.BOARD_GENERATED)
              && !boardName.startsWith(MapSettings.BOARD_SURPRISE);
        boolean hasEvenWidth = (lobby.mapSettings.getBoardWidth() % 2) == 0;

        JCheckBoxMenuItem rotateItem = new JCheckBoxMenuItem(
              Messages.getString("ChatLounge.MapPreviewButton.rotate"), isRotated);
        rotateItem.setToolTipText(Messages.getString("ChatLounge.MapPreviewButton.rotateTooltip"));
        rotateItem.setEnabled(isFixedBoard && hasEvenWidth);
        rotateItem.addActionListener(actionEvent -> toggleRotation());

        ScalingPopup popup = new ScalingPopup();
        popup.add(rotateItem);
        popup.add(themeMenu());
        JMenuItem levelItem = new JMenuItem(Messages.getString("ChatLounge.MapPreviewButton.levelOffset"));
        levelItem.setEnabled(!boardName.isBlank() && lobby.mapSettings.getMedium() != MapSettings.MEDIUM_SPACE);
        levelItem.addActionListener(event -> editLevelOffset());
        popup.add(levelItem);
        return popup;
    }

    private void editLevelOffset() {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(lobby.mapSettings.getBoardLevelOffset(index),
              MapSettings.MIN_BOARD_LEVEL_OFFSET, MapSettings.MAX_BOARD_LEVEL_OFFSET, 1));
        String title = Messages.getString("ChatLounge.MapPreviewButton.levelOffset");
        Object[] content = { Messages.getString("ChatLounge.MapPreviewButton.levelOffsetHelp",
              MapSettings.MIN_BOARD_LEVEL_OFFSET, MapSettings.MAX_BOARD_LEVEL_OFFSET), spinner };
        Object[] options = { Messages.getString("Okay"),
              Messages.getString("ChatLounge.MapPreviewButton.resetLevelOffset"), Messages.getString("Cancel") };
        while (true) {
            int choice = JOptionPane.showOptionDialog(this, content, title, JOptionPane.DEFAULT_OPTION,
                  JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (choice == 1) {
                lobby.changeMapLevelOffset(index, 0);
                return;
            }
            if (choice != 0) { return; }
            try {
                spinner.commitEdit();
                lobby.changeMapLevelOffset(index, (Integer) spinner.getValue());
                return;
            } catch (ParseException ex) {
                JOptionPane.showMessageDialog(this,
                      Messages.getString("ChatLounge.MapPreviewButton.levelOffsetInvalid",
                            MapSettings.MIN_BOARD_LEVEL_OFFSET, MapSettings.MAX_BOARD_LEVEL_OFFSET), title,
                      JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private JMenu themeMenu() {
        JMenu menu = new JMenu(Messages.getString("ChatLounge.MapPreviewButton.theme"));
        menu.setEnabled(!boardName.isBlank() && lobby.mapSettings.getMedium() != MapSettings.MEDIUM_SPACE);
        String selected = lobby.mapSettings.getBoardTheme(index);
        ButtonGroup group = new ButtonGroup();
        addThemeChoice(menu, group, Messages.getString("ChatLounge.MapPreviewButton.defaultTheme"), null, selected);
        menu.addSeparator();
        var themes = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        themes.add("grass");
        BoardArtwork.themes().stream().filter(theme -> !theme.isBlank()).forEach(themes::add);
        themes.addAll(BoardEditorBlueprint.get().specialThemes());
        if (selected != null) { themes.add(selected); }
        for (String theme : themes) {
            addThemeChoice(menu, group, BoardEditorBlueprint.themeLabel(theme), theme, selected);
        }
        return menu;
    }

    private void addThemeChoice(JMenu menu, ButtonGroup group, String label, String theme, String selected) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(label, Objects.equals(theme, selected));
        group.add(item);
        menu.add(item);
        item.addActionListener(event -> lobby.changeMapTheme(index, theme));
    }

    /** Replaces the board on this slot with its 180 degree rotated (or un-rotated) counterpart. */
    private void toggleRotation() {
        String newBoardName = boardName.startsWith(Board.BOARD_REQUEST_ROTATION)
              ? boardName.substring(Board.BOARD_REQUEST_ROTATION.length())
              : Board.BOARD_REQUEST_ROTATION + boardName;
        logger.debug("[LobbyBoard] board slot {}: rotation toggled to {}", index, newBoardName);
        lobby.changeMapDnD(newBoardName, this);
    }

    /** A specialized JButton for the map preview panel of the map setup. */
    public MapPreviewButton(MapSetupDialog cl) {
        this(cl, 0);
    }

    /** Sets the size of the button to the given size. */
    public void setPreviewSize(Dimension size) {
        if (!currentPreviewSize.equals(size)) {
            currentPreviewSize = size;
            revalidate();
        }
    }

    /**
     * Deletes the scaled minimap image for this button, making it rescale and redraw.
     */
    public void scheduleRescale() {
        scaledImage = null;
        generateTooltip();
    }

    /**
     * Sets the minimap image of the button to the given base image and stores the name for DnD
     */
    public void setImage(Image image, String name) {
        isExample = name.startsWith(MapSettings.BOARD_SURPRISE) || name.startsWith(MapSettings.BOARD_GENERATED);
        baseImage = image;
        boardName = name;
        setText(name);
        generateTooltip();
        scheduleRescale();
    }

    public void reset() {
        baseImage = null;
        boardName = "";
    }

    private void generateTooltip() {
        setToolTipText(lobby.createBoardTooltip(boardName));
    }

    /**
     * Returns true if this button has a base image stored, i.e. if a board file is set for it.
     */
    public boolean hasBoard() {
        return baseImage != null;
    }

    /**
     * Returns the map board index of this button; e.g. 0 for the upper left map.
     */
    public int getIndex() {
        return index;
    }

    public void setIndex(int newIndex) {
        index = newIndex;
    }

    public String getBoard() {
        return boardName;
    }

    @Override
    public Dimension getPreferredSize() {
        if (currentPreviewSize.width < 1 || currentPreviewSize.height < 1) {
            setPreviewSize(lobby.maxMapButtonSize());
        }
        return currentPreviewSize;
    }

    /**
     * Scales the present baseImage so that it fits inside the maximum button size allowed by the dimensions of the
     * preview panel while preserving the aspect ratio of the base image. Also, signals the lobby that all preview
     * buttons should be redrawn with the same resulting size regardless of whether they have a board image or not. Adds
     * the necessary labels to the image as well.
     */
    private void scaleImage() {
        Dimension optSize = lobby.maxMapButtonSize();
        if (optSize.width > 1 && optSize.height > 1 && baseImage != null) {
            // Scale to the maximum size keeping aspect ratio
            double factorX = (double) optSize.width / baseImage.getWidth(null);
            double factorY = (double) optSize.height / baseImage.getHeight(null);
            double factor = Math.min(factorX, factorY);
            int width = (int) (factor * baseImage.getWidth(null));
            int height = (int) (factor * baseImage.getHeight(null));
            scaledImage = baseImage.getScaledInstance(width, height, Image.SCALE_SMOOTH);
            // Add the labels (index, name, example)
            BufferedImage drawableImage = ImageUtil.createAcceleratedImage(scaledImage);

            if (drawableImage != null) {
                Graphics graphics = drawableImage.getGraphics();
                UIUtil.setHighQualityRendering(graphics);
                if (lobby.isMultipleBoards()) {
                    drawIndex(graphics, width, height);
                }
                if (isExample && lobby.mapSettings.getMedium() != MapSettings.MEDIUM_SPACE) {
                    drawExample(graphics, width, height);
                }
                if (lobby.mapSettings.getMedium() != MapSettings.MEDIUM_SPACE) {
                    String text = cleanBoardName(getText(), lobby.mapSettings);
                    String theme = lobby.mapSettings.getBoardTheme(index);
                    if (theme != null) { text += " [" + theme + "]"; }
                    int offset = lobby.mapSettings.getBoardLevelOffset(index);
                    if (offset != 0) { text += " [" + (offset > 0 ? "+" : "") + offset + "]"; }
                    drawMinimapLabel(text, width, height, graphics, lobby.hasInvalidBoard(getText()));
                }
                graphics.dispose();
                // Store the image and notify other buttons to redraw with the calculated size
                scaledImage = drawableImage;
            }

            currentPreviewSize = new Dimension(width, height);
            revalidate();
            lobby.updateMapButtons(currentPreviewSize);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (scaledImage == null) {
            scaleImage();
        }
        if (scaledImage != null) {
            g.drawImage(scaledImage, 0, 0, null);
        }
    }

    private void drawIndex(Graphics g, int w, int h) {
        String text = Integer.toString(index + 1);
        int fontSize = Math.min(w, h) / 4;
        fontSize = Math.min(fontSize, UIUtil.scaleForGUI(45));
        g.setFont(new Font(MMConstants.FONT_DIALOG, Font.PLAIN, fontSize));
        FontMetrics fm = g.getFontMetrics(g.getFont());
        int cx = (w - fm.stringWidth(text)) / 2;
        int cy = (h + fm.getAscent() - fm.getDescent()) / 2;
        g.setColor(INDEX_COLOR);
        g.drawString(text, cx, cy);
    }

    private void drawExample(Graphics g, int w, int h) {
        String text = "Example board";
        int fontSize = Math.min(w / 10, UIUtil.scaleForGUI(25));
        g.setFont(new Font(MMConstants.FONT_DIALOG, Font.ITALIC, fontSize));
        FontMetrics fm = g.getFontMetrics(g.getFont());
        int cx = (w - fm.stringWidth(text)) / 2;
        int cy = h / 10 + fm.getAscent();
        g.setColor(Color.BLACK);
        g.drawString(text, cx, cy);
    }

    /**
     * The TransferHandler manages drag-and-drop for the preview button. The preview buttons can import boards from
     * other preview buttons and from the available boards list. They can also export boards (to other preview
     * buttons).
     */
    private static class MapButtonTransferHandler extends TransferHandler {
        @Serial
        private static final long serialVersionUID = -1798418800717656572L;

        public final DataFlavor flavor = DataFlavor.stringFlavor;
        private final MapPreviewButton button;
        private final MapSetupDialog lobby;

        public MapButtonTransferHandler(MapSetupDialog cl, MapPreviewButton mpButton) {
            lobby = cl;
            button = mpButton;
        }

        @Override
        public int getSourceActions(JComponent c) {
            return DnDConstants.ACTION_COPY;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            // When multiple boards come from the available boards list, they
            // are just the board names separated by newlines; replicate this for the button
            // by removing the "Surprise" prefix.
            String selection = button.boardName;
            if (selection.startsWith(MapSettings.BOARD_SURPRISE)) {
                selection = selection.substring(MapSettings.BOARD_SURPRISE.length());
            }
            return new StringSelection(selection);
        }

        @Override
        public boolean canImport(TransferHandler.TransferSupport support) {
            return support.isDataFlavorSupported(flavor);
        }

        @Override
        public boolean importData(TransferHandler.TransferSupport support) {
            if (canImport(support)) {
                try {
                    Component component = support.getComponent();
                    Object value = support.getTransferable().getTransferData(flavor);
                    if ((value instanceof String) && (component instanceof MapPreviewButton)) {
                        lobby.changeMapDnD((String) value, button);
                        return true;
                    } else {
                        return false;
                    }
                } catch (Exception ex) {
                    logger.error(ex, "A problem has occurred with map drag-and-drop.");
                }
            }
            return false;
        }
    }

}
