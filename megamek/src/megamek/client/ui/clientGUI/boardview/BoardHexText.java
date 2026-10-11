/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static megamek.client.ui.tileset.HexTileset.HEX_H;
import static megamek.client.ui.tileset.HexTileset.HEX_W;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.font.FontRenderContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

import megamek.MegaMek;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.BuildingType;
import megamek.common.units.Terrains;

/** Shared label content; offsets are artwork pixels, and fromTop anchors the glyph top instead of its baseline. */
public record BoardHexText(String text, int baseline, Font font, int argb, boolean fromTop, int elevation) {
    private static final GUIPreferences GUIP = GUIPreferences.getInstance();
    private static final int HEX_TEXT_MARGIN = 6;
    /** The distance between the baselines of the stacked lines at the bottom edge, in artwork pixels. */
    private static final int LINE_SPACING = 10;
    /** How far every label's outline reaches out of its glyphs, as a share of its font size: about a stem's width. */
    private static final float OUTLINE = 0.05f;
    /** The copies of a label's text, in a ring around it, that draw its outline. */
    private static final int OUTLINE_COPIES = 8;
    private static final Font TACTICAL_DEPTH_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 26);
    private static final Font TACTICAL_CF_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final FontRenderContext MEASURE = new FontRenderContext(null, true, true);
    /** The type block's terrains after the cover, in the order of their lines: ground, liquids, modifiers, FIRE. */
    private static final int[] BELOW_COVER = { Terrains.ROUGH, Terrains.RUBBLE, Terrains.PAVEMENT, Terrains.SAND,
          Terrains.MUD, Terrains.TUNDRA, Terrains.FIELDS, Terrains.SNOW, Terrains.ICE, Terrains.BLACK_ICE,
          Terrains.MAGMA, Terrains.RAPIDS, Terrains.HAZARDOUS_LIQUID, Terrains.GEYSER, Terrains.IMPASSABLE,
          Terrains.FORTIFIED, Terrains.SOLARIS_ELEVATOR, Terrains.INDUSTRIAL_ELEVATOR, Terrains.SPACE, Terrains.FIRE };

    public static void draw(Graphics2D graphics, Point origin, int width, List<BoardHexText> labels) {
        draw(graphics, origin, width, labels, false);
    }

    /**
     * Draws the labels centred in a hex {@code width} wide, each on its {@link #outline} unless Client Settings >
     * Hex label outline is off; {@code printed} draws them black, always on a white outline, as the saved board image
     * does.
     */
    public static void draw(Graphics2D graphics, Point origin, int width, List<BoardHexText> labels, boolean printed) {
        boolean outlined = printed || GUIP.getHexLabelOutline();
        for (BoardHexText label : labels) {
            int argb = printed ? 0xFF000000 : label.argb();
            graphics.setFont(label.font());
            var metrics = graphics.getFontMetrics();
            int x = origin.x + (width - metrics.stringWidth(label.text())) / 2;
            int baseline = origin.y + label.baseline() + (label.fromTop() ? metrics.getAscent() : 0);
            if (outlined) {
                graphics.setColor(new Color(outline(argb), true));
                float[] offsets = outlineOffsets(label.font().getSize2D());
                for (int copy = 0; copy < offsets.length; copy += 2) {
                    graphics.drawString(label.text(), x + offsets[copy], baseline + offsets[copy + 1]);
                }
            }
            graphics.setColor(new Color(argb, true));
            graphics.drawString(label.text(), x, baseline);
        }
    }

    /** The colour of the outline around a label of colour {@code argb}: white around dark text, else black. */
    public static int outline(int argb) {
        boolean light = (argb >> 16 & 255) * 299 + (argb >> 8 & 255) * 587 + (argb & 255) * 114 > 127_500;
        return argb & 0xFF000000 | (light ? 0 : 0xFFFFFF);
    }

    /**
     * Where the copies of a label's text that draw its outline go, as x, y pairs in the units of its font, which is
     * {@code size} large: a ring around the text, close enough for the copies to join into one outline. Legacy 2D and
     * the saved image draw the outline so: far cheaper than stroking the glyphs' outlines for Legacy 2D, which draws
     * its labels on every paint. The 3D board draws a real stroke instead (GpuHexText, GpuBoardSkin.OUTLINE_FONT).
     */
    public static float[] outlineOffsets(float size) {
        float[] offsets = new float[2 * OUTLINE_COPIES];
        for (int copy = 0; copy < OUTLINE_COPIES; copy++) {
            double angle = copy * 2 * Math.PI / OUTLINE_COPIES;
            offsets[2 * copy] = (float) (Math.cos(angle) * size * OUTLINE);
            offsets[2 * copy + 1] = (float) (Math.sin(angle) * size * OUTLINE);
        }
        return offsets;
    }

    /** The hex's labels as the View menu's Hex Coords and Hex Types settings choose them. */
    public static List<BoardHexText> capture(Coords coords, Hex hex, Board board, float scale,
          Font font_hexNumber, Font font_elev) {
        return capture(coords, hex, board, scale, font_hexNumber, font_elev, GUIP.getCoordsEnabled(),
              GUIP.getHexTypesEnabled(), false);
    }

    /**
     * The hex's labels (b11 scope, "Hex TYPE labels"; the one place that decides them): its coordinates when
     * {@code coordinates}, and its lines at the bottom edge, top to bottom as the official map sheets stack them:
     * the height line (LEVEL n, below level 0 SUBLEVEL n, an ultra sublevel ULTRA and SUBLEVEL), DEPTH n, then the
     * structures, the cover, the ground, the liquids, the modifiers and FIRE. Without {@code types} only today's
     * labels follow the height line: DEPTH unless 0, the tallest structure's HEIGHT n (on its roof in 3D) and FOLIAGE
     * for a canopy of height 1; a basement hides the depth. With {@code types} the type block, in the height line's
     * colour: DEPTH n for every water hex, 0 and a basement included, then SWAMP (as the sheets' DEPTH 1 / SWAMP),
     * each structure with its height after a black triangle (BRIDGE, the building type, FUEL TANK, INDUSTRIAL; the
     * tallest one's line on its roof in 3D, as HEIGHT), the cover of woods and jungle alike (FOLIAGE, else LIGHT,
     * HEAVY or SUPER HEAVY by density), then the words of {@link #BELOW_COVER}: PAVED unless a building or a fuel
     * tank stands on it, and BLACK ICE, hidden information, once detected (the hex tooltip's rule) and never
     * {@code printed} in the saved image. The last line stands on the bottom edge, the others above it, each centred
     * and made smaller where it is wider than the hex.
     */
    public static List<BoardHexText> capture(Coords coords, Hex hex, Board board, float scale,
          Font font_hexNumber, Font font_elev, boolean coordinates, boolean types, boolean printed) {
        List<BoardHexText> labels = new ArrayList<>();
        int color = (board.isSpace() ? GUIP.getBoardSpaceTextColor() : GUIP.getBoardTextColor()).getRGB();
        if (coordinates && scale >= 0.5) {
            labels.add(new BoardHexText(coords.getBoardNum(), (int) (HEX_TEXT_MARGIN * scale), font_hexNumber,
                  color, true, 0));
        }
        if (scale <= 0.5f) {
            return List.copyOf(labels);
        }
        // The lines top to bottom; their baselines and fitted fonts are set below.
        List<BoardHexText> lines = new ArrayList<>();
        Consumer<String> line = text -> lines.add(new BoardHexText(text, 0, font_elev, color, false, 0));
        if (hex.containsTerrain(Terrains.ULTRA_SUBLEVEL)) {
            line.accept(Messages.getString("BoardView1.hexType.ultra"));
            line.accept(Messages.getString("BoardView1.hexType.sublevel"));
        } else if (hex.getLevel() != 0) {
            line.accept(level(hex.getLevel()));
        }
        int depth = hex.depth(false);
        if (hex.containsTerrain(Terrains.WATER)
              && (types || depth != 0 && !hex.containsTerrain(Terrains.BLDG_BASEMENT_TYPE))) {
            line.accept(Messages.getString("BoardView1.DEPTH") + " " + depth);
        }
        int height = Math.max(hex.terrainLevel(Terrains.BLDG_ELEV), hex.terrainLevel(Terrains.BRIDGE_ELEV));
        height = Math.max(height, hex.terrainLevel(Terrains.INDUSTRIAL));
        if (types) {
            if (hex.containsTerrain(Terrains.SWAMP)) {
                line.accept(word(hex, Terrains.SWAMP));
            }
            // The tallest structure's line lies on its roof in 3D (its elevation), where its model would hide the
            // ground and HEIGHT lies without types; the rest of the block stays at the ground.
            int roof = Math.max(height, hex.terrainLevel(Terrains.FUEL_TANK_ELEV));
            ObjIntConsumer<String> structureLine = (type, level) -> lines.add(new BoardHexText(
                  structure(type, level), 0, font_elev, color, false,
                  level == roof && lines.stream().noneMatch(above -> above.elevation() > 0) ? Math.max(0, level) : 0));
            if (hex.containsTerrain(Terrains.BRIDGE)) {
                structureLine.accept(Messages.getString("BoardView1.hexType.bridge"),
                      hex.terrainLevel(Terrains.BRIDGE_ELEV));
            }
            if (hex.containsTerrain(Terrains.BUILDING)) {
                String type = BuildingType.getType(hex.terrainLevel(Terrains.BUILDING)).toString();
                structureLine.accept(type.toUpperCase(MegaMek.getMMOptions().getLocale()),
                      hex.terrainLevel(Terrains.BLDG_ELEV));
            }
            if (hex.containsTerrain(Terrains.FUEL_TANK)) {
                structureLine.accept(word(hex, Terrains.FUEL_TANK), hex.terrainLevel(Terrains.FUEL_TANK_ELEV));
            }
            if (hex.containsTerrain(Terrains.INDUSTRIAL)) {
                structureLine.accept(Messages.getString("BoardView1.hexType.industrial"),
                      hex.terrainLevel(Terrains.INDUSTRIAL));
            }
        } else if (height > 0) {
            lines.add(new BoardHexText(Messages.getString("BoardView1.HEIGHT") + " " + height, 0, font_elev,
                  GUIP.getBuildingTextColor().getRGB(), false, height));
        }
        if (hex.vegetationCeiling() == 1) {
            lines.add(new BoardHexText(Messages.getString("BoardView1.LowFoliage"), 0, font_elev,
                  types ? color : GUIP.getLowFoliageColor().getRGB(), false, 0));
        } else if (types && hex.hasVegetation()) {
            int density = Math.max(hex.terrainLevel(Terrains.WOODS), hex.terrainLevel(Terrains.JUNGLE));
            line.accept(Messages.getString("BoardView1.hexType.cover" + Math.min(3, Math.max(1, density))));
        }
        if (types) {
            for (int terrain : BELOW_COVER) {
                if (hex.containsTerrain(terrain)
                      && !(terrain == Terrains.PAVEMENT
                            && (hex.containsTerrain(Terrains.BUILDING) || hex.containsTerrain(Terrains.FUEL_TANK)))
                      && !(terrain == Terrains.BLACK_ICE
                            && (printed || !hex.getTerrain(terrain).isBlackIceDetected()))) {
                    line.accept(word(hex, terrain));
                }
            }
        }
        for (int index = 0; index < lines.size(); index++) {
            BoardHexText next = lines.get(index);
            int y = HEX_H - HEX_TEXT_MARGIN - LINE_SPACING * (lines.size() - 1 - index);
            labels.add(new BoardHexText(next.text(), (int) (y * scale), fitted(font_elev, next.text(), y, scale),
                  next.argb(), false, next.elevation()));
        }
        return List.copyOf(labels);
    }

    /** The height line of a hex at {@code level}, not 0, as the official map sheets write it: LEVEL n or SUBLEVEL n. */
    public static String level(int level) {
        return level < 0 ? Messages.getString("BoardView1.hexType.sublevel") + " " + -level
              : Messages.getString("BoardView1.LEVEL") + " " + level;
    }

    /** A bridge's type line with Hex Types on, its deck {@code height} above the hex: BRIDGE ▲n. */
    public static String bridge(int height) {
        return structure(Messages.getString("BoardView1.hexType.bridge"), height);
    }

    /** {@code type} and a structure's {@code height} after a black triangle, without a space: MEDIUM▲5 (the CF chip keeps its own, CF 150 ▲1). */
    private static String structure(String type, int height) {
        return Messages.getFormattedString("BoardView1.hexType.structure", type, Math.max(0, height));
    }

    /**
     * {@code terrain}'s word in the type block, its key named after the terrain; the levels with a word of their own
     * use the key ending in 2: ULTRA ROUGH, U. RUBBLE (rubble 6), QUICKSAND (swamp 2 and 3), DEEP SNOW, LIQUID
     * MAGMA (level 1 is MAGMA CRUST), TORRENT and MAGMA VENT (geyser 3).
     */
    private static String word(Hex hex, int terrain) {
        int level = hex.terrainLevel(terrain);
        boolean own = switch (terrain) {
            case Terrains.ROUGH, Terrains.SNOW, Terrains.MAGMA, Terrains.RAPIDS -> level == 2;
            case Terrains.RUBBLE -> level == 6;
            case Terrains.SWAMP -> level == 2 || level == 3;
            case Terrains.GEYSER -> level == 3;
            default -> false;
        };
        return Messages.getString("BoardView1.hexType." + Terrains.getName(terrain) + (own ? "2" : ""));
    }

    /**
     * {@code font}, smaller where {@code text} and its outline are wider than the hex at the baseline {@code y}, so the
     * next hex does not clip the line's lower corners.
     */
    private static Font fitted(Font font, String text, int y, float scale) {
        float room = (HEX_W / 2f + (HEX_H - y) * HEX_W / (float) HEX_H) * scale;
        double width = font.getStringBounds(text, MEASURE).getWidth() + 2 * OUTLINE * font.getSize2D();
        return width <= room ? font : font.deriveFont((float) (font.getSize2D() * room / width));
    }

    /**
     * {@code kept}, what a 3D view leaves of a hex's {@code captured} labels, with its lines at the bottom edge moved
     * down onto the lowest of the captured lines' baselines, so that the block still ends on the edge: the ground
     * lines lowest, in order, and above them the roof labels (elevation above 0), which the 3D board lays on their
     * roof wherever it has one.
     */
    public static List<BoardHexText> settled(List<BoardHexText> kept, List<BoardHexText> captured) {
        List<Integer> slots = captured.stream().filter(label -> !label.fromTop()).map(BoardHexText::baseline)
              .sorted().toList();
        int roof = slots.size() - (int) kept.stream().filter(label -> !label.fromTop()).count();
        int ground = roof + (int) kept.stream().filter(label -> !label.fromTop() && label.elevation() > 0).count();
        List<BoardHexText> labels = new ArrayList<>();
        for (BoardHexText label : kept) {
            labels.add(label.fromTop() ? label : new BoardHexText(label.text(),
                  slots.get(label.elevation() > 0 ? roof++ : ground++), label.font(), label.argb(), false,
                  label.elevation()));
        }
        return List.copyOf(labels);
    }

    /**
     * A hex's labels in the Tactical View (b11-design 4.6): {@code labels}, the board's, with a water hex's depth large
     * in its middle instead of its DEPTH label, and a structure's construction factor and height on its roof
     * ({@code elevation} is the height, so the 3D board lays it on the roof) instead of the roof's own label; the
     * other lines {@link #settled settle} at the bottom edge. {@code depth} and {@code cf} are 0 for none; with Hex
     * Types on the depth is 0, as the type block tells it. The chip writes the height after its black triangle unless
     * a line of {@code board} already tells it (the type block's MEDIUM ▲5), which then stays in the block at the
     * ground, where the top view shows it, and leaves the roof to the chip: the captured labels decide, not the
     * setting, so a tile captured before Hex Types changed still shows its height once. A unit on the hex
     * ({@code covered}) hides both: it is drawn on top, and the hex tooltip still tells them.
     */
    public static List<BoardHexText> tactical(List<BoardHexText> board, int depth, int cf, int height,
          boolean covered) {
        String depthPrefix = Messages.getString("BoardView1.DEPTH"), told = cf > 0 ? structure("", height) : null;
        boolean typed = told != null && board.stream().anyMatch(label -> label.text().endsWith(told));
        List<BoardHexText> kept = new ArrayList<>();
        for (BoardHexText label : board) {
            if (cf > 0 && label.elevation() > 0) {
                if (typed) {
                    kept.add(new BoardHexText(label.text(), label.baseline(), label.font(), label.argb(),
                          label.fromTop(), 0));
                }
            } else if (!(depth > 0 && label.text().startsWith(depthPrefix))) {
                kept.add(label);
            }
        }
        List<BoardHexText> labels = new ArrayList<>(settled(kept, board));
        if (depth > 0 && !covered) {
            labels.add(new BoardHexText(Integer.toString(depth), HEX_H / 2 + 10, TACTICAL_DEPTH_FONT, 0xE6FFFFFF,
                  false, 0));
        }
        if (cf > 0 && !covered) {
            String chip = typed ? Messages.getString("BoardView1.tacticalCf", cf)
                  : Messages.getString("BoardView1.tacticalCfHeight", cf, Math.max(0, height));
            labels.add(new BoardHexText(chip, HEX_H / 2 + 5, TACTICAL_CF_FONT, 0xFF1C2226, false, Math.max(0, height)));
        }
        return List.copyOf(labels);
    }

    public static void drawInvalid(java.awt.Graphics graphics, java.awt.Point origin, float scale) {
        new megamek.client.ui.util.StringDrawer(Messages.getString("BoardEditor.INVALID"))
              .color(GUIP.getWarningColor()).font(megamek.client.ui.util.FontHandler.notoFont().deriveFont(Font.BOLD))
              .center().at(origin.x + (int) (megamek.client.ui.tileset.HexTileset.HEX_W / 2.0f * scale),
                    origin.y + (int) (HEX_H / 2.0f * scale))
              .fontSize(14.0f * scale).outline(Color.WHITE, scale / 2).draw(graphics);
    }
}
