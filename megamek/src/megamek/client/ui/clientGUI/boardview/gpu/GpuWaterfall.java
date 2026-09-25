/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Random;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

/**
 * Falls and the spray where they land. A fall starts on its pool's surface where the water was pulled back from its
 * crest, curves over the crest and follows the path of thrown water, level at first and ever steeper, to land a short
 * way out in the receiving pool, into which it spreads. Across the mouth the curtain bulges gently in streams. Falls
 * that run on round a corner share their crest's point and tangent there (see {@link BoardSurface.Crest}), so their
 * sheets, patterns and spray carry on round it without a gap or a seam.
 */
final class GpuWaterfall {
    /** Where the water starts to drop, as a share of the lip behind the crest: close enough to the crest that the sheet
     * still clears the ledge beneath it. */
    private static final float CREST = .2f;
    /** How far out a fall lands per unit of drop, and the bounds of that throw at hex scale 1, in world units. */
    private static final float THROW = .16f;
    private static final float MIN_THROW = 1.5f;
    private static final float MAX_THROW = 7;
    /** Rows along the thrown path, and columns across the mouth per waterline segment. */
    private static final int ROWS = 8;
    private static final int COLUMNS = 2;
    /** Outward bulge of the streams at the landing, in world units at hex scale 1. */
    private static final float BULGE = .7f;
    /** Share of a mouth over which a fall's free side frays out. */
    private static final float FRAY = .12f;
    /**
     * World units of sheet per unit of U at hex scale 1. The fall shader's patterns repeat with every whole unit of U,
     * so a corner that fixes U's fraction joins them without a seam.
     */
    private static final float U_LENGTH = 8;

    private GpuWaterfall() { }

    private static float thrown(float height) {
        return Math.clamp(THROW * height, MIN_THROW * BoardGeometry.HEX_SCALE, MAX_THROW * BoardGeometry.HEX_SCALE);
    }

    /** Radius over which a fall spreads into the water it lands in. */
    private static float fillet(float top, float bottom) {
        return Math.min(.5f * BoardSurface.fallLip(top, bottom), .1f * (top - bottom));
    }

    /** Where the water pouring over the crest at {@code s} meets the pool below. */
    static Vector3 landing(BoardSurface surface, BoardSurface.Side drop, float s) {
        BoardSurface.Crest crest = surface.crest(drop);
        Vector3 point = crest.point(s);
        point.mulAdd(crest.normal(s), thrown(BoardGeometry.waterZ(surface.tile) - drop.lowA()));
        point.z = surface.receivingHeight(drop, point.x, point.y);
        return point;
    }

