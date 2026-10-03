/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiTheme.ACCENT;
import static megamek.client.ui.gdx.UiTheme.AMBER;
import static megamek.client.ui.gdx.UiTheme.CORAL;
import static megamek.client.ui.gdx.UiTheme.FILL;
import static megamek.client.ui.gdx.UiTheme.MAIN;
import static megamek.client.ui.gdx.UiTheme.MINT;
import static megamek.client.ui.gdx.UiTheme.MUTED;
import static megamek.client.ui.gdx.UiTheme.QUIET;
import static megamek.client.ui.gdx.UiTheme.RAIL;
import static megamek.client.ui.gdx.UiTheme.TEXT;
import static megamek.client.ui.gdx.UiTheme.alpha;
import static megamek.client.ui.gdx.UiTheme.pad;
import static megamek.client.ui.gdx.UiTheme.rgba;
import static megamek.client.ui.gdx.UiTheme.tint;

import java.io.File;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TiledDrawable;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTheme.EdgeBox;
import megamek.client.ui.gdx.UiTheme.HudFrame;
import megamek.common.Configuration;

/**
 * The battle window's skin: the toolkit's UiTheme, plus the battle HUD's own drawables and enemy icon, the 3D hex
 * labels' font and the styles of the tuning model's controls, registered into the theme's Skin; the theme owns every
 * texture and font.
 */
final class GpuBoardSkin implements Disposable {
    // Range bands of the solution card (#solution .rng).
    static final Color BAND_SHORT = Color.valueOf("7DF0D1");
    static final Color BAND_MEDIUM = AMBER;
    static final Color BAND_LONG = Color.valueOf("9FB2F0");
    /** The hex labels' font size in texels: UiTheme.font rasterises the 15 units of default-font at 4x. */
    static final int FONT_RESOLUTION = 60;
    /** The Contacts utility's icon-enemy: the skull of mm-data's misc images, black on transparent. */
    private static final String ENEMY_ICON = "challenge_estimate_full.png";

    /** The theme's Skin, which holds the battle entries as well. */
    final Skin skin;
    private final UiTheme theme;

    GpuBoardSkin() {
        theme = new UiTheme(Configuration.fontsDir());
        skin = theme.skin;
        // Noto Sans stays the default font: the 3D hex labels and the window's loading message use it.
        BitmapFont font = theme.font("default-font", "Noto Sans/NotoSans-Regular.ttf", 15);
        theme.add("default", new Label.LabelStyle(font, TEXT), Label.LabelStyle.class);
        Pixmap enemy = new Pixmap(new FileHandle(new File(Configuration.miscImagesDir(), ENEMY_ICON)));
        try {
            theme.addIcon("enemy", enemy);
        } finally {
            enemy.dispose();
        }
        battleDrawables();
        tuningStyles();
    }

    /**
     * The styles of the tuning model's controls, under the names GpuBoardTuning uses (the loading message's title
     * takes "kicker" too). GpuTuningPanel shows the model in the hud-v3 look and never draws these widgets, so each
     * style only gives them the hud-small face and the parts their constructors and layout need.
     */
    private void tuningStyles() {
        BitmapFont font = skin.getFont("hud-small");
        theme.add("small", new Label.LabelStyle(font, MUTED), Label.LabelStyle.class);
        theme.add("kicker", new Label.LabelStyle(font, ACCENT), Label.LabelStyle.class);
        theme.add("menu", new Label.LabelStyle(font, TEXT), Label.LabelStyle.class);
        theme.add("menu-control", new TextButton.TextButtonStyle(null, null, null, font),
              TextButton.TextButtonStyle.class);
        theme.add("menu", new CheckBox.CheckBoxStyle(skin.getDrawable("icon-checkbox-off"),
              skin.getDrawable("icon-checkbox-on"), font, TEXT), CheckBox.CheckBoxStyle.class);
        var items = new com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(font, TEXT, TEXT,
              skin.getDrawable("row-selected"));
        theme.add("menu", new SelectBox.SelectBoxStyle(font, TEXT, null,
              skin.get("hud-list", ScrollPane.ScrollPaneStyle.class), items), SelectBox.SelectBoxStyle.class);
        theme.add("menu", new Slider.SliderStyle(skin.getDrawable("rule"), skin.getDrawable("rule")),
              Slider.SliderStyle.class);
        theme.add("menu", new TextTooltip.TextTooltipStyle(skin.get("hud-small", Label.LabelStyle.class), null),
              TextTooltip.TextTooltipStyle.class);
    }

