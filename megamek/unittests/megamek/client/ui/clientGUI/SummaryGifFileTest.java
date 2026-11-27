/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The summary GIF's save dialog opens in the folder last saved to, while it exists, else the working folder. */
class SummaryGifFileTest {
    @Test
    void theDialogOpensInTheRememberedFolder(@TempDir Path folder) {
        assertEquals(folder.toFile(), SummaryGifFile.resolveStartDirectory(folder.toString()));
    }

    @Test
    void aRememberedFolderThatIsGoneFallsBackToTheWorkingFolder(@TempDir Path folder) {
        assertEquals(new File("."), SummaryGifFile.resolveStartDirectory(folder.resolve("deleted").toString()));
    }

    @Test
    void withoutARememberedFolderTheDialogOpensInTheWorkingFolder() {
        assertEquals(new File("."), SummaryGifFile.resolveStartDirectory(""));
        assertEquals(new File("."), SummaryGifFile.resolveStartDirectory(null));
    }
}
