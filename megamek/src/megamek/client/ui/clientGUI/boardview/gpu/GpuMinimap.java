/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Coords;
import megamek.logging.MMLogger;

/**
 * Minimap under the utilities (C.1 G4): the board in the average colours of its tileset art, the presented units, the
 * planned route and the ground the camera shows. The map fills the panel inside its rails, with the close button over
 * its top-right corner (the user's decision of 2026-10-02: no caption, no north mark). A left press or drag on it
 * centres the camera there, a middle drag orbits the camera as the board's does, and neither changes an order.
 */
final class GpuMinimap implements GpuHud.Component {
    private static final MMLogger LOGGER = MMLogger.create(GpuMinimap.class);
    private static final Color BACKGROUND = Color.valueOf("141B1B");
    private static final Color ROUTE = Color.valueOf("D4FFF0");
    private static final Color FRUSTUM = new Color(1, 1, 1, .85f);
    private static final Color DESTROYED = Color.valueOf("555555");
    /** The map's height: the panel keeps the prototype's 210 units (#minimap, y 90-300) with its 2-unit rails. */
    private static final float CANVAS_HEIGHT = 206;
    /** The map's height when the window is 800 or less tall: the prototype's 180-unit panel. */
    private static final float LOW_CANVAS_HEIGHT = 176;
    /** The close button's room over the map's top-right corner. */
    private static final float CLOSE_SIZE = 30;
    private static final float CLOSE_INSET = 2;
    /** The drawn board keeps this margin inside the canvas. */
    private static final float MARGIN = 6;
    /** Hexes fill this share of their size, which leaves the prototype's fine dark seams between them. */
    private static final float HEX_FILL = .9f;
    /** The board image has this many texels per drawn pixel in each direction, so its hex edges come out smooth. */
    private static final int SUPERSAMPLE = 2;
    private static final int MAX_TEXELS = 4096;
    private static final float ROUTE_WIDTH = 2;
    private static final float FRUSTUM_WIDTH = 1.2f;
    /** A unit's square: half its side is 0.55 hex radii, and never under 2.5 units. */
    private static final float UNIT_SIZE = .55f;
    private static final float UNIT_MINIMUM = 2.5f;
    /** A sensor contact's "?": 1.1 hex radii tall, and never under 9 units. */
    private static final float CONTACT_SIZE = 1.1f;
    private static final float CONTACT_MINIMUM = 9;

    /**
     * The art of one hex seen from above: its ground or open liquid, then its decals, then its flat foliage (each may
     * be null), and the tint the board's liquid material gives the hex (white for none).
     */
    private record Art(BoardScene.Pixels ground, BoardScene.Pixels decals, BoardScene.Pixels foliage, Color tint) { }

    /**
     * What the board image shows: each hex's art in the order of the scene's tiles, the board's size in hexes and the
     * image's size in texels.
     */
    private record Baked(List<Art> art, int columns, int rows, int width, int height) { }

    private final UiKit ui;
    private final GpuHudState state;
    private final BoardCamera camera;
    private final Table root;
    private final UiButton close;
    private final Canvas canvas;
    private final TextureRegion pixel;
    private final Label contact;
    private final float contactScale;
    /** Each hex art's average colour, kept for the art on the current board. */
    private final Map<Art, Integer> colours = new HashMap<>();
    /** The first frame of each liquid animation file the board has shown, by its tileset path; empty if unreadable. */
    private final Map<String, Optional<BoardScene.Pixels>> liquids = new HashMap<>();
    private GpuHud.Inputs inputs;

    GpuMinimap(GpuHudKit kit, GpuBoardSource source, GpuHudState state, BoardCamera camera) {
        this.state = state;
        this.camera = camera;
        ui = kit.ui;
        pixel = new TextureRegion(ui.skin.get("white", Texture.class));
        // The panel's frame around the map: its 2-unit rails and side borders.
        root = ui.panel();
        root.setName("minimap");
        close = ui.closeButton(() -> GpuUtilityBar.runMinimap(inputs));
        close.setName("minimap-close");
        canvas = new Canvas();
        Container<UiButton> backdrop = new Container<>(close).fill();
        backdrop.setBackground(ui.skin.getDrawable("minimap-close"));
        // Presses beside the close button reach the map under it: a Table takes presses on its children only.
        Table corner = new Table();
        corner.top().right().add(backdrop).size(CLOSE_SIZE).pad(CLOSE_INSET);
        root.add(new Stack(canvas, corner)).growX();
        contact = ui.label("?", "hud-name", CONTACT_MINIMUM, UiTheme.BLIP);
        contactScale = contact.getFontScaleX() / CONTACT_MINIMUM;
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        BoardScene.Command command = GpuUtilityBar.minimapCommand(inputs);
        close.setDisabled(command == null || !command.enabled());
        canvas.height(inputs.metrics().lowHeight() ? LOW_CANVAS_HEIGHT : CANVAS_HEIGHT);
    }

