/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.Pool;
import megamek.client.ui.clientGUI.boardview.BoardRangeBorder;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.util.UIUtil;
import megamek.common.board.Coords;

/** GL-owned geometry and cached text. Camera movement never rebuilds the surface meshes or label artwork. */
final class GpuTactical implements Disposable {
    /** White dash travel in unscaled board pixels per second. 0f is static; negative values reverse direction. */
    static final float OUTLINE_SCROLL_SPEED = 4f;
    /** top-view degrees for switch to tactical view on/off */
    static final float FLAT_TILT_DEGREES = 15;
    /** A small depth bias keeps range curtains/tints stable where they share a vertical terrain face. */
    static final float COPLANAR_DEPTH_FAR = 1f - .000001f;

    private record TextImage(BoardScene.Pixels pixels, float x, float y) { }
    private static final int PAGE_TRIANGLES = 10000;
    private record FillKey(BoardTactical.Fill fill, int layer) { }
    private record FillGeometry(Map<Coords, BoardTacticalGeometry.Surface> surfaces, float[] vertices, int elevation) { }
    private record WallGeometry(Map<Coords, BoardTacticalGeometry.Surface> surfaces, int[] levels,
          float[] body, float[] uprightOutline, float[] flatOutline) { }
    /** Presentation 0 is always drawn; 1 and 2 are the upright and flat wall alternatives. */
    private record Group(int presentation, BasicStroke stroke) { }
    private record Span(float[] vertices, int start, int count) { }
    private final ModelBatch batch = new ModelBatch();
    private final GpuHexMasks hexMasks = new GpuHexMasks();
    private final GpuHexMasks deploymentTint = new GpuHexMasks();
    private final SpriteBatch textBatch = new SpriteBatch();
    private final GpuTextures<List<BoardTactical.Text>> textures = new GpuTextures<>(true);
    private final Map<List<BoardTactical.Text>, TextImage> images = new HashMap<>();
    private final Map<BoardTactical.Point, List<BoardTactical.Text>> labels = new LinkedHashMap<>();
    private final Material material = new Material(ColorAttribute.createDiffuse(Color.WHITE),
          new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA),
          new DepthTestAttribute(GL20.GL_LEQUAL, false), IntAttribute.createCullFace(GL20.GL_NONE));
    private final Material wallMaterial = material.copy();
    private BoardScene previous;
    private Map<FillKey, FillGeometry> fills = Map.of();
    private Map<BoardTactical.Wall, WallGeometry> walls = Map.of();
    private Map<Group, List<Page>> pages = Map.of();
    private Map<BasicStroke, Material> outlines = Map.of();
    private int tuning = -1;
    private long builds;
    private boolean iconsPresent;
    private final float outlineSpeed;
    private double scrollDistance;
    private final Function<Coords, BoardTacticalGeometry.Surface> terrain;

    GpuTactical() {
        this(OUTLINE_SCROLL_SPEED);
    }

    GpuTactical(float outlineSpeed) {
        this(outlineSpeed, null);
    }

    GpuTactical(Function<Coords, BoardTacticalGeometry.Surface> terrain) {
        this(OUTLINE_SCROLL_SPEED, terrain);
    }

    private GpuTactical(float outlineSpeed, Function<Coords, BoardTacticalGeometry.Surface> terrain) {
        this.outlineSpeed = outlineSpeed;
        this.terrain = terrain;
        wallMaterial.set(new DepthTestAttribute(GL20.GL_LEQUAL, 0, COPLANAR_DEPTH_FAR, false));
    }

    static boolean flat(Camera camera) {
        return -camera.direction.z >= MathUtils.cosDeg(FLAT_TILT_DEGREES);
    }

    void update(BoardScene scene) {
        boolean boardChanged = previous == null || previous.boardId() != scene.boardId()
              || previous.width() != scene.width() || previous.height() != scene.height();
        boolean terrainChanged = boardChanged || !sameTerrain(scene);
        if (tuning != BoardGeometry.revision() || terrainChanged
              || !previous.tactical().fills().equals(scene.tactical().fills())
              || !previous.tactical().walls().equals(scene.tactical().walls())
              || !previous.tactical().flatWalls().equals(scene.tactical().flatWalls())) {
            rebuild(scene, boardChanged || tuning != BoardGeometry.revision() || terrain == null && terrainChanged,
                  terrainChanged);
            tuning = BoardGeometry.revision();
        }
        if (previous == null || !previous.tactical().labels().equals(scene.tactical().labels())) {
            labels.clear();
            scene.tactical().labels().forEach(label ->
                  labels.computeIfAbsent(label.anchor(), key -> new ArrayList<>()).add(label.text()));
            labels.replaceAll((anchor, texts) -> List.copyOf(texts));
            images.keySet().retainAll(labels.values());
            Map<List<BoardTactical.Text>, BoardScene.Pixels> pixels = new HashMap<>();
            labels.values().forEach(texts -> pixels.put(texts, images.computeIfAbsent(texts, GpuTactical::paintText).pixels()));
            textures.update(pixels);
        }
        previous = scene;
    }

    private boolean sameTerrain(BoardScene scene) {
        if (previous.tiles() == scene.tiles()) {
            return true;
        }
        for (int i = 0; i < scene.tiles().size(); i++) {
            BoardScene.Tile a = previous.tiles().get(i), b = scene.tiles().get(i);
            if (!a.sameGeometry(b)) {
                return false;
            }
        }
        return true;
    }

    private void rebuild(BoardScene scene, boolean reset, boolean terrainChanged) {
        Function<Coords, BoardTacticalGeometry.Surface> surfaces = terrain == null
              ? BoardTacticalGeometry.surfaces(scene) : terrain;
        var clipper = new BoardTacticalGeometry.Clipper();
        Map<FillKey, FillGeometry> nextFills = new HashMap<>();
        Map<BoardTactical.Wall, WallGeometry> nextWalls = new HashMap<>();
        Map<Group, List<float[]>> groups = new LinkedHashMap<>();
        var deployment = BoardDeploymentGeometry.zoneFills(scene);
        var perimeter = BoardDeploymentGeometry.perimeter(scene, deployment);
        List<BoardTactical.Wall> commands = new ArrayList<>(scene.tactical().walls());
        commands.addAll(BoardDeploymentGeometry.walls(scene, perimeter));
        commands = BoardRangeBorder.join(commands);
        deploymentTint.updateDeployment(scene, deployment.values(), surfaces);
        boolean masked = hexMasks.update(scene, surfaces, !iconsPresent);
        addFills(scene, masked ? List.of() : scene.tactical().fills(), new Group(0, null), groups, nextFills,
              surfaces, clipper, reset, terrainChanged);
        groups.put(new Group(1, null), new ArrayList<>());
        for (BoardTactical.Wall wall : commands) {
            WallGeometry geometry = reset ? null : walls.get(wall);
            if (geometry == null || terrainChanged && (!current(geometry.surfaces(), surfaces)
                  || !Arrays.equals(geometry.levels(), wallLevels(scene, wall)))) {
                geometry = wallGeometry(scene, wall, surfaces, clipper, geometry);
            }
            nextWalls.put(wall, geometry);
            add(groups, new Group(1, null), geometry.body());
            if (wall.outline() != null) {
                add(groups, new Group(1, wall.outline().stroke()), geometry.uprightOutline());
            }
        }
        addFills(scene, scene.tactical().flatWalls(), new Group(2, null), groups, nextFills, surfaces, clipper, reset, terrainChanged);
        addFills(scene, perimeter, new Group(2, null), groups, nextFills, surfaces, clipper, reset, terrainChanged);
        for (BoardTactical.Wall wall : commands) {
            if (wall.outline() != null) {
                add(groups, new Group(2, wall.outline().stroke()), nextWalls.get(wall).flatOutline());
            }
        }
        replacePages(groups);
        fills = nextFills;
        walls = nextWalls;
        builds++;
    }

    private void addFills(BoardScene scene, List<BoardTactical.Fill> commands, Group group,
          Map<Group, List<float[]>> groups, Map<FillKey, FillGeometry> retained,
          Function<Coords, BoardTacticalGeometry.Surface> surfaces, BoardTacticalGeometry.Clipper clipper,
          boolean reset, boolean terrainChanged) {
        groups.computeIfAbsent(group, ignored -> new ArrayList<>());
        for (int layer = 0; layer < commands.size(); layer++) {
            BoardTactical.Fill command = commands.get(layer);
            if (BoardDeploymentGeometry.isZone(command)) { continue; }
            FillKey key = new FillKey(command, command.border() != null && command.border().floating()
                  ? 0 : Math.min(layer, 10000));
            FillGeometry geometry = retained.get(key);
            if (geometry == null) { geometry = reset ? null : fills.get(key); }
            int elevation = geometry == null || terrainChanged
                  ? floatingElevation(scene, key.fill()) : geometry.elevation();
            if (geometry == null || terrainChanged
                  && (geometry.elevation() != elevation || !current(geometry.surfaces(), surfaces))) {
                Map<Coords, BoardTacticalGeometry.Surface> used = new HashMap<>();
                FloatArray vertices = new FloatArray();
                BoardTacticalGeometry.drape(scene, key.fill(), key.layer(), packed(vertices, null),
                      tracking(surfaces, used), clipper);
                geometry = new FillGeometry(used.isEmpty() ? Map.of() : used,
                      reuse(vertices, geometry == null ? null : geometry.vertices()), elevation);
            }
            retained.put(key, geometry);
            add(groups, group, geometry.vertices());
        }
    }

    private static int floatingElevation(BoardScene scene, BoardTactical.Fill fill) {
        if (fill.border() == null || !fill.border().floating()) { return Integer.MIN_VALUE; }
        Coords coords = BoardTacticalGeometry.borderCoords(scene, fill.border());
        return coords == null ? Integer.MIN_VALUE : scene.tile(coords).elevation();
    }

    private static WallGeometry wallGeometry(BoardScene scene, BoardTactical.Wall wall,
          Function<Coords, BoardTacticalGeometry.Surface> surfaces, BoardTacticalGeometry.Clipper clipper,
          WallGeometry previous) {
        Map<Coords, BoardTacticalGeometry.Surface> used = new HashMap<>();
        FloatArray body = new FloatArray(), upright = new FloatArray(), flat = new FloatArray();
        var tracked = tracking(surfaces, used);
        var uprightTriangles = packed(upright, wall);
        var flatTriangles = packed(flat, wall);
        BoardTacticalGeometry.wall(scene, wall, false, packed(body, null),
              (ignored, triangle) -> uprightTriangles.accept(triangle), tracked, clipper);
        BoardTacticalGeometry.wall(scene, wall, true, ignored -> { },
              (ignored, triangle) -> flatTriangles.accept(triangle), tracked, clipper);
        return new WallGeometry(used, wallLevels(scene, wall), reuse(body, previous == null ? null : previous.body()),
              reuse(upright, previous == null ? null : previous.uprightOutline()),
              reuse(flat, previous == null ? null : previous.flatOutline()));
    }

    private static int[] wallLevels(BoardScene scene, BoardTactical.Wall wall) {
        int[] levels = new int[7];
        for (int i = 0; i < levels.length; i++) {
            var tile = scene.tile(i == 0 ? wall.coords() : wall.coords().translated(i - 1));
            levels[i] = tile == null ? Integer.MIN_VALUE : tile.elevation();
        }
        return levels;
    }

    private static Function<Coords, BoardTacticalGeometry.Surface> tracking(
          Function<Coords, BoardTacticalGeometry.Surface> surfaces, Map<Coords, BoardTacticalGeometry.Surface> used) {
        return coords -> used.computeIfAbsent(coords, surfaces);
    }

    private static boolean current(Map<Coords, BoardTacticalGeometry.Surface> used,
          Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        for (var entry : used.entrySet()) {
            if (surfaces.apply(entry.getKey()) != entry.getValue()) { return false; }
        }
        return true;
    }

    private static float[] reuse(FloatArray vertices, float[] previous) {
        float[] result = vertices.toArray();
        return Arrays.equals(result, previous) ? previous : result;
    }

    private static void add(Map<Group, List<float[]>> groups, Group group, float[] vertices) {
        if (vertices.length > 0) { groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(vertices); }
    }

    /** Keep the old draw boundaries: changing transparent part centers would change painter ordering. */
    private void replacePages(Map<Group, List<float[]>> groups) {
        Map<Group, List<Page>> next = new LinkedHashMap<>();
        Map<BasicStroke, Material> inks = new HashMap<>();
        List<Page> created = new ArrayList<>();
        List<Texture> createdTextures = new ArrayList<>();
        try {
            for (var entry : groups.entrySet()) {
                Group group = entry.getKey();
                if (entry.getValue().isEmpty()) { continue; }
                Material ink = group.presentation() == 1 ? wallMaterial : material;
                if (group.stroke() != null) {
                    ink = inks.get(group.stroke());
                    if (ink == null) {
                        ink = outlines.get(group.stroke());
                        if (ink == null) {
                            Texture texture = outlineTexture(group.stroke());
                            createdTextures.add(texture);
                            ink = wallMaterial.copy();
                            TextureAttribute diffuse = TextureAttribute.createDiffuse(texture);
                            diffuse.scaleU = 1 / dashPeriod(group.stroke());
                            ink.set(diffuse);
                        }
                        inks.put(group.stroke(), ink);
                    }
                }
                List<Page> previousPages = pages.getOrDefault(group, List.of());
                List<Page> replacement = new ArrayList<>();
                int capacity = PAGE_TRIANGLES * 3 * (group.stroke() == null ? 4 : 6), count = 0;
                List<Span> spans = new ArrayList<>();
                for (float[] vertices : entry.getValue()) {
                    for (int start = 0; start < vertices.length;) {
                        int length = Math.min(capacity - count, vertices.length - start);
                        spans.add(new Span(vertices, start, length));
                        count += length;
                        start += length;
                        if (count == capacity) {
                            appendPage(replacement, previousPages, spans, group, ink, created);
                            spans = new ArrayList<>();
                            count = 0;
                        }
                    }
                }
                if (!spans.isEmpty()) { appendPage(replacement, previousPages, spans, group, ink, created); }
                next.put(group, replacement);
            }
        } catch (RuntimeException | Error failure) {
            created.forEach(Page::dispose);
            createdTextures.forEach(Texture::dispose);
            throw failure;
        }
        var retained = new HashSet<Page>();
        next.values().forEach(retained::addAll);
        pages.values().forEach(group -> group.stream().filter(page -> !retained.contains(page)).forEach(Page::dispose));
        outlines.forEach((stroke, ink) -> {
            if (!inks.containsKey(stroke)) {
                ink.get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture.dispose();
            }
        });
        pages = next;
        outlines = inks;
    }

    private static void appendPage(List<Page> destination, List<Page> previous, List<Span> spans,
          Group group, Material material, List<Page> created) {
        int index = destination.size();
        Page page = index < previous.size() ? previous.get(index) : null;
        if (page == null || !page.spans.equals(spans)) {
            page = new Page(spans, group, material);
            created.add(page);
        }
        destination.add(page);
    }

    /** Pages own only their mesh. Command arrays and animated stroke materials are shared by the active geometry. */
    private static final class Page implements RenderableProvider, Disposable {
        final List<Span> spans;
        final Renderable renderable = new Renderable();

        Page(List<Span> spans, Group group, Material material) {
            this.spans = List.copyOf(spans);
            int count = spans.stream().mapToInt(Span::count).sum(), stride = group.stroke() == null ? 4 : 6;
            float[] vertices = new float[count];
            int offset = 0;
            for (Span span : spans) {
                System.arraycopy(span.vertices(), span.start(), vertices, offset, span.count());
                offset += span.count();
            }
            short[] indices = new short[count / stride];
            for (int i = 0; i < indices.length; i++) { indices[i] = (short) i; }
            Mesh mesh = group.stroke() == null
                  ? new Mesh(true, indices.length, indices.length, VertexAttribute.Position(), VertexAttribute.ColorPacked())
                  : new Mesh(true, indices.length, indices.length, VertexAttribute.Position(), VertexAttribute.ColorPacked(),
                        VertexAttribute.TexCoords(0));
            try {
                mesh.setVertices(vertices);
                mesh.setIndices(indices);
                renderable.meshPart.set("tactical", mesh, 0, indices.length, GL20.GL_TRIANGLES);
                renderable.meshPart.update();
                renderable.material = material;
            } catch (RuntimeException | Error failure) {
                mesh.dispose();
                throw failure;
            }
        }

        @Override
        public void getRenderables(Array<Renderable> renderables, Pool<Renderable> pool) {
            renderable.shader = null;
            renderable.environment = null;
            renderables.add(renderable);
        }

        @Override
        public void dispose() { renderable.meshPart.mesh.dispose(); }
    }

    private static Consumer<BoardTacticalGeometry.Triangle> packed(FloatArray vertices, BoardTactical.Wall wall) {
        Color color = new Color();
        return triangle -> {
            Color.argb8888ToColor(color, triangle.argb());
            float bits = color.toFloatBits();
            pack(vertices, triangle.a(), bits, wall);
            pack(vertices, triangle.b(), bits, wall);
            pack(vertices, triangle.c(), bits, wall);
        };
    }

    private static void pack(FloatArray vertices, Vector3 point, float color, BoardTactical.Wall wall) {
        vertices.add(point.x, point.y, point.z, color);
        if (wall != null) {
            float dx = wall.b().x() - wall.a().x(), dy = wall.b().y() - wall.a().y();
            float length = (float) Math.hypot(dx, dy);
            float along = ((point.x / BoardGeometry.HEX_SCALE - wall.a().x()) * dx
                  + (-point.y / BoardGeometry.HEX_SCALE - wall.a().y()) * dy) / length;
            vertices.add(wall.outline().stroke().getDashPhase() + wall.outlineDistance() + along, 0.5f);
        }
    }

    private static float dashPeriod(BasicStroke stroke) {
        float[] dashes = stroke.getDashArray();
        if (dashes == null) {
            return 1;
        }
        float length = 0;
        for (float dash : dashes) {
            length += dash;
        }
        return length * (dashes.length % 2 == 0 ? 1 : 2);
    }

    private static Texture outlineTexture(BasicStroke stroke) {
        float period = dashPeriod(stroke);
        float[] dashes = stroke.getDashArray();
        Pixmap pixels = new Pixmap(Math.max(1, (int) Math.ceil(period * 16)), 1, Pixmap.Format.RGBA8888);
        try {
            pixels.setBlending(Pixmap.Blending.None);
            for (int x = 0; x < pixels.getWidth(); x++) {
                float remaining = (x + 0.5f) * period / pixels.getWidth();
                int dash = 0;
                while (dashes != null && remaining >= dashes[dash % dashes.length]) {
                    remaining -= dashes[dash++ % dashes.length];
                }
                pixels.drawPixel(x, 0, dash % 2 == 0 ? 0xFFFFFFFF : 0xFFFFFF00);
            }
            Texture texture = new Texture(pixels, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.ClampToEdge);
            return texture;
        } finally {
            pixels.dispose();
        }
    }

    void render(Camera camera, float deltaSeconds) {
        render(camera, deltaSeconds, List.of());
    }

    void render(Camera camera, float deltaSeconds, Collection<ModelInstance> icons) {
        if (iconsPresent != !icons.isEmpty()) {
            iconsPresent = !icons.isEmpty();
            // Different transparent-page centers can reorder a depth-independent icon and a regular border.
            // Keep their established generic ordering whenever icons participate in this pass.
            if (previous != null) { rebuild(previous, false, false); }
        }
        scrollDistance += deltaSeconds * outlineSpeed;
        for (Material ink : outlines.values()) {
            TextureAttribute texture = ink.get(TextureAttribute.class, TextureAttribute.Diffuse);
            double offset = -scrollDistance * texture.scaleU;
            texture.offsetU = (float) (offset - Math.floor(offset));
        }
        if (pages.isEmpty() && icons.isEmpty() && !hexMasks.active() && !deploymentTint.active()) { return; }
        boolean flat = flat(camera);
        batch.begin(camera);
        deploymentTint.submit(batch, camera);
        hexMasks.submit(batch, camera);
        for (var entry : pages.entrySet()) {
            int presentation = entry.getKey().presentation();
            if (presentation == 0 || presentation == (flat ? 2 : 1)) {
                for (Page page : entry.getValue()) {
                    var bounds = page.renderable.meshPart;
                    if (camera.frustum.boundsInFrustum(bounds.center.x, bounds.center.y, bounds.center.z,
                          bounds.halfExtents.x, bounds.halfExtents.y, bounds.halfExtents.z)) { batch.render(page); }
                }
            }
        }
        for (var icon : icons) {
            if (camera.frustum.boundsInFrustum(UnitBounds.world(icon))) { batch.render(icon); }
        }
        batch.end();
    }

    void renderLabels(Camera camera) {
        if (labels.isEmpty()) {
            return;
        }
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        textBatch.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, camera.viewportWidth, camera.viewportHeight));
        textBatch.begin();
        for (var entry : labels.entrySet()) {
            var anchor = entry.getKey();
            int column = Math.round((anchor.x() - BoardGeometry.TILE_WIDTH / 2) / (BoardGeometry.TILE_WIDTH * 0.75f));
            float halfHeight = BoardGeometry.TILE_HEIGHT / 2;
            Coords coords = new Coords(column,
                  Math.round((anchor.y() - halfHeight - (column & 1) * halfHeight) / BoardGeometry.TILE_HEIGHT));
            var tile = previous.tile(coords);
            if (tile == null) {
                continue;
            }
            Vector3 position = new Vector3(anchor.x() * BoardGeometry.HEX_SCALE, -anchor.y() * BoardGeometry.HEX_SCALE,
                  BoardGeometry.surfaceZ(tile) + 2 * BoardGeometry.HEX_SCALE);
            if (!camera.frustum.sphereInFrustum(position, BoardGeometry.WIDTH)) {
                continue;
            }
            Vector3 right = new Vector3(camera.direction).crs(camera.up).nor().scl(BoardGeometry.HEX_SCALE);
            Vector3 screen = camera.project(new Vector3(position), 0, 0, camera.viewportWidth, camera.viewportHeight);
            float scale = camera.project(new Vector3(position).add(right), 0, 0,
                  camera.viewportWidth, camera.viewportHeight).dst(screen);
            TextImage image = images.get(entry.getValue());
            var region = textures.region(entry.getValue());
            textBatch.draw(region, screen.x + image.x() * scale,
                  screen.y - (image.y() + image.pixels().height() / 2f) * scale,
                  image.pixels().width() * scale / 2, image.pixels().height() * scale / 2);
        }
        textBatch.end();
    }

    private static TextImage paintText(List<BoardTactical.Text> texts) {
        var metrics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        List<Shape> glyphs = new ArrayList<>();
        Rectangle2D bounds = null;
        try {
            for (var text : texts) {
                Shape shape = text.font().createGlyphVector(metrics.getFontRenderContext(), text.value())
                      .getOutline(text.x(), text.y());
                glyphs.add(shape);
                bounds = bounds == null ? shape.getBounds2D() : bounds.createUnion(shape.getBounds2D());
            }
        } finally {
            metrics.dispose();
        }
        int x = (int) Math.floor(bounds.getX()) - 2, y = (int) Math.floor(bounds.getY()) - 2;
        BufferedImage image = new BufferedImage(Math.max(1, (int) Math.ceil(bounds.getMaxX() - x + 2) * 2),
              Math.max(1, (int) Math.ceil(bounds.getMaxY() - y + 2) * 2), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            UIUtil.setHighQualityRendering(graphics);
            graphics.scale(2, 2);
            graphics.translate(-x, -y);
            graphics.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < texts.size(); i++) {
                graphics.setColor(new java.awt.Color(0, 0, 0, texts.get(i).argb() >>> 24));
                graphics.draw(glyphs.get(i));
                graphics.setColor(new java.awt.Color(texts.get(i).argb(), true));
                graphics.fill(glyphs.get(i));
            }
        } finally {
            graphics.dispose();
        }
        return new TextImage(new BoardScene.Pixels(image), x, y);
    }

    long builds() {
        return builds;
    }

    boolean deploymentActive() {
        return deploymentTint.active();
    }

    @Override
    public void dispose() {
        hexMasks.dispose();
        deploymentTint.dispose();
        pages.values().forEach(group -> group.forEach(Page::dispose));
        pages = Map.of();
        outlines.values().forEach(ink ->
              ink.get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture.dispose());
        outlines = Map.of();
        fills = Map.of();
        walls = Map.of();
        batch.dispose();
        textBatch.dispose();
        textures.dispose();
        images.clear();
        labels.clear();
    }
}
