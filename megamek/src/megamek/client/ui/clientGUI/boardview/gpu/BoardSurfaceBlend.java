/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.board.Coords;

/** Natural cover at a world position. Rendering and grass placement query the same immutable board snapshot. */
final class BoardSurfaceBlend {
    static final float WIDTH_METRES = 4.5f;
    private static final BoardScene.Surface[] FAMILIES = BoardScene.Surface.values();

    record Cover(float grass, float dirt, float sand, float rock, float concrete, float snow) {
        float weight(BoardScene.Surface family) {
            return switch (family) {
                case GRASS -> grass;
                case DIRT -> dirt;
                case SAND -> sand;
                case ROCK -> rock;
                case CONCRETE -> concrete;
                case SNOW -> snow;
            };
        }

        int mask() {
            int mask = 0;
            for (var family : FAMILIES) {
                if (weight(family) > 0) { mask |= 1 << family.ordinal(); }
            }
            return mask;
        }
    }

    private static final Cover[] SOLID = {
          new Cover(1, 0, 0, 0, 0, 0), new Cover(0, 1, 0, 0, 0, 0), new Cover(0, 0, 1, 0, 0, 0),
          new Cover(0, 0, 0, 1, 0, 0), new Cover(0, 0, 0, 0, 1, 0), new Cover(0, 0, 0, 0, 0, 1)
    };

    private BoardSurfaceBlend() { }

    static Cover solid(BoardScene.Surface family) { return SOLID[family.ordinal()]; }

    static boolean natural(BoardScene.Tile tile) {
        return tile != null && tile.detailedGround() && !tile.liquid().present() && !tile.frozen()
              && (tile.roadExits() == 0 || BoardRoad.rendered(tile)) && tile.surface() != BoardScene.Surface.CONCRETE
              && tile.features().stream().noneMatch(feature -> feature.kind() == BoardScene.FeatureKind.BUILDING);
    }

    /** Uniform interiors retain the ordinary family material and incur no extra maps or vertex attributes. */
    static boolean boundary(BoardScene scene, BoardScene.Tile tile) {
        if (!natural(tile)) { return false; }
        for (int direction = 0; direction < 6; direction++) {
            var next = scene.tile(tile.coords().translated(direction));
            if (contact(tile, next) && next.surface() != tile.surface()) { return true; }
        }
        return false;
    }

    private static boolean contact(BoardScene.Tile a, BoardScene.Tile b) {
        return natural(b) && (a.elevation() == b.elevation()
              || BoardGeometry.tuning().stepsBetweenTops() && Math.abs(a.elevation() - b.elevation()) == 1);
    }

    static boolean cliffBoundary(BoardScene scene, BoardScene.Tile tile) {
        if (!natural(tile)) { return false; }
        for (int direction = 0; direction < 6; direction++) {
            var next = scene.tile(tile.coords().translated(direction));
            if (natural(next) && next.surface() != tile.surface()) { return true; }
        }
        return false;
    }

    /** A cliff samples the columns that reach its height, not the covers of the valley floor below it. */
    static Cover sampleCliff(BoardScene scene, BoardScene.Tile owner, float x, float y, float z) {
        if (!natural(owner)) { return solid(owner.surface()); }
        var at = BoardGeometry.tile(scene, x, y);
        if (at == null) { return solid(owner.surface()); }
        BoardScene.Tile column = null;
        float nearest = Float.POSITIVE_INFINITY;
        for (int direction = -1; direction < 6; direction++) {
            var candidate = direction < 0 ? at : scene.tile(at.coords().translated(direction));
            if (!reaches(candidate, z)) { continue; }
            float distance = distance(candidate.coords(), x, y);
            if (distance < nearest) { column = candidate; nearest = distance; }
        }
        return column == null ? solid(owner.surface()) : sampleAt(scene, column, column.surface(), x, y, z, true);
    }

    private static boolean reaches(BoardScene.Tile tile, float z) {
        return natural(tile) && BoardGeometry.groundZ(tile) + BoardRelief.metres(.05f) >= z;
    }

    /** The footprint, rather than the emitting mesh, owns the query; shared positions therefore agree. */
    static Cover sample(BoardScene scene, BoardScene.Tile owner, float x, float y, float z) {
        if (!natural(owner)) { return solid(owner.surface()); }
        var at = BoardGeometry.tile(scene, x, y);
        if (at == null || !natural(at) && (!at.liquid().present() || at.frozen())) { return solid(owner.surface()); }
        return sampleAt(scene, at, owner.surface(), x, y, z, false);
    }

