/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/**
 * The board's sun shadow maps and their caches: two square maps in one texture, the near map round the view's focus
 * with its texel from the zoom alone and the outer map round it, so turning or tilting the camera changes no shadow
 * (drawLayers); when each is redrawn, and what it reuses (each map's terrain under moving units, an unchanged
 * projection). GpuTerrain casts into them ({@link Casters}).
 */
final class GpuTerrainShadows implements Disposable {
    /** What casts into the map, from the light's camera: the terrain's own depth pass and the units over it. */
    interface Casters {
        /** The terrain, its trees and objects, and these units. */
        void terrain(Camera light, List<ModelInstance> units);

        /** These units over the terrain already in the map. */
        void units(Camera light, List<ModelInstance> units);

        /** A unit's bounds in the world, which hold all it casts. */
        BoundingBox bounds(ModelInstance unit);

        /** Revision of a deforming caster whose transform and node pose can remain unchanged. */
        default int revision(ModelInstance unit) { return 0; }
    }

    /**
     * The map's texels across follow the scene's pixels (resolution): at least its 1080p size, at most its 4K size, or
     * twice as many with High shadows. {@code -Dmegamek.gpu.shadowTexels} sets one size for every scene.
     */
    static final int MIN_RESOLUTION = 2304, MAX_RESOLUTION = 4608, MAX_HIGH_RESOLUTION = 8192;
    private static final Integer FORCED_RESOLUTION = Integer.getInteger("megamek.gpu.shadowTexels");
    /** Shadow texels a scene pixel takes along the scene's longer side: 2304 for a 1920-pixel scene. */
    private static final float TEXELS_PER_PIXEL = 1.2f;
    /** Frames a changed size must hold before the map follows it (a window being resized, a quality switch). */
    private static final int SETTLE_FRAMES = 30;
    /** Texels on a side of the tiles in which moved units' shadows are restored from the cached terrain map. */
    private static final int SHADOW_TILE = 64;
    /** Copies per restore before one copy of the whole map is the cheaper (an estimate, not measured). */
    private static final int SHADOW_RUNS = 32;
    private static final float SHADOW_GUARD_SCALE = 1.1f;
    /**
     * A refit keeps the map's texel while the view's fit still has this much room in it and needs a texel at most
     * SHADOW_MAX_SCALE finer (the map moves by whole texels only), and a map is kept while its texel is so fine: the
     * texel grid stays put under the casters through pans and orbits and changes only past this band. Fresh maps take
     * the ladder's texel for the guarded fit, 1.1 to 1.124 times the fit's.
     */
    private static final float SHADOW_KEEP_SCALE = 1.02f;
    private static final float SHADOW_MAX_SCALE = 1.2f;
    /** Texel sizes lie on a fixed ladder, this many steps an octave. */
    private static final int SHADOW_LADDER = 32;

    private final Casters casters;
    private final List<Model> shadowModels = new ArrayList<>();
    private final List<Matrix4> shadowTransforms = new ArrayList<>();
    private final com.badlogic.gdx.utils.IntArray shadowPoses = new com.badlogic.gdx.utils.IntArray();
    /** Each drawn unit's footprint in the near map and in the outer map ({@link #shadowTexels}), as shadowModels. */
    private final List<int[]> shadowRects = new ArrayList<>(), farShadowRects = new ArrayList<>();
    private final Matrix4 shadowView = new Matrix4();
    private boolean shadowProjectionValid;
    /** The retained map is clear while its directional source is zero; cleared once per such interval. */
    private boolean shadowEmpty;
    private boolean shadowViewPresent;
    private GpuShadowLight shadow;
    /**
     * The near map's texel in scene pixels at the view's focus: 0.11 m at the user's Mines 1 zoom, where one map fitted
     * to the view took 0.09 m top-down, 0.14 m at a 55 degree tilt and 0.28 m at 79.
     */
    private static final float NEAR_PIXELS = 1.6f;
    /** The outer map's side in near maps' sides: it holds what an orthographic view shows at its lowest tilt. */
    private static final float OUTER = 3;
    /** A map stays while the focus stays within this share of its side from its middle. */
    private static final float NEAR_KEEP = .1f;
    // Each map's terrain without units and whether it holds its camera's; the cache whose terrain each half of the
    // texture holds under its units (null after a render no cache holds); refined chunks the outer map redraws once
    // refinement settles; whether both maps are one (the board-wide fit), drawn once.
    private FrameBuffer nearTerrain, farTerrain;
    private FrameBuffer shadowBase, farBase;
    private boolean nearValid, farValid, farRefinedDue, farShared;
    private final GpuShadowLight.SlabCamera layerFit = new GpuShadowLight.SlabCamera(),
          wholeFit = new GpuShadowLight.SlabCamera();
    private final BoundingBox farRefined = new BoundingBox().inf();
    private boolean shadowDirty;
    private long revision;
    /** The revision whose map the coarser levels hold (GpuShadowLight#levels); -1 for none. */
    private long levelsRevision = -1;

