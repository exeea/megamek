/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** Sweeps the free-flight eye against the same finished terrain triangles used by rendering and picking. */
final class BoardCameraCollision {
    private static final float EPSILON = .001f;

    private BoardCameraCollision() { }

    /** Move a sphere enclosing the near plane, sliding along contacted terrain instead of tunnelling through it. */
    static void move(BoardScene scene, Function<Coords, BoardTacticalGeometry.Surface> surfaces,
          Vector3 eye, Vector3 displacement, float radius) {
        if (scene == null) { eye.add(displacement); return; }
        // Entering flight or editing terrain underneath the eye can begin inside the ground. Recover above it.
        List<BoardSurface.Face> faces = faces(scene, surfaces, eye, displacement, radius);
        float ceiling = eye.z;
        for (var face : faces) {
            ceiling = Math.max(ceiling, Math.max(face.a().z, Math.max(face.b().z, face.c().z)) + radius + EPSILON);
        }
        Sweep support = new Sweep(new Vector3(eye.x, eye.y, ceiling), new Vector3(0, 0, eye.z - ceiling), radius);
        for (var face : faces) { support.triangle(face); }
        if (support.time < 1) { eye.z = ceiling + (eye.z - ceiling) * support.time + EPSILON; }
        Vector3 remaining = displacement.cpy();
        for (int contact = 0; contact < 3 && !remaining.isZero(EPSILON); contact++) {
            if (contact > 0) { faces = faces(scene, surfaces, eye, remaining, radius); }
            Sweep sweep = new Sweep(eye, remaining, radius);
            for (var face : faces) { sweep.triangle(face); }
            if (sweep.time >= 1) {
                eye.add(remaining);
                break;
            }
            float travel = Math.max(0, sweep.time - EPSILON / remaining.len());
            eye.mulAdd(remaining, travel);
            remaining.scl(1 - travel);
            float inward = remaining.dot(sweep.normal);
            if (inward < 0) { remaining.mulAdd(sweep.normal, -inward); }
        }
    }

    private static List<BoardSurface.Face> faces(BoardScene scene, Function<Coords, BoardTacticalGeometry.Surface> surfaces,
          Vector3 eye, Vector3 displacement, float radius) {
        float reach = radius + BoardGeometry.WIDTH + 2 * BoardRelief.overhang();
        List<BoardSurface.Face> faces = new ArrayList<>();
        float endX = eye.x + displacement.x, endY = eye.y + displacement.y;
        int left = Math.max(0, (int) Math.floor((Math.min(eye.x, endX) - reach) / (BoardGeometry.WIDTH * .75f)));
        int right = Math.min(scene.width() - 1,
              (int) Math.ceil((Math.max(eye.x, endX) + reach) / (BoardGeometry.WIDTH * .75f)));
        int top = Math.max(0, (int) Math.floor((-Math.max(eye.y, endY) - reach) / BoardGeometry.HEIGHT));
        int bottom = Math.min(scene.height() - 1,
              (int) Math.ceil((-Math.min(eye.y, endY) + reach) / BoardGeometry.HEIGHT));
        for (int x = left; x <= right; x++) {
            for (int y = top; y <= bottom; y++) {
                var surface = surfaces.apply(new Coords(x, y));
                if (surface == null) { continue; }
                faces.addAll(surface.faces());
                faces.addAll(surface.walls());
                faces.addAll(surface.water());
            }
        }
        return faces;
    }

    /** Earliest contact with a triangle's face, edge cylinders or vertex spheres over this whole displacement. */
    private static final class Sweep {
        private final Vector3 origin, movement;
        private final float radius;
        private final Vector3 normal = new Vector3();
        private float time = 1;

        Sweep(Vector3 origin, Vector3 movement, float radius) {
            this.origin = origin;
            this.movement = movement;
            this.radius = radius;
        }

        void triangle(BoardSurface.Face face) {
            Vector3 a = face.a(), b = face.b(), c = face.c();
            // Reject triangles outside the swept sphere before doing any contact arithmetic.
            for (int axis = 0; axis < 3; axis++) {
                float start = component(origin, axis), end = start + component(movement, axis);
                float low = Math.min(component(a, axis), Math.min(component(b, axis), component(c, axis)));
                float high = Math.max(component(a, axis), Math.max(component(b, axis), component(c, axis)));
                if (Math.max(start, end) + radius < low || Math.min(start, end) - radius > high) { return; }
            }
            Vector3 faceNormal = b.cpy().sub(a).crs(c.cpy().sub(a)).nor();
            if (faceNormal.isZero()) { return; }
            float height = origin.cpy().sub(a).dot(faceNormal);
            if (height < 0) { faceNormal.scl(-1); height = -height; }
            float speed = movement.dot(faceNormal);
            if (speed < -EPSILON) {
                float t = Math.max(0, (radius - height) / speed);
                Vector3 point = origin.cpy().mulAdd(movement, t).mulAdd(faceNormal, -(height + speed * t));
                if (t < time && Intersector.isPointInTriangle(point, a, b, c)) { accept(t, faceNormal); }
            }
            edge(a, b);
            edge(b, c);
            edge(c, a);
            vertex(a);
            vertex(b);
            vertex(c);
        }

        private void edge(Vector3 a, Vector3 b) {
            Vector3 edge = b.cpy().sub(a), offset = origin.cpy().sub(a);
            float squared = edge.len2();
            if (squared < EPSILON * EPSILON) { return; }
            float start = offset.dot(edge) / squared, speed = movement.dot(edge) / squared;
            Vector3 radial = offset.mulAdd(edge, -start), velocity = movement.cpy().mulAdd(edge, -speed);
            float t = root(radial, velocity);
            float along = start + speed * t;
            if (t < time && along >= 0 && along <= 1) { accept(t, radial.mulAdd(velocity, t).nor()); }
        }

        private void vertex(Vector3 point) {
            Vector3 offset = origin.cpy().sub(point);
            float t = root(offset, movement);
            if (t < time) { accept(t, offset.mulAdd(movement, t).nor()); }
        }

        /** First approaching intersection of |offset + t * velocity| with the camera radius. */
        private float root(Vector3 offset, Vector3 velocity) {
            double a = velocity.len2(), b = offset.dot(velocity), c = offset.len2() - radius * radius;
            if (a < EPSILON * EPSILON || b >= 0) { return Float.POSITIVE_INFINITY; }
            if (c <= 0) { return 0; }
            double discriminant = b * b - a * c;
            // This form avoids cancellation when the camera starts close to contact.
            return discriminant < 0 ? Float.POSITIVE_INFINITY : (float) (c / (-b + Math.sqrt(discriminant)));
        }

        private void accept(float t, Vector3 outward) {
            if (t >= 0 && t < time && movement.dot(outward) < -EPSILON) {
                time = t;
                normal.set(outward);
            }
        }

        private static float component(Vector3 vector, int axis) {
            return axis == 0 ? vector.x : axis == 1 ? vector.y : vector.z;
        }
    }
}
