/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.KeyBindParser;
import megamek.common.preference.PreferenceManager;
import megamek.common.preference.PreferenceStore;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.mockito.Mockito;

/**
 * After each top-level UI test class ({@code megamek.client.ui} and below), what it left in the JVM's static state
 * goes:
 * <ul>
 *   <li>the preference and key-bind listeners it registered. A component a test builds (a phase display, a menu bar)
 *   registers itself with the static preference stores and with KeyBindParser and stays registered: a later class's
 *   preference change reached it, and through it its test's mocks;</li>
 *   <li>Mockito's inline mocks. An inline mock whose stubs or recorded calls lead back to it (an answer that captures
 *   its fixture, a spy of Gdx.graphics) is never collected, and kept a whole board view with its board.</li>
 * </ul>
 * Registered with the JUnit Platform through {@code META-INF/services} in {@code testresources}. Must stay public
 * with a public no-argument constructor.
 */
public final class UiTestRelease implements TestExecutionListener {
    private static final Field STORES = field(PreferenceManager.class, "stores");
    private static final Field CLIENT_STORE = field(PreferenceManager.class, "clientPreferenceStore");
    private static final Field STORE_LISTENERS = field(PreferenceStore.class, "listeners");
    private static final Field KEY_BIND_LISTENERS = field(KeyBindParser.class, "listeners");

    /** The listener lists and their members as the class began. */
    private final Map<List<?>, Set<Object>> before = new IdentityHashMap<>();

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (uiClass(testIdentifier)) {
            before.clear();
            for (List<?> listeners : listenerLists()) {
                Set<Object> members = Collections.newSetFromMap(new IdentityHashMap<>());
                synchronized (listeners) { members.addAll(listeners); }
                before.put(listeners, members);
            }
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (!uiClass(testIdentifier)) {
            return;
        }
        for (List<?> listeners : listenerLists()) {
            Set<Object> members = before.get(listeners);
            synchronized (listeners) { listeners.removeIf(listener -> members == null || !members.contains(listener)); }
        }
        before.clear();
        Mockito.framework().clearInlineMocks();
    }

    /** KeyBindParser's listeners and each preference store's. */
    private static List<List<?>> listenerLists() {
        List<List<?>> lists = new ArrayList<>();
        try {
            lists.add((List<?>) KEY_BIND_LISTENERS.get(null));
            PreferenceManager manager = PreferenceManager.getInstance();
            List<Object> stores = new ArrayList<>(((Map<?, ?>) STORES.get(manager)).values());
            stores.add(CLIENT_STORE.get(manager));
            for (Object store : stores) {
                if (store instanceof PreferenceStore preferences) {
                    Object listeners = STORE_LISTENERS.get(preferences);
                    if (!containsList(lists, listeners)) { lists.add((List<?>) listeners); }
                }
            }
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
        return lists;
    }

    private static boolean containsList(List<List<?>> lists, Object list) {
        return lists.stream().anyMatch(each -> each == list);
    }

    private static boolean uiClass(TestIdentifier testIdentifier) {
        return testIdentifier.getSource().filter(ClassSource.class::isInstance).map(source -> {
            String name = ((ClassSource) source).getClassName();
            return name.startsWith("megamek.client.ui.") && name.indexOf('$') < 0;
        }).orElse(false);
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
