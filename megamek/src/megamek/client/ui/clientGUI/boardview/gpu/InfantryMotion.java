/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;

/** Cosmetic formation placement. UnitMotion alone owns route/time; these are children, never transported Entities. */
final class InfantryMotion {
    record Step(float gait, float distance, float standing) { }
    private static final float DEPART_END = .22f;
    private static final float PARK_START = .72f;
    /** World units per second a member walks to a new spot in its hex, and the distance its gait eases over. */
    private static final float WALK_SPEED = 14;
    private static final float WALK_EASE = 2;
    private final Map<String, Member> members = new LinkedHashMap<>();
    private boolean walking;
    private float layoutScale = Float.NaN;
    private float[] layoutRoom = InfantryFootprint.NO_STEPS;
    /** The standing formation's fitted layout, and the unit scale and terrain rim it was worked out for. */
    private InfantryFootprint.Layout restLayout;
    private float restLayoutScale = Float.NaN;
    private float[] restLayoutRoom = InfantryFootprint.NO_STEPS;
    private BoardSurface roughSurface;
    private float[] roughLayout;
    private final Map<String, Vector3> roughOffsets = new LinkedHashMap<>();
    /**
     * Where troopers resting in the unit's hex stand this frame, and where its vehicles park ({@link BoardGroundCut});
     * none to keep their layout.
     */
    private List<BoardGroundCut.Stand> stands = List.of(), troopStands = List.of(), vehicleStands = List.of();
    /** The unit's family, which numbers its figures ({@link InfantryVisual#slot}). */
    private UnitFamilyScale family;
    /** The vehicles parked at a vehicle stand this frame, by container. */
    private final java.util.Set<String> parked = new java.util.HashSet<>();

    private static final class Member {
        final UnitRig rig;
        final Node node;
        final Vector3 rest, scale, door;
        final BoundingBox footprint;
        final float restHeading;
        final List<NodePart> visibleParts = new ArrayList<>();
        final Vector3 start = new Vector3();
        final Vector3 goal = new Vector3();
        final Vector3 lastDrivePosition = new Vector3();
        float drivenDistance;
        float heading, startHeading, goalHeading;
        long sequence = -1;
        boolean reversing;
        Step step;
        float verticalOffset;
        /** Where a resting member stands while it walks to its spot, and how far it has walked there. */
        final Vector3 shown = new Vector3();
        boolean rested;
        int age;
        float walked;

        Member(UnitRig rig, Node node, Node rest, Member previous) {
            this.rig = rig;
            this.node = node;
            this.rest = new Vector3(rest.translation);
            scale = new Vector3(rest.scale);
            var shape = rest.copy();
            shape.translation.setZero();
            shape.rotation.idt();
            shape.calculateTransforms(true);
            footprint = UnitBounds.subtree(shape);
            heading = -rest.rotation.getAngleAround(Vector3.Z);
            restHeading = heading;
            Node marker = rig.joints().containsKey("boarding")
                  ? UnitAnimator.find(rest.getChildren(), rig.joints().get("boarding")) : null;
            door = marker == null ? new Vector3(0, -15, 0)
                  : marker.globalTransform.getTranslation(new Vector3()).mul(rest.globalTransform.cpy().inv());
            start.set(this.rest);
            goal.set(this.rest);
            visibleParts(node, visibleParts);
            if (previous != null) {
                start.set(previous.start);
                goal.set(previous.goal);
                heading = previous.heading;
                startHeading = previous.startHeading;
                goalHeading = previous.goalHeading;
                sequence = previous.sequence;
                reversing = previous.reversing;
                lastDrivePosition.set(previous.lastDrivePosition);
                drivenDistance = previous.drivenDistance;
                node.translation.set(previous.node.translation);
                shown.set(previous.shown);
                age = previous.age;
                rested = previous.rested;
                walked = previous.walked;
            }
        }

        private static void visibleParts(Node node, List<NodePart> result) {
            node.parts.forEach(part -> { if (part.enabled) { result.add(part); } });
            node.getChildren().forEach(child -> visibleParts(child, result));
        }

        void visible(boolean visible) {
            visibleParts.forEach(part -> part.enabled = visible);
        }

        void orient(float value) {
            heading = value;
            node.rotation.set(Vector3.Z, -value);
        }

        Vector3 doorway() {
            return new Vector3(door).scl(scale).rotate(Vector3.Z, -heading).add(node.translation);
        }

        /** The authored position with the whole formation fitted to the available plateau. */
        Vector3 layout(InfantryFootprint.Layout layout) {
            return layout.place(rest);
        }

