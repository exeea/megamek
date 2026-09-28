/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Shape;
import java.awt.image.BufferedImage;

import megamek.client.ui.tileset.TilesetManager;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Entity;

/** Drawing inputs for shared tactical symbols and unit labels, independent of a board window. */
public interface BoardGlyphContext {
    default BoardGlyphContext glyphContext() { return this; }
    int C3_LINE_WIDTH = 1;
    int FLY_OVER_LINE_WIDTH = 3;
    void addSprite(megamek.client.ui.clientGUI.boardview.sprite.Sprite sprite);
    java.util.List<megamek.client.ui.clientGUI.boardview.sprite.AttackSprite> getAttackSprites();
    void drawHexBorder(Graphics2D graphics, Point point, Color color, double padding, double lineWidth);
    Game getGame();
    Board getBoard();
    Player getLocalPlayer();
    Entity getSelectedEntity();
    TilesetManager getTileManager();
    TilesetManager getTilesetManager();
    float getScale();
    Dimension getHexSize();
    Point getHexLocation(Coords coords);
    Point getCentreHexLocation(Coords coords);
    Point getCentreHexLocation(Coords coords, boolean ignoreElevation);
    int getVerticalOffset();
    Shape[] getFacingPolys();
    Shape[] getMovementPolys();
    Shape getUpArrow();
    Shape getDownArrow();
    Image getScaledImage(Image image, boolean useCache);
    BufferedImage createShadowMask(Image image);
    boolean isGpuCapture();
    void repaint();
    void drawHexBorder(Graphics2D graphics, Color color, double padding, double lineWidth);
    BoardMarker boardMarker(BoardMarker.Kind kind, Coords coords, String label);
    BoardMarker boardMarker(BoardMarker.Kind kind, Coords coords, int rgb, String label);

    FontMetrics getFontMetrics(Font font);
    int getDropShadowDistance();
}
