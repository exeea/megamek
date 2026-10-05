/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;

/** Deterministic industrial scenery. */
final class BoardIndustrial {
    enum Machine { COOLER, GENERATOR, SILO, REACTOR, EXCHANGER, COLUMN, FLARE }
    enum Cap { DOME, CONE, FLAT }
    record Equipment(Machine machine, float x, float y, float radius, float height, Cap cap, int detail) { }
    record Connection(Equipment from, Equipment to, float height, float routeY) { }
    /** Local authored coordinates; both neighbors independently derive the same world-space joint. */
    record Port(int direction, float x, float y, float z) { }
    record Layout(List<Equipment> equipment, List<Connection> connections, List<Port> ports) { }

    private static final Color IVORY = new Color(.92f, .9f, .83f, 1);
    private static final Color TEAL = new Color(.36f, .53f, .52f, 1);
    private static final Color STEEL = new Color(.72f, .77f, .8f, 1);
    private static final Color DARK = new Color(.32f, .36f, .39f, 1);
    private static final Color COPPER = new Color(.74f, .5f, .3f, 1);
    private static final Color YELLOW = new Color(.88f, .66f, .23f, 1);
    private static final int SIDES = 16;

    private BoardIndustrial() { }

    static boolean supports(BoardScene.Feature feature) {
        return feature.kind() == BoardScene.FeatureKind.INDUSTRIAL
              && feature.asset().matches("buildings/(?:saxarba/)?misc/heavy_industrial_[a-d]");
    }

