/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import megamek.client.ui.Messages;
import megamek.codeUtilities.MathUtility;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import megamek.common.planetaryConditions.BlowingSand;
import megamek.common.planetaryConditions.EMI;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import megamek.common.planetaryConditions.Wind;
import megamek.common.planetaryConditions.WindDirection;
import megamek.common.util.StringUtil;

/**
 * The planetary conditions without a toolkit, as the Swing {@code PlanetaryConditionsDialog} edits them: light,
 * weather, wind with its direction and the shifting wind range, atmosphere and its taint, fog, blowing sand, EMI,
 * temperature, gravity and whether the terrain is affected. The setters apply the dialog's adaptations (a thin
 * atmosphere clears the weather, vacuum calms everything, some weathers force the wind and cap the temperature, blowing
 * sand raises the wind, the shifting range clamps it); {@link #errors(Field)} gives the dialog's validation messages
 * per field and {@link #result()} the conditions to send.
 */
public final class PlanetaryConditionsForm {

    /** The fields of the form, also the places validation messages are shown. */
    public enum Field {
        TEMPERATURE, GRAVITY, LIGHT, ATMOSPHERE, TAINT, EMI, TERRAIN_AFFECTED, WEATHER, WIND, WIND_DIRECTION, FOG,
        BLOWING_SAND, SHIFT_DIRECTION, SHIFT_STRENGTH, WIND_FROM, WIND_TO
    }

    private static final String PCD = "PlanetaryConditionsDialog.";
    private static final String INVALID = PCD + "invalid.";

    private final PlanetaryConditions conditions;
    private Light light;
    private Weather weather;
    private Wind wind;
    private Wind windFrom;
    private Wind windTo;
    private WindDirection windDirection;
    private Atmosphere atmosphere;
    private AtmosphericTaint taint;
    private Fog fog;
    private boolean blowingSand;
    private boolean shiftDirection;
    private boolean shiftStrength;
    private String temperature;
    private String gravity;
    private boolean emi;
    private boolean terrainAffected;

    /** @param original the conditions to edit; they are not changed, {@link #result()} returns a copy */
    public PlanetaryConditionsForm(PlanetaryConditions original) {
        conditions = (PlanetaryConditions) original.clone();
        light = conditions.getLight();
        weather = conditions.getWeather();
        wind = conditions.getWind();
        windFrom = conditions.getWindMin();
        windTo = conditions.getWindMax();
        windDirection = conditions.getWindDirection();
        atmosphere = conditions.getAtmosphere();
        taint = conditions.getAtmosphericTaint();
        fog = conditions.getFog();
        blowingSand = conditions.isBlowingSand();
        shiftDirection = conditions.shiftingWindDirection();
        shiftStrength = conditions.shiftingWindStrength();
        temperature = Integer.toString(conditions.getTemperature());
        gravity = Float.toString(conditions.getGravity());
        emi = conditions.getEMI().isEMI();
        terrainAffected = conditions.isTerrainAffected();
        adaptToWeatherAtmosphere();
    }

    public Light light() {
        return light;
    }

    public Weather weather() {
        return weather;
    }

    public Wind wind() {
        return wind;
    }

    public Wind windFrom() {
        return windFrom;
    }

    public Wind windTo() {
        return windTo;
    }

    public WindDirection windDirection() {
        return windDirection;
    }

    public Atmosphere atmosphere() {
        return atmosphere;
    }

    public AtmosphericTaint taint() {
        return taint;
    }

    public Fog fog() {
        return fog;
    }

    public boolean blowingSand() {
        return blowingSand;
    }

    public boolean shiftDirection() {
        return shiftDirection;
    }

    public boolean shiftStrength() {
        return shiftStrength;
    }

    public String temperature() {
        return temperature;
    }

    public String gravity() {
        return gravity;
    }

    public boolean emi() {
        return emi;
    }

    public boolean terrainAffected() {
        return terrainAffected;
    }

    public void light(Light value) {
        light = value;
    }

    /** Changing the weather may clear or force fields and caps the temperature for snow, sleet, hail and ice. */
    public void weather(Weather value) {
        weather = value;
        adaptToWeatherAtmosphere();
        int maxTemp = switch (weather) {
            case LIGHT_SNOW, SLEET, LIGHT_HAIL, HEAVY_HAIL -> -40;
            case HEAVY_SNOW, MOD_SNOW, SNOW_FLURRIES -> -50;
            case ICE_STORM -> -60;
            default -> 200;
        };
        if (MathUtility.parseInt(temperature, 200) > maxTemp) {
            temperature = Integer.toString(maxTemp);
        }
    }

