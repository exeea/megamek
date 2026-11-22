/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * The board view's quality switches: every feature whose cost or look is a choice, defined once with its label, help,
 * range, its value in each preset ({@link BoardSwitches.Preset}) and how a change reaches the GPU. A set of values is a
 * {@link BoardSwitches}; Low, Medium and High are named sets, any other is Custom. Code reads a switch from the set it was
 * handed, never a quality level. The constants' order is the Display page's order and the order of the defines in a
 * program's prefix (GpuCloudShadow.prefix).
 */
enum BoardSwitch {
    // label, min, max, step, its value in Low, Medium and High, how a change applies, its define, help.
    HIGH_SHADOWS("High shadows", 0, 1, 1, 0, 0, 0, Applies.LIVE, null,
          "The sun's shadow map follows the screen: about one shadow texel to a rendered pixel. High doubles that for "
                + "sharper shadow edges under rims, rocks and plants; it uses more video memory and takes longer to redraw "
                + "the shadows when the view zooms."),
    SPECULAR_ANTI_ALIASING("Specular anti-aliasing", 0, 1, 1, 0, 0, 1, Applies.DEFINE, "specularAntialiasingFlag",
          "Widens highlights where a surface's normals turn within a pixel, so fine relief does not sparkle. Switching "
                + "it rebuilds the board's shaders."),
    TERRAIN_PARALLAX("Terrain parallax", 0, 1, 1, 0, 0, 1, Applies.LIVE, null,
          "Moves the sculpted terrain's materials with the view by their heightmaps, so stones, cracks and joints stand "
                + "out of the ground as the camera turns. Independent of Normal maps; off is a little faster on rocky "
                + "ground and cliffs. With Cheaper shading on, natural ground takes none; pavement, rubble and the rock "
                + "kit still do."),
    TESSELLATION("Tessellation", 0, 1, 1, 0, 0, 0, Applies.BUILD, null,
          "Rounds the sculpted banks and cliffs between their mesh points, so their outlines and the shadows they cast "
                + "curve as the ground does. Up close it costs an integrated graphics chip a few milliseconds a frame. "
                + "Needs OpenGL 4.0. Switching it rebuilds the board."),
    AMBIENT_OCCLUSION("Ambient occlusion", 0, 1, 1, 0, 0, 0, Applies.PROGRAMS, null,
          "Darkens the sky light where the ground and the trees hide the sky: cliff feet, hollows, the ground around "
                + "trunks. Sunlight is unchanged; buildings and units take no part. Switching it rebuilds the board's "
                + "shaders."),
    CHEAPER_SHADING("Cheaper shading", 0, 1, 1, 1, 1, 0, Applies.DEFINE, "mediumQualityFlag",
          "Medium's cheaper light and ground materials: one shadow comparison, an analytic sky and highlight, the "
                + "ground's first repeat and the mean finish of its maps. Switching it rebuilds the board's shaders."),
    RELIEF_PREPASS("Relief depth pre-pass", 0, 1, 1, 1, 1, 0, Applies.PROGRAMS, null,
          "Draws the relief's depth before its colour where hexes are small on screen and in perspective views, so the "
                + "ground nearer hides is not shaded. Switching it rebuilds the board's shaders."),
    DETAIL_SCALE("Terrain detail scale", 1, 4, .5f, 3, 3, 1, Applies.LIVE, null,
          "Terrain takes each detail level at this many times a hex's size on screen: higher draws coarser terrain "
                + "sooner, which is faster. Chunks take a new level as they come back into view."),
    TERRAIN_ANISOTROPY("Terrain filtering", 1, 16, 1, 2, 2, GpuAssets.MATERIAL_ANISOTROPY, Applies.LIVE, null,
          "Anisotropic filtering of the sculpted ground's materials: sharper at grazing angles, slower when higher."),
    MEADOW_SHARE("Meadow grass", 0, 1, .05f, 0, .6f, 1, Applies.LIVE, null,
          "The share of a meadow's grass blades drawn, each one wider by the root of the rest. At 0 no grass blade or "
                + "bank rim draws at all."),
    CROP_SHARE("Field plants", 0, 1, .05f, .75f, 1, 1, Applies.LIVE, null,
          "The share of a field's plants drawn, each one wider by as much."),
    HALF_RATE_WAVES("Waves at half rate", 0, 1, 1, 1, 1, 0, Applies.LIVE, null,
          "Waves advance every other frame, and not at all while no water shows; they follow the clock."),
    // The compact ground's rule kinds with a cost worth a choice (GroundFamilies.Rule names each one's switch).
    GROUND_SECOND_LAYER("Second ground layer", 0, 1, 1, 0, 0, 1, Applies.PROGRAMS, null,
          "Where two ground materials meet (turf and soil, rock and scree, dust on stone, a river bank's soil and "
                + "gravel), both are drawn and blended by their height. Off, each spot shows its stronger material alone, "
                + "which is faster. Switching it rebuilds the board's shaders."),
    GROUND_TONE_PATCHES("Ground tone patches", 0, 1, 1, 1, 1, 1, Applies.PROGRAMS, null,
          "Broad lighter and darker patches across open ground, dry and lush areas of a meadow and ripples on sand, so "
                + "a field reads neither flat nor tiled. Switching it rebuilds the board's shaders."),
    GROUND_SOIL_MANTLE("Soil mantle", 0, 1, 1, 1, 1, 1, Applies.PROGRAMS, null,
          "Bare soil on banks and under rims where the cover ends, and the cap plates of dry crests, instead of bare "
                + "rock. Switching it rebuilds the board's shaders."),
    GROUND_WEAR_THROUGH("Worn ground", 0, 1, 1, 1, 1, 1, Applies.PROGRAMS, null,
          "Level turf wears through to its soil in patches. Switching it rebuilds the board's shaders."),
    GROUND_TURF_LIP("Turf lip", 0, 1, 1, 1, 1, 1, Applies.PROGRAMS, null,
          "A two-level bank keeps a ragged lip of turf at its rim, broken turf on its shoulder and the root mat below "
                + "them. Switching it rebuilds the board's shaders."),
    GROUND_WALL_WEATHERING("Wall weathering", 0, 1, 1, 1, 1, 1, Applies.PROGRAMS, null,
          "Cliffs weather by their height: a bleached rim, streaks running down from it and grime toward the foot. "
                + "Switching it rebuilds the board's shaders."),
    GROUND_POCKETS("Cover pockets", 0, 1, 1, 1, 1, 1, Applies.PROGRAMS, null,
          "Small pockets break the edge of grass and other cover into a fine fringe on banks and slopes, where without "
                + "them it ends in larger patches. Switching it rebuilds the board's shaders."),
    GROUND_TWO_SCALE_PATCHES("Two-scale patches", 0, 1, 1, 0, 0, 1, Applies.PROGRAMS, null,
          "The ground's broad patches from two noise scales, the finer one breaking bare soil and turf into smaller "
                + "patches on banks and level tops. Off, one scale: one texture read less a pixel. Switching it rebuilds "
                + "the board's shaders.");

