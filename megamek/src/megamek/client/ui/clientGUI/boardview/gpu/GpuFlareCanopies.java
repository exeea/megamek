/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/**
 * The parachutes illumination flares hang from, as real 3D canopies: a shallow dome of alternating panels with a vent
 * at its crown and a scalloped hem, and shroud lines gathering to the flare. The flare lights the canopy from inside,
 * most strongly at the hem nearest it; the night's own light falls on its crown. The whole parachute swings a little
 * about the flare and turns slowly.
 */
final class GpuFlareCanopies implements Disposable {
    /** Panels around the canopy; each is split down its middle so it can billow between its seams. */
    private static final int GORES = 12;
    private static final int COLUMNS = GORES * 2;
    private static final int RINGS = 5;
    /** The vent at the crown and the hem, as angles down from the top of the dome's sphere. */
    private static final float VENT_ANGLE = .2f;
    private static final float HEM_ANGLE = 1.2f;
    /** The canopy's radius at its hem, and how far above the flare that hem hangs, in hexes. */
    private static final float RADIUS_HEXES = .16f;
    private static final float HEM_HEIGHT_HEXES = .30f;
    /** How much flatter than a sphere the dome is, and how far a panel's middle billows out past its seams. */
    private static final float FLATTEN = .8f;
    private static final float BILLOW = 1.06f;
    private static final float LINE_WIDTH_HEXES = .0025f;
    /** Position (3) and colour (4). */
    private static final int STRIDE = 7;
    private static final int TRIANGLES_PER_CANOPY = COLUMNS * (RINGS - 1) * 2 + GORES * 2;
    private static final int CANOPIES_PER_BATCH = 16;
    private static final float[] WHITE = { .92f, .91f, .88f };
    private static final float[] GREY = { .70f, .70f, .67f };
    private static final float[] LINE = { .82f, .81f, .78f };
    /** The flare's own light on the fabric: near white, a touch rose like its halo. */
    private static final float[] FLARE_LIGHT = { 1f, .84f, .80f };

    private final Vector3[][] dome = new Vector3[RINGS][COLUMNS];
    private final Vector3[][] normals = new Vector3[RINGS][COLUMNS];
    private final Matrix4 transform = new Matrix4();
    private final Vector3 a = new Vector3(), b = new Vector3(), c = new Vector3(), d = new Vector3();
    private final Vector3 side = new Vector3(), toCamera = new Vector3();
    private ShaderProgram shader;
    private Mesh mesh;
    private float[] vertices;
    private int offset;

    GpuFlareCanopies() {
        float hex = BoardGeometry.height();
        float radius = hex * RADIUS_HEXES;
        float sphere = radius / MathUtils.sin(HEM_ANGLE);
        float hemHeight = hex * HEM_HEIGHT_HEXES;
        for (int ring = 0; ring < RINGS; ring++) {
            float polar = MathUtils.lerp(VENT_ANGLE, HEM_ANGLE, ring / (float) (RINGS - 1));
            for (int column = 0; column < COLUMNS; column++) {
                float around = column * MathUtils.PI2 / COLUMNS;
                float spread = (column % 2 == 1) ? BILLOW : 1;
                float across = sphere * MathUtils.sin(polar) * spread;
                float up = hemHeight + sphere * (MathUtils.cos(polar) - MathUtils.cos(HEM_ANGLE)) * FLATTEN;
                // The hem rises a little between its seams, as each panel pulls in its edge.
                if ((ring == RINGS - 1) && (column % 2 == 1)) {
                    up += hex * .012f;
                }
                dome[ring][column] = new Vector3(across * MathUtils.cos(around), across * MathUtils.sin(around), up);
                normals[ring][column] = new Vector3(MathUtils.sin(polar) * MathUtils.cos(around),
                      MathUtils.sin(polar) * MathUtils.sin(around), MathUtils.cos(polar)).nor();
            }
        }
    }

    void begin() {
        offset = 0;
    }

    /**
     * Adds one flare's parachute.
     *
     * @param flare   where the flare hangs; the shroud lines gather here
     * @param seed    the flare's own variation, so neighbouring parachutes do not swing in step
     * @param clock   seconds, for the swing and the slow turn
     * @param glow    how brightly the flare burns, 0 to 1
     * @param ambient the night's own light on the canopy, 0 to 1
     * @param opacity 1, falling to 0 as a burnt-out flare's parachute fades away
     */
    void add(Camera camera, Vector3 flare, int seed, float clock, float glow, float ambient, float opacity) {
        if (vertices == null) {
            create();
        }
        if (offset + TRIANGLES_PER_CANOPY * 3 * STRIDE > vertices.length) {
            render(camera);
        }
        // Swings a few degrees about the flare, as a pendulum would, and turns slowly about its shroud lines.
        float swingX = MathUtils.sin(clock * .7f + seed) * 6;
        float swingY = MathUtils.cos(clock * .55f + seed) * 5;
        transform.setToTranslation(flare).rotate(Vector3.X, swingY).rotate(Vector3.Y, -swingX)
              .rotate(Vector3.Z, clock * 8 + seed * 37f);
        float breathe = 1 + .03f * MathUtils.sin(clock * 2.1f + seed);
        for (int ring = 0; ring < RINGS - 1; ring++) {
            for (int column = 0; column < COLUMNS; column++) {
                int next = (column + 1) % COLUMNS;
                float[] panel = ((column / 2) % 2 == 0) ? WHITE : GREY;
                corner(a, ring, column, breathe);
                corner(b, ring, next, breathe);
                corner(c, ring + 1, next, breathe);
                corner(d, ring + 1, column, breathe);
                float topShade = shade(panel, ring, column, glow, ambient);
                float nextShade = shade(panel, ring, next, glow, ambient);
                float hemShade = shade(panel, ring + 1, next, glow, ambient);
                float hemColumnShade = shade(panel, ring + 1, column, glow, ambient);
                vertex(a, panel, topShade, glow, ring, opacity);
                vertex(b, panel, nextShade, glow, ring, opacity);
                vertex(c, panel, hemShade, glow, ring + 1, opacity);
                vertex(a, panel, topShade, glow, ring, opacity);
                vertex(c, panel, hemShade, glow, ring + 1, opacity);
                vertex(d, panel, hemColumnShade, glow, ring + 1, opacity);
            }
        }
        float lineLight = MathUtils.clamp(ambient * .6f + glow * .9f, 0, 1);
        float lineWidth = BoardGeometry.height() * LINE_WIDTH_HEXES;
        for (int seam = 0; seam < COLUMNS; seam += 2) {
            corner(a, RINGS - 1, seam, breathe);
            line(camera, a, flare, lineWidth, lineLight, opacity);
        }
    }

