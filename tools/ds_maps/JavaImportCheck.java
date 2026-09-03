import com.kalenjohnson.chronoduo.dsimport.DsMapImporter;
import com.kalenjohnson.chronoduo.dsimport.SeekableSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Desktop test harness: runs the pure-Java DsMapImporter against the real
 * ROM and compares its output pixel-for-pixel (PNGs) and numerically
 * (area_calib.json) against the Python reference output in ds_maps_out/.
 *
 * Usage:
 *   javac -d <scratch>/classes app/src/main/java/com/kalenjohnson/chronoduo/dsimport/*.java tools/ds_maps/JavaImportCheck.java
 *   java -cp <scratch>/classes JavaImportCheck "<rom>" <outdir>
 *
 * Compares <outdir> against chrono-trigger/ds_maps_out (relative to this
 * file's repo root, auto-detected) unless overridden by a 3rd arg.
 */
public class JavaImportCheck {

    static final class RafSource implements SeekableSource {
        private final RandomAccessFile raf;

        RafSource(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override
        public int read(long pos, byte[] dst, int off, int len) {
            try {
                raf.seek(pos);
                return raf.read(dst, off, len);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public long length() {
            try {
                return raf.length();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: JavaImportCheck <rom> <outdir> [referenceDir]");
            System.exit(2);
        }
        File romFile = new File(args[0]);
        File outDir = new File(args[1]);
        File refDir = args.length > 2 ? new File(args[2]) : findDefaultRefDir();

        System.out.println("ROM: " + romFile.getAbsolutePath());
        System.out.println("Out: " + outDir.getAbsolutePath());
        System.out.println("Ref: " + refDir.getAbsolutePath());

        long t0 = System.nanoTime();

        try (RandomAccessFile raf = new RandomAccessFile(romFile, "r")) {
            RafSource src = new RafSource(raf);
            DsMapImporter.Result result = DsMapImporter.importRom(src, outDir, new DsMapImporter.Progress() {
                @Override
                public void onMinimap(String base, int index, int total, boolean ok) {
                    // quiet; summary printed at the end
                }

                @Override
                public void onCalibStatus(String message) {
                    System.out.println(message);
                }
            });

            long t1 = System.nanoTime();
            double seconds = (t1 - t0) / 1e9;

            System.out.println();
            System.out.println("=== Import summary ===");
            System.out.println("Minimaps found:   " + result.minimapCount);
            System.out.println("Minimaps OK:      " + result.minimapOk);
            System.out.println("Minimaps FAILED:  " + result.minimapFailed
                    + (result.failedBases.isEmpty() ? "" : " " + result.failedBases));
            System.out.println("ARM9 section0 length: " + result.arm9Section0Length
                    + " (expected approx 414240)");
            System.out.println("Overlay16 RAM address: 0x" + Integer.toHexString(result.overlay16RamAddress)
                    + " (expected 0x2198020)");
            System.out.println("Calib entries:    " + result.calibEntries);
            System.out.println("Skipped rooms:    " + result.skippedRoomIds.size()
                    + (result.skippedRoomIds.isEmpty() ? "" : " " + result.skippedRoomIds));
            System.out.printf("Import runtime:   %.2fs%n", seconds);

            System.out.println();
            comparePngs(outDir, refDir);

            System.out.println();
            compareCalib(new File(outDir, "area_calib.json"), new File(refDir, "area_calib.json"));
        }
    }

    private static File findDefaultRefDir() {
        File dir = new File(".").getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, "ds_maps_out");
            if (candidate.isDirectory()) return candidate;
            dir = dir.getParentFile();
        }
        return new File("ds_maps_out");
    }

    private static void comparePngs(File outDir, File refDir) throws IOException {
        System.out.println("=== PNG comparison ===");

        File[] refFiles = refDir.listFiles((d, name) -> name.startsWith("area_minimap_") && name.endsWith(".png"));
        if (refFiles == null) {
            System.out.println("No reference PNGs found in " + refDir);
            return;
        }

        TreeSet<String> refNames = new TreeSet<>();
        for (File f : refFiles) refNames.add(f.getName());

        File[] outFiles = outDir.listFiles((d, name) -> name.startsWith("area_minimap_") && name.endsWith(".png"));
        TreeSet<String> outNames = new TreeSet<>();
        if (outFiles != null) for (File f : outFiles) outNames.add(f.getName());

        TreeSet<String> onlyRef = new TreeSet<>(refNames);
        onlyRef.removeAll(outNames);
        TreeSet<String> onlyOut = new TreeSet<>(outNames);
        onlyOut.removeAll(refNames);

        if (!onlyRef.isEmpty()) System.out.println("Missing from Java output (" + onlyRef.size() + "): " + onlyRef);
        if (!onlyOut.isEmpty()) System.out.println("Extra in Java output (" + onlyOut.size() + "): " + onlyOut);

        int matchCount = 0, mismatchCount = 0;
        List<String> mismatches = new ArrayList<>();

        TreeSet<String> common = new TreeSet<>(refNames);
        common.retainAll(outNames);

        for (String name : common) {
            BufferedImage refImg = ImageIO.read(new File(refDir, name));
            BufferedImage outImg = ImageIO.read(new File(outDir, name));

            boolean match = refImg.getWidth() == outImg.getWidth() && refImg.getHeight() == outImg.getHeight();
            if (match) {
                outer:
                for (int y = 0; y < refImg.getHeight(); y++) {
                    for (int x = 0; x < refImg.getWidth(); x++) {
                        int a = normalizeTransparent(refImg.getRGB(x, y));
                        int b = normalizeTransparent(outImg.getRGB(x, y));
                        if (a != b) {
                            match = false;
                            break outer;
                        }
                    }
                }
            }

            if (match) matchCount++;
            else {
                mismatchCount++;
                mismatches.add(name);
            }
        }

        System.out.println("Compared: " + common.size() + "  Match: " + matchCount + "  Mismatch: " + mismatchCount);
        if (!mismatches.isEmpty()) {
            System.out.println("Mismatched files: " + mismatches.subList(0, Math.min(50, mismatches.size()))
                    + (mismatches.size() > 50 ? " ... (+" + (mismatches.size() - 50) + " more)" : ""));
        }
    }

    /** Fully-transparent pixels compare equal regardless of RGB, matching PIL's uniform (0,0,0,0) fill. */
    private static int normalizeTransparent(int argb) {
        int a = (argb >>> 24) & 0xFF;
        return a == 0 ? 0 : argb;
    }

    // ---- tiny JSON reader (object of objects; string/number/int-array leaves only) ----

    private static void compareCalib(File outFile, File refFile) throws IOException {
        System.out.println("=== area_calib.json comparison ===");
        if (!refFile.isFile()) {
            System.out.println("No reference calib file at " + refFile);
            return;
        }
        if (!outFile.isFile()) {
            System.out.println("No Java-produced calib file at " + outFile);
            return;
        }

        Map<String, Map<String, Object>> ref = parseCalib(refFile);
        Map<String, Map<String, Object>> out = parseCalib(outFile);

        TreeSet<String> refKeys = new TreeSet<>(ref.keySet());
        TreeSet<String> outKeys = new TreeSet<>(out.keySet());

        TreeSet<String> onlyRef = new TreeSet<>(refKeys);
        onlyRef.removeAll(outKeys);
        TreeSet<String> onlyOut = new TreeSet<>(outKeys);
        onlyOut.removeAll(refKeys);

        if (!onlyRef.isEmpty()) System.out.println("Missing keys (" + onlyRef.size() + "): " + sample(onlyRef));
        if (!onlyOut.isEmpty()) System.out.println("Extra keys (" + onlyOut.size() + "): " + sample(onlyOut));

        TreeSet<String> common = new TreeSet<>(refKeys);
        common.retainAll(outKeys);

        int match = 0, mismatch = 0;
        List<String> mismatchKeys = new ArrayList<>();
        double EPS = 1e-3;

        for (String key : common) {
            Map<String, Object> a = ref.get(key);
            Map<String, Object> b = out.get(key);
            boolean ok = entriesMatch(a, b, EPS);

            if (!ok) {
                mismatch++;
                mismatchKeys.add(key);
            } else {
                match++;
            }
        }

        System.out.println("Compared: " + common.size() + "  Match: " + match + "  Mismatch: " + mismatch);
        if (!mismatchKeys.isEmpty()) {
            System.out.println("Mismatched keys: " + sample(mismatchKeys));
            String k = mismatchKeys.get(0);
            System.out.println("First mismatch " + k + ": ref=" + ref.get(k) + " out=" + out.get(k));
        }
    }

    /**
     * Compares two calib entries (v5 schema: "file" int, either a
     * top-level {@code sx/sy/ox/oy/rect_tiles} transform (single-floor
     * rooms, including the no-rect/full-canvas case) or a "floors" array
     * (multi-floor rooms) -- never both. Recurses into "floors" (each
     * floor entry also carries its own "file"/"suffix"/transform).
     */
    private static boolean entriesMatch(Map<String, Object> a, Map<String, Object> b, double eps) {
        Object af = a.get("file");
        Object bf = b.get("file");
        if (af == null || bf == null || ((Number) af).longValue() != ((Number) bf).longValue()) return false;

        @SuppressWarnings("unchecked")
        List<Object> aFloors = (List<Object>) a.get("floors");
        @SuppressWarnings("unchecked")
        List<Object> bFloors = (List<Object>) b.get("floors");
        if ((aFloors == null) != (bFloors == null)) return false;

        if (aFloors != null) {
            if (aFloors.size() != bFloors.size()) return false;
            for (int i = 0; i < aFloors.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fa = (Map<String, Object>) aFloors.get(i);
                @SuppressWarnings("unchecked")
                Map<String, Object> fb = (Map<String, Object>) bFloors.get(i);
                if (!transformMatches(fa, fb, eps)) return false;
                Object asuf = fa.get("suffix");
                Object bsuf = fb.get("suffix");
                if (asuf == null || bsuf == null || ((Number) asuf).longValue() != ((Number) bsuf).longValue()) {
                    return false;
                }
            }
            return true;
        }
        return transformMatches(a, b, eps);
    }

    private static boolean transformMatches(Map<String, Object> a, Map<String, Object> b, double eps) {
        for (String numField : new String[]{"sx", "sy", "ox", "oy"}) {
            Object av = a.get(numField);
            Object bv = b.get(numField);
            if (av == null || bv == null) return false;
            if (Math.abs(((Number) av).doubleValue() - ((Number) bv).doubleValue()) > eps) return false;
        }
        List<?> ar = (List<?>) a.get("rect_tiles");
        List<?> br = (List<?>) b.get("rect_tiles");
        return ar != null && br != null && ar.equals(br);
    }

    private static String sample(java.util.Collection<String> c) {
        List<String> l = new ArrayList<>(c);
        List<String> s = l.subList(0, Math.min(30, l.size()));
        return s + (l.size() > 30 ? " ... (+" + (l.size() - 30) + " more)" : "");
    }

    private static Map<String, Map<String, Object>> parseCalib(File f) throws IOException {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        JsonParser p = new JsonParser(text);
        Object root = p.parseValue();
        Map<String, Map<String, Object>> result = new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> rootMap = (Map<String, Object>) root;
        for (Map.Entry<String, Object> e : rootMap.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            result.put(e.getKey(), entry);
        }
        return result;
    }

    /** Minimal recursive-descent JSON parser: objects, arrays, strings, numbers, true/false/null. */
    static final class JsonParser {
        private final String s;
        private int i;

        JsonParser(String s) {
            this.s = s;
            this.i = 0;
        }

        Object parseValue() {
            skipWs();
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') {
                i += 4;
                return Boolean.TRUE;
            }
            if (c == 'f') {
                i += 5;
                return Boolean.FALSE;
            }
            if (c == 'n') {
                i += 4;
                return null;
            }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            i++; // {
            skipWs();
            if (s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                i++; // :
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    break;
                }
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWs();
            if (s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                Object val = parseValue();
                list.add(val);
                skipWs();
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    break;
                }
            }
            return list;
        }

        String parseString() {
            i++; // opening "
            StringBuilder sb = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i);
                if (c == '\\') {
                    i++;
                    char esc = s.charAt(i);
                    sb.append(esc);
                } else {
                    sb.append(c);
                }
                i++;
            }
            i++; // closing "
            return sb.toString();
        }

        Object parseNumber() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String num = s.substring(start, i);
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