    /** How a change reaches the GPU. */
    enum Applies {
        /** Compiled in as {@link #define}: part of the lighting's program key, so a change goes behind the loading stage. */
        DEFINE,
        /** No define, but it changes which programs draws take (depth twins, a pass's inputs): part of the key too. */
        PROGRAMS,
        /** Rebuilds the terrain behind the loading stage. */
        BUILD,
        /** A uniform or a CPU value: the next frame. */
        LIVE
    }

    final String label;
    final String help;
    final float min, max, step;
    /** The value in Low, Medium and High, by {@link BoardSwitches.Preset} ordinal. */
    final float[] presets;
    final Applies applies;
    /** The define a {@link Applies#DEFINE} switch compiles in while on; null for the others. */
    final String define;

    BoardSwitch(String label, float min, float max, float step, float low, float medium, float high, Applies applies,
          String define, String help) {
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.presets = new float[] { low, medium, high };
        this.applies = applies;
        this.define = define;
        this.help = help;
    }

    /** On or off, rather than a value. */
    boolean toggle() { return min == 0 && max == 1 && step == 1; }

    /**
     * Whether a change selects other programs: the switch is part of a program key (BoardSwitches#programKey). A
     * compact-ground rule's switch only while the compact ground is on: without it no program reads the rule.
     */
    boolean keyed() {
        return applies == Applies.DEFINE
              || applies == Applies.PROGRAMS && (GroundFamilies.ON || !GroundFamilies.Rule.has(this));
    }
}
