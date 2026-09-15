package com.kalenjohnson.chronoduo;

import android.content.Context;
import android.util.Log;

/**
 * Live game state read out of libchrono.so via the chronoduo native hook.
 * Field offsets inside the 0x154-byte character block are calibrated
 * empirically (see NOTES.md).
 */
public final class GameState {
    private static final String TAG = "ChronoDuo";
    private static boolean attached;

    static {
        System.loadLibrary("chronoduo");
    }

    public static native boolean nativeAttach();

    // --- pixel graphics (GOT-patch cocos2d::Texture2D's default GL_LINEAR
    // filter to GL_NEAREST) -------------------------------------------------
    // Shared with PartyPanelView's settings-screen toggle -- same prefs file
    // (PREFS_NAME) PartyPanelView already uses for its own "chronoduo_prefs",
    // just a different key, so the pref lives here where AppActivity's boot
    // path can reach it without depending on PartyPanelView.
    private static final String PREFS_NAME = "chronoduo_prefs";
    private static final String KEY_PIXEL_GRAPHICS = "pixel_graphics";

    /**
     * GOT-patches libchrono.so's Texture2D::setAntiAliasTexParameters JUMP_SLOT
     * to redirect to setAliasTexParameters (enable=true, GL_NEAREST/"pixel
     * graphics") or restores the original slot value (enable=false,
     * GL_LINEAR). See gamestate.c for the ELF relocation walk. Only affects
     * textures bound after the call -- already-loaded textures keep whatever
     * filter they were bound with, hence the "restart to fully apply" note in
     * the settings UI. Returns false (with a LOGE on the native side) if the
     * patch could not be applied (symbols/slot not found, mprotect failure).
     */
    public static native boolean nativeSetPixelGraphics(boolean enable);

    /**
     * Enables/disables 2x2 decimation of texture uploads inside the
     * glTexImage2D hook (the game's art ships pre-upscaled ~2x with
     * smoothing baked in; GL_NEAREST alone can't undo that). Independent of
     * nativeSetPixelGraphics's GOT patching -- see gamestate.c. Off by
     * default; called right after nativeSetPixelGraphics() below.
     */
    public static native void nativeSetPixelDecimate(boolean on);

    /**
     * Diagnostic: dumps the running pixel-graphics counters (glTexImage2D
     * calls, glGenerateMipmap calls, glTexParameteri LINEAR->NEAREST
     * rewrites) to logcat as one line. See gamestate.c.
     */
    public static native void nativeLogPixelStats();

    /**
     * Loads the whole disk-backed texture-replacement registry from a text
     * index (see {@link com.kalenjohnson.chronoduo.OrigArtCache#refresh}):
     * {@code indexPath} is "&lt;filesDir&gt;/orig_art_cache/index.txt", one
     * line per sheet, "&lt;name&gt; &lt;w&gt; &lt;h&gt; &lt;alphaFp hex16&gt;
     * &lt;redFp hex16&gt;" (name is the asset basename, e.g. "c000_0.png" --
     * no directory component -- matched against the asset path the game
     * loads it with, or by content fingerprint when that bypasses the
     * path-based hooks; fps are lowercase 16-hex-digit, e.g. via Java's
     * {@code String.format("%016x", fp)}). {@code rgbaDir} is the directory
     * holding "&lt;name&gt;.rgba" -- tightly packed PREMULTIPLIED RGBA8888
     * bytes, {@code w*h*4} each, matching how the game premultiplies its own
     * PNGs before upload -- which the native side reads lazily (fread) on a
     * match inside hooked_glTexImage2D; nothing is held decoded in native RAM
     * between matches.
     *
     * The alpha/red fingerprints are a 64-bit FNV-1a hash over a 64x64 grid
     * of alpha-channel samples (w/h mixed into the hash first) and a second
     * FNV-1a hash over the same grid's red-channel samples as a tiebreaker --
     * see OrigArtCache#fingerprint and gamestate.c's tex_fingerprint for the
     * identical sampling formula both sides must agree on: for i,j in 0..63,
     * x = (i*w)/64, y = (j*h)/64 (integer division), sampling pixel (x,y).
     * These let hooked_glTexImage2D (gamestate.c) match uploads that bypass
     * the path-based hooks entirely (e.g. character sheets loaded via a code
     * path that never calls TextureCache::addImage or
     * ResourceManager::createTexture) by comparing fingerprints of the live
     * upload's pixels against a registered fingerprint of the original
     * asset, instead of by asset path.
     *
     * Replaces the registry wholesale, so this is idempotent and safe to
     * call again (e.g. from the settings toggle). Called from a background
     * thread at boot and from the pixel-graphics settings toggle; consulted
     * from the GL thread inside hooked_glTexImage2D. Returns the number of
     * entries loaded.
     */
    public static native int nativeLoadTextureReplacementIndex(String indexPath, String rgbaDir);

