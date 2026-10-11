/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.gpu.GpuExplosionEffects.Look;
import megamek.common.ResolvedAttack;
import megamek.common.annotations.Nullable;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponType;
import megamek.common.weapons.handlers.AreaEffectHelper;
import megamek.logging.MMLogger;

/**
 * The ground blast of an artillery shell: a choreography of fire and smoke volumes, drawn by {@link GpuExplosionEffects},
 * that outlives the attack which caused it. An attack's own clock ends a third of a second after contact, but a shell's
 * fire, thrown earth and smoke column take several seconds to rise and drift away, so each blast keeps its own clock.
 *
 * <p>A blast is one of three shapes, chosen per shell so a barrage does not repeat itself: a crown of jagged spikes of
 * earth and fire with a dust skirt, a fireball rising into a mushroom on a thin stem, or a bright billowing fireball
 * over a dark base. The crown and the fireball leave a dark smoke mass that the wind carries off; the mushroom's cap
 * darkens into its own.</p>
 */
final class GpuArtilleryBlast {
    private static final MMLogger LOGGER = MMLogger.create(GpuArtilleryBlast.class);

    /** The blast shapes, after the reference sheet the effect was drawn from. */
    enum Style { CROWN, MUSHROOM, FIREBALL }

    /** How long the slowest layer of a blast (its smoke) lasts. */
    static final float SECONDS = 5.5f;
    /** At most this many blasts draw at once. A heavier barrage reuses the oldest blast's slot. */
    static final int MAX_BLASTS = 6;
    /** Rounds that do not explode on landing: they lay mines, screen with smoke or light the field. */
    private static final Set<String> NON_EXPLOSIVE = Set.of("M_FASCAM", "M_THUNDER", "M_THUNDER_ACTIVE",
          "M_THUNDER_AUGMENTED", "M_THUNDER_INFERNO", "M_THUNDER_VIBRABOMB", "M_SMOKE", "M_SMOKE_WARHEAD", "M_FLARE",
          "M_LASER_INHIB");
    /** Damage of the shell that draws at the reference size: a Sniper or an Arrow IV. */
    private static final float REFERENCE_DAMAGE = 20;

    private static final Look FIRE = new Look(.85f, .75f, .1f);
    private static final Look BRIGHT_FIRE = new Look(.55f, 1, 0);
    private static final Look EARTH = new Look(.35f, 0, .9f);
    private static final Look SMOKE = new Look(1, .45f, .1f);
    private static final Look CAP = new Look(.9f, .4f, 0);
    private static final Look DEBRIS = new Look(.8f, .3f, .6f);
    /** Debris thrown per ring of hexes the shell damages; a shell that damages only its own hex throws a few. */
    private static final int DEBRIS_PER_RING = 8;
    private static final int MAX_DEBRIS = 24;

    private final List<Blast> blasts = new ArrayList<>();
    private final Vector3 point = new Vector3();
    private final Vector3 axis = new Vector3();

    /** One shell's blast, as captured at contact, with its own clock in seconds. */
    private static final class Blast {
        final Vector3 ground = new Vector3();
        final float size;
        final int hexRadius;
        final Style style;
        final int key;
        float seconds;

        Blast(Vector3 ground, float size, int hexRadius, Style style, int key) {
            this.ground.set(ground);
            this.size = size;
            this.hexRadius = hexRadius;
            this.style = style;
            this.key = key;
        }
    }

    /**
     * Whether the shot's landing should draw a blast: an artillery round, but not one that lays mines, screens with
     * smoke or lights the field, which keep their own presentation.
     */
    static boolean explodes(@Nullable ResolvedAttack.Shot profile) {
        if (profile == null || !profile.artillery()) { return false; }
        for (String munition : profile.munitions()) {
            if (NON_EXPLOSIVE.contains(munition)) { return false; }
        }
        return true;
    }

    /**
     * The radius, in world units, of the blast of this shell. A 20-damage shell spans about a hex; lighter and heavier
     * shells scale with their damage, within limits that keep a cannon round visible and a Long Tom on the map.
     */
    static float size(@Nullable ResolvedAttack.Shot profile) {
        double damage = profile == null || profile.damagePerHit() <= 0 ? REFERENCE_DAMAGE : profile.damagePerHit();
        float scale = MathUtils.clamp((float) Math.sqrt(damage / REFERENCE_DAMAGE), .65f, 1.35f);
        if (profile != null && profile.munitions().contains("M_DAVY_CROCKETT_M")) { scale = 2.5f; }
        else if (profile != null && profile.munitions().contains("M_FAE")) { scale *= 1.3f; }
        return BoardGeometry.height() * .55f * scale;
    }