    /** The board the minimap shows, or null before the first board or without hexes. */
    private BoardScene scene() {
        BoardScene scene = inputs == null ? null : inputs.frame().scene();
        return scene == null || scene.tiles().isEmpty() ? null : scene;
    }

    /** A straight line of the given width between two points. */
    private void line(Batch batch, Vector2 from, Vector2 to, float width) {
        float length = from.dst(to);
        float angle = MathUtils.atan2(to.y - from.y, to.x - from.x) * MathUtils.radiansToDegrees;
        batch.draw(pixel, from.x, from.y - width / 2, 0, width / 2, length, width, 1, 1, angle);
    }

    /**
     * A hex's art from above. Open water, hazardous liquid or magma covers the ground with its animated tileset art
     * (BoardLiquid), whose first frame stands for it, a hazardous liquid's in the board's tint; frozen water shows its
     * ground art's ice, as the board does.
     */
    private Art art(BoardScene.Tile tile) {
        BoardScene.Pixels ground = tile.ground();
        Color tint = Color.WHITE;
        if (tile.liquid().present() && !tile.frozen()) {
            Optional<BoardScene.Pixels> liquid = liquids.computeIfAbsent(
                  tile.liquid().textures(tile.waterDepth(), tile.elevation()).base(), GpuMinimap::firstFrame);
            if (liquid.isPresent()) {
                ground = liquid.get();
                tint = tile.liquid().kind() == BoardLiquid.Kind.HAZARDOUS ? BoardLiquid.HAZARDOUS_TINT : Color.WHITE;
            }
        }
        return new Art(ground, tile.decals(), tile.foliage(), tint);
    }

    /**
     * The first frame of a liquid animation of the board tileset, decoded as GpuAssets decodes it for the board, or
     * empty when it cannot be read: the hex then shows its ground art, and the failure is logged once.
     */
    private static Optional<BoardScene.Pixels> firstFrame(String path) {
        File file = GpuAssets.tilesetFile(path);
        try {
            return Optional.of(GpuAssets.readWater(file).frames().getFirst());
        } catch (IllegalStateException exception) {
            LOGGER.warn(exception, "The minimap cannot read the liquid art {}", file);
            return Optional.empty();
        }
    }

    /**
     * The mean colour of a hex's art as the board shows it from above: the ground, then its decals and flat foliage
     * over it, weighted by coverage, in the art's tint. Every layer is sampled on the first layer's grid.
     */
    private static int average(Art art) {
        BoardScene.Pixels[] layers = { art.ground(), art.decals(), art.foliage() };
        BoardScene.Pixels grid = art.ground() != null ? art.ground() : art.decals() != null ? art.decals()
              : art.foliage();
        if (grid == null) {
            return 0;
        }
        double red = 0;
        double green = 0;
        double blue = 0;
        double weight = 0;
        for (int row = 0; row < grid.height(); row++) {
            for (int column = 0; column < grid.width(); column++) {
                // Premultiplied colour of the stacked layers at this texel.
                float r = 0;
                float g = 0;
                float b = 0;
                float a = 0;
                for (BoardScene.Pixels layer : layers) {
                    if (layer == null) {
                        continue;
                    }
                    int rgba = layer.rgba(row * layer.height() / grid.height() * layer.width()
                          + column * layer.width() / grid.width());
                    float alpha = (rgba & 255) / 255f;
                    r = (rgba >>> 24) / 255f * alpha + r * (1 - alpha);
                    g = (rgba >>> 16 & 255) / 255f * alpha + g * (1 - alpha);
                    b = (rgba >>> 8 & 255) / 255f * alpha + b * (1 - alpha);
                    a = alpha + a * (1 - alpha);
                }
                red += r;
                green += g;
                blue += b;
                weight += a;
            }
        }
        Color tint = art.tint();
        return weight == 0 ? 0 : Color.rgba8888((float) (red / weight) * tint.r, (float) (green / weight) * tint.g,
              (float) (blue / weight) * tint.b, 1);
    }

    @Override
    public void dispose() {
        canvas.dispose();
    }

    /** The map: a board image baked from the hexes, with the units, the route and the frustum drawn over it. */
    private final class Canvas extends Widget {
        private final Vector2 from = new Vector2();
        private final Vector2 to = new Vector2();
        private float height = CANVAS_HEIGHT;
        private Texture board;
        /** The tiles list and the image content of the last bake; set together with {@link #board}. */
        private List<BoardScene.Tile> bakedTiles;
        private Baked baked;

