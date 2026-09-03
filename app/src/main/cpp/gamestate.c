// Reads live game state out of libchrono.so for the second-screen UI.
//
// Layout facts recovered from the binary (see NOTES.md):
//   ChronoCanvas::getInstance()            exported, returns the root singleton
//   cSfcWork                               embedded object at canvas + 0x40
//   cSfcWork::GetCharaData(i)              returns this + 0x6924 + i*0x154
//   virtual SNES memory                    pointer held in object members
//                                          (e.g. SceneBattle+0x8); bank $7E
//                                          lives at index 0x20000
#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/uio.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "ChronoDuoNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define SFC_WORK_OFFSET   0x40
#define CHARA_BASE        0x6924
#define CHARA_STRIDE      0x154
// cSfcWork::GetSendBtlDataa(): ldr x0, [x0, 0xc0f0]; ret -- heap ptr to battle data.
#define GETSENDBTLDATA_OFFSET 0xc0f0

static void *(*p_getInstance)(void);
static uint8_t **g_asm_mem_slot;  // libchrono base + 0xbeeba8: virtual SNES memory ptr
static void *(*p_dir_getInstance)(void);
static void *(*p_node_getName)(void *);      // returns const std::string&
static void *(*p_node_getChildren)(void *);  // returns cocos2d::Vector<Node*>&
static int   (*p_node_isVisible)(void *);
static void  (*p_node_setVisible)(void *, int);

// std::string returned by value (sret) from ChronoCanvas::getFieldMapName()
typedef struct { uint8_t raw[24]; } CppStr;
static CppStr (*p_getFieldMapName)(void *);
static char g_map_name[64];

// Asm::GetAddrY8 reads the memory base from an unnamed static at 0xbeeba8
// (bank $7E maps to +0x20000, bank $7F to +0x10000 within it).
#define ASM_MEM_GLOBAL 0xbeeba8

static uint8_t *sfc_work(void) {
    if (!p_getInstance) return NULL;
    uint8_t *canvas = (uint8_t *)p_getInstance();
    if (!canvas) return NULL;
    return canvas + SFC_WORK_OFFSET;
}

static int plausible_ptr(void *p) {
    // strip the top-byte tag (Android heap pointers are TBI-tagged, e.g. 0xb4...)
    uintptr_t v = (uintptr_t)p & 0x00ffffffffffffffull;
    return v > 0x1000000ull && v < (1ull << 48) && (v & 7) == 0;
}

JNIEXPORT jboolean JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeAttach(JNIEnv *env, jclass cls) {
    void *h = dlopen("libchrono.so", RTLD_NOW | RTLD_NOLOAD);
    if (!h) h = RTLD_DEFAULT;
    p_getInstance = (void *(*)(void)) dlsym(h, "_ZN12ChronoCanvas11getInstanceEv");
    if (p_getInstance) {
        Dl_info info;
        if (dladdr((void *)p_getInstance, &info) && info.dli_fbase) {
            g_asm_mem_slot = (uint8_t **)((uint8_t *)info.dli_fbase + ASM_MEM_GLOBAL);
        }
    }
    p_getFieldMapName = (CppStr (*)(void *)) dlsym(h, "_ZNK12ChronoCanvas15getFieldMapNameEv");
    p_dir_getInstance = (void *(*)(void)) dlsym(h, "_ZN7cocos2d8Director11getInstanceEv");
    p_node_getName = (void *(*)(void *)) dlsym(h, "_ZNK7cocos2d4Node7getNameEv");
    p_node_getChildren = (void *(*)(void *)) dlsym(h, "_ZN7cocos2d4Node11getChildrenEv");
    p_node_isVisible = (int (*)(void *)) dlsym(h, "_ZNK7cocos2d4Node9isVisibleEv");
    p_node_setVisible = (void (*)(void *, int)) dlsym(h, "_ZN7cocos2d4Node10setVisibleEb");
    LOGI("attach: getInstance=%p canvas=%p asm_slot=%p director=%p", (void *)p_getInstance,
         p_getInstance ? p_getInstance() : NULL, (void *)g_asm_mem_slot,
         (void *)p_dir_getInstance);
    return p_getInstance != NULL;
}

