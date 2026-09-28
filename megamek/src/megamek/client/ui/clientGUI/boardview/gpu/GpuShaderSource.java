/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.TreeSet;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import megamek.common.Configuration;

/** Uncached shader reads: installation overrides, checkout resources, then the bundled defaults. */
final class GpuShaderSource {
    private static final String RESOURCES = "megamek/client/ui/clientGUI/boardview/gpu/";

    private GpuShaderSource() { }

    static String read(String name) {
        var session = GpuShaderManager.current();
        return session == null ? readDisk(name) : session.read(name);
    }

    static String readDisk(String name) { return resolve(name).readString("UTF-8"); }

    private static FileHandle resolve(String name) {
        FileHandle override = new FileHandle(new File(Configuration.dataDir(), "shaders/" + name));
        if (override.exists()) { return override; }
        // Gradle runs in the megamek subproject; IDE launches may run from the repository root.
        for (String root : new String[] { "resources/", "megamek/resources/" }) {
            FileHandle source = Gdx.files.local(root + RESOURCES + name);
            if (source.exists()) { return source; }
        }
        return Gdx.files.classpath(RESOURCES + name);
    }

    record FileSource(String text, Path destination, boolean exists) { }

    static FileSource file(String name) {
        FileHandle source = resolve(name);
        boolean bundled = source.type() == com.badlogic.gdx.Files.FileType.Classpath;
        Path destination = (bundled ? new File(Configuration.dataDir(), "shaders/" + name) : source.file())
              .toPath().toAbsolutePath().normalize();
        return new FileSource(source.readString("UTF-8"), destination, !bundled);
    }

    /** Discover the same assets in a checkout and in a packaged JAR; no second list of shader filenames. */
    static Set<String> files() {
        Set<String> names = new TreeSet<>();
        try {
            var directories = GpuShaderSource.class.getClassLoader().getResources(RESOURCES);
            while (directories.hasMoreElements()) {
                var url = directories.nextElement();
                if ("jar".equals(url.getProtocol())) {
                    var connection = (JarURLConnection) url.openConnection();
                    connection.setUseCaches(false);
                    try (var jar = connection.getJarFile()) {
                        jar.stream().map(entry -> entry.getName()).filter(name -> name.startsWith(RESOURCES))
                              .map(name -> name.substring(RESOURCES.length())).filter(GpuShaderSource::shader)
                              .forEach(names::add);
                    }
                } else if ("file".equals(url.getProtocol())) {
                    addFiles(names, Path.of(url.toURI()));
                }
            }
            for (String root : new String[] { "resources/", "megamek/resources/" }) {
                addFiles(names, Gdx.files.local(root + RESOURCES).file().toPath());
            }
            addFiles(names, new File(Configuration.dataDir(), "shaders").toPath());
        } catch (IOException | URISyntaxException failure) {
            throw new IllegalStateException("Cannot list shader sources", failure);
        }
        return names;
    }

    private static void addFiles(Set<String> names, Path directory) throws IOException {
        if (!Files.isDirectory(directory)) { return; }
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                  .filter(GpuShaderSource::shader).forEach(names::add);
        }
    }

    private static boolean shader(String name) {
        return !name.contains("/") && (name.endsWith(".vert") || name.endsWith(".frag") || name.endsWith(".glsl"));
    }

    /** Save explicitly, and refuse to overwrite edits made by another editor since this document was loaded. */
    static FileSource save(FileSource source, String text) throws IOException {
        Path path = source.destination();
        if (Files.exists(path) != source.exists()
              || (source.exists() && !Files.readString(path).equals(source.text()))) {
            throw new IOException("Changed outside this editor: " + path + ". Use Reload file before saving.");
        }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".shader-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
        return new FileSource(text, path, true);
    }
}
