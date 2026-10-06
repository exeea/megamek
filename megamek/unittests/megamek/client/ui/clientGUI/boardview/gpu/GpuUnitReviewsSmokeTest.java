/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The unit model reviews on one real OpenGL context with one production asset library: the shipped Atlas as both its
 * near and its far body, the shipped Elemental's full and far suits, the GLB assemblies' animation, equipment,
 * camouflage and damage, and landing contacts and damage through terrain changes. Every group runs even when an
 * earlier one fails; {@link GpuDamageReview#verify} covers the GLB group's damage materials and the ground remains.
 */
@Tag("on-demand")
class GpuUnitReviewsSmokeTest {
    @TempDir
    Path scratch;

    @Test
    void lodsGlbAssembliesLandingSupportsAndDamageVerifyOnOneContext() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var library = new GpuUnitModels();
                var batch = new ModelBatch();
                try {
                    assertAll("Unit model reviews",
                          () -> assertAll("A Mek swaps between its near and far bodies",
                                () -> GpuMekLodReview.verify(scratch)),
                          () -> assertAll("Elemental squads swap between their full and far suits",
                                () -> GpuFormationLodReview.verify(library)),
                          () -> assertAll("GLB assemblies retain animation, equipment, camouflage and damage",
                                () -> {
                                    for (String asset : List.of("bodies/warhammer", "equipment/ppc",
                                          "troops/rifle-standing")) {
                                        assertTrue(library.modular("units/modular/" + asset + ".json").descriptor()
                                              .mesh().endsWith(".glb"), asset + " is a GLB asset");
                                    }
                                },
                                () -> GpuMekAssemblyReview.verify(library, batch),
                                () -> GpuEquipmentAssemblyReview.verify(library, batch),
                                () -> GpuFamilyAssemblyReview.verify(library, batch),
                                () -> GpuUnitAnimationReview.verify(library, batch),
                                () -> GpuFamilyMotionReview.verify(library, batch),
                                () -> GpuJumpJetReview.verify(library, batch),
                                () -> GpuCamouflageReview.verify(library)),
                          () -> assertAll("Landing contacts and damage survive terrain changes",
                                () -> GpuLandingSupportReview.verify(library, batch),
                                () -> GpuDamageReview.verify(library)));
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
        if (failure.get() != null) { throw new AssertionError("Unit model reviews failed", failure.get()); }
    }
}
