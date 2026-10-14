/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/**
 * An artillery round in the air marks the hex it is coming down on, from the moment it is fired until it lands; a
 * direct-fire shot marks nothing.
 */
class GpuIncomingTargetTest {
    private static final ResolvedAttack.Shot ARTILLERY = shot(true);
    private static final ResolvedAttack.Shot DIRECT_FIRE = shot(false);

    @Test
    void anArtilleryRoundMarksItsTargetUntilItLands() {
        UnitAttack round = GroundDamagePlaybackTest.shot("ISLongTom", true, ARTILLERY);
        Coords target = round.event.destination().coords();

        round.seconds = 0;
        assertEquals(List.of(target), GpuBoardOverlay.incomingTargets(List.of(round)), "Marked as it is fired");
        round.seconds = round.contactSeconds - .01f;
        assertEquals(List.of(target), GpuBoardOverlay.incomingTargets(List.of(round)), "Still marked just before");
        round.seconds = round.contactSeconds;
        assertTrue(GpuBoardOverlay.incomingTargets(List.of(round)).isEmpty(), "Gone once it lands");
    }

    @Test
    void aDirectFireShotMarksNothing() {
        UnitAttack shot = GroundDamagePlaybackTest.shot("ISLongTom", true, DIRECT_FIRE);
        shot.seconds = 0;

        assertTrue(GpuBoardOverlay.incomingTargets(List.of(shot)).isEmpty());
    }

    @Test
    void twoRoundsOnOneHexMarkItOnce() {
        UnitAttack first = GroundDamagePlaybackTest.shot("ISLongTom", true, ARTILLERY);
        UnitAttack second = GroundDamagePlaybackTest.shot("ISLongTom", false, ARTILLERY);

        assertEquals(1, GpuBoardOverlay.incomingTargets(List.of(first, second)).size());
    }

    private static ResolvedAttack.Shot shot(boolean artillery) {
        return new ResolvedAttack.Shot("", Set.of(), artillery, false, 1, 0, artillery, null, null, null, true, 20,
              false, false, false, null, 20);
    }
}
