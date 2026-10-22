/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design;

/** The color vision types the token checks and the galleries simulate. */
public enum Vision {
    NORMAL(null),
    /** Green-blind, the most common deficiency. */
    DEUTERANOPIA(new double[][] {
          { 0.367322, 0.860646, -0.227968 },
          { 0.280085, 0.672501, 0.047413 },
          { -0.011820, 0.042940, 0.968881 } }),
    /** Red-blind. */
    PROTANOPIA(new double[][] {
          { 0.152286, 1.052583, -0.204868 },
          { 0.114503, 0.786281, 0.099216 },
          { -0.003882, -0.048116, 1.051998 } }),
    /** Blue-blind. */
    TRITANOPIA(new double[][] {
          { 1.255528, -0.076749, -0.178779 },
          { -0.078411, 0.930809, 0.147602 },
          { 0.004733, 0.691367, 0.303900 } });

    private final double[][] matrix;

    Vision(double[][] matrix) {
        this.matrix = matrix;
    }

    /** The linear-RGB simulation matrix, or null for normal vision. */
    double[][] matrix() {
        return matrix;
    }
}