    long revision() { return revision; }
    // Frames since a camera refinement last replaced a chunk the cached shadow has not redrawn; -1 when none.
    private int refinedShadowFrames = -1;
    // The battle view's scene (setScene): its longer side in pixels, 0 for none, and High shadows (Display tab); the map's
    // texels across, and a different size asked for with the frames it has held.
    private float scenePixels;
    private boolean high;
    private int resolution = MIN_RESOLUTION, wanted, wantedFrames;
    /** The context's largest texture side, read once. */
    private int maxTexture;

    GpuTerrainShadows(Casters casters) { this.casters = casters; }

    /** Redraws the map at the next pass, its terrain too (GpuTerrain#refreshShadows). */
    void invalidate() { shadowDirty = true; }

    /**
     * The terrain's casters changed within {@code region} alone (a structure left its chunk for its falling pieces): the
     * cached terrain map is redrawn at the next pass over the texels the region casts into, and nowhere else
     * ({@link #redraw}). Where no cache holds the terrain, or the map is to be redrawn anyway, the whole map is.
     */
    void invalidate(BoundingBox region) {
        // No map yet, or none cached under its units: the next one is drawn whole anyway.
        if (shadow == null || !region.isValid()) { return; }
        regions.add(new BoundingBox(region));
    }

    /** The regions whose casters changed since the last pass ({@link #invalidate(BoundingBox)}). */
    private final List<BoundingBox> regions = new ArrayList<>();

    /** A refined chunk casts its terrain at finer detail: the map serves until refinement settles, then redraws once. */
    void refined() { refinedShadowFrames = 0; }

    /** As {@link #refined()}; the outer map redraws where the chunk lies outside the near map's middle. */
    void refined(BoundingBox chunk) {
        refined();
        farRefined.ext(chunk);
    }

    /**
     * A chunk is still being refined: the redraw waits for it too. Counting frames since the last refined chunk alone
     * redrew the map between two chunks wherever a frame is shorter than a chunk's build (ten redraws for a city).
     */
    void refining() {
        if (refinedShadowFrames >= 0) { refinedShadowFrames = 0; }
    }

    /** The map's texels across. */
    int resolution() { return resolution; }

    /**
     * The battle view's scene: {@code pixels} along its longer side as the render resolution draws it, and High shadows
     * (twice the texels a pixel takes). The map follows once they hold (followScene). Prints and previews never set it
     * and keep {@link #MIN_RESOLUTION}, so a window resized during a tiled print cannot change its tiles' shadows.
     */
    void setScene(float pixels, boolean high) {
        scenePixels = pixels;
        this.high = high;
    }

    /**
     * Texels across the map for a scene of {@code pixels} along its longer side: {@link #TEXELS_PER_PIXEL} each, twice
     * that when {@code high}, in whole tiles, from {@link #MIN_RESOLUTION} to {@link #MAX_RESOLUTION} (or
     * {@link #MAX_HIGH_RESOLUTION}) and the largest texture the context allows. The map is fitted to the view, so a
     * texel then covers about as many pixels at every scene size.
     */
    static int resolution(float pixels, boolean high, int maxTexture) {
        int largest = maxTexture / SHADOW_TILE * SHADOW_TILE;
        if (FORCED_RESOLUTION != null) { return tiled(Math.clamp(FORCED_RESOLUTION, SHADOW_TILE, largest)); }
        int most = Math.min(high ? MAX_HIGH_RESOLUTION : MAX_RESOLUTION, largest);
        return Math.max(Math.min(MIN_RESOLUTION, most), Math.min(tiled(pixels * TEXELS_PER_PIXEL * (high ? 2 : 1)), most));
    }

    /** Texels up to whole restore tiles. */
    private static int tiled(float texels) { return (int) Math.ceil(texels / SHADOW_TILE) * SHADOW_TILE; }

    /** The texels across the scene asks for (setScene). */
    private int sceneResolution() {
        if (maxTexture == 0) {
            var values = BufferUtils.newIntBuffer(16);
            Gdx.gl.glGetIntegerv(GL20.GL_MAX_TEXTURE_SIZE, values);
            maxTexture = values.get(0);
        }
        return resolution(scenePixels, high, maxTexture);
    }