        /** The outline this member is fitted by: troopers turn as they watch, vehicles keep their heading. */
        Polygon outline(float heading) {
            return InfantryFootprint.outline(footprint, heading, rig.trooper());
        }
    }

    void bind(GpuUnitModel model, ModelInstance placed) {
        Map<String, Member> previous = new LinkedHashMap<>(members);
        members.clear();
        restLayoutScale = Float.NaN;
        roughSurface = null;
        for (var rig : model.rigs()) {
            if (rig.container() != null && (rig.trooper() || rig.transport())) {
                var rest = model.instance.getNode(rig.container());
                members.put(rig.container(), new Member(rig, placed.getNode(rig.container()), rest, previous.get(rig.container())));
            }
        }
    }

    Step step(String member) {
        Member pose = members.get(member);
        return pose == null ? null : pose.step;
    }

    float forward(String member) {
        var pose = members.get(member);
        return pose != null && pose.reversing ? -1 : 1;
    }

    float drivenDistance(String member) {
        var pose = members.get(member);
        return pose == null ? 0 : pose.drivenDistance;
    }

    float verticalOffset(String member) {
        var pose = members.get(member);
        return pose == null ? 0 : pose.verticalOffset;
    }

    boolean isEmpty() {
        return members.isEmpty();
    }

    /**
     * @param room     the world units that the steps on each edge of the unit's hex, or the sectors of other troops
     *                 sharing it, take from its level ground, which the formation keeps clear of;
     *                 {@link InfantryFootprint#NO_STEPS} for a hex level to its edges and a formation alone in it
     * @param stands   where troopers resting in the unit's hex stand, facing out, as a trench's fire bays; none to keep
     *                 the formation's own layout
     * @param crowds   the view's packing of the hexes troops share with other units, which members join at rest
     * @param seconds  the frame's animation time, which members spend walking to new spots
     * @param instant  whether members take new spots at once
     */
    void apply(GpuUnitModel model, BoardScene.Unit unit, UnitMotion.Sample motion, float[] room,
          List<BoardGroundCut.Stand> stands, HexCrowds crowds, float seconds, boolean instant) {
        family = unit.model() == null || unit.model().state() == null ? null : unit.model().state().structure().family();
        if (stands != this.stands) {
            this.stands = stands;
            troopStands = stands.stream().filter(stand -> stand.kind() == BoardGroundCut.Kind.TROOP).toList();
            vehicleStands = stands.stream().filter(stand -> stand.kind() == BoardGroundCut.Kind.VEHICLE).toList();
        }
        position(model, unit, motion, room);
        settle(unit, motion, crowds, model.horizontalScale(unit), room, seconds, instant);
    }

