/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.ToHitData;
import megamek.common.board.Coords;
import megamek.server.SmokeCloud;

/**
 * One hexagon instance per tile, three uses. Outside the Tactical View: the top view's grid over the completed scene,
 * and the grid on the Classic columns' tops at every angle. In the Tactical View: the ground layer, one instanced draw
 * that marks what play: cliffs, Cliff Top exits included, stroked inside the upper hex with
 * hachures into the lower one, thin lines for 1- and 2-level steps, woods and jungle tinted by density, roofs with
 * their outline, water shaded by depth, ice, magma, smoke and fire, and the hovered or focused enemy's rear and side
 * arcs, and on request each hex lightened by its level. The masks follow the existing rules (BoardRim.high is the one
 * cliff rule) over the captured tiles; the arcs are the battle status's {@code Entity.sideTable} capture. The instance
 * data is rebuilt when the tiles or the arcs change, never per frame. Water lies a hair under its surface and every
 * other mark on a plane above the board, both depth-tested without writing depth, so a bridge deck covers the water
 * under it.
 */
final class GpuHexGrid implements Disposable {
    /** Floats per instance: {@code a_hex} (x, y, elevation), {@code a_style} and {@code a_overlay}. */
    static final int FLOATS = 11;
    // Fill codes of a_style.y bits 6-11; the shader's fill table follows them.
    static final int WOODS = 1;
    static final int JUNGLE = 4;
    static final int WATER = 7;
    static final int ICE = 11;
    static final int MAGMA = 12;
    static final int HAZARDOUS = 13;
    static final int MAGMA_CRUST = 14;
    /** A building of type 1-5 is BUILDING + type - 1; then the fuel tank and industry. */
    static final int BUILDING = 16;
    static final int FUEL_TANK = 21;
    static final int INDUSTRIAL = 22;
    // Arc codes of a_overlay.x.
    static final int REAR = 1;
    static final int SIDE = 2;
    static final int FRONT = 3;
    /** Side and front arcs meet in a dashed border this many hexes from the enemy. */
    static final int SIDE_BORDER_REACH = 3;
    /** The land plane lies this many levels over the highest hex top and structure, above anything the board draws. */
    private static final int PLANE_HEADROOM = 6;
    /** Level shading: each level above the board's lowest lightens a hex this much, up to {@link #LEVEL_SHADE_MAX}. */
    static final float LEVEL_SHADE = .07f;
    static final float LEVEL_SHADE_MAX = .21f;

    private final GpuInstancedMesh hex;
    private ShaderProgram shader;
    private List<BoardScene.Tile> tiles;
    private GpuBattleStatus.Arc arc = GpuBattleStatus.Arc.NONE;
    private float[] data = new float[0];
    private int capacity;
    /** The land plane, in levels. */
    private int top;
    /** The board's lowest hex level, where level shading starts. */
    private int lowest;
    private boolean space;

    GpuHexGrid() {
        shader = GpuShaderManager.program(() -> GpuGlsl.compile("GPU hex grid and ground layer",
              GpuShaderSource.read("hex-grid.vert"), GpuShaderSource.read("hex-grid.frag")), next -> shader = next);
        hex = new GpuInstancedMesh(6, 12, new VertexAttributes(VertexAttribute.Position()));
        hex.setVertices(new float[] { .5f, 0, 0, .25f, .5f, 0, -.25f, .5f, 0,
              -.5f, 0, 0, -.25f, -.5f, 0, .25f, -.5f, 0 });
        hex.setIndices(new short[] { 0, 1, 2, 0, 2, 3, 0, 3, 4, 0, 4, 5 });
    }

    /**
     * Draws the grid, or in the Tactical View the ground layer with {@code arc}'s arcs. {@code columns}: the Classic
     * columns, whose tops carry the grid at every angle. Outside the Tactical View, relief shows the grid only from
     * the top view.
     */
    void render(BoardCamera camera, BoardScene scene, boolean columns, boolean tactical, GpuBattleStatus.Arc arc) {
        render(camera, scene, columns, tactical, arc, false);
    }

    /**
     * As {@link #render(BoardCamera, BoardScene, boolean, boolean, GpuBattleStatus.Arc)}; the ground layer lightens
     * each hex by its level while {@code levelShading} is on (the Overlays panel's client setting).
     */
    void render(BoardCamera camera, BoardScene scene, boolean columns, boolean tactical, GpuBattleStatus.Arc arc,
          boolean levelShading) {
        render(camera, scene, columns, tactical, arc, levelShading, null);
    }

