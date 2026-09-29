/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.utils.FloatArray;

/**
 * One hex's ground cover, planted by a terrain worker on the hex's finished support and installed with it. Each
 * kind is present only when the chunk's detail level can show it, so memory follows detail rather than board size:
 * crops from {@link TerrainLod#COARSE}, reeds thinned to what their tiers draw, and grass on full detail only.
 * Every part is null when the hex grows none of it.
 */
record BoardPlants(GpuBiomeVegetation.Crops crops, FloatArray reeds, FloatArray grass) {
    static BoardPlants plant(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface support, TerrainLod lod) {
        if (lod == TerrainLod.DISTANT) { return null; }
        var kind = BoardBiome.plantKind(scene, tile);
        GpuBiomeVegetation.Crops crops = kind == BoardScene.Biome.FIELD ? GpuBiomeVegetation.plant(scene, tile, support) : null;
        // Reed tiers start at 240, 64 and 12 projected pixels per hex; a chunk at medium or coarse detail never
        // draws the finer subsets.
        FloatArray reeds = kind == BoardScene.Biome.MARSH ? GpuBiomeVegetation.plantReeds(scene, tile, support,
              lod == TerrainLod.FULL ? 1 : lod == TerrainLod.MEDIUM ? .35f : .08f) : null;
        FloatArray grass = lod == TerrainLod.FULL && GpuGroundCover.grows(scene, tile)
              ? GpuGroundCover.plant(scene, tile, support) : null;
        return crops == null && reeds == null && grass == null ? null : new BoardPlants(crops, reeds, grass);
    }
}