// Read from the translated-65816 layer's virtual SNES memory ("Asm" buffer).
JNIEXPORT jbyteArray JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeReadAsmMem(JNIEnv *env, jclass cls,
                                                           jint off, jint len) {
    if (len <= 0 || len > 0x10000 || off < 0 || off > 0x400000 || !g_asm_mem_slot) return NULL;
    uint8_t *mem = *g_asm_mem_slot;
    if (!plausible_ptr(mem)) {
        LOGI("asm mem implausible: %p", (void *)mem);
        return NULL;
    }
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)(mem + off));
    return arr;
}

// Copy of character data block idx (0x154 bytes), or null.
JNIEXPORT jbyteArray JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeReadChara(JNIEnv *env, jclass cls, jint idx) {
    if (idx < 0 || idx > 7) return NULL;
    uint8_t *sfc = sfc_work();
    if (!sfc) return NULL;
    uint8_t *block = sfc + CHARA_BASE + (size_t)idx * CHARA_STRIDE;
    jbyteArray arr = (*env)->NewByteArray(env, CHARA_STRIDE);
    if (!arr) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, CHARA_STRIDE, (const jbyte *)block);
    return arr;
}

// Probe: treat *(sfcWork + slotOff) as a pointer to the virtual SNES memory
// and copy len bytes from memOff within it. Returns null if implausible.
JNIEXPORT jbyteArray JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeProbeWork(JNIEnv *env, jclass cls,
                                                          jint slotOff, jint memOff, jint len) {
    if (len <= 0 || len > 0x10000 || memOff < 0 || memOff > 0x400000) return NULL;
    uint8_t *sfc = sfc_work();
    if (!sfc) return NULL;
    uint8_t *mem = *(uint8_t **)(sfc + slotOff);
    if (!plausible_ptr(mem)) {
        LOGI("probe slot +0x%x: implausible ptr %p (sfc=%p)", slotOff, (void *)mem, (void *)sfc);
        return NULL;
    }
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)(mem + memOff));
    return arr;
}