    /**
     * The battle HUD's own drawables: the Tactical View chip's rails, the inspected and the selected weapon rows, the
     * dock's waiting box, target pills and letters, the log's discs, the fire preview's boxes and toggles, the unit
     * panel's marks and boxes, and the heat hatch.
     */
    private void battleDrawables() {
        Texture white = skin.get("white", Texture.class);
        // The Tactical View chip (#mapchip): rails without corners; 2-unit rails plus 6 / 8 / 6 / 14 padding.
        HudFrame rails = pad(new HudFrame(white, rgba(22, 30, 30, .92f), RAIL, null, 0, null), 8, 8);
        rails.setLeftWidth(14);
        theme.add("panel-rails", rails, Drawable.class);
        // The inspected enemy row (.row.insp): the row's fill within coral edges; a friendly unit's edges are mint.
        theme.box("row-foe", rgba(255, 255, 255, .015f), CORAL, 1, 3, null, 7, 10, 0);
        theme.box("row-friend", rgba(255, 255, 255, .015f), MINT, 1, 3, null, 7, 10, 0);
        // The selected weapon row (.wrow.sel), padded like row so the two swap without a relayout.
        theme.box("row-weapon-selected", Color.CLEAR, Color.WHITE, 2, 3, null, 7, 10, 0);
        // The dock's waiting box (#dock .waiting).
        theme.box("waiting", rgba(255, 255, 255, .05f), alpha(QUIET, .3f), 1, 3, null, 0, 0, 46);
        // Target pills (.pill, .pill.on).
        theme.box("pill", Color.CLEAR, alpha(QUIET, .35f), 1, 3, null, 3, 8, 0).setLeftWidth(3);
        theme.box("pill-on", Color.valueOf("E2EDE6"), Color.WHITE, 1, 3, null, 3, 8, 0).setLeftWidth(3);
        // The letter square (.tcard .L, .pill i), tinted to each target's colour (GpuHudKit.letter).
        theme.box("letter-primary", MAIN, null, 0, 2, null, 0, 0, 19).setMinWidth(19);
        // The log's event discs (.ev .no, .ev.red .no).
        theme.box("disc", alpha(MINT, .15f), null, 0, 14, null, 0, 0, 28).setMinWidth(28);
        theme.box("disc-foe", alpha(CORAL, .15f), null, 0, 14, null, 0, 0, 28).setMinWidth(28);
        // The fire preview (.from, .prow.open, .pdetail): the "from" block's faint fill with a 3-unit bar, ink2 for
        // the current hex and mint for a destination; the open row's coral fill; and its detail's coral edges at
        // half alpha, open at the top under the row.
        Color from = rgba(255, 255, 255, .03f);
        theme.add("from-current", new EdgeBox(white, from, ACCENT, 0, 0, 0, 3), Drawable.class);
        theme.add("from-destination", new EdgeBox(white, from, MINT, 0, 0, 0, 3), Drawable.class);
        theme.add("preview-open", skin.newDrawable("white", alpha(CORAL, .05f)), Drawable.class);
        theme.add("preview-detail", new EdgeBox(white, null, alpha(CORAL, .5f), 0, 1, 1, 1), Drawable.class);
        // The fire preview's pressed Outgoing and Incoming toggles (.b.mini.out, .b.mini.in): the mini button's filled
        // face in mint or coral at 90 %.
        Drawable mini = skin.get("hud-mini", TextButton.TextButtonStyle.class).checked;
        theme.add("button-mini-out", skin.newDrawable(mini, tint(MINT, FILL, .9f)), Drawable.class);
        theme.add("button-mini-in", skin.newDrawable(mini, tint(CORAL, FILL, .9f)), Drawable.class);
        // The unit panel (unit panel design 5.1, 5.4, 5.6): white discs and rings its views tint (hit boxes .hc i,
        // ammunition dots .pips i, tab dots), the hit box (.hitbox), the count badge (.badge), the CASE tag (.case)
        // and the ARMOR inspector (.insp) with its white left bar.
        theme.box("unit-disc-10", Color.WHITE, null, 0, 5, null, 0, 0, 10).setMinWidth(10);
        theme.box("unit-ring-10", Color.CLEAR, Color.WHITE, 1.5f, 5, null, 0, 0, 10).setMinWidth(10);
        theme.box("unit-disc-6", Color.WHITE, null, 0, 3, null, 0, 0, 6).setMinWidth(6);
        theme.box("unit-disc-5", Color.WHITE, null, 0, 2.5f, null, 0, 0, 5).setMinWidth(5);
        theme.box("unit-ring-5", Color.CLEAR, Color.WHITE, 1, 2.5f, null, 0, 0, 5).setMinWidth(5);
        theme.box("unit-hitbox", Color.CLEAR, rgba(174, 187, 180, .28f), 1, 8, null, 9, 12, 0);
        theme.box("unit-badge", Color.CLEAR, rgba(174, 187, 180, .35f), 1, 3, null, 0, 4, 13).setMinWidth(20);
        theme.box("unit-case", Color.CLEAR, alpha(MINT, .55f), 1, 2, null, 0, 4, 13);
        Drawable inspector = theme.box("unit-inspector", rgba(255, 255, 255, .02f), rgba(174, 187, 180, .2f), 1, 3,
              Color.WHITE, 9, 12, 0);
        inspector.setLeftWidth(15);
        inspector.setBottomHeight(8);

        // The prototype's 135-degree stripes: 95% and 30% amber, three texels each along the diagonal.
        Pixmap stripes = new Pixmap(6, 6, Pixmap.Format.RGBA8888);
        stripes.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 6; x++) {
                stripes.drawPixel(x, y, (x + y) % 6 < 3 ? 0xFFFFFFF2 : 0xFFFFFF4D);
            }
        }
        Texture hatch = new Texture(stripes);
        stripes.dispose();
        theme.add("hatch-texture", hatch, Texture.class);
        theme.add("hatch", new TiledDrawable(new TextureRegion(hatch)).tint(AMBER), Drawable.class);
    }

    @Override
    public void dispose() {
        theme.dispose();
    }
}