    /** The shape of the blast: a nuclear round always mushrooms and fuel-air or anti-air rounds burn as a fireball. */
    static Style style(@Nullable ResolvedAttack.Shot profile, int seed) {
        Set<String> munitions = profile == null ? Set.of() : profile.munitions();
        if (munitions.contains("M_DAVY_CROCKETT_M")) { return Style.MUSHROOM; }
        if (munitions.contains("M_FAE") || munitions.contains("M_ADA")) { return Style.FIREBALL; }
        // Crowns are what a shell landing in earth mostly throws up; the other two keep a barrage varied.
        float pick = random(seed, 7);
        return pick < .5f ? Style.CROWN : pick < .75f ? Style.MUSHROOM : Style.FIREBALL;
    }

    /**
     * How many rings of hexes around the target the shell damages, the R value of the Artillery Ordnance Table: 0 is
     * the target hex alone, 2 is a Long Tom's target hex and the two rings around it. It is asked of the same rule the
     * server deals the damage with, for the round whose munitions the shot carried.
     *
     * @param weaponName the internal name of the weapon that fired, or {@code null} when unknown
     * @param profile    the shot, or {@code null} when unknown
     *
     * @return the radius in hexes; 0 when the weapon or its round cannot be found
     */
    static int hexRadius(@Nullable String weaponName, @Nullable ResolvedAttack.Shot profile) {
        if (weaponName == null || profile == null) { return 0; }
        if (!(EquipmentType.get(weaponName) instanceof WeaponType weapon)) {
            LOGGER.debug("[ArtilleryBlast] {} is not a weapon, the blast stays in its hex", weaponName);
            return 0;
        }
        AmmoType round = round(weapon, profile.munitions());
        if (round == null) {
            LOGGER.debug("[ArtilleryBlast] no {} round carries {}, the blast stays in its hex", weaponName,
                  profile.munitions());
            return 0;
        }
        return Math.max(0, AreaEffectHelper.calculateDamageFallOff(round, 0, false).radius);
    }

    /** The round of the weapon's ammunition family whose munitions are exactly those the shot carried. */
    private static @Nullable AmmoType round(WeaponType weapon, Set<String> munitions) {
        AmmoType standard = null;
        for (AmmoType candidate : AmmoType.getMunitionsFor(weapon.getAmmoType())) {
            Set<String> names = new HashSet<>();
            for (AmmoType.Munitions munition : candidate.getMunitionType()) { names.add(munition.name()); }
            if (names.equals(munitions)) { return candidate; }
            if (standard == null && names.contains("M_STANDARD")) { standard = candidate; }
        }
        // A shot captured without its munitions is a standard round.
        return munitions.isEmpty() ? standard : null;
    }

    /**
     * Starts the blast of a shell landing at {@code ground}. The impact is reported on every frame of the attack's
     * contact window, so a {@code key} already blasting is ignored.
     *
     * @param weaponName the internal name of the weapon that fired, or {@code null} when unknown
     */
    void detonate(Vector3 ground, @Nullable ResolvedAttack.Shot profile, @Nullable String weaponName, int key) {
        for (Blast blast : blasts) {
            if (blast.key == key) { return; }
        }
        if (blasts.size() == MAX_BLASTS) {
            LOGGER.debug("[ArtilleryBlast] {} blasts already drawing, the oldest makes way", MAX_BLASTS);
            blasts.removeFirst();
        }
        var blast = new Blast(ground, size(profile), hexRadius(weaponName, profile), style(profile, key), key);
        blasts.add(blast);
        LOGGER.info("[ArtilleryBlast] {} blast of radius {} with debris over R{} at {}", blast.style, blast.size,
              blast.hexRadius, ground);
    }

    /** Ages every blast and retires those whose smoke has cleared. */
    void advance(float seconds) {
        for (Blast blast : blasts) { blast.seconds += seconds; }
        blasts.removeIf(blast -> blast.seconds >= SECONDS);
    }

