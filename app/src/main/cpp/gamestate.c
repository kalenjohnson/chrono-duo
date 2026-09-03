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
static void  (*p_node_setPosition)(void *, const void *); // (this, const Vec2*)
// nsSpriteUtils::setCascadeOpacityEnabledRecursive(Node*, bool) -- plain free
// function (not a Node member): recursively flips cascade-opacity on so a
// later setOpacity() on the container actually propagates to its children
// instead of only dimming the container itself.
static void  (*p_setCascadeOpacityEnabledRecursive)(void *, int);
static void  (*p_node_setOpacity)(void *, uint8_t); // instance: (this, GLubyte)

// cocos2d::Size/Vec2 are HFAs (two floats) -- returned in s0/s1 per the arm64
// AAPCS, so plain C struct-by-value declarations match the real ABI.
typedef struct { float w, h; } CCSize;
typedef struct { float x, y; } CCVec2;
static int safe_read(const void *addr, void *out, size_t len);

// cocos2d::Node member offsets (3.14.1 arm64, from accessor disassembly):
// getPosition->this+0x50, getAnchorPoint->+0x78, getContentSize->+0x80,
// getParent->ldr [this,#0x190], isVisible->ldrb [this,#0x1f9]
#define NODE_POSITION 0x50
#define NODE_ANCHOR   0x78
#define NODE_CONTENT  0x80
#define NODE_PARENT   0x190
#define NODE_VISIBLE  0x1f9
// _tag (int) derived, not disassembled: walking cocos2d/2d/CCNode.h's
// protected member list in declaration order from _parent (trusted +0x190)
// -- Node* _parent(8) @0x190, Director* _director(8) @0x198, int _tag(4)
// @0x1a0 -- and continuing the same walk through _name/_hashOfName/
// _userData/_userObject/_glProgramState/_scheduler/_actionManager/
// _eventDispatcher/_running lands bool _visible at exactly 0x1f9, matching
// the independently-trusted NODE_VISIBLE constant with zero slack. That
// exact match validates the counting (including the earlier Mat4-sized
// _modelViewTransform/_transform/_inverse block between _contentSize and
// _parent, since NODE_PARENT=0x190 was hit exactly too), so 0x1a0 is used
// with confidence.
#define NODE_TAG 0x1a0
// Opacity (_displayedOpacity/_realOpacity, GLubyte) is intentionally NOT
// offset here: the fields after _visible depend on whether
// CC_ENABLE_SCRIPT_BINDING was compiled in (it inserts two ints + an enum
// before _componentContainer), which cannot be determined from the header
// alone and isn't covered by any trusted accessor offset in this file, so
// getting it wrong would silently print garbage. Skipped.
// cocos2d::Label's _utf8Text (CCLabel.h L691) is also skipped: Label uses
// multiple inheritance (Node, LabelProtocol, BlendProtocol) and _utf8Text
// sits after ~70 lines of intervening protected members (CCLabel.h
// L623-691) with no trusted accessor offset to anchor a derivation the way
// NODE_PARENT/NODE_VISIBLE anchor the Node fields above -- not confidently
// derivable from the header alone.

// World-space center of a node via pure safe_read parent-chain walk (ignores
// scale/rotation — fine for the unscaled battle menu). Engine transform calls
// (convertToWorldSpace) crashed on mid-destruction toggles; this cannot.
static int node_world_center(void *node, float *ox, float *oy) {
    float pt[2], cs0[2];
    if (!safe_read((uint8_t *)node + NODE_CONTENT, cs0, 8)) return 0;
    pt[0] = cs0[0] * 0.5f;
    pt[1] = cs0[1] * 0.5f;
    void *n = node;
    for (int i = 0; i < 12 && n; i++) {
        float pos[2], anc[2], csz[2];
        void *parent;
        if (!safe_read((uint8_t *)n + NODE_POSITION, pos, 8)) return 0;
        if (!safe_read((uint8_t *)n + NODE_ANCHOR, anc, 8)) return 0;
        if (!safe_read((uint8_t *)n + NODE_CONTENT, csz, 8)) return 0;
        if (!safe_read((uint8_t *)n + NODE_PARENT, &parent, 8)) return 0;
        pt[0] = pos[0] + (pt[0] - anc[0] * csz[0]);
        pt[1] = pos[1] + (pt[1] - anc[1] * csz[1]);
        n = parent;
    }
    *ox = pt[0];
    *oy = pt[1];
    return 1;
}

