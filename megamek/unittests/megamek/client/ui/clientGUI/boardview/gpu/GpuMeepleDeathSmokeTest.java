/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.equipment.IArmorState;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A destroyed unit's meeple explodes away from its hit, its pieces come to rest on the ground and scenery around and
 * sink away; a meeple formation's lost figures fall, lie and sink. Neither leaves a drawn wreck.
 */
@Tag("on-demand")
class GpuMeepleDeathSmokeTest {
    private static final float FRAME = 1 / 60f;

    @Test
    void meeplesExplodeAndFormationCasualtiesFallThenAllSink() throws Exception {
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create()) {
            List<Entity> units = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    units.add(fixture.entity);
                    units.add(add(fixture, new MekFileParser(new File("testresources/megamek/common/units/Bulldog Medium Tank.blk"))
                          .getEntity(), 2, new Coords(6, 5)));
                    units.add(add(fixture, platoon(EntityMovementMode.INF_LEG), 3, new Coords(4, 5)));
                    units.add(add(fixture, platoon(EntityMovementMode.INF_MOTORIZED), 4, new Coords(5, 6)));
                    units.add(add(fixture, new MekFileParser(new File("testresources/megamek/common/units/Elemental BA [Laser] (Sqd5).blk"))
                          .getEntity(), 5, new Coords(6, 6)));
                    fixture.source.refresh();
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var view = new GpuBattleView(fixture.source);
                    try {
                        view.create();
                        GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(true);
                        view.boardCamera.center(BoardGeometry.center(new Coords(5, 5), 0).add(0, -20, 0));
                        view.boardCamera.zoom(.3f);
                        float overview = view.boardCamera.camera.zoom;
                        view.render();
                        capture("before");
                        var atlas = UnitBounds.world(instance(view, 1)).getCenter(new Vector3());
                        // The Atlas was last hit from the west; nothing is known of the tank's hit.
                        @SuppressWarnings("unchecked")
                        var hits = (Map<Integer, Vector3>) field(view, "hitDirections");
                        hits.put(1, new Vector3(1, 0, 0));
                        SwingUtilities.invokeAndWait(() -> {
                            units.get(0).setDoomed(true);
                            units.get(1).setDoomed(true);
                            units.get(2).setInternal(9, ConvInfantry.LOC_INFANTRY);
                            units.get(3).setInternal(9, ConvInfantry.LOC_INFANTRY);
                            units.get(4).setInternal(IArmorState.ARMOR_DESTROYED, 4);
                            units.get(4).setInternal(IArmorState.ARMOR_DESTROYED, 5);
                            fixture.source.refresh();
                        });
                        view.render();
                        var casualties = (MeepleCasualties) field(view, "casualties");
                        var ground = (MeepleDebris.Ground) field(view, "debrisGround");
                        // The two meeples explode; six figures become three: the foot platoon loses three soldiers, the
                        // motorized one a buggy and two riders, the squad two suits.
                        assertEquals(5, casualties.casualties().size());
                        var instances = (Map<?, ?>) field(view, "unitInstances");
                        assertFalse(instances.containsKey("1:-1") || instances.containsKey("2:-1"), "No wreck is drawn");
                        float elapsed = 0;
                        for (float seconds : new float[] { .1f, .25f, .5f, 1, 2 }) {
                            for (; elapsed < seconds; elapsed += FRAME) { casualties.advance(FRAME, ground); }
                            for (var close : List.of(new Coords(5, 5), new Coords(5, 6))) {
                                view.boardCamera.center(BoardGeometry.center(close, 0));
                                view.boardCamera.zoom(overview * .6f / view.boardCamera.camera.zoom);
                                view.render();
                                capture(String.format("%.2fs-%d%d", seconds, close.getX(), close.getY()));
                            }
                        }
                        for (; elapsed < MeepleDebris.MAX_SECONDS; elapsed += FRAME) { casualties.advance(FRAME, ground); }
                        view.boardCamera.center(atlas);
                        view.render();
                        capture("settled-iso");
                        view.boardCamera.setIsometric(false);
                        view.render();
                        capture("settled-top");
                        view.boardCamera.setIsometric(true);
                        assertLanded(casualties, 1, atlas, ground, new Vector3(1, 0, 0));
                        assertLanded(casualties, 2, null, ground, null);
                        casualties.advance(MeepleCasualties.LIE_SECONDS + MeepleCasualties.SINK_SECONDS, ground);
                        view.render();
                        assertTrue(casualties.casualties().isEmpty(), "Everything sinks away");
                        capture("gone");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError("Meeple deaths failed", failure.get()); }
    }