    /** The light, made on first use, as the environment's shadow map. */
    GpuShadowLight bind(Environment environment) {
        if (shadow == null) {
            resolution = sceneResolution();
            shadow = new GpuShadowLight(resolution, 1, 1, 1, 2);
            layerFit.texels = wholeFit.texels = resolution;
            shadowProjectionValid = false;
        }
        if (environment.shadowMap != shadow) {
            environment.add(shadow);
            environment.shadowMap = shadow;
        }
        return shadow;
    }

    /** No light at all: the light and its map go. */
    void release(Environment environment) {
        if (shadow != null) {
            environment.remove(shadow);
            shadow.dispose();
            shadow = null;
            shadowEmpty = false;
            // The caches go with the map: the next one may take another size.
            disposeCaches();
        }
        environment.shadowMap = null;
    }

    /**
     * Redraws the map for this view and these units where they changed, from what it may reuse. {@code casterBounds}:
     * what casts, the board's terrain and objects; {@code bound}: the map is the environment's; {@code directLight}:
     * the sun or moon lights the board at all; {@code levels}: its programs read the map's coarser levels.
     */
    void render(Camera view, List<ModelInstance> units, BoundingBox casterBounds, boolean bound, boolean directLight,
          boolean levels) {
        if (shadow == null || !bound) {
            return;
        }
        draw(view, units, casterBounds, directLight);
        // The widest penumbrae read the map's coarser levels (contactShadow, without cheaper shading): written once a
        // revision, from the map as this frame left it, whether drawn, copied from a cache or restored around moved units.
        if (levels && !shadowEmpty && levelsRevision != revision) {
            shadow.levels();
            levelsRevision = revision;
        }
    }

    private void draw(Camera view, List<ModelInstance> units, BoundingBox casterBounds, boolean directLight) {
        followScene();
        // Refinement has settled: the refined chunks cast their own shadow (replaceDetail).
        if (refinedShadowFrames >= 0 && ++refinedShadowFrames > GpuMeshPage.SETTLE_FRAMES) {
            refinedShadowFrames = -1;
            // The near map, which holds the view's chunks, is redrawn; the outer map only where refined chunks lie
            // outside the near map's middle (drawLayers).
            nearValid = false;
            farRefinedDue = true;
        }
        if (!directLight) {
            // The map is drawn whole when the light returns (shadowDirty): no region waits for it.
            regions.clear();
            if (!shadowEmpty) {
                revision++;
                shadow.begin();
                shadow.end();
                shadowBase = farBase = null;
                shadowEmpty = true;
                shadowProjectionValid = false;
                shadowDirty = true;
            }
            return;
        }
        shadowEmpty = false;
        // A unit changed since the map last drew it: new, moved or posed anew.
        int[] poses = new int[units.size()];
        boolean[] moved = new boolean[units.size()];
        boolean changed = shadowDirty || !regions.isEmpty() || !shadowProjectionValid
              || units.size() != shadowModels.size() || !(nearValid && farValid);
        for (int index = 0; index < units.size(); index++) {
            ModelInstance unit = units.get(index);
            poses[index] = 31 * shadowPose(unit) + casters.revision(unit);
            moved[index] = index >= shadowModels.size() || unit.model != shadowModels.get(index)
                  || !Arrays.equals(unit.transform.val, shadowTransforms.get(index).val) || poses[index] != shadowPoses.get(index);
            changed |= moved[index];
        }
        boolean viewChanged = (view != null) != shadowViewPresent
              || (view != null && !Arrays.equals(view.combined.val, shadowView.val));
        if (!changed && !viewChanged) {
            return;
        }
        BoundingBox bounds = new BoundingBox(casterBounds);
        for (ModelInstance unit : units) { bounds.ext(casters.bounds(unit)); }
        drawLayers(view, units, bounds, changed, poses, moved);
    }

