/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import java.awt.Component;
import java.io.File;
import java.util.Locale;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.util.StringUtil;

/**
 * Where a game's summary GIF goes: the save dialog at the end of a game, opening in the folder the player last saved
 * one to (else the working folder) with a time-stamped name; the chosen folder is remembered.
 */
public final class SummaryGifFile {
    private static final String EXTENSION = ".gif";

    private SummaryGifFile() { }

    /** Asks the player where to save the GIF; null when they cancel. Swing thread. */
    public static @Nullable File choose(@Nullable Component parent) {
        GUIPreferences preferences = GUIPreferences.getInstance();
        JFileChooser chooser = new JFileChooser(resolveStartDirectory(preferences.getGifGameSummarySaveDirectory()));
        chooser.setDialogTitle(Messages.getString("ClientGUI.saveGameSummaryGifFileDialog.title"));
        chooser.setFileFilter(new FileNameExtensionFilter(Messages.getString("ClientGUI.descriptionGIFFiles"),
              EXTENSION.substring(1)));
        chooser.setSelectedFile(new File(StringUtil.addDateTimeStamp("combat_summary_") + EXTENSION));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return null;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            file = new File(file.getPath() + EXTENSION);
        }
        File directory = file.getAbsoluteFile().getParentFile();
        if (directory != null) {
            preferences.setGifGameSummarySaveDirectory(directory.getAbsolutePath());
        }
        return file;
    }

    /**
     * The folder the save dialog opens in: the folder the player last saved a GIF to, if it still exists, otherwise
     * the working directory (the install folder).
     */
    static File resolveStartDirectory(@Nullable String storedDirectory) {
        if ((storedDirectory != null) && !storedDirectory.isBlank()) {
            File directory = new File(storedDirectory);
            if (directory.isDirectory()) {
                return directory;
            }
        }
        return new File(".");
    }
}