static void scan_region(const char *name, uint8_t *base, int len) {
    if (!plausible_ptr(base)) { LOGI("scan %s: bad base %p", name, (void *)base); return; }
    // nonzero 4KB page map
    char map[200];
    int pages = len / 0x1000;
    if (pages > 190) pages = 190;
    for (int p = 0; p < pages; p++) {
        int nz = 0;
        for (int i = 0; i < 0x1000; i++) {
            if (base[p * 0x1000 + i]) { nz = 1; break; }
        }
        map[p] = nz ? '1' : '.';
    }
    map[pages] = 0;
    LOGI("scan %s pages(4K): %s", name, map);

    static const uint8_t ascii[] = {'C', 'r', 'o', 'n', 'o'};
    static const uint8_t snes[]  = {0xA2, 0xD1, 0xCE, 0xCD, 0xCE};
    int hits = 0;
    for (int i = 0; i + 5 < len && hits < 8; i++) {
        if (!memcmp(base + i, ascii, 5)) { LOGI("scan %s: ASCII 'Crono' @0x%x", name, i); hits++; }
        else if (!memcmp(base + i, snes, 5)) { LOGI("scan %s: SNES 'Crono' @0x%x", name, i); hits++; }
    }
    hits = 0;
    for (int i = 0; i + 4 < len && hits < 20; i++) {
        if (base[i] == 0x46 && base[i + 1] == 0 && base[i + 2] == 0x46 && base[i + 3] == 0) {
            LOGI("scan %s: hp70/70 u16 pair @0x%x", name, i);
            hits++;
        }
    }
    // u32 value 70 (0x46 00 00 00), aligned — the port uses int fields widely
    hits = 0;
    for (int i = 0; i + 8 <= len && hits < 24; i += 4) {
        if (*(uint32_t *)(base + i) == 70) {
            LOGI("scan %s: u32 70 @0x%x", name, i);
            hits++;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeScan(JNIEnv *env, jclass cls) {
    uint8_t *sfc = sfc_work();
    if (sfc) scan_region("cSfcWork", sfc, 0x10000);
    if (g_asm_mem_slot && plausible_ptr(*g_asm_mem_slot)) {
        scan_region("asmMem", *g_asm_mem_slot, 0x30000);
    }
}

// ---------------------------------------------------------------------------
// Location name: ChronoCanvas::getFieldMapName() returns std::string by value
// (sret). Must run on the GL thread; result cached for the UI poller.
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeUpdateMapName(JNIEnv *env, jclass cls) {
    if (!p_getFieldMapName || !p_getInstance) return;
    void *canvas = p_getInstance();
    if (!canvas) return;
    CppStr s = p_getFieldMapName(canvas);
    uint8_t *b = s.raw;
    if ((b[0] & 1) == 0) {
        int len = b[0] >> 1;
        if (len > 22) len = 22;
        memcpy(g_map_name, b + 1, len);
        g_map_name[len] = 0;
    } else {
        uint64_t len; char *data;
        memcpy(&len, b + 8, 8);
        memcpy(&data, b + 16, 8);
        if (len < sizeof(g_map_name) && data) {
            memcpy(g_map_name, data, len);
            g_map_name[len] = 0;
        }
        // long-mode heap buffer intentionally not freed (never hit for CT names)
    }
}

JNIEXPORT jstring JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeGetMapName(JNIEnv *env, jclass cls) {
    return (*env)->NewStringUTF(env, g_map_name);
}

// ---------------------------------------------------------------------------
// Battle flag: is the running scene (or a shallow child) a battle scene?
// Must run on the GL thread (scene graph unsafe off-thread) -- cached like
// the map name above.
// ---------------------------------------------------------------------------

static int g_in_battle;
static void *g_battle_node;   // cached SceneBattle instance ptr (GL thread writes, any thread reads)
static int g_battle_was;      // previous g_in_battle value, for edge-triggered "started/ended" logging

// ---------------------------------------------------------------------------
// Scene-graph access (must run on the GL thread via Cocos2dxHelper.runOnGLThread)
// ---------------------------------------------------------------------------

static int plausible_any(const void *p) {
    uintptr_t v = (uintptr_t)p & 0x00ffffffffffffffull;
    return v > 0x1000000ull && v < (1ull << 48);
}

// Fault-proof read: validates the address instead of crashing on garbage.
static int safe_read(const void *addr, void *out, size_t len) {
    if (!plausible_any(addr)) return 0;
    struct iovec local = { out, len };
    struct iovec remote = { (void *)addr, len };
    return process_vm_readv(getpid(), &local, 1, &remote, 1, 0) == (ssize_t)len;
}

// Itanium ABI: vtable[-1] = type_info*, whose name char* sits at +8.
// Copies the class name into buf; NULL if any link in the chain is bogus.
static const char *type_name(void *obj, char *buf, int cap) {
    void **vt; void *ti; char *nm;
    if (!plausible_ptr(obj)) return NULL;
    if (!safe_read(obj, &vt, 8) || !plausible_ptr(vt)) return NULL;
    if (!safe_read(vt - 1, &ti, 8) || !plausible_ptr(ti)) return NULL;
    if (!safe_read((char *)ti + 8, &nm, 8) || !plausible_any(nm)) return NULL;
    if (!safe_read(nm, buf, (size_t)cap - 1)) return NULL;
    buf[cap - 1] = 0;
    for (char *c = buf; *c; c++) {
        if (*c < 0x21 || *c > 0x7e) { *c = 0; break; }
    }
    return buf[0] ? buf : NULL;
}

// libc++ std::string (SSO): even first byte = short (len = b0>>1, data at +1);
// odd = long (data ptr at +16, size at +8).
static const char *sso_cstr(void *str, char *buf, int cap) {
    uint8_t s[24];
    buf[0] = 0;
    if (!safe_read(str, s, 24)) return buf;
    if ((s[0] & 1) == 0) {
        int len = s[0] >> 1;
        if (len >= cap) len = cap - 1;
        if (len > 22) len = 22;
        memcpy(buf, s + 1, len);
        buf[len] = 0;
    } else {
        uint64_t len; char *data;
        memcpy(&len, s + 8, 8);
        memcpy(&data, s + 16, 8);
        if (len < (uint64_t)cap && safe_read(data, buf, len)) buf[len] = 0;
        else buf[0] = 0;
    }
    return buf;
}

static void *find_running_scene(void) {
    char tb[96];
    if (!p_dir_getInstance) return NULL;
    uint8_t *dir = (uint8_t *)p_dir_getInstance();
    if (!plausible_ptr(dir)) return NULL;
    for (int off = 0; off < 0x300; off += 8) {
        void *p;
        if (!safe_read(dir + off, &p, 8)) continue;
        const char *tn = type_name(p, tb, sizeof(tb));
        if (tn && strstr(tn, "Scene")) {
            return p;
        }
    }
    return NULL;
}

// Depth-<=2 check: the scene itself (depth 0) or a direct child (depth 1)
// with an RTTI type name containing "Battle". Cheap and shallow on purpose --
// no full tree walk needed just to answer "are we in a battle".
static int node_or_children_is_battle(void *node, int depth, int max_depth) {
    char tb[96];
    const char *tn = type_name(node, tb, sizeof(tb));
    if (tn && strstr(tn, "Battle")) return 1;
    if (depth >= max_depth || !p_node_getChildren) return 0;
    void *vecp = p_node_getChildren(node);
    void *ptrs[2];
    if (!safe_read(vecp, ptrs, 16)) return 0;
    void **begin = (void **)ptrs[0], **end = (void **)ptrs[1];
    if (!plausible_any(begin) || !plausible_any(end) || end < begin
            || (end - begin) > 512) return 0;
    for (void **c = begin; c < end; c++) {
        void *child;
        if (safe_read(c, &child, 8) &&
                node_or_children_is_battle(child, depth + 1, max_depth)) return 1;
    }
    return 0;
}

// Recursive scene-graph search for a node whose RTTI type name contains
// `pat` (e.g. "SceneBattle"). Same child-walking shape as hide_walk/
// node_or_children_is_battle above. GL thread only -- the scene graph is
// unsafe to touch off-thread.
static void *find_node_by_type_rec(void *node, const char *pat, int depth, int max_depth) {
    char tb[96];
    const char *tn = type_name(node, tb, sizeof(tb));
    if (tn && strstr(tn, pat)) return node;
    if (depth >= max_depth || !p_node_getChildren) return NULL;
    void *vecp = p_node_getChildren(node);
    void *ptrs[2];
    if (!safe_read(vecp, ptrs, 16)) return NULL;
    void **begin = (void **)ptrs[0], **end = (void **)ptrs[1];
    if (!plausible_any(begin) || !plausible_any(end) || end < begin
            || (end - begin) > 512) return NULL;
    for (void **c = begin; c < end; c++) {
        void *child;
        if (safe_read(c, &child, 8)) {
            void *found = find_node_by_type_rec(child, pat, depth + 1, max_depth);
            if (found) return found;
        }
    }
    return NULL;
}

static void *find_node_by_type(void *root, const char *pat, int max_depth) {
    return find_node_by_type_rec(root, pat, 0, max_depth);
}

// Live battle HP/state lives behind a pointer at SceneBattle+0x8 (getwork8/
// getwork16 both do `x8 = *(this+0x8); return *(this_type*)(x8+idx)`), and
// SceneBattle::getNChara16 reads a second pointer at +0x68. CT battles are
// field-layer -- no dedicated battle Scene is pushed -- so the way to find
// the SceneBattle instance is a shallow scene-graph search for a node whose
// RTTI type name contains "SceneBattle" somewhere under the running scene.
// (An earlier heuristic read a flag byte at cSfcWork+0x7651; that proved
// wrong -- it read 0 during a real battle -- and is replaced by this.)
JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeUpdateBattleFlag(JNIEnv *env, jclass cls) {
    void *scene = find_running_scene();
    void *node = scene ? find_node_by_type(scene, "Battle", 2) : NULL;
    g_battle_node = node;
    g_in_battle = (node != NULL);
    if (node) {
        char tb[96];
        const char *tn = type_name(node, tb, sizeof(tb));
        LOGI("battle node: %p type=%s", node, tn ? tn : "?");
    }
    if (g_in_battle != g_battle_was) {
        LOGI("battle %s", g_in_battle ? "started" : "ended");
        g_battle_was = g_in_battle;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeGetBattleFlag(JNIEnv *env, jclass cls) {
    return g_in_battle ? JNI_TRUE : JNI_FALSE;
}

static int g_walk_count;
static void walk_node(void *node, int depth, int max_depth) {
    char tb[96], nb[64];
    if (g_walk_count > 300) return;
    const char *tn = type_name(node, tb, sizeof(tb));
    if (!tn) return; // no coherent RTTI -> not a live object, don't call methods
    g_walk_count++;
    const char *nm = p_node_getName ? sso_cstr(p_node_getName(node), nb, sizeof(nb)) : "";
    int vis = p_node_isVisible ? p_node_isVisible(node) : -1;
    LOGI("scene:%*s%p %s '%s' vis=%d", depth * 2, "", node, tn, nm, vis);
    if (depth >= max_depth || !p_node_getChildren) return;
    // cocos2d::Vector<Node*> wraps std::vector: {begin, end, cap}
    void *vecp = p_node_getChildren(node);
    void *ptrs[2];
    if (!safe_read(vecp, ptrs, 16)) return;
    void **begin = (void **)ptrs[0], **end = (void **)ptrs[1];
    if (!plausible_any(begin) || !plausible_any(end) || end < begin
            || (end - begin) > 512) return;
    for (void **c = begin; c < end; c++) {
        void *child;
        if (safe_read(c, &child, 8)) walk_node(child, depth + 1, max_depth);
    }
}

JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeSceneDump(JNIEnv *env, jclass cls, jint maxDepth) {
    void *scene = find_running_scene();
    if (!scene) { LOGI("scene: not found"); return; }
    g_walk_count = 0;
    walk_node(scene, 0, maxDepth);
    LOGI("scene: dump done, %d nodes", g_walk_count);
}

// Hide/show any node whose RTTI type name or node name contains `pat`.
static int g_hide_hits;
static void hide_walk(void *node, int depth, const char *pat, int visible) {
    char tb[96], nb[64];
    if (depth > 12) return;
    const char *tn = type_name(node, tb, sizeof(tb));
    if (!tn) return;
    const char *nm = p_node_getName ? sso_cstr(p_node_getName(node), nb, sizeof(nb)) : "";
    if (strstr(tn, pat) || (nm[0] && strstr(nm, pat))) {
        if (p_node_setVisible) { p_node_setVisible(node, visible); g_hide_hits++; }
        return;
    }
    void *vecp = p_node_getChildren(node);
    void *ptrs[2];
    if (!safe_read(vecp, ptrs, 16)) return;
    void **begin = (void **)ptrs[0], **end = (void **)ptrs[1];
    if (!plausible_any(begin) || !plausible_any(end) || end < begin
            || (end - begin) > 512) return;
    for (void **c = begin; c < end; c++) {
        void *child;
        if (safe_read(c, &child, 8)) hide_walk(child, depth + 1, pat, visible);
    }
}

JNIEXPORT jint JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeSetVisibleByPattern(JNIEnv *env, jclass cls,
                                                                    jstring jpat, jboolean visible) {
    const char *pat = (*env)->GetStringUTFChars(env, jpat, NULL);
    void *scene = find_running_scene();
    g_hide_hits = 0;
    if (scene) hide_walk(scene, 0, pat, visible);
    (*env)->ReleaseStringUTFChars(env, jpat, pat);
    return g_hide_hits;
}

// Dump whole regions to files for offline analysis (adb pull + python).
static void dump_file(const char *path, uint8_t *base, int len) {
    if (!plausible_ptr(base)) return;
    FILE *f = fopen(path, "wb");
    if (!f) { LOGI("dump: cannot open %s", path); return; }
    fwrite(base, 1, len, f);
    fclose(f);
    LOGI("dumped %d bytes to %s", len, path);
}

// Like dump_file, but reads via safe_read in 4KB chunks and zero-fills any
// chunk that faults, instead of direct-memcpy'ing an uncertain-sized region.
static void dump_file_safe(const char *path, uint8_t *base, size_t len) {
    // range-only guard: safe_read tolerates bad memory per-chunk, and real
    // game pointers can be 4-byte aligned (plausible_ptr would reject them)
    if (!plausible_any(base)) { LOGI("dump: bad base %p for %s", (void *)base, path); return; }
    FILE *f = fopen(path, "wb");
    if (!f) { LOGI("dump: cannot open %s", path); return; }
    uint8_t chunk[4096];
    size_t off = 0;
    size_t fail_chunks = 0, total_chunks = 0;
    size_t first_fail_off = (size_t)-1;
    while (off < len) {
        size_t n = (len - off < sizeof(chunk)) ? (len - off) : sizeof(chunk);
        total_chunks++;
        if (!safe_read(base + off, chunk, n)) {
            memset(chunk, 0, n);
            fail_chunks++;
            if (first_fail_off == (size_t)-1) first_fail_off = off;
        }
        fwrite(chunk, 1, n, f);
        off += n;
    }
    fclose(f);
    if (fail_chunks) {
        LOGI("dumped %zu bytes (safe) to %s: %zu/%zu chunks failed, first fail @0x%zx",
             len, path, fail_chunks, total_chunks, first_fail_off);
    } else {
        LOGI("dumped %zu bytes (safe) to %s: all %zu chunks ok", len, path, total_chunks);
    }
}

JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeDumpToFiles(JNIEnv *env, jclass cls, jstring jdir) {
    const char *dir = (*env)->GetStringUTFChars(env, jdir, NULL);
    char path[512];
    uint8_t *sfc = sfc_work();
    if (sfc) {
        snprintf(path, sizeof(path), "%s/sfcwork.bin", dir);
        dump_file(path, sfc, 0x10000);
    }
    if (g_asm_mem_slot && plausible_ptr(*g_asm_mem_slot)) {
        snprintf(path, sizeof(path), "%s/asmmem.bin", dir);
        dump_file(path, *g_asm_mem_slot, 0x30000);
    }
    // asmmem2.bin: Asm buffer bytes 0x30000..0x80000 -- cSfcWork::GetBattleRam(int)
    // maps battle indexes in here, just past where asmmem.bin stops. Size beyond
    // the real buffer is uncertain, so read safely in 4KB chunks.
    if (g_asm_mem_slot && plausible_ptr(*g_asm_mem_slot)) {
        uint8_t *mem = *g_asm_mem_slot;
        LOGI("asmmem2 src: asm_base=%p asm_base+0x30000=%p", (void *)mem, (void *)(mem + 0x30000));
        snprintf(path, sizeof(path), "%s/asmmem2.bin", dir);
        dump_file_safe(path, mem + 0x30000, 0x50000);
    }
    // btldata.bin: cSfcWork::GetSendBtlDataa() = *(cSfcWork + 0xc0f0), a heap
    // pointer to the live battle data block.
    if (sfc) {
        uint8_t *btl_ptr = NULL;
        int got = safe_read(sfc + GETSENDBTLDATA_OFFSET, &btl_ptr, sizeof(btl_ptr));
        LOGI("btldata src: sfc+0xc0f0=%p -> ptr=%p (read_ok=%d plausible=%d)",
             (void *)(sfc + GETSENDBTLDATA_OFFSET), (void *)btl_ptr, got,
             got ? plausible_ptr(btl_ptr) : 0);
        // the live pointer is only 4-byte aligned (observed 0x...17fc), so
        // plausible_ptr's 8-byte alignment test wrongly rejects it — check
        // range with 4-byte alignment here instead.
        uintptr_t v = (uintptr_t)btl_ptr & 0x00ffffffffffffffull;
        if (got && v > 0x1000000ull && v < (1ull << 48) && (v & 3) == 0) {
            snprintf(path, sizeof(path), "%s/btldata.bin", dir);
            dump_file_safe(path, btl_ptr, 0x8000);
        } else {
            LOGI("btldata: skipped, implausible pointer");
        }
    }
    (*env)->ReleaseStringUTFChars(env, jdir, dir);
}

// Dump the live battle work buffers, using the SceneBattle node cached by
// nativeUpdateBattleFlag. Plain safe_read on cached pointers -- does NOT
// need the GL thread (only touching the scene graph itself does).
#define BTLWORK_OFFSET  0x8
#define BTLCHARA_OFFSET 0x68

JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeDumpBattleBuffers(JNIEnv *env, jclass cls,
                                                                   jstring jdir) {
    if (!g_battle_node) { LOGI("dumpBattleBuffers: no battle node cached"); return; }
    const char *dir = (*env)->GetStringUTFChars(env, jdir, NULL);
    char path[512];

    // Battle (the cocos node) is a facade; the engine object is SceneBattle at
    // Battle+0x320 (Battle::update/isActive/setField all delegate through it).
    // SceneBattle+0x8 = work buffer (getwork8/16), +0x68 = chara buffer
    // (getNChara16); the object itself extends past +0x31D1 (isActive flag).
    uint8_t *sb = NULL;
    int got_sb = safe_read((uint8_t *)g_battle_node + 0x320, &sb, sizeof(sb));
    if (!got_sb || !plausible_any(sb)) {
        LOGI("battle buffers: SceneBattle ptr bad (ok=%d %p)", got_sb, (void *)sb);
        (*env)->ReleaseStringUTFChars(env, jdir, dir);
        return;
    }
    uint8_t *work_ptr = NULL, *chara_ptr = NULL;
    int got_work = safe_read(sb + BTLWORK_OFFSET, &work_ptr, sizeof(work_ptr));
    int got_chara = safe_read(sb + BTLCHARA_OFFSET, &chara_ptr, sizeof(chara_ptr));
    LOGI("battle buffers: node=%p scenebattle=%p work=%p(ok=%d p=%d) chara=%p(ok=%d p=%d)",
         g_battle_node, (void *)sb,
         (void *)work_ptr, got_work, got_work && plausible_any(work_ptr),
         (void *)chara_ptr, got_chara, got_chara && plausible_any(chara_ptr));

    snprintf(path, sizeof(path), "%s/btlobj.bin", dir);
    dump_file_safe(path, sb, 0x4000);
    if (got_work && plausible_any(work_ptr)) {
        snprintf(path, sizeof(path), "%s/btlwork.bin", dir);
        dump_file_safe(path, work_ptr, 0x10000);
    }
    if (got_chara && plausible_any(chara_ptr)) {
        snprintf(path, sizeof(path), "%s/btlchara.bin", dir);
        dump_file_safe(path, chara_ptr, 0x8000);
    }

    (*env)->ReleaseStringUTFChars(env, jdir, dir);
}

// Live battle actor array snapshot for the second-screen panel poller. Same
// pointer chase as nativeDumpBattleBuffers (g_battle_node -> +0x320 SceneBattle
// -> +0x68 chara array), but returns the raw bytes directly instead of writing
// files, and is meant to be called every UI poll tick (~500ms) -- NOT the GL
// thread. Actor array layout (SNES-heritage, verified live): stride 0x80 per
// actor, at least 10 slots; +0x03 = current HP, +0x05 = max HP (u16 LE).
// Slots 0-2 are the party (in party order), slots 3+ are enemies (a slot is
// "present" if max HP > 0). Returns NULL whenever any link in the chain is
// missing/implausible or no battle is active -- callers must treat that as
// "not in battle" and fall back to field mode.
#define BTLCHARA_STRIDE 0x80
#define BTLCHARA_SLOTS  10

JNIEXPORT jbyteArray JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeReadBattleActors(JNIEnv *env, jclass cls) {
    if (!g_battle_node) return NULL;

    uint8_t *sb = NULL;
    if (!safe_read((uint8_t *)g_battle_node + 0x320, &sb, sizeof(sb)) || !plausible_any(sb)) {
        return NULL;
    }
    uint8_t *chara_ptr = NULL;
    if (!safe_read(sb + BTLCHARA_OFFSET, &chara_ptr, sizeof(chara_ptr)) || !plausible_any(chara_ptr)) {
        return NULL;
    }

    size_t len = BTLCHARA_SLOTS * BTLCHARA_STRIDE;
    uint8_t buf[BTLCHARA_SLOTS * BTLCHARA_STRIDE];
    if (!safe_read(chara_ptr, buf, len)) return NULL;

    jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
    if (!arr) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)buf);
    return arr;
}

// Raw bytes of the embedded cSfcWork object itself (for calibration dumps).
JNIEXPORT jbyteArray JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeReadSfc(JNIEnv *env, jclass cls, jint off, jint len) {
    if (len <= 0 || len > 0x10000 || off < 0) return NULL;
    uint8_t *sfc = sfc_work();
    if (!sfc) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)(sfc + off));
    return arr;
}
