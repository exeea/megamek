/*
 * Copyright (c) 2002-2005 Ben Mazur (bmazur@sev.org)
 * Copyright (c) 2013 Edward Cullen (eddy@obsessedcomputers.co.uk)
 * Copyright (C) 2021-2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.dialogs.minimap;

import static megamek.common.units.Terrains.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.io.StreamTokenizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import megamek.MMConstants;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.util.StringDrawer;
import megamek.client.ui.util.UIUtil;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardHelper;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.*;
import megamek.common.util.ImageUtil;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.logging.MMLogger;

/**
 * Obviously, displays the map in scaled-down size. TBD: -move the buttons from graphics to real Swing buttons -clean up
 * listenercode.. -initializecolors is fugly -uses break-to-label -uses while-true
 */
public final class MinimapPanel extends JPanel {
    private static final MMLogger logger = MMLogger.create(MinimapPanel.class);

    private static final Color[] terrainColors = new Color[Terrains.SIZE];
    private static Color HEAVY_WOODS;
    private static Color ULTRA_HEAVY_WOODS;
    private static Color BACKGROUND;
    private static Color SINKHOLE;
    private static Color SMOKE_AND_FIRE;

    private static final int[] FONT_SIZE = { 6, 6, 8, 10, 12, 14, 16 };
    private static final int[] HEX_SIDE = { 2, 3, 5, 6, 8, 10, 12 };
    private static final int[] HEX_SIDE_BY_COS30 = { 2, 3, 4, 5, 7, 9, 10 };
    private static final int[] HEX_SIDE_BY_SIN30 = { 1, 2, 2, 3, 4, 5, 6 };
    private static final int[] HALF_ROAD_WIDTH_BY_COS30 = { 0, 0, 0, 1, 2, 2, 3 };
    private static final int[] HALF_ROAD_WIDTH_BY_SIN30 = { 0, 0, 0, 1, 1, 1, 2 };
    private static final int[] HALF_ROAD_WIDTH = { 0, 0, 0, 1, 2, 3, 3 };
    private static final int MIM_ZOOM = 0;
    private static final int MAX_ZOOM = HEX_SIDE.length - 1;
    private static final int MIM_ZOOM_FOR_HEIGHT = 4;

    private static final int SHOW_NO_HEIGHT = 0;
    private static final int SHOW_GROUND_HEIGHT = 1;
    private static final int SHOW_BUILDING_HEIGHT = 2;
    private static final int SHOW_TOTAL_HEIGHT = 3;
    private static final int MARGIN = 3;

    private static final GUIPreferences GUIP = GUIPreferences.getInstance();
    /** The colour theme file (in the minimap themes directory) used for every minimap. */
    private static final String MINIMAP_THEME_FILE = "defaultminimap.theme";
    private BufferedImage mapImage;
    private final Game game;
    private Board board;
    private int topMargin;
    private int leftMargin;
    /** A list of information on hexes with roads or bridges. */
    private final List<int[]> roadHexes = new ArrayList<>();
    private int zoom = GUIP.getMinimapZoom();
    private int heightDisplayMode = GUIP.getMinimapHeightDisplayMode();
    private boolean paintBorders = GUIP.paintBorders();
    /** Signifies that the whole minimap must be repainted. */
    private boolean dirtyMap = true;
    /** Keeps track of portions of the minimap that must be repainted. */
    private boolean[][] dirty = new boolean[1][1];
    private Image terrainBuffer;

    /** Returns a minimap image of the given board at the maximum zoom index. */
    public static BufferedImage getMinimapImageMaxZoom(Board board) {
        return getMinimapImage(board, MAX_ZOOM);
    }

    /** Returns a minimap image of the given board at the given zoom index. */
    public static BufferedImage getMinimapImage(Board board, int zoom) {
        try {
            // Send the fail image when the zoom index is wrong to make this noticeable
            if ((zoom < MIM_ZOOM) || (zoom > MAX_ZOOM)) {
                throw new Exception("The given zoom index is out of bounds.");
            }
            Game game = new Game();
            game.setBoard(board);
            MinimapPanel tempMM = new MinimapPanel(game, 0);
            tempMM.zoom = zoom;
            tempMM.initializeMap();
            tempMM.drawMap();
            return ImageUtil.createAcceleratedImage(tempMM.mapImage);
        } catch (Exception e) {
            logger.error(e, "");
            return ImageUtil.failStandardImage();
        }
    }

