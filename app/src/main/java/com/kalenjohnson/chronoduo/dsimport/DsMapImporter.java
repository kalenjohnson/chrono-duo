package com.kalenjohnson.chronoduo.dsimport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Top-level ROM -> assets importer. Two independent passes, mirroring the
 * two Python reference scripts:
 *  1) render_all.py's area-map loop: enumerate menu/bg/minimap_*_ncg.bin
 *     bases and render each to area_minimap_&lt;base&gt;.png.
 *  2) gen_calib.py's main() loop: read the ARM9/overlay-16 room tables and
 *     emit area_calib.json, independent of which minimap files actually
 *     exist on disk.
 */
public final class DsMapImporter {

    private DsMapImporter() {}

    public interface Progress {
        /** Called once per minimap base as it's processed (rendered or skipped). */
        void onMinimap(String base, int index, int total, boolean ok);

        /** Called once with a short status line at the start of the calib pass. */
        void onCalibStatus(String message);
    }

    public static final class Result {
        public int minimapCount;
        public int minimapOk;
        public int minimapFailed;
        public List<String> failedBases = new ArrayList<>();
        public int calibEntries;
        public List<Integer> skippedRoomIds = new ArrayList<>();
        public int arm9Section0Length;
        public int overlay16RamAddress;
    }

    private static final String MINIMAP_DIR = "menu/bg/";