    /**
     * The two maps: the near map, the light's own camera, centred on the view's focus with its texel
     * {@link #NEAR_PIXELS} scene pixels at the focus's zoom, so neither a turn nor a tilt changes it; and the outer
     * map, {@link #OUTER} times as wide round the same focus, for what a low view shows beyond it. Each takes the
     * board-wide fit where its side would hold the board (both: one map, drawn once). Each keeps its terrain in a cache
     * of its own; the texture holds the near map in its lower half and the outer map in its upper half, each copied
     * from its cache with the units drawn over it: only the tiles moved units touch where the half holds that cache
     * already. A view without a zoom (prints, previews) fits the near map to its receivers and takes the board-wide
     * outer map.
     */
    private void drawLayers(Camera view, List<ModelInstance> units, BoundingBox bounds, boolean changed, int[] poses,
          boolean[] moved) {
        GpuShadowLight.SlabCamera near = (GpuShadowLight.SlabCamera) shadow.getCamera(), far = shadow.far;
        // The board-wide fit, every caster: either map takes it once its own side would hold the board.
        fitShadowToReceivers(bounds, wholeFit, bounds, shadow.direction);
        float side = nearSide(view);
        Vector3 focus = Float.isNaN(side) ? null : focus((BoardProjectionCamera) view);
        if (!fitLayer(near, focus, side, view, bounds)) { nearValid = false; }
        // A perspective view reaches its horizon, which only the board-wide fit holds.
        boolean perspective = view instanceof BoardProjectionCamera board && board.perspective;
        if (!fitLayer(far, perspective ? null : focus, side * OUTER, null, bounds)) {
            shadow.farMoved();
            farValid = false;
        }
        shadowViewPresent = view != null;
        if (view != null) { shadowView.set(view.combined); }
        // Both on the board-wide fit (the whole board in view): one map, drawn once, in both halves.
        boolean one = sameFit(near, far);
        if (!one && farShared) { farValid = false; }
        if (!changed && nearValid && farValid) { return; }
        Gdx.gl.glDepthMask(true);
        if (shadowDirty) { nearValid = farValid = false; }
        // Where structures' pieces began to cast (invalidate(region)), and refined chunks outside the near map: each
        // cache redrawn there alone, and its half copied whole.
        for (BoundingBox region : regions) {
            if (nearValid) {
                redraw(nearTerrain, near, shadowTexels(near, region));
                shadowBase = null;
                if (one) { farBase = null; }
            }
            if (farValid && !one) {
                redraw(farTerrain, far, shadowTexels(far, region));
                farBase = null;
            }
        }
        regions.clear();
        if (farRefinedDue) {
            if (farValid && !one && farRefined.isValid() && !insideNear(farRefined)) {
                redraw(farTerrain, far, shadowTexels(far, farRefined));
                farBase = null;
            }
            farRefined.inf();
            farRefinedDue = false;
        }
        if (!nearValid) {
            nearTerrain = terrainInto(nearTerrain, near);
            nearValid = true;
            // One map: the outer half holds this cache too.
            shadowBase = farBase = null;
        }
        if (!farValid) {
            if (!one) { farTerrain = terrainInto(farTerrain, far); }
            farValid = true;
            farBase = null;
        }
        farShared = one;
        int[][] nearRects = new int[units.size()][], farRects = new int[units.size()][];
        for (int index = 0; index < units.size(); index++) {
            BoundingBox unit = casters.bounds(units.get(index));
            nearRects[index] = shadowTexels(near, unit);
            farRects[index] = shadowTexels(far, unit);
        }
        shadowBase = compose(nearTerrain, shadowBase, near, 0, units, moved, shadowRects, nearRects);
        farBase = compose(one ? nearTerrain : farTerrain, farBase, far, resolution, units, moved, farShadowRects,
              farRects);
        rememberUnits(units, poses, nearRects, farRects);
        shadowProjectionValid = true;
        shadowDirty = false;
        revision++;
    }

    /**
     * One half of the texture (row {@code y}) from its map's terrain {@code cache}, then the units over it: only the
     * restore tiles the moved units' shadows covered ({@code before}) or now cover ({@code after}) where the half holds
     * that cache already ({@code base}), else all of it. The cache the half now holds.
     */
    private FrameBuffer compose(FrameBuffer cache, FrameBuffer base, Camera camera, int y, List<ModelInstance> units,
          boolean[] moved, List<int[]> before, int[][] after) {
        boolean[] stale = new boolean[tiles() * tiles()];
        for (int index = 0; index < Math.max(units.size(), before.size()); index++) {
            if (index < units.size() && !moved[index]) { continue; }
            if (index < before.size()) { markTiles(stale, before.get(index)); }
            if (index < units.size()) { markTiles(stale, after[index]); }
        }
        restoreTiles(cache, cache == base, stale, y);
        unitsInto(camera, units, y);
        return cache;
    }

    /**
     * The near map's side (world units) for this view: its texels {@link #NEAR_PIXELS} scene pixels each at the focus's
     * zoom, which the perspective view shows its pivot's plane at too; NaN for a view without one.
     */
    private float nearSide(Camera view) {
        if (!(view instanceof BoardProjectionCamera board)) { return Float.NaN; }
        float viewport = Math.max(board.viewportWidth, board.viewportHeight);
        return resolution * NEAR_PIXELS * board.zoom * viewport / (scenePixels > 0 ? scenePixels : viewport);
    }