    private void position(GpuUnitModel model, BoardScene.Unit unit, UnitMotion.Sample motion, float[] room) {
        var travel = motion.boarding();
        float scale = model.horizontalScale(unit);
        var vehicles = members.values().stream().filter(member -> member.rig.transport()).toList();
        boolean fitGoals = travel != null && (scale != layoutScale || !Arrays.equals(room, layoutRoom)
              || members.values().stream().anyMatch(member -> member.sequence != travel.sequence()));
        layoutScale = scale;
        if (fitGoals) { layoutRoom = room.clone(); }
        if (travel != null && members.values().stream().allMatch(member -> member.sequence < 0)) {
            var layout = restLayout(scale, room);
            members.values().forEach(member -> member.node.translation.set(member.layout(layout)));
            avoidVehicles(vehicles, false, scale, room);
        }
        for (Member member : members.values()) {
            if (travel != null && member.sequence != travel.sequence()) {
                member.sequence = travel.sequence();
                member.start.set(member.node.translation);
                member.lastDrivePosition.set(travel.position(0)).scl(1 / scale)
                      .add(member.start);
                member.drivenDistance = 0;
                member.startHeading = member.heading;
                member.reversing = member.rig.transport()
                      && Math.abs(UpperBodyTurn.shortestTurn(travel.heading(0) - member.heading)) > 110;
                member.goal.set(member.rest).rotate(Vector3.Z, -travel.arrivalHeading());
                member.goalHeading = member.rig.transport()
                      ? travel.arrivalHeading() + Math.signum(member.rest.x) * 40 + (member.reversing ? 180 : 0)
                      : watchHeading(unit.id(), member.rig.container(), member.goal, travel.destination());
            }
            if (fitGoals) {
                member.goal.set(member.rest).rotate(Vector3.Z, -travel.arrivalHeading());
            }
        }
        if (fitGoals) {
            // Keep the captured parking positions through unloading and casualty/material rebinds.
            var layout = layout(true, scale, room);
            members.values().forEach(member -> member.goal.set(layout.place(member.goal)));
            avoidVehicles(vehicles, true, scale, room);
        }
        for (Member member : members.values()) {
            member.node.scale.set(member.scale);
            member.visible(true);
            member.step = null;
            member.verticalOffset = 0;
        }
        if (travel == null || vehicles.isEmpty()) {
            var layout = restLayout(scale, room);
            for (Member member : members.values()) {
                if (member.rig.trooper()) {
                    var individual = motion.member(unit.id(), member.rig.container());
                    var stand = stand(member, 0);
                    float target = stand != null ? stand.heading() : watchHeading(unit.id(), member.rig.container(),
                          member.rest, unit.location().coords().hashCode());
                    if (individual.moving() || individual.progress() != 0 || motion.group() == null) {
                        member.orient(individual.moving() ? individual.heading() : MathUtils.lerpAngleDeg(individual.heading(), target,
                              Math.min(1, individual.settledSeconds() / UnitMotion.FORMATION_SETTLE_SECONDS)));
                    }
                    member.node.translation.set(member.layout(layout));
                    if (motion.group() != null) {
                        var offset = motion.group().offset(unit.id(), member.rig.container());
                        member.verticalOffset = offset.z / model.verticalScale(scale, unit);
                        member.node.translation.add(offset.x / scale, offset.y / scale, member.verticalOffset);
                    }
                } else if (motion.moving()) {
                    member.orient(motion.heading());
                } else {
                    member.node.translation.set(member.layout(layout));
                }
            }
            avoidVehicles(vehicles, false, scale, room);
            return;
        }
        for (Member vehicle : vehicles) {
            vehicle(vehicle, travel, scale, restLayout(scale, room));
        }
        int index = 0;
        for (Member member : members.values()) {
            if (member.rig.trooper()) {
                // Stable slot IDs keep an existing passenger on its vehicle when another trooper disappears.
                int slot = Integer.parseInt(member.rig.container().substring("trooper-".length()));
                Member vehicle = vehicles.get(Math.floorMod(slot, vehicles.size()));
                passenger(member, vehicle, travel, index++);
            }
        }
    }

    /**
     * A resting formation joins the crowd of a hex it shares with other units, and walks to new spots instead of
     * jumping there, as when another unit enters or leaves the hex.
     */
    private void settle(BoardScene.Unit unit, UnitMotion.Sample motion, HexCrowds crowds, float scale, float[] room,
          float seconds, boolean instant) {
        var travel = motion.boarding();
        boolean still = !motion.moving()
              && (travel == null || travel.stage() == UnitMotion.Stage.UNLOAD && travel.progress() >= 1);
        Vector3 center = BoardGeometry.center(unit.location().coords(), 0);
        walking = false;
        parked.clear();
        int vehicle = 0;
        for (Member member : members.values()) {
            // Members of a staggered group land one after another, after the formation's own movement ended.
            boolean resting = still && !(member.rig.trooper() && motion.member(unit.id(), member.rig.container()).moving());
            Vector3 spot = new Vector3(member.node.translation);
            // A member that has just arrived stands where its movement ended, then walks only any further way.
            if (resting && !member.rested) { member.shown.set(spot); }
            member.rested = resting;
            var stand = resting ? stand(member, member.rig.transport() ? vehicle++ : 0) : null;
            if (stand != null) {
                spot.set(stand.x() / scale, stand.y() / scale, spot.z);
                if (stand.kind() == BoardGroundCut.Kind.VEHICLE) {
                    // A transport parks as its stand faces, beside the next, in from its pit's ramp.
                    member.orient(stand.heading());
                    parked.add(member.rig.container());
                }
            }
            if (resting) {
                // A trooper takes the ground of its watch heading, which it keeps once there, not the room it would
                // need to turn.
                float[] ground = InfantryFootprint.polygon(member.footprint, member.heading).getTransformedVertices().clone();
                for (int v = 0; v < ground.length; v++) { ground[v] *= scale; }
                crowds.register(unit.location().coords(), new HexCrowds.Figure(unit.id(), member.rig.container(),
                      new Vector2(center.x + spot.x * scale, center.y + spot.y * scale), new Polygon(ground),
                      member.rig.transport(), room));
                var packed = crowds.spot(unit.id(), member.rig.container());
                if (packed != null) { spot.set((packed.x - center.x) / scale, (packed.y - center.y) / scale, spot.z); }
            }
            // The first frames place a new formation, already packed into a crowded hex, without walking there.
            if (!resting || instant || member.age < 2) {
                member.shown.set(spot);
                member.walked = 0;
            } else {
                walk(member, spot, seconds * WALK_SPEED / scale);
            }
            member.age = Math.min(2, member.age + 1);
            member.node.translation.set(member.shown);
        }
    }

