/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.sprite.TextMarkerSprite;
import megamek.client.ui.gdx.UiTheme;

/**
 * The firing lines, unlit and depth-tested, and the flat range labels, shared by both cameras; the range bands and
 * their borders are GpuBoardOverlay's. Owns its meshes and batch on the GL thread.
 */
final class GpuFireControl implements Disposable {
    /** Positive size multiplier for firing-line thickness and the arrowhead; 1 keeps the current size. */
    static final float TARGET_ARROW_SIZE = 1f;
    /** Keep the entire flat label above the hex surface while it follows the camera. */
    static final float RANGE_LABEL_CLEARANCE_LEVELS = 1f / 3;
    private static final int LABEL_ART_SCALE = 3;
    private static final long ATTRIBUTES = VertexAttributes.Usage.Position | VertexAttributes.Usage.ColorPacked;
    private final ModelBatch batch = new ModelBatch();
    private final SpriteBatch labelBatch = new SpriteBatch();
    private final GpuTextures<String> labelTextures = new GpuTextures<>(true);
    private final Map<String, BoardScene.Pixels> labelImages = new HashMap<>();
    private final Matrix4 labelMatrix = new Matrix4();
    private final Material material = new Material(ColorAttribute.createDiffuse(Color.WHITE),
          new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA),
          new DepthTestAttribute(GL20.GL_LEQUAL, false), IntAttribute.createCullFace(GL20.GL_NONE));
    private List<BoardScene.FiringLine> firingLines = List.of();
    private List<BoardScene.RangeLabel> rangeLabels = List.of();
    private BoardScene scene;
    private ModelInstance instance;
    private int tuning = -1;
    // The inputs of the shown lines, by identity: the captured lines, whether attacks play, the status and orders.
    private List<BoardScene.FiringLine> capturedLines = List.of();
    private boolean hidden;
    private GpuBattleStatus.Snapshot status;
    private GpuFireOrders.Snapshot fire;

    /**
     * Shows the scene's firing lines and range labels. The lines hide while attacks play ({@code hideArrows}). The
     * declaring actor's lines to the targets of its orders are left to the board labels' traces, so that each
     * attacker and target has one trace (rebuild plan H32); every other line takes its attacker's side colour.
     */
    void update(BoardScene scene, boolean hideArrows, GpuBattleStatus.Snapshot status, GpuFireOrders.Snapshot fire) {
        updateLabels(scene);
        List<BoardScene.FiringLine> shownLines = firingLines;
        if (scene.firingLines() != capturedLines || hideArrows != hidden || status != this.status
              || fire != this.fire) {
            capturedLines = scene.firingLines();
            hidden = hideArrows;
            this.status = status;
            this.fire = fire;
            shownLines = hideArrows ? List.of() : lines(capturedLines, status, fire);
        }
        boolean changed = tuning != BoardGeometry.revision() || !firingLines.equals(shownLines)
              || !sameTerrain(scene.tiles());
        this.scene = scene;
        if (!changed) {
            return;
        }
        tuning = BoardGeometry.revision();
        firingLines = shownLines;
        if (instance != null) {
            instance.model.dispose();
            instance = null;
        }
        if (firingLines.isEmpty()) {
            return;
        }
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        int part = 0;
        for (BoardScene.FiringLine line : firingLines) {
            MeshPartBuilder mesh = builder.part("attack-" + part++, GL20.GL_TRIANGLES, ATTRIBUTES, material);
            Color color = color(line.rgb(), 1);
            List<Vector3> path = BoardFiringGeometry.trajectory(scene, line);
            for (int i = 1; i < path.size(); i++) {
                tube(mesh, path.get(i - 1), path.get(i), .725f * BoardGeometry.hexScale() * TARGET_ARROW_SIZE, color);
            }
            Vector3 end = path.getLast();
            Vector3 direction = new Vector3(end).sub(path.get(path.size() - 2)).nor();
            float length = Math.min(12 * BoardGeometry.hexScale(), path.getFirst().dst(end) * 0.2f) * TARGET_ARROW_SIZE;
            cone(mesh, new Vector3(end).mulAdd(direction, -length), end, length * 0.2f, color);
        }
        Model model = builder.end();
        instance = new ModelInstance(model);
    }

    /**
     * The lines to draw: all but the actor's lines to its orders' targets, which the board labels trace (a read-only
     * draft has none), each in its attacker's side colour, mint for the player's and allied units and coral for
     * enemies; an unlisted attacker keeps its line's colour.
     */
    private static List<BoardScene.FiringLine> lines(List<BoardScene.FiringLine> lines,
          GpuBattleStatus.Snapshot status, GpuFireOrders.Snapshot fire) {
        List<BoardScene.FiringLine> shown = new ArrayList<>();
        for (BoardScene.FiringLine line : lines) {
            if (line.attackerId() == fire.actorId()
                  && fire.targets().stream().anyMatch(target -> target.key().equals(line.targetKey()))) {
                continue;
            }
            GpuBattleStatus.UnitStatus attacker = GpuHudState.unit(status, line.attackerId());
            int rgb = attacker == null ? line.rgb()
                  : Color.rgb888(attacker.side() == GpuBattleStatus.Side.ENEMY ? UiTheme.CORAL : UiTheme.MINT);
            shown.add(new BoardScene.FiringLine(line.source(), line.target(), rgb, line.indirect(),
                  line.attackerId(), line.targetKey()));
        }
        return shown;
    }

    private void updateLabels(BoardScene scene) {
        List<BoardScene.RangeLabel> next = BoardTactical.SCROLLING_RANGE_LABELS ? List.of() : scene.rangeLabels();
        if (rangeLabels.equals(next)) {
            return;
        }
        rangeLabels = next;
        List<String> text = next.stream().map(BoardScene.RangeLabel::label).distinct().toList();
        labelImages.keySet().retainAll(text);
        for (String label : text) {
            labelImages.computeIfAbsent(label, GpuFireControl::labelArtwork);
        }
        labelTextures.update(labelImages);
    }

    private static BoardScene.Pixels labelArtwork(String label) {
        BufferedImage image = new BufferedImage((int) BoardGeometry.TILE_WIDTH * LABEL_ART_SCALE,
              (int) BoardGeometry.TILE_HEIGHT * LABEL_ART_SCALE, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            TextMarkerSprite.drawMarker(graphics, label, java.awt.Color.WHITE,
                  image.getWidth(), image.getHeight(), LABEL_ART_SCALE);
        } finally {
            graphics.dispose();
        }
        return new BoardScene.Pixels(image);
    }

    /** No time input: range letters are always flat and upright, including while orbiting or switching cameras. */
    static void labelTransform(Matrix4 out, Camera camera, BoardScene.Tile tile) {
        Vector3 right = new Vector3(camera.direction).crs(camera.up).nor();
        float clearance = BoardGeometry.level() * RANGE_LABEL_CLEARANCE_LEVELS
              + (BoardGeometry.width() * Math.abs(right.z) + BoardGeometry.height() * Math.abs(camera.up.z)) / 2;
        Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation()).add(0, 0, clearance);
        out.set(center, GpuMarkers.orientation(camera, true));
    }

    /** Ground labels and deployment highlights do not invalidate this geometry. */
    private boolean sameTerrain(List<BoardScene.Tile> next) {
        List<BoardScene.Tile> tiles = scene == null ? List.of() : scene.tiles();
        if (tiles == next) {
            return true;
        }
        if (tiles.size() != next.size()) {
            return false;
        }
        for (int i = 0; i < tiles.size(); i++) {
            BoardScene.Tile a = tiles.get(i), b = next.get(i);
            if (!a.coords().equals(b.coords()) || a.elevation() != b.elevation() || !a.features().equals(b.features())) {
                return false;
            }
        }
        return true;
    }

    private static Color color(int rgb, float alpha) {
        return new Color(((rgb >>> 16) & 255) / 255f, ((rgb >>> 8) & 255) / 255f, (rgb & 255) / 255f, alpha);
    }

    private static void quad(MeshPartBuilder mesh, Vector3 a, Vector3 b, Vector3 c, Vector3 d, Color color) {
        mesh.rect(vertex(a, color), vertex(b, color), vertex(c, color), vertex(d, color));
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, Color color) {
        return new MeshPartBuilder.VertexInfo().setPos(point).setCol(color);
    }

    private static Vector3[] ring(Vector3 start, Vector3 end, float radius) {
        Vector3 direction = new Vector3(end).sub(start).nor();
        Vector3 side = new Vector3(direction).crs(Math.abs(direction.z) > 0.95f ? Vector3.Y : Vector3.Z).nor().scl(radius);
        Vector3 up = new Vector3(direction).crs(side).nor().scl(radius);
        return new Vector3[] { side, up, new Vector3(side).scl(-1), new Vector3(up).scl(-1) };
    }

    private static void tube(MeshPartBuilder mesh, Vector3 start, Vector3 end, float radius, Color color) {
        if (start.dst2(end) < 0.0001f) {
            return;
        }
        Vector3[] ring = ring(start, end, radius);
        for (int i = 0; i < ring.length; i++) {
            Vector3 a = ring[i], b = ring[(i + 1) % ring.length];
            quad(mesh, new Vector3(start).add(a), new Vector3(start).add(b),
                  new Vector3(end).add(b), new Vector3(end).add(a), color);
        }
    }

    private static void cone(MeshPartBuilder mesh, Vector3 base, Vector3 tip, float radius, Color color) {
        Vector3[] ring = ring(base, tip, radius);
        for (int i = 0; i < ring.length; i++) {
            mesh.triangle(vertex(new Vector3(base).add(ring[i]), color),
                  vertex(new Vector3(base).add(ring[(i + 1) % ring.length]), color), vertex(tip, color));
        }
    }

    void render(Camera camera) {
        if (instance != null) {
            batch.begin(camera);
            batch.render(instance);
            batch.end();
        }
    }

    /** Draw after ground markings so their text cannot overpaint the camera-facing letters. */
    void renderLabels(Camera camera) {
        if (!rangeLabels.isEmpty()) {
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            labelBatch.setProjectionMatrix(camera.combined);
            labelBatch.begin();
            for (BoardScene.RangeLabel label : rangeLabels) {
                BoardScene.Tile tile = scene.tile(label.coords());
                if (tile == null) {
                    continue;
                }
                labelTransform(labelMatrix, camera, tile);
                labelBatch.setTransformMatrix(labelMatrix);
                labelBatch.setColor(color(label.rgb(), 1));
                labelBatch.draw(labelTextures.region(label.label()), -BoardGeometry.width() / 2,
                      -BoardGeometry.height() / 2, BoardGeometry.width(), BoardGeometry.height());
            }
            labelBatch.end();
        }
    }

    @Override
    public void dispose() {
        if (instance != null) {
            instance.model.dispose();
            instance = null;
        }
        batch.dispose();
        labelBatch.dispose();
        labelTextures.dispose();
        labelImages.clear();
    }
}
