/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import megamek.common.planetaryConditions.PlanetaryConditions;

/** Scenario-derived visual settings owned by the GPU thread; tuning never changes planetary game conditions. */
final class BoardAtmosphere {
    static final float MIN_GROUND_LAYER_HEIGHT = 1.0f;
    static final float STANDARD_GROUND_LAYER_HEIGHT = 2.0f;
    static final Settings DEFAULTS = new Settings(13, 0, 0, STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    static final float MAX_FOG_OPACITY = 0.25f;
    /** Blowing sand keeps at least this much ground fog, so its dust reads even with fog none. */
    static final float MIN_SAND_FOG = 0.05f;
    /** Fraction of the night sky's fill moved into moonlight where the moon casts shadows; 0 keeps the plain moon. */
    static final float MOONLIGHT_SHADOW_CONTRAST = 0.7f;
    /** Artistic palette blends, not gas opacity or a change to gameplay visibility. */
    static final float TAINTED_COLOR_STRENGTH = 0.45f;
    static final float TOXIC_COLOR_STRENGTH = 1.0f;
    static final float DEFAULT_TAINT_STRENGTH = 1.0f;
    /** How much of the air's palette blend reaches the display-space grade that covers every drawn surface. */
    static final float TAINT_GRADE_SHARE = 0.75f;

    private static final int CAUSTIC_TAINT_COLOR = 0xc4c07aff;
    private static final int POISON_TAINT_COLOR = 0x9e8aa6ff;
    private static final int FLAMMABLE_TAINT_COLOR = 0xc58e68ff;
    /** Keep low-angle shadows about five caster-heights long without unbounded incidence compensation. */
    private static final float MIN_LIGHT_ALTITUDE = 0.24f;
    /**
     * The moon stays higher, casting shadows about two caster-heights long: its beam carries the night fill that the
     * moon contrast moves into it, and at a grazing angle it would light walls and plants brighter than daylight.
     */
    private static final float MIN_MOON_ALTITUDE = 0.6f;
    /** The sun and the moon hand the one shadow-casting light over at this sun altitude (about 05:39 and 18:21). */
    private static final float HANDOVER = -0.09f;
    /** Sun altitudes over which the sun's shadows fade in above the handover (about 40 minutes)... */
    private static final float SUN_FADE = 0.17f;
    /** ...and the moon's below it (about 45 minutes), as the twilight sky darkens. */
    private static final float MOON_FADE = 0.19f;

    // The light model. Light is linear RGB in one unit: a white level surface in clear noon light receives
    // luminance 1. Sun and sky set the day, moon and night sky the night, and the view then adapts (ADAPTATION).
    /** Optical depth per air mass, Rayleigh scattering plus light dust: a sinking sun dims, warms and reddens. */
    private static final float[] EXTINCTION = { 0.12f, 0.18f, 0.32f };
    /** Keeps the air mass finite: 1 with the sun overhead, about 9.3 on the horizon. */
    private static final float AIR_MASS_OFFSET = 0.12f;
    /** Sunlight against sky fill on clear noon level ground: deep shadows, as in the desert reference. */
    private static final float KEY_FILL = 4;
    /** A neutral day sky fill, a little cooler than the sun, that falls to SKY_HORIZON as the sun sets. */
    private static final Color SKY_TINT = rgb(0.96f, 1, 1.06f);
    private static final float SKY_HORIZON = 0.3f;
    /** Around sunset the deep blue sky overhead fills the shade, cool against the golden sun. */
    private static final Color TWILIGHT_SKY = rgb(0.68f, 0.96f, 1.32f);
    /** Dawn and dusk warm the diffuse fill toward the glowing horizon, at constant luminance. */
    private static final Color WARM_GLOW = rgb(1, 0.78f, 0.58f);
    private static final float WARM_SHARE = 0.25f;
    /** Moonlight and the night sky are cool, but only a little blue. */
    private static final Color NIGHT_TINT = rgb(0.85f, 0.93f, 1);
    /** Full-moon light on level ground against clear noon, in stops, after the view's adaptation. */
    private static final float FULL_MOON_STOPS = -2;
    /** The full moon on level ground against the night sky, before MOONLIGHT_SHADOW_CONTRAST moves fill into it. */
    private static final float MOON_KEY = 2.5f;
    /** The view adapts to this share of a change in light, in stops: dusk and night stay darker, but readable. */
    private static final float ADAPTATION = 0.6f;
    /** Fog and haze scatter this share of the light that reaches level ground. */
    private static final float FOG_ALBEDO = 0.8f;
    /** Linear albedo of blowing sand: the sand fog and the composite's sand veil (GpuAtmosphere.bindSand) share it. */
    static final Color SAND_DUST = rgb(0.57f, 0.38f, 0.17f);
    /** Derived levels: clear noon sun and sky give level ground luminance 1, in the ratio KEY_FILL to 1. */
    private static final float NOON_INCIDENCE = -direction(12, true).z;
    private static final float SUN_LEVEL = KEY_FILL / (1 + KEY_FILL) / NOON_INCIDENCE / luminance(transmittance(1));
    private static final float SKY_LEVEL = 1 / (1 + KEY_FILL);
    /** Full-moon light overhead, before adaptation, so that the adapted moon lands FULL_MOON_STOPS below noon. */
    private static final float NIGHT_LEVEL = (float) Math.pow(2, FULL_MOON_STOPS / (1 - ADAPTATION));

    /** Ground layer height is in terrain levels; exposure is in stops. Night defaults to full moon unless explicitly suppressed. */
    record Settings(float hour, float clouds, float fog, float groundLayerHeight, float haze, float exposure,
          Effects effects, Atmosphere pressure, int temperature, boolean moonlight, AtmosphericTaint taint, float gravity) {
        Settings(float hour, float clouds, float fog, float groundLayerHeight, float haze, float exposure) {
            this(hour, clouds, fog, groundLayerHeight, haze, exposure, Effects.NONE);
        }

        Settings(float hour, float clouds, float fog, float groundLayerHeight, float haze, float exposure, Effects effects) {
            this(hour, clouds, fog, groundLayerHeight, haze, exposure, effects, Atmosphere.STANDARD);
        }

        Settings(float hour, float clouds, float fog, float groundLayerHeight, float haze, float exposure,
              Effects effects, Atmosphere pressure) {
            this(hour, clouds, fog, groundLayerHeight, haze, exposure, effects, pressure, 25);
        }

        Settings(float hour, float clouds, float fog, float groundLayerHeight, float haze, float exposure,
              Effects effects, Atmosphere pressure, int temperature) {
            this(hour, clouds, fog, groundLayerHeight, haze, exposure, effects, pressure, temperature, true);
        }

        Settings(float hour, float clouds, float fog, float groundLayerHeight, float haze, float exposure,
              Effects effects, Atmosphere pressure, int temperature, boolean moonlight) {
            this(hour, clouds, fog, groundLayerHeight, haze, exposure, effects, pressure, temperature, moonlight,
                  AtmosphericTaint.BREATHABLE);
        }

        Settings(float hour, float clouds, float fog, float groundLayerHeight, float haze, float exposure,
              Effects effects, Atmosphere pressure, int temperature, boolean moonlight, AtmosphericTaint taint) {
            this(hour, clouds, fog, groundLayerHeight, haze, exposure, effects, pressure, temperature, moonlight, taint, 1);
        }

        Settings {
            if (!Float.isFinite(hour) || !Float.isFinite(clouds) || !Float.isFinite(fog)
                  || !Float.isFinite(groundLayerHeight) || !Float.isFinite(haze) || !Float.isFinite(exposure)
                  || !Float.isFinite(gravity)) {
                throw new IllegalArgumentException("Atmosphere settings must be finite");
            }
            hour = ((hour % 24) + 24) % 24;
            clouds = MathUtils.clamp(clouds, 0, 1);
            fog = MathUtils.clamp(fog, 0, 1);
            groundLayerHeight = MathUtils.clamp(groundLayerHeight, MIN_GROUND_LAYER_HEIGHT, 8);
            haze = MathUtils.clamp(haze, 0, 1);
            exposure = MathUtils.clamp(exposure, -2, 2);
            gravity = Math.max(0, gravity);
            java.util.Objects.requireNonNull(effects);
            java.util.Objects.requireNonNull(pressure);
            java.util.Objects.requireNonNull(taint);
            if (pressure.isLighterThan(Atmosphere.THIN)) {
                clouds = 0;
            }
            if (pressure.isLighterThan(Atmosphere.STANDARD)) {
                fog = 0;
                effects = new Effects(0, 0, 0, effects.sand(), 0, effects.wind(), effects.windDirection());
            }
            if (pressure.isVacuum()) {
                haze = 0;
                effects = Effects.NONE;
            }
        }
    }

    /** Intensities are visual only. Wind direction is clockwise from north, in the direction particles travel. */
    record Effects(float rain, float snow, float hail, float sand, float lightning, float wind, float windDirection) {
        static final Effects NONE = new Effects(0, 0, 0, 0, 0, 0, 0);

        Effects {
            if (!Float.isFinite(rain) || !Float.isFinite(snow) || !Float.isFinite(hail) || !Float.isFinite(sand)
                  || !Float.isFinite(lightning) || !Float.isFinite(wind) || !Float.isFinite(windDirection)) {
                throw new IllegalArgumentException("Weather effects must be finite");
            }
            rain = MathUtils.clamp(rain, 0, 1);
            snow = MathUtils.clamp(snow, 0, 1);
            hail = MathUtils.clamp(hail, 0, 1);
            sand = MathUtils.clamp(sand, 0, 1);
            lightning = MathUtils.clamp(lightning, 0, 1);
            wind = MathUtils.clamp(wind, 0, 1);
            windDirection = ((windDirection % 360) + 360) % 360;
        }

        boolean hasParticles() {
            return rain > 0 || snow > 0 || hail > 0;
        }
    }

    /**
     * Derived afresh when settings change. Colors and direction are read-only to consumers. {@code direct},
     * {@code ambient} and {@code fog} are linear light, already exposed for the view, in the model's unit: clear noon
     * light gives white level ground luminance 1. Direct light can exceed 1, so these colors never pass through
     * libGDX's clamping Color operations. {@code sky} and {@code horizon} are display-encoded colors of the air
     * itself; {@code tint} and {@code saturation} grade every drawn surface in the composite, which is the only
     * stage that sees the board and the sky together.
     */
    record Lighting(Vector3 direction, Color direct, Color ambient, Color fog, Color sky, Color horizon, Color tint,
          float saturation, float daylight, boolean sunlight) {
        boolean hasDirectLight() {
            return direct.r > 0 || direct.g > 0 || direct.b > 0;
        }

        /** Linear light on lit level ground, ambient plus direct at its incidence; the camera cannot change it. */
        Color groundLight() {
            return BoardAtmosphere.groundLight(direction, direct, ambient);
        }

        /** Keep the light at the same screen bearing through the board camera's yaw, tilt, pan and zoom. */
        Lighting relativeTo(Camera camera) {
            // The source stays above the screen and board, including the overhead camera pole.
            Vector3 relative = new Vector3(camera.direction).crs(camera.up).nor().scl(direction.x)
                  .mulAdd(camera.up, -Math.abs(direction.y)).mulAdd(camera.direction, -direction.z).nor();
            // Tilting changes incidence on level ground; retain its established direct illumination.
            float compensation = direction.z / relative.z;
            Color compensated = rgb(direct.r * compensation, direct.g * compensation, direct.b * compensation);
            return new Lighting(relative, compensated, ambient, fog, sky, horizon, tint, saturation, daylight, sunlight);
        }
    }

    /** A visual cloud layer, in hex widths. This does not invent meteorological game conditions. */
    record Clouds(float altitude, float thickness, float density, float stratus, float scattering) { }

    static Clouds clouds(Settings settings) {
        Effects effects = settings.effects();
        float storm = Math.max(effects.rain(), Math.max(effects.hail(), effects.lightning()));
        float stratus = Math.max(settings.fog(), Math.max(effects.snow(),
              Math.max(0, (settings.clouds() - 0.7f) / 0.3f) * 0.65f));
        float pressure = switch (settings.pressure()) {
            case VACUUM, TRACE -> 0;
            case THIN -> 0.35f;
            case STANDARD -> 1;
            case HIGH -> 1.2f;
            case VERY_HIGH -> 1.4f;
        };
        boolean thin = settings.pressure().isThin();
        return new Clouds(thin ? 9 : 5 - storm * 1.5f, thin ? 0.6f : (1.5f + storm * 2.5f) * pressure,
              (4.0f + storm * 3) * pressure, thin ? 1 : stratus,
              pressure * (0.003f + settings.haze() * 0.045f + settings.fog() * 0.02f));
    }

    /** Liquid rain wets the ground immediately; this renderer does not simulate accumulation or drying. */
    static float wetness(Settings settings) {
        return permitsWetness(settings) ? settings.effects().rain() : 0;
    }

    static boolean permitsWetness(Settings settings) {
        return settings.pressure().isDenserThan(Atmosphere.THIN) && settings.temperature() > 0;
    }

    private BoardAtmosphere() { }

    /** Follow changed scenario fields unless their visual control was overridden; game conditions remain untouched. */
    static Settings followScenario(Settings current, Settings previous, Settings next) {
        Effects a = current.effects(), b = previous.effects(), c = next.effects();
        return new Settings(follow(current.hour(), previous.hour(), next.hour()),
              follow(current.clouds(), previous.clouds(), next.clouds()),
              follow(current.fog(), previous.fog(), next.fog()),
              follow(current.groundLayerHeight(), previous.groundLayerHeight(), next.groundLayerHeight()),
              follow(current.haze(), previous.haze(), next.haze()),
              follow(current.exposure(), previous.exposure(), next.exposure()),
              new Effects(follow(a.rain(), b.rain(), c.rain()), follow(a.snow(), b.snow(), c.snow()),
                    follow(a.hail(), b.hail(), c.hail()), follow(a.sand(), b.sand(), c.sand()),
                    follow(a.lightning(), b.lightning(), c.lightning()),
                    follow(a.wind(), b.wind(), c.wind()),
                    follow(a.windDirection(), b.windDirection(), c.windDirection())),
              current.pressure() == previous.pressure() ? next.pressure() : current.pressure(),
              current.temperature() == previous.temperature() ? next.temperature() : current.temperature(),
              current.moonlight() == previous.moonlight() ? next.moonlight() : current.moonlight(),
              current.taint() == previous.taint() ? next.taint() : current.taint(),
              follow(current.gravity(), previous.gravity(), next.gravity()));
    }

    private static float follow(float current, float previous, float next) {
        return MathUtils.isEqual(current, previous, 0.00001f) ? next : current;
    }

    /** Map a caller-owned conditions copy; publish immutable settings instead of sharing game conditions with GL. */
    static Settings fromScenario(PlanetaryConditions conditions, boolean inSpace, double timeSample) {
        if (!Double.isFinite(timeSample) || timeSample < 0 || timeSample >= 1) {
            throw new IllegalArgumentException("Scenario time sample must be in [0, 1)");
        }
        // One visual sample per GPU window, reused by snapshots and previews. Never consume game-rule randomness.
        float hour = switch (conditions.getLight()) {
            case DAY, GLARE, SOLAR_FLARE -> hourInWindow(9, 15.5f, timeSample);
            // Twilight occurs at both ends of the day; sun altitude is mirrored across sunrise and sunset.
            case DUSK_DAWN -> timeSample < 0.5 ? hourInWindow(6f, 6.75f, timeSample * 2)
                  : hourInWindow(17.25f, 18f, (timeSample - 0.5) * 2);
            case FULL_MOON, MOONLESS, PITCH_BLACK -> hourInWindow(20, 28, timeSample);
        };
        float exposure = switch (conditions.getLight()) {
            case DAY, DUSK_DAWN, FULL_MOON -> 0; // the light's own adaptation sets these
            case GLARE -> 0.6f;
            case SOLAR_FLARE -> 1.2f;
            case MOONLESS -> -0.6f;
            case PITCH_BLACK -> -1.0f;
        };
        boolean air = !inSpace && !conditions.getAtmosphere().isVacuum();
        // The scenario editor permits precipitation and fog only in standard or denser atmospheres.
        boolean weather = air && conditions.getAtmosphere().isDenserThan(Atmosphere.THIN);
        float rain = 0, snow = 0, hail = 0, lightning = 0;
        if (weather) {
            switch (conditions.getWeather()) {
                case CLEAR -> { }
                case LIGHT_RAIN -> rain = 0.25f;
                case MOD_RAIN -> rain = 0.5f;
                case HEAVY_RAIN -> rain = 0.7f;
                case GUSTING_RAIN -> rain = 0.85f;
                case DOWNPOUR -> rain = 1;
                case LIGHT_SNOW -> snow = 0.25f;
                case MOD_SNOW -> snow = 0.5f;
                case SNOW_FLURRIES -> snow = 0.7f;
                case HEAVY_SNOW -> snow = 1;
                case SLEET -> { rain = 0.35f; snow = 0.35f; }
                case ICE_STORM -> { rain = 0.5f; hail = 0.5f; }
                case LIGHT_HAIL -> hail = 0.25f;
                case HEAVY_HAIL -> hail = 1;
                case LIGHTNING_STORM -> { rain = 0.5f; lightning = 0.65f; }
            }
        }
        float wind = !air || conditions.getWindDirection().isRandomWindDirection() ? 0 : switch (conditions.getWind()) {
            case CALM -> 0;
            case LIGHT_GALE -> 0.2f;
            case MOD_GALE -> 0.4f;
            case STRONG_GALE -> 0.6f;
            case STORM -> 0.8f;
            case TORNADO_F1_TO_F3, TORNADO_F4 -> 1;
        };
        float direction = switch (conditions.getWindDirection()) {
            case SOUTH, RANDOM -> 0;
            case SOUTHWEST -> 60;
            case NORTHWEST -> 120;
            case NORTH -> 180;
            case NORTHEAST -> 240;
            case SOUTHEAST -> 300;
        };
        float sand = air && conditions.isBlowingSandActive() ? 0.6f : 0;
        // Ground fog also implies a hazier sky, so it lifts cloud cover without replacing precipitation clouds.
        float fogDensity = 0;
        float fogClouds = 0;
        if (weather) {
            switch (conditions.getFog()) {
                case FOG_NONE -> { }
                case FOG_LIGHT -> { fogDensity = 0.2f; fogClouds = 0.10f; }
                case FOG_HEAVY -> { fogDensity = 1; fogClouds = 0.25f; }
            }
        }
        float precipitation = Math.max(rain, Math.max(snow, hail));
        float clouds = Math.max(fogClouds, precipitation > 0 ? MathUtils.lerp(0f, 0.8f, precipitation) : 0);
        // Low fog pools around the terrain; haze scales its presence above that layer.
        float pressureReduction = switch (conditions.getAtmosphere()) {
            case HIGH -> 0.75f;
            case VERY_HIGH -> 1.5f;
            default -> 0;
        };
        float height = fogDensity > 0 ? Math.max(MIN_GROUND_LAYER_HEIGHT, STANDARD_GROUND_LAYER_HEIGHT - pressureReduction)
              : DEFAULTS.groundLayerHeight();
        // Sand supplies its own bounded veil in the composite; it adds no haze of its own.
        float haze = fogDensity;
        // Blowing sand still keeps a light ground fog of its own, wherever the air permits fog.
        if (weather && sand > 0) {
            fogDensity = Math.max(fogDensity, MIN_SAND_FOG);
        }
        return new Settings(hour, clouds, fogDensity, height, haze, exposure,
              new Effects(rain, snow, hail, sand, lightning, wind, direction),
              inSpace ? Atmosphere.VACUUM : conditions.getAtmosphere(), conditions.getTemperature(),
              !conditions.getLight().isMoonlessOrPitchBack(), conditions.getAtmosphericTaint(), conditions.getGravity());
    }

    /** Inclusive minute choices match the tuning slider; an end above 24 crosses midnight. */
    private static float hourInWindow(float start, float end, double sample) {
        int choices = Math.round((end - start) * 60) + 1;
        return ((Math.round(start * 60) + (int) (sample * choices)) / 60f) % 24;
    }

    private static float cloudiness(Settings settings) {
        return settings.clouds() * (settings.pressure().isThin() ? 0.35f : 1);
    }

    static Lighting lighting(Settings settings) {
        return lighting(settings, MOONLIGHT_SHADOW_CONTRAST);
    }

    static Lighting lighting(Settings settings, float moonShadowContrast) {
        return lighting(settings, moonShadowContrast, DEFAULT_TAINT_STRENGTH);
    }

    static Lighting lighting(Settings settings, float moonShadowContrast, float taintStrength) {
        // Coverage affects the sky palette. Spatial transmission alone attenuates surface sunlight.
        float clouds = cloudiness(settings);
        float angle = (settings.hour() - 6) * MathUtils.PI / 12;
        float altitude = MathUtils.sin(angle);
        float daylight = smooth(-0.30f, 0.25f, altitude);
        float scattering = switch (settings.pressure()) {
            case VACUUM -> 0;
            case TRACE -> 0.15f;
            case THIN -> 0.45f;
            default -> 1;
        };
        // Warm light peaks at the horizon, independently of how much daylight remains.
        float warmth = smooth(-0.20f, 0.015f, altitude) * (1 - smooth(0.16f, 0.50f, altitude)) * scattering;
        float twilight = (1 - smooth(0.05f, 0.45f, Math.abs(altitude))) * scattering;
        Color warm = settings.hour() < 12 ? new Color(1, 0.53f, 0.33f, 1) : new Color(1, 0.43f, 0.20f, 1);
        // One light casts shadows. The sun's fade out as it sets and the moon's fade in as the twilight darkens (the
        // reverse at dawn); the shadow direction flips at HANDOVER, where both have faded out, so it never jumps.
        // The sun outshines the twilight sky, so its fade eases out gently; the faint moon's starts steeply. Either
        // way the shadows weaken and return at about the same pace on both sides of the flip.
        boolean sunlight = altitude > HANDOVER;
        float sunShadows = smooth(HANDOVER, HANDOVER + SUN_FADE, altitude);
        float moonShadows = settings.moonlight() ? ease((HANDOVER - altitude) / MOON_FADE) : 0;
        Vector3 direction = direction(settings.hour(), sunlight);
        float incidence = -direction.z;
        float moonIncidence = -direction(settings.hour(), false).z;
        // Extinction alone dims and warms the sinking sun. It does not depend on pressure, so airless and
        // standard air light the ground alike.
        Color transmittance = transmittance(altitude);
        float sun = SUN_LEVEL * sunShadows;
        float fill = SKY_LEVEL * (SKY_HORIZON + (1 - SKY_HORIZON) * Math.max(altitude, 0)) * daylight
              / luminance(SKY_TINT);
        // The night sky and the moon, whose irradiance is constant: a lower moon lights level ground less.
        float night = NIGHT_LEVEL / (1 + MOON_KEY) * (1 - daylight) / luminance(NIGHT_TINT);
        float moon = settings.moonlight() ? night * MOON_KEY / NOON_INCIDENCE : 0;
        // Moonlight whose shadows the twilight sky washes out still lights the ground, as fill. The moon contrast
        // moves night-sky fill into the shadow-casting moonlight; lit level ground keeps its light.
        float transfer = moonShadowContrast * moonShadows;
        float moonlight = moon * moonShadows + night * transfer / incidence;
        float nightFill = night * (1 - transfer) + moon * (1 - moonShadows) * moonIncidence;
        Color direct = rgb(sun * transmittance.r + moonlight * NIGHT_TINT.r,
              sun * transmittance.g + moonlight * NIGHT_TINT.g, sun * transmittance.b + moonlight * NIGHT_TINT.b);
        Color ambient = rgb(fill * SKY_TINT.r, fill * SKY_TINT.g, fill * SKY_TINT.b);
        tintAtmosphere(ambient, TWILIGHT_SKY, twilight);
        tintAtmosphere(ambient, WARM_GLOW, warmth * WARM_SHARE);
        ambient.r += nightFill * NIGHT_TINT.r;
        ambient.g += nightFill * NIGHT_TINT.g;
        ambient.b += nightFill * NIGHT_TINT.b;
        // Pre-expose: the view adapts to part of the change in light on level ground; clear noon keeps the unit.
        float exposure = (float) Math.pow(luminance(groundLight(direction, direct, ambient)), -ADAPTATION);
        scale(direct, exposure);
        scale(ambient, exposure);
        // Fog and dust scatter the light that reaches the ground: warm by day, golden at dusk, moonlit at night.
        Color ground = groundLight(direction, direct, ambient);
        float sand = settings.effects().sand();
        Color fog = rgb(ground.r * MathUtils.lerp(FOG_ALBEDO, SAND_DUST.r, sand),
              ground.g * MathUtils.lerp(FOG_ALBEDO, SAND_DUST.g, sand),
              ground.b * MathUtils.lerp(FOG_ALBEDO, SAND_DUST.b, sand));
        // Display-encoded air colors, authored for the composite's neutral exposure of one.
        Color sky = new Color(0.031f, 0.049f, 0.106f, 1)
              .lerp(new Color(0.154f, 0.335f, 0.579f, 1), daylight)
              .lerp(new Color(0.14f, 0.20f, 0.42f, 1), twilight * 0.65f);
        Color horizon = new Color(0.075f, 0.088f, 0.15f, 1)
              .lerp(new Color(0.54f, 0.72f, 0.888f, 1), daylight)
              .lerp(new Color(0.44f, 0.30f, 0.36f, 1), twilight)
              .lerp(new Color(warm).mul(0.75f, 0.75f, 0.75f, 1), warmth * 0.9f);
        // A smooth cloud-colored veil is strongest overhead and fades toward the horizon. No sky texture/pass.
        Color overcast = new Color(0.057f, 0.066f, 0.097f, 1)
              .lerp(new Color(0.618f, 0.669f, 0.734f, 1), daylight)
              .lerp(new Color(0.48f, 0.32f, 0.34f, 1), twilight * 0.55f);
        sky.lerp(overcast, clouds * 0.92f);
        horizon.lerp(overcast, clouds * 0.10f);
        if (settings.pressure().isVacuum()) {
            sky.set(0.002f, 0.003f, 0.006f, 1);
            horizon.set(sky);
            fog.set(sky);
        }
        // The light carries the white balance, so the grade holds only the air's taint.
        Color tint = new Color(Color.WHITE);
        if (!settings.taint().isBreathable() && scattering > 0 && taintStrength > 0) {
            // Hazard categories do not specify gas composition: these restrained hues are visual cues only.
            Color palette = new Color(switch (settings.taint()) {
                case TAINTED_CAUSTIC, TOXIC_CAUSTIC -> CAUSTIC_TAINT_COLOR;
                case TAINTED_POISON, TOXIC_POISON -> POISON_TAINT_COLOR;
                case TAINTED_FLAME, TOXIC_FLAME -> FLAMMABLE_TAINT_COLOR;
                case BREATHABLE -> throw new IllegalStateException("Breathable air has no taint palette");
            });
            // Warm horizon light owns dawn and dusk, so the palette recedes while it shines.
            float toxicityStrength = (settings.taint().isToxic() ? TOXIC_COLOR_STRENGTH : TAINTED_COLOR_STRENGTH);
            float strength = toxicityStrength
                  * MathUtils.clamp(taintStrength, 0, 10) * scattering
                  * MathUtils.lerp(0.4f, 1, daylight) * (1 - warmth * 0.75f);
            // Existing gradients/volumes provide depth. Weather alone decides whether scattering is rendered.
            tintAtmosphere(sky, palette, strength * 0.55f);
            tintAtmosphere(horizon, palette, strength);
            tintAtmosphere(fog, palette, strength);
            // The composite grades every drawn surface, so the air reaches the board and not only the sky.
            tintAtmosphere(tint, palette, strength * TAINT_GRADE_SHARE);
        }
        return new Lighting(direction, direct, ambient, fog, sky, horizon, tint,
              0.78f + 0.22f * daylight, daylight, sunlight);
    }

    /** The rendered source direction. A small north/south component avoids the shadow camera's pole, even at noon. */
    private static Vector3 direction(float hour, boolean sunlight) {
        float angle = (hour - 6) * MathUtils.PI / 12;
        float side = sunlight ? 1 : -1;
        float lowest = sunlight ? MIN_LIGHT_ALTITUDE : MIN_MOON_ALTITUDE;
        return new Vector3(-MathUtils.cos(angle) * side, -0.35f * side,
              -Math.max(lowest, Math.abs(MathUtils.sin(angle))) * 0.9f).nor();
    }

    /** The air's transmittance along the sun's path at this altitude. */
    private static Color transmittance(float altitude) {
        float airMass = (1 + AIR_MASS_OFFSET) / (Math.max(altitude, 0) + AIR_MASS_OFFSET);
        return rgb((float) Math.exp(-EXTINCTION[0] * airMass), (float) Math.exp(-EXTINCTION[1] * airMass),
              (float) Math.exp(-EXTINCTION[2] * airMass));
    }

    private static Color groundLight(Vector3 direction, Color direct, Color ambient) {
        return rgb(ambient.r - direction.z * direct.r, ambient.g - direction.z * direct.g,
              ambient.b - direction.z * direct.b);
    }

    /** A light color built by field assignment: Color's constructor, add, mul and lerp clamp to [0, 1]. */
    private static Color rgb(float r, float g, float b) {
        Color color = new Color();
        color.r = r;
        color.g = g;
        color.b = b;
        color.a = 1;
        return color;
    }

    private static void scale(Color color, float factor) {
        color.r *= factor;
        color.g *= factor;
        color.b *= factor;
    }

    private static float luminance(Color color) {
        return 0.2126f * color.r + 0.7152f * color.g + 0.0722f * color.b;
    }

    /** Blend a palette into a color at that color's own luminance: tinting adds no emission or exposure. */
    private static void tintAtmosphere(Color color, Color palette, float strength) {
        // Above 1 the lerp overshoots the luminance-matched target and channel clamping would then break it.
        float blend = MathUtils.clamp(strength, 0, 1);
        if (blend <= 0) { return; }
        float energy = luminance(color) / luminance(palette);
        // Color.mul/lerp clamp to LDR. A multiplicative display grade can legitimately exceed one; clamping
        // its luminance-matched target darkened the whole board when increasing taint (especially white daylight).
        color.r += (palette.r * energy - color.r) * blend;
        color.g += (palette.g * energy - color.g) * blend;
        color.b += (palette.b * energy - color.b) * blend;
    }

    /** 0 below zero and 1 above one; in between it rises steeply at first and levels off into 1. */
    private static float ease(float t) {
        float clamped = MathUtils.clamp(t, 0, 1);
        return clamped * (2 - clamped);
    }

    private static float smooth(float low, float high, float value) {
        float t = MathUtils.clamp((value - low) / (high - low), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
