/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A fighter rolling in on a ground target pitches its nose down. Places a fighter level and then pitched 20 degrees
 * nose down, heading east, and writes both to {@code megamek.gpu.screenshots}/playback-roll-in-* for review: the nose
 * (the models' local +Y) must end lower than the tail, and a level placement must be unchanged by a pitch of 0.
 */
@Tag("on-demand")
class GpuRollInSmokeTest {
    private static final int FIGHTER = 9902;
    private static final float EAST = 90;
    private static final float NOSE_DOWN = -20;

    @Test
    void aNegativePitchPutsTheNoseBelowTheTail() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var library = new GpuUnitModels();
                try (var renderer = new GpuPlaybackReview.ReviewRenderer()) {
                    var selection = GpuFamilyAssemblyReview.selection("fighter", List.of());
                    var model = library.get(selection, FIGHTER);
                    assertNotNull(model, "the fighter family has a model");
                    var unit = new BoardScene.Unit(FIGHTER, -1, "Rolling fighter",
                          new BoardScene.Waypoint(new Coords(2, 3), 3, 1), null, false, null, 2, true, selection, 0);
                    Vector3 origin = BoardGeometry.center(new Coords(2, 3), 3);
                    var instance = new ModelInstance(model.instance.model);

                    model.place(instance, renderer.camera, origin, EAST, unit);
                    float levelDrop = noseBelowTail(instance);
                    renderer.frame(List.of(instance), origin, null, "roll-in-level", 0);
                    model.place(instance, renderer.camera, origin, EAST, unit, 0);
                    assertEquals(levelDrop, noseBelowTail(instance), 1e-4, "a pitch of 0 places it level, as before");

                    model.place(instance, renderer.camera, origin, EAST, unit, NOSE_DOWN);
                    float pitchedDrop = noseBelowTail(instance);
                    renderer.frame(List.of(instance), origin, null, "roll-in-nose-down", 0);
                    assertTrue(pitchedDrop > levelDrop + 1, "the nose dives below the tail: " + pitchedDrop
                          + " against " + levelDrop + " level");
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    library.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError("Roll-in review failed", failure.get());
        }
    }

    /** How far below the tail the nose is, in world units: the models' local +Y is their nose. */
    private static float noseBelowTail(ModelInstance placed) {
        float reach = Math.max(1, UnitBounds.local(placed).getHeight());
        Vector3 nose = new Vector3(0, reach, 0).mul(placed.transform);
        Vector3 tail = new Vector3(0, -reach, 0).mul(placed.transform);
        return tail.z - nose.z;
    }
}
