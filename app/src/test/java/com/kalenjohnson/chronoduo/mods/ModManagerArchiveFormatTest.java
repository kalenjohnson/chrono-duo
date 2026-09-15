package com.kalenjohnson.chronoduo.mods;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * JVM coverage for the real-world-failure fix in {@link ModManager}'s class
 * doc: a Nexus download that's secretly a 7z (or RAR, or something else
 * entirely) served under a misleading name (e.g. {@code CT-FMV-2.bin`)
 * must never be silently routed through {@link java.util.zip.ZipInputStream}
 * -- see {@link ModManager#sniff}. Covers each magic {@link
 * ModManager#sniff} recognizes, the zero-entries rejection (an import must
 * never create/enable a mod directory when nothing usable was extracted),
 * and a real 7z round trip through {@link ModManager#importArchiveFile}
 * using a fixture built at test time with Commons Compress's own {@link
 * SevenZOutputFile} (no third-party binary checked into the repo).
 */
public class ModManagerArchiveFormatTest {
    private File tmpRoot;
    private File modsRoot;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("modmanager-format-test").toFile();
        modsRoot = new File(tmpRoot, "mods");
        assertTrue(modsRoot.mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursive(tmpRoot);
    }

    // --- sniff() magic bytes -------------------------------------------------

    @Test
    public void sniffsZip() {
        byte[] head = bytes(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0);
        assertEquals(ModManager.ArchiveFormat.ZIP, ModManager.sniff(head, head.length));
    }

    @Test
    public void sniffsEmptyZip() {
        byte[] head = bytes(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0);
        assertEquals(ModManager.ArchiveFormat.ZIP_EMPTY, ModManager.sniff(head, head.length));
    }

    @Test
    public void sniffsSevenZ() {
        byte[] head = bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0, 0);
        assertEquals(ModManager.ArchiveFormat.SEVEN_Z, ModManager.sniff(head, head.length));
    }

    @Test
    public void sniffsRar4() {
        byte[] head = bytes('R', 'a', 'r', '!', 0x1A, 0x07, 0x00, 0);
        assertEquals(ModManager.ArchiveFormat.RAR4, ModManager.sniff(head, head.length));
    }

    @Test
    public void sniffsRar5NotRar4() {
        byte[] head = bytes('R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00);
        assertEquals(ModManager.ArchiveFormat.RAR5, ModManager.sniff(head, head.length));
    }

    @Test
    public void sniffsGzip() {
        byte[] head = bytes(0x1F, 0x8B, 0, 0);
        assertEquals(ModManager.ArchiveFormat.GZIP, ModManager.sniff(head, head.length));
    }

    @Test
    public void sniffsTar() throws IOException {
        byte[] head = new byte[262];
        System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII), 0, head, 257, 5);
        assertEquals(ModManager.ArchiveFormat.TAR, ModManager.sniff(head, head.length));
    }

    @Test
    public void unknownForRandomBytes() {
        byte[] head = bytes(0x00, 0x01, 0x02, 0x03);
        assertEquals(ModManager.ArchiveFormat.UNKNOWN, ModManager.sniff(head, head.length));
    }

    private static byte[] bytes(int... vals) {
        byte[] b = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) b[i] = (byte) vals[i];
        return b;
    }

    // --- zero-entries rejection -----------------------------------------------

    @Test
    public void zipWithNoFilesIsRejectedAndCreatesNothing() throws IOException {
        byte[] zipBytes = buildZip(); // no entries at all
        try {
            ModManager.importArchive(new ByteArrayInputStream(zipBytes), "Empty Mod", modsRoot);
            fail("expected IOException for a zip with no files");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no files"));
        }
        assertNoModDirsCreated();
    }

    @Test
    public void zipWithOnlyDirectoriesIsRejected() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("Game/"));
            zos.closeEntry();
        }
        try {
            ModManager.importArchive(new ByteArrayInputStream(bos.toByteArray()), "DirsOnly", modsRoot);
            fail("expected IOException for a zip with only directory entries");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no files"));
        }
        assertNoModDirsCreated();
    }

    @Test
    public void unrecognizedFormatIsRejectedWithClearError() throws IOException {
        byte[] junk = bytes(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09);
        try {
            ModManager.importArchive(new ByteArrayInputStream(junk), "Junk", modsRoot);
            fail("expected IOException for unrecognized magic bytes");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("unrecognized"));
        }
        assertNoModDirsCreated();
    }

    @Test
    public void rar5IsRejectedWithNameedError() throws IOException {
        byte[] head = bytes('R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00);
        try {
            ModManager.importArchive(new ByteArrayInputStream(head), "SomeRar5Mod", modsRoot);
            fail("expected IOException for RAR5");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("RAR5"));
        }
        assertNoModDirsCreated();
    }

    private void assertNoModDirsCreated() {
        File[] children = modsRoot.listFiles();
        if (children == null) return;
        for (File c : children) {
            fail("no mod directory should have been created/left behind, found: " + c);
        }
    }

    // --- real 7z fixture, built at test time -----------------------------------

    @Test
    public void importsRealSevenZFixture() throws IOException {
        File sevenZFile = new File(tmpRoot, "fixture.7z");
        byte[] pngBytes = "not really a png, just some bytes".getBytes(StandardCharsets.UTF_8);
        byte[] txtBytes = "hello from a 7z mod".getBytes(StandardCharsets.UTF_8);
        try (SevenZOutputFile out = new SevenZOutputFile(sevenZFile)) {
            writeSevenZEntry(out, "Game/common/face.png", pngBytes);
            writeSevenZEntry(out, "Localize/en/msg/tech.txt", txtBytes);
        }

        // Sanity: this is a REAL 7z, not a zip -- sniff must say so.
        byte[] head = new byte[ModManager.SNIFF_HEADER_LEN];
        try (java.io.InputStream in = new java.io.FileInputStream(sevenZFile)) {
            int n = in.read(head);
            assertEquals(ModManager.ArchiveFormat.SEVEN_Z, ModManager.sniff(head, Math.max(n, 0)));
        }

        java.util.List<long[]> progressTicks = new java.util.ArrayList<>();
        ModManager.ImportResult result = ModManager.importArchiveFile(sevenZFile, modsRoot, "SevenZMod",
                (processed, total) -> progressTicks.add(new long[]{processed, total}));

        assertEquals(1, result.modNames.size());
        File modDir = result.finalDir;
        assertTrue(modDir.isDirectory());
        File png = new File(modDir, "Game/common/face.png");
        File txt = new File(modDir, "Localize/en/msg/tech.txt");
        assertTrue(png.isFile());
        assertTrue(txt.isFile());
        assertArrayEqualsBytes(pngBytes, Files.readAllBytes(png.toPath()));
        assertArrayEqualsBytes(txtBytes, Files.readAllBytes(txt.toPath()));
        assertTrue("expected at least one progress tick", !progressTicks.isEmpty());
    }

    private static void writeSevenZEntry(SevenZOutputFile out, String name, byte[] data) throws IOException {
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName(name);
        entry.setDirectory(false);
        entry.setHasStream(true);
        out.putArchiveEntry(entry);
        out.write(data);
        out.closeArchiveEntry();
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        org.junit.Assert.assertArrayEquals(expected, actual);
    }

    private static byte[] buildZip(String... unused) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            // deliberately no entries
        }
        return bos.toByteArray();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }
}
