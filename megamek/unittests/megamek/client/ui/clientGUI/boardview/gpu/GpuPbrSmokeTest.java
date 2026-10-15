/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Independent numerical integrals of the production BRDF and the production standalone GPU environment LUT. */
@Tag("on-demand")
class GpuPbrSmokeTest {
    private static final int WIDTH = 8, HEIGHT = 6;
    private static final String PROBE = """
          uniform int u_mode;
          uniform vec2 u_probe;
          layout(location=0) out vec4 fragColor;
          void main() {
              vec3 n = vec3(0,0,1);
              float nv = (floor(gl_FragCoord.x) + .5) / 8.0;
              float rough = (floor(gl_FragCoord.y) + .5) / 6.0;
              vec3 v = normalize(vec3(sqrt(1.0-nv*nv),0,nv));
              if (u_mode == 1) {
                  vec3 direction = normalize(vec3(sqrt(max(0.0, 1.0-u_probe.x*u_probe.x)),0,u_probe.x));
                  fragColor = vec4(environmentRadiance(direction,u_probe.y,vec3(.1),vec3(.8)),1);
                  return;
              }
              if (u_mode == 9) {
                  // A one-ULP length excess represents the rounding envelope of normalized float directions.
                  // Keep it deliberate: normalizing this probe again could hide the very boundary under test.
                  vec3 eye=vec3(0,0,u_probe.x), light=n, f0=vec3(0,.04,1);
                  float r=.25+.1*floor(gl_FragCoord.y), a2=r*r*r*r;
                  vec3 point=pointReflectance(n,light,eye,r,f0);
                  vec3 filtered=surfaceReflectance(n,light,eye,r,f0);
                  float s=u_probe.x, radius2=.25*u_directionalRadiusSine*u_directionalRadiusSine;
                  float pointExpected=.04*.5/(a2*(sqrt(s*s*(1.0-a2)+a2)+s));
                  vec2 axes2=a2+vec2(radius2,radius2/(s*s+radius2));
                  float filteredExpected=.04/(4.0*s*sqrt(axes2.x*axes2.y))
                        *directionalNormalization(s,r,f0).g;
                  bool valid=!any(isnan(point))&&!any(isinf(point))&&!any(isnan(filtered))&&!any(isinf(filtered))
                        &&all(greaterThanEqual(point,vec3(0)))&&all(greaterThanEqual(filtered,vec3(0)))
                        &&point.r==0.0&&filtered.r==0.0;
                  float overshoot=(dot(eye,normalize(light+eye))-1.0)*8388608.0;
                  fragColor=vec4(point.g/pointExpected,filtered.g/filteredExpected,overshoot,valid?1.0:0.0);
                  return;
              }
              if (u_mode == 6) {
                  // Both arguments must have the same horizon behavior, including angles below the former
                  // view-only .0001 floor. The visibility denominator already has a symmetric finite guard.
                  float edge = (floor(gl_FragCoord.x)+.5)*.00001;
                  float nl = .15 + floor(gl_FragCoord.y)*.14;
                  vec3 eye = normalize(vec3(sqrt(1.0-edge*edge),0,edge));
                  vec3 light = normalize(vec3(vec2(-.8,.6)*sqrt(1.0-nl*nl),nl));
                  float forward = (diffuseReflectance(n,light,eye,rough,vec3(.04)).r
                        + pointReflectance(n,light,eye,rough,vec3(.04)).r)/nl;
                  float reverse = (diffuseReflectance(n,eye,light,rough,vec3(.04)).r
                        + pointReflectance(n,eye,light,rough,vec3(.04)).r)/edge;
                  float residual = abs(forward-reverse)/max(1.0,max(abs(forward),abs(reverse)));
                  fragColor = vec4(residual,forward,reverse,edge);
                  return;
              }
              if (u_mode == 7) {
                  // Opposite near-horizon rays have H=N exactly. A normalization floor could shorten H and
                  // inflate both directions equally, passing reciprocity while violating the analytic GGX value.
                  float edge = (floor(gl_FragCoord.x)+.5)*.00001;
                  float r = .35 + floor(gl_FragCoord.y)*.1;
                  vec3 eye = normalize(vec3(sqrt(1.0-edge*edge),0,edge));
                  vec3 light = normalize(vec3(-eye.x,0,edge));
                  float a2 = r*r*r*r;
                  float fresnel = .04 + .96*pow(1.0-edge,5.0);
                  float expected = fresnel/(4.0*a2*sqrt(edge*edge*(1.0-a2)+a2));
                  float forward = pointReflectance(n,light,eye,r,vec3(.04)).r;
                  float reverse = pointReflectance(n,eye,light,r,vec3(.04)).r;
                  fragColor = vec4(forward/expected,reverse/expected,
                        abs(forward-reverse)/max(1.0,max(abs(forward),abs(reverse))),edge);
                  return;
              }
              vec3 energy = vec3(0);
              float reciprocal = 0.0;
              vec3 reciprocalPair = vec3(0);
              vec3 reciprocalLight = vec3(0);
              vec4 reciprocalTerms = vec4(0);
              const int samples = 4096;
              // Golden-angle quadrature differs from the table's Hammersley sequence and uses four times as
              // many samples. Integrate diffuse by cosine sampling and specular by its GGX sampling density.
              for (int i=0; i<samples; i++) {
                  float u = (float(i)+.5)/float(samples);
                  float phi = 6.28318530718*fract((float(i)+.5)*.61803398875);
                  // Independent GPU sin/cos approximations need not preserve sin²+cos² exactly. BRDF inputs
                  // follow the production unit-vector contract, not an assumption about those approximations.
                  vec3 l = normalize(vec3(sqrt(u)*cos(phi),sqrt(u)*sin(phi),sqrt(1.0-u)));
                  vec3 d = diffuseReflectance(n,l,v,rough,vec3(.04));
                  energy.r += d.r / l.z;
                  vec3 sf = pointReflectance(n,l,v,rough,vec3(.04));
                  vec3 sr = pointReflectance(n,v,l,rough,vec3(.04));
                  vec3 rd = diffuseReflectance(n,v,l,rough,vec3(.04));
                  vec3 forward = (d + sf) / l.z;
                  vec3 reverse = (rd + sr) / nv;
                  // Compare the BRDF itself after removing NoL. A specular density can exceed 1000 near a
                  // grazing mirror direction: its absolute float error is not an energy or reciprocity error.
                  float difference = abs(forward.r-reverse.r);
                  float residual = difference / max(1.0,max(abs(forward.r),abs(reverse.r)));
                  if (residual > reciprocal) {
                      reciprocal = residual;
                      reciprocalPair = vec3(forward.r,reverse.r,difference);
                      reciprocalLight = l;
                      reciprocalTerms = vec4(sf.r/l.z,sr.r/nv,d.r/l.z,rd.r/nv);
                  }
                  float a2 = rough*rough*rough*rough;
                  float hz = sqrt((1.0-u)/(1.0+(a2-1.0)*u));
                  vec3 h = normalize(vec3(sqrt(1.0-hz*hz)*cos(phi),sqrt(1.0-hz*hz)*sin(phi),hz));
                  l = normalize(reflect(-v,h));
                  float vh = dot(v,h);
                  if (l.z > 0.0 && vh > 0.0) {
                      float den = dot(h.xy,h.xy)+a2*h.z*h.z;
                      float distribution = a2/(3.14159265359*den*den);
                      float pdf = distribution*h.z/(4.0*vh);
                      vec3 specular = pointReflectance(n,l,v,rough,vec3(.04,1,0));
                      energy.rg += specular.rg / (3.14159265359*pdf);
                  }
              }
              energy /= float(samples);
              float e = specularAlbedo(nv,rough,vec3(.04)).r;
              float furnace = environmentSpecular(n,v,rough,vec3(.04),vec3(1),vec3(1)).r
                    + environmentRadiance(n,1.0,vec3(1),vec3(1)).r*(1.0-e);
              if (u_mode == 4) fragColor = vec4(reciprocalLight,rough);
              else if (u_mode == 5) fragColor = reciprocalTerms;
              else fragColor = u_mode == 3 ? vec4(reciprocalPair,reciprocal) : vec4(energy.r,energy.g,reciprocal,furnace);
          }
          """;

