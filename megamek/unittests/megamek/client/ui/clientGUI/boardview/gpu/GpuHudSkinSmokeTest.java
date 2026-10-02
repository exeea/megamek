/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.gdx.UiTheme;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The hud-v3 skin: Roboto named instances, style names, glyphs, texture ownership, and swatches to compare. */
@Tag("on-demand")
class GpuHudSkinSmokeTest {
    private static final List<String> BUTTON_STYLES = List.of("hud", "hud-main", "hud-utility", "hud-mini", "hud-seg",
          "hud-menu-row");
    private static final List<String> DRAWABLES = List.of("panel", "panel-foe", "panel-pop", "panel-rails", "bracket",
          "bracket-over", "bracket-checked", "button", "button-over", "button-checked", "button-disabled",
          "button-main", "button-main-over", "button-main-disabled", "row", "row-over", "row-selected", "row-foe",
          "row-weapon-selected", "field", "field-focused", "select", "waiting", "chip", "chip-warn", "chip-bad",
          "pill", "pill-on", "letter", "letter-on", "badge", "disc", "disc-foe", "hatch", "rule");
    /**
     * "AS7-D" and the other non-ASCII characters of hud-v3's and the GPU HUD's texts. Roboto has most of them; the
     * arrows U+2190-U+2193, the marks U+25C2 and U+2715 and the stars U+2605 and U+2606 come from GpuBoardSkin's
     * fallback faces.
     */
    private static final String HUD_CHARACTERS = "AS7-D\u2192\u2190\u2191\u2193\u00B7\u2014\u2013\u2212\u00D7"
          + "\u25C2\u2715\u2265\u2039\u203A\u2026\u00B0\u201C\u201D\u2019\u2605\u2606";
    /** Marks thin enough to fade at some sub-pixel positions when drawn small: hyphen, middle dot, one, t. */
    private static final String THIN_MARKS = "-\u00B71t";
    /** The fallback arrows, whose shafts are about as thin as the hyphen. */
    private static final String ARROWS = "\u2192\u2190\u2191\u2193";
    /**
     * The share of an arrow's quad, from its tail along its axis, in which it is measured: its shaft alone. The head
     * takes about the other half, and it stays bright when the shaft fades, so the cell's brightest pixel says nothing
     * about the shaft.
     */
    private static final float SHAFT = .4f;
    /** Prototype strings (shots 02-06, 10) with the characters above, in every hud font of the glyph swatch. */
    private static final String GLYPH_SAMPLE = "AS7-D \u00B7 0 \u2192 4 \u00B7 Alt+\u2191/\u2193 \u00B7 \u2190 \u00B7 "
          + "\u221220 \u00B7 1\u00D720 \u2014 \u2013 \u00B7 2d6 \u2265 7 \u00B7 CT \u2715 \u00B7 \u2039 \u203A "
          + "\u2026 30\u00B0 \u201CA\u201D it\u2019s \u00B7 \u25C2 4 weapons \u00B7 \u2605 PRIMARY \u2606";
    /** GpuHudKit's model line: hud-small drawn at 10 units, the prototype's 10px variant line. */
    private static final float MODEL_LINE = 10;
    private static final int CELL = 30;
    private static final int OFFSETS = 8;
    /** A character at its font's size counts as drawn when its brightest pixel reaches this share of the text color. */
    private static final float VISIBLE = .4f;
    /**
     * A thin mark at its font's size peaks at no less than this share of the text color at every sub-pixel offset.
     * Glyphs are filtered, not hinted to the pixel grid: a 0.9-pixel stroke centred between two rows peaks at 0.45 even
     * when filtered perfectly.
     */
    private static final float THIN_VISIBLE = .35f;
    /** The same for a face drawn below its size: the model line samples mip level 1 at 2.3:1, which leaves gaps. */
    private static final float MODEL_LINE_VISIBLE = .25f;

    /**
     * One cell of the glyph grid: a character in a hud font at a size in units, shifted by a sub-pixel offset; thin
     * cells hold THIN_MARKS and ARROWS drawn at every offset.
     */
    private record Cell(String font, char glyph, float size, float offset, boolean thin) { }

    @Test
    void robotoInstancesStylesSwatchAndTextureOwnership() {
        onGl(GpuHudSkinSmokeTest::check);
    }