        Canvas() {
            setName("minimap-canvas");
            addListener(new InputListener() {
                /** Whether the press is a middle one, whose drag orbits; and its last point. */
                private boolean orbiting;
                private float lastX;
                private float lastY;

                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                    orbiting = button == Input.Buttons.MIDDLE;
                    if (orbiting) {
                        lastX = x;
                        lastY = y;
                    } else if (button == Input.Buttons.LEFT) {
                        centre(x, y);
                    } else {
                        return false;
                    }
                    return true;
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    if (!orbiting) {
                        centre(x, y);
                        return;
                    }
                    // As the board's middle drag: the stage's y points up, the screen's down.
                    camera.orbit((x - lastX) * GpuBattleView.ORBIT_DEGREES, (lastY - y) * GpuBattleView.ORBIT_DEGREES);
                    lastX = x;
                    lastY = y;
                }
            });
        }

        void height(float value) {
            if (height != value) {
                height = value;
                invalidateHierarchy();
            }
        }

        @Override
        public float getPrefHeight() {
            return height;
        }

        /** Canvas units per world unit: the board fits inside the margin, centred. */
        private float scale(BoardScene scene) {
            return Math.min((getWidth() - 2 * MARGIN) / BoardCamera.boardWidth(scene),
                  (getHeight() - 2 * MARGIN) / BoardCamera.boardHeight(scene));
        }

        /** A world point on the canvas, relative to the canvas at ({@code x}, {@code y}). */
        private Vector2 place(Vector2 out, BoardScene scene, float x, float y, float worldX, float worldY) {
            float scale = scale(scene);
            return out.set(x + (getWidth() - BoardCamera.boardWidth(scene) * scale) / 2 + worldX * scale,
                  y + (getHeight() + BoardCamera.boardHeight(scene) * scale) / 2 + worldY * scale);
        }

        /**
         * The prototype's minimap drag: the camera centres on the ground under the pointer, in either view. A drag
         * that leaves the canvas stops at the board's edge, as the prototype keeps its camera on the board.
         */
        private void centre(float x, float y) {
            BoardScene scene = scene();
            if (scene != null) {
                float scale = scale(scene);
                float worldX = (x - (getWidth() - BoardCamera.boardWidth(scene) * scale) / 2) / scale;
                float worldY = (y - (getHeight() + BoardCamera.boardHeight(scene) * scale) / 2) / scale;
                camera.center(new Vector3(MathUtils.clamp(worldX, 0, BoardCamera.boardWidth(scene)),
                      MathUtils.clamp(worldY, -BoardCamera.boardHeight(scene), 0), camera.focus.z));
            }
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            validate();
            float alpha = parentAlpha * getColor().a;
            float previous = batch.getPackedColor();
            ui.fill(batch, BACKGROUND, alpha, getX(), getY(), getWidth(), getHeight());
            BoardScene scene = scene();
            if (scene != null) {
                float scale = scale(scene);
                float width = BoardCamera.boardWidth(scene) * scale;
                float boardHeight = BoardCamera.boardHeight(scene) * scale;
                // Back-buffer pixels per stage unit.
                float pixels = UiTheme.pixelScale(batch);
                bake(scene, Math.round(width * pixels * SUPERSAMPLE), Math.round(boardHeight * pixels * SUPERSAMPLE));
                batch.setColor(1, 1, 1, alpha);
                place(from, scene, getX(), getY(), 0, -BoardCamera.boardHeight(scene));
                batch.draw(board, from.x, from.y, width, boardHeight);
                batch.flush();
                if (clipBegin()) {
                    overlays(batch, scene, scale, alpha);
                    batch.flush();
                    clipEnd();
                }
            }
            batch.setPackedColor(previous);
        }