    private static ConvInfantry platoon(EntityMovementMode movement) {
        var infantry = new ConvInfantry();
        infantry.setChassis("Foot Platoon");
        infantry.setModel("Rifle");
        infantry.setMovementMode(movement);
        infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
        return infantry;
    }

    private static Entity add(GpuBoardFixture fixture, Entity entity, int id, Coords position) {
        entity.setId(id);
        entity.setOwner(fixture.player);
        entity.setPosition(position);
        entity.setDeployed(true);
        fixture.game.addEntity(entity, false);
        return entity;
    }

    /**
     * The exploded meeple's pieces have come to rest on the ground under them, neither buried nor floating, and within
     * two hexes; with {@code middle}, some flew out of its hex, away from the hit when {@code away} is given.
     */
    private static void assertLanded(MeepleCasualties casualties, int unit, Vector3 middle, MeepleDebris.Ground ground,
          Vector3 away) {
        var casualty = casualties.casualties().stream().filter(candidate -> candidate.unit.id() == unit).findFirst().orElseThrow();
        Node pieces = casualty.instance.nodes.first();
        assertTrue(pieces.getChildCount() >= 4, "A destroyed meeple breaks into several pieces: " + pieces.getChildCount());
        var thrown = new Vector3();
        int outside = 0;
        for (Node piece : pieces.getChildren()) {
            // The debris' height field ramps a step over one of its cells: an edge can dip into a cliff's face there.
            float gap = gap(casualty.instance, piece, ground);
            assertTrue(gap < 3 && gap > -10, piece.id + " rests on the ground: " + gap);
            if (middle == null) { continue; }
            var center = UnitBounds.subtree(piece).mul(casualty.instance.transform).getCenter(new Vector3());
            var offset = center.sub(middle);
            offset.z = 0;
            assertTrue(offset.len() < BoardGeometry.height() * 2, piece.id + " lands near its hex: " + offset.len());
            if (offset.len() > BoardGeometry.height() * .5f) {
                outside++;
                thrown.add(offset.nor());
            }
        }
        if (middle != null) { assertTrue(outside > 0, "Some pieces fly into the hexes around"); }
        if (away != null) { assertTrue(thrown.dot(away) > 0, "Thrown pieces fly away from the hit: " + thrown); }
    }

    /**
     * The nearest any of the piece's vertices comes to the ground under it, negative below it: near zero for a piece
     * resting on the ground, a ledge or a rock. A broken meeple's meshes are unindexed.
     */
    private static float gap(ModelInstance instance, Node piece, MeepleDebris.Ground ground) {
        float gap = Float.POSITIVE_INFINITY;
        var world = new Matrix4(instance.transform).mul(piece.globalTransform);
        var point = new Vector3();
        for (var part : piece.parts) {
            var mesh = part.meshPart.mesh;
            int stride = mesh.getVertexSize() / Float.BYTES;
            int position = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
            float[] vertices = new float[part.meshPart.size * stride];
            mesh.getVertices(part.meshPart.offset * stride, vertices.length, vertices);
            for (int at = 0; at < vertices.length; at += stride) {
                point.set(vertices[at + position], vertices[at + position + 1], vertices[at + position + 2]).mul(world);
                float under = ground.height(point.x, point.y);
                if (Float.isFinite(under)) { gap = Math.min(gap, point.z - under); }
            }
        }
        return gap;
    }

    private static ModelInstance instance(GpuBattleView view, int id) throws Exception {
        return (ModelInstance) ((Map<?, ?>) field(view, "unitInstances")).get(id + ":-1");
    }

    private static void capture(String name) {
        GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "meeple-death-" + name + ".png"));
    }
}
