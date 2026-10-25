/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.annotation.Nullable;
import megamek.common.Facing;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.equipment.Flare;
import megamek.common.moves.MoveStep;
import megamek.common.units.Entity;
import megamek.common.units.IBomber;
import megamek.common.units.VTOL;

/**
 * A tactical marking the client publishes on its board state: what is marked, where and in which colour. Each board
 * draws it its own way; the marks hold no drawing. An area mark is one hex of the area with its outside sides as bits
 * by direction ({@code borders}).
 */
public sealed interface TacticalMark {
    /** One hex of where a unit can move, banded in its movement colour. */
    record Envelope(Coords coords, int borders, Color color) implements TacticalMark { }

    /** One hex of a weapon range bracket ({@code RangeType}); also the plain highlight of picked hexes. */
    record WeaponRange(Coords coords, int borders, int bracket) implements TacticalMark { }

    /** A weapon range bracket's letter on a hex. */
    record RangeLabel(Coords coords, int bracket) implements TacticalMark { }

    /** One hex of an objective's control zone, in its holder's colour. */
    record ControlZone(Coords coords, int borders, Color color) implements TacticalMark { }

    /** One hex of a sensor, visual or probe range (the {@code SensorRangeSprite} brackets). */
    record SensorRange(Coords coords, int borders, int bracket) implements TacticalMark { }

    /** The signed defensive modifier a move ending on {@code coords} with {@code facing} earns. */
    record FacingModifier(Coords coords, Facing facing, String modifier, Color color) implements TacticalMark { }

    /** A dot of a bot's planned route, the trail's or a turning point's size in hex units. */
    record RouteDot(Coords coords, Color color, double diameter) implements TacticalMark { }

    /** Free text on a hex, such as the order of picked hexes. */
    record HexText(Coords coords, String text, Color color) implements TacticalMark { }

    /** A hex a crane may unload into. */
    record CraneTarget(Coords coords, Color color) implements TacticalMark { }

    /** A sensor scan's sweep from the scanning unit towards its target hex. */
    record Scan(Coords scanner, Coords target, Color color) implements TacticalMark { }

    /** An objective or route flag: banner colour, optional label and counter, and the hex side an arrow leaves by. */
    record Flag(Coords coords, Color color, @Nullable String label, @Nullable String progress, int facing)
          implements TacticalMark {
        public static final int NO_FACING = -1;
    }

    /** Cargo lying on the ground. */
    record Cargo(Coords coords) implements TacticalMark { }

    /** A burning flare. */
    record Illumination(Flare flare) implements TacticalMark { }

    /** A building that may collapse under the units entering it. */
    record CollapseWarning(Coords coords) implements TacticalMark { }

    /** Woods being cut, with the turns of work left. */
    record SawClearing(Coords coords, int turnsRemaining) implements TacticalMark { }

    /** A bridge repaired this round. */
    record BridgeRepaired(Coords coords) implements TacticalMark { }

    /**
     * A bridge being raised, whose finished span of {@code bridgeType} ({@code BuildingType}) joins the hex sides
     * {@code exits}.
     */
    record BridgeBuild(Coords coords, int turnsWorked, int turnsRequired, int exits, int bridgeType,
          boolean hazardOutline) implements TacticalMark { }

    /** Infantry digging in, or dug in once {@code complete}. */
    record DugIn(Coords coords, boolean complete, String label) implements TacticalMark { }

    /** A hex being fortified, stage of stages. */
    record Fortify(Coords coords, int stage, int stages) implements TacticalMark { }

    /** Rubble being cleared. */
    record RubbleClear(Coords coords, int turnsCompleted, int turnsRequired) implements TacticalMark {
        /** The turn in progress, as the counter shows it. */
        public int displayTurn() {
            return Math.min(turnsCompleted + 1, turnsRequired);
        }
    }

    /** A crane unloading {@code unit} (may be null) to face {@code facing}, with the work done so far. */
    record CraneOperation(Coords coords, @Nullable Entity unit, int facing, int turnsCompleted, int turnsRequired)
          implements TacticalMark {
        /** The turns of crane work still to go, as the countdown shows them. */
        public int turnsLeft() {
            return Math.max(0, turnsRequired - turnsCompleted);
        }
    }

    /** A step of the planned move; {@code last} for the step that ends it. */
    record Step(MoveStep step, boolean last) implements TacticalMark { }

    /** The hex of a flight path indicator: {@code index} of the forward steps the remaining velocity takes. */
    record FlightPath(List<MoveStep> steps, int index, boolean last) implements TacticalMark {
        public FlightPath {
            steps = Collections.unmodifiableList(new ArrayList<>(steps));
        }
    }

    /** A C3, naval C3 or Nova CEWS link from a unit to another on its network. */
    record C3Link(Entity entity, Entity master) implements TacticalMark {
        public Color color() {
            return entity.getOwner().getColour().getColour();
        }
    }

    /** The hexes an airborne unit flew over this turn. */
    record FlyOver(Entity entity) implements TacticalMark {
        public Color color() {
            return entity.getOwner().getColour().getColour();
        }
    }

    /** The hexes a VTOL or bomber attacks while flying over. */
    record VtolAttack(Entity entity) implements TacticalMark {
        public Color color() {
            return entity.getOwner().getColour().getColour();
        }

        public List<Coords> targets() {
            if ((entity instanceof IBomber bomber) && bomber.isVTOLBombing()) {
                return List.of(bomber.getVTOLBombTarget().getPosition());
            }
            return entity instanceof VTOL vtol ? List.copyOf(vtol.getStrafingCoords()) : List.of();
        }
    }

    /** A unit's movement vector (advanced vector movement): from its hex to where its velocity carries it. */
    record MovementVector(Coords start, Coords end, int velocity, Color color) implements TacticalMark {
        /**
         * The vector of {@code entity} with {@code vectors}: grey when done, red when it leaves {@code board}, else its
         * owner's colour; {@code transparency} is the attack arrows' alpha.
         */
        public static MovementVector of(Entity entity, int[] vectors, Board board, int transparency) {
            Coords start = entity.getPosition();
            Coords end = Compute.getFinalPosition(start, vectors);
            int velocity = 0;
            for (int element : vectors) {
                velocity += element;
            }
            Color color = entity.getOwner().getColour().getColour();
            if (!board.contains(end)) {
                color = new Color(0xff0000 | (transparency << 24), true);
            }
            if (entity.isDone()) {
                color = new Color(0x696969 | (transparency << 24), true);
            }
            return new MovementVector(start, end, velocity, color);
        }
    }

    /** A line-of-sight tool end. */
    record LosEnd(Coords coords) implements TacticalMark { }
}
