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
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import megamek.common.Configuration;
import megamek.common.board.TilesetScenery;

/** GL-thread ownership of shared models, repeating terrain materials, and animated liquid frames. */
final class GpuAssets implements Disposable {
    private final File root = new File(Configuration.dataDir(), "models/board");
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
    private final Map<String, JsonValue> sculptEntries = new HashMap<>();
    private MaterialArrays sculptArray;
    private SculptRoughness sculptRoughness;
    private MaterialArrays roadArray;
    private Ice ice;
    private final Map<BoardLiquid.Textures, Animation<Texture>> liquids = new HashMap<>();
    private BoardScene.Pixels incline;
    private BoardScene.Pixels highIncline;

    private record Interior(String asset, int levels) { }

    /** Three aligned, repeating maps. Surface channels are height, roughness, occlusion and relief range. */
    record Cliff(Texture color, Texture normal, Texture surface) { }

    /** The ice sheet's colour, and its aligned normal and surface maps as layers 0 and 1 of one array. */
    record Ice(Texture color, TextureArray maps) { }

    /**
     * Molten lava's or solid crust's aligned maps in separate colour and numerical arrays:
     * colour; normal; surface (height, roughness, occlusion, relief range); heat. Molten heat is normalized linear
     * emission RGB with original heat coverage in A; crust retains R heat and A crack glow. Both require complete sets.
     */
    MaterialArrays magma(boolean molten) {
        if (magmas.containsKey(molten)) { return magmas.get(molten); }
        var files = new ArrayList<FileHandle>();
        for (String map : List.of("", "-normal", "-surface", "-heat")) {
            files.add(materialFile("terrain/lava/" + (molten ? "lava" : "crust") + map));
        }
        MaterialArrays maps = repeatingArray(files, 4);
        magmas.put(molten, maps);
        return maps;
    }

    /** The exported linear emission's scale and area mean, owned with the corresponding material maps. */
    record LavaEmission(float scale, float red, float green, float blue) { }

    LavaEmission lavaEmission() {
        if (lavaEmission != null) { return lavaEmission; }
        FileHandle definition = new FileHandle(new File(root, "textures/terrain/lava/lava.json"));
        FileHandle image = materialFile("terrain/lava/lava-heat");
        requireFiles(List.of(definition, image));
        JsonValue value = new JsonReader().parse(definition).get("emissionScale");
        if (value == null || !value.isNumber() || !Float.isFinite(value.asFloat()) || value.asFloat() < 0) {
            throw new IllegalArgumentException("Lava emissionScale must be finite and nonnegative: " + definition);
        }
        float scale = value.asFloat();
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
     * with occlusion in alpha, and the metres one repeat spans (from the set's manifest).
     */
    record Sculpt(Texture color, Texture normal, float tile) { }

    /** Aligned colour/height and normal/AO arrays in the supplied stable material order. */
    MaterialArrays sculptArray(List<String> names) {
        if (sculptArray == null) {
            var files = new ArrayList<FileHandle>();
            for (String name : names) {
                sculptEntry(name);
                files.add(materialFile("terrain/surfaces/" + name));
                files.add(materialFile("terrain/surfaces/" + name + "-normal"));
            }
            sculptArray = repeatingArray(files, 2);
        }
        return sculptArray;
    }

    /** Optional authored scalar/map metadata in the same stable material order as the sculpt array. */
    record SculptRoughness(TextureArray maps, float[] metadata) { }

    /** Map > scalar > missing-property fallback. A declared invalid map is never silently ignored. */
    SculptRoughness sculptRoughness(List<String> names) {
        if (sculptRoughness != null) { return sculptRoughness; }
        var files = new ArrayList<FileHandle>();
        float[] metadata = new float[names.size() * 2];
        Arrays.fill(metadata, -1);
        File directory = new File(root, "textures/terrain/surfaces");
        for (int index = 0; index < names.size(); index++) {
            JsonValue entry = sculptEntry(names.get(index));
            if (entry.has("roughness")) {
                float scalar = entry.getFloat("roughness");
                if (!Float.isFinite(scalar) || scalar < 0 || scalar > 1) {
                    throw new IllegalArgumentException("Invalid sculpt roughness scalar: " + names.get(index));
                }
                metadata[index * 2] = scalar;
            }
            if (entry.has("roughness_map")) {
                String name = entry.getString("roughness_map");
                if (!name.matches("[a-zA-Z0-9_-]+\\.png")) {
                    throw new IllegalArgumentException("Sculpt roughness_map must name one local PNG: " + name);
                }
                FileHandle file = new FileHandle(new File(directory, name));
                if (!file.exists()) { throw new IllegalArgumentException("Missing sculpt roughness map: " + file); }
                metadata[index * 2 + 1] = files.size();
                files.add(file);
            }
        }
        // Decode/validate every supplied image before allocating GL storage. No resizing or inferred material data.
        var data = new RoughnessArrayData(files);
        TextureArray maps = null;
        try {
            maps = new TextureArray(data);
            maps.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            maps.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            float anisotropy = Math.min(8, GLTexture.getMaxAnisotropicFilterLevel());
            if (anisotropy > 1) {
                maps.bind();
                Gdx.gl.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL20.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy);
            }
            sculptRoughness = new SculptRoughness(maps, metadata);
            return sculptRoughness;
        } catch (RuntimeException exception) {
            if (maps != null) { maps.dispose(); }
            throw exception;
        } finally { data.disposeSources(); }
    }

    /** R8 is independent of the albedo/normal pair; all supplied maps retain their exact declared resolution. */
    private static final class RoughnessArrayData implements TextureArrayData {
        private final List<Pixmap> sources = new ArrayList<>();
        private final int depth;
        private boolean prepared;
        private int width = 1, height = 1;

        RoughnessArrayData(List<FileHandle> files) {
            depth = Math.max(1, files.size());
            try {
                for (FileHandle file : files) {
                    byte[] bytes = file.readBytes();
                    if (bytes.length < 26 || bytes[0] != (byte) 137 || bytes[1] != 80 || bytes[2] != 78
                          || bytes[3] != 71 || bytes[24] != 8 || (bytes[25] != 0 && bytes[25] != 2
                          && bytes[25] != 4 && bytes[25] != 6)) {
                        throw new IllegalArgumentException("Roughness requires a non-indexed PNG8; no implicit precision conversion: " + file);
                    }
                    Pixmap pixels = GpuColorTexture.decode(file);
                    sources.add(pixels);
                    if (sources.size() == 1) { width = pixels.getWidth(); height = pixels.getHeight(); }
                    if (pixels.getWidth() != width || pixels.getHeight() != height) {
                        throw new IllegalArgumentException("Roughness array dimensions must agree; no implicit resize: " + file);
                    }
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int rgba = pixels.getPixel(x, y), r = rgba >>> 24;
                            if (r != ((rgba >>> 16) & 255) || r != ((rgba >>> 8) & 255)) {
                                throw new IllegalArgumentException("Roughness map must contain scalar data: " + file);
                            }
                        }
                    }
                }
            } catch (RuntimeException exception) { disposeSources(); throw exception; }
        }