    public static Result importRom(SeekableSource rom, File outDir, Progress cb) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Could not create output directory: " + outDir);
        }

        NitroRom nrom = new NitroRom(rom);
        Result result = new Result();

        // ---- Pass 1: area/room minimap PNGs ----
        NitroRom.Folder bgFolder = nrom.filenames.subfolder(MINIMAP_DIR);
        List<String> bases = new ArrayList<>();
        if (bgFolder != null) {
            for (String name : bgFolder.fileNames) {
                if (name.startsWith("minimap_") && name.endsWith("_ncg.bin")) {
                    bases.add(name.substring(0, name.length() - "_ncg.bin".length()));
                }
            }
        }
        Collections.sort(bases);

        byte[] fallbackNcl = nrom.readFileByPathOrNull(MINIMAP_DIR + "minimap_000_ncl.bin");

        result.minimapCount = bases.size();
        for (int i = 0; i < bases.size(); i++) {
            String base = bases.get(i);
            boolean ok = false;
            try {
                byte[] ncgRaw = nrom.readFileByPathOrNull(MINIMAP_DIR + base + "_ncg.bin");
                byte[] nscRaw = nrom.readFileByPathOrNull(MINIMAP_DIR + base + "_nsc.bin");
                byte[] nclRaw = nrom.readFileByPathOrNull(MINIMAP_DIR + base + "_ncl.bin");
                if (nclRaw == null) nclRaw = fallbackNcl;

                if (ncgRaw != null && nscRaw != null && nclRaw != null) {
                    byte[] ncgDec = Lz10.decompress(ncgRaw);
                    MinimapDecoder.Rendered rendered = MinimapDecoder.renderMap(ncgDec, nclRaw, nscRaw);

                    File out = new File(outDir, "area_" + base + ".png");
                    try (OutputStream os = new FileOutputStream(out)) {
                        PngWriter.write(os, rendered.width, rendered.height, rendered.argb);
                    }
                    ok = true;
                }
            } catch (Exception e) {
                ok = false;
            }

            if (ok) result.minimapOk++;
            else {
                result.minimapFailed++;
                result.failedBases.add(base);
            }
            if (cb != null) cb.onMinimap(base, i + 1, bases.size(), ok);
        }

        // ---- Pass 2: area_calib.json ----
        if (cb != null) cb.onCalibStatus("Decompressing ARM9 and overlay 16 for room tables...");

        Blz.Section arm9Section0 = Blz.extractArm9Section0(
                nrom.arm9, nrom.arm9RamAddress, nrom.arm9CodeSettingsPointerAddress);
        Blz.Section overlay16 = nrom.loadArm9Overlay(RoomTable.OVERLAY_ID);
        if (overlay16 == null) {
            throw new IOException("ARM9 overlay " + RoomTable.OVERLAY_ID + " not found in ROM overlay table");
        }

        result.arm9Section0Length = arm9Section0.data.length;
        result.overlay16RamAddress = overlay16.ramAddress;

        List<RoomTable.CalibEntry> entries = RoomTable.buildCalibEntries(
                arm9Section0.data, arm9Section0.ramAddress,
                overlay16.data, overlay16.ramAddress,
                result.skippedRoomIds);
        result.calibEntries = entries.size();

        Collections.sort(entries, (a, b) -> a.key.compareTo(b.key));

        File calibFile = new File(outDir, "area_calib.json");
        try (OutputStream os = new FileOutputStream(calibFile)) {
            writeCalibJson(os, entries);
        }

        return result;
    }

    // ---- JSON emission (matches gen_calib.py's json.dump(..., indent=2, sort_keys=True) schema) ----

    private static void writeCalibJson(OutputStream out, List<RoomTable.CalibEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        for (int i = 0; i < entries.size(); i++) {
            RoomTable.CalibEntry e = entries.get(i);
            sb.append("  ").append(jsonString(e.key)).append(": {\n");
            appendEntryFields(sb, "    ", e.floors, e.ox, e.oy, e.x0, e.y0, e.x1, e.y1, e.roomId, e.source, e.sx, e.sy);
            sb.append("  }");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("}");
        out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // Field order matches Python's json.dump(..., sort_keys=True): alphabetical.
    private static void appendEntryFields(
            StringBuilder sb, String indent, List<RoomTable.FloorEntry> floors,
            double ox, double oy, int x0, int y0, int x1, int y1,
            int roomId, String source, double sx, double sy) {

        List<String> fields = new ArrayList<>();
        if (floors != null) {
            StringBuilder fb = new StringBuilder();
            fb.append("\"floors\": [\n");
            for (int i = 0; i < floors.size(); i++) {
                RoomTable.FloorEntry fe = floors.get(i);
                fb.append(indent).append("  {\n");
                List<String> ff = new ArrayList<>();
                ff.add(indent + "    \"floor_local_index\": " + fe.floorLocalIndex);
                ff.add(indent + "    \"ox\": " + jsonNumber(fe.ox));
                ff.add(indent + "    \"oy\": " + jsonNumber(fe.oy));
                ff.add(indent + "    \"rect_tiles\": " + rectTilesJson(fe.x0, fe.y0, fe.x1, fe.y1));
                ff.add(indent + "    \"suffix\": " + fe.suffix);
                ff.add(indent + "    \"sx\": " + jsonNumber(fe.sx));
                ff.add(indent + "    \"sy\": " + jsonNumber(fe.sy));
                for (int j = 0; j < ff.size(); j++) {
                    fb.append(ff.get(j));
                    if (j < ff.size() - 1) fb.append(",");
                    fb.append("\n");
                }
                fb.append(indent).append("  }");
                if (i < floors.size() - 1) fb.append(",");
                fb.append("\n");
            }
            fb.append(indent).append("]");
            fields.add(indent + fb);
        }
        fields.add(indent + "\"ox\": " + jsonNumber(ox));
        fields.add(indent + "\"oy\": " + jsonNumber(oy));
        fields.add(indent + "\"rect_tiles\": " + rectTilesJson(x0, y0, x1, y1));
        fields.add(indent + "\"room_id\": " + roomId);
        fields.add(indent + "\"source\": " + jsonString(source));
        fields.add(indent + "\"sx\": " + jsonNumber(sx));
        fields.add(indent + "\"sy\": " + jsonNumber(sy));

        for (int j = 0; j < fields.size(); j++) {
            sb.append(fields.get(j));
            if (j < fields.size() - 1) sb.append(",");
            sb.append("\n");
        }
    }

    private static String rectTilesJson(int x0, int y0, int x1, int y1) {
        return "[\n      " + x0 + ",\n      " + y0 + ",\n      " + x1 + ",\n      " + y1 + "\n    ]";
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    /** Rounds to 4 decimal places with HALF_EVEN (banker's rounding), matching Python's round(). */
    private static String jsonNumber(double v) {
        BigDecimal bd = new BigDecimal(Double.toString(v)).setScale(4, RoundingMode.HALF_EVEN);
        bd = bd.stripTrailingZeros();
        if (bd.scale() < 0) bd = bd.setScale(0);
        String s = bd.toPlainString();
        return s;
    }
}
