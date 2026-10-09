/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Measure input-to-installed-geometry latency through the real editor, including EDT capture and render frames. */
@Tag("on-demand")
class GpuEditorLatencySmokeTest {
    @ParameterizedTest
    @ValueSource(strings = { "scenery/vehicles/car", "scenery/construction/bulldozer" })
    void reportsWarmObjectAndTerrainEditLatency(String asset) throws Exception {
        Coords at = new Coords(8, 8);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(16, 17);
            Hex hex = new Hex(0, "road:1:9", "grass");
            hex.setDecorations(List.of(new BoardDecoration("car", "prop", asset, null,
                  0, 0, 0, asset.endsWith("bulldozer"), 1, BoardDecoration.Placement.ground(), 0)));
            board.setHex(at, hex); editor.game().setBoard(board); editor.pointer(at, 0, 0, false, "car");
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup); GpuMapSource source = setup.get();
        List<Command> edits = new ArrayList<>(List.of(new Command(Action.OBJECT_VALUE, "x", ".1"),
              new Command(Action.OBJECT_VALUE, "rotation", "30"), new Command(Action.OBJECT_VALUE, "scale", "1.2"),
              new Command(Action.OBJECT_VALUE, "x", ".2"), new Command(Action.ELEVATION, "1"),
              new Command(Action.ELEVATION, "0"), new Command(Action.VARIANT, "road", "road/material/dirt"),
              new Command(Action.OBJECT_VALUE, "x", ".35"), new Command(Action.UNDO),
              new Command(Action.OBJECT_VALUE, "offset", "2"), new Command(Action.OBJECT_VALUE, "level", "4"),
              new Command(Action.OBJECT_VALUE, "receiver", "ground/top")));
        if (asset.endsWith("bulldozer")) {
            edits.add(new Command(Action.OBJECT_VALUE, "mirror", "false"));
            edits.add(new Command(Action.OBJECT_VALUE, "mirror", "true"));
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1280, 800);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 240_000_000_000L;
                final Map<String, Integer> stages = new LinkedHashMap<>();
                int index, settle;
                long started, published, visible, revision, startFrame;
                GpuTerrain.EditorObject before, shown;
                List<Matrix4> beforeTransforms, shownTransforms;
                double slowestFrame;
                private GpuTerrain terrain() throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
                    return (GpuTerrain) field.get(this);
                }
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        long frameStart = System.nanoTime();
                        super.render();
                        if (started != 0) { slowestFrame = Math.max(slowestFrame, (System.nanoTime() - frameStart) / 1e6); }
                        assertTrue(System.nanoTime() < deadline, "Edit latency test stalled at " + index);
                        GpuTerrain terrain = terrain();
                        if (started == 0) {
                            if (GpuBoardTestUi.loading(this) || terrain.busy()
                                  || !previewsReady(GpuBoardTestUi.stage().getRoot().findActor("editor-library"))) { return; }
                            if (++settle < 12) { return; }
                            if (index == edits.size()) { Gdx.app.exit(); return; }
                            revision = source.editorState().revision(); startFrame = frames();
                            before = terrain.editorObjects(at).getFirst(); shown = null;
                            beforeTransforms = objectTransforms(terrain, at); shownTransforms = null;
                            started = System.nanoTime(); published = 0; visible = 0; slowestFrame = 0; stages.clear();
                            long generation = source.takeFrame().boardGeneration();
                            if (index == 7) {
                                for (int tick = 1; tick <= 80; tick++) {
                                    source.editorValue(new Command(Action.OBJECT_VALUE, "x", Double.toString(.2 + .15 * tick / 80)), tick == 80, generation);
                                }
                            } else { source.editorCommand(edits.get(index), generation); }
                        } else {
                            if (source.editorState().revision() != revision && published == 0) { published = System.nanoTime(); }
                            String progress = terrain.buildDetails().stream().map(status -> status.step().task()).toList().toString();
                            stages.merge(progress, 1, Integer::sum);
                            var displayed = terrain.editorObjects(at).getFirst();
                            boolean objectEdit = edits.get(index).action() == Action.OBJECT_VALUE || index == 8;
                            var transforms = objectTransforms(terrain, at);
                            if (published != 0 && visible == 0 && !sameTransforms(beforeTransforms, transforms)) {
                                visible = System.nanoTime(); shown = displayed;
                                shownTransforms = transforms;
                                if (objectEdit) {
                                    assertTrue(terrain.busy(), "An object edit should be visible before terrain installation");
                                    var feature = source.takeFrame().scene().tile(at).features().stream()
                                          .filter(f -> f.decoration() != null && f.asset().equals(asset)).findFirst().orElseThrow();
                                    float x = BoardGeometry.centerX(at) + feature.x() * BoardGeometry.hexScale();
                                    float y = BoardGeometry.centerY(at) + feature.y() * BoardGeometry.hexScale();
                                    Ray ray = new Ray(new Vector3(x, y, 300), new Vector3(0, 0, -1));
                                    assertEquals("car", terrain.editorSectionPick(at, ray), "Side-view picking follows the visible pose");
                                    assertEquals("car", terrain.decorationHit(source.takeFrame().scene(), ray).id(), "Board picking follows the visible pose");
                                }
                            }
                            if (published == 0 || terrain.busy() || !source.takeFrame().scene().tile(at)
                                  .equals(terrain.presentation(source.takeFrame().scene()).tile(at))) { return; }
                            if (index == 7 && Math.abs(source.editorState().objects().getFirst().x() - .35) > .00001) { return; }
                            if (index == 8) { assertEquals(.2, source.editorState().objects().getFirst().x(), .00001, "Undo restores the whole drag"); }
                            assertFalse(source.editorState().message().startsWith("Cannot"), source.editorState().message());
                            assertFalse(source.editorState().message().contains("Unknown"), source.editorState().message());
                            if (objectEdit) {
                                assertNotNull(shown, "The object must visibly change");
                                assertTrue(shown.bounds().min.epsilonEquals(displayed.bounds().min, .01f), "Preview and installed minimum agree");
                                assertTrue(shown.bounds().max.epsilonEquals(displayed.bounds().max, .01f), "Preview and installed maximum agree");
                                assertEquals(shown.anchorLevel(), displayed.anchorLevel(), .001f, "Support height survives installation");
                                assertTrue(sameTransforms(shownTransforms, transforms), "Every composition part keeps its visible pose after installation");
                            }
                            System.out.printf(java.util.Locale.ROOT, "EDITOR LATENCY %s %s capture=%.1fms visible=%.1fms installed=%.1fms frames=%d maxRender=%.1fms stages=%s%n",
                                  asset, edits.get(index), (published - started) / 1e6, (visible == 0 ? System.nanoTime() - started : visible - started) / 1e6,
                                  (System.nanoTime() - started) / 1e6,
                                  frames() - startFrame, slowestFrame, stages);
                            index++; started = 0; settle = 0;
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Editor latency", failure.get()); }
    }

    /** Inspect the actual instances shared by both rendering cameras and picking, including symmetric bounds. */
    private static List<Matrix4> objectTransforms(GpuTerrain terrain, Coords at) throws ReflectiveOperationException {
        var lookup = GpuTerrain.class.getDeclaredMethod("editorTile", Coords.class); lookup.setAccessible(true);
        Object tile = lookup.invoke(terrain, at);
        var props = tile.getClass().getDeclaredField("props"); props.setAccessible(true);
        List<Matrix4> result = new ArrayList<>();
        for (Object prop : (List<?>) props.get(tile)) {
            var id = prop.getClass().getDeclaredField("decorationId"); id.setAccessible(true);
            if (!"car".equals(id.get(prop))) { continue; }
            var instance = prop.getClass().getDeclaredMethod("instance"); instance.setAccessible(true);
            result.add(new Matrix4(((ModelInstance) instance.invoke(prop)).transform));
        }
        return result;
    }

    private static boolean sameTransforms(List<Matrix4> first, List<Matrix4> second) {
        if (first.size() != second.size()) { return false; }
        for (int i = 0; i < first.size(); i++) {
            for (int j = 0; j < 16; j++) {
                if (Math.abs(first.get(i).val[j] - second.get(i).val[j]) > .001f) { return false; }
            }
        }
        return true;
    }

    private static boolean previewsReady(Actor actor) {
        if (actor instanceof Image image && image.getDrawable() == null) { return false; }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) { if (!previewsReady(child)) { return false; } }
        }
        return true;
    }
}
