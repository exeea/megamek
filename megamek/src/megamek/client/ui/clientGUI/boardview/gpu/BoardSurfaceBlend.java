/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;

/** Surface cover at a world position. Rendering and grass placement query the same immutable board snapshot. */
public final class BoardSurfaceBlend {
    static final float WIDTH_METRES = 7f;
    static final float FOOT_METRES = .9f;
    // Render-only covers derived from BoardLiquid; the board's terrain families and rules remain unchanged.
    static final int CRUST = BoardScene.Surface.values().length, BANK = CRUST + 1, FAMILIES = BANK + 1;

    record Cover(float grass, float dirt, float sand, float rock, float concrete, float snow, float lunar,
          float fungus, float desert, float mars, float volcano, float tropical, float crust, float bank, float interpolation) {
        Cover(float grass, float dirt, float sand, float rock, float concrete, float snow, float lunar,
              float fungus, float desert, float mars, float volcano, float tropical, float crust, float bank) {
            this(grass, dirt, sand, rock, concrete, snow, lunar, fungus, desert, mars, volcano, tropical, crust, bank, 0);
        }
        float weight(BoardScene.Surface family) { return weight(family.ordinal()); }

        float weight(int family) {
            return switch (family) {
                case 0 -> grass;
                case 1 -> dirt;
                case 2 -> sand;
                case 3 -> rock;
                case 4 -> concrete;
                case 5 -> snow;
                case 6 -> lunar;
                case 7 -> fungus;
                case 8 -> desert;
                case 9 -> mars;
                case 10 -> volcano;
                case 11 -> tropical;
                case 12 -> crust;
                case 13 -> bank;
                default -> throw new IllegalArgumentException("Surface cover " + family);
            };
        }

        int mask() {
            int mask = 0;
            for (int family = 0; family < FAMILIES; family++) {
                if (weight(family) > 0) { mask |= 1 << family; }
            }
            return mask;
        }
    }

    private static final Cover[] SOLID = new Cover[FAMILIES];
    static {
        for (int family = 0; family < FAMILIES; family++) {
            float[] weights = new float[FAMILIES];
            weights[family] = 1;
            SOLID[family] = cover(weights, 1, 0);
        }
    }

    private BoardSurfaceBlend() { }

    static Cover solid(BoardScene.Surface family) { return SOLID[family.ordinal()]; }
    static Cover solid(int family) { return SOLID[family]; }

    /** The classic tileset's ground_fluff transitions: desert, grass, tropical grass, Mars and Moon. */
    public static boolean hasTransition(Hex hex) { return transitionFamily(hex) >= 0; }

    /** Omit legacy paint only when native ground can actually display the captured material mixture. */
    public static boolean replacesTransition(Hex hex, Map<Integer, String> models, Set<Integer> blankTerrains) {
        return hasTransition(hex) && BoardFeatures.detailedGround(hex, models, blankTerrains);
    }

    public static boolean replacesTransition(Hex hex, Map<Integer, String> models, Set<Integer> blankTerrains,
          BoardArtwork.Scenery scenery) {
        return hasTransition(hex) && BoardFeatures.detailedGround(hex, models, blankTerrains, scenery);
    }

    static int transitionFamily(Hex hex) {
        var terrain = hex.getTerrain(Terrains.GROUND_FLUFF);
        if (terrain == null || !terrain.hasExitsSpecified() || terrain.getExits() < 1 || terrain.getExits() > 5) {
            return -1;
        }
        return switch (terrain.getLevel()) {
            case 1 -> BoardScene.Surface.DESERT.ordinal();
            case 2 -> BoardScene.Surface.GRASS.ordinal();
            case 3 -> BoardScene.Surface.TROPICAL.ordinal();
            case 4 -> BoardScene.Surface.MARS.ordinal();
            case 5 -> BoardScene.Surface.LUNAR.ordinal();
            default -> -1;
        };
    }

