/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.boardview.gpu.GpuExplosionEffects.Look;
import megamek.common.ResolvedAttack;
import megamek.common.annotations.Nullable;
import megamek.common.board.Coords;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponType;
import megamek.common.weapons.handlers.AreaEffectHelper;
import megamek.common.weapons.handlers.DamageFalloff;
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
 *
 * <p>The blast stays in the target hex. The hexes around it that the shell also damages each take a piece of debris,
 * thrown on its own arc and landing at its own moment, which leaves a scorch mark sized for that ring's damage.</p>
 */
final class GpuArtilleryBlast {
    private static final MMLogger LOGGER = MMLogger.create(GpuArtilleryBlast.class);

    /**
     * The blast shapes: the first three after the reference sheet the effect was drawn from, and CLUSTER for a round
     * that bursts in the air and scatters bomblets over its target hex and the ring around it.
     */
    enum Style { CROWN, MUSHROOM, FIREBALL, CLUSTER }

    /** How long the slowest layer of a blast (its smoke) lasts. */
    static final float SECONDS = 5.5f;
    /** At most this many blasts draw at once. A heavier barrage reuses the oldest blast's slot. */
    static final int MAX_BLASTS = 6;
    /** At most this many pieces of debris per blast: every hex of four rings, a Cruise Missile/120's R4. */
    static final int MAX_DEBRIS = 60;
    /** Rounds that do not explode on landing: they lay mines, screen with smoke or light the field. */
    private static final Set<String> NON_EXPLOSIVE = Set.of("M_FASCAM", "M_THUNDER", "M_THUNDER_ACTIVE",
          "M_THUNDER_AUGMENTED", "M_THUNDER_INFERNO", "M_THUNDER_VIBRABOMB", "M_SMOKE", "M_SMOKE_WARHEAD", "M_FLARE",
          "M_LASER_INHIB");
    /** Damage of the shell that draws at the reference size: a Sniper or an Arrow IV. */
    private static final float REFERENCE_DAMAGE = 20;
    /** Pieces a shell damaging only its own hex throws, all landing inside that hex. */
    private static final int TARGET_HEX_PIECES = 6;
    /** Any hex away from the board's edge: its neighbours sit at the same offsets as every other hex's. */
    private static final Coords LAYOUT_HEX = new Coords(10, 10);

    private static final Look FIRE = new Look(.85f, .75f, .1f);
    private static final Look BRIGHT_FIRE = new Look(.55f, 1, 0);
    private static final Look EARTH = new Look(.35f, 0, .9f);
    private static final Look SMOKE = new Look(1, .45f, .1f);
    private static final Look CAP = new Look(.9f, .4f, 0);
    private static final Look DEBRIS = new Look(.8f, .3f, .6f);
    private static final Look BURST_SMOKE = new Look(.55f, .1f, 0);
    /** Height of a cluster round's air burst above its target, in hexes. */
    private static final float AIR_BURST_HEXES = 1.3f;
    /** A cluster round's damage that one bomblet stands for: a 20-damage hex gets five, a 5-damage hex two. */
    private static final float DAMAGE_PER_BOMBLET = 4;
    /** Bomblets drawn for each one the damage alone calls for. */
    private static final float BOMBLET_DENSITY = 1.5f;
    /** The cluster round the shader preview shows: a Long Tom's, 20/10 over one ring. */
    static final AreaEffect PREVIEW_CLUSTER = new AreaEffect(1, 20, 10, true);

    private final List<Blast> blasts = new ArrayList<>();
    private final Vector3 point = new Vector3();
    private final Vector3 axis = new Vector3();
    private final Vector3 burst = new Vector3();

    /**
     * The area a shell damages, as the Artillery Ordnance Table gives it: a Long Tom's high-explosive round is
     * 25/15/5 (R2), so {@code damage} 25 in the target hex, {@code falloff} 10 less for each ring, {@code hexRadius} 2.
     *
     * @param hexRadius rings of hexes around the target that take damage
     * @param damage    damage in the target hex
     * @param falloff   damage lost for each ring outward
     * @param cluster   whether the round scatters bomblets instead of blasting once
     */
    record AreaEffect(int hexRadius, int damage, int falloff, boolean cluster) {
        static final AreaEffect TARGET_HEX_ONLY = new AreaEffect(0, 0, 10, false);

