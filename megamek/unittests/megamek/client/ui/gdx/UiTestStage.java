/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * The toolkit's test harness, and the helpers every native view's smoke test shares. {@link #run} opens a hidden
 * 1920 x 1080 window with a UiTheme from data/fonts, a UiKit and a Stage of one unit per back-buffer pixel, as the
 * prototype's CSS pixels. The helpers check GL texture ownership and missing message keys, and write captures and
 * crops beside the hud-v3 mock. All calls run on the GL thread.
 */
public final class UiTestStage {
    /** Where the captures go. */
    public static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots",
          "build/gpu-board-review"));
    /** The hud-v3 screenshots, by default from the sibling checkout; comparisons are skipped without them. */
    public static final File MOCK = new File(System.getProperty("megamek.gpu.hudMock",
          "../../megamek_temp/docs/design/claude-ui-concepts/hud-v3"));
    /** MegaMek's fonts directory, as the tests run in the game's directory. */
    public static final File FONTS = new File("data", "fonts");
    private static final int MARGIN = 8;

    /** The test body; it runs on the GL thread with the harness it receives. */
    public interface Body {
        void run(UiTestStage ui) throws Exception;
    }

    /** Work that runs on the GL thread of a test window. */
    public interface Check {
        void run() throws Exception;
    }

    public final UiTheme theme = new UiTheme(FONTS);
    public final UiKit kit = new UiKit(theme.skin);
    public final Stage stage = new Stage(new ScreenViewport());
    /** The window's area: a group of the stage's size, in which {@link #place} lays out actors from the top left. */
    public final Group window = new Group();

    private UiTestStage() {
        // Units per logical pixel = back-buffer pixels per logical pixel, so one unit is one back-buffer pixel.
        float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(density);
        stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        window.setTransform(false);
        window.setSize(stage.getWidth(), stage.getHeight());
        stage.addActor(window);
    }

    /** Opens the hidden window, runs {@code body} on its GL thread and disposes the stage, then the theme. */
    public static void run(Body body) {
        // As the battle window's configuration: no macOS JVM relaunch, no audio.
        Lwjgl3ApplicationConfiguration.useGlfwAsync();
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle(UiTestStage.class.getSimpleName());
        configuration.setWindowedMode(1920, 1080);
        configuration.setInitialVisible(false);
        configuration.disableAudio(true);
        onGl(configuration, () -> {
            UiTestStage ui = new UiTestStage();
            try {
                body.run(ui);
            } finally {
                ui.stage.dispose();
                ui.theme.dispose();
            }
        });
    }

    /**
     * Opens a window with {@code configuration}, runs {@code check} on its GL thread, closes the window and rethrows
     * a failure. The run fails when a GL texture made during the check outlives it or GL reports an error.
     */
    public static void onGl(Lwjgl3ApplicationConfiguration configuration, Check check) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    System.out.println("GL renderer " + Gdx.gl.glGetString(GL20.GL_RENDERER));
                    Set<Integer> before = liveTextures();
                    check.run();
                    Set<Integer> leaked = liveTextures();
                    leaked.removeAll(before);
                    assertTrue(leaked.isEmpty(), "GL textures left after disposal: " + leaked);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) {
            throw new AssertionError("HUD harness failed", failure.get());
        }
    }

    /**
     * GL texture names alive now. A name is a texture from its first bind until it is deleted; the scan reaches well
     * past the next free name, so textures made and kept under reused names are found as well.
     */
    public static Set<Integer> liveTextures() {
        int next = Gdx.gl.glGenTexture();
        Gdx.gl.glDeleteTexture(next);
        Set<Integer> live = new HashSet<>();
        for (int name = 1; name < next + (1 << 16); name++) {
            if (Gdx.gl.glIsTexture(name)) {
                live.add(name);
            }
        }
        return live;
    }

    /**
     * Acts {@code stage} in 60 Hz frames until no UiList moves a row any more (a dropped or keyboard-moved row has
     * landed and its view heard the move), at most three seconds. A list whose row the pointer still holds stays busy.
     */
    public static void settle(Stage stage) {
        for (int frame = 0; frame < 180 && moving(stage.getRoot()); frame++) {
            stage.act(1 / 60f);
        }
    }

    private static boolean moving(Actor actor) {
        if (actor instanceof UiList list && list.busy()) {
            return true;
        }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                if (moving(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Clears to a mid-tone olive, roughly the grass behind the prototype's panels, and draws one frame. */
    public void draw() {
        ScreenUtils.clear(.42f, .5f, .3f, 1, true);
        stage.getViewport().apply(true);
        stage.act(0);
        stage.draw();
    }

    /**
     * Writes the window to {name}.png after checking that no label shows a missing message key; the caller disposes
     * the returned image.
     */
    public Pixmap capture(String name) {
        assertTexts(stage.getRoot());
        return capture(name, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    /** Writes the back buffer's lower left {@code width} x {@code height} pixels to {name}.png. */
    public static Pixmap capture(String name, int width, int height) {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, width, height);
        PixmapIO.writePNG(new FileHandle(new File(OUTPUT, name + ".png")), image, -1, true);
        return image;
    }

    /** Messages renders a missing key as "!key!". */
    public static void assertTexts(Actor actor) {
        if (actor instanceof Label label) {
            assertFalse(label.getText().toString().startsWith("!"), "Missing message key: " + label.getText());
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(UiTestStage::assertTexts);
        }
    }

    /**
     * Writes {name}-vs-mock.png: the actor's area of {@code captured} beside the area of the same size whose top-left
     * corner is ({@code x}, {@code y}) in the named mock screenshot, each with a margin of 8 pixels.
     */
    public static void compare(String name, Pixmap captured, Actor actor, String mock, int x, int y) {
        File file = new File(MOCK, mock);
        if (!file.isFile()) {
            System.out.println("No mock screenshot at " + file.getAbsolutePath() + "; " + name + "-vs-mock skipped");
            return;
        }
        Rectangle area = bounds(actor);
        int left = Math.round(area.x) - MARGIN;
        int bottom = Math.round(area.y) - MARGIN;
        int cropWidth = Math.round(area.width) + 2 * MARGIN;
        int cropHeight = Math.round(area.height) + 2 * MARGIN;
        Pixmap reference = new Pixmap(new FileHandle(file));
        Pixmap result = new Pixmap(2 * cropWidth + MARGIN, cropHeight, Pixmap.Format.RGBA8888);
        try {
            result.setBlending(Pixmap.Blending.None);
            result.setColor(0, 0, 0, 1);
            result.fill();
            // The back buffer's rows run bottom up; the result's run top down.
            for (int row = 0; row < cropHeight; row++) {
                result.drawPixmap(captured, left, bottom + cropHeight - 1 - row, cropWidth, 1, 0, row, cropWidth, 1);
            }
            result.drawPixmap(reference, x - MARGIN, y - MARGIN, cropWidth, cropHeight, cropWidth + MARGIN, 0,
                  cropWidth, cropHeight);
            PixmapIO.writePNG(new FileHandle(new File(OUTPUT, name + "-vs-mock.png")), result);
        } finally {
            result.dispose();
            reference.dispose();
        }
    }

    /** The actor's bounds in stage units, y up; the window's lower left corner is the stage's. */
    public static Rectangle bounds(Actor actor) {
        Vector2 corner = actor.localToStageCoordinates(new Vector2());
        return new Rectangle(corner.x, corner.y, actor.getWidth(), actor.getHeight());
    }

    /**
     * Adds or moves the actor so that its top-left corner is at (x, y) of {@code window}, y down as in the prototype's
     * CSS; a table keeps a width set before, at its preferred height.
     */
    public static <T extends Actor> T place(Group window, T actor, float x, float y) {
        window.addActor(actor);
        if (actor instanceof Table table) {
            float width = table.getWidth();
            table.pack();
            if (width > 0) {
                table.setWidth(width);
                table.validate();
            }
        } else if (actor instanceof Layout layout) {
            layout.pack();
        }
        actor.setPosition(x, window.getHeight() - y - actor.getHeight());
        return actor;
    }
}
