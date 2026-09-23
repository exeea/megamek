/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

/**
 * Falls and the mist over the pools they land in. A fall starts on its pool's surface where the water was pulled back
 * from the mouth, curves over the crest and follows the path of thrown water, level at first and ever steeper, to land
 * a short way out in the receiving pool, into which it spreads. Across the mouth the curtain bulges gently in streams.
 * Two falls from one pool that share a corner both turn it on the same miter, so their sheets and the pool's edge meet
 * without a gap.
 */
final class GpuWaterfall {
    /** Where the water starts to drop, as a share of the lip behind the mouth: close enough to the edge that the sheet
     * still clears the ledge beneath it where it crosses the edge. */
    private static final float CREST = .2f;
    /** How far out a fall lands per unit of drop, and the bounds of that throw at hex scale 1, in world units. */
    private static final float THROW = .16f;
    private static final float MIN_THROW = 1.5f;
    private static final float MAX_THROW = 7;
    /** Rows along the thrown path, and one column per this many world units of mouth at hex scale 1. */
    private static final int ROWS = 8;
    private static final float COLUMN_WIDTH = 6;
    /** Outward bulge of the streams at the landing, in world units at hex scale 1. */
    private static final float BULGE = .7f;
    /** Share of a mouth over which a fall's free side frays out. */
    private static final float FRAY = .12f;

    private GpuWaterfall() { }

    /** Outward plane of a fall, shared with its wall: away from the higher hex, into the receiving one. */
    static Vector3 outward(BoardSurface.Side drop) {
        return new Vector3(drop.b().x - drop.a().x, drop.b().y - drop.a().y, 0).crs(Vector3.Z).nor();
    }

    /** How far out from its mouth a fall reaches, its spread into the pool included; the chunk's bounds hold it. */
    static float reach(BoardSurface.Side drop) {
        float height = drop.a().z - drop.lowA();
        return thrown(height) + BULGE * BoardGeometry.HEX_SCALE + fillet(drop.a().z, drop.lowA()) * 2;
    }

    private static float thrown(float height) {
        return Math.clamp(THROW * height, MIN_THROW * BoardGeometry.HEX_SCALE, MAX_THROW * BoardGeometry.HEX_SCALE);
    }

    /** Radius over which a fall spreads into the water it lands in. */
    private static float fillet(float top, float bottom) {
        return Math.min(.5f * BoardSurface.fallLip(top, bottom), .1f * (top - bottom));
    }

    /** The fall of the same pool that shares this fall's end, or null where there is none. */
    private static BoardSurface.Side neighbour(BoardSurface surface, BoardSurface.Side drop, boolean atA) {
        int edge = (drop.edge() + (atA ? 5 : 1)) % 6;
        for (BoardSurface.Side other : surface.waterfalls) {
            if (other.edge() == edge) { return other; }
        }
        return null;
    }

    /** Length of mouth before this fall along the chain of falls that turn corners into it, so textures run on. */
    private static float chainStart(BoardSurface surface, BoardSurface.Side drop) {
        float length = 0;
        BoardSurface.Side previous = neighbour(surface, drop, true);
        for (int step = 0; previous != null && step < 5; step++) {
            length += previous.a().dst(previous.b());
            previous = neighbour(surface, previous, true);
        }
        return length;
    }

    /**
     * The curtain's vertices, column by column across the mouth from {@code a} to {@code b}, each row by row down the
     * thrown path. Row zero lies on the pool's pulled-back edge; the last row lies on the receiving surface.
     */
    static Vector3[][] grid(BoardSurface surface, BoardSurface.Side drop) {
        float top = drop.a().z, bottom = drop.lowA(), height = Math.max(.001f, top - bottom);
        float lip = BoardSurface.fallLip(top, bottom), crest = -CREST * lip, thrown = thrown(height);
        float fillet = fillet(top, bottom);
        // The path: level from the pool's edge to the crest, then z = top - height u², x growing with u, down to where
        // a quadratic fillet takes it tangentially onto the receiving surface.
        float end = (float) Math.sqrt((height - fillet) / height);
        int rows = ROWS + 5;
        float[] x = new float[rows], z = new float[rows], fall = new float[rows];
        x[0] = -lip;
        z[0] = top;
        x[1] = crest;
        z[1] = top;
        for (int k = 1; k <= ROWS; k++) {
            float u = end * k / ROWS;
            x[k + 1] = crest + (thrown - crest) * u;
            z[k + 1] = top - height * u * u;
            fall[k + 1] = u * u;
        }
        float px = x[ROWS + 1], pz = z[ROWS + 1];
        float control = px + fillet * (thrown - crest) / (2 * height * end);
        float spread = control + .8f * fillet;
        for (int k = 1; k <= 3; k++) {
            float v = k / 3f, w = 1 - v;
            x[ROWS + 1 + k] = w * w * px + 2 * w * v * control + v * v * spread;
            z[ROWS + 1 + k] = w * w * pz + v * v * bottom + 2 * w * v * bottom;
            fall[ROWS + 1 + k] = 1;
        }
        Vector3 own = outward(drop), atA = surface.fallDirection(drop, true), atB = surface.fallDirection(drop, false);
        int columns = Math.clamp(Math.round(drop.a().dst(drop.b()) / (COLUMN_WIDTH * BoardGeometry.HEX_SCALE)), 2, 8);
        // Streams bulge out as the water falls, never at the ends, so shared corners stay exact.
        float phase = MathUtils.sin(drop.a().x * .173f + drop.a().y * .291f) * 43758.547f;
        phase -= (float) Math.floor(phase);
        Vector3[][] result = new Vector3[columns + 1][rows];
        for (int i = 0; i <= columns; i++) {
            float s = i / (float) columns;
            float fromA = (1 - s) * (1 - s) * (1 - s), fromB = s * s * s;
            Vector3 direction = new Vector3(own).mulAdd(new Vector3(atA).sub(own), fromA)
                  .mulAdd(new Vector3(atB).sub(own), fromB);
            float stream = MathUtils.sin(MathUtils.PI * s) * (.6f * MathUtils.sin(MathUtils.PI2 * (s * 1.7f + phase))
                  + .4f * MathUtils.sin(MathUtils.PI2 * (s * 3.9f + phase * 1.9f)));
            Vector3 base = new Vector3(drop.a()).lerp(drop.b(), s);
            for (int r = 0; r < rows; r++) {
                float offset = x[r] + stream * BULGE * BoardGeometry.HEX_SCALE * (float) Math.pow(fall[r], .65f);
                result[i][r] = new Vector3(base.x, base.y, z[r]).mulAdd(direction, offset);
            }
        }
        return result;
    }