    private static Cover sampleAt(BoardScene scene, BoardScene.Tile at, BoardScene.Surface fallback,
          float x, float y, float z, boolean cliff) {
        float[] weights = new float[FAMILIES.length];
        float total = 0;
        float width = BoardRelief.metres(WIDTH_METRES);
        float mx = x / BoardRelief.metres(1), my = y / BoardRelief.metres(1);
        float offset = 0;
        float bed = 0;
        int bedFamily = 0;
        if (at.liquid().present()) {
            // Extend the neighbouring cover fields to the curved bank. Absolute hex distance would end the blend
            // before the waterline at a recessed corner, leaving the two banks with a hard radial seam.
            float nearest = Float.POSITIVE_INFINITY;
            int[] banks = new int[FAMILIES.length];
            for (int direction = 0; direction < 6; direction++) {
                var land = scene.tile(at.coords().translated(direction));
                if (contact(at, land)) {
                    nearest = Math.min(nearest, distance(land.coords(), x, y));
                    banks[land.surface().ordinal()]++;
                }
            }
            if (!Float.isFinite(nearest)) { return solid(fallback); }
            offset = Math.max(0, nearest);
            for (int family = 1; family < banks.length; family++) {
                if (banks[family] > banks[bedFamily]) { bedFamily = family; }
            }
            float radius = (float) Math.hypot(x - BoardGeometry.centerX(at.coords()), y - BoardGeometry.centerY(at.coords()));
            float radial = radius / (BoardGeometry.height() * .5f);
            // A shallow pond can expose its bed. Let neighbouring bank covers meet a common central sediment,
            // chosen from those banks, instead of converging as six differently painted wedges at one point.
            bed = 1 - BoardRelief.smooth(radial / .5f);
            if (bed >= .9999f) { return solid(FAMILIES[bedFamily]); }
            // Keep the contact's angular reach bounded as the bank runs inward: distant shores must not bleed in.
            width *= Math.min(1, radial);
        }
        for (int direction = -1; direction < 6; direction++) {
            var tile = direction < 0 ? at : scene.tile(at.coords().translated(direction));
            if (cliff ? !reaches(tile, z) : !contact(at, tile)) { continue; }
            float distance = distance(tile.coords(), x, y) - offset;
            // A contact spreads and meanders down the exposed column; it starts at the plateau's own cover.
            // The bounded world-metre field is identical at every mesh LOD.
            float below = cliff ? Math.max(0, (BoardGeometry.groundZ(tile) - z) / BoardRelief.metres(1)) : 0;
            float contactWidth = width * (1 + .35f * BoardRelief.smooth(below / 4));
            float strength = cliff ? .9f + .7f * BoardRelief.smooth(below / 3) : .9f;
            if (distance >= contactWidth * (1 + strength * .5f)) { continue; }
            int family = tile.surface().ordinal();
            // Family-anchored patches continue through neighbouring hexes of that family.
            float px = mx + below * .65f, py = my + below * .4f;
            float patch = .7f * BoardRelief.noise(px / 5.3f + family * 19.7f, py / 5.3f - family * 11.3f)
                  + .3f * BoardRelief.noise(px / 1.7f - family * 7.1f, py / 1.7f + family * 23.9f);
            float warp = (patch - .5f) * contactWidth * strength;
            float weight = 1 - BoardRelief.smooth((distance + contactWidth - warp) / (2 * contactWidth));
            // On a gentle step, cover follows the actual height. Tall cliffs cannot paint the ground below them.
            float height = at.liquid().present() ? Math.max(z, BoardGeometry.groundZ(at)) : z;
            float dz = Math.abs(height - BoardGeometry.groundZ(tile)) / BoardGeometry.level();
            if (!cliff) { weight *= 1 - BoardRelief.smooth((dz - .35f) / .65f); }
            if (weight < .0001f) { continue; }
            weights[family] += weight;
            total += weight;
        }
        if (total <= .0001f) { return solid(fallback); }
        if (bed > 0) {
            for (int family = 0; family < weights.length; family++) { weights[family] *= 1 - bed; }
            weights[bedFamily] += bed * total;
        }
        return new Cover(weights[0] / total, weights[1] / total, weights[2] / total,
              weights[3] / total, weights[4] / total, weights[5] / total);
    }

    /** Signed distance to the hex's supporting edges, using the board's actual short/long dimensions. */
    private static float distance(Coords coords, float x, float y) {
        float dx = Math.abs(x - BoardGeometry.centerX(coords)), dy = Math.abs(y - BoardGeometry.centerY(coords));
        float a = BoardGeometry.height() / 2, b = BoardGeometry.width() / 4;
        return Math.max(dy - a, (a * dx + b * dy - BoardGeometry.width() * BoardGeometry.height() / 4)
              / (float) Math.sqrt(a * a + b * b));
    }
}
