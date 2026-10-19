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
            var polygon = List.of(a, b, c);
            for (var patch : profile) {
                int direction = patch.landEdge() < 0 ? -1 : BoardGeometry.edgeDirection(patch.landEdge());
                boolean solid = direction >= 0 && (footing.solidExits() & (1 << direction)) != 0;
                // The solid ramp owns its top and sides. Retain only the raised rails above that block.
                if (slab && solid) { continue; }
                var exposed = polygon;
                if (face.part() == BoardBridge.Part.STEEL && direction >= 0
                      && (solid || footing.lengths().get(direction) > 0 && deck.rises().get(direction) > 0)) {
                    // Fascia triangles cross the support's top: dropping only whole slab triangles leaves
                    // coplanar steel over concrete. Cut before grading so both meet on the same slope.
                    exposed = above(polygon, center.z - (solid ? 0 : BoardBridge.SLAB * scale));
                }
                for (int i = 1; i + 1 < exposed.size(); i++) {
                    clipper.prepare(new BoardTacticalGeometry.Triangle(exposed.getFirst(), exposed.get(i), exposed.get(i + 1), -1));
                    clipper.displace(patch, triangle -> BoardBridge.triangle(faces, triangle.a(), triangle.b(), triangle.c(),
                          face.part(), face.colour()));
                }
            }
        }
        return BoardBridge.shape(BoardScene.Surface.CONCRETE, level, faces);
    }

    /** Clip a facet at the concrete support's top, retaining winding and the exposed steel above it. */
    private static List<Vector3> above(List<Vector3> polygon, float height) {
        if (polygon.stream().allMatch(p -> p.z >= height)) { return polygon; }
        var result = new ArrayList<Vector3>(4);
        var previous = polygon.getLast();
        for (var point : polygon) {
            if ((previous.z >= height) != (point.z >= height)) {
                var cut = new Vector3(previous).lerp(point, (height - previous.z) / (point.z - previous.z));
                cut.z = height;
                result.add(cut);
            }
            if (point.z >= height) { result.add(point); }
            previous = point;
        }
        return result;
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
