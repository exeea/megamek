/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.units;

import java.util.ArrayList;
import java.util.List;

import megamek.common.Hex;
import megamek.common.annotations.Nullable;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.TaintedAtmosphereRules;

/**
 * The exposure status each location of a unit takes in a hex: wet where the water reaches it, otherwise what the air
 * gives it. It rolls nothing; the server makes the breach checks for the wet locations.
 */
public final class WaterExposure {

    private WaterExposure() { }

    /**
     * Sets the exposure status of every location of the unit at this elevation in the hex, as the server does when the
     * unit enters the hex. A breached location stays breached ("Even if a unit exits the water, all limbs and equipment
     * in the flooded location remain non-functional", TW p.121). A null hex changes nothing.
     *
     * @param isJump    whether the unit jumps into the hex (it then keeps its air status)
     * @param elevation the elevation the unit is at in the hex
     *
     * @return the wet locations the water reaches (never a breached one), which the server then checks for a breach in
     *       this order
     */
    public static List<Integer> apply(Entity entity, @Nullable Hex hex, boolean isJump, int elevation,
          PlanetaryConditions conditions) {
        List<Integer> wet = new ArrayList<>();
        boolean aeroSpaceborne = (entity.getEntityType() & Entity.ETYPE_AERO) == 0 && entity.isSpaceborne();
        if (hex == null) {
            return wet;
        }
        if ((hex.terrainLevel(Terrains.WATER) > 0) && !isJump && (elevation < 0)) {
            int partialWaterLevel = 1;
            if ((entity instanceof Mek) && entity.isSuperHeavy()) {
                partialWaterLevel = 2;
            }
            if ((entity instanceof Mek) &&
                  !entity.isProne() &&
                  (hex.terrainLevel(Terrains.WATER) <= partialWaterLevel)) {
                for (int loop = 0; loop < entity.locations(); loop++) {
                    entity.setLocationStatus(loop, airExposureStatus(entity, loop, conditions, aeroSpaceborne));
                }
                wet.add(Mek.LOC_RIGHT_LEG);
                wet.add(Mek.LOC_LEFT_LEG);
                if (entity instanceof QuadMek) {
                    wet.add(Mek.LOC_RIGHT_ARM);
                    wet.add(Mek.LOC_LEFT_ARM);
                }
                if (entity instanceof TripodMek) {
                    wet.add(Mek.LOC_CENTER_LEG);
                }
                for (int location : wet) {
                    entity.setLocationStatus(location, ILocationExposureStatus.WET);
                }
                // A breached leg stays breached, and its breach check would do nothing.
                wet.removeIf(location -> entity.getLocationStatus(location) == ILocationExposureStatus.BREACHED);
            } else {
                boolean isOutOfTheWater = entity.relHeight() >= 0;
                for (int loop = 0; loop < entity.locations(); loop++) {
                    // A breach does not heal by moving. Leaving it marked stops a later pass over the same water
                    // resetting it to merely wet and announcing the same hole all over again.
                    if (entity.getLocationStatus(loop) == ILocationExposureStatus.BREACHED) {
                        continue;
                    }
                    int status = isOutOfTheWater ?
                          airExposureStatus(entity, loop, conditions, aeroSpaceborne) :
                          ILocationExposureStatus.WET;
                    entity.setLocationStatus(loop, status);
                    if (status == ILocationExposureStatus.WET) {
                        wet.add(loop);
                    }
                }
            }
        } else {
            for (int loop = 0; loop < entity.locations(); loop++) {
                // Climbing out of the water does not close a breach either.
                if (entity.getLocationStatus(loop) == ILocationExposureStatus.BREACHED) {
                    continue;
                }
                entity.setLocationStatus(loop, airExposureStatus(entity, loop, conditions, aeroSpaceborne));
            }
        }
        return wet;
    }

    /**
     * The exposure status one location takes from the air around it. A vacuum or trace atmosphere exposes every
     * location (TO:AR p.52); a tainted or toxic atmosphere exposes only the locations whose breach the rules give an
     * effect to, which {@link TaintedAtmosphereRules#isLocationExposedToTaint} decides (TO:AR p.54).
     *
     * @param aeroSpaceborne whether this is a non-aerospace unit that is nonetheless in space
     */
    private static int airExposureStatus(Entity entity, int location, PlanetaryConditions conditions,
          boolean aeroSpaceborne) {
        if (conditions.getAtmosphere().isLighterThan(Atmosphere.THIN) || aeroSpaceborne) {
            return ILocationExposureStatus.VACUUM;
        }
        if (TaintedAtmosphereRules.isLocationExposedToTaint(entity, location, conditions.getAtmosphericTaint())) {
            return ILocationExposureStatus.TAINTED;
        }
        return ILocationExposureStatus.NORMAL;
    }
}
