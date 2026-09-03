// Reads live game state out of libchrono.so for the second-screen UI.
//
// Layout facts recovered from the binary (see NOTES.md):
//   ChronoCanvas::getInstance()            exported, returns the root singleton
//   cSfcWork                               embedded object at canvas + 0x40
//   cSfcWork::GetCharaData(i)              returns this + 0x6924 + i*0x154
//   virtual SNES memory                    pointer held in object members
//                                          (e.g. SceneBattle+0x8); bank $7E
//                                          lives at index 0x20000
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>

#define TAG "ChronoDuoNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define SFC_WORK_OFFSET   0x40
#define CHARA_BASE        0x6924
#define CHARA_STRIDE      0x154

static void *(*p_getInstance)(void);
static uint8_t **g_asm_mem_slot;  // libchrono base + 0xbeeba8: virtual SNES memory ptr

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
    LOGI("attach: getInstance=%p canvas=%p asm_slot=%p", (void *)p_getInstance,
         p_getInstance ? p_getInstance() : NULL, (void *)g_asm_mem_slot);
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