    static Layout layout(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        var random = new SplittableRandom(GpuBuilding.seed(tile, feature));
        float height = Math.max(1, feature.height()) * BoardGeometry.MODEL_LEVEL_HEIGHT;
        int detail = random.nextInt(4);
        Cap cap = Cap.values()[random.nextInt(Cap.values().length)];
        float shortHeight = height * (float) random.nextDouble(.3, .48);
        float mediumHeight = height * (float) random.nextDouble(.57, .78);
        List<Equipment> equipment = switch (feature.asset().charAt(feature.asset().length() - 1)) {
            case 'a' -> List.of(new Equipment(Machine.COOLER, -20, 0, 12, height, cap, detail),
                  new Equipment(Machine.GENERATOR, 19, -4, 9, mediumHeight, cap, detail),
                  new Equipment(Machine.GENERATOR, 16, 23, 5, shortHeight, cap, (detail + 1) % 4));
            case 'b' -> List.of(new Equipment(Machine.SILO, -19, -3, 11.5f, height, cap, detail),
                  new Equipment(Machine.SILO, 18, -18, 7, shortHeight, Cap.values()[(cap.ordinal() + 1) % 3], detail),
                  new Equipment(Machine.SILO, 18, 18, 6.5f, mediumHeight, Cap.values()[(cap.ordinal() + 2) % 3], detail));
            case 'c' -> List.of(new Equipment(Machine.REACTOR, -19, 0, 10, height, cap, detail),
                  new Equipment(Machine.EXCHANGER, 19, -2, 6, mediumHeight, cap, detail));
            default -> List.of(new Equipment(Machine.FLARE, 19, 10, 3, height, cap, detail),
                  new Equipment(Machine.COLUMN, -18, -8, 7, mediumHeight, cap, detail),
                  new Equipment(Machine.EXCHANGER, -16, 23, 4, shortHeight, cap, detail));
        };
        List<Connection> connections = new ArrayList<>();
        for (int i = 1; i < equipment.size(); i++) {
            Equipment from = equipment.getFirst(), to = equipment.get(i);
            float routeY = to.y() < 0 ? -24 : 24;
            float connection = to.height() * (.44f + .09f * ((detail + i) % 3));
            connections.add(new Connection(from, to, connection, routeY));
            if (to.height() >= 42) {
                connections.add(new Connection(from, to, to.height() * .84f, -routeY));
            }
        }
        float ground = BoardGeometry.groundZ(tile) / BoardGeometry.level() * BoardGeometry.MODEL_LEVEL_HEIGHT;
        List<Port> ports = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            var next = scene.tile(tile.coords().translated(direction));
            if (next == null) { continue; }
            var neighbor = next.features().stream().filter(BoardIndustrial::supports).findFirst().orElse(null);
            if (neighbor == null) { continue; }
            float nextGround = BoardGeometry.groundZ(next) / BoardGeometry.level() * BoardGeometry.MODEL_LEVEL_HEIGHT;
            float low = Math.max(ground, nextGround) + 4;
            float high = Math.min(ground + height, nextGround + neighbor.height() * BoardGeometry.MODEL_LEVEL_HEIGHT) - 2;
            // Both sides derive the same available vertical interval and unordered pair seed.
            if (high < low) { continue; }
            long ownSeed = GpuBuilding.seed(tile, feature), nextSeed = GpuBuilding.seed(next, neighbor);
            var pair = new SplittableRandom(Math.min(ownSeed, nextSeed) * 31 + Math.max(ownSeed, nextSeed));
            float px = (BoardGeometry.centerX(next.coords()) - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale() / 2;
            float py = (BoardGeometry.centerY(next.coords()) - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale() / 2;
            ports.add(new Port(direction, px, py, low + (high - low) * (float) pair.nextDouble(.22, .42) - ground));
            if (high - low >= 36) {
                ports.add(new Port(direction, px, py, low + (high - low) * (float) pair.nextDouble(.65, .85) - ground));
            }
        }
        return new Layout(equipment, List.copyOf(connections), List.copyOf(ports));
    }

    /** The same triangles feed GPU upload, physical picking and cosmetic obstacle clearance. */
    static ModelData model(Layout layout) {
        Mesh mesh = new Mesh();
        for (Equipment equipment : layout.equipment()) { machine(mesh, equipment); }
        for (Connection connection : layout.connections()) {
            Vector3 a = attachment(connection.from(), connection.height()), b = attachment(connection.to(), connection.height());
            float y = connection.routeY(), z = connection.height();
            mesh.pipe(COPPER, .85f, a, v(a.x, y, a.z), v(a.x, y, z), v(b.x, y, z), v(b.x, y, b.z), b);
            support(mesh, a.x, y, Math.max(z, a.z));
            support(mesh, b.x, y, Math.max(z, b.z));
        }
        // Ground-mounted service valves connect to each machine around, rather than across, the ground passage.
        for (Equipment equipment : layout.equipment()) {
            float side = Math.signum(equipment.x());
            float y = equipment.y() < 0 ? -24 : 24;
            float z = Math.min(3.5f, equipment.height() * .3f);
            Vector3 nozzle = attachment(equipment, z);
            mesh.pipe(COPPER, .65f, nozzle, v(side * 26, equipment.y(), nozzle.z),
                  v(side * 26, equipment.y(), z), v(side * 26, y, z));
            if (nozzle.z != z) { support(mesh, side * 26, equipment.y(), nozzle.z); }
            mesh.box(side * 26, y, z / 2, 1.3f, 1.3f, z, DARK);
            mesh.tube(v(side * 26, y, z), v(side * 26, y, z + .8f), 1.2f, STEEL, 8);
        }
        for (Port port : layout.ports()) {
            Equipment source = layout.equipment().stream().filter(e -> e.height() >= port.z() + 1.5f)
                  .min(Comparator.comparingDouble(e -> (e.x() - port.x()) * (e.x() - port.x())
                        + (e.y() - port.y()) * (e.y() - port.y()))).orElseThrow();
            float y = Math.copySign(24, port.y());
            Vector3 end = v(port.x(), port.y(), port.z());
            Vector3 outward = v(port.x(), port.y(), 0).nor();
            Vector3 neck = new Vector3(end).mulAdd(outward, -6);
            Vector3 nozzle = attachment(source, port.z());
            mesh.pipe(COPPER, 1.1f, nozzle, v(nozzle.x, y, nozzle.z), v(nozzle.x, y, port.z()),
                  v(neck.x, y, port.z()), neck, end);
            mesh.tube(new Vector3(end).mulAdd(outward, -1), new Vector3(end).mulAdd(outward, -.5f), 1.5f, STEEL, 10);
            // The elevated section has a real footing on its own hex, even when the other hex is higher.
            support(mesh, nozzle.x, y, Math.max(port.z(), nozzle.z));
            support(mesh, neck.x, neck.y, port.z());
        }
        mesh.supports.forEach((point, height) -> {
            mesh.box(point.x(), point.y(), .4f, 2, 2, .8f, STEEL);
            mesh.box(point.x(), point.y(), height / 2, .8f, .8f, height, DARK);
        });
        return mesh.data();
    }

    /** Horizontal exchangers attach at a real vessel, not in the empty space between their drums. */
    private static Vector3 attachment(Equipment equipment, float z) {
        if (equipment.machine() == Machine.EXCHANGER) {
            int count = exchangerCount(equipment.height(), equipment.radius());
            float spacing = equipment.height() / count;
            int drum = Math.clamp((int) (z / spacing), 0, count - 1);
            z = (drum + .5f) * spacing;
        }
        return v(equipment.x(), equipment.y(), z);
    }

    private static void support(Mesh mesh, float x, float y, float z) {
        // Several pipes can share one support. Emit its footing and column only once.
        mesh.supports.merge(new Mesh.Footing(x, y), z, Math::max);
    }

    private static int exchangerCount(float height, float radius) {
        return Math.max(1, Math.min(4, (int) (height / (radius * 2.5f))));
    }

    static List<Vector3> triangles(Layout layout) {
        float[] vertices = model(layout).meshes.first().vertices;
        List<Vector3> points = new ArrayList<>(vertices.length / RigidGlb.STRIDE);
        for (int i = 0; i < vertices.length; i += RigidGlb.STRIDE) {
            points.add(v(vertices[i], vertices[i + 1], vertices[i + 2]));
        }
        return points;
    }

    private static void machine(Mesh mesh, Equipment e) {
        float x = e.x(), y = e.y(), r = e.radius(), h = e.height();
        Color paint = e.machine() == Machine.COLUMN || e.detail() % 2 == 0 ? IVORY : TEAL;
        switch (e.machine()) {
            case COOLER -> {
                // A flared cooling chimney, with a visibly open mouth, instead of another capped silo.
                float[] zs = { 0, .06f, .28f, .6f, .83f, 1 };
                float[] rs = { 1, 1, .78f, .56f, .65f, .82f };
                for (int i = 1; i < zs.length; i++) {
                    mesh.frustum(x, y, h * zs[i - 1], r * rs[i - 1], h * zs[i], r * rs[i], IVORY);
                }
                mesh.frustum(x, y, h, r * .82f, h, r * .68f, STEEL);
                mesh.frustum(x, y, h, r * .68f, h * .8f, r * .52f, DARK);
                mesh.disc(x, y, h * .8f, r * .52f, DARK, true);
                for (int i = 0; i < 12; i++) {
                    float a = i * (float) Math.PI / 6;
                    mesh.tube(v(x + r * .9f * cos(a), y + r * .9f * sin(a), h * .035f),
                          v(x + r * .9f * cos(a), y + r * .9f * sin(a), h * .16f), .6f, DARK, 6);
                }
            }
            case GENERATOR -> {
                mesh.box(x, y, (h - 2.2f) / 2, r * 1.8f, r * 2.2f, h - 2.2f, DARK);
                mesh.box(x, y, 1, r * 2, r * 2.4f, 2, STEEL);
                mesh.box(x, y, h - 1.1f, r * 1.9f, r * 2.3f, 2.2f, TEAL, false);
                for (float z = 3; z < h - 2; z += Math.max(3, h / 36)) {
                    mesh.box(x, y, z, r * 2, r * 2.05f, .65f, STEEL);
                }
                fan(mesh, x, y, h, r);
                for (int side : new int[] { -1, 1 }) {
                    mesh.pipe(COPPER, .7f, v(x, y + side * r, h * .25f),
                          v(x + r, y + side * r, h * .25f), v(x + r, y + side * r, h * .78f),
                          v(x, y + side * r, h * .78f));
                }
            }
            case SILO, COLUMN -> {
                float capHeight = Math.min(h * .27f, e.cap() == Cap.CONE ? r * 1.05f : r * .55f);
                float body = h - capHeight;
                mesh.cylinder(x, y, 0, Math.min(1.4f, h * .1f), r + .5f, STEEL);
                mesh.cylinder(x, y, .8f, body, r, paint, false);
                for (float z = 5; z < body - 1; z += Math.max(e.machine() == Machine.COLUMN ? 9 : 19, body / 24)) {
                    mesh.cylinder(x, y, z, z + .4f, r + .2f, STEEL);
                }
                cap(mesh, x, y, r, body, h, e.cap(), paint);
                float ox = x + Math.copySign(r + 1, x);
                mesh.pipe(COPPER, .6f, v(x, y, 2), v(ox, y, 2), v(ox, y, body * .78f), v(x, y, body * .78f));
                if (e.machine() == Machine.COLUMN) {
                    float low = Math.min(3, body * .2f), high = body * .82f;
                    int count = Math.min(160, Math.max(24, (int) body * 2));
                    Vector3 before = v(x, y, low);
                    for (int i = 0; i <= count; i++) {
                        float a = i * (float) Math.PI / 10;
                        Vector3 next = v(x + (r + .5f) * cos(a), y + (r + .5f) * sin(a), low + (high - low) * i / count);
                        mesh.tube(before, next, .22f, COPPER, 6, false, false);
                        before = next;
                    }
                    mesh.tube(before, v(x, y, high), .22f, COPPER, 6);
                }
            }
            case REACTOR -> {
                // A pressure bulb over a narrow reaction stem, with a pointed separator head.
                mesh.cylinder(x, y, 0, h * .67f, r * .52f, STEEL);
                mesh.frustum(x, y, h * .15f, r * .52f, h * .43f, r, TEAL);
                mesh.frustum(x, y, h * .43f, r, h * .76f, r, TEAL);
                mesh.frustum(x, y, h * .76f, r, h * .92f, r * .28f, IVORY);
                mesh.cylinder(x, y, h * .92f, h, r * .28f, COPPER);
                for (int side : new int[] { -1, 1 }) {
                    mesh.box(x + side * (r + 1), y, h * .38f, .9f, 1, h * .76f, DARK);
                    mesh.tube(v(x + side * (r + 1), y, h * .73f), v(x, y, h * .73f), .5f, STEEL, 6);
                }
                mesh.pipe(COPPER, .8f, v(x, y, h * .25f), v(x, y - r - 2, h * .25f),
                      v(x, y - r - 2, h * .68f), v(x, y, h * .68f));
                mesh.cylinder(x, y, h * .44f, h * .44f + .5f, r + .3f, STEEL);
            }
            case EXCHANGER -> {
                int count = exchangerCount(h, r);
                float length = r * 4.5f;
                for (int i = 0; i < count; i++) {
                    float z = (i + .5f) * h / count;
                    float radius = Math.min(r, h / count * .42f);
                    mesh.tube(v(x, y - length / 2, z), v(x, y + length / 2, z), radius, IVORY, SIDES);
                    for (int side : new int[] { -1, 1 }) {
                        mesh.box(x, y + side * length * .4f, Math.max(.7f, z - radius),
                              (r + .7f) * 2, 1, .9f, STEEL);
                    }
                    for (int end : new int[] { -1, 1 }) {
                        float ey = y + end * length / 2;
                        mesh.tube(v(x, ey - .4f, z), v(x, ey + .4f, z), radius + .4f, STEEL, SIDES);
                        if (i < count - 1) { mesh.pipe(COPPER, .65f, v(x, ey, z), v(x + r + 1, ey, z)); }
                    }
                }
                for (int end : new int[] { -1, 1 }) {
                    float ey = y + end * length / 2, z = (count - .5f) * h / count;
                    // One shared return riser and footing, with the lower drums teeing into it.
                    mesh.pipe(COPPER, .65f, v(x, ey, z), v(x + r + 1, ey, z), v(x + r + 1, ey, 1));
                    mesh.box(x + r + 1, ey, .5f, 2.5f, 2.5f, 1, DARK);
                }
                for (int dx : new int[] { -1, 1 }) {
                    for (int dy : new int[] { -1, 1 }) {
                        mesh.box(x + dx * (r + .7f), y + dy * length * .4f, (h - 1) / 2, .9f, .9f, h - 1, DARK);
                    }
                    mesh.box(x + dx * (r + .7f), y, h - .5f, .9f, length, 1, STEEL);
                }
                mesh.box(x, y, .7f, r * 2.5f, length, 1.4f, DARK);
            }
            case FLARE -> {
                mesh.cylinder(x, y, 0, h, r, COPPER, false);
                mesh.cylinder(x, y, h - Math.min(3, h * .2f), h, r * 1.6f, DARK);
                int stages = Math.max(1, Math.min(12, (int) (h / 15)));
                for (int i = 0; i < stages; i++) {
                    float bottom = .7f + i * (h * .9f - .7f) / stages, top = .7f + (i + 1) * (h * .9f - .7f) / stages;
                    float spread = 8 - i * 3f / stages, upper = 8 - (i + 1) * 3f / stages;
                    for (int corner = 0; corner < 3; corner++) {
                        float a = corner * (float) Math.PI * 2 / 3, b = (corner + 1) * (float) Math.PI * 2 / 3;
                        Vector3 low = v(x + spread * cos(a), y + spread * sin(a), bottom);
                        Vector3 high = v(x + upper * cos(a), y + upper * sin(a), top);
                        Vector3 next = v(x + upper * cos(b), y + upper * sin(b), top);
                        mesh.tube(low, high, .6f, STEEL, 6);
                        mesh.tube(low, next, .28f, STEEL, 6);
                        mesh.tube(high, next, .4f, DARK, 6);
                        mesh.tube(high, v(x, y, top), .3f, DARK, 6);
                        if (i == 0) { mesh.box(low.x, low.y, .4f, 2.3f, 2.3f, .8f, DARK); }
                    }
                }
            }
        }
        // A bolted control box is attached to the equipment, without stairs or floor plates across the hex.
        if (e.machine() != Machine.FLARE && e.machine() != Machine.COOLER) {
            mesh.box(x, y - r * .85f, h * .36f, r * .7f, r * .45f, Math.min(3, h * .15f), YELLOW);
        }
    }

    private static void cap(Mesh mesh, float x, float y, float r, float bottom, float top, Cap cap, Color paint) {
        switch (cap) {
            case CONE -> mesh.frustum(x, y, bottom, r, top, 0, paint);
            case DOME -> {
                for (int i = 0; i < 6; i++) {
                    float a = i * (float) Math.PI / 12, b = (i + 1) * (float) Math.PI / 12;
                    mesh.frustum(x, y, bottom + (top - bottom) * sin(a), r * cos(a),
                          bottom + (top - bottom) * sin(b), Math.max(0, r * cos(b)), paint);
                }
            }
            case FLAT -> {
                mesh.cylinder(x, y, bottom, bottom + (top - bottom) * .16f, r + .15f, STEEL);
                // A flat plate and tall offset vent have a different outline from the dome and conical cap.
                mesh.cylinder(x + r * .4f, y, bottom, top, r * .18f, DARK, false);
                mesh.cylinder(x + r * .4f, y, top - .4f, top, r * .33f, STEEL);
            }
        }
    }

    private static void fan(Mesh mesh, float x, float y, float z, float r) {
        float rotor = r * .7f;
        // The roof has a real opening. No body/roof/fan discs are layered on nearly the same plane.
        List<Float> angles = new ArrayList<>();
        for (int i = 0; i < SIDES; i++) { angles.add(i * (float) Math.PI * 2 / SIDES); }
        float corner = (float) Math.atan2(r * 1.15f, r * .95f);
        angles.add(corner); angles.add((float) Math.PI - corner);
        angles.add((float) Math.PI + corner); angles.add((float) Math.PI * 2 - corner);
        angles.sort(Float::compare);
        for (int i = 0; i < angles.size(); i++) {
            float a = angles.get(i), b = angles.get((i + 1) % angles.size());
            mesh.quad(v(x + rotor * cos(a), y + rotor * sin(a), z), roofEdge(x, y, z, r, a), roofEdge(x, y, z, r, b),
                  v(x + rotor * cos(b), y + rotor * sin(b), z), Vector3.Z, Vector3.Z, TEAL);
            mesh.quad(v(x + rotor * cos(a), y + rotor * sin(a), z), v(x + rotor * cos(b), y + rotor * sin(b), z),
                  v(x + rotor * cos(b), y + rotor * sin(b), z - 1.2f), v(x + rotor * cos(a), y + rotor * sin(a), z - 1.2f),
                  v(-cos(a), -sin(a), 0), v(-cos(b), -sin(b), 0), DARK);
            mesh.triangle(v(x, y, z - 1.2f), v(x + rotor * cos(a), y + rotor * sin(a), z - 1.2f),
                  v(x + rotor * cos(b), y + rotor * sin(b), z - 1.2f), Vector3.Z, Vector3.Z, Vector3.Z, DARK);
        }
        for (int i = 0; i < 6; i++) {
            float a = i * (float) Math.PI / 3;
            Vector3 u = v(cos(a), sin(a), 0), w = v(-sin(a), cos(a), 0), p = v(x, y, z - .45f);
            mesh.quad(new Vector3(p).mulAdd(u, rotor * .15f).mulAdd(w, -.3f), new Vector3(p).mulAdd(u, rotor * .9f),
                  new Vector3(p).mulAdd(u, rotor * .7f).mulAdd(w, rotor * .23f), new Vector3(p).mulAdd(u, rotor * .15f).mulAdd(w, .3f),
                  Vector3.Z, Vector3.Z, STEEL);
        }
        mesh.cylinder(x, y, z - .6f, z - .3f, rotor * .18f, STEEL);
    }

    private static Vector3 roofEdge(float x, float y, float z, float r, float angle) {
        float distance = Math.min(r * .95f / Math.max(.00001f, Math.abs(cos(angle))),
              r * 1.15f / Math.max(.00001f, Math.abs(sin(angle))));
        return v(x + distance * cos(angle), y + distance * sin(angle), z);
    }

    private static Vector3 v(float x, float y, float z) { return new Vector3(x, y, z); }
    private static float cos(float angle) { return (float) Math.cos(angle); }
    private static float sin(float angle) { return (float) Math.sin(angle); }

    /** A bounded rigid mesh in the same position/normal/colour/UV format as imported scenery. */
    private static final class Mesh {
        record Footing(float x, float y) { }
        final Map<Footing, Float> supports = new LinkedHashMap<>();
        final FloatArray vertices = new FloatArray();
        final ShortArray paintIndices = new ShortArray();
        final ShortArray metalIndices = new ShortArray();

        void vertex(Vector3 p, Vector3 normal, Color color) {
            vertex(p, normal, color, textureU(p, normal), textureV(p, normal));
        }

        void vertex(Vector3 p, Vector3 normal, Color color, float u, float v) {
            int index = vertices.size / RigidGlb.STRIDE;
            if (index >= 65_535) { throw new IllegalArgumentException("Industrial mesh exceeds rigid vertex limit"); }
            boolean paint = color == IVORY || color == TEAL || color == YELLOW;
            (paint ? paintIndices : metalIndices).add((short) index);
            float tile = paint ? 32 : 10;
            vertices.addAll(p.x, p.y, p.z, normal.x, normal.y, normal.z, color.r, color.g, color.b, 1,
                  u / tile, v / tile);
        }

        private static float textureU(Vector3 p, Vector3 n) { return Math.abs(n.x) > Math.abs(n.y) ? p.y : p.x; }
        private static float textureV(Vector3 p, Vector3 n) { return Math.abs(n.z) > .8f ? p.y : p.z; }

        void quad(Vector3 a, Vector3 b, Vector3 c, Vector3 d, Vector3 first, Vector3 second, Color color) {
            triangle(a, b, c, first, second, second, color);
            triangle(a, c, d, first, second, first, color);
        }

        void triangle(Vector3 a, Vector3 b, Vector3 c, Vector3 na, Vector3 nb, Vector3 nc, Color color) {
            triangle(a, b, c, na, nb, nc, color, textureU(a, na), textureV(a, na), textureU(b, nb), textureV(b, nb),
                  textureU(c, nc), textureV(c, nc));
        }

        void triangle(Vector3 a, Vector3 b, Vector3 c, Vector3 na, Vector3 nb, Vector3 nc, Color color,
              float au, float av, float bu, float bv, float cu, float cv) {
            // Cone/dome tips collapse one half of a quad. Degenerate faces can produce false coplanar ray hits.
            if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() < .000001f) { return; }
            vertex(a, na, color, au, av); vertex(b, nb, color, bu, bv); vertex(c, nc, color, cu, cv);
        }

        /** Circumference/length mapping prevents projected grain stretching along curved metal. */
        void strip(Vector3 a, Vector3 b, Vector3 c, Vector3 d, Vector3 first, Vector3 second, Color color,
              float u0, float u1, float v0, float v1) {
            triangle(a, b, c, first, second, second, color, u0, v0, u1, v0, u1, v1);
            triangle(a, c, d, first, second, first, color, u0, v0, u1, v1, u0, v1);
        }

        void box(float x, float y, float z, float w, float d, float h, Color color) {
            box(x, y, z, w, d, h, color, true);
        }

        void box(float x, float y, float z, float w, float d, float h, Color color, boolean top) {
            Vector3[] p = new Vector3[8];
            for (int i = 0; i < 8; i++) {
                p[i] = v(x + ((i & 1) == 0 ? -w : w) / 2, y + ((i & 2) == 0 ? -d : d) / 2,
                      z + ((i & 4) == 0 ? -h : h) / 2);
            }
            for (int[] face : new int[][] { { 0, 2, 3, 1 }, { 4, 5, 7, 6 }, { 0, 1, 5, 4 },
                  { 2, 6, 7, 3 }, { 0, 4, 6, 2 }, { 1, 3, 7, 5 } }) {
                Vector3 n = new Vector3(p[face[1]]).sub(p[face[0]]).crs(new Vector3(p[face[2]]).sub(p[face[0]])).nor();
                if (!top && n.z > .9f) { continue; }
                quad(p[face[0]], p[face[1]], p[face[2]], p[face[3]], n, n, color);
            }
        }

        void disc(float x, float y, float z, float r, Color color, boolean up) {
            for (int i = 0; i < SIDES; i++) {
                float a = i * (float) Math.PI * 2 / SIDES, b = (i + 1) * (float) Math.PI * 2 / SIDES;
                Vector3 n = v(0, 0, up ? 1 : -1), p = v(x + r * cos(a), y + r * sin(a), z),
                      q = v(x + r * cos(b), y + r * sin(b), z);
                vertex(v(x, y, z), n, color); vertex(up ? p : q, n, color); vertex(up ? q : p, n, color);
            }
        }

        void frustum(float x, float y, float z0, float r0, float z1, float r1, Color color) {
            for (int i = 0; i < SIDES; i++) {
                float a = i * (float) Math.PI * 2 / SIDES, b = (i + 1) * (float) Math.PI * 2 / SIDES;
                Vector3 n0 = v((z1 - z0) * cos(a), (z1 - z0) * sin(a), r0 - r1).nor();
                Vector3 n1 = v((z1 - z0) * cos(b), (z1 - z0) * sin(b), r0 - r1).nor();
                Vector3 p = v(x + r0 * cos(a), y + r0 * sin(a), z0), q = v(x + r0 * cos(b), y + r0 * sin(b), z0),
                      s = v(x + r1 * cos(b), y + r1 * sin(b), z1), t = v(x + r1 * cos(a), y + r1 * sin(a), z1);
                if (z0 == z1) { quad(p, q, s, t, n0, n1, color); }
                else { strip(p, q, s, t, n0, n1, color, a * Math.max(r0, r1), b * Math.max(r0, r1), z0, z1); }
            }
        }

        void cylinder(float x, float y, float bottom, float top, float r, Color color) {
            cylinder(x, y, bottom, top, r, color, true);
        }

        void cylinder(float x, float y, float bottom, float top, float r, Color color, boolean topCap) {
            frustum(x, y, bottom, r, top, r, color);
            disc(x, y, bottom, r, color, false);
            if (topCap) { disc(x, y, top, r, color, true); }
        }

        void tube(Vector3 a, Vector3 b, float radius, Color color, int sides) {
            tube(a, b, radius, color, sides, true, true);
        }

        void tube(Vector3 a, Vector3 b, float radius, Color color, int sides, boolean startCap, boolean endCap) {
            if (a.dst2(b) < .00001f) { return; }
            sweep(List.of(a, b), radius, color, sides, startCap, endCap);
        }

        /** Adjacent bends share the same ring, so curved pipes have no gaps or buried end caps at their joints. */
        void sweep(List<Vector3> path, float radius, Color color, int sides, boolean startCap, boolean endCap) {
            Vector3[] previous = null, previousNormals = null;
            Vector3 previousU = null;
            float distance = 0;
            for (int ring = 0; ring < path.size(); ring++) {
                Vector3 center = path.get(ring);
                Vector3 tangent = ring == 0 ? new Vector3(path.get(1)).sub(center).nor()
                      : ring == path.size() - 1 ? new Vector3(center).sub(path.get(ring - 1)).nor()
                      : new Vector3(center).sub(path.get(ring - 1)).nor()
                            .add(new Vector3(path.get(ring + 1)).sub(center).nor()).nor();
                Vector3 u = new Vector3(tangent).crs(Vector3.Z);
                if (u.len2() < .01f) {
                    u = previousU == null ? new Vector3(tangent).crs(Vector3.Y)
                          : new Vector3(previousU).mulAdd(tangent, -previousU.dot(tangent));
                }
                u.nor();
                if (previousU != null && u.dot(previousU) < 0) { u.scl(-1); }
                Vector3 w = new Vector3(tangent).crs(u).nor();
                Vector3[] points = new Vector3[sides], normals = new Vector3[sides];
                for (int side = 0; side < sides; side++) {
                    float angle = side * (float) Math.PI * 2 / sides;
                    normals[side] = new Vector3(u).scl(cos(angle)).mulAdd(w, sin(angle));
                    points[side] = new Vector3(center).mulAdd(normals[side], radius);
                }
                float nextDistance = distance + (ring == 0 ? 0 : center.dst(path.get(ring - 1)));
                for (int side = 0; side < sides; side++) {
                    int next = (side + 1) % sides;
                    if (previous != null) {
                        float a = side * (float) Math.PI * 2 / sides * radius, b = (side + 1) * (float) Math.PI * 2 / sides * radius;
                        triangle(previous[side], previous[next], points[next], previousNormals[side], previousNormals[next], normals[next],
                              color, a, distance, b, distance, b, nextDistance);
                        triangle(previous[side], points[next], points[side], previousNormals[side], normals[next], normals[side],
                              color, a, distance, b, nextDistance, a, nextDistance);
                    }
                    if (ring == 0 && startCap) {
                        Vector3 n = new Vector3(tangent).scl(-1);
                        triangle(center, points[next], points[side], n, n, n, color);
                    } else if (ring == path.size() - 1 && endCap) {
                        triangle(center, points[side], points[next], tangent, tangent, tangent, color);
                    }
                }
                previous = points; previousNormals = normals; previousU = u; distance = nextDistance;
            }
        }

        void pipe(Color color, float radius, Vector3... points) {
            List<Vector3> anchors = new ArrayList<>();
            for (Vector3 point : points) {
                if (anchors.isEmpty() || !anchors.getLast().epsilonEquals(point, .001f)) { anchors.add(point); }
            }
            List<Vector3> path = new ArrayList<>();
            path.add(anchors.getFirst());
            for (int i = 1; i < anchors.size() - 1; i++) {
                Vector3 corner = anchors.get(i);
                Vector3 incoming = new Vector3(anchors.get(i - 1)).sub(corner);
                Vector3 outgoing = new Vector3(anchors.get(i + 1)).sub(corner);
                float bend = Math.min(radius * 2, Math.min(incoming.len(), outgoing.len()) * .3f);
                Vector3 a = new Vector3(corner).mulAdd(incoming.nor(), bend);
                Vector3 b = new Vector3(corner).mulAdd(outgoing.nor(), bend);
                path.add(a);
                for (int j = 1; j <= 4; j++) {
                    float t = j / 4f;
                    path.add(new Vector3(a).scl((1 - t) * (1 - t)).mulAdd(corner, 2 * (1 - t) * t).mulAdd(b, t * t));
                }
            }
            path.add(anchors.getLast());
            for (int i = path.size() - 1; i > 0; i--) {
                if (path.get(i).epsilonEquals(path.get(i - 1), .001f)) { path.remove(i); }
            }
            if (path.size() > 1) { sweep(path, radius, color, 8, true, true); }
        }

        ModelData data() {
            var data = new ModelData();
            data.id = "heavy-industrial";
            var mesh = new ModelMesh();
            mesh.id = "industrial"; mesh.vertices = vertices.toArray(); mesh.parts = new ModelMeshPart[2];
            mesh.attributes = new VertexAttribute[] { VertexAttribute.Position(), VertexAttribute.Normal(),
                  VertexAttribute.ColorUnpacked(), VertexAttribute.TexCoords(0) };
            data.meshes.add(mesh);
            var node = new ModelNode();
            node.id = "industrial"; node.meshId = mesh.id;
            node.parts = new ModelNodePart[2];
            for (int i = 0; i < 2; i++) {
                var part = new ModelMeshPart();
                part.id = i == 0 ? "paint" : "steel";
                part.primitiveType = GL20.GL_TRIANGLES;
                part.indices = (i == 0 ? paintIndices : metalIndices).toArray();
                mesh.parts[i] = part;
                var material = new ModelMaterial();
                material.id = part.id; material.diffuse = new Color(Color.WHITE);
                data.materials.add(material);
                var nodePart = new ModelNodePart();
                nodePart.materialId = material.id; nodePart.meshPartId = part.id;
                node.parts[i] = nodePart;
            }
            node.children = new ModelNode[0];
            data.nodes.add(node);
            return data;
        }
    }
}