// cocos2d-x 3.14.1: virtual const Size& getContentSize() const — returns a
// REFERENCE (pointer in x0), not a by-value HFA; read the floats through it.
static const void *(*p_node_getContentSize)(void *);
static uint8_t *g_lib_base;

// A live cocos object's vtable must point into libchrono.so's mapping; stale
// or reused heap can pass the RTTI readability checks with a garbage vtable
// whose virtual dispatch (inside convertToWorldSpace) jumps to junk.
static int vtable_in_libchrono(void *obj) {
    void *vt;
    if (!g_lib_base || !safe_read(obj, &vt, 8)) return 0;
    uintptr_t delta = (uintptr_t)vt - (uintptr_t)g_lib_base;
    return delta < 0x1000000;
}
static CCVec2 (*p_node_convertToWorldSpace)(void *, const CCVec2 *);

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
            g_lib_base = (uint8_t *)info.dli_fbase;
            g_asm_mem_slot = (uint8_t **)(g_lib_base + ASM_MEM_GLOBAL);
        }
    }
    p_getFieldMapName = (CppStr (*)(void *)) dlsym(h, "_ZNK12ChronoCanvas15getFieldMapNameEv");
    p_dir_getInstance = (void *(*)(void)) dlsym(h, "_ZN7cocos2d8Director11getInstanceEv");
    p_node_getName = (void *(*)(void *)) dlsym(h, "_ZNK7cocos2d4Node7getNameEv");
    p_node_getChildren = (void *(*)(void *)) dlsym(h, "_ZN7cocos2d4Node11getChildrenEv");
    p_node_isVisible = (int (*)(void *)) dlsym(h, "_ZNK7cocos2d4Node9isVisibleEv");
    p_node_setVisible = (void (*)(void *, int)) dlsym(h, "_ZN7cocos2d4Node10setVisibleEb");
    p_node_setPosition = (void (*)(void *, const void *))
        dlsym(h, "_ZN7cocos2d4Node11setPositionERKNS_4Vec2E");
    p_node_getContentSize = (const void *(*)(void *)) dlsym(h, "_ZNK7cocos2d4Node14getContentSizeEv");
    p_node_convertToWorldSpace = (CCVec2 (*)(void *, const CCVec2 *))
        dlsym(h, "_ZNK7cocos2d4Node19convertToWorldSpaceERKNS_4Vec2E");
    p_setCascadeOpacityEnabledRecursive = (void (*)(void *, int))
        dlsym(h, "_ZN13nsSpriteUtils33setCascadeOpacityEnabledRecursiveEPN7cocos2d4NodeEb");
    p_node_setOpacity = (void (*)(void *, uint8_t)) dlsym(h, "_ZN7cocos2d4Node10setOpacityEh");
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
static int g_last_logged_selected = -1; // toggle index last logged as selected, for on-change-only logging

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

// ---------------------------------------------------------------------------
// Battle command toggles: the Battle node's cocos2d::Menu contains ~17
// MenuItemToggle children (Attack/Tech/Item/etc.), a handful visible at any
// time. For each, cache its on-screen center (worldspace, via
// convertToWorldSpace on {contentSize/2}) and effective visibility (its own
// isVisible AND every ancestor's, down to the Battle node) so a later phase
// can mirror the buttons on the second screen and forward taps. GL thread
// only -- called from nativeUpdateBattleFlag, which already runs there.
// ---------------------------------------------------------------------------

// MenuItemToggle member offsets (arm64, from setter disasm): _selected (bool,
// u8) at +0x2F8, _selectedIndex (u32) at +0x330.
#define TOGGLE_SELECTED_OFFSET       0x2F8
#define TOGGLE_SELECTED_INDEX_OFFSET 0x330

