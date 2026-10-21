/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.util.UIUtil;
import megamek.common.board.Coords;

/** Editor-only stacks of terrain badges. Both cameras share the captured state, anchor and cached artwork. */
final class GpuEditorBadges implements Disposable {
    private static final int BASE_WIDTH = 64, ROW_HEIGHT = 32, STEM_HEIGHT = 12, RESOLUTION = 3;
    private static final float BORDER_WIDTH = 1.4f, STACK_STEP = 26 + BORDER_WIDTH;
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 14);
    private final GpuTextures<List<BoardEditorTerrain.Badge>> textures = new GpuTextures<>();
    private final Map<List<BoardEditorTerrain.Badge>, BoardScene.Pixels> images = new HashMap<>();
    private Map<Coords, List<BoardEditorTerrain.Badge>> previous;

    /** Borrows the view's active annotation batch and roof bounds; only the artwork atlas belongs to this renderer. */
    void render(SpriteBatch batch, Camera camera, float layoutScale, BoardScene scene,
          Map<Coords, List<BoardEditorTerrain.Badge>> badges, Function<Coords, BoundingBox> roofs, Coords hovered) {
        if (badges != previous) {
            var used = new HashSet<>(badges.values());
            images.keySet().retainAll(used);
            used.forEach(stack -> images.computeIfAbsent(stack, GpuEditorBadges::artwork));
            textures.update(images);
            previous = badges;
        }
        Color previousColor = new Color(batch.getColor());
        batch.setColor(Color.WHITE);
        try {
            for (var entry : badges.entrySet()) {
                Coords coords = entry.getKey();
                BoardScene.Tile tile = scene.tile(coords);
                if (tile == null || entry.getValue().isEmpty()) { continue; }
                Vector3 position = anchor(tile, tile.building() ? roofs.apply(coords) : null);
                if (!camera.frustum.sphereInFrustum(position, BoardGeometry.width())) { continue; }
                Vector3 screen = camera.project(new Vector3(position), 0, 0, camera.viewportWidth, camera.viewportHeight);
                if (screen.z < 0 || screen.z > 1) { continue; }
                Vector3 right = new Vector3(camera.direction).crs(camera.up).nor().scl(BoardGeometry.width());
                float hexPixels = camera.project(right.add(position), 0, 0,
                      camera.viewportWidth, camera.viewportHeight).dst(screen);
                float size = Math.min(BASE_WIDTH * layoutScale, hexPixels * .38f);
                // The whole stack scales together. Overview zoom retains the hovered hex without covering the map.
                if (coords.equals(hovered)) { size = Math.max(size, 48 * layoutScale); }
                else if (size < 26 * layoutScale) { continue; }
                float scale = size / (BASE_WIDTH * RESOLUTION);
                var region = textures.region(entry.getValue());
                float width = region.getRegionWidth() * scale, height = region.getRegionHeight() * scale;
                batch.draw(region, screen.x - width / 2, screen.y, width, height);
            }
        } finally { batch.setColor(previousColor); }
    }

    /** Keep a connected building's own hex XY; ground badges are not lifted by unrelated trees or mineral models. */
    static Vector3 anchor(BoardScene.Tile tile, BoundingBox roof) {
        Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
        center.z = BoardGeometry.surfaceZ(tile);
        if (tile.building()) {
            center.z = Math.max(center.z, (tile.elevation() + Math.max(0, tile.cover().height())) * BoardGeometry.level());
            if (roof != null && roof.isValid()) { center.z = Math.max(center.z, roof.max.z); }
        }
        return center;
    }

    /** One shared image per distinct stack, with separate rows and a single stem identifying the owning hex. */
    private static BoardScene.Pixels artwork(List<BoardEditorTerrain.Badge> stack) {
        var metrics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        int width = BASE_WIDTH;
        try {
            metrics.setFont(FONT);
            for (var badge : stack) { width = Math.max(width, 40 + metrics.getFontMetrics().stringWidth(badge.text())); }
        } finally { metrics.dispose(); }
        // Bodies run from y=5 to y=31; include the stroke width so outer edges touch without overlapping.
        int height = (int) Math.ceil(ROW_HEIGHT + STACK_STEP * (stack.size() - 1)) + STEM_HEIGHT;
        var image = new BufferedImage(width * RESOLUTION, height * RESOLUTION, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            UIUtil.setHighQualityRendering(g);
            g.scale(RESOLUTION, RESOLUTION);
            g.setFont(FONT);
            for (int row = 0; row < stack.size(); row++) {
                var badge = stack.get(row);
                g.translate(0, row * STACK_STEP);
                var shape = new Path2D.Float();
                shape.moveTo(3, 13);
                shape.lineTo(11, 5);
                shape.lineTo(width - 11, 5);
                shape.lineTo(width - 3, 13);
                shape.lineTo(width - 3, 23);
                shape.lineTo(width - 11, 31);
                shape.lineTo(11, 31);
                shape.lineTo(3, 23);
                shape.closePath();
                g.setColor(new java.awt.Color(0xF0182229, true));
                g.fill(shape);
                g.setColor(ink(badge.kind()));
                g.setStroke(badge.kind() == BoardEditorTerrain.Kind.UNKNOWN_BASEMENT
                      ? new BasicStroke(BORDER_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                            10, new float[] {4, 3}, 0)
                      : new BasicStroke(BORDER_WIDTH));
                g.draw(shape);
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                icon(g, badge.kind());
                g.drawString(badge.text(), 30, 23);
                g.translate(0, -row * STACK_STEP);
            }
            g.setColor(ink(stack.getLast().kind()));
            g.drawLine(width / 2, height - STEM_HEIGHT, width / 2, height - 4);
            g.fillOval(width / 2 - 2, height - 5, 4, 4);
        } finally { g.dispose(); }
        return new BoardScene.Pixels(image);
    }

    private static java.awt.Color ink(BoardEditorTerrain.Kind kind) {
        return new java.awt.Color(switch (kind) {
            case UNKNOWN_BASEMENT -> 0xBFC8CD;
            case BLACK_ICE -> 0xBBEEFF;
            case COLLAPSED_BASEMENT -> 0xF38B80;
            case METAL_DEPOSITS -> 0xD5BDE6;
            default -> 0xF2C66D;
        });
    }

    private static void icon(Graphics2D g, BoardEditorTerrain.Kind kind) {
        switch (kind) {
            case BASEMENT, UNKNOWN_BASEMENT ->
                  g.drawPolyline(new int[] {11, 16, 16, 21, 21, 26}, new int[] {12, 12, 17, 17, 22, 22}, 6);
            case COLLAPSED_BASEMENT -> {
                g.drawPolyline(new int[] {10, 16, 18, 21, 26}, new int[] {14, 14, 18, 14, 14}, 5);
                g.drawLine(18, 19, 18, 26);
                g.drawPolyline(new int[] {14, 18, 22}, new int[] {22, 26, 22}, 3);
            }
            case BLACK_ICE -> {
                for (int i = 0; i < 6; i++) {
                    g.translate(18, 18);
                    g.rotate(i * Math.PI / 3);
                    g.drawLine(0, 0, 0, -8);
                    g.drawPolyline(new int[] {-3, 0, 3}, new int[] {-6, -3, -6}, 3);
                    g.rotate(-i * Math.PI / 3);
                    g.translate(-18, -18);
                }
            }
            case METAL_DEPOSITS -> {
                g.drawPolygon(new int[] {10, 22, 27, 15}, new int[] {15, 11, 15, 19}, 4);
                g.drawPolyline(new int[] {10, 10, 15, 27, 27}, new int[] {15, 22, 26, 22, 15}, 5);
                g.drawLine(15, 19, 15, 26);
            }
        }
    }

    @Override public void dispose() {
        textures.dispose();
        images.clear();
        previous = null;
    }
}
