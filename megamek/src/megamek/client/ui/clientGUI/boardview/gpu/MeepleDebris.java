/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;

/**
 * A broken meeple's pieces as rigid bodies thrown by its explosion: away from the hits and out from its middle, under
 * a tabletop gravity, bouncing off the ground and scenery under them until they come to rest. They touch the board
 * through a height field sampled from it as they reach it, not each other. Presentation only.
 */
final class MeepleDebris {
    /** World units per second squared: faster than life, as a miniature's fall reads on the table. */
    private static final float GRAVITY = 260;
    private static final float STEP = 1 / 120f;
    /** Steps one frame may take: after a hitch the pieces slow down instead of passing through the ground. */
    private static final int MAX_STEPS = 12;
    /** However the pieces bounce or catch, they have come to rest this long after the explosion. */
    static final float MAX_SECONDS = 4;
    private static final float RESTITUTION = .3f, FRICTION = .8f;
    /**
     * How fast a piece touching the ground loses its speed and its spin, per second: rubble skids rather than rolls on,
     * but a slab on its edge still topples.
     */
    private static final float GROUND_DRAG = 4, GROUND_SPIN_DRAG = 3;
    /** A piece touching the ground this slowly, in world units and radians per second, for this long settles. */
    private static final float SLEEP_SPEED = 4, SLEEP_SPIN = .8f, SLEEP_SECONDS = .35f;
    /** The share of pieces thrown into the hexes around; the rest land on the meeple's own ground. */
    private static final float THROWN = .35f;
    /** The height field's spacing, in world units. */
    private static final float CELL = 3;

    /** The top of what lies at a world position (ground, roof or rock; foliage gives way), or NaN off the board. */
    interface Ground {
        float height(float x, float y);
    }

    private static final class Body {
        final Node node;
        /** Where its middle was before the explosion, and its outermost points around that middle. */
        final Vector3 rest;
        final Vector3[] points;
        final float inertia;
        final Vector3 position, velocity = new Vector3(), spin = new Vector3();
        final Quaternion orientation = new Quaternion();
        float still;
        boolean asleep;

        Body(Node node, Vector3 rest, Vector3[] points) {
            this.node = node;
            this.rest = rest;
            this.points = points;
            position = new Vector3(rest);
            float radius = 1;
            for (Vector3 point : points) { radius = Math.max(radius, point.len()); }
            inertia = .4f * radius * radius;
        }
    }

    private final ModelInstance instance;
    private final Matrix4 frame, inverse;
    private final List<Body> bodies = new ArrayList<>();
    /** The ground the meeple stood on, where the board gives none. */
    private final float floor;
    /** How far the pieces sink to disappear. */
    final float depth;
    private final Map<Long, Float> heights = new HashMap<>();
    private float seconds, pending;
    private float settled = Float.NaN;

    /**
     * Throws the pieces under {@code pieces} from where they stand in {@code instance}.
     *
     * @param away horizontal, away from the hits; null to burst out evenly
     */
    MeepleDebris(ModelInstance instance, Node pieces, Vector3 away, int seed) {
        this.instance = instance;
        instance.calculateTransforms();
        frame = new Matrix4(instance.transform).mul(pieces.globalTransform);
        inverse = new Matrix4(frame).inv();
        var all = new BoundingBox().inf();
        for (Node piece : pieces.getChildren()) {
            piece.isAnimated = true;
            var box = new BoundingBox().inf();
            var points = MeepleShatter.supports(piece).stream().map(point -> point.mul(frame)).toList();
            points.forEach(box::ext);
            all.ext(box);
            var middle = box.getCenter(new Vector3());
            bodies.add(new Body(piece, middle, points.stream().map(point -> point.sub(middle)).toArray(Vector3[]::new)));
        }
        floor = all.isValid() ? all.min.z : 0;
        depth = all.isValid() ? all.getDepth() + 1 : 1;
        var middle = all.isValid() ? all.getCenter(new Vector3()) : new Vector3();
        for (int index = 0; index < bodies.size(); index++) {
            Body body = bodies.get(index);
            int noise = seed * 977 + index * 131;
            var out = new Vector3(body.rest.x - middle.x, body.rest.y - middle.y, 0);
            if (out.len2() < 1e-4f) { out.set(Vector3.X).rotate(Vector3.Z, UnitAttack.noise(noise) * 360); }
            out.nor();
            boolean thrown = UnitAttack.noise(noise + 1) < THROWN;
            if (away != null) {
                // Thrown pieces fly within a fan away from the hits; the rest burst out, leaning that way.
                if (thrown) { out.set(away).rotate(Vector3.Z, (UnitAttack.noise(noise + 2) - .5f) * 90); }
                else { out.mulAdd(away, .8f).nor(); }
            }
            float along = thrown ? 55 + 40 * UnitAttack.noise(noise + 3) : 12 + 28 * UnitAttack.noise(noise + 3);
            float up = thrown ? 80 + 40 * UnitAttack.noise(noise + 4) : 35 + 45 * UnitAttack.noise(noise + 4);
            body.velocity.set(out).scl(along).add(0, 0, up);
            body.spin.set(UnitAttack.noise(noise + 5) - .5f, UnitAttack.noise(noise + 6) - .5f, UnitAttack.noise(noise + 7) - .5f)
                  .nor().scl(3 + 9 * UnitAttack.noise(noise + 8));
        }
    }

