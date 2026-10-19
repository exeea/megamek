/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.event;

import java.io.Serial;

import megamek.common.NuclearDetonation;

/** Scheduled-strike observation delivered before its authoritative board updates. */
public final class GameNuclearDetonationEvent extends GameEvent {
    @Serial
    private static final long serialVersionUID = 1L;
    private final NuclearDetonation detonation;
    private final boolean completed;

    public GameNuclearDetonationEvent(Object source, NuclearDetonation detonation, boolean completed) {
        super(source);
        this.detonation = detonation;
        this.completed = completed;
    }

    public NuclearDetonation detonation() { return detonation; }

    public boolean completed() { return completed; }

    @Override
    public void fireEvent(GameListener listener) { listener.gameNuclearDetonation(this); }

    @Override
    public String getEventName() { return "Nuclear detonation"; }
}
