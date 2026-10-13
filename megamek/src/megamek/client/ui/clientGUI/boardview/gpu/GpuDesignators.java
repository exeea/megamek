/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/**
 * A TAG's laser designator: a thin, pulsing deep-red line from the designating unit to the unit it marked, with a red
 * spot where it lands. The TAG shot draws it while it fires ({@link GpuAttackEffects}); after a hit it stays on, from
 * the scene's designations, until the end of the Firing phase, when semi-guided and homing rounds have used it.
 */
final class GpuDesignators implements Disposable {
    /** The beam shader's designator looks (beams.frag): its line and its spot. */
    static final int LINE = 3, SPOT = 4;
    /** About a weapon laser's width: any thinner falls between pixel rows at board zoom and vanishes. */
    static final float WIDTH = .55f;
    static final float SPOT_RADIUS = 4.5f;
    static final float PULSES_PER_SECOND = 2.5f;
    /** Designations a frame draws at most; a round rarely has more than a few. */
    private static final int CAPACITY = 64;

    private final GpuEffectBatch beams = new GpuEffectBatch(CAPACITY * 2, "beams");
    private final Vector3 from = new Vector3(), to = new Vector3();
    private float clock;

    /** The pulse at {@code seconds}: between {@code .2} and {@code 1}, {@link #PULSES_PER_SECOND} times a second. */
    static float pulse(float seconds) {
        return .6f + .4f * MathUtils.sin(seconds * PULSES_PER_SECOND * MathUtils.PI2);
    }

    /** Queues a designator from {@code start} to its spot at {@code target}, at {@code alpha} and {@code pulse}. */
    static void draw(GpuEffectBatch batch, Camera camera, Vector3 start, Vector3 target, float alpha, float pulse) {
        batch.ribbon(camera, start, target, WIDTH, LINE, alpha);
        batch.billboard(camera, target, SPOT_RADIUS * (.85f + .15f * pulse), SPOT, alpha);
    }

    /**
     * Draws every designation still in effect, pulsing on its own clock.
     *
     * @param seconds the frame's time, which advances the pulse
     */
    void render(Camera camera, List<BoardScene.Designation> designations, float seconds) {
        clock += seconds;
        beams.begin();
        if (designations.isEmpty()) {
            return;
        }
        float pulse = pulse(clock);
        for (BoardScene.Designation designation : designations) {
            UnitAttack.center(null, designation.source(), from);
            UnitAttack.center(null, designation.target(), to);
            draw(beams, camera, from, to, pulse, pulse);
        }
        beams.render(camera, 0);
    }

    @Override
    public void dispose() {
        beams.dispose();
    }
}
