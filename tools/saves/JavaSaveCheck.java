import com.kalenjohnson.chronoduo.saveimport.CtContainer;
import com.kalenjohnson.chronoduo.saveimport.CtSave;
import com.kalenjohnson.chronoduo.saveimport.DsSav;
import com.kalenjohnson.chronoduo.saveimport.SaveConverter;
import com.kalenjohnson.chronoduo.saveimport.SnesSrm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Desktop parity checker (no Android deps) for the Java port of
 * tools/saves/{ctcrypto,snes_srm,ctsave,convert}.py -- see REPORT.md and
 * app/src/main/java/com/kalenjohnson/chronoduo/saveimport/.
 *
 * Checks two things:
 *  1. Slot-conversion parity: for every used slot of every snes/**\/*.srm
 *     and every used slot of every ds/*.dst, ds/*.duc DS save, picks the
 *     same nearest-flags template the Python convert.py would (recomputed
 *     here, from the same steam/*.bin candidates) and compares the
 *     serialized payload byte-for-byte against a Python reference dump
 *     (see manifest file below -- produced by a one-off helper script that
 *     imports tools/saves' own modules and calls
 *     pick_template/snes_to_ct/ds_to_ct directly, so the reference is the
 *     exact same code path convert.py uses on the command line). Manifest
 *     lines are told apart by the save file's extension: {@code .srm} is
 *     SNES, anything else (.dst/.duc/.dsv/.sav) is DS.
 *  2. Template round-trip parity: for every steam/*.bin template, decrypt
 *     -> CtSave.parse -> CtSave.serialize -> re-encrypt with the ORIGINAL
 *     IV must reproduce the original file bytes exactly (the reference here
 *     is simply the original file -- no Python needed, since this only
 *     tests that the Java container/stream model round-trips losslessly).
 *
 * Usage (run from tools/saves, with the app sources on the classpath):
 *   javac -d /tmp/svclasses \
 *       ../../app/src/main/java/com/kalenjohnson/chronoduo/saveimport/*.java \
 *       JavaSaveCheck.java
 *   java -cp /tmp/svclasses JavaSaveCheck <manifest.tsv> <refDir>
 *
 * <manifest.tsv> lines are "<srmRelPath>\t<slotIndex>\t<templateBasename>\t<distance>\t<refPayloadFile>"
 * (relative to tools/saves and <refDir> respectively) -- see the
 * generate_refs.py helper used to produce them (not checked in; a
 * throwaway script run against the Python reference).
 */
