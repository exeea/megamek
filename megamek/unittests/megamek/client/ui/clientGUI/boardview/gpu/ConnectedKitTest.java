/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import org.junit.jupiter.api.Test;

class ConnectedKitTest {
    private record Box(String node, float x0, float y0, float z0, float x1, float y1, float z1) { }

    /** A core post, a north arm to the edge midpoint and a north cap off to the side, each in every module. */
    private static List<Box> symmetric() {
        List<Box> result = new ArrayList<>();
        for (String role : List.of("base0", "floor0", "roof0")) {
            float top = role.equals("roof0") ? 2 : 18;
            result.add(new Box("kit-lod0-" + role, -5, -5, 0, 5, 5, top));
            result.add(new Box("kit-lod0-" + role + "-link-n", -2, 5, 0, 2, 36, top));
            result.add(new Box("kit-lod0-" + role + "-end-n", 10, 0, 0, 12, 4, top));
        }
        return result;
    }

    @Test
    void theBoardHexIsRegularSoATurnedNorthPieceMeetsEveryEdge() {
        for (int edge = 0; edge < 6; edge++) {
            Vector3 midpoint = BoardGeometry.modelCorner(edge).add(BoardGeometry.modelCorner(edge + 1)).scl(.5f);
            int direction = BoardGeometry.edgeDirection(edge);
            double angle = Math.toRadians(90 - 60 * direction);
            // The width is the regular one to a 32nd (HexTile.MODEL_WIDTH): within 0.005 of regular.
            assertEquals(36 * Math.cos(angle), midpoint.x, .01, "edge " + edge);
            assertEquals(36 * Math.sin(angle), midpoint.y, .01, "edge " + edge);
        }
    }

    @Test
    void symmetricArmsTurnOntoEveryConnectedEdgeAndNowhereElse() {
        var kit = ConnectedKit.load(List.of(kit(symmetric())));
        assertTrue(kit.symmetric());
        var model = ConnectedKit.model(kit, 0, 1, new int[] { 0, 1, 0, 1, 0, 0 }, 0, 0, "test");
        for (int direction = 0; direction < 6; direction++) {
            float far = farthest(model, direction);
            if (direction == 1 || direction == 3) {
                assertEquals(36, far, 1e-4, "The turned arm ends exactly on edge " + direction);
            } else {
                assertTrue(far < 20, "No arm towards unconnected edge " + direction + ": " + far);
            }
        }
        // The whole kit turns: its north is board south-east here.
        var turned = ConnectedKit.model(kit, 0, 1, new int[] { 0, 0, 1, 0, 0, 0 }, 2, 0, "turned");
        assertEquals(36, farthest(turned, 2), 1e-4);
        assertTrue(farthest(turned, 0) < 20);
    }

    @Test
    void piecesStackThroughTheLevelsWhereTheirTagsHold() {
        var kit = ConnectedKit.load(List.of(kit(symmetric())));
        // Three levels high, connected north for the lowest level only (a lower neighbour).
        var model = ConnectedKit.model(kit, 0, 3, new int[] { 1, 0, 0, 0, 0, 0 }, 0, 0, "stacked");
        float[] arm = heights(model, (x, y) -> Math.abs(x) <= 2.01f && y > 20);
        assertEquals(0, arm[0], 1e-4);
        assertEquals(20, arm[1], 1e-4, "The arm's own roof caps it where the connection stops");
        float[] cap = heights(model, (x, y) -> x >= 9.99f && x <= 12.01f && y >= -.01f && y <= 4.01f);
        assertEquals(18, cap[0], 1e-4, "The closed side starts with a floor above the lower neighbour");
        assertEquals(56, cap[1], 1e-4, "and ends with its roof on top");
        float[] core = heights(model, (x, y) -> Math.abs(x) <= 5.01f && Math.abs(y) <= 5.01f);
        assertEquals(56, core[1], 1e-4);
    }

