/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import megamek.logging.MMLogger;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.windows.WinBase;

/**
 * Starts the running program again once it has quit, as it was started: the same Java, JVM options, class path, main
 * class and arguments, in the same folder and environment. Windows only, the one system with a graphics card choice
 * ({@link GpuGraphicsCard#cards}). Java knows no process's arguments there ({@code ProcessHandle.Info#arguments} is
 * empty), so the command is the process's own command line as Windows keeps it, split as Windows splits it: whatever
 * started the program, launch4j's {@code MegaMek.exe} (its {@code javaw} line with the {@code .l4j.ini} options),
 * Gradle's {@code run} or another program's launcher, starts it again.
 */
final class GpuRelaunch {
    private static final MMLogger LOGGER = MMLogger.create(GpuRelaunch.class);

    private GpuRelaunch() { }

    /**
     * Starts the program again once it has quit, then runs {@code quit}, the program's own Quit, which saves every
     * preference before the new start reads them. False, with nothing done, when there's no command to start.
     */
    static boolean afterQuit(Runnable quit) {
        List<String> command = command();
        if (command == null) { return false; }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> start(command), "Relaunch"));
        quit.run();
        return true;
    }

    /**
     * This process's command line, its program the executable's full path; null on other systems than Windows or when
     * Windows doesn't give it.
     */
    static List<String> command() {
        if (!GpuGraphicsCard.supported()) { return null; }
        long shell = WinBase.LoadLibrary("shell32.dll");
        if (shell == 0) { return null; }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long kernel = WinBase.GetModuleHandle("kernel32.dll");
            long line = WinBase.GetProcAddress(kernel, "GetCommandLineW");
            long split = WinBase.GetProcAddress(shell, "CommandLineToArgvW");
            long free = WinBase.GetProcAddress(kernel, "LocalFree");
            if (line == 0 || split == 0 || free == 0) { return null; }
            var count = stack.mallocInt(1);
            long arguments = JNI.callPPP(JNI.callP(line), MemoryUtil.memAddress(count), split);
            if (arguments == 0) { return null; }
            List<String> command = new ArrayList<>();
            try {
                for (int index = 0; index < count.get(0); index++) {
                    long argument = MemoryUtil.memGetAddress(arguments + (long) index * Pointer.POINTER_SIZE);
                    command.add(MemoryUtil.memUTF16(argument));
                }
            } finally {
                JNI.callPP(arguments, free);
            }
            if (command.isEmpty()) { return null; }
            ProcessHandle.current().info().command().ifPresent(program -> command.set(0, program));
            return command;
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn(failure, "Could not read the command that started this program");
            return null;
        } finally {
            WinBase.FreeLibrary(shell);
        }
    }

    private static void start(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(new File(System.getProperty("user.dir")))
              .inheritIO();
        // Java's copy of the environment is the one the program started with: it lacks the card this run asked for
        // in the process's own (GpuGraphicsCard#apply), which the new start asks for itself
        builder.environment();
        try {
            builder.start();
        } catch (IOException failure) {
            LOGGER.error(failure, "Could not start the program again: {}", command);
        }
    }
}
