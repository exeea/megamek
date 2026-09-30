/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;
import megamek.common.board.Coords;

/** Chunked solid hex columns, flat decals and authored features; owns all GL resources it creates. */
final class GpuTerrain implements Disposable {
    static final int CHUNK_SIZE = TerrainLod.CHUNK_SIZE;
    // Extra texels pay for the camera guard band without reducing the former 2048-map's world-space detail.
    static final int SHADOW_RESOLUTION = 2304;
    private static final float SHADOW_GUARD_SCALE = 1.1f;
    private static final float SHADOW_MAX_SCALE = SHADOW_RESOLUTION / 2048f;
    static final float DEFAULT_BUILDING_OPACITY = 0.5f;
    private static final long ATTRIBUTES = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
          | VertexAttributes.Usage.TextureCoordinates | VertexAttributes.Usage.ColorPacked;
    private final GpuAssets assets;
    private final BoardSurface.Cache pickingSurfaces = new BoardSurface.Cache();
    private final GpuRoads.Masks roadMaskData = new GpuRoads.Masks();
    /** Finished CPU geometry is useful for queries/edits, but must not grow with every uploaded hex. */
    private record CpuGeometry(BoardTacticalGeometry.Surface tactical, BoardSurface.WaterGeometry water) { }
    private final Map<TileMesh, CpuGeometry> cpuGeometry = new LinkedHashMap<>(32, .75f, true);
    private final boolean liquidShaderAnimation;
    private final boolean proceduralWater;
    private final Texture rainNoise = rainNoise();
    private final Texture waterDetail = GpuWaterShader.detailTexture();
    /** Wind waves for every open water surface, advanced once per frame. */
    private final GpuOcean ocean = new GpuOcean();
    private final GpuWaterExposure waterExposure = new GpuWaterExposure();
    /** The nearest wave of this frame; the scene's depth keeps the beds and units beneath it. */
    private final GpuWaterDepth waterDepth = new GpuWaterDepth();
    /** The shared FFT engine with a viscous lava spectrum, independent of wind. */
    private final GpuOcean lavaOcean = new GpuOcean(true);
    /** Units standing partly in open water, which the water laps against. */
    private final GpuWaders waders = new GpuWaders();
    private final GpuUnitModels unitModels;
    private Model limbModel;
    private record GroundSlot(Coords coords, boolean rims) { }
    private final GpuTextures<GroundSlot> ground = new GpuTextures<>(true);
    private final BoardRim rims = new BoardRim();
    private final GpuTextures<Coords> decals = new GpuTextures<>();
    private final GpuTextures<Coords> tactical = new GpuTextures<>();
    private final GpuTextures<Coords> foliage = new GpuTextures<>(true);
    private final GpuTerrainBatch terrainBatch = new GpuTerrainBatch(material -> material.has(Ground.TYPE)
          && !material.has(GpuLiquidShader.Frame.TYPE) && !material.has(GpuWaterShader.TYPE));
    private final GpuTerrainPages terrainPages = new GpuTerrainPages(terrainBatch::eligible);
    private final GpuWaterPages waterPages = new GpuWaterPages();
    private final ModelBatch batch = new ModelBatch(GpuShaderManager.provider("Terrain", () -> new DefaultShaderProvider(
          GpuCloudShadow.vertex(GpuUnitShader.linearVertex(DefaultShader.getDefaultVertexShader())),
          GpuCloudShadow.fragment(GpuUnitShader.linearFragment(DefaultShader.getDefaultFragmentShader()), false)) {
        private final DefaultShader.Config groundShader = new DefaultShader.Config(config.vertexShader,
              litFragment("terrain-normal.frag"));
        private final DefaultShader.Config roadShader = new DefaultShader.Config(GpuRoads.vertex(config.vertexShader),
              litFragment("terrain-road.frag"));
        private final DefaultShader.Config sculptShader = new DefaultShader.Config(GpuSurfaceBlend.vertex(config.vertexShader),
              litFragment("terrain-sculpt.frag"));
        private final DefaultShader.Config foliageShader = new DefaultShader.Config(config.vertexShader,
              litFragment("terrain-foliage.frag"));
        private final DefaultShader.Config instancedFoliageShader = new DefaultShader.Config(
              GpuTreeInstances.vertex(config.vertexShader), foliageShader.fragmentShader);
        private final DefaultShader.Config instancedPropShader = new DefaultShader.Config(
              GpuTreeInstances.vertex(config.vertexShader), config.fragmentShader);
        private final DefaultShader.Config vegetationShader = new DefaultShader.Config(GpuGroundCover.vertex(config.vertexShader),
              rainFragment(GpuCloudShadow.fragment(GpuShaderSource.read("terrain-vegetation.frag"), true)));
        private final DefaultShader.Config biomeVegetationShader = new DefaultShader.Config(
              GpuBiomeVegetation.vertex(config.vertexShader), vegetationShader.fragmentShader);
        private final DefaultShader.Config waterShader = new DefaultShader.Config(GpuWaterWaves.vertex(config.vertexShader),
              litFragment("water-surface.frag"));
        private final DefaultShader.Config waterDepthShader = new DefaultShader.Config(waterShader.vertexShader,
              GpuShaderSource.read("water-depth.frag"));
        private final DefaultShader.Config waterfallShader = new DefaultShader.Config(config.vertexShader,
              litFragment("water-fall.frag"));
        private final DefaultShader.Config sprayShader = new DefaultShader.Config(GpuWaterfall.vertex(config.vertexShader),
              litFragment("water-spray.frag"));
        // The board-edge section's top follows the waves it meets (water-waves.vert).
        private final DefaultShader.Config waterCutShader = new DefaultShader.Config(waterShader.vertexShader,
              litFragment("water-cut.frag"));
        private final DefaultShader.Config liquidShader = new DefaultShader.Config(config.vertexShader,
              GpuLiquidShader.fragment(config.fragmentShader));
        private final DefaultShader.Config waterLiquidShader = new DefaultShader.Config(waterShader.vertexShader,
              GpuLiquidShader.fragment(waterShader.fragmentShader));
        private final DefaultShader.Config waterfallLiquidShader = new DefaultShader.Config(config.vertexShader,
              GpuLiquidShader.fragment(waterfallShader.fragmentShader));
        private final DefaultShader.Config solidMagmaShader = new DefaultShader.Config(config.vertexShader,
              litFragment("terrain-magma-solid.frag"));
        private final DefaultShader.Config flowingMagmaShader = new DefaultShader.Config(config.vertexShader,
              litFragment("terrain-magma-flow.frag"));

        @Override
        protected Shader createShader(Renderable renderable) {
            var water = renderable.material.get(GpuWaterShader.class, GpuWaterShader.TYPE);
            var waterMode = water == null ? null : water.mode;
            var magma = renderable.material.get(GpuMagmaShader.class, GpuMagmaShader.TYPE);
            boolean flowingMagma = magma != null && magma.flowing();
            boolean animated = renderable.material.has(GpuLiquidShader.Frame.TYPE);
            DefaultShader.Config chosen = magma != null && !renderable.material.has(Sculpt.TYPE)
                        ? flowingMagma ? flowingMagmaShader : solidMagmaShader
                  : renderable.material.has(GpuBiomeVegetation.Kind.TYPE) ? biomeVegetationShader
                  : renderable.material.has(GpuGroundCover.Wind.TYPE) ? vegetationShader
                  : renderable.material.has(Foliage.TYPE)
                        ? GpuTreeInstances.instanced(renderable) ? instancedFoliageShader : foliageShader
                  : renderable.material.has(Sculpt.TYPE) ? sculptShader
                  : renderable.material.has(Ground.TYPE)
                        ? renderable.material.has(GpuRoads.TYPE) || renderable.material.has(BridgeDeck.TYPE)
                              ? roadShader : groundShader
                  : waterMode != null ? switch (waterMode) {
                      case SURFACE -> animated ? waterLiquidShader : waterShader;
                      case DEPTH -> waterDepthShader;
                      case FALL -> animated ? waterfallLiquidShader : waterfallShader;
                      case SPRAY -> sprayShader;
                      case CUT -> waterCutShader;
                  } : animated ? liquidShader : GpuTreeInstances.instanced(renderable) ? instancedPropShader : config;
            String prefix = GpuCloudShadow.prefix(renderable, chosen)
                  + (renderable.material.has(GpuIceShader.TYPE) ? "#define iceFlag\n" : "")
                  + (renderable.material.has(GpuBiomeVegetation.Kind.TYPE) ? "#define biomeVegetationFlag\n" : "")
                  + (renderable.material.has(GpuSurfaceBlend.TYPE) ? "#define terrainBlendFlag\n" : "")
                  + (renderable.material.has(GpuMagmaShader.TYPE) ? "#define volcanicFlag\n" : "")
                  + (renderable.material.has(GpuRoads.TYPE) ? "#define roadFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Mask.TYPE) ? "#define roadMaskFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Coats.TYPE) ? "#define roadCoatFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Soil.TYPE) ? "#define roadSoilFlag\n" : "")
                  + (renderable.material.has(BridgeDeck.TYPE) ? "#define bridgeDeckFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Maps.TYPE) ? "#define roadMapsFlag\n" : "")
                  + (renderable.material.has(Impostor.TYPE) && GpuTreeInstances.instanced(renderable)
                        ? "#define impostorFlag\n" : "");
            DefaultShader result = new DefaultShader(renderable, chosen,
                  GpuGlsl.compile("GPU terrain", prefix, chosen.vertexShader, chosen.fragmentShader)) {
                private final int normalMapsUniform = register("u_normalMaps");
                private final int iceNormalsUniform = register("u_iceNormals");
                private final int magmaTimeUniform = register("u_magmaTime");
                private final int magmaOceanUniform = register("u_magmaOcean");
                private final int magmaOceanScaleUniform = register("u_magmaOceanScale");
                private final int roadProfileUniform = register("u_roadProfile");
                private final int wetnessUniform = register("u_wetness");
                private final int viewDirectionUniform = register("u_viewDirection");
                private final int viewPositionUniform = register("u_viewPosition");
                private final int perspectiveUniform = register("u_perspective");
                private final int rainNoiseUniform = register("u_rainNoise");
                private final int rainScaleUniform = register("u_rainScale");
                private final int rainTimeUniform = register("u_rainTime");
                private final int rippleDetailUniform = register("u_rippleDetail");
                private final int rainDetailUniform = register("u_rainDetail");
                private final int skyUniform = register("u_rainSky");
                private final int horizonUniform = register("u_rainHorizon");
                private final int waterEffectsUniform = register("u_waterEffects");
                private final int windUniform = register("u_wind");
                private final int waterWindUniform = register("u_waterWind");
                private final int waterDriftUniform = register("u_waterDrift");
                private final int vegetationPhaseUniform = register("u_vegetationPhase");
                private final int metreUniform = register("u_worldMetre");
                private final int coverPixelsUniform = register("u_coverPixels");
                private final int coverHexWidthUniform = register("u_coverHexWidth");
                private final int gridShadeUniform = register("u_gridShade");
                private final int waterDetailUniform = register("u_waterDetail");
                private final int[] waterOceanUniforms = { register("u_waterOcean0"), register("u_waterOcean1"),
                      register("u_waterOcean2") };
                private final int waterShapeUniform = register("u_waterShape");
                private final int wavePixelsUniform = register("u_wavePixels");
                private final int waveFadeUniform = register("u_waveFade");
                private final int waterExposureUniform = register("u_waterExposure");
                private final int waterNearestUniform = register("u_waterNearest");
                private final int waterExposureMapUniform = register("u_waterExposureMap");
                private final int waterOceanScaleUniform = register("u_waterOceanScale");
                private final int waderCountUniform = register("u_waderCount");
                private final int wadersUniform = register("u_waders");
                private final int waderMotionUniform = register("u_waderMotion");
                private final int levelUniform = register("u_levelHeight");
                private final int clayUniform = register("u_clay");
                private final int sculptMetreUniform = register("u_metre");
                private final int waterLineUniform = register("u_waterLine");
                private final int biomeHexesUniform = register("u_biomeHexes");
                private final int biomeBoardUniform = register("u_biomeBoard");
                private final int iceBoardUniform = register("u_iceBoard");
                private final int biomeSoilUniform = register("u_biomeSoil");
                private final int biomeSoilNormalUniform = register("u_biomeSoilNormal");
                private final int biomeSoilTileUniform = register("u_biomeSoilTile");
                private final Vector3 detailPosition = new Vector3();
                private int rainNoiseUnit = -1, waterDetailUnit = -1, biomeUnit = -1;
                private final int[] waterOceanUnits = { -1, -1, -1 };
                private int biomeSoilUnit = -1, biomeSoilNormalUnit = -1;
                private int magmaOceanUnit = -1;
                private int waterShapeUnit = -1;
                private int waterExposureUnit = -1;
                private int waterNearestUnit = -1;
                private long globalsPass = -1;

                @Override
                public boolean canRender(Renderable other) {
                    var otherWater = other.material.get(GpuWaterShader.class, GpuWaterShader.TYPE);
                    var otherMagma = other.material.get(GpuMagmaShader.class, GpuMagmaShader.TYPE);
                    // The modes share an attribute mask but use different programs, including on shader reload.
                    return waterMode == (otherWater == null ? null : otherWater.mode)
                          && flowingMagma == (otherMagma != null && otherMagma.flowing()) && super.canRender(other);
                }

                @Override
                public void begin(Camera camera, RenderContext context) {
                    super.begin(camera, context);
                    // Blended road layers repeatedly switch programs. Their camera, weather and light inputs
                    // stay constant for this render call; uniforms remain resident when another program binds.
                    if (globalsPass == shadingPass) { return; }
                    globalsPass = shadingPass;
                    set(normalMapsUniform, normalMaps ? 1f : 0f);
                    set(iceNormalsUniform, normalMaps ? 1f : 0f);
                    set(magmaTimeUniform, clock);
                    set(magmaOceanScaleUniform, lavaOcean.texture() == null ? 0f : GpuOcean.lavaScale());
                    set(roadProfileUniform, BoardRoad.JOIN_REACH, BoardRoad.WHEEL_OFFSET,
                          BoardRoad.TRACK_HALF_WIDTH, GpuRoads.WHEEL_TINT);
                    set(wetnessUniform, wetness);
                    set(viewDirectionUniform, camera.direction);
                    set(viewPositionUniform, camera.position);
                    set(perspectiveUniform, camera.projection.val[Matrix4.M33] == 0 ? 1f : 0f);
                    set(waterEffectsUniform, waterEffects ? 1f : 0f);
                    set(windUniform, wind);
                    // Water follows the ocean's eased wind, so its ripples, gusts and foam turn with the waves.
                    Vector3 waterWind = ocean.wind();
                    set(waterWindUniform, waterWind == null ? wind : waterWind);
                    set(waterDriftUniform, ocean.drift());
                    set(vegetationPhaseUniform, vegetationPhase);
                    set(metreUniform, BoardRelief.detailMetres(1));
                    set(coverHexWidthUniform, BoardGeometry.width());
                    set(coverPixelsUniform, BoardGeometry.width() * Math.abs(camera.projection.val[Matrix4.M11])
                          * camera.viewportHeight * .5f * Gdx.graphics.getBackBufferHeight() / Math.max(1f, Gdx.graphics.getHeight()));
                    set(gridShadeUniform, BoardGeometry.tuning().gridShade());
                    set(levelUniform, BoardGeometry.level());
                    set(clayUniform, clay ? 1f : 0f);
                    set(sculptMetreUniform, BoardRelief.metres(1));
                    set(waterLineUniform, BoardGeometry.hexScale());
                    set(waterExposureMapUniform, waterExposure.mapping[0], waterExposure.mapping[1],
                          waterExposure.mapping[2], waterExposure.mapping[3]);
                    set(wavePixelsUniform, BoardGeometry.width() * Math.abs(camera.projection.val[Matrix4.M11])
                          * Gdx.graphics.getBackBufferHeight() * .5f);
                    float waveStart = Math.max(8, TerrainLod.tuning().threshold(2) * 1.1f);
                    set(waveFadeUniform, waveStart, Math.max(50, waveStart + 1));
                    if (has(biomeHexesUniform)) {
                        set(biomeBoardUniform, (float) biomes.width(), (float) biomes.height());
                        set(iceBoardUniform, (float) biomes.iceWidth(), (float) biomes.iceHeight());
                    }
                    if (has(biomeSoilUniform)) { set(biomeSoilTileUniform, assets.sculpt("earth").tile() * .45f); }
                    // BaseShader skips uniforms absent from a program. All lit surfaces share the same rain clock.
                    set(rainScaleUniform, 1f / BoardGeometry.width());
                    set(rainTimeUniform, clock);
                    Color sky = atmosphere == null ? Color.GRAY : atmosphere.sky();
                    Color horizon = atmosphere == null ? Color.LIGHT_GRAY : atmosphere.horizon();
                    set(skyUniform, sky.r, sky.g, sky.b);
                    set(horizonUniform, horizon.r, horizon.g, horizon.b);
                    if (has(waterOceanScaleUniform)) {
                        // Without the wave simulation the water keeps its static ripples alone.
                        if (ocean.texture() == null) { set(waterOceanScaleUniform, 0f, 0f, 0f); }
                        else { set(waterOceanScaleUniform, GpuOcean.scale(0), GpuOcean.scale(1), GpuOcean.scale(2)); }
                        set(waderCountUniform, waders.count);
                        if (waders.count > 0 && has(wadersUniform) && has(waderMotionUniform)) {
                            program.setUniform4fv(loc(wadersUniform), waders.bodies, 0, waders.count * 4);
                            program.setUniform4fv(loc(waderMotionUniform), waders.motion, 0, waders.count * 4);
                        }
                    }
                    // Orthographic projected size is shared by every part in this pass.
                    if (camera.projection.val[Matrix4.M33] != 0) { setDetailUniforms(); }
                }

                @Override
                public void render(Renderable part, Attributes attributes) {
                    // Chunk/material textures can evict these shared textures from the binder's LRU slots.
                    // Touch them for each draw so their sampler uniforms never point at another chunk's field.
                    // BaseShader.set skips uniforms absent from this shader; resident textures are reused.
                    rainNoiseUnit = bindShared(rainNoiseUniform, rainNoise, rainNoiseUnit);
                    waterDetailUnit = bindShared(waterDetailUniform, waterDetail, waterDetailUnit);
                    if (has(biomeHexesUniform)) { biomeUnit = bindShared(biomeHexesUniform, biomes.texture(), biomeUnit); }
                    if (has(biomeSoilUniform)) {
                        var soil = assets.sculpt("earth");
                        biomeSoilUnit = bindShared(biomeSoilUniform, soil.color(), biomeSoilUnit);
                        biomeSoilNormalUnit = bindShared(biomeSoilNormalUniform, soil.normal(), biomeSoilNormalUnit);
                    }
                    for (int cascade = 0; cascade < waterOceanUniforms.length; cascade++) {
                        Texture waves = ocean.waves(cascade);
                        waterOceanUnits[cascade] = bindShared(waterOceanUniforms[cascade],
                              waves == null ? waterDetail : waves, waterOceanUnits[cascade]);
                    }
                    if (has(waterShapeUniform)) {
                        Texture shape = ocean.displacement();
                        waterShapeUnit = bindShared(waterShapeUniform, shape == null ? waterDetail : shape, waterShapeUnit);
                    }
                    if (has(waterExposureUniform)) {
                        waterExposureUnit = bindShared(waterExposureUniform, waterExposure.texture(), waterExposureUnit);
                    }
                    if (has(waterNearestUniform) && waterDepth.texture() != null) {
                        waterNearestUnit = bindShared(waterNearestUniform, waterDepth.texture(), waterNearestUnit);
                    }
                    if (has(magmaOceanUniform)) {
                        Texture waves = lavaOcean.texture();
                        magmaOceanUnit = bindShared(magmaOceanUniform, waves == null ? waterDetail : waves, magmaOceanUnit);
                    }
                    if (camera.projection.val[Matrix4.M33] == 0) {
                        detailPosition.set(part.meshPart.center).mul(part.worldTransform);
                        setDetailUniforms();
                    }
                    super.render(part, attributes);
                }

                private int bindShared(int uniform, Texture texture, int previousUnit) {
                    if (!has(uniform)) { return previousUnit; }
                    int unit = context.textureBinder.bind(texture);
                    // The binder must see every use even when resident; only the redundant sampler upload is skipped.
                    if (unit != previousUnit) { set(uniform, unit); }
                    return unit;
                }

                private void setDetailUniforms() {
                    ShadingDetail detail = detailAt(camera, detailPosition);
                    set(rippleDetailUniform, detail.ripple());
                    set(rainDetailUniform, detail.rain());
                }
            };
            GpuCloudShadow.register(result);
            GpuLiquidShader.register(result);
            GpuMagmaShader.register(result);
            // Upload before drawing: creating ice maps from a uniform setter disturbs the active texture bindings.
            if (renderable.material.has(GpuIceShader.TYPE)) { assets.ice(); }
            GpuIceShader.register(result, assets);
            GpuWaterShader.register(result);
            result.register("u_biomeKind", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    GpuBiomeVegetation.Kind kind = attributes.get(GpuBiomeVegetation.Kind.class, GpuBiomeVegetation.Kind.TYPE);
                    if (kind != null) { target.set(id, kind.value); }
                }
            });
            result.register("u_biomeLod", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    FloatAttribute lod = attributes.get(FloatAttribute.class, GpuBiomeVegetation.Kind.LOD);
                    if (lod != null) { target.set(id, lod.value); }
                }
            });
            result.register("u_groundResponse", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    Ground ground = attributes.get(Ground.class, Ground.TYPE);
                    if (ground != null) { target.set(id, ground.value); }
                }
            });
            result.register("u_roadSurface", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    GpuRoads.Maps maps = attributes.get(GpuRoads.Maps.class, GpuRoads.Maps.TYPE);
                    if (maps != null) { target.set(id, maps.textureDescription); }
                }
            });
            for (boolean roads : new boolean[] { true, false }) {
                result.register(roads ? "u_roadMaps" : "u_sculptMaps", new BaseShader.LocalSetter() {
                    @Override
                    public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                        GpuRoads.Coats coats = attributes.get(GpuRoads.Coats.class, GpuRoads.Coats.TYPE);
                        if (coats != null) { target.set(id, roads ? coats.roads : coats.sculpt); }
                    }
                });
            }
            result.register("u_roadMask", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    var mask = attributes.get(TextureAttribute.class, GpuRoads.Mask.TYPE);
                    if (mask != null) { target.set(id, mask.textureDescription); }
                }
            });
            result.register("u_roadTransition", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    FloatAttribute road = attributes.get(FloatAttribute.class, GpuRoads.TYPE);
                    target.set(id, road == null ? 0 : road.value);
                }
            });
            for (int i = 0; i < SculptMap.TYPES.length; i++) {
                long type = SculptMap.TYPES[i];
                result.register(SculptMap.UNIFORMS[i], new BaseShader.LocalSetter() {
                    @Override
                    public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                        SculptMap map = attributes.get(SculptMap.class, type);
                        if (map != null) { target.set(id, map.textureDescription); }
                    }
                });
            }
            result.register("u_foliage", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    Foliage foliage = attributes.get(Foliage.class, Foliage.TYPE);
                    if (foliage != null) { target.set(id, foliage.value); }
                }
            });
            result.register("u_impostorLift", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    Impostor impostor = attributes.get(Impostor.class, Impostor.TYPE);
                    if (impostor != null) { target.set(id, impostor.value); }
                }
            });
            result.register("u_sculptTiles", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    SculptTiles tiles = attributes.get(SculptTiles.class, SculptTiles.TYPE);
                    if (tiles != null) { target.set(id, tiles.metres[0], tiles.metres[1], tiles.metres[2], tiles.metres[3]); }
                }
            });
            result.register("u_sculptFamily", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    Sculpt sculpt = attributes.get(Sculpt.class, Sculpt.TYPE);
                    if (sculpt != null) { target.set(id, sculpt.value); }
                }
            });
            result.register("u_coverFamilies", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    GpuSurfaceBlend blend = attributes.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
                    if (blend != null) {
                        target.set(id, blend.families[0], blend.families[1], blend.families[2], blend.families[3]);
                    }
                }
            });
            result.register("u_coverResponses", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    GpuSurfaceBlend blend = attributes.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
                    if (blend != null) { target.set(id, blend.responses[0], blend.responses[1], blend.responses[2], blend.responses[3]); }
                }
            });
            result.register("u_terrainLayers", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    GpuSurfaceBlend blend = attributes.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
                    if (blend != null) { target.set(id, blend.texture); }
                }
            });
            for (int family = 0; family < 4; family++) {
                int index = family;
                for (boolean layers : new boolean[] { false, true }) {
                    result.register((layers ? "u_coverLayers" : "u_coverTiles") + family, new BaseShader.LocalSetter() {
                        @Override
                        public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                            GpuSurfaceBlend blend = attributes.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
                            if (blend != null) {
                                float[] values = (layers ? blend.layers : blend.tiles)[index];
                                target.set(id, values[0], values[1], values[2], values[3]);
                            }
                        }
                    });
                }
            }
            return result;
        }
    }), terrainBatch);
    private final ModelBatch depthBatch = new ModelBatch(GpuShaderManager.provider("Shadows", () ->
          GpuTreeInstances.depthProvider(new DepthShader.Config(null, GpuShaderSource.read("shadow-depth.frag")))),
          new GpuOpaqueSorter());

    void shadersChanged() { refreshShadows(); }

    /**
     * Redraw the shadow map at the next pass, as terrain, light and shader changes do. Tree, scatter and building
     * level changes do not: the map keeps the previous level's silhouette, which differs by about a texel, until
     * the camera movement that changed the level refits the shadow.
     */
    void refreshShadows() { staticShadowValid = false; shadowDirty = true; }

    record ShadingDetail(float ripple, float rain) { }

    static ShadingDetail detail(Camera camera, Renderable part) {
        return detailAt(camera, part == null ? Vector3.Zero : new Vector3(part.meshPart.center).mul(part.worldTransform));
    }

    private static ShadingDetail detailAt(Camera camera, Vector3 position) {
        float pixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, position);
        return new ShadingDetail(MathUtils.clamp((pixels * Math.abs(camera.direction.z) - 28) / 60, 0, 1),
              MathUtils.clamp((pixels - 12) / 28, 0, 1));
    }
    /** Every tree of the board, drawn instanced; each pass gathers the trees of the chunks it draws. */
    private final GpuTreeInstances trees;
    private final Environment environment = new Environment();
    private final List<Chunk> chunks = new ArrayList<>();
    // At most four in-flight chunks and eight replaced chunks. A small worker pool leaves CPU capacity for
    // rendering/input instead of borrowing every common-pool worker. All GL ownership stays on the render thread.
    private final ExecutorService detailWorker = new ForkJoinPool(Math.max(1, Math.min(2,
          Runtime.getRuntime().availableProcessors() / 2)), pool -> {
        var thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        thread.setName("terrain-detail-" + thread.getPoolIndex());
        return thread;
    }, null, false);
    private final Map<Integer, Chunk> detailCache = new LinkedHashMap<>();
    private record DetailJob(long generation, int index, Chunk source, TerrainLod lod, BoardScene scene,
          TerrainSettings settings, float floor, boolean rebuilding, CompletableFuture<Prepared> surfaces,
          ChunkBuild build, CompletableFuture<Void> meshes, TerrainLoadProgress progress) {
        boolean ready() { return build == null ? surfaces.isDone() : meshes == null || meshes.isDone(); }
    }
    private record Prepared(Map<Coords, BoardSurface> surfaces, GpuWaterShader.Field.Prepared water,
          GpuWaterShader.Field.Prepared lava,
          Map<Coords, BoardTacticalGeometry.Surface> topography, Map<Coords, BoardPlants> plants,
          Map<Coords, SculptPlan> sculpts,
          Map<Coords, BoardFlow.Current> currents, Map<Coords, List<RoadPatch>> roads,
          Map<Coords, BoardBridge.Deck> bridges, Map<Coords, BoardBridge.Shape> bridgeShapes,
          Map<Coords, Map<BoardSurface.Side, List<BoardSurface.Face>>> walls, Set<Coords> reused) { }
    private record RoadPatch(GpuRoads.Patch patch, GpuRoads.MaskData mask,
          List<BoardTacticalGeometry.Triangle> triangles, boolean flat) { }
    private record Request(long generation, BoardScene scene, TerrainSettings settings) { }
    private record UpdatePlan(float floor, BoardConcrete coast, Map<Coords, BoardFlow.Current> currents,
          boolean all, Set<Coords> changed, Set<Coords> changedTiles, Map<GroundSlot, BoardScene.Pixels> colors,
          Map<GroundSlot, BoardScene.Pixels> normals, Map<Coords, BoardScene.Pixels> decals,
          Map<Coords, BoardScene.Pixels> foliage) { }
    private static final class Rebuild {
        final Request request;
        final CompletableFuture<UpdatePlan> preparation;
        final TerrainLoadProgress progress;
        final Set<Integer> remaining = new HashSet<>();
        final Set<Integer> started = new HashSet<>();
        final List<DetailJob> pending = new ArrayList<>();
        final Map<Integer, Chunk> replacements = new HashMap<>();
        UpdatePlan plan;

        Rebuild(Request request, CompletableFuture<UpdatePlan> preparation, TerrainLoadProgress progress) {
            this.request = request;
            this.preparation = preparation;
            this.progress = progress;
        }
    }
    private DetailJob detailJob;
    private volatile long meshGeneration;
    private Request requested;
    private Rebuild rebuild;
    private TerrainSettings installedSettings;
    private final ArrayDeque<Chunk> retiredChunks = new ArrayDeque<>();
    private final Set<Coords> atlasChanges = new HashSet<>();
    private final Set<Coords> atlasTileChanges = new HashSet<>();
    private final Set<Coords> markingChanges = new HashSet<>();
    private Map<GroundSlot, BoardScene.Pixels> terrainPixels = Map.of(), normalPixels = Map.of();
    private final GpuGroundCover groundCover = new GpuGroundCover();
    private final GpuBiomeVegetation biomeVegetation = new GpuBiomeVegetation();
    private final GpuBiomeSurface biomes = new GpuBiomeSurface();
    private final GpuPropBatch propBatch = new GpuPropBatch();
    private BoardScene coverScene;
    private final Vector3 wind = new Vector3();
    private final List<Model> shadowModels = new ArrayList<>();
    private final List<Matrix4> shadowTransforms = new ArrayList<>();
    private final com.badlogic.gdx.utils.IntArray shadowPoses = new com.badlogic.gdx.utils.IntArray();
    // Pure CPU geometry may follow model lifetime; native meshes always have explicit render-thread owners.
    private final Map<Model, List<Vector3>> featureTriangles = new java.util.WeakHashMap<>();
    private final BoundingBox shadowBounds = new BoundingBox();
    private final Matrix4 shadowView = new Matrix4();
    private boolean shadowProjectionValid;
    private final OrthographicCamera shadowFit = new OrthographicCamera();
    private final UnitBounds.Frame frameBounds;
    private boolean shadowViewPresent;
    private List<BoardScene.Tile> tiles;
    private Map<Coords, BoardFlow.Current> currents = Map.of();
    private BoardConcrete coast;
    private BoardScene.Light light;
    private BoardAtmosphere.Lighting atmosphere;
    private DirectionalShadowLight shadow;
    /** Allocated only for gameplay casters. Reuses the unchanged terrain depth when units move. */
    private FrameBuffer staticShadow;
    private boolean staticShadowValid;
    // The fit that covers the whole board (views crossing the horizon or below the highest terrain) is the same
    // every time a tilt returns to it and the costliest to draw: its terrain shadow is kept and copied back.
    private FrameBuffer boardShadow;
    private boolean boardShadowValid;
    private final OrthographicCamera boardShadowFit = new OrthographicCamera();
    private boolean shadowBoardWide;
    private boolean shadowDirty;
    private float clock;
    private long shadingPass;
    private float vegetationPhase;
    private float floor;
    private int chunkRows;
    private float buildingOpacity = DEFAULT_BUILDING_OPACITY;
    private boolean normalMaps = true;
    private boolean grass = true;
    /** Neutral material view: sculpted terrain drops its textures so only geometry, light and occlusion remain. */
    private boolean clay;
    private boolean waterEffects = true;
    private float wetness;
    private float detailPixelsPerUnit = Float.NaN;
    private boolean hasCutaways;
    private boolean flatTrees;
    private boolean terrainMaterialsReady;

    /** Tiny shared, mipmapped field: mask and sky variation; allocated once, never updated per frame. */
    private static Texture rainNoise() {
        Pixmap pixels = new Pixmap(64, 64, Pixmap.Format.RGBA8888);
        Random random = new Random(0x7261696eL);
        Random waterRandom = new Random(0x7761746572L);
        try {
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    int value = random.nextInt(256);
                    // Keep the existing red-channel puddle field; water borrows independent green/blue noise.
                    pixels.drawPixel(x, y, (value << 24) | (waterRandom.nextInt(256) << 16) | (waterRandom.nextInt(256) << 8) | 255);
                }
            }
            Texture texture = new Texture(pixels, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            return texture;
        } finally {
            pixels.dispose();
        }
    }

    /** A custom lit surface's fragment shader: its source with cloud shadows and the shared functions. */
    static String litFragment(String file) {
        String source = GpuShaderSource.read(file);
        if (source.contains("// terrain-projection-functions")) {
            source = source.replace("// terrain-projection-functions", GpuShaderSource.read("terrain-projection.glsl"));
        }
        if (source.contains("// water-uniforms")) {
            source = source.replace("// water-uniforms", GpuShaderSource.read("water-uniforms.glsl"));
        }
        // Compose shared lighting first so cloud modulation reaches the actual lighting function, not just main.
        return GpuIceShader.fragment(GpuCloudShadow.fragment(rainFragment(source), true));
    }

    /** Every custom surface shares the light model, rain field, lighting, geometry shadows and water optics. */
    private static String rainFragment(String source) {
        String functions = GpuShaderSource.read("light-model.glsl");
        functions += GpuShaderSource.read("rain-surface.glsl");
        functions += GpuShaderSource.read("surface-lighting.glsl");
        functions += GpuShaderSource.read("terrain-meadow.glsl");
        functions += GpuShaderSource.read("terrain-patterns.glsl");
        functions += GpuShaderSource.read("water-optics.glsl");
        functions += GpuShaderSource.read("terrain-hexes.glsl");
        if (source.contains("// sculpt-material-functions") || source.contains("// biome-water-functions")) {
            functions += GpuShaderSource.read("terrain-biome-mask.glsl");
        }
        if (source.contains("// sculpt-material-functions")) {
            functions += GpuShaderSource.read("terrain-concrete.glsl");
            functions += "\n#ifdef volcanicFlag\n" + GpuMagmaShader.functions(false) + "\n#endif\n";
            functions += GpuShaderSource.read("terrain-materials.glsl");
            functions += GpuShaderSource.read("terrain-biome.glsl");
        }
        if (source.contains("// magma-solid-functions") || source.contains("// magma-flow-functions")) {
            functions += GpuMagmaShader.functions(source.contains("// magma-flow-functions"));
            functions += GpuShaderSource.read("magma-lighting.glsl");
        }
        if (source.contains("// ground-surface-functions")) {
            functions += GpuShaderSource.read("ground-surface.glsl");
        }
        if (source.contains("// water-lighting-functions")) {
            functions += GpuShaderSource.read("water-lighting.glsl");
        }
        if (source.contains("// water-pool-functions")) {
            functions += GpuShaderSource.read("water-interactions.glsl");
            functions += GpuShaderSource.read("water-wave.glsl") + GpuShaderSource.read("water-pool.glsl");
        }
        return source.replace("void main() {", "// terrain-lighting-functions\n" + functions + "\nvoid main() {");
    }

    GpuTerrain() {
        this(null);
    }

    /** Lets native integration checks exercise the GIF fallback without changing the production constant. */
    GpuTerrain(boolean liquidShaderAnimation) {
        this(liquidShaderAnimation, false);
    }

    /** Native A/B checks share all effects and differ only in the source of the water color. */
    GpuTerrain(boolean liquidShaderAnimation, boolean proceduralWater) {
        this(null, null, liquidShaderAnimation, proceduralWater);
    }

    /** The battle view owns this library; terrain borrows the equipment buffers without disposing them. */
    GpuTerrain(GpuUnitModels unitModels) {
        this(unitModels, null);
    }

    /** Borrow the battle view's bounds only between its post-animation begin and the end of that render frame. */
    GpuTerrain(GpuUnitModels unitModels, UnitBounds.Frame frameBounds) {
        this(unitModels, frameBounds, GpuLiquidShader.USE_SHADER_ANIMATION, GpuWaterShader.USE_PROCEDURAL_WATER);
    }

    private GpuTerrain(GpuUnitModels unitModels, UnitBounds.Frame frameBounds, boolean liquidShaderAnimation, boolean proceduralWater) {
        this.unitModels = unitModels;
        this.frameBounds = frameBounds;
        this.liquidShaderAnimation = liquidShaderAnimation;
        this.proceduralWater = proceduralWater;
        assets = new GpuAssets();
        trees = new GpuTreeInstances((name, level) -> foliage(assets.lodModel(name, level)));
    }

    private static final class Prop {
        private final Coords coords;
        private final BoundingBox bounds;
        private final String treeAsset;
        private final Model pickingModel;
        private final float treeDiameter;
        private ModelInstance instance;
        private final boolean hardSurface;
        private final GpuBuilding.Assembly building;
        private int buildingLod;

        Prop(Coords coords, ModelInstance instance, BoundingBox bounds, String treeAsset, boolean hardSurface) {
            this(coords, instance, bounds, treeAsset, hardSurface, null);
        }

        Prop(Coords coords, ModelInstance instance, BoundingBox bounds, String treeAsset, boolean hardSurface,
              GpuBuilding.Assembly building) {
            this.coords = coords;
            this.building = building;
            this.instance = instance;
            this.bounds = bounds;
            this.treeAsset = treeAsset;
            this.hardSurface = hardSurface;
            pickingModel = building == null ? instance.model : null;
            treeDiameter = treeAsset == null ? 0 : bounds.getDimensions(new Vector3()).len();
        }

        boolean buildingDetail(float pixelsPerUnit) {
            if (building == null) { return false; }
            float pixels = Math.max(bounds.getWidth(), bounds.getHeight()) * pixelsPerUnit;
            buildingLod = Math.min(building.lodCount() - 1, TreeLod.level(TreeLod.PROPS, pixels, buildingLod));
            Model next = building.model(buildingLod);
            if (instance.model == next) { return false; }
            ModelInstance replacement = building.instance(buildingLod);
            replacement.transform.set(instance.transform);
            // Cutaway state belongs to the placement and survives a visual detail change.
            for (Material material : replacement.materials) {
                Material previous = instance.getMaterial(material.id);
                if (previous == null) { continue; }
                for (long type : new long[] { BlendingAttribute.Type, DepthTestAttribute.Type, IntAttribute.CullFace }) {
                    Attribute attribute = previous.get(type);
                    if (attribute != null) { material.set(attribute.copy()); }
                }
            }
            instance = replacement;
            return true;
        }

        Coords coords() { return coords; }
        ModelInstance instance() { return instance; }
        BoundingBox bounds() { return bounds; }
        boolean tree() { return treeAsset != null; }

    }
    private record LiquidSurface(Material material, BoardLiquid.Textures source, boolean falling, BoardFlow.Current current) { }

    /** Marks exposed ground and carries its water-film response; negative excludes snow, ice and water. */
    private static final class Ground extends FloatAttribute {
        static final long TYPE = register("boardGround");

        Ground(float response) {
            super(TYPE, response);
        }

        @Override
        public Ground copy() {
            return new Ground(value);
        }
    }

    /** Bridge asphalt uses the road's world UVs, even when its model instance rotates or changes length. */
    private static final class BridgeDeck extends FloatAttribute {
        static final long TYPE = register("boardBridgeDeck");
        BridgeDeck() { super(TYPE, 1); }
    }

    /** A tree part lit like the terrain around it: 0 solid (bark, cactus stems), 1 canopy that scatters light, 2 snow. */
    private static final class Foliage extends FloatAttribute {
        static final long TYPE = register("boardFoliage");

        Foliage(float part) { super(TYPE, part); }

        @Override
        public Foliage copy() { return new Foliage(value); }
    }

    /**
     * Impostor cards and their plant's crown radius in model units. A card stands in the middle of its crown, so its
     * shadow is looked up a crown radius toward the light: its own cards and crown cannot shade it, other casters can.
     */
    private static final class Impostor extends FloatAttribute {
        static final long TYPE = register("boardImpostor");

        Impostor(float radius) { super(TYPE, radius); }

        @Override
        public Impostor copy() { return new Impostor(value); }
    }

    /** Marks a tree model's materials for the foliage shader, once; instances copy the marks. */
    private static Model foliage(Model model) {
        for (Material material : model.materials) {
            if (!material.has(Foliage.TYPE)) {
                boolean solid = material.id.startsWith("bark") || material.id.equals("cactus") || material.id.equals("fruit");
                material.set(new Foliage(material.id.equals("snow") ? 2 : solid ? 0 : 1));
            }
            // The impostor cards are cutouts: their texels outside the plant discard in every pass, shadows included.
            // They draw as opaque (unblended, sorted with the solid trees), so they too hide the ground beneath first.
            if (material.id.equals("impostor")) {
                BoundingBox bounds = model.calculateBoundingBox(new BoundingBox());
                material.set(new BlendingAttribute(false, GL20.GL_ONE, GL20.GL_ZERO, 1),
                      new FloatAttribute(FloatAttribute.AlphaTest, .5f),
                      new Impostor(Math.max(bounds.getWidth(), bounds.getHeight()) / 2));
            }
        }
        return model;
    }

    /** Canonically sculpted tops and cliffs of one surface family; per-vertex data carries the rest. */
    private static final class Sculpt extends FloatAttribute {
        static final long TYPE = register("boardSculpt");

        Sculpt(float family) { super(TYPE, family); }

        @Override
        public Sculpt copy() { return new Sculpt(value); }
    }

    /**
     * The four materials of each surface family, by ordinal: ground on open tops, debris on rims, cliff feet, talus
     * and slopes, the wall on cliffs and the rock kit, and the mantle that covers low steps of grassland (earth),
     * snowfields (snow) and pavement (cast concrete) and caps their high cliffs. Names refer to
     * {@code textures/sculpt}.
     */
    private static final String[][] SCULPT_MATERIALS = {
          { "grass", "scree", "granite-contact", "soil-contact" },
          { "dirt", "gravel", "granite-contact", "soil-contact" },
          { "sand", "pavement", "sandstone", "sandstone" },
          { "rock", "scree", "granite-contact", "granite-contact" },
          { "concrete", "scree", "granite-contact", "cast" },
          { "snow", "scree", "granite-contact", "snow" },
    };
    private static final List<String> SCULPT_LAYERS = Arrays.stream(SCULPT_MATERIALS).flatMap(Arrays::stream).distinct().toList();

    /** Shared family maps; boundary families use the asset cache's texture array. */
    private static final class SculptMap extends TextureAttribute {
        static final String[] UNIFORMS = { "u_groundColor", "u_groundNormal", "u_debrisColor", "u_debrisNormal",
              "u_wallColor", "u_wallNormal", "u_mantleColor", "u_mantleNormal" };
        static final long[] TYPES = new long[UNIFORMS.length];

        static {
            for (int i = 0; i < TYPES.length; i++) {
                TYPES[i] = register("boardSculptMap" + i);
                Mask |= TYPES[i];
            }
        }

        SculptMap(long type, Texture texture) { super(type, texture); }

        @Override
        public SculptMap copy() { return new SculptMap(type, textureDescription.texture); }
    }

    /** Metres per repeat of the ground, debris, wall and mantle maps. */
    private static final class SculptTiles extends Attribute {
        static final long TYPE = register("boardSculptTiles");
        final float[] metres;

        SculptTiles(float... metres) {
            super(TYPE);
            this.metres = metres.clone();
        }

        @Override
        public SculptTiles copy() { return new SculptTiles(metres); }

        @Override
        public int compareTo(Attribute other) {
            if (type != other.type) { return Long.compare(type, other.type); }
            return Arrays.compare(metres, ((SculptTiles) other).metres);
        }

        @Override
        public int hashCode() { return 31 * super.hashCode() + Arrays.hashCode(metres); }
    }

    /** Index ranges borrow the chunk's meshes; no duplicate geometry or construction-time scene is retained. */
    private record TileRange(int layer, Mesh mesh, Material material, int offset, int count) { }

    private static final class TileMesh {
        // Vegetation already retains its support; find that same geometry even after the bounded CPU cache evicts it.
        WeakReference<BoardTacticalGeometry.Surface> support;
        // Ground cover planted on this tile's support while its chunk was prepared; null when it grows none.
        BoardPlants plants;
        BoardBridge.Shape bridgeShape;
        final List<TileRange> ranges = new ArrayList<>();
        final List<Prop> props = new ArrayList<>();
        final List<ModelInstance> struts = new ArrayList<>();
        final BoundingBox bounds = new BoundingBox().inf();
        float scatterDiameter;
    }

    private static final class Chunk implements Disposable {
        TerrainLod lod;
        final Map<Coords, TileMesh> tileMeshes = new HashMap<>();
        final List<ModelInstance> opaque = new ArrayList<>();
        Array<Renderable> terrainRenderables;
        GpuTerrainDepth depthTerrain;
        final List<ModelInstance> scatter = new ArrayList<>();
        float scatterDiameter;
        boolean scatterVisible = true;
        final List<ModelInstance> overlays = new ArrayList<>();
        final List<ModelInstance> water = new ArrayList<>();
        Array<Renderable> waterRenderables;
        final List<LiquidSurface> liquidMaterials = new ArrayList<>();
        final List<ModelInstance> tactical = new ArrayList<>();
        final List<ModelInstance> flatTrees = new ArrayList<>();
        final List<Prop> props = new ArrayList<>();
        final List<Prop> cutaways = new ArrayList<>();
        final GpuTreeInstances.Stand stand = new GpuTreeInstances.Stand();
        float treeDiameter;
        int treeLod;
        final List<ModelInstance> struts = new ArrayList<>();
        final Array<Renderable> propRenderables = new Array<>();
        Array<Renderable> scatterRenderables = new Array<>();
        final Array<Renderable> shadowPropRenderables = new Array<>();
        final Array<Renderable> sharedProps = new Array<>();
        final Array<Renderable> sharedShadows = new Array<>();
        final Set<Model> sharedInteriors = new HashSet<>();
        Set<Prop> faded = Set.of();
        final RenderableProvider solidProps = (out, pool) -> supply(
              faded.isEmpty() ? shadowPropRenderables : propRenderables, out);
        final RenderableProvider shadowProps = (out, pool) -> supply(shadowPropRenderables, out);
        final BoundingBox bounds = new BoundingBox().inf();
        /** Shore distance, depth and current of this chunk's open water; null without any. */
        GpuWaterShader.Field waterField;
        /** Lava reuses the same current/bank sampler with its own connectivity and chunk-owned texture. */
        GpuWaterShader.Field lavaField;
        final GpuTextures<BoardScene.Pixels> roadMasks = new GpuTextures<>();

        private static void supply(Array<Renderable> source, Array<Renderable> out) {
            for (Renderable renderable : source) {
                renderable.shader = null;
                renderable.environment = null;
            }
            out.addAll(source);
        }

        void cacheProps() {
            if (cutaways.isEmpty() && struts.isEmpty()) { return; }
            if (shadowPropRenderables.isEmpty()) {
                cacheProps(shadowPropRenderables, false);
                // A shadow always uses the original opaque materials, independently of live instance fading.
                opaqueMaterials(shadowPropRenderables);
            }
            // These snapshots contain transforms and mesh ranges only; module geometry is never merged per building.
            List<ModelInstance> shared = new ArrayList<>();
            for (ModelInstance strut : struts) { if (sharedInteriors.contains(strut.model)) { shared.add(strut); } }
            for (Prop prop : cutaways) {
                if (prop.building != null || sharedInteriors.contains(prop.instance.model)) { shared.add(prop.instance); }
            }
            sharedShadows.clear();
            sharedShadows.addAll(GpuTerrainDepth.snapshot(shared));
            opaqueMaterials(sharedShadows);
            for (Prop prop : faded) { shared.remove(prop.instance); }
            sharedProps.clear();
            sharedProps.addAll(GpuTerrainDepth.snapshot(shared));
            // Stable, opaque material keys for the instancing cache; live cutaway materials can mutate independently.
            opaqueMaterials(sharedProps);
            GpuPropBatch.disposeMeshes(propRenderables);
            // Share the complete cache for normal rendering; only occupied chunks need a second mesh cache.
            if (!faded.isEmpty()) {
                cacheProps(propRenderables, true);
            }
        }

        private static void opaqueMaterials(Array<Renderable> renderables) {
            for (Renderable renderable : renderables) {
                renderable.material = new Material(renderable.material);
                renderable.material.remove(BlendingAttribute.Type);
                renderable.material.remove(DepthTestAttribute.Type);
                renderable.material.remove(IntAttribute.CullFace);
            }
        }

        private void cacheProps(Array<Renderable> destination, boolean omitFaded) {
            GpuPropBatch.cache(destination, builder -> {
                for (ModelInstance strut : struts) { if (!sharedInteriors.contains(strut.model)) { builder.add(strut); } }
                for (Prop prop : cutaways) {
                    if (prop.building == null && !sharedInteriors.contains(prop.instance.model)
                          && (!omitFaded || !faded.contains(prop))) {
                        builder.add(prop.instance());
                    }
                }
            });
        }

        @Override
        public void dispose() {
            GpuPropBatch.disposeMeshes(propRenderables);
            GpuPropBatch.disposeMeshes(shadowPropRenderables);
            for (List<ModelInstance> layer : List.of(opaque, scatter, overlays, water, tactical, flatTrees)) {
                layer.forEach(instance -> instance.model.dispose());
            }
            if (waterField != null) { waterField.dispose(); }
            if (lavaField != null) { lavaField.dispose(); }
            roadMasks.dispose();
        }
    }

    /** Collect by material before opening a mesh part: ModelBuilder has only one active part at a time. */
    private static final class Layer {
        private record Shape(Coords owner, Material material, Consumer<MeshBatch> emit) { }
        final Map<Material, List<Shape>> geometry = new LinkedHashMap<>();
        Coords owner;

        void add(Material material, Consumer<MeshPartBuilder> shape) {
            // Bounded legacy shapes retain the headroom previously reserved at 48,000 vertices.
            geometry.computeIfAbsent(material, key -> new ArrayList<>())
                  .add(new Shape(owner, material, batch -> shape.accept(batch.reserve(65536 - 48001))));
        }

        /** Request a mesh before each triangle; a new mesh also requires fresh shared vertex indices. */
        void addTriangles(Material material, Consumer<Supplier<MeshPartBuilder>> shape) {
            geometry.computeIfAbsent(GpuRoads.batchMaterial(material), key -> new ArrayList<>())
                  .add(new Shape(owner, material, batch -> shape.accept(() -> batch.reserve(3))));
        }

        /** Snapshot CPU mesh buffers while the GL thread still owns the source chunk. */
        void reuse(TileRange range, Material material) {
            FloatArray points = new FloatArray();
            ShortArray indices = new ShortArray();
            GpuPropBatch.copyVertices(range.mesh(), range.offset(), range.count(), points, indices);
            float[] vertices = points.toArray();
            var mask = material.get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE);
            if (mask != null) { GpuRoads.relocate(vertices, mask); }
            short[] elements = indices.toArray();
            int count = vertices.length / (range.mesh().getVertexSize() / Float.BYTES);
            geometry.computeIfAbsent(GpuRoads.batchMaterial(material), key -> new ArrayList<>()).add(new Shape(owner, material, batch -> {
                MeshPartBuilder mesh = batch.reserve(count);
                int base = mesh.lastIndex() + 1;
                // MeshBuilder.addMesh uses a static remapping table, so it cannot run on multiple workers.
                // This range is already indexed: append its vertices and adjust indices directly.
                mesh.vertex(vertices);
                for (int i = 0; i < elements.length; i += 3) {
                    mesh.index((short) (base + Short.toUnsignedInt(elements[i])),
                          (short) (base + Short.toUnsignedInt(elements[i + 1])),
                          (short) (base + Short.toUnsignedInt(elements[i + 2])));
                }
            }));
        }

        void finish(List<ModelInstance> destination) {
            prepare().upload(destination);
        }

        MeshBatch prepare() { return prepare(() -> { }, () -> { }); }

        MeshBatch prepare(Runnable check, Runnable completed) {
            MeshBatch batch = new MeshBatch();
            int shapes = 0;
            for (var entry : geometry.entrySet()) {
                check.run();
                batch.part(entry.getKey());
                var ordered = entry.getValue();
                if (entry.getKey().has(GpuRoads.Coats.TYPE)) {
                    // One draw blends its triangles in index order: base coats first, wear and paint over them.
                    ordered = ordered.stream().sorted(Comparator.comparingDouble(shape -> GpuRoads.layer(shape.material()))).toList();
                }
                for (var shape : ordered) {
                    if ((shapes++ & 63) == 0) { check.run(); }
                    batch.owner(shape.owner(), shape.material());
                    shape.emit().accept(batch);
                    completed.run();
                }
            }
            batch.endRange();
            return batch;
        }
    }

    /** Splits even a single sculpted hex across meshes without exceeding unsigned 16-bit vertex indices. */
    private static final class MeshBatch {
        private record Part(MeshPart mesh, Material material) { }
        private record Range(Coords owner, Material material, int offset, int count) { }
        private static final class Buffer {
            final MeshBuilder builder = new MeshBuilder();
            final List<Part> parts = new ArrayList<>();
            final List<Range> ranges = new ArrayList<>();
            Buffer(VertexAttributes attributes) { builder.begin(attributes); }
        }
        private static final VertexAttributes STANDARD = MeshBuilder.createAttributes(ATTRIBUTES);
        private final List<Buffer> buffers = new ArrayList<>();
        private final Map<VertexAttributes, Buffer> active = new HashMap<>();
        private Buffer current;
        private Material material;
        private Material rangeMaterial;
        private Coords owner;
        private int rangeStart;
        private int index;

        void part(Material next) {
            endRange();
            material = next;
            VertexAttributes attributes = material.has(GpuSurfaceBlend.TYPE) ? GpuSurfaceBlend.VERTICES
                  : material.has(GpuRoads.Mask.TYPE) ? GpuRoads.VERTICES : STANDARD;
            current = active.computeIfAbsent(attributes, key -> {
                Buffer buffer = new Buffer(key);
                buffers.add(buffer);
                return buffer;
            });
            current.parts.add(new Part(current.builder.part("surface-" + index++, GL20.GL_TRIANGLES), material));
            rangeStart = current.builder.getNumIndices();
        }

        void owner(Coords next, Material source) {
            if (!Objects.equals(owner, next) || rangeMaterial != source) { endRange(); owner = next; rangeMaterial = source; }
        }

        void endRange() {
            if (current == null) { return; }
            int end = current.builder.getNumIndices();
            if (owner != null && end > rangeStart) {
                current.ranges.add(new Range(owner, rangeMaterial, rangeStart, end - rangeStart));
            }
            rangeStart = end;
        }

        MeshPartBuilder reserve(int vertices) {
            if (current.builder.lastIndex() + 1 + vertices > 65536) {
                active.remove(current.builder.getAttributes());
                part(material.has(GpuRoads.Coats.TYPE) ? GpuRoads.lifted(material, rangeMaterial) : material);
            }
            return current.builder;
        }

        /** Geometry emission above is CPU-only. Only this final ownership transfer allocates GL meshes. */
        void upload(List<ModelInstance> destination) {
            for (Buffer buffer : buffers) { upload(buffer, destination); }
        }

        void upload(Buffer buffer, List<ModelInstance> destination) {
            upload(buffer, destination, null, 0);
        }

        void upload(Buffer buffer, List<ModelInstance> destination, Map<Coords, TileMesh> tiles, int layer) {
            ModelBuilder model = new ModelBuilder();
            model.begin();
            var mesh = buffer.builder.end();
            model.manage(mesh);
            for (Part part : buffer.parts) { model.part(part.mesh(), part.material()); }
            destination.add(new ModelInstance(model.end()));
            if (tiles != null) {
                for (Range range : buffer.ranges) {
                    tiles.get(range.owner()).ranges.add(new TileRange(layer, mesh, range.material(), range.offset(), range.count()));
                }
            }
            // MeshBuilder.end() only fills CPU buffers. Bind now so uploads share the refinement budget instead
            // of all landing on the first shadow draw after publication. No shader attributes are needed yet.
            int[] locations = new int[mesh.getVertexAttributes().size()];
            Arrays.fill(locations, -1);
            mesh.bind(null, locations, null);
            mesh.unbind(null, locations, null);
        }
    }

    /** Blocking convenience for offline fixtures; uses the same preparation and installation path as the view. */
    void update(BoardScene scene) {
        update(scene, null);
        while (busy()) {
            refine(null);
            if (busy()) { java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); }
        }
    }

    /** Request only the latest rendering snapshot. Game state and commands remain owned by the source. */
    void update(BoardScene scene, Camera camera) {
        TerrainSettings settings = TerrainSettings.capture();
        BoardScene target = requested == null ? coverScene : requested.scene();
        TerrainSettings targetSettings = requested == null ? installedSettings : requested.settings();
        if (target == null || !sameBoard(target, scene) || !sameTerrain(target.tiles(), scene.tiles())
              || !sameSettings(targetSettings, settings)
              || limbModel != null && targetSettings.geometry().unitScale() != settings.geometry().unitScale()) {
            // Coalesce continuous painting into one latest snapshot without starving the build already underway.
            // Board/geometry-setting changes still cancel work whose coordinate system is no longer applicable.
            if (requested == null || !sameBoard(target, scene) || !sameSettings(targetSettings, settings)) { meshGeneration++; }
            requested = new Request(meshGeneration, scene, settings);
            retiredChunks.addAll(detailCache.values());
            detailCache.clear();
        } else if (requested == null) {
            try (TerrainSettings.Scope ignored = TerrainSettings.use(settings)) {
                if (tiles != scene.tiles()) {
                    for (Coords chunk : updateMarkingsAtlas(scene)) {
                        buildMarkings(scene, chunks.get(chunk.getX() * chunkRows + chunk.getY()),
                              chunk.getX() * CHUNK_SIZE, chunk.getY() * CHUNK_SIZE);
                    }
                    tactical.publish();
                }
                tiles = scene.tiles();
                coverScene = scene;
                installedSettings = settings;
                if (!Objects.equals(light, scene.light())) { updateLight(scene.light()); }
            }
        }
    }

    boolean busy() { return requested != null || rebuild != null || detailJob != null; }

    boolean ready(BoardScene scene) { return coverScene != null && sameBoard(coverScene, scene); }

    int buildProgress() {
        if (requested == null) { return -1; }
        if (rebuild == null || rebuild.request != requested || rebuild.plan == null) { return 0; }
        return 100 * rebuild.replacements.size() / Math.max(1, rebuild.remaining.size() + rebuild.replacements.size());
    }

    /** Read on the GL thread; each worker contributes one coherent, non-authoritative progress snapshot. */
    List<TerrainLoadProgress.Status> buildDetails() {
        if (requested == null || rebuild == null || rebuild.request.generation() != requested.generation()) {
            return List.of();
        }
        List<DetailJob> jobs = new ArrayList<>(rebuild.pending);
        if (detailJob != null && detailJob.rebuilding()) { jobs.add(detailJob); }
        if (jobs.isEmpty()) { return List.of(new TerrainLoadProgress.Status(0, 0, rebuild.progress.snapshot())); }
        BoardScene scene = rebuild.request.scene();
        int columns = (scene.width() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        int rows = (scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        return jobs.stream().filter(job -> job.generation() == requested.generation())
              .map(job -> new TerrainLoadProgress.Status(TerrainLoadProgress.displayIndex(job.index(), columns, rows) + 1,
                    columns * rows, job.progress().snapshot()))
              .sorted(Comparator.comparingInt(TerrainLoadProgress.Status::section)).toList();
    }

    /** Presentation only: derive cell colors from the current request's existing chunk ownership. */
    TerrainLoadProgress.Sections buildSections() {
        BoardScene scene = requested == null ? coverScene : requested.scene();
        if (scene == null) { return TerrainLoadProgress.Sections.EMPTY; }
        int columns = (scene.width() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        int rows = (scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        boolean current = requested != null && rebuild != null
              && rebuild.request.generation() == requested.generation() && rebuild.plan != null;
        List<TerrainLoadProgress.SectionState> states = new ArrayList<>(columns * rows);
        for (int index = 0; index < columns * rows; index++) {
            var state = TerrainLoadProgress.SectionState.WAITING;
            if (requested == null || current && !rebuild.remaining.contains(index)) {
                state = TerrainLoadProgress.SectionState.READY;
            } else if (current && rebuild.started.contains(index)) {
                state = TerrainLoadProgress.SectionState.LOADING;
            }
            states.add(state);
        }
        return new TerrainLoadProgress.Sections(columns, rows, states);
    }

    /** A different board must not remain selectable while its replacement is being prepared. */
    void boardChanged() {
        coverScene = null;
        requested = null;
        meshGeneration++;
    }

    /** Only terrain presentation waits for publication; units, commands and the source's game state remain current. */
    BoardScene presentation(BoardScene scene) {
        if (requested == null || tiles == scene.tiles()) { return scene; }
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), tiles, scene.units(), scene.plannedPath(),
              scene.selectedId(), scene.phase(), scene.commands(), scene.light(), scene.firingLines(), scene.rangeBorders(),
              scene.markers(), scene.tactical(), scene.rangeLabels(), scene.fieldOfView());
    }

    TerrainSettings settings() { return installedSettings; }

    private static boolean sameBoard(BoardScene a, BoardScene b) {
        return a.boardId() == b.boardId() && a.width() == b.width() && a.height() == b.height();
    }

    private static boolean sameSettings(TerrainSettings a, TerrainSettings b) {
        if (a == null) { return false; }
        BoardGeometry.Tuning x = a.geometry(), y = b.geometry();
        return x.hexScale() == y.hexScale() && x.levelHeight() == y.levelHeight() && x.gridShade() == y.gridShade()
              && x.transitions() == y.transitions() && x.padding() == y.padding()
              && a.terrainRevision() == b.terrainRevision();
    }

    private void checkBuild(long generation) {
        if (meshGeneration != generation || Thread.currentThread().isInterrupted()) { throw new CancellationException(); }
    }

    private UpdatePlan prepareUpdate(Request request, BoardScene beforeScene, float previousFloor,
          BoardConcrete previousCoast, Map<Coords, BoardFlow.Current> previousCurrents,
          Map<GroundSlot, BoardScene.Pixels> previousColors, Map<GroundSlot, BoardScene.Pixels> previousNormals,
          boolean changedTuning, boolean changedLimbScale, BoardScene.Pixels incline, BoardScene.Pixels highIncline,
          TerrainLoadProgress progress) {
        BoardScene scene = request.scene();
        progress.begin("layout", 1);
        long generation = request.generation();
        Map<GroundSlot, BoardScene.Pixels> terrainPixels = new ConcurrentHashMap<>();
        Map<GroundSlot, BoardScene.Pixels> normalPixels = new ConcurrentHashMap<>();
        Map<Coords, BoardScene.Pixels> decalPixels = new ConcurrentHashMap<>();
        Map<Coords, BoardScene.Pixels> foliagePixels = new ConcurrentHashMap<>();
        float nextFloor = BoardGeometry.floor(scene);
        boolean rebuildAll = changedTuning || beforeScene == null || !sameBoard(beforeScene, scene);
        boolean changedFlow = rebuildAll;
        Map<Coords, BoardFlow.Current> nextCurrents = previousCurrents;
        BoardConcrete nextCoast = BoardConcrete.of(scene);
        progress.advance();
        progress.begin("changes", scene.tiles().size());
        Set<Coords> changedTiles = new HashSet<>();
        for (int index = 0; index < scene.tiles().size(); index++) {
            checkBuild(generation);
            BoardScene.Tile tile = scene.tiles().get(index);
            if (!rebuildAll) {
                // The visual floor closes the outside of the board; interior hexes never reach it.
                Coords at = tile.coords();
                if (nextFloor != previousFloor && (at.getX() == 0 || at.getY() == 0
                      || at.getX() == scene.width() - 1 || at.getY() == scene.height() - 1)) { changedTiles.add(at); }
                if (changedLimbScale && tile.features().stream().anyMatch(feature -> feature.kind() == BoardScene.FeatureKind.LIMB)) {
                    changedTiles.add(tile.coords());
                }
                BoardScene.Tile before = beforeScene.tiles().get(index);
                if (before.blackIce() != tile.blackIce()) { changedTiles.add(tile.coords()); }
                changedFlow |= !before.coords().equals(tile.coords()) || before.elevation() != tile.elevation()
                      || before.frozen() != tile.frozen() || !before.liquid().equals(tile.liquid());
                // Rim vertex colors come from the selected ground artwork, not the atlas texture.
                if (!before.ground().equals(tile.ground()) || !Objects.equals(before.normals(), tile.normals())) {
                    changedTiles.add(tile.coords());
                }
                if (!before.coords().equals(tile.coords())) {
                    rebuildAll = true;
                } else {
                    // A water shore reaches the banks and corners it shapes from further out (BoardSurface.Key);
                    // joined landforms also depend on the neighbours' road approaches, two hexes out.
                    boolean shore = before.elevation() != tile.elevation() || before.waterDepth() != tile.waterDepth()
                          || !before.liquid().equals(tile.liquid()) || before.roadExits() != tile.roadExits()
                          || before.road() != tile.road()
                          || before.surface() != tile.surface() || before.detailedGround() != tile.detailedGround()
                          || !nextCoast.sameCorners(previousCoast, tile.coords());
                    if (shore || before.frozen() != tile.frozen() || before.biome() != tile.biome()
                          || !before.features().equals(tile.features())) {
                        int reach = shore ? editReach(beforeScene, scene, at) : 2;
                        for (int x = at.getX() - reach; x <= at.getX() + reach; x++) {
                            for (int y = at.getY() - reach - 1; y <= at.getY() + reach + 1; y++) {
                                if (at.distance(x, y) <= reach && scene.tile(new Coords(x, y)) != null) {
                                    changedTiles.add(new Coords(x, y));
                                }
                            }
                        }
                    }
                }
            }
            progress.advance();
        }
        if (changedFlow) {
            progress.begin("currents", 1);
            Map<Coords, BoardFlow.Current> next = BoardFlow.calculate(scene);
            // An outlet edit can reverse a distant flat reach, beyond the edited hex's immediate neighbors.
            Set<Coords> affected = new HashSet<>(previousCurrents.keySet());
            affected.addAll(next.keySet());
            for (Coords coords : affected) {
                if (!Objects.equals(previousCurrents.get(coords), next.get(coords))) {
                    changedTiles.add(coords);
                    // The water field blends each current into the neighbouring hexes, possibly across a chunk border.
                    for (int direction = 0; direction < 6; direction++) {
                        Coords neighbor = coords.translated(direction);
                        if (scene.tile(neighbor) != null) { changedTiles.add(neighbor); }
                    }
                }
            }
            nextCurrents = next;
            progress.advance();
        }
        if (!rebuildAll) {
            // An approach edit can change a span's material or natural form beyond the usual terrain edit radius.
            for (var tile : scene.tiles()) {
                var before = beforeScene.tile(tile.coords());
                if (BoardBridge.feature(tile) == null && BoardBridge.feature(before) == null) { continue; }
                if (!Objects.equals(BoardBridge.deck(scene, tile), BoardBridge.deck(beforeScene, before))) {
                    changedTiles.add(tile.coords());
                    for (int d = 0; d < 6; d++) {
                        var neighbor = tile.coords().translated(d);
                        if (scene.tile(neighbor) != null) { changedTiles.add(neighbor); }
                    }
                }
            }
        }
        Set<Coords> changedChunks = new HashSet<>();
        changedTiles.forEach(coords -> dirtyChunk(changedChunks, coords));
        boolean all = rebuildAll;
        progress.begin("textures", scene.tiles().size());
        scene.tiles().parallelStream().forEach(tile -> request.settings().run(() -> {
            checkBuild(generation);
            BoardRim.Images material = tile.detailedGround() ? new BoardRim.Images(tile.ground(), tile.normals())
                  : !all && !changedChunks.contains(new Coords(tile.coords().getX() / CHUNK_SIZE, tile.coords().getY() / CHUNK_SIZE))
                        && previousColors.containsKey(new GroundSlot(tile.coords(), true))
                        ? new BoardRim.Images(previousColors.get(new GroundSlot(tile.coords(), true)),
                              previousNormals.get(new GroundSlot(tile.coords(), true)))
                        : rims.material(scene, tile, nextFloor, incline, highIncline);
            GroundSlot topSlot = new GroundSlot(tile.coords(), true);
            GroundSlot baseSlot = new GroundSlot(tile.coords(), false);
            terrainPixels.put(topSlot, material.color());
            // Riverbanks borrow adjacent ground art, never its cliff-top decoration.
            // The atlas shares these slots whenever the top has no rim.
            terrainPixels.put(baseSlot, tile.ground());
            if (material.normal() != null) {
                normalPixels.put(topSlot, material.normal());
            }
            if (tile.normals() != null) {
                normalPixels.put(baseSlot, tile.normals());
            }
            if (decals(tile) != null) {
                decalPixels.put(tile.coords(), decals(tile));
            }
            if (tile.foliage() != null) { foliagePixels.put(tile.coords(), tile.foliage()); }
            progress.advance();
        }));
        rims.retainUsed();
        progress.begin("atlases", 4);
        return new UpdatePlan(nextFloor, nextCoast, nextCurrents, rebuildAll, changedChunks, changedTiles,
              terrainPixels, normalPixels, decalPixels, foliagePixels);

    }

    private static int editReach(BoardScene before, BoardScene after, Coords at) {
        if (!shoreNearby(before, after, at)) { return 2; }
        if (before.tile(at).liquid().present() || after.tile(at).liquid().present()) { return BoardSurface.SHORE_RINGS; }
        // A height edit can change a neighbouring road approach, which in turn pins its shore field.
        List<Coords> local = new ArrayList<>();
        local.add(at);
        for (int direction = 0; direction < 6; direction++) { local.add(at.translated(direction)); }
        for (Coords coords : local) {
            if (after.tile(coords) != null && BoardRelief.shoreClass(before, before.tile(coords))
                  != BoardRelief.shoreClass(after, after.tile(coords))) { return BoardSurface.SHORE_RINGS; }
        }
        // Local levels/materials affect corners, the adjacent land's corner allowance, and its borrowed walls.
        return 3;
    }

    /** Dry relief has only local neighbours; the larger dependency radius belongs to shore deformation. */
    private static boolean shoreNearby(BoardScene before, BoardScene after, Coords at) {
        int radius = BoardSurface.SHORE_RINGS;
        for (int x = Math.max(0, at.getX() - radius); x <= Math.min(after.width() - 1, at.getX() + radius); x++) {
            for (int y = Math.max(0, at.getY() - radius - 1); y <= Math.min(after.height() - 1, at.getY() + radius + 1); y++) {
                if (at.distance(x, y) > radius) { continue; }
                Coords coords = new Coords(x, y);
                if (before.tile(coords).liquid().present() || after.tile(coords).liquid().present()) { return true; }
            }
        }
        return false;
    }

    private static void dirtyChunk(Set<Coords> chunks, Coords coords) {
        chunks.add(new Coords(coords.getX() / CHUNK_SIZE, coords.getY() / CHUNK_SIZE));
    }

    private static TerrainLod initialDetail(BoardScene scene, int x, int y, Camera camera) {
        if (camera == null) { return TerrainLod.FULL; }
        BoundingBox bounds = new BoundingBox().inf();
        for (int cx = x; cx < Math.min(scene.width(), x + CHUNK_SIZE); cx++) {
            for (int cy = y; cy < Math.min(scene.height(), y + CHUNK_SIZE); cy++) {
                BoardScene.Tile tile = scene.tile(new Coords(cx, cy));
                Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                bounds.ext(center.x - BoardGeometry.width(), center.y - BoardGeometry.height(),
                      center.z - BoardGeometry.level());
                bounds.ext(center.x + BoardGeometry.width(), center.y + BoardGeometry.height(),
                      center.z + BoardRelief.headroom(tile));
            }
        }
        return TerrainLod.select(hexPixels(camera, bounds), null);
    }

    /**
     * The level an installed chunk builds next for its projected size. Coarsening goes straight to its level;
     * refinement to full detail passes through medium detail when the chunk is coarser than that, because medium
     * detail builds in a fraction of the time and already carries the grass that full detail shows, so a view that
     * closes in fast sees its blades long before the full ground lands.
     */
    private static TerrainLod nextDetail(float pixels, TerrainLod current) {
        TerrainLod lod = TerrainLod.select(pixels, current);
        return lod == TerrainLod.FULL && current.ordinal() > TerrainLod.MEDIUM.ordinal() ? TerrainLod.MEDIUM : lod;
    }

    private static float hexPixels(Camera camera, BoundingBox bounds) {
        if (camera.projection.val[Matrix4.M33] != 0) { return BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera); }
        // Nearest possible depth: every point of a perspective chunk receives at least the required detail.
        Vector3 nearest = new Vector3(camera.direction.x >= 0 ? bounds.min.x : bounds.max.x,
              camera.direction.y >= 0 ? bounds.min.y : bounds.max.y,
              camera.direction.z >= 0 ? bounds.min.z : bounds.max.z);
        return BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, nearest);
    }

    /** A bounded edit pipeline shares two CPU workers; GL collection/upload remains under the frame budget. */
    boolean refine(Camera camera) {
        // While opening/replacing a board, use more of the loading frame for uploads instead of stretching each
        // chunk's handoffs over several frames. Interactive edits retain their smaller budget.
        long deadline = System.nanoTime() + (coverScene == null ? 8_000_000 : 2_000_000);
        drainRetired(deadline);
        do {
            if (advanceBuild(camera, deadline)) { return true; }
            if (detailJob != null && !detailJob.ready()
                  || rebuild != null && !rebuild.preparation.isDone() || !busy()) { break; }
        } while (System.nanoTime() < deadline);
        return false;
    }

    private boolean advanceBuild(Camera camera, long deadline) {
        if (rebuild != null && !rebuild.pending.isEmpty() && (detailJob == null || !detailJob.ready())) {
            int ready = 0;
            for (int i = 0; i < rebuild.pending.size(); i++) {
                if (rebuild.pending.get(i).ready()) { ready = i; break; }
            }
            DetailJob next = rebuild.pending.remove(ready);
            if (detailJob != null) { rebuild.pending.add(detailJob); }
            detailJob = next;
        }
        if (detailJob != null && detailJob.ready()) {
            DetailJob job = detailJob;
            detailJob = null;
            boolean valid = job.generation() == meshGeneration;
            if (!job.rebuilding()) {
                valid &= requested == null && job.source() == chunks.get(job.index())
                      && (camera == null || job.settings().call(
                            () -> nextDetail(hexPixels(camera, job.source().bounds), job.source().lod)) == job.lod());
            }
            if (valid) {
                try (TerrainSettings.Scope ignored = TerrainSettings.use(job.settings())) {
                    if (job.build() == null) {
                        int rows = (job.scene().height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
                        ChunkBuild build = new ChunkBuild(job.scene(), job.index() / rows * CHUNK_SIZE,
                              job.index() % rows * CHUNK_SIZE, job.floor(), job.lod(), job.surfaces().join(), job.source(), job.progress());
                        detailJob = new DetailJob(job.generation(), job.index(), job.source(), job.lod(), job.scene(),
                              job.settings(), job.floor(), job.rebuilding(), null, build, null, job.progress());
                    } else if (job.meshes() == null) {
                        detailJob = job;
                        if (job.build().collectUntil(deadline)) {
                            detailJob = new DetailJob(job.generation(), job.index(), job.source(), job.lod(), job.scene(),
                                  job.settings(), job.floor(), job.rebuilding(), null, job.build(),
                                  CompletableFuture.runAsync(() -> job.settings().run(() ->
                                        job.build().prepare(() -> checkBuild(job.generation()))), detailWorker), job.progress());
                        }
                    } else {
                        job.meshes().join();
                        if (!job.build().uploadUntil(deadline)) { detailJob = job; return false; }
                        Chunk next = job.build().finish();
                        if (job.rebuilding()) {
                            rebuild.replacements.put(job.index(), next);
                            rebuild.remaining.remove(job.index());
                            rebuild.progress.advance();
                            if (rebuild.remaining.isEmpty()) { rebuild.progress.begin("finishing", 1); }
                        } else {
                            replaceDetail(job.index(), next);
                            return true;
                        }
                    }
                }
            } else if (job.build() != null) {
                retiredChunks.add(job.build().chunk);
            }
        }
        if (detailJob != null) {
            if (rebuild != null && rebuild.plan != null && rebuild.request.generation() == meshGeneration) { queueChunks(camera); }
            return false;
        }
        if (rebuild != null && !rebuild.pending.isEmpty()) {
            detailJob = rebuild.pending.removeFirst();
            return false;
        }
        if (rebuild != null && rebuild.request.generation() != meshGeneration) {
            if (!rebuild.preparation.isDone()) { return false; }
            retiredChunks.addAll(rebuild.replacements.values());
            rims.clear();
            rebuild = null;
        }
        if (requested != null) {
            if (rebuild == null) {
                if (limbModel == null && unitModels != null && requested.scene().tiles().stream()
                      .anyMatch(tile -> tile.features().stream().anyMatch(feature -> feature.kind() == BoardScene.FeatureKind.LIMB))) {
                    var limb = unitModels.equipment("Limb Club");
                    limbModel = limb == null ? null : limb.model();
                }
                Request request = requested;
                BoardScene previous = coverScene;
                float bottom = floor;
                BoardConcrete previousCoast = coast;
                Map<Coords, BoardFlow.Current> flow = currents;
                Map<GroundSlot, BoardScene.Pixels> colors = terrainPixels, normals = normalPixels;
                BoardScene.Pixels incline = assets.inclineMask(), highIncline = assets.highInclineMask();
                boolean changedTuning = installedSettings == null
                      || !sameSettings(installedSettings, request.settings());
                boolean changedLimbScale = limbModel != null && installedSettings != null
                      && installedSettings.geometry().unitScale() != request.settings().geometry().unitScale();
                TerrainLoadProgress progress = new TerrainLoadProgress();
                rebuild = new Rebuild(request, CompletableFuture.supplyAsync(() -> request.settings().call(
                      () -> prepareUpdate(request, previous, bottom, previousCoast, flow, colors, normals,
                            changedTuning, changedLimbScale, incline, highIncline, progress)), detailWorker), progress);
                return false;
            }
            if (!rebuild.preparation.isDone()) { return false; }
            try (TerrainSettings.Scope ignored = TerrainSettings.use(rebuild.request.settings())) {
                BoardScene snapshot = rebuild.request.scene();
                if (rebuild.plan == null) {
                    rebuild.plan = rebuild.preparation.join();
                    prepareAtlases(snapshot, rebuild.plan, rebuild.progress);
                    int rows = (snapshot.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
                    for (int x = 0; x < snapshot.width(); x += CHUNK_SIZE) {
                        for (int y = 0; y < snapshot.height(); y += CHUNK_SIZE) {
                            Coords at = new Coords(x / CHUNK_SIZE, y / CHUNK_SIZE);
                            if (rebuild.plan.all() || rebuild.plan.changed().contains(at) || atlasChanges.contains(at)) {
                                rebuild.remaining.add(at.getX() * rows + at.getY());
                            }
                        }
                    }
                    rebuild.progress.begin("sections", rebuild.remaining.size());
                    return false;
                }
                if (rebuild.remaining.isEmpty()) { commitRebuild(); return true; }
                queueChunks(camera);
                return false;
            }
        }
        if (camera == null || coverScene == null) { return false; }
        try (TerrainSettings.Scope ignored = TerrainSettings.use(installedSettings)) {
            int candidate = -1;
            float priority = -Float.MAX_VALUE;
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                if (!camera.frustum.boundsInFrustum(chunk.bounds)) { continue; }
                float pixels = hexPixels(camera, chunk.bounds);
                TerrainLod lod = nextDetail(pixels, chunk.lod);
                if (lod == chunk.lod) { continue; }
                float score = lod.ordinal() < chunk.lod.ordinal() ? pixels : -pixels;
                if (score > priority) { priority = score; candidate = i; }
            }
            if (candidate >= 0) {
                Chunk source = chunks.get(candidate);
                TerrainLod lod = nextDetail(hexPixels(camera, source.bounds), source.lod);
                Chunk cached = detailCache.get(candidate);
                if (cached != null && cached.lod == lod) {
                    detailCache.remove(candidate);
                    buildMarkings(coverScene, cached, candidate / chunkRows * CHUNK_SIZE, candidate % chunkRows * CHUNK_SIZE);
                    replaceDetail(candidate, cached);
                    return true;
                }
                startChunk(candidate, source, lod, coverScene, installedSettings, floor, currents, false);
            }
        }
        return false;
    }

    /** Loading and local edits share the same bounded CPU/GL pipeline, without extra worker threads. */
    private void queueChunks(Camera camera) {
        try (TerrainSettings.Scope ignored = TerrainSettings.use(rebuild.request.settings())) {
            BoardScene snapshot = rebuild.request.scene();
            int columns = (snapshot.width() + CHUNK_SIZE - 1) / CHUNK_SIZE;
            int rows = (snapshot.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
            boolean loading = !ready(snapshot);
            while (rebuild.pending.size() + (detailJob == null ? 0 : 1) < 4) {
                int candidate = -1;
                float priority = -Float.MAX_VALUE;
                for (int index : rebuild.remaining) {
                    if (rebuild.started.contains(index)) { continue; }
                    float x = BoardGeometry.centerX(new Coords(index / rows * CHUNK_SIZE, 0));
                    float y = BoardGeometry.centerY(new Coords(0, index % rows * CHUNK_SIZE));
                    float score = loading ? -TerrainLoadProgress.displayIndex(index, columns, rows)
                          : camera == null ? -index : -camera.position.dst2(x, y, 0);
                    if (!loading && camera != null && camera.frustum.sphereInFrustum(x, y, 0,
                          CHUNK_SIZE * BoardGeometry.width())) { score += 1e20f; }
                    if (score > priority) { priority = score; candidate = index; }
                }
                if (candidate < 0) { break; }
                rebuild.started.add(candidate);
                DetailJob active = detailJob;
                int x = candidate / rows * CHUNK_SIZE, y = candidate % rows * CHUNK_SIZE;
                TerrainLod lod = initialDetail(snapshot, x, y, camera);
                startChunk(candidate, rebuild.plan.all() ? null : chunks.get(candidate), lod, snapshot, rebuild.request.settings(), rebuild.plan.floor(),
                      rebuild.plan.currents(), true);
                if (active != null) {
                    rebuild.pending.add(detailJob);
                    detailJob = active;
                }
            }
        }
    }

    private void startChunk(int index, Chunk source, TerrainLod lod, BoardScene scene, TerrainSettings settings,
          float bottom, Map<Coords, BoardFlow.Current> flow, boolean rebuilding) {
        int rows = (scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        int x = index / rows * CHUNK_SIZE, y = index % rows * CHUNK_SIZE;
        long generation = meshGeneration;
        Set<Coords> reuse = new HashSet<>();
        Map<Coords, BoardSurface.WaterGeometry> waterShapes = new HashMap<>();
        if (rebuilding && source != null && source.lod == lod) {
            for (Coords coords : source.tileMeshes.keySet()) {
                if (!rebuild.plan.changedTiles().contains(coords)
                      && !atlasTileChanges.contains(coords)) { reuse.add(coords); }
            }
        }
        if (source != null) {
            for (Coords coords : reuse) {
                var cached = cpuGeometry.get(source.tileMeshes.get(coords));
                var water = cached == null ? null : cached.water();
                if (water != null) { waterShapes.put(coords, water); }
            }
        }
        TerrainLoadProgress progress = new TerrainLoadProgress();
        detailJob = new DetailJob(generation, index, source, lod, scene, settings, bottom, rebuilding,
              CompletableFuture.supplyAsync(() -> settings.call(
                    () -> prepare(scene, x, y, bottom, lod, flow, settings, reuse, waterShapes,
                          () -> checkBuild(generation), progress)), detailWorker), null, null, progress);
    }

    private void prepareAtlases(BoardScene scene, UpdatePlan plan, TerrainLoadProgress progress) {
        ground.retainReplacedPages();
        decals.retainReplacedPages();
        foliage.retainReplacedPages();
        tactical.retainReplacedPages();
        ground.updateRegions(plan.colors(), plan.normals()).forEach(slot -> {
            dirtyChunk(atlasChanges, slot.coords());
            atlasTileChanges.add(slot.coords());
            if (!slot.rims()) {
                for (int direction = 0; direction < 6; direction++) {
                    BoardScene.Tile neighbor = scene.tile(slot.coords().translated(direction));
                    if (neighbor != null && neighbor.liquid().present()) {
                        dirtyChunk(atlasChanges, neighbor.coords());
                        atlasTileChanges.add(neighbor.coords());
                    }
                }
            }
        });
        progress.advance();
        decals.updateRegions(plan.decals(), Map.of()).forEach(coords -> {
            dirtyChunk(atlasChanges, coords);
            atlasTileChanges.add(coords);
        });
        progress.advance();
        foliage.updateRegions(plan.foliage(), Map.of()).forEach(coords -> {
            dirtyChunk(atlasChanges, coords);
            atlasTileChanges.add(coords);
        });
        progress.advance();
        markingChanges.addAll(updateMarkingsAtlas(scene));
        progress.advance();
    }

    private void commitRebuild() {
        BoardScene scene = rebuild.request.scene();
        UpdatePlan plan = rebuild.plan;
        if (plan.all()) {
            propBatch.dispose();
            terrainPages.dispose();
            waterPages.dispose();
            retiredChunks.addAll(chunks);
            chunks.clear();
            int count = ((scene.width() + CHUNK_SIZE - 1) / CHUNK_SIZE) * ((scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE);
            for (int index = 0; index < count; index++) { chunks.add(rebuild.replacements.get(index)); }
        } else {
            rebuild.replacements.forEach((index, next) -> retiredChunks.add(chunks.set(index, next)));
            for (Coords at : markingChanges) {
                int index = at.getX() * chunkRows + at.getY();
                if (!rebuild.replacements.containsKey(index)) {
                    buildMarkings(scene, chunks.get(index), at.getX() * CHUNK_SIZE, at.getY() * CHUNK_SIZE);
                }
            }
        }
        coverScene = scene;
        tiles = scene.tiles();
        installedSettings = rebuild.request.settings();
        floor = plan.floor();
        coast = plan.coast();
        currents = plan.currents();
        terrainPixels = plan.colors();
        normalPixels = plan.normals();
        chunkRows = (scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (requested == rebuild.request) { requested = null; }
        rebuild = null;
        atlasChanges.clear();
        atlasTileChanges.clear();
        markingChanges.clear();
        ground.publish();
        decals.publish();
        foliage.publish();
        tactical.publish();
        terrainBatch.clear();
        pickingSurfaces.clear();
        detailPixelsPerUnit = Float.NaN;
        hasCutaways = chunks.stream().anyMatch(chunk -> !chunk.cutaways.isEmpty());
        Set<GpuBuilding.Assembly> liveBuildings = new HashSet<>();
        for (Chunk chunk : chunks) {
            for (Prop prop : chunk.props) { if (prop.building != null) { liveBuildings.add(prop.building); } }
        }
        for (Chunk chunk : detailCache.values()) {
            for (Prop prop : chunk.props) { if (prop.building != null) { liveBuildings.add(prop.building); } }
        }
        Set<Mesh> sharedSources = new HashSet<>();
        for (Chunk chunk : chunks) {
            for (Renderable part : chunk.sharedShadows) { sharedSources.add(part.meshPart.mesh); }
        }
        for (Chunk chunk : detailCache.values()) {
            for (Renderable part : chunk.sharedShadows) { sharedSources.add(part.meshPart.mesh); }
        }
        trees.retainParts(sharedSources);
        assets.retainBuildings(liveBuildings);
        updateLight(scene.light());
        if (!terrainMaterialsReady) { prepareTerrainMaterials(); }
    }

    /** Pay the fixed natural-terrain palette and shader costs while opening, before the first painting stroke. */
    private void prepareTerrainMaterials() {
        for (BoardScene.Surface family : BoardScene.Surface.values()) { sculptMaterial(family); }
        Material blend = blendMaterial(new GpuSurfaceBlend.Palette(BoardScene.Surface.GRASS,
              BoardScene.Surface.SAND, BoardScene.Surface.ROCK));
        for (Material material : List.of(sculptMaterial(BoardScene.Surface.GRASS), blend)) {
            Mesh mesh = new Mesh(true, 1, 0, material.has(GpuSurfaceBlend.TYPE) ? GpuSurfaceBlend.VERTICES : MeshBatch.STANDARD);
            try {
                Renderable sample = new Renderable();
                sample.material = material;
                sample.environment = environment;
                sample.meshPart.set("terrain-material-warmup", mesh, 0, 0, GL20.GL_TRIANGLES);
                batch.getShaderProvider().getShader(sample);
            } finally { mesh.dispose(); }
        }
        terrainMaterialsReady = true;
    }

    private void drainRetired(long deadline) {
        while (!retiredChunks.isEmpty() && System.nanoTime() < deadline) { retiredChunks.removeFirst().dispose(); }
        if (retiredChunks.isEmpty()) {
            ground.releaseRetiredPages();
            decals.releaseRetiredPages();
            foliage.releaseRetiredPages();
            tactical.releaseRetiredPages();
        }
    }

    private void replaceDetail(int index, Chunk next) {
        Chunk old = chunks.set(index, next);
        Chunk evicted = detailCache.remove(index);
        if (evicted != null) { evicted.dispose(); }
        detailCache.put(index, old);
        if (detailCache.size() > 8) {
            var oldest = detailCache.entrySet().iterator();
            oldest.next().getValue().dispose();
            oldest.remove();
        }
        terrainBatch.clear();
        pickingSurfaces.clear();
        detailPixelsPerUnit = Float.NaN;
        shadowDirty = true;
        updateLight(coverScene.light());
    }

    private static boolean sameTerrain(List<BoardScene.Tile> tiles, List<BoardScene.Tile> next) {
        if (tiles == next) { return true; }
        if (tiles == null || tiles.size() != next.size()) { return false; }
        for (int i = 0; i < tiles.size(); i++) {
            BoardScene.Tile before = tiles.get(i), after = next.get(i);
            if (!before.sameGeometry(after) || before.blackIce() != after.blackIce() || !Objects.equals(before.ground(), after.ground())
                  || !Objects.equals(before.normals(), after.normals()) || !Objects.equals(before.decals(), after.decals())
                  || !Objects.equals(before.decalsWithoutLimbs(), after.decalsWithoutLimbs())
                  || !Objects.equals(before.foliage(), after.foliage())) { return false; }
        }
        return true;
    }

    /** Only changed atlas references require new marking meshes; in-place pixel uploads keep their geometry. */
    private Set<Coords> updateMarkingsAtlas(BoardScene scene) {
        Map<Coords, BoardScene.Pixels> pixels = new HashMap<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            if (tile.tactical() != null) { pixels.put(tile.coords(), tile.tactical()); }
        }
        Set<Coords> changedChunks = new HashSet<>();
        tactical.updateRegions(pixels, Map.of()).forEach(coords -> dirtyChunk(changedChunks, coords));
        return changedChunks;
    }

    private BoardScene.Pixels decals(BoardScene.Tile tile) {
        return limbModel != null && tile.decalsWithoutLimbs() != null ? tile.decalsWithoutLimbs() : tile.decals();
    }

    private Prepared prepare(BoardScene scene, int startX, int startY, float floor, TerrainLod lod,
          Map<Coords, BoardFlow.Current> currents, TerrainSettings settings, Set<Coords> reused,
          Map<Coords, BoardSurface.WaterGeometry> waterShapes, Runnable check, TerrainLoadProgress progress) {
        check.run();
        // Sculpting is pure CPU work per hex: prepare the chunk's surfaces and cliffs in parallel, then build meshes.
        List<BoardScene.Tile> chunkTiles = new ArrayList<>();
        for (int x = startX; x < Math.min(scene.width(), startX + CHUNK_SIZE); x++) {
            for (int y = startY; y < Math.min(scene.height(), startY + CHUNK_SIZE); y++) {
                Coords coords = new Coords(x, y);
                if (!reused.contains(coords)) { chunkTiles.add(scene.tile(coords)); }
            }
        }
        Map<Coords, BoardSurface> surfaces = new java.util.concurrent.ConcurrentHashMap<>();
        progress.begin("surfaces", chunkTiles.size() + reused.size());
        waterShapes.forEach((coords, shape) -> surfaces.put(coords, shape.surface(scene)));
        // An evicted query snapshot is reconstructed only for an edit that actually needs that water again.
        for (Coords coords : reused) {
            if (scene.tile(coords).liquid().present()) {
                surfaces.computeIfAbsent(coords, key -> new BoardSurface(scene, scene.tile(key), lod));
            }
            progress.advance();
        }
        chunkTiles.parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            surfaces.put(tile.coords(), new BoardSurface(scene, tile, lod));
            progress.advance();
        }));
        // Every neighbour's top is complete before cliff construction borrows it. Each task still owns its walls.
        progress.begin("cliffs", chunkTiles.size());
        chunkTiles.parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            BoardSurface surface = surfaces.get(tile.coords());
            if (surface.relief.sculpted() || BoardGeometry.tuning().stepsBetweenTops()) {
                surface.walls(scene, floor, surfaces);
            }
            progress.advance();
        }));
        Map<Coords, BoardTacticalGeometry.Surface> topography = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, BoardPlants> plants = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, SculptPlan> sculpts = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, List<RoadPatch>> roads = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, BoardBridge.Deck> bridges = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, BoardBridge.Shape> bridgeShapes = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, Map<BoardSurface.Side, List<BoardSurface.Face>>> walls = new java.util.concurrent.ConcurrentHashMap<>();
        progress.begin("topography", chunkTiles.size());
        chunkTiles.parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            BoardSurface surface = surfaces.get(tile.coords());
            topography.put(surface.tile.coords(), BoardTacticalGeometry.Surface.of(surface, scene, floor));
            if (surface.relief.sculpted()) {
                sculpts.put(surface.tile.coords(), prepareSculpt(scene, surface.tile, surface, floor, lod, surfaces));
            } else {
                // Side sampling can scan thousands of top faces. Keep it on the terrain worker, like sculpted cliffs.
                walls.put(tile.coords(), prepareWalls(scene, surface, floor, surfaces));
            }
            progress.advance();
        }));
        progress.begin("vegetation", chunkTiles.size());
        chunkTiles.parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            // Ground cover stands on this finished support and installs with it: it never trails or outlives its ground.
            BoardPlants planted = BoardPlants.plant(scene, tile, topography.get(tile.coords()), lod);
            if (planted != null) { plants.put(tile.coords(), planted); }
            progress.advance();
        }));
        progress.begin("roads", chunkTiles.size());
        chunkTiles.parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            BoardSurface surface = surfaces.get(tile.coords());
            List<RoadPatch> patches = new ArrayList<>();
            if (BoardRoad.rendered(surface.tile)) {
                BoardRoad road = BoardRoad.of(scene, surface.tile);
                for (var patch : GpuRoads.patches(surface.tile, road)) {
                    check.run();
                    var triangles = GpuRoads.drape(surface.tile, surface, patch);
                    if (!triangles.isEmpty() && !patch.shape().isEmpty()) {
                        patches.add(new RoadPatch(patch, roadMaskData.share(GpuRoads.mask(road, patch)), triangles, false));
                    }
                }
            }
            for (var feature : tile.features()) {
                if (!feature.asset().equals("bridge")) { continue; }
                var deck = BoardBridge.deck(scene, tile);
                bridges.put(tile.coords(), deck);
                if (deck.natural()) {
                    bridgeShapes.put(tile.coords(), BoardNaturalBridge.build(scene, tile, deck, lod, surfaces));
                    continue;
                }
                var footing = BoardBridgeFooting.build(scene, tile, lod, surfaces);
                if (!footing.shape().facets().isEmpty()) { bridgeShapes.put(tile.coords(), footing.shape()); }
                BoardRoad road = footing.road(deck, tile.coords());
                for (var patch : GpuRoads.deckPatches(tile, deck, road, footing)) {
                    check.run();
                    if (patch.shape().isEmpty()) { continue; }
                    patches.add(new RoadPatch(patch, roadMaskData.share(GpuRoads.mask(road, patch, false)),
                          GpuRoads.deck(tile, feature, patch, footing, surfaces), true));
                }
            }
            if (!patches.isEmpty()) { roads.put(tile.coords(), patches); }
            progress.advance();
        }));
        progress.begin("water", 2);
        var water = GpuWaterShader.Field.prepare(scene, currents, surfaces);
        progress.advance();
        var lava = GpuWaterShader.Field.prepare(scene, currents, surfaces, true);
        progress.advance();
        progress.begin("masks", 3);
        return new Prepared(surfaces, water, lava, topography, plants, sculpts, currents, roads,
              bridges, bridgeShapes, walls, reused);
    }

    /** Natural walls are the completed surface mesh, shared with picking; legacy skirts still follow their sides. */
    static Map<BoardSurface.Side, List<BoardSurface.Face>> prepareWalls(BoardScene scene, BoardSurface surface,
          float floor, Map<Coords, BoardSurface> surfaces) {
        Map<BoardSurface.Side, List<BoardSurface.Face>> result = new LinkedHashMap<>();
        Set<Integer> natural = new HashSet<>();
        for (BoardSurface.Side side : surface.sides(scene, floor, surfaces)) {
            if (surface.relief.naturalEdge(side.edge())) {
                if (natural.add(side.edge())) {
                    result.put(side, surface.walls(scene, floor, surfaces).stream()
                          .filter(face -> face.landEdge() == side.edge()).toList());
                }
            } else {
                result.put(side, surface.relief.walls(List.of(side)));
            }
        }
        return result;
    }

    private void collectTile(ChunkBuild build, BoardScene.Tile tile) {
        BoardScene scene = build.scene;
        Prepared prepared = build.prepared;
        Map<Coords, BoardSurface> surfaces = prepared.surfaces();
        Chunk chunk = build.chunk;
        float floor = build.floor;
        TerrainLod lod = chunk.lod;
        Layer solid = build.layers.get(0), scatter = build.layers.get(1), overlay = build.layers.get(2),
              trees = build.layers.get(3), liquid = build.layers.get(4);
        Map<String, LiquidSurface> animations = build.animations;
        BoardSurface surface = surfaces.get(tile.coords());
        List<BoardSurface.Face> smoothTop = new ArrayList<>();
        List<BoardSurface.Face> outcrops = new ArrayList<>();
        TextureRegion top = ground.region(new GroundSlot(tile.coords(), true));
        boolean sculpted = surface.relief.sculpted();
        Material magma = tile.liquid().volcanic() ? GpuMagmaShader.material(assets,
              tile.liquid().molten() ? GpuMagmaShader.BANK : GpuMagmaShader.CRUST, null) : null;
        if (sculpted) {
            sculpt(solid, overlay, chunk, scene, tile, surface, top, surfaces, prepared.sculpts().get(tile.coords()), magma);
        }
        if (tile.frozen() && tile.water()) {
            // One shared material in the existing transparent pass reveals the already-rendered bed/units.
            // No per-hex texture, refraction target or additional surface mesh is needed.
            Material ice = GpuIceShader.lake(material(assets.ice().color(), true));
            ice.set(new Ground(1));
            liquid.addTriangles(ice, meshes -> drawnIce(meshes, surface));
        }
        for (BoardSurface.Face face : sculpted ? List.<BoardSurface.Face>of() : surface.faces) {
            if (face.finish() == BoardSurface.Finish.ROUGH) { continue; }
            if (face.finish() == BoardSurface.Finish.ICE) { continue; }
            chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c());
            // Flat road hexes keep their ground path, but their rocks use the same geology as sculpted hexes.
            if (magma == null && face.finish() == BoardSurface.Finish.OUTCROP) { outcrops.add(face); continue; }
            boolean artwork = !tile.liquid().molten() && (face.finish() == BoardSurface.Finish.TOP
                  || face.finish() == BoardSurface.Finish.ICE || face.finish() == BoardSurface.Finish.SHORE);
            BoardScene.Tile land = face.landEdge() < 0 ? null
                  : scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(face.landEdge())));
            BoardScene.Tile cover = land != null && !land.liquid().present() ? land : tile;
            boolean physical = !tile.liquid().volcanic() && face.finish() != BoardSurface.Finish.ICE
                  && (cover.detailedGround() || face.finish() == BoardSurface.Finish.BED
                  || face.finish() == BoardSurface.Finish.BANK || face.finish() == BoardSurface.Finish.RIM);
            if (magma != null) {
                smoothTop.add(face);
            } else if (physical) {
                // Non-sculpted ground is the graded road/approach mesh; water already uses sculpt().
                smoothTop.add(face);
            } else if (land != null && !land.liquid().present()) {
                TextureRegion bankArt = ground.region(new GroundSlot(land.coords(), false));
                solid.add(groundMaterial(bankArt.getTexture(), scene, land),
                      mesh -> bank(mesh, tile.coords(), face, land.coords(), bankArt));
            } else {
                Texture texture = artwork ? top.getTexture() : assets.material(tile.liquid().molten() ? "terrain/rock"
                      : face.finish() == BoardSurface.Finish.BED ? "terrain/water_bed"
                      : tile.liquid().present() ? "terrain/sand" : tile.surface().wall);
                solid.add(artwork ? groundMaterial(texture, scene, tile) : material(texture, false),
                      mesh -> surface(mesh, tile.coords(), face, artwork ? top : null, 0));
            }
            if (face.finish() == BoardSurface.Finish.SHORE && !physical && magma == null) {
                overlay.add(material(assets.material(tile.liquid().molten() ? "terrain/rock" : "terrain/sand"), true),
                      mesh -> shore(mesh, tile, face));
            }
            if (decals(tile) != null && artwork) {
                TextureRegion art = decals.region(tile.coords());
                overlay.add(material(art.getTexture(), true), mesh -> surface(mesh, tile.coords(), face, art, 0.08f));
            }
        }
        for (var group : byFamily(surface, outcrops).entrySet()) {
            float shore = tile.liquid().present() ? GpuWaterShader.palette(tile.liquid()) : Float.NaN;
            solid.addTriangles(sculptMaterial(group.getKey()),
                  mesh -> sculptedFaces(mesh, surface, group.getValue(), shore, scene, surfaces));
        }
        if (!sculpted && !smoothTop.isEmpty()) {
            Map<Vector3, Vector3> normals = wallNormals(smoothTop);
            float earthwork = surface.ramps == 0 ? 1 : .3f + .1f * Math.min(surface.roadLevels(), 7);
            blended(solid, GpuSurfaceBlend.prepare(scene, tile, smoothTop,
                  p -> vertex(p, surface.roadNormal(p, normals.get(p)), 99, 99,
                        new Color(1, (tile.elevation() + 64) / 255f, 0, earthwork))), scene, tile);
        }
        if (!sculpted && !surface.retainingWalls.isEmpty()) {
            blended(solid, GpuSurfaceBlend.prepare(scene, tile, surface.retainingWalls,
                  p -> sculptVertex(p, surface.relief.shade(p), Float.NaN, surface)));
            for (var face : surface.retainingWalls) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        }
        if (!surface.retainingPanels.isEmpty()) {
            // Precast retaining walls share the tunnel portals' concrete.
            solid.add(material(assets.material("tunnel-concrete"), false), mesh -> {
                for (var face : surface.retainingPanels) { panel(mesh, face); }
            });
            for (var face : surface.retainingPanels) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        }
        roads(overlay, prepared.roads().getOrDefault(tile.coords(), List.of()), surface, chunk.roadMasks, scene, tile);
        if (tile.foliage() != null) {
            TextureRegion art = foliage.region(tile.coords());
            trees.add(material(art.getTexture(), true), mesh -> foliage(mesh, tile, art));
            float height = BoardGeometry.surfaceZ(tile) + .16f * BoardGeometry.hexScale();
            chunk.bounds.ext(BoardGeometry.centerX(tile.coords()) - BoardGeometry.width() / 2,
                  BoardGeometry.centerY(tile.coords()) - BoardGeometry.height() / 2, height);
            chunk.bounds.ext(BoardGeometry.centerX(tile.coords()) + BoardGeometry.width() / 2,
                  BoardGeometry.centerY(tile.coords()) + BoardGeometry.height() / 2, height);
        }
        if (!tile.detailedGround() && !tile.liquid().present() && tile.roadExits() == 0
              && surface.ramps == 0 && BoardGeometry.tuning().gridShade() < 1) {
            solid.add(groundMaterial(top.getTexture(), scene, tile),
                  mesh -> grid(mesh, tile.coords(), BoardGeometry.groundZ(tile), top));
        }
        for (var wall : prepared.walls().getOrDefault(tile.coords(), Map.of()).entrySet()) {
            BoardSurface.Side side = wall.getKey();
            if (magma != null) {
                blended(solid, GpuSurfaceBlend.prepare(scene, tile, wall.getValue(),
                      p -> sculptVertex(p, surface.relief.shade(p), Float.NaN, surface)));
                for (var face : wall.getValue()) {
                    chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c());
                }
                continue;
            }
            List<BoardSurface.Face> wallFaces = wall.getValue();
            for (var face : wallFaces) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
            if (surface.relief.naturalEdge(side.edge())) {
                blended(solid, GpuSurfaceBlend.prepare(scene, tile, wallFaces,
                      p -> sculptVertex(p, surface.relief.shade(p), Float.NaN, surface)));
                continue;
            }
            solid.addTriangles(sculptMaterial(tile.surface()),
                  mesh -> sculptedFaces(mesh, surface, wallFaces, Float.NaN, scene, surfaces));
        }
        if (!surface.water.isEmpty()) {
            BoardLiquid.Textures source = tile.liquid().textures(tile.waterDepth(), tile.elevation());
            BoardFlow.Current current = prepared.currents().getOrDefault(tile.coords(), BoardFlow.Current.STILL);
            GpuWaterShader.Field field = tile.liquid().molten() ? chunk.lavaField : chunk.waterField;
            Material water = liquidMaterial(scene, surface, source, false, field);
            boolean animatedFrames = !water.has(GpuMagmaShader.TYPE) && (!proceduralWater || tile.liquid().molten());
            // Authored frames scroll their own texture with the hex's current; procedural water reads the
            // field's blended current instead, so it shares one material with its neighbours.
            if (animatedFrames) {
                water.id += ":" + current;
                animations.put(water.id, new LiquidSurface(water, source, false, current));
            }
            TextureRegion waterArt = new TextureRegion(water.get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture);
            // Molten material writes opaque depth; water and hazardous pools reveal their beds and units.
            Layer destination = tile.liquid().molten() ? solid : liquid;
            List<BoardSurface.Face> waterFaces = proceduralWater && !tile.liquid().molten()
                  ? GpuWaterWaves.faces(surface.waterFaces, lod) : surface.waterFaces;
            Map<Vector3, Vector3> waterNormals = wallNormals(waterFaces);
            if (tile.liquid().molten() && !water.has(GpuMagmaShader.TYPE)) {
                for (BoardSurface.Face face : waterFaces) {
                    destination.add(water, mesh -> surface(mesh, tile.coords(), face, waterArt, 0));
                }
            } else {
                destination.add(water, mesh -> waterSurface(mesh, field, tile.coords(), waterFaces,
                      proceduralWater ? null : waterArt, waterNormals));
            }
            for (BoardSurface.Face face : waterFaces) {
                chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c());
            }
            Material cut = water;
            if (!surface.cutFaces.isEmpty() && !tile.liquid().molten()) {
                cut = new Material(water);
                cut.id = water.id + ":cut";
                cut.remove(GpuLiquidShader.Frame.TYPE | GpuLiquidShader.Frame.BLEND);
                cut.set(water.get(GpuWaterShader.class, GpuWaterShader.TYPE).cut());
            }
            for (BoardSurface.Face face : surface.cutFaces) {
                if (tile.liquid().molten()) {
                    // A wall of lava, mapped like other walls instead of smeared down from its top.
                    destination.add(water, mesh -> surface(mesh, tile.coords(), face, null, 0));
                } else {
                    destination.add(cut, mesh -> waterCut(mesh, BoardGeometry.waterZ(tile), face));
                }
                chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c());
            }
            if (!surface.waterfalls.isEmpty()) {
                Material fall = liquidMaterial(scene, surface, source, true, field);
                if (animatedFrames) { animations.put(fall.id, new LiquidSurface(fall, source, true, BoardFlow.Current.STILL)); }
                Material back = null, spray = null;
                GpuWaterShader sheet = fall.get(GpuWaterShader.class, GpuWaterShader.TYPE);
                if (!tile.liquid().molten()) {
                    fall.set(IntAttribute.createCullFace(GL20.GL_BACK));
                    // Keep the inward face visible through the upper surface, with half the front's opacity.
                    back = new Material(fall);
                    back.set(new BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA, 0.4f));
                    back.set(IntAttribute.createCullFace(GL20.GL_FRONT));
                }
                if (sheet != null) {
                    // Spray is launched by the water's vertex shader, always at full opacity and from its own
                    // material, so authored frames never swap its shader; every fall's spray shares it.
                    spray = new Material(fall);
                    spray.id = fall.id + ":spray";
                    spray.remove(GpuLiquidShader.Frame.TYPE | GpuLiquidShader.Frame.BLEND);
                    spray.set(new BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA, 1),
                          sheet.spray());
                }
                for (BoardSurface.Side drop : surface.waterfalls) {
                    destination.add(fall, mesh -> GpuWaterfall.sheet(mesh, surface, drop, chunk.waterField));
                    if (back != null) {
                        destination.add(back, mesh -> GpuWaterfall.sheet(mesh, surface, drop, chunk.waterField));
                    }
                    if (spray != null && !surface.bottomless(drop)) {
                        destination.add(spray, mesh -> GpuWaterfall.spray(mesh, surface, drop));
                    }
                    // The fall lands out in the receiving hex and throws its spray up around there.
                    Vector3[][] grid = GpuWaterfall.grid(surface, drop);
                    float around = spray == null ? 0 : GpuWaterfall.sprayReach();
                    float above = spray == null ? 0 : GpuWaterfall.sprayHeight(BoardGeometry.waterZ(tile) - drop.lowA());
                    for (Vector3[] column : grid) {
                        for (Vector3 p : column) { chunk.bounds.ext(p); }
                        Vector3 landing = column[column.length - 1];
                        chunk.bounds.ext(landing.x - around, landing.y - around, landing.z)
                              .ext(landing.x + around, landing.y + around, landing.z + above);
                    }
                }
            }
        }
        for (var tunnel : BoardTunnel.entrances(scene, tile)) {
            Model model = assets.model(tunnel.asset());
            if (model == null) { continue; }
            ModelInstance instance = new ModelInstance(model);
            // The road continues inside with the same world UVs, normal/surface maps and weather response as its approach.
            Material tunnelFloor = instance.getMaterial("tunnel-floor");
            tunnelFloor.set(roadMaterial(GpuRoads.texture(tunnel.kind()).substring("roads/".length()), false));
            tunnelFloor.set(new BridgeDeck());
            instance.transform.set(tunnel.transform());
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform);
            chunk.props.add(new Prop(tile.coords(), instance, bounds, null, false));
            chunk.bounds.ext(bounds);
        }
        for (BoardRough.Placement placement : surface.roughModels()) {
            ModelInstance instance = new ModelInstance(assets.model(placement.asset()));
            instance.transform.set(placement.transform());
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform);
            chunk.props.add(new Prop(tile.coords(), instance, bounds, null, false));
            chunk.bounds.ext(bounds);
        }
        for (BoardScene.Feature feature : tile.features()) {
            // Rough boulders are already part of the shared terrain mesh, shading and picking geometry.
            if (feature.kind() == BoardScene.FeatureKind.BOULDER || feature.kind() == BoardScene.FeatureKind.ROUGH) { continue; }
            if (feature.kind() == BoardScene.FeatureKind.SCATTER) {
                // Road approaches can extend into a hex that has no road terrain of its own.
                if (!tile.liquid().present() && surface.ramps == 0
                      && !(tile.detailedGround() && feature.asset().equals("scatter-grass"))) {
                    if (build.scatterMaterial == null) {
                        build.scatterMaterial = new Material(ColorAttribute.createDiffuse(Color.WHITE),
                              TextureAttribute.createDiffuse(assets.scatter()));
                    }
                    scatter.add(build.scatterMaterial, mesh -> GpuScatter.build(mesh, tile, surface, feature));
                    chunk.scatterDiameter = Math.max(chunk.scatterDiameter, GpuScatter.diameter(feature));
                    chunk.bounds.ext(BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords()),
                          (tile.elevation() + feature.height()) * BoardGeometry.level());
                }
                continue;
            }
            boolean limb = feature.kind() == BoardScene.FeatureKind.LIMB;
            if (limb && limbModel == null) {
                continue;
            }
            boolean bridge = feature.asset().equals("bridge");
            var bridgeShape = bridge ? prepared.bridgeShapes().get(tile.coords()) : null;
            if (bridgeShape != null) {
                chunk.tileMeshes.get(tile.coords()).bridgeShape = bridgeShape;
                chunk.bounds.ext(bridgeShape.bounds());
            }
            if (bridge && prepared.bridges().get(tile.coords()).natural()) {
                naturalBridge(solid, bridgeShape);
                continue;
            }
            GpuBuilding.Assembly building = assets.building(feature.asset(), Math.round(feature.height()),
                  tile.coords().getX() * 73_856_093L ^ tile.coords().getY() * 19_349_663L ^ feature.asset().hashCode());
            Model model = building != null ? building.model(0)
                  : limb ? limbModel : feature.kind() == BoardScene.FeatureKind.TREE
                  ? assets.lodModel(feature.asset(), 0)
                  : assets.model(bridge ? BoardBridge.asset(feature.bridgeExits()) : feature.asset());
            ModelInstance instance = building != null ? building.instance(0)
                  : new ModelInstance(feature.kind() == BoardScene.FeatureKind.TREE ? foliage(model) : model);
            if (bridge) {
                Material deck = instance.getMaterial("bridge-deck");
                deck.set(roadMaterial(GpuRoads.texture(prepared.bridges().get(tile.coords()).kind()).substring("roads/".length()), false));
                deck.set(GpuIceShader.road(deck, scene, tile));
                deck.set(new BridgeDeck());
                if (bridgeShape != null) { bridgeFooting(solid, bridgeShape, deck, instance.getMaterial("bridge-structure")); }
            }
            for (Material material : instance.materials) {
                if (building == null && material.id.equals("wall")) {
                    material.get(TextureAttribute.class, TextureAttribute.Diffuse).scaleV = feature.height();
                }
            }
            float px = BoardGeometry.centerX(tile.coords()) + feature.x() * BoardGeometry.hexScale();
            float py = BoardGeometry.centerY(tile.coords()) + feature.y() * BoardGeometry.hexScale();
            if (feature.kind() == BoardScene.FeatureKind.TREE) {
                // A tree stands on its hex's own ground, never over a receding rim or a transition's slope.
                float[] spot = surface.relief.settle(px, py, BoardRelief.metres(.8f));
                px = spot[0];
                py = spot[1];
            }
            // The deck meets the road surface, including its small clearance above the ground.
            float base = bridge
                  ? tile.elevation() * BoardGeometry.level() + GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale()
                  : surface.height(px, py);
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox());
            if (limb) {
                // Lay the held limb on its side, then ground its actual bounds. No unit/entity is created.
                float scale = feature.scale() * BoardGeometry.unitScale() * BoardGeometry.hexScale();
                instance.transform.setToTranslation(px, py, base).rotate(Vector3.Z, feature.rotation())
                      .rotate(Vector3.Y, 90).scale(scale, scale, scale);
                BoundingBox placed = new BoundingBox(bounds).mul(instance.transform);
                instance.transform.val[Matrix4.M23] += base - placed.min.z + .12f * BoardGeometry.hexScale();
            } else if (bridge) {
                // Bridge elevation locates the deck; it never stretches its thickness or raised rails.
                // Each GLB is a complete joined deck, already clipped to the actual hex exit edges.
                float scale = BoardGeometry.hexScale();
                instance.transform.setToTranslation(px, py, base + feature.elevation() * BoardGeometry.level())
                      .scale(scale, scale, scale);
            } else {
                // Trees and structures retain inspectable proportions; their actual Z extent fits the rules' height.
                // Fuel tanks and industrial structures also use the building catalog, without building cutaways.
                boolean fitHeight = feature.kind() == BoardScene.FeatureKind.TREE
                      || feature.kind() == BoardScene.FeatureKind.BUILDING || feature.asset().startsWith("buildings/");
                float sourceHeight = fitHeight ? bounds.getDepth() : 1;
                float verticalScale = building == null ? feature.height() * BoardGeometry.level() / sourceHeight
                      : BoardGeometry.level() / GpuBuilding.LEVEL_HEIGHT;
                // Understory meshes are authored as shrubs: preserve their proportions when fitting one level.
                float horizontalScale = feature.asset().startsWith("foliage-") ? verticalScale
                      : feature.scale() * BoardGeometry.hexScale();
                instance.transform.setToTranslation(px, py,
                      base + feature.elevation() * BoardGeometry.level())
                      .rotate(Vector3.Z, feature.rotation())
                      .scale(horizontalScale, horizontalScale, verticalScale);
            }
            bounds.mul(instance.transform);
            chunk.props.add(new Prop(tile.coords(), instance, bounds,
                  feature.kind() == BoardScene.FeatureKind.TREE ? feature.asset() : null,
                  feature.kind() == BoardScene.FeatureKind.LIMB, building));
            if (feature.kind() == BoardScene.FeatureKind.BUILDING) {
                Model interior = building == null ? assets.interior(feature.asset(), Math.round(feature.height()))
                      : building.interior();
                ModelInstance struts = new ModelInstance(interior, "struts");
                struts.transform.set(instance.transform);
                chunk.struts.add(struts);
                ModelInstance floors = new ModelInstance(interior, "floors");
                floors.transform.set(instance.transform);
                chunk.props.add(new Prop(tile.coords(), floors,
                      floors.calculateBoundingBox(new BoundingBox()).mul(floors.transform), null, false));
            }
            chunk.bounds.ext(bounds);
        }
    }

    private final class ChunkBuild {
        final Chunk chunk = new Chunk();
        final Chunk source;
        final BoundingBox bounds = new BoundingBox().inf();
        float scatterDiameter;
        final BoardScene scene;
        final Prepared prepared;
        final TerrainLoadProgress progress;
        final int x, y, width, height;
        final float floor;
        final List<Layer> layers = List.of(new Layer(), new Layer(), new Layer(), new Layer(), new Layer());
        Material scatterMaterial;
        final Map<String, LiquidSurface> animations = new HashMap<>();
        final Map<Material, Material> reusedMaterials = new java.util.IdentityHashMap<>();
        List<MeshBatch> meshes;
        int collected, uploadLayer, uploadBuffer;

        ChunkBuild(BoardScene scene, int x, int y, float floor, TerrainLod lod, Prepared prepared, Chunk source,
              TerrainLoadProgress progress) {
            this.scene = scene;
            this.source = source;
            this.x = x;
            this.y = y;
            this.floor = floor;
            this.prepared = prepared;
            this.progress = progress;
            width = Math.min(CHUNK_SIZE, scene.width() - x);
            height = Math.min(CHUNK_SIZE, scene.height() - y);
            chunk.lod = lod;
            Map<BoardScene.Pixels, BoardScene.Pixels> masks = new HashMap<>();
            prepared.roads().values().forEach(patches -> patches.forEach(p -> masks.put(p.mask().pixels(), p.mask().pixels())));
            if (source != null) {
                for (Coords coords : prepared.reused()) {
                    for (TileRange range : source.tileMeshes.get(coords).ranges) {
                        var mask = range.material().get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE);
                        if (mask != null) { masks.put(mask.data.pixels(), mask.data.pixels()); }
                    }
                }
            }
            try {
                chunk.waterField = prepared.water() == null ? null : prepared.water().upload();
                progress.advance();
                chunk.lavaField = prepared.lava() == null ? null : prepared.lava().upload(false);
                progress.advance();
                chunk.roadMasks.update(masks);
                progress.advance();
            } catch (RuntimeException | Error failure) {
                chunk.dispose();
                throw failure;
            }
            if (source != null) {
                for (LiquidSurface animation : source.liquidMaterials) { animations.put(animation.material().id, animation); }
            }
            progress.begin("models", width * height);
        }

        boolean collectUntil(long deadline) {
            do {
                Coords coords = new Coords(x + collected / height, y + collected % height);
                layers.forEach(layer -> layer.owner = coords);
                TileMesh tile = new TileMesh();
                chunk.tileMeshes.put(coords, tile);
                if (prepared.reused().contains(coords)) {
                    TileMesh previous = source.tileMeshes.get(coords);
                    tile.support = previous.support;
                    tile.plants = previous.plants;
                    tile.bridgeShape = previous.bridgeShape;
                    for (TileRange range : previous.ranges) {
                        Material material = reusedMaterials.computeIfAbsent(range.material(), original -> {
                            Material copy = new Material(original);
                            GpuWaterShader water = copy.get(GpuWaterShader.class, GpuWaterShader.TYPE);
                            if (water != null) { copy.set(water.withField(chunk.waterField)); }
                            GpuMagmaShader magma = copy.get(GpuMagmaShader.class, GpuMagmaShader.TYPE);
                            if (magma != null && scene.tile(coords).liquid().molten()) {
                                copy.set(magma.withField(chunk.lavaField));
                            }
                            var mask = copy.get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE);
                            if (mask != null) { copy.set(mask.withRegion(chunk.roadMasks.region(mask.data.pixels()))); }
                            return copy;
                        });
                        layers.get(range.layer()).reuse(range, material);
                    }
                    // Instances have mutable fading/LoD state, while their authored models remain asset-owned.
                    for (Prop prop : previous.props) {
                        tile.props.add(new Prop(coords, new ModelInstance(prop.instance()), prop.bounds(),
                              prop.treeAsset, prop.hardSurface, prop.building));
                    }
                    for (ModelInstance instance : previous.struts) { tile.struts.add(new ModelInstance(instance)); }
                    chunk.props.addAll(tile.props);
                    chunk.struts.addAll(tile.struts);
                    var cached = cpuGeometry.get(previous);
                    if (cached != null) { rememberGeometry(tile, cached); }
                    tile.bounds.set(previous.bounds);
                    tile.scatterDiameter = previous.scatterDiameter;
                } else {
                    chunk.bounds.inf();
                    chunk.scatterDiameter = 0;
                    int props = chunk.props.size(), struts = chunk.struts.size();
                    collectTile(this, scene.tile(coords));
                    rememberGeometry(tile, new CpuGeometry(prepared.topography().get(coords),
                          scene.tile(coords).liquid().present() ? prepared.surfaces().get(coords).waterGeometry() : null));
                    tile.plants = prepared.plants().get(coords);
                    tile.props.addAll(chunk.props.subList(props, chunk.props.size()));
                    tile.struts.addAll(chunk.struts.subList(struts, chunk.struts.size()));
                    tile.bounds.set(chunk.bounds);
                    tile.scatterDiameter = chunk.scatterDiameter;
                }
                bounds.ext(tile.bounds);
                scatterDiameter = Math.max(scatterDiameter, tile.scatterDiameter);
                collected++;
                progress.advance();
            } while (collected < width * height && System.nanoTime() < deadline);
            if (collected == width * height) {
                progress.begin("meshes", layers.stream().mapToInt(layer -> layer.geometry.values().stream()
                      .mapToInt(List::size).sum()).sum());
            }
            return collected == width * height;
        }

        void prepare(Runnable check) {
            meshes = layers.stream().map(layer -> layer.prepare(check, progress::advance)).toList();
            progress.begin("upload", meshes.stream().mapToInt(mesh -> mesh.buffers.size()).sum());
        }

        boolean uploadUntil(long deadline) {
            List<List<ModelInstance>> targets = List.of(chunk.opaque, chunk.scatter, chunk.overlays, chunk.flatTrees, chunk.water);
            while (uploadLayer < meshes.size()) {
                MeshBatch batch = meshes.get(uploadLayer);
                while (uploadBuffer < batch.buffers.size()) {
                    batch.upload(batch.buffers.get(uploadBuffer++), targets.get(uploadLayer), chunk.tileMeshes, uploadLayer);
                    progress.advance();
                    if (System.nanoTime() >= deadline) { return false; }
                }
                uploadBuffer = 0;
                uploadLayer++;
            }
            progress.begin("finishing", 1);
            return true;
        }

        Chunk finish() {
            chunk.bounds.set(bounds);
            chunk.scatterDiameter = scatterDiameter;
            chunk.terrainRenderables = GpuTerrainDepth.snapshot(chunk.opaque);
            chunk.scatterRenderables = GpuTerrainDepth.snapshot(chunk.scatter);
            chunk.waterRenderables = GpuTerrainDepth.snapshot(chunk.water);
            chunk.depthTerrain = new GpuTerrainDepth(chunk.opaque);
            if (chunk.waterField != null) { chunk.waterField.finish(); }
            if (chunk.waterField != null) {
                float waveMargin = BoardRelief.metres(4);
                Vector3 low = new Vector3(chunk.bounds.min).sub(waveMargin, waveMargin, waveMargin);
                Vector3 high = new Vector3(chunk.bounds.max).add(waveMargin, waveMargin, waveMargin);
                chunk.bounds.ext(low).ext(high);
            }
            if (chunk.lavaField != null) { chunk.lavaField.finish(); }
            // The floating markings remain visible when only their raised edge enters the viewport.
            chunk.bounds.ext(chunk.bounds.max.x, chunk.bounds.max.y, chunk.bounds.max.z + BoardTacticalGeometry.HEX_PLANE_CLEARANCE);
            buildMarkings(scene, chunk, x, y);
            // ModelInstance copies materials; animate those owned by the rendered instances.
            for (List<ModelInstance> layer : List.of(chunk.opaque, chunk.water)) {
                for (ModelInstance instance : layer) {
                    for (Material material : instance.materials) {
                        LiquidSurface animation = animations.get(material.id);
                        if (animation != null) {
                            chunk.liquidMaterials.add(new LiquidSurface(material, animation.source(), animation.falling(), animation.current()));
                        }
                    }
                }
            }
            for (Prop prop : chunk.props) {
                if (prop.building != null) { chunk.sharedInteriors.add(prop.building.interior()); }
                if (prop.tree()) {
                    chunk.treeDiameter = Math.max(chunk.treeDiameter, prop.treeDiameter);
                    chunk.stand.add(prop.treeAsset, prop.instance().transform);
                } else {
                    chunk.cutaways.add(prop);
                }
            }
            chunk.cacheProps();
            progress.advance();
            detailPixelsPerUnit = Float.NaN;
            return chunk;
        }
    }

    private void buildMarkings(BoardScene scene, Chunk chunk, int startX, int startY) {
        chunk.tactical.forEach(instance -> instance.model.dispose());
        chunk.tactical.clear();
        Layer marks = new Layer();
        for (int x = startX; x < Math.min(scene.width(), startX + CHUNK_SIZE); x++) {
            for (int y = startY; y < Math.min(scene.height(), startY + CHUNK_SIZE); y++) {
                BoardScene.Tile tile = scene.tile(new Coords(x, y));
                if (tile.tactical() == null) {
                    continue;
                }
                TextureRegion art = tactical.region(tile.coords());
                Material mark = material(art.getTexture(), true);
                float z = BoardTacticalGeometry.floatingZ(scene, tile.coords());
                // The shared blended material tests opaque depth without hiding later annotations.
                marks.add(mark, mesh -> markingHex(mesh, tile.coords(), art, z));
            }
        }
        marks.finish(chunk.tactical);
    }

    /** Finished rendering triangles, also used to drape deployment borders and other native overlays. */
    BoardTacticalGeometry.Surface tacticalSurface(Coords coords) {
        if (coverScene == null || coverScene.tile(coords) == null) { return null; }
        Chunk chunk = chunks.get(coords.getX() / CHUNK_SIZE * chunkRows + coords.getY() / CHUNK_SIZE);
        return installedSettings.call(() -> tacticalSurface(coverScene, chunk, coords, floor));
    }

    /** Ground cover of the installed tile, planted on its support at its chunk's detail; null when it grows none. */
    BoardPlants planted(Coords coords) {
        if (coverScene == null || coverScene.tile(coords) == null) { return null; }
        return chunks.get(coords.getX() / CHUNK_SIZE * chunkRows + coords.getY() / CHUNK_SIZE).tileMeshes.get(coords).plants;
    }

    /** Borrow a specific chunk's support, including during construction or while restoring a cached detail level. */
    private BoardTacticalGeometry.Surface tacticalSurface(BoardScene scene, Chunk chunk, Coords coords, float bottom) {
        TileMesh tile = chunk.tileMeshes.get(coords);
        BoardTacticalGeometry.Surface retained = tile.support == null ? null : tile.support.get();
        if (retained != null) { return retained; }
        CpuGeometry cached = cpuGeometry.get(tile);
        if (cached == null) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(coords), chunk.lod);
            cached = new CpuGeometry(BoardTacticalGeometry.Surface.of(surface, scene, bottom),
                  surface.tile.liquid().present() ? surface.waterGeometry() : null);
            rememberGeometry(tile, cached);
        }
        return cached.tactical();
    }

    /** Render-thread-owned LRU; it retains four chunks' worth of exact geometry, independently of board size. */
    private void rememberGeometry(TileMesh tile, CpuGeometry geometry) {
        tile.support = new WeakReference<>(geometry.tactical());
        cpuGeometry.put(tile, geometry);
        if (cpuGeometry.size() > 4 * CHUNK_SIZE * CHUNK_SIZE) {
            cpuGeometry.remove(cpuGeometry.keySet().iterator().next());
        }
    }

    /** Camera input borrows the installed mesh snapshot, including between receipt and installation of a new board. */
    void moveCamera(Vector3 eye, Vector3 movement, float radius) {
        if (coverScene != null) {
            installedSettings.run(() -> BoardCameraCollision.move(coverScene, this::tacticalSurface, eye, movement, radius));
        }
    }

    /**
     * Sculpted tops, cliffs and rock formations share a material per surface family. Shared vertices carry the
     * canonical normal, occlusion and material masks across mesh boundaries.
     */
    private record SculptPlan(List<BoardSurface.Face> walls, List<BoardSurface.Face> ground,
          List<BoardSurface.Face> formed, List<BoardSurface.Face> bed,
          Map<Vector3, Vector3> bedNormals,
          Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> blended) { }

    private static SculptPlan prepareSculpt(BoardScene scene, BoardScene.Tile tile, BoardSurface surface, float floor,
          TerrainLod lod, Map<Coords, BoardSurface> surfaces) {
        List<BoardSurface.Face> walls = surface.walls(scene, floor);
        boolean liquid = tile.liquid().present();
        List<BoardSurface.Face> ground = new ArrayList<>();
        List<BoardSurface.Face> formed = new ArrayList<>(walls);
        List<BoardSurface.Face> bed = new ArrayList<>();
        for (BoardSurface.Face face : surface.faces) {
            // A water hex's banks are sculpted ground whatever its artwork; its ice keeps the artwork.
            if (face.finish() == BoardSurface.Finish.ROUGH) { continue; }
            if (face.finish() == BoardSurface.Finish.ICE) { continue; }
            boolean art = face.finish() == BoardSurface.Finish.ICE
                  || face.finish() == BoardSurface.Finish.TOP && (!tile.detailedGround() || tile.liquid().volcanic()) && !liquid;
            (face.finish() == BoardSurface.Finish.BED ? bed : art ? ground : formed).add(face);
        }
        // Keep canonical lighting across LoDs and material splits, including exposed bars at bank junctions.
        Map<Vector3, Vector3> bedNormals = wallNormals(bed);
        bed = surface.renderBed(bed);
        Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> groups = new LinkedHashMap<>();
        float spacing = BoardRelief.metres(lod == TerrainLod.DISTANT ? 8 : lod == TerrainLod.COARSE ? 4 : 2);
        if (BoardSurfaceBlend.cliffBoundary(scene, tile)) {
            if (tile.liquid().volcanic() && tile.detailedGround()) {
                formed.addAll(ground);
                ground.clear();
            }
            List<BoardSurface.Face> blended = new ArrayList<>();
            formed.removeIf(face -> {
                var a = surface.relief.shade(face.a());
                var b = surface.relief.shade(face.b());
                var c = surface.relief.shade(face.c());
                boolean cover = a != null && b != null && c != null
                      && (a.kind() == BoardRelief.Kind.GROUND || a.kind() == BoardRelief.Kind.CLIFF
                            || a.kind() == BoardRelief.Kind.SUBMERGED_CLIFF)
                      && a.kind() == b.kind() && a.kind() == c.kind();
                if (cover) { blended.add(face); }
                return cover;
            });
            var waters = coveringWaters(scene, surface, surfaces);
            for (var face : blended) {
                boolean cliff = surface.relief.shade(face.a()).kind() != BoardRelief.Kind.GROUND;
                coveredPolygons(surface, face, waters, polygon -> GpuSurfaceBlend.appendPolygon(groups, BoardSurfaceBlend.family(tile),
                      polygon, p -> cliff ? BoardSurfaceBlend.sampleCliff(scene, tile, p.x, p.y, p.z)
                            : BoardSurfaceBlend.sample(scene, tile, p.x, p.y, p.z), spacing));
            }
        }
        if (openWater(tile) != null) {
            List<BoardSurface> waters = null;
            for (int edge = 0; edge < 6; edge++) {
                var land = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
                if (!BoardSurfaceBlend.boundary(scene, land)) { continue; }
                List<BoardSurface.Face> blended = new ArrayList<>();
                int bankEdge = edge;
                formed.removeIf(face -> {
                    var a = surface.relief.shade(face.a());
                    var b = surface.relief.shade(face.b());
                    var c = surface.relief.shade(face.c());
                    boolean cover = face.landEdge() == bankEdge && a != null && b != null && c != null
                          && (a.kind() == BoardRelief.Kind.GROUND || a.kind() == BoardRelief.Kind.SUBMERGED_CLIFF)
                          && b.kind() == a.kind() && c.kind() == a.kind();
                    if (cover) { blended.add(face); }
                    return cover;
                });
                List<BoardSurface.Face> bars = new ArrayList<>();
                bed.removeIf(face -> {
                    if (face.landEdge() != bankEdge) { return false; }
                    bars.add(face);
                    return true;
                });
                if (blended.isEmpty() && bars.isEmpty()) { continue; }
                if (waters == null) { waters = coveringWaters(scene, surface, surfaces); }
                for (var face : blended) {
                    coveredPolygons(surface, face, waters, polygon -> GpuSurfaceBlend.appendPolygon(groups,
                          land.surface(), polygon, p -> BoardSurfaceBlend.sample(scene, land, p.x, p.y, p.z), spacing));
                }
                for (var face : bars) {
                    coveredPolygons(face, waters, p -> {
                        var shade = surface.relief.shade(p);
                        return shade != null ? sculptVertex(p, shade, Float.NaN, surface)
                              : vertex(p, bedNormals.get(p), 99, 99,
                                    new Color(1, (tile.elevation() + 64) / 255f, 0, .3f));
                    }, polygon -> GpuSurfaceBlend.appendPolygon(groups, land.surface(), polygon,
                          p -> BoardSurfaceBlend.sample(scene, land, p.x, p.y, p.z), spacing));
                }
            }
        }
        return new SculptPlan(walls, ground, formed, bed, bedNormals, groups);
    }

    private void sculpt(Layer solid, Layer overlay, Chunk chunk, BoardScene scene, BoardScene.Tile tile,
          BoardSurface surface, TextureRegion top, Map<Coords, BoardSurface> surfaces, SculptPlan plan, Material magma) {
        List<BoardSurface.Face> walls = plan.walls(), ground = plan.ground(), formed = plan.formed();
        List<BoardSurface.Face> bed = plan.bed();
        // A water hex's ground carries its water's palette, so the shader wets it and tints it below the waterline.
        float shore = tile.liquid().present() ? GpuWaterShader.palette(tile.liquid()) : Float.NaN;
        blended(solid, plan.blended(), scene, tile);
        for (var group : byFamily(surface, formed).entrySet()) {
            Material formedMaterial = magma != null ? magma : sculptMaterial(group.getKey());
            // A frozen lake's banks below frozen land carry that land's ice, so one sheet spans the shore.
            boolean frozenLake = tile.frozen() && tile.liquid().present();
            List<BoardSurface.Face> iced = group.getValue().stream()
                  .filter(face -> frozenLake && frozenLand(scene, tile, face)).toList();
            List<BoardSurface.Face> plain = iced.isEmpty() ? group.getValue()
                  : group.getValue().stream().filter(face -> !frozenLand(scene, tile, face)).toList();
            if (!plain.isEmpty()) {
                solid.addTriangles(tile.liquid().present() ? formedMaterial : GpuIceShader.ground(formedMaterial, scene, tile),
                      mesh -> sculptedFaces(mesh, surface, plain, shore, scene, surfaces));
            }
            if (!iced.isEmpty()) {
                solid.addTriangles(GpuIceShader.land(formedMaterial),
                      mesh -> sculptedFaces(mesh, surface, iced, shore, scene, surfaces));
            }
        }
        for (var group : byFamily(surface, bed).entrySet()) {
            solid.addTriangles(sculptMaterial(group.getKey()),
                  mesh -> bedFaces(mesh, surface, group.getValue(), shore, plan.bedNormals()));
        }
        if (!ground.isEmpty()) {
            // Special artwork keeps its own texture on the sculpted outline.
            if (magma != null) {
                solid.addTriangles(magma, mesh -> sculptedFaces(mesh, surface, ground, shore, scene, surfaces));
            } else {
                solid.add(groundMaterial(top.getTexture(), scene, tile),
                      mesh -> ground.forEach(face -> surface(mesh, tile.coords(), face, top, 0)));
            }
        }
        for (BoardSurface.Face face : surface.faces) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        for (BoardSurface.Face face : walls) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        if (decals(tile) != null) {
            TextureRegion art = decals.region(tile.coords());
            Material decal = material(art.getTexture(), true);
            overlay.add(decal, mesh -> {
                for (BoardSurface.Face face : surface.faces) {
                    if (face.finish() == BoardSurface.Finish.TOP) { surface(mesh, tile.coords(), face, art, 0.08f); }
                }
            });
        }
    }

    /** Use the ordinary terrain materials and chunk batches; the shell has no per-bridge GPU resource. */
    private void naturalBridge(Layer solid, BoardBridge.Shape shape) {
        float metre = BoardRelief.metres(1);
        solid.addTriangles(sculptMaterial(shape.surface()), meshes -> {
            for (var face : shape.facets()) {
                var points = new MeshPartBuilder.VertexInfo[3];
                int i = 0;
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    boolean top = face.part() == BoardBridge.Part.TOP;
                    boolean soffit = face.part() == BoardBridge.Part.SOFFIT;
                    float above = (p.z - shape.bounds().min.z) / metre;
                    float below = Math.max(0, shape.level() * BoardGeometry.level() - p.z) / metre;
                    var data = new Color(soffit ? .78f : 1, top || soffit ? (shape.level() + 64) / 255f : 1,
                          top ? 0 : soffit ? 1 : .5f, top ? .6f : .55f);
                    points[i++] = vertex(p, face.normal(), top ? Math.max(.02f, 2.5f - below * 6) : above,
                          top ? 99 : below, data);
                }
                meshes.get().triangle(points[0], points[1], points[2]);
            }
        });
    }

    private void bridgeFooting(Layer solid, BoardBridge.Shape shape, Material deck, Material structure) {
        for (boolean top : new boolean[] { true, false }) {
            solid.addTriangles(top ? deck : structure, meshes -> {
                for (var face : shape.facets()) {
                    if ((face.part() == BoardBridge.Part.TOP) != top) { continue; }
                    var points = new MeshPartBuilder.VertexInfo[3];
                    int i = 0;
                    float repeat = BoardGeometry.width() / 5;
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        float u = Math.abs(face.normal().x) > .5f ? p.y : p.x;
                        float v = Math.abs(face.normal().z) > .5f ? -p.y : -p.z;
                        points[i++] = vertex(p, face.normal(), u / repeat, v / repeat, Color.WHITE);
                    }
                    meshes.get().triangle(points[0], points[1], points[2]);
                }
            });
        }
    }

    private void blended(Layer solid, Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> groups) {
        blended(solid, groups, null, null);
    }

    private void blended(Layer solid, Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> groups,
          BoardScene scene, BoardScene.Tile tile) {
        for (var group : groups.entrySet()) {
            var palette = group.getKey();
            boolean boundary = palette.first() != palette.base() || palette.second() != palette.base() || palette.third() != palette.base();
            Material material = boundary ? blendMaterial(palette) : sculptMaterial(palette.base());
            if (tile != null && !tile.liquid().present()) { material = GpuIceShader.ground(material, scene, tile); }
            solid.addTriangles(material, meshes -> {
                for (var triangle : group.getValue()) {
                    MeshPartBuilder mesh = meshes.get();
                    if (boundary) { triangle.write(mesh, palette); }
                    else { mesh.triangle(triangle.a().vertex(), triangle.b().vertex(), triangle.c().vertex()); }
                }
            });
        }
    }

    private void roads(Layer overlay, List<RoadPatch> prepared, BoardSurface surface,
          GpuTextures<BoardScene.Pixels> masks, BoardScene scene, BoardScene.Tile tile) {
        TextureArray roadMaps = assets.roadArray();
        for (var preparedPatch : prepared) {
            var patch = preparedPatch.patch();
            var mask = new GpuRoads.Mask(masks.region(preparedPatch.mask().pixels()), preparedPatch.mask());
            String road = patch.texture().startsWith("roads/") ? patch.texture().substring("roads/".length()) : null;
            int maps = road != null ? GpuRoads.MATERIALS.indexOf(road) : 3 + SCULPT_LAYERS.indexOf(patch.texture());
            Material material;
            float coat = Color.WHITE.toFloatBits();
            if (roadMaps != null && maps >= (road != null ? 0 : 3)) {
                // Every coat of the chunk shares this material; its own maps, finish and response are in its vertices.
                // The mask page stands in as the diffuse texture only to give the coat its texture coordinates.
                material = material(mask.textureDescription.texture, true);
                material.set(new Ground(.65f));
                material.set(new GpuRoads.Coats(roadMaps, assets.sculptArray(SCULPT_LAYERS)));
                coat = GpuRoads.coat(maps, patch, "dirt".equals(road) ? .15f : .65f);
            } else if (road != null) {
                material = roadMaterial(road, true);
            } else {
                var sculpt = assets.sculpt(patch.texture());
                material = material(sculpt.color(), true);
                material.set(TextureAttribute.createNormal(sculpt.normal()));
                material.set(new Ground(.65f));
            }
            material.set(GpuRoads.attribute(patch));
            material.set(mask);
            material = GpuIceShader.road(material, scene, tile);
            float vertexCoat = coat;
            overlay.addTriangles(material, meshes -> GpuRoads.write(meshes, preparedPatch.triangles(), patch, mask, surface,
                  preparedPatch.flat(), vertexCoat));
        }
    }

    private Material roadMaterial(String name, boolean blended) {
        var maps = assets.road(name);
        Material material = material(maps.color(), blended);
        if (maps.normal() != null) { material.set(TextureAttribute.createNormal(maps.normal())); }
        if (maps.surface() != null) { material.set(new GpuRoads.Maps(maps.surface())); }
        material.set(new Ground(name.equals("dirt") ? .15f : .65f));
        if (name.equals("dirt")) { material.set(new GpuRoads.Soil()); }
        return material;
    }

    private static Map<BoardScene.Surface, List<BoardSurface.Face>> byFamily(BoardSurface surface,
          List<BoardSurface.Face> faces) {
        Map<BoardScene.Surface, List<BoardSurface.Face>> groups = new EnumMap<>(BoardScene.Surface.class);
        for (BoardSurface.Face face : faces) {
            groups.computeIfAbsent(surface.family(face), key -> new ArrayList<>()).add(face);
        }
        return groups;
    }

    /** A water hex's bed, smoothly shaded over shared vertices; shore is its water's palette. */
    private static void bedFaces(Supplier<MeshPartBuilder> triangles, BoardSurface surface,
          List<BoardSurface.Face> bed, float shore, Map<Vector3, Vector3> normals) {
        Map<Vector3, Short> indices = new HashMap<>();
        MeshPartBuilder previous = null;
        Function<Vector3, Short> shared = null;
        for (BoardSurface.Face face : bed) {
            MeshPartBuilder mesh = triangles.get();
            if (mesh != previous) {
                indices.clear();
                shared = p -> indices.computeIfAbsent(p, key -> {
                    BoardRelief.Shade bank = surface.relief.shade(key);
                    return mesh.vertex(bank == null
                          ? vertex(key, normals.get(key), 99, 99,
                                waterColor(1, surface.waterHeight(key.x, key.y), shoreTint(shore, 0)))
                          : sculptVertex(key, bank, shore, surface));
                });
                previous = mesh;
            }
            mesh.triangle(shared.apply(face.a()), shared.apply(face.b()), shared.apply(face.c()));
        }
    }

    private Material sculptMaterial(BoardScene.Surface family) {
        // The diffuse slot only enables the default vertex shader's UV output; the sculpt maps are bound below.
        Material material = new Material("sculpt-" + family, TextureAttribute.createDiffuse(rainNoise),
              IntAttribute.createCullFace(GL20.GL_BACK));
        material.set(new Sculpt(family.ordinal()), new Ground(family == BoardScene.Surface.SNOW ? -1
              : groundResponse(family)));
        float[] tiles = new float[4];
        for (int layer = 0; layer < 4; layer++) {
            GpuAssets.Sculpt set = assets.sculpt(SCULPT_MATERIALS[family.ordinal()][layer]);
            material.set(new SculptMap(SculptMap.TYPES[2 * layer], set.color()),
                  new SculptMap(SculptMap.TYPES[2 * layer + 1], set.normal()));
            tiles[layer] = set.tile();
        }
        material.set(new SculptTiles(tiles));
        return material;
    }

    private Material sculptMaterial(int family) {
        if (family < BoardSurfaceBlend.CRUST) { return sculptMaterial(BoardScene.Surface.values()[family]); }
        Material material = sculptMaterial(BoardScene.Surface.ROCK);
        Material volcanic = GpuMagmaShader.material(assets,
              family == BoardSurfaceBlend.BANK ? GpuMagmaShader.BANK : GpuMagmaShader.CRUST, null);
        if (volcanic != null) {
            for (Attribute attribute : volcanic) { material.set(attribute.copy()); }
            material.set(new Sculpt(family), new Ground(-1));
        }
        return material;
    }

    private Material blendMaterial(GpuSurfaceBlend.Palette palette) {
        Material material = sculptMaterial(palette.base());
        var families = List.of(palette.base(), palette.first(), palette.second(), palette.third());
        float[] ids = new float[4], responses = new float[4];
        float[][] tiles = new float[4][4], layers = new float[4][4];
        for (int i = 0; i < families.size(); i++) {
            var family = families.get(i);
            ids[i] = family;
            var ordinary = family >= BoardSurfaceBlend.CRUST ? BoardScene.Surface.ROCK : BoardScene.Surface.values()[family];
            responses[i] = family >= BoardSurfaceBlend.CRUST ? -1 : groundResponse(ordinary);
            for (int role = 0; role < 4; role++) {
                String name = SCULPT_MATERIALS[ordinary.ordinal()][role];
                tiles[i][role] = assets.sculpt(name).tile();
                layers[i][role] = 2 * SCULPT_LAYERS.indexOf(name);
            }
        }
        material.set(new GpuSurfaceBlend(assets.sculptArray(SCULPT_LAYERS), ids, responses, tiles, layers));
        if (palette.volcanic() && !material.has(GpuMagmaShader.TYPE)) {
            Material volcanic = GpuMagmaShader.material(assets, GpuMagmaShader.CRUST, null);
            if (volcanic != null) { for (Attribute attribute : volcanic) { material.set(attribute.copy()); } }
        }
        return material;
    }

    /** Sculpted faces over shared vertices; shore, unless NaN, is the palette of the water hex they belong to. */
    private static void sculptedFaces(Supplier<MeshPartBuilder> triangles, BoardSurface surface, List<BoardSurface.Face> faces,
          float shore, BoardScene scene, Map<Coords, BoardSurface> surfaces) {
        Map<Vector3, Short> indices = new java.util.IdentityHashMap<>();
        MeshPartBuilder previous = null;
        Function<Vector3, Short> vertex = null;
        Map<BoardSurface, List<BoardSurface>> coverage = new java.util.IdentityHashMap<>();
        for (BoardSurface.Face face : faces) {
            BoardSurface water = openWater(surface.tile) == null ? null : surface;
            if (water == null && face.landEdge() >= 0) {
                BoardScene.Tile adjacent = openWater(scene.tile(surface.tile.coords()
                      .translated(BoardGeometry.edgeDirection(face.landEdge()))));
                if (adjacent != null) {
                    water = surfaces.computeIfAbsent(adjacent.coords(), key -> new BoardSurface(scene, adjacent));
                }
            }
            if (water != null) {
                List<BoardSurface> waters = coverage.computeIfAbsent(water, own -> coveringWaters(scene, own, surfaces));
                coveredFace(triangles, surface, face, waters);
                continue;
            }
            MeshPartBuilder mesh = triangles.get();
            if (mesh != previous) {
                // Cached indices belong to the old mesh, including when wet polygons caused the split.
                indices.clear();
                vertex = p -> indices.computeIfAbsent(p,
                      key -> mesh.vertex(sculptVertex(key, surface.relief.shade(key), shore, surface)));
                previous = mesh;
            }
            mesh.triangle(vertex.apply(face.a()), vertex.apply(face.b()), vertex.apply(face.c()));
        }
    }

    private static List<BoardSurface> coveringWaters(BoardScene scene, BoardSurface own, Map<Coords, BoardSurface> surfaces) {
        List<BoardSurface> joined = new ArrayList<>(List.of(own));
        // Rocks and cliff corners can project across a mouth into the next water hex.
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile next = openWater(scene.tile(own.tile.coords().translated(direction)));
            if (next != null) { joined.add(surfaces.computeIfAbsent(next.coords(), key -> new BoardSurface(scene, next))); }
        }
        return joined;
    }

    /** Split at the drawn water triangles, so absorption cannot escape onto an exposed bank or cliff. */
    static void coveredFace(MeshPartBuilder mesh, BoardSurface surface, BoardSurface.Face face,
          List<BoardSurface> waters) {
        coveredFace(() -> mesh, surface, face, waters);
    }

    private static void coveredFace(Supplier<MeshPartBuilder> triangles, BoardSurface surface, BoardSurface.Face face,
          List<BoardSurface> waters) {
        coveredPolygons(surface, face, waters, polygon -> surfacePolygon(triangles, polygon));
    }

    private static void coveredPolygons(BoardSurface surface, BoardSurface.Face face, List<BoardSurface> waters,
          Consumer<List<MeshPartBuilder.VertexInfo>> polygonConsumer) {
        if (openWater(surface.tile) != null && List.of(face.a(), face.b(), face.c()).stream().allMatch(p -> {
            BoardRelief.Shade shade = surface.relief.shade(p);
            return shade != null && shade.kind() == BoardRelief.Kind.SUBMERGED_CLIFF;
        })) {
            // This is the pool's own vertical bed boundary. Its projection lies on (or behind an undercut in)
            // the water outline, so an XY coverage test cannot decide whether it is submerged. Keep the bed's
            // waterline and material mapping; the shader uses height to leave the narrow emerged rim dry.
            float shore = GpuWaterShader.palette(surface.tile.liquid());
            polygonConsumer.accept(List.of(sculptVertex(face.a(), surface.relief.shade(face.a()), shore, surface),
                  sculptVertex(face.b(), surface.relief.shade(face.b()), shore, surface),
                  sculptVertex(face.c(), surface.relief.shade(face.c()), shore, surface)));
            return;
        }
        if (List.of(face.a(), face.b(), face.c()).stream().allMatch(p -> {
            BoardRelief.Shade shade = surface.relief.shade(p);
            return shade != null && shade.kind() == BoardRelief.Kind.CLIFF;
        })) {
            // Exterior walls stand outside the basin. A recess beneath the upper pool's footprint is still solid
            // rock, not an infinitely deep water column. Only lower pools can submerge this side of the cliff;
            // the basin's own submerged walls are handled above.
            waters = waters.stream().filter(water -> water.tile.elevation() < surface.tile.elevation()).toList();
        }
        coveredPolygons(face, waters, p -> sculptVertex(p, surface.relief.shade(p), Float.NaN, surface), polygonConsumer);
    }

    private static void coveredPolygons(BoardSurface.Face face, List<BoardSurface> waters,
          Function<Vector3, MeshPartBuilder.VertexInfo> vertices, Consumer<List<MeshPartBuilder.VertexInfo>> polygonConsumer) {
        List<List<MeshPartBuilder.VertexInfo>> dry = new ArrayList<>();
        dry.add(List.of(vertices.apply(face.a()), vertices.apply(face.b()), vertices.apply(face.c())));
        float minX = Math.min(face.a().x, Math.min(face.b().x, face.c().x));
        float maxX = Math.max(face.a().x, Math.max(face.b().x, face.c().x));
        float minY = Math.min(face.a().y, Math.min(face.b().y, face.c().y));
        float maxY = Math.max(face.a().y, Math.max(face.b().y, face.c().y));
        float minZ = Math.min(face.a().z, Math.min(face.b().z, face.c().z));
        for (BoardSurface water : waters) {
            for (BoardSurface.Face top : water.waterFaces) {
                if (dry.isEmpty()) { break; }
                if (maxX < Math.min(top.a().x, Math.min(top.b().x, top.c().x))
                      || minX > Math.max(top.a().x, Math.max(top.b().x, top.c().x))
                      || maxY < Math.min(top.a().y, Math.min(top.b().y, top.c().y))
                      || minY > Math.max(top.a().y, Math.max(top.b().y, top.c().y))
                      || minZ >= Math.max(top.a().z, Math.max(top.b().z, top.c().z))) { continue; }
                Vector3 normal = new Vector3(top.b()).sub(top.a()).crs(new Vector3(top.c()).sub(top.a())).nor();
                if (normal.z <= .00001f) { continue; }
                List<List<MeshPartBuilder.VertexInfo>> remaining = new ArrayList<>();
                Vector3[] corners = { top.a(), top.b(), top.c() };
                for (List<MeshPartBuilder.VertexInfo> polygon : dry) {
                    List<MeshPartBuilder.VertexInfo> wet = polygon;
                    List<List<MeshPartBuilder.VertexInfo>> outside = new ArrayList<>();
                    for (int edge = 0; edge < 3 && wet.size() >= 3; edge++) {
                        Vector3 a = corners[edge], b = corners[(edge + 1) % 3];
                        wet = splitCovered(wet, a, new Vector3(b.y - a.y, a.x - b.x, 0).nor(), outside);
                    }
                    if (wet.size() >= 3) { wet = splitCovered(wet, top.a(), normal, outside); }
                    if (wet.size() >= 3) {
                        remaining.addAll(outside);
                        List<MeshPartBuilder.VertexInfo> tinted = new ArrayList<>();
                        for (var vertex : wet) {
                            Vector3 p = vertex.position;
                            float height = top.a().z - (normal.x * (p.x - top.a().x) + normal.y * (p.y - top.a().y)) / normal.z;
                            float kind = vertex.color.b;
                            boolean ground = kind < .125f;
                            Color data = waterColor(vertex.color.r, height, shoreTint(GpuWaterShader.palette(water.tile.liquid()),
                                  ground ? (vertex.color.a - .3f) / .1f : 0));
                            if (!ground) { data.b = (224 + 240 * data.b) / 255f; }
                            tinted.add(new MeshPartBuilder.VertexInfo().set(vertex).setCol(data));
                        }
                        polygonConsumer.accept(tinted);
                    } else { remaining.add(polygon); }
                }
                dry = remaining;
            }
        }
        dry.forEach(polygonConsumer);
    }

    /** Keep the half-space below a plane and retain the outside polygon for subsequent water triangles. */
    private static List<MeshPartBuilder.VertexInfo> splitCovered(List<MeshPartBuilder.VertexInfo> polygon,
          Vector3 origin, Vector3 normal, List<List<MeshPartBuilder.VertexInfo>> outside) {
        List<MeshPartBuilder.VertexInfo> inside = new ArrayList<>(), dry = new ArrayList<>();
        var previous = polygon.getLast();
        float before = new Vector3(previous.position).sub(origin).dot(normal);
        for (var point : polygon) {
            float after = new Vector3(point.position).sub(origin).dot(normal);
            if ((before <= 0) != (after <= 0)) {
                var crossing = new MeshPartBuilder.VertexInfo().set(previous).lerp(point, before / (before - after));
                inside.add(crossing);
                dry.add(crossing);
            }
            (after <= 0 ? inside : dry).add(point);
            previous = point;
            before = after;
        }
        if (dry.size() >= 3) { outside.add(dry); }
        return inside;
    }

    private static void surfacePolygon(Supplier<MeshPartBuilder> triangles, List<MeshPartBuilder.VertexInfo> polygon) {
        for (int i = 1; i + 1 < polygon.size(); i++) {
            var a = polygon.getFirst();
            var b = polygon.get(i);
            var c = polygon.get(i + 1);
            if (new Vector3(b.position).sub(a.position).crs(new Vector3(c.position).sub(a.position)).len2() > 1e-8f) {
                triangles.get().triangle(a, b, c);
            }
        }
    }

    private static MeshPartBuilder.VertexInfo sculptVertex(Vector3 p, BoardRelief.Shade shade, float shore, BoardSurface surface) {
        if (shade == null) {
            return vertex(p, Vector3.Z, 0, 0, new Color(1, 64 / 255f, 1, 1));
        }
        if (shade.kind() == BoardRelief.Kind.ROCK && openWater(surface.tile) != null && !Float.isNaN(shore)) {
            // Keep the rock projection and pack water optics in its spare kind range (blue bytes 224..254).
            // Its variation moves into occlusion; alpha now carries the same water palette as the bed.
            Color data = waterColor(shade.occlusion() * (.78f + .22f * shade.tint()),
                  surface.waterHeight(p.x, p.y), shoreTint(shore, 0));
            data.b = (224 + 240 * data.b) / 255f;
            return vertex(p, shade.normal(), shade.rim(), shade.foot(), data);
        }
        float kind = switch (shade.kind()) {
            case GROUND, SUBMERGED_CLIFF -> 0;
            case PLANT -> .25f;
            case CLIFF -> .5f;
            case PIT -> .75f;
            case ROCK -> 1;
        };
        // A cliff carries its rockiness here; everything else its integer game level.
        float level = shade.kind() == BoardRelief.Kind.CLIFF ? Math.clamp(shade.level(), 0, 1)
              : Math.clamp((Math.round(shade.level()) + 64) / 255f, 0, 1);
        boolean submergedCliff = shade.kind() == BoardRelief.Kind.SUBMERGED_CLIFF;
        boolean wet = !Float.isNaN(shore) && (shade.kind() == BoardRelief.Kind.GROUND || submergedCliff);
        // A raised stream cannot drown the bank outside its channel. Below the hex's own waterline, however, the
        // bank is submerged too, including where a beach meets the foot of a cliff.
        float water = wet ? surface.waterHeight(p.x, p.y) : 0;
        if (wet && !submergedCliff) { water = Math.min(water, Math.max(p.z, BoardGeometry.waterZ(surface.tile))); }
        Color data = wet ? waterColor(shade.occlusion(), water,
              shoreTint(shore, (shade.tint() - .3f) / .1f)) : new Color(shade.occlusion(), level, kind, shade.tint());
        return vertex(p, shade.normal(), shade.rim(), shade.foot(), data);
    }

    /** Ground's otherwise unused blue range holds the fractional water level, preserving sloped-bed optics. */
    private static Color waterColor(float occlusion, float waterHeight, float tint) {
        float level = (waterHeight + BoardGeometry.hexScale()) / BoardGeometry.level();
        float whole = (float) Math.floor(level);
        return new Color(occlusion, Math.clamp((whole + 64) / 255f, 0, 1), (level - whole) / 8, tint);
    }

    /**
     * The tint of a water hex's ground: below a quarter, which tells the shader to wet it and tint it under the hex's
     * water. It packs the water's palette with the nearest step's height in levels, up to seven, as terrain-sculpt.frag
     * unpacks them; packed colours keep only even alpha bytes.
     */
    private static float shoreTint(float palette, float levels) {
        return (16 * palette + 1 + 2 * Math.clamp(levels, 0, 7)) / 255f;
    }

    private Material groundMaterial(Texture texture, BoardScene scene, BoardScene.Tile tile) {
        Material material = material(texture, false);
        material.set(new Ground(groundResponse(tile)));
        Texture normal = ground.normal(texture);
        if (normal != null) {
            material.set(TextureAttribute.createNormal(normal));
        }
        return GpuIceShader.ground(material, scene, tile);
    }

    private static Map<Vector3, Vector3> wallNormals(List<BoardSurface.Face> faces) {
        Map<Vector3, Vector3> normals = new HashMap<>();
        for (BoardSurface.Face face : faces) {
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                normals.computeIfAbsent(p, key -> new Vector3()).add(normal);
            }
        }
        normals.values().forEach(Vector3::nor);
        return normals;
    }

    /** The tile itself when it holds open water that can cover a face; frozen, molten and dry tiles do not. */
    private static BoardScene.Tile openWater(BoardScene.Tile tile) {
        return tile != null && tile.liquid().present() && !tile.liquid().molten() && !tile.frozen() ? tile : null;
    }

    /**
     * The water surface keeps its bank outline; the chunk's field supplies depth, bank distance and current per
     * pixel, so only the blended rapids ride on the vertices. Authored GIF water maps its artwork as before.
     */
    private static void waterSurface(MeshPartBuilder mesh, GpuWaterShader.Field field, Coords coords,
          List<BoardSurface.Face> faces, TextureRegion art, Map<Vector3, Vector3> normals) {
        Map<Vector3, Short> indices = new HashMap<>();
        for (BoardSurface.Face face : faces) {
            short a = indices.computeIfAbsent(face.a(), p -> mesh.vertex(waterVertex(field, coords, p, normals.get(p), art)));
            short b = indices.computeIfAbsent(face.b(), p -> mesh.vertex(waterVertex(field, coords, p, normals.get(p), art)));
            short c = indices.computeIfAbsent(face.c(), p -> mesh.vertex(waterVertex(field, coords, p, normals.get(p), art)));
            mesh.triangle(a, b, c);
        }
    }

    /**
     * The board-edge section uses the cut-water material. Green stores its depth below the surface over
     * {@link GpuWaterShader#DEPTH_RANGE} levels.
     */
    private static void waterCut(MeshPartBuilder mesh, float top, BoardSurface.Face face) {
        mesh.triangle(cutVertex(face.a(), top), cutVertex(face.b(), top), cutVertex(face.c(), top));
    }

    private static MeshPartBuilder.VertexInfo cutVertex(Vector3 p, float top) {
        float below = Math.clamp((top - p.z) / (GpuWaterShader.DEPTH_RANGE * BoardGeometry.level()), 0, 1);
        return vertex(p, Vector3.Z, p.x / BoardGeometry.width(), -p.y / BoardGeometry.width(), new Color(0, below, 1, 1));
    }

    private static MeshPartBuilder.VertexInfo waterVertex(GpuWaterShader.Field field, Coords coords, Vector3 p, Vector3 normal,
          TextureRegion art) {
        Color agitation = new Color(field.agitation(p), 0, 0, 1);
        return art == null ? vertex(p, normal, p.x / BoardGeometry.width(), -p.y / BoardGeometry.width(), agitation)
              : topVertex(p, coords, art, agitation).setNor(normal);
    }

    /** How much water film a tile's own exposed material takes; negative excludes snow, ice and water. */
    private static float groundResponse(BoardScene.Tile tile) {
        return tile.liquid().present() || tile.frozen() ? -1 : groundResponse(tile.surface());
    }

    private static float groundResponse(BoardScene.Surface surface) {
        return switch (surface) {
            case SNOW -> -1;
            case SAND -> 0.05f;
            case DIRT -> 0.15f;
            case GRASS -> 0.25f;
            case ROCK -> 0.7f;
            case CONCRETE -> 1;
        };
    }

    private static Material material(Texture texture, boolean blend) {
        Material material = new Material("surface", TextureAttribute.createDiffuse(texture),
              IntAttribute.createCullFace(GL20.GL_NONE));
        if (blend) {
            material.set(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA));
            material.set(new DepthTestAttribute(GL20.GL_LEQUAL, false));
        }
        return material;
    }

    private Material liquidMaterial(BoardScene scene, BoardSurface surface, BoardLiquid.Textures source,
          boolean falling, GpuWaterShader.Field field) {
        BoardLiquid liquid = surface.tile.liquid();
        if (liquid.molten()) {
            Material magma = GpuMagmaShader.material(assets, falling ? GpuMagmaShader.FALL : GpuMagmaShader.LAVA, field);
            if (magma != null) { return magma; }
        }
        boolean procedural = proceduralWater && !liquid.molten();
        Texture texture = procedural ? rainNoise : assets.liquid(source, 0);
        Material material = material(texture, !liquid.molten());
        // Procedural water varies per pixel, not per hex, so every depth and rapids class shares one draw.
        material.id = (falling ? "falls:" : "liquid:") + liquid.kind() + ":"
              + (procedural ? liquid.theme() : source.base() + ":" + source.foam());
        if (liquidShaderAnimation && !procedural) {
            material.set(new GpuLiquidShader.Frame(texture), new FloatAttribute(GpuLiquidShader.Frame.BLEND, 0));
        }
        if (liquid.molten()) {
            material.set(ColorAttribute.createDiffuse(0.35f, 0.35f, 0.35f, 1));
            material.set(TextureAttribute.createEmissive(texture), ColorAttribute.createEmissive(0.65f, 0.65f, 0.65f, 1));
        } else {
            // Premultiplied: the shader adds reflected and scattered light and removes only what the column absorbs.
            material.set(new BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA,
                  procedural ? 1 : falling ? 0.8f : GpuWaterShader.SURFACE_OPACITY));
            GpuWaterShader water = new GpuWaterShader(scene, surface, procedural, falling, field);
            material.set(water);
            if (!water.impacts.isEmpty()) { material.id += ":splash:" + surface.tile.coords(); }
            if (liquid.kind() == BoardLiquid.Kind.HAZARDOUS) {
                material.set(ColorAttribute.createDiffuse(0.4f, 1, 0.12f, 1));
            }
        }
        return material;
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 p, Vector3 normal, float u, float v, Color color) {
        return new MeshPartBuilder.VertexInfo().setPos(p).setNor(normal).setUV(u, v).setCol(color);
    }

    private static MeshPartBuilder.VertexInfo topVertex(Vector3 p, Coords coords, TextureRegion region, Color color) {
        // Sample just inside the artwork's alpha border, without moving the actual geometry.
        float u = 0.5f + (p.x - BoardGeometry.centerX(coords)) / BoardGeometry.width() * BoardRim.GROUND_UV_SCALE;
        float v = 0.5f - (p.y - BoardGeometry.centerY(coords)) / BoardGeometry.height() * BoardRim.GROUND_UV_SCALE;
        return vertex(p, Vector3.Z, region.getU() + u * (region.getU2() - region.getU()),
              region.getV() + v * (region.getV2() - region.getV()), color);
    }

    /** The same flat annotation plane as native shapes; structures can cut through its transparent artwork. */
    private static void markingHex(MeshPartBuilder mesh, Coords coords, TextureRegion region, float z) {
        Vector3 center = BoardGeometry.center(coords, 0);
        center.z = z;
        for (int edge = 0; edge < 6; edge++) {
            Vector3 a = BoardGeometry.corner(coords, 0, edge);
            Vector3 b = BoardGeometry.corner(coords, 0, edge + 1);
            a.z = b.z = center.z;
            mesh.triangle(markingVertex(center, coords, region), markingVertex(a, coords, region),
                  markingVertex(b, coords, region));
        }
    }

    private static MeshPartBuilder.VertexInfo markingVertex(Vector3 point, Coords coords, TextureRegion region) {
        float u = 0.5f + (point.x - BoardGeometry.centerX(coords)) / BoardGeometry.width();
        float v = 0.5f - (point.y - BoardGeometry.centerY(coords)) / BoardGeometry.height();
        return vertex(point, Vector3.Z, region.getU() + u * (region.getU2() - region.getU()),
              region.getV() + v * (region.getV2() - region.getV()), Color.WHITE);
    }

    /** Preserve the full transparent sprite: canopies extend beyond the hex's diagonal edges. */
    private static void foliage(MeshPartBuilder mesh, BoardScene.Tile tile, TextureRegion art) {
        var center = BoardGeometry.center(tile.coords(), 0);
        center.z = BoardGeometry.surfaceZ(tile) + .16f * BoardGeometry.hexScale();
        float halfWidth = BoardGeometry.width() / 2, halfHeight = BoardGeometry.height() / 2;
        mesh.rect(markingVertex(new Vector3(center).add(-halfWidth, -halfHeight, 0), tile.coords(), art),
              markingVertex(new Vector3(center).add(halfWidth, -halfHeight, 0), tile.coords(), art),
              markingVertex(new Vector3(center).add(halfWidth, halfHeight, 0), tile.coords(), art),
              markingVertex(new Vector3(center).add(-halfWidth, halfHeight, 0), tile.coords(), art));
    }

    private static void surface(MeshPartBuilder mesh, Coords coords, BoardSurface.Face face,
          TextureRegion art, float lift) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        mesh.triangle(surfaceVertex(face.a(), coords, normal, art, lift),
              surfaceVertex(face.b(), coords, normal, art, lift), surfaceVertex(face.c(), coords, normal, art, lift));
    }

    /** One flat face of a precast wall; its concrete repeats every three metres along and up the wall. */
    private static void panel(MeshPartBuilder mesh, BoardSurface.Face face) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        float repeat = BoardRelief.metres(3);
        MeshPartBuilder.VertexInfo[] corners = new MeshPartBuilder.VertexInfo[3];
        Vector3[] points = { face.a(), face.b(), face.c() };
        for (int i = 0; i < 3; i++) {
            Vector3 p = points[i];
            float u = Math.abs(normal.z) > .7f ? p.x : Math.abs(normal.y) > Math.abs(normal.x) ? p.x : p.y;
            float v = Math.abs(normal.z) > .7f ? p.y : -p.z;
            corners[i] = vertex(p, normal, u / repeat, v / repeat, Color.WHITE);
        }
        mesh.triangle(corners[0], corners[1], corners[2]);
    }

    private static MeshPartBuilder.VertexInfo surfaceVertex(Vector3 point, Coords coords, Vector3 normal,
          TextureRegion art, float lift) {
        Vector3 raised = new Vector3(point).add(0, 0, lift);
        if (art != null) {
            return topVertex(raised, coords, art, Color.WHITE).setNor(normal);
        }
        float repeat = 96 * BoardGeometry.hexScale();
        float u = Math.abs(normal.z) > 0.7f ? point.x / repeat
              : (Math.abs(normal.y) > Math.abs(normal.x) ? point.x : point.y) / repeat;
        float v = Math.abs(normal.z) > 0.7f ? point.y / repeat : -point.z / repeat;
        return vertex(raised, normal, u, v, Color.WHITE);
    }

    /** Whether a bank face stands above the water below dry, frozen land. Beneath the slab the bank stays bare. */
    private static boolean frozenLand(BoardScene scene, BoardScene.Tile tile, BoardSurface.Face face) {
        if (face.landEdge() < 0 || face.a().z + face.b().z + face.c().z < 3 * BoardGeometry.waterZ(tile)) { return false; }
        BoardScene.Tile land = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(face.landEdge())));
        return land != null && land.frozen() && !land.liquid().present();
    }

    /**
     * A frozen lake as drawn. Vertex alpha is zero on the broken pieces except where they share the slab's own
     * vertices, so the ragged margin fades into broken ice across its width; red is zero on open leads.
     */
    private static void drawnIce(Supplier<MeshPartBuilder> meshes, BoardSurface surface) {
        Set<Vector3> slab = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var face : surface.iceSlab) {
            slab.addAll(List.of(face.a(), face.b(), face.c()));
            iceTriangle(meshes.get(), face, point -> Color.WHITE);
        }
        Color broken = new Color(1, 1, 1, 0), lead = new Color(0, 1, 1, 0);
        for (var face : surface.iceBroken) {
            iceTriangle(meshes.get(), face, point -> slab.contains(point) ? Color.WHITE : broken);
        }
        for (var face : surface.iceLeads) { iceTriangle(meshes.get(), face, point -> lead); }
    }

    private static void iceTriangle(MeshPartBuilder mesh, BoardSurface.Face face, Function<Vector3, Color> colors) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        float repeat = 96 * BoardGeometry.hexScale();
        MeshPartBuilder.VertexInfo[] corners = new MeshPartBuilder.VertexInfo[3];
        Vector3[] points = { face.a(), face.b(), face.c() };
        for (int i = 0; i < 3; i++) {
            corners[i] = vertex(points[i], normal, points[i].x / repeat, points[i].y / repeat, colors.apply(points[i]));
        }
        mesh.triangle(corners[0], corners[1], corners[2]);
    }

    /** Continue the neighboring land's selected artwork across the edge, beneath the existing sand fade. */
    private static void bank(MeshPartBuilder mesh, Coords water, BoardSurface.Face face, Coords land, TextureRegion art) {
        Vector3 a = BoardGeometry.corner(water, 0, face.landEdge());
        Vector3 outward = BoardGeometry.corner(water, 0, face.landEdge() + 1).sub(a).crs(Vector3.Z).nor();
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        mesh.triangle(bankVertex(face.a(), land, art, normal, a, outward),
              bankVertex(face.b(), land, art, normal, a, outward), bankVertex(face.c(), land, art, normal, a, outward));
    }

    private static MeshPartBuilder.VertexInfo bankVertex(Vector3 point, Coords land, TextureRegion art,
          Vector3 normal, Vector3 edge, Vector3 outward) {
        // Mirror only the texture sample into the neighbor: geometry and the waterline stay unchanged.
        Vector3 sample = new Vector3(point).mulAdd(outward, -2 * new Vector3(point).sub(edge).dot(outward));
        return topVertex(sample, land, art, Color.WHITE).setPos(point).setNor(normal);
    }

    private static void shore(MeshPartBuilder mesh, BoardScene.Tile tile, BoardSurface.Face face) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        mesh.triangle(shoreVertex(tile, face.a(), normal), shoreVertex(tile, face.b(), normal),
              shoreVertex(tile, face.c(), normal));
    }

    private static MeshPartBuilder.VertexInfo shoreVertex(BoardScene.Tile tile, Vector3 point, Vector3 normal) {
        float wet = Math.max(0, Math.min(1, (tile.elevation() * BoardGeometry.level() - point.z) / BoardGeometry.hexScale()));
        // Sand appears gradually across the bank and darkens slightly at the waterline.
        float shade = 1 - wet * 0.12f;
        return surfaceVertex(point, tile.coords(), normal, null, 0.035f * BoardGeometry.hexScale())
              .setCol(shade, shade, shade, wet);
    }

    private static void grid(MeshPartBuilder mesh, Coords coords, float z, TextureRegion region) {
        Vector3 center = BoardGeometry.center(coords, 0);
        center.z = z + 0.03f;
        float shade = BoardGeometry.tuning().gridShade();
        Color color = new Color(shade, shade, shade, 1);
        for (int edge = 0; edge < 6; edge++) {
            Vector3 a = BoardGeometry.corner(coords, 0, edge);
            Vector3 b = BoardGeometry.corner(coords, 0, edge + 1);
            a.z = b.z = center.z;
            Vector3 innerA = new Vector3(a).lerp(center, 0.012f);
            Vector3 innerB = new Vector3(b).lerp(center, 0.012f);
            mesh.rect(topVertex(a, coords, region, color), topVertex(b, coords, region, color),
                  topVertex(innerB, coords, region, color), topVertex(innerA, coords, region, color));
        }
    }

    private BoundingBox unitBounds(ModelInstance unit) {
        return frameBounds == null ? UnitBounds.world(unit) : frameBounds.get(unit);
    }

    /** Unit instances already contain the shared animation's current position; hidden units never enter this list. */
    void animate(float delta, List<ModelInstance> units) {
        animate(delta, units, DEFAULT_BUILDING_OPACITY);
    }

    void animate(float delta, List<ModelInstance> units, float buildingAlpha) {
        float nextBuilding = MathUtils.clamp(buildingAlpha, 0, 1);
        boolean changedOpacity = nextBuilding != buildingOpacity;
        buildingOpacity = nextBuilding;
        clock += delta;
        // A shared, continuous gust clock: stronger wind moves faster without rephasing when the slider changes.
        vegetationPhase = (vegetationPhase + delta * (.65f + 3.35f * wind.z)) % MathUtils.PI2;
        if (chunks.stream().anyMatch(chunk -> chunk.lavaField != null)) { lavaOcean.update(clock, wind); }
        else if (lavaOcean.texture() != null) { lavaOcean.dispose(); }
        if (proceduralWater && waterEffects && chunks.stream().anyMatch(chunk -> chunk.waterField != null)) {
            ocean.update(clock, wind);
            waders.update(coverScene, units, units.stream().map(this::unitBounds).toList(), delta);
        }
        List<BoundingBox> occupied = hasCutaways && buildingOpacity < 1
              ? units.stream().map(this::unitBounds).toList() : List.of();
        for (Chunk chunk : chunks) {
            for (LiquidSurface liquid : chunk.liquidMaterials) {
                GpuAssets.Animation<Texture> frames = assets.liquidAnimation(liquid.source());
                int index = frames.index(clock);
                Texture frame = frames.frames().get(index);
                TextureAttribute texture = liquid.material().get(TextureAttribute.class, TextureAttribute.Diffuse);
                TextureAttribute emission = liquid.material().get(TextureAttribute.class, TextureAttribute.Emissive);
                float offsetU = (clock * liquid.current().u()) % 1;
                float offsetV = liquid.falling() ? (clock * (emission == null ? 1 : 0.25f)) % 1 : (clock * liquid.current().v()) % 1;
                texture.textureDescription.texture = frame;
                texture.offsetU = offsetU;
                texture.offsetV = offsetV;
                if (liquidShaderAnimation) {
                    liquid.material().get(TextureAttribute.class, GpuLiquidShader.Frame.TYPE).textureDescription.texture
                          = frames.frames().get((index + 1) % frames.frames().size());
                    liquid.material().get(FloatAttribute.class, GpuLiquidShader.Frame.BLEND).value = frames.blend(clock, index);
                }
                if (emission != null) {
                    emission.textureDescription.texture = frame;
                    emission.offsetU = offsetU;
                    emission.offsetV = offsetV;
                }
            }
            if (chunk.cutaways.isEmpty()) { continue; }
            Set<Coords> occupiedHexes = new HashSet<>();
            for (BoundingBox unit : occupied) {
                if (!chunk.bounds.intersects(unit)) {
                    continue;
                }
                for (Prop prop : chunk.cutaways) {
                    if (prop.bounds().intersects(unit)) {
                        occupiedHexes.add(prop.coords());
                    }
                }
            }
            Set<Prop> faded = new HashSet<>();
            if (!occupiedHexes.isEmpty()) {
                for (Prop prop : chunk.cutaways) {
                    if (occupiedHexes.contains(prop.coords())) {
                        faded.add(prop);
                    }
                }
            }
            boolean changedOccupancy = !faded.equals(chunk.faded);
            if (changedOccupancy || changedOpacity) {
                chunk.faded = Set.copyOf(faded);
                for (Prop prop : chunk.cutaways) {
                    for (Material material : prop.instance().materials) {
                        if (faded.contains(prop)) {
                            BlendingAttribute blend = material.get(BlendingAttribute.class, BlendingAttribute.Type);
                            if (blend == null) {
                                blend = new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                                material.set(blend);
                                material.set(new DepthTestAttribute(GL20.GL_LEQUAL, false));
                                material.set(IntAttribute.createCullFace(GL20.GL_BACK));
                            }
                            blend.opacity = buildingOpacity;
                        } else {
                            material.remove(BlendingAttribute.Type);
                            material.remove(DepthTestAttribute.Type);
                            material.remove(IntAttribute.CullFace);
                        }
                    }
                }
                if (changedOccupancy) {
                    chunk.cacheProps();
                }
            }
        }
    }

    Environment environment() {
        return environment;
    }

    BoundingBox roofBounds(Coords coords) {
        BoundingBox result = null;
        int index = (coords.getX() / CHUNK_SIZE) * chunkRows + coords.getY() / CHUNK_SIZE;
        if (index < 0 || index >= chunks.size()) {
            return null;
        }
        var tile = chunks.get(index).tileMeshes.get(coords);
        if (tile != null && tile.bridgeShape != null) { result = new BoundingBox(tile.bridgeShape.bounds()); }
        for (Prop prop : chunks.get(index).props) {
            if (flatTrees && prop.tree()) { continue; }
            if (prop.coords().equals(coords) && tile != null && tile.bridgeShape != null) {
                result.ext(prop.bounds());
            } else if (prop.coords().equals(coords)
                  && (result == null || prop.bounds().max.z > result.max.z)) {
                result = prop.bounds();
            }
        }
        return result;
    }

    /** Pointer picks pass through foliage; roofs, courtyard openings and walls retain their authored mesh. */
    Coords pick(BoardScene scene, Ray ray) {
        BoardGeometry.Hit hit = selectionHit(scene, ray);
        return hit == null ? null : hit.coords();
    }

    /** Hover and clicks use the board beneath tree/shrub canopies, including when they overhang another hex. */
    BoardGeometry.Hit selectionHit(BoardScene scene, Ray ray) {
        return hit(scene, ray, false);
    }

    /** Physical hits for attack effects still include foliage. */
    BoardGeometry.Hit hit(BoardScene scene, Ray ray) {
        return hit(scene, ray, true);
    }

    private BoardGeometry.Hit hit(BoardScene scene, Ray ray, boolean includeFoliage) {
        if (!ready(scene)) { return null; }
        return installedSettings.call(() -> hitInstalled(presentation(scene), ray, includeFoliage));
    }

    private BoardGeometry.Hit hitInstalled(BoardScene scene, Ray ray, boolean includeFoliage) {
        // The caller has selected the installed coordinate system. A source snapshot that update has not yet
        // received still uses the authoritative-snapshot fallback, as before.
        boolean finished = tiles == scene.tiles();
        Coords result = null;
        float nearest = Float.POSITIVE_INFINITY;
        boolean hardSurface = false;
        List<BoardScene.Tile> candidates = new ArrayList<>();
        Vector3 hit = new Vector3();
        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            if (!Intersector.intersectRayBoundsFast(ray, chunk.bounds)) {
                continue;
            }
            // Reuse render bounds before invoking the shared surface picker; a pointer event must not
            // inspect six neighbors and allocate geometry bounds for every hex of a 40,000-hex board.
            if (finished) { chunkTiles(scene, index, candidates); }
            for (var entry : chunk.tileMeshes.entrySet()) {
                var bridge = entry.getValue().bridgeShape;
                if (bridge == null) { continue; }
                float distance = bridge.hit(ray);
                if (distance < nearest) {
                    nearest = distance;
                    ray.getEndPoint(hit, (float) Math.sqrt(distance));
                    var footprint = BoardGeometry.tile(scene, hit.x, hit.y);
                    result = footprint == null ? entry.getKey() : footprint.coords();
                    hardSurface = false;
                }
            }
            for (Prop prop : chunk.props) {
                if (prop.tree() && (flatTrees || !includeFoliage)) { continue; }
                if (!Intersector.intersectRayBoundsFast(ray, prop.bounds())) {
                    continue;
                }
                Ray local = new Ray(ray.origin, ray.direction).mul(new Matrix4(prop.instance().transform).inv());
                if (prop.building != null) {
                    if (prop.building.hit(local, hit)) {
                        float distance = ray.origin.dst2(hit.mul(prop.instance().transform));
                        if (distance < nearest) {
                            nearest = distance;
                            result = prop.coords();
                            hardSurface = prop.hardSurface;
                        }
                    }
                    continue;
                }
                // Camera zoom changes visual detail, never the hex selected by the same board-space ray.
                List<Vector3> triangles = featureTriangles.computeIfAbsent(prop.pickingModel, GpuTerrain::triangles);
                for (int i = 0; i < triangles.size(); i += 3) {
                    if (Intersector.intersectRayTriangle(local, triangles.get(i), triangles.get(i + 1), triangles.get(i + 2), hit)) {
                        float distance = ray.origin.dst2(hit.mul(prop.instance().transform));
                        if (distance < nearest) {
                            nearest = distance;
                            result = prop.coords();
                            hardSurface = prop.hardSurface;
                        }
                    }
                }
            }
        }
        BoardGeometry.Hit groundHit = finished
              ? BoardGeometry.hit(scene, ray, candidates, floor, this::tacticalSurface)
              : BoardGeometry.hit(scene, ray, scene.tiles(), BoardGeometry.floor(scene), pickingSurfaces);
        return groundHit != null && groundHit.distance() <= nearest ? groundHit
              : result == null ? null : new BoardGeometry.Hit(result, nearest, hardSurface);
    }

    static List<Vector3> triangles(Model model) {
        List<Vector3> result = new ArrayList<>();
        Map<Mesh, float[]> vertices = new HashMap<>();
        Map<Mesh, short[]> indices = new HashMap<>();
        for (Node node : model.nodes) { triangles(node, result, vertices, indices); }
        return result;
    }

    static List<Vector3> triangles(Node node) {
        List<Vector3> result = new ArrayList<>();
        triangles(node, result, new HashMap<>(), new HashMap<>());
        return result;
    }

    private static void triangles(Node node, List<Vector3> result, Map<Mesh, float[]> vertexCache,
          Map<Mesh, short[]> indexCache) {
        for (var part : node.parts) {
            if (!part.enabled || part.meshPart.primitiveType != GL20.GL_TRIANGLES) { continue; }
            Mesh mesh = part.meshPart.mesh;
            int stride = mesh.getVertexSize() / Float.BYTES;
            int offset = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
            float[] vertices = vertexCache.computeIfAbsent(mesh,
                  key -> key.getVertices(new float[key.getNumVertices() * stride]));
            short[] indices = indexCache.computeIfAbsent(mesh, key -> {
                short[] values = new short[key.getNumIndices()];
                key.getIndices(values);
                return values;
            });
            for (int index = part.meshPart.offset; index < part.meshPart.offset + part.meshPart.size; index++) {
                int at = (indices.length == 0 ? index : Short.toUnsignedInt(indices[index])) * stride + offset;
                result.add(new Vector3(vertices[at], vertices[at + 1], vertices[at + 2]).mul(node.globalTransform));
            }
        }
        for (Node child : node.getChildren()) { triangles(child, result, vertexCache, indexCache); }
    }

    void setFlatTrees(boolean enabled) {
        if (flatTrees != enabled) {
            flatTrees = enabled;
            shadowDirty = true;
            detailPixelsPerUnit = Float.NaN;
        }
    }

    /** Opaque world first. Tactical overlays are a separate final pass. */
    void render(Camera camera, boolean drawTactical) {
        shadingPass++;
        if (!drawTactical) {
            updateDetail(camera);
            // Upload before the render context tracks bound texture units for this pass.
            biomes.update(coverScene);
        }
        batch.begin(camera);
        trees.begin(GpuTreeInstances.Pass.COLOUR);
        if (!drawTactical) { propBatch.begin(); terrainPages.begin(camera); }
        int propPageSize = GpuPropBatch.CHUNKS_PER_PAGE;
        int propPageRows = (chunkRows + propPageSize - 1) / propPageSize;
        int terrainPageRows = (chunkRows + GpuTerrainPages.CHUNKS_PER_PAGE - 1) / GpuTerrainPages.CHUNKS_PER_PAGE;
        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            Chunk chunk = chunks.get(chunkIndex);
            boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
            if (!drawTactical) {
                int page = chunkIndex / chunkRows / GpuTerrainPages.CHUNKS_PER_PAGE * terrainPageRows
                      + chunkIndex % chunkRows / GpuTerrainPages.CHUNKS_PER_PAGE;
                terrainPages.add(chunk.terrainRenderables, page, visible);
                int propPage = (chunkIndex / chunkRows / propPageSize) * propPageRows
                      + chunkIndex % chunkRows / propPageSize;
                propBatch.add(chunk.solidProps, propPage, visible);
                propBatch.add(chunk.scatterRenderables, propPage, visible && chunk.scatterVisible);
                if (!flatTrees) { trees.add(chunk.stand, chunk.treeLod, visible); }
            }
            if (!visible) {
                continue;
            }
            if (drawTactical) {
                if (flatTrees) { chunk.flatTrees.forEach(instance -> batch.render(instance)); }
                chunk.tactical.forEach(instance -> batch.render(instance));
            } else {
                trees.add(chunk.sharedProps);
            }
        }
        // Trees first: the opaque sorter keeps this order of shaders, so the ground hidden under crowns fails the depth
        // test instead of being shaded and painted over.
        batch.render(trees, environment);
        if (!drawTactical) { propBatch.render(batch, environment); terrainPages.render(batch, environment); }
        batch.end();
        if (!drawTactical) {
            batch.begin(camera);
            renderCover(camera);
            for (Chunk chunk : chunks) {
                if (camera.frustum.boundsInFrustum(chunk.bounds)) {
                    chunk.overlays.forEach(instance -> batch.render(instance, environment));
                }
            }
            batch.end();
        }
    }

    /** Terrain writes depth first; adding grass must not disable its material/page batching. */
    private void renderCover(Camera camera) {
        if (coverScene == null) { return; }
        List<BoardScene.Tile> candidates = new ArrayList<>();
        BoundingBox guard = new BoundingBox();
        float margin = BoardGeometry.width() * 2;
        for (int index = 0; index < chunks.size(); index++) {
            guard.set(chunks.get(index).bounds);
            guard.min.add(-margin, -margin, 0);
            guard.max.add(margin, margin, 0);
            guard.update();
            if (camera.frustum.boundsInFrustum(guard)) { chunkTiles(coverScene, index, candidates); }
        }
        if (grass && GpuGroundCover.visibleAtScale(camera)) {
            for (ModelInstance instance : groundCover.visible(coverScene, camera, candidates, this::planted)) {
                batch.render(instance, environment);
            }
        }
        for (ModelInstance instance : biomeVegetation.visible(coverScene, camera, candidates, this::planted)) {
            batch.render(instance, environment);
        }
    }

    /** Bytes of shared tree/building geometry and instance data held on the GPU; see {@link GpuTreeInstances#bytes()}. */
    long treeGeometryBytes() {
        return trees.bytes();
    }

    /** Tree/building instance buffer uploads so far; see {@link GpuTreeInstances#uploads()}. */
    long treeInstanceUploads() {
        return trees.uploads();
    }

    /** Water and faded features follow units, with depth testing but no depth writes. */
    void renderTransparent(Camera camera) {
        shadingPass++;
        waterDepth.invalidate();
        updateDetail(camera);
        biomes.update(coverScene);
        boolean hasWater = chunks.stream().anyMatch(chunk -> chunk.waterField != null);
        if (hasWater) { waterExposure.update(coverScene); }
        waterPages.begin();
        int pageRows = (chunkRows + GpuWaterPages.CHUNKS_PER_PAGE - 1) / GpuWaterPages.CHUNKS_PER_PAGE;
        batch.begin(camera);
        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
            int page = index / chunkRows / GpuWaterPages.CHUNKS_PER_PAGE * pageRows
                  + index % chunkRows / GpuWaterPages.CHUNKS_PER_PAGE;
            waterPages.add(chunk.waterRenderables, page, visible);
            if (visible) {
                for (Prop prop : chunk.faded) { batch.render(prop.instance(), environment); }
            }
        }
        batch.end();
        if (!hasWater) {
            // Without open water there is no field atlas or depth prepass, but lake ice still draws. The pages must
            // still take this frame's sources: a page's mesh otherwise keeps the chunks of the last wet frame, whose
            // water models a partial rebuild has already disposed.
            waterPages.prepare();
            batch.begin(camera);
            waterPages.render(batch, environment, false);
            batch.end();
            waterPages.dispose();
            return;
        }
        waterPages.prepare();
        // Waves need to hide the water behind them at grazing angles. This cheap pass shares both vertices and
        // displacement with the colour pass, and writes the nearest wave into a depth target of its own: the colour
        // pass tests against it, fog stops at it, and the scene's depth keeps the beds for outlines and overlays.
        int scene = waterDepth.begin();
        try {
            batch.begin(camera);
            waterPages.render(batch, environment, true);
            batch.end();
        } finally { Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, scene); }
        batch.begin(camera);
        waterPages.render(batch, environment, false);
        batch.end();
    }

    private void updateLight(BoardScene.Light next) {
        light = next;
        shadowDirty = true;
        shadowBounds.inf();
        chunks.forEach(chunk -> shadowBounds.ext(chunk.bounds));
        applyLight();
    }

    void setAtmosphere(BoardAtmosphere.Lighting next) {
        // Color and fog cannot change a shadow's geometry. The light is applied every time: it is cheap, and Color
        // equality rounds to 8 bits, too coarse for night light and wrong above 1.
        shadowDirty |= atmosphere == null || next == null || !atmosphere.direction().equals(next.direction());
        atmosphere = next;
        applyLight();
    }

    /** Live shading preview; changing it needs no geometry, atlas or shadow rebuild. */
    void setNormalMaps(boolean enabled) {
        normalMaps = enabled;
    }

    /** Live grass visibility; disabled cover skips root preparation and drawing without rebuilding the terrain. */
    void setGrass(boolean enabled) {
        grass = enabled;
    }

    /** Live material preview: neutral clay without textures or decoration, for judging sculpted shapes. */
    void setClay(boolean enabled) {
        clay = enabled;
    }

    void setWetness(float wetness) {
        this.wetness = wetness;
    }

    /** This frame's nearest water surface, for fog to stop at; null when the frame drew no water. */
    Texture waterDepth() { return waterDepth.nearest(); }

    void setWind(BoardAtmosphere.Effects effects) {
        double angle = Math.toRadians(effects.windDirection());
        wind.set((float) Math.sin(angle), (float) Math.cos(angle), effects.wind());
    }

    private void chunkTiles(BoardScene scene, int index, List<BoardScene.Tile> destination) {
        int startX = (index / chunkRows) * CHUNK_SIZE;
        int startY = (index % chunkRows) * CHUNK_SIZE;
        for (int x = startX; x < Math.min(startX + CHUNK_SIZE, scene.width()); x++) {
            for (int y = startY; y < Math.min(startY + CHUNK_SIZE, scene.height()); y++) {
                destination.add(scene.tile(new Coords(x, y)));
            }
        }
    }

    /** Isolate authored frame interpolation from surface deformation in native reference comparisons. */
    void setWaterEffects(boolean enabled) { waterEffects = enabled; }

    private void applyLight() {
        if (light == null && atmosphere == null) {
            if (shadow != null) {
                environment.remove(shadow);
                shadow.dispose();
                shadow = null;
                staticShadowValid = false;
            }
            environment.shadowMap = null;
            // Linear light: 0.7 shows albedo as 0.85 would in display space.
            environment.set(ColorAttribute.createAmbientLight(0.7f, 0.7f, 0.7f, 1));
            return;
        }
        // Without an atmosphere, the scene's light direction takes the default atmosphere's light.
        BoardAtmosphere.Lighting lighting = atmosphere != null ? atmosphere
              : BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
        if (!lighting.hasDirectLight()) {
            // Moonless and pitch-black nights retain ambient readability, but have no directional source.
            if (shadow != null) { environment.remove(shadow); }
            environment.shadowMap = null;
            environment.set(ColorAttribute.createAmbientLight(lighting.ambient()));
            return;
        }
        if (shadow == null) {
            shadow = new DirectionalShadowLight(SHADOW_RESOLUTION, SHADOW_RESOLUTION, 1, 1, 1, 2);
            shadowProjectionValid = false;
        }
        if (environment.shadowMap != shadow) {
            environment.add(shadow);
            environment.shadowMap = shadow;
        }
        if (atmosphere == null) {
            shadow.set(lighting.direct(), light.x() * BoardGeometry.hexScale(), light.y() * BoardGeometry.hexScale(),
                  -BoardGeometry.level());
        } else {
            shadow.set(atmosphere.direct(), atmosphere.direction());
        }
        environment.set(ColorAttribute.createAmbientLight(lighting.ambient()));
    }

    /** Camera depth retains the cutaway so atmosphere effects do not hide units behind faded surfaces. */
    void renderDepth(Camera camera, List<ModelInstance> units, ModelBatch pass) {
        updateDetail(camera);
        renderDepth(camera, units, pass, false);
    }

    private void renderDepth(Camera camera, List<ModelInstance> units, ModelBatch pass, boolean shadows) {
        pass.begin(camera);
        trees.begin(shadows ? GpuTreeInstances.Pass.SHADOW : GpuTreeInstances.Pass.DEPTH);
        // A shadow map whose texel spans half a metre or more cannot resolve leaves: its trees cast the shadow of
        // their second decimated level at most. Alpha-tested foliage drawn into such a map at the near level was
        // fragment-bound, 440-510 ms per refit on an Intel Iris Xe over MesaCity 1's woods.
        int coarsest = shadows && camera.viewportWidth / SHADOW_RESOLUTION >= BoardRelief.metres(.5f) ? 2 : 0;
        for (Chunk chunk : chunks) {
            boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
            if (!flatTrees) { trees.add(chunk.stand, Math.max(chunk.treeLod, coarsest), visible); }
            if (visible) {
                pass.render(chunk.depthTerrain);
                if (chunk.scatterVisible) {
                    chunk.scatter.forEach(pass::render);
                }
                pass.render(shadows ? chunk.shadowProps : chunk.solidProps);
                trees.add(shadows ? chunk.sharedShadows : chunk.sharedProps);
            }
        }
        pass.render(trees);
        renderDepthUnits(camera, units, pass);
        pass.end();
    }

    private void renderDepthUnits(Camera camera, List<ModelInstance> units, ModelBatch pass) {
        for (ModelInstance unit : units) {
            if (camera.frustum.boundsInFrustum(unitBounds(unit))) { GpuUnitInstance.renderDepth(pass, unit); }
        }
    }

    void renderShadows(List<ModelInstance> units) {
        renderShadows(null, units);
    }

    void renderShadows(Camera view, List<ModelInstance> units) {
        if (view != null) {
            updateDetail(view);
        }
        if (shadow == null || environment.shadowMap == null) {
            return;
        }
        boolean changed = shadowDirty || !shadowProjectionValid || units.size() != shadowModels.size();
        for (int index = 0; !changed && index < units.size(); index++) {
            changed = units.get(index).model != shadowModels.get(index)
                  || !Arrays.equals(units.get(index).transform.val, shadowTransforms.get(index).val)
                  || shadowPose(units.get(index)) != shadowPoses.get(index);
        }
        boolean viewChanged = (view != null) != shadowViewPresent
              || (view != null && !Arrays.equals(view.combined.val, shadowView.val));
        if (!changed && !viewChanged) {
            return;
        }
        BoundingBox bounds = new BoundingBox(shadowBounds);
        for (ModelInstance unit : units) { bounds.ext(unitBounds(unit)); }
        BoundingBox receivers = view == null ? bounds : BoardCamera.viewportBounds(view, bounds);
        // A view that crosses the horizon or sits below the highest terrain needs most of the board, and a tilt
        // there changes the required fit every frame; each refit redrew every chunk and tree (200-430 ms on an
        // Intel Iris Xe on MesaCity 1). Views needing most of the board share the board-wide fit, whose map is
        // cached, and keep it until they need well under half of it, so a tilt cannot alternate between fits.
        float share = receivers.getWidth() * receivers.getHeight() / Math.max(1e-6f, bounds.getWidth() * bounds.getHeight());
        boolean boardWide = share > (shadowBoardWide ? .4f : .7f);
        shadowBoardWide = boardWide;
        fitShadowToReceivers(boardWide ? bounds : receivers, shadowFit, bounds, shadow.direction);
        shadowViewPresent = view != null;
        if (view != null) { shadowView.set(view.combined); }
        Camera lightCamera = shadow.getCamera();
        boolean projectionChanged = !shadowProjectionValid || !canReuseShadowCamera(lightCamera, shadowFit);
        if (!changed && !projectionChanged) { return; }
        shadowProjectionValid = false;
        if (projectionChanged) {
            lightCamera.position.set(shadowFit.position);
            lightCamera.direction.set(shadowFit.direction);
            lightCamera.up.set(shadowFit.up);
            lightCamera.viewportWidth = shadowFit.viewportWidth * SHADOW_GUARD_SCALE;
            lightCamera.viewportHeight = shadowFit.viewportHeight * SHADOW_GUARD_SCALE;
            lightCamera.near = shadowFit.near;
            lightCamera.far = shadowFit.far;
            snapShadowCamera(lightCamera);
        }
        shadowModels.clear();
        shadowPoses.clear();
        while (shadowTransforms.size() > units.size()) { shadowTransforms.removeLast(); }
        for (int index = 0; index < units.size(); index++) {
            ModelInstance unit = units.get(index);
            shadowModels.add(unit.model);
            if (index == shadowTransforms.size()) { shadowTransforms.add(new Matrix4()); }
            shadowTransforms.get(index).set(unit.transform);
            shadowPoses.add(shadowPose(unit));
        }
        int buffers = GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT;
        if (shadowDirty) { boardShadowValid = false; }
        boolean boardShadowCurrent = boardWide && boardShadowValid && sameFit(boardShadowFit, lightCamera);
        if (shadowDirty || projectionChanged || units.isEmpty() && !staticShadowValid) {
            // Coverage and terrain/light changes pay only the full pass. Populate the cache lazily when
            // units move in a stable view, rather than copying an invalidated cache on every camera frame.
            // Preview/editor maps retain just the original framebuffer, with no copy or extra allocation.
            staticShadowValid = false;
            if (boardShadowCurrent) {
                // A tilt that crosses the horizon or drops below the highest terrain returns to the board-wide fit
                // every time; drawing every chunk and tree again took 220-530 ms on an Intel Iris Xe, a copy a few.
                boardShadow.transfer(shadow.getFrameBuffer(), buffers);
                renderUnitShadows(lightCamera, units);
            } else {
                shadow.begin();
                renderDepth(lightCamera, boardWide ? List.of() : units, depthBatch, true);
                shadow.end();
                if (boardWide) {
                    if (boardShadow == null) {
                        boardShadow = new FrameBuffer(Pixmap.Format.RGBA8888, SHADOW_RESOLUTION, SHADOW_RESOLUTION, true);
                    }
                    shadow.getFrameBuffer().transfer(boardShadow, buffers);
                    remember(boardShadowFit, lightCamera);
                    boardShadowValid = true;
                    renderUnitShadows(lightCamera, units);
                }
            }
        } else {
            if (boardShadowCurrent) {
                boardShadow.transfer(shadow.getFrameBuffer(), buffers);
            } else {
                if (staticShadow == null) {
                    staticShadow = new FrameBuffer(Pixmap.Format.RGBA8888, SHADOW_RESOLUTION, SHADOW_RESOLUTION, true);
                }
                if (!staticShadowValid) {
                    shadow.begin();
                    renderDepth(lightCamera, List.of(), depthBatch, true);
                    shadow.end();
                    shadow.getFrameBuffer().transfer(staticShadow, buffers);
                    staticShadowValid = true;
                } else {
                    // Copy both packed shader depth and the depth attachment; moved units must erase their old
                    // shadow and remain occluded by terrain. Reusing just the color attachment would produce stale
                    // depth.
                    staticShadow.transfer(shadow.getFrameBuffer(), buffers);
                }
            }
            renderUnitShadows(lightCamera, units);
        }
        shadowProjectionValid = true;
        shadowDirty = false;
    }

    /** The units' shadows over a terrain shadow already in the light's framebuffer. */
    private void renderUnitShadows(Camera lightCamera, List<ModelInstance> units) {
        if (units.isEmpty()) { return; }
        shadow.getFrameBuffer().begin();
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(1, 1, SHADOW_RESOLUTION - 2, SHADOW_RESOLUTION - 2);
        depthBatch.begin(lightCamera);
        renderDepthUnits(lightCamera, units, depthBatch);
        depthBatch.end();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        shadow.getFrameBuffer().end();
    }

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

    /** All passes use the viewing camera's detail selection; scatter culling never rebuilds a mesh. */
    private void updateDetail(Camera camera) {
        boolean perspective = camera.projection.val[Matrix4.M33] == 0;
        float pixelsPerUnit = perspective ? Float.NaN : BoardCamera.pixelsPerUnit(camera);
        if (pixelsPerUnit == detailPixelsPerUnit) {
            return;
        }
        detailPixelsPerUnit = pixelsPerUnit;
        Vector3 nearest = new Vector3();
        for (Chunk chunk : chunks) {
            if (perspective) {
                // A chunk uses its nearest possible depth, preserving detail for all of its trees and scatter.
                nearest.set(camera.direction.x >= 0 ? chunk.bounds.min.x : chunk.bounds.max.x,
                      camera.direction.y >= 0 ? chunk.bounds.min.y : chunk.bounds.max.y,
                      camera.direction.z >= 0 ? chunk.bounds.min.z : chunk.bounds.max.z);
                pixelsPerUnit = BoardCamera.pixelsPerUnit(camera, nearest);
            }
            float scatterPixels = chunk.scatterDiameter * pixelsPerUnit;
            // Scatter this small casts a shadow under a texel: its appearance waits for the next refit, like trees'.
            chunk.scatterVisible = scatterPixels >= (chunk.scatterVisible ? 2 : 3);
            boolean buildingsChanged = false;
            for (Prop prop : chunk.cutaways) { buildingsChanged |= prop.buildingDetail(pixelsPerUnit); }
            if (buildingsChanged) {
                // The rebuilt caches serve the next shadow refit; a building's outline is the same at every level,
                // so the map is not redrawn for the change alone (200-400 ms per redraw on an Intel Iris Xe when
                // the fit spans MesaCity 1, and its 233 building hexes change level throughout a tilt).
                GpuPropBatch.disposeMeshes(chunk.shadowPropRenderables);
                chunk.shadowPropRenderables.clear();
                chunk.cacheProps();
            }
            // Largest tree wins: smaller neighbors may retain extra detail, never lose it early.
            if (flatTrees) { continue; }
            // A level change alone does not redraw the shadow map: the next refit, which the camera movement that
            // changed the level brings within a few frames, draws the new level, and until then the map holds the
            // previous level's silhouette, which differs by less than a texel. Redrawing on every change drew every
            // chunk and tree again, 220-530 ms per change on an Intel Iris Xe on MesaCity 1 while orbiting.
            chunk.treeLod = TreeLod.level(chunk.treeDiameter * pixelsPerUnit, chunk.treeLod);
        }
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
    static void fitShadowCamera(Camera view, Camera target, BoundingBox bounds, Vector3 direction) {
        fitShadowToReceivers(view == null ? bounds : BoardCamera.viewportBounds(view, bounds), target, bounds, direction);
    }

    /** Focus texels on these receivers, retaining the full light depth for offscreen shadow casters. */
    static void fitShadowToReceivers(BoundingBox receivers, Camera target, BoundingBox bounds, Vector3 direction) {
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
        float border = 1 + 4f / SHADOW_RESOLUTION;
        target.viewportWidth = Math.max(1, lightSpace.getWidth()) * border;
        target.viewportHeight = Math.max(1, lightSpace.getHeight()) * border;
        Vector3 center = lightSpace.getCenter(new Vector3());
        target.position.set(right).scl(center.x).mulAdd(target.up, center.y).mulAdd(target.direction, near - 1);
        target.near = 1;
        target.far = far - near + 2;
        snapShadowCamera(target);
    }

    private static void snapShadowCamera(Camera target) {
        Vector3 right = new Vector3(target.direction).crs(target.up).nor();
        float x = target.position.dot(right), y = target.position.dot(target.up);
        float texelX = target.viewportWidth / SHADOW_RESOLUTION;
        float texelY = target.viewportHeight / SHADOW_RESOLUTION;
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

    @Override
    public void dispose() {
        meshGeneration++;
        detailWorker.shutdownNow();
        try {
            if (!detailWorker.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Terrain worker did not stop");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing terrain", interrupted);
        }
        retiredChunks.forEach(Chunk::dispose);
        retiredChunks.clear();
        if (rebuild != null) {
            rebuild.replacements.values().forEach(Chunk::dispose);
            for (DetailJob job : rebuild.pending) { if (job.build() != null) { job.build().chunk.dispose(); } }
        }
        rebuild = null;
        requested = null;
        if (detailJob != null && detailJob.build() != null) { detailJob.build().chunk.dispose(); }
        detailJob = null;
        detailCache.values().forEach(Chunk::dispose);
        detailCache.clear();
        terrainBatch.clear();
        groundCover.dispose();
        biomeVegetation.dispose();
        biomes.dispose();
        propBatch.dispose();
        terrainPages.dispose();
        waterPages.dispose();
        trees.dispose();
        pickingSurfaces.clear();
        cpuGeometry.clear();
        chunks.forEach(Chunk::dispose);
        chunks.clear();
        ground.dispose();
        rims.clear();
        decals.dispose();
        tactical.dispose();
        foliage.dispose();
        batch.dispose();
        depthBatch.dispose();
        if (staticShadow != null) { staticShadow.dispose(); }
        if (boardShadow != null) { boardShadow.dispose(); }
        if (shadow != null) {
            shadow.dispose();
        }
        assets.dispose();
        rainNoise.dispose();
        waterDetail.dispose();
        waterDepth.dispose();
        ocean.dispose();
        waterExposure.dispose();
        lavaOcean.dispose();
        featureTriangles.clear();
    }
}