    @Test
    void rejectsAmbiguousOrIncompleteKits() {
        List<List<Box>> kits = new ArrayList<>();
        kits.add(renamed(symmetric(), "kit-lod0-base0", "kit-lod0-base0.001"));
        kits.add(renamed(symmetric(), "kit-lod0-base0-link-n", "kit-lod0-base0-link-north"));
        // North and south-east arms only: neither symmetric nor complete.
        kits.add(renamed(renamed(renamed(symmetric(), "kit-lod0-base0-end-n", "kit-lod0-base0-link-se"),
              "kit-lod0-floor0-end-n", "kit-lod0-floor0-link-se"), "kit-lod0-roof0-end-n", "kit-lod0-roof0-link-se"));
        // An arm that stops short of its edge.
        kits.add(symmetric().stream().map(box -> box.node().contains("link") ? new Box(box.node(), -2, 5, 0, 2, 30,
              box.z1()) : box).toList());
        // An arm that makes its module taller than one level.
        kits.add(symmetric().stream().map(box -> box.node().equals("kit-lod0-floor0-link-n")
              ? new Box(box.node(), -2, 5, 0, 2, 36, 20) : box).toList());
        // A module missing a tagged piece the others have, or the core piece the others have.
        kits.add(symmetric().stream().filter(box -> !box.node().equals("kit-lod0-floor0-end-n")).toList());
        kits.add(symmetric().stream().filter(box -> !box.node().equals("kit-lod0-floor0")).toList());
        // A corner piece without its other three states.
        var corner = new ArrayList<>(symmetric());
        corner.removeIf(box -> box.node().contains("-n"));
        for (String role : List.of("base0", "floor0", "roof0")) {
            corner.add(new Box("kit-lod0-" + role + "-link-n-end-ne", 0, 0, 0, 20, 36, role.equals("roof0") ? 2 : 18));
        }
        kits.add(corner);
        for (var boxes : kits) {
            assertThrows(IllegalArgumentException.class, () -> ConnectedKit.load(List.of(kit(boxes))), boxes.toString());
        }
        var far = kit(symmetric().stream().filter(box -> !box.node().contains("end")).toList());
        assertThrows(IllegalArgumentException.class, () -> ConnectedKit.load(List.of(kit(symmetric()), far)),
              "Every level of detail has the same pieces");
    }

    private static List<Box> renamed(List<Box> boxes, String from, String to) {
        return boxes.stream().map(box -> box.node().equals(from)
              ? new Box(to, box.x0(), box.y0(), box.z0(), box.x1(), box.y1(), box.z1()) : box).toList();
    }

    private static RigidGlb.Data kit(List<Box> boxes) {
        var data = new RigidGlb.Data();
        data.id = "kit-lod0";
        var material = new ModelMaterial();
        material.id = "paint";
        data.materials.add(material);
        var mesh = new ModelMesh();
        mesh.id = "mesh";
        var vertices = new FloatArray();
        List<ModelMeshPart> parts = new ArrayList<>();
        for (Box box : boxes) {
            int first = vertices.size / RigidGlb.STRIDE;
            for (int corner = 0; corner < 8; corner++) {
                vertices.addAll((corner & 1) == 0 ? box.x0() : box.x1(), (corner & 2) == 0 ? box.y0() : box.y1(),
                      (corner & 4) == 0 ? box.z0() : box.z1(), 0, 0, 1, 1, 1, 1, 1, 0, 0);
            }
            var part = new ModelMeshPart();
            part.id = box.node() + "/part";
            part.primitiveType = 4;
            part.indices = new short[12];
            int[] corners = { 0, 1, 2, 1, 3, 2, 4, 5, 6, 5, 7, 6 };
            for (int i = 0; i < corners.length; i++) { part.indices[i] = (short) (first + corners[i]); }
            parts.add(part);
            var node = new ModelNode();
            node.id = box.node();
            node.translation = new Vector3();
            node.rotation = new Quaternion();
            node.scale = new Vector3(1, 1, 1);
            var nodePart = new ModelNodePart();
            nodePart.meshPartId = part.id;
            nodePart.materialId = material.id;
            node.parts = new ModelNodePart[] { nodePart };
            data.nodes.add(node);
        }
        mesh.vertices = vertices.toArray();
        mesh.parts = parts.toArray(ModelMeshPart[]::new);
        data.meshes.add(mesh);
        return data;
    }

    /** The model's farthest point along board direction {@code direction}, from the hex centre. */
    private static float farthest(RigidGlb.Data model, int direction) {
        double angle = Math.toRadians(90 - 60 * direction);
        float x = (float) Math.cos(angle), y = (float) Math.sin(angle), result = Float.NEGATIVE_INFINITY;
        for (var mesh : model.meshes) {
            for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                result = Math.max(result, mesh.vertices[i] * x + mesh.vertices[i + 1] * y);
            }
        }
        return result;
    }

    private interface Region { boolean contains(float x, float y); }

    /** The lowest and highest point of the model's vertices standing in {@code region}. */
    private static float[] heights(RigidGlb.Data model, Region region) {
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (var mesh : model.meshes) {
            for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                if (!region.contains(mesh.vertices[i], mesh.vertices[i + 1])) { continue; }
                low = Math.min(low, mesh.vertices[i + 2]);
                high = Math.max(high, mesh.vertices[i + 2]);
            }
        }
        return new float[] { low, high };
    }
}