    /**
     * A member's stand in its hex: a trooper's that of its formation slot ({@link InfantryVisual#slot}), as its own
     * fighting hole, or one by its slot where there is none; the formation's {@code vehicle}th vehicle's in turn while
     * there are vehicle stands left; null for none.
     */
    private BoardGroundCut.Stand stand(Member member, int vehicle) {
        if (member.rig.transport()) { return vehicle < vehicleStands.size() ? vehicleStands.get(vehicle) : null; }
        if (troopStands.isEmpty() || !member.rig.trooper()) { return null; }
        int slot = InfantryVisual.slot(family, Integer.parseInt(member.rig.container().substring("trooper-".length())));
        return troopStands.stream().filter(stand -> stand.number() == slot).findFirst()
              .orElse(troopStands.get(Math.floorMod(slot, troopStands.size())));
    }

    /** Whether the member {@code container} is a vehicle parked at a vehicle stand, as in a strongpoint's pit. */
    boolean parked(String container) {
        return parked.contains(container);
    }

    /** One frame's walk toward the spot, with the gait eased in and out and the walker facing its way. */
    private void walk(Member member, Vector3 spot, float stride) {
        float remaining = member.shown.dst(spot);
        if (remaining <= stride || remaining < .01f) {
            member.shown.set(spot);
            member.walked = 0;
            return;
        }
        float direction = MathUtils.atan2(spot.x - member.shown.x, spot.y - member.shown.y) * MathUtils.radiansToDegrees;
        member.shown.lerp(spot, stride / remaining);
        member.walked += stride;
        float ease = Math.min(1, Math.min(member.walked, remaining - stride) / WALK_EASE);
        walking = true;
        if (member.rig.trooper()) {
            member.orient(MathUtils.lerpAngleDeg(member.heading, direction, ease));
            member.step = new Step(ease, member.walked, ease);
        }
    }

    /**
     * The standing formation's layout, worked out again only when the unit scale, the members or the terrain rim
     * around the hex change.
     */
    private InfantryFootprint.Layout restLayout(float scale, float[] room) {
        if (scale != restLayoutScale || !Arrays.equals(room, restLayoutRoom)) {
            restLayoutScale = scale;
            restLayoutRoom = room.clone();
            restLayout = layout(false, scale, room);
        }
        return restLayout;
    }

    /** Fit the authored layout, or the parking goals, as one shape to the available ground. */
    private InfantryFootprint.Layout layout(boolean goal, float scale, float[] room) {
        List<Vector3> positions = new ArrayList<>();
        List<Polygon> outlines = new ArrayList<>();
        for (var member : members.values()) {
            positions.add(goal ? member.goal : member.rest);
            outlines.add(member.outline(goal ? member.goalHeading : member.restHeading));
        }
        return InfantryFootprint.layout(positions, outlines, scale, room);
    }

    private void avoidVehicles(List<Member> vehicles, boolean goal, float scale, float[] room) {
        if (vehicles.isEmpty()) { return; }
        var obstacles = vehicles.stream().map(vehicle -> {
            var shape = vehicle.outline(goal ? vehicle.goalHeading : vehicle.heading);
            var position = goal ? vehicle.goal : vehicle.node.translation;
            shape.setPosition(position.x, position.y);
            return shape;
        }).toList();
        for (var member : members.values()) {
            if (member.rig.trooper()) {
                InfantryFootprint.avoid(goal ? member.goal : member.node.translation,
                      member.outline(goal ? member.goalHeading : member.heading), obstacles, scale, room);
            }
        }
    }

