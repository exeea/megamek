/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

/**
 * A rules or placement problem of one hex, or what the legacy conversion could not decode exactly; {@code object} is the
 * placed object it concerns, or "".
 */
public record BoardIssue(Coords coords, String object, String text) { }
