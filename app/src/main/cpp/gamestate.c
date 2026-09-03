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

static void *(*p_getInstance)(void);
static uint8_t **g_asm_mem_slot;  // libchrono base + 0xbeeba8: virtual SNES memory ptr
static void *(*p_dir_getInstance)(void);
static void *(*p_node_getName)(void *);      // returns const std::string&
static void *(*p_node_getChildren)(void *);  // returns cocos2d::Vector<Node*>&
static int   (*p_node_isVisible)(void *);
static void  (*p_node_setVisible)(void *, int);

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
    (*env)->ReleaseStringUTFChars(env, jdir, dir);
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