    /** Capture authored material proportions once; the renderer never reads or changes the source hex. */
    static Cover capture(Hex hex) {
        int base = BoardFeatures.surface(hex).ordinal(), target = transitionFamily(hex);
        // Natural transitions describe the substrate; pavement covers it with concrete.
        if (base == BoardScene.Surface.CONCRETE.ordinal()) { return solid(base); }
        // Sand is one gameplay surface treatment, independent of the theme beneath it. Snow/paving/magma
        // retain their existing precedence. Authored theme gradients must not dilute the SAND gameplay cue.
        if (hex.containsTerrain(Terrains.SAND) && base != BoardScene.Surface.SNOW.ordinal()
              && base != BoardScene.Surface.CONCRETE.ordinal() && !hex.containsTerrain(Terrains.MAGMA)) {
            // Retain the supporting material in the palette. Small wind-scoured windows are resolved in the
            // shader, so a uniform sand flat still needs only six triangles rather than a mesh for every patch.
            float[] weights = new float[FAMILIES];
            weights[base] = .06f;
            weights[BoardScene.Surface.SAND.ordinal()] += .94f;
            return cover(weights, 1, 0);
        }
        if (target < 0 || target == base) { return solid(base); }
        // Five evenly spaced interior points leave some of both materials at every authored strength.
        float amount = hex.getTerrain(Terrains.GROUND_FLUFF).getExits() / 6f;
        float[] weights = new float[FAMILIES];
        weights[base] = 1 - amount;
        weights[target] += amount;
        return cover(weights, 1, 1);
    }

    private static Cover cover(BoardScene.Tile tile) {
        return tile.liquid().volcanic() ? solid(family(tile)) : tile.groundCover();
    }

    /** Same metre-based field as sandExposure in terrain-hexes.glsl. Sand stays dominant between sparse openings. */
    static float sandExposure(float xMetres, float yMetres) {
        float field = .75f * BoardRelief.noise(xMetres / 6, yMetres / 6)
              + .25f * BoardRelief.noise(xMetres / 1.7f + 19, yMetres / 1.7f - 7);
        return BoardRelief.smooth((field - .60f) / .14f);
    }

    /** Vegetation uses the same exposed substrate windows as the material, including sand reaching a neighbour. */
    static float grass(BoardScene.Tile tile, Cover cover, float x, float y) {
        if (tile.surface() != BoardScene.Surface.GRASS || cover.sand() <= 0) { return cover.grass(); }
        float metre = BoardRelief.metres(1);
        return cover.grass() + cover.sand() * sandExposure(x / metre, y / metre);
    }

    /** Loose surface deposits do not turn the supporting cliff into sandstone or erase the theme's geology. */
    private static Cover cover(BoardScene.Tile tile, float z) {
        Cover top = cover(tile);
        if (top.sand() == 0 || tile.surface() == BoardScene.Surface.SAND || tile.liquid().present()) { return top; }
        float exposed = BoardRelief.smooth((BoardGeometry.groundZ(tile) - z) / BoardRelief.metres(1.2f));
        if (exposed <= 0) { return top; }
        float[] weights = new float[FAMILIES];
        for (int family = 0; family < FAMILIES; family++) { weights[family] = top.weight(family); }
        float mineral = top.sand() * exposed;
        weights[BoardScene.Surface.SAND.ordinal()] -= mineral;
        weights[tile.surface().ordinal()] += mineral;
        return cover(weights, 1, top.interpolation());
    }

    static int family(BoardScene.Tile tile) {
        return tile.liquid().volcanic() ? tile.liquid().molten() ? BANK : CRUST : tile.surface().ordinal();
    }

    static boolean natural(BoardScene.Tile tile) {
        return tile != null && !tile.ultraSublevel() && tile.detailedGround()
              && !tile.liquid().present() && !tile.liquid().volcanic() && !tile.frozen()
              && (tile.roadExits() == 0 || BoardRoad.rendered(tile)) && tile.surface() != BoardScene.Surface.CONCRETE
              && !tile.building();
    }

    private static boolean blendable(BoardScene.Tile tile) {
        return tile != null && !tile.ultraSublevel() && tile.detailedGround()
              && (!tile.liquid().present() || tile.liquid().volcanic())
              && !tile.frozen() && (tile.roadExits() == 0 || BoardRoad.rendered(tile)) && !tile.building();
    }

    /** Uniform interiors retain the ordinary family material and incur no extra maps or vertex attributes. */
    static boolean boundary(BoardScene scene, BoardScene.Tile tile) {
        if (tile == null || tile.ultraSublevel() || !tile.detailedGround()) { return false; }
        Cover own = cover(tile);
        // A building or ice sheet blocks neighbouring cover, but cannot erase its own authored ground treatment.
        if (!own.equals(solid(family(tile)))) { return true; }
        if (!blendable(tile)) { return false; }
        for (int direction = 0; direction < 6; direction++) {
            var next = scene.neighbor(tile, direction);
            if (contact(tile, next) && !cover(next).equals(own)) { return true; }
        }
        return false;
    }

