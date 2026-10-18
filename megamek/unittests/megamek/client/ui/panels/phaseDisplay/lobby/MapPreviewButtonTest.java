/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.panels.phaseDisplay.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.loaders.MapSettings;
import org.junit.jupiter.api.Test;

class MapPreviewButtonTest {
    @Test
    void themeRadioChoicesChangeOnlyTheirPanelAndDefaultRestoresNoOverride() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatLounge lobby = mock(ChatLounge.class);
            lobby.mapSettings = MapSettings.getInstance();
            lobby.mapSettings.setMapSize(2, 2);
            lobby.mapSettings.setBoardTheme(0, "snow");
            when(lobby.maxMapButtonSize()).thenReturn(new Dimension(100, 100));
            when(lobby.createBoardTooltip(anyString())).thenReturn("");
            doAnswer(call -> {
                lobby.mapSettings.setBoardTheme(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(lobby).changeMapTheme(anyInt(), nullable(String.class));

            try (var artwork = mockStatic(BoardArtwork.class);
                  var blueprints = mockStatic(BoardEditorBlueprint.class, CALLS_REAL_METHODS)) {
                artwork.when(BoardArtwork::themes).thenReturn(List.of("grass", "volcano", "snow"));
                BoardEditorBlueprint blueprint = mock(BoardEditorBlueprint.class);
                when(blueprint.specialThemes()).thenReturn(List.of("tundra"));
                blueprints.when(BoardEditorBlueprint::get).thenReturn(blueprint);
                MapPreviewButton button = new MapPreviewButton(lobby, 3);
                button.setImage(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "sample");
                JMenu menu = (JMenu) button.createPopup().getComponent(1);
                JRadioButtonMenuItem original = (JRadioButtonMenuItem) menu.getItem(0);
                JMenuItem volcano = choice(menu, "Volcano");
                assertFalse(choice(menu, "Tundra").isSelected());
                assertTrue(original.isSelected());
                volcano.doClick();
                assertTrue(volcano.isSelected());
                assertFalse(original.isSelected());
                verify(lobby).changeMapTheme(3, "volcano");
                assertEquals("volcano", lobby.mapSettings.getBoardTheme(3));
                assertEquals("snow", lobby.mapSettings.getBoardTheme(0));
                assertNull(lobby.mapSettings.getBoardTheme(1));
                assertNull(lobby.mapSettings.getBoardTheme(2));

                JMenu reopened = (JMenu) button.createPopup().getComponent(1);
                assertTrue(choice(reopened, "Volcano").isSelected());
                reopened.getItem(0).doClick();
                verify(lobby).changeMapTheme(3, null);
                assertNull(lobby.mapSettings.getBoardTheme(3));
                assertEquals("snow", lobby.mapSettings.getBoardTheme(0));
            }
        });
    }

    private static JMenuItem choice(JMenu menu, String label) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem item = menu.getItem(i);
            if (item != null && label.equals(item.getText())) { return item; }
        }
        throw new AssertionError("Missing theme choice: " + label);
    }
}
