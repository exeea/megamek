/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Matrix4;

/** One render-owned camera retaining the same pose when its projection changes. */
final class BoardProjectionCamera extends Camera {
    public float zoom = 1;
    boolean perspective;
    float fieldOfView = BoardCamera.DEFAULT_FIELD_OF_VIEW;

    /**
     * The perspective camera's distance from its orbit pivot: where its field of view spans as many world units per
     * pixel as the orthographic zoom does. Both projections then show the pivot's plane at one scale, so switching
     * between them or changing the field of view does not jump the view in or out.
     */
    float distance() {
        return viewportHeight * zoom / (2 * (float) Math.tan(Math.toRadians(fieldOfView / 2)));
    }

    @Override
    public void update() {
        update(true);
    }

    @Override
    public void update(boolean updateFrustum) {
        if (perspective) {
            projection.setToProjection(near, far, fieldOfView, viewportWidth / viewportHeight);
        } else {
            projection.setToOrtho(-viewportWidth * zoom / 2, viewportWidth * zoom / 2,
                  -viewportHeight * zoom / 2, viewportHeight * zoom / 2, near, far);
        }
        view.setToLookAt(direction, up);
        view.translate(-position.x, -position.y, -position.z);
        combined.set(projection);
        Matrix4.mul(combined.val, view.val);
        if (updateFrustum) {
            invProjectionView.set(combined);
            Matrix4.inv(invProjectionView.val);
            frustum.update(invProjectionView);
        }
    }
}
