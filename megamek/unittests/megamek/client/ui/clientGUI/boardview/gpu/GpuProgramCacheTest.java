/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Program binary entries: their names, damaged files and pruning, without a GL context. */
class GpuProgramCacheTest {
    private static final String DRIVER = "Intel\nIntel(R) Iris(R) Xe Graphics\n4.6.0 - Build 32.0.101.7088";
    private static final int FORMAT = 0x8E21;
    @TempDir Path directory;

    @Test
    void keyNamesTheDriverBuildAndBothCompleteStages() {
        String key = GpuProgramCache.key(DRIVER, "vertex", "fragment");
        assertTrue(key.matches("[0-9a-f]{64}"), key);
        assertEquals(key, GpuProgramCache.key(DRIVER, "vertex", "fragment"));
        assertNotEquals(key, GpuProgramCache.key(DRIVER.replace("7088", "7089"), "vertex", "fragment"),
              "A driver update compiles again");
        assertNotEquals(key, GpuProgramCache.key(DRIVER, "vertex ", "fragment"));
        assertNotEquals(key, GpuProgramCache.key(DRIVER, "vertex", "fragment "));
        assertNotEquals(GpuProgramCache.key(DRIVER, "ab", "c"), GpuProgramCache.key(DRIVER, "a", "bc"),
              "Moving text between the stages is another program");
    }

    @Test
    void entriesReadBackAndUnusableOnesAreDeleted() throws IOException {
        var cache = new GpuProgramCache(directory, DRIVER, FORMAT);
        byte[] binary = { 1, 2, 3, 4, 5, 6, 7, 8 };
        String key = GpuProgramCache.key(DRIVER, "vertex", "fragment");
        assertNull(cache.read(key), "Nothing is stored yet");
        assertTrue(cache.write(key, FORMAT, binary));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(key + ".bin"), files.map(file -> file.getFileName().toString()).toList(),
                  "The partial file became the entry");
        }
        Path file = directory.resolve(key + ".bin");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(3))));
        var entry = cache.read(key);
        assertEquals(FORMAT, entry.format());
        assertArrayEquals(binary, entry.data());
        assertTrue(Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(Duration.ofMinutes(1))),
              "Reading an entry marks it as used");

        byte[] stored = Files.readAllBytes(file);
        stored[stored.length - 1] ^= 1;
        Files.write(file, stored);
        assertNull(cache.read(key), "A flipped bit fails the checksum");
        assertFalse(Files.exists(file), "A damaged entry is deleted");

        assertTrue(cache.write(key, FORMAT, binary));
        stored = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(stored, stored.length - 3));
        assertNull(cache.read(key), "A truncated entry is unusable");
        assertFalse(Files.exists(file));

        assertTrue(cache.write(key, FORMAT + 1, binary));
        assertNull(cache.read(key), "A format this driver does not offer is unusable");
        assertFalse(Files.exists(file));
        assertEquals(3, cache.discarded.get());
    }

    @Test
    void pruningDropsUnusedEntriesThenTheLeastRecentlyUsedBeyondTheCapacity() throws IOException {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        Path recent = file('1', ".bin", 100, now.minus(Duration.ofHours(1)));
        Path older = file('2', ".bin", 100, now.minus(Duration.ofDays(2)));
        Path oldest = file('3', ".bin", 100, now.minus(Duration.ofDays(3)));
        Path expired = file('4', ".bin", 10, now.minus(Duration.ofDays(31)));
        Path abandoned = file('5', "-7.tmp", 10, now.minus(Duration.ofDays(2)));
        Path writing = file('6', "-8.tmp", 10, now.minus(Duration.ofMinutes(1)));
        Path foreign = directory.resolve("notes.txt");
        Files.writeString(foreign, "kept");
        Files.setLastModifiedTime(foreign, FileTime.from(now.minus(Duration.ofDays(400))));

        GpuProgramCache.prune(directory, now, Duration.ofDays(30), 350);
        assertFalse(Files.exists(expired), "An entry unused for 31 days expires, though it would fit");
        assertTrue(Files.exists(recent) && Files.exists(older) && Files.exists(oldest));
        assertFalse(Files.exists(abandoned), "A day-old partial write is abandoned");
        assertTrue(Files.exists(writing), "Another MegaMek may still be writing");
        assertTrue(Files.exists(foreign), "Other files are left alone");

        GpuProgramCache.prune(directory, now, Duration.ofDays(30), 250);
        assertTrue(Files.exists(recent) && Files.exists(older), "The most recently used entries fit");
        assertFalse(Files.exists(oldest), "The least recently used entry exceeds the capacity");
    }

    @Test
    void intelsCacheIsFullOnceOneOfItsFilesReachesTheCap() throws IOException {
        assertFalse(GpuProgramCache.full(directory.resolve("absent"), 16), "A missing folder is not full");
        assertFalse(GpuProgramCache.full(directory, 16), "Nor is an empty one");
        Files.write(directory.resolve("small"), new byte[15]);
        Files.write(directory.resolve("other"), new byte[15]);
        assertFalse(GpuProgramCache.full(directory, 16), "Files below the cap, even together over it");
        Files.write(directory.resolve("capped"), new byte[16]);
        assertTrue(GpuProgramCache.full(directory, 16), "One file at the cap");
        assertTrue(Files.exists(directory.resolve("capped")), "Checking changes nothing");
    }

    /** A file named like an entry of the digit, with the suffix, size and last use. */
    private Path file(char digit, String suffix, int size, Instant used) throws IOException {
        Path file = directory.resolve(String.valueOf(digit).repeat(64) + suffix);
        Files.write(file, new byte[size]);
        Files.setLastModifiedTime(file, FileTime.from(used));
        return file;
    }
}
