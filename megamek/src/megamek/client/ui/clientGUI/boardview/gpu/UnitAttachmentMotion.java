/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

/** One exterior transition on UnitPlayback's clock; matrices are GL-owned release poses, never game state. */
final class UnitAttachmentMotion {
    static final float DURATION = 1.65f;
    final BoardScene.AttachmentChange event;
    record Flight(Matrix4 origin, Vector3 outward, float lift, float clearance) { }
    final Map<String, Flight> flights = new HashMap<>();
    float seconds;
    boolean settled;

    UnitAttachmentMotion(BoardScene.AttachmentChange event) { this.event = event; }

    float progress() { return MathUtils.clamp(seconds / DURATION, 0, 1); }

    boolean boarding() { return event.release() == BoardScene.Release.BOARD; }

    boolean thrown() { return !boarding() && event.release() != BoardScene.Release.CLIMB_DOWN; }

    float grip() { return boarding() ? UnitAttack.smooth(progress()) : 1 - UnitAttack.smooth(progress() * 4); }

    /** Exact endpoints with zero terminal velocity; a lateral bow keeps same-hex falls outside the hull. */
    static Vector3 trajectory(Vector3 origin, Vector3 destination, Vector3 outward, float progress,
          float lift, float clearance, Vector3 result) {
        float t = MathUtils.clamp(progress, 0, 1);
        float travel = UnitAttack.smooth(t);
        float arc = 16 * t * t * (1 - t) * (1 - t);
        return result.set(origin).lerp(destination, travel).mulAdd(outward, clearance * arc).add(0, 0, lift * arc);
    }
}
