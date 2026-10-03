/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpuShaderEditorTest {
    @TempDir
    Path directory;

    @Test
    void savesAnOverrideAndRejectsConflictingExternalEdits() throws Exception {
        Path path = directory.resolve("shaders/beams.frag");
        var bundled = new GpuShaderSource.FileSource("original", path, false);
        assertFalse(Files.exists(path));
        var saved = GpuShaderSource.save(bundled, "first edit");
        assertTrue(saved.exists());
        assertEquals("first edit", Files.readString(path));
        var second = GpuShaderSource.save(saved, "second edit");
        Files.writeString(path, "external edit");
        assertThrows(IOException.class, () -> GpuShaderSource.save(second, "overwrite"));
        assertEquals("external edit", Files.readString(path));
        try (var files = Files.list(path.getParent())) { assertEquals(1, files.count(), "No temporary file left behind"); }
    }

    @Test
    void refusesToOverwriteAnOverrideCreatedAfterOpeningABundledFile() throws Exception {
        Path path = directory.resolve("beams.frag");
        var bundled = new GpuShaderSource.FileSource("original", path, false);
        Files.writeString(path, "another editor");
        assertThrows(IOException.class, () -> GpuShaderSource.save(bundled, "draft"));
        assertEquals("another editor", Files.readString(path));
    }

    @Test
    void discoversStagesAndSharedFilesAndKeepsDraftsOutOfDiskReads() {
        var previous = Gdx.files;
        Gdx.files = new Lwjgl3Files();
        var manager = new GpuShaderManager();
        try {
            var files = GpuShaderSource.files();
            assertTrue(files.containsAll(java.util.List.of("beams.frag", "effects.vert", "light-model.glsl")));
            assertTrue(files.size() >= 50);
            String original = GpuShaderSource.read("beams.frag");
            manager.run(() -> {
                var result = manager.apply(java.util.Map.of("beams.frag", "draft"));
                assertTrue(result.success());
                assertEquals(0, result.updated());
                assertEquals("draft", GpuShaderSource.read("beams.frag"));
                assertEquals(original, GpuShaderSource.readDisk("beams.frag"));
            });
            assertEquals(original, GpuShaderSource.read("beams.frag"), "Other windows do not inherit this draft");
        } finally { manager.close(); Gdx.files = previous; }
    }
}
