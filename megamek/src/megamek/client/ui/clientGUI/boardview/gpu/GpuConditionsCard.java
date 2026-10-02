/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * Planetary conditions card beside the forces (C.1 G1, plan B11): the lines of MegaMek's planetary conditions
 * overlay, shown while its View-menu preference is on. The close button runs that View-menu item.
 */
final class GpuConditionsCard implements GpuHud.Component {
    private static final String FONT = "hud-body";

    private final UiKit ui;
    private final Table root;
    private final Table lines = new Table();
    private final UiButton close;
    private List<String> shown;
    /** The View-menu item's command of the current frame, or null when it is missing or disabled. */
    private Runnable toggle;

    GpuConditionsCard(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        root = ui.panel();
        root.setName("conditions-card");
        close = ui.closeButton(() -> {
            if (toggle != null) {
                toggle.run();
            }
        });
        close.setName("conditions-close");
        root.add(ui.header(Messages.getString("GpuBoard.hud.conditions.title"), null, close)).growX().row();
        lines.top().left();
        lines.defaults().left().growX().minWidth(0);
        root.add(lines).growX().pad(0, 14, 12, 14);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        root.setVisible(inputs.preferences().conditionsVisible());
        List<String> next = inputs.frame().panels().phase().conditions();
        if (!next.equals(shown)) {
            shown = next;
            lines.clearChildren();
            BitmapFont font = ui.skin.getFont(FONT);
            for (String line : next) {
                String text = drawable(line, font);
                // A line of indicators only (labels and values off) may have nothing left to draw.
                if (!text.isEmpty()) {
                    Label label = ui.label(text, FONT, 12.5f, UiTheme.ACCENT);
                    label.setEllipsis(true);
                    // CSS line-height 1.35
                    lines.add(label).height(17).row();
                }
            }
        }
        BoardScene.Command command = GpuBoardActions.menuItem(inputs.frame().globalCommands(),
              ClientGUI.VIEW_PLANETARY_CONDITIONS_OVERLAY);
        toggle = command == null || !command.enabled() ? null : command.action();
        close.setDisabled(toggle == null);
    }

    /**
     * The line as {@code font} can draw it. The indicator characters that no face of the skin's font chain has are
     * left out instead of drawing as blanks: the emoji outside the Basic Multilingual Plane (a BitmapFont keys its
     * glyphs by UTF-16 char; the thermometer and flame of the temperature, the eye and wind face of fog and blowing
     * sand) and such symbols as the fog's and pitch black's full block, the hail's shade or the calm wind's flag.
     */
    private static String drawable(String line, BitmapFont font) {
        BitmapFont.BitmapFontData data = font.getData();
        StringBuilder text = new StringBuilder();
        line.codePoints().filter(point -> Character.isWhitespace(point)
              || (Character.isBmpCodePoint(point) && hasGlyph(data, (char) point))).forEach(text::appendCodePoint);
        return text.toString().strip();
    }

    private static boolean hasGlyph(BitmapFont.BitmapFontData data, char character) {
        BitmapFont.Glyph glyph = data.getGlyph(character);
        return glyph != null && glyph != data.missingGlyph;
    }
}