    /**
     * The curtain's vertices, column by column along the crest from {@code a} to {@code b}, each row by row down the
     * thrown path. Row zero lies on the pool's pulled-back edge; the last row lies on the receiving surface.
     */
    static Vector3[][] grid(BoardSurface surface, BoardSurface.Side drop) {
        BoardSurface.Crest crest = surface.crest(drop);
        float top = BoardGeometry.waterZ(surface.tile), bottom = drop.lowA(), height = Math.max(.001f, top - bottom);
        float lip = BoardSurface.fallLip(top, bottom), thrown = thrown(height);
        float fillet = fillet(top, bottom);
        // The path: level from the pool's edge to the crest, then z = top - height u², x growing with u, down to where
        // a quadratic fillet takes it tangentially onto the receiving surface.
        float end = (float) Math.sqrt((height - fillet) / height);
        int rows = ROWS + 5;
        float[] z = new float[rows], fall = new float[rows];
        z[0] = top;
        z[1] = top;
        for (int k = 1; k <= ROWS; k++) {
            float u = end * k / ROWS;
            z[k + 1] = top - height * u * u;
            fall[k + 1] = u * u;
        }
        float pz = z[ROWS + 1];
        for (int k = 1; k <= 3; k++) {
            float v = k / 3f, w = 1 - v;
            z[ROWS + 1 + k] = w * w * pz + v * v * bottom + 2 * w * v * bottom;
            fall[ROWS + 1 + k] = 1;
        }
        int columns = BoardSurface.SHORE_SEGMENTS * COLUMNS;
        // Streams bulge out as the water falls, easing to nothing at the ends, so shared corners stay exact and smooth.
        float phase = MathUtils.sin(crest.a().x * .173f + crest.a().y * .291f) * 43758.547f;
        phase -= (float) Math.floor(phase);
        Vector3[][] result = new Vector3[columns + 1][rows];
        for (int i = 0; i <= columns; i++) {
            float s = i / (float) columns, envelope = MathUtils.sin(MathUtils.PI * s);
            float stream = envelope * envelope * (.6f * MathUtils.sin(MathUtils.PI2 * (s * 1.7f + phase))
                  + .4f * MathUtils.sin(MathUtils.PI2 * (s * 3.9f + phase * 1.9f)));
            Vector3 base = crest.point(s), direction = crest.normal(s);
            float localLip = surface.lipWidth(drop.edge(), s, lip);
            float landing = offset(localLip, height, fillet, thrown, rows - 1);
            float landingX = base.x + direction.x * landing, landingY = base.y + direction.y * landing;
            float landingZ = surface.receivingHeight(drop, landingX, landingY);
            for (int r = 0; r < rows; r++) {
                float offset = offset(localLip, height, fillet, thrown, r)
                      + stream * BULGE * BoardGeometry.HEX_SCALE * (float) Math.pow(fall[r], .65f);
                float descent = (top - z[r]) / height;
                result[i][r] = new Vector3(base.x, base.y, base.z + (landingZ - base.z) * descent).mulAdd(direction, offset);
            }
        }
        // Where the water leaves the pool the sheet starts exactly on the pool's pulled-back edge, straight between the
        // edge's points like the surface itself.
        for (int i = 1; i < columns; i += 2) {
            result[i][0].set(result[i - 1][0]).lerp(result[i + 1][0], .5f);
        }
        return result;
    }

    /** Horizontal distance down the same thrown path, with a lip that can taper into a free bank. */
    private static float offset(float lip, float height, float fillet, float thrown, int row) {
        if (row == 0) { return -lip; }
        float crest = -CREST * lip, end = (float) Math.sqrt((height - fillet) / height);
        if (row <= ROWS + 1) { return crest + (thrown - crest) * end * (row - 1) / ROWS; }
        float px = crest + (thrown - crest) * end;
        float control = px + fillet * (thrown - crest) / (2 * height * end);
        float v = (row - ROWS - 1) / 3f, w = 1 - v;
        return w * w * px + 2 * w * v * control + v * v * (control + .8f * fillet);
    }

    /**
     * One fall. Vertex colour: red how far down the drop a vertex lies, green how far inside its frayed sides (0 at a
     * free end, 1 from a little inside it and all the way to an end shared with the next fall), blue the drop in four
     * levels and alpha the pool's agitation there, so the crest starts out exactly like the water it leaves. U runs
     * along the crest and on round corners shared with other falls, V with world height, as the authored animation
     * expects.
     */
    static void sheet(MeshPartBuilder mesh, BoardSurface surface, BoardSurface.Side drop, GpuWaterShader.Field field) {
        Vector3[][] grid = grid(surface, drop);
        BoardSurface.Crest crest = surface.crest(drop);
        int columns = grid.length - 1, rows = grid[0].length;
        float top = BoardGeometry.waterZ(surface.tile), height = Math.max(.001f, top - drop.lowA());
        float repeat = 48 * BoardGeometry.HEX_SCALE;
        boolean freeA = !surface.fallJoins(drop, true), freeB = !surface.fallJoins(drop, false);
        float[] across = across(grid, crest, freeA, freeB);
        Color data = new Color(0, 0, Math.min(1, height / (GpuWaterShader.DEPTH_RANGE * BoardGeometry.LEVEL)), 0);
        short[][] index = new short[columns + 1][rows];
        Vector3 alongMouth = new Vector3(), alongPath = new Vector3(), normal = new Vector3();
        for (int i = 0; i <= columns; i++) {
            for (int r = 0; r < rows; r++) {
                Vector3 p = grid[i][r];
                // At a corner shared with the next fall both sheets turn their normal on the crest's own tangent there,
                // so the shading runs on.
                if (i == 0 && !freeA) {
                    alongMouth.set(crest.startTangent());
                } else if (i == columns && !freeB) {
                    alongMouth.set(crest.endTangent());
                } else {
                    alongMouth.set(grid[Math.min(i + 1, columns)][r]).sub(grid[Math.max(i - 1, 0)][r]);
                }
                alongPath.set(grid[i][Math.min(r + 1, rows - 1)]).sub(grid[i][Math.max(r - 1, 0)]);
                // Down the path crossed with along the crest faces away from the wall and up over the crest.
                normal.set(alongPath).crs(alongMouth).nor();
                float s = i / (float) columns;
                data.r = Math.clamp((top - p.z) / height, 0, 1);
                data.g = Math.min(freeA ? Math.min(1, s / FRAY) : 1, freeB ? Math.min(1, (1 - s) / FRAY) : 1);
                // Off the board's edge nothing catches the fall: it frays away as it drops, like its free sides.
                if (surface.bottomless(drop)) { data.g *= 1 - BoardRelief.smooth((data.r - .25f) / .7f); }
                data.a = field == null ? 0 : field.agitation(p);
                index[i][r] = mesh.vertex(new MeshPartBuilder.VertexInfo().setPos(p).setNor(normal)
                      .setUV(across[i], p.z / repeat).setCol(data));
            }
        }
        for (int i = 0; i < columns; i++) {
            for (int r = 0; r + 1 < rows; r++) {
                mesh.rect(index[i][r], index[i][r + 1], index[i + 1][r + 1], index[i + 1][r]);
            }
        }
    }

