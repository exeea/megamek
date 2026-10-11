/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.gdx.UiTestStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real outlined board fonts under minification, including glyphs added after their atlas pages were uploaded. */
@Tag("on-demand")
class GpuHexTextFilteringSmokeTest {
    private static final int WIDTH = 720;
    private static final int HEIGHT = 280;
    private static final int REFERENCE_SCALE = 4;

    @Test
    void angledLabelsKeepTheirCoverageAndIncrementalGlyphMipmaps() {
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(WIDTH, HEIGHT);
        UiTestStage.onGl(configuration, () -> {
            GpuBoardSkin skin = new GpuBoardSkin();
            SpriteBatch batch = new SpriteBatch();
            try {
                BitmapFont font = skin.skin.getFont("default-font");
                BitmapFont outline = skin.skin.getFont(GpuBoardSkin.OUTLINE_FONT);
                List<BitmapFont> fonts = List.of(font, outline);
                for (BitmapFont face : fonts) {
                    face.getData().setScale(1);
                }
                checkMinification(batch, font, outline);

                // These characters were not in the initial atlas. First add a few to its existing pages.
                checkMipmaps(batch, fonts, "\u03a9\u03a8\u0416\u042f");
                int oldPages = font.getRegions().size;
                StringBuilder extra = new StringBuilder();
                for (char character = '\u0100'; character < '\u0250'; character++) {
                    extra.append(character);
                }
                for (BitmapFont face : fonts) {
                    new GlyphLayout(face, extra);
                }
                assertTrue(font.getRegions().size > oldPages, "The fixture must grow the incremental font atlas");
                checkMipmaps(batch, fonts, "\u04e8\u04ee\u04f0\u04f2");

                // Wide empty slots must never acquire ink from nearby atlas glyphs at the tested viewing angles.
                Pixmap blank = render(batch, font, outline, "          ", 1);
                try {
                    int background = blank.getPixel(0, 0);
                    for (int y = 0; y < HEIGHT; y++) {
                        for (int x = 0; x < WIDTH; x++) {
                            assertEquals(background, blank.getPixel(x, y), "Filtered spaces must not bleed atlas ink");
                        }
                    }
                } finally {
                    blank.dispose();
                }
            } finally {
                batch.dispose();
                skin.dispose();
            }
        });
    }

    private static void checkMinification(SpriteBatch batch, BitmapFont font, BitmapFont outline) {
        List<BitmapFont> fonts = List.of(font, outline);
        String text = "0613   LEVEL 2";
        Pixmap filtered = render(batch, font, outline, text, 1);
        var minFilter = font.getRegion().getTexture().getMinFilter();
        float anisotropy = font.getRegion().getTexture().getAnisotropicFilter();
        sampling(fonts, Texture.TextureFilter.Linear, 1);
        Pixmap bilinear = render(batch, font, outline, text, 1);
        Pixmap reference = render(batch, font, outline, text, REFERENCE_SCALE);
        try {
            capture("terrain-label-filtered", filtered);
            capture("terrain-label-bilinear", bilinear);
            double filteredError = error(filtered, reference);
            double bilinearError = error(bilinear, reference);
            System.out.println("Angled label squared error against supersampled coverage: filtered="
                  + filteredError + ", bilinear=" + bilinearError);
            assertTrue(bilinearError > 1, "The fixture must exercise minification aliasing");
            assertTrue(filteredError < bilinearError,
                  "Filtered outlines must match supersampled glyph coverage more closely than bilinear sampling");
        } finally {
            sampling(fonts, minFilter, anisotropy);
            filtered.dispose();
            bilinear.dispose();
            reference.dispose();
        }
    }

    private static void checkMipmaps(SpriteBatch batch, List<BitmapFont> fonts, String text) {
        for (BitmapFont face : fonts) {
            new GlyphLayout(face, text);
            for (int index = 0; index < text.length(); index++) {
                var glyph = face.getData().getGlyph(text.charAt(index));
                assertTrue(glyph != null && glyph != face.getData().missingGlyph,
                      "The incremental fixture must render actual glyphs, not missing-character boxes");
            }
            // Check both pages independently: opaque text must not conceal a stale outline mipmap.
            Pixmap actual = render(batch, face, null, text, 1);
            for (var region : face.getRegions()) {
                region.getTexture().bind();
                Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
            }
            Pixmap regenerated = render(batch, face, null, text, 1);
            try {
                assertTrue(ink(actual) > 100, "New glyphs must be visible in the minified atlas samples");
                assertTrue(actual.getPixels().equals(regenerated.getPixels()),
                      "Incremental glyph upload must refresh every mip level before rendering");
            } finally {
                actual.dispose();
                regenerated.dispose();
            }
        }
    }

    private static void sampling(List<BitmapFont> fonts, Texture.TextureFilter minFilter, float anisotropy) {
        for (BitmapFont font : fonts) {
            for (var region : font.getRegions()) {
                region.getTexture().setFilter(minFilter, Texture.TextureFilter.Linear);
                region.getTexture().setAnisotropicFilter(anisotropy);
            }
        }
    }

    /** Three font sizes and three grazing angles; the outlined fill follows GpuHexText's border alignment. */
    private static Pixmap render(SpriteBatch batch, BitmapFont font, BitmapFont outline, String text, int supersample) {
        FrameBuffer target = new FrameBuffer(Pixmap.Format.RGBA8888, WIDTH * supersample,
              HEIGHT * supersample, false);
        target.begin();
        try {
            ScreenUtils.clear(.5f, .5f, .5f, 1);
            batch.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, WIDTH, HEIGHT));
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    float scale = (18 + row * 6) / (float) GpuBoardSkin.FONT_RESOLUTION;
                    float tilt = .6f - column * .2f;
                    batch.setTransformMatrix(new Matrix4().translate(30 + column * 230, 45 + row * 85, 0)
                          .rotate(0, 0, 1, 20).scale(scale, scale * tilt, 1));
                    batch.begin();
                    if (outline != null) {
                        outline.setColor(Color.BLACK);
                        outline.draw(batch, text, -GpuBoardSkin.OUTLINE, 0);
                    }
                    font.setColor(Color.WHITE);
                    font.draw(batch, text, 0, 0);
                    batch.end();
                }
            }
            return Pixmap.createFromFrameBuffer(0, 0, WIDTH * supersample, HEIGHT * supersample);
        } finally {
            target.end();
            target.dispose();
        }
    }

    private static int ink(Pixmap pixels) {
        int result = 0;
        int background = pixels.getPixel(0, 0);
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                if (pixels.getPixel(x, y) != background) {
                    result++;
                }
            }
        }
        return result;
    }

    /** Pixel-area coverage reference, independent of the font atlas mipmap implementation. */
    private static double error(Pixmap actual, Pixmap reference) {
        double total = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                double expected = 0;
                for (int sy = 0; sy < REFERENCE_SCALE; sy++) {
                    for (int sx = 0; sx < REFERENCE_SCALE; sx++) {
                        expected += reference.getPixel(x * REFERENCE_SCALE + sx, y * REFERENCE_SCALE + sy) >>> 24;
                    }
                }
                double difference = (actual.getPixel(x, y) >>> 24)
                      - expected / (REFERENCE_SCALE * REFERENCE_SCALE);
                total += difference * difference;
            }
        }
        return total;
    }

    private static void capture(String name, Pixmap pixels) {
        assertTrue(UiTestStage.OUTPUT.isDirectory() || UiTestStage.OUTPUT.mkdirs());
        PixmapIO.writePNG(new FileHandle(new File(UiTestStage.OUTPUT, name + ".png")), pixels, -1, true);
    }
}
