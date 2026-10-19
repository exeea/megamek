/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;

/** Stationary ice grown over the shared waterfall crest: connected rounded deposits and closed edges. */
final class GpuFrozenWaterfall {
    private GpuFrozenWaterfall() { }

    /** Owned with the cached faces; the existing mesh UV channel borrows six floats per triangle. */
    record Geometry(List<BoardSurface.Face> faces, float[] uv) { }

    /** Along the crest and down each actual column, including the horizontal lip and landing apron. */
    private static Vector2[][] curtainUv(BoardSurface surface, BoardSurface.Side drop, Vector3[][] grid) {
        float[] across = GpuWaterfall.across(surface.crest(drop), !surface.fallJoins(drop, true),
              !surface.fallJoins(drop, false), BoardRelief.metres(3.5f));
        int stride = (across.length - 1) / (grid.length - 1);
        Vector2[][] result = new Vector2[grid.length][grid[0].length];
        for (int column = 0; column < grid.length; column++) {
            float down = grid[column][0].z / BoardRelief.metres(15);
            for (int row = 0; row < grid[column].length; row++) {
                if (row > 0) { down -= grid[column][row].dst(grid[column][row - 1]) / BoardRelief.metres(15); }
                result[column][row] = new Vector2(across[column * stride], down);
            }
        }
        return result;
    }

    /** The front skin, sharing the slab's lip and the receiving pool with the ordinary waterfall geometry. */
    static Vector3[][] grid(BoardSurface surface, BoardSurface.Side drop) {
        return grid(surface, drop, TerrainLod.FULL);
    }

    static Vector3[][] grid(BoardSurface surface, BoardSurface.Side drop, TerrainLod lod) {
        Vector3[][] base = GpuWaterfall.grid(surface, drop);
        int stride = lod.dressing ? 1 : 2;
        int columns = (base.length - 1) / stride, rows = base[0].length;
        Vector3[][] result = new Vector3[columns + 1][rows];
        BoardSurface.Crest crest = surface.crest(drop);
        float metre = BoardRelief.metres(1);
        float top = BoardGeometry.surfaceZ(surface.tile), height = Math.max(metre, top - drop.lowA());
        Random random = new Random(seed(surface, drop));
        float phase = random.nextFloat() * 64;
        // Four broad overlapping deposits: crest position, descent and strength. Their seed and shape
        // are independent of the tessellation, so every LOD samples the same connected ice body.
        float[][] deposits = new float[4][3];
        for (int i = 0; i < deposits.length; i++) {
            deposits[i][0] = (i + .3f + .4f * random.nextFloat()) / deposits.length;
            deposits[i][1] = .3f + .4f * random.nextFloat();
            deposits[i][2] = .65f + .35f * random.nextFloat();
        }
        for (int column = 0; column <= columns; column++) {
            float s = column / (float) columns;
            Vector3 outward = crest.normal(s);
            float edge = Math.min(1, Math.min(s, 1 - s) * 6);
            edge = BoardRelief.smooth(edge);
            for (int row = 0; row < rows; row++) {
                Vector3 p = new Vector3(base[column * stride][row]);
                float descent = Math.clamp((top - p.z) / height, 0, 1);
                float ends = BoardRelief.smooth(descent / .10f) * BoardRelief.smooth((1 - descent) / .20f);
                if (row == 0 || row == rows - 1) { ends = 0; }
                float mass = 0;
                for (float[] deposit : deposits) {
                    float down = descent - deposit[1];
                    // A wide shoulder merges into the sheet; its lower end narrows instead of forming a capped peg.
                    float width = .22f - .08f * BoardRelief.smooth(down / .45f);
                    float x = (s - deposit[0]) / width, y = down / (down < 0 ? .3f : .45f);
                    float rounded = Math.max(0, 1 - x * x - y * y);
                    mass += deposit[2] * rounded * rounded;
                }
                float apron = BoardRelief.smooth((descent - .55f) / .25f)
                      * (.5f + .5f * BoardRelief.noise(s * 2 + phase, phase + 43));
                // Smooth saturation avoids flat clipped crowns. The envelope leaves the existing lip,
                // complete corner curves and receiving-surface contacts untouched, including their heights.
                float swell = .08f + .70f * mass / (.2f + mass) + .22f * apron;
                float growth = Math.min(6 * metre, height * .20f) * swell;
                result[column][row] = p.mulAdd(outward, growth * edge * ends);
            }
        }
        return result;
    }