    /** The view's focus: its orbit pivot. */
    private static Vector3 focus(BoardProjectionCamera view) {
        return new Vector3(view.position).mulAdd(view.direction, view.pivotDepth);
    }

    /**
     * One map's fit (two maps): a square of {@code side} world units centred on {@code focus}, snapped to its texels
     * and kept while the focus stays within NEAR_KEEP of its side and the side within the keep band; the board-wide
     * fit (wholeFit) where that square would hold the board (a tenth less to leave it). Without a focus: {@code view}'s
     * receivers' fit, as one map takes it, or without a view the board-wide fit. Whether the camera kept its fit.
     */
    private boolean fitLayer(GpuShadowLight.SlabCamera camera, Vector3 focus, float side, Camera view,
          BoundingBox bounds) {
        boolean whole = sameFit(camera, wholeFit);
        if (focus == null && view != null) {
            fitShadowCamera(view, layerFit, bounds, shadow.direction);
            if (shadowProjectionValid && canReuseShadowCamera(camera, layerFit)) { return true; }
            remember(camera, layerFit);
            camera.viewportWidth = ladderExtent(layerFit.viewportWidth);
            camera.viewportHeight = ladderExtent(layerFit.viewportHeight);
            snapShadowCamera(camera);
            return false;
        }
        if (focus == null || side >= Math.max(wholeFit.viewportWidth, wholeFit.viewportHeight) * (whole ? .9f : 1)) {
            if (shadowProjectionValid && whole) { return true; }
            remember(camera, wholeFit);
            camera.update();
            return false;
        }
        fitShadowToReceivers(new BoundingBox(focus, focus), layerFit, bounds, shadow.direction);
        if (shadowProjectionValid && !whole && camera.direction.equals(layerFit.direction)
              && camera.up.equals(layerFit.up) && camera.near == layerFit.near && camera.far == layerFit.far
              && keepsTexel(camera.viewportWidth, side) && centred(camera, layerFit)) {
            return true;
        }
        remember(camera, layerFit);
        camera.viewportWidth = camera.viewportHeight = ladderExtent(side);
        snapShadowCamera(camera);
        return false;
    }

    /** Whether the near map still has the focus within NEAR_KEEP of its side from its middle. */
    private static boolean centred(Camera current, Camera required) {
        Vector3 right = new Vector3(current.direction).crs(current.up).nor();
        Vector3 shift = new Vector3(required.position).sub(current.position);
        float reach = NEAR_KEEP * Math.min(current.viewportWidth, current.viewportHeight);
        return Math.abs(shift.dot(right)) <= reach && Math.abs(shift.dot(current.up)) <= reach;
    }

    /** Whether a box casts only into the near map's middle, where lookups read the near map alone. */
    private boolean insideNear(BoundingBox box) {
        int[] texels = shadowTexels(shadow.getCamera(), box);
        int margin = resolution / 8;
        return texels[0] >= margin && texels[1] >= margin && texels[2] <= resolution - margin
              && texels[3] <= resolution - margin;
    }

    /** The terrain's casters from {@code camera} into {@code cache} (made if absent), its edge left clear. */
    private FrameBuffer terrainInto(FrameBuffer cache, Camera camera) {
        if (cache == null) { cache = GpuShadowLight.depthBuffer(resolution, resolution); }
        cache.begin();
        try {
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glClearDepthf(1);
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glScissor(1, 1, resolution - 2, resolution - 2);
            GpuShadowLight.depthClamp(true);
            casters.terrain(camera, List.of());
        } finally {
            GpuShadowLight.depthClamp(false);
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            cache.end();
        }
        return cache;
    }

    /** The units from {@code camera} over the terrain already in the texture's square at row {@code y}. */
    private void unitsInto(Camera camera, List<ModelInstance> units, int y) {
        if (units.isEmpty()) { return; }
        shadow.getFrameBuffer().begin();
        try {
            Gdx.gl.glViewport(0, y, resolution, resolution);
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glScissor(1, y + 1, resolution - 2, resolution - 2);
            GpuShadowLight.depthClamp(true);
            casters.units(camera, units);
        } finally {
            GpuShadowLight.depthClamp(false);
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            shadow.getFrameBuffer().end();
        }
    }

