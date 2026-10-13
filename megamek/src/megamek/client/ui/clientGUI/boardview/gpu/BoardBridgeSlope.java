/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;

/** Grade the generated slab and rails together; the installed facets serve drawing, road paint and picking. */
final class BoardBridgeSlope {
    private BoardBridgeSlope() { }

    /** Install one generated deck, including its actual end or joint trusses, onto the shared grade profile. */
    static BoardBridge.Shape build(BoardScene.Tile tile, BoardBridge.Deck deck, BoardBridgeFooting footing) {
        var profile = deck.sloped() || footing.solidExits() != 0 ? profile(tile, deck) : List.<BoardSurface.Face>of();
        var faces = new ArrayList<>(footing.shape().facets());
        var clipper = new BoardTacticalGeometry.Clipper();
        float level = tile.elevation() + BoardBridge.feature(tile).elevation();
        float scale = BoardGeometry.hexScale();
        var center = BoardGeometry.center(tile.coords(), 0);
        center.z = BoardBridge.deckZ(level);
        for (var face : BoardBridgeMesh.build(tile, deck)) {
            var a = new Vector3(face.a()).scl(scale).add(center);
            var b = new Vector3(face.b()).scl(scale).add(center);
            var c = new Vector3(face.c()).scl(scale).add(center);
            if (profile.isEmpty()) {
                BoardBridge.triangle(faces, a, b, c, face.part(), face.colour());
                continue;
            }
            boolean slab = Math.max(face.a().z, Math.max(face.b().z, face.c().z)) <= .001f;
            clipper.prepare(new BoardTacticalGeometry.Triangle(a, b, c, -1));
            for (var patch : profile) {
                // The solid ramp owns its top and sides. Retain only the raised rails above that block.
                if (slab && patch.landEdge() >= 0
                      && (footing.solidExits() & (1 << BoardGeometry.edgeDirection(patch.landEdge()))) != 0) { continue; }
                clipper.displace(patch, triangle -> BoardBridge.triangle(faces, triangle.a(), triangle.b(), triangle.c(),
                      face.part(), face.colour()));
            }
        }
        return BoardBridge.shape(BoardScene.Surface.CONCRETE, level, faces);
    }

    /**
     * A level central hub and broad planar approaches, with a constant height across each complete mouth: the grade the
     * deck's slab is displaced onto, relative to its deck plane (world XY). The piers' caps follow it too.
     */
    static List<BoardSurface.Face> profile(BoardScene.Tile tile, BoardBridge.Deck deck) {
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
            result.add(face(center, innerA, innerB, -1));
            result.add(face(innerA, a, mouthA, edge));
            result.add(face(innerA, mouthA, mouthB, edge));
            result.add(face(innerA, mouthB, innerB, edge));
            result.add(face(innerB, mouthB, b, edge));
        }
        return result;
    }

    private static BoardSurface.Face face(Vector3 a, Vector3 b, Vector3 c, int edge) {
        return new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP, edge);
    }
}