    /** The completed atmosphere is borrowed only by the display-space grid, never the tactical palette. */
    void render(BoardCamera camera, BoardScene scene, boolean columns, boolean tactical, GpuBattleStatus.Arc arc,
          boolean levelShading, GpuAtmosphere atmosphere) {
        boolean grid = BoardGeometry.tuning().gridShade() < 1;
        if (scene.tiles().isEmpty() || !tactical && !grid) {
            return;
        }
        update(scene, tactical ? arc : GpuBattleStatus.Arc.NONE);
        boolean spaceOnly = !tactical && !columns && !camera.isTopDown();
        if (spaceOnly && !space) { return; }
        if (tactical) {
            // Water a hair under its surface, the rest on a plane above the board: only a deck over water covers it.
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        } else if (columns || spaceOnly) {
            // Coplanar with the column tops: pulled toward the camera, it wins over them and loses to anything nearer.
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glEnable(GL20.GL_POLYGON_OFFSET_FILL);
            Gdx.gl.glPolygonOffset(-1, -1);
        } else {
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        }
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        // Source-over alpha keeps opaque terrain opaque beneath antialiased grid edges in prints.
        Gdx.gl.glBlendFuncSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE,
              GL20.GL_ONE_MINUS_SRC_ALPHA);
        try {
            shader.bind();
            shader.setUniformf("u_contrastEnabled", 0);
            if (!tactical && atmosphere != null) { atmosphere.bindContrast(shader, 0); }
            shader.setUniformMatrix("u_projTrans", camera.camera.combined);
            shader.setUniformf("u_hexSize", BoardGeometry.width(), BoardGeometry.height(), BoardGeometry.level());
            shader.setUniformf("u_gridShade", grid ? BoardGeometry.tuning().gridShade() : 1);
            shader.setUniformi("u_ground", tactical ? 1 : 0);
            shader.setUniformi("u_spaceOnly", spaceOnly ? 1 : 0);
            shader.setUniformf("u_planes", top * BoardGeometry.level(), BoardGeometry.hexScale());
            shader.setUniformf("u_levels", lowest, levelShading ? LEVEL_SHADE : 0, LEVEL_SHADE_MAX);
            hex.render(shader, GL20.GL_TRIANGLES);
        } finally {
            Gdx.gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL);
            Gdx.gl.glPolygonOffset(0, 0);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    /** One render-owned instance buffer per board edit or arc change; the camera and scale only change uniforms. */
    private void update(BoardScene scene, GpuBattleStatus.Arc nextArc) {
        boolean newTiles = tiles != scene.tiles();
        if (!newTiles && arc.equals(nextArc)) { return; }
        int count = scene.tiles().size();
        if (count > capacity) {
            if (capacity > 0) { hex.disableInstancedRendering(); }
            capacity = Math.max(count, capacity * 2);
            hex.enableInstancedRendering(false, capacity,
                  new VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_hex"),
                  new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_style"),
                  new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_overlay"));
        }
        if (newTiles) {
            data = new float[count * FLOATS];
            top = 0;
            lowest = Integer.MAX_VALUE;
            space = false;
            int at = 0;
            for (var tile : scene.tiles()) {
                data[at] = tile.coords().getX();
                data[at + 1] = tile.coords().getY();
                data[at + 2] = tile.elevation();
                data[at + 3] = edges(scene, tile);
                data[at + 4] = area(scene, tile);
                data[at + 5] = marks(tile);
                data[at + 6] = tile.backdrop() == BoardBackdrop.SPACE ? 1 : 0;
                space |= data[at + 6] != 0;
                at += FLOATS;
                top = Math.max(top, tile.elevation() + tile.cover().height() + PLANE_HEADROOM);
                lowest = Math.min(lowest, tile.elevation());
            }
        }
        // The previous arcs' hexes clear, then the arcs' own, in the scene's column order.
        if (!newTiles) {
            for (Coords coords : arc.sides().keySet()) {
                if (scene.tile(coords) != null) {
                    java.util.Arrays.fill(data, index(scene, coords) + 7, index(scene, coords) + 11, 0);
                }
            }
        }
        for (Coords coords : nextArc.sides().keySet()) {
            if (scene.tile(coords) != null) {
                System.arraycopy(overlay(scene, nextArc, coords), 0, data, index(scene, coords) + 7, 4);
            }
        }
        hex.setInstanceData(data);
        tiles = scene.tiles();
        arc = nextArc;
    }

    /** The first float of a hex's instance, in the scene's column order. */
    private static int index(BoardScene scene, Coords coords) {
        return (coords.getX() * scene.height() + coords.getY()) * FLOATS;
    }

    /**
     * The hexsides that bound a height change, as bits by MegaMek direction: the cliffs this hex tops (bits 0-5), the
     * cliffs a neighbour tops over this hex (6-11), and this hex's 1-level (12-17) and 2-level steps down (18-23). A
     * drop is a cliff by {@link BoardRim#high}, the Classic stroke's rule, which counts a Cliff Top exit at any drop;
     * other drops of 1 or 2 levels are steps. The board's edge bounds nothing.
     */
    static int edges(BoardScene scene, BoardScene.Tile tile) {
        int bits = 0;
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile neighbor = scene.neighbor(tile, direction);
            if (neighbor == null) { continue; }
            int drop = tile.elevation() - neighbor.elevation();
            if (drop > 0 && BoardRim.high(tile, direction, drop)) {
                bits |= 1 << direction;
            } else if (drop == 1 || drop == 2) {
                bits |= 1 << (direction + (drop == 1 ? 12 : 18));
            }
            int rise = -drop;
            if (rise > 0 && BoardRim.high(neighbor, (direction + 3) % 6, rise)) {
                bits |= 1 << (direction + 6);
            }
        }
        return bits;
    }