    /**
     * Every font of the skin has a glyph for each character of HUD_CHARACTERS, and each hud font draws them visibly at
     * 1 pixel per unit; the thin marks and the arrows' shafts stay visible at eight sub-pixel offsets, at the font's
     * size and at the kit's model line. Before the hud glyphs were mipmapped with transparent white around their ink,
     * the model line lost the hyphen of "AS7-D" at some offsets; arrows from Bitter Medium at 0.65 of the font's size
     * lost their shafts at some offsets in hud-utility, hud-sub and hud-mini.
     */
    @Test
    void everyFontDrawsHudCharactersAtEverySubPixelOffset() {
        onGl(() -> {
            GpuBoardSkin theme = new GpuBoardSkin();
            try {
                Skin skin = theme.skin;
                for (ObjectMap.Entry<String, BitmapFont> font : skin.getAll(BitmapFont.class)) {
                    BitmapFont.BitmapFontData data = font.value.getData();
                    // Laid out first, as a Label does, so incremental glyphs are created and uploaded.
                    new GlyphLayout(font.value, HUD_CHARACTERS);
                    for (char character : HUD_CHARACTERS.toCharArray()) {
                        BitmapFont.Glyph glyph = data.getGlyph(character);
                        String name = font.key + " U+" + Integer.toHexString(character).toUpperCase(Locale.ROOT);
                        assertTrue(glyph != null && glyph != data.missingGlyph, name + " has its own glyph");
                        assertTrue(glyph.width > 0 && glyph.height > 0, name + " has a non-empty region");
                        assertTrue(glyph.xadvance > 0, name + " advances the pen");
                    }
                }
                Set<Texture> pages = new LinkedHashSet<>();
                for (UiTheme.HudFont hud : UiTheme.HUD_FONTS) {
                    BitmapFont font = skin.getFont(hud.name());
                    // The glyph margins stay out of line metrics: a lone space measures its advance.
                    assertEquals(font.getData().getGlyph(' ').xadvance * font.getScaleX(),
                          new GlyphLayout(font, " ").width, 1e-4f, hud.name() + " space width");
                    font.getRegions().forEach(region -> pages.add(region.getTexture()));
                }
                // Filtering mixes a glyph's ink with the texels around it; transparent black would darken its edges.
                for (Texture page : pages) {
                    ByteBuffer bytes = page.getTextureData().consumePixmap().getPixels().duplicate();
                    IntBuffer texels = bytes.position(0).order(ByteOrder.BIG_ENDIAN).asIntBuffer();
                    while (texels.hasRemaining()) {
                        int rgba = texels.get();
                        assertTrue((rgba & 0xFF) > 0 || rgba == 0xFFFFFF00, () -> "hud glyph page texel "
                              + Integer.toHexString(rgba) + " is ink or transparent white");
                    }
                }
                Stage stage = new Stage(new ScreenViewport());
                try {
                    stage.addActor(glyphSwatch(skin));
                    capture(stage, 1, "hud-glyph-swatch.png");
                } finally {
                    stage.dispose();
                }
                drawsAtEveryOffset(skin);
            } finally {
                theme.dispose();
            }
        });
    }