    private static boolean contact(BoardScene.Tile a, BoardScene.Tile b) {
        if (!blendable(b)) { return false; }
        if (a.liquid().present() && !a.liquid().volcanic()) {
            // The bank includes the submerged continuation of higher land, including tall cliffs.
            return natural(b) && (b.elevation() >= a.elevation()
                  || BoardGeometry.tuning().stepsBetweenTops() && a.elevation() - b.elevation() == 1);
        }
        // Constructed faces keep their material all the way to the adjoining ground.
        return a == b || a.surface() != BoardScene.Surface.CONCRETE && b.surface() != BoardScene.Surface.CONCRETE;
    }

    static boolean cliffBoundary(BoardScene scene, BoardScene.Tile tile) { return boundary(scene, tile); }

    /** Cliff and ground agree at the foot. The lower cover reaches only a short way up the exposed column. */
    static Cover sampleCliff(BoardScene scene, BoardScene.Tile owner, float x, float y, float z) {
        return new Sampler(scene, owner).sampleCliff(x, y, z);
    }

    private static boolean reaches(BoardScene.Tile tile, float z) {
        return blendable(tile) && BoardGeometry.groundZ(tile) + BoardRelief.metres(.05f) >= z;
    }

    /** The footprint, rather than the emitting mesh, owns the query; shared positions therefore agree. */
    static Cover sample(BoardScene scene, BoardScene.Tile owner, float x, float y, float z) {
        return new Sampler(scene, owner).sample(x, y, z);
    }

    private static boolean water(BoardScene.Tile tile) {
        return tile != null && tile.liquid().present() && !tile.liquid().volcanic() && !tile.frozen();
    }

    /**
     * Cover queries for the vertices of one hex. Which tiles blend into a tile never depends on the queried point,
     * so each tile's contacts are derived once, and a position asked again (shared vertices, subdivision probes)
     * returns its earlier answer. Every result equals the single static query.
     */
    static final class Sampler {
        private record Position(float x, float y, float z) { }

        /** Point-independent facts about one tile; the arrays keep the query order: itself, then directions 0-5. */
        private record Contacts(boolean blendable, boolean water, boolean ownerContact, BoardScene.Tile[] touching,
              int bedFamily, BoardScene.Tile[] pools) { }

        private final BoardScene scene;
        private final BoardScene.Tile owner;
        private final BoardConcrete coast;
        private final boolean blendableOwner, waterOwner;
        private final Map<BoardScene.Tile, Contacts> contacts = new IdentityHashMap<>();
        private final Map<Position, Cover> ground = new HashMap<>(), cliffs = new HashMap<>();

        Sampler(BoardScene scene, BoardScene.Tile owner) {
            this.scene = scene;
            this.owner = owner;
            coast = BoardConcrete.of(scene);
            blendableOwner = blendable(owner);
            waterOwner = water(owner);
        }

        Cover sample(float x, float y, float z) {
            Position key = new Position(x, y, z);
            Cover cover = ground.get(key);
            if (cover == null) {
                cover = sampleGround(x, y, z);
                ground.put(key, cover);
            }
            return cover;
        }

        Cover sampleCliff(float x, float y, float z) {
            Position key = new Position(x, y, z);
            Cover cover = cliffs.get(key);
            if (cover == null) {
                cover = sampleColumn(x, y, z);
                cliffs.put(key, cover);
            }
            return cover;
        }

        private Cover sampleGround(float x, float y, float z) {
            if (!blendableOwner && !waterOwner) { return cover(owner, z); }
            var at = BoardGeometry.tile(scene, x, y);
            if (at == null) { return cover(owner, z); }
            Contacts around = contacts(at);
            if (!around.blendable() && (!at.liquid().present() || at.frozen())) { return cover(owner, z); }
            if ((!at.liquid().present() || at.liquid().volcanic() || owner.surface() == BoardScene.Surface.CONCRETE)
                  && !around.ownerContact()) {
                return cover(owner, z);
            }
            if (around.water()) { return sampleWater(at, around, x, y, z); }
            return sampleAt(at, around, cover(owner, z), x, y, z, false);
        }

        private Cover sampleColumn(float x, float y, float z) {
            if (!blendableOwner) { return cover(owner, z); }
            var at = BoardGeometry.tile(scene, x, y);
            if (at == null) { return cover(owner, z); }
            if (!at.liquid().present() || at.liquid().volcanic()) { return sample(x, y, z); }
            BoardScene.Tile column = null;
            float nearest = Float.POSITIVE_INFINITY;
            for (int direction = -1; direction < 6; direction++) {
                var candidate = direction < 0 ? at : scene.neighbor(at, direction);
                if (!reaches(candidate, z)) { continue; }
                float distance = distance(candidate.coords(), x, y);
                if (distance < nearest) { column = candidate; nearest = distance; }
            }
            return column == null ? cover(owner, z) : sampleAt(column, contacts(column), cover(column, z), x, y, z, true);
        }