        /** The planned route, the units and the ground the camera shows, over the board. */
        private void overlays(Batch batch, BoardScene scene, float scale, float alpha) {
            float x = getX();
            float y = getY();
            batch.setColor(ROUTE.r, ROUTE.g, ROUTE.b, ROUTE.a * alpha);
            GpuMovePlan.Snapshot move = inputs.frame().panels().move();
            // The route leaves out the moving unit's own hex (E2b), so the line starts where the unit is shown.
            boolean previous = !move.route().isEmpty() && shown(from, scene, x, y, state.presented(move.entityId()));
            for (GpuMovePlan.Step step : move.route()) {
                boolean here = step.boardId() == scene.boardId();
                if (here) {
                    centre(to, scene, x, y, step.coords());
                    if (previous) {
                        line(batch, from, to, ROUTE_WIDTH);
                    }
                    from.set(to);
                }
                previous = here;
            }
            // The prototype measures its unit marks in hex radii.
            float radius = scale * BoardGeometry.WIDTH / 2;
            float half = Math.max(UNIT_MINIMUM, UNIT_SIZE * radius);
            int actor = inputs.frame().status().actorId();
            for (GpuBattleStatus.UnitStatus unit : state.presentedUnits()) {
                if (!shown(from, scene, x, y, unit)) {
                    continue;
                }
                if (unit.sensorContact()) {
                    contact.setFontScale(contactScale * Math.max(CONTACT_MINIMUM, CONTACT_SIZE * radius));
                    contact.pack();
                    contact.setPosition(from.x - contact.getWidth() / 2, from.y - contact.getHeight() / 2);
                    contact.draw(batch, alpha);
                } else {
                    Color color = unit.id() == actor ? Color.WHITE : unit.destroyed() ? DESTROYED
                          : unit.side() == GpuBattleStatus.Side.ENEMY ? UiTheme.CORAL : UiTheme.MINT;
                    ui.fill(batch, color, alpha, from.x - half, from.y - half, 2 * half, 2 * half);
                }
            }
            batch.setColor(FRUSTUM.r, FRUSTUM.g, FRUSTUM.b, FRUSTUM.a * alpha);
            Vector3[] quad = camera.groundQuad(camera.focus.z);
            for (int corner = 0; corner < quad.length; corner++) {
                Vector3 next = quad[(corner + 1) % quad.length];
                line(batch, place(from, scene, x, y, quad[corner].x, quad[corner].y),
                      place(to, scene, x, y, next.x, next.y), FRUSTUM_WIDTH);
            }
        }

        private Vector2 centre(Vector2 out, BoardScene scene, float x, float y, Coords coords) {
            return place(out, scene, x, y, BoardGeometry.centerX(coords), BoardGeometry.centerY(coords));
        }

        /**
         * Places {@code out} where the board view shows a presented unit now, moving with the playback, else at its
         * hex; false for no unit or a unit on another board.
         */
        private boolean shown(Vector2 out, BoardScene scene, float x, float y, GpuBattleStatus.UnitStatus unit) {
            if (unit == null || unit.position() == null || unit.boardId() != scene.boardId()) {
                return false;
            }
            Vector2 position = inputs.view().unitPositions().get(unit.id());
            if (position == null) {
                centre(out, scene, x, y, unit.position());
            } else {
                place(out, scene, x, y, position.x, position.y);
            }
            return true;
        }

        /**
         * Paints the hexes into the board image, each in its art's average colour, at {@link #HEX_FILL} of its size
         * so the background shows between them. The image is painted again only when the hexes' art, the board or
         * the drawn size changes: the source also replaces the tiles list for tactical markings and hex texts, which
         * the minimap does not show.
         */
        private void bake(BoardScene scene, int width, int height) {
            width = MathUtils.clamp(width, 1, MAX_TEXELS);
            height = MathUtils.clamp(height, 1, MAX_TEXELS);
            if (board != null && scene.tiles() == bakedTiles && width == baked.width() && height == baked.height()) {
                return;
            }
            bakedTiles = scene.tiles();
            Baked next = new Baked(bakedTiles.stream().map(GpuMinimap.this::art).toList(), scene.width(),
                  scene.height(), width, height);
            if (board != null && next.equals(baked)) {
                return;
            }
            baked = next;
            colours.keySet().retainAll(Set.copyOf(next.art()));
            float texelsX = width / BoardCamera.boardWidth(scene);
            float texelsY = height / BoardCamera.boardHeight(scene);
            Pixmap image = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            image.setBlending(Pixmap.Blending.None);
            int[] xs = new int[6];
            int[] ys = new int[6];
            Vector3 corner = new Vector3();
            for (int hex = 0; hex < bakedTiles.size(); hex++) {
                BoardScene.Tile tile = bakedTiles.get(hex);
                image.setColor(colours.computeIfAbsent(next.art().get(hex), GpuMinimap::average));
                float centreX = BoardGeometry.centerX(tile.coords());
                float centreY = BoardGeometry.centerY(tile.coords());
                for (int index = 0; index < 6; index++) {
                    BoardGeometry.corner(corner, tile.coords(), 0, index);
                    // Image rows run down from the board's top edge at world y 0.
                    xs[index] = Math.round((centreX + (corner.x - centreX) * HEX_FILL) * texelsX);
                    ys[index] = Math.round(-(centreY + (corner.y - centreY) * HEX_FILL) * texelsY);
                }
                image.fillTriangle(xs[0], ys[0], xs[1], ys[1], xs[2], ys[2]);
                image.fillTriangle(xs[0], ys[0], xs[2], ys[2], xs[3], ys[3]);
                image.fillTriangle(xs[0], ys[0], xs[3], ys[3], xs[5], ys[5]);
                image.fillTriangle(xs[3], ys[3], xs[4], ys[4], xs[5], ys[5]);
            }
            if (board != null) {
                board.dispose();
            }
            board = new Texture(image);
            board.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            image.dispose();
        }

        void dispose() {
            if (board != null) {
                board.dispose();
                board = null;
            }
        }
    }
}
