/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** Overlays authoring assets from a sibling mm-data checkout before the existing asset reload. */
final class GpuAssetSync {
    private static final List<String> ASSET_DIRECTORIES = List.of("models", "images", "board-editor", "shaders");

    record Result(Path source, int copied) { }

    private GpuAssetSync() { }

    /** Copies new or byte-different assets; never removes files that only exist in the running data directory. */
    static Result sync(Path data) throws IOException {
        Path target = data.toAbsolutePath().normalize();
        Path source = findSource(target);
        if (source == null) { return new Result(null, 0); }
        Files.createDirectories(target);
        Path targetRoot = target.toRealPath();
        if (source.equals(targetRoot)) { return new Result(null, 0); }
        if (source.startsWith(targetRoot) || targetRoot.startsWith(source)) {
            throw new IOException("Asset source and staged data directories overlap: " + source + ", " + targetRoot);
        }
        int[] copied = { 0 };
        for (String name : ASSET_DIRECTORIES) {
            Path directory = source.resolve(name);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { continue; }
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
                    Path destination = target.resolve(source.relativize(file));
                    checkDestination(destination, targetRoot);
                    if (!Files.exists(destination) || Files.mismatch(file, destination) != -1) {
                        copy(file, destination);
                        copied[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return new Result(source, copied[0]);
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
        if (Files.isSymbolicLink(path)
              || (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !path.toRealPath().startsWith(root))) {
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
