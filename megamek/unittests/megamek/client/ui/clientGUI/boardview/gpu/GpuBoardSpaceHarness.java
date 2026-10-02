/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.common.board.Board;

/**
 * The board-space harness (rebuild plan E rule 6): the mock roster on a shipped 32 x 17 board, drawn by the real
 * terrain under a BoardCamera in 3D or in the Tactical View, with one board-space component rendered over it. In 3D
 * the units are GpuBattleView's fallback sprite models, the flat classic sprites it shows for a unit without a 3D
 * model (the fixture has none), and the sensor contact is its marker; {@link #anchors} then holds each unit's anchor,
 * as the view fills it. In the Tactical View the units are GpuUnitIcons, which a test draws as (part of) its
 * component. Not drawn: the view's clouds, weather, unit outlines, fire control, GpuTactical overlays, markers other
 * than the contact, animation and hover. The scene is captured on Swing before the window opens; the harness itself
 * lives on the GL thread, inside {@link GpuHudTestStage#run}, whose stage can draw HUD labels over the board.
 */
final class GpuBoardSpaceHarness implements Disposable {
    static final String BOARD = "data/boards/Grasslands BattleMats/32x17 Grasslands A BattleMat.board";

    final BoardScene scene;
    final GpuBattleStatus.Snapshot status = GpuHudFixtures.status();
    final BoardCamera camera = new BoardCamera();
    /** Still poses: each unit at its hex center and level, turned to its facing. */
    final Map<BoardScene.Unit, UnitFootprint.Pose> poses = new HashMap<>();
    final BoardSurface.Cache surfaces = new BoardSurface.Cache();
    /** Each unit's anchor in the last 3D draw with units, as GpuBattleView places nameplates and badges. */
    final Map<BoardScene.Unit, Vector3> anchors = new HashMap<>();
    /** Whether the 3D view draws the roster, as GpuBattleView does outside the Tactical View. */
    boolean units = true;
    /** Each unit's 3D visual, placed by the last 3D draw with units. */
    final Map<BoardScene.Unit, ModelInstance> instances = new HashMap<>();
    private final GpuTerrain terrain = new GpuTerrain();
    private final GpuAtmosphere atmosphere = new GpuAtmosphere();
    private final GpuTextures<BoardScene.Pixels> unitTextures = new GpuTextures<>();
    private final Map<BoardScene.Pixels, GpuUnitModel> spriteModels = new HashMap<>();
    private final GpuMarkers markers = new GpuMarkers();
    private final ModelBatch unitBatch = new ModelBatch(GpuUnitShader.provider(), new GpuOpaqueSorter());

    /** The shipped board's tiles and tileset art from the real capture, with the fixture roster as its units. */
    static BoardScene scene() throws Exception {
        Board board = new Board();
        board.load(new File(BOARD));
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            FutureTask<BoardScene> capture = new FutureTask<>(() -> {
                fixture.source.setVisibleArea(new Rectangle(0, 0, board.getWidth(), board.getHeight()));
                fixture.source.refresh();
                return fixture.source.takeFrame().scene();
            });
            SwingUtilities.invokeAndWait(capture);
            BoardScene captured = capture.get();
            return captured.withUnits(GpuHudFixtures.units(coords -> captured.tile(coords).elevation()));
        }
    }

    GpuBoardSpaceHarness(BoardScene scene) {
        this.scene = scene;
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        terrain.update(scene);
        // As GpuBattleView: one sprite model per distinct image of a unit that is no sensor contact.
        unitTextures.update(scene.units().stream().filter(unit -> !unit.sensorContact()).map(BoardScene.Unit::image)
              .distinct().collect(Collectors.toMap(pixels -> pixels, pixels -> pixels)));
        for (BoardScene.Unit unit : scene.units()) {
            poses.put(unit, new UnitFootprint.Pose(unit,
                  BoardGeometry.center(unit.location().coords(), unit.location().elevation()),
                  unit.location().facing() * 60));
            instances.put(unit, new GpuUnitInstance(visual(unit)));
        }
    }

    private GpuUnitModel visual(BoardScene.Unit unit) {
        return unit.sensorContact() ? markers.model(BoardMarker.Kind.SENSOR_CONTACT)
              : spriteModels.computeIfAbsent(unit.image(),
                    pixels -> GpuUnitModel.sprite(pixels, unitTextures.region(pixels)));
    }

    /** Fits the board in the 3D view's isometric start pose, or in the Tactical View with flat tileset art. */
    void view(boolean tactical) {
        camera.setTactical(tactical, scene);
        camera.setIsometric(true);
        camera.fit(scene);
        terrain.setFlatFeatures(tactical);
    }

    boolean tactical() {
        return camera.tactical();
    }

    /** Draws the board with GpuBattleView's world passes in its order, then the component with the board camera. */
    void draw(Consumer<Camera> component) {
        List<ModelInstance> shown = new ArrayList<>();
        anchors.clear();
        if (units && !tactical()) {
            // As GpuBattleView: zoomed out, the units grow; the harness draws one HUD unit per window pixel.
            float growth = UnitScreenScale.factor(UnitScreenScale.hexPixels(camera.camera.zoom, 1));
            for (BoardScene.Unit unit : scene.units()) {
                ModelInstance instance = instances.get(unit);
                Vector3 position = poses.get(unit).position().cpy();
                Vector3 anchor = unit.sensorContact()
                      ? markers.placeSensor(unit.location().coords(), instance, camera.camera, position)
                      : visual(unit).place(instance, camera.camera, position, poses.get(unit).facing(), unit);
                anchors.put(unit, UnitScreenScale.grow(unit, instance, anchor, growth));
                shown.add(instance);
            }
        }
        atmosphere.updateLight(camera.camera);
        terrain.setAtmosphere(atmosphere.lighting());
        terrain.animate(0, shown);
        terrain.renderShadows(camera.camera, shown);
        ScreenUtils.clear(0.035f, 0.055f, 0.075f, 1, true);
        atmosphere.begin(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0);
        terrain.render(camera.camera, false);
        unitBatch.begin(camera.camera);
        for (BoardScene.Unit unit : anchors.keySet()) {
            if (unit.sensorContact()) {
                unitBatch.render(instances.get(unit));
            } else {
                unitBatch.render(instances.get(unit), terrain.environment());
            }
        }
        unitBatch.end();
        terrain.renderTransparent(camera.camera);
        atmosphere.end(camera.camera, terrain, scene, 0);
        terrain.render(camera.camera, true);
        component.accept(camera.camera);
    }

    /** A world point's back-buffer pixel, y up as the captured rows. */
    Vector2 screen(Vector3 world) {
        Vector3 projected = camera.camera.project(new Vector3(world));
        float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
        return new Vector2(projected.x * density, projected.y * density);
    }

    @Override
    public void dispose() {
        unitBatch.dispose();
        spriteModels.values().forEach(GpuUnitModel::dispose);
        unitTextures.dispose();
        markers.dispose();
        terrain.dispose();
        atmosphere.dispose();
    }
}
