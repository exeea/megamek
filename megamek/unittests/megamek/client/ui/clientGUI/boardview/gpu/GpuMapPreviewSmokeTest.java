/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.event.InputEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The map preview's HUD on the real view (the user's decisions of 2026-10-03): the hovered hex's card lists the hex and
 * the terrains a player sees, without the automated inclines of the rise beside it or terrain codes; and the preview
 * measures line of sight with MegaMek's ruler, whose line the board draws.
 */
@Tag("on-demand")
class GpuMapPreviewSmokeTest {
    /** Light woods at the foot of a two-level rise. */
    private static final Coords WOODS = new Coords(3, 3);

    @Test
    void theHexCardAndTheRulersLineShowOverThePreview() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows the Swing ruler");
        AtomicReference<GpuMapSource> source = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Game game = new Game();
            Hex[] hexes = new Hex[81];
            for (int y = 0; y < 9; y++) {
                for (int x = 0; x < 9; x++) {
                    Coords coords = new Coords(x, y);
                    hexes[y * 9 + x] = new Hex(x <= 2 ? 2 : 0, coords.equals(WOODS) ? "woods:1;foliage_elev:2" : "",
                          "grass", coords);
                }
            }
            game.setBoard(new Board(9, 9, hexes));
            source.set(new GpuMapSource(game, null, null));
        });
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1200, 850);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var view = new GpuBattleView(source.get());
                try {
                    view.create();
                    GpuBoardTestUi.present(view);
                    view.boardCamera.center(BoardGeometry.center(WOODS, 0));
                    SwingUtilities.invokeAndWait(() -> source.get().setHover(WOODS));
                    settle(view, source.get());
                    List<String> card = texts(GpuBoardTestUi.stage().getRoot().findActor("map-hex"));
                    assertEquals(List.of("HEX 0404", "Level 0 · grass", "Light woods", "TF 50",
                          "Woods/Jungle elevation: 2"), card, "The hex and what a player sees there");
                    GpuBoardTestUi.capture(new File(output, "map-preview-card.png"));

                    source.get().measure(new Coords(5, 7), InputEvent.CTRL_DOWN_MASK, Float.NaN);
                    settle(view, source.get());
                    assertTrue(texts(GpuBoardTestUi.stage().getRoot().findActor("map-hint"))
                          .contains(Messages.getString("GpuBoard.hud.hint.completeLos")), "The status line waits");
                    source.get().measure(new Coords(5, 1), 0, Float.NaN);
                    settle(view, source.get());
                    var field = GpuMapSource.class.getDeclaredField("ruler");
                    field.setAccessible(true);
                    RulerDialog ruler = (RulerDialog) field.get(source.get());
                    assertTrue(ruler.isVisible(), "A plain click ends the measurement and shows the ruler");
                    assertFalse(source.get().takeFrame().scene().tactical().fills().isEmpty(),
                          "and its line is on the board");
                    GpuBoardTestUi.capture(new File(output, "map-preview-ruler.png"));
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    view.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        SwingUtilities.invokeAndWait(() -> source.get().close());
        if (failure.get() != null) { throw new AssertionError("Map preview HUD", failure.get()); }
    }

    /** Lets the source's EDT work land, then draws a few frames with it. */
    private static void settle(GpuBattleView view, GpuMapSource source) throws Exception {
        SwingUtilities.invokeAndWait(source::refresh);
        for (int frame = 0; frame < 3; frame++) {
            view.render();
        }
    }

    /** The non-empty label texts under an actor, in drawing order. */
    private static List<String> texts(Actor actor) {
        List<String> texts = new ArrayList<>();
        if (actor instanceof Label label && !label.getText().isEmpty()) {
            texts.add(label.getText().toString());
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> texts.addAll(texts(child)));
        }
        return texts;
    }
}
