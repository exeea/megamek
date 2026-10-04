/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.math.Vector3;

/** Grade the authored slab and rails together; the installed facets serve drawing, road paint and picking. */
final class BoardBridgeSlope {
    private static final Map<Integer, BoardKit<BoardShape>> SHAPES = new ConcurrentHashMap<>();

    private BoardBridgeSlope() { }

    static void reload() { SHAPES.clear(); }

    static BoardBridge.Shape build(BoardScene.Tile tile, BoardBridge.Deck deck, BoardBridge.Shape footing) {
        var authored = SHAPES.computeIfAbsent(deck.exits(), exits ->
              new BoardKit<>(() -> BoardShape.loadModel(BoardBridge.asset(exits)))).get();
        var profile = profile(tile, deck);
        var faces = new ArrayList<>(footing.facets());
        var clipper = new BoardTacticalGeometry.Clipper();
        float level = tile.elevation() + BoardBridge.feature(tile).elevation();
        float scale = BoardGeometry.hexScale();
        var center = BoardGeometry.center(tile.coords(), level).add(0, 0, GpuRoads.SURFACE_LIFT * scale);
        for (var face : authored.polygons()) {
            var points = face.points();
            var part = face.normal().z > .99f && Math.abs(points[0].z) < .001f
                  ? BoardBridge.Part.TOP : BoardBridge.Part.STRUCTURE;
            clipper.prepare(new BoardTacticalGeometry.Triangle(new Vector3(points[0]).scl(scale).add(center),
                  new Vector3(points[1]).scl(scale).add(center), new Vector3(points[2]).scl(scale).add(center), -1));
            for (var patch : profile) {
                clipper.displace(patch, triangle ->
                      BoardBridge.triangle(faces, triangle.a(), triangle.b(), triangle.c(), part));
            }
        }
        return BoardBridge.shape(BoardScene.Surface.CONCRETE, level, faces);
    }

    /** A level central hub and broad planar approaches, with a constant height across each complete mouth. */
    private static List<BoardSurface.Face> profile(BoardScene.Tile tile, BoardBridge.Deck deck) {
        var result = new ArrayList<BoardSurface.Face>();
        var center = BoardGeometry.center(tile.coords(), 0);
        for (int edge = 0; edge < 6; edge++) {
            var a = BoardGeometry.corner(tile.coords(), 0, edge);
            var b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
            var innerA = new Vector3(center).lerp(a, .5f);
            var innerB = new Vector3(center).lerp(b, .5f);
            var mouthA = new Vector3(a).lerp(b, .25f);
            var mouthB = new Vector3(a).lerp(b, .75f);
            mouthA.z = mouthB.z = deck.rises().get(BoardGeometry.edgeDirection(edge)) * BoardGeometry.level();
            result.add(face(center, innerA, innerB));
            result.add(face(innerA, a, mouthA));
            result.add(face(innerA, mouthA, mouthB));
            result.add(face(innerA, mouthB, innerB));
            result.add(face(innerB, mouthB, b));
        }
        return result;
    }

    private static BoardSurface.Face face(Vector3 a, Vector3 b, Vector3 c) {
        return new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP, -1);
    }
}
