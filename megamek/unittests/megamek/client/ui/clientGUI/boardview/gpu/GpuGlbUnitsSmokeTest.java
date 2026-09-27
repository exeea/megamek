/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** GLB asset integration stays independently runnable from the broad terrain/landing-support review. */
@Tag("on-demand")
class GpuGlbUnitsSmokeTest {
    @Test
    void glbAssembliesRetainAnimationEquipmentCamouflageAndDamage() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var library = new GpuUnitModels();
                var batch = new ModelBatch();
                try {
                    for (String asset : List.of("bodies/warhammer", "equipment/ppc", "troops/rifle-standing")) {
                        assertTrue(library.modular("units/modular/" + asset + ".json").descriptor().mesh().endsWith(".glb"));
                    }
                    assertAll("GLB unit integration",
                          () -> GpuMekAssemblyReview.verify(library, batch),
                          () -> GpuEquipmentAssemblyReview.verify(library, batch),
                          () -> GpuFamilyAssemblyReview.verify(library, batch),
                          () -> GpuUnitAnimationReview.verify(library, batch),
                          () -> GpuFamilyMotionReview.verify(library, batch),
                          () -> GpuJumpJetReview.verify(library, batch),
                          () -> GpuCamouflageReview.verify(library),
                          () -> GpuDamageReview.verifyModels(library));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    batch.dispose();
                    library.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("GLB unit integration failed", failure.get()); }
    }
}
