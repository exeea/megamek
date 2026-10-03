/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import megamek.common.board.Board;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.Weather;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * macOS's OpenGL gives each shader stage only 16 texture units, and a program that uses more fails to link there, so
 * the board would not draw at all. Windows and Linux drivers allow 32, so nothing else catches an overrun before a Mac
 * user does. Render the battle view over boards and conditions that reach every terrain, liquid and weather variant,
 * and count the samplers each compiled program actually uses.
 */
@Tag("on-demand")
class GpuSamplerBudgetSmokeTest {
    /** Apple's OpenGL 4.1 limit per stage; counting a whole program against it is conservative. */
    private static final int MAC_TEXTURE_UNITS = 16;
    private static final Set<Integer> SAMPLERS = Set.of(0x8B5E, 0x8B5F, 0x8B60, 0x8B62, 0x8B63, 0x8DC1, 0x8DC2, 0x8DC4,
          0x8DC5, 0x8DCA, 0x8DCB, 0x8DCC, 0x8DCF, 0x8DD0, 0x8DD2, 0x8DD3, 0x8DD4, 0x8DD7, 0x8DD8, 0x9108, 0x9109,
          0x910A, 0x910B, 0x910C, 0x910D);

    private record Scene(String board, Weather weather, Light light, Fog fog) { }

    private static final List<Scene> SCENES = List.of(
          new Scene("Map Pack Volcanic/16x17 Dome Vent 2.board", Weather.CLEAR, Light.DAY, Fog.FOG_NONE),
          new Scene("Map Pack Volcanic/16x17 Dome Vent 2.board", Weather.HEAVY_RAIN, Light.MOONLESS, Fog.FOG_NONE),
          new Scene("Map Pack Savannahs/16x17 Mountain Lake (Savannah).board", Weather.MOD_RAIN, Light.DAY, Fog.FOG_LIGHT),
          new Scene("unofficial/Drewbacca/16x17 Fire And Ice 2.board", Weather.HEAVY_SNOW, Light.DAY, Fog.FOG_NONE),
          new Scene("unofficial/Vamp/Elevated Highway.board", Weather.CLEAR, Light.DUSK_DAWN, Fog.FOG_NONE),
          new Scene("unofficial/Drewbacca/16x17 Jungle River 1.board", Weather.CLEAR, Light.DAY, Fog.FOG_NONE),
          new Scene("Shrapnel 13/16x17 Badlands 2 (Hazardous Liquid).board", Weather.CLEAR, Light.DAY, Fog.FOG_NONE));

    @Test
    void everyBattleViewProgramFitsMacTextureUnits() throws Exception {
        var over = new TreeMap<String, Integer>();
        var used = new TreeMap<String, Integer>();
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    for (Scene scene : SCENES) { review(scene, used, over); }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Sampler budget review", failure.get()); }
        // The headroom left for new maps: every variant with the units it uses, the fullest first.
        used.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
              .forEach(program -> System.out.println(program.getValue() + " " + program.getKey()));
        assertTrue(used.keySet().stream().anyMatch(program -> program.contains("volcanicFlag")),
              "Reach the volcanic terrain programs: " + used.keySet());
        assertTrue(over.isEmpty(), "Programs over macOS's " + MAC_TEXTURE_UNITS + " texture units: " + over);
    }

    private static void review(Scene scene, Map<String, Integer> used, Map<String, Integer> over) throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/" + scene.board()));
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                var conditions = fixture.game.getPlanetaryConditions();
                conditions.setWeather(scene.weather());
                conditions.setLight(scene.light());
                conditions.setFog(scene.fog());
                fixture.source.refresh();
            });
            var view = new GpuBattleView(fixture.source);
            try {
                view.create();
                // The board arrives asynchronously: wait for its terrain, then for the loading screen to go.
                GpuCamouflageReview.renderReady(view);
                GpuBoardTestUi.present(view);
                for (int frame = 0; frame < 20; frame++) { view.render(); }
                scan(scene, used, over);
            } finally {
                view.dispose();
            }
        } catch (Throwable error) {
            throw new AssertionError(scene.toString(), error);
        }
    }

    @SuppressWarnings("unchecked")
    private static void scan(Scene scene, Map<String, Integer> used, Map<String, Integer> over) throws Exception {
        var registry = ShaderProgram.class.getDeclaredField("shaders");
        registry.setAccessible(true);
        var programs = new ArrayList<ShaderProgram>();
        for (Array<ShaderProgram> list : ((ObjectMap<?, Array<ShaderProgram>>) registry.get(null)).values()) {
            for (ShaderProgram program : list) { programs.add(program); }
        }
        assertTrue(programs.size() > 5, "The battle view compiled its programs");
        System.out.println(scene + ": " + programs.size() + " live programs");
        IntBuffer count = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
        IntBuffer size = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
        IntBuffer type = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
        for (ShaderProgram program : programs) {
            if (!program.isCompiled()) { continue; }
            Gdx.gl20.glGetProgramiv(program.getHandle(), GL20.GL_ACTIVE_UNIFORMS, count);
            int units = 0;
            var names = new TreeSet<String>();
            for (int i = 0; i < count.get(0); i++) {
                size.clear();
                type.clear();
                String name = Gdx.gl20.glGetActiveUniform(program.getHandle(), i, size, type);
                if (SAMPLERS.contains(type.get(0))) {
                    units += size.get(0);
                    names.add(name);
                }
            }
            String label = (program instanceof GpuShaderUniforms named ? named.name : "libGDX") + " " + flags(program)
                  + " " + names;
            used.merge(label, units, Math::max);
            if (units > MAC_TEXTURE_UNITS) { over.merge(label + " in " + scene, units, Math::max); }
        }
    }

    /** The variant's feature flags, which identify a program together with the samplers it reads. */
    private static String flags(ShaderProgram program) {
        var flags = new TreeSet<String>();
        for (String line : (program.getVertexShaderSource() + "\n" + program.getFragmentShaderSource()).split("\n")) {
            if (line.startsWith("#define ") && line.trim().endsWith("Flag")) { flags.add(line.substring(8).trim()); }
        }
        return flags.toString();
    }
}