    /** A minimap of {@code game}'s board {@code boardId} for a snapshot image ({@link #getMinimapImage}). */
    private MinimapPanel(Game game, int boardId) {
        this.game = Objects.requireNonNull(game);
        board = Objects.requireNonNull(this.game.getBoard(boardId));
        initializeColors();
    }

    /** Initialize default colors and override with config file if there is one. */
    private void initializeColors() {

        BACKGROUND = Color.black;
        terrainColors[0] = new Color(218, 215, 170);
        SINKHOLE = new Color(218, 215, 170);
        terrainColors[WOODS] = new Color(180, 230, 130);
        HEAVY_WOODS = new Color(160, 200, 100);
        ULTRA_HEAVY_WOODS = new Color(0, 100, 0);
        terrainColors[ROUGH] = new Color(215, 181, 0);
        terrainColors[RUBBLE] = new Color(200, 200, 200);
        terrainColors[WATER] = new Color(200, 247, 253);
        terrainColors[PAVEMENT] = new Color(204, 204, 204);
        terrainColors[ROAD] = new Color(71, 79, 107);
        terrainColors[FIRE] = Color.red;
        terrainColors[SMOKE] = new Color(204, 204, 204);
        SMOKE_AND_FIRE = new Color(153, 0, 0);
        terrainColors[SWAMP] = new Color(49, 136, 74);
        terrainColors[BUILDING] = new Color(204, 204, 204);
        terrainColors[FUEL_TANK] = new Color(255, 204, 204);
        terrainColors[BRIDGE] = new Color(109, 55, 25);
        terrainColors[ICE] = new Color(204, 204, 255);
        terrainColors[MAGMA] = new Color(200, 0, 0);
        // m_terrainColors[MUD] = new Color(218, 160, 100);
        terrainColors[JUNGLE] = new Color(180, 230, 130);
        terrainColors[FIELDS] = new Color(250, 255, 205);
        terrainColors[INDUSTRIAL] = new Color(112, 138, 144);
        terrainColors[SPACE] = Color.BLACK;

        // now try to read in the config file
        int red;
        int green;
        int blue;

        File minimapTheme = new MegaMekFile(Configuration.minimapThemesDir(), MINIMAP_THEME_FILE).getFile();

        // only while the defaults are hard-coded!
        if (!minimapTheme.exists()) {
            return;
        }

        try (Reader cr = new FileReader(minimapTheme)) {
            StreamTokenizer st = new StreamTokenizer(cr);

            st.lowerCaseMode(true);
            st.quoteChar('"');
            st.commentChar('#');

            scan:
            while (true) {
                switch (st.nextToken()) {
                    case StreamTokenizer.TT_EOF:
                    case StreamTokenizer.TT_EOL:
                        break scan;
                    case StreamTokenizer.TT_WORD:
                        // read in
                        String key = st.sval;
                        switch (key) {
                            case "unitsize" -> {
                                // no units are drawn: the theme's unit size is read past
                                st.nextToken();
                            }
                            case "background" -> {
                                st.nextToken();
                                red = (int) st.nval;
                                st.nextToken();
                                green = (int) st.nval;
                                st.nextToken();
                                blue = (int) st.nval;

                                BACKGROUND = new Color(red, green, blue);
                            }
                            case "heavywoods" -> {
                                st.nextToken();
                                red = (int) st.nval;
                                st.nextToken();
                                green = (int) st.nval;
                                st.nextToken();
                                blue = (int) st.nval;

                                HEAVY_WOODS = new Color(red, green, blue);
                            }
                            case "ultraheavywoods" -> {
                                st.nextToken();
                                red = (int) st.nval;
                                st.nextToken();
                                green = (int) st.nval;
                                st.nextToken();
                                blue = (int) st.nval;

                                ULTRA_HEAVY_WOODS = new Color(red, green, blue);
                            }
                            case "sinkhole" -> {
                                st.nextToken();
                                red = (int) st.nval;
                                st.nextToken();
                                green = (int) st.nval;
                                st.nextToken();
                                blue = (int) st.nval;

                                SINKHOLE = new Color(red, green, blue);
                            }
                            case "smokeandfire" -> {
                                st.nextToken();
                                red = (int) st.nval;
                                st.nextToken();
                                green = (int) st.nval;
                                st.nextToken();
                                blue = (int) st.nval;

                                SMOKE_AND_FIRE = new Color(red, green, blue);
                            }
                            default -> {
                                st.nextToken();
                                red = (int) st.nval;
                                st.nextToken();
                                green = (int) st.nval;
                                st.nextToken();
                                blue = (int) st.nval;

                                terrainColors[getType(key)] = new Color(red, green, blue);
                            }
                        }
                        break;
                }
            }
        } catch (Exception e) {
            // Fall back to the default colors
            logger.error(e, "");
        }
    }

