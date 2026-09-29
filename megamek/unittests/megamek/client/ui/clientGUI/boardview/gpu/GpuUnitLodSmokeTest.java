/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests actual mesh-part switching with disposable primitive fixtures; production unit artwork is untouched. */
@Tag("on-demand")
class GpuUnitLodSmokeTest {
    @Test
    void threeLevelsPreserveFallbackComponentsFocusAndDamage() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                long attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;
                ModelBuilder builder = new ModelBuilder();
                Model near = builder.createSphere(2, 2, 2, 12, 8, new Material(), attributes);
                Model middle = builder.createSphere(2, 2, 2, 6, 4, new Material(), attributes);
                Model distant = builder.createBox(2, 2, 2, new Material(), attributes);
                Model fixed = builder.createBox(1, 1, 1, new Material(), attributes);
                for (Model model : List.of(near, middle, distant)) { model.nodes.first().id = "RA"; }
                fixed.nodes.first().id = "fixed";
                try {
                    for (float[] thresholds : new float[][] { { 96, 32 }, { 48, 16 } }) {
                        verify(List.of(near, middle, distant), fixed, thresholds, 1, 2);
                        verify(List.of(near, near, distant), fixed, thresholds, 0, 2);
                        verify(List.of(near, middle, middle), fixed, thresholds, 1, 1);
                        verify(List.of(near, near, near), fixed, thresholds, 0, 0);
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    for (Model model : List.of(near, middle, distant, fixed)) { model.dispose(); }
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Unit LOD switching failed", failure.get()); }
    }

    private static void verify(List<Model> models, Model fixed, float[] thresholds, int middle, int distant) {
        Model assembled = new Model();
        for (Node node : new ModelInstance(models.getFirst()).nodes) { assembled.nodes.add(node); }
        for (Node node : new ModelInstance(fixed).nodes) { assembled.nodes.add(node); }
        var meshes = GpuUnitModels.attachDetailLevels(models, id -> assembled.getNode(id, true), node -> { }, "test");
        // A mixed formation must retain a member which has no optional levels at any distance.
        for (Set<Mesh> level : meshes) { fixed.meshes.forEach(level::add); }
        var model = new GpuUnitModel(assembled).detailLevels(new GpuUnitModel.DetailLevels(
              meshes.get(0), meshes.get(1), meshes.get(2), thresholds[0], thresholds[1]));
        try {
            var instance = new GpuUnitInstance(model);
            var other = new GpuUnitInstance(model);
            check(instance, model, thresholds[0] * .85f, false, middle);
            check(instance, model, thresholds[1] * .95f, false, middle);
            check(instance, model, thresholds[1] * .85f, false, distant);
            check(instance, model, thresholds[1] * 1.05f, false, distant);
            check(instance, model, thresholds[1] * 1.15f, false, middle);
            check(instance, model, thresholds[0] * 1.15f, false, 0);
            // A large camera change switches directly, and focus overrides it immediately.
            check(instance, model, 1, false, distant);
            check(instance, model, 1, true, 0);
            check(instance, model, 1, false, distant);
            assertEquals(0, other.detailLevel(), "Instances sharing buffers retain independent LOD state");
            for (var part : other.getNode("RA").parts) {
                assertEquals(meshes.get(0).contains(part.meshPart.mesh), part.enabled);
            }
            UnitDamageDisplay.show(instance, new BoardScene.LocationDamage(Set.of("RA"), Set.of()));
            instance.bodyDetail(200 / model.figureHeight(), false);
            instance.bodyDetail(1 / model.figureHeight(), false);
            instance.bodyDetail(1 / model.figureHeight(), true);
            for (var part : instance.getNode("RA").parts) { assertFalse(part.enabled, "Lost parts stay hidden"); }
            assertTrue(instance.getNode("fixed").parts.first().enabled);
        } finally {
            model.dispose();
        }
    }

    private static void check(GpuUnitInstance instance, GpuUnitModel model, float pixels, boolean focused, int expected) {
        instance.bodyDetail(pixels / model.figureHeight(), focused);
        assertEquals(expected, instance.detailLevel());
        Set<Mesh> visible = new HashSet<>();
        for (Node node : instance.nodes) {
            for (var part : node.parts) {
                if (part.enabled) { visible.add(part.meshPart.mesh); }
            }
        }
        assertEquals(model.detailLevels().meshes(expected), visible, "Exactly the selected meshes are drawable");
    }
}
