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
package megamek.client.ui.dialogs.unitSelectorDialogs;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPortraits;
import megamek.client.ui.clientGUI.boardview.gpu.UnitPortraitAngle;
import megamek.client.ui.clientGUI.boardview.gpu.UnitPortraitZoom;
import megamek.client.ui.util.UIUtil;
import megamek.common.annotations.Nullable;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * Shows a unit's 3D model in the unit readout, in the lobby and unit selection only (see
 * {@link GpuUnitPortraits#availability(Entity)}). Dragging with the mouse walks the camera round the unit and up and
 * down, and the mouse wheel zooms while the pointer is over the model (elsewhere it still scrolls the readout text);
 * a double-click goes back to the starting view. The picture itself is drawn on the GPU by
 * {@link GpuUnitPortraits}, and nothing is drawn while this view is not the one being shown.
 */
public class UnitModelViewPanel extends JPanel {

    private static final MMLogger LOGGER = MMLogger.create(UnitModelViewPanel.class);

    /**
     * The view's height before GUI scaling; its width is the readout's image column,
     * {@link EntityReadoutPanel#DEFAULT_WIDTH}.
     */
    static final int VIEW_HEIGHT = 420;

    /** Degrees the camera walks round the unit per pixel dragged sideways. */
    private static final float YAW_DEGREES_PER_PIXEL = 0.6f;

    /** Degrees the camera rises per pixel dragged downwards, like tilting a miniature towards you. */
    private static final float PITCH_DEGREES_PER_PIXEL = 0.4f;

    private final JLabel pictureLabel = new JLabel("", SwingConstants.CENTER);

    /** The unit being shown, or {@code null} for none. */
    private Entity entity;
    private boolean hasModel;
    /** The unit captured for drawing; {@code null} until the view is first shown for it, as capturing costs time. */
    private GpuUnitPortraits.Subject subject;
    private UnitPortraitAngle angle = UnitPortraitAngle.DEFAULT;
    private UnitPortraitZoom zoom = UnitPortraitZoom.DEFAULT;
    private boolean isActive;
    private long latestRequest;
    /** The size of the last picture asked for, so a resize that changes it asks again. */
    private Dimension requestedSize;
    private Point dragStart;

    /**
     * Creates an empty view, sized to the readout's image column and the GUI scale. It draws nothing until a unit is
     * given with {@link #showUnit(Entity)} and the view is made active with {@link #setActive(boolean)}.
     */
    public UnitModelViewPanel() {
        super(new BorderLayout());
        Dimension size = UIUtil.scaleForGUI(EntityReadoutPanel.DEFAULT_WIDTH, VIEW_HEIGHT);
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
        pictureLabel.setToolTipText(Messages.getString("UnitModelViewPanel.toolTipText"));
        pictureLabel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        add(pictureLabel, BorderLayout.CENTER);

        MouseAdapter dragToTurn = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                dragStart = event.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragStart == null) {
                    return;
                }
                int sideways = event.getX() - dragStart.x;
                int downwards = event.getY() - dragStart.y;
                dragStart = event.getPoint();
                // Like spinning a turntable: dragging right moves the near side of the unit to the right, which
                // means the camera walks round the other way.
                angle = angle.turned(-sideways * YAW_DEGREES_PER_PIXEL, downwards * PITCH_DEGREES_PER_PIXEL);
                requestPicture();
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    angle = UnitPortraitAngle.DEFAULT;
                    zoom = UnitPortraitZoom.DEFAULT;
                    requestPicture();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                // Zoom towards the pointer: its place in the picture, -1 to 1 across and from bottom to top.
                float halfWidth = Math.max(1, pictureLabel.getWidth()) / 2f;
                float halfHeight = Math.max(1, pictureLabel.getHeight()) / 2f;
                float pointerX = (event.getX() - halfWidth) / halfWidth;
                float pointerY = (halfHeight - event.getY()) / halfHeight;
                UnitPortraitZoom next = zoom.wheeled(event.getPreciseWheelRotation(), pointerX, pointerY);
                if (!next.equals(zoom)) {
                    zoom = next;
                    requestPicture();
                }
            }
        };
        pictureLabel.addMouseListener(dragToTurn);
        pictureLabel.addMouseMotionListener(dragToTurn);
        // Only over the model: the readout's own wheel handling keeps scrolling the text everywhere else.
        pictureLabel.addMouseWheelListener(dragToTurn);

        // The first picture is often asked for before the readout is laid out; draw again at the final size.
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                if (!getSize().equals(requestedSize)) {
                    requestPicture();
                }
            }
        });
    }

    /**
     * Sets the unit to show. Only a quick look-up happens here; the unit is captured and drawn once this view is
     * actually shown, so paging through units with the picture showing costs nothing extra. The camera keeps its
     * angle, so paging through units in this view compares them from one side.
     *
     * @param entity The unit, or {@code null} to show nothing
     *
     * @return {@link GpuUnitPortraits.Availability#AVAILABLE} if the model can be shown, otherwise the reason it
     *       cannot, for the readout to explain on its switch
     */
    public GpuUnitPortraits.Availability showUnit(@Nullable Entity entity) {
        this.entity = entity;
        subject = null;
        GpuUnitPortraits.Availability availability = GpuUnitPortraits.availability(entity);
        hasModel = availability == GpuUnitPortraits.Availability.AVAILABLE;
        if (!hasModel) {
            pictureLabel.setIcon(null);
            pictureLabel.setText("");
            return availability;
        }
        requestPicture();
        return availability;
    }

    /**
     * @param active {@code true} while this view is the one visible in the readout. Only then are pictures drawn.
     */
    public void setActive(boolean active) {
        isActive = active;
        requestPicture();
    }

    private void requestPicture() {
        if (!isActive || !hasModel) {
            return;
        }
        if (subject == null) {
            subject = GpuUnitPortraits.capture(entity);
            if (subject == null) {
                hasModel = false;
                pictureLabel.setIcon(null);
                pictureLabel.setText(Messages.getString("UnitModelViewPanel.failed"));
                return;
            }
        }
        if (pictureLabel.getIcon() == null) {
            pictureLabel.setText(Messages.getString("UnitModelViewPanel.loading"));
        }
        int width = (getWidth() > 0) ? getWidth() : getPreferredSize().width;
        int height = (getHeight() > 0) ? getHeight() : getPreferredSize().height;
        requestedSize = new Dimension(width, height);
        GpuUnitPortraits.Subject requested = subject;
        latestRequest = GpuUnitPortraits.request(requested, angle, zoom, width, height, getBackground().getRGB(),
              result -> showResult(requested, result));
    }

    private void showResult(GpuUnitPortraits.Subject requested, GpuUnitPortraits.Result result) {
        // A picture of a unit no longer selected, or an angle already superseded, is dropped.
        if ((requested != subject) || (result.sequence() < latestRequest)) {
            return;
        }
        if (result.image() == null) {
            // A readout opened in the lobby may still be open when the game starts; say why, not that it failed.
            boolean isLobbyOver = GpuUnitPortraits.availability(entity) != GpuUnitPortraits.Availability.AVAILABLE;
            LOGGER.debug("[UnitPortrait] {}: no picture came back ({})", requested.unitName(),
                  isLobbyOver ? "the game has started" : "drawing failed");
            pictureLabel.setIcon(null);
            pictureLabel.setText(Messages.getString(isLobbyOver
                  ? "UnitModelViewPanel.lobbyOnly"
                  : "UnitModelViewPanel.failed"));
            return;
        }
        pictureLabel.setText("");
        pictureLabel.setIcon(new ImageIcon(result.image()));
    }
}
