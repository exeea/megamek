/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileDialogPathsTest {
    @TempDir Path directory;
    private final UiFileDialog.Filter boards = new UiFileDialog.Filter("Boards", "board", "board2");

    @Test void listsFoldersFirstAndFiltersFilesWithoutLosingUnicodeOrSpaces() throws IOException {
        Files.createDirectory(directory.resolve("Z folder"));
        Files.createDirectory(directory.resolve("a folder"));
        Files.writeString(directory.resolve("Árvíz 東京.BOARD2"), "");
        Files.writeString(directory.resolve("a.board"), "");
        Files.writeString(directory.resolve("no.board2.bak"), "");
        Files.writeString(directory.resolve(".hidden.board2"), "");
        var entries = FileDialogPaths.list(directory, false, boards);
        assertEquals(List.of("a folder", "Z folder", "a.board", "Árvíz 東京.BOARD2"), entries.stream().map(FileDialogPaths.Entry::name).toList());
        assertTrue(entries.getFirst().directory());
        assertFalse(entries.getLast().directory());
        assertEquals(List.of(), FileDialogPaths.list(Files.createDirectory(directory.resolve("empty")), false, boards));
        assertThrows(IOException.class, () -> FileDialogPaths.list(directory.resolve("missing"), false, boards));
    }

    @Test void pathsAndRecoveryUseTheHostFilesystem() throws IOException {
        Path child = Files.createDirectory(directory.resolve("folder with spaces"));
        assertEquals(child.resolve("Árvíz.board2"), FileDialogPaths.resolve(directory, "folder with spaces/Árvíz.board2"));
        assertEquals(directory.resolve("a.board"), FileDialogPaths.resolve(child, "../a.board"));
        assertEquals(child, FileDialogPaths.resolve(directory, child.toString()));
        assertEquals(FileDialogPaths.home(), FileDialogPaths.resolve(directory, "~"));
        assertEquals(child, FileDialogPaths.initial(child.resolve("deleted/folder").toString(), directory));
        assertEquals(directory, FileDialogPaths.initial("invalid\0path", directory));
        assertEquals(directory, FileDialogPaths.initial("", directory));
        assertNotNull(FileDialogPaths.resolve(directory, directory.getRoot().toString()).getRoot());
        assertTrue(FileDialogPaths.places(directory).contains(directory.getRoot()));
        if (java.io.File.separatorChar == '\\') {
            assertEquals(Path.of("\\\\server\\share\\map.board2"), FileDialogPaths.resolve(directory, "\\\\server\\share\\map.board2"));
        } else {
            // A backslash is a legal Unix filename character, not a path separator to rewrite.
            assertEquals(directory.resolve("a\\b.board2"), FileDialogPaths.resolve(directory, "a\\b.board2"));
        }
    }

    @Test void saveExtensionsAreCaseInsensitiveAndNeverDropAnUnrelatedSuffix() {
        var nativeBoards = new UiFileDialog.Filter("Boards", "board2");
        assertEquals(directory.resolve("new.board2"), FileDialogPaths.saveTarget(directory.resolve("new"), nativeBoards));
        assertEquals(directory.resolve("new.BOARD2"), FileDialogPaths.saveTarget(directory.resolve("new.BOARD2"), nativeBoards));
        assertEquals(directory.resolve("v1.2.board2"), FileDialogPaths.saveTarget(directory.resolve("v1.2"), nativeBoards));
        assertFalse(boards.accepts(directory.resolve("no.board2.bak")));
        assertTrue(new UiFileDialog.Filter("Any").accepts(directory.resolve("anything")));
    }

    @Test void directorySymlinksRemainNavigable() throws IOException {
        Path real = Files.createDirectory(directory.resolve("real"));
        Path file = Files.writeString(real.resolve("map.board2"), "");
        Path link = directory.resolve("linked");
        try { Files.createSymbolicLink(link, real); }
        catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("Symlink creation unavailable: " + unsupported.getMessage());
        }
        assertTrue(FileDialogPaths.list(directory, false, boards).stream().anyMatch(entry -> entry.path().equals(link) && entry.directory()));
        assertTrue(Files.isSameFile(file, FileDialogPaths.list(link, false, boards).getFirst().path()));
    }
}