    /** A point of the dome, swung and placed over its flare. */
    private void corner(Vector3 out, int ring, int column, float breathe) {
        Vector3 local = dome[ring][column];
        out.set(local.x * breathe, local.y * breathe, local.z).mul(transform);
    }

    /**
     * How lit the fabric is here: the night's light on the crown, and the flare's light through the cloth, strongest
     * at the hem nearest it.
     */
    private float shade(float[] panel, int ring, int column, float glow, float ambient) {
        float up = normals[ring][column].z;
        float towardHem = ring / (float) (RINGS - 1);
        return ambient * (.55f + .45f * up) + glow * (.35f + .65f * towardHem);
    }

    private void vertex(Vector3 at, float[] panel, float shade, float glow, int ring, float opacity) {
        // The flare's light tints the fabric rose, the more where it falls strongest.
        float tint = glow * (.35f + .65f * ring / (float) (RINGS - 1));
        vertices[offset++] = at.x;
        vertices[offset++] = at.y;
        vertices[offset++] = at.z;
        for (int channel = 0; channel < 3; channel++) {
            float colour = panel[channel] * shade * MathUtils.lerp(1, FLARE_LIGHT[channel], Math.min(1, tint));
            vertices[offset++] = MathUtils.clamp(colour, 0, 1);
        }
        vertices[offset++] = opacity;
    }

    /** A shroud line: a thin strip from the hem to the flare, turned to face the camera. */
    private void line(Camera camera, Vector3 from, Vector3 to, float width, float light, float opacity) {
        toCamera.set(camera.position).sub(from);
        side.set(to).sub(from).crs(toCamera).nor().scl(width);
        b.set(from).add(side);
        c.set(to).add(side);
        d.set(to).sub(side);
        Vector3 start = new Vector3(from).sub(side);
        float[] colour = { LINE[0] * light, LINE[1] * light, LINE[2] * light };
        lineVertex(start, colour, opacity);
        lineVertex(b, colour, opacity);
        lineVertex(c, colour, opacity);
        lineVertex(start, colour, opacity);
        lineVertex(c, colour, opacity);
        lineVertex(d, colour, opacity);
    }

    private void lineVertex(Vector3 at, float[] colour, float opacity) {
        vertices[offset++] = at.x;
        vertices[offset++] = at.y;
        vertices[offset++] = at.z;
        vertices[offset++] = colour[0];
        vertices[offset++] = colour[1];
        vertices[offset++] = colour[2];
        vertices[offset++] = opacity;
    }

    /** Draws the parachutes added since {@link #begin()}, depth tested against the scene, both faces of the cloth. */
    void render(Camera camera) {
        if (offset == 0) {
            return;
        }
        mesh.setVertices(vertices, 0, offset);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        try {
            shader.bind();
            shader.setUniformMatrix("u_projView", camera.combined);
            mesh.render(shader, GL20.GL_TRIANGLES, 0, offset / STRIDE);
        } finally {
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
        offset = 0;
    }

    /** Triangles in one parachute: the dome's panels and its shroud lines. */
    static int trianglesPerCanopy() {
        return TRIANGLES_PER_CANOPY;
    }

    private void create() {
        // The missile shader draws coloured triangles as they come, which is all a canopy needs.
        shader = GpuShaderManager.program(() -> GpuGlsl.compile("GPU flare canopy",
              GpuShaderSource.read("missile.vert"), GpuShaderSource.read("missile.frag")), next -> shader = next);
        vertices = new float[CANOPIES_PER_BATCH * TRIANGLES_PER_CANOPY * 3 * STRIDE];
        mesh = new Mesh(false, vertices.length / STRIDE, 0, VertexAttribute.Position(),
              VertexAttribute.ColorUnpacked());
    }

    @Override
    public void dispose() {
        if (mesh != null) {
            mesh.dispose();
            mesh = null;
        }
        if (shader != null) {
            GpuShaderManager.dispose(shader);
            shader = null;
        }
        vertices = null;
    }
}
