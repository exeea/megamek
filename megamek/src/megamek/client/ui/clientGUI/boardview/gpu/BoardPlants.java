/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.utils.FloatArray;

/**
 * One hex's ground cover, planted by a terrain worker on the hex's finished support and installed with it. Each
 * kind is present only when the chunk's detail level can show it, or is about to: memory follows detail rather than
 * board size. Reeds grow thinned to what their tiers draw, and grass and the crops' plants from
 * {@link TerrainLod#MEDIUM}, so a chunk already carries its blades while its full-detail replacement is still being
 * built: grass only shows from 120 projected pixels per hex and crop plants from the 24 at which medium detail is
 * requested, and a medium chunk's roots keep their places and ranks on the full ground. Nothing grows through paint:
 * every kind grows by one minus the opacity of the decals painting the hex's ground ({@code stamps}) and of its legacy
 * paint overlay ({@link BoardDecals.Opacity}). Every part is null when the hex grows none of it.
 */
record BoardPlants(FloatArray reeds, FloatArray grass, FloatArray wheat, FloatArray maize, FloatArray turf) {
    static BoardPlants plant(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface support, TerrainLod lod,
          List<BoardDecals.Stamp> stamps) {
        // A Solaris elevator's steel platform covers its whole hex (BoardElevator).
        if (tile.ultraSublevel() || tile.backdrop().present() || BoardFeatures.shaft(tile) || lod == TerrainLod.DISTANT) {
            return null;
        }
        var kind = BoardBiome.plantKind(scene, tile);
        var paint = BoardDecals.Opacity.of(tile, stamps);
        // Reed tiers start at 240, 64 and 12 projected pixels per hex; a chunk at medium or coarse detail never
        // draws the finer subsets.
        FloatArray reeds = kind == BoardScene.Biome.MARSH ? GpuBiomeVegetation.plantReeds(scene, tile, support,
              lod == TerrainLod.FULL ? 1 : lod == TerrainLod.MEDIUM ? .35f : .08f, paint) : null;
        FloatArray grass = lod != TerrainLod.COARSE && GpuGroundCover.grows(scene, tile)
              ? GpuGroundCover.plant(scene, tile, support, paint) : null;
        boolean stalks = lod != TerrainLod.COARSE && kind == BoardScene.Biome.FIELD;
        FloatArray wheat = stalks ? GpuBiomeVegetation.plantStalks(scene, tile, support, paint, BoardFields.Crop.WHEAT) : null;
        FloatArray maize = stalks ? GpuBiomeVegetation.plantStalks(scene, tile, support, paint, BoardFields.Crop.MAIZE) : null;
        FloatArray turf = grass == null ? null : GpuBankTurf.plant(scene, tile, support, paint);
        return reeds == null && grass == null && wheat == null && maize == null && turf == null ? null
              : new BoardPlants(reeds, grass, wheat, maize, turf);
    }
}