    // Keep the historical method selector used by native runners; the obsolete array route is gone.
    @Test
    void coupledBrdfConservesEnergyAndEnvironmentArrayMatchesStandaloneLookup() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { check(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("PBR numerical integration", failure.get()); }
    }

    private static void check() {
        var environment = new GpuEnvironmentLighting();
        var target = GpuHeatGlow.buffer(WIDTH, HEIGHT, false);
        var quad = GpuAtmosphere.screenQuad();
        ShaderProgram ordinary = probe();
        try {
            ordinary.bind(); ordinary.setUniformi("u_environmentLut", 0);
            environment.texture().bind(0);
            float[] integrated = draw(target, quad, ordinary, 0, 0, 0, 1, 1);
            float[] reciprocity = draw(target, quad, ordinary, 3, 0, 0, 1, 1);
            float[] directions = draw(target, quad, ordinary, 4, 0, 0, 1, 1);
            float[] terms = draw(target, quad, ordinary, 5, 0, 0, 1, 1);
            for (int i = 0; i < WIDTH * HEIGHT; i++) {
                assertEquals(1, integrated[i * 4], .012,
                      "White dielectric direct integral must close its energy budget at pixel " + i);
                assertTrue(integrated[i * 4 + 1] >= 0 && integrated[i * 4 + 1] <= 1.012,
                      "Single-scattering conductor cannot create energy at pixel " + i);
                assertTrue(integrated[i * 4 + 2] < .00002,
                      "The coupled direct BRDF must be reciprocal at pixel " + i
                            + "; maximum normalized residual=" + integrated[i * 4 + 2]
                            + ", forward=" + reciprocity[i * 4] + ", reverse=" + reciprocity[i * 4 + 1]
                            + ", absolute difference=" + reciprocity[i * 4 + 2]
                            + ", L=" + directions[i * 4] + "," + directions[i * 4 + 1] + "," + directions[i * 4 + 2]
                            + ", specular pair=" + terms[i * 4] + "," + terms[i * 4 + 1]
                            + ", diffuse pair=" + terms[i * 4 + 2] + "," + terms[i * 4 + 3]);
                assertEquals(1, integrated[i * 4 + 3], .001,
                      "A constant white environment must conserve dielectric energy at pixel " + i);
            }
            float[] horizon = draw(target, quad, ordinary, 6, 0, 0, 1, 1);
            for (int i = 0; i < WIDTH * HEIGHT; i++) {
                assertTrue(horizon[i * 4] < .00002,
                      "Near-horizon reciprocity at pixel " + i + ": residual=" + horizon[i * 4]
                            + ", forward=" + horizon[i * 4 + 1] + ", reverse=" + horizon[i * 4 + 2]
                            + ", NoV=" + horizon[i * 4 + 3]);
            }
            float[] opposing = draw(target, quad, ordinary, 7, 0, 0, 1, 1);
            for (int i = 0; i < WIDTH * HEIGHT; i++) {
                assertEquals(1, opposing[i * 4], .001,
                      "Opposing horizon rays must retain a unit half vector at pixel " + i);
                assertEquals(1, opposing[i * 4 + 1], .001,
                      "Swapped opposing rays must match analytic GGX at pixel " + i);
                assertTrue(opposing[i * 4 + 2] < .00002, "Opposing horizon rays must be reciprocal at pixel " + i);
            }
            ordinary.bind();
            ordinary.setUniformf("u_directionalRadiusSine", GpuEnvironmentLighting.SOURCE_RADIUS_SINE);
            float[] rounded = draw(target, quad, ordinary, 9, Math.nextUp(1f), 0, 1, 1);
            for (int i = 0; i < WIDTH * HEIGHT; i++) {
                assertEquals(1, rounded[i * 4 + 2], 0, "The Schlick probe must exercise a one-ULP cosine overshoot");
                assertEquals(1, rounded[i * 4 + 3], 0,
                      "Both direct BRDF paths must stay finite and nonnegative at a rounded normal direction");
                assertEquals(1, rounded[i * 4], .001, "Point reflection must retain the aligned analytic response");
                assertEquals(1, rounded[i * 4 + 1], .001,
                      "Finite-source reflection must retain the aligned analytic response");
            }
            for (float z : new float[] { -1, -.4f, .1f, .5f, 1 }) {
                float expected = z > 0 ? .2f + .6f * (float) Math.sqrt(z) : .1f;
                float[] mirror = draw(target, quad, ordinary, 1, z, 0, .8f, .2f);
                assertEquals(expected, mirror[0], .002, "Mirror limit must sample the actual sky/ground gradient");
            }
            float[] diffuse = draw(target, quad, ordinary, 1, 1, 1, .8f, .2f);
            assertEquals(.68, diffuse[0], .002, "Cosine sky integral is .2*horizon+.8*zenith");
            float[] broad = draw(target, quad, ordinary, 1, 0, 1, .8f, .2f);
            assertTrue(broad[0] > .1 && broad[0] < .68, "Rough grazing reflection must integrate sky and ground");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            ordinary.dispose(); quad.dispose(); target.dispose(); environment.dispose();
        }
    }

    private static ShaderProgram probe() {
        String prefix = "#define environmentLightingFlag\n";
        return GpuGlsl.compile("PBR integration probe", prefix, GpuShaderSource.read("atmosphere.vert"),
              "#version 330 core\n" + GpuShaderSource.read("light-model.glsl")
                    + GpuShaderSource.read("environment-lighting.glsl") + PROBE);
    }

    private static float[] draw(FrameBuffer target, Mesh quad, ShaderProgram shader, int mode, float z, float roughness,
          float sky, float horizon) {
        target.begin();
        Gdx.gl.glDisable(GL20.GL_BLEND); Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        shader.bind(); shader.setUniformi("u_mode", mode); shader.setUniformf("u_probe", z, roughness);
        shader.setUniformf("u_environmentSky", sky, sky, sky);
        shader.setUniformf("u_environmentHorizon", horizon, horizon, horizon);
        quad.render(shader, GL20.GL_TRIANGLES);
        var pixels = BufferUtils.newFloatBuffer(WIDTH * HEIGHT * 4);
        Gdx.gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
        float[] result = new float[WIDTH * HEIGHT * 4]; pixels.get(result); target.end(); return result;
    }
}
