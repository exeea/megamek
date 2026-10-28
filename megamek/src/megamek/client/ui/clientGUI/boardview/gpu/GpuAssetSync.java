/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Overlays authoring models and their textures from a sibling mm-data checkout before the existing asset reload. */
final class GpuAssetSync {
    record Result(Path source, int copied) { }

    /** Immutable worker snapshot; total is -1 until the file inventory is complete. */
    record Progress(String phase, int completed, int total, int copied, Path file) { }

    private GpuAssetSync() { }

    /** Copies new or byte-different assets; never removes files that only exist in the running data directory. */
    static Result sync(Path data) throws IOException {
        return sync(data, progress -> { });
    }

    static Result sync(Path data, Consumer<Progress> progress) throws IOException {
        progress.accept(new Progress("Finding mm-data", 0, -1, 0, null));
        Path target = data.toAbsolutePath().normalize();
        Path source = findSource(target);
        if (source == null) { return new Result(null, 0); }
        Files.createDirectories(target);
        Path targetRoot = target.toRealPath();
        if (source.equals(targetRoot)) { return new Result(null, 0); }
        if (source.startsWith(targetRoot) || targetRoot.startsWith(source)) {
            throw new IOException("Asset source and staged data directories overlap: " + source + ", " + targetRoot);
        }
        List<Path> files = new ArrayList<>();
        Path directory = source.resolve("models");
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                    Path destination = target.resolve(source.relativize(dir));
                    checkDestination(destination, targetRoot);
                    Files.createDirectories(destination);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    String filename = file.getFileName().toString();
                    if (!attributes.isRegularFile() || filename.equals(".DS_Store") || filename.equals("Thumbs.db")) {
                        return FileVisitResult.CONTINUE;
                    }
                    files.add(file);
                    progress.accept(new Progress("Listing mm-data", files.size(), -1, 0, source.relativize(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        int completed = 0, copied = 0;
        progress.accept(new Progress("Checking assets", 0, files.size(), 0, null));
        for (Path file : files) {
            Path relative = source.relativize(file), destination = target.resolve(relative);
            progress.accept(new Progress("Checking assets", completed, files.size(), copied, relative));
            // The inventory already checked every parent directory. Only repeat that check before a write.
            checkDestination(destination, targetRoot);
            if (!Files.exists(destination) || Files.mismatch(file, destination) != -1) {
                progress.accept(new Progress("Copying assets", completed, files.size(), copied, relative));
                checkDestination(destination.getParent(), targetRoot);
                copy(file, destination);
                copied++;
            }
            progress.accept(new Progress("Checking assets", ++completed, files.size(), copied, relative));
        }
        return new Result(source, copied);
    }

    /** Supports both an installation's data/ and Gradle's repository/megamek/data/. */
    private static Path findSource(Path target) throws IOException {
        Path installation = target.getParent();
        for (int depth = 0; installation != null && installation.getParent() != null && depth < 2;
              depth++, installation = installation.getParent()) {
            Path candidate = installation.resolveSibling("mm-data").resolve("data");
            if (Files.isDirectory(candidate)) { return candidate.toRealPath(); }
        }
        return null;
    }

    /** Do not follow staged links into the source checkout or another directory. Parents are checked on entry. */
    private static void checkDestination(Path path, Path root) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return;
        }
        // Ordinary files cannot redirect traversal. Resolving their full path again is costly on Windows;
        // directories and other entries still need it to catch junctions as well as symbolic links.
        if (attributes.isSymbolicLink() || (!attributes.isRegularFile() && !path.toRealPath().startsWith(root))) {
            throw new IOException("Cannot sync through a linked asset destination: " + path);
        }
    }

    /** Readers must see either the old complete file or the new complete file, including when a copy fails. */
    private static void copy(Path source, Path destination) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".asset-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