        private Contacts contacts(BoardScene.Tile at) {
            Contacts around = contacts.get(at);
            if (around == null) {
                around = derive(at);
                contacts.put(at, around);
            }
            return around;
        }

        private Contacts derive(BoardScene.Tile at) {
            boolean water = water(at);
            List<BoardScene.Tile> touching = new ArrayList<>(7), pools = new ArrayList<>(7);
            float[] banks = new float[FAMILIES];
            for (int direction = -1; direction < 6; direction++) {
                BoardScene.Tile tile = direction < 0 ? at : scene.neighbor(at, direction);
                if (contact(at, tile)) {
                    touching.add(tile);
                    if (water) {
                        Cover cover = cover(tile);
                        for (int family = 0; family < FAMILIES; family++) { banks[family] += cover.weight(family); }
                    }
                }
                if (water && water(tile) && tile.elevation() == at.elevation()) { pools.add(tile); }
            }
            int bedFamily = 0;
            for (int family = 1; family < FAMILIES; family++) {
                if (banks[family] > banks[bedFamily]) { bedFamily = family; }
            }
            return new Contacts(blendable(at), water, contact(owner, at), touching.toArray(BoardScene.Tile[]::new),
                  bedFamily, pools.toArray(BoardScene.Tile[]::new));
        }

        /** Water hexes share their bank/bed fields across their boundaries, independently of triangle ownership. */
        private Cover sampleWater(BoardScene.Tile at, Contacts around, float x, float y, float z) {
            float[] weights = new float[FAMILIES];
            float total = 0, interpolation = 0;
            float width = BoardRelief.metres(WIDTH_METRES);
            for (BoardScene.Tile tile : around.pools()) {
                float amount = 1 - BoardRelief.smooth((distance(tile.coords(), x, y) + width) / (2 * width));
                if (amount <= .0001f) { continue; }
                var cover = sampleAt(tile, contacts(tile), cover(tile, z), x, y, z, false);
                for (int family = 0; family < FAMILIES; family++) { weights[family] += amount * cover.weight(family); }
                interpolation += amount * cover.interpolation();
                total += amount;
            }
            return total > .0001f ? cover(weights, total, interpolation / total) : cover(at, z);
        }

