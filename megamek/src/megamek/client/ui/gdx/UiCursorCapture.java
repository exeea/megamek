/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;

/**
 * Holds the pointer still for a relative drag, such as a value scrub or a camera orbit. The desktop backend supplies
 * unbounded virtual coordinates while keeping the cursor hidden and anchored, so the drag never stops at the screen's
 * edge, then restores its visible position on release.
 * <p>
 * On Windows, GLFW's first event poll after the capture can still report the pointer where it was before GLFW centred
 * it, and turns that into one bogus step of about the distance to the window's centre (glfw/glfw#1523, open). The
 * drag's owner therefore reads its pointer through {@link #trusted}, which drops the movement of that frame.
 */
public final class UiCursorCapture {
    /** The window input that hid the cursor; each window has its own. */
    private Input captured;
    private long frame;
    private float lastX, lastY, offsetX, offsetY;
    private final Vector2 point = new Vector2();

    /**
     * Starts a drag with the pointer at (x, y) and hides and anchors the cursor. Does not take over another gesture's
     * capture; the drag's pointer is then read as it comes.
     */
    public void capture(float x, float y) {
        lastX = x;
        lastY = y;
        offsetX = 0;
        offsetY = 0;
        if (captured != null || Gdx.input.isCursorCatched()) { return; }
        captured = Gdx.input;
        captured.setCursorCatched(true);
        frame = Gdx.graphics.getFrameId();
    }

    /**
     * The pointer at (x, y), in the caller's coordinates, without the movement GLFW reported in the frame after the
     * capture: events of the capture's own frame were polled before it and are kept.
     */
    public Vector2 trusted(float x, float y) {
        if (captured != null && Gdx.graphics.getFrameId() == frame + 1) {
            offsetX += x - lastX;
            offsetY += y - lastY;
        }
        lastX = x;
        lastY = y;
        return point.set(x - offsetX, y - offsetY);
    }

    /** Shows the cursor again where the capture began; every exit of the gesture may call it. */
    public void release() {
        if (captured != null) {
            captured.setCursorCatched(false);
            captured = null;
        }
    }
}