        /** The damage a hex {@code ring} rings out takes, never below 1 so its mark stays visible. */
        int ringDamage(int ring) { return Math.max(1, damage - falloff * ring); }
    }

    /**
     * One piece of debris, thrown at one hex.
     *
     * @param offsetX how far east of the target hex's centre it lands, in world units
     * @param offsetY how far north of the target hex's centre it lands, in world units
     * @param ring    the ring of hexes it lands in: 0 is the target hex
     * @param launch  seconds after contact it leaves the crater
     * @param flight  seconds it spends in the air
     */
    record Piece(float offsetX, float offsetY, int ring, float launch, float flight) {
        float landing() { return launch + flight; }
    }

    /** One shell's blast, as captured at contact, with its own clock in seconds. */
    private static final class Blast {
        final Vector3 ground = new Vector3();
        final float size;
        final AreaEffect area;
        final Style style;
        final int key;
        /** The shot, {@code null} for a blast drawn without one. */
        final ResolvedAttack.Shot profile;
        /** The attack that landed the shell, {@code null} for a blast drawn without one. */
        final UnitAttack attack;
        final List<Piece> pieces;
        /** Where each piece lands, on the terrain where it could be found, else level with the crater. */
        final List<Vector3> landings;
        /** Whether each landing was found on the terrain, which a scorch mark needs. */
        final boolean[] onTerrain;
        float seconds;
        /** One bit per piece of debris that has landed, whether or not it left a mark. */
        long landed;
        int marks;
        boolean marksReported;

