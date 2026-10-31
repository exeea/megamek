/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.annotation.Nullable;
import megamek.client.ui.util.PlayerColour;
import megamek.common.battlefieldSupport.OverlayStyle;
import megamek.common.battlefieldSupport.StripeDirection;
import megamek.common.icons.Camouflage;

/**
 * What the player has set in the camo chooser so far, without a toolkit, and the camo it makes, as the Swing
 * {@code CamoChooserDialog} makes it: the chosen camo (or no camo), its rotation (-180 to 180 degrees in steps of 10)
 * and scale (3 to 15 tenths), and the marker overlay that Battlefield Support Assets draw over it (colour, stripe
 * direction and style). It starts from the camo the chooser opens with.
 * <p>
 * The overlay colour is one of a short palette rather than any colour: the starting camo's own, the default caution
 * yellow and the player colours. Each is given as a camo that carries it, so this class never names a toolkit colour.
 */
public final class CamoChoice {
    public static final int MIN_ROTATION = -180;
    public static final int MAX_ROTATION = 180;
    public static final int ROTATION_STEP = 10;
    public static final int MIN_SCALE = 3;
    public static final int MAX_SCALE = 15;

    private final List<Camouflage> palette = new ArrayList<>();
    private final boolean individual;
    private Camouflage selected;
    private int rotation;
    private int scale = 10;
    private int overlayColour;
    private StripeDirection direction = StripeDirection.DIAGONAL;
    private OverlayStyle style = OverlayStyle.BAND;

    /**
     * @param initial    the camo to start from, or null
     * @param individual whether the camo is a unit's own, so that going back to the owner's camo is offered
     */
    public CamoChoice(@Nullable Camouflage initial, boolean individual) {
        this.individual = individual;
        Camouflage first = new Camouflage();
        if (initial != null) {
            selected = CamoCatalog.plain(initial);
            rotation = initial.getRotationAngle();
            scale = initial.getScale();
            direction = initial.getOverlayDirection();
            style = initial.getOverlayStyle();
            first.setOverlayColor(initial.getOverlayColor());
        }
        palette.add(first);
        add(new Camouflage());
        for (PlayerColour colour : PlayerColour.values()) {
            Camouflage carrier = new Camouflage();
            carrier.setOverlayColor(colour.getColour());
            add(carrier);
        }
    }

    private void add(Camouflage carrier) {
        for (Camouflage each : palette) {
            if (each.getOverlayColor().equals(carrier.getOverlayColor())) {
                return;
            }
        }
        palette.add(carrier);
    }

    /** Whether going back to the owner's camo is offered: the units' camo is their own. */
    public boolean individual() {
        return individual;
    }

    /** The chosen camo as the chooser lists it, or null for none. */
    public @Nullable Camouflage selected() {
        return selected;
    }

    public void select(@Nullable Camouflage camo) {
        selected = camo == null ? null : CamoCatalog.plain(camo);
    }

    public int rotation() {
        return rotation;
    }

    /** Sets the rotation, kept within -180 to 180 and snapped to the nearest step of 10 degrees. */
    public void rotation(int degrees) {
        int clamped = Math.max(MIN_ROTATION, Math.min(MAX_ROTATION, degrees));
        rotation = Math.round(clamped / (float) ROTATION_STEP) * ROTATION_STEP;
    }

    public int scale() {
        return scale;
    }

    /** Sets the scale in tenths, kept within 3 to 15. */
    public void scale(int tenths) {
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, tenths));
    }

    /** The overlay colours on offer, each carried by a camo: the starting one first, then the caution yellow. */
    public List<Camouflage> palette() {
        return List.copyOf(palette);
    }

    /** The index of the overlay colour in the {@link #palette}. */
    public int overlayColour() {
        return overlayColour;
    }

    public void overlayColour(int index) {
        overlayColour = Objects.checkIndex(index, palette.size());
    }

    public StripeDirection direction() {
        return direction;
    }

    public void direction(StripeDirection value) {
        direction = Objects.requireNonNull(value);
    }

    public OverlayStyle style() {
        return style;
    }

    public void style(OverlayStyle value) {
        style = Objects.requireNonNull(value);
    }

    /** The camo the player confirms: the chosen one (or no camo) with the rotation, scale and overlay. */
    public Camouflage result() {
        return make(selected);
    }

    /** The camo for going back to the owner's camo: no camo of its own, with the rotation, scale and overlay. */
    public Camouflage ownerResult() {
        return make(null);
    }

    private Camouflage make(@Nullable Camouflage base) {
        Camouflage result = base == null ? new Camouflage() : base.clone();
        result.setScale(scale);
        result.setRotationAngle(rotation);
        result.setOverlayColor(palette.get(overlayColour).getOverlayColor());
        result.setOverlayDirection(direction);
        result.setOverlayStyle(style);
        return result;
    }
}
