/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;

/** Exterior placement after the carrier's final pose, shared by drawing, shadows, effects and picking. */
final class UnitAttachments {
    private final Map<String, Matrix4> previous = new HashMap<>();
    private final BoardSurface.Cache surfaces;

    UnitAttachments() { this(new BoardSurface.Cache()); }

    UnitAttachments(BoardSurface.Cache surfaces) { this.surfaces = surfaces; }

    void clear() { previous.clear(); }

    void place(BoardScene scene, GpuUnitModels models, Map<String, ModelInstance> instances,
          Map<String, UnitAnimator> animators, UnitAttachmentMotion motion, Camera camera,
          Map<BoardScene.Unit, Vector3> anchors, float clock) {
        if (models == null) { return; }
        Map<Integer, BoardScene.Unit> units = new HashMap<>();
        scene.units().forEach(unit -> units.put(unit.id(), unit));
        Set<String> retained = new HashSet<>();
        for (var unit : scene.units()) {
            if (unit.sensorContact() || unit.model() == null) { continue; }
            String key = unit.id() + ":" + unit.part();
            var instance = instances.get(key);
            var model = models.get(unit.model(), unit.id());
            if (instance == null || model == null || !model.infantry()) { continue; }
            var transition = motion != null && motion.event.entityId() == unit.id() && motion.progress() < 1 ? motion : null;
            var binding = transition == null ? unit.attachment() : transition.boarding()
                  ? transition.event.after().attachment() : transition.event.before().attachment();
            var carrier = binding == null ? null : units.get(binding.carrierId());
            var host = carrier == null ? null : instances.get(carrier.id() + ":" + carrier.part());
            var hostModel = carrier == null ? null : models.get(carrier.model(), carrier.id());
            Map<String, Matrix4> ground = new HashMap<>();
            for (var rig : model.rigs()) {
                Node member = rig.container() == null ? null : instance.getNode(rig.container());
                if (member != null && rig.trooper()) {
                    ground.put(rig.container(), instance.transform.cpy().mul(member.globalTransform));
                }
            }
            var animator = animators.get(key);
            if (animator != null && binding != null && host != null) {
                animator.attachmentPose(binding.hostile(), clock, transition);
            }
            int index = 0;
            for (var rig : model.rigs()) {
                Node member = rig.container() == null ? null : instance.getNode(rig.container());
                if (member == null || !rig.trooper()) { continue; }
                String memberKey = key + ":" + rig.container();
                retained.add(memberKey);
                Matrix4 destination = ground.get(rig.container());
                if (binding != null && host != null && hostModel != null) {
                    int slot = rig.container().startsWith("trooper-") ? Integer.parseInt(rig.container().substring(8)) - 1 : index;
                    Vector3 suitSize = UnitBounds.subtree(model.instance.getNode(rig.container())).getDimensions(new Vector3())
                          .scl(instance.transform.getScale(new Vector3()));
                    float suitHeight = suitSize.z;
                    Matrix4 socket = socket(hostModel, host, slot, binding.hostile(), destination, suitSize);
                    if (transition == null) { destination = socket; }
                    else {
                        if (transition.boarding()) { destination = socket; }
                        var landingTile = scene.tile(transition.event.destination().coords());
                        if (transition.event.after() == null && landingTile != null && landingTile.waterDepth() > 0 && !landingTile.frozen()) {
                            destination = destination.cpy().trn(0, 0, -BoardGeometry.level() * .35f
                                  * UnitAttack.smooth((transition.progress() - .65f) / .35f));
                        }
                        var flight = transition.flights.get(memberKey);
                        if (flight == null) {
                            // Travel/landing may finish in this tick. Release from the carrier's current pose,
                            // not a cached world position drawn partway through that earlier movement.
                            Matrix4 origin = !transition.boarding() ? socket.cpy()
                                  : previous.containsKey(memberKey) ? previous.get(memberKey).cpy() : ground.get(rig.container()).cpy();
                            if (transition.boarding() && !previous.containsKey(memberKey)) {
                                var before = transition.event.before().location();
                                origin.trn(BoardGeometry.center(before.coords(), before.elevation())
                                      .sub(BoardGeometry.center(unit.location().coords(), unit.location().elevation())));
                            }
                            Vector3 from = origin.getTranslation(new Vector3());
                            Vector3 outward = socket.getTranslation(new Vector3()).sub(host.transform.getTranslation(new Vector3()));
                            outward.z = 0;
                            if (outward.isZero(.001f)) { outward.set(slot % 2 == 0 ? -1 : 1, 0, 0); }
                            var bounds = UnitBounds.world(host);
                            boolean water = transition.event.release() == BoardScene.Release.WATER;
                            float clearance = transition.thrown() ? Math.max(bounds.getWidth(), bounds.getHeight()) * (water ? .2f : .6f) : 0;
                            // A launch that must cross the hull travels above it, including a destination on the other side.
                            float lift = water ? 0 : transition.thrown() ? Math.max(BoardGeometry.level() * .25f,
                                  bounds.max.z - Math.min(from.z, destination.getTranslation(new Vector3()).z))
                                  : BoardGeometry.level() * .12f;
                            outward.nor();
                            if (!water) {
                                // Raise only this cosmetic arc when the installed terrain crosses its route.
                                var to = destination.getTranslation(new Vector3());
                                for (int sample = 1; sample < 10; sample++) {
                                    float t = sample / 10f, arc = 16 * t * t * (1 - t) * (1 - t);
                                    var point = UnitAttachmentMotion.trajectory(from, to, outward, t, 0, clearance, new Vector3());
                                    float floor = UnitLandingSupports.ground(scene, point.x, point.y, surfaces);
                                    if (Float.isFinite(floor)) {
                                        lift = Math.max(lift, (floor + suitHeight * .3f * MathUtils.sin(t * MathUtils.PI) - point.z) / arc);
                                    }
                                }
                            }
                            flight = new UnitAttachmentMotion.Flight(origin, outward, lift, clearance);
                            transition.flights.put(memberKey, flight);
                        }
                        Matrix4 start = flight.origin();
                        Vector3 from = start.getTranslation(new Vector3()), to = destination.getTranslation(new Vector3());
                        Vector3 position = UnitAttachmentMotion.trajectory(from, to, flight.outward(), transition.progress(), flight.lift(),
                              flight.clearance(), new Vector3());
                        Quaternion turn = start.getRotation(new Quaternion(), true).slerp(
                              destination.getRotation(new Quaternion(), true), UnitAttack.smooth(transition.progress()));
                        if (transition.thrown()) {
                            float tumble = MathUtils.sin(transition.progress() * MathUtils.PI);
                            turn.mul(new Quaternion(Vector3.X, (slot % 2 == 0 ? 1 : -1)
                                  * (transition.event.release() == BoardScene.Release.WATER ? 35 : 105) * tumble));
                        }
                        destination = new Matrix4(position, turn, destination.getScale(new Vector3()));
                    }
                    setWorld(instance, member, destination);
                }
                previous.put(memberKey, destination.cpy());
                index++;
            }
            if (binding != null && host != null) { anchors.put(unit, model.anchor(instance, camera)); }
        }
        previous.keySet().retainAll(retained);
    }

