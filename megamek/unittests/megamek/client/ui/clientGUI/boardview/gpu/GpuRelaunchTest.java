/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The relaunch command is the one that started this Java: its program, its JVM options, its class path, then its
 * main class and arguments. Windows only, where the command is read from Windows.
 */
class GpuRelaunchTest {
    @Test
    void theCommandIsTheOneThatStartedThisJava() {
        assumeTrue(GpuGraphicsCard.supported(), "only Windows relaunches");
        List<String> command = GpuRelaunch.command();
        assertNotNull(command);
        assertEquals(ProcessHandle.current().info().command().orElseThrow(), command.getFirst());
        int classPath = Math.max(command.indexOf("-cp"), command.indexOf("-classpath"));
        assertTrue(classPath > 0, "a class path: " + command);
        assertEquals(System.getProperty("java.class.path"), command.get(classPath + 1));
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (option.startsWith("-X") || option.startsWith("-D")) {
                assertTrue(command.subList(1, classPath).contains(option), option + " before the class path");
            }
        }
        // The main class and its arguments, as the launcher records them
        assertEquals(System.getProperty("sun.java.command"),
              String.join(" ", command.subList(classPath + 2, command.size())));
    }
}
