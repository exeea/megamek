/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import megamek.common.Configuration;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real file edits and fallback order, independent of the launch directory or an OpenGL context. */
class GpuShaderReloadTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = { "resources", "megamek/resources" })
    void rereadsOverridesAndCheckoutIncludesWithBundledFallback(String root) throws Exception {
        File data = Configuration.dataDir();
        var previousFiles = Gdx.files;
        Configuration.setDataDir(directory.resolve("data").toFile());
        Gdx.files = spy(new Lwjgl3Files());
        doAnswer(call -> new FileHandle(directory.resolve((String) call.getArgument(0)).toFile()))
              .when(Gdx.files).local(anyString());
        try {
            String resource = "megamek/client/ui/clientGUI/boardview/gpu/light-model.glsl";
            String bundled = Gdx.files.classpath(resource).readString("UTF-8");
            assertEquals(bundled, GpuShaderSource.read("light-model.glsl"));

            Path checkout = directory.resolve(root).resolve(resource);
            Files.createDirectories(checkout.getParent());
            Files.writeString(checkout, bundled + "\n// checkout edit");
            assertTrue(unitVertex().contains("// checkout edit"));

            Path override = directory.resolve("data/shaders/light-model.glsl");
            Files.createDirectories(override.getParent());
            Files.writeString(override, bundled + "\n// first override");
            assertTrue(unitVertex().contains("// first override"));
            Files.writeString(override, bundled + "\n// second override");
            assertTrue(unitVertex().contains("// second override"), "Re-read the same filename without restarting");

            Files.delete(override);
            assertTrue(unitVertex().contains("// checkout edit"));
            Files.delete(checkout);
            assertEquals(bundled, GpuShaderSource.read("light-model.glsl"));
        } finally {
            Configuration.setDataDir(data);
            Gdx.files = previousFiles;
        }
    }

    private static String unitVertex() {
        return GpuUnitShader.linearVertex(DefaultShader.getDefaultVertexShader());
    }
}