        Blast(Vector3 ground, @Nullable ResolvedAttack.Shot profile, @Nullable UnitAttack attack, int key) {
            this.ground.set(ground);
            this.profile = profile;
            this.attack = attack;
            this.key = key;
            size = size(profile);
            area = areaEffect(attack == null ? null : attack.event.result().equipmentName(), profile);
            style = area.cluster() ? Style.CLUSTER : style(profile, key);
            pieces = area.cluster() ? bomblets(area, key) : debrisPieces(area.hexRadius(), key);
            Vector3 hexCentre = attack == null ? ground : BoardGeometry.center(attack.event.destination().coords(), 0);
            landings = new ArrayList<>(pieces.size());
            onTerrain = new boolean[pieces.size()];
            for (int index = 0; index < pieces.size(); index++) {
                Piece piece = pieces.get(index);
                float x = hexCentre.x + piece.offsetX();
                float y = hexCentre.y + piece.offsetY();
                Vector3 terrain = attack == null || attack.landscape == null ? null : terrain(attack, x, y, ground.z);
                onTerrain[index] = terrain != null;
                landings.add(terrain != null ? terrain : new Vector3(x, y, ground.z));
            }
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
     * The area the shell damages, with the R value of the Artillery Ordnance Table: 0 is the target hex alone, 2 is a
     * Long Tom's target hex and the two rings around it. It is asked of the same rule the server deals the damage with,
     * for the round whose munitions the shot carried.
     *
     * @param weaponName the internal name of the weapon that fired, or {@code null} when unknown
     * @param profile    the shot, or {@code null} when unknown
     *
     * @return the area; {@link AreaEffect#TARGET_HEX_ONLY} when the weapon or its round cannot be found
     */
    static AreaEffect areaEffect(@Nullable String weaponName, @Nullable ResolvedAttack.Shot profile) {
        if (weaponName == null || profile == null) { return AreaEffect.TARGET_HEX_ONLY; }
        if (!(EquipmentType.get(weaponName) instanceof WeaponType weapon)) {
            LOGGER.debug("[ArtilleryBlast] {} is not a weapon, the blast stays in its hex", weaponName);
            return AreaEffect.TARGET_HEX_ONLY;
        }
        AmmoType round = round(weapon, profile.munitions());
        if (round == null) {
            LOGGER.debug("[ArtilleryBlast] no {} round carries {}, the blast stays in its hex", weaponName,
                  profile.munitions());
            return AreaEffect.TARGET_HEX_ONLY;
        }
        DamageFalloff falloff = AreaEffectHelper.calculateDamageFallOff(round, 0, false);
        return new AreaEffect(Math.max(0, falloff.radius), falloff.damage, falloff.falloff,
              falloff.clusterMunitionsFlag);
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
     * The debris a shell throws: one piece at every hex of every ring it damages, each landing somewhere inside its
     * hex and at its own moment, the outer rings a little later than the inner ones. A shell that damages only its own
     * hex throws a few pieces that stay inside it.
     */
    static List<Piece> debrisPieces(int hexRadius, int seed) {
        List<Piece> pieces = new ArrayList<>();
        float hex = BoardGeometry.height();
        if (hexRadius == 0) {
            for (int piece = 0; piece < TARGET_HEX_PIECES; piece++) {
                float bearing = piece * 2.399963f + random(seed, piece + 200);
                float distance = hex * (.1f + .25f * random(seed, piece + 220));
                pieces.add(new Piece(MathUtils.cos(bearing) * distance, MathUtils.sin(bearing) * distance, 0,
                      launch(seed, piece), .55f + .3f * random(seed, piece + 260)));
            }
            return pieces;
        }
        Vector3 centre = BoardGeometry.center(LAYOUT_HEX, 0);
        for (int ring = 1; ring <= hexRadius; ring++) {
            for (Coords coords : LAYOUT_HEX.allAtDistance(ring)) {
                int piece = pieces.size();
                if (piece == MAX_DEBRIS) { return pieces; }
                Vector3 hexCentre = BoardGeometry.center(coords, 0);
                // Somewhere in the hex rather than its exact centre, so the marks do not form a neat grid.
                float jitter = hex * .22f;
                float offsetX = hexCentre.x - centre.x + (random(seed, piece + 300) - .5f) * 2 * jitter;
                float offsetY = hexCentre.y - centre.y + (random(seed, piece + 320) - .5f) * 2 * jitter;
                float flight = .55f + .3f * ring + .3f * random(seed, piece + 260);
                pieces.add(new Piece(offsetX, offsetY, ring, launch(seed, piece), flight));
            }
        }
        return pieces;
    }

    /**
     * The bomblets of a cluster round: a few in every hex it damages, more where it deals more damage, each landing
     * somewhere inside its hex. They leave the casing over a quarter second and fall for different times, so their
     * pops ripple across the area for about a second instead of going off together.
     */
    static List<Piece> bomblets(AreaEffect area, int seed) {
        List<Piece> pieces = new ArrayList<>();
        float hex = BoardGeometry.height();
        Vector3 centre = BoardGeometry.center(LAYOUT_HEX, 0);
        for (int ring = 0; ring <= area.hexRadius(); ring++) {
            List<Coords> hexes = ring == 0 ? List.of(LAYOUT_HEX) : LAYOUT_HEX.allAtDistance(ring);
            // The ring's whole share, spread as evenly as whole bomblets allow: a share of 4.5 per hex gives three
            // hexes five and three hexes four, rather than rounding every hex the same way.
            int ringTotal = Math.round(bombletsPerHex(area.ringDamage(ring)) * hexes.size());
            for (int hexIndex = 0; hexIndex < hexes.size(); hexIndex++) {
                Vector3 hexCentre = BoardGeometry.center(hexes.get(hexIndex), 0);
                int inHex = ringTotal / hexes.size() + (hexIndex < ringTotal % hexes.size() ? 1 : 0);
                for (int bomblet = 0; bomblet < inHex; bomblet++) {
                    int piece = pieces.size();
                    if (piece == MAX_DEBRIS) { return pieces; }
                    float bearing = random(seed, piece + 400) * MathUtils.PI2;
                    // Square root spreads the bomblets evenly over the hex rather than crowding its centre.
                    float out = hex * .38f * (float) Math.sqrt(random(seed, piece + 420));
                    float offsetX = hexCentre.x - centre.x + MathUtils.cos(bearing) * out;
                    float offsetY = hexCentre.y - centre.y + MathUtils.sin(bearing) * out;
                    float release = .08f + .25f * random(seed, piece + 440);
                    float fall = .35f + .3f * random(seed, piece + 460);
                    pieces.add(new Piece(offsetX, offsetY, ring, release, fall));
                }
            }
        }
        return pieces;
    }

    /**
     * Bomblets for a hex taking {@code damage}, on average: one for about every four points, at least two and at most
     * five, then half as many again ({@link #BOMBLET_DENSITY}) so the area reads as densely covered.
     */
    static float bombletsPerHex(int damage) {
        return MathUtils.clamp(Math.round(damage / DAMAGE_PER_BOMBLET), 2, 5) * BOMBLET_DENSITY;
    }

    /** Whether the shot is a cluster round, which scatters bomblets and leaves no single crater. */
    static boolean scattersBomblets(@Nullable String weaponName, @Nullable ResolvedAttack.Shot profile) {
        return explodes(profile) && areaEffect(weaponName, profile).cluster();
    }

    /** Pieces leave the crater over the first half second rather than all at once. */
    private static float launch(int seed, int piece) {
        return .05f + .5f * random(seed, piece + 240);
    }

    /**
     * Starts the blast of a shell landing at {@code ground}. The impact is reported on every frame of the attack's
     * contact window, so a {@code key} already blasting is ignored.
     *
     * @param attack the attack that landed the shell, which names the weapon, the target hex and the terrain for the
     *               debris, or {@code null} for a blast with none of them
     */
    void detonate(Vector3 ground, @Nullable ResolvedAttack.Shot profile, @Nullable UnitAttack attack, int key) {
        for (Blast blast : blasts) {
            if (blast.key == key) { return; }
        }
        if (blasts.size() == MAX_BLASTS) {
            LOGGER.debug("[ArtilleryBlast] {} blasts already drawing, the oldest makes way", MAX_BLASTS);
            blasts.removeFirst();
        }
        var blast = new Blast(ground, profile, attack, key);
        blasts.add(blast);
        LOGGER.info("[ArtilleryBlast] {} blast of radius {} with {} pieces of debris over R{} at {}", blast.style,
              blast.size, blast.pieces.size(), blast.area.hexRadius(), ground);
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
            emit(explosions, blast.ground, blast.size, blast.pieces, blast.landings, blast.style, blast.seconds,
                  blast.key);
        }
    }

    /**
     * Queues the volumes of one blast {@code seconds} after contact, on level ground centred on its target hex. The
     * result depends only on its arguments, so the shader preview and the review sheets can show any moment of any
     * blast. A {@link Style#CLUSTER} blast shows a Long Tom's cluster round ({@link #PREVIEW_CLUSTER}).
     */
    void emit(GpuExplosionEffects explosions, Vector3 ground, float size, int hexRadius, Style style, float seconds,
          int seed) {
        List<Piece> pieces = style == Style.CLUSTER ? bomblets(PREVIEW_CLUSTER, seed) : debrisPieces(hexRadius, seed);
        List<Vector3> landings = new ArrayList<>(pieces.size());
        for (Piece piece : pieces) {
            landings.add(new Vector3(ground.x + piece.offsetX(), ground.y + piece.offsetY(), ground.z));
        }
        emit(explosions, ground, size, pieces, landings, style, seconds, seed);
    }

    private void emit(GpuExplosionEffects explosions, Vector3 ground, float size, List<Piece> pieces,
          List<Vector3> landings, Style style, float seconds, int seed) {
        if (seconds < 0 || seconds >= SECONDS) { return; }
        if (style == Style.CLUSTER) {
            cluster(explosions, ground, pieces, landings, seconds, seed);
            return;
        }
        debris(explosions, ground, size, pieces, landings, seconds, seed);
        switch (style) {
            case CROWN -> crown(explosions, ground, size, seconds, seed);
            case MUSHROOM -> mushroom(explosions, ground, size, seconds, seed);
            case FIREBALL -> fireball(explosions, ground, size, seconds, seed);
            default -> { }
        }
        // A mushroom's cap is its smoke: it darkens where it is instead of leaving a second column under it.
        // The fireball's own smoke follows once it burns down; drawn earlier, the column cuts a dark streak through it.
        if (style == Style.CROWN) { smokeColumn(explosions, ground, size, seconds, seed, .4f); }
        if (style == Style.FIREBALL) { smokeColumn(explosions, ground, size, seconds, seed, 1.9f); }
    }

    /**
     * A cluster round: the casing bursts in a flash and a puff of smoke above the target, its bomblets fall as dark
     * streaks across the target hex and the ring around it, and each pops in a small sharp flash where it lands,
     * leaving a puff of smoke. The puffs drift together into a thin haze; there is no single fireball.
     */
    private void cluster(GpuExplosionEffects explosions, Vector3 ground, List<Piece> pieces, List<Vector3> landings,
          float seconds, int seed) {
        float hex = BoardGeometry.height();
        burst.set(ground).add(0, 0, hex * AIR_BURST_HEXES);
        float flashAge = seconds / .35f;
        if (flashAge < 1) {
            explosions.add(burst, hex * (.18f + .3f * flashAge), flashAge, 1, fadeOut(flashAge, .3f), seed + 1,
                  BRIGHT_FIRE, Vector3.Zero);
        }
        // Smoke follows its flash rather than sharing its centre and moment, which would cover the flash's bright core.
        float casingSmokeAge = (seconds - .25f) / 3;
        if (casingSmokeAge >= 0 && casingSmokeAge < 1) {
            explosions.add(burst, hex * (.12f + .2f * casingSmokeAge), casingSmokeAge, .2f,
                  .8f * fadeOut(casingSmokeAge, .4f), seed + 2, BURST_SMOKE, Vector3.Zero);
        }
        for (int index = 0; index < pieces.size(); index++) {
            Piece piece = pieces.get(index);
            Vector3 landing = landings.get(index);
            float travel = (seconds - piece.launch()) / piece.flight();
            if (travel > 0 && travel < 1) {
                // Slow out of the casing, fast into the ground.
                point.set(burst).lerp(landing, travel * travel);
                axis.set(landing).sub(burst).nor().scl(2.2f);
                explosions.add(point, hex * .04f, .5f, 0, .9f, seed + index * 41, DEBRIS, axis);
            }
            float popAge = (seconds - piece.landing()) / .7f;
            if (popAge >= 0 && popAge < 1) {
                point.set(landing).add(0, 0, hex * .05f);
                float popSize = hex * (.05f + .09f * (1 - (float) Math.exp(-popAge * 8)));
                explosions.add(point, popSize, popAge, 1, fadeOut(popAge, .4f), seed + index * 43, BRIGHT_FIRE,
                      Vector3.Zero);
            }
            float smokeAge = (seconds - piece.landing() - .4f) / 2.4f;
            if (smokeAge >= 0 && smokeAge < 1) {
                point.set(landing).add(0, 0, hex * (.06f + .1f * smokeAge));
                explosions.add(point, hex * (.08f + .1f * smokeAge), smokeAge, .1f, .6f * fadeOut(smokeAge, .35f),
                      seed + index * 47, BURST_SMOKE, Vector3.Zero);
            }
        }
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
     * Earth and wreckage thrown out of the crater, one piece at each hex the shell damages, each on its own arc and
     * landing at its own moment with a puff of dust where it comes down.
     */
    private void debris(GpuExplosionEffects explosions, Vector3 ground, float size, List<Piece> pieces,
          List<Vector3> landings, float seconds, int seed) {
        for (int index = 0; index < pieces.size(); index++) {
            Piece piece = pieces.get(index);
            Vector3 landing = landings.get(index);
            float travel = (seconds - piece.launch()) / piece.flight();
            if (travel > 0 && travel < 1) {
                float distance = (float) Math.hypot(landing.x - ground.x, landing.y - ground.y);
                float apex = distance * MathUtils.lerp(.35f, .6f, random(seed, index + 280)) + size * .3f;
                point.set(ground).lerp(landing, travel).add(0, 0, size * .2f * (1 - travel)
                      + 4 * apex * travel * (1 - travel));
                // Drawn out along its flight, so a piece reads as a streak of earth trailing dust.
                float climb = 4 * apex * (1 - 2 * travel) + (landing.z - ground.z);
                axis.set(landing.x - ground.x, landing.y - ground.y, climb).nor().scl(1.4f);
                float pieceSize = size * MathUtils.lerp(.07f, .12f, random(seed, index + 290));
                explosions.add(point, pieceSize, travel * .6f, .35f, .9f, seed + index * 41, DEBRIS, axis);
            }
            float settle = (seconds - piece.landing()) / 1.6f;
            if (settle >= 0 && settle < 1) {
                point.set(landing).add(0, 0, size * .05f);
                float puffSize = size * (.14f + .18f * settle);
                explosions.add(point, puffSize, settle, 0, .55f * fadeOut(settle, .3f), seed + index * 43, EARTH,
                      Vector3.Zero);
            }
        }
    }

    /**
     * Hands over a scorch mark for each piece of debris that has just landed in a ring around the target hex, sized for
     * the damage that ring takes, so the burned ground shows how far the shell reached. Debris landing in the target
     * hex leaves nothing more, as the shell's own crater already marks it. Each mark is handed over once.
     */
    void groundMarks(Consumer<GpuGroundDamage.Impact> receiver) {
        for (Blast blast : blasts) {
            if (!leavesRingMarks(blast)) { continue; }
            for (int index = 0; index < blast.pieces.size(); index++) {
                long bit = 1L << index;
                Piece piece = blast.pieces.get(index);
                boolean handled = (blast.landed & bit) != 0;
                if (handled || blast.seconds < piece.landing()) { continue; }
                blast.landed |= bit;
                // A high-explosive shell's own crater marks its target hex; a cluster round has none, so its
                // bomblets mark the target hex too.
                boolean isCraterHex = piece.ring() == 0 && !blast.area.cluster();
                if (isCraterHex || !blast.onTerrain[index]) { continue; }
                blast.marks++;
                ResolvedAttack.Shot markShot = blast.profile.withDamagePerHit(markDamage(blast.area, piece.ring()));
                receiver.accept(new GpuGroundDamage.Impact(blast.attack, "artillery-debris-" + index,
                      blast.ground.cpy(), blast.landings.get(index).cpy(), "bomb", markShot));
            }
            boolean allLanded = blast.landed == (1L << blast.pieces.size()) - 1;
            if (allLanded && !blast.marksReported) {
                blast.marksReported = true;
                LOGGER.debug("[ArtilleryBlast] {} debris marks over R{} around {}", blast.marks,
                      blast.area.hexRadius(), blast.ground);
            }
        }
    }

    /**
     * The damage one mark is sized for: a ring's damage for a high-explosive shell's debris, and a bomblet's share of
     * its hex's damage for a cluster round, so a cluster round freckles the ground with small marks.
     */
    static double markDamage(AreaEffect area, int ring) {
        int damage = area.ringDamage(ring);
        return area.cluster() ? (double) damage / bombletsPerHex(damage) : damage;
    }

    /** Whether the blast has rings around its target to mark, and the attack and shot it needs to mark them. */
    private static boolean leavesRingMarks(Blast blast) {
        if (blast.area.hexRadius() == 0 || blast.profile == null) { return false; }
        return blast.attack != null && blast.attack.landscape != null;
    }

    /** Where a vertical line through {@code x}, {@code y} meets the installed terrain, searching well above and below. */
    private static @Nullable Vector3 terrain(UnitAttack attack, float x, float y, float near) {
        float drop = BoardGeometry.height() * 8;
        var ray = new Ray(new Vector3(x, y, near + drop / 2), new Vector3(0, 0, -1));
        BoardGeometry.Hit hit = attack.landscape.apply(ray);
        // A hit's distance is squared, as every landscape query reports it.
        return hit == null ? null : ray.getEndPoint(new Vector3(), (float) Math.sqrt(hit.distance()));
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
