/**
 * MegaMek - Copyright (C) 2004,2005 Ben Mazur (bmazur@sev.org)
 *
 *  This program is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation; either version 2 of the License, or (at your option)
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 *  for more details.
 */
/*
 * Created on Sep 2, 2004
 *
 */
package megamek.common.weapons.capitalweapons;

import megamek.common.Mounted;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.weapons.lasers.EnergyWeapon;

/**
 * @author Jay Lawson
 */
public abstract class NPPCWeapon extends EnergyWeapon {
    /**
     *
     */
    private static final long serialVersionUID = 414010600231978506L;

    public NPPCWeapon() {
        super();
        atClass = CLASS_CAPITAL_PPC;
        capital = true;
        flags = flags.andNot(F_PROTO_WEAPON).andNot(F_MEK_WEAPON).andNot(F_TANK_WEAPON);
    }

    @Override
    public int getBattleForceClass() {
        return BFCLASS_CAPITAL;
    }

    @Override
    public double getBattleForceDamage(int range, Mounted<?> linked) {
        int maxRange = damage == 7 ? AlphaStrikeElement.LONG_RANGE : AlphaStrikeElement.EXTREME_RANGE;
        return (range <= maxRange) ? damage : 0;
    }
}