    /**
     * U at each column, growing with the length of the crest. Where the next fall carries on, U's fraction there is
     * the corner's own, which the sheet on the other side shares whichever hex draws it; between two such corners the
     * sheet stretches by at most half a unit to meet both.
     */
    private static float[] across(Vector3[][] grid, BoardSurface.Crest crest, boolean freeA, boolean freeB) {
        int columns = grid.length - 1;
        float[] result = new float[columns + 1];
        float unit = U_LENGTH * BoardGeometry.HEX_SCALE;
        for (int i = 1; i <= columns; i++) {
            Vector3 a = grid[i - 1][1], b = grid[i][1];
            result[i] = result[i - 1] + (float) Math.hypot(b.x - a.x, b.y - a.y) / unit;
        }
        float span = Math.max(result[columns], 1e-4f), start = 0, stretched = span;
        if (!freeA) {
            start = fraction(crest.a());
            if (!freeB) {
                float miss = fraction(crest.b()) - (start + span);
                stretched = span + miss - Math.round(miss);
                if (stretched < .5f) { stretched += 1; }
            }
        } else if (!freeB) {
            start = fraction(crest.b()) + (float) Math.ceil(span) - span;
        }
        for (int i = 0; i <= columns; i++) { result[i] = start + result[i] * stretched / span; }
        return result;
    }

    /** A corner's own fraction of U: any sheet that ends there computes the same one. */
    private static float fraction(Vector3 corner) {
        int hash = Float.floatToIntBits(corner.x) * 73856093 ^ Float.floatToIntBits(corner.y) * 19349663;
        hash ^= hash >>> 15;
        hash *= 0x2c1b3c6d;
        return (hash >>> 8) / (float) (1 << 24);
    }

    /** Fastest launch the spray's vertex colour encodes, in metres per second. */
    private static final float SPRAY_SPEED = 20;
    /** Gravity, in metres per second squared. */
    private static final float GRAVITY = 9.81f;

