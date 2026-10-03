/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs both terrain-contact reviews even when one fails, using the deployed unit assets. */
@Tag("on-demand")
class GpuLandingDamageSmokeTest {
    @Test
    void landingContactsAndDamageSurviveTerrainChanges() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var library = new GpuUnitModels();
                var batch = new ModelBatch();
                try {
                    assertAll("Terrain contacts and damage",
                          () -> GpuLandingSupportReview.verify(library, batch),
                          () -> GpuDamageReview.verify(library));
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
        if (failure.get() != null) { throw new AssertionError("Terrain-contact review failed", failure.get()); }
    }
}