    /** Runs the check on the GL thread of a hidden 1280x900 window and rethrows its failure. */
    private static void onGl(Runnable check) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Lwjgl3ApplicationConfiguration configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    check.run();
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) {
            throw new AssertionError("hud-v3 skin check failed", failure.get());
        }
    }

    /**
     * Draws one glyph per cell at 1 pixel per unit in two frames. In the first, each hud font draws HUD_CHARACTERS at
     * its size, and each character's brightest pixel must reach VISIBLE of the text color. In the second, each hud
     * font draws THIN_MARKS and ARROWS at its size, offset by 0 to 7 eighths of a pixel along both axes, and hud-small
     * draws them again on the kit's model line. At every offset a thin mark's brightest pixel, and an arrow's brightest
     * pixel in its shaft, must reach THIN_VISIBLE, or MODEL_LINE_VISIBLE on the model line.
     */
    private static void drawsAtEveryOffset(Skin skin) {
        List<List<Cell>> characters = new ArrayList<>();
        List<List<Cell>> offsets = new ArrayList<>();
        for (UiTheme.HudFont hud : UiTheme.HUD_FONTS) {
            List<Cell> row = new ArrayList<>();
            for (char character : HUD_CHARACTERS.toCharArray()) {
                row.add(new Cell(hud.name(), character, hud.size(), 0, false));
            }
            characters.add(row);
            offsets.add(atEveryOffset(THIN_MARKS, hud.name(), hud.size()));
            offsets.add(atEveryOffset(ARROWS, hud.name(), hud.size()));
        }
        offsets.add(atEveryOffset(THIN_MARKS, "hud-small", MODEL_LINE));
        offsets.add(atEveryOffset(ARROWS, "hud-small", MODEL_LINE));
        Map<Cell, Float> measured = new LinkedHashMap<>(peaks(skin, characters, "hud-glyph-characters.png"));
        measured.putAll(peaks(skin, offsets, "hud-glyph-offsets.png"));
        float faintest = 1;
        // The faintest peak of each thin mark and arrow shaft over its offsets, by font and size.
        Map<String, Map<Character, Float>> thin = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (Map.Entry<Cell, Float> entry : measured.entrySet()) {
            Cell cell = entry.getKey();
            float peak = entry.getValue();
            if (cell.thin()) {
                thin.computeIfAbsent(cell.font() + " at " + cell.size(), key -> new LinkedHashMap<>())
                      .merge(cell.glyph(), peak, Math::min);
            } else {
                faintest = Math.min(faintest, peak);
            }
            boolean scaledDown = cell.size() < hudFont(cell.font()).size();
            float least = !cell.thin() ? VISIBLE : scaledDown ? MODEL_LINE_VISIBLE : THIN_VISIBLE;
            if (peak < least) {
                failures.add(String.format(Locale.ROOT, "%s at %.1f U+%04X, offset %.3f, peaks at %.2f",
                      cell.font(), cell.size(), (int) cell.glyph(), cell.offset(), peak));
            }
        }
        System.out.printf(Locale.ROOT, "At 1 px per unit: faintest character peak %.2f of the text color; faintest"
              + " thin-mark and arrow-shaft peak over %d sub-pixel offsets: %s%n", faintest, OFFSETS, thin);
        assertTrue(failures.isEmpty(), "Glyphs drawn too faint at some offsets: " + failures);
    }

    /** Each of the characters in a hud font at a size, at OFFSETS sub-pixel offsets. */
    private static List<Cell> atEveryOffset(String characters, String font, float size) {
        List<Cell> cells = new ArrayList<>();
        for (int offset = 0; offset < OFFSETS; offset++) {
            for (char character : characters.toCharArray()) {
                cells.add(new Cell(font, character, size, offset / (float) OFFSETS, true));
            }
        }
        return cells;
    }

    /**
     * Draws the rows one glyph per cell, white on black at 1 pixel per unit, saves the frame and returns each cell's
     * peak as a share of the text color: its brightest pixel, or for a thin arrow its brightest pixel in its shaft.
     */
    private static Map<Cell, Float> peaks(Skin skin, List<List<Cell>> rows, String name) {
        int width = Gdx.graphics.getBackBufferWidth();
        int height = Gdx.graphics.getBackBufferHeight();
        assertTrue(rows.size() * CELL <= height && rows.stream().allMatch(row -> row.size() * CELL <= width),
              "cells fit");
        Map<Cell, Rectangle> regions = new LinkedHashMap<>();
        SpriteBatch batch = new SpriteBatch();
        Pixmap pixels;
        try {
            Gdx.gl.glViewport(0, 0, width, height);
            batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
            ScreenUtils.clear(0, 0, 0, 1);
            batch.begin();
            for (int row = 0; row < rows.size(); row++) {
                for (int column = 0; column < rows.get(row).size(); column++) {
                    Cell cell = rows.get(row).get(column);
                    BitmapFont font = skin.getFont(cell.font());
                    float scale = font.getScaleX();
                    font.getData().setScale(scale * cell.size() / hudFont(cell.font()).size());
                    // y is the top of the capitals; arrows and quotes stay within the cell.
                    font.draw(batch, String.valueOf(cell.glyph()), column * CELL + 6 + cell.offset(),
                          (row + 1) * CELL - 7 + cell.offset());
                    font.getData().setScale(scale);
                    boolean arrow = cell.thin() && ARROWS.indexOf(cell.glyph()) >= 0;
                    regions.put(cell, arrow ? shaft(font, cell.glyph())
                          : new Rectangle(column * CELL, row * CELL, CELL, CELL));
                }
            }
            batch.end();
            pixels = Pixmap.createFromFrameBuffer(0, 0, width, height);
        } finally {
            batch.dispose();
        }
        try {
            File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            assertTrue(directory.isDirectory() || directory.mkdirs());
            PixmapIO.writePNG(new FileHandle(new File(directory, name)), pixels, -1, true);
            Map<Cell, Float> peaks = new LinkedHashMap<>();
            for (Map.Entry<Cell, Rectangle> region : regions.entrySet()) {
                peaks.put(region.getKey(), peak(pixels, region.getValue()) / 255f);
            }
            return peaks;
        } finally {
            pixels.dispose();
        }
    }

    /**
     * The tail SHAFT of the quad that the font just drew for an arrow, read from the font's cache, which holds x, y,
     * color, u and v for each corner, the first corner lower left and the third upper right.
     */
    private static Rectangle shaft(BitmapFont font, char arrow) {
        float[] corners = font.getCache().getVertices(font.getData().getGlyph(arrow).page);
        Rectangle quad = new Rectangle(corners[0], corners[1], corners[10] - corners[0], corners[11] - corners[1]);
        return switch (arrow) {
            case '\u2192' -> quad.setWidth(quad.width * SHAFT);
            case '\u2190' -> quad.setX(quad.x + quad.width * (1 - SHAFT)).setWidth(quad.width * SHAFT);
            case '\u2191' -> quad.setHeight(quad.height * SHAFT);
            default -> quad.setY(quad.y + quad.height * (1 - SHAFT)).setHeight(quad.height * SHAFT);
        };
    }

    /**
     * The brightest coverage among the pixels whose centres lie in a region of white text on black, where the red
     * channel is the drawn coverage.
     */
    private static int peak(Pixmap pixels, Rectangle region) {
        int peak = 0;
        for (int y = (int) Math.ceil(region.y - .5f); y + .5f <= region.y + region.height; y++) {
            for (int x = (int) Math.ceil(region.x - .5f); x + .5f <= region.x + region.width; x++) {
                peak = Math.max(peak, pixels.getPixel(x, y) >>> 24);
            }
        }
        return peak;
    }

    /** GLYPH_SAMPLE in every hud font on a hud panel, each line after its font's name, then the kit's model line. */
    private static Table glyphSwatch(Skin skin) {
        Table lines = new Table();
        lines.setBackground(skin.getDrawable("panel"));
        lines.defaults().left().padRight(16).padBottom(4);
        for (UiTheme.HudFont font : UiTheme.HUD_FONTS) {
            lines.add(new Label(font.name(), skin, "hud-small"));
            lines.add(new Label(GLYPH_SAMPLE, skin, font.name())).row();
        }
        lines.add(new Label("hud-small at " + (int) MODEL_LINE, skin, "hud-small"));
        lines.add(label(skin, "AS7-D  WHM-6R  MAD-3R  PNT-9R  LCT-1V  TBR-C  KGC-000  BLR-1G  ARC-2K  LCT-1M",
              "hud-small", MODEL_LINE, UiTheme.MUTED, null));
        Table root = new Table();
        root.setFillParent(true);
        root.top().left().pad(20).add(lines);
        return root;
    }

    private static void check() {
        namedInstances();
        long start = System.nanoTime();
        GpuBoardSkin theme = new GpuBoardSkin();
        System.out.printf("GpuBoardSkin built in %.0f ms; GL renderer %s%n", (System.nanoTime() - start) / 1e6,
              Gdx.gl.glGetString(GL20.GL_RENDERER));
        Set<Texture> textures = new LinkedHashSet<>();
        Stage stage = new Stage(new ScreenViewport());
        try {
            Skin skin = theme.skin;
            for (UiTheme.HudFont font : UiTheme.HUD_FONTS) {
                assertNotNull(skin.get(font.name(), Label.LabelStyle.class).font, font.name());
            }
            BUTTON_STYLES.forEach(name -> assertNotNull(skin.get(name, TextButton.TextButtonStyle.class), name));
            assertNotNull(skin.get("hud", TextField.TextFieldStyle.class));
            DRAWABLES.forEach(name -> assertNotNull(skin.getDrawable(name), name));
            for (String icon : UiTheme.ICONS.keySet()) {
                TextureRegion region = ((TextureRegionDrawable) skin.getDrawable("icon-" + icon)).getRegion();
                assertEquals(region.getRegionWidth(), region.getRegionHeight(), icon + " keeps its em square");
            }
            Set<Texture> hudPages = new LinkedHashSet<>();
            for (UiTheme.HudFont font : UiTheme.HUD_FONTS) {
                skin.getFont(font.name()).getRegions().forEach(region -> hudPages.add(region.getTexture()));
            }
            assertSame(skin.getFont("hud-body").getRegion(0).getTexture(),
                  skin.getFont("hud-phase").getRegion(0).getTexture(), "hud fonts share their glyph pages");
            System.out.println("hud-* glyph pages: " + hudPages.size());

            stage.addActor(swatch(skin));
            stage.setKeyboardFocus(stage.getRoot().findActor("focused-field"));
            capture(stage, 1, "hud-skin-swatch.png");
            capture(stage, 1.5f, "hud-skin-swatch-150.png");

            textures.addAll(hudPages);
            skin.getAll(Texture.class).values().forEach(textures::add);
            skin.getFont("default-font").getRegions().forEach(region -> textures.add(region.getTexture()));
        } finally {
            stage.dispose();
            theme.dispose();
        }
        assertTrue(textures.size() > 20, "The skin's own textures were collected");
        for (Texture texture : textures) {
            assertEquals(0, texture.getTextureObjectHandle(), "GpuBoardSkin.dispose frees every texture it created");
        }
    }

    /** FreeType picks the named instance from the face index: Condensed Bold is narrower and darker than Regular. */
    private static void namedInstances() {
        Glyph regular = glyph(instance("hud-body"));
        Glyph condensedBold = glyph(instance("hud-name"));
        System.out.printf("'M' at 52 px: Regular advance %d ink %d; Condensed Bold advance %d ink %d%n",
              regular.advance(), regular.ink(), condensedBold.advance(), condensedBold.ink());
        assertTrue(condensedBold.advance() < regular.advance(), "Condensed Bold must be narrower than Regular");
        assertTrue(condensedBold.ink() > regular.ink(), "Condensed Bold must have more ink than Regular");
    }

    private record Glyph(int advance, long ink) { }

    private static Glyph glyph(int instance) {
        FreeTypeFontGenerator generator = UiTheme.generator(UiTestStage.FONTS, UiTheme.ROBOTO, instance);
        try {
            FreeTypeFontGenerator.GlyphAndBitmap result = generator.generateGlyphAndBitmap('M', 52, false);
            Pixmap pixels = result.bitmap.getPixmap(Pixmap.Format.RGBA8888, Color.WHITE, 1);
            try {
                long ink = 0;
                for (int y = 0; y < pixels.getHeight(); y++) {
                    for (int x = 0; x < pixels.getWidth(); x++) {
                        ink += pixels.getPixel(x, y) & 0xff;
                    }
                }
                return new Glyph(result.glyph.xadvance, ink / 255);
            } finally {
                pixels.dispose();
            }
        } finally {
            generator.dispose();
        }
    }

    private static int instance(String font) {
        return hudFont(font).instance();
    }

    private static UiTheme.HudFont hudFont(String name) {
        return UiTheme.HUD_FONTS.stream().filter(entry -> entry.name().equals(name)).findFirst().orElseThrow();
    }

    /** Every hud-v3 surface and state side by side, at the prototype's sizes in stage units. */
    private static Table swatch(Skin skin) {
        Table root = new Table();
        root.setFillParent(true);
        root.top().left().pad(20).defaults().left().top();

        Table panels = new Table();
        panels.defaults().top().padRight(20);
        Table forces = new Table();
        forces.setBackground(skin.getDrawable("panel"));
        forces.defaults().left().growX();
        forces.add(new Label(UiTheme.upper("Forces"), skin, "hud-title")).padBottom(8).row();
        forces.add(row(skin, "row-selected", "AS7-D", "Atlas", "Selected row")).padBottom(6).row();
        forces.add(row(skin, "row-over", "WHM-6R", "Warhammer", "Hovered row")).padBottom(6).row();
        forces.add(row(skin, "row", "MAD-3R", "Marauder", "Resting row")).padBottom(6).row();
        forces.add(row(skin, "row-foe", "TBR-C", "Timber Wolf", "Inspected enemy row")).padBottom(8).row();
        TextField search = new TextField("", skin, "hud");
        search.setMessageText("Search units...");
        forces.add(search).height(32).padBottom(6).row();
        TextField focused = new TextField("Focused field", skin, "hud");
        focused.setName("focused-field");
        forces.add(focused).height(32).padBottom(8).row();
        forces.add(new Image(skin.getDrawable("rule"))).height(1).padBottom(6).row();
        forces.add(new Label("5 pending / 5", skin, "hud-small"));
        panels.add(forces).width(300);

        Table foe = new Table();
        foe.setBackground(skin.getDrawable("panel-foe"));
        foe.defaults().left();
        foe.add(new Label(UiTheme.upper("Timber Wolf"), skin, "hud-heading")).row();
        foe.add(new Label("Coral corners mark an inspected enemy.", skin, "hud-small")).padBottom(10).row();
        foe.add(new Label(UiTheme.upper("Heat if fired"), skin, "hud-caption")).padBottom(6).row();
        Table heat = new Table();
        heat.add(new Image(skin.newDrawable("white", UiTheme.AMBER))).width(60).height(7);
        heat.add(new Image(skin.getDrawable("hatch"))).width(110).height(7).padLeft(2);
        heat.add(new Image(skin.newDrawable("white", new Color(1, 1, 1, .09f)))).growX().height(7).padLeft(2);
        foe.add(heat).growX().padBottom(12).row();
        Table modes = new Table();
        modes.defaults().growX().uniformX().padRight(6);
        modes.add(button(skin, "hud-seg", "Formation", false, false));
        modes.add(button(skin, "hud-seg", "Weight", false, true));
        modes.add(button(skin, "hud-seg", "Status", true, false)).padRight(0);
        foe.add(modes).growX().padBottom(10).row();
        foe.add(button(skin, "hud-mini", UiTheme.upper("Next pending"), false, false)).right();
        panels.add(foe).width(300);

        Table pop = new Table();
        pop.setBackground(skin.getDrawable("panel-pop"));
        pop.defaults().left().growX();
        pop.add(new Label(UiTheme.upper("King Crab"), skin, "hud-name")).padLeft(14).row();
        pop.add(new Label("Popover on the opaque surface", skin, "hud-small")).padLeft(14).padBottom(6).row();
        pop.add(menuRow(skin, "Resting item", false, false)).row();
        pop.add(menuRow(skin, "Hovered item", true, false)).row();
        pop.add(menuRow(skin, "Keyboard item", false, true)).row();
        TextButton unavailable = menuRow(skin, "Unavailable item", false, false);
        unavailable.setDisabled(true);
        pop.add(unavailable).row();
        panels.add(pop).width(260);
        panels.add(weapons(skin)).width(300).padRight(0);
        root.add(panels).row();

        Table utilities = new Table();
        utilities.defaults().padRight(8);
        utilities.add(utility(skin, "tactical", "Tactical view", false, false));
        utilities.add(utility(skin, "map", "Map", false, true));
        utilities.add(utility(skin, "report", "Log", true, false));
        utilities.add(utility(skin, "help", "Help", false, false));
        // Hovering a pressed utility or button shows the hover look, as in proto3.css.
        utilities.add(utility(skin, "settings", "Settings", true, true));
        utilities.add(utility(skin, "menu", "Menu", false, false)).padRight(28);
        utilities.add(sub(skin, button(skin, "hud", UiTheme.upper("Walk"), false, false), "3 MP",
              UiTheme.MUTED)).width(96);
        utilities.add(button(skin, "hud", UiTheme.upper("Run"), true, false)).width(96);
        utilities.add(sub(skin, button(skin, "hud", UiTheme.upper("Jump"), false, true), "5 MP",
              UiTheme.FILL_MUTED)).width(96);
        utilities.add(button(skin, "hud", UiTheme.upper("Pressed"), true, true)).width(96);
        TextButton disabled = button(skin, "hud", UiTheme.upper("Twist"), false, false);
        disabled.setDisabled(true);
        utilities.add(disabled).width(96);
        root.add(utilities).padTop(20).row();

        Table commits = new Table();
        commits.defaults().padRight(8).height(46);
        commits.add(button(skin, "hud-main", UiTheme.upper("Confirm move"), false, false)).width(300);
        commits.add(button(skin, "hud-main", UiTheme.upper("Hovered"), true, false)).width(200);
        TextButton ghost = button(skin, "hud-main", UiTheme.upper("Choose an attack"), false, false);
        ghost.setDisabled(true);
        commits.add(ghost).width(240);
        Label waiting = new Label(UiTheme.upper("Waiting for opponent"),
              style(skin, "hud-main", Color.valueOf("8A9593"), "waiting"));
        waiting.setAlignment(Align.center);
        commits.add(waiting).width(300);
        root.add(commits).padTop(12).row();

        Table type = new Table();
        type.defaults().left().padRight(28);
        int column = 0;
        for (UiTheme.HudFont font : UiTheme.HUD_FONTS) {
            String sample = font.instance() >= UiTheme.CONDENSED_MEDIUM ? "Condensed sample" : "Roboto sample";
            if (font.instance() >= UiTheme.CONDENSED_SEMIBOLD) {
                sample = UiTheme.upper(sample);
            }
            type.add(new Label(font.name() + "  " + font.size() + "  " + sample, skin, font.name()));
            if (++column % 3 == 0) {
                type.row();
            }
        }
        root.add(type).padTop(18).row();

        Table icons = new Table();
        icons.defaults().width(68).padBottom(8);
        column = 0;
        for (String name : UiTheme.ICONS.keySet().stream().sorted().toList()) {
            Table cell = new Table();
            cell.add(icon(skin, name, UiTheme.ACCENT)).size(19).row();
            cell.add(new Label(name, skin, "hud-small")).padTop(2);
            icons.add(cell);
            if (++column % 18 == 0) {
                icons.row();
            }
        }
        root.add(icons).padTop(14);
        return root;
    }

    private static Table row(Skin skin, String background, String model, String name, String status) {
        Table row = new Table();
        row.setBackground(skin.getDrawable(background));
        row.defaults().left().growX();
        row.add(new Label(model, skin, "hud-small")).row();
        row.add(new Label(UiTheme.upper(name), skin, "hud-name")).row();
        Color color = background.equals("row-selected") ? UiTheme.MINT : UiTheme.ACCENT;
        row.add(new Label(status, new Label.LabelStyle(skin.getFont("hud-small"), color)));
        return row;
    }

    /** The weapons panel's pills, rows and chips, the log's discs and badge, a select and the Tactical View chip. */
    private static Table weapons(Skin skin) {
        Table weapons = new Table();
        weapons.setBackground(skin.getDrawable("panel"));
        weapons.defaults().left();
        weapons.add(new Label(UiTheme.upper("Weapons"), skin, "hud-title")).padBottom(8).row();
        Table pills = new Table();
        pills.add(pill(skin, "A", "Timber Wolf", false)).padRight(6);
        pills.add(pill(skin, "B", "Mad Cat", true));
        weapons.add(pills).padBottom(8).row();
        weapons.add(weaponRow(skin, "row-weapon-selected", "AC/20", "RT, 20 dmg, 7 heat", "8+")).growX().padBottom(6)
              .row();
        weapons.add(weaponRow(skin, "row", "Medium laser", "LA, 5 dmg, 3 heat", "6+")).growX().padBottom(10).row();
        Table chips = new Table();
        chips.defaults().padRight(5);
        chips.add(label(skin, UiTheme.upper("Walk 2 hex"), "hud-button", 10.5f, UiTheme.ACCENT, "chip"));
        chips.add(label(skin, UiTheme.upper("Heat +4"), "hud-button", 10.5f, UiTheme.AMBER, "chip-warn"));
        chips.add(label(skin, UiTheme.upper("Prone"), "hud-button", 10.5f, UiTheme.CORAL, "chip-bad"));
        weapons.add(chips).padBottom(10).row();
        Table marks = new Table();
        marks.defaults().padRight(8);
        marks.add(label(skin, "3", "hud-name", 11, UiTheme.MINT, "disc")).size(28);
        Container<Image> walk = new Container<>(icon(skin, "walk", UiTheme.MINT)).size(13);
        walk.setBackground(skin.getDrawable("disc"));
        marks.add(walk).size(28);
        marks.add(label(skin, "7", "hud-name", 11, UiTheme.CORAL, "disc-foe")).size(28);
        marks.add(label(skin, "12", "hud-name", 10, Color.valueOf("1A1A1A"), "badge")).height(17);
        weapons.add(marks).padBottom(10).row();
        Table select = new Table();
        select.setBackground(skin.getDrawable("select"));
        select.add(new Label("Group by formation", skin, "hud-body")).growX().left();
        select.add(icon(skin, "chevron-down", UiTheme.MUTED)).size(16);
        weapons.add(select).growX().padBottom(10).row();
        Table chip = new Table();
        chip.setBackground(skin.getDrawable("panel-rails"));
        chip.add(label(skin, UiTheme.upper("Tactical view"), "hud-caption", 12, UiTheme.MINT, null))
              .padRight(10);
        chip.add(button(skin, "hud-mini", UiTheme.upper("Back to 3D"), false, false));
        weapons.add(chip);
        return weapons;
    }

    /** A target pill: its letter square and the target's name. */
    private static Table pill(Skin skin, String letter, String name, boolean on) {
        Table pill = new Table();
        pill.setBackground(skin.getDrawable(on ? "pill-on" : "pill"));
        pill.add(label(skin, letter, "hud-name", 11, on ? Color.WHITE : Color.valueOf("1D1413"),
              on ? "letter-on" : "letter")).size(19).padRight(6);
        pill.add(label(skin, name, "hud-medium", 11.5f, on ? Color.valueOf("17201D") : UiTheme.TEXT, null));
        return pill;
    }

    private static Table weaponRow(Skin skin, String background, String name, String detail, String target) {
        Table row = new Table();
        row.setBackground(skin.getDrawable(background));
        Table text = new Table();
        text.left().defaults().left();
        text.add(new Label(UiTheme.upper(name), skin, "hud-name")).row();
        text.add(new Label(detail, skin, "hud-small"));
        row.add(text).growX().left();
        row.add(label(skin, target, "hud-heading", 20, UiTheme.MINT, null));
        return row;
    }

    /** A label in a hud font at another size and color, on an optional skin drawable. */
    private static Label label(Skin skin, String text, String font, float size, Color color, String background) {
        Label label = new Label(text, style(skin, font, color, background));
        // Label.setFontScale replaces the font's own scale (hud fonts are rasterised at 4x), so it is multiplied in.
        label.setFontScale(skin.getFont(font).getScaleX() * size / hudFont(font).size());
        label.setAlignment(Align.center);
        return label;
    }

    private static Label.LabelStyle style(Skin skin, String font, Color color, String background) {
        Label.LabelStyle style = new Label.LabelStyle(skin.getFont(font), color);
        style.background = background == null ? null : skin.getDrawable(background);
        return style;
    }

    private static Image icon(Skin skin, String name, Color color) {
        Image image = new Image(skin.getDrawable("icon-" + name));
        image.setColor(color);
        return image;
    }

    /** A dock button with the prototype's sub-label (.b small) under its caption. */
    private static TextButton sub(Skin skin, TextButton button, String text, Color color) {
        button.clearChildren();
        button.add(button.getLabel()).row();
        button.add(new Label(text, style(skin, "hud-sub", color, null)));
        return button;
    }

    private static TextButton menuRow(Skin skin, String text, boolean over, boolean checked) {
        TextButton row = button(skin, "hud-menu-row", text, over, checked);
        row.getLabel().setAlignment(Align.left);
        return row;
    }

    /** A button whose hover state is forced, because a swatch has no pointer. */
    private static TextButton button(Skin skin, String style, String text, boolean over, boolean checked) {
        TextButton button = new TextButton(text, skin, style) {
            @Override
            public boolean isOver() {
                return over || super.isOver();
            }
        };
        button.setChecked(checked);
        return button;
    }

    /** A top-right utility: the icon above the label, both following the style's font color. */
    private static TextButton utility(Skin skin, String icon, String text, boolean over, boolean checked) {
        Image image = new Image(skin.getDrawable("icon-" + icon));
        TextButton button = new TextButton(text, skin, "hud-utility") {
            @Override
            public boolean isOver() {
                return over || super.isOver();
            }

            @Override
            public void draw(Batch batch, float parentAlpha) {
                image.setColor(getFontColor());
                super.draw(batch, parentAlpha);
            }
        };
        button.clearChildren();
        button.add(image).size(19).row();
        button.add(button.getLabel()).padTop(4);
        button.setChecked(checked);
        return button;
    }

    /** Draws the stage at scale physical pixels per unit and saves the back buffer. */
    private static void capture(Stage stage, float scale, String name) {
        // The viewport takes logical sizes, as in GpuHud; the units per pixel follow the back-buffer density.
        float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(density / scale);
        stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        // A mid-tone olive, roughly the grass behind the prototype's panels.
        ScreenUtils.clear(0.42f, 0.5f, 0.3f, 1);
        stage.act(0);
        stage.draw();
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        GpuBoardTestUi.capture(new File(directory, name));
    }
}