    public void wind(Wind value) {
        wind = value;
    }

    /** Sets the lower end of the shifting wind range and clamps the wind into the range. */
    public void windFrom(Wind value) {
        windFrom = value;
        clampWindToRange();
    }

    /** Sets the upper end of the shifting wind range and clamps the wind into the range. */
    public void windTo(Wind value) {
        windTo = value;
        clampWindToRange();
    }

    public void windDirection(WindDirection value) {
        windDirection = value;
    }

    /** A thin or trace atmosphere clears weather and fog; vacuum also calms the wind and removes taint and sand. */
    public void atmosphere(Atmosphere value) {
        atmosphere = value;
        adaptToWeatherAtmosphere();
    }

    public void taint(AtmosphericTaint value) {
        taint = value;
    }

    public void fog(Fog value) {
        fog = value;
    }

    /** Blowing sand raises the wind to at least a moderate gale. */
    public void blowingSand(boolean value) {
        blowingSand = value;
        if (blowingSand && wind.isWeakerThan(Wind.MOD_GALE)) {
            wind = Wind.MOD_GALE;
        }
    }

    public void shiftDirection(boolean value) {
        shiftDirection = value;
    }

    public void shiftStrength(boolean value) {
        shiftStrength = value;
    }

    public void temperature(String value) {
        temperature = value;
    }

    public void gravity(String value) {
        gravity = value;
    }

    public void emi(boolean value) {
        emi = value;
    }

    public void terrainAffected(boolean value) {
        terrainAffected = value;
    }

    /** @return the lower end of the shifting wind range, whichever of from and to is weaker */
    public Wind windMin() {
        return Wind.getWind(Math.min(windFrom.ordinal(), windTo.ordinal()));
    }

    /** @return the upper end of the shifting wind range, whichever of from and to is stronger */
    public Wind windMax() {
        return Wind.getWind(Math.max(windFrom.ordinal(), windTo.ordinal()));
    }

    /** @return whether the field can be edited now; disabled fields keep their (adapted) values */
    public boolean enabled(Field field) {
        boolean vacuum = atmosphere.isVacuum();
        boolean dense = !vacuum && !atmosphere.isTraceOrThin();
        boolean forcedWind = weather.isGustingRainOrSnowFlurriesOrIceStormOrLightningStorm();
        return switch (field) {
            case WEATHER, FOG -> dense;
            case TAINT, BLOWING_SAND, SHIFT_DIRECTION, WIND_DIRECTION -> !vacuum;
            case WIND, SHIFT_STRENGTH -> !vacuum && !forcedWind;
            case WIND_FROM, WIND_TO -> shiftStrength;
            default -> true;
        };
    }

    /** @return the message keys of the problems shown on the field, in the dialog's order; empty when it is fine */
    public List<String> errors(Field field) {
        return validation().getOrDefault(field, List.of());
    }

    /** @return the problems shown on the field as text, one per line; empty when it is fine */
    public String errorText(Field field) {
        List<String> lines = new ArrayList<>();
        for (String key : errors(field)) {
            lines.add(Messages.getString(key).replace("<BR>", "").replace("<br>", "").strip());
        }
        return String.join("\n", lines);
    }

    /** @return whether nothing blocks OK */
    public boolean valid() {
        return validation().isEmpty();
    }

    /** @return a copy of the original conditions with every field of the form applied */
    public PlanetaryConditions result() {
        PlanetaryConditions result = (PlanetaryConditions) conditions.clone();
        clampWindToRange();
        result.setLight(light);
        result.setWeather(weather);
        result.setWind(wind);
        result.setWindDirection(windDirection);
        result.setWindMin(windMin());
        result.setWindMax(windMax());
        result.setAtmosphere(atmosphere);
        result.setAtmosphericTaint(taint);
        result.setFog(fog);
        result.setBlowingSand(blowingSand ? BlowingSand.BLOWING_SAND : BlowingSand.BLOWING_SAND_NONE);
        result.setShiftingWindDirection(shiftDirection);
        result.setShiftingWindStrength(shiftStrength);
        result.setTemperature(StringUtil.toInt(temperature, 25));
        result.setGravity(StringUtil.toFloat(gravity, 1.0f));
        result.setEMI(emi ? EMI.EMI : EMI.EMI_NONE);
        result.setTerrainAffected(terrainAffected);
        return result;
    }

