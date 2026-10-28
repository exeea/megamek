/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Filesystem work for {@link UiFileDialog}. Called on its worker, never on the rendering thread. */
final class FileDialogPaths {
    record Entry(Path path, boolean directory) {
        String name() { return path.getFileName() == null ? path.toString() : path.getFileName().toString(); }
        @Override public String toString() { return (directory ? "[+]  " : "      ") + name(); }
    }

    private FileDialogPaths() { }

    static Path home() { return Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize(); }

    static Path resolve(Path directory, String text) {
        if (text.equals("~")) { return home(); }
        if (text.startsWith("~/") || java.io.File.separatorChar == '\\' && text.startsWith("~\\")) {
            return home().resolve(text.substring(2)).normalize();
        }
        return directory.resolve(Path.of(text)).toAbsolutePath().normalize();
    }

    /** A missing remembered folder falls back to its nearest readable ancestor, then the caller's default/home. */
    static Path initial(String remembered, Path fallback) throws IOException {
        List<Path> candidates = new ArrayList<>();
        try { if (!remembered.isBlank()) { candidates.add(Path.of(remembered)); } }
        catch (InvalidPathException ignored) { /* A preference copied from another operating system. */ }
        if (fallback != null) { candidates.add(fallback); }
        candidates.add(home());
        candidates.add(Path.of("."));
        for (Path candidate : candidates) {
            for (Path path = candidate.toAbsolutePath().normalize(); path != null; path = path.getParent()) {
                if (Files.isDirectory(path) && Files.isReadable(path)) { return path; }
            }
        }
        throw new IOException(UiKit.text("FileDialog.noFolder"));
    }

    static List<Entry> list(Path directory, boolean hidden, UiFileDialog.Filter filter) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (Thread.currentThread().isInterrupted()) { throw new IOException("Cancelled"); }
                try {
                    if (!hidden && (path.getFileName().toString().startsWith(".") || Files.isHidden(path))) { continue; }
                    boolean folder = Files.isDirectory(path);
                    if (folder || Files.isRegularFile(path) && filter.accepts(path)) { entries.add(new Entry(path, folder)); }
                } catch (IOException | SecurityException ignored) {
                    // An entry may disappear or become inaccessible while the directory is being enumerated.
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::directory).reversed()
              .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::name));
        return List.copyOf(entries);
    }

    static List<Path> places(Path fallback) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        paths.add(home());
        if (fallback != null) { paths.add(fallback.toAbsolutePath().normalize()); }
        home().getFileSystem().getRootDirectories().forEach(paths::add);
        // Unix mount locations are ordinary directories. Windows exposes drives through getRootDirectories().
        for (String mount : List.of("/Volumes", "/media", "/run/media", "/mnt")) {
            if (java.io.File.separatorChar == '/' && Files.isDirectory(Path.of(mount))) { paths.add(Path.of(mount)); }
        }
        return List.copyOf(paths);
    }

    static Path saveTarget(Path path, UiFileDialog.Filter filter) {
        if (filter.extensions().isEmpty() || filter.accepts(path) || path.getFileName() == null) { return path; }
        return path.resolveSibling(path.getFileName() + "." + filter.extensions().getFirst());
    }

    static String search(String value) { return value.toLowerCase(Locale.ROOT); }
}