        void disposeSources() { sources.forEach(Pixmap::dispose); sources.clear(); }
        @Override public boolean isPrepared() { return prepared; }
        @Override public void prepare() { prepared = true; }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
        @Override public int getDepth() { return depth; }
        @Override public boolean isManaged() { return false; }
        @Override public int getInternalFormat() { return GL30.GL_RED; }
        @Override public int getGLType() { return GL20.GL_UNSIGNED_BYTE; }

        @Override
        public void consumeTextureArrayData() {
            Gdx.gl30.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_R8, width, height, getDepth(), 0,
                  GL30.GL_RED, GL20.GL_UNSIGNED_BYTE, (java.nio.Buffer) null);
            var upload = com.badlogic.gdx.utils.BufferUtils.newByteBuffer(width * height);
            var oldAlignment = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(1);
            Gdx.gl.glGetIntegerv(GL20.GL_UNPACK_ALIGNMENT, oldAlignment);
            int alignment = oldAlignment.get(0);
            Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1);
            try {
                for (int layer = 0; layer < getDepth(); layer++) {
                    upload.clear();
                    if (sources.isEmpty()) { upload.put((byte) 0); }
                    else {
                        Pixmap pixels = sources.get(layer);
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) { upload.put((byte) (pixels.getPixel(x, y) >>> 24)); }
                        }
                    }
                    upload.flip();
                    Gdx.gl30.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, width, height, 1,
                          GL30.GL_RED, GL20.GL_UNSIGNED_BYTE, upload);
                }
            } finally {
                Reference.reachabilityFence(upload);
                Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, alignment);
            }
            Gdx.gl.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
            prepared = false;
        }
    }

    /**
     * Colour, normal and surface maps of every road material, three layers each in {@link GpuRoads#MATERIALS} order,
     * so that one draw holds all of a chunk's road coats. All maps are required.
     */
    MaterialArrays roadArray() {
        if (roadArray == null) {
            var files = new ArrayList<FileHandle>();
            for (String name : GpuRoads.MATERIALS) {
                for (String map : List.of("", "-normal", "-surface")) { files.add(materialFile("roads/" + name + map)); }
            }
            roadArray = repeatingArray(files, 3);
        }
        return roadArray;
    }

    /** One owned pair: sRGB colour/height and raw numerical maps, each compacted in material order. */
    record MaterialArrays(TextureArray color, TextureArray data) implements Disposable {
        @Override public void dispose() { color.dispose(); data.dispose(); }
    }

    /** Sets of aligned maps; their first map is colour and all remaining maps carry numerical data. */
    private static MaterialArrays repeatingArray(List<FileHandle> files, int maps) {
        requireFiles(files);
        if (files.isEmpty() || maps < 2 || files.size() % maps != 0) {
            throw new IllegalArgumentException("Incomplete repeating material arrays");
        }
        var colors = new ArrayList<FileHandle>();
        var data = new ArrayList<FileHandle>();
        for (int index = 0; index < files.size(); index++) {
            (index % maps == 0 ? colors : data).add(files.get(index));
        }
        // Both arrays retain the previous set's grid, even when a numerical source has a different size.
        var first = GpuColorTexture.decode(files.getFirst());
        int width = first.getWidth(), height = first.getHeight();
        first.dispose();
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
        private final List<FileHandle> files;
        private final int width, height;
        private final boolean color;
        private boolean prepared;

        MaterialArrayData(List<FileHandle> files, int width, int height, boolean color) {
            requireFiles(files);
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
                    var pixels = GpuColorTexture.decode(files.get(layer));
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
        if (!asset.startsWith("buildings/")) { return null; }
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
            var data = BoardIndustrial.model(key);
            Texture fan = data.materials.size > 2 ? material("structures/industrial/fan") : null;
            Model model = new Model(data);
            for (var surface : model.materials) {
                if (surface.id.equals("fan")) {
                    // Shared opaque diffuse material stays eligible for the existing prop batch.
                    surface.set(TextureAttribute.createDiffuse(fan));
                    continue;
                }
                boolean painted = surface.id.equals("paint");
                surface.set(TextureAttribute.createDiffuse(painted ? paint : steel));
                float shine = painted ? .12f : .3f;
                surface.set(ColorAttribute.createSpecular(shine, shine, shine, 1),
                      FloatAttribute.createShininess(painted ? 20 : 45));
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

    Texture material(String name) {
        return texture(materialFile(name), true);
    }

    Texture materialData(String name) {
        return texture(materialFile(name), false);
    }

    Texture scatter() {
        return materials.computeIfAbsent("scatter-atlas", key -> GpuScatter.atlas(root));
    }

    Cliff road(String name) {
        return relief("roads", name);
    }

    Ice ice() {
        if (ice == null) {
            FileHandle color = materialFile("terrain/ice/sheet");
            List<FileHandle> maps = List.of(materialFile("terrain/ice/sheet-normal"), materialFile("terrain/ice/sheet-surface"));
            requireFiles(List.of(color, maps.get(0), maps.get(1)));
            var first = GpuColorTexture.decode(maps.getFirst());
            int width = first.getWidth(), height = first.getHeight();
            first.dispose();
            ice = new Ice(texture(color, true), repeatingArray(new MaterialArrayData(maps, width, height, false)));
        }
        return ice;
    }

    private Cliff relief(String folder, String family) {
        return cliffs.computeIfAbsent(folder + "/" + family, key -> {
            FileHandle color = materialFile(key);
            FileHandle normal = materialFile(key + "-normal");
            FileHandle surface = materialFile(key + "-surface");
            requireFiles(List.of(color, normal, surface));
            return new Cliff(texture(color, true), texture(normal, false), texture(surface, false));
        });
    }

    /** One complete sculpt material and its authored repeat scale. */
    Sculpt sculpt(String name) {
        return sculpts.computeIfAbsent(name, key -> {
            JsonValue entry = sculptEntry(key);
            return new Sculpt(texture(materialFile("terrain/surfaces/" + key), true), texture(materialFile("terrain/surfaces/" + key + "-normal"), false),
                  entry.getFloat("tile"));
        });
    }

    /** Array-backed materials need the repeat scale without decoding and uploading a second set of 2D textures. */
    float sculptTile(String name) {
        return sculptEntry(name).getFloat("tile");
    }

    private JsonValue sculptEntry(String name) {
        return sculptEntries.computeIfAbsent(name, key -> {
            FileHandle definition = new FileHandle(new File(root, "textures/terrain/surfaces/" + key + ".json"));
            requireFiles(List.of(definition, materialFile("terrain/surfaces/" + key),
                  materialFile("terrain/surfaces/" + key + "-normal")));
            // Like the textures, metadata belongs to this asset owner; asset reload creates a fresh owner.
            return new JsonReader().parse(definition);
        });
    }

    private static void requireFiles(List<FileHandle> files) {
        for (FileHandle file : files) {
            if (!file.exists()) {
                throw new IllegalArgumentException("Missing required Enhanced terrain asset: " + file);
            }
        }
    }

    private FileHandle materialFile(String name) {
        return new FileHandle(new File(root, "textures/" + name + ".png"));
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
            return new BoardScene.Pixels(ImageIO.read(materialFile(asset).file()));
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot load the cliff-top rim mask", error);
        }
    }

    private Texture texture(FileHandle file, boolean color) {
        return materials.computeIfAbsent((color ? "color:" : "data:") + file.file().toPath().toAbsolutePath().normalize(), key -> {
            Texture texture = color ? GpuColorTexture.create(file, true) : GpuColorTexture.createData(file, true);
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
        sculptEntries.clear();
        if (sculptArray != null) { sculptArray.dispose(); sculptArray = null; }
        if (sculptRoughness != null) { sculptRoughness.maps().dispose(); sculptRoughness = null; }
        if (roadArray != null) { roadArray.dispose(); roadArray = null; }
        if (ice != null) { ice.maps().dispose(); ice = null; }
        liquids.clear();
        users.clear();
        incline = null;
        highIncline = null;
    }
}
