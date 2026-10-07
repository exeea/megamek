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
    /** Native paint, Tactical View paint, and Tactical View object sprites share the atlas. */
    private record DecalSlot(Coords coords, int kind) { }
    private final GpuTextures<GroundSlot> ground = new GpuTextures<>(true);
    private final BoardRim rims = new BoardRim();
    private final GpuTextures<DecalSlot> decals = new GpuTextures<>();
    private final GpuTextures<Coords> tactical = new GpuTextures<>();
    private final GpuTilesetTerrain tileset = new GpuTilesetTerrain();
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
        private final DefaultShader.Config bankTurfShader = new DefaultShader.Config(
              GpuBankTurf.vertex(config.vertexShader), vegetationShader.fragmentShader);
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
                  : renderable.material.has(GpuBankTurf.Turf.TYPE) ? bankTurfShader
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
                  + "#define SURFACE_FAMILIES " + BoardScene.Surface.values().length + "\n"
                  + "#define LUNAR_FAMILY " + BoardScene.Surface.LUNAR.ordinal() + ".0\n"
                  + "#define FUNGUS_FAMILY " + BoardScene.Surface.FUNGUS.ordinal() + ".0\n"
                  + "#define DESERT_FAMILY " + BoardScene.Surface.DESERT.ordinal() + ".0\n"
                  + "#define MARS_FAMILY " + BoardScene.Surface.MARS.ordinal() + ".0\n"
                  + "#define VOLCANO_FAMILY " + BoardScene.Surface.VOLCANO.ordinal() + ".0\n"
                  + "#define TROPICAL_FAMILY " + BoardScene.Surface.TROPICAL.ordinal() + ".0\n"
                  + "#define VOLCANIC_CRUST_FAMILY " + BoardSurfaceBlend.CRUST + ".0\n"
                  + "#define VOLCANIC_BANK_FAMILY " + BoardSurfaceBlend.BANK + ".0\n"
                  + (renderable.material.has(GpuIceShader.TYPE) ? "#define iceFlag\n" : "")
                  + (renderable.material.has(GpuBiomeVegetation.Kind.TYPE) ? "#define biomeVegetationFlag\n" : "")
                  + (renderable.material.has(GpuBankTurf.Turf.TYPE) ? "#define bankTurfFlag\n" : "")
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
                  GpuGlsl.compile("GPU terrain", prefix, chosen.vertexShader,
                        renderable.material.has(GpuBuildingCutaway.TYPE)
                              ? GpuBuildingCutaway.fragment(chosen.fragmentShader, "v_cloudPosition.z") : chosen.fragmentShader)) {
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
                private final int gravityUniform = register("u_gravity");
                private final int windUniform = register("u_wind");
                private final int waterWindUniform = register("u_waterWind");
                private final int waterDriftUniform = register("u_waterDrift");
                private final int waterStormUniform = register("u_waterStorm");
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
                private final long instances = instanceMask(renderable);

                @Override
                public boolean canRender(Renderable other) {
                    var otherWater = other.material.get(GpuWaterShader.class, GpuWaterShader.TYPE);
                    var otherMagma = other.material.get(GpuMagmaShader.class, GpuMagmaShader.TYPE);
                    // The modes share an attribute mask but use different programs, including on shader reload.
                    // libGDX binds only the instance attributes of the renderable a shader was created for: crop rows
                    // must not reuse a reed shader, which never binds a_coverRow and would draw rows as clumps.
                    return waterMode == (otherWater == null ? null : otherWater.mode)
                          && flowingMagma == (otherMagma != null && otherMagma.flowing())
                          // A shadow map changes the compiled samplers without changing the environment's mask.
                          && shadowMap == (other.environment != null && other.environment.shadowMap != null)
                          && instances == instanceMask(other) && super.canRender(other);
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
                    set(magmaTimeUniform, magmaClock);
                    set(magmaOceanScaleUniform, lavaOcean.texture() == null ? 0f : GpuOcean.lavaScale());
                    set(roadProfileUniform, BoardRoad.JOIN_REACH, BoardRoad.WHEEL_OFFSET,
                          BoardRoad.TRACK_HALF_WIDTH, GpuRoads.WHEEL_TINT);
                    set(wetnessUniform, wetness);
                    set(viewDirectionUniform, camera.direction);
                    set(viewPositionUniform, camera.position);
                    set(perspectiveUniform, camera.projection.val[Matrix4.M33] == 0 ? 1f : 0f);
                    set(waterEffectsUniform, waterEffects && waterVisible() ? 1f : 0f);
                    set(gravityUniform, gravity);
                    set(windUniform, wind);
                    // Water follows the ocean's eased wind, so its ripples, gusts and foam turn with the waves.
                    Vector3 waterWind = ocean.wind();
                    set(waterWindUniform, waterWind == null ? wind : waterWind);
                    set(waterDriftUniform, ocean.drift());
                    set(waterStormUniform, ocean.gain());
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
                    if (has(biomeSoilUniform)) { set(biomeSoilTileUniform, assets.sculptTile("earth") * .45f); }
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
            GpuLavaLighting.register(result);
            GpuBuildingCutaway.register(result);
            GpuLiquidShader.register(result);
            GpuMagmaShader.register(result, rainNoise);
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
            result.register("u_sculptLayers", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    SculptLayers maps = attributes.get(SculptLayers.class, SculptLayers.TYPE);
                    if (maps != null) { target.set(id, maps.layers[0], maps.layers[1], maps.layers[2], maps.layers[3]); }
                }
            });
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
                    SculptLayers maps = attributes.get(SculptLayers.class, SculptLayers.TYPE);
                    if (maps != null) { target.set(id, maps.texture); }
                    else if (attributes.has(GpuBankTurf.Turf.TYPE)) { target.set(id, assets.sculptArray(SCULPT_LAYERS)); }
                }
            });
            for (var family : BoardScene.Surface.values()) {
                String name = SCULPT_MATERIALS[family.ordinal()][0];
                result.register("u_turfMaterials[" + family.ordinal() + "]", new BaseShader.LocalSetter() {
                    @Override
                    public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                        if (attributes.has(GpuBankTurf.Turf.TYPE)) {
                            target.set(id, 2f * SCULPT_LAYERS.indexOf(name), assets.sculptTile(name));
                        }
                    }
                });
            }
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
    private final GpuLavaLighting lavaLighting = new GpuLavaLighting();
    private final List<Chunk> chunks = new ArrayList<>();
    // At most four in-flight chunks and eight replaced chunks. Half the cores, at most eight, build terrain: each
    // chunk spreads its hexes across the pool, while the other half keeps rendering, input and garbage collection
    // responsive. All GL ownership stays on the render thread.
    static final int DEFAULT_WORKERS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    private final ExecutorService detailWorker = TerrainSettings.workers(DEFAULT_WORKERS);
    private final Map<Integer, Chunk> detailCache = new LinkedHashMap<>();
    private record DetailJob(long generation, int index, Chunk source, TerrainLod lod, BoardScene scene,
          TerrainSettings settings, float floor, boolean rebuilding, CompletableFuture<Prepared> surfaces,
          ChunkBuild build, CompletableFuture<Void> meshes, TerrainLoadProgress progress) {
        boolean ready() { return build == null ? surfaces.isDone() : meshes == null || meshes.isDone(); }
    }
    record Prepared(Map<Coords, BoardSurface> surfaces, GpuWaterShader.Field.Prepared water,
          GpuWaterShader.Field.Prepared lava,
          Map<Coords, BoardTacticalGeometry.Surface> topography, Map<Coords, BoardPlants> plants,
          Map<Coords, SculptPlan> sculpts,
          Map<Coords, BoardFlow.Current> currents, Map<Coords, List<RoadPatch>> roads,
          Map<Coords, BoardBridge.Deck> bridges, Map<Coords, BoardBridge.Shape> bridgeShapes,
          Map<Coords, Map<BoardSurface.Side, List<BoardSurface.Face>>> walls, Set<Coords> reused,
          Map<Coords, List<BoardDecals.Stamp>> paint) { }
    record RoadPatch(GpuRoads.Patch patch, GpuRoads.MaskData mask,
          List<BoardTacticalGeometry.Triangle> triangles, boolean flat) { }
    private record Request(long generation, BoardScene scene, TerrainSettings settings) { }
    private record UpdatePlan(float floor, BoardConcrete coast, Map<Coords, BoardFlow.Current> currents,
          boolean all, Set<Coords> changed, Set<Coords> changedTiles, Map<GroundSlot, BoardScene.Pixels> colors,
          Map<GroundSlot, BoardScene.Pixels> normals, Map<DecalSlot, BoardScene.Pixels> decals,
          Map<Coords, List<BoardDecals.Stamp>> paint) { }
    private Map<Coords, List<BoardDecals.Stamp>> installedPaint = Map.of();
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
    private Map<DecalSlot, BoardScene.Pixels> decalPixels = Map.of();
    private final GpuGroundCover groundCover = new GpuGroundCover();
    private final GpuBiomeVegetation biomeVegetation = new GpuBiomeVegetation();
    private final GpuBiomeSurface biomes = new GpuBiomeSurface();
    private final GpuPropBatch propBatch = new GpuPropBatch();
    private final GpuFungus fungus = new GpuFungus();
    private final GpuGeysers geysers = new GpuGeysers();
    private BoardScene coverScene;
    /** GL-thread cache derived from the immutable source tiles; published with the existing terrain rebuild. */
    private List<BoardScene.Tile> lunarSource, lunarTiles;
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
    /** Render-owned flow time, advanced only by animate: gravity changes speed without restarting the pattern. */
    private float magmaClock;
    private float floor;
    private int chunkRows;
    private float buildingOpacity = DEFAULT_BUILDING_OPACITY;
    private boolean normalMaps = true;
    private boolean grass = true;
    /** Neutral material view: sculpted terrain drops its textures so only geometry, light and occlusion remain. */
    private boolean clay;
    private boolean waterEffects = true;
    /** This frame's visual gravity, converted from the scenario's g multiplier to metres per second squared. */
    private float gravity = BoardAtmosphere.STANDARD_GRAVITY;
    private float wetness;
    private float detailPixelsPerUnit = Float.NaN;
    private boolean hasCutaways;
    /** The Tactical View draws, drapes overlays on and picks the tileset columns instead of this terrain. */
    private boolean tacticalView;
    /** The overlays' surfaces changed with the view; the next refine reports it like a new level of detail. */
    private boolean drapeChanged;
    private boolean terrainMaterialsReady;
    private GpuGlsl.Preparation terrainMaterialPreparation;
    private final List<Renderable> terrainMaterialSamples = new ArrayList<>();
    private final TerrainLoadProgress terrainMaterialProgress = new TerrainLoadProgress();
    private long terrainMaterialEnvironment = -1;
    private boolean terrainMaterialShadows;

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
        if (source.contains("// terrain-ground-color-functions")) {
            source = source.replace("// terrain-ground-color-functions", GpuShaderSource.read("terrain-ground-color.glsl"));
        }
        if (source.contains("// terrain-projection-functions")) {
            source = source.replace("// terrain-projection-functions", GpuShaderSource.read("terrain-detail.glsl")
                  + GpuShaderSource.read("terrain-projection.glsl"));
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
        functions += GpuShaderSource.read("lava-lighting.glsl");
        functions += GpuShaderSource.read("surface-lighting.glsl");
        functions += GpuShaderSource.read("terrain-meadow.glsl");
        functions += GpuShaderSource.read("terrain-patterns.glsl");
        functions += GpuShaderSource.read("water-optics.glsl");
        functions += GpuShaderSource.read("terrain-hexes.glsl");
        if (source.contains("bankTurfFlag")) {
            functions += "\n#ifdef bankTurfFlag\n" + GpuShaderSource.read("terrain-ground-color.glsl")
                  + GpuShaderSource.read("terrain-bank-color.glsl") + "\n#endif\n";
        }
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
            functions += GpuShaderSource.read("terrain-detail.glsl");
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
            functions += GpuShaderSource.read("liquid-flow.glsl");
            functions += GpuShaderSource.read("water-interactions.glsl");
            functions += GpuShaderSource.read("water-wave.glsl") + GpuShaderSource.read("water-pool.glsl");
        }
        return source.replace("void main() {", "// terrain-lighting-functions\n" + functions + "\nvoid main() {");
    }

    /** The per-instance attribute layout, which DefaultShader.canRender does not compare; 0 when not instanced. */
    private static long instanceMask(Renderable renderable) {
        VertexAttributes instances = renderable.meshPart.mesh.getInstancedAttributes();
        return instances == null ? 0 : instances.getMaskWithSizePacked();
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
        environment.set(lavaLighting);
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
        /** A building's, fuel tank's or industrial structure's shell or floor: what the Tactical View keeps in 3D. */
        private final boolean structure;
        /** Height-only industrial cover has neither occupiable storeys nor building cutaways. */
        private final boolean industrial;
        /** Cosmetic cover, including instanced fungi; excluded from wireframe fill and lines. */
        private boolean scatter;
        /** Authored object identity, shared by both camera presentations and precise model picking. */
        private String decorationId;
        private float decorationAnchorZ;
        private int decorationIndex;
        private BoardScene.Feature decorationFeature, previewFeature;
        // Render-thread presentation only. Rebuilds continue borrowing the untouched installed instance/bounds.
        private ModelInstance previewInstance;
        private BoundingBox previewBounds;
        private float previewAnchorZ;
        private Coords previewCoords;
        private String receiver;
        /** World support height for an interior floor; NaN for shells and other props. */
        private final float floorZ;
        private ModelInstance hoverOpaque;
        private int buildingLod;

        Prop(Coords coords, ModelInstance instance, BoundingBox bounds, String treeAsset, boolean hardSurface) {
            this(coords, instance, bounds, treeAsset, hardSurface, null, false);
        }

        Prop(Coords coords, ModelInstance instance, BoundingBox bounds, String treeAsset, boolean hardSurface,
              GpuBuilding.Assembly building, boolean structure) {
            this(coords, instance, bounds, treeAsset, hardSurface, building, structure, Float.NaN);
        }

        Prop(Coords coords, ModelInstance instance, BoundingBox bounds, String treeAsset, boolean hardSurface,
              GpuBuilding.Assembly building, boolean structure, float floorZ) {
            this(coords, instance, bounds, treeAsset, hardSurface, building, structure, floorZ, false);
        }

        Prop(Coords coords, ModelInstance instance, BoundingBox bounds, String treeAsset, boolean hardSurface,
              GpuBuilding.Assembly building, boolean structure, float floorZ, boolean industrial) {
            this.coords = coords;
            this.building = building;
            this.structure = structure;
            this.industrial = industrial;
            this.floorZ = floorZ;
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
                for (long type : new long[] { BlendingAttribute.Type, DepthTestAttribute.Type, IntAttribute.CullFace, GpuBuildingCutaway.TYPE }) {
                    Attribute attribute = previous.get(type);
                    if (attribute != null) { material.set(attribute.copy()); }
                }
            }
            instance = replacement;
            if (hoverOpaque != null) { updateHoverOpaque(); }
            return true;
        }

        private void updateHoverOpaque() {
            hoverOpaque = new ModelInstance(instance);
            for (Material material : hoverOpaque.materials) {
                var clip = material.get(GpuBuildingCutaway.class, GpuBuildingCutaway.TYPE);
                material.set(new GpuBuildingCutaway(clip.floor, clip.ceiling, false));
                material.remove(BlendingAttribute.Type);
                material.remove(DepthTestAttribute.Type);
                material.remove(IntAttribute.CullFace);
            }
        }

        Coords coords() { return previewCoords == null ? coords : previewCoords; }
        ModelInstance instance() { return previewInstance == null ? instance : previewInstance; }
        BoundingBox bounds() { return previewInstance == null ? bounds : previewBounds; }
        float anchorZ() { return previewInstance == null ? decorationAnchorZ : previewAnchorZ; }
        boolean tree() { return treeAsset != null; }

    }
    /** An animated liquid material: its frames, whether it falls, and the current it moves with. */
    record LiquidSurface(Material material, BoardLiquid.Textures source, boolean falling, BoardFlow.Current current) { }

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

    /** Foliage shading: solid, canopy, snow, fungal fruiting body or cyan mycelium. */
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

    /** Plant material roles are authored in the GLB, so new plant assets need no editor-specific ID mapping. */
    static boolean foliageModel(Model model) {
        for (Material material : model.materials) {
            if (material.id.startsWith("bark") || material.id.startsWith("canopy") || material.id.equals("cactus")
                  || material.id.equals("fungus") || material.id.equals("mycelium") || material.id.equals("coral")) { return true; }
        }
        return false;
    }

    /** Marks a tree model's materials for the foliage shader, once; instances copy the marks. */
    private static Model foliage(Model model) {
        for (Material material : model.materials) {
            if (!material.has(Foliage.TYPE)) {
                boolean solid = material.id.startsWith("bark") || material.id.equals("cactus") || material.id.equals("fruit")
                      || material.id.equals("fungus") || material.id.equals("mycelium") || material.id.equals("coral");
                material.set(new Foliage(material.id.equals("mycelium") ? 4 : material.id.equals("fungus") ? 3
                      : material.id.equals("snow") || material.id.equals("canopy-snow-cutout") ? 2 : solid ? 0 : 1));
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
          { "loose-sand", "pavement", "sandstone", "sandstone" },
          { "rock", "scree", "granite-contact", "granite-contact" },
          { "concrete", "scree", "granite-contact", "cast" },
          { "snow", "scree", "granite-contact", "snow" },
          { "lunar", "lunar-scree", "lunar-cliff", "lunar-cliff" },
          { "fungus-ground", "fungus-mat", "fungus-cliff", "fungus-fibres" },
          { "desert-hardpan", "pavement", "sandstone", "sandstone-cap" },
          { "mars-hardpan", "mars-hardpan", "mars-bedrock", "mars-cap" },
          { "volcano-ground", "volcano-ground", "volcano-basalt", "basalt-cap" },
          { "tropical-ground", "dirt", "granite-contact", "soil-contact" },
    };
    private static final List<String> SCULPT_LAYERS = Arrays.stream(SCULPT_MATERIALS).flatMap(Arrays::stream).distinct().toList();

    /**
     * Every family's maps in one array (GpuAssets.sculptArray, bound as u_terrainLayers), so all of them take a
     * single texture unit: macOS gives a shader stage only 16. layers are the colour/height layers of this family's
     * ground, debris, wall and mantle maps; each normal/AO map is the next layer.
     */
    private static final class SculptLayers extends Attribute {
        static final long TYPE = register("boardSculptLayers");
        final TextureArray texture;
        final float[] layers;

        SculptLayers(TextureArray texture, float... layers) {
            super(TYPE);
            this.texture = texture;
            this.layers = layers.clone();
        }

        @Override
        public SculptLayers copy() { return new SculptLayers(texture, layers); }

        @Override
        public int compareTo(Attribute other) {
            if (type != other.type) { return Long.compare(type, other.type); }
            var maps = (SculptLayers) other;
            int order = Integer.compare(System.identityHashCode(texture), System.identityHashCode(maps.texture));
            return order != 0 ? order : Arrays.compare(layers, maps.layers);
        }

        @Override
        public int hashCode() { return 31 * (31 * super.hashCode() + System.identityHashCode(texture)) + Arrays.hashCode(layers); }
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
    private record PaintedDecal(BoardDecals.Stamp stamp, List<BoardSurface.Face> faces, float lift) { }

    private record InteriorStruts(Coords coords, ModelInstance instance) { }

    private static final class TileMesh {
        // Vegetation already retains its support; find that same geometry even after the bounded CPU cache evicts it.
        WeakReference<BoardTacticalGeometry.Surface> support;
        // Ground cover planted on this tile's support while its chunk was prepared; null when it grows none.
        BoardPlants plants;
        BoardBridge.Shape bridgeShape;
        final List<TileRange> ranges = new ArrayList<>();
        final List<Prop> props = new ArrayList<>();
        final List<PaintedDecal> paint = new ArrayList<>();
        final List<InteriorStruts> struts = new ArrayList<>();
        final BoundingBox bounds = new BoundingBox().inf();
        float scatterDiameter;
    }

    private static final class Chunk implements Disposable {
        TerrainLod lod;
        final Map<Coords, TileMesh> tileMeshes = new HashMap<>();
        final List<ModelInstance> opaque = new ArrayList<>();
        Array<Renderable> terrainRenderables;
        GpuTerrainDepth depthTerrain;
        /** Packed and instanced cosmetic cover share one visibility decision in every pass. */
        final List<ModelInstance> scatter = new ArrayList<>();
        final GpuTreeInstances.Stand scatterStand = new GpuTreeInstances.Stand();
        float scatterDiameter;
        boolean scatterVisible = true;
        final List<ModelInstance> overlays = new ArrayList<>();
        final List<ModelInstance> surfaceDecals = new ArrayList<>();
        final List<ModelInstance> tilesetDecals = new ArrayList<>();
        final List<ModelInstance> water = new ArrayList<>();
        Array<Renderable> waterRenderables;
        final List<LiquidSurface> liquidMaterials = new ArrayList<>();
        final List<ModelInstance> tactical = new ArrayList<>();
        final List<Prop> props = new ArrayList<>();
        final List<Prop> cutaways = new ArrayList<>();
        final GpuTreeInstances.Stand stand = new GpuTreeInstances.Stand();
        final List<GpuFungus.Emitter> spores = new ArrayList<>();
        final List<GpuGeysers.Emitter> geysers = new ArrayList<>();
        final List<GpuLavaLighting.Source> fungalLights = new ArrayList<>();
        float treeDiameter;
        int treeLod;
        final List<InteriorStruts> struts = new ArrayList<>();
        final Array<Renderable> propRenderables = new Array<>();
        Array<Renderable> scatterRenderables = new Array<>();
        final Array<Renderable> shadowPropRenderables = new Array<>();
        final Array<Renderable> sharedProps = new Array<>();
        final Array<Renderable> sharedShadows = new Array<>();
        final Array<Renderable> decorationRenderables = new Array<>();
        List<BoardScene.Tile> editorTiles;
        final Set<Model> sharedInteriors = new HashSet<>();
        Set<Prop> faded = Set.of();
        Coords hoverCoords;
        float hoverFloor = Float.NaN;
        final RenderableProvider solidProps = (out, pool) -> supply(
              faded.isEmpty() ? shadowPropRenderables : propRenderables, out);
        final RenderableProvider shadowProps = (out, pool) -> supply(shadowPropRenderables, out);
        final RenderableProvider decorations = (out, pool) -> supply(decorationRenderables, out);
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

        /** Caches the props for drawing and shadows; {@code structuresOnly} for the Tactical View. */
        void cacheProps(boolean structuresOnly) {
            cacheDecorations(structuresOnly);
            if (cutaways.isEmpty() && struts.isEmpty()) { return; }
            if (shadowPropRenderables.isEmpty()) {
                cacheProps(shadowPropRenderables, false, structuresOnly);
                // A shadow always uses the original opaque materials, independently of live instance fading.
                opaqueMaterials(shadowPropRenderables);
                for (Renderable part : shadowPropRenderables) { part.material.remove(GpuBuildingCutaway.TYPE); }
            }
            // These snapshots contain transforms and mesh ranges only; module geometry is never merged per building.
            List<ModelInstance> shared = new ArrayList<>();
            for (Prop prop : cutaways) {
                if (prop.building != null) { shared.add(prop.instance); }
            }
            sharedShadows.clear();
            sharedShadows.addAll(GpuTerrainDepth.snapshot(shared));
            opaqueMaterials(sharedShadows);
            for (Renderable part : sharedShadows) { part.material.remove(GpuBuildingCutaway.TYPE); }
            for (Prop prop : faded) {
                shared.remove(prop.instance);
                if (prop.building != null && prop.hoverOpaque != null) { shared.add(prop.hoverOpaque); }
            }
            for (InteriorStruts strut : struts) {
                if (sharedInteriors.contains(strut.instance.model) && interiorVisible(strut.coords)) {
                    shared.add(strut.instance);
                }
            }
            for (Prop prop : cutaways) {
                if (!Float.isNaN(prop.floorZ) && sharedInteriors.contains(prop.instance.model)
                      && interiorFloorVisible(prop) && !faded.contains(prop)) { shared.add(prop.instance); }
            }
            sharedProps.clear();
            sharedProps.addAll(GpuTerrainDepth.snapshot(shared));
            // Stable, opaque material keys for the instancing cache; live cutaway materials can mutate independently.
            opaqueMaterials(sharedProps);
            GpuPropBatch.disposeMeshes(propRenderables);
            // Share the complete cache for normal rendering; only occupied chunks need a second mesh cache.
            if (!faded.isEmpty()) {
                cacheProps(propRenderables, true, structuresOnly);
            }
        }

        /** Authored placements borrow asset meshes; changing a transform never repacks a static terrain mesh. */
        void cacheDecorations(boolean structuresOnly) {
            decorationRenderables.clear();
            decorationRenderables.addAll(GpuTerrainDepth.snapshot(props.stream()
                  .filter(prop -> prop.decorationId != null && (!structuresOnly || prop.structure))
                  .map(Prop::instance).toList()));
            opaqueMaterials(decorationRenderables);
        }

        private boolean interiorVisible(Coords coords) {
            return faded.stream().anyMatch(prop -> Float.isNaN(prop.floorZ) && prop.coords.equals(coords));
        }

        private boolean interiorFloorVisible(Prop floor) {
            if (!interiorVisible(floor.coords)) { return false; }
            boolean hoverOnly = cutaways.stream().anyMatch(prop -> prop.coords.equals(floor.coords) && prop.hoverOpaque != null);
            return !hoverOnly || Math.abs(floor.floorZ - hoverFloor) < .05f * BoardGeometry.hexScale();
        }

        private static void opaqueMaterials(Array<Renderable> renderables) {
            for (Renderable renderable : renderables) {
                renderable.material = new Material(renderable.material);
                // Alpha-tested GLB cards are opaque cutouts, not building transparency. LibGDX's shaders
                // require the blending attribute even when blending is disabled to apply their alpha test.
                if (renderable.material.has(FloatAttribute.AlphaTest)) {
                    renderable.material.set(new BlendingAttribute(false, GL20.GL_ONE, GL20.GL_ZERO, 1));
                } else { renderable.material.remove(BlendingAttribute.Type); }
                renderable.material.remove(DepthTestAttribute.Type);
                // Keep authored culling, including the reversed winding of mirrored native placements.
            }
        }

        private void cacheProps(Array<Renderable> destination, boolean omitFaded, boolean structuresOnly) {
            GpuPropBatch.cache(destination, builder -> {
                for (InteriorStruts strut : struts) {
                    if (omitFaded && !sharedInteriors.contains(strut.instance.model) && interiorVisible(strut.coords)) {
                        builder.add(strut.instance);
                    }
                }
                for (Prop prop : cutaways) {
                    if (prop.decorationId != null || structuresOnly && !prop.structure) { continue; }
                    if (omitFaded && prop.building == null && prop.hoverOpaque != null) { builder.add(prop.hoverOpaque); }
                    if (prop.building == null && !sharedInteriors.contains(prop.instance.model)
                          && (Float.isNaN(prop.floorZ) || omitFaded && interiorFloorVisible(prop))
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
            for (List<ModelInstance> layer : List.of(opaque, scatter, overlays, water, tactical, surfaceDecals, tilesetDecals)) {
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
            refine(null, true);
            if (busy()) { java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); }
        }
    }

    /** Request only the latest rendering snapshot. Game state and commands remain owned by the source. */
    void update(BoardScene scene, Camera camera) {
        if (gravity == 0) {
            if (lunarSource != scene.tiles()) {
                lunarTiles = List.copyOf(scene.tiles().stream().map(BoardScene.Tile::lunar).toList());
                lunarSource = scene.tiles();
            }
            scene = scene.withTiles(lunarTiles);
        } else {
            lunarSource = null;
            lunarTiles = null;
        }
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

    private boolean materialsReady() {
        return terrainMaterialsReady && terrainMaterialEnvironment == environment.getMask()
              && terrainMaterialShadows == (environment.shadowMap != null);
    }

    boolean busy() { return requested != null || rebuild != null || detailJob != null || coverScene != null && !materialsReady(); }

    boolean ready(BoardScene scene) { return materialsReady() && coverScene != null && sameBoard(coverScene, scene); }

    int buildProgress() {
        if (requested == null) { return materialsReady() ? -1 : 100; }
        if (rebuild == null || rebuild.request != requested || rebuild.plan == null) { return 0; }
        return 100 * rebuild.replacements.size() / Math.max(1, rebuild.remaining.size() + rebuild.replacements.size());
    }

    /** Read on the GL thread; each worker contributes one coherent, non-authoritative progress snapshot. */
    List<TerrainLoadProgress.Status> buildDetails() {
        if (requested == null && coverScene == null) { return List.of(); }
        List<TerrainLoadProgress.Status> result = new ArrayList<>();
        if (terrainMaterialPreparation != null && !materialsReady()) {
            result.add(new TerrainLoadProgress.Status(0, 0, terrainMaterialProgress.snapshot()));
        }
        if (requested == null || rebuild == null || rebuild.request.generation() != requested.generation()) {
            return result;
        }
        List<DetailJob> jobs = new ArrayList<>(rebuild.pending);
        if (detailJob != null && detailJob.rebuilding()) { jobs.add(detailJob); }
        if (jobs.isEmpty()) {
            result.add(new TerrainLoadProgress.Status(0, 0, rebuild.progress.snapshot()));
            return result;
        }
        BoardScene scene = rebuild.request.scene();
        int columns = (scene.width() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        int rows = (scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        result.addAll(jobs.stream().filter(job -> job.generation() == requested.generation())
              .map(job -> new TerrainLoadProgress.Status(TerrainLoadProgress.displayIndex(job.index(), columns, rows) + 1,
                    columns * rows, job.progress().snapshot()))
              .sorted(Comparator.comparingInt(TerrainLoadProgress.Status::section)).toList());
        return result;
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
        lunarSource = null;
        lunarTiles = null;
        requested = null;
        meshGeneration++;
    }

    /** Only terrain presentation waits for publication; units, commands and the source's game state remain current. */
    BoardScene presentation(BoardScene scene) {
        return ready(scene) ? scene.withTiles(tiles) : scene;
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
          Map<DecalSlot, BoardScene.Pixels> previousDecals, boolean changedTuning, boolean changedLimbScale,
          boolean changedLimbModel, BoardScene.Pixels incline, BoardScene.Pixels highIncline,
          TerrainLoadProgress progress, Map<Coords, List<BoardDecals.Stamp>> previousPaint) {
        BoardScene scene = request.scene();
        progress.begin("layout", 1);
        long generation = request.generation();
        Map<GroundSlot, BoardScene.Pixels> terrainPixels = new ConcurrentHashMap<>(scene.tiles().size() * 2);
        Map<GroundSlot, BoardScene.Pixels> normalPixels = new ConcurrentHashMap<>(scene.tiles().size() * 2);
        Map<DecalSlot, BoardScene.Pixels> decalPixels = new ConcurrentHashMap<>(scene.tiles().size() * 2);
        float nextFloor = BoardGeometry.floor(scene);
        boolean rebuildAll = changedTuning || beforeScene == null || !sameBoard(beforeScene, scene);
        boolean changedFlow = rebuildAll;
        Map<Coords, BoardFlow.Current> nextCurrents = previousCurrents;
        // The fitted concrete outline reads ground, water, levels, roads and features. An edit that changes none
        // of them keeps the previous fit, and the corner comparison below then has nothing to find.
        boolean refit = rebuildAll || previousCoast == null || concreteInputsChanged(beforeScene, scene);
        BoardConcrete nextCoast = refit ? BoardConcrete.of(scene) : BoardConcrete.adopt(scene, previousCoast);
        progress.advance();
        progress.begin("changes", scene.tiles().size());
        Set<Coords> changedTiles = new HashSet<>();
        Map<Coords, List<BoardDecals.Stamp>> paint = rebuildAll ? BoardDecals.index(scene) : BoardDecals.update(beforeScene, scene, previousPaint);
        changedTiles.addAll(BoardDecals.changed(previousPaint, paint));
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
                // A capture keeps the tile instance of every hex it did not touch; only its fitted concrete
                // corners, which a neighbour's edit can move, still need checking.
                boolean same = before == tile;
                if (!same && (before.blackIce() != tile.blackIce() || before.bare() != tile.bare()
                      || before.ultraSublevel() != tile.ultraSublevel()
                      || !before.appearance().equals(tile.appearance()))) { changedTiles.add(tile.coords()); }
                // Artwork is recomposed for dirtied chunks only, so a hex whose decal or tileset artwork changed is
                // dirtied here; the atlas comparison that used to notice it now sees just those chunks.
                if (!same && (!Objects.equals(decals(before), decals(tile))
                      || !Objects.equals(before.tilesetDecals(), tile.tilesetDecals())
                      || !Objects.equals(before.tilesetScenery(), tile.tilesetScenery()))) { changedTiles.add(tile.coords()); }
                // Currents run between liquid hexes only (BoardFlow); dry ground changes level without moving them.
                changedFlow |= !same && (!before.coords().equals(tile.coords()) || !before.liquid().equals(tile.liquid())
                      || tile.liquid().present() && (before.elevation() != tile.elevation() || before.frozen() != tile.frozen()));
                // Rim vertex colors come from the selected ground artwork, not the atlas texture.
                if (!same && (!before.ground().equals(tile.ground()) || !Objects.equals(before.normals(), tile.normals()))) {
                    changedTiles.add(tile.coords());
                }
                if (!same && !before.coords().equals(tile.coords())) {
                    rebuildAll = true;
                } else {
                    // A water shore reaches the banks and corners it shapes from further out (BoardSurface.Key);
                    // joined landforms also depend on the neighbours' road approaches, two hexes out.
                    boolean shore = !same && (before.elevation() != tile.elevation() || before.waterDepth() != tile.waterDepth()
                          || !before.liquid().equals(tile.liquid()) || before.roadExits() != tile.roadExits()
                          || before.road() != tile.road()
                          || before.cliffTopExits() != tile.cliffTopExits() || before.ultraSublevel() != tile.ultraSublevel()
                          || before.surface() != tile.surface() || before.detailedGround() != tile.detailedGround())
                          || refit && !nextCoast.sameCorners(previousCoast, tile.coords());
                    if (shore || !same && (before.frozen() != tile.frozen() || before.biome() != tile.biome()
                          || !before.groundCover().equals(tile.groundCover())
                          || !solidFeatures(before).equals(solidFeatures(tile)))) {
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
        // The hexes of untouched chunks keep every artwork slot of the previous build, which captured the same
        // pixel objects for them; an edit recomposes only the chunks it dirtied.
        List<BoardScene.Tile> retextured;
        if (all || changedLimbModel) {
            retextured = scene.tiles();
        } else {
            terrainPixels.putAll(previousColors);
            normalPixels.putAll(previousNormals);
            decalPixels.putAll(previousDecals);
            retextured = new ArrayList<>();
            for (Coords chunk : changedChunks) {
                for (int x = chunk.getX() * CHUNK_SIZE; x < Math.min(scene.width(), (chunk.getX() + 1) * CHUNK_SIZE); x++) {
                    for (int y = chunk.getY() * CHUNK_SIZE; y < Math.min(scene.height(), (chunk.getY() + 1) * CHUNK_SIZE); y++) {
                        retextured.add(scene.tile(x, y));
                    }
                }
            }
        }
        progress.begin("textures", retextured.size());
        retextured.parallelStream().forEach(tile -> request.settings().run(() -> {
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
            if (material.normal() != null) { normalPixels.put(topSlot, material.normal()); } else { normalPixels.remove(topSlot); }
            if (tile.normals() != null) { normalPixels.put(baseSlot, tile.normals()); } else { normalPixels.remove(baseSlot); }
            BoardScene.Pixels[] overlays = { decals(tile), tile.tilesetDecals(), tile.tilesetScenery() };
            for (int kind = 0; kind < overlays.length; kind++) {
                DecalSlot slot = new DecalSlot(tile.coords(), kind);
                if (overlays[kind] != null) { decalPixels.put(slot, overlays[kind]); } else { decalPixels.remove(slot); }
            }
            progress.advance();
        }));
        // A full build retains only the rim compositions it used; an edit keeps those of the hexes it left alone.
        if (all) { rims.retainUsed(); }
        progress.begin("atlases", 4);
        return new UpdatePlan(nextFloor, nextCoast, nextCurrents, rebuildAll, changedChunks, changedTiles,
              terrainPixels, normalPixels, decalPixels, paint);

    }

    /** Whether any hex changed what the concrete fit reads: its ground, water, level, roads or structures. */
    private static List<BoardScene.Feature> solidFeatures(BoardScene.Tile tile) {
        return tile.features().stream().filter(f -> f.decoration() == null || !f.decoration().kind().equals("decal")).toList();
    }

    private static boolean concreteInputsChanged(BoardScene before, BoardScene after) {
        for (int index = 0; index < after.tiles().size(); index++) {
            BoardScene.Tile was = before.tiles().get(index), is = after.tiles().get(index);
            if (was == is) { continue; }
            if (was.surface() != is.surface() || !was.liquid().equals(is.liquid()) || was.elevation() != is.elevation()
                  || was.waterDepth() != is.waterDepth() || was.frozen() != is.frozen()
                  || was.ultraSublevel() != is.ultraSublevel() || was.bare() != is.bare()
                  || was.roadExits() != is.roadExits() || was.road() != is.road()
                  || was.cliffTopExits() != is.cliffTopExits() || was.detailedGround() != is.detailedGround()
                  || !structures(was).equals(structures(is))) { return true; }
        }
        return false;
    }

    /** The features the concrete fit reads: structures and props such as bridges, never trees, rocks or scenery. */
    private static List<BoardScene.Feature> structures(BoardScene.Tile tile) {
        List<BoardScene.Feature> result = new ArrayList<>();
        for (BoardScene.Feature feature : tile.features()) {
            if (feature.kind() == BoardScene.FeatureKind.BUILDING || feature.kind() == BoardScene.FeatureKind.INDUSTRIAL
                  || feature.kind() == BoardScene.FeatureKind.PROP) { result.add(feature); }
        }
        return result;
    }

    private static int editReach(BoardScene before, BoardScene after, Coords at) {
        if (!shoreNearby(before, after, at)) { return 2; }
        BoardScene.Tile was = before.tile(at), is = after.tile(at);
        if ((was.liquid().present() || is.liquid().present()) && !deepened(was, is)) { return BoardSurface.SHORE_RINGS; }
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

    /**
     * A pool that only grew deeper or shallower and stays at least a level deep. Beyond a level the shore field and the
     * river's channels read no depth ({@link BoardRiver} clamps it), so its bed, the beds it meets and the steps down
     * into it change, as with a level edit beside water.
     */
    private static boolean deepened(BoardScene.Tile was, BoardScene.Tile is) {
        return was.waterDepth() != is.waterDepth() && Math.min(was.waterDepth(), is.waterDepth()) >= 1
              && BoardSurface.shape(was, 0).equals(BoardSurface.shape(is, 0));
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
        return refine(camera, false);
    }

    boolean refine(Camera camera, boolean editing) {
        if (drapeChanged) {
            drapeChanged = false;
            return true;
        }
        // Launch links while CPU terrain jobs are being prepared, and poll without blocking the loading screen.
        if (!materialsReady()) {
            if (terrainMaterialPreparation == null && requested != null) { updateLight(requested.scene().light()); }
            prepareTerrainMaterials();
        }
        // While opening/replacing a board, use more of the loading frame for uploads instead of stretching each
        // chunk's handoffs over several frames. An editor change gets a larger bounded slice so each small edit
        // does not wait dozens of frames (its collection and upload take about 60 ms of render-thread time, a
        // handful of frames at this slice); ordinary camera refinement keeps the two-millisecond budget.
        long deadline = System.nanoTime() + (coverScene == null ? 8_000_000
              : editing && (requested != null || rebuild != null) ? 12_000_000 : 2_000_000);
        drainRetired(deadline);
        if (requested == null && rebuild == null && detailJob == null) { return false; }
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
                Model previousLimbModel = limbModel;
                if (limbModel == null && unitModels != null && requested.scene().tiles().stream()
                      .anyMatch(tile -> tile.features().stream().anyMatch(feature -> feature.kind() == BoardScene.FeatureKind.LIMB))) {
                    var limb = unitModels.equipment("Limb Club");
                    limbModel = limb == null ? null : limb.model();
                }
                // Limbs becoming drawable switch the decal artwork of every hex that carries them.
                boolean changedLimbModel = limbModel != previousLimbModel;
                Request request = requested;
                BoardScene previous = coverScene;
                float bottom = floor;
                BoardConcrete previousCoast = coast;
                Map<Coords, BoardFlow.Current> flow = currents;
                Map<GroundSlot, BoardScene.Pixels> colors = terrainPixels, normals = normalPixels;
                Map<DecalSlot, BoardScene.Pixels> overlays = decalPixels;
                BoardScene.Pixels incline = assets.inclineMask(), highIncline = assets.highInclineMask();
                boolean changedTuning = installedSettings == null
                      || !sameSettings(installedSettings, request.settings());
                boolean changedLimbScale = limbModel != null && installedSettings != null
                      && installedSettings.geometry().unitScale() != request.settings().geometry().unitScale();
                TerrainLoadProgress progress = new TerrainLoadProgress();
                var previousPaint = installedPaint;
                rebuild = new Rebuild(request, CompletableFuture.supplyAsync(() -> request.settings().call(
                      () -> prepareUpdate(request, previous, bottom, previousCoast, flow, colors, normals, overlays,
                            changedTuning, changedLimbScale, changedLimbModel, incline, highIncline, progress, previousPaint)), detailWorker), progress);
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
        var paint = rebuilding ? rebuild.plan.paint() : installedPaint;
        detailJob = new DetailJob(generation, index, source, lod, scene, settings, bottom, rebuilding,
              CompletableFuture.supplyAsync(() -> settings.call(
                    () -> prepare(scene, x, y, bottom, lod, flow, settings, reuse, waterShapes,
                          () -> checkBuild(generation), progress, roadMaskData, paint)), detailWorker), null, null, progress);
    }

    private void prepareAtlases(BoardScene scene, UpdatePlan plan, TerrainLoadProgress progress) {
        ground.retainReplacedPages();
        decals.retainReplacedPages();
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
        decals.updateRegions(plan.decals(), Map.of()).forEach(slot -> {
            dirtyChunk(atlasChanges, slot.coords());
            atlasTileChanges.add(slot.coords());
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
        lavaLighting.setFungus(chunks.stream().flatMap(chunk -> chunk.fungalLights.stream()).toList());
        installedSettings = rebuild.request.settings();
        floor = plan.floor();
        coast = plan.coast();
        currents = plan.currents();
        terrainPixels = plan.colors();
        normalPixels = plan.normals();
        decalPixels = plan.decals();
        installedPaint = plan.paint();
        chunkRows = (scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (requested == rebuild.request) { requested = null; }
        rebuild = null;
        atlasChanges.clear();
        atlasTileChanges.clear();
        markingChanges.clear();
        ground.publish();
        decals.publish();
        tactical.publish();
        terrainBatch.clear();
        pickingSurfaces.clear();
        detailPixelsPerUnit = Float.NaN;
        hasCutaways = chunks.stream().anyMatch(chunk -> !chunk.cutaways.isEmpty());
        Set<GpuBuilding.Assembly> liveBuildings = new HashSet<>();
        Set<Model> liveIndustrial = new HashSet<>();
        for (Chunk chunk : chunks) {
            for (Prop prop : chunk.props) { if (prop.building != null) { liveBuildings.add(prop.building); } }
            for (Prop prop : chunk.props) { if (prop.industrial) { liveIndustrial.add(prop.instance.model); } }
        }
        for (Chunk chunk : detailCache.values()) {
            for (Prop prop : chunk.props) { if (prop.building != null) { liveBuildings.add(prop.building); } }
            for (Prop prop : chunk.props) { if (prop.industrial) { liveIndustrial.add(prop.instance.model); } }
        }
        Set<Mesh> sharedSources = new HashSet<>();
        for (Chunk chunk : chunks) {
            for (Renderable part : chunk.sharedShadows) { sharedSources.add(part.meshPart.mesh); }
            for (Renderable part : chunk.sharedProps) { sharedSources.add(part.meshPart.mesh); }
        }
        for (Chunk chunk : detailCache.values()) {
            for (Renderable part : chunk.sharedShadows) { sharedSources.add(part.meshPart.mesh); }
            for (Renderable part : chunk.sharedProps) { sharedSources.add(part.meshPart.mesh); }
        }
        trees.retainParts(sharedSources);
        assets.retainBuildings(liveBuildings);
        assets.retainIndustrial(liveIndustrial);
        updateLight(scene.light());
    }

    /** Retain one layout per sculpt variant, including ice/volcanic flags discovered in actual chunk materials. */
    private void prepareTerrainMaterial(Material material, VertexAttributes attributes) {
        for (Renderable sample : terrainMaterialSamples) {
            if (sample.material.getMask() == material.getMask()
                  && sample.meshPart.mesh.getVertexAttributes().equals(attributes)) { return; }
        }
        Renderable sample = new Renderable();
        sample.material = new Material(material);
        sample.environment = environment;
        sample.meshPart.set("terrain-material-warmup", new Mesh(true, 0, 0, attributes), 0, 0, GL20.GL_TRIANGLES);
        terrainMaterialSamples.add(sample);
        terrainMaterialsReady = false;
    }

    /** Driver links continue across loading frames. Retained samples are checked again when lighting flags change. */
    private void prepareTerrainMaterials() {
        if (terrainMaterialSamples.isEmpty()) {
            // Start the common program while CPU workers build the board. Chunk materials queue any mixed,
            // ice or volcanic variants they actually use; a uniform board never needs a hypothetical blend.
            prepareTerrainMaterial(sculptMaterial(BoardScene.Surface.GRASS), MeshBatch.STANDARD);
        }
        if (terrainMaterialPreparation == null || terrainMaterialEnvironment != environment.getMask()
              || terrainMaterialShadows != (environment.shadowMap != null)
              || terrainMaterialProgress.snapshot().total() != terrainMaterialSamples.size()) {
            terrainMaterialProgress.begin("materials", terrainMaterialSamples.size());
        }
        if (terrainMaterialPreparation == null) { terrainMaterialPreparation = new GpuGlsl.Preparation(); }
        terrainMaterialEnvironment = environment.getMask();
        terrainMaterialShadows = environment.shadowMap != null;
        terrainMaterialsReady = true;
        int completed = 0;
        for (Renderable sample : terrainMaterialSamples) {
            if (terrainMaterialPreparation.run(() -> sample.shader = batch.getShaderProvider().getShader(sample))) {
                completed++;
            } else { terrainMaterialsReady = false; }
        }
        while (terrainMaterialProgress.snapshot().completed() < completed) { terrainMaterialProgress.advance(); }
    }

    private void drainRetired(long deadline) {
        while (!retiredChunks.isEmpty() && System.nanoTime() < deadline) { retiredChunks.removeFirst().dispose(); }
        if (retiredChunks.isEmpty()) {
            ground.releaseRetiredPages();
            decals.releaseRetiredPages();
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
                  || !Objects.equals(before.tilesetDecals(), after.tilesetDecals())
                  || !Objects.equals(before.tilesetScenery(), after.tilesetScenery())) { return false; }
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

    /** The CPU side of one chunk: pure terrain work that any worker can run, with no GL or renderer state. */
    static Prepared prepare(BoardScene scene, int startX, int startY, float floor, TerrainLod lod,
          Map<Coords, BoardFlow.Current> currents, TerrainSettings settings, Set<Coords> reused,
          Map<Coords, BoardSurface.WaterGeometry> waterShapes, Runnable check, TerrainLoadProgress progress,
          GpuRoads.Masks masks) {
        return prepare(scene, startX, startY, floor, lod, currents, settings, reused, waterShapes, check, progress,
              masks, BoardDecals.index(scene));
    }

    private static Prepared prepare(BoardScene scene, int startX, int startY, float floor, TerrainLod lod,
          Map<Coords, BoardFlow.Current> currents, TerrainSettings settings, Set<Coords> reused,
          Map<Coords, BoardSurface.WaterGeometry> waterShapes, Runnable check, TerrainLoadProgress progress,
          GpuRoads.Masks masks, Map<Coords, List<BoardDecals.Stamp>> paint) {
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
                        patches.add(new RoadPatch(patch, masks.share(GpuRoads.mask(road, patch)), triangles, false));
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
                var slope = deck.sloped() ? BoardBridgeSlope.build(tile, deck, footing) : null;
                if (slope != null) { bridgeShapes.put(tile.coords(), slope); }
                else if (!footing.shape().facets().isEmpty()) { bridgeShapes.put(tile.coords(), footing.shape()); }
                BoardRoad road = footing.road(deck, tile.coords());
                for (var patch : GpuRoads.deckPatches(tile, deck, road, footing)) {
                    check.run();
                    if (patch.shape().isEmpty()) { continue; }
                    patches.add(new RoadPatch(patch, masks.share(GpuRoads.mask(road, patch)),
                          GpuRoads.deck(tile, feature, patch, footing, surfaces, slope), true));
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
              bridges, bridgeShapes, walls, reused, paint);
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

    /** Collect the existing approach faces for concrete and leave the remaining terrain on its native material. */
    private static List<BoardSurface.Face> collectBridgeSupports(BoardSurface surface, List<BoardSurface.Face> faces,
          List<BoardSurface.Face> supports) {
        if (surface.ramps == 0) { return faces; }
        var ground = new ArrayList<BoardSurface.Face>();
        for (var face : faces) {
            if (surface.bridgeSupport(face)) { supports.add(face); }
            else { ground.add(face); }
        }
        return ground;
    }

    private void collectTile(ChunkBuild build, BoardScene.Tile tile) {
        BoardScene scene = build.scene;
        Prepared prepared = build.prepared;
        Map<Coords, BoardSurface> surfaces = prepared.surfaces();
        Chunk chunk = build.chunk;
        float floor = build.floor;
        TerrainLod lod = chunk.lod;
        Layer solid = build.layers.get(0), scatter = build.layers.get(1), overlay = build.layers.get(2),
              liquid = build.layers.get(3);
        Map<String, LiquidSurface> animations = build.animations;
        BoardSurface surface = surfaces.get(tile.coords());
        List<BoardSurface.Face> smoothTop = new ArrayList<>();
        List<BoardSurface.Face> outcrops = new ArrayList<>();
        List<BoardSurface.Face> bridgeSupports = new ArrayList<>();
        TextureRegion top = ground.region(new GroundSlot(tile.coords(), true));
        boolean sculpted = surface.relief.sculpted();
        Material magma = tile.liquid().volcanic() ? GpuMagmaShader.material(assets,
              tile.liquid().molten() ? GpuMagmaShader.BANK : GpuMagmaShader.CRUST, chunk.lavaField) : null;
        if (sculpted) {
            sculpt(solid, overlay, chunk, scene, tile, surface, top, surfaces, prepared.sculpts().get(tile.coords()), magma);
        }
        // Field stones and shrubs share the scatter pass, while retaining their canonical picking/support faces.
        var fieldScatter = surface.faces.stream().filter(BoardSurface.Face::scatter).toList();
        if (!fieldScatter.isEmpty()) {
            BoundingBox bounds = new BoundingBox().inf();
            for (var face : fieldScatter) { bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
            chunk.bounds.ext(bounds);
            chunk.scatterDiameter = Math.max(chunk.scatterDiameter, bounds.getDimensions(new Vector3()).len());
            for (var group : byFamily(surface, fieldScatter).entrySet()) {
                Material material = sculptMaterial(group.getKey());
                if (sculpted && !tile.liquid().present()) { material = GpuIceShader.ground(material, scene, tile); }
                float shore = tile.liquid().present() ? GpuWaterShader.palette(tile.liquid()) : Float.NaN;
                scatter.addTriangles(material, mesh -> sculptedFaces(mesh, surface, group.getValue(), shore, scene, surfaces));
            }
        }
        if (tile.frozen() && tile.water()) {
            // One shared material in the existing transparent pass reveals the already-rendered bed/units.
            // No per-hex texture, refraction target or additional surface mesh is needed.
            Material ice = GpuIceShader.lake(material(assets.ice().color(), true));
            ice.set(new Ground(1));
            liquid.addTriangles(ice, meshes -> drawnIce(meshes, surface));
        }
        for (BoardSurface.Face face : sculpted ? List.<BoardSurface.Face>of() : surface.faces) {
            if (face.scatter()) { continue; }
            if (face.finish() == BoardSurface.Finish.ROUGH) { continue; }
            if (face.finish() == BoardSurface.Finish.ICE) { continue; }
            chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c());
            if (surface.bridgeSupport(face)) { bridgeSupports.add(face); continue; }
            if (tile.ultraSublevel()) {
                solid.add(pitMaterial(), mesh -> surface(mesh, tile.coords(), face, null, 0));
                continue;
            }
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
                        new Color(1, (tile.elevation() + 64) / 255f, 0, earthwork))), scene, tile, chunk.lavaField);
        }
        if (!sculpted && !surface.retainingWalls.isEmpty()) {
            var walls = collectBridgeSupports(surface, surface.retainingWalls, bridgeSupports);
            blended(solid, GpuSurfaceBlend.prepare(scene, tile, walls,
                  p -> sculptVertex(p, surface.relief.shade(p), Float.NaN, surface)), null, null, chunk.lavaField);
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
        if (!tile.ultraSublevel() && !tile.detailedGround() && !tile.liquid().present() && tile.roadExits() == 0
              && surface.ramps == 0 && BoardGeometry.tuning().gridShade() < 1) {
            solid.add(groundMaterial(top.getTexture(), scene, tile),
                  mesh -> grid(mesh, tile.coords(), BoardGeometry.groundZ(tile), top));
        }
        for (var wall : prepared.walls().getOrDefault(tile.coords(), Map.of()).entrySet()) {
            BoardSurface.Side side = wall.getKey();
            if (magma != null) {
                blended(solid, GpuSurfaceBlend.prepare(scene, tile, wall.getValue(),
                      p -> sculptVertex(p, surface.relief.shade(p), Float.NaN, surface)), null, null, chunk.lavaField);
                for (var face : wall.getValue()) {
                    chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c());
                }
                continue;
            }
            for (var face : wall.getValue()) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
            var wallFaces = collectBridgeSupports(surface, wall.getValue(), bridgeSupports);
            if (wallFaces.isEmpty()) { continue; }
            if (surface.relief.naturalEdge(side.edge())) {
                blended(solid, GpuSurfaceBlend.prepare(scene, tile, wallFaces,
                      p -> sculptVertex(p, surface.relief.shade(p), Float.NaN, surface)), null, null, chunk.lavaField);
                continue;
            }
            solid.addTriangles(sculptMaterial(tile.surface()),
                  mesh -> sculptedFaces(mesh, surface, wallFaces, Float.NaN, scene, surfaces));
        }
        if (!bridgeSupports.isEmpty()) {
            solid.addTriangles(material(assets.material("sculpt/concrete"), false), meshes -> {
                for (var face : bridgeSupports) {
                    var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    bridgeFace(meshes.get(), face.a(), face.b(), face.c(), normal);
                }
            });
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
            TextureAttribute waterTexture = water.get(TextureAttribute.class, TextureAttribute.Diffuse);
            // Magma maps itself in world space; its sheet keeps texture coordinates that span the hex like artwork.
            TextureRegion waterArt = new TextureRegion(waterTexture == null ? rainNoise : waterTexture.textureDescription.texture);
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
        if (tile.surface() == BoardScene.Surface.FUNGUS) {
            List<BoardSurface.Face> cliffs = new ArrayList<>(surface.faces);
            cliffs.addAll(surface.walls(scene, floor, surfaces));
            for (var placement : BoardFungus.cliffs(scene, tile, cliffs)) {
                ModelInstance instance = new ModelInstance(foliage(assets.lodModel(placement.asset(), 0)));
                instance.transform.set(placement.transform());
                BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform);
                var prop = new Prop(tile.coords(), instance, bounds, placement.asset(), false);
                prop.scatter = true;
                chunk.props.add(prop);
                chunk.bounds.ext(bounds);
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
        var features = tile.features();
        if (features.stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.SCENERY || f.authoredPlacement() || f.decoration() != null)) {
            // Install solid supports first, regardless of the captured artwork layer order.
            features = features.stream().sorted(Comparator.comparing(f -> f.kind() == BoardScene.FeatureKind.SCENERY
                  || f.authoredPlacement() || f.decoration() != null)).toList();
        }
        List<Prop> supports = new ArrayList<>();
        for (BoardScene.Feature feature : features) {
            if (feature.decoration() != null && feature.decoration().kind().equals("decal")) { continue; }
            // Rough boulders are already part of the shared terrain mesh, shading and picking geometry.
            if (feature.kind() == BoardScene.FeatureKind.BOULDER || feature.kind() == BoardScene.FeatureKind.ROUGH) { continue; }
            boolean fungus = BoardFungus.asset(feature.asset());
            boolean fungusScatter = fungus && feature.kind() == BoardScene.FeatureKind.SCATTER;
            if (fungusScatter && (!BoardScatter.allowed(tile) || tile.liquid().present() || surface.ramps != 0)) { continue; }
            if (feature.kind() == BoardScene.FeatureKind.SCATTER && !fungus) {
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
            boolean generatedIndustrial = BoardIndustrial.supports(feature);
            GpuBuilding.Assembly building = generatedIndustrial || feature.decoration() != null ? null : assets.building(feature.asset(), Math.round(feature.height()),
                  GpuBuilding.seed(tile, feature), feature.kind() == BoardScene.FeatureKind.BUILDING);
            Model model = feature.decoration() != null ? assets.decoration(feature.asset())
                  : generatedIndustrial ? assets.industrial(BoardIndustrial.layout(scene, tile, feature))
                  : building != null ? building.model(0)
                  : limb ? limbModel : feature.kind() == BoardScene.FeatureKind.TREE
                  ? assets.lodModel(feature.asset(), 0)
                  : assets.model(bridge ? BoardBridge.asset(feature.bridgeExits()) : feature.asset());
            ModelInstance instance = building != null ? building.instance(0)
                  : new ModelInstance(feature.kind() == BoardScene.FeatureKind.TREE || fungus || foliageModel(model) ? foliage(model) : model);
            if (bridge) {
                Material deck = instance.getMaterial("bridge-deck");
                deck.set(roadMaterial(GpuRoads.texture(prepared.bridges().get(tile.coords()).kind()).substring("roads/".length()), false));
                deck.set(GpuIceShader.road(deck, scene, tile));
                deck.set(new BridgeDeck());
                if (bridgeShape != null) { bridgeFooting(solid, bridgeShape, deck, instance.getMaterial("bridge-structure")); }
                if (prepared.bridges().get(tile.coords()).sloped()) { continue; }
            }
            for (Material material : instance.materials) {
                if (building == null && material.id.equals("wall")) {
                    material.get(TextureAttribute.class, TextureAttribute.Diffuse).scaleV = feature.height();
                }
                if (material.id.startsWith("geyser-") || material.id.startsWith("rubble-")
                      || material.id.startsWith("fortified-")) {
                    // Terrain scenery shares the ground's lighting and weather response.
                    material.set(new Ground(.15f));
                    if (material.id.equals("rubble-concrete")) {
                        material.set(TextureAttribute.createNormal(assets.material("sculpt/concrete-normal")));
                    } else if (material.id.equals("geyser-rock")) {
                        material.set(TextureAttribute.createNormal(assets.material(feature.asset().endsWith("magma")
                              ? "sculpt/volcano-basalt-normal" : "sculpt/rock-normal")));
                    } else if (material.id.equals("geyser-lava")) {
                        Material lava = GpuMagmaShader.material(assets, GpuMagmaShader.LAVA, null);
                        if (lava != null) { for (Attribute attribute : lava) { material.set(attribute.copy()); } }
                    }
                }
            }
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox());
            boolean coral = feature.asset().startsWith("mars/");
            // Coral colonies have wide, uneven mineral roots. Embed that root layer while preserving cover height.
            float rootInset = coral ? bounds.getDepth() * .06f : 0;
            float coralScale = coral ? feature.height() * BoardGeometry.level() / (bounds.getDepth() - rootInset) : 0;
            float plantingRadius = coral ? build.coralRootRadii.computeIfAbsent(model,
                  key -> coralRootRadius(key, bounds.min.z + rootInset)) * coralScale : BoardRelief.metres(.8f);
            float px = BoardGeometry.centerX(tile.coords()) + feature.x() * BoardGeometry.hexScale();
            float py = BoardGeometry.centerY(tile.coords()) + feature.y() * BoardGeometry.hexScale();
            if (feature.kind() == BoardScene.FeatureKind.TREE && !feature.authoredPlacement() || fungusScatter) {
                // A tree stands on its hex's own ground, never over a receding rim or a transition's slope.
                // Roadside cover keeps its captured route clearance instead of being pulled toward the carriageway.
                float[] spot = surface.relief.settle(px, py,
                      coral && tile.road() != BoardRoad.Kind.NONE ? BoardRelief.metres(.8f) : plantingRadius);
                px = spot[0];
                py = spot[1];
            }
            // The deck meets the road surface, including its small clearance above the ground.
            float base = bridge
                  ? tile.elevation() * BoardGeometry.level() + GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale()
                  : surface.height(px, py);
            if (coral) {
                // Sample the drawn terrain around the roots; a high point must not suspend the rest of the base.
                for (int sample = 0; sample < 8; sample++) {
                    double angle = sample * Math.PI / 4;
                    float x = px + plantingRadius * (float) Math.cos(angle);
                    float y = py + plantingRadius * (float) Math.sin(angle);
                    var receiving = BoardGeometry.tile(scene, x, y);
                    var support = receiving == null ? surface : surfaces.getOrDefault(receiving.coords(), surface);
                    base = Math.min(base, support.height(x, y));
                }
                base -= (bounds.min.z + rootInset) * coralScale;
            }
            if (feature.decoration() != null) {
                var object = feature.decoration();
                float anchorX = BoardGeometry.centerX(tile.coords()) + (float) object.x() * BoardGeometry.width();
                float anchorY = BoardGeometry.centerY(tile.coords()) + (float) object.y() * BoardGeometry.height();
                // A held drag keeps its original owner until release, but already stands on its destination surface.
                var footprint = BoardGeometry.tile(scene, anchorX, anchorY);
                Coords receiving = footprint == null ? tile.coords() : footprint.coords();
                BoardSurface receivingSurface = surfaces.computeIfAbsent(receiving, at -> new BoardSurface(scene, scene.tile(at), chunk.lod));
                TileMesh receivingMesh = receiving.equals(tile.coords()) ? null : editorTile(receiving);
                var receivingBridge = prepared.bridgeShapes().get(receiving);
                if (receivingBridge == null && receivingMesh != null) { receivingBridge = receivingMesh.bridgeShape; }
                float receiver = decorationHeight(feature, receivingSurface.height(anchorX, anchorY), receivingBridge,
                      receivingMesh == null ? supports : receivingMesh.props, anchorX, anchorY)
                      + feature.elevation() * BoardGeometry.level();
                if (!Float.isFinite(receiver)) { continue; }
                placeDecoration(instance, tile.coords(), feature, receiver);
            } else if (limb) {
                // Lay the held limb on its side, then ground its actual bounds. No unit/entity is created.
                float scale = feature.scale() * BoardGeometry.unitScale() * BoardGeometry.hexScale();
                instance.transform.setToTranslation(px, py, base).rotate(Vector3.Z, feature.rotation())
                      .rotate(Vector3.Y, 90).scale(scale, scale, scale);
                BoundingBox placed = new BoundingBox(bounds).mul(instance.transform);
                instance.transform.val[Matrix4.M23] += base - placed.min.z + .12f * BoardGeometry.hexScale();
            } else if (feature.authoredPlacement()) {
                // Components share their composition's supporting plane and retain their local origins and offsets.
                float scale = feature.scale() * BoardGeometry.hexScale();
                base = scenerySupport(surface, prepared.bridgeShapes().get(tile.coords()), supports,
                      BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords()));
                instance.transform.setToTranslation(px, py,
                            base + feature.elevation() * BoardGeometry.MODEL_LEVEL_HEIGHT * BoardGeometry.hexScale())
                      .rotate(Vector3.Z, feature.rotation()).scale(scale, scale, scale);
            } else if (feature.kind() == BoardScene.FeatureKind.SCENERY) {
                // Scenery is authored in tile units, like bridge furniture. A game level is not its thickness.
                float scale = feature.scale() * BoardGeometry.hexScale();
                base = scenerySupport(surface, prepared.bridgeShapes().get(tile.coords()), supports, px, py);
                instance.transform.setToTranslation(px, py, base - bounds.min.z * scale)
                      .rotate(Vector3.Z, feature.rotation()).scale(scale, scale, scale);
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
                      || fungus || feature.kind() == BoardScene.FeatureKind.BUILDING || feature.asset().startsWith("buildings/");
                float sourceHeight = fitHeight ? bounds.getDepth() : 1;
                float verticalScale = coral ? coralScale
                      : building == null ? feature.height() * BoardGeometry.level() / sourceHeight
                      : BoardGeometry.level() / GpuBuilding.LEVEL_HEIGHT;
                // Authored colonies and understory retain their proportions when fitted to the foliage height.
                float horizontalScale = fungus || coral
                      || feature.asset().startsWith("foliage-") ? verticalScale
                      : feature.scale() * BoardGeometry.hexScale();
                instance.transform.setToTranslation(px, py,
                      base + feature.elevation() * BoardGeometry.level())
                      .rotate(Vector3.Z, feature.rotation())
                      .scale(horizontalScale, horizontalScale, verticalScale);
            }
            bounds.mul(instance.transform);
            GpuGeysers.Emitter geyser = GpuGeysers.emitter(feature.asset(), instance.transform);
            if (geyser != null) {
                chunk.geysers.add(geyser);
                geyserBounds(bounds, geyser);
            }
            if (fungusScatter && (surface.relief.obstructed(new Vector3(px, py, base),
                  Math.max(bounds.getWidth(), bounds.getHeight()) * .5f, bounds.getDepth())
                  || !surface.relief.visibleScatter(new Vector3(px, py, base), bounds.getDepth()))) { continue; }
            var prop = new Prop(tile.coords(), instance, bounds,
                  feature.decoration() == null && (feature.kind() == BoardScene.FeatureKind.TREE || fungus) ? feature.asset() : null,
                  feature.kind() == BoardScene.FeatureKind.LIMB, building,
                  feature.decoration() != null || feature.kind() == BoardScene.FeatureKind.BUILDING || feature.asset().startsWith("buildings/"),
                  Float.NaN, feature.kind() == BoardScene.FeatureKind.INDUSTRIAL);
            prop.scatter = feature.kind() == BoardScene.FeatureKind.SCATTER;
            prop.decorationId = feature.decoration() == null ? null : feature.decoration().id();
            if (prop.decorationId != null) {
                prop.decorationIndex = (int) tile.features().subList(0, tile.features().indexOf(feature)).stream()
                      .filter(part -> part.decoration() != null && part.decoration().id().equals(prop.decorationId)).count();
                prop.decorationFeature = feature;
            }
            prop.decorationAnchorZ = instance.transform.val[Matrix4.M23] - feature.elevation() * BoardGeometry.level();
            prop.receiver = feature.kind() == BoardScene.FeatureKind.BUILDING ? "building"
                  : feature.kind() == BoardScene.FeatureKind.INDUSTRIAL ? "industrial" : null;
            chunk.props.add(prop);
            if (feature.decoration() == null && (feature.kind() == BoardScene.FeatureKind.BUILDING
                  || feature.kind() == BoardScene.FeatureKind.INDUSTRIAL
                  || feature.kind() == BoardScene.FeatureKind.PROP && !bridge && !feature.asset().equals("field"))) {
                supports.add(prop);
            }
            if (feature.kind() == BoardScene.FeatureKind.BUILDING) {
                Model interior = building == null ? assets.interior(feature.asset(), Math.round(feature.height()))
                      : building.interior();
                ModelInstance struts = new ModelInstance(interior, "struts");
                struts.transform.set(instance.transform);
                chunk.struts.add(new InteriorStruts(tile.coords(), struts));
                // Each floor borrows its existing mesh range but owns its opacity and opaque/transparent pass choice.
                for (int level = 0; level < Math.round(feature.height()); level++) {
                    ModelInstance floorInstance = new ModelInstance(interior, "floors");
                    var parts = floorInstance.nodes.first().parts;
                    String id = "floor-" + level;
                    for (int part = parts.size - 1; part >= 0; part--) {
                        if (!parts.get(part).meshPart.id.equals(id)) { parts.removeIndex(part); }
                    }
                    floorInstance.transform.set(instance.transform);
                    BoundingBox floorBounds = floorInstance.calculateBoundingBox(new BoundingBox()).mul(floorInstance.transform);
                    chunk.props.add(new Prop(tile.coords(), floorInstance, floorBounds, null, false, null, true,
                          floorBounds.min.z));
                }
            }
            chunk.bounds.ext(bounds);
        }
        placeDecals(build, tile, surface, supports);
        placeDecorationPaint(build, tile, surface, supports);
    }

    /** Use the chosen support, or the sampled ground when that support has no surface beneath the anchor. */
    private float decorationHeight(BoardScene.Feature feature, float groundHeight, BoardBridge.Shape bridge,
          List<Prop> supports, float x, float y) {
        var placement = feature.decoration().placement();
        if (placement.mode().equals("absolute")) { return placement.level().floatValue() * BoardGeometry.level(); }
        String receiver = placement.receiver().terrain();
        float height = Float.NEGATIVE_INFINITY;
        if (receiver.equals("ground")) { height = groundHeight; }
        else if (receiver.equals("bridge") && bridge != null) {
            for (var facet : bridge.facets()) {
                if (facet.part() == BoardBridge.Part.TOP) {
                    height = Math.max(height, new BoardSurface.Face(facet.a(), facet.b(), facet.c(), BoardSurface.Finish.TOP).height(x, y));
                }
            }
        } else if (receiver.equals("building") || receiver.equals("industrial")) {
            var point = new Vector3();
            for (Prop support : supports) {
                if (!receiver.equals(support.receiver)) { continue; }
                var ray = new Ray(new Vector3(x, y, support.bounds.max.z + 1), new Vector3(0, 0, -1));
                if (hitProp(support, ray, point)) { height = Math.max(height, point.z); }
            }
        }
        return (Float.isFinite(height) ? height : groundHeight) + placement.offset().floatValue() * BoardGeometry.level();
    }

    private static void placeDecoration(ModelInstance instance, Coords coords, BoardScene.Feature feature, float z) {
        float scale = feature.scale() * BoardGeometry.hexScale();
        instance.transform.setToTranslation(BoardGeometry.centerX(coords) + feature.x() * BoardGeometry.hexScale(),
                    BoardGeometry.centerY(coords) + feature.y() * BoardGeometry.hexScale(), z)
              .rotate(Vector3.Z, feature.rotation()).scale(feature.decoration().mirror() ? -scale : scale, scale, scale);
        // Restore authored culling when unmirroring; reflected winding needs both faces in every pass.
        for (Material material : instance.materials) {
            Attribute cull = instance.model.getMaterial(material.id).get(IntAttribute.CullFace);
            if (feature.decoration().mirror()) { material.set(IntAttribute.createCullFace(GL20.GL_NONE)); }
            else if (cull != null) { material.set(cull.copy()); }
            else { material.remove(IntAttribute.CullFace); }
        }
    }

    /** Include an animated plume in both the installed and temporary placement's culling envelope. */
    private static void geyserBounds(BoundingBox bounds, GpuGeysers.Emitter geyser) {
        float reach = 20 * geyser.scale();
        bounds.ext(geyser.origin().x - reach, geyser.origin().y - reach, geyser.origin().z);
        bounds.ext(geyser.origin().x + reach, geyser.origin().y + reach,
              geyser.origin().z + (geyser.active() ? 40 : 15) * geyser.scale());
    }

    private List<BoardSurface.Face> decorationReceivers(String receiver, ChunkBuild build, BoardScene.Tile tile,
          BoardSurface surface, List<Prop> supports) {
        if (receiver.equals("ground")) {
            return surface.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.ICE && BoardDecals.upward(f)).toList();
        }
        List<BoardSurface.Face> faces = new ArrayList<>();
        if (receiver.equals("bridge")) {
            var bridge = build.prepared.bridgeShapes().get(tile.coords());
            if (bridge != null) {
                for (var facet : bridge.facets()) {
                    if (facet.part() == BoardBridge.Part.TOP) { faces.add(new BoardSurface.Face(facet.a(), facet.b(), facet.c(), BoardSurface.Finish.TOP)); }
                }
            }
        } else {
            for (Prop prop : supports) {
                if (!receiver.equals(prop.receiver)) { continue; }
                faces.addAll(receiverFaces(prop));
            }
        }
        return faces;
    }

    private void placeDecorationPaint(ChunkBuild build, BoardScene.Tile tile, BoardSurface surface, List<Prop> supports) {
        var paint = build.prepared.paint().getOrDefault(tile.coords(), List.of());
        for (var stamp : paint) {
            var object = stamp.object();
            Texture texture = assets.decorationPaint(object.asset());
            if (texture == null) { continue; }
            var receivers = decorationReceivers(object.placement().receiver().terrain(), build, tile, surface, supports);
            var faces = BoardDecals.project(stamp.owner(), tile.coords(), receivers, object);
            // The same order has the same lift in every recipient, independent of other paint in that hex.
            float lift = (BoardDecals.LIFT + .04f * (float) (.5 + Math.atan(object.drawOrder()) / Math.PI)) * BoardGeometry.hexScale();
            build.chunk.tileMeshes.get(tile.coords()).paint.add(new PaintedDecal(stamp, faces, lift));
            Material material = material(texture, true);
            for (int target : new int[] { 4, 5 }) {
                build.layers.get(target).addTriangles(material, meshes -> {
                    for (var face : faces) {
                        var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                        meshes.get().triangle(decorationVertex(face.a(), stamp.owner(), object, normal, lift),
                              decorationVertex(face.b(), stamp.owner(), object, normal, lift),
                              decorationVertex(face.c(), stamp.owner(), object, normal, lift));
                    }
                });
            }
        }
    }

    private static MeshPartBuilder.VertexInfo decorationVertex(Vector3 point, Coords coords,
          megamek.common.board.BoardDecoration object, Vector3 normal, float lift) {
        double angle = Math.toRadians(object.rotation());
        double dx = point.x - BoardGeometry.centerX(coords) - object.x() * BoardGeometry.width();
        double dy = point.y - BoardGeometry.centerY(coords) - object.y() * BoardGeometry.height();
        float u = .5f + (float) ((dx * Math.cos(angle) + dy * Math.sin(angle)) / (BoardGeometry.width() * object.scale())) * (object.mirror() ? -1 : 1);
        float v = .5f - (float) ((-dx * Math.sin(angle) + dy * Math.cos(angle)) / (BoardGeometry.height() * object.scale()));
        return vertex(new Vector3(point).add(0, 0, lift), normal, u, v, Color.WHITE);
    }

    private List<BoardSurface.Face> receiverFaces(Prop prop) {
        List<BoardSurface.Face> faces = new ArrayList<>();
        List<Vector3> points = new ArrayList<>();
        Map<Mesh, float[]> vertices = new HashMap<>();
        Map<Mesh, short[]> indices = new HashMap<>();
        for (Node node : prop.instance().nodes) { triangles(node, points, vertices, indices); }
        for (int i = 0; i < points.size(); i += 3) {
            var face = new BoardSurface.Face(points.get(i).mul(prop.instance().transform),
                  points.get(i + 1).mul(prop.instance().transform), points.get(i + 2).mul(prop.instance().transform),
                  BoardSurface.Finish.TOP);
            if (BoardDecals.upward(face)) { faces.add(face); }
        }
        return faces;
    }

    /** Project after supports are installed; both presentations borrow the same structure and bridge meshes. */
    private void placeDecals(ChunkBuild build, BoardScene.Tile tile, BoardSurface surface, List<Prop> supports) {
        if (decals(tile) == null && tile.tilesetDecals() == null && tile.tilesetScenery() == null) { return; }
        List<BoardSurface.Face> raised = new ArrayList<>();
        for (Prop prop : supports) { raised.addAll(receiverFaces(prop)); }
        var bridge = build.prepared.bridgeShapes().get(tile.coords());
        for (boolean original : new boolean[] { false, true }) {
            if (original ? tile.tilesetDecals() == null && tile.tilesetScenery() == null : decals(tile) == null) { continue; }
            List<BoardSurface.Face> receivers = new ArrayList<>(raised);
            if (bridge != null) {
                for (var facet : bridge.facets()) {
                    if (facet.part() != BoardBridge.Part.TOP) { continue; }
                    Vector3 a = new Vector3(facet.a()), b = new Vector3(facet.b()), c = new Vector3(facet.c());
                    if (original) { a.z = b.z = c.z = GpuTilesetTerrain.deckZ(tile); }
                    receivers.add(new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP));
                }
            }
            List<BoardSurface.Face> ground = original
                  ? GpuTilesetTerrain.column(build.scene, tile, build.floor).faces()
                  : surface.faces.stream().filter(face -> !tile.frozen() || !tile.water()
                        || face.finish() == BoardSurface.Finish.ICE).toList();
            if (!original || tile.tilesetDecals() != null) {
                List<BoardSurface.Face> faces = BoardDecals.project(tile.coords(), ground, receivers);
                TextureRegion art = decals.region(new DecalSlot(tile.coords(), original ? 1 : 0));
                build.layers.get(original ? 5 : 4).addTriangles(material(art.getTexture(), true), meshes -> {
                    for (var face : faces) {
                        surface(meshes.get(), tile.coords(), face, art, BoardDecals.LIFT * BoardGeometry.hexScale());
                    }
                });
                for (var face : faces) { build.chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
            }
            if (original && tile.tilesetScenery() != null) {
                // A sprite represents a rigid object, just like its 3D replacement. Keep its overhang at its anchor.
                Vector3 center = BoardGeometry.center(tile.coords(), 0);
                center.z = Float.NEGATIVE_INFINITY;
                for (var faces : List.of(ground, receivers)) {
                    for (var face : faces) { center.z = Math.max(center.z, face.height(center.x, center.y)); }
                }
                TextureRegion art = decals.region(new DecalSlot(tile.coords(), 2));
                build.layers.get(5).add(material(art.getTexture(), true), mesh -> {
                    for (int edge = 0; edge < 6; edge++) {
                        Vector3 a = BoardGeometry.corner(tile.coords(), 0, edge), b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
                        a.z = b.z = center.z;
                        surface(mesh, tile.coords(), new BoardSurface.Face(center, a, b, BoardSurface.Finish.TOP),
                              art, BoardDecals.LIFT * BoardGeometry.hexScale());
                    }
                });
                build.chunk.bounds.ext(center);
            }
        }
    }

    /** Drop onto the highest solid at the anchor. Water is never a support; roofs/decks use their drawn meshes. */
    private float scenerySupport(BoardSurface surface, BoardBridge.Shape bridge, List<Prop> supports, float x, float y) {
        float height = surface.height(x, y);
        for (var face : surface.faces) {
            if (face.finish() == BoardSurface.Finish.ICE) { height = Math.max(height, face.height(x, y)); }
        }
        var ray = new Ray(new Vector3(x, y, height + 1), new Vector3(0, 0, -1));
        var point = new Vector3();
        if (bridge != null) {
            ray.origin.z = Math.max(height, bridge.bounds().max.z) + 1;
            float distance = bridge.hit(ray);
            if (Float.isFinite(distance)) { height = Math.max(height, ray.origin.z - (float) Math.sqrt(distance)); }
        }
        for (Prop prop : supports) {
            ray.origin.z = Math.max(height, prop.bounds().max.z) + 1;
            if (hitProp(prop, ray, point)) { height = Math.max(height, point.z); }
        }
        return height;
    }

    /** The authored root layer determines clearance; upper fans and branches may overhang a narrow terrace. */
    private static float coralRootRadius(Model model, float rootTop) {
        float radiusSquared = 0;
        for (Mesh mesh : model.meshes) {
            int stride = mesh.getVertexSize() / Float.BYTES;
            int position = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
            float[] vertices = new float[mesh.getNumVertices() * stride];
            mesh.getVertices(vertices);
            for (int i = position; i < vertices.length; i += stride) {
                if (vertices[i + 2] <= rootTop) {
                    radiusSquared = Math.max(radiusSquared, vertices[i] * vertices[i] + vertices[i + 1] * vertices[i + 1]);
                }
            }
        }
        return (float) Math.sqrt(radiusSquared);
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
        final List<Layer> layers = List.of(new Layer(), new Layer(), new Layer(), new Layer(), new Layer(), new Layer());
        Material scatterMaterial;
        // Derived once per model while preparing a chunk; no extra model data survives construction.
        final Map<Model, Float> coralRootRadii = new IdentityHashMap<>();
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
                    tile.paint.addAll(previous.paint);
                    for (TileRange range : previous.ranges) {
                        Material material = reusedMaterials.computeIfAbsent(range.material(), original -> {
                            Material copy = new Material(original);
                            GpuWaterShader water = copy.get(GpuWaterShader.class, GpuWaterShader.TYPE);
                            if (water != null) { copy.set(water.withField(chunk.waterField)); }
                            GpuMagmaShader magma = copy.get(GpuMagmaShader.class, GpuMagmaShader.TYPE);
                            if (magma != null) { copy.set(magma.withField(chunk.lavaField)); }
                            var mask = copy.get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE);
                            if (mask != null) { copy.set(mask.withRegion(chunk.roadMasks.region(mask.data.pixels()))); }
                            return copy;
                        });
                        layers.get(range.layer()).reuse(range, material);
                    }
                    // Instances have mutable fading/LoD state, while their authored models remain asset-owned.
                    for (Prop prop : previous.props) {
                        Prop copy = new Prop(coords, new ModelInstance(prop.instance), prop.bounds,
                              prop.treeAsset, prop.hardSurface, prop.building, prop.structure, prop.floorZ, prop.industrial);
                        copy.scatter = prop.scatter;
                        copy.decorationId = prop.decorationId;
                        copy.decorationIndex = prop.decorationIndex;
                        copy.decorationFeature = prop.decorationFeature;
                        copy.decorationAnchorZ = prop.decorationAnchorZ;
                        copy.receiver = prop.receiver;
                        tile.props.add(copy);
                    }
                    for (InteriorStruts strut : previous.struts) {
                        tile.struts.add(new InteriorStruts(coords, new ModelInstance(strut.instance)));
                    }
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
            List<List<ModelInstance>> targets = List.of(chunk.opaque, chunk.scatter, chunk.overlays, chunk.water,
                  chunk.surfaceDecals, chunk.tilesetDecals);
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
            for (Array<Renderable> layer : List.of(chunk.terrainRenderables, chunk.scatterRenderables, chunk.waterRenderables)) {
                for (Renderable part : layer) {
                    if (part.material.has(Sculpt.TYPE)) {
                        prepareTerrainMaterial(part.material, part.meshPart.mesh.getVertexAttributes());
                    }
                }
            }
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
            Map<Coords, BoundingBox> fungalHexes = new LinkedHashMap<>();
            for (Prop prop : chunk.props) {
                if (prop.building != null && prop.building.interior() != null) { chunk.sharedInteriors.add(prop.building.interior()); }
                if (prop.tree()) {
                    chunk.treeDiameter = Math.max(chunk.treeDiameter, prop.treeDiameter);
                    if (prop.scatter) { chunk.scatterDiameter = Math.max(chunk.scatterDiameter, prop.treeDiameter); }
                    (prop.scatter ? chunk.scatterStand : chunk.stand).add(prop.treeAsset, prop.instance().transform);
                    var glow = GpuFungus.light(scene.height(), prop.coords(), prop.treeAsset, prop.bounds());
                    if (glow != null) { chunk.fungalLights.add(glow); }
                    GpuFungus.Emitter emitter = GpuFungus.emitter(prop.treeAsset, prop.bounds());
                    if (emitter != null) {
                        chunk.spores.add(emitter);
                        fungalHexes.computeIfAbsent(prop.coords(), at -> new BoundingBox().inf()).ext(prop.bounds());
                    }
                } else {
                    chunk.cutaways.add(prop);
                }
            }
            fungalHexes.forEach((at, colony) -> chunk.spores.add(GpuFungus.cloud(at, colony)));
            chunk.cacheProps(tacticalView);
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
        if (tacticalView) { return installedSettings.call(() -> tileset.surface(coverScene, coords, floor)); }
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
    record SculptPlan(List<BoardSurface.Face> walls, List<BoardSurface.Face> ground,
          List<BoardSurface.Face> formed, List<BoardSurface.Face> bed,
          Map<Vector3, Vector3> bedNormals,
          Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> blended) { }

    static SculptPlan prepareSculpt(BoardScene scene, BoardScene.Tile tile, BoardSurface surface, float floor,
          TerrainLod lod, Map<Coords, BoardSurface> surfaces) {
        List<BoardSurface.Face> walls = surface.walls(scene, floor);
        boolean liquid = tile.liquid().present();
        List<BoardSurface.Face> ground = new ArrayList<>();
        List<BoardSurface.Face> formed = new ArrayList<>(walls);
        List<BoardSurface.Face> bed = new ArrayList<>();
        for (BoardSurface.Face face : surface.faces) {
            // A water hex's banks are sculpted ground whatever its artwork; its ice keeps the artwork.
            if (face.scatter()) { continue; }
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
        float spacing = GpuSurfaceBlend.spacing(lod);
        var sampler = new BoardSurfaceBlend.Sampler(scene, tile);
        // Open-water banks belong to their adjacent land. Process them with the shared water field below,
        // before a water hex's authored mixture can consume these faces and repaint the shore with its nominal theme.
        if (openWater(tile) == null && BoardSurfaceBlend.cliffBoundary(scene, tile)) {
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
                      && (a.kind() == BoardRelief.Kind.GROUND || a.kind().cliff()
                            || a.kind() == BoardRelief.Kind.SUBMERGED_CLIFF)
                      && a.kind() == b.kind() && a.kind() == c.kind();
                if (cover) { blended.add(face); }
                return cover;
            });
            var waters = coveringWaters(scene, surface, surfaces);
            for (var face : blended) {
                boolean cliff = surface.relief.shade(face.a()).kind() != BoardRelief.Kind.GROUND;
                coveredPolygons(surface, face, waters, polygon -> GpuSurfaceBlend.appendPolygon(groups, BoardSurfaceBlend.family(tile),
                      polygon, p -> cliff ? sampler.sampleCliff(p.x, p.y, p.z) : sampler.sample(p.x, p.y, p.z), spacing));
            }
        }
        if (openWater(tile) != null) {
            // Every natural bank and bed triangle queries one shared field. Processing only the sectors facing
            // mixed land leaves radial wedges with the water hex's material, including at water-to-water edges.
            List<BoardSurface.Face> blended = new ArrayList<>();
            formed.removeIf(face -> {
                var land = face.landEdge() < 0 ? null
                      : scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(face.landEdge())));
                if (land != null && !land.liquid().present() && !BoardSurfaceBlend.natural(land)) { return false; }
                var a = surface.relief.shade(face.a());
                var b = surface.relief.shade(face.b());
                var c = surface.relief.shade(face.c());
                boolean cover = a != null && b != null && c != null
                      && (a.kind() == BoardRelief.Kind.GROUND || a.kind() == BoardRelief.Kind.SUBMERGED_CLIFF)
                      && b.kind() == a.kind() && c.kind() == a.kind();
                if (cover) { blended.add(face); }
                return cover;
            });
            var waters = coveringWaters(scene, surface, surfaces);
            for (var face : blended) {
                coveredPolygons(surface, face, waters, polygon -> GpuSurfaceBlend.appendPolygon(groups,
                      surface.family(face), polygon, p -> sampler.sample(p.x, p.y, p.z), spacing));
            }
            float shore = surface.bedUnderLevelWater() ? GpuWaterShader.palette(tile.liquid()) : Float.NaN;
            Function<Vector3, MeshPartBuilder.VertexInfo> vertices = p -> bedVertex(p, surface, bedNormals, shore);
            for (var face : bed) {
                Consumer<List<MeshPartBuilder.VertexInfo>> blend = polygon -> GpuSurfaceBlend.appendPolygon(groups,
                      surface.family(face), polygon, p -> sampler.sample(p.x, p.y, p.z), spacing);
                if (Float.isNaN(shore)) { coveredPolygons(face, waters, Integer.MAX_VALUE, vertices, blend); }
                else {
                    // The entire bed shares one water plane. Its surface's internal diagonals add no information
                    // here; clipping against them would turn a six-triangle floor into hundreds of slivers.
                    blend.accept(List.of(vertices.apply(face.a()), vertices.apply(face.b()), vertices.apply(face.c())));
                }
            }
            bed.clear();
        }
        return new SculptPlan(walls, ground, formed, bed, bedNormals, groups);
    }

    private void sculpt(Layer solid, Layer overlay, Chunk chunk, BoardScene scene, BoardScene.Tile tile,
          BoardSurface surface, TextureRegion top, Map<Coords, BoardSurface> surfaces, SculptPlan plan, Material magma) {
        List<BoardSurface.Face> walls = plan.walls(), ground = plan.ground(), formed = plan.formed();
        List<BoardSurface.Face> bed = plan.bed();
        // A water hex's ground carries its water's palette, so the shader wets it and tints it below the waterline.
        float shore = tile.liquid().present() ? GpuWaterShader.palette(tile.liquid()) : Float.NaN;
        blended(solid, plan.blended(), scene, tile, chunk.lavaField);
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
    }

    /** Use the ordinary terrain materials and chunk batches; the shell has no per-bridge GPU resource. */
    private void naturalBridge(Layer solid, BoardBridge.Shape shape) {
        float metre = BoardRelief.metres(1);
        var normals = wallNormals(shape.facets().stream().map(face ->
              new BoardSurface.Face(face.a(), face.b(), face.c(), BoardSurface.Finish.WALL, -1)).toList());
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
                    points[i++] = vertex(p, normals.get(p), top ? Math.max(.02f, 2.5f - below * 6) : above,
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
                    bridgeFace(meshes.get(), face.a(), face.b(), face.c(), face.normal());
                }
            });
        }
    }

    /** Decks, terminals and their supports share one world-space concrete texture scale. */
    private static void bridgeFace(MeshPartBuilder mesh, Vector3 a, Vector3 b, Vector3 c, Vector3 normal) {
        var points = new MeshPartBuilder.VertexInfo[3];
        int i = 0;
        float repeat = BoardGeometry.width() / 5;
        for (var p : List.of(a, b, c)) {
            float u = Math.abs(normal.z) > .5f || Math.abs(normal.x) <= .5f ? p.x : p.y;
            float v = Math.abs(normal.z) > .5f ? -p.y : -p.z;
            points[i++] = vertex(p, normal, u / repeat, v / repeat, Color.WHITE);
        }
        mesh.triangle(points[0], points[1], points[2]);
    }

    /** {@code lava} is the chunk's lava field: cooled banks, the lava's own and those of land beside it, glow at it. */
    private void blended(Layer solid, Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> groups,
          BoardScene scene, BoardScene.Tile tile, GpuWaterShader.Field lava) {
        for (var group : groups.entrySet()) {
            var palette = group.getKey();
            boolean boundary = palette.first() != palette.base() || palette.second() != palette.base() || palette.third() != palette.base();
            Material material = boundary ? blendMaterial(palette) : sculptMaterial(palette.base());
            GpuMagmaShader magma = material.get(GpuMagmaShader.class, GpuMagmaShader.TYPE);
            if (lava != null && magma != null) {
                material = new Material(material);
                material.set(magma.withField(lava));
            }
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
            if (road != null && patch.texture().equals(GpuRoads.texture(tile.road()))) {
                var style = tile.appearance().get("road");
                if (style != null) {
                    var variant = megamek.common.board.BoardEditorBlueprint.get().variant(style.variant());
                    String authored = style.material() != null ? style.material() : variant == null ? "" : variant.material();
                    if (!authored.isBlank()) { road = authored; }
                }
            }
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
                shared = p -> indices.computeIfAbsent(p, key -> mesh.vertex(bedVertex(key, surface, normals, shore)));
                previous = mesh;
            }
            mesh.triangle(shared.apply(face.a()), shared.apply(face.b()), shared.apply(face.c()));
        }
    }

    private static MeshPartBuilder.VertexInfo bedVertex(Vector3 p, BoardSurface surface,
          Map<Vector3, Vector3> normals, float shore) {
        BoardRelief.Shade bank = surface.relief.shade(p);
        if (bank != null) { return sculptVertex(p, bank, shore, surface); }
        Color data = Float.isNaN(shore) ? new Color(1, (surface.tile.elevation() + 64) / 255f, 0, .3f)
              : waterColor(1, surface.waterHeight(p.x, p.y), shoreTint(shore, 0));
        return vertex(p, normals.get(p), 99, 99, data);
    }

    private Material sculptMaterial(BoardScene.Surface family) {
        // The diffuse slot only enables the default vertex shader's UV output; the sculpt maps are bound below.
        Material material = new Material("sculpt-" + family, TextureAttribute.createDiffuse(rainNoise),
              IntAttribute.createCullFace(GL20.GL_BACK));
        material.set(new Sculpt(family.ordinal()), new Ground(family == BoardScene.Surface.SNOW ? -1
              : groundResponse(family)));
        material.set(new SculptTiles(sculptTiles(family)),
              new SculptLayers(assets.sculptArray(SCULPT_LAYERS), sculptLayers(family)));
        return material;
    }

    /** Metres per repeat of the family's ground, debris, wall and mantle maps. */
    private float[] sculptTiles(BoardScene.Surface family) {
        float[] tiles = new float[4];
        for (int role = 0; role < 4; role++) { tiles[role] = assets.sculptTile(SCULPT_MATERIALS[family.ordinal()][role]); }
        return tiles;
    }

    /** The colour/height layers of the family's ground, debris, wall and mantle maps in the sculpt array. */
    private static float[] sculptLayers(BoardScene.Surface family) {
        float[] layers = new float[4];
        for (int role = 0; role < 4; role++) { layers[role] = 2 * SCULPT_LAYERS.indexOf(SCULPT_MATERIALS[family.ordinal()][role]); }
        return layers;
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
        float[][] tiles = new float[4][], layers = new float[4][];
        for (int i = 0; i < families.size(); i++) {
            var family = families.get(i);
            ids[i] = family;
            var ordinary = family >= BoardSurfaceBlend.CRUST ? BoardScene.Surface.ROCK : BoardScene.Surface.values()[family];
            responses[i] = family >= BoardSurfaceBlend.CRUST ? -1 : groundResponse(ordinary);
            tiles[i] = sculptTiles(ordinary);
            layers[i] = sculptLayers(ordinary);
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
        Map<BoardSurface, WaterCover> coverage = new java.util.IdentityHashMap<>();
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
                WaterCover waters = coverage.computeIfAbsent(water, own -> coveringWaters(scene, own, surfaces));
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

    /** One drawn water triangle and its clipping planes, derived once per hex for every face it may cover. */
    private record WaterTop(BoardSurface water, BoardSurface.Face top, float minX, float maxX, float minY, float maxY,
          float maxZ, float nx, float ny, float nz, float[] edges) {
        /** World units of slack for float rounding before a piece beyond a clipping plane is left unclipped. */
        private static final float MARGIN = .01f;

        /** Whether a face's bounds can meet this triangle's; a face wholly above its surface stays dry. */
        boolean reaches(float faceMinX, float faceMaxX, float faceMinY, float faceMaxY, float faceMinZ) {
            return faceMaxX >= minX && faceMinX <= maxX && faceMaxY >= minY && faceMinY <= maxY && faceMinZ < maxZ;
        }

        /**
         * Whether every vertex of a piece lies beyond one of the four clipping planes by more than rounding. The
         * clips only ever cut a piece, so each crossing they create lies between two of its vertices and is beyond
         * that plane too: the clip at that plane would keep nothing, and the piece would be handed back whole.
         */
        boolean misses(List<MeshPartBuilder.VertexInfo> polygon) {
            for (int plane = 0; plane < 4; plane++) {
                Vector3 origin = plane == 1 ? top.b() : plane == 2 ? top.c() : top.a();
                float planeX = plane < 3 ? edges[plane * 2] : nx, planeY = plane < 3 ? edges[plane * 2 + 1] : ny;
                float planeZ = plane < 3 ? 0 : nz;
                boolean beyond = true;
                for (int i = 0; i < polygon.size() && beyond; i++) {
                    beyond = planeDistance(polygon.get(i).position, origin, planeX, planeY, planeZ) > MARGIN;
                }
                if (beyond) { return true; }
            }
            return false;
        }
    }

    /**
     * The drawn water triangles that can cover a hex's faces, in the order they are applied, binned on a grid so a
     * face tests the few triangles near it rather than every triangle of up to seven water hexes.
     */
    private static final class WaterCover {
        private final List<WaterTop> tops;
        private final float minX, minY, cell;
        private final int columns, rows;
        private final int[][] cells;
        private final int[] seen;
        private int[] candidates = new int[64];
        private int stamp;

        WaterCover(List<WaterTop> tops) {
            this.tops = tops;
            float left = Float.POSITIVE_INFINITY, bottom = Float.POSITIVE_INFINITY;
            float right = Float.NEGATIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
            for (WaterTop water : tops) {
                left = Math.min(left, water.minX());
                bottom = Math.min(bottom, water.minY());
                right = Math.max(right, water.maxX());
                top = Math.max(top, water.maxY());
            }
            // Water triangles span a fraction of a hex; cells of a sixth of a hex width hold a few of them each.
            cell = BoardGeometry.width() / 6;
            minX = left;
            minY = bottom;
            columns = tops.isEmpty() ? 0 : (int) ((right - left) / cell) + 1;
            rows = tops.isEmpty() ? 0 : (int) ((top - bottom) / cell) + 1;
            cells = new int[columns * rows][];
            seen = new int[tops.size()];
            int[] counts = new int[cells.length];
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < tops.size(); i++) {
                    WaterTop water = tops.get(i);
                    for (int r = row(water.minY()); r <= row(water.maxY()); r++) {
                        for (int c = column(water.minX()); c <= column(water.maxX()); c++) {
                            if (pass == 0) { counts[r * columns + c]++; } else { cells[r * columns + c][counts[r * columns + c]++] = i; }
                        }
                    }
                }
                if (pass == 0) {
                    for (int i = 0; i < cells.length; i++) { cells[i] = new int[counts[i]]; }
                    Arrays.fill(counts, 0);
                }
            }
        }

        private int column(float x) { return Math.clamp((int) ((x - minX) / cell), 0, columns - 1); }

        private int row(float y) { return Math.clamp((int) ((y - minY) / cell), 0, rows - 1); }

        /** The triangles whose bounds meet a face's, below a ceiling elevation, in application order. */
        int collect(float faceMinX, float faceMaxX, float faceMinY, float faceMaxY, float faceMinZ, int ceiling) {
            if (tops.isEmpty() || faceMaxX < minX || faceMaxY < minY
                  || faceMinX > minX + columns * cell || faceMinY > minY + rows * cell) { return 0; }
            stamp++;
            int count = 0;
            int lastRow = row(faceMaxY), lastColumn = column(faceMaxX);
            for (int r = row(faceMinY); r <= lastRow; r++) {
                for (int c = column(faceMinX); c <= lastColumn; c++) {
                    for (int index : cells[r * columns + c]) {
                        if (seen[index] == stamp) { continue; }
                        seen[index] = stamp;
                        WaterTop water = tops.get(index);
                        if (water.water().tile.elevation() >= ceiling
                              || !water.reaches(faceMinX, faceMaxX, faceMinY, faceMaxY, faceMinZ)) { continue; }
                        if (count == candidates.length) { candidates = Arrays.copyOf(candidates, count * 2); }
                        candidates[count++] = index;
                    }
                }
            }
            Arrays.sort(candidates, 0, count);
            return count;
        }

        WaterTop candidate(int i) { return tops.get(candidates[i]); }
    }

    private static WaterCover coveringWaters(BoardScene scene, BoardSurface own, Map<Coords, BoardSurface> surfaces) {
        List<WaterTop> joined = new ArrayList<>();
        waterTops(own, joined);
        // Rocks and cliff corners can project across a mouth into the next water hex.
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile next = openWater(scene.tile(own.tile.coords().translated(direction)));
            if (next != null) {
                waterTops(surfaces.computeIfAbsent(next.coords(), key -> new BoardSurface(scene, next)), joined);
            }
        }
        return new WaterCover(joined);
    }

    private static WaterCover waterTops(List<BoardSurface> waters) {
        List<WaterTop> result = new ArrayList<>();
        for (BoardSurface water : waters) { waterTops(water, result); }
        return new WaterCover(result);
    }

    /** The planes as Vector3's cross product and normalisation compute them, without a vector per polygon. */
    private static void waterTops(BoardSurface water, List<WaterTop> result) {
        for (BoardSurface.Face top : water.waterFaces) {
            Vector3 a = top.a(), b = top.b(), c = top.c();
            float abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z, acx = c.x - a.x, acy = c.y - a.y, acz = c.z - a.z;
            float nx = aby * acz - abz * acy, ny = abz * acx - abx * acz, nz = abx * acy - aby * acx;
            float length = nx * nx + ny * ny + nz * nz;
            if (length != 0 && length != 1) {
                float scale = 1f / (float) Math.sqrt(length);
                nx *= scale;
                ny *= scale;
                nz *= scale;
            }
            if (nz <= .00001f) { continue; }
            float[] edges = new float[6];
            for (int edge = 0; edge < 3; edge++) {
                Vector3 from = edge == 0 ? a : edge == 1 ? b : c, to = edge == 0 ? b : edge == 1 ? c : a;
                float ex = to.y - from.y, ey = from.x - to.x, span = ex * ex + ey * ey;
                if (span != 0 && span != 1) {
                    float scale = 1f / (float) Math.sqrt(span);
                    ex *= scale;
                    ey *= scale;
                }
                edges[edge * 2] = ex;
                edges[edge * 2 + 1] = ey;
            }
            result.add(new WaterTop(water, top, Math.min(a.x, Math.min(b.x, c.x)), Math.max(a.x, Math.max(b.x, c.x)),
                  Math.min(a.y, Math.min(b.y, c.y)), Math.max(a.y, Math.max(b.y, c.y)), Math.max(a.z, Math.max(b.z, c.z)),
                  nx, ny, nz, edges));
        }
    }

    /** Split at the drawn water triangles, so absorption cannot escape onto an exposed bank or cliff. */
    static void coveredFace(MeshPartBuilder mesh, BoardSurface surface, BoardSurface.Face face,
          List<BoardSurface> waters) {
        coveredFace(() -> mesh, surface, face, waterTops(waters));
    }

    private static void coveredFace(Supplier<MeshPartBuilder> triangles, BoardSurface surface, BoardSurface.Face face,
          WaterCover waters) {
        coveredPolygons(surface, face, waters, polygon -> surfacePolygon(triangles, polygon));
    }

    private static void coveredPolygons(BoardSurface surface, BoardSurface.Face face, WaterCover waters,
          Consumer<List<MeshPartBuilder.VertexInfo>> polygonConsumer) {
        BoardRelief.Shade a = surface.relief.shade(face.a()), b = surface.relief.shade(face.b()), c = surface.relief.shade(face.c());
        if (openWater(surface.tile) != null && submergedCliff(a) && submergedCliff(b) && submergedCliff(c)) {
            // This is the pool's own vertical bed boundary. Its projection lies on (or behind an undercut in)
            // the water outline, so an XY coverage test cannot decide whether it is submerged. Keep the bed's
            // waterline and material mapping; the shader uses height to leave the narrow emerged rim dry.
            float shore = GpuWaterShader.palette(surface.tile.liquid());
            polygonConsumer.accept(List.of(sculptVertex(face.a(), a, shore, surface),
                  sculptVertex(face.b(), b, shore, surface), sculptVertex(face.c(), c, shore, surface)));
            return;
        }
        // Exterior walls stand outside the basin. A recess beneath the upper pool's footprint is still solid
        // rock, not an infinitely deep water column. Only lower pools can submerge this side of the cliff;
        // the basin's own submerged walls are handled above.
        int ceiling = cliff(a) && cliff(b) && cliff(c) ? surface.tile.elevation() : Integer.MAX_VALUE;
        coveredPolygons(face, waters, ceiling, p -> sculptVertex(p, surface.relief.shade(p), Float.NaN, surface), polygonConsumer);
    }

    private static boolean submergedCliff(BoardRelief.Shade shade) {
        return shade != null && shade.kind() == BoardRelief.Kind.SUBMERGED_CLIFF;
    }

    private static boolean cliff(BoardRelief.Shade shade) { return shade != null && shade.kind().cliff(); }

    private static void coveredPolygons(BoardSurface.Face face, WaterCover waters, int ceiling,
          Function<Vector3, MeshPartBuilder.VertexInfo> vertices, Consumer<List<MeshPartBuilder.VertexInfo>> polygonConsumer) {
        List<List<MeshPartBuilder.VertexInfo>> dry = new ArrayList<>();
        dry.add(List.of(vertices.apply(face.a()), vertices.apply(face.b()), vertices.apply(face.c())));
        float minX = Math.min(face.a().x, Math.min(face.b().x, face.c().x));
        float maxX = Math.max(face.a().x, Math.max(face.b().x, face.c().x));
        float minY = Math.min(face.a().y, Math.min(face.b().y, face.c().y));
        float maxY = Math.max(face.a().y, Math.max(face.b().y, face.c().y));
        float minZ = Math.min(face.a().z, Math.min(face.b().z, face.c().z));
        int count = waters.collect(minX, maxX, minY, maxY, minZ, ceiling);
        for (int i = 0; i < count && !dry.isEmpty(); i++) {
            WaterTop water = waters.candidate(i);
            BoardSurface.Face top = water.top();
            Vector3 origin = top.a();
            float[] edges = water.edges();
            // The pieces stay as they are until this triangle actually splits one of them.
            List<List<MeshPartBuilder.VertexInfo>> remaining = null;
            for (int index = 0; index < dry.size(); index++) {
                List<MeshPartBuilder.VertexInfo> polygon = dry.get(index);
                boolean reaches = !water.misses(polygon);
                List<MeshPartBuilder.VertexInfo> wet = reaches ? polygon : List.of();
                List<List<MeshPartBuilder.VertexInfo>> outside = reaches ? new ArrayList<>() : List.of();
                for (int edge = 0; edge < 3 && wet.size() >= 3; edge++) {
                    Vector3 corner = edge == 0 ? top.a() : edge == 1 ? top.b() : top.c();
                    wet = splitCovered(wet, corner, edges[edge * 2], edges[edge * 2 + 1], 0, outside);
                }
                if (wet.size() >= 3) { wet = splitCovered(wet, origin, water.nx(), water.ny(), water.nz(), outside); }
                if (wet.size() >= 3) {
                    if (remaining == null) { remaining = new ArrayList<>(dry.subList(0, index)); }
                    remaining.addAll(outside);
                    List<MeshPartBuilder.VertexInfo> tinted = new ArrayList<>();
                    for (var vertex : wet) {
                        Vector3 p = vertex.position;
                        float height = origin.z - (water.nx() * (p.x - origin.x) + water.ny() * (p.y - origin.y)) / water.nz();
                        float kind = vertex.color.b;
                        boolean ground = kind < .125f;
                        Color data = waterColor(vertex.color.r, height, shoreTint(GpuWaterShader.palette(water.water().tile.liquid()),
                              ground ? (vertex.color.a - .3f) / .1f : 0));
                        if (!ground) { data.b = (224 + 240 * data.b) / 255f; }
                        tinted.add(new MeshPartBuilder.VertexInfo().set(vertex).setCol(data));
                    }
                    polygonConsumer.accept(tinted);
                } else if (remaining != null) { remaining.add(polygon); }
            }
            if (remaining != null) { dry = remaining; }
        }
        dry.forEach(polygonConsumer);
    }

    /** Keep the half-space below a plane and retain the outside polygon for subsequent water triangles. */
    private static List<MeshPartBuilder.VertexInfo> splitCovered(List<MeshPartBuilder.VertexInfo> polygon,
          Vector3 origin, float nx, float ny, float nz, List<List<MeshPartBuilder.VertexInfo>> outside) {
        // Most clipping planes do not cross this polygon. Keep its vertices instead of allocating two
        // replacement lists for every nearby water triangle; only an actual crossing needs interpolation.
        int count = 0;
        for (var point : polygon) { if (planeDistance(point.position, origin, nx, ny, nz) <= 0) { count++; } }
        if (count == polygon.size()) { return polygon; }
        if (count == 0) {
            if (polygon.size() >= 3) { outside.add(polygon); }
            return List.of();
        }
        // A plane cut adds at most one vertex to each side.
        List<MeshPartBuilder.VertexInfo> inside = new ArrayList<>(polygon.size() + 1);
        List<MeshPartBuilder.VertexInfo> dry = new ArrayList<>(polygon.size() + 1);
        var previous = polygon.getLast();
        float before = planeDistance(previous.position, origin, nx, ny, nz);
        for (var point : polygon) {
            float after = planeDistance(point.position, origin, nx, ny, nz);
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

    private static float planeDistance(Vector3 point, Vector3 origin, float nx, float ny, float nz) {
        return (point.x - origin.x) * nx + (point.y - origin.y) * ny + (point.z - origin.z) * nz;
    }

    private static void surfacePolygon(Supplier<MeshPartBuilder> triangles, List<MeshPartBuilder.VertexInfo> polygon) {
        for (int i = 1; i + 1 < polygon.size(); i++) {
            var a = polygon.getFirst();
            var b = polygon.get(i);
            var c = polygon.get(i + 1);
            if (!GpuSurfaceBlend.degenerate(a.position, b.position, c.position)) {
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
            // .25 is the earlier procedural shrub format; .3 carries authored RGB in UV/alpha.
            case PLANT -> .3f;
            case CLIFF -> Float.isFinite(shade.projection()) ? .45f : .5f;
            case PIT_WALL -> .6f;
            case PIT -> .75f;
            case ROCK -> 1;
        };
        // A cliff carries its rockiness here; everything else its integer game level.
        float level = shade.kind().cliff() ? Math.clamp(shade.level(), 0, 1)
              : Math.clamp((Math.round(shade.level()) + 64) / 255f, 0, 1);
        boolean submergedCliff = shade.kind() == BoardRelief.Kind.SUBMERGED_CLIFF;
        boolean wet = !Float.isNaN(shore) && (shade.kind() == BoardRelief.Kind.GROUND || submergedCliff);
        // A raised stream cannot drown the bank outside its channel. Below the hex's own waterline, however, the
        // bank is submerged too, including where a beach meets the foot of a cliff.
        float water = wet ? surface.waterHeight(p.x, p.y) : 0;
        if (wet && !submergedCliff) { water = Math.min(water, Math.max(p.z, BoardGeometry.waterZ(surface.tile))); }
        Color data = wet ? waterColor(shade.occlusion(), water,
              shoreTint(shore, (shade.tint() - .3f) / .1f)) : new Color(shade.occlusion(), level, kind, shade.tint());
        if (shade.kind() == BoardRelief.Kind.CLIFF && Float.isFinite(shade.projection())) {
            data.a = .5f + shade.projection() / (2 * (float) Math.PI);
        }
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
        return BoardSurface.vertexNormals(faces);
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
            case DESERT, MARS -> 0.12f;
            case DIRT -> 0.15f;
            case GRASS -> 0.25f;
            case ROCK, LUNAR, VOLCANO -> 0.7f;
            case FUNGUS, TROPICAL -> 0.35f;
            case CONCRETE -> 1;
        };
    }

    /** Opaque black, without terrain pigment, specular response or transparency: the pit cannot reveal the sky. */
    static Material pitMaterial() {
        return new Material("pit", ColorAttribute.createDiffuse(Color.BLACK), IntAttribute.createCullFace(GL20.GL_NONE));
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

    /** Shows the liquid's current frame, moved on by its current or, falling, down its fall; magma on its own clock. */
    private void animateLiquid(LiquidSurface liquid) {
        GpuAssets.Animation<Texture> frames = assets.liquidAnimation(liquid.source());
        TextureAttribute texture = liquid.material().get(TextureAttribute.class, TextureAttribute.Diffuse);
        TextureAttribute emission = liquid.material().get(TextureAttribute.class, TextureAttribute.Emissive);
        float liquidTime = emission == null ? clock : magmaClock;
        int index = frames.index(liquidTime);
        Texture frame = frames.frames().get(index);
        float offsetU = (liquidTime * liquid.current().u()) % 1;
        float offsetV = liquid.falling() ? (liquidTime * (emission == null ? 1 : 0.25f)) % 1 : (liquidTime * liquid.current().v()) % 1;
        texture.textureDescription.texture = frame;
        texture.offsetU = offsetU;
        texture.offsetV = offsetV;
        TextureAttribute next = liquid.material().get(TextureAttribute.class, GpuLiquidShader.Frame.TYPE);
        if (next != null) {
            next.textureDescription.texture = frames.frames().get((index + 1) % frames.frames().size());
            liquid.material().get(FloatAttribute.class, GpuLiquidShader.Frame.BLEND).value = frames.blend(liquidTime, index);
        }
        if (emission != null) {
            emission.textureDescription.texture = frame;
            emission.offsetU = offsetU;
            emission.offsetV = offsetV;
        }
    }

    /** Magma glows in its own frames; hazardous liquid is tinted green. The Tactical View's liquids share this. */
    static void liquidColour(Material material, BoardLiquid liquid, Texture frame) {
        if (liquid.molten()) {
            material.set(ColorAttribute.createDiffuse(0.35f, 0.35f, 0.35f, 1));
            material.set(TextureAttribute.createEmissive(frame), ColorAttribute.createEmissive(0.65f, 0.65f, 0.65f, 1));
        } else if (liquid.kind() == BoardLiquid.Kind.HAZARDOUS) {
            material.set(ColorAttribute.createDiffuse(0.4f, 1, 0.12f, 1));
        }
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
        if (!liquid.molten()) {
            // Premultiplied: the shader adds reflected and scattered light and removes only what the column absorbs.
            material.set(new BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA,
                  procedural ? 1 : falling ? 0.8f : GpuWaterShader.SURFACE_OPACITY));
            GpuWaterShader water = new GpuWaterShader(scene, surface, procedural, falling, field);
            material.set(water);
            if (!water.impacts.isEmpty()) { material.id += ":splash:" + surface.tile.coords(); }
        }
        liquidColour(material, liquid, texture);
        return material;
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 p, Vector3 normal, float u, float v, Color color) {
        return new MeshPartBuilder.VertexInfo().setPos(p).setNor(normal).setUV(u, v).setCol(color);
    }

    static MeshPartBuilder.VertexInfo topVertex(Vector3 p, Coords coords, TextureRegion region, Color color) {
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
        animate(delta, units, buildingAlpha, null, Float.NaN);
    }

    void animate(float delta, List<ModelInstance> units, float buildingAlpha, Coords hovered, float hoverFloorZ) {
        float nextBuilding = MathUtils.clamp(buildingAlpha, 0, 1);
        boolean changedOpacity = nextBuilding != buildingOpacity;
        buildingOpacity = nextBuilding;
        clock += delta;
        magmaClock += delta * (float) Math.sqrt(gravity / BoardAtmosphere.STANDARD_GRAVITY);
        // A shared, continuous gust clock: stronger wind moves faster without rephasing when the slider changes.
        vegetationPhase = (vegetationPhase + delta * (.65f + 3.35f * wind.z)) % MathUtils.PI2;
        if (chunks.stream().anyMatch(chunk -> chunk.lavaField != null)) { lavaOcean.update(clock, wind, gravity); }
        else if (lavaOcean.texture() != null) { lavaOcean.dispose(); }
        if (proceduralWater && waterEffects && chunks.stream().anyMatch(chunk -> chunk.waterField != null)) {
            ocean.update(clock, wind, gravity);
            waders.update(coverScene, units, units.stream().map(this::unitBounds).toList(), delta);
        }
        List<BoundingBox> occupied = hasCutaways && buildingOpacity < 1
              ? units.stream().map(this::unitBounds).toList() : List.of();
        BoardScene.Tile hoverTile = coverScene == null || hovered == null ? null : coverScene.tile(hovered);
        // The roof is the boundary above the last interior floor, never a hover cutaway target.
        boolean hoverInterior = buildingOpacity < 1 && hoverTile != null && hoverTile.features().stream().anyMatch(feature ->
              feature.kind() == BoardScene.FeatureKind.BUILDING
                    && hoverFloorZ >= (hoverTile.elevation() + feature.elevation()) * BoardGeometry.level()
                    && hoverFloorZ < (hoverTile.elevation() + feature.elevation() + feature.height()) * BoardGeometry.level());
        // The Tactical View's liquids run on the same clocks.
        if (tacticalView) { tileset.liquids().forEach(this::animateLiquid); }
        for (Chunk chunk : chunks) {
            chunk.liquidMaterials.forEach(this::animateLiquid);
            if (chunk.cutaways.isEmpty()) { continue; }
            Map<Coords, Float> occupiedHexes = new HashMap<>();
            for (BoundingBox unit : occupied) {
                if (!chunk.bounds.intersects(unit)) {
                    continue;
                }
                for (Prop prop : chunk.cutaways) {
                    if (!prop.industrial && Float.isNaN(prop.floorZ) && prop.bounds().intersects(unit)) {
                        occupiedHexes.merge(prop.coords(), unit.min.z, Math::min);
                    }
                }
            }
            Coords hoverCoords = hoverInterior && chunk.tileMeshes.containsKey(hovered) ? hovered : null;
            float hoverFloor = hoverCoords == null ? Float.NaN : hoverFloorZ;
            boolean changedHover = !java.util.Objects.equals(hoverCoords, chunk.hoverCoords)
                  || Float.compare(hoverFloor, chunk.hoverFloor) != 0;
            chunk.hoverCoords = hoverCoords;
            chunk.hoverFloor = hoverFloor;
            Set<Prop> faded = new HashSet<>();
            if (!occupiedHexes.isEmpty()) {
                for (Prop prop : chunk.cutaways) {
                    Float lowest = occupiedHexes.get(prop.coords());
                    boolean hoverSupport = prop.coords.equals(hoverCoords)
                          && Math.abs(prop.floorZ - hoverFloor) < .05f * BoardGeometry.hexScale();
                    if (prop.decorationId == null && !prop.industrial && lowest != null && (Float.isNaN(prop.floorZ)
                          || prop.floorZ > lowest + .05f * BoardGeometry.hexScale() && !hoverSupport)) {
                        faded.add(prop);
                    }
                }
            }
            if (hoverCoords != null) {
                for (Prop prop : chunk.cutaways) {
                    if (prop.decorationId == null && !prop.industrial && Float.isNaN(prop.floorZ) && prop.coords.equals(hoverCoords)) { faded.add(prop); }
                }
            }
            boolean changedOccupancy = !faded.equals(chunk.faded) || changedHover || chunk.cutaways.stream().anyMatch(prop ->
                  prop.decorationId == null && !prop.industrial && Float.isNaN(prop.floorZ) && prop.coords.equals(hoverCoords)
                        && (prop.hoverOpaque != null) != !occupiedHexes.containsKey(prop.coords));
            if (changedOccupancy || changedOpacity) {
                chunk.faded = Set.copyOf(faded);
                for (Prop prop : chunk.cutaways) {
                    if (prop.decorationId != null) { continue; }
                    boolean hoverShell = !prop.industrial && Float.isNaN(prop.floorZ) && prop.coords.equals(hoverCoords);
                    boolean hoverOnly = hoverShell && !occupiedHexes.containsKey(prop.coords);
                    for (Material material : prop.instance().materials) {
                        if (hoverOnly) {
                            material.set(new GpuBuildingCutaway(hoverFloor, hoverFloor + BoardGeometry.level(), true));
                        } else { material.remove(GpuBuildingCutaway.TYPE); }
                        if (faded.contains(prop)) {
                            BlendingAttribute blend = material.get(BlendingAttribute.class, BlendingAttribute.Type);
                            if (blend == null) {
                                blend = new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                                material.set(blend);
                                material.set(new DepthTestAttribute(GL20.GL_LEQUAL, false));
                                material.set(IntAttribute.createCullFace(GL20.GL_BACK));
                            }
                            blend.opacity = Float.isNaN(prop.floorZ) ? buildingOpacity
                                  : MathUtils.lerp(buildingOpacity, 1, .25f);
                        } else {
                            material.remove(BlendingAttribute.Type);
                            material.remove(DepthTestAttribute.Type);
                            material.remove(IntAttribute.CullFace);
                        }
                    }
                    if (hoverOnly) { prop.updateHoverOpaque(); }
                    else { prop.hoverOpaque = null; }
                }
                for (InteriorStruts strut : chunk.struts) {
                    for (Material material : strut.instance.materials) {
                        if (strut.coords.equals(hoverCoords) && !occupiedHexes.containsKey(strut.coords)) {
                            material.set(new GpuBuildingCutaway(hoverFloor, hoverFloor + BoardGeometry.level(), true));
                        } else { material.remove(GpuBuildingCutaway.TYPE); }
                    }
                }
                if (changedOccupancy) {
                    chunk.cacheProps(tacticalView);
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
            if (prop.coords().equals(coords) && tile != null && tile.bridgeShape != null) {
                result.ext(prop.bounds());
            } else if (prop.coords().equals(coords)
                  && (result == null || prop.bounds().max.z > result.max.z)) {
                result = prop.bounds();
            }
        }
        return result;
    }

    /** Pointer picks pass through foliage and industrial cover; occupiable structures retain their authored mesh. */
    Coords pick(BoardScene scene, Ray ray) {
        BoardGeometry.Hit hit = selectionHit(scene, ray);
        return hit == null ? null : hit.coords();
    }

    record DecorationHit(Coords coords, String id, float distance, float anchorZ) { }

    record EditorObject(String id, BoundingBox bounds, float anchorLevel) { }

    private record DecorationPart(String id, int part) { }
    private record EditorPlacement(Coords coords, BoardScene.Feature feature) { }
    /** Borrowed instances that crossed an owner boundary before the replacement chunks finish. */
    private Map<Coords, List<Prop>> editorMovedProps = Map.of();

    private TileMesh editorTile(Coords coords) {
        if (coords == null || chunkRows == 0) { return null; }
        int index = (coords.getX() / CHUNK_SIZE) * chunkRows + coords.getY() / CHUNK_SIZE;
        return index < 0 || index >= chunks.size() ? null : chunks.get(index).tileMeshes.get(coords);
    }

    private List<Prop> editorProps(Coords coords) {
        TileMesh tile = editorTile(coords);
        if (tile == null) { return List.of(); }
        List<Prop> moved = editorMovedProps.getOrDefault(coords, List.of());
        if (moved.isEmpty() && tile.props.stream().allMatch(prop -> coords.equals(prop.coords()))) { return tile.props; }
        List<Prop> result = new ArrayList<>(moved);
        tile.props.stream().filter(prop -> coords.equals(prop.coords())).forEach(result::add);
        return result;
    }

    /**
     * Show edits to existing model placements as soon as the EDT publishes them. All views, picks and shadow passes
     * borrow these same instances. Their support is the currently drawn terrain; a completed chunk replaces the
     * temporary pose and its vegetation together. Neither the document nor pending build inputs are mutated here.
     */
    void previewEditorObjects(BoardScene scene) {
        if (!ready(scene)) { return; }
        installedSettings.run(() -> {
            Map<DecorationPart, EditorPlacement> placements = null;
            for (Chunk chunk : chunks) {
                if (chunk.editorTiles == scene.tiles()) { continue; }
                if (placements == null) {
                    placements = new HashMap<>();
                    for (var tile : scene.tiles()) {
                        Map<String, Integer> parts = new HashMap<>();
                        for (var feature : tile.features()) {
                            if (feature.decoration() == null) { continue; }
                            String id = feature.decoration().id();
                            int part = parts.merge(id, 1, Integer::sum) - 1;
                            placements.put(new DecorationPart(id, part), new EditorPlacement(tile.coords(), feature));
                        }
                    }
                }
                chunk.editorTiles = scene.tiles();
                boolean changed = false;
                for (Prop prop : chunk.props) {
                    if (prop.decorationId == null) { continue; }
                    var placement = placements.get(new DecorationPart(prop.decorationId, prop.decorationIndex));
                    BoardScene.Feature next = placement == null ? prop.decorationFeature : placement.feature();
                    Coords coords = placement == null ? prop.coords : placement.coords();
                    if (!prop.decorationFeature.asset().equals(next.asset())) { continue; }
                    if (next.equals(prop.previewFeature == null ? prop.decorationFeature : prop.previewFeature)
                          && coords.equals(prop.coords())) { continue; }
                    ModelInstance preview = null;
                    float z = 0;
                    if (!next.equals(prop.decorationFeature) || !coords.equals(prop.coords)) {
                        var object = next.decoration();
                        float x = BoardGeometry.centerX(coords) + (float) object.x() * BoardGeometry.width();
                        float y = BoardGeometry.centerY(coords) + (float) object.y() * BoardGeometry.height();
                        var footprint = BoardGeometry.tile(coverScene, x, y);
                        Coords supportCoords = footprint == null ? coords : footprint.coords();
                        Chunk receiving = chunks.get(supportCoords.getX() / CHUNK_SIZE * chunkRows + supportCoords.getY() / CHUNK_SIZE);
                        var support = tacticalSurface(coverScene, receiving, supportCoords, floor);
                        float groundZ = BoardSurface.sampleHeight(support.faces(), x, y,
                              BoardGeometry.groundZ(coverScene.tile(supportCoords)));
                        TileMesh mesh = receiving.tileMeshes.get(supportCoords);
                        z = decorationHeight(next, groundZ, mesh.bridgeShape, mesh.props, x, y);
                        if (!Float.isFinite(z)) { continue; }
                        preview = new ModelInstance(prop.instance);
                        placeDecoration(preview, coords, next, z + next.elevation() * BoardGeometry.level());
                    }
                    var oldGeyser = GpuGeysers.emitter(prop.decorationFeature.asset(), prop.instance().transform);
                    prop.previewFeature = next;
                    prop.previewCoords = coords;
                    prop.previewInstance = preview;
                    prop.previewBounds = preview == null ? null : preview.calculateBoundingBox(new BoundingBox()).mul(preview.transform);
                    prop.previewAnchorZ = z;
                    if (oldGeyser != null) {
                        chunk.geysers.remove(oldGeyser);
                        var geyser = GpuGeysers.emitter(next.asset(), prop.instance().transform);
                        chunk.geysers.add(geyser);
                        if (preview != null) { geyserBounds(prop.previewBounds, geyser); }
                    }
                    // Keep both the installed terrain and the moved object's envelope until the rebuild commits.
                    chunk.bounds.ext(prop.bounds());
                    shadowBounds.ext(prop.bounds());
                    changed = true;
                }
                if (changed) {
                    chunk.cacheDecorations(tacticalView);
                    refreshShadows();
                }
            }
            if (placements != null) {
                Map<Coords, List<Prop>> moved = new HashMap<>();
                for (Chunk chunk : chunks) {
                    for (Prop prop : chunk.props) {
                        if (!prop.coords.equals(prop.coords())) {
                            moved.computeIfAbsent(prop.coords(), key -> new ArrayList<>()).add(prop);
                        }
                    }
                }
                editorMovedProps = moved;
            }
        });
    }

    /** Derived from installed geometry, including composition parts. These are display data, never document state. */
    List<EditorObject> editorObjects(Coords coords) {
        TileMesh tile = editorTile(coords);
        if (tile == null) { return List.of(); }
        return installedSettings.call(() -> {
            Map<String, EditorObject> result = new LinkedHashMap<>();
            for (Prop prop : editorProps(coords)) {
                if (prop.decorationId == null) { continue; }
                EditorObject previous = result.get(prop.decorationId);
                if (previous != null) { previous.bounds().ext(prop.bounds()); }
                else { result.put(prop.decorationId, new EditorObject(prop.decorationId, new BoundingBox(prop.bounds()),
                      prop.anchorZ() / BoardGeometry.level())); }
            }
            return List.copyOf(result.values());
        });
    }

    /** The cut through the actual ground triangles; internal hexes have no exposed vertical wall of their own. */
    List<Vector3> editorGroundProfile(Coords coords, boolean east) {
        if (editorTile(coords) == null || coverScene == null) { return List.of(); }
        return installedSettings.call(() -> {
            Chunk chunk = chunks.get(coords.getX() / CHUNK_SIZE * chunkRows + coords.getY() / CHUNK_SIZE);
            var surface = tacticalSurface(coverScene, chunk, coords, floor);
            List<Vector3> samples = new ArrayList<>();
            for (int i = 0; i <= 48; i++) {
                float position = (i / 48f - .5f) * .998f;
                float x = BoardGeometry.centerX(coords) + (east ? 0 : position * BoardGeometry.width());
                float y = BoardGeometry.centerY(coords) + (east ? position * BoardGeometry.height() : 0);
                float z = Float.NEGATIVE_INFINITY;
                for (var face : surface.top()) { z = Math.max(z, face.height(x, y)); }
                for (var face : surface.slopes()) { z = Math.max(z, face.height(x, y)); }
                if (Float.isFinite(z)) { samples.add(new Vector3(x, y, z)); }
            }
            return samples;
        });
    }

    /** A second camera borrows the installed hex meshes, transforms, materials and animation clocks. */
    void renderEditorSection(Camera camera, Coords coords, String isolate) {
        TileMesh tile = editorTile(coords);
        if (tile == null) { return; }
        installedSettings.call(() -> {
            shadingPass++;
            // This camera also renders stand-alone palette samples, before any main-board pass.
            biomes.update(coverScene);
            batch.begin(camera);
            try {
                batch.render((out, pool) -> {
                    for (TileRange range : tile.ranges) {
                        if (range.layer() == 5 || !isolate.isEmpty()) { continue; }
                        Renderable part = pool.obtain();
                        part.worldTransform.idt(); part.material = range.material();
                        part.meshPart.set("editor-section", range.mesh(), range.offset(), range.count(), GL20.GL_TRIANGLES);
                        if (part.material.has(Sculpt.TYPE)) { prepareTerrainMaterial(part.material, range.mesh().getVertexAttributes()); }
                        out.add(part);
                    }
                }, environment);
                for (Prop prop : editorProps(coords)) {
                    if (!Float.isNaN(prop.floorZ) || !isolate.isEmpty() && !isolate.equals(prop.decorationId)) { continue; }
                    batch.render(prop.instance(), environment);
                }
            } finally { batch.end(); }
            return null;
        });
    }

    record EditorSectionHit(String object, String component) { }

    /** Side-view picking borrows the same installed model, bridge and ground triangles as board picking. */
    EditorSectionHit editorSectionHit(Coords coords, Ray ray) {
        return installedSettings.call(() -> editorSectionHitInstalled(coords, ray));
    }

    String editorSectionPick(Coords coords, Ray ray) {
        EditorSectionHit hit = editorSectionHit(coords, ray);
        return hit == null || hit.object().isEmpty() ? null : hit.object();
    }

    private EditorSectionHit editorSectionHitInstalled(Coords coords, Ray ray) {
        TileMesh tile = editorTile(coords);
        if (tile == null) { return null; }
        Vector3 point = new Vector3(); float nearest = Float.POSITIVE_INFINITY; EditorSectionHit result = null;
        if (tile.bridgeShape != null) {
            nearest = tile.bridgeShape.hit(ray);
            if (Float.isFinite(nearest)) { result = new EditorSectionHit("", "bridge"); }
        }
        for (Prop prop : editorProps(coords)) {
            if (!Float.isNaN(prop.floorZ)) { continue; }
            String component = prop.industrial ? "industry" : "building".equals(prop.receiver) ? "building"
                  : prop.tree() ? "vegetation" : prop.structure ? "fuelTank" : "";
            if ((prop.decorationId != null || !component.isEmpty())
                  && hitProp(prop, ray, point) && ray.origin.dst2(point) < nearest) {
                nearest = ray.origin.dst2(point);
                result = new EditorSectionHit(prop.decorationId == null ? "" : prop.decorationId, component);
            }
        }
        if (coverScene != null) {
            var ground = BoardGeometry.hit(coverScene, ray, List.of(coverScene.tile(coords)), floor, this::tacticalSurface);
            if (ground != null && ground.distance() < nearest) { result = new EditorSectionHit("", "ground"); }
        }
        return result;
    }

    /** Native editor selection uses the same installed instances and triangle picker as both board cameras. */
    DecorationHit decorationHit(BoardScene scene, Ray ray) {
        if (!ready(scene)) { return null; }
        return installedSettings.call(() -> {
            BoardGeometry.Hit solid = hitInstalled(presentation(scene), ray, true);
            float nearest = solid == null ? Float.POSITIVE_INFINITY : solid.distance() + .001f;
            DecorationHit result = null;
            Vector3 point = new Vector3();
            for (Chunk chunk : chunks) {
                if (!Intersector.intersectRayBoundsFast(ray, chunk.bounds)) { continue; }
                for (Prop prop : chunk.props) {
                    if (prop.decorationId != null && hitProp(prop, ray, point) && ray.origin.dst2(point) <= nearest) {
                        nearest = ray.origin.dst2(point);
                        result = new DecorationHit(prop.coords(), prop.decorationId, nearest, prop.instance().transform.val[Matrix4.M23]);
                    }
                }
                for (TileMesh tile : chunk.tileMeshes.values()) {
                    for (PaintedDecal paint : tile.paint) {
                        for (var face : paint.faces()) {
                            if (Intersector.intersectRayTriangle(ray, new Vector3(face.a()).add(0, 0, paint.lift()),
                                  new Vector3(face.b()).add(0, 0, paint.lift()), new Vector3(face.c()).add(0, 0, paint.lift()), point)
                                  && ray.origin.dst2(point) <= nearest) {
                                nearest = ray.origin.dst2(point);
                                result = new DecorationHit(paint.stamp().owner(), paint.stamp().object().id(), nearest, point.z);
                            }
                        }
                    }
                }
            }
            return result;
        });
    }

    /** Hover and clicks use the ground beneath foliage and industrial cover, which have no occupiable floors. */
    BoardGeometry.Hit selectionHit(BoardScene scene, Ray ray) {
        return hit(scene, ray, false);
    }

    /** Placement uses the same triangles, restricted to ground and authored supporting structures. */
    BoardGeometry.Hit placementHit(BoardScene scene, Ray ray) {
        if (!ready(scene)) { return null; }
        return installedSettings.call(() -> hitInstalled(presentation(scene), ray, true, true));
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
        return hitInstalled(scene, ray, includeFoliage, false);
    }

    private BoardGeometry.Hit hitInstalled(BoardScene scene, Ray ray, boolean includeFoliage, boolean placement) {
        // The caller has selected the installed coordinate system. A source snapshot that update has not yet
        // received still uses the authoritative-snapshot fallback, as before.
        boolean finished = tiles == scene.tiles();
        Coords result = null;
        float nearest = Float.POSITIVE_INFINITY;
        boolean hardSurface = false;
        String receiver = "ground";
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
            // The Tactical View has its bridges and other props in the columns' flat art, its structures in 3D.
            for (var entry : chunk.tileMeshes.entrySet()) {
                var bridge = entry.getValue().bridgeShape;
                if (bridge == null || tacticalView) { continue; }
                float distance = bridge.hit(ray);
                if (distance < nearest) {
                    nearest = distance;
                    ray.getEndPoint(hit, (float) Math.sqrt(distance));
                    var footprint = BoardGeometry.tile(scene, hit.x, hit.y);
                    result = footprint == null ? entry.getKey() : footprint.coords();
                    hardSurface = false;
                    receiver = "bridge";
                }
            }
            for (Prop prop : chunk.props) {
                if (placement && prop.receiver == null) { continue; }
                if ((prop.tree() || prop.industrial) && !includeFoliage || tacticalView && !prop.structure) { continue; }
                if (hitProp(prop, ray, hit)) {
                    float distance = ray.origin.dst2(hit);
                    if (distance < nearest) {
                        nearest = distance;
                        result = prop.coords();
                        hardSurface = prop.hardSurface;
                        receiver = prop.receiver == null ? "ground" : prop.receiver;
                    }
                }
            }
        }
        BoardGeometry.Hit groundHit = finished
              ? BoardGeometry.hit(scene, ray, candidates, floor, this::tacticalSurface)
              : BoardGeometry.hit(scene, ray, scene.tiles(), BoardGeometry.floor(scene), pickingSurfaces);
        return groundHit != null && groundHit.distance() <= nearest ? groundHit
              : result == null ? null : new BoardGeometry.Hit(result, nearest, hardSurface, receiver);
    }

    /** Shared by scenery placement and picking; zoom/cutaways do not change the supporting solid. */
    private boolean hitProp(Prop prop, Ray ray, Vector3 hit) {
        if (!Intersector.intersectRayBoundsFast(ray, prop.bounds())) { return false; }
        Matrix4 inverse = new Matrix4(prop.instance().transform).inv();
        // Transform the direction directly: Ray.mul subtracts distant points and loses picking precision.
        Ray local = new Ray(new Vector3(ray.origin).mul(inverse), new Vector3(ray.direction).rot(inverse));
        boolean found = prop.building != null ? prop.building.hit(local, hit)
              : Intersector.intersectRayTriangles(local,
                    featureTriangles.computeIfAbsent(prop.pickingModel, GpuTerrain::triangles), hit);
        if (found) { hit.mul(prop.instance().transform); }
        return found;
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

    /**
     * The Tactical View: the columns of {@link GpuTilesetTerrain} replace this terrain's drawing, shadow and picking,
     * while its structures stay as they are, with their interiors, cutaways and picks.
     */
    void setTacticalView(boolean enabled) {
        if (tacticalView != enabled) {
            tacticalView = enabled;
            drapeChanged = true;
            shadowDirty = true;
            chunks.forEach(this::recacheProps);
            detailCache.values().forEach(this::recacheProps);
        }
    }

    private void recacheProps(Chunk chunk) {
        GpuPropBatch.disposeMeshes(chunk.shadowPropRenderables);
        chunk.shadowPropRenderables.clear();
        chunk.cacheProps(tacticalView);
    }

    boolean tacticalView() { return tacticalView; }

    /** Brings the Tactical View's columns up to the scene, outside any batch; a rebuilt section redraws the shadow. */
    private void updateTileset() {
        if (coverScene != null && tileset.update(coverScene, floor, assets)) { shadowDirty = true; }
    }

    /** Opaque world first. Tactical overlays are a separate final pass. */
    void render(Camera camera, boolean drawTactical) {
        shadingPass++;
        // The Tactical View's columns replace the terrain, its trees and scatter; its structures stand as here.
        boolean terrain = !drawTactical && !tacticalView;
        if (!drawTactical) {
            if (tacticalView) { updateTileset(); }
            updateDetail(camera);
            lavaLighting.update(coverScene, camera, terrain && !clay);
            // Upload before the render context tracks bound texture units for this pass.
            if (terrain) { biomes.update(coverScene); }
        }
        batch.begin(camera);
        // The columns take this terrain's sun or moon, ambient, shadow map, cloud shadow and lava glow.
        if (!drawTactical && tacticalView) { tileset.render(batch, environment); }
        trees.begin(GpuTreeInstances.Pass.COLOUR);
        if (!drawTactical) { propBatch.begin(); }
        if (terrain) { terrainPages.begin(camera); }
        int propPageSize = GpuPropBatch.CHUNKS_PER_PAGE;
        int propPageRows = (chunkRows + propPageSize - 1) / propPageSize;
        int terrainPageRows = (chunkRows + GpuTerrainPages.CHUNKS_PER_PAGE - 1) / GpuTerrainPages.CHUNKS_PER_PAGE;
        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            Chunk chunk = chunks.get(chunkIndex);
            boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
            boolean scatterVisible = terrain && visible && chunk.scatterVisible;
            if (terrain) {
                int page = chunkIndex / chunkRows / GpuTerrainPages.CHUNKS_PER_PAGE * terrainPageRows
                      + chunkIndex % chunkRows / GpuTerrainPages.CHUNKS_PER_PAGE;
                terrainPages.add(chunk.terrainRenderables, page, visible);
            }
            if (!drawTactical) {
                int propPage = (chunkIndex / chunkRows / propPageSize) * propPageRows
                      + chunkIndex % chunkRows / propPageSize;
                // Hover/occupancy changes only this chunk's presentation, not its static page membership.
                // Keep the complete opaque source cached and draw the opened chunk separately.
                propBatch.add(chunk.shadowProps, propPage, visible && chunk.faded.isEmpty());
                if (visible && !chunk.faded.isEmpty()) { propBatch.addDynamic(chunk.solidProps); }
                propBatch.add(chunk.scatterRenderables, propPage, scatterVisible);
            }
            if (terrain) {
                trees.add(chunk.stand, chunk.treeLod, visible);
                trees.add(chunk.scatterStand, chunk.treeLod, scatterVisible);
            }
            if (!visible) {
                continue;
            }
            if (drawTactical) {
                chunk.tactical.forEach(instance -> batch.render(instance));
            } else {
                trees.add(chunk.sharedProps);
                batch.render(chunk.decorations, environment);
            }
        }
        // Trees first: the opaque sorter keeps this order of shaders, so the ground hidden under crowns fails the depth
        // test instead of being shaded and painted over.
        batch.render(trees, environment);
        if (!drawTactical) { propBatch.render(batch, environment); }
        if (terrain) { terrainPages.render(batch, environment); }
        batch.end();
        if (!drawTactical) {
            batch.begin(camera);
            if (terrain) { renderCover(camera); }
            for (Chunk chunk : chunks) {
                if (camera.frustum.boundsInFrustum(chunk.bounds)) {
                    if (terrain) { chunk.overlays.forEach(instance -> batch.render(instance, environment)); }
                    (tacticalView ? chunk.tilesetDecals : chunk.surfaceDecals)
                          .forEach(instance -> batch.render(instance, environment));
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
        if (tacticalView) {
            batch.begin(camera);
            if (waterVisible()) { tileset.renderLiquids(batch, environment); }
            for (Chunk chunk : chunks) {
                if (!camera.frustum.boundsInFrustum(chunk.bounds)) { continue; }
                for (Prop prop : chunk.faded) {
                    if (prop.structure) { batch.render(prop.instance(), environment); }
                }
            }
            batch.end();
            return;
        }
        updateDetail(camera);
        biomes.update(coverScene);
        boolean hasWater = waterVisible() && chunks.stream().anyMatch(chunk -> chunk.waterField != null);
        if (hasWater) { waterExposure.update(coverScene); }
        waterPages.begin();
        int pageRows = (chunkRows + GpuWaterPages.CHUNKS_PER_PAGE - 1) / GpuWaterPages.CHUNKS_PER_PAGE;
        batch.begin(camera);
        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
            int page = index / chunkRows / GpuWaterPages.CHUNKS_PER_PAGE * pageRows
                  + index % chunkRows / GpuWaterPages.CHUNKS_PER_PAGE;
            waterPages.add(chunk.waterRenderables, page, visible, waterVisible());
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
            renderSceneryEffects(camera);
            return;
        }
        waterPages.prepare();
        // Waves need to hide the water behind them at grazing angles. This cheap pass shares both vertices and
        // displacement with the colour pass, and writes the nearest wave or board-edge section into a depth target of
        // its own: the colour pass tests against it, fog stops at it, and the scene's depth keeps the beds for outlines
        // and overlays.
        int scene = waterDepth.begin();
        try {
            batch.begin(camera);
            waterPages.render(batch, environment, true);
            batch.end();
        } finally { Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, scene); }
        batch.begin(camera);
        waterPages.render(batch, environment, false);
        batch.end();
        renderSceneryEffects(camera);
    }

    private void renderSceneryEffects(Camera camera) {
        fungus.begin();
        geysers.begin();
        if (!clay) {
            for (Chunk chunk : chunks) {
                fungus.add(chunk.spores, camera);
                geysers.add(chunk.geysers, camera);
            }
        }
        fungus.render(camera, clock, wind);
        geysers.render(camera, clock, gravity, wind, atmosphere == null ? Color.WHITE : atmosphere.groundLight());
    }

    int geyserParticles() { return geysers.particles(); }

    int fungusParticles() { return fungus.particles(); }

    long fungusClouds() {
        return chunks.stream().flatMap(chunk -> chunk.spores.stream()).filter(GpuFungus.Emitter::cloud).count();
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

    /** Scenario or local preview gravity, in g; update derives lunar terrain at zero without editing game state. */
    void setGravity(float gravity) { this.gravity = gravity * BoardAtmosphere.STANDARD_GRAVITY; }

    boolean waterVisible() { return gravity > 0; }

    /** This frame's nearest water, for fog to stop at; null when the frame drew no water. */
    Texture waterDepth() { return waterVisible() ? waterDepth.nearest() : null; }

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
        renderDepth(camera, units, pass, false, true);
    }

    /** Wireframe fill and lines share the camera's cutaway and detail selection, without scatter terrain. */
    void renderWireframe(Camera camera, ModelBatch pass) {
        updateDetail(camera);
        renderDepth(camera, List.of(), pass, false, false);
    }

    private void renderDepth(Camera camera, List<ModelInstance> units, ModelBatch pass, boolean shadows,
          boolean includeScatter) {
        pass.begin(camera);
        trees.begin(shadows ? GpuTreeInstances.Pass.SHADOW : GpuTreeInstances.Pass.DEPTH);
        // The Tactical View's columns and decks cast its shadows instead of the terrain, trees and scatter.
        boolean columns = shadows && tacticalView;
        if (columns) { tileset.render(pass, null); }
        // A shadow map whose texel spans half a metre or more cannot resolve leaves: its trees cast the shadow of
        // their second decimated level at most. Alpha-tested foliage drawn into such a map at the near level was
        // fragment-bound, 440-510 ms per refit on an Intel Iris Xe over MesaCity 1's woods.
        int coarsest = shadows && camera.viewportWidth / SHADOW_RESOLUTION >= BoardRelief.metres(.5f) ? 2 : 0;
        for (Chunk chunk : chunks) {
            boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
            boolean scatterVisible = includeScatter && visible && chunk.scatterVisible;
            if (!columns) {
                trees.add(chunk.stand, Math.max(chunk.treeLod, coarsest), visible);
                trees.add(chunk.scatterStand, Math.max(chunk.treeLod, coarsest), scatterVisible);
            }
            if (visible) {
                if (!columns) { pass.render(chunk.depthTerrain); }
                if (!columns && scatterVisible) {
                    chunk.scatter.forEach(pass::render);
                }
                pass.render(shadows ? chunk.shadowProps : chunk.solidProps);
                pass.render(chunk.decorations);
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
        // The Tactical View's columns cast and receive in this terrain's place, through the same fit and caches.
        if (tacticalView) {
            updateTileset();
        } else if (view != null) {
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
        BoundingBox bounds = tacticalView ? tileset.bounds() : new BoundingBox(shadowBounds);
        if (tacticalView) {
            chunks.forEach(chunk -> chunk.cutaways.forEach(prop -> { if (prop.structure) { bounds.ext(prop.bounds()); } }));
        }
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
                renderDepth(lightCamera, boardWide ? List.of() : units, depthBatch, true, true);
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
                    renderDepth(lightCamera, List.of(), depthBatch, true, true);
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
                recacheProps(chunk);
            }
            // Largest tree wins: smaller neighbors may retain extra detail, never lose it early.
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
        // Workers borrow chunk/asset data. Join them before freeing it; a slow cancellation or an interrupted
        // closing thread must not abandon GL cleanup. ExecutorService.close also restores the caller's interrupt.
        detailWorker.close();
        if (terrainMaterialPreparation != null) { terrainMaterialPreparation.dispose(); }
        terrainMaterialSamples.forEach(sample -> sample.meshPart.mesh.dispose());
        terrainMaterialSamples.clear();
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
        fungus.dispose();
        geysers.dispose();
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
        tileset.dispose();
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
