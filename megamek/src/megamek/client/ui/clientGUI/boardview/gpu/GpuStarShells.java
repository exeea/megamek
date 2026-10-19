/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.annotations.Nullable;
import megamek.common.board.Coords;
import megamek.logging.MMLogger;

/**
 * Illumination rounds' flares: star shells hanging over the board under small parachutes, each lighting exactly the
 * hexes the rules say it does. A flare appears where its round burst and glides to its place; when the wind moves it,
 * it glides to its new hex. It sinks under its parachute through its five turns, a step each turn, flickers on its
 * last, and when it burns out it sputters away.
 *
 * <p>The light pool is a pass over the finished opaque scene: the surface under each pixel is found from the scene's
 * depth, and its hex counted against the flare's, so terrain and units inside the radius are lit and nothing outside
 * it. {@link #hexAt} and {@link #hexDistance} mirror flare-light.frag; tests hold them to the game's hex distance.</p>
 */
final class GpuStarShells implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuStarShells.class);
    /** How high a flare hangs over its hex when it arrives, in hexes. */
    static final float HANG_HEXES = 2.2f;
    /** How many turns an illumination round's flare burns, and how low, as a share of its first height, its last is. */
    static final int BURN_TURNS = 5;
    private static final float LAST_TURN_HEIGHT = .35f;
    /** How much further it falls, as a share of its first height, while it sputters out. */
    private static final float SPUTTER_FALL = .15f;
    private static final float GLIDE_SECONDS = 2.5f;
    private static final float SPUTTER_SECONDS = 1.5f;
    /** How much brighter it makes the lit scene at night (1 + gain times), and by full daylight. */
    private static final float NIGHT_GAIN = 3f;
    private static final float DAY_GAIN = .12f;
    /**
     * An even light it adds for each unit of gain, so a near-black surface rises visibly too: brightening alone leaves
     * black black.
     */
    private static final float LIFT = .02f;
    /** Near-white and a touch warm, so the colours of what it lights stay true. */
    private static final Color LIGHT = GpuColor.linear(1, .96f, .9f);
    private static final int CORE = 0, HALO = 1, RAY = 2, SMOKE = 4;
    private static final int RAYS = 4;
    private static final int SMOKE_PUFFS = 5;
    /** Flares lit in one pass; flare-light.frag holds as many. */
    private static final int MAX_LIGHTS = 16;

    private final GpuEffectBatch quads = new GpuEffectBatch(256, "starshell");
    private final List<Shown> shown = new ArrayList<>();
    private final float[] flareHexes = new float[MAX_LIGHTS * 3];
    private final float[] flareCenters = new float[MAX_LIGHTS * 2];
    private final float[] flareStrengths = new float[MAX_LIGHTS];
    private final GpuFlareCanopies canopies = new GpuFlareCanopies();
    private final Vector3 point = new Vector3(), width = new Vector3(), length = new Vector3();
    private ShaderProgram lightShader;
    private Mesh box;
    private float clock;

    /** One flare on screen, matched across scenes so it can glide when it moves and sputter when it is gone. */
    private static final class Shown {
        BoardScene.StarShell shell;
        final Vector3 from = new Vector3();
        final Vector3 to = new Vector3();
        float glide = 1;
        /** Seconds since it burnt out, below 0 while it burns. */
        float sputter = -1;
        int seed;

        boolean burntOut() { return sputter >= SPUTTER_SECONDS; }
    }

    /**
     * Matches the scene's flares to those on screen. A flare that moved glides to its new hex; a new one glides from
     * where its round burst, when that is known, else appears in place; one that is gone sputters out.
     *
     * @param openings where each illumination round that just landed burst, by its target hex
     * @param seconds  the frame's time
     */
    void update(BoardScene scene, Map<Coords, Vector3> openings, float seconds) {
        clock += seconds;
        List<Shown> unmatched = new ArrayList<>(shown);
        unmatched.removeIf(flare -> flare.sputter >= 0);
        List<Shown> next = new ArrayList<>();
        for (BoardScene.StarShell shell : scene.starShells()) {
            Shown match = nearest(unmatched, shell);
            Vector3 hang = hang(scene, shell.coords(), shell.turnsLeft());
            if (match == null) {
                match = new Shown();
                match.seed = shell.coords().hashCode() * 31 + shown.size();
                Vector3 burst = openings.get(shell.coords());
                match.from.set(burst != null ? burst : hang);
                match.to.set(hang);
                match.glide = burst != null ? 0 : 1;
                LOGGER.debug("[Illumination] flare of radius {} over {}, {} turns left, {}", shell.radius(),
                      shell.coords(), shell.turnsLeft(), shell.lit() ? "lit" : "not lit yet");
            } else {
                unmatched.remove(match);
                boolean drifted = !match.shell.coords().equals(shell.coords());
                boolean sank = match.shell.turnsLeft() != shell.turnsLeft();
                if (drifted || sank) {
                    match.from.set(position(match));
                    match.to.set(hang);
                    match.glide = 0;
                    LOGGER.debug("[Illumination] flare moves from {} to {}, {} turns left", match.shell.coords(),
                          shell.coords(), shell.turnsLeft());
                }
            }
            match.shell = shell;
            next.add(match);
        }
        for (Shown gone : unmatched) {
            gone.sputter = 0;
            LOGGER.debug("[Illumination] flare over {} burns out", gone.shell.coords());
        }
        for (Shown flare : shown) {
            if (flare.sputter >= 0) {
                flare.sputter += seconds;
            }
        }
        // The flares still burning are replaced by this scene's; those sputtering out stay until they are gone.
        shown.removeIf(flare -> flare.sputter < 0 || flare.burntOut());
        shown.addAll(next);
        for (Shown flare : next) {
            flare.glide = Math.min(1, flare.glide + seconds / GLIDE_SECONDS);
        }
    }

    /** The unmatched flare on screen nearest the scene's flare, of its radius and within the farthest drift. */
    private static @Nullable Shown nearest(List<Shown> candidates, BoardScene.StarShell shell) {
        Shown best = null;
        int nearest = Integer.MAX_VALUE;
        for (Shown candidate : candidates) {
            int distance = candidate.shell.coords().distance(shell.coords());
            if (candidate.shell.radius() == shell.radius() && distance <= 6 && distance < nearest) {
                nearest = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** Where a flare hangs over its hex: high on its first turn, lower a step each turn, lowest on its last. */
    private static Vector3 hang(BoardScene scene, Coords coords, int turnsLeft) {
        BoardScene.Tile tile = scene.tile(coords);
        return BoardGeometry.center(coords, tile == null ? 0 : tile.elevation())
              .add(0, 0, BoardGeometry.height() * hangHexes(turnsLeft));
    }

    /**
     * How high a flare hangs, in hexes, with this many turns left to burn: {@link #HANG_HEXES} with all of them left,
     * falling evenly to its lowest on its last.
     */
    static float hangHexes(int turnsLeft) {
        float burnt = MathUtils.clamp((BURN_TURNS - turnsLeft) / (float) (BURN_TURNS - 1), 0, 1);
        return HANG_HEXES * MathUtils.lerp(1, LAST_TURN_HEIGHT, burnt);
    }

    /** Where the flare is drawn: gliding, swaying a little under its canopy, and falling as it sputters out. */
    private Vector3 position(Shown flare) {
        float eased = flare.glide * flare.glide * (3 - 2 * flare.glide);
        Vector3 at = new Vector3(flare.from).lerp(flare.to, eased);
        float hex = BoardGeometry.height();
        at.add(MathUtils.sin(clock * .7f + flare.seed) * hex * .06f, MathUtils.cos(clock * .55f + flare.seed) * hex * .05f,
              0);
        if (flare.sputter >= 0) {
            at.z -= hex * HANG_HEXES * SPUTTER_FALL * Math.min(1, flare.sputter / SPUTTER_SECONDS);
        }
        return at;
    }

    /** How bright the flare burns now, 0 to 1: steady, flickering more on its last turn, and dying as it sputters. */
    private float brightness(Shown flare) {
        if (!flare.shell.lit()) {
            return 0;
        }
        boolean lastTurn = flare.shell.turnsLeft() <= 1 || flare.sputter >= 0;
        float flicker = lastTurn ? .7f + .3f * noise(clock * 9 + flare.seed) : .92f + .08f * noise(clock * 5 + flare.seed);
        float sputter = flare.sputter < 0 ? 1 : Math.max(0, 1 - flare.sputter / SPUTTER_SECONDS)
              * (.5f + .5f * noise(clock * 20 + flare.seed));
        return flicker * sputter;
    }

    /** Links its programs now (GpuBattleView's warm-up), not at the first star shell in play. */
    void prepare() {
        quads.prepare();
        canopies.prepare();
        prepareLight();
    }

    private void prepareLight() {
        if (lightShader != null) { return; }
        lightShader = GpuShaderManager.program(() -> GpuGlsl.compile("GPU flare light",
              GpuShaderSource.read("flare-light.vert"), GpuShaderSource.read("flare-light.frag")),
              next -> lightShader = next);
        box = new Mesh(true, 8, 36, VertexAttribute.Position());
        box.setVertices(new float[] {
              -1, -1, -1, 1, -1, -1, 1, 1, -1, -1, 1, -1,
              -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1 });
        box.setIndices(new short[] {
              0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7,
              0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5,
              2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7 });
    }

    /**
     * The light pools over the finished opaque scene, before smoke and fire are drawn over it.
     *
     * @param daylight 0 at night to 1 in full daylight: the light is strong at night and barely there by day
     */
    void renderLight(Camera camera, GpuEffectDepth depth, float daylight) {
        if (shown.isEmpty()) {
            return;
        }
        prepareLight();
        float gain = MathUtils.lerp(NIGHT_GAIN, DAY_GAIN, MathUtils.clamp(daylight, 0, 1));
        Texture scene = depth.capture();
        scene.bind(0);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        // Back faces cover the box even when the camera is inside it.
        Gdx.gl.glCullFace(GL20.GL_FRONT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        try {
            lightShader.bind();
            lightShader.setUniformMatrix("u_projView", camera.combined);
            lightShader.setUniformMatrix("u_inverseProjView", camera.invProjectionView);
            lightShader.setUniformf("u_viewport", depth.x, depth.y, depth.width, depth.height);
            lightShader.setUniformi("u_depth", 0);
            lightShader.setUniformf("u_hexSize", BoardGeometry.width(), BoardGeometry.height());
            int count = 0;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (Shown flare : shown) {
                float strength = brightness(flare) * gain;
                if (strength <= 0 || count == MAX_LIGHTS) {
                    continue;
                }
                Coords hex = flare.shell.coords();
                int radius = flare.shell.radius();
                Vector3 ground = BoardGeometry.center(hex, 0);
                Vector3 at = position(flare);
                flareHexes[count * 3] = hex.getX();
                flareHexes[count * 3 + 1] = hex.getY();
                flareHexes[count * 3 + 2] = radius;
                flareCenters[count * 2] = at.x;
                flareCenters[count * 2 + 1] = at.y;
                flareStrengths[count] = strength;
                float reachX = BoardGeometry.width() * .75f * (radius + 2), reachY = BoardGeometry.height() * (radius + 2);
                minX = Math.min(minX, ground.x - reachX);
                maxX = Math.max(maxX, ground.x + reachX);
                minY = Math.min(minY, ground.y - reachY);
                maxY = Math.max(maxY, ground.y + reachY);
                count++;
            }
            if (count > 0) {
                // One box around every pool, lit once: where pools overlap the brightest flare wins, so two flares
                // light a hex no more than one does, as the rules have it.
                lightShader.setUniformi("u_flareCount", count);
                lightShader.setUniform3fv("u_flareHex[0]", flareHexes, 0, count * 3);
                lightShader.setUniform2fv("u_flareCenter[0]", flareCenters, 0, count * 2);
                lightShader.setUniform1fv("u_flareStrength[0]", flareStrengths, 0, count);
                lightShader.setUniformf("u_boxCenter", (minX + maxX) / 2, (minY + maxY) / 2, 0);
                lightShader.setUniformf("u_boxHalf", (maxX - minX) / 2, (maxY - minY) / 2, BoardGeometry.level() * 60);
                // A dimensionless gain multiplies stored scene RGB directly; it must not be storage-scaled.
                Gdx.gl.glBlendFuncSeparate(GL20.GL_DST_COLOR, GL20.GL_ONE, GL20.GL_ZERO, GL20.GL_ONE);
                lightShader.setUniformf("u_light", LIGHT.r, LIGHT.g, LIGHT.b, 1);
                box.render(lightShader, GL20.GL_TRIANGLES);
                // The additive lift is radiance: convert to scene storage units once. Both passes preserve coverage.
                Gdx.gl.glBlendFuncSeparate(GL20.GL_ONE, GL20.GL_ONE, GL20.GL_ZERO, GL20.GL_ONE);
                lightShader.setUniformf("u_light", LIGHT.r, LIGHT.g, LIGHT.b, LIFT * GpuSceneRadiance.SCALE);
                box.render(lightShader, GL20.GL_TRIANGLES);
            }
        } finally {
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glCullFace(GL20.GL_BACK);
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    /**
     * The flares themselves: the parachutes, then the smoke wisps, then the glowing point, halo and starburst over them.
     *
     * @param daylight 0 at night to 1 in full daylight, for the light on the parachutes' crowns
     */
    void renderFlares(Camera camera, float daylight) {
        if (shown.isEmpty()) {
            return;
        }
        float hex = BoardGeometry.height();
        float ambient = .22f + .7f * MathUtils.clamp(daylight, 0, 1);
        canopies.begin();
        for (Shown flare : shown) {
            float fade = flare.sputter < 0 ? 1 : Math.max(0, 1 - flare.sputter / SPUTTER_SECONDS);
            canopies.add(camera, position(flare), flare.seed, clock, brightness(flare), ambient, fade);
        }
        canopies.render(camera);
        quads.begin();
        for (Shown flare : shown) {
            Vector3 at = position(flare);
            float fade = flare.sputter < 0 ? 1 : Math.max(0, 1 - flare.sputter / SPUTTER_SECONDS);
            for (int puff = 0; puff < SMOKE_PUFFS; puff++) {
                float rise = (clock * .35f + puff / (float) SMOKE_PUFFS) % 1;
                point.set(at).add(-hex * .05f * rise, hex * .03f * rise, hex * (.25f + .6f * rise));
                quads.billboard(camera, point, hex * (.05f + .08f * rise), SMOKE, .35f * (1 - rise) * fade);
            }
        }
        int soft = quads.size();
        for (Shown flare : shown) {
            float glow = brightness(flare);
            if (glow <= 0) {
                continue;
            }
            Vector3 at = position(flare);
            quads.billboard(camera, at, hex * .55f, HALO, glow);
            quads.billboard(camera, at, hex * .07f, CORE, glow);
            width.set(camera.direction).crs(camera.up).nor();
            for (int ray = 0; ray < RAYS; ray++) {
                float angle = clock * .25f + ray * MathUtils.PI / RAYS + flare.seed;
                length.set(width).scl(MathUtils.cos(angle)).mulAdd(camera.up, MathUtils.sin(angle)).scl(hex * .4f);
                point.set(at).add(length);
                quads.ribbon(camera, at, point, hex * .012f, RAY, .8f * glow);
                point.set(at).sub(length);
                quads.ribbon(camera, at, point, hex * .012f, RAY, .8f * glow);
            }
        }
        quads.render(camera, soft);
    }

    /** The hex a point on the board lies in: the nearest of the hex centres around it, as flare-light.frag finds it. */
    static Coords hexAt(float x, float y) {
        int column = (int) Math.floor(x / (BoardGeometry.width() * .75f));
        int row = (int) Math.floor(-y / BoardGeometry.height());
        Coords best = null;
        float nearest = Float.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                Coords candidate = new Coords(column + dx, row + dy);
                float offsetX = x - BoardGeometry.centerX(candidate);
                float offsetY = y - BoardGeometry.centerY(candidate);
                float distance = offsetX * offsetX + offsetY * offsetY;
                if (distance < nearest) {
                    nearest = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** Hexes between two hexes, by the cube arithmetic flare-light.frag uses ("odd-q": odd columns sit lower). */
    static int hexDistance(int columnA, int rowA, int columnB, int rowB) {
        int zA = rowA - (columnA - (columnA & 1)) / 2;
        int zB = rowB - (columnB - (columnB & 1)) / 2;
        int dx = Math.abs(columnA - columnB);
        int dz = Math.abs(zA - zB);
        int dy = Math.abs((-columnA - zA) - (-columnB - zB));
        return Math.max(dx, Math.max(dy, dz));
    }

    int size() { return shown.size(); }

    private static float noise(float value) {
        return .5f + .25f * MathUtils.sin(value * 1.7f) + .25f * MathUtils.sin(value * 2.9f + 1.3f);
    }

    @Override
    public void dispose() {
        quads.dispose();
        canopies.dispose();
        if (lightShader != null) {
            GpuShaderManager.dispose(lightShader);
            lightShader = null;
        }
        if (box != null) {
            box.dispose();
            box = null;
        }
    }
}
