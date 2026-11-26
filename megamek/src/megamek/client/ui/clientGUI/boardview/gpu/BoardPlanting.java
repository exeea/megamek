/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.math.Matrix4;
import megamek.common.board.Coords;

/**
 * One game's original planting: what grew on each hex when the game began, and which hexes have lost it since.
 * Terrain detail and destruction cannot sow new roots on newly exposed ground, because every root is planted here,
 * on the original board, at the medium-detail support (the first tier carrying grass); drawing thins that set for the
 * camera, and refining the ground never changes these anchors. Planting a hex again gives the same roots, so none are
 * kept here: the chunk that draws a hex owns its roots ({@code GpuTerrain.TileMesh#plants}) and hands them to its
 * replacement, and memory follows the view instead of the board. Kept for the whole game are only the hexes that lost
 * their plants and the poses of the models already placed. The editor does not use this. No GPU resources or
 * authoritative game state are owned here.
 */
final class BoardPlanting {
    final BoardScene scene;
    /** This scene's height field once its ground changed (#field), and the build's field and outline it came from. */
    private FieldBoard field, fieldFrom;
    private BoardRelief.StepOutline fieldOutline;
    private final Map<Coords, List<BoardDecals.Stamp>> paint;
    /** Read by the terrain workers; the render thread adds to it. */
    private final Set<Coords> removed = ConcurrentHashMap.newKeySet();
    // Render thread only, with the poses below.
    private final Set<Coords> uprooted = new HashSet<>();
    private final Map<Coords, Set<BoardScene.Feature>> remaining = new HashMap<>();
    private List<BoardScene.Tile> previous;
    /**
     * Poses of the models already placed, including rejected placements: a surviving member never retries after its
     * neighbour dies. The render thread writes them; the terrain workers read which trees are posed ({@link #posed}).
     */
    final Map<Coords, Map<BoardScene.Feature, Optional<Matrix4>>> poses = new ConcurrentHashMap<>();

