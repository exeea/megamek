/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;

/**
 * The start screen's question at the first start on a computer with two graphics cards ({@link GpuGraphicsCard#asks}):
 * which card does the program draw with? A kit dialog over the art left of the menu, with one button per card in
 * Windows' own name, the card this run draws with first and marked; it has no close button, and Escape and the scrim
 * leave it open. A card is saved where the board's Tuning panel saves it; the card in use closes the question, the
 * other one starts the program again, as a card applies only from a start.
 */
final class StartGraphicsCardChoice {
    /** Wide enough for two cards' names side by side on the footer. */
    private static final String WIDTH = "dialog.lg";
    private final UiDialog dialog;
    private final List<UiButton> buttons;
    /** Runs once the card in use is chosen and the question has closed; null for nothing. */
    private Runnable then;

    /**
     * @param program  the program's name, such as "MegaMek"
     * @param names    the cards' names, INTEGRATED's then DISCRETE's ({@link GpuGraphicsCard#cards})
     * @param inUse    the card this run draws with
     * @param menuLeft the menu column's left edge on the stage: the question is centred on the art left of it
     * @param relaunch starts the program again on the chosen card, on the Swing thread once the card is saved
     */
    StartGraphicsCardChoice(UiKit kit, String program, List<String> names, GpuGraphicsCard inUse,
          DoubleSupplier menuLeft, Consumer<GpuGraphicsCard> relaunch) {
        dialog = new UiDialog(kit, Messages.getString("StartGraphicsCard.title"), null).width(WIDTH);
        dialog.card().setName("start-graphics-card");
        Label text = kit.typeLabel(Messages.getString("StartGraphicsCard.text", program), "body", "text.primary");
        text.setWrap(true);
        dialog.body().add(text).growX().minWidth(0);
        UiButton[] made = new UiButton[2];
        for (GpuGraphicsCard card : GpuGraphicsCard.values()) {
            String name = names.get(card.ordinal());
            Runnable chosen = () -> choose(card, inUse, relaunch);
            made[card.ordinal()] = card == inUse
                  ? dialog.primary(Messages.getString("StartGraphicsCard.inUse", name), chosen)
                  : dialog.secondary(name, chosen);
            made[card.ordinal()].setName("graphics-card-" + card.name());
        }
        buttons = List.of(made);
        float width = DesignTokens.shipped().size().get(WIDTH);
        dialog.layer().getCell(dialog.card()).padRight(new Value() {
            @Override
            public float get(Actor context) {
                // Over the art when it has room for the card, else over the middle of the window
                float left = (float) menuLeft.getAsDouble();
                return left >= width ? Math.max(0, dialog.layer().getWidth() - left) : 0;
            }
        });
    }

    private void choose(GpuGraphicsCard card, GpuGraphicsCard inUse, Consumer<GpuGraphicsCard> relaunch) {
        dialog.close();
        // Saved as the Tuning panel's Graphics card saves it (GpuBoardTuning#applyGraphicsCard)
        SwingUtilities.invokeLater(() -> {
            GUIPreferences.getInstance().setBoardGraphicsCard(card.name());
            if (card != inUse) {
                relaunch.accept(card);
            }
        });
        if (card == inUse && then != null) {
            then.run();
        }
    }

    /** Shows the question over everything on {@code stage}. */
    void show(Stage stage, boolean motion) {
        dialog.show(stage, motion);
    }

    boolean isOpen() {
        return dialog.isOpen();
    }

    /** Runs {@code next} once the card in use is chosen, as the welcome waits for the answer. */
    void then(Runnable next) {
        then = next;
    }

    UiDialog dialog() {
        return dialog;
    }

    /** The cards' buttons, INTEGRATED's then DISCRETE's. */
    List<UiButton> buttons() {
        return buttons;
    }
}
