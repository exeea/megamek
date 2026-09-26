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
              && tile.roadExits() == 0 && tile.surface() != BoardScene.Surface.CONCRETE
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

    /** The footprint, rather than the emitting mesh, owns the query; shared positions therefore agree. */
    static Cover sample(BoardScene scene, BoardScene.Tile owner, float x, float y, float z) {
        if (!natural(owner)) { return solid(owner.surface()); }
        var at = BoardGeometry.tile(scene, x, y);
        if (at == null || !natural(at) && (!at.liquid().present() || at.frozen())) { return solid(owner.surface()); }
        return sampleAt(scene, at, owner.surface(), x, y, z);
    }

    private static Cover sampleAt(BoardScene scene, BoardScene.Tile at, BoardScene.Surface fallback,
          float x, float y, float z) {
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
            float radial = radius / (BoardGeometry.HEIGHT * .5f);
            // A shallow pond can expose its bed. Let neighbouring bank covers meet a common central sediment,
            // chosen from those banks, instead of converging as six differently painted wedges at one point.
            bed = 1 - BoardRelief.smooth(radial / .5f);
            if (bed >= .9999f) { return solid(FAMILIES[bedFamily]); }
            // Keep the contact's angular reach bounded as the bank runs inward: distant shores must not bleed in.
            width *= Math.min(1, radial);
        }
        for (int direction = -1; direction < 6; direction++) {
            var tile = direction < 0 ? at : scene.tile(at.coords().translated(direction));
            if (!contact(at, tile)) { continue; }
            float distance = distance(tile.coords(), x, y) - offset;
            if (distance >= width * 1.45f) { continue; }
            int family = tile.surface().ordinal();
            // Family-anchored patches continue through neighbouring hexes of that family.
            float patch = .7f * BoardRelief.noise(mx / 5.3f + family * 19.7f, my / 5.3f - family * 11.3f)
                  + .3f * BoardRelief.noise(mx / 1.7f - family * 7.1f, my / 1.7f + family * 23.9f);
            float warp = (patch - .5f) * width * .9f;
            float weight = 1 - BoardRelief.smooth((distance + width - warp) / (2 * width));
            // On a gentle step, cover follows the actual height. Tall cliffs cannot paint the ground below them.
            float height = at.liquid().present() ? Math.max(z, BoardGeometry.groundZ(at)) : z;
            float dz = Math.abs(height - BoardGeometry.groundZ(tile)) / BoardGeometry.LEVEL;
            weight *= 1 - BoardRelief.smooth((dz - .35f) / .65f);
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
        float a = BoardGeometry.HEIGHT / 2, b = BoardGeometry.WIDTH / 4;
        return Math.max(dy - a, (a * dx + b * dy - BoardGeometry.WIDTH * BoardGeometry.HEIGHT / 4)
              / (float) Math.sqrt(a * a + b * b));
    }
}
