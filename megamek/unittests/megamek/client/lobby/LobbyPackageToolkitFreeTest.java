/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** The lobby's logic must stay usable by any view, so it may not depend on a UI toolkit. */
class LobbyPackageToolkitFreeTest {

    private static final Path SOURCES = Path.of("src/megamek/client/lobby");
    private static final Pattern TOOLKIT_IMPORT =
          Pattern.compile("^\\s*import\\s+(static\\s+)?(javax\\.swing|java\\.awt|com\\.badlogic)\\b.*$",
                Pattern.MULTILINE);

    @Test
    void lobbySourcesImportNoUiToolkit() throws IOException {
        assertTrue(Files.isDirectory(SOURCES), "Lobby sources not found at " + SOURCES.toAbsolutePath());
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                checked++;
                var matcher = TOOLKIT_IMPORT.matcher(Files.readString(file));
                while (matcher.find()) {
                    offenders.add(file.getFileName() + ": " + matcher.group().trim());
                }
            }
        }
        assertTrue(checked > 0, "No lobby sources found");
        assertTrue(offenders.isEmpty(), "UI toolkit imports in megamek.client.lobby: " + offenders);
    }
}
