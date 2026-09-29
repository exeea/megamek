/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static megamek.client.ui.tileset.HexTileset.HEX_H;
import static megamek.client.ui.tileset.HexTileset.HEX_W;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import megamek.MMConstants;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.BoardRough;
import megamek.client.ui.tileset.HexTileset;
import megamek.client.ui.tileset.TilesetManager;
import megamek.client.ui.util.UIUtil;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import megamek.common.util.ImageUtil;

/** EDT-owned artwork composition. Game and map sources share this service; it never constructs a view. */
public final class BoardArtwork implements AutoCloseable {
    public record HexImage(Coords coords, BufferedImage terrain, BufferedImage normals, BufferedImage decals,
          BufferedImage decalsWithoutLimbs, BufferedImage tactical, List<BoardHexText> text,
          Map<Integer, String> structureModels, BufferedImage foliage, Set<Integer> blankTerrains) {
        public HexImage(Coords coords, BufferedImage terrain, BufferedImage normals, BufferedImage decals,
              BufferedImage decalsWithoutLimbs, BufferedImage tactical, List<BoardHexText> text,
              Map<Integer, String> structureModels, BufferedImage foliage) {
            this(coords, terrain, normals, decals, decalsWithoutLimbs, tactical, text, structureModels, foliage, Set.of());
        }
        public HexImage { blankTerrains = Set.copyOf(blankTerrains); }
    }
    private record GroundArtwork(BufferedImage color, BufferedImage normal) { }
    private record DecalArtwork(BufferedImage full, BufferedImage withoutLimbs, BufferedImage foliage) { }
    private final Map<Coords, GroundArtwork> groundArtwork = new HashMap<>();
    private final Map<Coords, DecalArtwork> featureArtwork = new HashMap<>();
    private final Map<String, Image> groundNormals = new HashMap<>();
    private HexTileset gpuTileset;
    private Image hexMask;
    private static final Font LABEL_FONT = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 10);

    /** Printable whole-board image, independent of either viewport, its zoom, or a live GL context. */
    public static BufferedImage printable(Board board, TilesetManager tileset,
          Function<Coords, BufferedImage> overlay) {
        int width = board.getWidth() * HEX_W * 3 / 4 + HEX_W / 4;
        int height = board.getHeight() * HEX_H + HEX_H / 2;
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var tile = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
        var graphics = result.createGraphics();
        var local = tile.createGraphics();
        try {
            UIUtil.setHighQualityRendering(graphics);
            UIUtil.setHighQualityRendering(local);
            for (int y = 0; y < board.getHeight(); y++) {
                for (int parity = 0; parity < 2; parity++) {
                    for (int x = parity; x < board.getWidth(); x += 2) {
                        Coords coords = new Coords(x, y);
                        Hex hex = board.getHex(coords);
                        local.setComposite(AlphaComposite.Clear);
                        local.fillRect(0, 0, HEX_W, HEX_H);
                        local.setComposite(AlphaComposite.SrcOver);
                        Image base = tileset.baseFor(hex);
                        drawBaseTerrain(hex, local, base, base, tileset.getHexMask(), 1);
                        for (Image image : tileset.supersFor(hex)) { local.drawImage(image, 0, 0, null); }
                        for (Image image : tileset.orthographicFor(hex)) { local.drawImage(image, 0, 0, null); }
                        if (GUIPreferences.getInstance().getLevelHighlight()) {
                            var outline = HexDrawUtilities.rasterHex();
                            int[] starts = {0, 1, 3, 4, 5, 7};
                            local.setColor(Color.BLACK);
                            for (int direction = 0; direction < 6; direction++) {
                                if (HexDrawUtilities.hasElevationBorder(board, coords, direction)) {
                                    int start = starts[direction], end = (start + 1) % outline.npoints;
                                    local.drawLine(outline.xpoints[start], outline.ypoints[start],
                                          outline.xpoints[end], outline.ypoints[end]);
                                }
                            }
                        }
                        local.drawImage(overlay.apply(coords), 0, 0, HEX_W, HEX_H, null);
                        BoardHexText.draw(local, new Point(), HEX_W,
                              BoardHexText.capture(coords, hex, board, 1, LABEL_FONT, LABEL_FONT));
                        Point position = largeTileLocation(x, y, 1);
                        graphics.drawImage(tile, position.x, position.y, null);
                    }
                }
            }
            return result;
        } finally {
            local.dispose();
            graphics.dispose();
        }
    }

    public BoardArtwork() {
        reload();
    }

    /** Reread the definitions and source images, including normal maps that ordinary hex invalidation retains. */
    public void reload() {
        var replacement = new HexTileset(new File(Configuration.dataDir(), "models/board/tileset"));
        try {
            replacement.loadFromFile("saxarba.tileset");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load the 3D board's Saxarba tileset", exception);
        }
        if (gpuTileset != null) { gpuTileset.close(); }
        gpuTileset = replacement;
        clear();
        groundNormals.clear();
        hexMask = null;
    }

    public HexImage capture(Board board, Coords coords, boolean includeArtwork) {
        // Bound the transient raster cache; immutable snapshots intern and retain the pixels they actually use.
        if (groundArtwork.size() > 256) { clear(); }
        Hex hex = board.getHex(coords);
        GroundArtwork ground = includeArtwork ? captureGroundArtwork(board, coords) : null;
        DecalArtwork decals = includeArtwork ? captureDecals(board, coords) : null;
        Map<Integer, String> models = includeArtwork ? structureModels(hex) : Map.of();
        Set<Integer> blank = includeArtwork ? gpuTileset.blankTerrainTypes(hex) : Set.of();
        gpuTileset.clearHex(hex);
        return new HexImage(coords, ground == null ? null : ground.color(), ground == null ? null : ground.normal(),
              decals == null ? null : decals.full(), decals == null ? null : decals.withoutLimbs(), null,
              BoardHexText.capture(coords, hex, board, 1, LABEL_FONT, LABEL_FONT),
              models, decals == null ? null : decals.foliage(), blank);
    }

    public void invalidate(Coords coords) {
        groundArtwork.remove(coords);
        featureArtwork.remove(coords);
    }

    public void clear() {
        groundArtwork.clear();
        featureArtwork.clear();
        gpuTileset.clearAllHexes();
    }

    private Image mask() {
        if (hexMask == null) { hexMask = TilesetManager.loadHexMask(); }
        return hexMask;
    }

    public static Point largeTileLocation(int x, int y, float scale) {
        int yPosition = (int) (y * HEX_H * scale) + ((x & 1) == 1 ? (int) ((HEX_H / 2.0f) * scale) : 0);
        return new Point((int) (x * HEX_W * 0.75f * scale), yPosition);
    }

    public static void drawBaseTerrain(Hex hex, Graphics2D graphics2D, Image baseImage, Image scaledImage,
          Image hexMask, float scale) {

        // check if this is a standard tile image 84x72 or something different
        boolean standardTile = (baseImage.getHeight(null) == HEX_H) && (baseImage.getWidth(null) == HEX_W);
        // do not make larger than hex images even when the input image is big
        int origImgWidth = scaledImage.getWidth(null); // save for later, needed for large tiles
        int origImgHeight = scaledImage.getHeight(null);

        if (standardTile) { // is the image hex-sized, 84*72?
            graphics2D.drawImage(scaledImage, 0, 0, null);
            return;
        }

        // Draw image for a texture larger than a hex
        Point p1SRC = largeTileLocation(hex.getCoords().getX(), hex.getCoords().getY(), scale);
        p1SRC.x = p1SRC.x % origImgWidth;
        p1SRC.y = p1SRC.y % origImgHeight;
        Point p2SRC = new Point((int) (p1SRC.x + HEX_W * scale), (int) (p1SRC.y + HEX_H * scale));
        Point p2DST = new Point((int) (HEX_W * scale), (int) (HEX_H * scale));

        // hex mask to limit drawing to the hex shape
        // TODO : this is not ideal yet but at least it draws without leaving gaps at any zoom
        graphics2D.drawImage(hexMask, 0, 0, null);
        Composite svComp = graphics2D.getComposite();
        graphics2D.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, 1f));

        // paint the right slice from the big pic
        graphics2D.drawImage(scaledImage, 0, 0, p2DST.x, p2DST.y, p1SRC.x, p1SRC.y, p2SRC.x, p2SRC.y, null);

        // Handle wrapping of the image
        if (p2SRC.x > origImgWidth && p2SRC.y <= origImgHeight) {
            graphics2D.drawImage(scaledImage,
                  origImgWidth - p1SRC.x,
                  0,
                  p2DST.x,
                  p2DST.y,
                  0,
                  p1SRC.y,
                  p2SRC.x - origImgWidth,
                  p2SRC.y,
                  null); // paint additional slice on the left side
        } else if (p2SRC.x <= origImgWidth && p2SRC.y > origImgHeight) {
            graphics2D.drawImage(scaledImage,
                  0,
                  origImgHeight - p1SRC.y,
                  p2DST.x,
                  p2DST.y,
                  p1SRC.x,
                  0,
                  p2SRC.x,
                  p2SRC.y - origImgHeight,
                  null); // paint additional slice on the top
        } else if (p2SRC.x > origImgWidth) {
            graphics2D.drawImage(scaledImage,
                  origImgWidth - p1SRC.x,
                  0,
                  p2DST.x,
                  p2DST.y,
                  0,
                  p1SRC.y,
                  p2SRC.x - origImgWidth,
                  p2SRC.y,
                  null); // paint additional slice on the top
            graphics2D.drawImage(scaledImage,
                  0,
                  origImgHeight - p1SRC.y,
                  p2DST.x,
                  p2DST.y,
                  p1SRC.x,
                  0,
                  p2SRC.x,
                  p2SRC.y - origImgHeight,
                  null); // paint additional slice on the left side
            // paint additional slice on the top left side
            graphics2D.drawImage(scaledImage,
                  origImgWidth - p1SRC.x,
                  origImgHeight - p1SRC.y,
                  p2DST.x,
                  p2DST.y,
                  0,
                  0,
                  p2SRC.x - origImgWidth,
                  p2SRC.y - origImgHeight,
                  null);
        }

        graphics2D.setComposite(svComp);
    }

    /** New kits use family/name paths; the selected legacy asset keeps its tileset provenance for fallback. */
    public static File customBuildingFile(String asset) {
        String relative = asset.substring("buildings/".length());
        if (relative.startsWith("saxarba/")) { relative = relative.substring("saxarba/".length()); }
        return new File(Configuration.dataDir(), "models/buildings/" + relative + ".glb");
    }

    private Map<Integer, String> structureModels(Hex hex) {
        Map<Integer, String> models = new HashMap<>();
        if (hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL)) {
            List<Image> images = new ArrayList<>(gpuTileset.getSupers(hex));
            images.add(gpuTileset.getBase(hex));
            for (Image image : images) {
                String source = gpuTileset.imageSource(image).replace('\\', '/');
                int extension = source.lastIndexOf('.');
                if (extension > 0) {
                    String model = "buildings/" + source.substring(0, extension);
                    boolean legacy = new File(Configuration.dataDir(), "models/board/" + model + ".glb").isFile();
                    boolean custom = customBuildingFile(model).isFile();
                    for (int terrain : new int[] { Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL }) {
                        if ((legacy || custom)
                              && hex.containsTerrain(terrain) && gpuTileset.imageHasTerrain(image, terrain)) {
                            models.putIfAbsent(terrain, model);
                        }
                    }
                }
            }
        }
        return Map.copyOf(models);
    }

    private GroundArtwork captureGroundArtwork(Board board, Coords coords) {
        return groundArtwork.computeIfAbsent(coords, key -> {
            Hex ground = board.getHex(key).duplicate();
            ground.removeAllTerrains();
            Hex source = board.getHex(key);
            for (int terrain : GROUND_TERRAINS) {
                if (source.containsTerrain(terrain)) {
                    ground.addTerrain(source.getTerrain(terrain));
                }
            }
            BufferedImage image = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
            BufferedImage normal = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            Graphics2D normalGraphics = normal.createGraphics();
            try {
                UIUtil.setHighQualityRendering(graphics);
                UIUtil.setHighQualityRendering(normalGraphics);
                Image base = gpuTileset.getBase(ground);
                drawBaseTerrain(ground, graphics, base, base, mask(), 1);
                drawBaseTerrain(ground, normalGraphics, groundNormal(base), groundNormal(base), mask(), 1);
                // Select variants once, and use the same layering and large-image crop for their normal maps.
                for (Image overlay : gpuTileset.getSupers(ground)) {
                    if (overlay != null) {
                        graphics.drawImage(overlay, 0, 0, null);
                        normalGraphics.drawImage(groundNormal(overlay), 0, 0, null);
                    }
                }
            } finally {
                graphics.dispose();
                normalGraphics.dispose();
                // Filtered hexes are temporary, not board-owned tileset cache keys.
                gpuTileset.clearHex(ground);
            }
            return new GroundArtwork(image, normal);
        });
    }

    /** Normal assets are prepared offline from this exact source image; custom art without a map stays flat. */
    private Image groundNormal(Image artwork) {
        String source = gpuTileset.imageSource(artwork).replace('\\', '/');
        return groundNormals.computeIfAbsent(source, key -> {
            File file = new File(Configuration.dataDir(), "models/board/normals/" + key + ".png");
            if (!key.isEmpty() && file.isFile()) {
                Image normal = ImageUtil.loadImageFromFile(file.toString());
                if (normal != null && normal.getWidth(null) == artwork.getWidth(null)
                      && normal.getHeight(null) == artwork.getHeight(null)) {
                    return normal;
                }
            }
            BufferedImage flat = new BufferedImage(artwork.getWidth(null), artwork.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = flat.createGraphics();
            try {
                graphics.drawImage(artwork, 0, 0, null);
                graphics.setComposite(AlphaComposite.SrcIn);
                graphics.setColor(new Color(128, 128, 255));
                graphics.fillRect(0, 0, flat.getWidth(), flat.getHeight());
            } finally {
                graphics.dispose();
            }
            return flat;
        });
    }

    private DecalArtwork captureDecals(Board board, Coords coords) {
        return featureArtwork.computeIfAbsent(coords, key -> {
            Hex flat = board.getHex(key).duplicate();
            BufferedImage foliage = null;
            if (flat.containsAnyTerrainOf(Terrains.WOODS, Terrains.JUNGLE)) {
                Hex trees = flat.duplicate();
                trees.removeAllTerrains();
                for (int type : new int[] { Terrains.WOODS, Terrains.JUNGLE, Terrains.FOLIAGE_ELEV, Terrains.FLUFF }) {
                    if (flat.containsTerrain(type)) { trees.addTerrain(flat.getTerrain(type)); }
                }
                foliage = drawDecals(trees);
            }
            if (BoardRough.variant(flat) != 0) { flat.removeTerrain(Terrains.FLUFF); }
            for (int terrain : GROUND_TERRAINS) {
                flat.removeTerrain(terrain);
            }
            for (int terrain : MODEL_TERRAINS) {
                flat.removeTerrain(terrain);
            }
            BufferedImage full = drawDecals(flat);
            BufferedImage withoutLimbs = null;
            if (flat.containsAnyTerrainOf(Terrains.ARMS, Terrains.LEGS)) {
                flat.removeTerrain(Terrains.ARMS);
                flat.removeTerrain(Terrains.LEGS);
                withoutLimbs = drawDecals(flat);
            }
            // The GPU chooses the filtered image only after successfully loading the replacement mesh.
            return new DecalArtwork(full, withoutLimbs, foliage);
        });
    }

    private BufferedImage drawDecals(Hex flat) {
        BufferedImage image = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            UIUtil.setHighQualityRendering(graphics);
            drawSupers(flat, graphics);
        } finally {
            graphics.dispose();
            gpuTileset.clearHex(flat);
        }
        return image;
    }

    /** Draws the tileset's super images for a hex, which it layers over the base terrain. */
    private void drawSupers(Hex hex, Graphics2D graphics) {
        if (hex == null) {
            return;
        }
        List<Image> supers = gpuTileset.getSupers(hex);
        if (supers == null) {
            return;
        }
        for (Image image : supers) {
            if (image != null) {
                graphics.drawImage(image, 0, 0, null);
            }
        }
    }

    private static final int[] GROUND_TERRAINS = { Terrains.ROAD, Terrains.ROAD_FLUFF, Terrains.PAVEMENT, Terrains.SAND,
          Terrains.SNOW, Terrains.TUNDRA, Terrains.MUD, Terrains.SWAMP, Terrains.ICE, Terrains.MAGMA, Terrains.FIELDS,
          Terrains.RUBBLE };

    /** These have geometry or tactical markings in 3D; their painted symbols would duplicate that presentation. */
    private static final int[] MODEL_TERRAINS = { Terrains.WATER, Terrains.WATER_FLUFF, Terrains.RAPIDS, Terrains.HAZARDOUS_LIQUID,
          Terrains.BUILDING, Terrains.BLDG_CF, Terrains.BLDG_ELEV, Terrains.BLDG_FLUFF, Terrains.BLDG_ARMOR,
          Terrains.FUEL_TANK, Terrains.FUEL_TANK_CF, Terrains.FUEL_TANK_ELEV, Terrains.FUEL_TANK_MAGN,
          Terrains.BRIDGE, Terrains.BRIDGE_CF, Terrains.BRIDGE_ELEV, Terrains.BRIDGE_REPAIRED,
          Terrains.WOODS, Terrains.JUNGLE, Terrains.FOLIAGE_ELEV, Terrains.INDUSTRIAL, Terrains.ROUGH,
          Terrains.CLIFF_TOP, Terrains.CLIFF_BOTTOM, Terrains.INCLINE_TOP, Terrains.INCLINE_BOTTOM,
          Terrains.INCLINE_HIGH_TOP, Terrains.INCLINE_HIGH_BOTTOM, Terrains.FIRE, Terrains.SMOKE, Terrains.IMPASSABLE };

    @Override
    public void close() {
        clear();
        groundNormals.clear();
        if (gpuTileset != null) { gpuTileset.close(); gpuTileset = null; }
        hexMask = null;
    }

}
