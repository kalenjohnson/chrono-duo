package com.kalenjohnson.chronoduo.saveimport;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives an SNES -&gt; ChronoDuo save import end to end: reads an .srm,
 * converts the chosen slot via {@link SaveConverter}, and installs the
 * result as a {@code Chrono_sp_<N>_0.dat} file plus an updated
 * {@code meta.bin}, per REPORT.md #6.2/#6.3 (facts confirmed live on
 * device, not just from the report).
 */
public final class SaveImporter {
    private SaveImporter() {
    }

    public static final int NUM_MENU_SLOTS = 20;
    private static final int SUSPEND_MENU_SLOT_FILE_NUMBER = 6;
    private static final int META_SLOT_COUNT = 23;

    /**
     * Menu slot N (0-based, 0..19) -&gt; {@code Chrono_sp_<n>_0.dat}, where
     * n = N+3, except N=3 maps to 23 (n=3+3=6 collides with the game's own
     * suspend slot, which must never be written).
     */
    public static String destinationFileName(int menuSlot) {
        if (menuSlot < 0 || menuSlot >= NUM_MENU_SLOTS) {
            throw new IllegalArgumentException("menu slot out of range: " + menuSlot);
        }
        int n = (menuSlot == 3) ? 23 : menuSlot + 3;
        if (n == SUSPEND_MENU_SLOT_FILE_NUMBER) {
            // Should be unreachable given the slot==3 special case above, but
            // guard explicitly since overwriting the suspend slot is unrecoverable.
            throw new IllegalStateException("refusing to write the suspend slot file");
        }
        return "Chrono_sp_" + n + "_0.dat";
    }

    public static boolean slotUsed(File saveDir, int menuSlot) {
        return new File(saveDir, destinationFileName(menuSlot)).isFile();
    }

    /** One-line summary, e.g. "Crono Lv14, Marle Lv13, Lucca Lv14 · 1579 G · 04:47". */
    public static String describeSlot(SnesSrm.SnesSlot slot) {
        StringBuilder party = new StringBuilder();
        for (byte pb : slot.party) {
            int id = pb & 0xFF;
            if (id >= 7) continue; // empty marker (0x80, and >=7 in general -- see tools/saves/README.md)
            if (party.length() > 0) party.append(", ");
            String name = slot.names[id];
            if (name == null || name.isEmpty()) name = SnesSrm.CHAR_ORDER[id];
            party.append(name).append(" Lv").append(slot.chars[id].level);
        }
        return party + " · " + slot.gold + " G · "
                + String.format("%02d:%02d", slot.playHours, slot.playMinutes);
    }

    /**
     * Converts {@code slot} and writes it into {@code destSlot} of
     * {@code saveDir}, atomically (temp file + rename), then updates
     * {@code meta.bin}'s slotInfos entry for {@code destSlot} to the current
     * time. Returns the chosen template's name (for logging/diagnostics).
     */
    public static String importSave(SnesSrm.SnesSlot slot, int destSlot, File saveDir,
                                     List<SaveConverter.TemplateCandidate> templates,
                                     SecureRandom random) throws IOException {
        if (slot == null) throw new IllegalArgumentException("slot is unused");
        SaveConverter.PickResult pick = SaveConverter.pickTemplate(slot.flags, templates);
        if (pick == null) throw new IllegalStateException("no save templates available");
        CtSave templateSave = CtSave.parse(pick.candidate.payload);
        CtSave result = SaveConverter.snesToCt(slot, templateSave);

        byte[] payload = result.serialize();
        byte[] fileBytes = CtContainer.encrypt(payload, random);

        String destName = destinationFileName(destSlot);
        writeAtomic(saveDir, destName, fileBytes);
        updateMetaSavedTime(saveDir, destSlot, random);
        return pick.candidate.name;
    }

    private static void writeAtomic(File dir, String name, byte[] bytes) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create save directory: " + dir);
        }
        File tmp = File.createTempFile(name, ".tmp", dir);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(bytes);
            fos.getFD().sync();
        }
        File dest = new File(dir, name);
        if (!tmp.renameTo(dest)) {
            // Cross-filesystem or existing-file rename failure on some platforms; fall back to
            // delete+rename.
            dest.delete();
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                throw new IOException("could not install " + name);
            }
        }
    }

    // --- meta.bin: same container around JSON {"slotInfos": [{"savedTime": "..."}, x23]} ---

    private static final Pattern SAVED_TIME_PATTERN =
            Pattern.compile("\"savedTime\"\\s*:\\s*\"([^\"]*)\"");

    static String[] parseSavedTimes(byte[] payload) {
        String json = new String(payload, StandardCharsets.UTF_8);
        Matcher m = SAVED_TIME_PATTERN.matcher(json);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group(1));
        return out.toArray(new String[0]);
    }

    static byte[] buildMetaPayload(String[] savedTimes) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"slotInfos\": [");
        for (int i = 0; i < savedTimes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("{\"savedTime\": \"").append(savedTimes[i]).append("\"}");
        }
        sb.append("]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void updateMetaSavedTime(File saveDir, int destSlot, SecureRandom random) throws IOException {
        File metaFile = new File(saveDir, "meta.bin");
        String[] savedTimes;
        if (metaFile.isFile()) {
            byte[] data = readAll(metaFile);
            String[] parsed;
            try {
                byte[] payload = CtContainer.decrypt(data);
                parsed = parseSavedTimes(payload);
            } catch (RuntimeException e) {
                parsed = null; // unreadable/corrupt meta.bin -- rebuild from scratch below
            }
            savedTimes = (parsed != null && parsed.length == META_SLOT_COUNT) ? parsed : defaultSavedTimes();
        } else {
            savedTimes = defaultSavedTimes();
        }
        savedTimes[destSlot] = Long.toString(System.currentTimeMillis() / 1000L);
        byte[] payload = buildMetaPayload(savedTimes);
        byte[] fileBytes = CtContainer.encrypt(payload, random);
        writeAtomic(saveDir, "meta.bin", fileBytes);
    }

    private static String[] defaultSavedTimes() {
        String[] out = new String[META_SLOT_COUNT];
        java.util.Arrays.fill(out, "0");
        return out;
    }

    private static byte[] readAll(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        return out.toByteArray();
    }
}
