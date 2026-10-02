/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI.boardview.spriteHandler;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.sprite.MovementEnvelopeSprite;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.common.board.Coords;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.game.IGame;

/**
 * This class handles the sprites shown on an attached BoardClientState for the movement envelope (showing the hexes where a
 * currently selected unit can move to in the movement phase).
 */
public class MovementEnvelopeSpriteHandler extends BoardViewSpriteHandler {

    /** The movement band of an envelope hex; each band has its own envelope colour. */
    public enum Band { WALK, RUN, SPRINT, JUMP }

    private final IGame game;

    public MovementEnvelopeSpriteHandler(AbstractClientGUI clientGUI, IGame game) {
        super(clientGUI);
        this.game = game;
    }

    @Override
    public void initialize() {
        game.addGameListener(this);
    }

    @Override
    public void dispose() {
        clear();
        game.removeGameListener(this);
    }

    public void setMovementEnvelope(Map<Coords, Integer> mvEnvData, int boardId, int walk, int run, int jump,
          int gear) {
        clear();

        if (mvEnvData == null) {
            return;
        }

        BoardClientState boardView = clientGUI.getBoardState(boardId);
        if (boardView == null) {
            return;
        }

        Map<Coords, Band> bands = bands(mvEnvData, walk, run, jump, gear);
        // A DFA hex is banded as a jump but bordered against its neighbours' walk and run bands, so every DFA hex is
        // outlined on all sides (as it always was)
        Map<Coords, Band> adjacentBands = (gear == MovementDisplay.GEAR_DFA)
              ? bands(mvEnvData, walk, run, jump, MovementDisplay.GEAR_LAND) : bands;
        for (Coords loc : mvEnvData.keySet()) {
            Band band = bands.get(loc);
            if (band == null) {
                continue;
            }

            // Next: check the adjacent hexes and find those with the same movement type,
            // send this to the Sprite so it paints only the borders of the movement type areas
            int edgesToPaint = 0;
            for (int dir = 0; dir < 6; dir++) {
                // other movement type: paint a border in this direction
                if (adjacentBands.get(loc.translated(dir)) != band) {
                    edgesToPaint += (1 << dir);
                }
            }
            currentSprites.add(new MovementEnvelopeSprite(boardView, color(band), loc, edgesToPaint));
        }

        boardView.addSprites(currentSprites);
    }

    /**
     * Classifies a movement envelope (the MP needed to reach each hex) into the bands the envelope draws: in the jump
     * and DFA gears {@link Band#JUMP} up to the jump MP, leaving out hexes beyond it; otherwise {@link Band#WALK} up to
     * the walk MP, {@link Band#RUN} up to the run MP and {@link Band#SPRINT} beyond.
     *
     * @param envelope the MP needed per hex, such as {@code MovementDisplay.getLastEnvelope().mp()}
     * @param gear     the MovementDisplay gear the envelope was computed in
     *
     * @return a new map with the band of each banded hex
     */
    public static Map<Coords, Band> bands(Map<Coords, Integer> envelope, int walk, int run, int jump, int gear) {
        boolean jumping = (gear == MovementDisplay.GEAR_JUMP) || (gear == MovementDisplay.GEAR_DFA);
        Map<Coords, Band> bands = new HashMap<>();
        envelope.forEach((coords, mp) -> {
            if (!jumping) {
                bands.put(coords, (mp <= walk) ? Band.WALK : ((mp <= run) ? Band.RUN : Band.SPRINT));
            } else if (mp <= jump) {
                bands.put(coords, Band.JUMP);
            }
        });
        return bands;
    }

    private static Color color(Band band) {
        return switch (band) {
            case WALK -> GUIP.getMoveDefaultColor();
            case RUN -> GUIP.getMoveRunColor();
            case SPRINT -> GUIP.getMoveSprintColor();
            case JUMP -> GUIP.getMoveJumpColor();
        };
    }

    @Override
    public void gamePhaseChange(GamePhaseChangeEvent e) {
        clear();
    }
}