    /** Fixed friendly/hostile surfaces avoid moving either squad when the other attaches or leaves. */
    private static Matrix4 socket(GpuUnitModel model, ModelInstance host, int index, boolean hostile, Matrix4 suit, Vector3 suitSize) {
        UnitRig rig = model.rigs().isEmpty() ? null : model.rigs().getFirst();
        boolean mek = rig != null && rig.mek();
        String role = mek && index < 2 ? index == 0 ? "leftArm" : "rightArm" : mek ? "torso" : "hull";
        if (mek && hostile && index >= 4) { role = index % 2 == 0 ? "leftLeg" : "rightLeg"; }
        if (mek && !"torso".equals(role) && !rig.joints().containsKey(role)) {
            // A quadruped's front legs provide the grips a biped carries on its arms.
            role = (index < 2 ? "F" : "R") + (index % 2 == 0 ? "L" : "R") + "L";
        }
        Node rest = rig == null ? null : model.instance.getNode(rig.joints().getOrDefault(role, ""));
        Node posed = rest == null ? null : host.getNode(rest.id);
        boolean fallback = rest == null || posed == null || !UnitBounds.subtree(posed).isValid();
        if (fallback) {
            rest = model.instance.nodes.first();
            posed = host.nodes.first();
        }
        BoundingBox bounds = new BoundingBox().inf();
        for (var part : rest.parts) {
            var mesh = part.meshPart;
            if (mesh.radius < 0) { mesh.update(); }
            bounds.ext(mesh.center.cpy().sub(mesh.halfExtents));
            bounds.ext(mesh.center.cpy().add(mesh.halfExtents));
        }
        if (!bounds.isValid()) { bounds = UnitBounds.subtree(rest).mul(rest.globalTransform.cpy().inv()); }
        Matrix4 frame = host.transform.cpy().mul(posed.globalTransform);
        Vector3 point = bounds.getCenter(new Vector3());
        // A narrow central torso must still leave space between the two full-sized suits.
        float pairedSpacing = Math.max(bounds.getWidth() * .28f, suitSize.x * .6f / frame.getScaleX());
        float yaw;
        if (hostile && mek) {
            // Fronts of arms, torso and legs leave the riding squad's side/rear grips free.
            if ("torso".equals(role) || fallback) { point.x += pairedSpacing * (index % 2 == 0 ? -1 : 1); }
            point.y = bounds.max.y;
            yaw = 180;
        } else if (hostile) {
            // Vehicles carry riders down their sides; swarmers use three grips at each end.
            float spacing = Math.max(bounds.getWidth() * .3f, suitSize.x * 1.2f / frame.getScaleX());
            point.x += spacing * (index / 2 % 3 - 1);
            point.y = index % 2 == 0 ? bounds.max.y : bounds.min.y;
            yaw = index % 2 == 0 ? 180 : 0;
        } else if (mek && index >= 2 && index < 4) {
            point.x += pairedSpacing * (index % 2 == 0 ? -1 : 1);
            point.y = bounds.min.y;
            yaw = 0;
        } else {
            point.x = index % 2 == 0 ? bounds.min.x : bounds.max.x;
            point.y += bounds.getHeight() * (index / 2 % 3 - 1) * .25f;
            yaw = index % 2 == 0 ? -90 : 90;
        }
        float height = mek ? .72f : .9f;
        if (mek && hostile && index >= 4) { height = .35f; }
        point.z = bounds.min.z + bounds.getDepth() * height;
        point.mul(frame);
        Quaternion orientation = frame.getRotation(new Quaternion(), true).mul(new Quaternion(Vector3.Z, yaw));
        // Container origin is at the boots. Hands reach the socket while the boots brace below it.
        // Strike poses need extra room at the corners beside friendly riders.
        float reach = hostile ? .34f : .22f;
        point.add(new Vector3(0, -suitSize.z * reach, -suitSize.z * .55f).mul(orientation));
        // Keep the suit's own scale; only its attachment frame inherits the animated carrier joint.
        return new Matrix4(point, orientation, suit.getScale(new Vector3()));
    }

    static void setWorld(ModelInstance instance, Node member, Matrix4 world) {
        Matrix4 parent = instance.transform.cpy();
        if (member.getParent() != null) { parent.mul(member.getParent().globalTransform); }
        Matrix4 local = parent.inv().mul(world);
        local.getTranslation(member.translation);
        local.getRotation(member.rotation, true);
        local.getScale(member.scale);
        instance.calculateTransforms();
    }
}