    int size() { return blasts.size(); }

    void clear() { blasts.clear(); }

    /** Queues the volumes of every live blast. */
    void emit(GpuExplosionEffects explosions) {
        for (Blast blast : blasts) {
            emit(explosions, blast.ground, blast.size, blast.hexRadius, blast.style, blast.seconds, blast.key);
        }
    }

    /**
     * Queues the volumes of one blast {@code seconds} after contact. The result depends only on its arguments, so the
     * shader preview and the review sheets can show any moment of any blast. The blast itself stays in the target hex;
     * its debris reaches the outer edge of the last ring of hexes that {@code hexRadius} says the shell damages.
     */
    void emit(GpuExplosionEffects explosions, Vector3 ground, float size, int hexRadius, Style style, float seconds,
          int seed) {
        if (seconds < 0 || seconds >= SECONDS) { return; }
        debris(explosions, ground, size, hexRadius, seconds, seed);
        switch (style) {
            case CROWN -> crown(explosions, ground, size, seconds, seed);
            case MUSHROOM -> mushroom(explosions, ground, size, seconds, seed);
            case FIREBALL -> fireball(explosions, ground, size, seconds, seed);
        }
        // A mushroom's cap is its smoke: it darkens where it is instead of leaving a second column under it.
        // The fireball's own smoke follows once it burns down; drawn earlier, the column cuts a dark streak through it.
        if (style == Style.CROWN) { smokeColumn(explosions, ground, size, seconds, seed, .4f); }
        if (style == Style.FIREBALL) { smokeColumn(explosions, ground, size, seconds, seed, 1.9f); }
    }

    /** Spikes of fire and earth thrown up and out in a cone around a low core, with a dust skirt racing outward. */
    private void crown(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed) {
        core(explosions, ground, size, seconds, seed, 1.1f);
        int spikes = 11;
        float life = 2.6f;
        float age = seconds / life;
        if (age < 1) {
            float thrown = 1 - (float) Math.exp(-age * 5);
            for (int spike = 0; spike < spikes; spike++) {
                float bearing = spike * 2.399963f + random(seed, spike) * .6f;
                float tilt = MathUtils.lerp(.12f, .95f, random(seed, spike + 20));
                float reach = size * MathUtils.lerp(.7f, 1.3f, random(seed, spike + 40));
                axis.set(MathUtils.sin(tilt) * MathUtils.cos(bearing), MathUtils.sin(tilt) * MathUtils.sin(bearing),
                      MathUtils.cos(tilt));
                point.set(ground).mulAdd(axis, size * .12f + reach * thrown * .6f);
                float radius = size * MathUtils.lerp(.24f, .32f, random(seed, spike + 60)) * (.45f + .55f * thrown);
                axis.scl(1.6f * (1 - age * .45f));
                // Every other spike is mostly earth and smoke, so the crown is fire mixed through dark smoke.
                float fire = spike % 2 == 0 ? 1 : .55f;
                explosions.add(point, radius, age, fire, fadeOut(age, .7f), seed + spike * 13, FIRE, axis);
            }
        }
        skirt(explosions, ground, size, seconds, seed);
    }