    private Map<Field, List<String>> validation() {
        Map<Field, List<String>> errors = new EnumMap<>(Field.class);
        int temp = MathUtility.parseInt(temperature, 0);
        if ((temp > 200) || (temp < -200)) {
            add(errors, "tempRange", Field.TEMPERATURE);
        }
        float grav = MathUtility.parseFloat(gravity, 1.0f);
        if ((grav < 0.1) || (grav > 10.0)) {
            add(errors, "gravRange", Field.GRAVITY);
        }
        if ((blowingSand && wind.isWeakerThan(Wind.MOD_GALE))
              || (shiftStrength && windMax().isWeakerThan(Wind.MOD_GALE))) {
            add(errors, "sandsLost", Field.WIND, Field.BLOWING_SAND);
        }
        if (atmosphere.isTrace() && wind.isLightGale()) {
            add(errors, "traceLightGale", Field.ATMOSPHERE, Field.WIND);
        }
        if (weather.isLightSnowOrSleetOrLightHailOrHeavyHail() && (temp > -40)) {
            add(errors, "lightSnowTemp", Field.TEMPERATURE, Field.WEATHER);
        }
        if (weather.isModerateSnowOrHeavySnowOrSnowFlurries() && (temp > -50)) {
            add(errors, "modSnowTemp", Field.TEMPERATURE, Field.WEATHER);
        }
        if (weather.isIceStorm() && (temp > -60)) {
            add(errors, "iceStormTemp", Field.TEMPERATURE, Field.WEATHER);
        }
        if (shiftStrength && (wind.isWeakerThan(windMin()) || wind.isStrongerThan(windMax()))) {
            add(errors, "windRange", Field.WIND);
        }
        return errors;
    }

    private static void add(Map<Field, List<String>> errors, String key, Field... fields) {
        for (Field field : fields) {
            errors.computeIfAbsent(field, f -> new ArrayList<>()).add(INVALID + key);
        }
    }

    private void adaptToWeatherAtmosphere() {
        Weather before = weather;
        if (atmosphere.isTraceOrThin()) {
            weather = Weather.CLEAR;
            fog = Fog.FOG_NONE;
        }
        if (atmosphere.isVacuum()) {
            wind = Wind.CALM;
            blowingSand = false;
            shiftDirection = false;
            shiftStrength = false;
            weather = Weather.CLEAR;
            fog = Fog.FOG_NONE;
            taint = AtmosphericTaint.BREATHABLE;
        }
        // as in the Swing dialog, the forced wind follows the weather chosen before a thin atmosphere cleared it
        if (before.isGustingRainOrSnowFlurriesOrIceStormOrLightningStorm()) {
            shiftStrength = false;
            switch (before) {
                case LIGHTNING_STORM, SNOW_FLURRIES, ICE_STORM -> wind = Wind.MOD_GALE;
                case GUSTING_RAIN -> wind = Wind.STRONG_GALE;
                default -> {
                }
            }
        }
    }

    private void clampWindToRange() {
        Wind min = windMin();
        Wind max = windMax();
        if (wind.isWeakerThan(min)) {
            wind = min;
        }
        if (wind.isStrongerThan(max)) {
            wind = max;
        }
    }

    /** @return the label of the field, as the Swing dialog shows it */
    public static String label(Field field) {
        String key = switch (field) {
            case TEMPERATURE -> "labTemp";
            case GRAVITY -> "labGrav";
            case LIGHT -> "labLight";
            case ATMOSPHERE -> "labAtmosphere";
            case TAINT -> "labAtmosphericTaint";
            case EMI -> "EMI";
            case TERRAIN_AFFECTED -> "TerrainAffected";
            case WEATHER -> "labWeather";
            case WIND -> "labWind";
            case WIND_DIRECTION -> "labWindDirection";
            case FOG -> "labFog";
            case BLOWING_SAND -> "BlowingSands";
            case SHIFT_DIRECTION -> "shiftWindDir";
            case SHIFT_STRENGTH -> "shiftWindStr";
            case WIND_FROM -> "labMinWind";
            case WIND_TO -> "labMaxWind";
        };
        return Messages.getString(PCD + key);
    }
}