    /** The units as the maps now hold them, and each one's footprint in the near and in the outer map. */
    private void rememberUnits(List<ModelInstance> units, int[] poses, int[][] nearRects, int[][] farRects) {
        shadowModels.clear();
        shadowPoses.clear();
        shadowRects.clear();
        farShadowRects.clear();
        while (shadowTransforms.size() > units.size()) { shadowTransforms.removeLast(); }
        for (int index = 0; index < units.size(); index++) {
            ModelInstance unit = units.get(index);
            shadowModels.add(unit.model);
            if (index == shadowTransforms.size()) { shadowTransforms.add(new Matrix4()); }
            shadowTransforms.get(index).set(unit.transform);
            shadowPoses.add(poses[index]);
            shadowRects.add(nearRects[index]);
            farShadowRects.add(farRects[index]);
        }
    }

    /**
     * A size held for {@link #SETTLE_FRAMES} frames resizes the map, whose caches go with its depth; this render then
     * draws it whole.
     */
    private void followScene() {
        int next = sceneResolution();
        if (next == resolution) { wantedFrames = 0; return; }
        wantedFrames = next == wanted ? wantedFrames + 1 : 0;
        wanted = next;
        if (wantedFrames < SETTLE_FRAMES) { return; }
        resolution = next;
        wantedFrames = 0;
        shadow.resize(resolution);
        layerFit.texels = wholeFit.texels = resolution;
        disposeCaches();
        // Its depth is undefined until drawn or cleared: a lightless board clears it again.
        shadowEmpty = false;
        shadowProjectionValid = false;
        shadowDirty = true;
    }

    /** The cached terrain maps, sized as the map they were drawn for. */
    private void disposeCaches() {
        if (nearTerrain != null) { nearTerrain.dispose(); nearTerrain = null; }
        if (farTerrain != null) { farTerrain.dispose(); farTerrain = null; }
        nearValid = farValid = false;
        shadowBase = farBase = null;
    }

    /** A unit's footprint in the light's map, in texels from x0, y0 to x1, y1, a texel wider each side. */
    private int[] shadowTexels(Camera lightCamera, BoundingBox bounds) {
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = x1;
        Vector3 corner = new Vector3();
        for (int i = 0; i < 8; i++) {
            corner.set((i & 1) == 0 ? bounds.min.x : bounds.max.x, (i & 2) == 0 ? bounds.min.y : bounds.max.y,
                  (i & 4) == 0 ? bounds.min.z : bounds.max.z).prj(lightCamera.combined);
            x0 = Math.min(x0, corner.x);
            y0 = Math.min(y0, corner.y);
            x1 = Math.max(x1, corner.x);
            y1 = Math.max(y1, corner.y);
        }
        float half = resolution / 2f;
        return new int[] { Math.clamp((int) Math.floor((x0 + 1) * half) - 1, 0, resolution),
              Math.clamp((int) Math.floor((y0 + 1) * half) - 1, 0, resolution),
              Math.clamp((int) Math.ceil((x1 + 1) * half) + 1, 0, resolution),
              Math.clamp((int) Math.ceil((y1 + 1) * half) + 1, 0, resolution) };
    }

    /** The map's restore tiles across. */
    private int tiles() { return resolution / SHADOW_TILE; }

    private void markTiles(boolean[] tiles, int[] texels) {
        int across = tiles();
        int top = Math.min(across, (texels[3] + SHADOW_TILE - 1) / SHADOW_TILE);
        int right = Math.min(across, (texels[2] + SHADOW_TILE - 1) / SHADOW_TILE);
        for (int y = texels[1] / SHADOW_TILE; y < top; y++) {
            for (int x = texels[0] / SHADOW_TILE; x < right; x++) { tiles[y * across + x] = true; }
        }
    }

    /**
     * A map's cached terrain back into its half of the texture (row {@code y}) over the stale tiles, a run of them in a
     * row per copy, instead of the whole half (2304 by 2304 depth texels or more) whenever a unit moves. The whole half
     * in one copy where it holds another cache's terrain ({@code holds} false: the two may hold different levels of
     * detail), past half the half, or past SHADOW_RUNS copies.
     */
    private void restoreTiles(FrameBuffer cache, boolean holds, boolean[] stale, int y) {
        var runs = new com.badlogic.gdx.utils.IntArray();
        int marked = 0;
        int across = tiles();
        for (int row = 0; row < across; row++) {
            for (int x = 0; x < across; x++) {
                if (!stale[row * across + x]) { continue; }
                int end = x;
                while (end + 1 < across && stale[row * across + end + 1]) { end++; }
                runs.addAll(x * SHADOW_TILE, row * SHADOW_TILE, (end + 1) * SHADOW_TILE, (row + 1) * SHADOW_TILE);
                marked += end + 1 - x;
                x = end;
            }
        }
        if (!holds || marked * 2 > stale.length || runs.size / 4 > SHADOW_RUNS) {
            GpuShadowLight.copyLayer(cache, shadow.getFrameBuffer(), y);
        } else if (!runs.isEmpty()) {
            GpuShadowLight.copyDepth(cache, shadow.getFrameBuffer(), runs, y);
        }
    }