    /**
     * Spray where a fall lands: white water bursting up in dense puffs, bright droplets flung out in arcs, both falling
     * back into the pool, and mist that billows up and drifts away, all along the landing and on to the corners it
     * shares with the next fall, whose spray carries on from there. Each particle is one camera-facing quad that the
     * vertex shader launches on a loop, so nothing runs on the CPU per frame. Vertex data: position the launch point,
     * normal the launch direction, UV the quad's corner; colour red a phase, green a size, blue the launch speed as a
     * share of {@link #SPRAY_SPEED}, alpha the kind (0 a puff, a half a droplet, 1 mist). A taller fall throws its
     * spray higher and farther.
     */
    static void spray(MeshPartBuilder mesh, BoardSurface surface, BoardSurface.Side drop) {
        BoardSurface.Crest crest = surface.crest(drop);
        float metre = BoardRelief.metres(1), height = BoardGeometry.waterZ(surface.tile) - drop.lowA();
        float fall = Math.max(height, metre) / metre;
        float length = 0;
        Vector3 previous = landing(surface, drop, 0);
        for (int i = 1; i <= 8; i++) {
            Vector3 next = landing(surface, drop, i / 8f);
            length += previous.dst(next);
            previous = next;
        }
        float width = length / metre;
        int puffs = Math.clamp(Math.round(width / .3f), 16, 80);
        int droplets = Math.clamp(Math.round(width / .35f), 12, 64);
        int mists = Math.clamp(Math.round(width / 2.5f), 3, 12);
        // A free side's spray thins out like the side itself; where the next fall carries on it reaches the corner.
        float from = surface.fallJoins(drop, true) ? 0 : FRAY / 2;
        float to = surface.fallJoins(drop, false) ? 1 : 1 - FRAY / 2;
        Random random = new Random(Float.floatToIntBits(crest.a().x) * 31L + Float.floatToIntBits(crest.a().y));
        Color data = new Color();
        Vector3 direction = new Vector3(), origin = new Vector3();
        for (int i = 0; i < puffs + droplets + mists; i++) {
            float kind = i < puffs ? 0 : i < puffs + droplets ? .5f : 1;
            float s = from + (to - from) * random.nextFloat();
            Vector3 inward = crest.normal(s), along = crest.tangent(s);
            origin.set(landing(surface, drop, s))
                  .mulAdd(inward, (random.nextFloat() - .3f) * (kind == 1 ? 2.5f : 1.2f) * metre);
            float speed;
            if (kind == 1) {
                // Buoyant mist rises slowly and spreads out over the pool.
                direction.set(inward).scl(.4f + .5f * random.nextFloat())
                      .mulAdd(along, (random.nextFloat() - .5f) * .6f).add(0, 0, 1).nor();
                speed = .6f + .8f * random.nextFloat();
            } else {
                // Puffs burst up close to the fall, some back against it; droplets fly farther out over the pool.
                boolean droplet = kind > 0;
                direction.set(inward).scl(droplet ? .2f + random.nextFloat() : random.nextFloat() * .9f - .35f)
                      .mulAdd(along, (random.nextFloat() - .5f) * (droplet ? .9f : .5f))
                      .add(0, 0, (droplet ? .9f : 1.3f) + .6f * random.nextFloat()).nor();
                float share = droplet ? .2f + .35f * random.nextFloat() : .12f + .28f * random.nextFloat();
                float rise = Math.clamp(fall * share, 1.2f, 14);
                speed = (float) Math.sqrt(2 * GRAVITY * rise) / direction.z;
            }
            data.set(random.nextFloat(), random.nextFloat(), Math.min(1, speed / SPRAY_SPEED), kind);
            short first = mesh.vertex(corner(origin, direction, 0, 0, data));
            short second = mesh.vertex(corner(origin, direction, 1, 0, data));
            mesh.rect(first, second, mesh.vertex(corner(origin, direction, 1, 1, data)),
                  mesh.vertex(corner(origin, direction, 0, 1, data)));
        }
    }

    /** Highest the spray over a landing reaches above it, its puffs included, for a fall of this height. */
    static float sprayHeight(float height) {
        float metre = BoardRelief.metres(1);
        return Math.min(height * .55f, 14 * metre) + 5 * metre;
    }

    /** Farthest the spray flies out from where a fall lands, for the chunk's bounds. */
    static float sprayReach() {
        return 14 * BoardRelief.metres(1);
    }

    private static MeshPartBuilder.VertexInfo corner(Vector3 origin, Vector3 direction, float u, float v, Color data) {
        return new MeshPartBuilder.VertexInfo().setPos(origin).setNor(direction).setUV(u, v).setCol(data);
    }

