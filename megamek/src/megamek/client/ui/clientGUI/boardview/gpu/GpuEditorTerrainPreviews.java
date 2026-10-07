/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.logging.MMLogger;

/** Small, private sample boards, rendered progressively with the same geometry, artwork and lighting as the board. */
final class GpuEditorTerrainPreviews implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuEditorTerrainPreviews.class);
    private static final int WIDTH = 256, HEIGHT = 160, CACHE_SIZE = 48;
    /** The cliff behind two adjacent level-zero hexes; each of these three hexes touches both of the others. */
    private static final List<Coords> THEME_HEXES = List.of(new Coords(0, 0), new Coords(0, 1), new Coords(1, 0));
    record Sample(String theme, String component, String variant, String asset) { }
    private record Cached(FrameBuffer buffer, List<Image> targets) { }
    private final Map<Sample, List<Image>> queue = new LinkedHashMap<>();
    private final Map<Sample, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "board-editor-terrain-previews"); thread.setDaemon(true); return thread;
    });
    /** Only the preview worker touches its artwork resolver and these disposable sample boards. */
    private BoardArtwork artwork;
    private Future<BoardScene> pending;
    private Sample current;
    private BoardScene scene;
    private GpuTerrain terrain;
    private GpuAtmosphere atmosphere;
    private final BoardCamera camera = new BoardCamera();
    private int rendered;

    void add(String theme, String component, String variant, Image target) {
        add(new Sample(theme.isEmpty() ? "grass" : theme, component, variant, ""), target);
    }

    void addObject(String theme, String asset, Image target) { add(new Sample(theme, "ground", "", asset), target); }

    private void add(Sample sample, Image target) {
        cache.values().forEach(value -> value.targets().remove(target));
        queue.values().forEach(targets -> targets.remove(target));
        Cached ready = cache.get(sample);
        if (ready != null) { assign(target, ready.buffer()); ready.targets().add(target); }
        else { queue.computeIfAbsent(sample, ignored -> new ArrayList<>()).add(target); }
    }

    /** No waiting on the UI thread, and at most one completed image per frame. */
    void update() {
        cache.values().forEach(value -> value.targets().removeIf(image -> image.getStage() == null));
        queue.values().forEach(targets -> targets.removeIf(image -> image.getStage() == null));
        queue.entrySet().removeIf(entry -> entry.getValue().isEmpty() && !entry.getKey().equals(current));
        try {
            if (pending != null && pending.isDone()) {
                scene = pending.get(); pending = null;
                if (terrain == null) {
                    terrain = new GpuTerrain(); atmosphere = new GpuAtmosphere();
                    atmosphere.configure(BoardAtmosphere.DEFAULTS);
                    camera.resize(WIDTH, HEIGHT);
                }
                camera.setIsometric(true);
                if (current.component().isEmpty()) {
                    camera.orbit(-15, 0);
                    camera.fit(scene, THEME_HEXES.stream().map(scene::tile).toList());
                } else { camera.fit(scene); }
                atmosphere.updateLight(camera.camera); terrain.setAtmosphere(atmosphere.lighting());
                atmosphere.configureClouds(terrain, scene);
                terrain.update(scene, camera.camera);
            }
            if (scene != null) {
                terrain.refine(camera.camera);
                if (!terrain.busy() && terrain.ready(scene)) {
                    List<Image> targets = queue.get(current);
                    if (targets != null && !targets.isEmpty()) {
                        FrameBuffer buffer = render();
                        targets.forEach(target -> assign(target, buffer));
                        cache.put(current, new Cached(buffer, targets));
                        trim();
                    }
                    queue.remove(current);
                    scene = null; current = null;
                }
            }
            if (current == null && !queue.isEmpty()) {
                current = queue.keySet().stream().filter(sample -> sample.component().isEmpty()).findFirst()
                      .orElseGet(() -> queue.keySet().iterator().next());
                Sample sample = current;
                pending = worker.submit(() -> capture(sample));
            }
        } catch (Exception failure) {
            LOGGER.warn(failure, "Editor sample preview failed: {}", current);
            List<Image> targets = queue.remove(current);
            if (targets != null) { targets.forEach(target -> target.setUserObject("Preview unavailable")); }
            current = null; scene = null; pending = null;
        }
    }

    private FrameBuffer render() {
        FrameBuffer buffer = GpuAtmosphere.buffer(WIDTH, HEIGHT, true);
        try (TerrainSettings.Scope ignored = TerrainSettings.use(terrain.settings())) {
            terrain.animate(0, List.of());
            terrain.renderShadows(camera.camera, List.of());
            atmosphere.begin(WIDTH, HEIGHT, 0);
            if (current.component().isEmpty()) {
                for (Coords coords : THEME_HEXES) { terrain.renderEditorSection(camera.camera, coords, ""); }
            } else { terrain.render(camera.camera, false); terrain.renderTransparent(camera.camera); }
            atmosphere.end(camera.camera, terrain, scene, 0, null, false, buffer);
            buffer.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            return buffer;
        } catch (RuntimeException failure) { buffer.dispose(); throw failure; }
    }

    private BoardScene capture(Sample sample) {
        if (artwork == null) { artwork = new BoardArtwork(); }
        artwork.clear();
        BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
        boolean theme = sample.component().isEmpty();
        Board board = Board.createEmptyBoard(theme ? 2 : 1, theme ? 2 : 1);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                String componentId = theme ? x == 0 ? y == 0 ? "cliff" : "vegetation" : "ground" : sample.component();
                var component = blueprint.component(componentId);
                Hex hex = new Hex(component.previewLevel(), component.preview(), sample.theme());
                if (sample.variant().startsWith("preset:")) {
                    var preset = component.presets().stream().filter(p -> p.id().equals(sample.variant().substring(7))).findFirst().orElseThrow();
                    hex = new Hex(component.previewLevel(), preset.terrain(), sample.theme());
                    for (int type : new int[] { Terrains.ROAD, Terrains.BRIDGE }) {
                        Terrain terrain = hex.getTerrain(type);
                        if (terrain != null && !terrain.hasExitsSpecified()) { hex.addTerrain(new Terrain(type, terrain.getLevel(), true, 9)); }
                    }
                }
                if (theme) {
                    // The rectangular sample's unused corner is below the three rendered hexes. Cliff edges close
                    // their exposed skirts; only the three touching hexes are framed and rendered, using native meshes.
                    if (x == 1 && y == 1) { hex.setLevel(-1); }
                    else { hex.addTerrain(new Terrain(Terrains.CLIFF_TOP, 1, true, 63)); }
                }
                if (!sample.asset().isEmpty()) {
                    hex.setDecorations(List.of(new BoardDecoration("preview", "prop", sample.asset(), null, 0, 0, 0, false, 1,
                          BoardDecoration.Placement.ground(), 0)));
                }
                if (!sample.variant().isEmpty() && !sample.variant().startsWith("preset:")) {
                    var variant = blueprint.variant(sample.variant());
                    hex.setAppearance(Map.of(variant.owner(), variant.material().isEmpty()
                          ? new HexAppearance(variant.id(), null, null, variant.blend() ? .5 : null)
                          : new HexAppearance(null, null, variant.material(), null)));
                }
                board.setHex(new Coords(x, y), hex);
            }
        }
        var pixels = new BoardScene.PixelPool();
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords at = new Coords(x, y);
                tiles.add(BoardScene.captureTile(board.getHex(at), artwork.capture(board, at, true), null, pixels, board::getHex));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), List.copyOf(tiles), List.of(), List.of(), -1, "", List.of());
    }

    private static void assign(Image target, FrameBuffer buffer) {
        TextureRegion region = new TextureRegion(buffer.getColorBufferTexture()); region.flip(false, true);
        target.setDrawable(new TextureRegionDrawable(region)); target.setUserObject("Ready");
    }

    private void trim() {
        var entries = cache.entrySet().iterator();
        while (cache.size() > CACHE_SIZE && entries.hasNext()) {
            var entry = entries.next();
            // Never dispose a texture still displayed by a card or the selected theme's face.
            if (entry.getValue().targets().isEmpty()) { entry.getValue().buffer().dispose(); entries.remove(); }
        }
        // A terrain renderer also owns decoded models; retire that cache between batches of many different samples.
        if (++rendered >= CACHE_SIZE) {
            terrain.dispose(); terrain = null; atmosphere.dispose(); atmosphere = null; rendered = 0;
        }
    }

    @Override public void dispose() {
        queue.clear();
        if (pending != null) { pending.cancel(true); }
        worker.submit(() -> { if (artwork != null) { artwork.close(); artwork = null; } });
        worker.shutdown();
        cache.values().forEach(value -> value.buffer().dispose()); cache.clear();
        if (terrain != null) { terrain.dispose(); }
        if (atmosphere != null) { atmosphere.dispose(); }
    }
}
