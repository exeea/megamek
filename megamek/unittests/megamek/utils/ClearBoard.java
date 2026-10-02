/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.utils;

import megamek.common.board.Board;

/** A clear, level board for tests. */
public final class ClearBoard {

    private ClearBoard() { }

    /** A board of this size whose hexes are all level 0 with no terrain. */
    public static Board of(int width, int height) {
        StringBuilder data = new StringBuilder("size " + width + " " + height + "\n");
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                data.append(String.format("hex %02d%02d 0 \"\" \"\"", x + 1, y + 1)).append('\n');
            }
        }
        return BoardLoader.initializeBoard(data.append("end").toString());
    }
}