        private Cover sampleAt(BoardScene.Tile at, Contacts around, Cover fallback, float x, float y, float z, boolean cliff) {
            float[] weights = new float[FAMILIES];
            float total = 0;
            float interpolation = 0;
            float width = BoardRelief.metres(WIDTH_METRES);
            float mx = x / BoardRelief.metres(1), my = y / BoardRelief.metres(1);
            float offset = 0;
            float bed = 0;
            int bedFamily = 0;
            boolean water = at.liquid().present() && !at.liquid().volcanic();
            if (water) {
                // Extend the neighbouring cover fields to the curved bank. Absolute hex distance would end the blend
                // before the waterline at a recessed corner, leaving the two banks with a hard radial seam.
                if (around.touching().length == 0) { return fallback; }
                float nearest = Float.POSITIVE_INFINITY;
                for (BoardScene.Tile land : around.touching()) {
                    nearest = Math.min(nearest, distance(land.coords(), x, y));
                }
                offset = Math.max(0, nearest);
                bedFamily = around.bedFamily();
                float radius = (float) Math.hypot(x - BoardGeometry.centerX(at.coords()), y - BoardGeometry.centerY(at.coords()));
                float radial = radius / (BoardGeometry.height() * .5f);
                // A shallow pond can expose its bed. Let neighbouring bank covers meet a common central sediment,
                // chosen from those banks, instead of converging as six differently painted wedges at one point.
                bed = 1 - BoardRelief.smooth(radial / .5f);
                if (bed >= .9999f) { return solid(bedFamily); }
                // Keep the contact's angular reach bounded as the bank runs inward: distant shores must not bleed in.
                width *= Math.min(1, radial);
            }
            // A cliff's column reaches the point by height; ground contacts were settled with the tile.
            BoardScene.Tile[] tiles = cliff ? reaching(at, z) : around.touching();
            for (BoardScene.Tile tile : tiles) {
                // A contact spreads and meanders down the exposed column; it starts at the plateau's own cover.
                // The bounded world-metre field is identical at every mesh LOD.
                float below = water ? 0 : Math.max(0, (BoardGeometry.groundZ(tile) - z) / BoardRelief.metres(1));
                // The cliff's talus projects beyond its plateau footprint. Carry its broken material out with it,
                // so the receiving floor does not cut off the contact before the protruding foot is reached.
                float toe = BoardRelief.metres(Math.min(2.4f, below * .3f));
                float distance = distance(tile.coords(), x, y) - offset - toe;
                float contactWidth = width * (1 + .35f * BoardRelief.smooth(below / 4));
                float strength = .9f + .7f * BoardRelief.smooth(below / 3);
                if (distance >= contactWidth * (1 + strength * .5f)) { continue; }
                int family = family(tile);
                // Volcanic contacts keep their established noise when a new ordinary family is appended.
                if (tile.liquid().volcanic()) { family = tile.liquid().molten() ? 12 : 11; }
                // Family-anchored patches continue through neighbouring hexes of that family.
                float px = mx + below * .65f, py = my + below * .4f;
                float patch = .7f * BoardRelief.noise(px / 5.3f + family * 19.7f, py / 5.3f - family * 11.3f)
                      + .3f * BoardRelief.noise(px / 1.7f - family * 7.1f, py / 1.7f + family * 23.9f);
                float warp = (patch - .5f) * contactWidth * strength;
                float weight = 1 - BoardRelief.smooth((distance + contactWidth - warp) / (2 * contactWidth));
                // A column's material reaches the foot and nearby talus. The receiving ground creeps a bounded
                // distance up that column, then disappears; it cannot paint the whole cliff or the upper plateau.
                if (water) {
                    // Land continues down into the basin; only cover from below this height is attenuated.
                    // The water surface is the reference, not its recessed bed or an absolute height difference.
                    float dz = Math.max(0, Math.max(z, at.elevation() * BoardGeometry.level()) - BoardGeometry.groundZ(tile))
                          / BoardGeometry.level();
                    weight *= 1 - BoardRelief.smooth((dz - .35f) / .65f);
                } else {
                    float above = Math.max(0, (z - BoardGeometry.groundZ(tile)) / BoardRelief.metres(1));
                    weight *= 1 - BoardRelief.smooth(above / FOOT_METRES);
                }
                if (weight < .0001f) { continue; }
                Cover cover = water ? cover(tile) : cover(tile, z);
                for (int material = 0; material < FAMILIES; material++) { weights[material] += weight * cover.weight(material); }
                interpolation += weight * cover.interpolation();
                total += weight;
            }
            if (total <= .0001f) { return fallback; }
            if (bed > 0) {
                for (int family = 0; family < weights.length; family++) { weights[family] *= 1 - bed; }
                weights[bedFamily] += bed * total;
            }
            return cover(weights, total, interpolation / total * (1 - bed));
        }

        /** The tile and the neighbours whose ground stands at or above the queried height, in query order. */
        private BoardScene.Tile[] reaching(BoardScene.Tile at, float z) {
            List<BoardScene.Tile> result = new ArrayList<>(7);
            for (int direction = -1; direction < 6; direction++) {
                BoardScene.Tile tile = direction < 0 ? at : scene.neighbor(at, direction);
                if (reaches(tile, z)) { result.add(tile); }
            }
            return result.toArray(BoardScene.Tile[]::new);
        }

        /** Signed distance to the hex's supporting edges, using the board's actual short/long dimensions. */
        private float distance(Coords coords, float x, float y) {
            float fitted = coast.distance(coords, x, y);
            if (Float.isFinite(fitted)) { return fitted; }
            float dx = Math.abs(x - BoardGeometry.centerX(coords)), dy = Math.abs(y - BoardGeometry.centerY(coords));
            float a = BoardGeometry.height() / 2, b = BoardGeometry.width() / 4;
            return Math.max(dy - a, (a * dx + b * dy - BoardGeometry.width() * BoardGeometry.height() / 4)
                  / (float) Math.sqrt(a * a + b * b));
        }
    }

    private static Cover cover(float[] weights, float total, float interpolation) {
        return new Cover(weights[0] / total, weights[1] / total, weights[2] / total,
              weights[3] / total, weights[4] / total, weights[5] / total, weights[6] / total, weights[7] / total,
              weights[8] / total, weights[9] / total, weights[10] / total, weights[11] / total, weights[12] / total,
              weights[13] / total, interpolation);
    }
}
