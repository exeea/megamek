/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;

/** GL-thread ownership of shared models, repeating terrain materials, and animated liquid frames. */
final class GpuAssets implements Disposable {
    private final File root = new File(Configuration.dataDir(), "models/board");
    private final Map<String, Model> models = new HashMap<>();
    private final Map<String, List<Model>> modelLods = new HashMap<>();
    private final Map<Interior, Model> interiors = new HashMap<>();
    private final Map<String, GpuBuilding> buildings = new HashMap<>();
    private final Map<String, Texture> materials = new HashMap<>();
    private final Map<String, Cliff> cliffs = new HashMap<>();
    private final Map<Boolean, TextureArray> magmas = new HashMap<>();
    private final Map<String, Sculpt> sculpts = new HashMap<>();
    private final Map<String, JsonValue> sculptEntries = new HashMap<>();
    private TextureArray sculptArray;
    private TextureArray roadArray;
    private JsonValue sculptManifest;
    private Texture flatColor;
    private Texture flatNormal;
    private final Map<BoardLiquid.Textures, Animation<Texture>> liquids = new HashMap<>();
    private BoardScene.Pixels incline;
    private BoardScene.Pixels highIncline;

    private record Interior(String asset, int levels) { }

    /** Three aligned, repeating maps. Surface channels are height, roughness, occlusion and relief range. */
    record Cliff(Texture color, Texture normal, Texture surface) { }

    /**
     * Molten lava's or solid crust's aligned maps as the four layers of one array, so they take one texture unit:
     * colour; normal; surface (height, roughness, occlusion, relief range); heat (R heat, GB signed flow, A heat
     * bleeding onto crack walls). Null while the data set lacks any of them: magma then keeps its legacy artwork/GIF.
     */
    TextureArray magma(boolean molten) {
        if (magmas.containsKey(molten)) { return magmas.get(molten); }
        var files = new ArrayList<FileHandle>();
        for (String map : List.of("", "-normal", "-surface", "-heat")) {
            files.add(materialFile("magma/" + (molten ? "lava" : "crust") + map));
        }
        TextureArray maps = files.stream().allMatch(FileHandle::exists) ? repeatingArray(files, 4) : null;
        magmas.put(molten, maps);
        return maps;
    }

    /**
     * A sculpted-terrain material from {@code textures/sculpt}: albedo with height in alpha, a tangent-space normal
     * with occlusion in alpha, and the metres one repeat spans (from the set's manifest).
     */
    record Sculpt(Texture color, Texture normal, float tile) { }

    /** Existing colour/height and normal/AO maps, interleaved in one sampler for complete boundary materials. */
    TextureArray sculptArray(List<String> names) {
        if (sculptArray == null) {
            var files = new ArrayList<FileHandle>();
            for (String name : names) {
                files.add(materialFile("sculpt/" + name));
                files.add(materialFile("sculpt/" + name + "-normal"));
            }
            sculptArray = repeatingArray(files, 2);
        }
        return sculptArray;
    }

    /**
     * Colour, normal and surface maps of every road material, three layers each in {@link GpuRoads#MATERIALS} order,
     * so that one draw holds all of a chunk's road coats. Null while a data set lacks any of them: its roads then keep
     * their original materials.
     */
    TextureArray roadArray() {
        if (roadArray == null) {
            var files = new ArrayList<FileHandle>();
            for (String name : GpuRoads.MATERIALS) {
                for (String map : List.of("", "-normal", "-surface")) { files.add(materialFile("roads/" + name + map)); }
            }
            if (!files.stream().allMatch(FileHandle::exists)) { return null; }
            roadArray = repeatingArray(files, 3);
        }
        return roadArray;
    }