    /**
     * The hex's fill code (bits 6-11) and the hexsides its area ends at (bits 0-5): a structure, else a liquid, else
     * woods or jungle. A structure ends where its hex has no exit (the building's own continuation), a liquid where the
     * neighbour holds none, woods where the neighbour has none; at the board's edge only a structure ends.
     */
    static int area(BoardScene scene, BoardScene.Tile tile) {
        int fill = fill(tile);
        if (fill == 0) { return 0; }
        int outline = 0;
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile neighbor = scene.neighbor(tile, direction);
            boolean ends;
            if (fill >= BUILDING) {
                ends = fill == INDUSTRIAL ? neighbor == null || fill(neighbor) != INDUSTRIAL
                      : (tile.cover().exits() & (1 << direction)) == 0;
            } else {
                ends = neighbor != null && kind(fill(neighbor)) != kind(fill);
            }
            if (ends) { outline |= 1 << direction; }
        }
        return fill << 6 | outline;
    }

    /** The fill code of a tile: a structure, else a liquid or ice, else woods or jungle by density; 0 for none. */
    static int fill(BoardScene.Tile tile) {
        BoardScene.Cover cover = tile.cover();
        if (cover.structure() == BoardScene.Cover.FUEL_TANK) { return FUEL_TANK; }
        if (cover.structure() == BoardScene.Cover.INDUSTRIAL) { return INDUSTRIAL; }
        if (cover.structure() > 0) { return BUILDING + Math.min(cover.structure(), 5) - 1; }
        if (tile.frozen() && tile.water()) { return ICE; }
        switch (tile.liquid().kind()) {
            case MAGMA -> { return MAGMA; }
            case MAGMA_CRUST -> { return MAGMA_CRUST; }
            case HAZARDOUS -> { return HAZARDOUS; }
            case WATER -> { return WATER + Math.clamp(tile.waterDepth(), 0, 3); }
            default -> { }
        }
        if (cover.woods() > 0) { return (cover.jungle() ? JUNGLE : WOODS) + Math.min(cover.woods(), 3) - 1; }
        return 0;
    }

    /** The area a fill belongs to: 0 none, 1 woods or jungle, 2 a liquid or ice, 3 a structure. */
    private static int kind(int fill) {
        return fill == 0 ? 0 : fill < WATER ? 1 : fill < BUILDING ? 2 : 3;
    }

    /**
     * Smoke (bits 0-1: 0 none, 1 light, 2 heavy), fire (bit 2) and whether the mark lies on the liquid's surface
     * (bit 3) rather than the land plane.
     */
    static int marks(BoardScene.Tile tile) {
        BoardFireSmoke fireSmoke = tile.fireSmoke();
        int smoke = fireSmoke.smoke() <= SmokeCloud.SMOKE_NONE ? 0 : fireSmoke.density() > 1 ? 2 : 1;
        boolean liquid = tile.liquid().present() && !tile.frozen();
        return smoke | (fireSmoke.fire() > 0 ? 4 : 0) | (liquid ? 8 : 0);
    }

    /**
     * A hex's arc marks: its code ({@link #REAR}, {@link #SIDE}, {@link #FRONT}, 0 outside the reach), its distance
     * from the enemy, the hexsides where a rear hex meets one that is not (solid rays) and those where a side hex
     * meets a front hex within {@link #SIDE_BORDER_REACH} (dashed).
     */
    static float[] overlay(BoardScene scene, GpuBattleStatus.Arc arc, Coords coords) {
        int code = code(arc, coords);
        if (code == 0) { return new float[4]; }
        int rays = 0;
        int dashes = 0;
        int distance = coords.distance(arc.origin());
        for (int direction = 0; direction < 6; direction++) {
            Coords next = coords.translated(direction);
            if (scene.tile(next) == null) { continue; }
            int other = code(arc, next);
            if (code == REAR && other != REAR && other != 0) { rays |= 1 << direction; }
            if (code == SIDE && other == FRONT && distance <= SIDE_BORDER_REACH) { dashes |= 1 << direction; }
        }
        return new float[] { code, distance, rays, dashes };
    }

    /** The arc a hex lies in: rear, side or front by the captured side table; 0 outside its reach. */
    static int code(GpuBattleStatus.Arc arc, Coords coords) {
        Integer side = arc.sides().get(coords);
        if (side == null) { return 0; }
        return switch (side) {
            case ToHitData.SIDE_REAR, ToHitData.SIDE_REAR_LEFT, ToHitData.SIDE_REAR_RIGHT -> REAR;
            case ToHitData.SIDE_LEFT, ToHitData.SIDE_RIGHT, ToHitData.SIDE_FRONT_LEFT, ToHitData.SIDE_FRONT_RIGHT -> SIDE;
            default -> FRONT;
        };
    }

    @Override
    public void dispose() {
        hex.dispose();
        tiles = null;
        GpuShaderManager.dispose(shader);
    }
}
