/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.graphics.Cubemap;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture3D;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.ObjectMap;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * After each top-level test class, forgets the libGDX applications its tests ended ({@link GpuHost#forget}): libGDX's
 * static maps of managed GL resources would otherwise keep every application, its window and its listener (a whole
 * board view with its board and mocks) for the rest of the run. Registered with the JUnit Platform through
 * {@code META-INF/services} in {@code testresources}. Must stay public with a public no-argument constructor.
 */
public final class GpuApplicationRelease implements TestExecutionListener {
    /** The maps' fields, private to libGDX: they hold the applications, the public clear methods take one each. */
    private static final List<Field> MAPS = fields(ShaderProgram.class, "shaders", Mesh.class, "meshes", Texture.class,
          "managedTextures", Cubemap.class, "managedCubemaps", TextureArray.class, "managedTextureArrays",
          Texture3D.class, "managedTexture3Ds", GLFrameBuffer.class, "buffers");

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        boolean topLevelClass = testIdentifier.getSource().filter(ClassSource.class::isInstance)
              .map(source -> ((ClassSource) source).getClassName().indexOf('$') < 0).orElse(false);
        if (!topLevelClass) {
            return;
        }
        Set<Application> applications = new HashSet<>();
        try {
            for (Field map : MAPS) {
                Object held = map.get(null);
                if (held instanceof ObjectMap<?, ?> objects) {
                    objects.keys().forEach(key -> applications.add((Application) key));
                } else {
                    ((Map<?, ?>) held).keySet().forEach(key -> applications.add((Application) key));
                }
            }
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
        applications.forEach(GpuHost::forget);
    }

    private static List<Field> fields(Object... pairs) {
        List<Field> fields = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            try {
                Field field = ((Class<?>) pairs[i]).getDeclaredField((String) pairs[i + 1]);
                field.setAccessible(true);
                fields.add(field);
            } catch (NoSuchFieldException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
        return List.copyOf(fields);
    }
}