    /** Marks all the minimap as clean (not requiring an update). */
    private void markAsClean() {
        dirtyMap = false;
        for (boolean[] booleans : dirty) {
            Arrays.fill(booleans, false);
        }
    }

    void initializeMap() {
        dirty = new boolean[(board.getWidth() / 10) + 1][(board.getHeight() / 10) + 1];
        dirtyMap = true;

        topMargin = MARGIN;
        leftMargin = MARGIN;
        int requiredWidth = (board.getWidth() * (HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom]))
              + HEX_SIDE_BY_SIN30[zoom] + (2 * MARGIN);
        int requiredHeight = (((2 * board.getHeight()) + 1) * HEX_SIDE_BY_COS30[zoom]) + (2 * MARGIN);

        mapImage = ImageUtil.createAcceleratedImage(requiredWidth, requiredHeight);
        terrainBuffer = ImageUtil.createAcceleratedImage(requiredWidth, requiredHeight);
        Graphics gg = terrainBuffer.getGraphics();
        gg.setColor(BACKGROUND);
        gg.fillRect(0, 0, getSize().width, getSize().height);
    }

    /**
     * Draws the minimap to the back-buffer. When forceDraw is true, the map will be drawn even if it is minimized or
     * not visible. This can be used to draw the minimap for saving it as an image regardless of its visual status
     * onscreen.
     */
    private void drawMap() {
        Graphics g = mapImage.getGraphics();
        UIUtil.setHighQualityRendering(g);

        roadHexes.clear();
        Graphics gg = terrainBuffer.getGraphics();
        UIUtil.setHighQualityRendering(gg);
        for (int j = 0; j < board.getWidth(); j++) {
            for (int k = 0; k < board.getHeight(); k++) {
                Hex h = board.getHex(j, k);
                if (h == null) {
                    continue;
                }
                if (dirtyMap || dirty[j / 10][k / 10]) {
                    gg.setColor(terrainColor(h));
                    if (board.isHighAltitude()) {
                        paintHighAltCoord(gg, j, k);
                    } else if (board.isSpace()) {
                        paintSpaceCoord(gg, j, k);
                    } else if (h.containsTerrain(SKY)) {
                        paintLowAtmosphereSkyCoord(gg, j, k, paintBorders && zoom > 1);
                    } else {
                        paintCoord(gg, j, k, paintBorders && zoom > 1);
                    }
                }
                addRoadElements(h, j, k);
                // Color invalid hexes red when in the Map Editor
                if ((game != null) && game.getPhase().isUnknown() && !h.isValid(null)) {
                    gg.setColor(GUIP.getWarningColor());
                    paintCoord(gg, j, k, true);
                }
            }
        }
        g.drawImage(terrainBuffer, 0, 0, this);
        markAsClean();

        paintRoads(g);

        if (SHOW_NO_HEIGHT != heightDisplayMode) {
            for (int j = 0; j < board.getWidth(); j++) {
                for (int k = 0; k < board.getHeight(); k++) {
                    Hex h = board.getHex(j, k);
                    paintHeight(g, h, j, k);
                }
            }
        }

        if (board.isHighAltitude()
              && (HEX_SIDE[zoom] >= 10)) {
            int baseX = HEX_SIDE[zoom] + leftMargin;
            int baseY = (board.getHeight() + 1) * HEX_SIDE_BY_COS30[zoom] + topMargin;
            new StringDrawer("Ground Hexes").at(baseX, baseY).fontSize(HEX_SIDE[zoom])
                  .rotate(Math.toRadians(90)).center().color(Color.BLACK).draw(g);

            if ((game != null) && !game.getPlanetaryConditions().getAtmosphere().isVacuum()) {
                baseX = HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom] + HEX_SIDE[zoom] + leftMargin;
                new StringDrawer("Atmospheric Row 1").at(baseX, baseY).fontSize(HEX_SIDE[zoom])
                      .rotate(Math.toRadians(90)).center().color(Color.LIGHT_GRAY).draw(g);

                baseX = (4 * (HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom]) + HEX_SIDE[zoom]) + leftMargin;
                new StringDrawer("Atmospheric Row 4").at(baseX, baseY).fontSize(HEX_SIDE[zoom])
                      .rotate(Math.toRadians(90)).center().color(Color.GRAY).draw(g);

                if (game.getPlanetaryConditions().getAtmosphere().isVeryHigh()) {
                    baseX = (7 * (HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom]) + HEX_SIDE[zoom]) + leftMargin;
                    new StringDrawer("Atmospheric Row 7").at(baseX, baseY).fontSize(HEX_SIDE[zoom])
                          .rotate(Math.toRadians(90)).center().color(Color.GRAY).draw(g);
                }

                baseX = (BoardHelper.spaceAtmosphereInterfacePosition(game)
                      * (HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom]) + HEX_SIDE[zoom]) + leftMargin;
                new StringDrawer("Space/Atmosphere Interface").at(baseX, baseY).fontSize(HEX_SIDE[zoom])
                      .rotate(Math.toRadians(90)).center().color(Color.GRAY).draw(g);
            }

            if (board.getWidth() > 15) {
                baseX = (15 * (HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom]) + HEX_SIDE[zoom]) + leftMargin;
                new StringDrawer("End of Gravity").at(baseX, baseY).fontSize(HEX_SIDE[zoom])
                      .rotate(Math.toRadians(90)).center().color(Color.GRAY).draw(g);
            }
        }

        drawEmbeddedBoards(g);
    }

    /**
     * Draw an outline around legal deployment hexes
     */
    private void drawEmbeddedBoards(Graphics g) {
        for (Coords coords : board.embeddedBoardCoords()) {
            paintEmbeddedBoard(g, coords.getX(), coords.getY(), Color.GREEN);
        }
    }

    /** Writes the height value (hex/building/none) in the minimap hexes. */
    private void paintHeight(Graphics g, Hex h, int x, int y) {
        if ((heightDisplayMode == SHOW_NO_HEIGHT) || (zoom < MIM_ZOOM_FOR_HEIGHT)) {
            return;
        }

        int height = 0;
        if ((heightDisplayMode == SHOW_BUILDING_HEIGHT) && h.containsTerrain(BUILDING)) {
            height = h.ceiling();
        } else if (heightDisplayMode == SHOW_GROUND_HEIGHT) {
            height = h.floor();
        } else if (heightDisplayMode == SHOW_TOTAL_HEIGHT) {
            height = (h.containsAnyTerrainOf(BUILDING, FUEL_TANK)) ? h.ceiling() : h.floor();
        }
        if (height != 0) {
            String sHeight = height + "";
            int baseX = coordsXToPixel(x);
            int baseY = coordsYtoPixel(y, x);
            Font font = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, FONT_SIZE[zoom]);
            int fontWidth = getFontMetrics(font).stringWidth(sHeight) / 2;
            int fontHeight = getFontMetrics(font).getHeight() / 3;
            g.setFont(font);
            g.setColor(Color.white);
            g.drawString(sHeight, baseX - fontWidth, baseY + fontHeight);
        }
    }

    private int[] xPoints(int x) {
        int baseX = (x * (HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom])) + leftMargin;
        int[] xPoints = new int[6];
        xPoints[0] = baseX;
        xPoints[1] = baseX + HEX_SIDE_BY_SIN30[zoom];
        xPoints[2] = xPoints[1] + HEX_SIDE[zoom];
        xPoints[3] = xPoints[2] + HEX_SIDE_BY_SIN30[zoom];
        xPoints[4] = xPoints[2];
        xPoints[5] = xPoints[1];
        return xPoints;
    }

    private int[] yPoints(int x, int y) {
        int baseY = (((2 * y) + 1 + (x % 2)) * HEX_SIDE_BY_COS30[zoom]) + topMargin;
        int[] yPoints = new int[6];
        yPoints[0] = baseY;
        yPoints[1] = baseY + HEX_SIDE_BY_COS30[zoom];
        yPoints[2] = yPoints[1];
        yPoints[3] = baseY;
        yPoints[4] = baseY - HEX_SIDE_BY_COS30[zoom];
        yPoints[5] = yPoints[4];
        return yPoints;
    }


    private void paintEmbeddedBoard(Graphics graphics, int x, int y, Color color) {
        graphics.setColor(color);
        ((Graphics2D) graphics).setStroke(new BasicStroke(Math.max(0.8f, zoom / 3f)));

        int baseX = (x * (HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom])) + leftMargin;
        int[] xPoints = new int[6];
        xPoints[0] = baseX + HEX_SIDE_BY_SIN30[zoom];
        xPoints[1] = xPoints[0] + HEX_SIDE[zoom];
        xPoints[2] = xPoints[1];
        xPoints[3] = xPoints[0];

        int baseY = (((2 * y) + 1 + (x % 2)) * HEX_SIDE_BY_COS30[zoom]) + topMargin;
        int[] yPoints = new int[6];
        yPoints[0] = baseY + HEX_SIDE_BY_COS30[zoom];
        yPoints[1] = yPoints[0];
        yPoints[2] = baseY - HEX_SIDE_BY_COS30[zoom];
        yPoints[3] = yPoints[2];
        graphics.drawPolygon(xPoints, yPoints, 4);
    }

    private void paintCoord(Graphics g, int x, int y, boolean border) {
        int[] xPoints = xPoints(x);
        int[] yPoints = yPoints(x, y);
        g.fillPolygon(xPoints, yPoints, 6);
        if (border) {
            g.setColor(g.getColor().darker());
        }
        g.drawPolygon(xPoints, yPoints, 6);
    }

    private void paintLowAtmosphereSkyCoord(Graphics g, int x, int y, boolean paintBorder) {
        int[] xPoints = xPoints(x);
        int[] yPoints = yPoints(x, y);
        int c = 190;
        g.setColor(new Color(c / 2, c, c));
        g.fillPolygon(xPoints, yPoints, 6);
        if (paintBorder) {
            c = 170;
            g.setColor(new Color(c / 2, c, c));
        }
        g.drawPolygon(xPoints, yPoints, 6);
    }

    private void paintSpaceCoord(Graphics g, int x, int y) {
        int baseX = (x * (HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom])) + leftMargin;
        int baseY = (((2 * y) + 1 + (x % 2)) * HEX_SIDE_BY_COS30[zoom]) + topMargin;
        int[] xPoints = xPoints(x);
        int[] yPoints = yPoints(x, y);
        g.fillPolygon(xPoints, yPoints, 6);
        // alpha to tone down borders and stars at low zoom so they don't overwhelm the
        // image
        int alpha = Math.min(250, 20 * HEX_SIDE[zoom]);
        g.setColor(new Color(20, 20, 60, alpha));
        g.drawPolygon(xPoints, yPoints, 6);
        // Drop in a star
        int dx = (int) (Math.random() * HEX_SIDE[zoom]);
        int dy = (int) ((Math.random() - 0.5) * HEX_SIDE_BY_COS30[zoom]);
        int c = (int) (Math.random() * 180);
        g.setColor(new Color(c, c, c, alpha));
        if (Math.random() < 0.1) {
            g.setColor(new Color(c, c / 10, c / 10, alpha)); // red star
        } else if (Math.random() < 0.1) {
            int factor = (int) (Math.random() * 10) + 1;
            g.setColor(new Color(c / factor, c / factor, c, alpha)); // blue star
        }
        g.fillRect(baseX + dx, baseY + dy, 1, 1);
    }

    private void paintHighAltCoord(Graphics g, int x, int y) {
        int baseX = (x * (HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom])) + leftMargin;
        int baseY = (((2 * y) + 1 + (x % 2)) * HEX_SIDE_BY_COS30[zoom]) + topMargin;
        int[] xPoints = xPoints(x);
        int[] yPoints = yPoints(x, y);

        int spaceInterfacePosition = BoardHelper.spaceAtmosphereInterfacePosition(game);
        // Draw in atmosphere in a high-altitude map (unless planetary conditions say its vacuum)
        if (BoardHelper.isAtmosphericRow(game, board, x)) {
            int atmosphericRow = BoardHelper.effectiveAtmosphericRowNumber(game, board, x);
            // Add atmosphere
            int step = 120 / (spaceInterfacePosition - 1);
            g.setColor(new Color(75 - step / 2 * atmosphericRow,
                  150 - step * atmosphericRow, 150 - step * atmosphericRow));
        } else if (BoardHelper.isGroundRowHex(board, x)) {
            g.setColor(Color.GREEN);
        }
        g.fillPolygon(xPoints, yPoints, 6);
        if (paintBorders && zoom > 1) {
            g.setColor(new Color(20, 20, 60));
        }
        g.drawPolygon(xPoints, yPoints, 6);

        if (x > spaceInterfacePosition) {
            // Drop in a star
            int dx = (int) (Math.random() * HEX_SIDE[zoom]);
            int dy = (int) ((Math.random() - 0.5) * HEX_SIDE_BY_COS30[zoom]);
            int c = (int) (Math.random() * 180);
            g.setColor(new Color(c, c, c));
            if (Math.random() < 0.1) {
                g.setColor(new Color(c, c / 10, c / 10)); // red star
            } else if (Math.random() < 0.1) {
                int factor = (int) (Math.random() * 10) + 1;
                g.setColor(new Color(c / factor, c / factor, c)); // blue star
            }
            g.fillRect(baseX + dx, baseY + dy, 1, 1);
        }
    }


    private int coordsYtoPixel(int y, int x) {
        return (2 * y + 1 + (x % 2)) * HEX_SIDE_BY_COS30[zoom] + topMargin;
    }

    private int coordsXToPixel(int x) {
        return x * (HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom]) + leftMargin + HEX_SIDE[zoom];
    }

    /** Draws the road elements previously assembles into the roadHexes list. */
    private void paintRoads(Graphics g) {
        int exits;
        int baseX;
        int baseY;
        int x;
        int y;
        int[] xPoints = new int[4];
        int[] yPoints = new int[4];
        g.setColor(terrainColors[ROAD]);
        for (int[] roadEntry : roadHexes) {
            x = roadEntry[0];
            y = roadEntry[1];
            baseX = (x * (HEX_SIDE[zoom] + HEX_SIDE_BY_SIN30[zoom])) + leftMargin + HEX_SIDE[zoom];
            baseY = (((2 * y) + 1 + (x % 2)) * HEX_SIDE_BY_COS30[zoom]) + topMargin;
            exits = roadEntry[2];
            // Is there a North exit?
            if (0 != (exits & 1)) {
                xPoints[0] = baseX - HALF_ROAD_WIDTH[zoom];
                yPoints[0] = baseY;
                xPoints[1] = baseX - HALF_ROAD_WIDTH[zoom];
                yPoints[1] = baseY - HEX_SIDE_BY_COS30[zoom];
                xPoints[2] = baseX + HALF_ROAD_WIDTH[zoom];
                yPoints[2] = baseY - HEX_SIDE_BY_COS30[zoom];
                xPoints[3] = baseX + HALF_ROAD_WIDTH[zoom];
                yPoints[3] = baseY;
                g.drawPolygon(xPoints, yPoints, 4);
                g.fillPolygon(xPoints, yPoints, 4);
            }
            // Is there a North-East exit?
            if (0 != (exits & 2)) {
                xPoints[0] = baseX - HALF_ROAD_WIDTH_BY_SIN30[zoom];
                yPoints[0] = baseY - HALF_ROAD_WIDTH_BY_COS30[zoom];
                xPoints[1] = Math.round((baseX + ((3 * HEX_SIDE[zoom]) / 4.0f)) - HALF_ROAD_WIDTH_BY_SIN30[zoom]);
                yPoints[1] = Math.round(baseY - (HEX_SIDE_BY_COS30[zoom] / 2.0f) - HALF_ROAD_WIDTH_BY_COS30[zoom]);
                xPoints[2] = xPoints[1] + (2 * HALF_ROAD_WIDTH_BY_SIN30[zoom]);
                yPoints[2] = yPoints[1] + (2 * HALF_ROAD_WIDTH_BY_COS30[zoom]);
                xPoints[3] = baseX + HALF_ROAD_WIDTH_BY_SIN30[zoom];
                yPoints[3] = baseY + HALF_ROAD_WIDTH_BY_COS30[zoom];
                g.drawPolygon(xPoints, yPoints, 4);
                g.fillPolygon(xPoints, yPoints, 4);
            }
            // Is there a South-East exit?
            if (0 != (exits & 4)) {
                xPoints[0] = baseX + HALF_ROAD_WIDTH_BY_SIN30[zoom];
                yPoints[0] = baseY - HALF_ROAD_WIDTH_BY_COS30[zoom];
                xPoints[1] = Math.round(baseX + ((3 * HEX_SIDE[zoom]) / 4.0f) + HALF_ROAD_WIDTH_BY_SIN30[zoom]);
                yPoints[1] = Math.round((baseY + (HEX_SIDE_BY_COS30[zoom] / 2.0f)) - HALF_ROAD_WIDTH_BY_COS30[zoom]);
                xPoints[2] = xPoints[1] - (2 * HALF_ROAD_WIDTH_BY_SIN30[zoom]);
                yPoints[2] = yPoints[1] + (2 * HALF_ROAD_WIDTH_BY_COS30[zoom]);
                xPoints[3] = baseX - HALF_ROAD_WIDTH_BY_SIN30[zoom];
                yPoints[3] = baseY + HALF_ROAD_WIDTH_BY_COS30[zoom];
                g.drawPolygon(xPoints, yPoints, 4);
                g.fillPolygon(xPoints, yPoints, 4);
            }
            // Is there a South exit?
            if (0 != (exits & 8)) {
                xPoints[0] = baseX + HALF_ROAD_WIDTH[zoom];
                yPoints[0] = baseY;
                xPoints[1] = baseX + HALF_ROAD_WIDTH[zoom];
                yPoints[1] = baseY + HEX_SIDE_BY_COS30[zoom];
                xPoints[2] = baseX - HALF_ROAD_WIDTH[zoom];
                yPoints[2] = baseY + HEX_SIDE_BY_COS30[zoom];
                xPoints[3] = baseX - HALF_ROAD_WIDTH[zoom];
                yPoints[3] = baseY;
                g.drawPolygon(xPoints, yPoints, 4);
                g.fillPolygon(xPoints, yPoints, 4);
            }
            // Is there a South-West exit?
            if (0 != (exits & 16)) {
                xPoints[0] = baseX + HALF_ROAD_WIDTH_BY_SIN30[zoom];
                yPoints[0] = baseY + HALF_ROAD_WIDTH_BY_COS30[zoom];
                xPoints[1] = Math.round((baseX - ((3 * HEX_SIDE[zoom]) / 4.0f)) + HALF_ROAD_WIDTH_BY_SIN30[zoom]);
                yPoints[1] = Math.round(baseY + (HEX_SIDE_BY_COS30[zoom] / 2.0f) + HALF_ROAD_WIDTH_BY_COS30[zoom]);
                xPoints[2] = xPoints[1] - (2 * HALF_ROAD_WIDTH_BY_SIN30[zoom]);
                yPoints[2] = yPoints[1] - (2 * HALF_ROAD_WIDTH_BY_COS30[zoom]);
                xPoints[3] = baseX - HALF_ROAD_WIDTH_BY_SIN30[zoom];
                yPoints[3] = baseY - HALF_ROAD_WIDTH_BY_COS30[zoom];
                g.drawPolygon(xPoints, yPoints, 4);
                g.fillPolygon(xPoints, yPoints, 4);
            }
            // Is there a North-West exit?
            if (0 != (exits & 32)) {
                xPoints[0] = baseX - HALF_ROAD_WIDTH_BY_SIN30[zoom];
                yPoints[0] = baseY + HALF_ROAD_WIDTH_BY_COS30[zoom];
                xPoints[1] = Math.round(baseX - ((3 * HEX_SIDE[zoom]) / 4.0f) - HALF_ROAD_WIDTH_BY_SIN30[zoom]);
                yPoints[1] = Math.round((baseY - (HEX_SIDE_BY_COS30[zoom] / 2.0f)) + HALF_ROAD_WIDTH_BY_COS30[zoom]);
                xPoints[2] = xPoints[1] + (2 * HALF_ROAD_WIDTH_BY_SIN30[zoom]);
                yPoints[2] = yPoints[1] - (2 * HALF_ROAD_WIDTH_BY_COS30[zoom]);
                xPoints[3] = baseX + HALF_ROAD_WIDTH_BY_SIN30[zoom];
                yPoints[3] = baseY - HALF_ROAD_WIDTH_BY_COS30[zoom];
                g.drawPolygon(xPoints, yPoints, 4);
                g.fillPolygon(xPoints, yPoints, 4);
            }

        }
    }

    /** If the given hex contains ROAD or BRIDGE, adds an entry to roadHexes. */
    private void addRoadElements(Hex hex, int boardX, int boardY) {
        if (hex.containsAnyTerrainOf(ROAD, BRIDGE)) {
            var terrain = hex.getAnyTerrainOf(ROAD, BRIDGE);
            roadHexes.add(new int[] { boardX, boardY, terrain.getExits() });
        }
    }

    private Color terrainColor(Hex hex) {
        Color terrColor = terrainColors[0];
        if (hex.getLevel() < 0) {
            terrColor = SINKHOLE;
        }

        int terrain = 0;
        // Check for Smoke and Fire - this overrides any other colors
        if (hex.containsTerrain(SMOKE) && hex.containsTerrain(FIRE)) {
            terrColor = SMOKE_AND_FIRE;
            // Check for Fire - this overrides any other colors
        } else if (hex.containsTerrain(FIRE)) {
            terrColor = terrainColors[FIRE];
        } else { // Otherwise, color based on terrains - higher valued terrains take color
            // precedence
            for (int j = terrainColors.length - 1; j >= 0; j--) {
                if ((hex.getTerrain(j) != null) && (terrainColors[j] != null)) {
                    if ((j == ROAD) || (j == BRIDGE)) {
                        continue;
                    }
                    terrColor = terrainColors[j];
                    terrain = j;
                    // make heavy woods darker
                    if (((j == WOODS) || (j == JUNGLE)) && (hex.getTerrain(j).getLevel() == 2)) {
                        terrColor = HEAVY_WOODS;
                    }
                    if (((j == WOODS) || (j == JUNGLE)) && (hex.getTerrain(j).getLevel() > 2)) {
                        terrColor = ULTRA_HEAVY_WOODS;
                    }
                    break;
                }
            }
        }
        return switch (terrain) {
            case 0, WOODS, JUNGLE, ROUGH, RUBBLE, WATER, PAVEMENT, ICE, FIELDS ->
                  adjustByLevel(terrColor, Math.abs(hex.floor()));
            case FUEL_TANK, BUILDING -> adjustByLevel(terrColor, Math.abs(hex.ceiling()));
            default -> terrColor;
        };
    }

    private Color adjustByLevel(Color color, int level) {
        if (level > 10) {
            level = 10;
        }
        int r = color.getRed() - (level * 15);
        int g = color.getGreen() - (level * 15);
        int b = color.getBlue() - (level * 15);
        if (r < 0) {
            r = 0;
        }
        if (g < 0) {
            g = 0;
        }
        if (b < 0) {
            b = 0;
        }
        return new Color(r, g, b);
    }


}