    /**
     * One fall. Vertex colour: red how far down the drop a vertex lies, green how far inside its frayed sides (0 at a
     * free end, 1 from a little inside it and all the way to an end shared with the next fall), blue the drop in four
     * levels and alpha the pool's agitation there, so the crest starts out exactly like the water it leaves. U runs
     * across the mouth, on around corners shared with other falls, and V with world height, as the authored animation
     * expects.
     */
    static void sheet(MeshPartBuilder mesh, BoardSurface surface, BoardSurface.Side drop, GpuWaterShader.Field field) {
        Vector3[][] grid = grid(surface, drop);
        int columns = grid.length - 1, rows = grid[0].length;
        float top = drop.a().z, height = Math.max(.001f, top - drop.lowA());
        float scale = 24 * BoardGeometry.HEX_SCALE, repeat = 48 * BoardGeometry.HEX_SCALE;
        float start = chainStart(surface, drop) / scale, across = drop.a().dst(drop.b()) / scale;
        boolean freeA = !surface.fallJoins(drop, true), freeB = !surface.fallJoins(drop, false);
        // At a corner shared with the next fall, both sheets turn their normal on the miter, so the shading runs on.
        Vector3 cornerA = new Vector3(Vector3.Z).crs(surface.fallDirection(drop, true));
        Vector3 cornerB = new Vector3(Vector3.Z).crs(surface.fallDirection(drop, false));
        Color data = new Color(0, 0, Math.min(1, height / (GpuWaterShader.DEPTH_RANGE * BoardGeometry.LEVEL)), 0);
        short[][] index = new short[columns + 1][rows];
        Vector3 alongMouth = new Vector3(), alongPath = new Vector3(), normal = new Vector3();
        for (int i = 0; i <= columns; i++) {
            for (int r = 0; r < rows; r++) {
                Vector3 p = grid[i][r];
                if (i == 0 && !freeA) {
                    alongMouth.set(cornerA);
                } else if (i == columns && !freeB) {
                    alongMouth.set(cornerB);
                } else {
                    alongMouth.set(grid[Math.min(i + 1, columns)][r]).sub(grid[Math.max(i - 1, 0)][r]);
                }
                alongPath.set(grid[i][Math.min(r + 1, rows - 1)]).sub(grid[i][Math.max(r - 1, 0)]);
                // Down the path crossed with across the mouth faces away from the wall and up over the crest.
                normal.set(alongPath).crs(alongMouth).nor();
                float s = i / (float) columns;
                data.r = Math.clamp((top - p.z) / height, 0, 1);
                data.g = Math.min(freeA ? Math.min(1, s / FRAY) : 1, freeB ? Math.min(1, (1 - s) / FRAY) : 1);
                data.a = field == null ? 0 : field.agitation(p);
                index[i][r] = mesh.vertex(new MeshPartBuilder.VertexInfo().setPos(p).setNor(normal)
                      .setUV(start + s * across, p.z / repeat).setCol(data));
            }
        }
        for (int i = 0; i < columns; i++) {
            for (int r = 0; r + 1 < rows; r++) {
                mesh.rect(index[i][r], index[i][r + 1], index[i + 1][r + 1], index[i + 1][r]);
            }
        }
    }

    /**
     * Mist over each receiving pool, blue marking spray, no particle objects: a curtain rising in front of the landing
     * water and a low billow drifting out over the pool, which also shows from above.
     */
    static void mist(MeshPartBuilder mesh, GpuWaterShader.Impact impact) {
        Vector3 center = new Vector3(impact.center()).mulAdd(impact.inward(),
              thrown(impact.drop()) + .6f * BULGE * BoardGeometry.HEX_SCALE);
        // Wider than the mouth, so the cloud also drifts over the banks and the wall beside the fall.
        Vector3 tangent = new Vector3(impact.inward().y, -impact.inward().x, 0).scl(1.35f * impact.halfWidth());
        Vector3 a = new Vector3(center).sub(tangent), b = new Vector3(center).add(tangent);
        float rise = Math.min(impact.radius(), .5f * impact.drop() + 4 * BoardGeometry.HEX_SCALE);
        for (Vector3 reach : List.of(new Vector3(0, 0, rise),
              new Vector3(impact.inward()).scl(1.4f * impact.radius()).add(0, 0, .45f * rise))) {
            mesh.rect(vertex(a, impact.inward(), 0, 0), vertex(b, impact.inward(), 1, 0),
                  vertex(new Vector3(b).add(reach), impact.inward(), 1, 1),
                  vertex(new Vector3(a).add(reach), impact.inward(), 0, 1));
        }
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 p, Vector3 normal, float u, float v) {
        return new MeshPartBuilder.VertexInfo().setPos(p).setNor(normal).setUV(u, v).setCol(Color.BLUE);
    }
}