    /**
     * Draws the terrain's casters into {@code cache} over the texels from x0, y0 to x1, y1 alone, at whole restore tiles:
     * the same projection and the same casters' detail as the whole map's, the chunks outside the rectangle culled by a
     * frustum narrowed to it, everything else kept from before by the scissor.
     */
    private void redraw(FrameBuffer cache, Camera lightCamera, int[] texels) {
        int x0 = texels[0] / SHADOW_TILE * SHADOW_TILE, y0 = texels[1] / SHADOW_TILE * SHADOW_TILE;
        int x1 = Math.min(resolution, (texels[2] + SHADOW_TILE - 1) / SHADOW_TILE * SHADOW_TILE);
        int y1 = Math.min(resolution, (texels[3] + SHADOW_TILE - 1) / SHADOW_TILE * SHADOW_TILE);
        if (x1 <= x0 || y1 <= y0) { return; }
        GpuShadowLight.SlabCamera region = regionCamera, narrow = narrowCamera;
        remember(region, lightCamera);
        region.texels = ((GpuShadowLight.SlabCamera) lightCamera).texels;
        region.update();
        // The frustum of the rectangle alone, about its middle in the light's frame: chunks beyond it are not drawn.
        remember(narrow, lightCamera);
        var right = new Vector3(lightCamera.direction).crs(lightCamera.up).nor();
        var up = new Vector3(right).crs(lightCamera.direction).nor();
        float u = (x0 + x1) / (float) resolution - 1, v = (y0 + y1) / (float) resolution - 1;
        narrow.position.mulAdd(right, u * lightCamera.viewportWidth / 2).mulAdd(up, v * lightCamera.viewportHeight / 2);
        narrow.viewportWidth = lightCamera.viewportWidth * (x1 - x0) / resolution;
        narrow.viewportHeight = lightCamera.viewportHeight * (y1 - y0) / resolution;
        narrow.update();
        region.frustum.update(narrow.invProjectionView);
        cache.begin();
        try {
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glScissor(x0, y0, x1 - x0, y1 - y0);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glClearDepthf(1);
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            GpuShadowLight.depthClamp(true);
            casters.terrain(region, List.of());
        } finally {
            GpuShadowLight.depthClamp(false);
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            cache.end();
        }
    }

    /** The light's camera for a region's redraw ({@link #redraw}), and the narrower one whose frustum culls for it. */
    private final GpuShadowLight.SlabCamera regionCamera = new GpuShadowLight.SlabCamera(),
          narrowCamera = new GpuShadowLight.SlabCamera();

    private static boolean sameFit(Camera a, Camera b) {
        return a.position.equals(b.position) && a.direction.equals(b.direction) && a.up.equals(b.up)
              && a.viewportWidth == b.viewportWidth && a.viewportHeight == b.viewportHeight && a.near == b.near && a.far == b.far;
    }

    private static void remember(Camera target, Camera source) {
        target.position.set(source.position);
        target.direction.set(source.direction);
        target.up.set(source.up);
        target.viewportWidth = source.viewportWidth;
        target.viewportHeight = source.viewportHeight;
        target.near = source.near;
        target.far = source.far;
    }

    private static int poseHash(Iterable<Node> nodes) {
        int result = 1;
        for (Node node : nodes) {
            result = 31 * result + Arrays.hashCode(node.globalTransform.val);
            for (var part : node.parts) {
                result = 31 * result + (part.enabled ? 1 : 0);
            }
            result = 31 * result + poseHash(node.getChildren());
        }
        return result;
    }

    private static int shadowPose(ModelInstance instance) {
        return 31 * poseHash(instance.nodes) + (instance instanceof GpuUnitInstance unit ? unit.detailRevision() : 0);
    }

    /** Focus texels on visible receivers, retaining the full light depth for offscreen shadow casters. */
    static void fitShadowCamera(Camera view, GpuShadowLight.SlabCamera target, BoundingBox bounds, Vector3 direction) {
        fitShadowToReceivers(view == null ? bounds : BoardCamera.viewportBounds(view, bounds), target, bounds, direction);
    }

