/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.TextureArrayData;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.Configuration;
import megamek.common.board.TilesetScenery;

/** GL-thread ownership of shared models, repeating terrain materials, and animated liquid frames. */
final class GpuAssets implements Disposable {
    private final File root = new File(Configuration.dataDir(), "models/board");
    /** Board materials: Godot 4 StandardMaterial3D resources and their maps (GodotMaterial), with materials.json. */
    private final File textures = new File(root, "textures");
    private final Map<String, GodotMaterial> definitions = new HashMap<>();
    private final Map<String, Float> coverages = new HashMap<>();
    private GpuMaterialCatalog catalog;
    private final Map<String, Model> models = new HashMap<>();
    private final Map<String, List<Model>> modelLods = new HashMap<>();
    /** Each recoloured model's decoded levels, in the model's own colours (a missing level repeats the one before). */
    private final Map<String, List<ModelData>> recolourable = new HashMap<>();
    /** Whether each placed model has colour slots, read from its file's JSON once. */
    private final Map<String, Boolean> hasSlots = new HashMap<>();
    /** How many mesh levels each placed model's file holds, read from its JSON once. */
    private final Map<String, Integer> fileLevels = new HashMap<>();
    private final Map<Interior, Model> interiors = new HashMap<>();
    private final Map<String, GpuBuilding> buildings = new HashMap<>();
    private final Map<BoardIndustrial.Layout, Model> industrial = new HashMap<>();
    private final Map<BoardRail.Layout, Model> routes = new HashMap<>();
    private final Map<String, Texture> materials = new HashMap<>();
    private final Map<String, Cliff> cliffs = new HashMap<>();
    private final Map<Boolean, MaterialArrays> magmas = new HashMap<>();
    private LavaEmission lavaEmission;
    private final Map<String, Sculpt> sculpts = new HashMap<>();
    private MaterialArrays sculptArray;
    private FinishArrays finishArrays;
    private float[] roadRelief;
    private MaterialArrays roadArray;
    private Ice ice;
    private final Map<BoardLiquid.Textures, Animation<Texture>> liquids = new HashMap<>();
    private BoardScene.Pixels incline;
    private BoardScene.Pixels highIncline;

    private record Interior(String asset, int levels) { }

    /** Three aligned, repeating maps. Surface channels are height, roughness, occlusion and relief range. */
    record Cliff(Texture color, Texture normal, Texture surface) { }

    /**
     * The ice sheet's colour, and its aligned normal and surface maps as layers 0 and 1 of one array; {@code coverage}
     * multiplies its repeat (GpuMaterialCatalog).
     */
    record Ice(Texture color, TextureArray maps, float coverage) { }

    /** A board material's Godot 4 Standard definition, read once per asset owner (asset reload makes a fresh owner). */
    GodotMaterial definition(String name) {
        return definitions.computeIfAbsent(name, key -> {
            File file = new File(textures, key + ".tres");
            requireFiles(List.of(new FileHandle(file)));
            return GodotMaterial.read(file, textures);
        });
    }

    boolean hasMaterial(String name) { return new File(textures, name + ".tres").isFile(); }

    GpuMaterialCatalog catalog() {
        if (catalog == null) { catalog = GpuMaterialCatalog.read(new File(textures, "materials.json")); }
        return catalog;
    }

    /**
     * How many times its catalogued ground a loose material's texture covers: 1 at its catalogued width, 2 at twice
     * that width. Every renderer that repeats the material in world space multiplies its repeat by this.
     */
    float coverage(String name) {
        return coverages.computeIfAbsent(name, key -> {
            int[] size = GpuMaterialMaps.size(definition(key).albedo());
            return catalog().coverage(key, size[0], size[1]);
        });
    }

    /** One packed layout of a board material, made when its texture is uploaded. */
    private Supplier<Pixmap> packed(String name, GpuMaterialMaps.Layout layout) {
        GodotMaterial material = definition(name);
        // Resolve and check the maps now, so a missing file fails before any upload.
        requireFiles(GpuMaterialMaps.maps(material, layout).stream().map(FileHandle::new).toList());
        return () -> GpuMaterialMaps.pack(material, layout);
    }

    /** A packed layout's dimensions, from its main map's header. */
    private int[] size(String name, GpuMaterialMaps.Layout layout) {
        return GpuMaterialMaps.size(GpuMaterialMaps.primary(definition(name), layout));
    }

    /**
     * Molten lava's or solid crust's aligned maps in separate colour and numerical arrays:
     * colour; normal; surface (height, roughness, occlusion, relief range); heat. Molten heat is normalized linear
     * emission RGB with original heat coverage in A; crust retains R heat and A crack glow. Both require complete sets.
     */
    MaterialArrays magma(boolean molten) {
        if (magmas.containsKey(molten)) { return magmas.get(molten); }
        String name = magmaName(molten);
        var layers = new ArrayList<Layer>();
        for (var layout : List.of(GpuMaterialMaps.Layout.COLOR, GpuMaterialMaps.Layout.NORMAL, GpuMaterialMaps.Layout.RELIEF,
              molten ? GpuMaterialMaps.Layout.MOLTEN_HEAT : GpuMaterialMaps.Layout.CRUST_HEAT)) {
            layers.add(layer(name, layout));
        }
        MaterialArrays maps = repeatingArray(layers, 4);
        magmas.put(molten, maps);
        return maps;
    }

    static String magmaName(boolean molten) { return molten ? "terrain/lava/lava" : "terrain/lava/crust"; }

    /** The exported linear emission's scale and area mean, owned with the corresponding material maps. */
    record LavaEmission(float scale, float red, float green, float blue) { }

