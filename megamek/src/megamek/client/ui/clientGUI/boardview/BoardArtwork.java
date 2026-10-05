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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import megamek.MMConstants;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.BoardRough;
import megamek.client.ui.clientGUI.boardview.gpu.BoardSurfaceBlend;
import megamek.client.ui.tileset.HexTileset;
import megamek.client.ui.tileset.TilesetManager;
import megamek.client.ui.util.UIUtil;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.util.ImageUtil;

/** EDT-owned artwork composition. Game and map sources share this service; it never constructs a view. */
public final class BoardArtwork implements AutoCloseable {
    /**
     * {@code tileset} is the hex's Saxarba art, which the Tactical View draws on its plain hex column, and
     * {@code bridge} its bridge's art, which that view lays on the deck.
     */
    public record HexImage(Coords coords, BufferedImage terrain, BufferedImage normals, BufferedImage decals,
          BufferedImage decalsWithoutLimbs, BufferedImage tactical, List<BoardHexText> text,
          Map<Integer, String> structureModels, BufferedImage tileset, BufferedImage bridge, Set<Integer> blankTerrains,
          Scenery scenery, BufferedImage tilesetDecals, BufferedImage tilesetScenery) {
        public HexImage(Coords coords, BufferedImage terrain, BufferedImage normals, BufferedImage decals,
              BufferedImage decalsWithoutLimbs, BufferedImage tactical, List<BoardHexText> text,
              Map<Integer, String> structureModels, BufferedImage tileset, BufferedImage bridge, Set<Integer> blankTerrains,
              Scenery scenery) {
            this(coords, terrain, normals, decals, decalsWithoutLimbs, tactical, text, structureModels, tileset, bridge,
                  blankTerrains, scenery, null, null);
        }
        public HexImage(Coords coords, BufferedImage terrain, BufferedImage normals, BufferedImage decals,
              BufferedImage decalsWithoutLimbs, BufferedImage tactical, List<BoardHexText> text,
              Map<Integer, String> structureModels, BufferedImage tileset, BufferedImage bridge, Set<Integer> blankTerrains) {
            this(coords, terrain, normals, decals, decalsWithoutLimbs, tactical, text, structureModels, tileset, bridge,
                  blankTerrains, Scenery.EMPTY);
        }
        public HexImage(Coords coords, BufferedImage terrain, BufferedImage normals, BufferedImage decals,
              BufferedImage decalsWithoutLimbs, BufferedImage tactical, List<BoardHexText> text,
              Map<Integer, String> structureModels, BufferedImage tileset) {
            this(coords, terrain, normals, decals, decalsWithoutLimbs, tactical, text, structureModels, tileset, null);
        }
        public HexImage(Coords coords, BufferedImage terrain, BufferedImage normals, BufferedImage decals,
              BufferedImage decalsWithoutLimbs, BufferedImage tactical, List<BoardHexText> text,
              Map<Integer, String> structureModels, BufferedImage tileset, BufferedImage bridge) {
            this(coords, terrain, normals, decals, decalsWithoutLimbs, tactical, text, structureModels, tileset, bridge,
                  Set.of());
        }
        public HexImage { blankTerrains = Set.copyOf(blankTerrains); }
    }
    /** Selected cosmetic layers, captured on Swing; no terrain matching or game objects reach the GL thread. */
    public record Scenery(List<String> models, Set<Integer> terrains, Set<Integer> modelTerrains, int cosmeticRoadExits) {
        public static final Scenery EMPTY = new Scenery(List.of(), Set.of(), Set.of(), 0);
        public Scenery {
            models = List.copyOf(models);
            terrains = Set.copyOf(terrains);
            modelTerrains = Set.copyOf(modelTerrains);
        }
    }
    private record GroundArtwork(BufferedImage color, BufferedImage normal) { }
    private record DecalArtwork(BufferedImage full, BufferedImage withoutLimbs, BufferedImage tileset,
          BufferedImage bridge, Scenery scenery, BufferedImage tilesetDecals, BufferedImage tilesetScenery) { }
    private final Map<Coords, GroundArtwork> groundArtwork = new HashMap<>();
    private final Map<Coords, DecalArtwork> featureArtwork = new HashMap<>();
    private final Map<String, Image> groundNormals = new HashMap<>();
    private final Map<String, Boolean> sceneryModels = new HashMap<>();
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
        sceneryModels.clear();
        hexMask = null;
    }

    public HexImage capture(Board board, Coords coords, boolean includeArtwork) {
        // Bound the transient raster cache; immutable snapshots intern and retain the pixels they actually use.
        if (groundArtwork.size() > 256) { clear(); }
        Hex hex = board.getHex(coords);
        GroundArtwork ground = includeArtwork ? captureGroundArtwork(board, coords) : null;
        Map<Integer, String> models = includeArtwork ? structureModels(hex) : Map.of();
        Set<Integer> blank = includeArtwork ? gpuTileset.blankTerrainTypes(hex) : Set.of();
        DecalArtwork decals = includeArtwork
              ? captureDecals(board, coords, models, blank) : null;
        gpuTileset.clearHex(hex);
        return new HexImage(coords, ground == null ? null : ground.color(), ground == null ? null : ground.normal(),
              decals == null ? null : decals.full(), decals == null ? null : decals.withoutLimbs(), null,
              BoardHexText.capture(coords, hex, board, 1, LABEL_FONT, LABEL_FONT),
              models, decals == null ? null : decals.tileset(), decals == null ? null : decals.bridge(), blank,
              decals == null ? Scenery.EMPTY : decals.scenery(), decals == null ? null : decals.tilesetDecals(),
              decals == null ? null : decals.tilesetScenery());
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

    private DecalArtwork captureDecals(Board board, Coords coords, Map<Integer, String> structures, Set<Integer> blank) {
        return featureArtwork.computeIfAbsent(coords, key -> {
            Hex flat = board.getHex(key).duplicate();
            BufferedImage bridge = drawBridge(board.getHex(key));
            for (int terrain : GROUND_TERRAINS) {
                flat.removeTerrain(terrain);
            }
            for (int terrain : MODEL_TERRAINS) {
                flat.removeTerrain(terrain);
            }
            for (int terrain : SCENERY_TERRAINS) { flat.removeTerrain(terrain); }
            BufferedImage full = drawDecals(flat);
            BufferedImage tilesetDecals = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
            BufferedImage tilesetScenery = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
            Set<String> placedSources = new HashSet<>();
            Scenery scenery = drawScenery(board.getHex(key), structures, blank, full, tilesetDecals, tilesetScenery, placedSources);
            BufferedImage tileset = drawTileset(board.getHex(key), structures, placedSources);
            BufferedImage withoutLimbs = null;
            if (flat.containsAnyTerrainOf(Terrains.ARMS, Terrains.LEGS)) {
                flat.removeTerrain(Terrains.ARMS);
                flat.removeTerrain(Terrains.LEGS);
                withoutLimbs = drawDecals(flat);
                drawScenery(board.getHex(key), structures, blank, withoutLimbs, null, null, null);
            }
            // The GPU chooses the filtered image only after successfully loading the replacement mesh.
            return new DecalArtwork(full, withoutLimbs, tileset, bridge, scenery, tilesetDecals, tilesetScenery);
        });
    }

    /**
     * Resolve from the original selected layers. Matching a hex after removing roads/woods/buildings loses composite
     * rules (parked cars, port containers, gardens, etc.). A sibling scenery GLB replaces only that layer; flat
     * artwork stays an alpha decal on the existing surface. Original images remain available to Tactical View.
     */
    private Scenery drawScenery(Hex hex, Map<Integer, String> structures, Set<Integer> blank,
          BufferedImage decals, BufferedImage tilesetDecals, BufferedImage tilesetScenery, Set<String> placedSources) {
        List<String> models = new ArrayList<>();
        List<Image> paint = new ArrayList<>();
        Set<Integer> covered = new HashSet<>(), modeled = new HashSet<>();
        int cosmeticRoadExits = 0;
        List<Image> layers = new ArrayList<>(gpuTileset.getSupers(hex));
        layers.addAll(gpuTileset.getOrthographic(hex));
        layers.add(gpuTileset.getBase(hex));
        Graphics2D graphics = decals.createGraphics();
        Graphics2D original = tilesetDecals == null ? null : tilesetDecals.createGraphics();
        Graphics2D objects = tilesetScenery == null ? null : tilesetScenery.createGraphics();
        try {
            UIUtil.setHighQualityRendering(graphics);
            if (original != null) { UIUtil.setHighQualityRendering(original); }
            if (objects != null) { UIUtil.setHighQualityRendering(objects); }
            for (Image layer : layers) {
                Set<Integer> types = new HashSet<>();
                for (int type : hex.getTerrainTypes()) {
                    if (gpuTileset.imageHasTerrain(layer, type)) { types.add(type); }
                }
                boolean decoration = false;
                for (int type : SCENERY_TERRAINS) { decoration |= types.contains(type); }
                decoration |= types.contains(Terrains.ROAD) && hex.terrainLevel(Terrains.ROAD) == 2;
                // Pavement variants belong to the ground; repainting them as scenery hides the native concrete.
                if (!decoration) { continue; }
                covered.addAll(types);
                if (types.stream().anyMatch(structures::containsKey)
                      || types.contains(Terrains.ROAD_FLUFF) && hex.terrainLevel(Terrains.ROAD_FLUFF) == 1
                            && types.contains(Terrains.ROAD)
                      || types.contains(Terrains.FLUFF) && (BoardRough.variant(hex) != 0
                            || hex.terrainLevel(Terrains.FLUFF) == 12 && types.contains(Terrains.WOODS))) { continue; }
                String source = gpuTileset.imageSource(layer).replace('\\', '/');
                // Both GPU views use the fitted quay wall. Its old perspective sprite must not also be
                // projected onto the seabed or baked into the Tactical View's ground artwork.
                if (source.startsWith("Structured_Pavement/Fluff/quay_fluff")) {
                    if (placedSources != null) { placedSources.add(source); }
                    continue;
                }
                int extension = source.lastIndexOf('.');
                String asset = extension < 0 ? "" : "scenery/" + source.substring(0, extension);
                boolean model = !asset.isEmpty() && sceneryModels.computeIfAbsent(asset,
                      name -> new File(Configuration.dataDir(), "models/board/" + name + ".glb").isFile());
                // Keep ground paint in the tileset's authored order, below structured pavement edges.
                if (original != null && (model || !types.contains(Terrains.GROUND_FLUFF))) {
                    (model ? objects : original).drawImage(layer, 0, 0, null);
                    placedSources.add(source);
                }
                if (model) {
                    models.add(asset);
                    modeled.addAll(types);
                    // The selected parking sprite specifies a visual route even without gameplay ROAD terrain.
                    // These are ordinary hex-direction bits consumed by the existing road engine.
                    cosmeticRoadExits |= switch (source) {
                        case "fluff/cars_1.gif", "fluff/cars_4.gif" -> 18;
                        case "fluff/cars_2.gif", "fluff/cars_5.gif", "fluff/cars_7.gif" -> 36;
                        case "fluff/cars_3.gif", "fluff/cars_6.gif", "fluff/cars_8.gif" -> 9;
                        default -> 0; // The 2b/3b variants intentionally omit the road.
                    };
                } else if (types.contains(Terrains.ROAD) && hex.terrainLevel(Terrains.ROAD) >= 1
                      && hex.terrainLevel(Terrains.ROAD) <= Terrains.ROAD_LVL_GRAVEL) {
                    // Road-fluff surface variants use the same material, joins and relief as every other road.
                } else {
                    paint.add(layer);
                }
            }
            Scenery scenery = new Scenery(models, covered, modeled, cosmeticRoadExits);
            boolean nativeTransition = BoardSurfaceBlend.replacesTransition(hex, structures, blank, scenery);
            for (Image layer : paint) {
                if (!nativeTransition || !gpuTileset.imageHasTerrain(layer, Terrains.GROUND_FLUFF)) {
                    graphics.drawImage(layer, 0, 0, null);
                }
            }
            return scenery;
        } finally {
            graphics.dispose();
            if (original != null) { original.dispose(); }
            if (objects != null) { objects.dispose(); }
        }
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

    /**
     * The hex's Saxarba art, as the Tactical View shows it: its base, its supers and its orthographic images, without
     * the incline and cliff edges, which the view's BoardRim shades from the levels instead, without the bridge, which
     * it lays on the deck, and without structures or scenery. Scenery retains its original selected artwork in a
     * separate overlay, so both views can put it on roofs and lakebeds instead of baking it into the column top.
     * Pavement uses its full variant: the shared concrete geometry supplies its outline in both GPU views.
     * Water also fills its footprint: a painted shore on the lakebed would duplicate the column's bank wall.
     */
    private BufferedImage drawTileset(Hex source, Map<Integer, String> structures, Set<String> placedSources) {
        Hex hex = source.duplicate();
        for (int terrain : DROP_TERRAINS) { hex.removeTerrain(terrain); }
        for (int terrain : BRIDGE_TERRAINS) { hex.removeTerrain(terrain); }
        for (int structure : structures.keySet()) {
            for (int terrain : STRUCTURE_TERRAINS.get(structure)) { hex.removeTerrain(terrain); }
        }
        if (hex.containsTerrain(Terrains.PAVEMENT)) {
            hex.addTerrain(new Terrain(Terrains.PAVEMENT, hex.terrainLevel(Terrains.PAVEMENT), true, 63));
            if (BoardSurfaceBlend.hasTransition(hex)) { hex.removeTerrain(Terrains.GROUND_FLUFF); }
        }
        if (hex.containsTerrain(Terrains.WATER)) {
            hex.addTerrain(new Terrain(Terrains.WATER, hex.terrainLevel(Terrains.WATER), true, 63));
        }
        BufferedImage image = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            UIUtil.setHighQualityRendering(graphics);
            Image base = gpuTileset.getBase(hex);
            drawBaseTerrain(hex, graphics, base, base, mask(), 1);
            List<Image> layers = new ArrayList<>(gpuTileset.getSupers(hex));
            layers.addAll(gpuTileset.getOrthographic(hex));
            for (Image layer : layers) {
                if (layer != null && !placedSources.contains(gpuTileset.imageSource(layer).replace('\\', '/'))) {
                    graphics.drawImage(layer, 0, 0, null);
                }
            }
        } finally {
            graphics.dispose();
            gpuTileset.clearHex(hex);
        }
        return image;
    }

    /** The bridge's own Saxarba art, its supers and orthographic images; null without a bridge. */
    private BufferedImage drawBridge(Hex source) {
        if (!source.containsTerrain(Terrains.BRIDGE)) { return null; }
        Hex bridge = source.duplicate();
        bridge.removeAllTerrains();
        for (int terrain : BRIDGE_TERRAINS) {
            if (source.containsTerrain(terrain)) { bridge.addTerrain(source.getTerrain(terrain)); }
        }
        BufferedImage image = drawDecals(bridge);
        Graphics2D graphics = image.createGraphics();
        try {
            for (Image orthographic : gpuTileset.getOrthographic(bridge)) {
                if (orthographic != null) { graphics.drawImage(orthographic, 0, 0, null); }
            }
        } finally {
            graphics.dispose();
            gpuTileset.clearHex(bridge);
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

    private static final int[] DROP_TERRAINS = { Terrains.CLIFF_TOP, Terrains.CLIFF_BOTTOM, Terrains.INCLINE_TOP,
          Terrains.INCLINE_BOTTOM, Terrains.INCLINE_HIGH_TOP, Terrains.INCLINE_HIGH_BOTTOM };

    private static final int[] BRIDGE_TERRAINS = { Terrains.BRIDGE, Terrains.BRIDGE_CF, Terrains.BRIDGE_ELEV };

    /** Each structure with the terrains describing it, any of which its tileset images may match. */
    private static final Map<Integer, int[]> STRUCTURE_TERRAINS = Map.of(
          Terrains.BUILDING, new int[] { Terrains.BUILDING, Terrains.BLDG_CF, Terrains.BLDG_ELEV, Terrains.BLDG_FLUFF,
                Terrains.BLDG_ARMOR, Terrains.BLDG_CLASS },
          Terrains.FUEL_TANK, new int[] { Terrains.FUEL_TANK, Terrains.FUEL_TANK_CF, Terrains.FUEL_TANK_ELEV,
                Terrains.FUEL_TANK_MAGN },
          Terrains.INDUSTRIAL, new int[] { Terrains.INDUSTRIAL });

    private static final int[] GROUND_TERRAINS = { Terrains.ROAD, Terrains.ROAD_FLUFF, Terrains.PAVEMENT, Terrains.SAND,
          Terrains.SNOW, Terrains.TUNDRA, Terrains.MUD, Terrains.SWAMP, Terrains.ICE, Terrains.MAGMA, Terrains.FIELDS,
          Terrains.RUBBLE };

    private static final int[] SCENERY_TERRAINS = { Terrains.FLUFF, Terrains.GROUND_FLUFF, Terrains.ROAD_FLUFF,
          Terrains.WATER_FLUFF, Terrains.FORTIFIED, Terrains.GEYSER, Terrains.SOLARIS_ELEVATOR,
          Terrains.INDUSTRIAL_ELEVATOR, Terrains.RUBBLE };

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
        sceneryModels.clear();
        if (gpuTileset != null) { gpuTileset.close(); gpuTileset = null; }
        hexMask = null;
    }

}
