/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.g3d.Attribute;
import megamek.common.board.Coords;

/** Painter order survives material batching and is identical in every recipient of a spanning decal. */
final class GpuDecalOrder extends Attribute {
    static final long TYPE = register("boardDecalOrder");
    final int order;
    final Coords owner;
    final String id;

    GpuDecalOrder(BoardDecals.Stamp stamp) { this(stamp.object().drawOrder(), stamp.owner(), stamp.object().id()); }
    private GpuDecalOrder(int order, Coords owner, String id) {
        super(TYPE); this.order = order; this.owner = owner; this.id = id;
    }

    /**
     * The n-th draw of one chunk's grouped paint. Chunks never paint the same hex, so equal layers of different chunks
     * may share a draw and need no order between them.
     */
    static GpuDecalOrder layer(int layer) { return new GpuDecalOrder(layer, new Coords(0, 0), ""); }

    /** The same layer continued in another mesh: drawn after the part it continues, before the next layer. */
    GpuDecalOrder continued() { return new GpuDecalOrder(order, owner, id + "+"); }

    @Override public Attribute copy() { return new GpuDecalOrder(order, owner, id); }

    @Override public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        GpuDecalOrder next = (GpuDecalOrder) other;
        int value = Integer.compare(order, next.order);
        if (value == 0) { value = Integer.compare(owner.getX(), next.owner.getX()); }
        if (value == 0) { value = Integer.compare(owner.getY(), next.owner.getY()); }
        return value == 0 ? id.compareTo(next.id) : value;
    }

    @Override public int hashCode() { return java.util.Objects.hash(super.hashCode(), order, owner, id); }
}
