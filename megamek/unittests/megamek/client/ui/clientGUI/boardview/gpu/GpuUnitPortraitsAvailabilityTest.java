/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.function.BooleanSupplier;

import megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPortraits.Availability;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The readout's 3D view is for the lobby and unit selection only. During a game the board shows the units already,
 * and while a board window is open it owns the only libGDX application, so the switch must be off with the reason.
 */
class GpuUnitPortraitsAvailabilityTest {

    private static final BooleanSupplier HAS_MODEL = () -> true;
    private static final BooleanSupplier HAS_NO_MODEL = () -> false;
    /** The mekset look-up is the only step that reads data; it must be skipped when the answer is already known. */
    private static final BooleanSupplier MUST_NOT_LOOK_UP = () -> fail("The mekset must not be read here");

    @Test
    void inTheLobbyAUnitWithAModelCanBeShown() {
        assertEquals(Availability.AVAILABLE, GpuUnitPortraits.availability(true, false, false, HAS_MODEL));
    }

    @Test
    void inTheLobbyAUnitWithoutAModelCannot() {
        assertEquals(Availability.NO_MODEL, GpuUnitPortraits.availability(true, false, false, HAS_NO_MODEL));
    }

    @Test
    void duringAGameTheViewIsOffWithoutReadingTheMekset() {
        assertEquals(Availability.IN_GAME, GpuUnitPortraits.availability(true, true, true, MUST_NOT_LOOK_UP));
    }

    @Test
    void anOpenBoardWindowInTheLobbyTurnsTheViewOff() {
        // A map preview opened from the lobby is a board window too.
        assertEquals(Availability.BOARD_OPEN, GpuUnitPortraits.availability(true, false, true, MUST_NOT_LOOK_UP));
    }

    @Test
    void aBuildWithoutModelsNeverOffersTheView() {
        assertEquals(Availability.MODELS_DISABLED,
              GpuUnitPortraits.availability(false, false, false, MUST_NOT_LOOK_UP));
    }

    @Test
    void aUnitFromTheUnitSelectorBelongsToNoGame() {
        Entity unit = new BipedMek();

        assertFalse(GpuUnitPortraits.isInGame(unit));
    }

    @Test
    void aUnitInAGameStillInItsStartingPhaseIsNotInGame() {
        // MekHQ's campaign game and a freshly created game have not reached any phase yet.
        Entity unit = unitInPhase(GamePhase.UNKNOWN);

        assertFalse(GpuUnitPortraits.isInGame(unit));
    }

    @Test
    void aUnitInTheLobbyIsNotInGame() {
        Entity unit = unitInPhase(GamePhase.LOUNGE);

        assertFalse(GpuUnitPortraits.isInGame(unit));
    }

    @ParameterizedTest
    @EnumSource(value = GamePhase.class, names = { "UNKNOWN", "LOUNGE" }, mode = EnumSource.Mode.EXCLUDE)
    void aUnitInAnyLaterPhaseIsInGame(GamePhase phase) {
        Entity unit = unitInPhase(phase);

        assertTrue(GpuUnitPortraits.isInGame(unit), phase.name());
    }

    private static Entity unitInPhase(GamePhase phase) {
        Game game = new Game();
        game.setPhase(phase);
        Entity unit = new BipedMek();
        unit.setGame(game);
        return unit;
    }
}
