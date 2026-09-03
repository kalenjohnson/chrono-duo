package com.kalenjohnson.chronoduo;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Locates the user's installed copy of the official game and prepares its
 * pieces for hosting: the APK path (asset archive), an AssetManager bound to
 * that APK, and the extracted arm64 native libraries.
 *
 * Nothing belonging to Square Enix is bundled or redistributed — everything is
 * read at runtime from the copy the user installed from Google Play.
 */
public final class ChronoRuntime {
    public static final String CHRONO_PACKAGE = "com.square_enix.android_googleplay.chrono";
    private static final String TAG = "ChronoDuo";
    private static final String[] LIBS = {"libc++_shared.so", "libchrono.so"};

    private static ChronoRuntime instance;

    private final String apkPath;
    private final java.util.ArrayList<String> allApks = new java.util.ArrayList<>();
    private final AssetManager chronoAssets;
    private final File libDir;

    public static synchronized ChronoRuntime bootstrap(Context ctx) throws Exception {
        if (instance == null) {
            instance = new ChronoRuntime(ctx.getApplicationContext());
        }
        return instance;
    }

    private ChronoRuntime(Context ctx) throws Exception {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            if ("arm64-v8a".equals(abi)) arm64 = true;
        }
        if (!arm64) {
            throw new IllegalStateException("This device has no arm64-v8a support; libchrono.so is arm64-only.");
        }

        PackageInfo pi = ctx.getPackageManager().getPackageInfo(CHRONO_PACKAGE, 0);
        ApplicationInfo ai = pi.applicationInfo;
        apkPath = ai.sourceDir;
        // Play installs the game as split APKs; native libs live in
        // split_config.arm64_v8a.apk, assets in split_assetPack.apk.
        allApks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) {
            for (String split : ai.splitSourceDirs) allApks.add(split);
        }
        Log.i(TAG, "found " + CHRONO_PACKAGE + " v" + pi.versionName + ", " + allApks.size() + " apk(s)");

        chronoAssets = ctx.createPackageContext(CHRONO_PACKAGE, 0).getAssets();

        long version = pi.getLongVersionCode();
        libDir = new File(ctx.getFilesDir(), "chrono-libs/" + version);
        extractLibsIfNeeded();
    }

    private void extractLibsIfNeeded() throws IOException {
        boolean complete = true;
        for (String lib : LIBS) {
            if (!new File(libDir, lib).isFile()) complete = false;
        }
        if (complete) return;

        if (!libDir.isDirectory() && !libDir.mkdirs()) {
            throw new IOException("cannot create " + libDir);
        }
        for (String lib : LIBS) {
            if (!extractLib(lib)) {
                throw new IOException("lib/arm64-v8a/" + lib + " not found in any of " + allApks
                        + " — is the installed game the arm64 build?");
            }
        }
    }

    private boolean extractLib(String lib) throws IOException {
        for (String apk : allApks) {
            try (ZipFile zip = new ZipFile(apk)) {
                ZipEntry entry = zip.getEntry("lib/arm64-v8a/" + lib);
                if (entry == null) continue;
                File out = new File(libDir, lib);
                File tmp = new File(libDir, lib + ".tmp");
                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                if (!tmp.renameTo(out)) throw new IOException("rename failed for " + out);
                Log.i(TAG, "extracted " + lib + " (" + out.length() + " bytes) from " + apk);
                return true;
            }
        }
        return false;
    }

    public String getApkPath() {
        return apkPath;
    }

    public AssetManager getChronoAssets() {
        return chronoAssets;
    }

    public File getLibDir() {
        return libDir;
    }
}