public class JavaSaveCheck {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: JavaSaveCheck <manifest.tsv> <refDir>");
            System.exit(2);
        }
        File manifestFile = new File(args[0]);
        File refDir = new File(args[1]);
        File savesDir = new File("."); // run from tools/saves
        File steamDir = new File(savesDir, "steam");

        // --- Load every steam/[0-9]*.bin template once, sorted by filename
        // (matches Python's sorted(glob.glob("steam/[0-9]*.bin"))). ---
        List<SaveConverter.TemplateCandidate> templates = new ArrayList<>();
        List<File> templateFiles = new ArrayList<>();
        File[] steamFiles = steamDir.listFiles((dir, name) -> name.matches("[0-9].*\\.bin"));
        if (steamFiles == null) throw new IOException("no steam/ dir found -- run from tools/saves");
        Arrays.sort(steamFiles, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : steamFiles) {
            templateFiles.add(f);
            byte[] data = Files.readAllBytes(f.toPath());
            byte[] payload = CtContainer.decrypt(data);
            templates.add(new SaveConverter.TemplateCandidate(f.getName(), payload));
        }
        System.out.println("Loaded " + templates.size() + " templates from " + steamDir);

        // --- 1. Slot-conversion parity (SNES + DS) ---
        int slotChecked = 0, slotPass = 0;
        int snesChecked = 0, dsChecked = 0;
        List<String> slotFailures = new ArrayList<>();
        for (String line : Files.readAllLines(manifestFile.toPath())) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t");
            String saveRel = parts[0];
            int slotIndex = Integer.parseInt(parts[1]);
            String expectedTemplateName = parts[2];
            int expectedDist = Integer.parseInt(parts[3]);
            String refFileName = parts[4];
            boolean isDs = !saveRel.toLowerCase(java.util.Locale.ROOT).endsWith(".srm");

            byte[] fileData = Files.readAllBytes(new File(savesDir, saveRel).toPath());
            byte[] flags;
            CtSave result;
            slotChecked++;
            boolean ok = true;

            if (isDs) {
                dsChecked++;
                DsSav.DsSlot[] dsSlots = DsSav.parseFile(fileData);
                DsSav.DsSlot slot = dsSlots[slotIndex];
                if (slot == null || !slot.used) {
                    slotFailures.add(saveRel + " slot " + slotIndex + ": slot is unused in Java parse (manifest expected used)");
                    continue;
                }
                flags = slot.flags;
                SaveConverter.PickResult pick = SaveConverter.pickTemplate(flags, templates);
                if (!pick.candidate.name.equals(expectedTemplateName) || pick.distance != expectedDist) {
                    ok = false;
                    slotFailures.add(saveRel + " slot " + slotIndex + ": template mismatch (Java picked "
                            + pick.candidate.name + " dist " + pick.distance + ", expected " + expectedTemplateName
                            + " dist " + expectedDist + ")");
                }
                CtSave templateSave = CtSave.parse(pick.candidate.payload);
                result = SaveConverter.dsToCt(slot, templateSave);
            } else {
                snesChecked++;
                SnesSrm.SrmFile srm = SnesSrm.parseSrm(fileData);
                SnesSrm.SnesSlot slot = srm.slots[slotIndex];
                if (slot == null) {
                    slotFailures.add(saveRel + " slot " + slotIndex + ": slot is unused in Java parse (manifest expected used)");
                    continue;
                }
                int computedChecksum = SnesSrm.slotChecksum(slot.raw);
                if (computedChecksum != srm.checksums[slotIndex]) {
                    slotFailures.add(saveRel + " slot " + slotIndex + ": checksum mismatch (stored "
                            + Integer.toHexString(srm.checksums[slotIndex]) + ", computed " + Integer.toHexString(computedChecksum) + ")");
                }
                flags = slot.flags;
                SaveConverter.PickResult pick = SaveConverter.pickTemplate(flags, templates);
                if (!pick.candidate.name.equals(expectedTemplateName) || pick.distance != expectedDist) {
                    ok = false;
                    slotFailures.add(saveRel + " slot " + slotIndex + ": template mismatch (Java picked "
                            + pick.candidate.name + " dist " + pick.distance + ", expected " + expectedTemplateName
                            + " dist " + expectedDist + ")");
                }
                CtSave templateSave = CtSave.parse(pick.candidate.payload);
                result = SaveConverter.snesToCt(slot, templateSave);
            }

            byte[] javaPayload = result.serialize();
            byte[] refPayload = Files.readAllBytes(new File(refDir, refFileName).toPath());
            if (!Arrays.equals(javaPayload, refPayload)) {
                ok = false;
                int firstDiff = firstDifference(javaPayload, refPayload);
                slotFailures.add(saveRel + " slot " + slotIndex + ": payload mismatch (java " + javaPayload.length
                        + " bytes, ref " + refPayload.length + " bytes, first diff at " + firstDiff + ")");
            }
            if (ok) slotPass++;
        }

        // --- 2. Template round-trip parity ---
        int tplChecked = 0, tplPass = 0;
        List<String> tplFailures = new ArrayList<>();
        for (File f : templateFiles) {
            tplChecked++;
            byte[] original = Files.readAllBytes(f.toPath());
            byte[] iv = new byte[8];
            // header = iv xor magic; recover iv the same way CtContainer does internally by
            // re-deriving it from the known magic (CtContainer doesn't expose the header, so
            // just re-decrypt via decryptToPlaintext and reuse the original header bytes as IV
            // input to encryptFromPlaintext -- IV is XORed with the same magic on both sides,
            // so passing the on-disk header through unchanged as "iv" and letting
            // encryptFromPlaintext XOR it against the magic again reconstructs the original IV).
            byte[] magic = {0x75, (byte) 0xFA, 0x29, (byte) 0x95, 0x05, 0x4D, 0x41, 0x5F};
            for (int i = 0; i < 8; i++) iv[i] = (byte) (original[i] ^ magic[i]);

            byte[] plaintext = CtContainer.decryptToPlaintext(original);
            long n = readU32LE(plaintext, plaintext.length - 4);
            byte[] payload = Arrays.copyOfRange(plaintext, 0, (int) n);
            byte[] trailer = Arrays.copyOfRange(plaintext, (int) n, plaintext.length);

            CtSave parsed = CtSave.parse(payload);
            byte[] reserialized = parsed.serialize();

            boolean ok = true;
            if (!Arrays.equals(reserialized, payload)) {
                ok = false;
                int d = firstDifference(reserialized, payload);
                tplFailures.add(f.getName() + ": CtSave round-trip payload mismatch (first diff at " + d + ")");
            } else {
                byte[] rebuiltPlaintext = new byte[reserialized.length + trailer.length];
                System.arraycopy(reserialized, 0, rebuiltPlaintext, 0, reserialized.length);
                System.arraycopy(trailer, 0, rebuiltPlaintext, reserialized.length, trailer.length);
                byte[] reencrypted = CtContainer.encryptFromPlaintext(rebuiltPlaintext, iv);
                if (!Arrays.equals(reencrypted, original)) {
                    ok = false;
                    int d = firstDifference(reencrypted, original);
                    tplFailures.add(f.getName() + ": container round-trip mismatch (java " + reencrypted.length
                            + " bytes, original " + original.length + " bytes, first diff at " + d + ")");
                }
            }
            if (ok) tplPass++;
        }

        System.out.println();
        System.out.println("=== Slot-conversion parity ===");
        System.out.println(slotPass + "/" + slotChecked + " slots passed (" + snesChecked + " SNES, " + dsChecked + " DS)");
        for (String f : slotFailures) System.out.println("  FAIL " + f);

        System.out.println();
        System.out.println("=== Template round-trip parity ===");
        System.out.println(tplPass + "/" + tplChecked + " templates passed");
        for (String f : tplFailures) System.out.println("  FAIL " + f);

        boolean allPass = slotFailures.isEmpty() && tplFailures.isEmpty() && slotChecked > 0 && tplChecked > 0;
        System.out.println();
        System.out.println(allPass ? "ALL CHECKS PASSED" : "CHECKS FAILED");
        System.exit(allPass ? 0 : 1);
    }

    private static int firstDifference(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) return i;
        }
        return a.length == b.length ? -1 : n;
    }

    private static long readU32LE(byte[] a, int off) {
        return (a[off] & 0xFFL) | ((a[off + 1] & 0xFFL) << 8)
                | ((a[off + 2] & 0xFFL) << 16) | ((a[off + 3] & 0xFFL) << 24);
    }
}
