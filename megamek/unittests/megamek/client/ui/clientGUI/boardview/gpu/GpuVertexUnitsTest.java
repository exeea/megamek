/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarFile;

import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import org.junit.jupiter.api.Test;

/**
 * No two vertex attributes that meet in one mesh or one program share a libGDX key (usage and unit): a program maps a
 * mesh's attributes to its inputs by that key, so the second of two takes the first's data and leaves its own input
 * unbound (the scar patches' page UV and the height field's parent cover weights both had generic unit 9). The layouts
 * are read from the code: every static layout a class of the board renderer declares, and what every static method
 * that makes a layout from a layout (a wall's chart, tessellation contacts, the patches' page UV) makes of them, each
 * maker once, two deep. A program's own map is checked where it is made (GpuSurfaceScars#requireDistinct).
 */
class GpuVertexUnitsTest {
    private static final String PACKAGE = GpuTerrain.class.getPackageName();

    @Test
    void declaredLayoutsAndWhatTheirMakersAddHaveDistinctUnits() throws Exception {
        Map<String, VertexAttributes> layouts = declared();
        assertTrue(layouts.containsKey("TerrainField.COVER_LAYOUT") && layouts.containsKey("GpuSurfaceBlend.VERTICES"),
              "The scan finds the renderer's static layouts: " + layouts.keySet());
        List<Method> makers = makers();
        assertTrue(makers.stream().anyMatch(maker -> maker.getName().equals("attributes")
              && maker.getDeclaringClass() == GpuSurfaceScars.class), "The scan finds the page UV's maker: " + makers);
        for (int round = 0; round < 2; round++) {
            Map<String, VertexAttributes> made = new LinkedHashMap<>();
            for (var layout : layouts.entrySet()) {
                for (Method maker : makers) {
                    String call = maker.getDeclaringClass().getSimpleName() + "." + maker.getName() + "(";
                    // A maker adds its attribute once: never to a layout it already made.
                    if (layout.getKey().contains(call)) { continue; }
                    String name = call + layout.getKey() + ")";
                    made.put(name, assertDoesNotThrow(() -> invoke(maker, layout.getValue()), name));
                }
            }
            layouts.putAll(made);
        }
        TreeSet<Integer> units = new TreeSet<>();
        for (var layout : layouts.entrySet()) {
            assertDoesNotThrow(() -> GpuSurfaceScars.requireDistinct(layout.getValue()), layout.getKey());
            for (int i = 0; i < layout.getValue().size(); i++) {
                VertexAttribute attribute = layout.getValue().get(i);
                if (attribute.usage == VertexAttributes.Usage.Generic) { units.add(attribute.unit); }
            }
        }
        // For the next attribute's author (AGREEMENTS.md records the free ones): the generic units these layouts take.
        System.out.println("Generic vertex attribute units in use: " + units);
        assertFalse(units.isEmpty());
    }

    private static VertexAttributes invoke(Method maker, VertexAttributes layout) throws Exception {
        try {
            return (VertexAttributes) maker.invoke(null, layout);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) { throw cause; }
            throw failure;
        }
    }

    /** Every static field of a renderer class that holds a layout, by "Class.FIELD". */
    private static Map<String, VertexAttributes> declared() throws Exception {
        Map<String, VertexAttributes> layouts = new LinkedHashMap<>();
        for (Class<?> type : classes()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == VertexAttributes.class) {
                    field.setAccessible(true);
                    layouts.put(type.getSimpleName() + "." + field.getName(), (VertexAttributes) field.get(null));
                }
            }
        }
        return layouts;
    }

    /** Every static method of a renderer class that takes one layout and returns one. */
    private static List<Method> makers() throws Exception {
        List<Method> makers = new ArrayList<>();
        for (Class<?> type : classes()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == VertexAttributes.class
                      && method.getParameterCount() == 1 && method.getParameterTypes()[0] == VertexAttributes.class) {
                    method.setAccessible(true);
                    makers.add(method);
                }
            }
        }
        return makers;
    }

    /** The renderer package's own classes (not the tests'), loaded without running their static initializers. */
    private static List<Class<?>> classes() throws Exception {
        String folder = PACKAGE.replace('.', '/') + "/";
        URL home = GpuTerrain.class.getResource("GpuTerrain.class");
        List<String> names = new ArrayList<>();
        if ("jar".equals(home.getProtocol())) {
            String jar = home.getPath().substring("file:".length(), home.getPath().indexOf('!'));
            try (JarFile file = new JarFile(java.net.URLDecoder.decode(jar, java.nio.charset.StandardCharsets.UTF_8))) {
                file.stream().map(entry -> entry.getName()).filter(name -> name.startsWith(folder) && name.endsWith(".class")
                      && name.indexOf('/', folder.length()) < 0).forEach(name -> names.add(name.substring(folder.length())));
            }
        } else {
            for (File file : new File(home.toURI()).getParentFile().listFiles((dir, name) -> name.endsWith(".class"))) {
                names.add(file.getName());
            }
        }
        List<Class<?>> classes = new ArrayList<>();
        for (String name : new TreeSet<>(names)) {
            classes.add(Class.forName(PACKAGE + "." + name.substring(0, name.length() - ".class".length()), false,
                  GpuTerrain.class.getClassLoader()));
        }
        return classes;
    }
}