    /**
     * The water's vertex shader with the spray's launch added: for spray particles ({@code u_waterMaterial.w}), each
     * vertex flies its particle along its arc and opens the camera-facing quad around it; everything else is left as
     * the source has it. The particle's age replaces its vertex colour on the way to the fragment shader.
     */
    static String vertex(String source) {
        String declarations = """
              #if defined(colorFlag) && defined(normalFlag) && defined(diffuseTextureFlag)
              #define waterSprayFlag
              uniform vec4 u_waterMaterial;
              uniform float u_rainTime;
              uniform float u_metre;
              uniform vec3 u_wind;
              uniform vec3 u_cameraDirection;
              uniform vec3 u_cameraUp;
              #endif
              """;
        String launch = """
              #ifdef waterSprayFlag
              if (u_waterMaterial.w > 0.5) {
                  float gravity = 9.81 * u_metre;
                  float mist = step(0.75, a_color.a), droplet = step(0.25, a_color.a) - mist;
                  float speed = a_color.b * %s * u_metre;
                  vec3 aim = normalize(a_normal);
                  // A droplet lives until it falls back into the water; mist until it has thinned away. Each rests a
                  // moment below the surface, then launches again with a fresh aim, never twice on the same arc.
                  float life = mix(2.0 * speed * max(aim.z, 0.2) / gravity, 2.4 + 1.8 * a_color.g, mist);
                  float period = life * 1.3 + 0.15;
                  float cycle = u_rainTime / period + a_color.r;
                  float t = fract(cycle) * period;
                  float launch = floor(cycle);
                  vec2 jitter = fract(sin(vec2(launch * 12.9898 + a_color.r * 78.233,
                        launch * 39.346 + a_color.g * 11.135)) * 43758.5453) - 0.5;
                  vec3 flight = normalize(aim + vec3(jitter * 0.4, 0.0));
                  vec3 p = pos.xyz + vec3(jitter * 2.0 * u_metre, 0.0);
                  p += flight * (speed * t) * (1.0 - mist);
                  p.z -= 0.5 * gravity * t * t * (1.0 - mist);
                  vec3 drift = vec3(flight.xy * 0.8 * u_metre + u_wind.xy * u_wind.z * 2.0 * u_metre, speed);
                  p += drift * t * mist;
                  float age = t / life;
                  float alive = step(t, life);
                  float puff = mix(0.9, 2.0, a_color.g) * (0.7 + 0.9 * age);
                  float size = mix(mix(puff, mix(0.12, 0.24, a_color.g), droplet),
                        mix(2.2, 4.2, a_color.g) * (0.6 + 1.1 * age), mist) * u_metre * alive;
                  // The quad faces the camera; a droplet's is drawn out along its flight on screen, a short streak.
                  vec3 velocity = flight * speed - vec3(0.0, 0.0, gravity * t);
                  vec3 onScreen = velocity - u_cameraDirection * dot(velocity, u_cameraDirection);
                  vec3 across = normalize(cross(u_cameraDirection, u_cameraUp));
                  vec3 along = normalize(cross(across, u_cameraDirection));
                  if (droplet > 0.5 && dot(onScreen, onScreen) > 1e-4) {
                      along = normalize(onScreen);
                      across = normalize(cross(u_cameraDirection, along));
                  }
                  float stretch = size + droplet * length(onScreen) * 0.045 * alive;
                  vec2 corner = a_texCoord0 * 2.0 - 1.0;
                  pos.xyz = p + across * (corner.x * size) + along * (corner.y * stretch);
                  v_color = vec4(age, a_color.g, alive, a_color.a);
              }
              #endif
              """.formatted(SPRAY_SPEED + "");
        String main = "void main() {", anchor = "gl_Position = u_projViewTrans * pos;";
        return insertOnce(insertOnce(source, main, declarations + main), anchor, launch + anchor);
    }

    private static String insertOnce(String source, String anchor, String replacement) {
        int index = source.indexOf(anchor);
        if (index < 0 || source.indexOf(anchor, index + anchor.length()) >= 0) {
            throw new IllegalStateException("Incompatible water vertex shader insertion point: " + anchor);
        }
        return source.substring(0, index) + replacement + source.substring(index + anchor.length());
    }
}
