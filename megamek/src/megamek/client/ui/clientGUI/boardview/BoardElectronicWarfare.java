/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.common.ECMInfo;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.ComputeECM;
import megamek.common.units.Entity;

/**
 * Immutable presentation of the shared E(C)CM rules ({@link ComputeECM}) on one board, in column order: what the 3D
 * board's ECM pass and its source badges draw. Built on the Swing thread from the fields the local player may see; the
 * render thread draws its bits and decides no rule. The Legacy 2D board keeps its own colour maps.
 *
 * @param hexes    per hex: the {@link #OWN_ECM} to {@link #RING} bits, and from {@link #COUNT_SHIFT} the number of
 *                 displayed sources covering it
 * @param sources  every displayed emitter, and every shown unit whose field TacOps ECCM negated (it covers no hex)
 * @param strength the ECM Transparency setting (0-256, 128 by default), the line strength; 0 shows nothing
 */
public record BoardElectronicWarfare(int width, int height, List<Integer> hexes, List<Source> sources, int strength) {
    /** Coverage by side and kind: own or allied, enemy (fields without an owner are enemy). */
    public static final int OWN_ECM = 1, OWN_ECCM = 2, ENEMY_ECM = 4, ENEMY_ECCM = 8;
    /** A unit of the local player here would be affected by enemy ECM ({@link ComputeECM#getECMEffectsAt}). */
    public static final int JAMMED = 16;
    /** An uncovered hex beside a covered one: it carries the outer half of the field's line. */
    public static final int RING = 32;
    public static final int COUNT_SHIFT = 8;
    public static final BoardElectronicWarfare EMPTY = new BoardElectronicWarfare(0, 0, List.of(), List.of(), 0);

    /**
     * One emitter: a unit, whose ECM and ECCM count once, or an entity-less field (chaff, EMP mines, IMP) with
     * {@link Entity#NONE}, which cannot be hovered.
     */
    public record Source(int entityId, Coords position, boolean own, boolean ecm, boolean eccm, boolean angel,
          Set<Coords> hexes) {
        public Source {
            hexes = Set.copyOf(hexes);
        }

        /** A unit whose field TacOps ECCM negated: it emits, but its field covers nothing. */
        public boolean suppressed() {
            return hexes.isEmpty();
        }
    }

    public BoardElectronicWarfare {
        hexes = List.copyOf(hexes);
        sources = List.copyOf(sources);
        if (width < 0 || height < 0 || hexes.size() != width * height) {
            throw new IllegalArgumentException("Electronic warfare must contain one value per hex");
        }
    }

    /** The hex's bits and count, 0 off the board. */
    public int hex(Coords coords) {
        int x = coords.getX(), y = coords.getY();
        return x < 0 || y < 0 || x >= width || y >= height ? 0 : hexes.get(x * height + y);
    }

    public static int count(int hex) {
        return hex >>> COUNT_SHIFT;
    }

    /**
     * Builds the snapshot from the fields the local player may see and their covered hexes, as the board's coverage
     * loop found them, and the shown units that carry ECM or ECCM. Hexes off the board are ignored.
     */
    static BoardElectronicWarfare capture(Board board, @Nullable Player localPlayer,
          List<Map.Entry<ECMInfo, List<Coords>>> fields, List<Entity> emitters, int strength) {
        if (strength <= 0 || fields.isEmpty() && emitters.isEmpty()) {
            return EMPTY;
        }
        int width = board.getWidth(), height = board.getHeight();
        int[] bits = new int[width * height];
        // One source per emitting unit, one per entity-less field, in the fields' order.
        Map<Object, List<Map.Entry<ECMInfo, List<Coords>>>> grouped = new LinkedHashMap<>();
        for (var field : fields) {
            Entity entity = field.getKey().getEntity();
            grouped.computeIfAbsent(entity == null ? field : entity.getId(), key -> new ArrayList<>()).add(field);
        }
        List<Source> sources = new ArrayList<>();
        Set<Integer> shown = new HashSet<>();
        for (var group : grouped.values()) {
            ECMInfo first = group.getFirst().getKey();
            boolean own = !first.isOpposed(localPlayer), ecm = false, eccm = false, angel = false;
            Set<Coords> covered = new HashSet<>();
            for (var field : group) {
                ECMInfo info = field.getKey();
                boolean counters = info.isECCM();
                ecm |= !counters;
                eccm |= counters;
                angel |= info.isAngelECM() || info.isAngelECCM();
                int kind = own ? (counters ? OWN_ECCM : OWN_ECM) : (counters ? ENEMY_ECCM : ENEMY_ECM);
                for (Coords coords : field.getValue()) {
                    if (board.contains(coords)) {
                        bits[coords.getX() * height + coords.getY()] |= kind;
                        covered.add(coords);
                    }
                }
            }
            for (Coords coords : covered) {
                bits[coords.getX() * height + coords.getY()] += 1 << COUNT_SHIFT;
            }
            int id = first.getEntity() == null ? Entity.NONE : first.getEntity().getId();
            shown.add(id);
            sources.add(new Source(id, first.getPos(), own, ecm, eccm, angel, covered));
        }
        for (Entity entity : emitters) {
            if (!shown.contains(entity.getId())) {
                ECMInfo ecm = entity.getECMInfo(), eccm = entity.getECCMInfo();
                sources.add(new Source(entity.getId(), entity.getPosition(), localPlayer != null
                      && !entity.getOwner().isEnemyOf(localPlayer), ecm != null, eccm != null,
                      ecm != null && ecm.isAngelECM() || eccm != null && eccm.isAngelECCM(), Set.of()));
            }
        }
        List<ECMInfo> displayed = fields.stream().map(Map.Entry::getKey).toList();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if ((bits[x * height + y] & 15) == 0) { continue; }
                Coords coords = new Coords(x, y);
                // Only the displayed fields, so an unseen emitter cannot show through; no ECM rule applies in space.
                if (localPlayer != null && !board.isSpace()) {
                    ECMInfo effects = ComputeECM.getECMEffectsAt(null, localPlayer, coords, displayed);
                    if (effects != null && effects.isECM()) { bits[x * height + y] |= JAMMED; }
                }
                for (int direction = 0; direction < 6; direction++) {
                    Coords next = coords.translated(direction);
                    if (board.contains(next) && (bits[next.getX() * height + next.getY()] & 15) == 0) {
                        bits[next.getX() * height + next.getY()] |= RING;
                    }
                }
            }
        }
        List<Integer> hexes = new ArrayList<>(bits.length);
        for (int value : bits) { hexes.add(value); }
        return new BoardElectronicWarfare(width, height, hexes, sources, strength);
    }
}