    /**
     * Registers the file-level asset substitution table (gamestate.c
     * mechanism 7): {@code names} are asset basenames exactly as the game
     * asks for them (e.g. "mapchip_0_117_0.png") and {@code paths} the
     * matching absolute paths of the replacement files, one per name.
     *
     * <p>The native hook sits on {@code ctr::ResourceManager::getData}, the
     * single choke point every archive/filesystem asset read passes through,
     * and returns the replacement file's bytes in place of the archive
     * entry -- so the game DECODES our PNG and every consumer of the decoded
     * image sees it, not just the glTexImage2D upload that {@link
     * #nativeLoadTextureReplacementIndex}'s registry can reach. Bytes are
     * served verbatim (this is upstream of cocos2d-x's premultiply step).
     * Gated on the pixel-graphics pref, like the other mechanisms.</p>
     *
     * <p>Replaces the table wholesale; cheap (no file IO), so call it early.
     * Returns the number of entries registered.</p>
     */
    public static native int nativeRegisterFileSubstitutions(String[] names, String[] paths);

    /**
     * Registers the mod-loader's file substitution table (gamestate.c's
     * separate mod_subst_t hash table, layered onto the same getData hook
     * as {@link #nativeRegisterFileSubstitutions} above): {@code
     * archivePaths} are FULL archive paths exactly as the game asks for them
     * (e.g. "Localize/en/msg/tech.txt"), not basenames, and {@code
     * diskPaths} the matching absolute replacement file paths, one per
     * entry. Unlike the orig_art registry, these are never gated on the
     * Pixel graphics pref, and are checked BEFORE the basename-keyed
     * orig_art registry, so a mod can override an orig_art sheet.
     *
     * <p>Replaces the mod table wholesale (independent of the orig_art
     * table); called by {@link com.kalenjohnson.chronoduo.mods.ModManager}
     * from a background thread on scan()/import/enable-toggle. Returns the
     * number of entries registered.</p>
     */
    public static native int nativeRegisterModSubstitutions(String[] archivePaths, String[] diskPaths);

    /** Clears the registered texture-replacement registry. See gamestate.c. */
    public static native void nativeClearTextureReplacements();

