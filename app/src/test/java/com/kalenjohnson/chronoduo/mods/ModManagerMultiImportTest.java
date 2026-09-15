package com.kalenjohnson.chronoduo.mods;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * JVM sanity check for {@link ModManager#extractZip}'s multi-archive-download
 * splitting (see its class doc) and the font-decryption path it drives (see
 * {@link ModManager#findFont}). Mimics a small slice of the "Chrono Trigger
 * Pixel Demaster" Nexus download's real layout: an outer wrapper folder
 * containing numbered option folders, each holding one nested {@code .ctp}
 * -- here just three (Main, a Font variant, a UI colour variant) instead of
 * the ~30 the real download has, to keep the test fast.
 *
 * <p>The font sub-mod's {@code .ctp} embeds a synthetic {@code string_2.bin}
 * built in {@link #buildSyntheticFont()} -- a minimal sfnt with a single
 * {@code EBLC} table carrying one 48-byte bitmapSizeTable at 10 ppem --
 * encrypted with the real {@link com.kalenjohnson.chronoduo.saveimport.CtContainer}
 * Blowfish/CBC scheme via {@link
 * com.kalenjohnson.chronoduo.saveimport.CtContainer#encryptFromPlaintext}, so
 * this test still exercises the real decrypt path end to end without
 * shipping a third-party font binary in the repo.
 */
public class ModManagerMultiImportTest {
    private File tmpRoot;
    private File modsRoot;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("modmanager-test").toFile();
        modsRoot = new File(tmpRoot, "mods");
        assertTrue(modsRoot.mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursive(tmpRoot);
    }

    // --- multi-archive download: splits into one mod per nested archive ---

    @Test
    public void multiArchiveDownloadSplitsIntoSubMods() throws Exception {
        byte[] syntheticFont = buildSyntheticFont();
        byte[] iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] fontBin = com.kalenjohnson.chronoduo.saveimport.CtContainer.encryptFromPlaintext(syntheticFont, iv);
        // Round-trip sanity: decrypt(encrypt(x)) == x, mirroring what
        // processFonts actually does to a real string_N.bin.
        assertArrayEquals(syntheticFont,
                com.kalenjohnson.chronoduo.saveimport.CtContainer.decryptToPlaintext(fontBin));

        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(outer)) {
            // Outer wrapper folder (peeled by findLogicalRoot, NOT included
            // in sub-mod names) around the numbered option-folder tree.
            writeCtpEntry(zos, "Pixel Demaster Test/1 - Main File/main.ctp",
                    ctpBytes("Extension/main.png", new byte[]{1, 2, 3}));
            // Font option: the nested .ctp's root holds string_2.bin, which
            // extractZip must decrypt to font.ttf and never register as an
            // archive substitution.
            ByteArrayOutputStream fontCtp = new ByteArrayOutputStream();
            try (ZipOutputStream fontZos = new ZipOutputStream(fontCtp)) {
                fontZos.putNextEntry(new ZipEntry("string_2.bin"));
                fontZos.write(fontBin);
                fontZos.closeEntry();
            }
            writeCtpEntry(zos, "Pixel Demaster Test/2 - Font/2.1 - SNES Font/font.ctp", fontCtp.toByteArray());
            writeCtpEntry(zos, "Pixel Demaster Test/3 - Interface/3.1 - UI/3.1.2 - Blue UI/ui.ctp",
                    ctpBytes("Extension/ui.png", new byte[]{4, 5, 6}));
            // A Steam-only text-button-prompt patch alongside a real option
            // -- must be deleted, not mistaken for a nested archive or an
            // asset substitution.
            zos.putNextEntry(new ZipEntry("Pixel Demaster Test/4 - Text Button Prompts/4.1 - Playstation/prompts.xdelta"));
            zos.write(new byte[]{9, 9, 9});
            zos.closeEntry();
        }

        ModManager.ImportResult result;
        try (InputStream in = new ByteArrayInputStream(outer.toByteArray())) {
            result = ModManager.extractZip(in, modsRoot, "Pixel Demaster Test");
        }

        assertEquals(3, result.modNames.size());
        assertTrue(result.modNames.contains("Pixel Demaster Test - Main File"));
        assertTrue(result.modNames.contains("Pixel Demaster Test - Font - SNES Font"));
        assertTrue(result.modNames.contains("Pixel Demaster Test - Interface - UI - Blue UI"));
        assertEquals(1, result.enabledCount);
        assertEquals("Pixel Demaster Test", result.downloadName);

        File mainDir = new File(modsRoot, "Pixel Demaster Test - Main File");
        File fontDir = new File(modsRoot, "Pixel Demaster Test - Font - SNES Font");
        File uiDir = new File(modsRoot, "Pixel Demaster Test - Interface - UI - Blue UI");
        assertTrue(mainDir.isDirectory());
        assertTrue(fontDir.isDirectory());
        assertTrue(uiDir.isDirectory());

        // Only the "Main File" sub-mod starts enabled.
        assertFalse(new File(mainDir, ".disabled").isFile());
        assertTrue(new File(fontDir, ".disabled").isFile());
        assertTrue(new File(uiDir, ".disabled").isFile());

        // .xdelta patches never survive extraction anywhere.
        assertNoFilesWithExtension(modsRoot, "xdelta");

        // .group records the download name in every sub-mod.
        assertEquals("Pixel Demaster Test", readAll(new File(mainDir, ".group")));
        assertEquals("Pixel Demaster Test", readAll(new File(fontDir, ".group")));
        assertEquals("Pixel Demaster Test", readAll(new File(uiDir, ".group")));

        // The font .bin was decrypted to font.ttf and deleted -- exactly the
        // synthetic plaintext built above, byte for byte.
        File fontTtf = new File(fontDir, "font.ttf");
        assertTrue(fontTtf.isFile());
        assertArrayEquals(syntheticFont, Files.readAllBytes(fontTtf.toPath()));
        byte[] head = new byte[4];
        try (InputStream in = Files.newInputStream(fontTtf.toPath())) {
            assertEquals(4, in.read(head));
        }
        assertEquals(0x00, head[0] & 0xFF);
        assertEquals(0x01, head[1] & 0xFF);
        assertEquals(0x00, head[2] & 0xFF);
        assertEquals(0x00, head[3] & 0xFF);
        assertFalse(new File(fontDir, "string_2.bin").isFile());

        // fontNativePpem: the synthetic font's EBLC bitmap strike is baked
        // at 10 ppem.
        assertEquals(10, ModManager.fontNativePpem(fontTtf));

        // collect() never registers a .bin as an archive substitution (the
        // font sub-mod is still disabled here, so this alone wouldn't
        // exercise walk()'s .ttf skip -- see below, after enabling it).
        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        for (String archivePath : scan.archivePaths) {
            assertFalse("unexpected substitution registered: " + archivePath,
                    archivePath.toLowerCase(java.util.Locale.ROOT).endsWith(".bin"));
        }
        // The enabled sub-mods' real assets ARE registered.
        assertTrue(java.util.Arrays.asList(scan.archivePaths).contains("Extension/main.png"));

        // findFont: the font sub-mod is disabled, so no font is active yet.
        assertNull(ModManager.findFont(modsRoot));

        // Enable it (mirrors ModManager#setEnabled without needing an
        // Android-backed instance) -- findFont now returns its font.ttf.
        assertTrue(new File(fontDir, ".disabled").delete());
        File found = ModManager.findFont(modsRoot);
        assertNotNull(found);
        assertEquals(fontTtf.getCanonicalPath(), found.getCanonicalPath());

        // Now that the font sub-mod is enabled, collect() walks it too --
        // font.ttf must still never be registered as an archive substitution.
        ModManager.ScanResult scanWithFontEnabled = ModManager.collect(modsRoot);
        for (String archivePath : scanWithFontEnabled.archivePaths) {
            String lower = archivePath.toLowerCase(java.util.Locale.ROOT);
            assertFalse("unexpected substitution registered: " + archivePath,
                    lower.endsWith(".ttf") || lower.endsWith(".bin"));
        }
    }

    // --- fontNativePpem: a normal (non-bitmap-strike) font returns 0 -------

    @Test
    public void fontNativePpemReturnsZeroForNonBitmapFont() throws Exception {
        // Minimal fake sfnt: 'OTTO' magic, numTables = 0 -- no EBLC table
        // to find, so fontNativePpem must fall back to 0 rather than throw.
        byte[] fake = new byte[12];
        fake[0] = 'O'; fake[1] = 'T'; fake[2] = 'T'; fake[3] = 'O';
        // numTables (uint16 at offset 4) left as 0; remaining header bytes unused.
        File fakeFont = new File(tmpRoot, "fake.otf");
        Files.write(fakeFont.toPath(), fake);

        assertEquals(0, ModManager.fontNativePpem(fakeFont));
    }

    // --- single nested archive: unchanged legacy one-mod-in-place behavior ---

    @Test
    public void singleArchiveDownloadStaysOneMod() throws Exception {
        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(outer)) {
            writeCtpEntry(zos, "SNESOverworld-9-1-1/SNESOverworld.ctp",
                    ctpBytes("Extension/overworld.png", new byte[]{7, 8, 9}));
        }

        ModManager.ImportResult result;
        try (InputStream in = new ByteArrayInputStream(outer.toByteArray())) {
            result = ModManager.extractZip(in, modsRoot, "SNES Overworld Sprites Restoration");
        }

        assertEquals(1, result.modNames.size());
        assertEquals("SNES Overworld Sprites Restoration", result.modNames.get(0));
        assertEquals(1, result.enabledCount);
        File modDir = new File(modsRoot, "SNES Overworld Sprites Restoration");
        assertTrue(modDir.isDirectory());
        assertFalse(new File(modDir, ".disabled").isFile());
        assertTrue(new File(modDir, "Extension/overworld.png").isFile());
        // No .group is written for the single-archive path (nothing to group).
        assertFalse(new File(modDir, ".group").isFile());
    }

    // --- helpers -------------------------------------------------------------

    private static void writeCtpEntry(ZipOutputStream zos, String path, byte[] ctpBytes) throws IOException {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(ctpBytes);
        zos.closeEntry();
    }

    private static byte[] ctpBytes(String innerPath, byte[] innerContent) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(innerPath));
            zos.write(innerContent);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    /**
     * A minimal sfnt with one {@code EBLC} table baked at 10 ppem -- just
     * enough for {@link ModManager#fontNativePpem} to recognize it, without
     * shipping a real (non-redistributable) font binary in the repo. Layout
     * mirrors the parser in {@link ModManager#fontNativePpem}'s doc:
     *
     * <pre>
     * offset 0:  sfnt header (12 bytes) -- version 00 01 00 00, numTables=1,
     *            searchRange/entrySelector/rangeShift (unused, left 0)
     * offset 12: one table record (16 bytes) -- tag "EBLC", checksum
     *            (unused), offset=28, length=56
     * offset 28: EBLC table (56 bytes) -- uint32 version 0x00020000,
     *            uint32 numSizes=1, one 48-byte bitmapSizeTable with
     *            ppemX (byte 44) and ppemY (byte 45) both 10
     * offset 84: 4 bytes of zero padding, to bring the total to 88 -- a
     *            multiple of 8, since CtContainer's Blowfish-CBC framing
     *            requires the plaintext length be block-aligned to survive
     *            an encrypt/decrypt round trip unchanged.
     * </pre>
     */
    private static byte[] buildSyntheticFont() {
        byte[] f = new byte[88];
        // sfnt header: version 0x00010000, numTables = 1.
        f[0] = 0x00; f[1] = 0x01; f[2] = 0x00; f[3] = 0x00;
        f[4] = 0x00; f[5] = 0x01; // numTables = 1
        // searchRange/entrySelector/rangeShift (bytes 6-11) left as 0.

        // Table record at offset 12: tag "EBLC", checksum (unused),
        // offset=28, length=56.
        f[12] = 'E'; f[13] = 'B'; f[14] = 'L'; f[15] = 'C';
        // checksum (bytes 16-19) left as 0.
        writeU32BE(f, 20, 28); // offset
        writeU32BE(f, 24, 56); // length

        // EBLC table at offset 28: version 0x00020000, numSizes = 1.
        writeU32BE(f, 28, 0x00020000);
        writeU32BE(f, 32, 1);
        // 48-byte bitmapSizeTable starts at offset 36; ppemX/ppemY at its
        // bytes 44/45, i.e. absolute offsets 36+44=80 and 36+45=81.
        f[80] = 10; // ppemX
        f[81] = 10; // ppemY
        // Remaining bytes (bitmapSizeTable's other fields, plus the 4-byte
        // tail pad to reach a multiple of 8) stay zero.
        return f;
    }

    private static void writeU32BE(byte[] a, int off, long v) {
        a[off] = (byte) ((v >>> 24) & 0xFF);
        a[off + 1] = (byte) ((v >>> 16) & 0xFF);
        a[off + 2] = (byte) ((v >>> 8) & 0xFF);
        a[off + 3] = (byte) (v & 0xFF);
    }

    private static String readAll(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void assertNoFilesWithExtension(File dir, String ext) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                assertNoFilesWithExtension(c, ext);
            } else {
                assertFalse("unexpected ." + ext + " file left behind: " + c,
                        c.getName().toLowerCase(java.util.Locale.ROOT).endsWith("." + ext));
            }
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
