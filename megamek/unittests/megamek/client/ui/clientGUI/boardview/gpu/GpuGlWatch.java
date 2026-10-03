/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;

/**
 * Routes every GL call of the running application through a handler, for tests that watch GL calls. On an OpenGL 3
 * context libGDX issues some calls, such as buffer deletion, through its GL30 object, so both are replaced.
 */
final class GpuGlWatch implements AutoCloseable {
    final GL20 original = Gdx.gl20;
    private final GL30 original30 = Gdx.gl30;

    GpuGlWatch(InvocationHandler handler) {
        Class<?> api = original30 != null ? GL30.class : GL20.class;
        GL20 watched = (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[] { api }, handler);
        Gdx.graphics.setGL20(watched);
        Gdx.gl = Gdx.gl20 = watched;
        if (original30 != null) {
            Gdx.graphics.setGL30((GL30) watched);
            Gdx.gl30 = (GL30) watched;
        }
    }

    @Override
    public void close() {
        Gdx.graphics.setGL20(original);
        Gdx.gl = Gdx.gl20 = original;
        if (original30 != null) {
            Gdx.graphics.setGL30(original30);
            Gdx.gl30 = original30;
        }
    }
}
