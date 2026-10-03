/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.common.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests that weapon quirks read back from a save game or a network packet can still show their names.
 */
class WeaponQuirksTest {

    /**
     * Loading a save in a fresh MegaMek rebuilds each weapon's quirks without running their constructor. Before the
     * fix, nothing had filled the weapon quirk name table at that point, so asking for a quirk's display name crashed
     * the game. Other tests in this run have long since filled the table, so the quirks are read back through a
     * separate class loader, which starts with its own empty table just like a freshly started MegaMek.
     */
    @Test
    void deserializedQuirksShowTheirNameInAFreshSession() throws Exception {
        WeaponQuirks savedQuirks = new WeaponQuirks();
        savedQuirks.getOption(OptionsConstants.QUIRK_WEAPON_POS_ACCURATE).setValue(true);
        String expectedName = savedQuirks.getOption(OptionsConstants.QUIRK_WEAPON_POS_ACCURATE)
              .getDisplayableNameWithValue();
        byte[] savedBytes = serialize(savedQuirks);

        try (URLClassLoader freshSessionLoader = new URLClassLoader(testClassPath(),
              ClassLoader.getPlatformClassLoader())) {
            Class<?> freshWeaponQuirksClass = freshSessionLoader.loadClass(WeaponQuirks.class.getName());
            assertNotSame(WeaponQuirks.class, freshWeaponQuirksClass,
                  "The quirks must load in their own class loader, or the name table is not fresh");

            Object loadedQuirks = deserialize(savedBytes, freshSessionLoader);
            List<?> activeQuirks = (List<?>) freshWeaponQuirksClass.getMethod("activeQuirks").invoke(loadedQuirks);
            assertEquals(1, activeQuirks.size(), "The saved Accurate Weapon quirk should be read back as active");

            Object loadedQuirk = activeQuirks.getFirst();
            Class<?> freshOptionInterface = freshSessionLoader.loadClass(IOption.class.getName());
            Method displayNameMethod = freshOptionInterface.getMethod("getDisplayableNameWithValue");
            assertEquals(expectedName, displayNameMethod.invoke(loadedQuirk));
        }
    }

    private static byte[] serialize(Object object) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(object);
        }
        return bytes.toByteArray();
    }

    private static Object deserialize(byte[] bytes, ClassLoader classLoader) throws Exception {
        try (ObjectInputStream input = new ClassLoaderObjectInputStream(new ByteArrayInputStream(bytes),
              classLoader)) {
            return input.readObject();
        }
    }

    private static URL[] testClassPath() throws IOException {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(new File(entry).toURI().toURL());
        }
        return urls.toArray(new URL[0]);
    }

    /** Resolves the classes of the serialized object through the given class loader. */
    private static class ClassLoaderObjectInputStream extends ObjectInputStream {
        private final ClassLoader classLoader;

        ClassLoaderObjectInputStream(InputStream input, ClassLoader classLoader) throws IOException {
            super(input);
            this.classLoader = classLoader;
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass streamClass) throws IOException, ClassNotFoundException {
            return Class.forName(streamClass.getName(), false, classLoader);
        }
    }
}