    BoardPlanting(BoardScene scene) {
        this.scene = new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(),
              List.of(), List.of(), -1, "", List.of()).withGround(scene.ground());
        paint = BoardDecals.index(this.scene);
        previous = scene.tiles();
        remove(scene);
    }

    /** Destruction can remove a planting, including a crater's unsupported roots, but cannot restore it. */
    void remove(BoardScene current) {
        for (var tile : current.tiles()) {
            var original = scene.tile(tile.coords());
            if (tile.barren() || tile.bare() || tile.elevation() != original.elevation()
                  || tile.waterDepth() != original.waterDepth()) { removed.add(tile.coords()); }
            if (tile.elevation() != original.elevation() || tile.waterDepth() != original.waterDepth()) {
                uprooted.add(tile.coords());
            }
            if (tile != previous.get(tile.coords().getX() * scene.height() + tile.coords().getY())) {
                remaining.computeIfAbsent(tile.coords(), key -> new HashSet<>(features(scene, original)))
                      .retainAll(features(current, tile));
            }
        }
        previous = current.tiles();
    }

    private static List<BoardScene.Feature> features(BoardScene scene, BoardScene.Tile tile) {
        return BoardMultiHexBuilding.withTrees(scene, tile, BoardFeatures.fittedControls(scene, tile));
    }

    boolean allows(Coords at, BoardScene.Feature feature) {
        // Scorched grass is cosmetic; standing woods remain until the game's feature snapshot removes them.
        return !uprooted.contains(at) && remaining.computeIfAbsent(at,
              key -> new HashSet<>(features(scene, scene.tile(key)))).contains(feature);
    }

    /**
     * The hex's original support, which its planted models and field stones stand on, under the caller's settings.
     * Built on request and not kept: a chunk build holds it only while it places that hex. With the height field
     * ({@code field}, the build's: null when the build draws none) a hex the field takes stands on the field's ground,
     * as a full-detail chunk's own surface does: {@code field} while {@code scene} is this planting's ground, else the
     * field of this planting's own scene (#field).
     */
    BoardSurface support(Coords at, FieldBoard field, BoardScene scene) {
        FieldBoard original = original(field, scene);
        BoardScene.Tile tile = this.scene.tile(at);
        if (original == null || !original.taken(at)) { return new BoardSurface(this.scene, tile); }
        ChunkField full = new ChunkField(original, GroundFamilies.ON ? this.scene : null, TerrainLod.FULL,
              BoardGeometry.floor(this.scene));
        return GpuTerrain.fieldSurface(this.scene, tile, TerrainLod.FULL, full.block(at), original);
    }

    /** This planting's field: the build's {@code field} while its {@code scene} is this ground; none without it. */
    private FieldBoard original(FieldBoard field, BoardScene scene) {
        if (field == null) { return null; }
        return scene.ground().tiles() == this.scene.tiles() ? field : field(field, scene);
    }

    /**
     * The height field of this planting's own scene once its ground has changed: the build's field of the changed
     * ground ({@code current}, of {@code scene}) with the hexes in reach of each changed one recomputed from this scene
     * and the rest shared with it (FieldBoard#of), as the terrain's own revisions are. Made again for the build's next
     * field or another hill outline.
     */
    private synchronized FieldBoard field(FieldBoard current, BoardScene scene) {
        var outline = BoardRelief.tuning().stepOutline();
        if (field == null || fieldFrom != current || fieldOutline != outline) {
            List<BoardScene.Tile> now = scene.ground().tiles(), then = this.scene.tiles();
            Set<Coords> changed = new HashSet<>();
            for (int i = 0; i < then.size(); i++) {
                if (!then.get(i).equals(now.get(i))) { changed.add(then.get(i).coords()); }
            }
            field = FieldBoard.of(this.scene, outline, current, changed);
            fieldFrom = current;
            fieldOutline = outline;
        }
        return field;
    }

    boolean cleared(Coords at) { return removed.contains(at); }

    /** The poses of a hex's placed models, which the render thread adds to. */
    Map<BoardScene.Feature, Optional<Matrix4>> poses(Coords at) {
        return poses.computeIfAbsent(at, key -> new ConcurrentHashMap<>());
    }

    /**
     * Whether a model of a hex already has its pose: it stands there again whatever the detail, so its support is not
     * read again (GpuTerrain#plantSupports).
     */
    boolean posed(Coords at, BoardScene.Feature feature) {
        var placed = poses.get(at);
        return placed != null && placed.containsKey(feature);
    }

    /**
     * The original roots of {@code tiles}, by hex, for a chunk drawn at {@code lod}: where its chunk holds a hex's roots
     * already ({@code kept}) those, else the hex planted again on its original support. The supports of one call are
     * finished in a batch, so adjacent hexes do not each rebuild the same banks and walls; {@code installed} are the
     * chunk's own surfaces, which stand in for the original ones where a hex's geometry never changed. A hex that lost
     * its plants, or whose chunk is too far to draw any, has no entry. {@code settings} are the build's, which every
     * task of the batch runs under.
     */
    Map<Coords, BoardPlants> roots(List<BoardScene.Tile> tiles, TerrainLod lod, Map<Coords, BoardSurface> installed,
          Map<Coords, BoardPlants> kept, TerrainSettings settings, Runnable check, FieldBoard field, BoardScene built) {
        Map<Coords, BoardPlants> result = new ConcurrentHashMap<>();
        List<BoardScene.Tile> needed = new ArrayList<>();
        for (var current : tiles) {
            var tile = scene.tile(current.coords());
            if (removed.contains(tile.coords()) || lod == TerrainLod.DISTANT
                  || BoardBiome.plantKind(scene, tile) != BoardScene.Biome.MARSH) { continue; }
            BoardPlants held = kept.get(tile.coords());
            if (held != null) { result.put(tile.coords(), held); } else { needed.add(tile); }
        }
        if (needed.isEmpty()) { return result; }
        float floor = settings.call(() -> BoardGeometry.floor(scene));
        Map<Coords, BoardSurface> surfaces = new ConcurrentHashMap<>();
        var obstacles = new BoardObstacles.Cache(scene);
        // With the height field, a hex it takes stands on the field's ground (#support), and so do the walls of a hex
        // it does not take where they meet it (BoardSurface#sides).
        FieldBoard original = settings.call(() -> original(field, built));
        ChunkField medium = original == null ? null
              : new ChunkField(original, GroundFamilies.ON ? scene : null, TerrainLod.MEDIUM, floor);
        needed.parallelStream().forEach(tile -> settings.run(() -> obstacles.run(() -> {
            check.run();
            var existing = lod == TerrainLod.MEDIUM ? installed.get(tile.coords()) : null;
            ChunkField.Block block = medium == null ? null : medium.block(tile.coords());
            surfaces.put(tile.coords(), existing != null && tile.sameGeometry(existing.tile) ? existing
                  : block != null ? GpuTerrain.fieldSurface(scene, tile, TerrainLod.MEDIUM, block, original)
                  : new BoardSurface(scene, tile, TerrainLod.MEDIUM));
        })));
        if (medium != null) {
            for (var tile : needed) {
                if (medium.block(tile.coords()) != null) { continue; }
                for (int d = 0; d < 6; d++) {
                    Coords next = tile.coords().translated(d);
                    ChunkField.Block block = surfaces.containsKey(next) ? null : medium.block(next);
                    if (block != null) {
                        BoardScene.Tile neighbour = scene.tile(next);
                        surfaces.put(next, settings.call(
                              () -> GpuTerrain.fieldSurface(scene, neighbour, TerrainLod.MEDIUM, block, original)));
                    }
                }
            }
        }
        needed.parallelStream().forEach(tile -> settings.run(() -> obstacles.run(() -> {
            check.run();
            var support = BoardTacticalGeometry.Surface.of(surfaces.get(tile.coords()), scene, floor, surfaces);
            // The map fixes every candidate at load: the full root set, whatever detail asked first.
            BoardPlants planted = BoardPlants.plant(scene, tile, support, TerrainLod.FULL,
                  paint.getOrDefault(tile.coords(), List.of()));
            if (planted != null) { result.put(tile.coords(), planted); }
        })));
        return result;
    }
}
