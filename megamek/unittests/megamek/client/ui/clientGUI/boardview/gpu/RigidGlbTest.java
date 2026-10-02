/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.badlogic.gdx.files.FileHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file/CPU boundary, independently encoded with interleaved attributes and normalized byte colors. */
class RigidGlbTest {
    @TempDir
    Path directory;

    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode document() throws Exception {
        return (ObjectNode) json.readTree("""
              {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
               "nodes":[{"name":"root","children":[1]},
                        {"name":"arm","mesh":0,"translation":[2,3,4],"scale":[1,2,3],
                         "rotation":[0,0.70710677,0,0.70710677]}],
               "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"COLOR_0":2,"TEXCOORD_0":3},
                           "indices":4,"material":0}]}],
               "materials":[{"name":"paint","pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1],
                              "metallicFactor":0,"roughnessFactor":1}}],
               "buffers":[{"byteLength":116}],
               "bufferViews":[{"buffer":0,"byteLength":108,"byteStride":36},
                              {"buffer":0,"byteOffset":108,"byteLength":6}],
               "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                            {"bufferView":0,"byteOffset":12,"componentType":5126,"count":3,"type":"VEC3"},
                            {"bufferView":0,"byteOffset":24,"componentType":5121,"normalized":true,"count":3,"type":"VEC4"},
                            {"bufferView":0,"byteOffset":28,"componentType":5126,"count":3,"type":"VEC2"},
                            {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}]}
              """);
    }

    private FileHandle file(ObjectNode document, boolean invalidIndex) throws Exception {
        return file(document, invalidIndex, new byte[0]);
    }