#define MAX_BATTLE_TOGGLES 24
typedef struct { float x, y; int visible; int selected; uint32_t selectedIndex; } BattleToggle;
static BattleToggle g_battle_toggles[MAX_BATTLE_TOGGLES];
static int g_battle_toggle_count;

static void collect_toggles_rec(void *node, int depth, int max_depth, int ancestors_visible) {
    if (g_battle_toggle_count >= MAX_BATTLE_TOGGLES) return;
    char tb[96];
    const char *tn = type_name(node, tb, sizeof(tb));
    if (!tn) return;
    uint8_t visb = 0;
    int vis = safe_read((uint8_t *)node + NODE_VISIBLE, &visb, 1) && visb;
    int this_visible = ancestors_visible && vis;
    if (strstr(tn, "MenuItemToggle")) {
        float wx, wy;
        if (node_world_center(node, &wx, &wy)) {
            uint8_t selb = 0;
            uint32_t selIdx = 0;
            safe_read((uint8_t *)node + TOGGLE_SELECTED_OFFSET, &selb, 1);
            safe_read((uint8_t *)node + TOGGLE_SELECTED_INDEX_OFFSET, &selIdx, 4);
            g_battle_toggles[g_battle_toggle_count].x = wx;
            g_battle_toggles[g_battle_toggle_count].y = wy;
            g_battle_toggles[g_battle_toggle_count].visible = this_visible ? 1 : 0;
            g_battle_toggles[g_battle_toggle_count].selected = selb ? 1 : 0;
            g_battle_toggles[g_battle_toggle_count].selectedIndex = selIdx;
            g_battle_toggle_count++;
        }
        return; // toggles have no meaningful children to recurse into
    }
    if (depth >= max_depth || !p_node_getChildren) return;
    void *vecp = p_node_getChildren(node);
    void *ptrs[2];
    if (!safe_read(vecp, ptrs, 16)) return;
    void **begin = (void **)ptrs[0], **end = (void **)ptrs[1];
    if (!plausible_any(begin) || !plausible_any(end) || end < begin
            || (end - begin) > 512) return;
    for (void **c = begin; c < end; c++) {
        void *child;
        if (safe_read(c, &child, 8) && g_battle_toggle_count < MAX_BATTLE_TOGGLES)
            collect_toggles_rec(child, depth + 1, max_depth, this_visible);
    }
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
    g_battle_toggle_count = 0;
    if (node) {
        char tb[96];
        const char *tn = type_name(node, tb, sizeof(tb));
        LOGI("battle node: %p type=%s", node, tn ? tn : "?");
        collect_toggles_rec(node, 0, 3, 1);
        for (int i = 0; i < g_battle_toggle_count; i++) {
            if (g_battle_toggles[i].visible) {
                LOGI("battle toggle[%d]: x=%.1f y=%.1f vis=1", i,
                     g_battle_toggles[i].x, g_battle_toggles[i].y);
            }
        }
        // Selection mirroring verification: log (battle only, on change
        // only) which toggle index reports _selected, so a dpad move on the
        // controller's cursor can be confirmed to reach the panel's
        // highlight within one poll tick.
        int sel_idx = -1;
        for (int i = 0; i < g_battle_toggle_count; i++) {
            if (g_battle_toggles[i].selected) { sel_idx = i; break; }
        }
        if (sel_idx != g_last_logged_selected) {
            LOGI("battle selection: toggle[%d] selected (was %d)", sel_idx, g_last_logged_selected);
            g_last_logged_selected = sel_idx;
        }
    } else {
        g_last_logged_selected = -1; // battle ended/not found -- reset so re-entry logs fresh
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

// Cached battle command toggles, as flat [x0,y0,vis0,sel0,selIdx0, ...]
// quintuples (vis/sel are 0.0/1.0; selIdx is the toggle's raw _selectedIndex,
// cast to float -- exact for the small ints this field ever holds). Empty
// array when not in battle. Populated on the GL thread by
// nativeUpdateBattleFlag; safe to call from any thread (plain read of the
// cached array, single-writer/racy-reader like the rest of this file).
JNIEXPORT jfloatArray JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeGetBattleToggles(JNIEnv *env, jclass cls) {
    int count = g_battle_toggle_count;
    jfloatArray arr = (*env)->NewFloatArray(env, count * 5);
    if (!arr) return NULL;
    if (count > 0) {
        float buf[MAX_BATTLE_TOGGLES * 5];
        for (int i = 0; i < count; i++) {
            buf[i * 5 + 0] = g_battle_toggles[i].x;
            buf[i * 5 + 1] = g_battle_toggles[i].y;
            buf[i * 5 + 2] = g_battle_toggles[i].visible ? 1.0f : 0.0f;
            buf[i * 5 + 3] = g_battle_toggles[i].selected ? 1.0f : 0.0f;
            buf[i * 5 + 4] = (float) g_battle_toggles[i].selectedIndex;
        }
        (*env)->SetFloatArrayRegion(env, arr, 0, count * 5, buf);
    }
    return arr;
}

static int g_walk_count;
static int g_walk_cap_hit;
static void walk_node(void *node, int depth, int max_depth) {
    char tb[96], nb[64];
    if (g_walk_count > 2000) { g_walk_cap_hit = 1; return; }
    const char *tn = type_name(node, tb, sizeof(tb));
    if (!tn) return; // no coherent RTTI -> not a live object, don't call methods
    g_walk_count++;
    const char *nm = p_node_getName ? sso_cstr(p_node_getName(node), nb, sizeof(nb)) : "";
    int vis = p_node_isVisible ? p_node_isVisible(node) : -1;

    // Geometry via safe_read only -- see node_world_center comment above for
    // why we avoid calling engine methods (other than the getName/isVisible/
    // getChildren already used elsewhere) on nodes that may be mid-destruction.
    float pos[2] = {0, 0}, anc[2] = {0, 0}, csz[2] = {0, 0};
    int32_t tag = 0;
    int got_pos = safe_read((uint8_t *)node + NODE_POSITION, pos, 8);
    int got_anc = safe_read((uint8_t *)node + NODE_ANCHOR, anc, 8);
    int got_csz = safe_read((uint8_t *)node + NODE_CONTENT, csz, 8);
    int got_tag = safe_read((uint8_t *)node + NODE_TAG, &tag, 4);
    float wx = 0, wy = 0;
    int got_world = node_world_center(node, &wx, &wy);
    // opacity is intentionally not read here -- see NODE_TAG comment block
    // above for why the offset isn't confidently derivable.

    // cocos2d::Vector<Node*> wraps std::vector: {begin, end, cap}
    void *vecp = p_node_getChildren ? p_node_getChildren(node) : NULL;
    void *ptrs[2] = {0, 0};
    int child_ok = vecp && safe_read(vecp, ptrs, 16);
    void **begin = (void **)ptrs[0], **end = (void **)ptrs[1];
    if (child_ok && (!plausible_any(begin) || !plausible_any(end) || end < begin
            || (end - begin) > 512)) {
        child_ok = 0;
    }
    int child_count = child_ok ? (int)(end - begin) : -1;

    // cocos2d::Label string is intentionally not read here -- see the
    // comment above NODE_TAG for why _utf8Text's offset isn't confidently
    // derivable from CCLabel.h alone (multiple inheritance, no trusted
    // anchor to walk from).

    if (got_world) {
        LOGI("scene:%*s%p %s '%s' vis=%d pos=(%.1f,%.1f) size=(%.1f,%.1f) "
             "anchor=(%.2f,%.2f) tag=%d children=%d world=(%.1f,%.1f)",
             depth * 2, "", node, tn, nm, vis,
             got_pos ? pos[0] : 0.0f, got_pos ? pos[1] : 0.0f,
             got_csz ? csz[0] : 0.0f, got_csz ? csz[1] : 0.0f,
             got_anc ? anc[0] : 0.0f, got_anc ? anc[1] : 0.0f,
             got_tag ? tag : 0, child_count, wx, wy);
    } else {
        LOGI("scene:%*s%p %s '%s' vis=%d pos=(%.1f,%.1f) size=(%.1f,%.1f) "
             "anchor=(%.2f,%.2f) tag=%d children=%d world=?",
             depth * 2, "", node, tn, nm, vis,
             got_pos ? pos[0] : 0.0f, got_pos ? pos[1] : 0.0f,
             got_csz ? csz[0] : 0.0f, got_csz ? csz[1] : 0.0f,
             got_anc ? anc[0] : 0.0f, got_anc ? anc[1] : 0.0f,
             got_tag ? tag : 0, child_count);
    }

    if (depth >= max_depth || !child_ok) return;
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
    g_walk_cap_hit = 0;
    walk_node(scene, 0, maxDepth);
    LOGI("scene: dump done, %d nodes%s", g_walk_count,
         g_walk_cap_hit ? " (cap hit)" : "");
}

// Small ring of recently-moved node addresses, so the off-screen-park log
// line fires once per node rather than every tick (tick runs at 700ms and
// these container nodes are long-lived, so this ring rarely wraps).
#define MOVED_RING_CAP 16
static void *g_moved_ring[MOVED_RING_CAP];
static int g_moved_ring_pos;
static int already_logged_move(void *node) {
    for (int i = 0; i < MOVED_RING_CAP; i++) {
        if (g_moved_ring[i] == node) return 1;
    }
    g_moved_ring[g_moved_ring_pos] = node;
    g_moved_ring_pos = (g_moved_ring_pos + 1) % MOVED_RING_CAP;
    return 0;
}

// WorldMenu (overworld Menu/Map buttons) re-asserts setVisible(true) every
// frame from the scene's own update -- a setVisible(false) here loses that
// per-frame war and the button never actually disappears. Parking the node's
// *position* far off-screen wins instead: nothing re-asserts position, and
// the node stays exactly as visible/invisible as the engine thinks (touch
// dispatch, layout, etc. all keep working normally), it's simply nowhere
// the camera or the touch hit-test can reach it. Only applied on the hide
// path (visible=false); the show path (visible=true, currently unused by any
// caller) intentionally leaves position alone since there is no
// previously-saved position to restore to.
#define OFFSCREEN_X -100000.0f
#define OFFSCREEN_Y -100000.0f

// Hide/show any node whose RTTI type name or node name contains `pat`.
static int g_hide_hits;
static void hide_walk(void *node, int depth, const char *pat, int visible) {
    char tb[96], nb[64];
    if (depth > 12) return;
    const char *tn = type_name(node, tb, sizeof(tb));
    if (!tn) return;
    const char *nm = p_node_getName ? sso_cstr(p_node_getName(node), nb, sizeof(nb)) : "";
    if (strstr(tn, pat) || (nm[0] && strstr(nm, pat))) {
        // Only call engine methods on nodes with coherent RTTI whose vtable
        // still points into libchrono.so's own mapping (see
        // vtable_in_libchrono comment above node_world_center) -- these
        // Field/World menu containers are stable scene children (unlike the
        // churning battle nodes that crashed on this check historically),
        // but the guard costs nothing and keeps the invariant uniform.
        if (vtable_in_libchrono(node)) {
            if (p_node_setVisible) { p_node_setVisible(node, visible); g_hide_hits++; }
            if (!visible && p_node_setPosition) {
                CCVec2 off = { OFFSCREEN_X, OFFSCREEN_Y };
                p_node_setPosition(node, &off);
                if (!already_logged_move(node)) {
                    LOGI("clean-ui: parked %s '%s' (%p) off-screen", tn, nm, node);
                }
            }
        }
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

// ---------------------------------------------------------------------------
// Frame-perfect enforcer: a per-rendered-frame GL-thread tick, queued from
// Java as a self-reposting Runnable (see GameState.startFrameEnforcer/
// FRAME_ENFORCER_TICK) via Cocos2dxGLSurfaceView.queueEvent -- C code here
// cannot queue GL runnables itself, only Java can. nativeStartFrameEnforcer
// is just an idempotent start-once gate so a repeat call from Java (e.g. a
// second attach) is a harmless no-op instead of stacking a second repost
// loop. The tick itself (nativeEnforceUiTick) is kept deliberately cheap:
// depth<=2 park sweep for FieldMenu/WorldMenu (a handful of nodes near the
// scene root, not a full tree walk like nativeSceneDump/hide_walk), plus,
// when in battle and enabled, a direct-children-only sweep of the battle
// node's command menus.
// ---------------------------------------------------------------------------

static int g_frame_enforcer_started;

JNIEXPORT jboolean JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeStartFrameEnforcer(JNIEnv *env, jclass cls) {
    if (g_frame_enforcer_started) return JNI_FALSE;
    g_frame_enforcer_started = 1;
    LOGI("frame enforcer: starting");
    return JNI_TRUE;
}

// FieldMenu/WorldMenu park sweep for the per-frame tick: same park action as
// hide_walk's hide path (setVisible false + offscreen setPosition, logged
// once per address via the same g_moved_ring), but shallow on purpose --
// depth 0 (scene) through depth 2 (grandchildren) only, matching
// node_or_children_is_battle's max_depth convention (a node AT max_depth is
// still checked, just not descended past).
static void enforce_park_walk(void *node, int depth, int max_depth) {
    char tb[96], nb[64];
    const char *tn = type_name(node, tb, sizeof(tb));
    if (!tn) return;
    const char *nm = p_node_getName ? sso_cstr(p_node_getName(node), nb, sizeof(nb)) : "";
    if (strstr(tn, "FieldMenu") || strstr(tn, "WorldMenu")
            || (nm[0] && (strstr(nm, "FieldMenu") || strstr(nm, "WorldMenu")))) {
        if (vtable_in_libchrono(node)) {
            if (p_node_setVisible) p_node_setVisible(node, 0);
            if (p_node_setPosition) {
                CCVec2 off = { OFFSCREEN_X, OFFSCREEN_Y };
                p_node_setPosition(node, &off);
            }
            if (!already_logged_move(node)) {
                LOGI("clean-ui: parked %s '%s' (%p) off-screen [enforcer]", tn, nm, node);
            }
        }
        return;
    }
    if (depth >= max_depth || !p_node_getChildren) return;
    void *vecp = p_node_getChildren(node);
    void *ptrs[2];
    if (!safe_read(vecp, ptrs, 16)) return;
    void **begin = (void **)ptrs[0], **end = (void **)ptrs[1];
    if (!plausible_any(begin) || !plausible_any(end) || end < begin
            || (end - begin) > 512) return;
    for (void **c = begin; c < end; c++) {
        void *child;
        if (safe_read(c, &child, 8)) enforce_park_walk(child, depth + 1, max_depth);
    }
}

// Ring of node addresses already handed to setCascadeOpacityEnabledRecursive,
// so that (expensive, and only needed once) call fires at most once per node
// -- separate from g_moved_ring since these are different nodes (battle
// command menus) hit by a different sweep.
#define OPACITY_RING_CAP 16
static void *g_opacity_ring[OPACITY_RING_CAP];
static int g_opacity_ring_pos;
static int already_cascade_set(void *node) {
    for (int i = 0; i < OPACITY_RING_CAP; i++) {
        if (g_opacity_ring[i] == node) return 1;
    }
    g_opacity_ring[g_opacity_ring_pos] = node;
    g_opacity_ring_pos = (g_opacity_ring_pos + 1) % OPACITY_RING_CAP;
    return 0;
}

// Battle top-UI hiding toggle (Java-settable, see nativeSetHideBattleUi).
// Defaults to enabled.
static int g_hide_battle_ui = 1;

JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeSetHideBattleUi(JNIEnv *env, jclass cls,
                                                                 jboolean hide) {
    g_hide_battle_ui = hide ? 1 : 0;
}

// Dev experiment hook: bit i (0-based) blanks direct child index i of
// g_battle_node's children vector, counted over every entry (including ones
// whose type_name lookup fails), regardless of type -- lets the user hide
// children one at a time to see what each one draws. We never restore
// opacity when a bit is cleared (this is a one-way dev hook, not a real
// toggle); reopening the affected menu or restarting battle is the reset.
static uint32_t g_battle_hide_mask = 0;

JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeSetBattleHideMask(JNIEnv *env, jclass cls,
                                                                   jint mask) {
    uint32_t m = (uint32_t)mask;
    if (m != g_battle_hide_mask) {
        LOGI("battle-ui: hide mask changed 0x%08x -> 0x%08x", g_battle_hide_mask, m);
        g_battle_hide_mask = m;
    }
}

// Opacity (not visibility/position) hiding of the battle command menus:
// touch hit-testing and the controller cursor both need the real node graph
// untouched (visible, at its real position) for the game's own input/tap-
// injection targeting to keep working -- only the pixels are hidden. Direct
// children of g_battle_node only (no recursion). The top screen mirror
// composites its own HUD, so every plain-engine chrome node here is
// redundant and gets blanked:
//   - cocos2d::Menu (plain, non-Battle*)  -- Attack/Combo/Item command toggles
//   - cocos2d::RenderTexture              -- the baked SNES-style HUD sprite
//                                             (party HP/MP frame, portraits,
//                                             ATB bars)
//   - cocos2d::Node (exact, plain engine) -- the HP/MP number label group
//   - cocos2d::Label (exact)              -- the "Attack"/"Tech"/"Item" text
// The nsBattleListMenu::BattleTechMenu / BattleItemMenu submenus (and
// anything else with "Battle" in its RTTI name) are deliberately excluded:
// we don't mirror their list contents on the bottom screen yet, so hiding
// them would leave tech/item selection invisible everywhere. Damage numbers
// and the target cursor live outside this set and are unaffected.
// The matching node gets cascade-opacity enabled (so setOpacity below
// actually propagates to children instead of only dimming the container),
// then setOpacity(0) applied EVERY tick, since the game may reassert its own
// opacity whenever the menu (re)opens.
static int should_hide_battle_child(const char *tn) {
    if (!tn) return 0;
    if (strstr(tn, "Battle")) return 0;
    if (strstr(tn, "cocos2d") && strstr(tn, "4MenuE")) return 1;
    if (strcmp(tn, "N7cocos2d13RenderTextureE") == 0) return 1;
    if (strcmp(tn, "N7cocos2d4NodeE") == 0) return 1;
    if (strcmp(tn, "N7cocos2d5LabelE") == 0) return 1;
    return 0;
}

static void enforce_battle_ui_hide(void) {
    if (!g_hide_battle_ui || !g_battle_node || !vtable_in_libchrono(g_battle_node)
            || !p_node_getChildren) {
        return;
    }
    void *vecp = p_node_getChildren(g_battle_node);
    void *ptrs[2];
    if (!safe_read(vecp, ptrs, 16)) return;
    void **begin = (void **)ptrs[0], **end = (void **)ptrs[1];
    if (!plausible_any(begin) || !plausible_any(end) || end < begin
            || (end - begin) > 512) return;
    int idx = -1;
    for (void **c = begin; c < end; c++) {
        idx++;
        void *child;
        if (!safe_read(c, &child, 8)) continue;
        char tb[96];
        const char *tn = type_name(child, tb, sizeof(tb));
        int hide_type = should_hide_battle_child(tn);
        // Dev hook: also hide direct child index `idx` when its bit is set
        // in g_battle_hide_mask, regardless of type -- see
        // nativeSetBattleHideMask above.
        int mask_hit = idx >= 0 && idx < 32 && (g_battle_hide_mask & (1u << idx));
        if (!hide_type && !mask_hit) continue;
        if (!vtable_in_libchrono(child)) continue;
        // Cascade must be (re)enabled EVERY tick, not once per node: the
        // toggles swap in freshly created child sprites each time the menu
        // opens, and children born after a one-shot cascade call render at
        // full opacity (observed live: window frames and labels stayed
        // visible while interiors faded). The recursive call is cheap at
        // this subtree size; the ring now only gates the log line.
        if (p_setCascadeOpacityEnabledRecursive) {
            p_setCascadeOpacityEnabledRecursive(child, 1);
            if (!already_cascade_set(child)) {
                LOGI("battle-ui: cascade-opacity enabled on %s (%p)", tn ? tn : "?", child);
            }
        }
        if (p_node_setOpacity) p_node_setOpacity(child, 0);
    }
}

JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeEnforceUiTick(JNIEnv *env, jclass cls) {
    void *scene = find_running_scene();
    if (scene) enforce_park_walk(scene, 0, 2);
    enforce_battle_ui_hide();
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