    /** Standing infantry seek gaps in the rendered Rough mesh; vehicles keep their fitted positions. */
    boolean roughGround(ModelInstance instance, BoardScene scene, BoardScene.Unit unit, UnitMotion.Sample motion,
          BoardSurface.Cache surfaces, float[] room) {
        if (members.isEmpty() || walking || motion.moving() || motion.boarding() != null || motion.airborne(unit)) { return false; }
        BoardScene.Tile tile = scene.tile(unit.location().coords());
        if (tile == null || tile.liquid().present() || unit.location().elevation() != tile.elevation()) { return false; }
        BoardSurface surface = surfaces.get(scene, tile);
        if (surface.rough.isEmpty()) { return false; }
        // Watching headings cannot shuffle troopers: their outlines enclose every heading. Terrain edits, scale,
        // placement, parking or membership changes do invalidate the derived layout.
        float[] layout = Arrays.copyOf(instance.transform.val, 22 + members.size() * 4);
        System.arraycopy(room, 0, layout, 16, 6);
        int index = 22;
        for (Member member : members.values()) {
            layout[index++] = member.node.translation.x;
            layout[index++] = member.node.translation.y;
            layout[index++] = member.node.translation.z;
            layout[index++] = member.rig.transport() ? member.heading : 0;
        }
        if (surface != roughSurface || !Arrays.equals(layout, roughLayout)) {
            roughSurface = surface;
            roughLayout = layout;
            roughOffsets.clear();
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            List<Polygon> rocks = new ArrayList<>();
            for (var face : surface.rough) {
                if (Math.max(face.a().z, Math.max(face.b().z, face.c().z)) <= center.z) { continue; }
                Polygon polygon = new Polygon(new float[] { face.a().x - center.x, face.a().y - center.y,
                      face.b().x - center.x, face.b().y - center.y, face.c().x - center.x, face.c().y - center.y });
                if (Math.abs(polygon.area()) > .00001f) { rocks.add(polygon); }
            }
            Map<Member, Polygon> shapes = new LinkedHashMap<>();
            for (Member member : members.values()) { shapes.put(member, footprint(member, instance.transform, center)); }
            Matrix4 local = new Matrix4(instance.transform).inv();
            for (var entry : members.entrySet()) {
                Member member = entry.getValue();
                if (!member.rig.trooper()) { continue; }
                Vector3 original = new Vector3(member.node.translation).mul(instance.transform).sub(center);
                Vector3 position = new Vector3(original);
                List<Polygon> obstacles = new ArrayList<>(rocks);
                shapes.forEach((other, shape) -> { if (other != member) { obstacles.add(shape); } });
                Polygon shape = shapes.get(member);
                InfantryFootprint.avoidRough(position, shape, obstacles, 1, room);
                shape.setPosition(position.x, position.y);
                Vector3 offset = position.add(center).mul(local).sub(member.node.translation);
                offset.z = 0;
                roughOffsets.put(entry.getKey(), offset);
            }
        }
        roughOffsets.forEach((id, offset) -> members.get(id).node.translation.add(offset));
        instance.calculateTransforms();
        return true;
    }

    /** Outline in world units relative to the hex, preserving a transport's heading and a trooper's turning room. */
    private static Polygon footprint(Member member, Matrix4 transform, Vector3 center) {
        float[] vertices = member.outline(member.heading).getTransformedVertices().clone();
        Vector3 origin = new Vector3(member.node.translation).mul(transform);
        for (int i = 0; i < vertices.length; i += 2) {
            Vector3 point = new Vector3(vertices[i], vertices[i + 1], 0).add(member.node.translation).mul(transform).sub(origin);
            vertices[i] = point.x;
            vertices[i + 1] = point.y;
        }
        Polygon polygon = new Polygon(vertices);
        polygon.setPosition(origin.x - center.x, origin.y - center.y);
        return polygon;
    }

    private static void vehicle(Member member, UnitMotion.Boarding travel, float scale, InfantryFootprint.Layout layout) {
        if (travel.stage() == UnitMotion.Stage.BOARD) {
            member.node.translation.set(member.start);
            member.orient(member.startHeading);
            return;
        }
        if (travel.stage() == UnitMotion.Stage.UNLOAD) {
            var point = travel.position(1).scl(1 / scale).add(member.goal);
            member.drivenDistance += point.dst(member.lastDrivePosition);
            member.lastDrivePosition.set(point);
            member.node.translation.set(member.goal);
            member.orient(member.goalHeading);
            return;
        }
        float t = travel.progress();
        Vector3 root = travel.position(t).scl(1 / scale);
        Vector3 point;
        if (t < DEPART_END) {
            Vector3 start = travel.position(0).scl(1 / scale).add(member.start);
            Vector3 end = travel.position(DEPART_END).scl(1 / scale).add(member.layout(layout));
            point = steer(member, start, end, member.startHeading + (member.reversing ? 180 : 0),
                  travel.heading(DEPART_END), t / DEPART_END);
        } else if (t > PARK_START) {
            Vector3 start = travel.position(PARK_START).scl(1 / scale).add(member.layout(layout));
            Vector3 end = travel.position(1).scl(1 / scale).add(member.goal);
            point = steer(member, start, end, travel.heading(PARK_START), member.goalHeading - (member.reversing ? 180 : 0),
                  (t - PARK_START) / (1 - PARK_START));
        } else {
            point = new Vector3(root).add(member.layout(layout));
            member.orient(travel.heading(t) + (member.reversing ? 180 : 0));
        }
        member.drivenDistance += point.dst(member.lastDrivePosition);
        member.lastDrivePosition.set(point);
        member.node.translation.set(point.sub(root));
        member.node.translation.z = member.rest.z;
    }

