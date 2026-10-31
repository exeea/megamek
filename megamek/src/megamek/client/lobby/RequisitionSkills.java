/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import megamek.common.battleValue.BVCalculator;

/**
 * The pilot skills the Requisition view's adding strip gives the units it adds: the unit file's own crew, random skills
 * from the client's skill generator (as the unit selector dialog gives them when the "average skills" preference is
 * on), or the gunnery and piloting set in the strip. The Battle Value shown follows set skills.
 *
 * @param mode     where the skills come from
 * @param gunnery  the gunnery skill to set, used in {@link Mode#FIXED} only
 * @param piloting the piloting skill to set, used in {@link Mode#FIXED} only
 */
public record RequisitionSkills(Mode mode, int gunnery, int piloting) {

    /** Where the skills of an added unit come from. */
    public enum Mode {
        /** The unit file's crew, as the dialog leaves them with the "average skills" preference off. */
        DEFAULT,
        /** The skill generator's, as the dialog sets them with the preference on. */
        RANDOM,
        /** The gunnery and piloting set in the strip. */
        FIXED
    }

    /** The skills of a unit file's crew when it names none: gunnery 4, piloting 5. */
    public static final int DEFAULT_GUNNERY = 4;
    public static final int DEFAULT_PILOTING = 5;
    /** The skill range the strip offers. */
    public static final int MIN_SKILL = 0;
    public static final int MAX_SKILL = 8;

    public RequisitionSkills {
        gunnery = Math.max(MIN_SKILL, Math.min(MAX_SKILL, gunnery));
        piloting = Math.max(MIN_SKILL, Math.min(MAX_SKILL, piloting));
    }

    /** The strip's skills when it opens: random when the client prefers them, as the dialog does, else the file's. */
    public static RequisitionSkills initial(boolean randomPreferred) {
        return new RequisitionSkills(randomPreferred ? Mode.RANDOM : Mode.DEFAULT, DEFAULT_GUNNERY,
              DEFAULT_PILOTING);
    }

    public RequisitionSkills withMode(Mode next) {
        return new RequisitionSkills(next, gunnery, piloting);
    }

    public RequisitionSkills withGunnery(int next) {
        return new RequisitionSkills(mode, next, piloting);
    }

    public RequisitionSkills withPiloting(int next) {
        return new RequisitionSkills(mode, gunnery, next);
    }

    /** The Battle Value of a unit worth {@code bv} at 4/5 once it has these skills; unchanged unless they are set. */
    public int bv(int bv) {
        if (mode != Mode.FIXED) {
            return bv;
        }
        return (int) Math.round(bv * BVCalculator.bvSkillMultiplier(gunnery, piloting));
    }
}