    LavaEmission lavaEmission() {
        if (lavaEmission != null) { return lavaEmission; }
        GodotMaterial lava = definition(magmaName(true));
        File emission = lava.emission();
        if (emission == null) { throw new IllegalArgumentException("Molten lava needs its emission map: " + lava.file); }
        FileHandle image = new FileHandle(emission);
        requireFiles(List.of(image));
        // The Godot material's emission energy is the exported linear emission's scale.
        float scale = lava.emissionEnergy();
        if (scale < 0) { throw new IllegalArgumentException("Lava emission energy must be nonnegative: " + lava.file); }
        Pixmap pixels = GpuColorTexture.decode(image);
        try {
            long red = 0, green = 0, blue = 0;
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    int rgba = pixels.getPixel(x, y);
                    red += rgba >>> 24;
                    green += (rgba >>> 16) & 255;
                    blue += (rgba >>> 8) & 255;
                }
            }
            // RGB already contains the authored heat/crack/exposed coverage. Alpha must not weight it again.
            double factor = scale / (255.0 * pixels.getWidth() * pixels.getHeight());
            lavaEmission = new LavaEmission(scale, (float) (red * factor), (float) (green * factor), (float) (blue * factor));
            return lavaEmission;
        } finally { pixels.dispose(); }
    }

    /**
     * A sculpted-terrain material from {@code textures/terrain/surfaces}: albedo with height in alpha, a tangent-space normal
     * with occlusion in alpha, and the metres one repeat spans ({@link #sculptTile}).
     */
    record Sculpt(Texture color, Texture normal, float tile) { }

    /** Aligned colour/height and normal/AO arrays in the supplied stable material order. */
    MaterialArrays sculptArray(List<String> names) {
        if (sculptArray == null) {
            var layers = new ArrayList<Layer>();
            for (String name : names) {
                String material = sculptName(name);
                sculptTile(name);
                layers.add(layer(material, GpuMaterialMaps.colorLayout(material)));
                layers.add(layer(material, GpuMaterialMaps.normalLayout(material)));
            }
            sculptArray = repeatingArray(layers, 2);
        }
        return sculptArray;
    }

    private static String sculptName(String name) { return "terrain/surfaces/" + name; }

    /**
     * The finish ({@link #authoredFinish}) of every board material the terrain draws from arrays (sculpted surfaces, then
     * from {@code roads} on the road materials), in the given stable order of material names. One raw RGBA8 array holds
     * the surface layer (roughness, metallic and scattering in R, G and B) of each material whose finish varies and,
     * after them, each emission map's sRGB bytes, which the shader decodes: one sampler for both, as the terrain's
     * programs are at their texture units' limit. Per material: its surface values or means and surface layer (-1 if
     * uniform), its emission tint and emission layer (-1 without a map), and its constant glow. The sculpt maps set the
     * grid, which the road materials' maps take; road materials keep their roughness in their own surface map, so theirs
     * is sent as its mean alone. The array holds one black texel when no material has a map.
     */
    record FinishArrays(TextureArray maps, float[] surface, float[] tint, float[] glow) implements Disposable {
        @Override public void dispose() { maps.dispose(); }
    }

    FinishArrays finishArrays(List<String> materials, int roads) {
        if (finishArrays != null) { return finishArrays; }
        int count = materials.size();
        float[] surface = new float[count * 4], tint = new float[count * 4], glow = new float[count * 3];
        var surfaces = new ArrayList<Supplier<Pixmap>>();
        var emissions = new ArrayList<Supplier<Pixmap>>();
        var surfaceLayers = new int[count];
        var emissionLayers = new int[count];
        int width = 0, height = 0, roadWidth = 1, roadHeight = 1;
        for (int index = 0; index < count; index++) {
            var finish = authoredFinish(definition(materials.get(index)), index < roads);
            System.arraycopy(finish.surface(), 0, surface, index * 4, 3);
            System.arraycopy(finish.tint(), 0, tint, index * 4, 3);
            System.arraycopy(finish.glow(), 0, glow, index * 3, 3);
            surfaceLayers[index] = finish.map() == null ? -1 : surfaces.size();
            emissionLayers[index] = finish.emission() == null ? -1 : emissions.size();
            int[] emissionSize = finish.emission() == null ? new int[] { 1, 1 } : GpuMaterialMaps.size(finish.emission());
            int layerWidth = Math.max(finish.map() == null ? 1 : finish.width(), emissionSize[0]);
            int layerHeight = Math.max(finish.map() == null ? 1 : finish.height(), emissionSize[1]);
            if (index < roads) {
                width = Math.max(width, layerWidth);
                height = Math.max(height, layerHeight);
            } else {
                roadWidth = Math.max(roadWidth, layerWidth);
                roadHeight = Math.max(roadHeight, layerHeight);
            }
            if (finish.map() != null) { surfaces.add(() -> rgbaPixmap(finish.map(), finish.width(), finish.height())); }
            if (finish.emission() != null) {
                FileHandle map = new FileHandle(finish.emission());
                emissions.add(() -> GpuColorTexture.decode(map));
            }
        }
        if (width == 0) { width = roadWidth; height = roadHeight; }
        for (int index = 0; index < count; index++) {
            surface[index * 4 + 3] = surfaceLayers[index];
            tint[index * 4 + 3] = emissionLayers[index] < 0 ? -1 : surfaces.size() + emissionLayers[index];
        }
        // Every layer takes the grid, resampled as the colour arrays resample theirs, so each still spans its repeat.
        var layers = new ArrayList<>(surfaces);
        layers.addAll(emissions);
        if (layers.isEmpty()) { layers.add(() -> rgbaPixmap(new byte[4], 1, 1)); }
        finishArrays = new FinishArrays(repeatingArray(new MaterialArrayData(layers, Math.max(width, 1), Math.max(height, 1),
              false)), surface, tint, glow);
        return finishArrays;
    }

    private static Pixmap rgbaPixmap(byte[] rgba, int width, int height) {
        var pixels = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        pixels.getPixels().put(rgba).position(0);
        return pixels;
    }

    /**
     * A material's finish as Godot's StandardMaterial3D reads it. Roughness, metallic and subsurface scattering: each its
     * map's channel times its factor ({@code roughness}, {@code metallic}, {@code subsurf_scatter_strength}), or the
     * factor alone without a map (Godot's maps default to white); scattering only where it is enabled. Where all three
     * are uniform they are {@code surface}; otherwise {@code map} holds them in R, G and B at the largest of their grids,
     * and {@code surface} their means, which distant terrain uses instead of reading the map. Emission: its map, the
     * map's tint and a constant glow ({@link GpuMaterialMaps#emission}).
     */
    record AuthoredFinish(float[] surface, byte[] map, int width, int height, float[] tint, File emission, float[] glow) { }

    /** {@link #finishArrays}' rule for one material, without a graphics context. */
    static AuthoredFinish authoredFinish(GodotMaterial material) { return authoredFinish(material, true); }

    /** Without its roughness map, a material whose roughness another map carries sends only the map's mean. */
    static AuthoredFinish authoredFinish(GodotMaterial material, boolean roughnessMap) {
        boolean scatters = material.subsurfaceEnabled();
        var channels = new FinishChannel[] {
              withoutMap(finishChannel(material, "roughness", material.roughness(), material.roughnessMap(),
                    material.roughnessChannel()), !roughnessMap),
              finishChannel(material, "metallic", material.metallic(), material.metallicMap(), material.metallicChannel()),
              finishChannel(material, "subsurf_scatter_strength", scatters ? material.subsurfaceStrength() : 0,
                    scatters ? material.subsurface() : null, GodotMaterial.RED) };
        float[] surface = new float[3];
        int width = 1, height = 1;
        for (var channel : channels) {
            if (channel.map() == null) { continue; }
            width = Math.max(width, channel.map().width());
            height = Math.max(height, channel.map().height());
        }
        byte[] map = null;
        for (int index = 0; index < 3; index++) {
            var channel = channels[index];
            surface[index] = channel.value();
            if (channel.map() == null) { continue; }
            if (map == null) {
                map = new byte[width * height * 4];
                for (int texel = 0; texel < width * height; texel++) {
                    for (int other = 0; other < 3; other++) {
                        map[texel * 4 + other] = (byte) Math.round(channels[other].value() * 255);
                    }
                    map[texel * 4 + 3] = (byte) 255;
                }
            }
            byte[] values = onGrid(channel.map(), width, height);
            for (int texel = 0; texel < values.length; texel++) { map[texel * 4 + index] = values[texel]; }
        }
        var emission = GpuMaterialMaps.emission(material);
        if (emission.map() != null) { requireFiles(List.of(new FileHandle(emission.map()))); }
        return new AuthoredFinish(surface, map, width, height, emission.tint(), emission.map(), emission.glow());
    }

    /** One finish channel: its value (a map's mean), and the map times its factor where it varies. */
    private record FinishChannel(float value, GpuMaterialMaps.Channel map) { }

    private static FinishChannel withoutMap(FinishChannel channel, boolean drop) {
        return drop ? new FinishChannel(channel.value(), null) : channel;
    }

    private static FinishChannel finishChannel(GodotMaterial material, String property, float factor, File map,
          int channel) {
        if (factor < 0 || factor > 1) { throw new IllegalArgumentException("Invalid " + property + ": " + material.file); }
        if (map == null || factor == 0) { return new FinishChannel(factor, null); }
        requireFiles(List.of(new FileHandle(map)));
        var read = GpuMaterialMaps.channel(map, channel);
        byte[] values = read.values();
        boolean uniform = true;
        for (byte value : values) { uniform &= value == values[0]; }
        if (uniform) { return new FinishChannel((values[0] & 255) / 255f * factor, null); }
        long sum = 0;
        for (int texel = 0; texel < values.length; texel++) {
            if (factor != 1) { values[texel] = (byte) Math.round((values[texel] & 255) * factor); }
            sum += values[texel] & 255;
        }
        return new FinishChannel(sum / 255f / values.length, read);
    }

    /** A channel on a grid, as {@link GpuColorTexture#arrayPixels} resamples a colour map; a uniform one fills it. */
    static byte[] onGrid(GpuMaterialMaps.Channel channel, int width, int height) {
        width = Math.max(width, 1);
        height = Math.max(height, 1);
        if (channel.width() == width && channel.height() == height) { return channel.values(); }
        byte[] values = new byte[width * height];
        if (channel.values().length == 1) {
            Arrays.fill(values, channel.values()[0]);
            return values;
        }
        var source = new Pixmap(channel.width(), channel.height(), Pixmap.Format.RGBA8888);
        try {
            var pixels = source.getPixels();
            for (byte value : channel.values()) { pixels.put(value).put(value).put(value).put((byte) 255); }
            pixels.position(0);
            var resized = GpuColorTexture.arrayPixels(source, width, height, false);
            for (int texel = 0; texel < values.length; texel++) { values[texel] = resized.get(texel * 4); }
            return values;
        } finally { source.dispose(); }
    }

    /**
     * Colour, normal and surface maps of every road material, three layers each in {@link GpuRoads#MATERIALS} order,
     * so that one draw holds all of a chunk's road coats. All maps are required.
     */
    MaterialArrays roadArray() {
        if (roadArray == null) {
            var layers = new ArrayList<Layer>();
            for (String name : GpuRoads.MATERIALS) {
                for (var layout : List.of(GpuMaterialMaps.Layout.COLOR, GpuMaterialMaps.Layout.NORMAL,
                      GpuMaterialMaps.Layout.RELIEF)) {
                    layers.add(layer("roads/" + name, layout));
                }
            }
            roadArray = repeatingArray(layers, 3);
        }
        return roadArray;
    }

    /** One owned pair: sRGB colour/height and raw numerical maps, each compacted in material order. */
    record MaterialArrays(TextureArray color, TextureArray data) implements Disposable {
        @Override public void dispose() { color.dispose(); data.dispose(); }
    }

    /** One array layer: a board material's packed layout and its dimensions. */
    record Layer(int width, int height, Supplier<Pixmap> pixels) { }

    private Layer layer(String name, GpuMaterialMaps.Layout layout) {
        int[] size = size(name, layout);
        return new Layer(size[0], size[1], packed(name, layout));
    }

    /**
     * Sets of aligned maps; their first map is colour and all remaining maps carry numerical data. Both arrays share the
     * largest layer's grid, so a material exported larger keeps its detail; smaller layers are resampled to it.
     */
    private static MaterialArrays repeatingArray(List<Layer> layers, int maps) {
        if (layers.isEmpty() || maps < 2 || layers.size() % maps != 0) {
            throw new IllegalArgumentException("Incomplete repeating material arrays");
        }
        var colors = new ArrayList<Supplier<Pixmap>>();
        var data = new ArrayList<Supplier<Pixmap>>();
        int width = 0, height = 0;
        for (int index = 0; index < layers.size(); index++) {
            Layer layer = layers.get(index);
            (index % maps == 0 ? colors : data).add(layer.pixels());
            width = Math.max(width, layer.width());
            height = Math.max(height, layer.height());
        }
        TextureArray color = null, numeric = null;
        try {
            color = repeatingArray(new MaterialArrayData(colors, width, height, true));
            numeric = repeatingArray(new MaterialArrayData(data, width, height, false));
            return new MaterialArrays(color, numeric);
        } catch (RuntimeException failure) {
            if (numeric != null) { numeric.dispose(); }
            if (color != null) { color.dispose(); }
            throw failure;
        }
    }

    private static TextureArray repeatingArray(MaterialArrayData data) {
        var array = new TextureArray(data);
        try {
            // Own the allocated handle before decoding/uploading: any source failure releases this array.
            array.bind();
            data.upload();
            array.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            array.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            // GLTexture.setAnisotropicFilter targets 2D, so set the array's own target explicitly.
            float anisotropy = Math.min(8, GLTexture.getMaxAnisotropicFilterLevel());
            if (anisotropy > 1) {
                array.bind();
                Gdx.gl.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL20.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy);
            }
            return array;
        } catch (RuntimeException failure) { array.dispose(); throw failure; }
    }

    /** A single storage domain; source bytes are unchanged unless the existing grid requires resizing. */
    private static final class MaterialArrayData implements TextureArrayData {
        private final List<Supplier<Pixmap>> files;
        private final int width, height;
        private final boolean color;
        private boolean prepared;

        MaterialArrayData(List<Supplier<Pixmap>> files, int width, int height, boolean color) {
            this.files = List.copyOf(files);
            this.width = width;
            this.height = height;
            this.color = color;
        }

        @Override public boolean isPrepared() { return prepared; }
        @Override public void prepare() { prepared = true; }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
        @Override public int getDepth() { return files.size(); }
        @Override public boolean isManaged() { return false; }
        @Override public int getInternalFormat() { return GL20.GL_RGBA; }
        @Override public int getGLType() { return GL20.GL_UNSIGNED_BYTE; }

        @Override
        public void consumeTextureArrayData() {
            // libGDX's allocator shares internal/input enums; specify the sized storage here.
            Gdx.gl30.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, color ? GL30.GL_SRGB8_ALPHA8 : GL30.GL_RGBA8,
                  width, height, getDepth(), 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, (java.nio.Buffer) null);
            prepared = false;
        }

        /** The caller has an owned TextureArray handle and releases it if source decoding fails. */
        void upload() {
            var oldAlignment = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(1);
            Gdx.gl.glGetIntegerv(GL20.GL_UNPACK_ALIGNMENT, oldAlignment);
            Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1);
            try {
                for (int layer = 0; layer < files.size(); layer++) {
                    var pixels = files.get(layer).get();
                    try {
                        var upload = GpuColorTexture.arrayPixels(pixels, width, height, color);
                        try {
                            Gdx.gl30.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, width, height, 1,
                                  GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, upload);
                        } finally { Reference.reachabilityFence(upload); }
                    } finally { pixels.dispose(); }
                }
            } finally { Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, oldAlignment.get(0)); }
            Gdx.gl.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
        }
    }

    record Animation<T>(List<T> frames, float[] ends, float duration) {
        T at(float time) {
            return frames.get(index(time));
        }

        int index(float time) {
            float position = time % duration;
            for (int index = 0; index < ends.length; index++) {
                if (position < ends[index]) {
                    return index;
                }
            }
            return frames.size() - 1;
        }

        float blend(float time, int index) {
            float start = index == 0 ? 0 : ends[index - 1];
            return Math.clamp((time % duration - start) / (ends[index] - start), 0, 1);
        }
    }

    Model model(String name) {
        return lodModel(name, 0);
    }

    /**
     * The model in {@code colours} (a layout row's, a truss deck's or an editor preview's): the model itself, or one copy
     * per set of colours with its colour slots replaced ({@link RigidGlb#recolour}). Objects of one colour share their
     * copy, so they still batch together. Placed objects draw the shared mesh instead ({@link #slotted}).
     */
    Model model(String name, megamek.common.board.BoardDecoration.Colours colours) {
        if (colours.slots().isEmpty()) { return lodModel(name, 0); }
        // A copy's key cannot meet a level's name ("name-lodN").
        return models.computeIfAbsent(name + colours.slots(), key -> {
            // The file is decoded once; each set of colours recolours a copy of its vertices for its own GPU mesh.
            ModelData data = decoded(name).getFirst();
            var mesh = data.meshes.first();
            float[] vertices = mesh.vertices;
            mesh.vertices = vertices.clone();
            try {
                RigidGlb.recolour(data, colours);
                return createModel(data);
            } finally { mesh.vertices = vertices; }
        });
    }

    /** A recolourable model's decoded levels, in its own colours; decoded once. */
    private List<ModelData> decoded(String name) {
        return recolourable.computeIfAbsent(name, shape -> RigidGlb.loadLods(RigidGlb.source(root, shape), root.toPath()));
    }

    /** The first level whose mesh {@code level} draws: itself, or the level it repeats where the file lacks it. */
    private int ownLevel(String name, int level) {
        var levels = decoded(name);
        return levels.indexOf(levels.get(level));
    }

    /**
     * A placed object's mesh per level of the props' level rule ({@link TreeLod#PROPS}: its near mesh and two simpler
     * ones), each {@link #slotted}. A level its file lacks repeats the one before, so a model with one level is its near
     * mesh alone; its file's level count is read once.
     */
    List<Model> objectLevels(String name) {
        int count = fileLevels.computeIfAbsent(name, key -> {
            var source = RigidGlb.source(root, key);
            return source.file().isFile() ? RigidGlb.levels(source.file()) : 1;
        });
        List<Model> result = new ArrayList<>(List.of(slotted(name)));
        for (int level = 1; level < Math.min(count, TreeLod.PROPS.length + 1); level++) { result.add(slotted(name, level)); }
        return result;
    }

    /**
     * A placed object's model, in every view: a model with colour slots is one shared mesh whose placements pass their
     * colours per draw ({@link RigidGlb#colourSlotMesh}, applied by model-colour-slots.glsl); another model is the model
     * itself, a missing file the marked box ({@link #decoration}).
     */
    Model slotted(String name) { return slotted(name, 0); }

    /** {@link #slotted(String)} at a mesh level ({@link #objectLevels}). */
    Model slotted(String name, int level) {
        var source = RigidGlb.source(root, name);
        if (!source.file().isFile()) { return decoration(name, megamek.common.board.BoardDecoration.Colours.NONE); }
        if (!hasSlots.computeIfAbsent(name, key -> !RigidGlb.colourSlots(source.file()).isEmpty())) { return lodModel(name, level); }
        int own = ownLevel(name, level);
        // The key cannot meet a detail level's name or a colour copy's "name[...]".
        return models.computeIfAbsent((own == 0 ? name : MeshLod.name(name, own)) + "#slots",
              key -> createModel(RigidGlb.colourSlotMesh(decoded(name).get(own))));
    }

    /**
     * A placed model in its colours as one model of its own ({@link #model(String,
     * megamek.common.board.BoardDecoration.Colours)}), for the editor preview's ghosts; a missing file is a marked box,
     * so missing authored references remain editable and visible instead of aborting the board renderer.
     */
    Model decoration(String name, megamek.common.board.BoardDecoration.Colours colours) {
        if (RigidGlb.source(root, name).file().isFile()) { return model(name, colours); }
        return models.computeIfAbsent("missing-decoration", key -> new com.badlogic.gdx.graphics.g3d.utils.ModelBuilder()
              .createBox(8, 8, 8, new com.badlogic.gdx.graphics.g3d.Material(ColorAttribute.createDiffuse(1, .25f, .1f, 1)),
                    com.badlogic.gdx.graphics.VertexAttributes.Usage.Position | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal));
    }

    /**
     * A decal's image by path convention ({@link megamek.common.board.BoardDecalArt#image}): one mipmapped texture per
     * image, clamped at its edges, so no neighbour's texels bleed into any mip level (paint draws are grouped per image
     * by {@code Layer.paintGroups} instead). Its mip levels are averaged by alpha ({@link #paintTexture}).
     */
    Texture decorationPaint(String asset) {
        File file = megamek.common.board.BoardDecalArt.image(asset);
        if (!file.isFile()) { return null; }
        return materials.computeIfAbsent(file.toPath().toAbsolutePath().normalize().toString(), key -> paintTexture(file));
    }

    /**
     * Straight-alpha mip levels whose colours are averaged by alpha, as premultiplied filtering would average them:
     * glGenerateMipmap would weigh the colour of transparent texels equally and tint multi-coloured edges at distance.
     * A level's fully transparent texels keep the plain average of the level above, the image's own edge colour.
     */
    private static Texture paintTexture(File file) {
        Pixmap source = GpuColorTexture.decode(new FileHandle(file));
        Pixmap image = new Pixmap(source.getWidth(), source.getHeight(), Pixmap.Format.RGBA8888);
        try {
            image.setBlending(Pixmap.Blending.None);
            image.drawPixmap(source, 0, 0);
            Texture texture = GpuColorTexture.create(image, false);
            int width = image.getWidth(), height = image.getHeight();
            float[] weighted = new float[width * height * 4], plain = new float[width * height * 3];
            var bytes = image.getPixels();
            for (int texel = 0; texel < width * height; texel++) {
                float alpha = (bytes.get(texel * 4 + 3) & 255) / 255f;
                for (int c = 0; c < 3; c++) {
                    plain[texel * 3 + c] = GpuColor.linear((bytes.get(texel * 4 + c) & 255) / 255f);
                    weighted[texel * 4 + c] = plain[texel * 3 + c] * alpha;
                }
                weighted[texel * 4 + 3] = alpha;
            }
            texture.bind();
            for (int level = 1; width > 1 || height > 1; level++) {
                int w = Math.max(1, width / 2), h = Math.max(1, height / 2);
                float[] nextWeighted = new float[w * h * 4], nextPlain = new float[w * h * 3];
                var upload = com.badlogic.gdx.utils.BufferUtils.newByteBuffer(w * h * 4);
                for (int y = 0; y < h; y++) {
                    double top = y * (double) height / h, bottom = (y + 1) * (double) height / h;
                    for (int x = 0; x < w; x++) {
                        int texel = y * w + x;
                        double left = x * (double) width / w, right = (x + 1) * (double) width / w;
                        // Integrate the whole footprint: a 3-wide source must not lose its last column at 1-wide.
                        for (int sy = (int) top; sy < Math.ceil(bottom); sy++) {
                            for (int sx = (int) left; sx < Math.ceil(right); sx++) {
                                int from = sy * width + sx;
                                float weight = (float) ((Math.min(bottom, sy + 1) - Math.max(top, sy))
                                      * (Math.min(right, sx + 1) - Math.max(left, sx)) / ((bottom - top) * (right - left)));
                                for (int c = 0; c < 4; c++) { nextWeighted[texel * 4 + c] += weighted[from * 4 + c] * weight; }
                                for (int c = 0; c < 3; c++) { nextPlain[texel * 3 + c] += plain[from * 3 + c] * weight; }
                            }
                        }
                        float alpha = nextWeighted[texel * 4 + 3];
                        for (int c = 0; c < 3; c++) {
                            if (alpha > 0) { nextPlain[texel * 3 + c] = Math.min(1, nextWeighted[texel * 4 + c] / alpha); }
                            // Re-encode only at the sRGB storage boundary; the weighted mip calculation stays linear.
                            upload.put(texel * 4 + c, (byte) Math.round(GpuColor.display(nextPlain[texel * 3 + c]) * 255));
                        }
                        upload.put(texel * 4 + 3, (byte) Math.round(alpha * 255));
                    }
                }
                try {
                    Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, level, GL30.GL_SRGB8_ALPHA8, w, h, 0,
                          GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, upload);
                } finally { Reference.reachabilityFence(upload); }
                weighted = nextWeighted; plain = nextPlain; width = w; height = h;
            }
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            // Ground paint is seen at grazing angles; keep its lines sharp along the view direction.
            texture.setAnisotropicFilter(8);
            return texture;
        } finally {
            source.dispose();
            image.dispose();
        }
    }

    /** Custom kits override the exact tileset path for buildings, fuel tanks and industrial structures. */
    GpuBuilding.Assembly building(String asset, int levels, long seed) {
        return building(asset, levels, seed, true);
    }

    GpuBuilding.Assembly building(String asset, int levels, long seed, boolean withInterior) {
        if (!asset.startsWith("buildings/") || BoardWall.supports(asset)) { return null; }
        if (!buildings.containsKey(asset)) {
            File customRoot = new File(Configuration.dataDir(), "models/buildings");
            FileHandle file = new FileHandle(TilesetScenery.customBuildingFile(asset));
            buildings.put(asset, file.file().isFile() ? new GpuBuilding(file, customRoot.toPath(), this::createModel) : null);
        }
        GpuBuilding building = buildings.get(asset);
        return building == null ? null : building.assemble(levels, seed, withInterior);
    }

    /**
     * What each terrain sharing these assets draws: its installed chunks at its last commit, plus whatever its builds
     * took since ({@link #take}) and have not installed yet.
     */
    private record Live(java.util.Set<GpuBuilding.Assembly> buildings, java.util.Set<Model> models) { }
    private final Map<Object, Live> users = new java.util.IdentityHashMap<>();

    /**
     * Records the generated buildings, industrial plants and rail and maglev routes that {@code user}'s installed chunks draw,
     * then retires those no user of these assets draws or is still building with. On the GL thread, at a scene commit,
     * when {@code user} has no chunk build in flight.
     */
    void retain(Object user, java.util.Set<GpuBuilding.Assembly> buildings, java.util.Set<Model> industrial, java.util.Set<Model> routes) {
        java.util.Set<Model> models = new java.util.HashSet<>(industrial);
        models.addAll(routes);
        users.put(user, new Live(new java.util.HashSet<>(buildings), models));
        java.util.Set<GpuBuilding.Assembly> liveBuildings = new java.util.HashSet<>();
        java.util.Set<Model> liveModels = new java.util.HashSet<>();
        for (Live live : users.values()) { liveBuildings.addAll(live.buildings()); liveModels.addAll(live.models()); }
        this.buildings.values().stream().filter(java.util.Objects::nonNull).forEach(building -> building.retain(liveBuildings));
        retain(this.industrial, liveModels);
        retain(this.routes, liveModels);
    }

    /**
     * Keeps what one of {@code user}'s chunk builds took until {@code user}'s own next {@link #retain}, so another
     * user's commit cannot retire it between collection and installation.
     */
    void take(Object user, GpuBuilding.Assembly building, Model model) {
        Live live = users.computeIfAbsent(user, key -> new Live(new java.util.HashSet<>(), new java.util.HashSet<>()));
        if (building != null) { live.buildings().add(building); }
        if (model != null) { live.models().add(model); }
    }

    /** A disposed borrower draws nothing; the next {@link #retain} retires what only it drew. */
    void release(Object user) { users.remove(user); }

    Model industrial(BoardIndustrial.Layout layout) {
        return industrial.computeIfAbsent(layout, key -> {
            // Textures are asset-owned; decode before allocating native mesh buffers.
            Texture paint = material("structures/industrial/paint"), steel = material("structures/industrial/steel");
            // Their planar repeats widen with their textures (GpuMaterialCatalog); the fan is fitted to its opening.
            var data = BoardIndustrial.model(key, coverage("structures/industrial/paint"), coverage("structures/industrial/steel"));
            Texture fan = data.materials.size > 2 ? material("structures/industrial/fan") : null;
            Model model = new Model(data);
            for (var surface : model.materials) {
                // An opaque diffuse material with its finish stays eligible for the existing prop batch.
                String name = surface.id.equals("fan") ? "structures/industrial/fan"
                      : surface.id.equals("paint") ? "structures/industrial/paint" : "structures/industrial/steel";
                surface.set(TextureAttribute.createDiffuse(surface.id.equals("fan") ? fan
                      : surface.id.equals("paint") ? paint : steel));
                finish(name, surface);
            }
            return model;
        });
    }

    /** Procedural models belong to this renderer and survive while installed or cached chunks use them. */
    Model route(BoardRail.Layout layout) {
        return routes.computeIfAbsent(layout, key -> new Model(BoardRail.model(key)));
    }

    private static void retain(Map<?, Model> models, java.util.Set<Model> live) {
        models.values().removeIf(model -> {
            if (live.contains(model)) { return false; }
            model.dispose();
            return true;
        });
    }

    private Model createModel(ModelData data) {
        return ModelTextures.create(data, materials, (filename, color) -> texture(new FileHandle(filename), color));
    }

    Model lodModel(String name, int level) {
        if (BoardWall.supports(name)) { return wallLevels(name, 1).get(level); }
        return modelLods.computeIfAbsent(name, shape -> {
            // A tree's winter form may be its bare file's snow variant.
            var source = RigidGlb.source(root, shape);
            if (!source.file().isFile()) {
                throw new IllegalArgumentException("Missing board model " + source.file().getPath());
            }
            var data = RigidGlb.loadLods(source, root.toPath());
            List<Model> levels = new ArrayList<>();
            for (int index = 0; index < data.size(); index++) {
                int previous = data.indexOf(data.get(index));
                levels.add(previous < index ? levels.get(previous)
                      : models.computeIfAbsent(MeshLod.name(shape, index), key -> createModel(data.get(previous))));
            }
            return List.copyOf(levels);
        }).get(level);
    }

    /** The captured wall layout determines the shared mesh; this asset owner's ordinary cache disposes it. */
    Model wall(BoardWall.Layout layout) {
        return wallLevels(layout).getFirst();
    }

    /** Two authored component levels; remaining slots reuse the far mesh, like the ordinary rigid model loader. */
    List<Model> wallLevels(String asset, int levels) {
        return wallLevels(BoardWall.layout(asset, levels));
    }

    List<Model> wallLevels(BoardWall.Layout layout) {
        String shape = layout.asset() + "@height-" + layout.height() + "@arms-" + layout.arms();
        return modelLods.computeIfAbsent(shape, ignored -> {
            List<Model> result = new ArrayList<>();
            for (int lod = 0; lod < BoardWall.LODS; lod++) {
                int own = lod;
                result.add(models.computeIfAbsent(MeshLod.name(shape, lod),
                      key -> createModel(BoardWall.model(layout, own))));
            }
            while (result.size() < RigidGlb.LEVELS) { result.add(result.getLast()); }
            return List.copyOf(result);
        });
    }

    /** A board material's colour: albedo with its coverage, or with height for sculpted terrain. */
    Texture material(String name) {
        return texture(name, GpuMaterialMaps.colorLayout(name), true);
    }

    /** A board material's normal map in the board's layout (GpuMaterialMaps.normalLayout). */
    Texture materialNormal(String name) {
        return texture(name, GpuMaterialMaps.normalLayout(name), false);
    }

    /**
     * Gives a draw of a board material's own (a road, a tile, an industrial or decoration material) the material's whole
     * finish, as a GLB material whose base colour is its albedo has it (RigidGlb): GpuModelMaterial, its normal and
     * occlusion maps, the emission map as the emissive texture and the heightmap as the bump texture.
     */
    Material finish(String name, Material material) {
        var finish = GpuMaterialMaps.modelFinish(definition(name));
        material.set(new GpuModelMaterial(finish.roughness(), finish.metallic(), finish.scatter(),
              finish.map() == null ? null : texture(name, GpuMaterialMaps.Layout.METALLIC_ROUGHNESS, false), finish.tint(),
              finish.glow(), true, new float[] { finish.depthU(), finish.depthV(), finish.minimumLayers(),
                    finish.maximumLayers() }));
        if (finish.emission() != null) {
            material.set(TextureAttribute.createEmissive(texture(new FileHandle(finish.emission()), true)));
        }
        GodotMaterial board = definition(name);
        if (board.normal() != null) {
            material.set(TextureAttribute.createNormal(texture(name, GpuMaterialMaps.Layout.NORMAL, false)));
        }
        if (board.occlusion() != null) {
            material.set(TextureAttribute.createAmbient(texture(name, GpuMaterialMaps.Layout.OCCLUSION, false)));
        }
        // The heightmap is the material's bump texture (GpuModelMaterial).
        if (finish.heightmap() != null) {
            material.set(TextureAttribute.createBump(texture(name, GpuMaterialMaps.Layout.HEIGHT, false)));
        }
        return material;
    }

    /** The albedo image of a board material, for code that composes its own textures from it. */
    FileHandle albedo(String name) {
        FileHandle file = new FileHandle(definition(name).albedo());
        requireFiles(List.of(file));
        return file;
    }

    Texture scatter() {
        return materials.computeIfAbsent("scatter-atlas", key -> GpuScatter.atlas(this));
    }

    Cliff road(String name) {
        return cliffs.computeIfAbsent("roads/" + name, key -> new Cliff(texture(key, GpuMaterialMaps.Layout.COLOR, true),
              texture(key, GpuMaterialMaps.Layout.NORMAL, false), texture(key, GpuMaterialMaps.Layout.RELIEF, false)));
    }

    /**
     * Each road material's heightmap parallax in {@link GpuRoads#MATERIALS} order (GpuMaterialMaps.ModelFinish): depth
     * along U and V, and the deep parallax's minimum and maximum layers; zero depth without a heightmap.
     */
    float[] roadRelief() {
        if (roadRelief != null) { return roadRelief; }
        float[] relief = new float[GpuRoads.MATERIALS.size() * 4];
        for (int index = 0; index < GpuRoads.MATERIALS.size(); index++) {
            var finish = GpuMaterialMaps.modelFinish(definition("roads/" + GpuRoads.MATERIALS.get(index)));
            if (finish.heightmap() == null) { continue; }
            relief[index * 4] = finish.depthU();
            relief[index * 4 + 1] = finish.depthV();
            relief[index * 4 + 2] = finish.minimumLayers();
            relief[index * 4 + 3] = finish.maximumLayers();
        }
        roadRelief = relief;
        return relief;
    }

    Ice ice() {
        if (ice == null) {
            String name = "terrain/ice/sheet";
            Texture color = texture(name, GpuMaterialMaps.Layout.COLOR, true);
            var maps = List.of(layer(name, GpuMaterialMaps.Layout.NORMAL), layer(name, GpuMaterialMaps.Layout.ICE));
            int width = Math.max(maps.get(0).width(), maps.get(1).width());
            int height = Math.max(maps.get(0).height(), maps.get(1).height());
            ice = new Ice(color, repeatingArray(new MaterialArrayData(maps.stream().map(Layer::pixels).toList(), width, height,
                  false)), coverage(name));
        }
        return ice;
    }

    /** One complete sculpt material and its repeat scale. */
    Sculpt sculpt(String name) {
        return sculpts.computeIfAbsent(name, key -> new Sculpt(material(sculptName(key)), materialNormal(sculptName(key)),
              sculptTile(key)));
    }

    /**
     * The metres one repeat of a sculpted-terrain material covers: its catalogued repeat at its catalogued width, widened
     * with the texture ({@link #coverage}). Array-backed materials need it without uploading a second set of 2D textures.
     */
    float sculptTile(String name) {
        String material = sculptName(name);
        definition(material);
        float metres = catalog().loose(material).metres();
        if (Float.isNaN(metres)) { throw new IllegalArgumentException("A sculpted material needs its catalogued metres: " + name); }
        return metres * coverage(material);
    }

    private static void requireFiles(List<FileHandle> files) {
        for (FileHandle file : files) {
            if (!file.exists()) {
                throw new IllegalArgumentException("Missing required Enhanced terrain asset: " + file);
            }
        }
    }

    /** Original rim lightness mask for drops up to two levels, without normalization or generated normals. */
    BoardScene.Pixels inclineMask() {
        if (incline == null) {
            incline = loadRimMask("terrain/classic/incline_dark");
        }
        return incline;
    }

    /** The coarser lightness mask of a drop above two levels, the board's own high-incline split. */
    BoardScene.Pixels highInclineMask() {
        if (highIncline == null) {
            highIncline = loadRimMask("terrain/classic/high_incline_dark");
        }
        return highIncline;
    }

    private BoardScene.Pixels loadRimMask(String asset) {
        try {
            return new BoardScene.Pixels(ImageIO.read(albedo(asset).file()));
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot load the cliff-top rim mask", error);
        }
    }

    /**
     * A board material's packed layout as a texture, keyed by its main map so a model naming that map shares it
     * (GpuMaterialMaps.modelMap).
     */
    private Texture texture(String name, GpuMaterialMaps.Layout layout, boolean color) {
        return texture(new FileHandle(GpuMaterialMaps.primary(definition(name), layout)), color,
              GpuMaterialMaps.cacheSuffix(layout), packed(name, layout));
    }

    private Texture texture(FileHandle file, boolean color) { return texture(file, color, "", null); }

    private Texture texture(FileHandle file, boolean color, String suffix, Supplier<Pixmap> pixels) {
        return materials.computeIfAbsent((color ? "color:" : "data:") + file.file().toPath().toAbsolutePath().normalize() + suffix, key -> {
            Texture texture = pixels != null ? GpuColorTexture.create(pixels, true, color)
                  : color ? GpuColorTexture.create(file, true) : GpuColorTexture.createData(file, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            boolean repeating = file.file().toPath().toAbsolutePath().normalize()
                  .startsWith(new File(root, "textures").toPath().toAbsolutePath().normalize());
            Texture.TextureWrap wrap = repeating ? Texture.TextureWrap.Repeat : Texture.TextureWrap.ClampToEdge;
            texture.setWrap(wrap, wrap);
            if (repeating && (file.parent().name().equals("surfaces") || file.parent().name().equals("roads")
                  || file.parent().name().equals("ice") || file.parent().name().equals("industrial"))) {
                // Detailed materials retain full resolution; mipmaps and supported anisotropy handle distance.
                texture.setAnisotropicFilter(8);
            } else if (repeating) {
                int level = Math.max(0, (int) Math.ceil(Math.log(Math.max(texture.getWidth(), texture.getHeight()) / 128.0) / Math.log(2)));
                texture.bind();
                // Match the board artwork's texel density while retaining editable source images.
                Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, level);
            }
            return texture;
        });
    }

    Texture liquid(BoardLiquid.Textures source, float time) {
        return liquidAnimation(source).at(time);
    }

    Animation<Texture> liquidAnimation(BoardLiquid.Textures source) {
        return liquids.computeIfAbsent(source, this::loadLiquid);
    }

    /** A file of the board tileset, such as a liquid animation the board plays and the minimap shows. */
    static File tilesetFile(String path) {
        return new File(Configuration.dataDir(), "models/board/tileset/" + path);
    }

    private Animation<Texture> loadLiquid(BoardLiquid.Textures source) {
        Animation<BoardScene.Pixels> decoded = readWater(tilesetFile(source.base()));
        Animation<BoardScene.Pixels> foam = source.foam().isEmpty() ? null
              : readAnimation(tilesetFile(source.foam()), false);
        if (foam != null && !Arrays.equals(decoded.ends(), foam.ends())) {
            throw new IllegalStateException("Liquid and foam GIF timelines must match: " + source);
        }
        List<Texture> frames = new ArrayList<>();
        try {
            for (int index = 0; index < decoded.frames().size(); index++) {
                BoardScene.Pixels image = decoded.frames().get(index);
                BoardScene.Pixels overlay = foam == null ? null : foam.frames().get(index);
                if (overlay != null && (overlay.width() != image.width() || overlay.height() != image.height())) {
                    throw new IllegalStateException("Liquid and foam GIF dimensions must match: " + source);
                }
                var pixels = new float[image.width() * image.height() * 4];
                for (int pixel = 0; pixel < image.width() * image.height(); pixel++) {
                    int base = image.rgba(pixel), foamPixel = overlay == null ? 0 : overlay.rgba(pixel);
                    float alpha = (foamPixel & 255) / 255f;
                    for (int shift = 24; shift >= 8; shift -= 8) {
                        float a = GpuColor.linear((base >>> shift & 255) / 255f);
                        float b = GpuColor.linear((foamPixel >>> shift & 255) / 255f);
                        pixels[pixel * 4 + (24 - shift) / 8] = a + (b - a) * alpha;
                    }
                    pixels[pixel * 4 + 3] = (base & 255) / 255f;
                }
                Texture texture = GpuColorTexture.ldr(pixels, image.width(), image.height(), true);
                texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
                frames.add(texture);
            }
            return new Animation<>(List.copyOf(frames), decoded.ends(), decoded.duration());
        } catch (RuntimeException error) {
            frames.forEach(Texture::dispose);
            throw error;
        }
    }

    static Animation<BoardScene.Pixels> readWater(File file) {
        return readAnimation(file, true);
    }

    /** Compose optimized GIF patches with their disposal and timing; foam retains its transparent coverage. */
    static Animation<BoardScene.Pixels> readAnimation(File file, boolean fillEdges) {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            reader.setInput(input);
            var stream = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
            var screen = child(stream, "LogicalScreenDescriptor");
            int width = attribute(screen, "logicalScreenWidth"), height = attribute(screen, "logicalScreenHeight");
            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int count = reader.getNumImages(true);
            List<BoardScene.Pixels> frames = new ArrayList<>();
            float[] ends = new float[count];
            float duration = 0;
            for (int index = 0; index < count; index++) {
                var metadata = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0");
                var control = child(metadata, "GraphicControlExtension");
                var descriptor = child(metadata, "ImageDescriptor");
                String disposal = control.getAttributes().getNamedItem("disposalMethod").getNodeValue();
                int left = attribute(descriptor, "imageLeftPosition"), top = attribute(descriptor, "imageTopPosition");
                BufferedImage patch = reader.read(index);
                BufferedImage previous = null;
                if (disposal.equals("restoreToPrevious")) {
                    previous = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    previous.setData(canvas.getData());
                }
                Graphics2D graphics = canvas.createGraphics();
                try {
                    graphics.drawImage(patch, left, top, null);
                    frames.add(fillEdges ? waterFrame(canvas) : new BoardScene.Pixels(canvas));
                    if (disposal.equals("restoreToBackgroundColor")) {
                        graphics.setComposite(AlphaComposite.Clear);
                        graphics.fillRect(left, top, patch.getWidth(), patch.getHeight());
                    }
                } finally {
                    graphics.dispose();
                }
                if (previous != null) {
                    canvas = previous;
                }
                duration += Math.max(0.02f, attribute(control, "delayTime") / 100f);
                ends[index] = duration;
            }
            return new Animation<>(List.copyOf(frames), ends, duration);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot decode board liquid animation " + file, exception);
        } finally {
            reader.dispose();
        }
    }

    /** The mesh defines the shoreline; repeating hex-shaped alpha would pinch a scrolling waterfall. */
    private static BoardScene.Pixels waterFrame(BufferedImage canvas) {
        BufferedImage frame = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
        List<Integer> filledRows = new ArrayList<>();
        for (int y = 0; y < canvas.getHeight(); y++) {
            int left = 0, right = canvas.getWidth() - 1;
            while (left < right && (canvas.getRGB(left, y) >>> 24) == 0) {
                left++;
            }
            while (right > left && (canvas.getRGB(right, y) >>> 24) == 0) {
                right--;
            }
            if ((canvas.getRGB(left, y) >>> 24) == 0) { continue; }
            filledRows.add(y);
            int color = canvas.getRGB(left, y);
            for (int x = 0; x < canvas.getWidth(); x++) {
                int pixel = canvas.getRGB(Math.max(left, Math.min(right, x)), y);
                // Mars water dithers transparency inside the hex too; material opacity replaces that 2D mask.
                if ((pixel >>> 24) != 0) { color = pixel; }
                frame.setRGB(x, y, color);
            }
        }
        if (filledRows.isEmpty()) { throw new IllegalArgumentException("Empty liquid image"); }
        // Some themed GIFs also have a completely transparent row above or below the hex.
        for (int y = 0; y < canvas.getHeight(); y++) {
            if ((frame.getRGB(0, y) >>> 24) != 0) { continue; }
            int nearest = filledRows.getFirst();
            for (int row : filledRows) {
                if (Math.abs(row - y) < Math.abs(nearest - y)) { nearest = row; }
            }
            for (int x = 0; x < canvas.getWidth(); x++) { frame.setRGB(x, y, frame.getRGB(x, nearest)); }
        }
        return new BoardScene.Pixels(frame);
    }

    /** Interior meshes share the shell's local coordinates; geometry tuning only changes their instance transform. */
    Model interior(String asset, int levels) {
        return interiors.computeIfAbsent(new Interior(asset, levels),
              key -> GpuBuildingInterior.build(model(key.asset()), key.levels()));
    }

    private static org.w3c.dom.Node child(org.w3c.dom.Node parent, String name) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeName().equals(name)) {
                return node;
            }
        }
        throw new IllegalArgumentException("Missing GIF metadata: " + name);
    }

    private static int attribute(org.w3c.dom.Node node, String name) {
        return Integer.parseInt(node.getAttributes().getNamedItem(name).getNodeValue());
    }

    @Override
    public void dispose() {
        routes.values().forEach(Model::dispose);
        routes.clear();
        industrial.values().forEach(Model::dispose);
        industrial.clear();
        buildings.values().stream().filter(java.util.Objects::nonNull).forEach(GpuBuilding::dispose);
        buildings.clear();
        interiors.values().forEach(Model::dispose);
        interiors.clear();
        models.values().forEach(Model::dispose);
        materials.values().forEach(Texture::dispose);
        liquids.values().forEach(animation -> animation.frames().forEach(Texture::dispose));
        models.clear();
        modelLods.clear();
        materials.clear();
        cliffs.clear();
        magmas.values().forEach(MaterialArrays::dispose);
        magmas.clear();
        lavaEmission = null;
        sculpts.clear();
        definitions.clear();
        coverages.clear();
        catalog = null;
        if (sculptArray != null) { sculptArray.dispose(); sculptArray = null; }
        if (finishArrays != null) { finishArrays.dispose(); finishArrays = null; }
        roadRelief = null;
        if (roadArray != null) { roadArray.dispose(); roadArray = null; }
        if (ice != null) { ice.maps().dispose(); ice = null; }
        liquids.clear();
        users.clear();
        incline = null;
        highIncline = null;
    }
}