    /** Runs the pieces on by {@code delta} seconds, in fixed steps. */
    void advance(float delta, Ground ground) {
        pending = Math.min(pending + Math.max(0, delta), STEP * MAX_STEPS);
        while (pending >= STEP) {
            pending -= STEP;
            step(ground);
        }
    }

    /** The seconds after the explosion at which every piece had come to rest, or NaN while some still move. */
    float settled() { return settled; }

    private void step(Ground ground) {
        if (!Float.isNaN(settled)) { return; }
        seconds += STEP;
        boolean moving = false;
        for (Body body : bodies) {
            // The safeguard: whatever a piece is caught in, it stops.
            if (body.asleep || seconds >= MAX_SECONDS) {
                body.asleep = true;
                continue;
            }
            body.velocity.z -= GRAVITY * STEP;
            body.position.mulAdd(body.velocity, STEP);
            float angle = body.spin.len() * STEP;
            if (angle > 0) {
                body.orientation.mulLeft(new Quaternion(new Vector3(body.spin).nor(), angle * MathUtils.radiansToDegrees)).nor();
            }
            boolean contact = collide(body, ground);
            if (contact) {
                body.velocity.scl(1 - GROUND_DRAG * STEP);
                body.spin.scl(1 - GROUND_SPIN_DRAG * STEP);
            }
            body.still = contact && body.velocity.len() < SLEEP_SPEED && body.spin.len() < SLEEP_SPIN ? body.still + STEP : 0;
            body.asleep = body.still >= SLEEP_SECONDS;
            moving |= !body.asleep;
        }
        if (!moving) { settled = seconds; }
    }

    /** The deepest point below the ground pushes the piece out along the ground's normal there, and bounces it. */
    private boolean collide(Body body, Ground ground) {
        float deepest = 0;
        Vector3 arm = null;
        var turned = new Vector3();
        for (Vector3 point : body.points) {
            body.orientation.transform(turned.set(point));
            float below = height(body.position.x + turned.x, body.position.y + turned.y, ground) - body.position.z - turned.z;
            if (below > deepest) {
                deepest = below;
                arm = new Vector3(turned);
            }
        }
        if (arm == null) { return false; }
        var normal = normal(body.position.x + arm.x, body.position.y + arm.y, ground);
        // A wall's top is far above the point: leave it a cell at a time, sideways.
        body.position.mulAdd(normal, Math.min(deepest, CELL));
        var contact = new Vector3(body.spin).crs(arm).add(body.velocity);
        float into = contact.dot(normal);
        if (into >= 0) { return true; }
        var lever = new Vector3(arm).crs(normal);
        float push = -(1 + RESTITUTION) * into / (1 + lever.len2() / body.inertia);
        body.velocity.mulAdd(normal, push);
        body.spin.mulAdd(lever, push / body.inertia);
        var slide = contact.mulAdd(normal, -into);
        float speed = slide.len();
        if (speed > 1e-4f) {
            slide.scl(1 / speed);
            var twist = new Vector3(arm).crs(slide);
            float drag = Math.min(FRICTION * push, speed / (1 + twist.len2() / body.inertia));
            body.velocity.mulAdd(slide, -drag);
            body.spin.mulAdd(twist, -drag / body.inertia);
        }
        return true;
    }

    /** The board's height at a world position, bilinear between samples taken the first time a piece comes near. */
    private float height(float x, float y, Ground ground) {
        float column = x / CELL, row = y / CELL;
        int left = MathUtils.floor(column), bottom = MathUtils.floor(row);
        float across = column - left, up = row - bottom;
        return MathUtils.lerp(MathUtils.lerp(sample(left, bottom, ground), sample(left + 1, bottom, ground), across),
              MathUtils.lerp(sample(left, bottom + 1, ground), sample(left + 1, bottom + 1, ground), across), up);
    }

    private float sample(int column, int row, Ground ground) {
        return heights.computeIfAbsent(((long) column << 32) ^ (row & 0xffffffffL), key -> {
            float height = ground.height(column * CELL, row * CELL);
            return Float.isFinite(height) ? height : floor;
        });
    }

    private Vector3 normal(float x, float y, Ground ground) {
        float half = CELL * .5f;
        return new Vector3(height(x - half, y, ground) - height(x + half, y, ground),
              height(x, y - half, ground) - height(x, y + half, ground), CELL).nor();
    }

    /** Draws the pieces where they are, {@code sink} world units down into the ground. */
    void pose(float sink) {
        var motion = new Matrix4();
        for (Body body : bodies) {
            motion.setToTranslation(body.position.x, body.position.y, body.position.z - sink).rotate(body.orientation)
                  .translate(-body.rest.x, -body.rest.y, -body.rest.z);
            body.node.localTransform.set(inverse).mul(motion).mul(frame);
        }
        instance.calculateTransforms();
    }
}
