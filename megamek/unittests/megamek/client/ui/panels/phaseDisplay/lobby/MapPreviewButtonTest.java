/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.panels.phaseDisplay.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Dimension;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.loaders.MapSettings;
import org.junit.jupiter.api.Test;

class MapPreviewButtonTest {
    @Test
    void levelDialogEnforcesBoundsChangesOnlyItsPanelAndResetBypassesInvalidInput() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatLounge lobby = mock(ChatLounge.class);
            lobby.mapSettings = MapSettings.getInstance();
            lobby.mapSettings.setMapSize(2, 2);
            lobby.mapSettings.setBoardLevelOffset(0, 5);
            when(lobby.maxMapButtonSize()).thenReturn(new Dimension(100, 100));
            when(lobby.createBoardTooltip(anyString())).thenReturn("");
            doAnswer(call -> {
                lobby.mapSettings.setBoardLevelOffset(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(lobby).changeMapLevelOffset(anyInt(), anyInt());

            try (var artwork = mockStatic(BoardArtwork.class);
                  var blueprints = mockStatic(BoardEditorBlueprint.class, CALLS_REAL_METHODS);
                  var dialogs = mockStatic(JOptionPane.class)) {
                artwork.when(BoardArtwork::themes).thenReturn(List.of("grass"));
                blueprints.when(BoardEditorBlueprint::get).thenReturn(mock(BoardEditorBlueprint.class));
                int[] step = { 0 };
                dialogs.when(() -> JOptionPane.showOptionDialog(any(Component.class), any(Object[].class),
                      anyString(), eq(JOptionPane.DEFAULT_OPTION), eq(JOptionPane.PLAIN_MESSAGE), isNull(),
                      any(Object[].class), any()))
                      .thenAnswer(call -> {
                          Object[] options = call.getArgument(6);
                          assertEquals(3, options.length);
                          assertEquals(options[0], call.getArgument(7));
                          JSpinner spinner = (JSpinner) ((Object[]) call.getArgument(1))[1];
                          String text = switch (step[0]++) {
                              case 0 -> "51";
                              case 1 -> "-50";
                              case 2 -> "50";
                              default -> "invalid input";
                          };
                          ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setText(text);
                          return switch (step[0]) {
                              case 4 -> 2; // Cancel
                              case 5 -> JOptionPane.CLOSED_OPTION;
                              case 6 -> 1; // Reset, without committing the invalid field
                              default -> 0; // Okay
                          };
                      });
                MapPreviewButton button = new MapPreviewButton(lobby, 3);
                button.setImage(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "sample");
                JMenuItem item = (JMenuItem) button.createPopup().getComponent(2);
                assertTrue(item.isEnabled());
                item.doClick();
                assertEquals(-50, lobby.mapSettings.getBoardLevelOffset(3));
                dialogs.verify(() -> JOptionPane.showMessageDialog(eq(button), anyString(), anyString(),
                      eq(JOptionPane.ERROR_MESSAGE)));
                item.doClick();
                assertEquals(50, lobby.mapSettings.getBoardLevelOffset(3));
                item.doClick();
                assertEquals(50, lobby.mapSettings.getBoardLevelOffset(3));
                item.doClick();
                assertEquals(50, lobby.mapSettings.getBoardLevelOffset(3));
                item.doClick();
                assertEquals(0, lobby.mapSettings.getBoardLevelOffset(3));
                assertEquals(6, step[0], "Reset must close without reopening the dialog");
                dialogs.verify(() -> JOptionPane.showMessageDialog(eq(button), anyString(), anyString(),
                      eq(JOptionPane.ERROR_MESSAGE)));
                assertEquals(5, lobby.mapSettings.getBoardLevelOffset(0));
                assertEquals(0, lobby.mapSettings.getBoardLevelOffset(1));
                assertEquals(0, lobby.mapSettings.getBoardLevelOffset(2));
                lobby.mapSettings.setMedium(MapSettings.MEDIUM_SPACE);
                assertFalse(((JMenuItem) button.createPopup().getComponent(2)).isEnabled());
            }
        });
    }

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
