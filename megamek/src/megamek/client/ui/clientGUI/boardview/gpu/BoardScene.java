/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.swing.ImageIcon;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.client.ui.clientGUI.boardview.BoardHexText;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Terrains;

/** A presentation snapshot. Only the Swing thread reads the game; the GPU thread owns rendering. */
record BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
      List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
      List<FiringLine> firingLines, List<RangeBorder> rangeBorders, List<BoardMarker> markers, BoardTactical tactical,
      List<RangeLabel> rangeLabels, BoardFieldOfView fieldOfView) {

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
          List<FiringLine> firingLines, List<RangeBorder> rangeBorders, List<BoardMarker> markers, BoardTactical tactical,
          List<RangeLabel> rangeLabels) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, firingLines,
              rangeBorders, markers, tactical, rangeLabels, BoardFieldOfView.EMPTY);
    }

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
          List<FiringLine> firingLines, List<RangeBorder> rangeBorders, List<BoardMarker> markers, BoardTactical tactical) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, firingLines,
              rangeBorders, markers, tactical, List.of());
    }

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
          List<FiringLine> firingLines, List<RangeBorder> rangeBorders, List<BoardMarker> markers) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, firingLines,
              rangeBorders, markers, BoardTactical.EMPTY);
    }

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
          List<FiringLine> firingLines, List<RangeBorder> rangeBorders) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, firingLines,
              rangeBorders, List.of());
    }

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, List.of(), List.of());
    }

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, null);
    }

    static Tile captureTile(megamek.common.Hex hex, BoardArtwork.HexImage pixels, Tile previous, PixelPool terrainImages) {
        return captureTile(hex, pixels, previous, terrainImages, neighbor -> null);
    }

    /** The board supplies neighbouring roads, whose course through this hex its scenery keeps clear of. */
    static Tile captureTile(megamek.common.Hex hex, BoardArtwork.HexImage pixels, Tile previous, PixelPool terrainImages,
          java.util.function.Function<Coords, megamek.common.Hex> board) {
        return new BoardScene.Tile(pixels.coords(), hex.getLevel(),
              hex.containsTerrain(Terrains.WATER) ? Math.max(0, hex.terrainLevel(Terrains.WATER)) : -1,
              hex.containsTerrain(Terrains.ICE),
              hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0,
              BoardFeatures.surface(hex),
              terrainImages.capture(pixels.terrain(), previous == null ? null : previous.ground()),
              terrainImages.capture(pixels.normals(), previous == null ? null : previous.normals()),
              terrainImages.captureOverlay(pixels.decals(), previous == null ? null : previous.decals()),
              terrainImages.capture(pixels.decalsWithoutLimbs(), previous == null ? null : previous.decalsWithoutLimbs()),
              terrainImages.capture(pixels.tactical(), previous == null ? null : previous.tactical()),
              BoardFeatures.capture(hex, pixels.coords(), pixels.structureModels(), pixels.blankTerrains(), board),
              pixels.text(), BoardLiquid.capture(hex),
              terrainImages.capture(pixels.tileset(), previous == null ? null : previous.tileset()),
              BoardFeatures.detailedGround(hex, pixels.structureModels(), pixels.blankTerrains()), BoardRoad.capture(hex),
              BoardFireSmoke.capture(hex), BoardFeatures.biome(hex), hex.containsTerrain(Terrains.IMPASSABLE),
              hex.containsTerrain(Terrains.BLACK_ICE) && hex.getTerrain(Terrains.BLACK_ICE).isBlackIceDetected(),
              hex.containsTerrain(Terrains.CLIFF_TOP) && hex.getTerrain(Terrains.CLIFF_TOP).hasExitsSpecified()
                    ? hex.getTerrain(Terrains.CLIFF_TOP).getExits() & 63 : 0, false, BoardSurfaceBlend.capture(hex),
              terrainImages.captureOverlay(pixels.bridge(), previous == null ? null : previous.bridge()));
    }

    /** World-space shadow travel per elevation level; null means directional shadows are disabled. */
    public record Light(float x, float y) { }

    public BoardScene {
        if (width < 1 || height < 1 || tiles.size() != width * height) {
            throw new IllegalArgumentException("A board snapshot must contain every hex in column order");
        }
        tiles = List.copyOf(tiles);
        units = List.copyOf(units);
        plannedPath = List.copyOf(plannedPath);
        commands = List.copyOf(commands);
        firingLines = List.copyOf(firingLines);
        rangeBorders = List.copyOf(rangeBorders);
        rangeLabels = List.copyOf(rangeLabels);
        markers = List.copyOf(markers);
    }

    /** Absolute endpoint levels and displayed attack modes, copied from the existing visible attack sprites. */
    record FiringLine(Waypoint source, Waypoint target, int rgb, boolean indirect, int attackerId, int targetId) { }

    /** The weapon handler already determines these edges, brackets and colours. No range rules live in the renderer. */
    record RangeBorder(Coords coords, int edges, int rgb, String label) { }

    /** Flat lettering at positions chosen by the weapon handler, independently oriented toward the camera. */
    record RangeLabel(Coords coords, int rgb, String label) { }

    public Tile tile(Coords coords) {
        return coords.getX() < 0 || coords.getY() < 0 || coords.getX() >= width || coords.getY() >= height
              ? null : tiles.get(coords.getX() * height + coords.getY());
    }

    /** Render materials. A tile's surface supplies its geology; groundCover can put shared SAND over any theme. */
    enum Surface {
        GRASS("terrain/rock"),
        DIRT("terrain/dirt"),
        SAND("terrain/sand"),
        ROCK("terrain/rock"),
        CONCRETE("terrain/concrete"),
        SNOW("terrain/snow"),
        LUNAR("terrain/lunar"),
        FUNGUS("sculpt/fungus-cliff"),
        DESERT("terrain/sand"),
        MARS("sculpt/mars-bedrock");

        final String wall;

        Surface(String wall) {
            this.wall = wall;
        }
    }

    enum FeatureKind { PROP, BUILDING, TREE, LIMB, SCATTER, BOULDER, ROUGH }

    /** Captured visual ground treatment; movement and cover modifiers remain in the game terrain. */
    enum Biome { NONE, FIELD, MARSH, QUICKSAND, MUD }

    /** Authored model or scatter shape, placement in tile pixels, and height in elevation levels. */
    record Feature(String asset, float x, float y, float rotation, float scale, float height, float elevation,
          FeatureKind kind, int bridgeExits) {
        Feature(String asset, float x, float y, float rotation, float scale, float height, float elevation,
              FeatureKind kind) {
            this(asset, x, y, rotation, scale, height, elevation, kind, 0);
        }

        Feature(String asset, float x, float y, float rotation, float scale, float height, float elevation) {
            this(asset, x, y, rotation, scale, height, elevation, FeatureKind.PROP);
        }
    }

    /**
     * Water depth -1 means dry. Ground and decals are independent from solid feature geometry. A bare tile is the
     * zero-gravity rock of {@link #lunar}: nothing lies loose or grows on it, so neither scatter nor field cover
     * dresses it. The tileset art is the hex's Saxarba art and the bridge art its bridge's, which only the Tactical View
     * draws.
     */
    record Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
          Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
          Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
          boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
          boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover, Pixels bridge) {
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, bare, groundCover, null);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits, boolean bare) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, bare, BoardSurfaceBlend.solid(surface == null ? Surface.GRASS : surface));
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, false);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce, 0);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, false);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, false);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, liquid, tileset, detailedGround, road, fireSmoke, Biome.NONE);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, liquid, tileset, detailedGround, road, BoardFireSmoke.NONE);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, liquid, tileset, detailedGround, BoardRoad.Kind.NONE);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, liquid, tileset, false);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text, BoardLiquid liquid) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, liquid, null);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels decalsWithoutLimbs,
              Pixels tactical, List<Feature> features, List<BoardHexText> text) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, decalsWithoutLimbs,
                  tactical, features, text, waterDepth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels normals, Pixels decals, Pixels tactical, List<Feature> features, List<BoardHexText> text) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals, null, tactical, features, text);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface, Pixels ground,
              Pixels decals, Pixels tactical, List<Feature> features, List<BoardHexText> text) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, ground, null, decals, tactical, features, text);
        }

        Tile withTactical(Pixels marking) {
            if (marking == tactical) { return this; }
            return new Tile(coords, elevation, waterDepth, frozen, roadExits, surface, ground, normals, decals,
                  decalsWithoutLimbs, marking, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome,
                  impassable, blackIce, cliffTopExits, bare, groundCover, bridge);
        }

        /** Zero-gravity presentation only: expose the liquid bed as bare rock, without editing the source hex. */
        Tile lunar() {
            int depth = Math.max(0, waterDepth);
            int level = elevation - depth;
            Feature bridge = BoardBridge.feature(this);
            List<Feature> kept = new ArrayList<>();
            int boulders = 0;
            for (Feature feature : features) {
                // Keep the deck at its original absolute level when the ground beneath it drops.
                if (feature == bridge && depth > 0) {
                    feature = new Feature(feature.asset(), feature.x(), feature.y(), feature.rotation(), feature.scale(),
                          feature.height(), feature.elevation() + depth, feature.kind(), feature.bridgeExits());
                }
                switch (feature.kind()) {
                    case TREE, SCATTER -> { }
                    case ROUGH -> { if (feature.asset().equals("rough/dragon-tooth")) { kept.add(feature); } }
                    case PROP -> { if (!feature.asset().equals("field")) { kept.add(feature); } }
                    // Nothing lies loose without gravity. Rough is bedrock breaking the surface: every third boulder
                    // becomes an outcrop three times its size, its height a bound. A repeated presentation keeps them.
                    case BOULDER -> {
                        if (feature.asset().equals(BoardRocks.OUTCROP)) {
                            kept.add(feature);
                        } else if (boulders++ % 3 == 0) {
                            kept.add(new Feature(BoardRocks.OUTCROP, feature.x(), feature.y(), feature.rotation(),
                                  feature.scale() * 3, feature.height() * 1.5f, feature.elevation(), feature.kind()));
                        }
                    }
                    default -> kept.add(feature);
                }
            }
            String levelPrefix = Messages.getString("BoardView1.LEVEL");
            String depthPrefix = Messages.getString("BoardView1.DEPTH");
            String heightPrefix = Messages.getString("BoardView1.HEIGHT") + " ";
            int bridgeHeight = bridge == null ? 0 : Math.round(bridge.elevation()) + depth;
            String lowFoliage = Messages.getString("BoardView1.LowFoliage");
            List<BoardHexText> labels = new ArrayList<>();
            boolean groundLabel = false;
            for (BoardHexText label : text) {
                if (label.text().startsWith(levelPrefix) || label.text().startsWith(depthPrefix)) {
                    if (!groundLabel && level != 0) {
                        labels.add(new BoardHexText(levelPrefix + level, label.baseline(), label.font(), label.argb(),
                              label.fromTop(), 0));
                    }
                    groundLabel = true;
                } else if (bridgeHeight > label.elevation() && label.text().startsWith(heightPrefix)) {
                    labels.add(new BoardHexText(heightPrefix + bridgeHeight, label.baseline(), label.font(), label.argb(),
                          label.fromTop(), bridgeHeight));
                } else if (!label.text().equals(lowFoliage)) {
                    labels.add(label);
                }
            }
            return new Tile(coords, level, -1, false, roadExits, Surface.LUNAR, ground, null, null, null,
                  tactical, kept, labels, BoardLiquid.NONE, null, true, road, fireSmoke, Biome.NONE,
                  impassable, false, cliffTopExits, true, BoardSurfaceBlend.solid(Surface.LUNAR), bridge());
        }

        Tile {
            features = List.copyOf(features);
            text = List.copyOf(text);
        }

        boolean water() {
            return waterDepth >= 0;
        }

        /** Inputs shared by terrain meshes, support surfaces and draped tactical geometry. */
        boolean sameGeometry(Tile other) {
            return this == other || coords.equals(other.coords) && elevation == other.elevation
                  && waterDepth == other.waterDepth && frozen == other.frozen && roadExits == other.roadExits
                  && cliffTopExits == other.cliffTopExits && bare == other.bare
                  && surface == other.surface && detailedGround == other.detailedGround && road == other.road && biome == other.biome
                  && liquid.equals(other.liquid) && features.equals(other.features) && groundCover.equals(other.groundCover);
        }
    }

    /**
     * Stand/flight elevation, occupied levels and heat come from the game. Heat -1 means this unit does not track
     * heat or is only a sensor contact; zero is a valid captured heat for an identified unit.
     */
    public record Unit(int id, int part, String name, Waypoint location, Pixels image, boolean sensorContact,
          Pixels annotations, int height, boolean airborne, UnitModel model, int outlineRgb, List<Coords> footprint,
          Attachment attachment, int heat) {
        public Unit {
            footprint = List.copyOf(footprint);
        }

        Unit(int id, int part, String name, Waypoint location, Pixels image, boolean sensorContact,
              Pixels annotations, int height, boolean airborne, UnitModel model, int outlineRgb, List<Coords> footprint,
              Attachment attachment) {
            this(id, part, name, location, image, sensorContact, annotations, height, airborne, model, outlineRgb,
                  footprint, attachment, -1);
        }

        Unit(int id, int part, String name, Waypoint location, Pixels image, boolean sensorContact,
              Pixels annotations, int height, boolean airborne, UnitModel model, int outlineRgb, List<Coords> footprint) {
            this(id, part, name, location, image, sensorContact, annotations, height, airborne, model, outlineRgb, footprint, null);
        }

        Unit withAttachment(Attachment value) {
            return new Unit(id, part, name, location, image, sensorContact, annotations, height, airborne, model, outlineRgb,
                  footprint, value, heat);
        }

        Unit at(Waypoint point) {
            return new Unit(id, part, name, point, image, sensorContact, annotations, height, airborne, model, outlineRgb,
                  point.footprint().isEmpty() ? List.of(point.coords()) : point.footprint(), attachment, heat);
        }

        Unit(int id, int part, String name, Waypoint location, Pixels image, boolean sensorContact,
              Pixels annotations, int height, boolean airborne, UnitModel model, int outlineRgb) {
            this(id, part, name, location, image, sensorContact, annotations, height, airborne, model, outlineRgb,
                  List.of(location.coords()));
        }
        Unit(int id, int part, String name, Waypoint location, Pixels image, boolean sensorContact,
              Pixels annotations, int height, boolean airborne) {
            this(id, part, name, location, image, sensorContact, annotations, height, airborne, null, 0xFFC0C0C0);
        }
    }

    /** Exterior occupancy observed on Swing. Internal bay passengers never acquire an attachment. */
    record Attachment(int carrierId, boolean hostile) { }

    enum Release { BOARD, CLIMB_DOWN, THROWN, JUMP, WATER }

    /** Both endpoints are authorized snapshots, including the destination and surviving figures after release. */
    record AttachmentChange(int boardId, Unit before, Unit after, Unit carrier, Release release, Waypoint destination) implements Animation {
        AttachmentChange(int boardId, Unit before, Unit after, Unit carrier, Release release) {
            this(boardId, before, after, carrier, release, after == null ? carrier.location() : after.location());
        }
        @Override
        public int entityId() { return before.id(); }
    }

    /**
     * Derived on Swing after visibility filtering; the render thread never reads an Entity.
     *
     * @param asset    the descriptor chosen for the unit, relative to the model directory
     * @param fallback the generic descriptor used when {@code asset} cannot be loaded, or {@code null}
     * @param variant  the key of the unit's loadout or formation inside the descriptor
     * @param figures  the number of figures a formation shows
     * @param twist    hexsides the displayed facing (a Mek's torso) is turned clockwise from the unit's own facing
     *                 (its legs), from {@code -2} to {@code 3}; {@code 0} when the two agree
     * @param damage   the locations to show as lost or destroyed
     * @param state    immutable live components and posture, or {@code null} for legacy review fixtures
     */
    record UnitModel(String asset, String fallback, String variant, int figures, int twist, LocationDamage damage,
          UnitModelState state) {
        UnitModel(String asset, String fallback, String variant, int figures, int twist, LocationDamage damage) {
            this(asset, fallback, variant, figures, twist, damage, null);
        }

        UnitModel(String asset, String fallback, String variant, int figures) {
            this(asset, fallback, variant, figures, 0, LocationDamage.NONE);
        }
    }

    /**
     * The locations of a unit that a model shows as lost, each by the game's own abbreviation ({@code LA},
     * {@code RT}, ...), which is also the name of that location's part in the model.
     *
     * @param removed locations shown as gone altogether: a lost arm
     * @param wrecked locations shown still in place but burnt out: a destroyed leg, side torso or head, which the
     *                rest of the model stands on or hangs from
     */
    record LocationDamage(Set<String> removed, Set<String> wrecked, Map<String, UnitDamageDisplay.Stage> stages) {
        static final LocationDamage NONE = new LocationDamage(Set.of(), Set.of());

        LocationDamage(Set<String> removed, Set<String> wrecked) { this(removed, wrecked, Map.of()); }

        LocationDamage {
            removed = Set.copyOf(removed);
            wrecked = Set.copyOf(wrecked);
            stages = Map.copyOf(stages);
        }

        boolean isNone() {
            return removed.isEmpty() && wrecked.isEmpty() && stages.isEmpty();
        }
    }

    /** Observed aerospace state, kept separate from the absolute height used for drawing. */
    public enum AeroState { LANDED, ELEVATED, AIRBORNE }

    /** Elevation is an absolute render level, already including the hex level for airborne units. */
    public record Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause,
          AeroState aeroState, List<Coords> footprint, megamek.common.units.UnitLocation.Form form,
          megamek.common.units.FallSide fallSide, Boolean hullDown) {
        public Waypoint {
            footprint = List.copyOf(footprint);
        }

        public Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause,
              AeroState aeroState, List<Coords> footprint, megamek.common.units.UnitLocation.Form form,
              megamek.common.units.FallSide fallSide) {
            this(coords, elevation, facing, proneCause, aeroState, footprint, form, fallSide, null);
        }

        public Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause,
              AeroState aeroState, List<Coords> footprint, megamek.common.units.UnitLocation.Form form) {
            this(coords, elevation, facing, proneCause, aeroState, footprint, form, null);
        }

        public Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause,
              AeroState aeroState, List<Coords> footprint) {
            this(coords, elevation, facing, proneCause, aeroState, footprint, null);
        }

        public Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause,
              AeroState aeroState) {
            this(coords, elevation, facing, proneCause, aeroState, List.of());
        }
        public Waypoint(Coords coords, float elevation, float facing) {
            this(coords, elevation, facing, null, null);
        }

        public Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause) {
            this(coords, elevation, facing, proneCause, null);
        }

        Waypoint withProneCause(megamek.common.units.ProneCause cause) {
            return new Waypoint(coords, elevation, facing, cause, aeroState, footprint, form, fallSide, hullDown);
        }

        Waypoint withAeroState(AeroState state) {
            return new Waypoint(coords, elevation, facing, proneCause, state, footprint, form, fallSide, hullDown);
        }

        Waypoint withFootprint(List<Coords> occupied) {
            return new Waypoint(coords, elevation, facing, proneCause, aeroState, occupied, form, fallSide, hullDown);
        }

        Waypoint withForm(megamek.common.units.UnitLocation.Form value) {
            return new Waypoint(coords, elevation, facing, proneCause, aeroState, footprint, value, fallSide, hullDown);
        }

        Waypoint withFallSide(megamek.common.units.FallSide side) {
            return new Waypoint(coords, elevation, facing, proneCause, aeroState, footprint, form, side, hullDown);
        }

        Waypoint withHullDown(Boolean value) {
            return new Waypoint(coords, elevation, facing, proneCause, aeroState, footprint, form, fallSide, value);
        }

        /** Captured fitting metadata does not create another movement step at a queued path boundary. */
        boolean samePose(Waypoint other) {
            return facing == other.facing && samePoseExceptFacing(other);
        }

        boolean samePoseExceptFacing(Waypoint other) {
            return coords.equals(other.coords) && elevation == other.elevation
                  && proneCause == other.proneCause && fallSide == other.fallSide && aeroState == other.aeroState
                  && java.util.Objects.equals(form, other.form) && java.util.Objects.equals(hullDown, other.hullDown);
        }
    }

    BoardScene withTiles(List<Tile> shown) {
        if (shown == tiles) { return this; }
        return new BoardScene(boardId, width, height, shown, units, plannedPath, selectedId, phase, commands, light,
              firingLines, rangeBorders, markers, tactical, rangeLabels, fieldOfView);
    }

    BoardScene withUnits(List<Unit> shown) {
        return new BoardScene(boardId, width, height, tiles, shown, plannedPath, selectedId, phase, commands, light,
              firingLines, rangeBorders, markers, tactical, rangeLabels, fieldOfView);
    }

    /** Board artwork, terrain and authorized contacts advance with the queue; interactive tools stay live. */
    BoardScene duringPlayback(BoardScene settled, boolean hideMovement) {
        if (settled == this && !hideMovement) {
            return this;
        }
        BoardScene world = settled == null ? this : settled;
        var heldMarkers = settled == null ? Stream.<BoardMarker>empty() : settled.markers.stream();
        var shownMarkers = Stream.concat(heldMarkers.filter(marker ->
                    (!hideMovement || marker.kind() != BoardMarker.Kind.COLLAPSE_WARNING)
                          && marker.kind() != BoardMarker.Kind.PLAYER_NOTE),
              markers.stream().filter(marker -> marker.kind() == BoardMarker.Kind.PLAYER_NOTE)).toList();
        return new BoardScene(boardId, width, height, world.tiles, world.units,
              hideMovement ? List.of() : world.plannedPath, selectedId, phase, commands, light,
              hideMovement ? List.of() : world.firingLines, hideMovement ? List.of() : world.rangeBorders, shownMarkers,
              tactical.duringPlayback(settled == null ? BoardTactical.EMPTY : settled.tactical, hideMovement),
              hideMovement ? List.of() : world.rangeLabels, hideMovement ? BoardFieldOfView.EMPTY : world.fieldOfView);
    }

    /** Excludes HUD commands and other live controls, which do not need a playback checkpoint. */
    boolean samePlaybackState(BoardScene other) {
        return other != null && boardId == other.boardId && width == other.width && height == other.height
              && tiles.equals(other.tiles) && units.equals(other.units) && markers.equals(other.markers)
              && tactical.equals(other.tactical) && plannedPath.equals(other.plannedPath)
              && firingLines.equals(other.firingLines) && rangeBorders.equals(other.rangeBorders)
              && rangeLabels.equals(other.rangeLabels) && fieldOfView.equals(other.fieldOfView);
    }

    /** The action marshals back to Swing and rechecks the original button before invoking it. */
    public record Command(String id, String label, String detail, boolean enabled, boolean commit, boolean boardTool,
          List<Command> children, Runnable action) {
        public Command {
            children = List.copyOf(children);
        }

        public Command(String label, boolean enabled, Runnable action) {
            this(label, label, "", enabled, false, false, List.of(), action);
        }

        public Command(String id, String label, String detail, boolean enabled, boolean commit,
              List<Command> children, Runnable action) {
            this(id, label, detail, enabled, commit, false, children, action);
        }
    }

    public record Context(Coords coords, List<Command> commands) {
        public Context {
            commands = List.copyOf(commands);
        }
    }

    /** Swing owns attack selection and calculations; the GL thread receives only their presentation. */
    public record Attack(String targetName, String weaponDetails, String targetDetails, int selectedWeapon,
          List<String> orders) {
        public Attack {
            orders = List.copyOf(orders);
        }
    }

    interface Animation {
        int entityId();
        int boardId();
    }

    /** Visibility loss invalidates historical presentation immediately, including while playback is paused. */
    record Concealed(int entityId, int boardId) implements Animation { }

    /** An immutable, visibility-filtered checkpoint in packet order; it consumes no animation time. */
    record SceneUpdate(BoardScene scene) implements Animation {
        @Override
        public int entityId() { return -1; }

        @Override
        public int boardId() { return scene.boardId(); }
    }

    /** Target type is captured with the visible event, independently of its chosen model or sprite. */
    record Combat(megamek.common.ResolvedAttack result, Unit attacker, Unit target, Waypoint destination,
          boolean conventionalInfantryTarget) implements Animation {
        Combat(megamek.common.ResolvedAttack result, Unit attacker, Unit target, Waypoint destination) {
            this(result, attacker, target, destination, false);
        }

        @Override
        public int entityId() { return attacker.id(); }

        @Override
        public int boardId() { return result.attacker().boardId(); }
    }

    record Conversion(int boardId, Unit before, Unit after) implements Animation {
        @Override
        public int entityId() { return after.id(); }
    }

    public record Movement(int entityId, int boardId, List<Waypoint> path, EntityMovementType type, int jumpMP, int movementMP,
          Unit unit, float gravity) implements Animation {
        public Movement {
            path = List.copyOf(path);
        }

        public Movement(int entityId, int boardId, List<Waypoint> path, EntityMovementType type, int jumpMP, int movementMP) {
            this(entityId, boardId, path, type, jumpMP, movementMP, null);
        }

        public Movement(int entityId, int boardId, List<Waypoint> path, EntityMovementType type, int jumpMP, int movementMP, Unit unit) {
            this(entityId, boardId, path, type, jumpMP, movementMP, unit, 1);
        }

        public Movement(int entityId, int boardId, List<Waypoint> path, EntityMovementType type, int jumpMP) {
            this(entityId, boardId, path, type, jumpMP, 0);
        }

        boolean forced() { return type == EntityMovementType.MOVE_NONE; }
    }

    /** Immutable pixel ownership avoids accessing AWT images or the tileset from the GL thread. */
    public static final class Pixels {
        private final int width;
        private final int height;
        private final int[] argb;
        private final int[] runEnds;
        private final int hash;

        public Pixels(BufferedImage image) {
            this(image.getWidth(), image.getHeight(), read(image, null));
        }

        static Pixels copy(Image source) {
            ImageIcon loaded = new ImageIcon(source);
            BufferedImage copy = new BufferedImage(Math.max(1, loaded.getIconWidth()), Math.max(1, loaded.getIconHeight()),
                  BufferedImage.TYPE_INT_ARGB);
            var graphics = copy.createGraphics();
            try {
                graphics.drawImage(loaded.getImage(), 0, 0, null);
            } finally {
                graphics.dispose();
            }
            return new Pixels(copy);
        }

        private static int[] read(BufferedImage image, int[] target) {
            int width = image.getWidth();
            int height = image.getHeight();
            if (image.getType() != BufferedImage.TYPE_INT_ARGB) {
                return image.getRGB(0, 0, width, height, target, 0, width);
            }
            int[] result = target == null ? new int[width * height] : target;
            Raster raster = image.getRaster();
            DataBufferInt data = (DataBufferInt) raster.getDataBuffer();
            SinglePixelPackedSampleModel sample = (SinglePixelPackedSampleModel) raster.getSampleModel();
            int offset = data.getOffset() + sample.getOffset(raster.getMinX() - raster.getSampleModelTranslateX(),
                  raster.getMinY() - raster.getSampleModelTranslateY());
            int[] source = data.getData();
            for (int row = 0; row < height; row++) {
                System.arraycopy(source, offset + row * sample.getScanlineStride(), result, row * width, width);
            }
            return result;
        }

        private Pixels(int width, int height, int[] argb) {
            this(width, height, argb, null, 31 * (31 * width + height) + Arrays.hashCode(argb));
        }

        private Pixels(int width, int height, int[] argb, int[] runEnds, int hash) {
            this.width = width;
            this.height = height;
            this.argb = argb;
            this.runEnds = runEnds;
            this.hash = hash;
        }

        /** Lossless storage for sparse material masks, without reducing their resolution or color precision. */
        Pixels compact() {
            if (runEnds != null) { return this; }
            int runs = 1;
            for (int i = 1; i < argb.length; i++) { if (argb[i] != argb[i - 1]) { runs++; } }
            if (runs * 2 >= argb.length) { return this; }
            int[] colors = new int[runs], ends = new int[runs];
            int run = 0;
            for (int i = 1; i <= argb.length; i++) {
                if (i == argb.length || argb[i] != argb[i - 1]) {
                    colors[run] = argb[i - 1];
                    ends[run++] = i;
                }
            }
            return new Pixels(width, height, colors, ends, hash);
        }

        public static Pixels capture(BufferedImage image, Pixels previous) {
            if (image == null) {
                return null;
            }
            Pixels next = new Pixels(image);
            return next.equals(previous) ? previous : next;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof Pixels pixels) || width != pixels.width || height != pixels.height || hash != pixels.hash) {
                return false;
            }
            if ((runEnds == null) == (pixels.runEnds == null)) {
                return Arrays.equals(argb, pixels.argb) && Arrays.equals(runEnds, pixels.runEnds);
            }
            for (int i = 0; i < width * height; i++) { if (rgba(i) != pixels.rgba(i)) { return false; } }
            return true;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int rgba(int index) {
            if (runEnds != null) {
                int run = Arrays.binarySearch(runEnds, index + 1);
                index = run < 0 ? -run - 1 : run;
            }
            return Integer.rotateLeft(argb[index], 8);
        }

        /** Sequential atlas upload decodes each compressed run once, without expanding a second image on the heap. */
        void writeRgba(IntBuffer target, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, width * height);
            int end = offset + length;
            if (runEnds == null) {
                for (int i = offset; i < end; i++) { target.put(Integer.rotateLeft(argb[i], 8)); }
                return;
            }
            int run = Arrays.binarySearch(runEnds, offset + 1);
            if (run < 0) { run = -run - 1; }
            while (offset < end) {
                int stop = Math.min(end, runEnds[run]), color = Integer.rotateLeft(argb[run++], 8);
                while (offset < stop) { target.put(color); offset++; }
            }
        }
    }

    /** Exact sharing, owned by the Swing source. Only artwork still referenced by the current scene is retained. */
    static final class PixelPool {
        private final Map<Pixels, Pixels> images = new HashMap<>();
        private final Map<Integer, int[]> buffers = new HashMap<>();

        Pixels capture(BufferedImage image, Pixels previous) {
            if (image == null) {
                return null;
            }
            int[] buffer = buffers.computeIfAbsent(image.getWidth() * image.getHeight(), size -> new int[size]);
            Pixels.read(image, buffer);
            Pixels lookup = new Pixels(image.getWidth(), image.getHeight(), buffer);
            if (lookup.equals(previous)) {
                return previous;
            }
            Pixels shared = images.get(lookup);
            if (shared != null) {
                return shared;
            }
            Pixels owned = new Pixels(lookup.width, lookup.height, buffer.clone());
            images.put(owned, owned);
            return owned;
        }

        Pixels captureOverlay(BufferedImage image, Pixels previous) {
            Pixels pixels = capture(image, previous);
            return pixels == null || Arrays.stream(pixels.argb).allMatch(pixel -> (pixel >>> 24) == 0) ? null : pixels;
        }

        void retain(List<Tile> tiles) {
            Set<Pixels> used = new HashSet<>();
            for (Tile tile : tiles) {
                used.add(tile.ground());
                used.add(tile.normals());
                used.add(tile.decals());
                used.add(tile.decalsWithoutLimbs());
                used.add(tile.tactical());
                used.add(tile.tileset());
                used.add(tile.bridge());
            }
            images.keySet().retainAll(used);
        }

        void clear() {
            images.clear();
            buffers.clear();
        }
    }
}
