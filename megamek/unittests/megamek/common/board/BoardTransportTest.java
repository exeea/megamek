/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;

import megamek.common.net.marshalling.SanityInputFilter;
import megamek.common.util.SerializationHelper;
import org.junit.jupiter.api.Test;

/** A .board2 map travels to clients as a serialized Board and is stored in XStream save games. */
class BoardTransportTest {
    private static Board nativeBoard() {
        Board board = Board.createEmptyBoard(2, 1);
        board.getHex(0, 0).setAppearance(Map.of("road", new HexAppearance("road/rounded", null, "roads/gravel", null),
              "ground", new HexAppearance("ground/grass", null, null, null)));
        board.getHex(1, 0).setDecorations(List.of(new BoardDecoration("member", "prop",
              "scenery/parks/picnic-table", null, -.2, .3, 15, false, .65, BoardDecoration.Placement.ground(),
              0, false, 0, 0, "L2_1_g0")));
        return board;
    }

    private static void assertVisualsEqual(Board expected, Board actual) {
        for (int x = 0; x < expected.getWidth(); x++) {
            assertEquals(expected.getHex(x, 0).getAppearance(), actual.getHex(x, 0).getAppearance());
            assertEquals(expected.getHex(x, 0).getDecorations(), actual.getHex(x, 0).getDecorations());
        }
    }

    @Test void networkFilterAcceptsBoardVisuals() throws Exception {
        Board board = nativeBoard();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(board); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            input.setObjectInputFilter(new SanityInputFilter());
            assertVisualsEqual(board, (Board) input.readObject());
        }
    }

    @Test void saveGameKeepsBoardVisuals() {
        Board board = nativeBoard();
        String xml = SerializationHelper.getSaveGameXStream().toXML(board);
        assertVisualsEqual(board, (Board) SerializationHelper.getLoadSaveGameXStream().fromXML(xml));
    }
}