    /** Focus texels on these receivers, retaining the full light depth for offscreen shadow casters. */
    static void fitShadowToReceivers(BoundingBox receivers, GpuShadowLight.SlabCamera target, BoundingBox bounds,
          Vector3 direction) {
        target.direction.set(direction).nor();
        Vector3 right = new Vector3(target.direction)
              .crs(Math.abs(target.direction.z) > 0.99f ? Vector3.Y : Vector3.Z).nor();
        target.up.set(right).crs(target.direction).nor();
        BoundingBox lightSpace = new BoundingBox().inf();
        float near = Float.POSITIVE_INFINITY;
        float far = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            Vector3 point = new Vector3((corner & 1) == 0 ? receivers.min.x : receivers.max.x,
                  (corner & 2) == 0 ? receivers.min.y : receivers.max.y,
                  (corner & 4) == 0 ? receivers.min.z : receivers.max.z);
            lightSpace.ext(point.dot(right), point.dot(target.up), 0);
            point.set((corner & 1) == 0 ? bounds.min.x : bounds.max.x,
                  (corner & 2) == 0 ? bounds.min.y : bounds.max.y,
                  (corner & 4) == 0 ? bounds.min.z : bounds.max.z);
            float depth = point.dot(target.direction);
            near = Math.min(near, depth);
            far = Math.max(far, depth);
        }
        // Leave a filtering border, and align the light grid to texels to avoid crawling edges while panning.
        float border = 1 + 4f / target.texels;
        target.viewportWidth = Math.max(1, lightSpace.getWidth()) * border;
        target.viewportHeight = Math.max(1, lightSpace.getHeight()) * border;
        Vector3 center = lightSpace.getCenter(new Vector3());
        target.position.set(right).scl(center.x).mulAdd(target.up, center.y);
        if (GpuShadowLight.slab(target.direction)) {
            // Depth runs down all casters' heights, onscreen or not, from a world unit above the highest to one below
            // the lowest (GpuShadowLight.SlabCamera).
            target.near = bounds.max.z + 1;
            target.far = bounds.min.z - 1;
        } else {
            // Lower than the world sun ever is, along the light over all casters, onscreen or not.
            target.position.mulAdd(target.direction, near - 1);
            target.near = 1;
            target.far = far - near + 2;
        }
        snapShadowCamera(target);
    }

    private static void snapShadowCamera(GpuShadowLight.SlabCamera target) {
        Vector3 right = new Vector3(target.direction).crs(target.up).nor();
        float x = target.position.dot(right), y = target.position.dot(target.up);
        float texelX = target.viewportWidth / target.texels;
        float texelY = target.viewportHeight / target.texels;
        target.position.mulAdd(right, Math.round(x / texelX) * texelX - x)
              .mulAdd(target.up, Math.round(y / texelY) * texelY - y);
        target.update();
    }

    /** Keep the existing projection only while it covers all receivers/caster depths at the required detail. */
    static boolean canReuseShadowCamera(Camera cached, Camera required) {
        if (!cached.direction.equals(required.direction) || !cached.up.equals(required.up)
              || cached.viewportWidth > required.viewportWidth * SHADOW_MAX_SCALE
              || cached.viewportHeight > required.viewportHeight * SHADOW_MAX_SCALE) { return false; }
        Vector3 point = new Vector3();
        for (Vector3 corner : required.frustum.planePoints) {
            point.set(corner).prj(cached.combined);
            // The required fit already includes the PCF border. Allow only floating-point depth roundoff.
            if (Math.abs(point.x) > 1 || Math.abs(point.y) > 1 || Math.abs(point.z) > 1.00001f) { return false; }
        }
        return true;
    }

    /** Whether the light's extent across one axis still serves a fit needing {@code required} there. */
    private static boolean keepsTexel(float current, float required) {
        return current >= required * SHADOW_KEEP_SCALE && current <= required * SHADOW_MAX_SCALE;
    }

    /** The light's extent across one axis for a fit needing {@code required}: the guarded fit's, its texel on the ladder. */
    private float ladderExtent(float required) {
        double steps = Math.ceil(Math.log(required * SHADOW_GUARD_SCALE / resolution) / Math.log(2) * SHADOW_LADDER);
        return (float) Math.pow(2, steps / SHADOW_LADDER) * resolution;
    }

    @Override
    public void dispose() {
        if (nearTerrain != null) { nearTerrain.dispose(); }
        if (farTerrain != null) { farTerrain.dispose(); }
        if (shadow != null) {
            shadow.dispose();
        }
    }
}