    FileHandle file(ObjectNode document, boolean invalidIndex, byte[] image) throws Exception {
        int binaryLength = (116 + image.length + 3) & ~3;
        ((ObjectNode) document.get("buffers").get(0)).put("byteLength", 116 + image.length);
        byte[] text = json.writeValueAsString(document).getBytes(StandardCharsets.UTF_8);
        int padded = (text.length + 3) & ~3;
        ByteBuffer bytes = ByteBuffer.allocate(28 + padded + binaryLength).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x46546c67).putInt(2).putInt(bytes.capacity()).putInt(padded).putInt(0x4e4f534a).put(text);
        while (bytes.position() < 20 + padded) { bytes.put((byte) ' '); }
        bytes.putInt(binaryLength).putInt(0x004e4942);
        for (int i = 0; i < 3; i++) {
            bytes.putFloat(i == 1 ? 2 : 1).putFloat(2).putFloat(i == 2 ? 2 : 3);
            bytes.putFloat(0).putFloat(1).putFloat(0);
            bytes.put((byte) 128).put((byte) 255).put((byte) 0).put((byte) 255);
            bytes.putFloat(.25f).putFloat(.75f);
        }
        bytes.putShort((short) 0).putShort((short) 1).putShort((short) (invalidIndex ? 3 : 2));
        bytes.position(28 + padded + 116);
        bytes.put(image);
        Path file = directory.resolve("mesh.glb");
        Files.write(file, bytes.array());
        return new FileHandle(file.toFile());
    }

    @Test
    void loadsEmptySquadWithoutAllocatingAnEmptyMesh() throws Exception {
        byte[] text = """
              {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
               "nodes":[{"name":"squad-0-lod0","children":[1]},{"name":"root"}]}
              """.getBytes(StandardCharsets.UTF_8);
        int padded = (text.length + 3) & ~3;
        ByteBuffer bytes = ByteBuffer.allocate(20 + padded).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x46546c67).putInt(2).putInt(bytes.capacity()).putInt(padded).putInt(0x4e4f534a).put(text);
        while (bytes.hasRemaining()) { bytes.put((byte) ' '); }
        Path path = directory.resolve("squad-0.glb");
        Files.write(path, bytes.array());
        var levels = RigidGlb.loadLods(new FileHandle(path.toFile()));
        assertEquals(0, levels.getFirst().meshes.size);
        assertEquals("root", levels.getFirst().nodes.first().id);
        assertSame(levels.get(0), levels.get(1));
        assertSame(levels.get(0), levels.get(2));
    }

    @Test
    void preservesRigidHierarchyWhileConvertingAxesAndLinearColorsWithoutOpenGl() throws Exception {
        var data = RigidGlb.load(file(document(), false));
        var node = data.nodes.first().children[0];
        assertEquals("arm", node.id);
        assertEquals("paint", node.parts[0].materialId);
        assertEquals(2, node.translation.x);
        assertEquals(-4, node.translation.y);
        assertEquals(3, node.translation.z);
        assertEquals(3, node.scale.y);
        assertEquals(2, node.scale.z);
        assertEquals(.70710677f, node.rotation.z, 1e-6);
        float[] vertices = data.meshes.first().vertices;
        assertEquals(1, vertices[0]);
        assertEquals(-3, vertices[1]);
        assertEquals(2, vertices[2]);
        assertEquals(1, vertices[5]);
        assertEquals(.73664694f, vertices[6], 1e-6);
        assertEquals(1, vertices[7]);
        assertEquals(0, vertices[8]);
        assertEquals(.25f, vertices[10]);
        assertEquals(.75f, vertices[11]);
        assertEquals(3, data.meshes.first().parts[0].indices.length);
    }

    @Test
    void rejectsOutOfRangeIndicesBeforeGraphicsAllocation() throws Exception {
        var file = file(document(), true);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(file));
    }

    @Test
    void refusesExternalBuffersAndUnsupportedRequiredExtensions() throws Exception {
        var external = document();
        ((ObjectNode) external.get("buffers").get(0)).put("uri", "https://invalid.example/mesh.bin");
        var file = file(external, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(file));
        var compressed = document();
        compressed.putArray("extensionsRequired").add("KHR_draco_mesh_compression");
        var compressedFile = file(compressed, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(compressedFile));
    }

    FileHandle sided(boolean doubleSided) throws Exception {
        var document = document();
        ((ObjectNode) document.get("materials").get(0)).put("doubleSided", doubleSided);
        var node = (ObjectNode) document.get("nodes").get(1);
        node.remove(java.util.List.of("translation", "rotation", "scale"));
        return file(document, false);
    }

    @Test
    void doubleSidedMaterialsPreservePaintAndReverseWindingAndNormals() throws Exception {
        for (boolean indexed : new boolean[] { true, false }) {
            var document = document();
            ((ObjectNode) document.get("materials").get(0)).put("doubleSided", true);
            if (!indexed) { ((ObjectNode) document.get("meshes").get(0).get("primitives").get(0)).remove("indices"); }
            var mesh = RigidGlb.load(file(document, false)).meshes.first();
            var indices = mesh.parts[0].indices;
            assertEquals(6, indices.length);
            for (int corner = 0; corner < 3; corner++) {
                int front = Short.toUnsignedInt(indices[corner]) * RigidGlb.STRIDE;
                int back = Short.toUnsignedInt(indices[5 - corner]) * RigidGlb.STRIDE;
                for (int attribute = 0; attribute < RigidGlb.STRIDE; attribute++) {
                    float expected = mesh.vertices[front + attribute];
                    if (attribute >= 3 && attribute < 6) { expected = -expected; }
                    assertEquals(expected, mesh.vertices[back + attribute], .00001f,
                          "Reverse faces preserve position, vertex colour and UV, and flip only the normal");
                }
            }
        }
    }

    @Test
    void mixedMaterialsKeepSingleSidedFacesAndShareReverseVertices() throws Exception {
        var document = document();
        var materials = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("materials");
        materials.addObject().put("name", "two-sided").put("doubleSided", true);
        var primitives = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("meshes").get(0).get("primitives");
        primitives.add(primitives.get(0).deepCopy());
        primitives.add(primitives.get(0).deepCopy());
        ((ObjectNode) primitives.get(1)).put("material", 1);
        ((ObjectNode) primitives.get(2)).put("material", 1);
        var mesh = RigidGlb.load(file(document, false)).meshes.first();
        assertArrayEquals(new short[] { 0, 1, 2 }, mesh.parts[0].indices);
        assertEquals(6, mesh.parts[1].indices.length);
        assertArrayEquals(mesh.parts[1].indices, mesh.parts[2].indices);
        assertEquals(6 * RigidGlb.STRIDE, mesh.vertices.length, "Shared attributes need only one reverse vertex set");
        assertEquals(3 * RigidGlb.STRIDE, RigidGlb.load(sided(false)).meshes.first().vertices.length);
    }

    @Test
    void doubleSidedDoesNotEnableUnsupportedAlphaModes() throws Exception {
        for (String mode : new String[] { "BLEND", "MASK" }) {
            var document = document();
            ((ObjectNode) document.get("materials").get(0)).put("doubleSided", true).put("alphaMode", mode);
            var file = file(document, false);
            assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(file));
        }
    }

    private ObjectNode levels() throws Exception {
        var document = document();
        var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("nodes");
        var meshes = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("meshes");
        meshes.add(meshes.get(0).deepCopy());
        ((ObjectNode) meshes.get(0).get("primitives").get(0)).putObject("extras").put("mmPart", "arm-paint");
        ((ObjectNode) meshes.get(1).get("primitives").get(0)).putObject("extras").put("mmPart", "arm-paint");
        nodes.addObject().put("name", "root").putArray("children").add(3);
        var arm = nodes.get(1).deepCopy();
        ((ObjectNode) arm).put("mesh", 1);
        nodes.add(arm);
        nodes.addObject().put("name", "mesh-lod0").putArray("children").add(0);
        nodes.addObject().put("name", "mesh-lod2").putArray("children").add(2);
        ((ObjectNode) document.get("scenes").get(0)).putArray("nodes").add(4).add(5);
        return document;
    }

    @Test
    void isolatesLevelsWithRepeatedJointAndPartNamesAndResolvesMissingLevelsOnce() throws Exception {
        var levels = RigidGlb.loadLods(file(levels(), false));
        assertSame(levels.get(0), levels.get(1), "Missing LOD1 reuses LOD0 data");
        assertNotSame(levels.get(1), levels.get(2), "An authored LOD2 remains available after a gap");
        for (var level : levels) {
            assertEquals("root", level.nodes.first().id, "The packaging group is stripped from the rig");
            assertEquals("arm", level.nodes.first().children[0].id);
            assertEquals("arm-paint", level.nodes.first().children[0].parts[0].meshPartId);
            assertEquals(1, level.meshes.first().parts.length, "Other levels must not inflate this level's budget");
            assertEquals(1, UnitModelDescriptor.triangleCount(level));
        }
    }

    @Test
    void requiresLod0AndIdentityPackagingGroups() throws Exception {
        var absent = levels();
        ((ObjectNode) absent.get("nodes").get(4)).put("name", "mesh-lod1");
        var missing = file(absent, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.loadLods(missing));
        var moved = levels();
        ((ObjectNode) moved.get("nodes").get(4)).putArray("translation").add(0).add(1).add(0);
        var translated = file(moved, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.loadLods(translated));
    }

    @Test
    void refusesAFileWhoseLevelsAreNotNamedGroups() throws Exception {
        // The same geometry as the grouped file, but without its <name>-lodN packaging groups.
        var ungrouped = file(document(), false);
        var error = assertThrows(IllegalArgumentException.class, () -> RigidGlb.loadLods(ungrouped));
        assertTrue(error.getMessage().contains("-lod0"), error.getMessage());
        // The single-level reader used for kits still reads it.
        assertEquals("arm", RigidGlb.load(ungrouped).nodes.first().children[0].id);
    }

    @Test
    void resolvesSharedLocalTextureWithoutAllocatingItAndRejectsRemoteImages() throws Exception {
        var textured = document();
        textured.putArray("images").addObject().put("uri", "foliage.png");
        textured.putArray("textures").addObject().put("source", 0);
        ((ObjectNode) textured.get("materials").get(0).get("pbrMetallicRoughness"))
              .putObject("baseColorTexture").put("index", 0);
        Files.write(directory.resolve("foliage.png"), encodedImage("png"));
        var data = RigidGlb.load(file(textured, false));
        assertEquals(directory.resolve("foliage.png").toString(), data.materials.first().textures.first().fileName);
        ((ObjectNode) textured.get("images").get(0)).put("uri", "https://invalid.example/foliage.png");
        var remote = file(textured, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(remote));
    }

    static byte[] encodedImage(String format) throws Exception {
        var pixels = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        pixels.setRGB(0, 0, 0x669933);
        pixels.setRGB(1, 1, 0x3366cc);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(pixels, format, bytes);
        return bytes.toByteArray();
    }

    FileHandle embedded(String format) throws Exception {
        byte[] bytes = encodedImage(format);
        var document = levels();
        int view = document.get("bufferViews").size();
        ((com.fasterxml.jackson.databind.node.ArrayNode) document.get("bufferViews")).addObject()
              .put("buffer", 0).put("byteOffset", 116).put("byteLength", bytes.length);
        document.putArray("images").addObject().put("bufferView", view)
              .put("mimeType", format.equals("png") ? "image/png" : "image/jpeg");
        document.putArray("samplers").addObject().put("wrapS", 33071).put("wrapT", 33648)
              .put("minFilter", 9987).put("magFilter", 9728);
        document.putArray("textures").addObject().put("source", 0).put("sampler", 0);
        ((ObjectNode) document.get("materials").get(0).get("pbrMetallicRoughness"))
              .putObject("baseColorTexture").put("index", 0);
        return file(document, false, bytes);
    }

    @Test
    void importsEmbeddedPngAndJpegAndSharesImageSourcesAcrossLevels() throws Exception {
        for (String format : new String[] { "png", "jpg" }) {
            var levels = RigidGlb.loadLods(embedded(format));
            var lod0 = (RigidGlb.Data) levels.getFirst();
            var lod2 = (RigidGlb.Data) levels.get(2);
            var image = lod0.images.values().iterator().next();
            assertArrayEquals(encodedImage(format), image.encoded());
            assertSame(image, lod2.images.get(image.key()));
            assertEquals(33071, image.wrapS());
            assertEquals(33648, image.wrapT());
            assertEquals(9728, image.magFilter());
        }
    }
}