    /** A fireball that rises on a thin smoke stem and rolls into a dark cap, with fire still burning at its foot. */
    private void mushroom(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed) {
        float age = seconds / SECONDS;
        float rise = 1 - (float) Math.exp(-seconds * .75f);
        float capHeight = size * (.3f + 1.5f * rise);
        float capRadius = size * (.34f + .3f * rise);
        float alpha = fadeOut(age, .6f);
        point.set(ground).add(0, 0, capHeight);
        explosions.add(point, capRadius, age, 1, alpha, seed, CAP, Vector3.Zero);
        // The cap's billows: smaller lobes around and above its middle make the cauliflower top.
        for (int lobe = 0; lobe < 5; lobe++) {
            float bearing = lobe * MathUtils.PI2 / 5 + random(seed, lobe + 80);
            float out = lobe == 4 ? 0 : capRadius * .6f;
            float lift = lobe == 4 ? .5f : .15f + .3f * random(seed, lobe + 90);
            point.set(ground).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out, capHeight + capRadius * lift);
            explosions.add(point, capRadius * .62f, age, 1, alpha, seed + lobe * 17, CAP, Vector3.Zero);
        }
        // The stem follows the cap up once it has left the ground: overlapping puffs, so it reads as one column.
        float stemTop = capHeight - capRadius * .6f;
        float stemRadius = size * .12f;
        int puffs = Math.min(10, (int) Math.ceil(stemTop / (stemRadius * 1.2f)));
        for (int puff = 0; puff < puffs; puff++) {
            point.set(ground).add(0, 0, stemTop * (puff + .5f) / puffs);
            float flare = 1 + .5f * (1 - (puff + .5f) / puffs);
            explosions.add(point, stemRadius * flare, age, .35f, alpha * .85f, seed + puff * 29, SMOKE, Vector3.Zero);
        }
        float footAge = seconds / 2.6f;
        if (footAge < 1) {
            point.set(ground).add(0, 0, size * .08f);
            explosions.add(point, size * .24f, footAge, 1, fadeOut(footAge, .6f), seed + 7, BRIGHT_FIRE, Vector3.Zero);
        }
        skirt(explosions, ground, size * .75f, seconds, seed);
    }

    /** A bright, billowing fireball that lingers before going dark, over a base of black smoke. */
    private void fireball(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed) {
        float life = 3.4f;
        float age = seconds / life;
        if (age >= 1) { return; }
        float swell = 1 - (float) Math.exp(-age * 6);
        float alpha = fadeOut(age, .7f);
        for (int lobe = 0; lobe < 6; lobe++) {
            float bearing = lobe * 2.399963f + random(seed, lobe + 100);
            float out = lobe == 0 ? 0 : size * .36f * swell;
            float lift = lobe == 0 ? .1f : .3f * random(seed, lobe + 110);
            point.set(ground).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out,
                  size * (.6f + .35f * age + lift));
            float radius = size * (lobe == 0 ? .62f : .44f) * (.4f + .6f * swell);
            explosions.add(point, radius, age, 1, alpha, seed + lobe * 19, BRIGHT_FIRE, Vector3.Zero);
        }
        for (int puff = 0; puff < 6; puff++) {
            float bearing = puff * MathUtils.PI2 / 6 + random(seed, puff + 120);
            float out = size * (.3f + .25f * swell);
            point.set(ground).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out, size * .18f);
            explosions.add(point, size * .3f * (.5f + .5f * swell), age, .25f, alpha * .9f, seed + puff * 23, SMOKE,
                  Vector3.Zero);
        }
        skirt(explosions, ground, size * .8f, seconds, seed);
    }

    /**
     * Earth and wreckage thrown out of the crater on high arcs, landing across the hexes the shell also damages, each
     * kicking up a puff of dust where it comes down. The farthest pieces land near the outer edge of the last damaged
     * ring, so a Long Tom's debris carries two hexes out and a Copperhead's stays in its own hex.
     */
    private void debris(GpuExplosionEffects explosions, Vector3 ground, float size, int hexRadius, float seconds,
          int seed) {
        float hex = BoardGeometry.height();
        // The outer edge of the last damaged ring, measured from the target hex's centre.
        float reach = hex * (hexRadius + .45f);
        int pieces = Math.min(MAX_DEBRIS, DEBRIS_PER_RING * Math.max(1, hexRadius));
        float launch = .08f;
        for (int piece = 0; piece < pieces; piece++) {
            float bearing = piece * 2.399963f + random(seed, piece + 200);
            // Square root spreads the landings evenly over the area rather than crowding them near the crater.
            float distance = reach * (float) Math.sqrt(MathUtils.lerp(.15f, 1, random(seed, piece + 220)));
            float flight = .7f + .5f * distance / Math.max(1, reach) + .25f * random(seed, piece + 240);
            float travel = (seconds - launch) / flight;
            float cosine = MathUtils.cos(bearing);
            float sine = MathUtils.sin(bearing);
            if (travel > 0 && travel < 1) {
                float apex = distance * MathUtils.lerp(.35f, .6f, random(seed, piece + 260)) + size * .3f;
                point.set(ground).add(cosine * distance * travel, sine * distance * travel,
                      size * .2f + 4 * apex * travel * (1 - travel));
                // Drawn out along its flight, so a piece reads as a streak of earth trailing dust.
                float climb = 4 * apex * (1 - 2 * travel);
                axis.set(cosine * distance, sine * distance, climb).nor().scl(1.4f);
                float pieceSize = size * MathUtils.lerp(.07f, .12f, random(seed, piece + 280));
                explosions.add(point, pieceSize, travel * .6f, .35f, .9f, seed + piece * 41, DEBRIS, axis);
            }
            float settle = (seconds - launch - flight) / 1.6f;
            if (settle >= 0 && settle < 1) {
                point.set(ground).add(cosine * distance, sine * distance, size * .05f);
                float puffSize = size * (.14f + .18f * settle);
                explosions.add(point, puffSize, settle, 0, .55f * fadeOut(settle, .3f), seed + piece * 43, EARTH,
                      Vector3.Zero);
            }
        }
    }

    /** The first instant: a hot, low core that flashes and swells before the shape of the blast takes over. */
    private void core(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed, float life) {
        float age = seconds / life;
        if (age >= 1) { return; }
        float swell = .35f + .65f * (1 - (float) Math.exp(-age * 9));
        point.set(ground).add(0, 0, size * .25f);
        explosions.add(point, size * .48f * swell, age, 1, fadeOut(age, .5f), seed + 3, BRIGHT_FIRE, Vector3.Zero);
    }

    /** Earth thrown sideways along the ground: a low ring of dust that races out and settles. */
    private void skirt(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed) {
        float life = 2.6f;
        float age = (seconds - .05f) / life;
        if (age < 0 || age >= 1) { return; }
        float spread = 1 - (float) Math.exp(-age * 4);
        int puffs = 12;
        for (int puff = 0; puff < puffs; puff++) {
            float bearing = puff * MathUtils.PI2 / puffs + random(seed, puff + 140) * .3f;
            // Each puff travels and swells by its own amount, so the edge is ragged rather than a neat ring.
            float out = size * (.35f + 1.1f * spread * MathUtils.lerp(.6f, 1.2f, random(seed, puff + 150)));
            float radius = size * .2f * (.7f + .9f * age) * MathUtils.lerp(.7f, 1.3f, random(seed, puff + 170));
            point.set(ground).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out, size * .07f);
            // Drawn out around the ring, so neighbouring puffs merge into one sheet of dust sliding outward.
            axis.set(-MathUtils.sin(bearing), MathUtils.cos(bearing), 0).scl(1.1f);
            explosions.add(point, radius, age, 0, .4f * fadeOut(age, .35f), seed + puff * 31, EARTH, axis);
        }
    }

    /**
     * The dark mass the blast leaves behind, appearing {@code start} seconds after contact: it rises, spreads and
     * drifts with the wind until it clears.
     */
    private void smokeColumn(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed,
          float start) {
        float age = (seconds - start) / (SECONDS - start);
        if (age < 0 || age >= 1) { return; }
        float fadeIn = MathUtils.clamp((seconds - start) / .6f, 0, 1);
        float alpha = fadeIn * fadeOut(age, .5f);
        for (int puff = 0; puff < 4; puff++) {
            float bearing = puff * 2.1f + random(seed, puff + 160);
            point.set(ground).add(MathUtils.cos(bearing) * size * .18f, MathUtils.sin(bearing) * size * .18f,
                  size * (.3f + puff * .26f + age * .9f));
            // Only faint embers: a hotter column reads as a second explosion bursting upward out of the first.
            explosions.add(point, size * (.42f + .45f * age) * (1 - puff * .1f), age, .12f, alpha, seed + puff * 37,
                  SMOKE, Vector3.Zero);
        }
    }

    /** 1 until {@code from}, then easing smoothly to 0 at the end of the layer's life. */
    private static float fadeOut(float age, float from) {
        float fade = MathUtils.clamp((age - from) / (1 - from), 0, 1);
        return 1 - fade * fade * (3 - 2 * fade);
    }

    /** A stable value in {@code [0, 1)} for one detail of one blast, so a paused or replayed blast never flickers. */
    static float random(int seed, int detail) {
        int hash = seed * 0x9E3779B1 + detail * 0x85EBCA6B;
        hash ^= hash >>> 15;
        hash *= 0x2C1B3C6D;
        hash ^= hash >>> 12;
        return (hash >>> 8) / (float) (1 << 24);
    }
}