    /** Reads the persisted pixel-graphics preference. Default true (crisp/GL_NEAREST). */
    public static boolean getPixelGraphicsPref(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PIXEL_GRAPHICS, true);
    }

    /**
     * Persists {@code enable} and applies it immediately (called from the
     * settings-screen toggle -- see PartyPanelView#drawSettingsScreen).
     * Returns whether the native patch call itself succeeded.
     */
    public static boolean setPixelGraphicsPref(Context ctx, boolean enable) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PIXEL_GRAPHICS, enable)
                .apply();
        return nativeSetPixelGraphics(enable);
    }

    /**
     * Applies the persisted preference at boot. Called from AppActivity's
     * onLoadNativeLibraries override, immediately after libchrono.so is
     * System.load()ed and well before the GL surface/nativeInit runs, so the
     * patch is in place before the game binds its first texture. Referencing
     * this class here is also what triggers GameState's own static
     * System.loadLibrary("chronoduo") if it hasn't run yet. Returns the
     * preference value (regardless of whether the native patch succeeded).
     */
    public static boolean applyPixelGraphicsPref(Context ctx) {
        boolean enable = getPixelGraphicsPref(ctx);
        nativeSetPixelGraphics(enable);
        nativeSetPixelDecimate(false); // decimation experiment: breaks texel-space map sampling, off until fixed
        return enable;
    }

    public static native byte[] nativeReadChara(int idx);
    public static native byte[] nativeProbeWork(int slotOff, int memOff, int len);
    public static native byte[] nativeReadSfc(int off, int len);
    public static native byte[] nativeReadAsmMem(int off, int len);
    public static native void nativeScan();
    public static native void nativeDumpToFiles(String dir);
    public static native void nativeSceneDump(int maxDepth);      // GL thread only
    public static native int nativeSetVisibleByPattern(String pattern, boolean visible); // GL thread only

    public static native void nativeUpdateMapName();              // GL thread only
    public static native String nativeGetMapName();

    /**
     * Current field-map/location id -- a plain int32 read of
     * ChronoCanvas::getInstance()+0x12300 (== the embedded FIELD_MAp struct's
     * +0x1018), the exact field ChronoCanvas::getFieldMapName() itself loads
     * (via {@code ldrsw x11, [x9, #0x98c]} where x9 = this+0x11974) to pick
     * which localized name variant to show -- see NOTES.md /
     * fieldmap_id_report.md for the disassembly. Unlike nativeUpdateMapName
     * this is a plain safe_read (no scene-graph/std::string sret call), so it
     * is safe to call from any thread. Returns -1 if the canvas or the read
     * is unavailable.
     */
    public static native int nativeGetFieldMapId();

    /**
     * Party leader's in-field tile position: [x, y] as floats, read from the
     * CHARACTER_DATa record for party slot 1 (record index 1 -- cSfcWork +
     * 0x6924 + 1*0x154, the same base/stride nativeReadChara uses). Verified
     * live by differential dumps: int32 X tile at record+0x80, int32 X*256
     * sub-tile at +0x84, int32 Y tile at +0x8c, int32 Y*256 sub-tile at +0x90
     * (Y grows downward); the native side prefers the sub-tile values divided
     * by 256.0f and falls back to the plain tile ints only if that read
     * fails. Only meaningful in field maps, not on the overworld (use
     * worldX/worldY there instead -- see PartySnapshot). Plain safe_read, so
     * safe to call from any thread. Returns null if the record is
     * unreadable.
     */
    public static native float[] nativeGetFieldPos();

    /**
     * Current overworld/era id, decoded from the u16 raw world selector at
     * Asm mem 0x2E100 (id = raw - 0x1F0, valid for raw in [0x1F0, 0x1FA] i.e.
     * id 0..10 -- era 0 = 1000 AD, 1 = 600 AD, 2 = 2300 AD, 3 = 65,000,000 BC,
     * 4 = 12,000 BC, others are special maps -- see world_era_report.md).
     * Plain safe_read off the same Asm buffer {@link #nativeReadAsmMem} uses,
     * so safe to call from any thread. Returns -1 if unattached/unreadable or
     * the raw value is out of range.
     */
    public static native int nativeGetWorldEra();

    /**
     * In-game overworld MAP overview mode byte, at Asm mem 0x2E27C --
     * {@code WorldScene::mapButton()} writes 6 there when the player opens
     * the map screen (see world_era_report.md); no other value is confirmed,
     * so this is exposed as the raw byte (0..255), not a decoded bool --
     * treat {@code == 6} as "map screen open". Plain safe_read, safe to call
     * from any thread. Returns -1 if unattached/unreadable.
     */
    public static native int nativeGetWorldMapMode();

    public static native void nativeUpdateBattleFlag();           // GL thread only
    public static native boolean nativeGetBattleFlag();
    public static native void nativeDumpBattleBuffers(String dir); // any thread; uses cached node ptr
    // Live battle actor array (10 * 0x80-byte slots), or null if not in battle
    // or any pointer in the chase is bad. Any thread; uses cached node ptr.
    public static native byte[] nativeReadBattleActors();
    // Battle command button (MenuItemToggle) positions/visibility/selection,
    // cached by nativeUpdateBattleFlag: flat [x0,y0,vis0,sel0,selIdx0, ...]
    // quintuples in worldspace pixels; vis/sel are 0.0/1.0, selIdx is the
    // toggle's raw _selectedIndex cast to float. Empty array when not in
    // battle. Any thread; uses the cached array populated on the GL thread.
    public static native float[] nativeGetBattleToggles();
    // Index into nativeGetBattleToggles() of the Auto Battle MenuItemToggle
    // (BattleMenu::autoButton), resolved fresh each nativeUpdateBattleFlag
    // scan by pointer match, not vector position. -1 when not found / not in
    // battle. Any thread; uses the cached int populated on the GL thread.
    public static native int nativeGetBattleAutoToggleIndex();
    // Cached open battle list submenu (Tech or Item), populated by
    // nativeUpdateBattleFlag right after the toggle scan above -- same
    // thread/cadence. Returns null when no submenu is open. Otherwise a
    // flat float array: [kind, count, id0, usable0, extra0, x0, y0, id1,
    // usable1, extra1, x1, y1, ...]. kind is 0 (Tech) or 1 (Item); usable is
    // 0.0/1.0; extra is the tech's param/cost or the item's count, cast to
    // float; x/y are worldspace pixels in the same space as
    // nativeGetBattleToggles, or NaN when the row's on-screen button node
    // couldn't be resolved (skip tapping that row). Any thread; uses the
    // cached array populated on the GL thread.
    public static native float[] nativeGetBattleList();
    // Opts the BattleTechMenu/BattleItemMenu submenu nodes into the same
    // opacity hiding nativeEnforceUiTick applies to the rest of the battle
    // chrome (see nativeSetHideBattleUi) -- only meaningful once the caller
    // is mirroring nativeGetBattleList's rows on the second screen. Default
    // false. Any thread (plain flag write).
    public static native void nativeSetHideBattleSubmenus(boolean hide);

    // Live battle-results accumulator (EXP/Gold/TP/item drops), read from
    // *(SceneBattle+0x60) -- see gamestate.c's nativeGetBattleResults comment
    // block for the full offset table. Null when there's no active battle
    // node or SceneBattle can't be resolved. Otherwise an int array:
    //   [step, exp, gold, tp, flags, itemCount, item0, item1, ...]
    // step is comment_out2's own results-phase state index (sb+0x22f4; 0=EXP
    // message, 2=TP, 4=Gold, 8/16/24=item drops, 32=idle -- see
    // battle_results_phase() in the native code, which reads the same field
    // for scene-visibility purposes). When the battlework pointer itself
    // isn't readable/plausible, exp/gold/tp/itemCount come back as
    // [-1, -1, -1, 0] (flags 0) with step still populated. itemCount is at
    // most 8; item ids are plain item.txt indices. Any thread; uses the
    // cached g_battle_node pointer like nativeReadBattleActors.
    public static native int[] nativeGetBattleResults();

    // --- frame-perfect UI enforcer -----------------------------------------
    // --- live overworld map (see gamestate.c's world_map_tick) --------------
    // The panel re-composites the world from the game's OWN live metatile
    // grid, so story changes (bridges, craters) show up on the second screen.
    // Not from the game's RenderTextures: those hold a 2x scrolling window
    // around the party during play, and only become the whole 1x world inside
    // the game's own map screen -- see the NOTES.md record.

    /** True while the running scene contains a WorldScene whose WorldMap resolves (GL-tick cached). */
    public static native boolean nativeGetWorldScenePresent();
    /** The game's own map-dirty byte (WorldMap+0x26d88) as last seen by the GL tick; -1 = no WorldScene. */
    public static native int nativeGetWorldMapDirty();
    /**
     * FNV-1a hash of the live 96x64x2 metatile grid, 0 before the first
     * snapshot. The cheap change signal -- poll this and only pull the 12KB
     * via {@link #nativeGetWorldMapData} when it moves.
     */
    public static native int nativeGetWorldMapHash();
    /**
     * Copies the live metatile grid into {@code out}: {@link
     * #nativeGetWorldMapDataSize()} bytes in exactly the {@code Map_%04d.dat}
     * layout (layer 0 at 0, layer 1 at 0x1800), so it drops straight into
     * {@link WorldMapCompositor#composite}. False if nothing is snapshotted
     * yet or the array is too small. Any thread.
     */
    public static native boolean nativeGetWorldMapData(byte[] out);
    public static native int nativeGetWorldMapDataSize();
    /** {@code WorldMap+0x320}, the WorldMap object's own 0..6 world index, for the last snapshot; -1 if none. Diagnostic only -- NOT the same id space as {@link #nativeGetWorldEra()}. */
    public static native int nativeGetWorldMapIndex();
    /**
     * Pixel-granular overworld marker positions, the same Asm values the
     * game's own map screen uses (WorldMap::markMiniMap): {@code {partyImgX,
     * partyImgY, epochImgX, epochImgY, epochVisible, rawPartyX, rawPartyY,
     * rawEpochX, rawEpochY}} in 1x world-image pixels (1536x1024, top-left
     * origin). Calibrated on device: the raw values already ARE those pixels
     * (raw == tile*8 on both axes), so no shift is applied. Party entries are
     * -1 when unreadable, Epoch entries -1 unless {@code epochVisible == 1}.
     * Null when the Asm buffer isn't attached.
     */
    public static native int[] nativeGetWorldPixelPos();

    // Idempotent start gate (native side) for the per-rendered-frame GL tick
    // below -- see startFrameEnforcer(). Returns true only the first time.
    public static native boolean nativeStartFrameEnforcer();
    // Cheap per-frame GL-thread tick: depth<=2 park sweep for FieldMenu/
    // WorldMenu (kills the field-MENU flash the 700ms tick can still miss
    // between ticks), plus -- when in battle and nativeSetHideBattleUi(true)
    // (the default) -- an opacity-only sweep of the battle node's command
    // menus. Must run on the GL thread; queued every frame via
    // FRAME_ENFORCER_TICK below.
    public static native void nativeEnforceUiTick();
    // Toggles the battle top-UI opacity hiding done by nativeEnforceUiTick.
    // Default true; expose an off switch in case it ever misbehaves. Any
    // thread (plain flag write).
    public static native void nativeSetHideBattleUi(boolean hide);
    // Dev experiment hook: bit i blanks direct child index i of the battle
    // node's children (any type), so which child draws what can be seen
    // live. One-way -- clearing a bit does not restore opacity. Any thread
    // (plain flag write).
    public static native void nativeSetBattleHideMask(int mask);

    private static boolean frameEnforcerStarted;
    private static final android.os.Handler sEnforcerHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    // Per-frame tick. CRITICAL: the repost must bounce through the MAIN
    // thread with a frame's delay — a Runnable that re-queues itself from
    // inside the GL thread's event drain starves rendering completely
    // (GLSurfaceView drains its event queue before drawing; a self-reposting
    // event means the queue never empties and the first frame never renders —
    // this hung the app at the splash screen when done the "obvious" way).
    private static final Runnable FRAME_ENFORCER_TICK = new Runnable() {
        @Override public void run() {
            nativeEnforceUiTick();
            sEnforcerHandler.postDelayed(GameState::queueEnforcerTick, 16);
        }
    };

    private static void queueEnforcerTick() {
        org.cocos2dx.lib.Cocos2dxGLSurfaceView view =
                org.cocos2dx.lib.Cocos2dxGLSurfaceView.getInstance();
        if (view != null) view.queueEvent(FRAME_ENFORCER_TICK);
        else sEnforcerHandler.postDelayed(GameState::queueEnforcerTick, 100);
    }

    /**
     * Starts the per-frame UI enforcer loop. Idempotent (a second call is a
     * no-op, guarded natively so even a call racing the Java-side flag can't
     * stack a duplicate repost loop) and safe to call before the GL surface
     * exists -- queueEvent() just buffers the Runnable until the render
     * thread is ready to run it. No stop path: the tick is a no-op whenever
     * nothing currently matches its patterns, so leaving it running forever
     * costs nothing extra once idle.
     */
    public static void startFrameEnforcer() {
        if (frameEnforcerStarted || !isAttached()) return;
        if (!nativeStartFrameEnforcer()) return;
        frameEnforcerStarted = true;
        queueEnforcerTick();
    }

    /** Scene-graph node type/name patterns hidden by the "clean UI" tick. */
    public static final String[] HIDDEN_UI_PATTERNS = {"FieldMenu", "WorldMenu"};

    /**
     * Hides every node matching HIDDEN_UI_PATTERNS. Must run on the GL thread
     * (scene-graph access) -- shared by AppActivity's periodic "clean UI" tick
     * and SecondScreenPresentation's scene-change fast path so both apply the
     * exact same call.
     */
    public static void applyHiddenUiPatterns() {
        for (String pat : HIDDEN_UI_PATTERNS) {
            nativeSetVisibleByPattern(pat, false);
        }
    }

    /**
     * Queues one applyHiddenUiPatterns() run on the GL thread. Safe to call
     * from any thread; no-op if the native hook isn't attached yet.
     */
    public static void queueHiddenUiPatterns() {
        if (isAttached()) {
            org.cocos2dx.lib.Cocos2dxHelper.runOnGLThread(GameState::applyHiddenUiPatterns);
        }
    }

    public static boolean attach() {
        if (!attached) {
            attached = nativeAttach();
            Log.i(TAG, "GameState.attach: " + attached);
        }
        return attached;
    }

    public static boolean isAttached() {
        return attached;
    }

    public static String hexDump(byte[] data, int base) {
        if (data == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 16) {
            sb.append(String.format("%04x:", base + i));
            for (int j = i; j < Math.min(i + 16, data.length); j++) {
                sb.append(String.format(" %02x", data[j]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** cSfcWork::Setup stores Asm::getBuffer() at this+0xfdf8 — the virtual SNES memory. */
    public static final int WORK_PTR_SLOT = 0xfdf8;
    /** SNES bank $7E lives at index 0x20000 in the Asm buffer (GetWorkBank7E). */
    public static final int BANK_7E = 0x20000;

    /** Full-region dumps for offline analysis (adb pull the .bin files). */
    public static void dumpToFiles(String dir) {
        if (attach()) nativeDumpToFiles(dir);
    }
}