    /** Turn by driving through a short cubic curve. Heading follows its tangent, including the parking approach. */
    private static Vector3 steer(Member member, Vector3 start, Vector3 end, float from, float to, float progress) {
        float distance = start.dst(end) * .36f;
        var a = new Vector3(start).mulAdd(direction(from), distance);
        var b = new Vector3(end).mulAdd(direction(to), -distance);
        // The shared route already accelerates/brakes. Easing each parking sub-curve would cause extra stops.
        float t = MathUtils.clamp(progress, 0, 1), u = 1 - t;
        var tangent = new Vector3(a).sub(start).scl(u * u).mulAdd(new Vector3(b).sub(a), 2 * u * t)
              .mulAdd(new Vector3(end).sub(b), t * t);
        member.orient((tangent.isZero(.0001f) ? to : MathUtils.atan2(tangent.x, tangent.y) * MathUtils.radiansToDegrees)
              + (member.reversing ? 180 : 0));
        return new Vector3(start).scl(u * u * u).mulAdd(a, 3 * u * u * t).mulAdd(b, 3 * u * t * t)
              .mulAdd(end, t * t * t);
    }

    private static void passenger(Member member, Member vehicle, UnitMotion.Boarding travel, int order) {
        float offset = order * .035f;
        Vector3 door = vehicle.doorway();
        if (travel.stage() == UnitMotion.Stage.DRIVE) {
            member.node.translation.set(door);
            member.visible(false);
            member.step = new Step(0, 0, 1);
            return;
        }
        boolean boarding = travel.stage() == UnitMotion.Stage.BOARD;
        float progress = MathUtils.clamp((travel.progress() - offset) / (1 - offset), 0, 1);
        float walk = boarding ? Math.min(1, progress / .9f) : Math.min(1, progress / .8f);
        var from = boarding ? member.start : door;
        var to = boarding ? door : member.goal;
        member.node.translation.set(from).lerp(to, smooth(walk));
        float direction = MathUtils.atan2(to.x - from.x, to.y - from.y) * MathUtils.radiansToDegrees;
        float turn = boarding ? Math.min(1, progress * 7) : Math.max(0, (progress - .8f) / .2f);
        member.orient(boarding ? MathUtils.lerpAngleDeg(member.startHeading, direction, turn)
              : MathUtils.lerpAngleDeg(direction, member.goalHeading, turn));
        if (boarding && progress >= .97f || !boarding && progress <= .02f) {
            member.visible(false);
        }
        float gait = Math.min(1, Math.min(walk, 1 - walk) * 10);
        float standing = boarding ? Math.min(1, progress * 6) : 1 - smooth(Math.max(0, (progress - .7f) / .3f));
        member.step = new Step(gait, from.dst(to) * smooth(walk), standing);
    }

    private static Vector3 direction(float heading) {
        return new Vector3(MathUtils.sinDeg(heading), MathUtils.cosDeg(heading), 0);
    }

    private static float smooth(float value) {
        float t = MathUtils.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static float watchHeading(int unit, String member, Vector3 position, int destination) {
        int seed = 31 * (31 * unit + member.hashCode()) + destination;
        seed ^= seed >>> 16;
        seed *= 0x45d9f3b;
        seed ^= seed >>> 16;
        float outward = MathUtils.atan2(position.x, position.y) * MathUtils.radiansToDegrees;
        int formation = 31 * unit + destination;
        int slot = Integer.parseInt(member.substring("trooper-".length()));
        // At most one reserved slot occasionally watches inward. Casualties never reroll surviving headings.
        boolean inward = Math.floorMod(formation, 4) == 0 && slot == Math.floorMod(formation / 4, 6);
        return outward + Math.floorMod(seed, 701) * .1f - 35 + (inward ? 180 : 0);
    }
}