    /** Rendering and picking consume these same triangles; no new support height or game terrain is introduced. */
    static Geometry geometry(BoardSurface surface, BoardSurface.Side drop, TerrainLod lod) {
        Vector3[][] front = grid(surface, drop, lod);
        int columns = front.length - 1, rows = front[0].length;
        Vector3[][] back = new Vector3[columns + 1][rows];
        List<BoardSurface.Face> faces = new ArrayList<>();
        FloatArray coordinates = new FloatArray();
        Vector2[][] uv = curtainUv(surface, drop, front);
        BoardSurface.Crest crest = surface.crest(drop);
        float metre = BoardRelief.metres(1);
        for (int column = 0; column <= columns; column++) {
            Vector3 outward = crest.normal(column / (float) columns);
            for (int row = 0; row < rows; row++) {
                back[column][row] = new Vector3(front[column][row]).mulAdd(outward, -.38f * metre);
            }
        }
        for (int column = 0; column < columns; column++) {
            for (int row = 0; row + 1 < rows; row++) {
                quad(faces, coordinates, front[column][row], front[column][row + 1], front[column + 1][row + 1],
                      front[column + 1][row], uv[column][row], uv[column][row + 1], uv[column + 1][row + 1], uv[column + 1][row]);
                quad(faces, coordinates, back[column + 1][row], back[column + 1][row + 1], back[column][row + 1],
                      back[column][row], uv[column + 1][row], uv[column + 1][row + 1], uv[column][row + 1], uv[column][row]);
            }
            // Cap charts meet the visible skin; their unavoidable seams sit on the rear skin.
            float thickness = .38f / 15;
            quad(faces, coordinates, back[column][0], front[column][0], front[column + 1][0], back[column + 1][0],
                  new Vector2(uv[column][0]).add(0, thickness), uv[column][0], uv[column + 1][0],
                  new Vector2(uv[column + 1][0]).add(0, thickness));
            int foot = rows - 1;
            quad(faces, coordinates, front[column][foot], back[column][foot], back[column + 1][foot], front[column + 1][foot],
                  uv[column][foot], new Vector2(uv[column][foot]).add(0, -thickness),
                  new Vector2(uv[column + 1][foot]).add(0, -thickness), uv[column + 1][foot]);
        }
        for (int row = 0; row + 1 < rows; row++) {
            float thickness = .38f / 3.5f;
            quad(faces, coordinates, back[0][row], back[0][row + 1], front[0][row + 1], front[0][row],
                  new Vector2(uv[0][row]).add(-thickness, 0), new Vector2(uv[0][row + 1]).add(-thickness, 0),
                  uv[0][row + 1], uv[0][row]);
            quad(faces, coordinates, front[columns][row], front[columns][row + 1], back[columns][row + 1], back[columns][row],
                  uv[columns][row], uv[columns][row + 1], new Vector2(uv[columns][row + 1]).add(thickness, 0),
                  new Vector2(uv[columns][row]).add(thickness, 0));
        }
        return new Geometry(List.copyOf(faces), coordinates.toArray());
    }

    private static long seed(BoardSurface surface, BoardSurface.Side drop) {
        return surface.tile.coords().getX() * 0x9E3779B97F4A7C15L
              ^ surface.tile.coords().getY() * 0xC2B2AE3D27D4EB4FL ^ drop.edge() * 7919L;
    }

    private static void quad(List<BoardSurface.Face> faces, FloatArray coordinates, Vector3 a, Vector3 b, Vector3 c,
          Vector3 d, Vector2 ua, Vector2 ub, Vector2 uc, Vector2 ud) {
        triangle(faces, coordinates, a, b, c, ua, ub, uc);
        triangle(faces, coordinates, c, d, a, uc, ud, ua);
    }

    private static void triangle(List<BoardSurface.Face> faces, FloatArray coordinates, Vector3 a, Vector3 b, Vector3 c,
          Vector2 ua, Vector2 ub, Vector2 uc) {
        if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > 1e-10f) {
            faces.add(new BoardSurface.Face(a, b, c, BoardSurface.Finish.ICE));
            coordinates.add(ua.x, ua.y);
            coordinates.add(ub.x, ub.y);
            coordinates.add(uc.x, uc.y);
        }
    }
}
