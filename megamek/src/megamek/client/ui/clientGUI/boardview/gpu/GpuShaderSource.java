/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import megamek.common.Configuration;

/** Uncached shader reads: installation overrides, checkout resources, then the bundled defaults. */
final class GpuShaderSource {
    private static final String RESOURCES = "megamek/client/ui/clientGUI/boardview/gpu/";

    private GpuShaderSource() { }

    static String read(String name) {
        FileHandle override = new FileHandle(new File(Configuration.dataDir(), "shaders/" + name));
        if (override.exists()) { return override.readString("UTF-8"); }
        // Gradle runs in the megamek subproject; IDE launches may run from the repository root.
        for (String root : new String[] { "resources/", "megamek/resources/" }) {
            FileHandle source = Gdx.files.local(root + RESOURCES + name);
            if (source.exists()) { return source.readString("UTF-8"); }
        }
        return Gdx.files.classpath(RESOURCES + name).readString("UTF-8");
    }
}
