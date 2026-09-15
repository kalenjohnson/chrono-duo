package com.kalenjohnson.chronoduo.mods;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * JVM round-trip test for {@link ArchiveOverlayImporter} (the "a mod ships a
 * whole replacement resources.bin" fix -- e.g. "Orchestral Wonders", a 508
 * MiB full ARC1 repack -- see {@link ModManager}'s class doc's real-world
 * failures). Builds two small synthetic ARC1 archives with {@link
 * Arc1TestWriter} (a from-scratch writer mirroring {@link
 * ArchiveOverlayImporter}'s reader byte-for-byte: same magic/header layout,
 * same XOR stream cipher keyed by absolute offset, same gzip'd table/entry
 * regions -- see that class's doc for the exact format) and overlays a
 * "mod" archive against a "game" archive covering all three outcomes in one
 * pass: an entry present in both with different content ({@code changed}),
 * one present in both with identical content ({@code identical} -- must NOT
 * be written), and one present only in the mod ({@code added}).
 */
public class ArchiveOverlayImporterTest {
    private File tmpRoot;
    private File modDir;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("overlay-importer-test").toFile();
        modDir = new File(tmpRoot, "SomeMod");
        assertTrue(modDir.mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursive(tmpRoot);
    }

    @Test
    public void overlayWritesAddedAndChangedButSkipsIdentical() throws IOException {
        LinkedHashMap<String, byte[]> gameEntries = new LinkedHashMap<>();
        gameEntries.put("Game/common/A.dat", "AAAA".getBytes(StandardCharsets.US_ASCII));
        gameEntries.put("Game/common/B.dat", "shared-unchanged-bytes".getBytes(StandardCharsets.US_ASCII));

        LinkedHashMap<String, byte[]> modEntries = new LinkedHashMap<>();
        modEntries.put("Game/common/A.dat", "BBBB".getBytes(StandardCharsets.US_ASCII)); // same size, different bytes -> changed
        modEntries.put("Game/common/B.dat", "shared-unchanged-bytes".getBytes(StandardCharsets.US_ASCII)); // identical -> skipped
        modEntries.put("Game/common/C.dat", "brand new entry only in the mod".getBytes(StandardCharsets.US_ASCII)); // added

        File gameArchiveFile = new File(tmpRoot, "game_resources.bin");
        Arc1TestWriter.write(gameArchiveFile, gameEntries);

        // The mod's resources.bin sits inside a wrapper folder that should
        // become empty (and get removed) once it's overlaid away.
        File wrapper = new File(modDir, "OrchestralWonders");
        assertTrue(wrapper.mkdirs());
        File modResourcesBin = new File(wrapper, "resources.bin");
        Arc1TestWriter.write(modResourcesBin, modEntries);

        List<int[]> progressTicks = new ArrayList<>();
        try (ArchiveOverlayImporter.FileRegionSource gameSource =
                     new ArchiveOverlayImporter.FileRegionSource(gameArchiveFile)) {
            ArchiveOverlayImporter.Result result = ArchiveOverlayImporter.overlay(
                    modResourcesBin, gameSource, modDir, (done, total) -> progressTicks.add(new int[]{done, total}));

            assertEquals(3, result.total);
            assertEquals(1, result.added);
            assertEquals(1, result.changed);
            assertEquals(1, result.identical);
        }

        File writtenA = new File(modDir, "Game/common/A.dat");
        File writtenB = new File(modDir, "Game/common/B.dat");
        File writtenC = new File(modDir, "Game/common/C.dat");
        assertTrue("changed entry should be written", writtenA.isFile());
        assertFalse("identical entry should NOT be written", writtenB.isFile());
        assertTrue("added entry should be written", writtenC.isFile());
        assertArrayEquals(modEntries.get("Game/common/A.dat"), Files.readAllBytes(writtenA.toPath()));
        assertArrayEquals(modEntries.get("Game/common/C.dat"), Files.readAllBytes(writtenC.toPath()));

        assertFalse("resources.bin should be deleted after overlay", modResourcesBin.exists());
        assertFalse("empty wrapper folder should be removed", wrapper.exists());

        File marker = new File(modDir, ".from-archive");
        assertTrue(marker.isFile());
        String markerText = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8);
        assertTrue(markerText.contains("total=3"));
        assertTrue(markerText.contains("added=1"));
        assertTrue(markerText.contains("changed=1"));
        assertTrue(markerText.contains("identical=1"));

        assertTrue("expected at least one progress tick", !progressTicks.isEmpty());
    }

    @Test
    public void overlayHandlesEmptyEntry() throws IOException {
        // decodeEntry returns early for uncompLen == 0 without touching gzip
        // at all -- exercise that path on both sides via a zero-length entry.
        LinkedHashMap<String, byte[]> gameEntries = new LinkedHashMap<>();
        gameEntries.put("Game/common/empty.dat", new byte[0]);
        LinkedHashMap<String, byte[]> modEntries = new LinkedHashMap<>();
        modEntries.put("Game/common/empty.dat", new byte[0]);

        File gameArchiveFile = new File(tmpRoot, "game2.bin");
        Arc1TestWriter.write(gameArchiveFile, gameEntries);
        File modResourcesBin = new File(modDir, "resources.bin");
        Arc1TestWriter.write(modResourcesBin, modEntries);

        try (ArchiveOverlayImporter.FileRegionSource gameSource =
                     new ArchiveOverlayImporter.FileRegionSource(gameArchiveFile)) {
            ArchiveOverlayImporter.Result result = ArchiveOverlayImporter.overlay(modResourcesBin, gameSource, modDir, null);
            assertEquals(1, result.total);
            assertEquals(1, result.identical);
            assertEquals(0, result.added);
            assertEquals(0, result.changed);
        }
        assertFalse(new File(modDir, "Game/common/empty.dat").exists());
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }

    /**
     * From-scratch ARC1 writer, built purely for this test: encodes the
     * SAME container {@link ArchiveOverlayImporter}'s reader (itself
     * mirroring {@code ChronoResources}) decodes -- header magic/offsets,
     * XOR-obfuscated gzip'd table, XOR-obfuscated gzip'd (length-prefixed)
     * entries. Reuses {@link ArchiveOverlayImporter#xorDecode} directly
     * (package-private) since the cipher is self-inverse: applying it to
     * plaintext IS the encoding step.
     */
    static final class Arc1TestWriter {
        private Arc1TestWriter() {}

        static void write(File out, LinkedHashMap<String, byte[]> entries) throws IOException {
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            file.write(new byte[16]); // header placeholder, patched at the end

            List<String> names = new ArrayList<>(entries.keySet());
            List<long[]> tableInfo = new ArrayList<>(names.size()); // {entryOffset, entryRegionLen}
            for (String name : names) {
                long entryOffset = file.size();
                byte[] region = buildEntryRegion(entries.get(name), entryOffset);
                file.write(region);
                tableInfo.add(new long[]{entryOffset, region.length});
            }

            long headerOffset = file.size();
            byte[] tableRegion = buildTableRegion(names, tableInfo, headerOffset);
            file.write(tableRegion);

            byte[] result = file.toByteArray();
            // The 16-byte header is itself an XOR-obfuscated region (keyed at
            // offset 0) -- see ArchiveOverlayImporter#parseTable's very
            // first readRegion call -- so it must be encoded the same way
            // every other region here is, not written as plain bytes.
            byte[] header = new byte[16];
            header[0] = 'A'; header[1] = 'R'; header[2] = 'C'; header[3] = '1';
            writeU32LE(header, 8, headerOffset);
            writeU32LE(header, 12, tableRegion.length);
            ArchiveOverlayImporter.xorDecode(header, 0);
            System.arraycopy(header, 0, result, 0, 16);

            try (OutputStream os = new FileOutputStream(out)) {
                os.write(result);
            }
        }

        /** {@code u32BE uncompressedLen ‖ gzip(plaintext)}, XOR-obfuscated at {@code offset} -- empty entries (uncompLen==0) skip the gzip payload entirely, mirroring {@link ArchiveOverlayImporter#decodeEntry}'s early return. */
        private static byte[] buildEntryRegion(byte[] plaintext, long offset) throws IOException {
            byte[] region;
            if (plaintext.length == 0) {
                region = new byte[4]; // all-zero uncompressed-length prefix, no payload
            } else {
                byte[] gz = gzip(plaintext);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeU32BE(b, plaintext.length);
                b.write(gz);
                region = b.toByteArray();
            }
            ArchiveOverlayImporter.xorDecode(region, offset);
            return region;
        }

        private static byte[] buildTableRegion(List<String> names, List<long[]> tableInfo, long headerOffset)
                throws IOException {
            ByteArrayOutputStream namePool = new ByteArrayOutputStream();
            long[] namePoolOffsets = new long[names.size()];
            for (int i = 0; i < names.size(); i++) {
                namePoolOffsets[i] = namePool.size();
                byte[] nameBytes = names.get(i).getBytes(StandardCharsets.UTF_8);
                namePool.write(nameBytes);
                namePool.write(0);
            }

            int headerArraySize = 4 + names.size() * 12; // entryCount u32 + 12 bytes/entry
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeU32LE(body, names.size());
            for (int i = 0; i < names.size(); i++) {
                writeU32LE(body, headerArraySize + namePoolOffsets[i]); // nameOff (absolute within `inflated`)
                writeU32LE(body, tableInfo.get(i)[0]); // entryOff
                writeU32LE(body, tableInfo.get(i)[1]); // entryLen (the on-disk region length, i.e. compressed+prefix)
            }
            body.write(namePool.toByteArray());
            byte[] inflated = body.toByteArray();

            byte[] gz = gzip(inflated);
            ByteArrayOutputStream region = new ByteArrayOutputStream();
            writeU32BE(region, inflated.length);
            region.write(gz);
            byte[] regionBytes = region.toByteArray();
            ArchiveOverlayImporter.xorDecode(regionBytes, headerOffset);
            return regionBytes;
        }

        private static byte[] gzip(byte[] data) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(data);
            }
            return bos.toByteArray();
        }

        private static void writeU32LE(ByteArrayOutputStream out, long v) {
            out.write((int) (v & 0xFF));
            out.write((int) ((v >>> 8) & 0xFF));
            out.write((int) ((v >>> 16) & 0xFF));
            out.write((int) ((v >>> 24) & 0xFF));
        }

        private static void writeU32LE(byte[] b, int off, long v) {
            b[off] = (byte) (v & 0xFF);
            b[off + 1] = (byte) ((v >>> 8) & 0xFF);
            b[off + 2] = (byte) ((v >>> 16) & 0xFF);
            b[off + 3] = (byte) ((v >>> 24) & 0xFF);
        }

        private static void writeU32BE(ByteArrayOutputStream out, long v) {
            out.write((int) ((v >>> 24) & 0xFF));
            out.write((int) ((v >>> 16) & 0xFF));
            out.write((int) ((v >>> 8) & 0xFF));
            out.write((int) (v & 0xFF));
        }
    }
}