    /** Sets of {@code maps} aligned repeating maps, filtered like the separate material textures. */
    private static TextureArray repeatingArray(List<FileHandle> files, int maps) {
        var array = new TextureArray(new SculptArrayData(files, maps));
        array.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        array.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        // GLTexture.setAnisotropicFilter always sets GL_TEXTURE_2D, so it would leave an array isotropic (blurred
        // at grazing angles) and change whichever 2D texture the unit holds; set the array's own parameter.
        float anisotropy = Math.min(8, GLTexture.getMaxAnisotropicFilterLevel());
        if (anisotropy > 1) {
            array.bind();
            Gdx.gl.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL20.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy);
        }
        return array;
    }

    /** Uploaded once per renderer; missing maps get the same neutral fallback as ordinary sculpt materials. */
    private static final class SculptArrayData implements TextureArrayData {
        /** Neutral colour, flat normal and middling surface, by a layer's place in its material's set of maps. */
        private static final int[] FALLBACKS = { 0xa0a0a0ff, 0x8080ffff, 0x808080ff };
        private final List<FileHandle> files;
        private final int maps;
        private boolean prepared;
        private int width = 2, height = 2;

        SculptArrayData(List<FileHandle> files, int maps) {
            this.files = List.copyOf(files);
            this.maps = maps;
            // TextureArray allocates storage before calling prepare(), so its dimensions must already be known.
            for (var file : files) {
                if (!file.exists()) { continue; }
                var pixels = new Pixmap(file);
                try { width = pixels.getWidth(); height = pixels.getHeight(); }
                finally { pixels.dispose(); }
                break;
            }
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
            for (int layer = 0; layer < files.size(); layer++) {
                var pixels = new Pixmap(width, height, Pixmap.Format.RGBA8888);
                try {
                    pixels.setBlending(Pixmap.Blending.None);
                    if (files.get(layer).exists()) {
                        var source = new Pixmap(files.get(layer));
                        try { pixels.drawPixmap(source, 0, 0, source.getWidth(), source.getHeight(), 0, 0, width, height); }
                        finally { source.dispose(); }
                    } else {
                        pixels.setColor(FALLBACKS[layer % maps]);
                        pixels.fill();
                    }
                    Gdx.gl30.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, width, height, 1,
                          GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels.getPixels());
                } finally { pixels.dispose(); }
            }
            Gdx.gl.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
            prepared = false;
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

    /** Custom kits override the exact tileset path for buildings, fuel tanks and industrial structures. */
    GpuBuilding.Assembly building(String asset, int levels, long seed) {
        if (!asset.startsWith("buildings/")) { return null; }
        if (!buildings.containsKey(asset)) {
            File customRoot = new File(Configuration.dataDir(), "models/buildings");
            FileHandle file = new FileHandle(BoardArtwork.customBuildingFile(asset));
            buildings.put(asset, file.file().isFile() ? new GpuBuilding(file, customRoot.toPath(), this::createModel) : null);
        }
        GpuBuilding building = buildings.get(asset);
        return building == null ? null : building.assemble(levels, seed);
    }

    void retainBuildings(java.util.Set<GpuBuilding.Assembly> live) {
        buildings.values().stream().filter(java.util.Objects::nonNull).forEach(building -> building.retain(live));
    }

    private Model createModel(ModelData data) {
        return ModelTextures.create(data, materials, filename -> texture(new FileHandle(filename)));
    }

    Model lodModel(String name, int level) {
        return modelLods.computeIfAbsent(name, shape -> {
            FileHandle file = new FileHandle(new File(root, shape + ".glb"));
            if (!file.exists()) {
                throw new IllegalArgumentException("Missing board model " + file.path());
            }
            var data = RigidGlb.loadLods(file, root.toPath());
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
        return texture(materialFile(name));
    }

    Texture scatter() {
        return materials.computeIfAbsent("scatter-atlas", key -> GpuScatter.atlas(root));
    }

    Cliff road(String name) {
        return relief("roads", name, "terrain/" + (name.equals("asphalt") ? "concrete" : name.equals("gravel") ? "rock" : "dirt"));
    }

    Cliff ice() {
        Cliff maps = relief("ice", "sheet", "terrain/snow");
        if (maps.normal() != null) { return maps; }
        flatMaps();
        return new Cliff(maps.color(), flatNormal, flatColor);
    }

    private Cliff relief(String folder, String family, String fallback) {
        return cliffs.computeIfAbsent(folder + "/" + family, key -> {
            FileHandle color = materialFile(key);
            FileHandle normal = materialFile(key + "-normal");
            FileHandle surface = materialFile(key + "-surface");
            // Older/custom data sets retain their original material until a complete set is supplied.
            if (!color.exists() || !normal.exists() || !surface.exists()) {
                return new Cliff(material(fallback), null, null);
            }
            return new Cliff(texture(color), texture(normal), texture(surface));
        });
    }

    /**
     * One sculpt material. A data pack without the set still renders: a neutral albedo and a flat normal stand in,
     * so geometry, light and the per-level grade remain.
     */
    Sculpt sculpt(String name) {
        return sculpts.computeIfAbsent(name, key -> {
            JsonValue entry = sculptEntry(key);
            if (entry == null) {
                flatMaps();
                return new Sculpt(flatColor, flatNormal, 4);
            }
            return new Sculpt(texture(materialFile("sculpt/" + key)), texture(materialFile("sculpt/" + key + "-normal")),
                  entry.getFloat("tile"));
        });
    }

    /** Array-backed materials need the repeat scale without decoding and uploading a second set of 2D textures. */
    float sculptTile(String name) {
        JsonValue entry = sculptEntry(name);
        return entry == null ? 4 : entry.getFloat("tile");
    }

    /** Physical range used to bake the normals. Older/custom sets without height metadata keep normal mapping. */
    float sculptRelief(String name) {
        JsonValue entry = sculptEntry(name);
        return entry == null ? 0 : Math.max(0, entry.getFloat("relief_metres", 0));
    }

    private JsonValue sculptEntry(String name) {
        if (!sculptEntries.containsKey(name)) {
            FileHandle manifest = new FileHandle(new File(root, "textures/sculpt/manifest.json"));
            if (sculptManifest == null && manifest.exists()) { sculptManifest = new JsonReader().parse(manifest); }
            JsonValue entry = sculptManifest == null ? null : sculptManifest.get("materials").get(name);
            if (!materialFile("sculpt/" + name).exists() || !materialFile("sculpt/" + name + "-normal").exists()) {
                entry = null;
            }
            // Like the textures, metadata belongs to this asset owner; asset reload creates a fresh owner.
            sculptEntries.put(name, entry);
        }
        return sculptEntries.get(name);
    }

    private static Texture solid(int rgba) {
        Pixmap pixels = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        try {
            pixels.setColor(rgba);
            pixels.fill();
            Texture texture = new Texture(pixels);
            texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            return texture;
        } finally {
            pixels.dispose();
        }
    }

    private void flatMaps() {
        if (flatColor == null) {
            flatColor = solid(0xa0a0a0ff);
            flatNormal = solid(0x8080ffff);
        }
    }

    private FileHandle materialFile(String name) {
        return new FileHandle(new File(root, "textures/" + name + ".png"));
    }

    /** Original rim lightness mask for drops up to two levels, without normalization or generated normals. */
    BoardScene.Pixels inclineMask() {
        if (incline == null) {
            incline = loadRimMask("terrain/incline_dark");
        }
        return incline;
    }

    /** The coarser lightness mask of a drop above two levels, the board's own high-incline split. */
    BoardScene.Pixels highInclineMask() {
        if (highIncline == null) {
            highIncline = loadRimMask("terrain/high_incline_dark");
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

    private Texture texture(FileHandle file) {
        return materials.computeIfAbsent(file.file().toPath().toAbsolutePath().normalize().toString(), key -> {
            Texture texture = new Texture(file, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            boolean repeating = file.file().toPath().toAbsolutePath().normalize()
                  .startsWith(new File(root, "textures").toPath().toAbsolutePath().normalize());
            Texture.TextureWrap wrap = repeating ? Texture.TextureWrap.Repeat : Texture.TextureWrap.ClampToEdge;
            texture.setWrap(wrap, wrap);
            if (repeating && (file.parent().name().equals("cliffs") || file.parent().name().equals("ground")
                  || file.parent().name().equals("sculpt") || file.parent().name().equals("roads")
                  || file.parent().name().equals("ice"))) {
                // Cliff relief needs its full resolution; mipmaps and supported anisotropy handle distance.
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

    private Animation<Texture> loadLiquid(BoardLiquid.Textures source) {
        Animation<BoardScene.Pixels> decoded = readWater(new File(root, "tileset/" + source.base()));
        Animation<BoardScene.Pixels> foam = source.foam().isEmpty() ? null
              : readAnimation(new File(root, "tileset/" + source.foam()), false);
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
                Pixmap pixmap = new Pixmap(image.width(), image.height(), Pixmap.Format.RGBA8888);
                try {
                    pixmap.setBlending(Pixmap.Blending.None);
                    for (int y = 0; y < image.height(); y++) {
                        for (int x = 0; x < image.width(); x++) {
                            int pixel = y * image.width() + x;
                            pixmap.drawPixel(x, y, overlay == null ? image.rgba(pixel)
                                  : blend(image.rgba(pixel), overlay.rgba(pixel)));
                        }
                    }
                    Texture texture = new Texture(pixmap, true);
                    texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                    texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
                    frames.add(texture);
                } finally {
                    pixmap.dispose();
                }
            }
            return new Animation<>(List.copyOf(frames), decoded.ends(), decoded.duration());
        } catch (RuntimeException error) {
            frames.forEach(Texture::dispose);
            throw error;
        }
    }

    private static int blend(int base, int overlay) {
        float alpha = (overlay & 255) / 255f;
        int result = base & 255;
        for (int shift = 24; shift >= 8; shift -= 8) {
            int a = (base >>> shift) & 255, b = (overlay >>> shift) & 255;
            result |= Math.round(a + (b - a) * alpha) << shift;
        }
        return result;
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
        magmas.values().stream().filter(java.util.Objects::nonNull).forEach(TextureArray::dispose);
        magmas.clear();
        sculpts.clear();
        sculptEntries.clear();
        if (sculptArray != null) { sculptArray.dispose(); sculptArray = null; }
        if (roadArray != null) { roadArray.dispose(); roadArray = null; }
        sculptManifest = null;
        if (flatColor != null) {
            flatColor.dispose();
            flatNormal.dispose();
            flatColor = null;
            flatNormal = null;
        }
        liquids.clear();
        incline = null;
        highIncline = null;
    }
}
