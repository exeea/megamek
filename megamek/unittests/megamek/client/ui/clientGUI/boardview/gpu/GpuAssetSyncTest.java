/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GpuAssetSyncTest {
    @TempDir
    Path directory;

    @Test
    void reportsInventoryAndCompletedFileProgressIncludingUnchangedFiles() throws Exception {
        Path source = directory.resolve("mm-data/data"), target = directory.resolve("installation/data");
        write(source.resolve("models/unchanged.glb"), "same");
        write(target.resolve("models/unchanged.glb"), "same");
        write(source.resolve("models/changed.glb"), "new mesh");
        write(target.resolve("models/changed.glb"), "old mesh");
        write(source.resolve("models/textures/new.png"), "new image");
        write(source.resolve("models/Thumbs.db"), "ignored");
        write(source.resolve("images/new.png"), "ignored");
        write(source.resolve("mekfiles/unit.mtf"), "ignored");
        List<GpuAssetSync.Progress> updates = new ArrayList<>();

        var result = GpuAssetSync.sync(target, updates::add);

        assertEquals(2, result.copied());
        assertEquals(-1, updates.getFirst().total(), "Discovery has no invented percentage");
        assertEquals(List.of(1, 2, 3), updates.stream().filter(p -> p.phase().equals("Listing mm-data"))
              .map(GpuAssetSync.Progress::completed).toList());
        int completed = 0;
        for (var progress : updates) {
            if (progress.file() != null) { assertFalse(progress.file().isAbsolute()); }
            if (progress.total() < 0) { continue; }
            assertEquals(3, progress.total(), "Only eligible assets enter the total");
            assertTrue(progress.completed() >= completed && progress.completed() <= 3);
            assertTrue(progress.copied() <= progress.completed(), "In-flight copies are not counted as done");
            completed = progress.completed();
        }
        assertEquals(3, updates.getLast().completed());
        assertEquals(2, updates.getLast().copied());
        assertEquals(2, updates.stream().filter(p -> p.phase().equals("Copying assets")).count());
        assertEquals("new mesh", Files.readString(target.resolve("models/changed.glb")));
        assertEquals("new image", Files.readString(target.resolve("models/textures/new.png")));
        assertFalse(Files.exists(target.resolve("images")));
    }

    @Test
    void failedComparisonDoesNotReportTheFileAsComplete() throws Exception {
        Path target = directory.resolve("installation/data");
        write(directory.resolve("mm-data/data/models/wall.glb"), "mesh");
        Files.createDirectories(target.resolve("models/wall.glb"));
        List<GpuAssetSync.Progress> updates = new ArrayList<>();

        assertThrows(IOException.class, () -> GpuAssetSync.sync(target, updates::add));

        assertEquals(1, updates.getLast().total());
        assertEquals(0, updates.getLast().completed());
        assertEquals(0, updates.getLast().copied());
    }

    @ParameterizedTest
    @ValueSource(strings = { "installation/data", "repository/megamek/data" })
    void findsSiblingAndOverlaysOnlyModelsAndTheirTextures(String layout) throws Exception {
        Path source = directory.resolve("mm-data/data");
        Path target = directory.resolve(layout);
        var assets = List.of("models/board/wall.glb", "models/board/textures/wall.tres",
              "models/board/textures/wall_emission.png", "models/board/textures/materials.json");
        for (String asset : assets) { write(source.resolve(asset), asset); }
        var excluded = List.of("images/units/unit.png", "board-editor/blueprint.json",
              "shaders/unit-material.frag", "mekfiles/unit.mtf");
        for (String asset : excluded) {
            write(source.resolve(asset), "authoring file");
            write(target.resolve(asset), "runtime file");
        }
        write(source.resolve("models/.DS_Store"), "metadata");
        write(source.resolve("images/Thumbs.db"), "metadata");
        write(target.resolve("models/local.glb"), "local asset");

        var result = GpuAssetSync.sync(target);

        assertEquals(source.toRealPath(), result.source());
        assertEquals(assets.size(), result.copied());
        for (String asset : assets) { assertEquals(asset, Files.readString(target.resolve(asset))); }
        for (String asset : excluded) { assertEquals("runtime file", Files.readString(target.resolve(asset))); }
        assertFalse(Files.exists(target.resolve("models/.DS_Store")));
        assertFalse(Files.exists(target.resolve("images/Thumbs.db")));
        assertEquals("local asset", Files.readString(target.resolve("models/local.glb")));

        Files.delete(source.resolve(assets.getFirst()));
        assertEquals(0, GpuAssetSync.sync(target).copied());
        assertTrue(Files.exists(target.resolve(assets.getFirst())), "Source deletions must not delete staged assets");
    }

    @Test
    void comparesContentsEvenWithMatchingSizeAndTimeAndLeavesIdenticalFilesUntouched() throws Exception {
        Path source = directory.resolve("mm-data/data/models/board/wall.glb");
        Path target = directory.resolve("installation/data");
        Path staged = target.resolve("models/board/wall.glb");
        write(source, "new mesh");
        write(staged, "old mesh");
        FileTime time = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(source, time);
        Files.setLastModifiedTime(staged, time);

        assertEquals(1, GpuAssetSync.sync(target).copied());
        assertEquals("new mesh", Files.readString(staged));

        // Equal content with different timestamps must not cause another write.
        FileTime stagedTime = FileTime.fromMillis(time.toMillis() + 10_000);
        Files.setLastModifiedTime(staged, stagedTime);
        assertEquals(0, GpuAssetSync.sync(target).copied());
        assertEquals(stagedTime, Files.getLastModifiedTime(staged));
    }

    @Test
    void missingSiblingLeavesRuntimeDataAlone() throws Exception {
        Path target = directory.resolve("installation/data");
        write(target.resolve("models/local.glb"), "local asset");
        assertNull(GpuAssetSync.sync(target).source());
        assertEquals("local asset", Files.readString(target.resolve("models/local.glb")));
    }

    @Test
    void runningDirectlyFromMmDataDoesNotCopyIntoItself() throws Exception {
        Path data = directory.resolve("mm-data/data");
        write(data.resolve("models/wall.glb"), "mesh");
        assertNull(GpuAssetSync.sync(data).source());
        assertEquals("mesh", Files.readString(data.resolve("models/wall.glb")));
        assertThrows(IOException.class, () -> GpuAssetSync.sync(data.resolve("models")));
    }

    @Test
    void copyFailurePreservesConflictingFileAndCanBeRetried() throws Exception {
        Path target = directory.resolve("installation/data");
        write(directory.resolve("mm-data/data/models/board/wall.glb"), "mesh");
        Path conflict = target.resolve("models/board");
        write(conflict, "existing file");

        assertThrows(IOException.class, () -> GpuAssetSync.sync(target));
        assertEquals("existing file", Files.readString(conflict));
        Files.delete(conflict);
        assertEquals(1, GpuAssetSync.sync(target).copied());
        assertEquals("mesh", Files.readString(conflict.resolve("wall.glb")));
    }

    @Test
    void refusesToWriteThroughStagedDirectoryLinks() throws Exception {
        Path target = Files.createDirectories(directory.resolve("installation/data"));
        Path outside = Files.createDirectories(directory.resolve("outside"));
        write(directory.resolve("mm-data/data/models/wall.glb"), "mesh");
        try {
            Files.createSymbolicLink(target.resolve("models"), outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false, "Symbolic links unavailable: " + unsupported.getMessage());
        }

        assertThrows(IOException.class, () -> GpuAssetSync.sync(target));
        assertFalse(Files.exists(outside.resolve("wall.glb")));
    }

    @Test
    void refusesToReplaceStagedFileLinks() throws Exception {
        Path target = directory.resolve("installation/data");
        Path outside = directory.resolve("outside.glb");
        write(outside, "outside mesh");
        write(directory.resolve("mm-data/data/models/wall.glb"), "new mesh");
        Files.createDirectories(target.resolve("models"));
        try {
            Files.createSymbolicLink(target.resolve("models/wall.glb"), outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false, "Symbolic links unavailable: " + unsupported.getMessage());
        }

        assertThrows(IOException.class, () -> GpuAssetSync.sync(target));
        assertEquals("outside mesh", Files.readString(outside));
        assertTrue(Files.isSymbolicLink(target.resolve("models/wall.glb")));
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
