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
    /** Acceleration in metres per second squared when scenario gravity is 1 g. */
    static final float STANDARD_GRAVITY = 9.81f;
    static final float MIN_GROUND_LAYER_HEIGHT = 1.0f;
    static final float STANDARD_GROUND_LAYER_HEIGHT = 2.0f;
    static final Settings DEFAULTS = new Settings(13, 0, 0, STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    static final float MAX_FOG_OPACITY = 0.25f;
    /** Blowing sand keeps at least this much ground fog, so its dust reads even with fog none. */
    static final float MIN_SAND_FOG = 0.05f;
    /**
     * Fraction of sky fill moved into visible moonlight. The default gives comparable standard-air noon/midnight
     * key-to-fill ratios; it is a presentation calibration, not a universal day/night contrast rule.
     */
    static final float MOONLIGHT_SHADOW_CONTRAST = 0.3f;
    /** Artistic palette blends, not gas opacity or a change to gameplay visibility. */
    static final float TAINTED_COLOR_STRENGTH = 0.45f;
    static final float TOXIC_COLOR_STRENGTH = 1.0f;
    static final float DEFAULT_TAINT_STRENGTH = 1.0f;

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
    static final float DEFAULT_AMBIENT_SHARE = 1 / (1 + KEY_FILL);
    /** A neutral day sky fill, a little cooler than the sun, that falls to SKY_HORIZON as the sun sets. */
    private static final Color SKY_TINT = rgb(0.96f, 1, 1.06f);
    private static final float SKY_HORIZON = 0.3f;
    /** Readable moonless fill, in units of the night-sky budget; presentation light, not astronomical irradiance. */
    private static final float MOONLESS_SKY_SHARE = 1.2f;
    /** Retain the dim no-moon backdrop independently of the moon's adjustable shadow contrast. */
    private static final float MOONLESS_BACKDROP_SHARE = 0.75f;
    /** Neutral gameplay fill relative to incident light, separate from visible sky and atmospheric scattering. */
    private static final float AIRLESS_READABILITY_SHARE = 0.075f;
    /** Around sunset the deep blue sky overhead fills the shade, cool against the golden sun. */
    private static final Color TWILIGHT_SKY = rgb(0.68f, 0.96f, 1.32f);
    /** Dawn and dusk warm the diffuse fill toward the glowing horizon, at constant luminance. */
    private static final Color WARM_GLOW = rgb(1, 0.78f, 0.58f);
    private static final float WARM_SHARE = 0.25f;
    /** Moonlight and the night sky are cool, but only a little blue. */
    private static final Color NIGHT_TINT = rgb(0.85f, 0.93f, 1.0f);
    /** Full-moon light on level ground against clear noon, in stops, after the view's adaptation. */
    private static final float FULL_MOON_STOPS = -1.85f;
    /** Reference night budget for readable moonless fill, independent of the full-moon brightness target. */
    private static final float MOONLESS_REFERENCE_STOPS = -1.75f;
    /** The full moon on level ground against the night sky, before MOONLIGHT_SHADOW_CONTRAST moves fill into it. */
    private static final float MOON_KEY = 2.5f;
    /** The view adapts to this share of a change in light, in stops: dusk and night stay darker, but readable. */
    private static final float ADAPTATION = 0.6f;
    /** Preserve the established exposure ceiling independently of the full-moon readability target. */
    private static final float MIN_METERED_LIGHT = 1f / 112;
    /** Fog and haze scatter this share of the light that reaches level ground. */
    private static final float FOG_ALBEDO = 0.8f;
    /** Linear albedo of blowing sand: the sand fog and the composite's sand veil (GpuAtmosphere.bindSand) share it. */
    static final Color SAND_DUST = rgb(0.57f, 0.38f, 0.17f);
    /** Derived levels: clear noon sun and sky give level ground luminance 1, in the ratio KEY_FILL to 1. */
    private static final float NOON_INCIDENCE = -direction(12, true).z;
    private static final Color STANDARD_TRANSMITTANCE = transmittance(1, 1);
    private static final float SUN_LEVEL = KEY_FILL / (1 + KEY_FILL) / NOON_INCIDENCE
          / luminance(STANDARD_TRANSMITTANCE);
    private static final float SKY_LEVEL = 1 / (1 + KEY_FILL);
    /** Full-moon light overhead, before adaptation, so that the adapted moon lands FULL_MOON_STOPS below noon. */
    private static final float NIGHT_LEVEL = (float) Math.pow(2, FULL_MOON_STOPS / (1 - ADAPTATION));
    private static final float MOONLESS_LEVEL = (float) Math.pow(2, MOONLESS_REFERENCE_STOPS / (1 - ADAPTATION));
    /**
     * The Tactical View's fixed light (b11 design item 23), so shadows read the same on every map: from the
     * north-west, 50 degrees above the horizon (board +y is north), in clear 13:00 colours and a neutral grade; the
     * light reaching level ground stays that of 13:00. Night and dust stay rules data on the conditions card. After
     * the levels above, which the 13:00 lighting reads.
     */
    static final Lighting TACTICAL = tactical(lighting(DEFAULTS));

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
            // Zero gravity shares the existing vacuum limits for both scenarios and local previews.
            if (gravity == 0) { pressure = Atmosphere.VACUUM; }
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
     * {@code ambient} and {@code fog} are linear light, before view adaptation, in the model's unit: clear noon
     * light gives white level ground luminance 1. Direct light can exceed 1, so these colors never pass through
     * libGDX's clamping Color operations. {@code sky} and {@code horizon} are linear radiance of the air itself;
     * {@code tint} and {@code saturation} grade every drawn surface in the composite, which is the only
     * stage that sees the board and the sky together.
     */
    record Lighting(Vector3 direction, Color direct, Color ambient, Color fog, Color sky, Color horizon, Color tint,
          float saturation, float daylight, boolean sunlight, float shadowStrength,
          Color environmentSky, Color environmentHorizon, GpuLightingEnvironment environmentImage, float environmentGain,
          BoardLightingSky.Source skySource) {
        Lighting(Vector3 direction, Color direct, Color ambient, Color fog, Color sky, Color horizon, Color tint,
              float saturation, float daylight, boolean sunlight, float shadowStrength,
              Color environmentSky, Color environmentHorizon, GpuLightingEnvironment environmentImage, float environmentGain) {
            this(direction, direct, ambient, fog, sky, horizon, tint, saturation, daylight, sunlight, shadowStrength,
                  environmentSky, environmentHorizon, environmentImage, environmentGain, null);
        }
        Lighting(Vector3 direction, Color direct, Color ambient, Color fog, Color sky, Color horizon, Color tint,
              float saturation, float daylight, boolean sunlight) {
            this(direction, direct, ambient, fog, sky, horizon, tint, saturation, daylight, sunlight,
                  1, sky, horizon, null, 1);
        }

        /** Local preview controls leave the visible sky and the atmospheric palette alone. */
        Lighting tuned(float shadows, float ambientShare, float skyContribution, GpuLightingEnvironment image) {
            if (shadows == 1 && ambientShare == DEFAULT_AMBIENT_SHARE && skyContribution == 1 && image == null) {
                return this;
            }
            float directGain = (1 - ambientShare) / (1 - DEFAULT_AMBIENT_SHARE);
            float skyGain = ambientShare / DEFAULT_AMBIENT_SHARE * skyContribution;
            Color diffuse = image == null ? ambient : image.upwardIrradiance();
            return new Lighting(direction, scaled(direct, directGain), scaled(diffuse, skyGain), fog, sky, horizon,
                  tint, saturation, daylight, sunlight, shadows, scaled(environmentSky, skyGain),
                  scaled(environmentHorizon, skyGain), image == null ? environmentImage : image, skyGain, skySource);
        }

        /** Install one complete CPU-filtered environment before publishing its matching diffuse light. */
        Lighting withEnvironment(GpuLightingEnvironment image) {
            return new Lighting(direction, direct, image.upwardIrradiance(), fog, sky, horizon, tint,
                  saturation, daylight, sunlight, shadowStrength, environmentSky, environmentHorizon,
                  image, environmentGain, skySource);
        }

        private static Color scaled(Color color, float gain) {
            return rgb(color.r * gain, color.g * gain, color.b * gain);
        }
        boolean hasDirectLight() {
            return direct.r > 0 || direct.g > 0 || direct.b > 0;
        }

        /** Linear light on lit level ground, ambient plus direct at its incidence; the camera cannot change it. */
        Color groundLight() {
            return BoardAtmosphere.groundLight(direction, direct, ambient);
        }

        /** Meter the source before camera-relative presentation; grazing sunlight also illuminates slopes. */
        float adaptation() {
            float incidence = meteringIncidence(direction);
            return BoardAtmosphere.adaptation(rgb(ambient.r + incidence * direct.r,
                  ambient.g + incidence * direct.g, ambient.b + incidence * direct.b));
        }

        /** Keep the light at the same screen bearing through the board camera's yaw, tilt, pan and zoom. */
        Lighting relativeTo(Camera camera) {
            // The source stays above the screen and board, including the overhead camera pole.
            Vector3 relative = new Vector3(camera.direction).crs(camera.up).nor().scl(direction.x)
                  .mulAdd(camera.up, -Math.abs(direction.y)).mulAdd(camera.direction, -direction.z).nor();
            // Free flight can look above the horizon. Keep the source above the board there too: crossing zero
            // incidence would otherwise create infinite, then negative light. Ordinary board poses stay unchanged.
            if (relative.z > -.05f) {
                if (relative.x * relative.x + relative.y * relative.y < 1e-12f) { relative.set(1, 0, 0); }
                float horizontal = (float) Math.sqrt((1 - .05f * .05f)
                      / (relative.x * relative.x + relative.y * relative.y));
                relative.set(relative.x * horizontal, relative.y * horizontal, -.05f);
            }
            // Tilting changes incidence on level ground; retain its established direct illumination.
            float compensation = direction.z / relative.z;
            Color compensated = rgb(direct.r * compensation, direct.g * compensation, direct.b * compensation);
            return new Lighting(relative, compensated, ambient, fog, sky, horizon, tint, saturation, daylight, sunlight,
                  shadowStrength, environmentSky, environmentHorizon, environmentImage, environmentGain, skySource);
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
        return lighting(settings, moonShadowContrast, taintStrength, true);
    }

    private static Lighting lighting(Settings settings, float moonShadowContrast, float taintStrength,
          boolean calibrateDaylight) {
        // Spatial transmission alone attenuates surface sunlight.
        float angle = (settings.hour() - 6) * MathUtils.PI / 12;
        // The sine table introduces discrete steps at sub-minute source handovers.
        float altitude = (float) Math.sin(angle);
        float daylight = smooth(-0.30f, 0.25f, altitude);
        float density = airDensity(settings.pressure());
        float scattering = Math.min(1, density);
        // Warm light peaks at the horizon, independently of how much daylight remains.
        float warmth = smooth(-0.20f, 0.015f, altitude) * (1 - smooth(0.16f, 0.50f, altitude)) * scattering;
        float twilight = (1 - smooth(0.05f, 0.45f, Math.abs(altitude))) * scattering;
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
        // Both sources use the same category optical depth. Lost beam energy drives a relative sky-fill
        // response, normalized to the established standard-air calibration at the same source altitude.
        Color transmittance = transmittance(altitude, density);
        Color moonTransmission = transmittance(-altitude, density);
        float sunScatter = scatteredShare(transmittance, altitude);
        float moonScatter = scatteredShare(moonTransmission, -altitude);
        // The moon's cool spectrum remains a presentation choice; extinction changes its luminance only.
        float moonTransmissionGain = luminance(moonTransmission) / luminance(STANDARD_TRANSMITTANCE);
        float sun = SUN_LEVEL * sunShadows;
        float fill = SKY_LEVEL * (SKY_HORIZON + (1 - SKY_HORIZON) * Math.max(altitude, 0)) * daylight
              * sunScatter / luminance(SKY_TINT);
        // Brighten the visible moon without raising dawn's sky fill. Squaring the existing visibility gives
        // the extra source a flat onset at handover; a missing moon retains its independent night budget.
        float nightLevel = MathUtils.lerp(MOONLESS_LEVEL, NIGHT_LEVEL, moonShadows * moonShadows);
        float night = nightLevel / (1 + MOON_KEY) * (1 - daylight) / luminance(NIGHT_TINT);
        float moon = settings.moonlight() ? night * MOON_KEY / NOON_INCIDENCE : 0;
        // Moonlight whose shadows the twilight sky washes out still lights the ground, as fill. The moon contrast
        // moves night-sky fill into the shadow-casting moonlight; lit level ground keeps its luminance.
        float transfer = moonShadowContrast * moonShadows;
        float moonlight = moon * moonShadows * moonTransmissionGain + night * moonScatter * transfer / incidence;
        // The no-moon source and backdrop keep their own readability budget; adjusting full-moon brightness
        // or contrast must not change them. The live moon-contrast control still affects only moonlight.
        float skyShare = settings.moonlight() ? 1 - transfer : MOONLESS_BACKDROP_SHARE;
        float nightFill = (night * skyShare + moon * (1 - moonShadows) * moonIncidence) * moonScatter;
        Color direct = rgb(sun * transmittance.r + moonlight * NIGHT_TINT.r,
              sun * transmittance.g + moonlight * NIGHT_TINT.g, sun * transmittance.b + moonlight * NIGHT_TINT.b);
        Color ambient = rgb(fill * SKY_TINT.r, fill * SKY_TINT.g, fill * SKY_TINT.b);
        tintAtmosphere(ambient, TWILIGHT_SKY, twilight);
        tintAtmosphere(ambient, WARM_GLOW, warmth * WARM_SHARE);
        ambient.r += nightFill * NIGHT_TINT.r;
        ambient.g += nightFill * NIGHT_TINT.g;
        ambient.b += nightFill * NIGHT_TINT.b;
        // The physical lighting budget is owned here, before the illustrative background palette is evaluated.
        Color diffuseSky = new Color(ambient), gameplayFill = rgb(0, 0, 0);
        if (!settings.moonlight()) {
            // An absent moon contributes no scattering source. The calibrated night allowance is explicit fill.
            float readableFill = night * MOONLESS_SKY_SHARE * scattering;
            diffuseSky.r -= nightFill * NIGHT_TINT.r;
            diffuseSky.g -= nightFill * NIGHT_TINT.g;
            diffuseSky.b -= nightFill * NIGHT_TINT.b;
            gameplayFill.set(rgb(readableFill * NIGHT_TINT.r, readableFill * NIGHT_TINT.g, readableFill * NIGHT_TINT.b));
        }
        SkyPalette palette = skyPalette(settings, taintStrength, true);
        Color sky = palette.sky(), horizon = palette.horizon();
        Color tint = new Color(Color.WHITE);
        Color taintPalette = palette.taint();
        float fogTaint = palette.taintStrength();
        // Optical taint belongs to the incident environment. Do not tint the complete scene a second time.
        // Retain the legacy display endpoints for existing backdrop consumers. They never feed incident lighting.
        Color integratedSky = rgb(.2f * horizon.r + .8f * sky.r, .2f * horizon.g + .8f * sky.g,
              .2f * horizon.b + .8f * sky.b);
        float skyScale = settings.pressure().isVacuum() ? 1
              : luminance(ambient) / Math.max(1e-8f, luminance(integratedSky));
        scale(sky, skyScale);
        scale(horizon, skyScale);
        scale(integratedSky, skyScale);
        // Neutral gameplay fill keeps airless shade readable. Once the moon is visible, its beam sets
        // the allowance; a fixed daytime floor would wash out shadows as moon brightness is reduced.
        // Without a source, use the same night budget, fading into the daytime allowance through dawn.
        float readability = AIRLESS_READABILITY_SHARE
              * (MathUtils.lerp(MOONLESS_LEVEL, SKY_LEVEL, daylight) * (1 - moonShadows * moonShadows)
                    + luminance(direct) * incidence) * (1 - scattering);
        gameplayFill.r += readability;
        gameplayFill.g += readability;
        gameplayFill.b += readability;
        if (!settings.moonlight()) {
            // The readability fill is not a visible moon. Keep even the faint airless backdrop dark under adaptation.
            float backdrop = MathUtils.lerp(.35f, 1, daylight);
            scale(sky, backdrop);
            scale(horizon, backdrop);
        }
        ambient.set(rgb(diffuseSky.r + gameplayFill.r, diffuseSky.g + gameplayFill.g, diffuseSky.b + gameplayFill.b));
        Color environmentSky = new Color(ambient), environmentHorizon = new Color(ambient);
        if (calibrateDaylight) {
            // One mildly warm daylight white point for direct, diffuse and reflected light. No material tint.
            // The visible sky retains its established presentation; it is not a calibrated HDR light probe.
            calibrateDaylight(direct, daylight, DaylightWhitePoint.GAIN);
            calibrateDaylight(ambient, daylight, DaylightWhitePoint.GAIN);
            calibrateDaylight(environmentSky, daylight, DaylightWhitePoint.GAIN);
            calibrateDaylight(environmentHorizon, daylight, DaylightWhitePoint.GAIN);
            calibrateDaylight(diffuseSky, daylight, DaylightWhitePoint.GAIN);
            calibrateDaylight(gameplayFill, daylight, DaylightWhitePoint.GAIN);
            calibrateDaylight(sky, daylight, DaylightWhitePoint.VISIBLE_GAIN);
            calibrateDaylight(horizon, daylight, DaylightWhitePoint.VISIBLE_GAIN);
        }
        // Fog and dust scatter this same environment and sun, after sky normalization and before display exposure.
        Color ground = groundLight(direction, direct, ambient);
        float sand = settings.effects().sand();
        Color fog = rgb(ground.r * MathUtils.lerp(FOG_ALBEDO, SAND_DUST.r, sand),
              ground.g * MathUtils.lerp(FOG_ALBEDO, SAND_DUST.g, sand),
              ground.b * MathUtils.lerp(FOG_ALBEDO, SAND_DUST.b, sand));
        if (taintPalette != null) { tintAtmosphere(fog, taintPalette, fogTaint); }
        var skySource = new BoardLightingSky.Source(new Vector3(direction),
              new Vector3(-(float) Math.cos(angle), -.35f, -.9f * altitude).nor(), altitude, daylight,
              sunlight ? sunShadows : moonShadows, density, settings.haze(), settings.clouds(), settings.taint(),
              MathUtils.clamp(taintStrength, 0, 10), diffuseSky, gameplayFill);
        return new Lighting(direction, direct, ambient, fog, sky, horizon, tint,
              1, daylight, sunlight, 1,
              environmentSky, environmentHorizon, null, 1, skySource);
    }

    /** Lazy initialization avoids referring to partially initialized defaults while TACTICAL is being built. */
    private static final class DaylightWhitePoint {
        private static final Color GAIN = gains(false);
        private static final Color VISIBLE_GAIN = gains(true);

        private static Color gains(boolean visible) {
            Lighting reference = lighting(new Settings(12, 0, 0, STANDARD_GROUND_LAYER_HEIGHT, 0, 0),
                  MOONLIGHT_SHADOW_CONTRAST, 0, false);
            Color white = reference.groundLight();
            Color target = rgb(1.02f, 1, .96f);
            scale(target, 1 / luminance(target));
            if (visible) {
                Color sky = reference.sky(), horizon = reference.horizon();
                white = groundLight(reference.direction(), reference.direct(), rgb(
                      .2f * horizon.r + .8f * sky.r, .2f * horizon.g + .8f * sky.g,
                      .2f * horizon.b + .8f * sky.b));
                target.set(Color.WHITE);
            }
            return rgb(target.r / white.r, target.g / white.g, target.b / white.b);
        }
    }

    private static void calibrateDaylight(Color color, float daylight, Color gain) {
        color.r *= MathUtils.lerp(1, gain.r, daylight);
        color.g *= MathUtils.lerp(1, gain.g, daylight);
        color.b *= MathUtils.lerp(1, gain.b, daylight);
    }

    /** The rendered source direction. A small north/south component avoids the shadow camera's pole, even at noon. */
    private static Vector3 direction(float hour, boolean sunlight) {
        float angle = (hour - 6) * MathUtils.PI / 12;
        float side = sunlight ? 1 : -1;
        float lowest = sunlight ? MIN_LIGHT_ALTITUDE : MIN_MOON_ALTITUDE;
        return new Vector3(-MathUtils.cos(angle) * side, -0.35f * side,
              -Math.max(lowest, Math.abs(MathUtils.sin(angle))) * 0.9f).nor();
    }

    /** One authored palette: native UI interpolation for the backdrop, linear interpolation for incident light. */
    record SkyPalette(Color sky, Color horizon, Color taint, float taintStrength) { }

    static SkyPalette skyPalette(Settings settings, float taintStrength, boolean linear) {
        float angle = (settings.hour() - 6) * MathUtils.PI / 12;
        // The illustration retains the UI's sampling; source illumination retains continuous source handovers.
        float altitude = linear ? (float) Math.sin(angle) : MathUtils.sin(angle);
        float daylight = smooth(-.30f, .25f, altitude);
        float scattering = Math.min(1, airDensity(settings.pressure()));
        float warmth = smooth(-.20f, .015f, altitude) * (1 - smooth(.16f, .50f, altitude)) * scattering;
        float twilight = (1 - smooth(.05f, .45f, Math.abs(altitude))) * scattering;
        float clouds = cloudiness(settings);
        Color warm = settings.hour() < 12 ? paletteColor(linear, 1, .53f, .33f) : paletteColor(linear, 1, .43f, .20f);
        Color sky = paletteColor(linear, .031f, .049f, .106f)
              .lerp(paletteColor(linear, .154f, .335f, .579f), daylight)
              .lerp(paletteColor(linear, .14f, .20f, .42f), twilight * .65f);
        Color horizon = paletteColor(linear, .075f, .088f, .15f)
              .lerp(paletteColor(linear, .54f, .72f, .888f), daylight)
              .lerp(paletteColor(linear, .44f, .30f, .36f), twilight)
              .lerp(new Color(warm).mul(0.75f, 0.75f, 0.75f, 1), warmth * 0.9f);
        // A smooth cloud-colored veil is strongest overhead and fades toward the horizon. No sky texture/pass.
        Color overcast = paletteColor(linear, .057f, .066f, .097f)
              .lerp(paletteColor(linear, .618f, .669f, .734f), daylight)
              .lerp(paletteColor(linear, .48f, .32f, .34f), twilight * .55f);
        sky.lerp(overcast, clouds * 0.92f);
        horizon.lerp(overcast, clouds * 0.10f);
        if (settings.pressure().isVacuum()) {
            sky.set(paletteColor(linear, .002f, .003f, .006f));
            horizon.set(sky);
        }
        Color taintPalette = null;
        float strengthForFog = 0;
        if (!settings.taint().isBreathable() && scattering > 0 && taintStrength > 0) {
            // Hazard categories do not specify gas composition: these restrained hues are visual cues only.
            Color palette = paletteColor(linear, new Color(switch (settings.taint()) {
                case TAINTED_CAUSTIC, TOXIC_CAUSTIC -> CAUSTIC_TAINT_COLOR;
                case TAINTED_POISON, TOXIC_POISON -> POISON_TAINT_COLOR;
                case TAINTED_FLAME, TOXIC_FLAME -> FLAMMABLE_TAINT_COLOR;
                case BREATHABLE -> throw new IllegalStateException("Breathable air has no taint palette");
            }));
            float toxicityStrength = (settings.taint().isToxic() ? TOXIC_COLOR_STRENGTH : TAINTED_COLOR_STRENGTH);
            float horizonStrength = toxicityStrength * MathUtils.clamp(taintStrength, 0, 10) * scattering;
            float strength = horizonStrength * MathUtils.lerp(0.4f, 1, daylight) * (1 - warmth * 0.75f);
            // Existing gradients/volumes provide depth. Weather alone decides whether scattering is rendered.
            tintAtmosphere(sky, palette, strength * 0.55f);
            // Keep the visible low-altitude hazard band through night and twilight. Its own luminance still
            // follows the time of day; the upper sky and physical scattering have independent color responses.
            tintAtmosphere(horizon, palette, linear ? strength : horizonStrength);
            taintPalette = palette;
            strengthForFog = strength;
        }
        return new SkyPalette(sky, horizon, taintPalette, strengthForFog);
    }

    private static Color paletteColor(boolean linear, float red, float green, float blue) {
        return linear ? GpuColor.linear(red, green, blue) : new Color(red, green, blue, 1);
    }

    private static Color paletteColor(boolean linear, Color color) {
        return linear ? GpuColor.linear(color) : color;
    }

    /** Representative category optical densities, not measured pressures or gas compositions. */
    private static float airDensity(Atmosphere pressure) {
        return switch (pressure) {
            case VACUUM -> 0;
            case TRACE -> 0.15f;
            case THIN -> 0.45f;
            case STANDARD -> 1;
            case HIGH -> 1.2f;
            case VERY_HIGH -> 1.4f;
        };
    }

    /** The air's transmittance along either source's path at this altitude. */
    private static Color transmittance(float altitude, float density) {
        float airMass = (1 + AIR_MASS_OFFSET) / (Math.max(altitude, 0) + AIR_MASS_OFFSET);
        return rgb((float) Math.exp(-EXTINCTION[0] * airMass * density),
              (float) Math.exp(-EXTINCTION[1] * airMass * density),
              (float) Math.exp(-EXTINCTION[2] * airMass * density));
    }

    /** Relative lost-light response; preserves the standard-air sky calibration, not a scattering simulation. */
    private static float scatteredShare(Color transmission, float altitude) {
        return (1 - luminance(transmission)) / (1 - luminance(transmittance(altitude, 1)));
    }

    /** {@code noon}'s colours from the fixed north-west direction, with its direct light on level ground kept. */
    private static Lighting tactical(Lighting noon) {
        Vector3 direction = new Vector3(.5f, -.5f, -.84f).nor();
        float compensation = noon.direction().z / direction.z;
        Color direct = rgb(noon.direct().r * compensation, noon.direct().g * compensation,
              noon.direct().b * compensation);
        return new Lighting(direction, direct, noon.ambient(), noon.fog(), noon.sky(), noon.horizon(),
              new Color(Color.WHITE), 1, noon.daylight(), true, noon.shadowStrength(),
              noon.environmentSky(), noon.environmentHorizon(), noon.environmentImage(), noon.environmentGain(),
              noon.skySource().oriented(direction));
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

    /** Include sloped receivers in the exposure meter without changing physical ground light or fog. */
    static float meteringIncidence(Vector3 direction) {
        return Math.max(.5f, -direction.z);
    }

    /**
     * View adaptation from the metered source. The established night metering range bounds gain in vacuum
     * or a black environment; this readability floor changes exposure, never the source light itself.
     */
    static float adaptation(Color metered) {
        return (float) Math.pow(Math.max(MIN_METERED_LIGHT, luminance(metered)), -ADAPTATION);
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
        // Color.mul/lerp clamp to LDR. A multiplicative linear grade can legitimately exceed one; clamping
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
