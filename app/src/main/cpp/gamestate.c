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
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <errno.h>
#include <elf.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "ChronoDuoNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define SFC_WORK_OFFSET   0x40
#define CHARA_BASE        0x6924
#define CHARA_STRIDE      0x154
// cSfcWork::GetSendBtlDataa(): ldr x0, [x0, 0xc0f0]; ret -- heap ptr to battle data.
#define GETSENDBTLDATA_OFFSET 0xc0f0
// ChronoCanvas::getFieldMapName() (0x5577a0) loads the current field-map id
// via `ldrsw x11, [x9, #0x98c]` where x9 = this+0x11974, i.e. the id is a
// plain sign-extended int32 at ChronoCanvas+0x12300. The ctor independently
// places this inside the embedded FIELD_MAp struct at canvas+0x112e8, so the
// id also reads as FIELD_MAp+0x1018 -- see fieldmap_id_report.md.
#define FIELD_MAP_ID_OFFSET 0x12300

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
// nsBattleListMenu::BattleListMenuBase::getElement(int) const -- bounds-
// checked accessor into the submenu's row button Node tree (ScrollView's
// inner container, not exposed via Node::getChildren -- see collect_battle_
// list). Returns the row's button Node* or NULL if out of range.
static void  *(*p_list_getElement)(void *, int);

// cocos2d::Size/Vec2 are HFAs (two floats) -- returned in s0/s1 per the arm64
// AAPCS, so plain C struct-by-value declarations match the real ABI.
typedef struct { float w, h; } CCSize;
typedef struct { float x, y; } CCVec2;
static int safe_read(const void *addr, void *out, size_t len);
// libc++ std::string reader (defined later in the file); forward-declared so
// the addImage/createTexture hooks below (which run well before the
// definition) can use it, same as safe_read above.
static const char *sso_cstr(void *str, char *buf, int cap);

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
    p_list_getElement = (void *(*)(void *, int))
        dlsym(h, "_ZNK16nsBattleListMenu18BattleListMenuBase10getElementEi");
    LOGI("attach: getInstance=%p canvas=%p asm_slot=%p director=%p", (void *)p_getInstance,
         p_getInstance ? p_getInstance() : NULL, (void *)g_asm_mem_slot,
         (void *)p_dir_getInstance);
    return p_getInstance != NULL;
}

// ---------------------------------------------------------------------------
// Pixel graphics: GOT-patch cocos2d::Texture2D::setAntiAliasTexParameters()'s
// R_AARCH64_JUMP_SLOT relocation to redirect to Texture2D::
// setAliasTexParameters() instead. cocos2d-x calls the AntiAlias (GL_LINEAR)
// path by default for every texture it loads (mapchips, character sheets,
// battle art, ...); redirecting that one JUMP_SLOT makes every such call set
// GL_NEAREST instead, with zero reimplementation of GL state logic -- see
// pixel_filter_report.md. cocos2d::FontAtlas has its own separate alias/
// antialias pair (not Texture2D's), so text glyph rendering is untouched.
// ---------------------------------------------------------------------------

#define PIXEL_SYM_ANTIALIAS "_ZN7cocos2d9Texture2D25setAntiAliasTexParametersEv"
#define PIXEL_SYM_ALIAS     "_ZN7cocos2d9Texture2D21setAliasTexParametersEv"
// Sanity-log-only expectation from the disassembly report (libchrono.so
// v2.1.5) -- NOT relied on; the real slot is always found by walking the
// ELF's own JUMP_SLOT relocations at runtime (below).
#define PIXEL_EXPECTED_GOT_OFFSET 0xbd65b8UL

static uint8_t   *g_pixel_lib_base;    // load bias (== dli_fbase, same convention as g_lib_base)
static uintptr_t *g_pixel_got_slot;    // resolved GOT slot address, or NULL until first call
static uintptr_t  g_pixel_orig_value;  // slot's original value (setAntiAliasTexParameters' address)
static int        g_pixel_orig_saved;
static void       *g_pixel_alias_addr; // live address of setAliasTexParameters (redirect target)

// Reads /proc/self/maps to find the rwx protection currently applied to the
// page containing `addr`. Returns -1 if the address isn't found in any
// mapping (caller should fall back to a conservative PROT_READ).
static int pixel_page_prot_at(uintptr_t addr) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return -1;
    char line[512];
    int prot = -1;
    while (fgets(line, sizeof(line), f)) {
        unsigned long start, end;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s", &start, &end, perms) != 3) continue;
        if (addr >= start && addr < end) {
            prot = 0;
            if (perms[0] == 'r') prot |= PROT_READ;
            if (perms[1] == 'w') prot |= PROT_WRITE;
            if (perms[2] == 'x') prot |= PROT_EXEC;
            break;
        }
    }
    fclose(f);
    return prot;
}

// Walks the ELF image mapped at `base` (load bias == base, the same
// assumption g_lib_base/ASM_MEM_GLOBAL already rely on elsewhere in this
// file) to find the PT_DYNAMIC segment, then its DT_JMPREL/DT_PLTRELSZ/
// DT_SYMTAB/DT_STRTAB entries, and returns the runtime address of the
// R_AARCH64_JUMP_SLOT relocation whose symbol name equals `sym_name` (NULL
// if not found or the ELF/dynamic structure looks wrong).
static uintptr_t *pixel_find_jump_slot(uint8_t *base, const char *sym_name) {
    Elf64_Ehdr *eh = (Elf64_Ehdr *) base;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) {
        LOGE("pixel-gfx: bad ELF magic at base %p", (void *) base);
        return NULL;
    }
    Elf64_Phdr *ph = (Elf64_Phdr *) (base + eh->e_phoff);
    Elf64_Dyn *dyn = NULL;
    for (int i = 0; i < eh->e_phnum; i++) {
        if (ph[i].p_type == PT_DYNAMIC) {
            dyn = (Elf64_Dyn *) (base + ph[i].p_vaddr);
            break;
        }
    }
    if (!dyn) {
        LOGE("pixel-gfx: no PT_DYNAMIC segment found");
        return NULL;
    }

    Elf64_Rela *jmprel = NULL;
    Elf64_Sym *symtab = NULL;
    const char *strtab = NULL;
    size_t pltrelsz = 0;
    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_JMPREL:   jmprel = (Elf64_Rela *) (base + d->d_un.d_ptr); break;
            case DT_PLTRELSZ: pltrelsz = (size_t) d->d_un.d_val; break;
            case DT_SYMTAB:   symtab = (Elf64_Sym *) (base + d->d_un.d_ptr); break;
            case DT_STRTAB:   strtab = (const char *) (base + d->d_un.d_ptr); break;
            default: break;
        }
    }
    if (!jmprel || !symtab || !strtab || !pltrelsz) {
        LOGE("pixel-gfx: missing dynamic entries (jmprel=%p symtab=%p strtab=%p pltrelsz=%zu)",
             (void *) jmprel, (void *) symtab, (void *) strtab, pltrelsz);
        return NULL;
    }

    size_t count = pltrelsz / sizeof(Elf64_Rela);
    for (size_t i = 0; i < count; i++) {
        Elf64_Rela *r = &jmprel[i];
        if (ELF64_R_TYPE(r->r_info) != R_AARCH64_JUMP_SLOT) continue;
        uint32_t symidx = (uint32_t) ELF64_R_SYM(r->r_info);
        const char *name = strtab + symtab[symidx].st_name;
        if (strcmp(name, sym_name) == 0) {
            LOGI("pixel-gfx: %s slot at base+0x%lx (expected 0x%lx for v2.1.5)",
                 sym_name, (unsigned long) r->r_offset,
                 (unsigned long) PIXEL_EXPECTED_GOT_OFFSET);
            return (uintptr_t *) (base + r->r_offset);
        }
    }
    LOGE("pixel-gfx: symbol %s not found among %zu JUMP_SLOT relocations", sym_name, count);
    return NULL;
}

// ---------------------------------------------------------------------------
// Pixel graphics, mechanism 2: GOT-patch glTexParameteri/glTexParameterf.
//
// The setAntiAliasTexParameters redirect above only covers calls that go
// through cocos2d-x's Texture2D helper methods; the live result was that the
// redirect applied (log confirmed) but the rendered game looked unchanged --
// so some texture filters must be set another way (direct internal calls,
// RenderTexture, or similar) that never touches setAntiAliasTexParameters/
// setAliasTexParameters at all. libchrono imports glTexParameteri and
// (maybe) glTexParameterf from libGLESv2.so; those are still ordinary
// R_AARCH64_JUMP_SLOT relocations in libchrono's own PLT (pixel_find_jump_
// slot walks by symbol name and doesn't care whether the target is defined
// inside libchrono or an external import), so the same GOT-patch mechanism
// works here: point the slot at a small trampoline that rewrites any
// *_LINEAR* MIN/MAG filter to its *_NEAREST* equivalent, then forwards to
// the real GL entrypoint (resolved once via dlsym before patching).
//
// NOTE: this rewrites every glTexParameter{i,f} call libchrono makes,
// including text/glyph textures -- FontAtlas has its own separate alias/
// antialias pair from Texture2D's, but if glyph textures still end up going
// through glTexParameteri somewhere, they get nearest-filtered too. Accepted
// for now; a per-texture exemption could be added later by tracking
// glBindTexture/glTexImage2D texture ids/sizes and skipping the rewrite for
// ones that look like glyph atlases.
// ---------------------------------------------------------------------------

typedef unsigned int GLenum;
typedef int          GLint;
typedef float         GLfloat;
typedef int          GLsizei;

#define GL_TEXTURE_MAG_FILTER      0x2800
#define GL_TEXTURE_MIN_FILTER      0x2801
#define GL_NEAREST                 0x2600
#define GL_LINEAR                  0x2601
#define GL_NEAREST_MIPMAP_NEAREST  0x2700
#define GL_LINEAR_MIPMAP_NEAREST   0x2701
#define GL_NEAREST_MIPMAP_LINEAR   0x2702
#define GL_LINEAR_MIPMAP_LINEAR    0x2703

#define PIXEL_SYM_TEXPARAMI "glTexParameteri"
#define PIXEL_SYM_TEXPARAMF "glTexParameterf"

static uintptr_t *g_pixel_texpi_slot;
static uintptr_t  g_pixel_texpi_orig;
static int        g_pixel_texpi_orig_saved;
static uintptr_t *g_pixel_texpf_slot;
static uintptr_t  g_pixel_texpf_orig;
static int        g_pixel_texpf_orig_saved;

static void (*p_real_glTexParameteri)(GLenum target, GLenum pname, GLint param);
static void (*p_real_glTexParameterf)(GLenum target, GLenum pname, GLfloat param);

// Rewrites a LINEAR* filter value to its NEAREST* equivalent for MIN/MAG
// filter pnames only; everything else (including non-filter pnames such as
// wrap modes) passes through unchanged.
static GLint pixel_rewrite_filter(GLenum pname, GLint param) {
    if (pname != GL_TEXTURE_MIN_FILTER && pname != GL_TEXTURE_MAG_FILTER) return param;
    switch (param) {
        case GL_LINEAR:                return GL_NEAREST;
        case GL_LINEAR_MIPMAP_LINEAR:
        case GL_LINEAR_MIPMAP_NEAREST:
        case GL_NEAREST_MIPMAP_LINEAR: return GL_NEAREST_MIPMAP_NEAREST;
        default:                       return param;
    }
}

// Rate-limited (pname,param) rewrite logging -- dedup'd against a small
// static set so the hook firing is visible in logcat (useful to confirm the
// mechanism is live) without flooding it, since a real rewrite can happen on
// every texture bind.
#define PIXEL_LOG_SEEN_MAX 32
static uint32_t g_pixel_log_seen[PIXEL_LOG_SEEN_MAX]; // (pname<<16)^orig, 0 = empty
static int      g_pixel_log_seen_count;

static void pixel_log_rewrite_once(const char *fn, GLenum pname, GLint orig, GLint rewritten) {
    if (orig == rewritten) return; // not actually a rewrite -- nothing to log
    uint32_t key = ((uint32_t) pname << 16) ^ (uint32_t) (orig & 0xffff);
    for (int i = 0; i < g_pixel_log_seen_count; i++) {
        if (g_pixel_log_seen[i] == key) return;
    }
    if (g_pixel_log_seen_count < PIXEL_LOG_SEEN_MAX) {
        g_pixel_log_seen[g_pixel_log_seen_count++] = key;
    }
    LOGI("pixel-gfx: %s rewrote pname=0x%x 0x%x -> 0x%x", fn, pname, orig, rewritten);
}

// Total glTexParameteri calls that actually rewrote a LINEAR* filter value
// (i.e. rewritten != original) -- diagnostic counter, see nativeLogPixelStats
// and Java_..._nativeSetPixelGraphics's periodic dump below.
static uint32_t g_pixel_texparami_rewrites;

static void hooked_glTexParameteri(GLenum target, GLenum pname, GLint param) {
    GLint rewritten = pixel_rewrite_filter(pname, param);
    if (rewritten != param) g_pixel_texparami_rewrites++;
    pixel_log_rewrite_once("glTexParameteri", pname, param, rewritten);
    if (p_real_glTexParameteri) p_real_glTexParameteri(target, pname, rewritten);
}

static void hooked_glTexParameterf(GLenum target, GLenum pname, GLfloat param) {
    GLint rewritten = pixel_rewrite_filter(pname, (GLint) param);
    pixel_log_rewrite_once("glTexParameterf", pname, (GLint) param, rewritten);
    if (p_real_glTexParameterf) p_real_glTexParameterf(target, pname, (GLfloat) rewritten);
}

// ---------------------------------------------------------------------------
// Pixel graphics, mechanism 3 (diagnostic only): GOT-patch glTexImage2D and
// glGenerateMipmap, same PLT-walk mechanism as glTexParameteri/f above. These
// don't rewrite anything -- they just forward to the real entrypoint after
// logging what the game actually uploads, so we can see texture sizes/
// formats/types independent of whatever glTexParameter{i,f} is doing to the
// filter state. See Java_..._nativeSetPixelGraphics and nativeLogPixelStats.
// ---------------------------------------------------------------------------

#define PIXEL_SYM_TEXIMAGE2D  "glTexImage2D"
#define PIXEL_SYM_GENMIPMAP   "glGenerateMipmap"

static uintptr_t *g_pixel_teximg_slot;
static uintptr_t  g_pixel_teximg_orig;
static int        g_pixel_teximg_orig_saved;
static uintptr_t *g_pixel_genmip_slot;
static uintptr_t  g_pixel_genmip_orig;
static int        g_pixel_genmip_orig_saved;

static void (*p_real_glTexImage2D)(GLenum target, GLint level, GLint internalformat,
                                    GLsizei width, GLsizei height, GLint border,
                                    GLenum format, GLenum type, const void *pixels);
static void (*p_real_glGenerateMipmap)(GLenum target);

// Running counters, dumped periodically below and on demand via
// nativeLogPixelStats.
static uint32_t g_pixel_teximage_calls;
static uint32_t g_pixel_genmipmap_calls;

// After the first 40 logged calls, dedup further texImage2D logging against
// this small table so a distinct (width,height,internalformat) still gets
// one line without flooding logcat on every re-upload of the same texture.
#define PIXEL_TEXIMG_SEEN_MAX 64
typedef struct {
    GLsizei width;
    GLsizei height;
    GLenum  internalformat;
} pixel_teximg_seen_t;
static pixel_teximg_seen_t g_pixel_teximg_seen[PIXEL_TEXIMG_SEEN_MAX];
static int g_pixel_teximg_seen_count;

// ---------------------------------------------------------------------------
// Pixel graphics, mechanism 4: 2x2 decimation of pre-upscaled art on upload.
//
// The game's art assets ship pre-upscaled ~2x with smoothing baked in
// (512x512 RGBA sprite sheets, 32x32 map chips for what were 16x16 SNES
// tiles, and the CPU-composited 768x448 field index texture). GL_NEAREST
// alone (mechanism 1/2 above) stops the *filtering* from blurring things
// further, but it can't undo the smoothing already baked into the upscaled
// pixels themselves. Decimating every RGBA/UNSIGNED_BYTE upload down to one
// texel per 2x2 block approximates the original 1x pixel art.
//
// RenderTexture framebuffers create their backing texture via glTexImage2D
// with pixels == NULL (no initial data) -- the NULL check below skips those,
// so framebuffer sizes are left intact. glTexSubImage2D is not imported by
// libchrono (checked against its import list), so partial updates can't
// bypass this; every glTexImage2D upload of a given texture is decimated
// the same way, so re-uploads stay consistent.
// ---------------------------------------------------------------------------

#define GL_RGBA           0x1908
#define GL_UNSIGNED_BYTE  0x1401

// Set by nativeSetPixelDecimate; default off. Java flips it on right after
// nativeSetPixelGraphics() when the pixel-graphics pref is enabled -- see
// GameState.applyPixelGraphicsPref.
static int g_pixel_decimate;

// Known font/UI atlases that should NOT be decimated (e.g. sizes that are
// already 1x, or that don't tolerate losing half their resolution). Empty
// for now -- fill in from observation (logcat "pixel-gfx: decimated ..."
// lines vs. in-game visual inspection) if a specific WxH turns out to need
// exemption.
typedef struct { GLsizei width, height; } pixel_decimate_exempt_t;
static const pixel_decimate_exempt_t g_pixel_decimate_exempt[] = {
    // Placeholder -- {0,0} never matches a real texture (decimation only
    // ever considers width,height >= 64), so the list is effectively empty.
    // Add real { width, height } entries here as they're identified.
    {0, 0},
};
#define PIXEL_DECIMATE_EXEMPT_COUNT \
    (sizeof(g_pixel_decimate_exempt) / sizeof(g_pixel_decimate_exempt[0]))

static int pixel_decimate_is_exempt(GLsizei width, GLsizei height) {
    for (size_t i = 0; i < PIXEL_DECIMATE_EXEMPT_COUNT; i++) {
        if (g_pixel_decimate_exempt[i].width == width &&
            g_pixel_decimate_exempt[i].height == height) {
            return 1;
        }
    }
    return 0;
}

// 0 = take the 2x2 block's top-left texel (pixel (2x,2y)); 1 = take the
// block's most common color (majority vote across the 4 texels), falling
// back to top-left when no color repeats.
#define PIXEL_DECIMATE_MODE 0

// Picks the output color for one 2x2 source block. `p` holds the 4 texels
// in row-major order: p[0]=(2x,2y), p[1]=(2x+1,2y), p[2]=(2x,2y+1),
// p[3]=(2x+1,2y+1).
static uint32_t pixel_decimate_pick(const uint32_t p[4]) {
#if PIXEL_DECIMATE_MODE == 1
    int best_count = 0;
    uint32_t best = p[0];
    for (int i = 0; i < 4; i++) {
        int count = 0;
        for (int j = 0; j < 4; j++) {
            if (p[j] == p[i]) count++;
        }
        if (count > best_count) {
            best_count = count;
            best = p[i];
        }
    }
    // No repeated color anywhere in the block (best_count == 1, all four
    // distinct) -- no real majority, fall back to top-left.
    if (best_count < 2) return p[0];
    return best;
#else
    (void) p[1]; (void) p[2]; (void) p[3]; // unused in top-left mode
    return p[0];
#endif
}

// Heap scratch buffer for the decimated (width/2 x height/2 x 4 bytes)
// output, grown as needed and kept (not freed) between calls -- textures
// only get bigger up to the game's largest asset, so steady-state this
// allocates once.
static uint8_t *g_pixel_decimate_buf;
static size_t   g_pixel_decimate_buf_cap;

static uint8_t *pixel_decimate_scratch(size_t needed) {
    if (needed > g_pixel_decimate_buf_cap) {
        uint8_t *grown = (uint8_t *) realloc(g_pixel_decimate_buf, needed);
        if (!grown) return NULL;
        g_pixel_decimate_buf = grown;
        g_pixel_decimate_buf_cap = needed;
    }
    return g_pixel_decimate_buf;
}

// Fills `dst` (width/2 * height/2 RGBA texels, tightly packed) from `src`
// (width * height RGBA texels, tightly packed -- true for RGBA/
// UNSIGNED_BYTE regardless of GL_UNPACK_ALIGNMENT since 4-byte texels are
// always 4-byte-row-aligned).
static void pixel_decimate_rgba(const uint8_t *src, GLsizei width, GLsizei height,
                                 uint8_t *dst) {
    const uint32_t *src32 = (const uint32_t *) src;
    uint32_t *dst32 = (uint32_t *) dst;
    GLsizei out_w = width / 2;
    GLsizei out_h = height / 2;
    for (GLsizei y = 0; y < out_h; y++) {
        const uint32_t *row0 = src32 + (size_t) (2 * y) * width;
        const uint32_t *row1 = src32 + (size_t) (2 * y + 1) * width;
        uint32_t *out_row = dst32 + (size_t) y * out_w;
        for (GLsizei x = 0; x < out_w; x++) {
            uint32_t block[4] = { row0[2 * x], row0[2 * x + 1], row1[2 * x], row1[2 * x + 1] };
            out_row[x] = pixel_decimate_pick(block);
        }
    }
}

// Rate-limited logging for decimated uploads, same style/budget as the
// existing texImage2D size log above: first 40 calls logged unconditionally,
// then dedup'd per distinct (width,height).
static uint32_t g_pixel_decimate_calls;
#define PIXEL_DECIMATE_SEEN_MAX 64
typedef struct { GLsizei width, height; } pixel_decimate_seen_t;
static pixel_decimate_seen_t g_pixel_decimate_seen[PIXEL_DECIMATE_SEEN_MAX];
static int g_pixel_decimate_seen_count;

static void pixel_decimate_log_once(GLsizei width, GLsizei height) {
    g_pixel_decimate_calls++;
    if (g_pixel_decimate_calls <= 40) {
        LOGI("pixel-gfx: decimated %dx%d -> %dx%d", width, height, width / 2, height / 2);
        return;
    }
    for (int i = 0; i < g_pixel_decimate_seen_count; i++) {
        if (g_pixel_decimate_seen[i].width == width && g_pixel_decimate_seen[i].height == height) {
            return;
        }
    }
    if (g_pixel_decimate_seen_count < PIXEL_DECIMATE_SEEN_MAX) {
        g_pixel_decimate_seen[g_pixel_decimate_seen_count].width = width;
        g_pixel_decimate_seen[g_pixel_decimate_seen_count].height = height;
        g_pixel_decimate_seen_count++;
    }
    LOGI("pixel-gfx: decimated %dx%d -> %dx%d", width, height, width / 2, height / 2);
}

// ---------------------------------------------------------------------------
// Pixel graphics, mechanism 5: user-local texture replacement.
//
// GOT-patches cocos2d::TextureCache::addImage(const std::string&) and
// ctr::ResourceManager::createTexture(const std::string&) -- both take just
// an asset path (self, const std::string*) and both have R_AARCH64_JUMP_SLOT
// relocations in libchrono.so (verified with `readelf -r -W`; addImage's
// other overload and createTexture's Image*-taking overload also have slots
// but don't carry a filename by themselves, so they're not hooked). Texture
// upload happens synchronously inside these calls (-> initWithImage ->
// glTexImage2D), so the hook here just records which asset path is "in
// flight" in g_pending_tex_path; hooked_glTexImage2D (mechanism 3, below)
// consults it to decide whether to substitute a user-registered replacement
// image for the upload.
//
// The replacement registry itself (g_tex_repl[]) is disk-backed: entries
// hold only name/size/fingerprints/path, never pixels. It's populated from
// Java via nativeLoadTextureReplacementIndex, which parses a small text
// index (<filesDir>/orig_art_cache/index.txt) built by
// com.kalenjohnson.chronoduo.OrigArtCache#refresh from
// <externalFilesDir|filesDir>/orig_art/*.png at boot (and from the
// pixel-graphics settings toggle) -- see AppActivity.scanOrigArtReplacements.
// On a match, hooked_glTexImage2D freads the matched entry's "<name>.rgba"
// file (raw premultiplied RGBA8 bytes, w*h*4, tightly packed) straight into
// the decimation scratch buffer and uploads that -- nothing is held decoded
// in RAM between matches. This keeps steady-state native RAM to one
// scratch buffer regardless of how many sheets (up to TEX_REPL_MAX) are
// registered, instead of holding all of them malloc'd and decoded at once.
// ---------------------------------------------------------------------------

#define PIXEL_SYM_ADDIMAGE \
    "_ZN7cocos2d12TextureCache8addImageERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"
#define PIXEL_SYM_CREATETEXTURE \
    "_ZN3ctr15ResourceManager13createTextureERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE"

static uintptr_t *g_pixel_addimage_slot;
static uintptr_t  g_pixel_addimage_orig;
static int        g_pixel_addimage_orig_saved;
static uintptr_t *g_pixel_createtex_slot;
static uintptr_t  g_pixel_createtex_orig;
static int        g_pixel_createtex_orig_saved;

static void *(*p_real_addImage)(void *self, const void *stdstring);
static void *(*p_real_createTexture)(void *self, const void *stdstring);

// Basename (e.g. "c000_0.png") and full path of the asset currently being
// loaded, valid only for the duration of the addImage/createTexture call
// that's in flight -- set on entry, cleared on return. GL thread only (both
// hooked functions and glTexImage2D itself only ever run there).
static char g_pending_tex_path[256];
static char g_pending_tex_path_full[256];

static void pixel_set_pending_tex_path(const char *full) {
    if (!full || !full[0]) { g_pending_tex_path[0] = 0; g_pending_tex_path_full[0] = 0; return; }
    strncpy(g_pending_tex_path_full, full, sizeof(g_pending_tex_path_full) - 1);
    g_pending_tex_path_full[sizeof(g_pending_tex_path_full) - 1] = 0;
    const char *slash = strrchr(full, '/');
    const char *base = slash ? slash + 1 : full;
    strncpy(g_pending_tex_path, base, sizeof(g_pending_tex_path) - 1);
    g_pending_tex_path[sizeof(g_pending_tex_path) - 1] = 0;
}

static void pixel_clear_pending_tex_path(void) {
    g_pending_tex_path[0] = 0;
    g_pending_tex_path_full[0] = 0;
}

static void *hooked_addImage(void *self, const void *stdstring) {
    char buf[256];
    pixel_set_pending_tex_path(sso_cstr((void *) stdstring, buf, sizeof(buf)));
    void *ret = p_real_addImage ? p_real_addImage(self, stdstring) : NULL;
    pixel_clear_pending_tex_path();
    return ret;
}

static void *hooked_createTexture(void *self, const void *stdstring) {
    char buf[256];
    pixel_set_pending_tex_path(sso_cstr((void *) stdstring, buf, sizeof(buf)));
    void *ret = p_real_createTexture ? p_real_createTexture(self, stdstring) : NULL;
    pixel_clear_pending_tex_path();
    return ret;
}

// Replacement registry: up to TEX_REPL_MAX disk-backed entries, keyed by the
// asset basename (e.g. "c000_0.png") that g_pending_tex_path is set to when
// the game loads it. Loaded wholesale by nativeLoadTextureReplacementIndex
// (Java, background thread, at boot and from the settings toggle); consulted
// by hooked_glTexImage2D (GL thread), which freads rgba_path on a match.
// Guarded by g_tex_repl_mutex since loading and consultation run on
// different threads. Entries never hold decoded pixels -- see the mechanism
// 5 comment block above.
#define TEX_REPL_MAX 2048
#define TEX_REPL_NAME_MAX 32
#define TEX_REPL_CAND_MAX 16
typedef struct {
    char     name[TEX_REPL_NAME_MAX];
    int      w, h;
    uint64_t alpha_fp;       // FNV-1a over a 64x64 alpha-channel sample grid of the ORIGINAL asset (see tex_fingerprint)
    uint64_t red_fp;         // same grid, red channel -- tiebreaker when alpha_fp collides across entries
    char     rgba_path[300]; // "<rgbaDir>/<name>.rgba", tightly packed PREMULTIPLIED RGBA8888, w*h*4 bytes
    int      replaced_logged;
    int      mismatch_logged;
} tex_replacement_t;
static tex_replacement_t g_tex_repl[TEX_REPL_MAX];
static int              g_tex_repl_count;
static pthread_mutex_t  g_tex_repl_mutex = PTHREAD_MUTEX_INITIALIZER;

// Content-fingerprint match (mechanism 5b): some uploads (observed live for
// the character sheets, Game/chara/png/c000_0.png 512x512 and c000_1.png
// 512x416) go through neither TextureCache::addImage nor
// ResourceManager::createTexture, so g_pending_tex_path is never set for
// them and the path-based match above always misses. For those, match by
// content instead: hash a fixed 64x64 grid of samples from the live upload's
// alpha channel (premultiplication doesn't touch alpha, so this hash is
// stable regardless of how the game's premultiply/swizzle step treats
// color) and compare against the same hash computed (in Java,
// AppActivity#fingerprint) over the ORIGINAL asset extracted from
// resources.bin. A second FNV-1a hash over the same grid's red channel
// breaks ties when more than one registered entry shares an alpha hash.
//
// Sampling formula (must stay bit-identical to the Java side): for a WxH
// image, for i,j in 0..63 (4096 samples), x = (i*w)/64, y = (j*h)/64
// (integer/floor division), sample pixel (x,y). Hash is 64-bit FNV-1a,
// seeded with w then h (as big-endian bytes) before the sample bytes, so
// two same-content-but-different-size buffers still hash differently.
#define FNV64_OFFSET 0xcbf29ce484222325ULL
#define FNV64_PRIME  0x100000001b3ULL

static inline uint64_t fnv1a_byte(uint64_t h, uint8_t b) {
    h ^= b;
    h *= FNV64_PRIME;
    return h;
}

static inline uint64_t fnv1a_u32(uint64_t h, uint32_t v) {
    h = fnv1a_byte(h, (uint8_t) ((v >> 24) & 0xff));
    h = fnv1a_byte(h, (uint8_t) ((v >> 16) & 0xff));
    h = fnv1a_byte(h, (uint8_t) ((v >> 8) & 0xff));
    h = fnv1a_byte(h, (uint8_t) (v & 0xff));
    return h;
}

// Computes (alpha_fp, red_fp) over `rgba` (tightly packed RGBA8888, w*h*4
// bytes) per the sampling formula above. Cheap: 4096 grid samples, 2 byte
// reads each -- only call when the upload's size matches at least one
// registered replacement (see hooked_glTexImage2D).
static void tex_fingerprint(const uint8_t *rgba, int w, int h,
                             uint64_t *out_alpha_fp, uint64_t *out_red_fp) {
    uint64_t ah = FNV64_OFFSET, rh = FNV64_OFFSET;
    ah = fnv1a_u32(ah, (uint32_t) w);
    ah = fnv1a_u32(ah, (uint32_t) h);
    rh = fnv1a_u32(rh, (uint32_t) w);
    rh = fnv1a_u32(rh, (uint32_t) h);
    for (int i = 0; i < 64; i++) {
        int x = (i * w) / 64;
        for (int j = 0; j < 64; j++) {
            int y = (j * h) / 64;
            const uint8_t *p = rgba + ((size_t) y * (size_t) w + (size_t) x) * 4;
            ah = fnv1a_byte(ah, p[3]); // alpha
            rh = fnv1a_byte(rh, p[0]); // red
        }
    }
    *out_alpha_fp = ah;
    *out_red_fp = rh;
}

static void hooked_glTexImage2D(GLenum target, GLint level, GLint internalformat,
                                 GLsizei width, GLsizei height, GLint border,
                                 GLenum format, GLenum type, const void *pixels) {
    g_pixel_teximage_calls++;
    if (g_pixel_teximage_calls <= 40) {
        LOGI("pixel-gfx: texImage2D %dx%d fmt=0x%x type=0x%x level=%d path=%s",
             width, height, (unsigned int) internalformat, type, level,
             g_pending_tex_path_full[0] ? g_pending_tex_path_full : "-");
    } else {
        int seen = 0;
        for (int i = 0; i < g_pixel_teximg_seen_count; i++) {
            if (g_pixel_teximg_seen[i].width == width &&
                g_pixel_teximg_seen[i].height == height &&
                g_pixel_teximg_seen[i].internalformat == (GLenum) internalformat) {
                seen = 1;
                break;
            }
        }
        if (!seen) {
            if (g_pixel_teximg_seen_count < PIXEL_TEXIMG_SEEN_MAX) {
                g_pixel_teximg_seen[g_pixel_teximg_seen_count].width = width;
                g_pixel_teximg_seen[g_pixel_teximg_seen_count].height = height;
                g_pixel_teximg_seen[g_pixel_teximg_seen_count].internalformat =
                    (GLenum) internalformat;
                g_pixel_teximg_seen_count++;
            }
            LOGI("pixel-gfx: texImage2D %dx%d fmt=0x%x type=0x%x level=%d",
                 width, height, (unsigned int) internalformat, type, level);
        }
    }
    if (g_pixel_teximage_calls % 200 == 0) {
        LOGI("pixel-gfx: stats texImage2D_calls=%u texParam_rewrites=%u",
             g_pixel_teximage_calls, g_pixel_texparami_rewrites);
    }

    // Texture replacement (mechanism 5): if a registered replacement matches
    // this upload -- by asset path (addImage/createTexture hooks above) or,
    // failing that, by content fingerprint (mechanism 5b, for uploads that
    // bypass both hooks) -- upload the replacement's pixels instead of the
    // game's. Independent of g_pixel_decimate -- active whenever anything is
    // registered -- and checked ahead of decimation so a replaced texture is
    // never also decimated. Only for fresh (level 0), real (pixels != NULL)
    // RGBA/UNSIGNED_BYTE uploads, same guard style as decimation below.
    if (level == 0 && pixels != NULL && format == GL_RGBA && type == GL_UNSIGNED_BYTE &&
        g_tex_repl_count > 0) {
        pthread_mutex_lock(&g_tex_repl_mutex);
        tex_replacement_t *r = NULL;
        int via_fingerprint = 0;

        // First attempt: path-based match (original mechanism 5).
        if (g_pending_tex_path[0]) {
            for (int i = 0; i < g_tex_repl_count; i++) {
                if (strcmp(g_tex_repl[i].name, g_pending_tex_path) == 0) { r = &g_tex_repl[i]; break; }
            }
        }

        // Second attempt: content fingerprint (mechanism 5b). Only bother
        // hashing the upload if its size matches at least one registered
        // entry -- keeps this a no-op for the vast majority of uploads.
        if (!r) {
            int size_registered = 0;
            for (int i = 0; i < g_tex_repl_count; i++) {
                if (g_tex_repl[i].w == width && g_tex_repl[i].h == height) {
                    size_registered = 1;
                    break;
                }
            }
            if (size_registered) {
                uint64_t alpha_fp, red_fp;
                tex_fingerprint((const uint8_t *) pixels, width, height, &alpha_fp, &red_fp);
                // Static, not stack: with TEX_REPL_MAX at 2048 a
                // tex_replacement_t* array sized to match would be 16KB on
                // the stack. GL-thread-only, so static is safe, and
                // TEX_REPL_CAND_MAX collisions on one (w,h,alpha_fp) triple
                // is already an absurd number of same-sized, same-alpha
                // sheets -- extras are simply not considered.
                static tex_replacement_t *candidates[TEX_REPL_CAND_MAX];
                int ncand = 0;
                for (int i = 0; i < g_tex_repl_count; i++) {
                    if (g_tex_repl[i].w == width && g_tex_repl[i].h == height &&
                        g_tex_repl[i].alpha_fp == alpha_fp) {
                        if (ncand < TEX_REPL_CAND_MAX) candidates[ncand++] = &g_tex_repl[i];
                    }
                }
                if (ncand == 1) {
                    r = candidates[0];
                    via_fingerprint = 1;
                } else if (ncand > 1) {
                    for (int i = 0; i < ncand; i++) {
                        if (candidates[i]->red_fp == red_fp) {
                            r = candidates[i];
                            via_fingerprint = 1;
                            break;
                        }
                    }
                }
            }
        }

        if (r) {
            if (r->w == width && r->h == height) {
                size_t needed = (size_t) width * (size_t) height * 4;
                // Reuses g_pixel_decimate_buf (via pixel_decimate_scratch) as
                // a generic grow-only I/O buffer. Safe: this path always
                // either uploads-and-returns or falls through to the normal
                // (non-decimated-replacement) path below without touching
                // the buffer again, so the fread here and
                // pixel_decimate_rgba's writes below never run on the same
                // buffer contents; both are GL-thread-only besides.
                uint8_t *scratch = pixel_decimate_scratch(needed);
                FILE *rf = scratch ? fopen(r->rgba_path, "rb") : NULL;
                size_t rd = 0;
                if (rf) {
                    rd = fread(scratch, 1, needed, rf);
                    fclose(rf);
                }
                if (scratch && rf && rd == needed) {
                    if (p_real_glTexImage2D) {
                        p_real_glTexImage2D(target, level, internalformat, width, height, border,
                                             format, type, scratch);
                    }
                    if (!r->replaced_logged) {
                        if (via_fingerprint) {
                            LOGI("pixel-gfx: replaced %s by fingerprint", r->name);
                        } else {
                            LOGI("pixel-gfx: replaced %s %dx%d", r->name, width, height);
                        }
                        r->replaced_logged = 1;
                    }
                    pthread_mutex_unlock(&g_tex_repl_mutex);
                    return;
                }
                if (!r->mismatch_logged) {
                    LOGE("pixel-gfx: replacement %s: failed to load %s (scratch=%d open=%d "
                         "read=%zu/%zu) -- using original",
                         r->name, r->rgba_path, scratch != NULL, rf != NULL, rd, needed);
                    r->mismatch_logged = 1;
                }
                // Falls through to the normal upload path below, using the
                // live `pixels` (not the half-read scratch buffer).
            } else if (!r->mismatch_logged) {
                LOGE("pixel-gfx: replacement %s is %dx%d, upload is %dx%d -- size mismatch, "
                     "using original", r->name, r->w, r->h, width, height);
                r->mismatch_logged = 1;
            }
        }
        pthread_mutex_unlock(&g_tex_repl_mutex);
    }

    // Decimate: keep one texel of every 2x2 block so pre-upscaled ~2x art
    // approximates the original 1x pixel art. Only for fresh (level 0),
    // real (pixels != NULL, so RenderTexture's empty-framebuffer allocation
    // is left alone), plain RGBA/UNSIGNED_BYTE uploads that are large enough
    // to be real art (skip tiny UI textures / 16x1 palettes) and evenly
    // sized (so width/2, height/2 is exact), and not on the exemption list.
    if (g_pixel_decimate && level == 0 && pixels != NULL &&
        format == GL_RGBA && type == GL_UNSIGNED_BYTE &&
        width >= 64 && height >= 64 &&
        (width % 2) == 0 && (height % 2) == 0 &&
        !pixel_decimate_is_exempt(width, height)) {
        GLsizei out_w = width / 2;
        GLsizei out_h = height / 2;
        size_t needed = (size_t) out_w * (size_t) out_h * 4;
        uint8_t *scratch = pixel_decimate_scratch(needed);
        if (scratch) {
            pixel_decimate_rgba((const uint8_t *) pixels, width, height, scratch);
            pixel_decimate_log_once(width, height);
            if (p_real_glTexImage2D) {
                p_real_glTexImage2D(target, level, internalformat, out_w, out_h, border, format,
                                     type, scratch);
            }
            return;
        }
        LOGE("pixel-gfx: decimate scratch alloc failed (%dx%d, %zu bytes) -- uploading full-size",
             width, height, needed);
    }

    if (p_real_glTexImage2D) {
        p_real_glTexImage2D(target, level, internalformat, width, height, border, format,
                             type, pixels);
    }
}

static void hooked_glGenerateMipmap(GLenum target) {
    g_pixel_genmipmap_calls++;
    if (g_pixel_genmipmap_calls <= 5) {
        LOGI("pixel-gfx: glGenerateMipmap target=0x%x (call #%u)",
             target, g_pixel_genmipmap_calls);
    }
    if (p_real_glGenerateMipmap) p_real_glGenerateMipmap(target);
}

// Patches (enable) or restores (disable) one already-resolved GOT slot,
// mirroring the mprotect dance nativeSetPixelGraphics does for the
// setAntiAliasTexParameters slot. `slot` may be NULL (e.g. libchrono doesn't
// import glTexParameterf at all in some builds) -- silent no-op.
static void pixel_patch_slot(uintptr_t *slot, uintptr_t *orig_value, int *orig_saved,
                              uintptr_t hook_value, int enable) {
    if (!slot) return;
    uintptr_t addr = (uintptr_t) slot;
    long pagesize = sysconf(_SC_PAGESIZE);
    uintptr_t page = addr & ~(uintptr_t) (pagesize - 1);
    int orig_prot = pixel_page_prot_at(addr);
    int restore_prot = (orig_prot >= 0) ? orig_prot : PROT_READ;

    if (mprotect((void *) page, (size_t) pagesize, PROT_READ | PROT_WRITE) != 0) {
        LOGE("pixel-gfx: mprotect(RW) failed on slot %p: %s", (void *) slot, strerror(errno));
        return;
    }
    if (!*orig_saved) {
        *orig_value = *slot;
        *orig_saved = 1;
    }
    *slot = enable ? hook_value : *orig_value;
    if (mprotect((void *) page, (size_t) pagesize, restore_prot) != 0) {
        LOGE("pixel-gfx: mprotect(restore 0x%x) failed on slot %p: %s",
             restore_prot, (void *) slot, strerror(errno));
    }
}

// Enables (GL_NEAREST, "pixel graphics") or disables (restores GL_LINEAR,
// the engine's stock default) both redirects: the setAntiAliasTexParameters
// GOT slot (Texture2D helper path) and the glTexParameteri/f GOT slots
// (direct-call/RenderTexture path). Resolves slots on first call and caches
// them; every call after that just flips the already-resolved pointers.
// Must be called before the game's own textures load to have full effect on
// them (already-loaded textures keep whichever filter they were bound with)
// -- see AppActivity.onLoadNativeLibraries, which calls this right after
// libchrono.so is System.load()ed and well before the GL surface's
// nativeInit runs.
JNIEXPORT jboolean JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeSetPixelGraphics(JNIEnv *env, jclass cls,
                                                                   jboolean enable) {
    if (!g_pixel_got_slot) {
        void *h = dlopen("libchrono.so", RTLD_NOW | RTLD_NOLOAD);
        if (!h) {
            LOGE("pixel-gfx: libchrono.so not loaded yet");
            return JNI_FALSE;
        }
        void *antialias_fn = dlsym(h, PIXEL_SYM_ANTIALIAS);
        void *alias_fn = dlsym(h, PIXEL_SYM_ALIAS);
        if (!antialias_fn || !alias_fn) {
            LOGE("pixel-gfx: symbol resolution failed (antialias=%p alias=%p)",
                 antialias_fn, alias_fn);
            return JNI_FALSE;
        }
        Dl_info info;
        if (!dladdr(antialias_fn, &info) || !info.dli_fbase) {
            LOGE("pixel-gfx: dladdr failed on setAntiAliasTexParameters");
            return JNI_FALSE;
        }
        g_pixel_lib_base = (uint8_t *) info.dli_fbase;
        g_pixel_alias_addr = alias_fn;
        g_pixel_got_slot = pixel_find_jump_slot(g_pixel_lib_base, PIXEL_SYM_ANTIALIAS);
        if (!g_pixel_got_slot) return JNI_FALSE;

        // Second mechanism: glTexParameteri/f GOT slots, same base. The real
        // GL entrypoints are resolved via RTLD_DEFAULT first (libGLESv2.so
        // is already loaded into the process by the time this runs, since
        // it's called after System.load("chrono"), which itself links
        // against it); fall back to an explicit dlopen if that ever misses.
        void *gl_h = dlopen("libGLESv2.so", RTLD_NOW | RTLD_NOLOAD);
        p_real_glTexParameteri = (void (*)(GLenum, GLenum, GLint))
            dlsym(RTLD_DEFAULT, PIXEL_SYM_TEXPARAMI);
        if (!p_real_glTexParameteri && gl_h) {
            p_real_glTexParameteri = (void (*)(GLenum, GLenum, GLint))
                dlsym(gl_h, PIXEL_SYM_TEXPARAMI);
        }
        p_real_glTexParameterf = (void (*)(GLenum, GLenum, GLfloat))
            dlsym(RTLD_DEFAULT, PIXEL_SYM_TEXPARAMF);
        if (!p_real_glTexParameterf && gl_h) {
            p_real_glTexParameterf = (void (*)(GLenum, GLenum, GLfloat))
                dlsym(gl_h, PIXEL_SYM_TEXPARAMF);
        }
        if (!p_real_glTexParameteri) {
            LOGE("pixel-gfx: real glTexParameteri not resolvable -- skipping tex-param hook");
        } else {
            g_pixel_texpi_slot = pixel_find_jump_slot(g_pixel_lib_base, PIXEL_SYM_TEXPARAMI);
        }
        if (!p_real_glTexParameterf) {
            LOGI("pixel-gfx: real glTexParameterf not resolvable -- skipping (may be unused)");
        } else {
            g_pixel_texpf_slot = pixel_find_jump_slot(g_pixel_lib_base, PIXEL_SYM_TEXPARAMF);
        }

        // Diagnostic-only hooks: glTexImage2D / glGenerateMipmap. Same
        // resolve-then-walk-PLT approach as the texparam hooks above.
        p_real_glTexImage2D = (void (*)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum,
                                         GLenum, const void *))
            dlsym(RTLD_DEFAULT, PIXEL_SYM_TEXIMAGE2D);
        if (!p_real_glTexImage2D && gl_h) {
            p_real_glTexImage2D = (void (*)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint,
                                             GLenum, GLenum, const void *))
                dlsym(gl_h, PIXEL_SYM_TEXIMAGE2D);
        }
        if (!p_real_glTexImage2D) {
            LOGE("pixel-gfx: real glTexImage2D not resolvable -- skipping diagnostic hook");
        } else {
            g_pixel_teximg_slot = pixel_find_jump_slot(g_pixel_lib_base, PIXEL_SYM_TEXIMAGE2D);
            // Installed unconditionally (enable=1, not gated on the `enable`
            // param below): hooked_glTexImage2D is also where texture
            // replacement (mechanism 5) substitutes registered replacement
            // pixels, which must stay active regardless of the pixel-
            // graphics on/off pref -- see the addImage/createTexture hooks
            // below, which feed it g_pending_tex_path. Decimation and the
            // diagnostic logging inside the hook are separately gated by
            // g_pixel_decimate and always-on respectively, so this being
            // unconditional doesn't change their behavior.
            pixel_patch_slot(g_pixel_teximg_slot, &g_pixel_teximg_orig,
                              &g_pixel_teximg_orig_saved, (uintptr_t) hooked_glTexImage2D, 1);
        }

        p_real_glGenerateMipmap = (void (*)(GLenum)) dlsym(RTLD_DEFAULT, PIXEL_SYM_GENMIPMAP);
        if (!p_real_glGenerateMipmap && gl_h) {
            p_real_glGenerateMipmap = (void (*)(GLenum)) dlsym(gl_h, PIXEL_SYM_GENMIPMAP);
        }
        if (!p_real_glGenerateMipmap) {
            LOGI("pixel-gfx: real glGenerateMipmap not resolvable -- skipping diagnostic hook");
        } else {
            g_pixel_genmip_slot = pixel_find_jump_slot(g_pixel_lib_base, PIXEL_SYM_GENMIPMAP);
        }

        // Texture replacement hooks (mechanism 5): TextureCache::addImage and
        // ResourceManager::createTexture, both (const std::string&) overloads
        // resolved from libchrono.so itself (h), same convention as the
        // antialias/alias symbols above. Installed unconditionally here --
        // independent of `enable` -- since consulting g_pending_tex_path in
        // hooked_glTexImage2D is a no-op whenever the replacement registry is
        // empty, so leaving these patched in is harmless.
        p_real_addImage = (void *(*)(void *, const void *)) dlsym(h, PIXEL_SYM_ADDIMAGE);
        if (!p_real_addImage) {
            LOGE("pixel-gfx: symbol %s not found -- addImage texture replacement unavailable",
                 PIXEL_SYM_ADDIMAGE);
        } else {
            g_pixel_addimage_slot = pixel_find_jump_slot(g_pixel_lib_base, PIXEL_SYM_ADDIMAGE);
            pixel_patch_slot(g_pixel_addimage_slot, &g_pixel_addimage_orig,
                              &g_pixel_addimage_orig_saved, (uintptr_t) hooked_addImage, 1);
        }
        p_real_createTexture = (void *(*)(void *, const void *)) dlsym(h, PIXEL_SYM_CREATETEXTURE);
        if (!p_real_createTexture) {
            LOGI("pixel-gfx: symbol %s not found -- createTexture texture replacement unavailable",
                 PIXEL_SYM_CREATETEXTURE);
        } else {
            g_pixel_createtex_slot = pixel_find_jump_slot(g_pixel_lib_base, PIXEL_SYM_CREATETEXTURE);
            pixel_patch_slot(g_pixel_createtex_slot, &g_pixel_createtex_orig,
                              &g_pixel_createtex_orig_saved, (uintptr_t) hooked_createTexture, 1);
        }
        LOGI("pixel-gfx: texture-replacement hooks installed: addImage-slot=%p createTexture-slot=%p",
             (void *) g_pixel_addimage_slot, (void *) g_pixel_createtex_slot);
    }

    uintptr_t addr = (uintptr_t) g_pixel_got_slot;
    long pagesize = sysconf(_SC_PAGESIZE);
    uintptr_t page = addr & ~(uintptr_t) (pagesize - 1);
    int orig_prot = pixel_page_prot_at(addr);
    int restore_prot = (orig_prot >= 0) ? orig_prot : PROT_READ;

    if (mprotect((void *) page, (size_t) pagesize, PROT_READ | PROT_WRITE) != 0) {
        LOGE("pixel-gfx: mprotect(RW) failed: %s", strerror(errno));
        return JNI_FALSE;
    }

    if (!g_pixel_orig_saved) {
        g_pixel_orig_value = *g_pixel_got_slot;
        g_pixel_orig_saved = 1;
    }
    *g_pixel_got_slot = enable ? (uintptr_t) g_pixel_alias_addr : g_pixel_orig_value;

    if (mprotect((void *) page, (size_t) pagesize, restore_prot) != 0) {
        LOGE("pixel-gfx: mprotect(restore 0x%x) failed: %s", restore_prot, strerror(errno));
        // Not fatal to the toggle itself (the write above already landed) --
        // just means this page is left more permissive than it started.
    }

    pixel_patch_slot(g_pixel_texpi_slot, &g_pixel_texpi_orig, &g_pixel_texpi_orig_saved,
                      (uintptr_t) hooked_glTexParameteri, enable);
    pixel_patch_slot(g_pixel_texpf_slot, &g_pixel_texpf_orig, &g_pixel_texpf_orig_saved,
                      (uintptr_t) hooked_glTexParameterf, enable);
    // glTexImage2D is intentionally NOT re-patched here on every toggle --
    // it was installed unconditionally (enable=1) in the one-time setup
    // block above and must stay installed even when `enable` is false, so
    // texture replacement keeps working with pixel graphics off. See the
    // comment at its install site.
    pixel_patch_slot(g_pixel_genmip_slot, &g_pixel_genmip_orig, &g_pixel_genmip_orig_saved,
                      (uintptr_t) hooked_glGenerateMipmap, enable);

    LOGI("pixel-gfx: %s (aa-slot=%p value=%p; texpi-slot=%p texpf-slot=%p teximg-slot=%p "
         "genmip-slot=%p)",
         enable ? "enabled (GL_NEAREST)" : "disabled (GL_LINEAR)",
         (void *) g_pixel_got_slot, (void *) *g_pixel_got_slot,
         (void *) g_pixel_texpi_slot, (void *) g_pixel_texpf_slot,
         (void *) g_pixel_teximg_slot, (void *) g_pixel_genmip_slot);
    return JNI_TRUE;
}

// Enables/disables the 2x2 decimation done inside hooked_glTexImage2D (see
// the "mechanism 4" block above). Independent of nativeSetPixelGraphics's
// GOT patching -- this only flips a flag the hook already in place checks --
// so it's safe to call whether or not the glTexImage2D hook is installed
// (the flag is simply inert if it isn't). Off by default. Java flips it on
// right after nativeSetPixelGraphics() in applyPixelGraphicsPref.
JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeSetPixelDecimate(JNIEnv *env, jclass cls,
                                                                   jboolean on) {
    g_pixel_decimate = on ? 1 : 0;
    LOGI("pixel-gfx: decimate %s", g_pixel_decimate ? "enabled" : "disabled");
}

// Dumps the running pixel-graphics diagnostic counters (glTexImage2D calls,
// glGenerateMipmap calls, glTexParameteri LINEAR->NEAREST rewrites) as one
// LOGI line, on demand from Java.
JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeLogPixelStats(JNIEnv *env, jclass cls) {
    LOGI("pixel-gfx: stats texImage2D_calls=%u glGenerateMipmap_calls=%u texParam_rewrites=%u",
         g_pixel_teximage_calls, g_pixel_genmipmap_calls, g_pixel_texparami_rewrites);
}

// Loads the whole replacement registry from a text index built by Java's
// OrigArtCache#refresh: `indexPath` is "<filesDir>/orig_art_cache/index.txt",
// one line per sheet, "<name> <w> <h> <alphaFp hex16> <redFp hex16>" (name is
// the asset basename, e.g. "c000_0.png", matched against g_pending_tex_path
// -- see mechanism 5 above -- or, failing that, by content fingerprint
// against alphaFp/redFp -- see mechanism 5b / tex_fingerprint above; fps are
// zero-padded lowercase 16-hex-digit, e.g. via Java's "%016x" on a long).
// `rgbaDir` is the directory holding "<name>.rgba" (tightly packed
// PREMULTIPLIED RGBA8888, w*h*4 bytes each) -- each entry's rgba_path is
// built as "<rgbaDir>/<name>.rgba" and read lazily (fread) on a match inside
// hooked_glTexImage2D, never held decoded here. Replaces the registry
// wholesale (resets g_tex_repl_count first), so this is idempotent and safe
// to call again from the settings toggle. A malformed line (wrong field
// count, empty/too-long name, non-positive or implausible w*h*4) is skipped
// and logged rather than aborting the whole load. Called from a background
// Java thread at boot and from the pixel-graphics settings toggle; consulted
// from the GL thread inside hooked_glTexImage2D -- g_tex_repl_mutex covers
// the handoff. Returns the number of entries loaded (0 if indexPath/rgbaDir
// is null or the index can't be opened).
JNIEXPORT jint JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeLoadTextureReplacementIndex(
        JNIEnv *env, jclass cls, jstring indexPath, jstring rgbaDir) {
    if (!indexPath || !rgbaDir) return 0;
    const char *cindex = (*env)->GetStringUTFChars(env, indexPath, NULL);
    const char *cdir = cindex ? (*env)->GetStringUTFChars(env, rgbaDir, NULL) : NULL;
    int count = 0;

    if (cindex && cdir) {
        FILE *f = fopen(cindex, "r");
        if (f) {
            pthread_mutex_lock(&g_tex_repl_mutex);
            g_tex_repl_count = 0;
            char line[512];
            while (fgets(line, sizeof(line), f) && g_tex_repl_count < TEX_REPL_MAX) {
                char name[64];
                int w = 0, h = 0;
                unsigned long long afp = 0, rfp = 0;
                int n = sscanf(line, "%63s %d %d %16llx %16llx", name, &w, &h, &afp, &rfp);
                if (n != 5) continue;
                if (w <= 0 || h <= 0) continue;
                size_t nlen = strlen(name);
                if (nlen == 0 || nlen >= TEX_REPL_NAME_MAX) {
                    LOGE("pixel-gfx: index: name empty/too long, skipping: %s", name);
                    continue;
                }
                size_t needed = (size_t) w * (size_t) h * 4;
                if (needed == 0 || needed > (size_t) 64 * 1024 * 1024) {
                    LOGE("pixel-gfx: index: implausible size %dx%d for %s, skipping", w, h, name);
                    continue;
                }
                tex_replacement_t *slot = &g_tex_repl[g_tex_repl_count++];
                memset(slot, 0, sizeof(*slot));
                strncpy(slot->name, name, sizeof(slot->name) - 1);
                slot->w = w;
                slot->h = h;
                slot->alpha_fp = (uint64_t) afp;
                slot->red_fp = (uint64_t) rfp;
                snprintf(slot->rgba_path, sizeof(slot->rgba_path), "%s/%s.rgba", cdir, name);
            }
            count = g_tex_repl_count;
            pthread_mutex_unlock(&g_tex_repl_mutex);
            fclose(f);
        } else {
            LOGE("pixel-gfx: nativeLoadTextureReplacementIndex: failed to open %s", cindex);
        }
    }

    if (cdir) (*env)->ReleaseStringUTFChars(env, rgbaDir, cdir);
    if (cindex) (*env)->ReleaseStringUTFChars(env, indexPath, cindex);
    LOGI("pixel-gfx: loaded %d texture replacement(s) from index", count);
    return count;
}

// Clears the replacement registry (used when the pixel-graphics pref is
// toggled off). No pixel buffers to free -- entries are just name/size/
// fingerprint/path -- so this is just a count reset under the mutex.
JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeClearTextureReplacements(JNIEnv *env, jclass cls) {
    pthread_mutex_lock(&g_tex_repl_mutex);
    g_tex_repl_count = 0;
    pthread_mutex_unlock(&g_tex_repl_mutex);
    LOGI("pixel-gfx: cleared all texture replacements");
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

// Current field-map/location id, straight off ChronoCanvas -- a plain int32
// read (no scene-graph walk), so unlike nativeUpdateMapName this is safe to
// call from any thread. -1 when the canvas or the read is unavailable.
JNIEXPORT jint JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeGetFieldMapId(JNIEnv *env, jclass cls) {
    if (!p_getInstance) return -1;
    uint8_t *canvas = (uint8_t *)p_getInstance();
    if (!canvas) return -1;
    int32_t id;
    if (!safe_read(canvas + FIELD_MAP_ID_OFFSET, &id, sizeof(id))) return -1;
    return (jint) id;
}

// Party leader's in-field tile position, from the CHARACTER_DATa record for
// party slot 1 (record index 1, i.e. cSfcWork + CHARA_BASE + 1*CHARA_STRIDE
// -- same base/stride as nativeReadChara). Verified live by differential
// dumps: int32 X tile @+0x80, int32 X*256 sub-tile @+0x84, int32 Y tile
// @+0x8c, int32 Y*256 sub-tile @+0x90 (Y grows downward). Only meaningful in
// field maps, not on the overworld. Plain safe_read, so like
// nativeGetFieldMapId this is safe to call from any thread. Returns [x, y]
// as floats (sub-tile / 256.0f, preferred for sub-pixel precision; falls
// back to the plain tile ints if the sub-tile read fails), or NULL if the
// record is unreadable.
#define FIELD_POS_X_TILE_OFFSET  0x80
#define FIELD_POS_X_SUB_OFFSET   0x84
#define FIELD_POS_Y_TILE_OFFSET  0x8c
#define FIELD_POS_Y_SUB_OFFSET   0x90

JNIEXPORT jfloatArray JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeGetFieldPos(JNIEnv *env, jclass cls) {
    uint8_t *sfc = sfc_work();
    if (!sfc) return NULL;
    uint8_t *rec = sfc + CHARA_BASE + 1 * CHARA_STRIDE;

    int32_t xSub, ySub;
    int gotXSub = safe_read(rec + FIELD_POS_X_SUB_OFFSET, &xSub, sizeof(xSub));
    int gotYSub = safe_read(rec + FIELD_POS_Y_SUB_OFFSET, &ySub, sizeof(ySub));

    float x, y;
    if (gotXSub && gotYSub) {
        x = xSub / 256.0f;
        y = ySub / 256.0f;
    } else {
        int32_t xTile, yTile;
        if (!safe_read(rec + FIELD_POS_X_TILE_OFFSET, &xTile, sizeof(xTile))) return NULL;
        if (!safe_read(rec + FIELD_POS_Y_TILE_OFFSET, &yTile, sizeof(yTile))) return NULL;
        x = (float) xTile;
        y = (float) yTile;
    }

    jfloatArray arr = (*env)->NewFloatArray(env, 2);
    if (!arr) return NULL;
    float buf[2] = { x, y };
    (*env)->SetFloatArrayRegion(env, arr, 0, 2, buf);
    return arr;
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
// Battle list submenu (Tech/Item) cache -- declared here (statics must
// precede use) so nativeUpdateBattleFlag below can reset it on battle-end;
// filled by collect_battle_list, defined further down with the rest of the
// scan (after g_battle_toggles/nativeGetBattleToggles) but called from here
// so it runs in the same GL-thread scan/cadence that fills g_battle_toggles.
#define MAX_BATTLE_LIST_ROWS 64
typedef struct { int32_t id; int usable; int32_t extra; float x, y; } BattleListRow;
static int g_battle_list_kind = -1; // -1 none, 0 Tech, 1 Item
static int g_battle_list_count;
static BattleListRow g_battle_list_rows[MAX_BATTLE_LIST_ROWS];
// Rate-limit the summary log line to once per kind/count change.
static int g_last_logged_list_kind = -2;
static int g_last_logged_list_count = -1;
static void collect_battle_list(void *battle_node);

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
        collect_battle_list(node);
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
        g_battle_list_kind = -1;
        g_battle_list_count = 0;
        g_last_logged_list_kind = -2;
        g_last_logged_list_count = -1;
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

// ---------------------------------------------------------------------------
// Battle list submenus (Tech/Item): mirror whichever BattleTechMenu/
// BattleItemMenu is currently open (BattleListMenuBase offsets from NOTES.md
// "Battle UI hiding, battle MP, list submenus" RE record). Collected in the
// same GL-thread scan that fills g_battle_toggles above (called from
// nativeUpdateBattleFlag, right after collect_toggles_rec), so it shares its
// thread/cadence. GL thread only -- touches the live scene graph.
// ---------------------------------------------------------------------------

// BattleListMenuBase (this-relative) member offsets, arm64.
#define BATTLELIST_SCROLLVIEW  0x330
#define BATTLELIST_ISOPEN      0x380
#define BATTLELIST_VEC_BEGIN   0x388
#define BATTLELIST_VEC_END     0x390
#define BATTLELIST_ROW_STRIDE  12

// MAX_BATTLE_LIST_ROWS, BattleListRow, g_battle_list_kind/count/rows and
// g_last_logged_list_kind/count are declared earlier, right before
// nativeUpdateBattleFlag (statics must precede use there too).

// Reads the on-screen row-button node for row `i` and its world center.
// Preferred path: BattleListMenuBase::getElement(int) const, a bounds-
// checked accessor the game itself uses (getButton/scrollToChildIfNeeded)
// to reach the row buttons -- this is required because cocos2d::ui::
// ScrollView overrides the virtual getChildren() to return its *inner
// container's* children, so the dlsym'd non-virtual Node::getChildren on
// the ScrollView object sees an empty vector (confirmed live: children=-1
// on a depth-9 scene dump with the Tech list open). Only called when
// `use_getElement` (p_list_getElement resolved AND the submenu's vtable
// checked in-library) is set. Falls back to a direct ScrollView child-
// vector walk (rc_begin/rc_end from resolve_row_container) only when
// getElement isn't available -- kept as a last-resort path, not expected to
// find real rows given the above (ScrollView's own children are empty; a
// nested container might still work by luck, so it's not removed outright).
static void battle_list_row_pos(int use_getElement, void *submenu_node,
                                 void **rc_begin, void **rc_end, int i, float *ox, float *oy) {
    *ox = NAN;
    *oy = NAN;
    if (use_getElement) {
        void *rownode = p_list_getElement(submenu_node, i);
        if (!plausible_ptr(rownode)) return;
        char tb[96];
        if (!type_name(rownode, tb, sizeof(tb))) return; // incoherent RTTI -> not a live object
        node_world_center(rownode, ox, oy); // leaves *ox/*oy untouched (still NaN) on failure
        return;
    }
    if (!rc_begin || (rc_begin + i) >= rc_end) return;
    void *rownode;
    if (!safe_read(rc_begin + i, &rownode, 8) || !plausible_ptr(rownode)) return;
    node_world_center(rownode, ox, oy); // leaves *ox/*oy untouched (still NaN) on failure
}

// Fallback-only (see battle_list_row_pos): given a resolved node whose
// direct children vector holds `want_count` or more entries, tries the node
// itself first, then (cocos2d-x ui::ScrollView wraps an inner container)
// its first child that has enough children of its own. Returns 1 and fills
// *out_begin/*out_end on success.
static int resolve_row_container(void *scrollview, int want_count, void ***out_begin, void ***out_end) {
    if (!scrollview || !vtable_in_libchrono(scrollview) || !p_node_getChildren) return 0;
    void *svvecp = p_node_getChildren(scrollview);
    void *svptrs[2];
    if (!safe_read(svvecp, svptrs, 16)) return 0;
    void **sb = (void **)svptrs[0], **se = (void **)svptrs[1];
    if (!plausible_any(sb) || !plausible_any(se) || se < sb || (se - sb) > 512) return 0;
    if ((int)(se - sb) >= want_count) {
        *out_begin = sb;
        *out_end = se;
        return 1;
    }
    for (void **c = sb; c < se; c++) {
        void *child;
        if (!safe_read(c, &child, 8)) continue;
        if (!vtable_in_libchrono(child) || !p_node_getChildren) continue;
        void *cvecp = p_node_getChildren(child);
        void *cptrs[2];
        if (!safe_read(cvecp, cptrs, 16)) continue;
        void **cb = (void **)cptrs[0], **ce = (void **)cptrs[1];
        if (!plausible_any(cb) || !plausible_any(ce) || ce < cb || (ce - cb) > 512) continue;
        if ((int)(ce - cb) >= want_count) {
            *out_begin = cb;
            *out_end = ce;
            return 1;
        }
    }
    return 0;
}

// Populates g_battle_list_kind/count/rows from whichever BattleTechMenu/
// BattleItemMenu direct child of `battle_node` is currently open. Clears the
// cache (kind=-1, count=0) when none or more-than-one is open, or on any
// validation failure -- matches nativeGetBattleList's "NULL when no submenu
// is open" contract.
static void collect_battle_list(void *battle_node) {
    g_battle_list_kind = -1;
    g_battle_list_count = 0;
    if (!battle_node || !p_node_getChildren) return;
    void *vecp = p_node_getChildren(battle_node);
    void *ptrs[2];
    if (!safe_read(vecp, ptrs, 16)) return;
    void **begin = (void **)ptrs[0], **end = (void **)ptrs[1];
    if (!plausible_any(begin) || !plausible_any(end) || end < begin || (end - begin) > 512) return;

    void *tech_node = NULL, *item_node = NULL;
    for (void **c = begin; c < end; c++) {
        void *child;
        if (!safe_read(c, &child, 8)) continue;
        char tb[96];
        const char *tn = type_name(child, tb, sizeof(tb));
        if (!tn) continue;
        if (strcmp(tn, "N16nsBattleListMenu14BattleTechMenuE") == 0) tech_node = child;
        else if (strcmp(tn, "N16nsBattleListMenu14BattleItemMenuE") == 0) item_node = child;
    }

    int open_kind = -1;
    void *open_node = NULL;
    uint8_t isopen;
    if (tech_node && safe_read((uint8_t *)tech_node + BATTLELIST_ISOPEN, &isopen, 1) && isopen) {
        open_kind = 0;
        open_node = tech_node;
    }
    if (item_node && safe_read((uint8_t *)item_node + BATTLELIST_ISOPEN, &isopen, 1) && isopen) {
        if (open_kind != -1) return; // both open -- ambiguous, bail (spec: exactly one)
        open_kind = 1;
        open_node = item_node;
    }
    if (open_kind == -1 || !open_node) return;
    // Row reads below (getElement call included) require a live vtable, not
    // just a coherent-looking one -- see vtable_in_libchrono's comment.
    if (!vtable_in_libchrono(open_node)) return;

    uint64_t vbegin, vend;
    if (!safe_read((uint8_t *)open_node + BATTLELIST_VEC_BEGIN, &vbegin, 8)) return;
    if (!safe_read((uint8_t *)open_node + BATTLELIST_VEC_END, &vend, 8)) return;
    if (!plausible_any((void *)vbegin) || !plausible_any((void *)vend) || vend < vbegin) return;
    uint64_t nbytes = vend - vbegin;
    if (nbytes % BATTLELIST_ROW_STRIDE != 0) return;
    int count = (int)(nbytes / BATTLELIST_ROW_STRIDE);
    if (count < 0 || count > MAX_BATTLE_LIST_ROWS) return;

    // Preferred row-position path: BattleListMenuBase::getElement(int) --
    // already gated on isOpen (open_kind resolved above) and vtable_in_
    // libchrono(open_node) (checked above). Only fall back to the raw
    // ScrollView child-vector walk when getElement isn't resolvable, since
    // ui::ScrollView's overridden getChildren() means that walk normally
    // finds nothing (see battle_list_row_pos comment).
    int use_getElement = p_list_getElement != NULL;
    void **rc_begin = NULL, **rc_end = NULL;
    if (!use_getElement) {
        void *scrollview = NULL;
        safe_read((uint8_t *)open_node + BATTLELIST_SCROLLVIEW, &scrollview, 8);
        resolve_row_container(scrollview, count, &rc_begin, &rc_end); // leaves both NULL on failure
    }

    for (int i = 0; i < count; i++) {
        uint8_t rowbuf[BATTLELIST_ROW_STRIDE];
        BattleListRow *out = &g_battle_list_rows[i];
        if (!safe_read((uint8_t *)vbegin + (size_t)i * BATTLELIST_ROW_STRIDE, rowbuf, BATTLELIST_ROW_STRIDE)) {
            out->id = 0;
            out->usable = 0;
            out->extra = 0;
            out->x = NAN;
            out->y = NAN;
            continue;
        }
        int32_t id, extra;
        uint8_t usable;
        memcpy(&id, rowbuf + 0, 4);
        if (open_kind == 0) { // Tech: id, param, usable
            memcpy(&extra, rowbuf + 4, 4);
            usable = rowbuf[8];
        } else { // Item: id, usable, pad, count
            usable = rowbuf[4];
            memcpy(&extra, rowbuf + 8, 4);
        }
        out->id = id;
        out->usable = usable ? 1 : 0;
        out->extra = extra;
        battle_list_row_pos(use_getElement, open_node, rc_begin, rc_end, i, &out->x, &out->y);
    }

    g_battle_list_kind = open_kind;
    g_battle_list_count = count;

    if (open_kind != g_last_logged_list_kind || count != g_last_logged_list_count) {
        LOGI("battle-list: kind=%d count=%d first id=%d usable=%d pos=(%.1f,%.1f)",
             open_kind, count,
             count > 0 ? g_battle_list_rows[0].id : 0,
             count > 0 ? g_battle_list_rows[0].usable : 0,
             count > 0 ? g_battle_list_rows[0].x : 0.0f,
             count > 0 ? g_battle_list_rows[0].y : 0.0f);
        g_last_logged_list_kind = open_kind;
        g_last_logged_list_count = count;
    }
}

// Cached battle list submenu (Tech/Item), populated on the GL thread by
// nativeUpdateBattleFlag right after the battle toggle scan. Returns NULL
// when no submenu is open; otherwise a float array laid out as:
//   [kind, count, id0, usable0, extra0, x0, y0, id1, usable1, extra1, x1, y1, ...]
// kind is 0 (Tech) or 1 (Item); usable is 0.0/1.0; extra is the tech's param
// (cost) or the item's count, cast to float; x/y are worldspace pixels in
// the same space as nativeGetBattleToggles, or NaN when the row's on-screen
// button node couldn't be resolved (Java should skip tapping that row). Safe
// to call from any thread -- plain read of the cached array.
JNIEXPORT jfloatArray JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeGetBattleList(JNIEnv *env, jclass cls) {
    if (g_battle_list_kind < 0) return NULL;
    int count = g_battle_list_count;
    jfloatArray arr = (*env)->NewFloatArray(env, 2 + count * 5);
    if (!arr) return NULL;
    float buf[2 + MAX_BATTLE_LIST_ROWS * 5];
    buf[0] = (float) g_battle_list_kind;
    buf[1] = (float) count;
    for (int i = 0; i < count; i++) {
        buf[2 + i * 5 + 0] = (float) g_battle_list_rows[i].id;
        buf[2 + i * 5 + 1] = g_battle_list_rows[i].usable ? 1.0f : 0.0f;
        buf[2 + i * 5 + 2] = (float) g_battle_list_rows[i].extra;
        buf[2 + i * 5 + 3] = g_battle_list_rows[i].x;
        buf[2 + i * 5 + 4] = g_battle_list_rows[i].y;
    }
    (*env)->SetFloatArrayRegion(env, arr, 0, 2 + count * 5, buf);
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
// anything else with "Battle" in its RTTI name) are excluded by default: we
// don't mirror their list contents on the bottom screen yet, so hiding them
// would leave tech/item selection invisible everywhere. Damage numbers and
// the target cursor live outside this set and are unaffected. Once
// nativeGetBattleList's mirror is wired up on the Java side,
// nativeSetHideBattleSubmenus(true) opts the two list menus into hiding too
// (see g_hide_battle_submenus below).
// The matching node gets cascade-opacity enabled (so setOpacity below
// actually propagates to children instead of only dimming the container),
// then setOpacity(0) applied EVERY tick, since the game may reassert its own
// opacity whenever the menu (re)opens.

// Java-settable opt-in (see nativeSetHideBattleSubmenus) to also hide the
// BattleTechMenu/BattleItemMenu submenu nodes once their contents are
// mirrored via nativeGetBattleList. Defaults to disabled -- see comment
// above should_hide_battle_child.
static int g_hide_battle_submenus = 0;

JNIEXPORT void JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeSetHideBattleSubmenus(JNIEnv *env, jclass cls,
                                                                       jboolean hide) {
    g_hide_battle_submenus = hide ? 1 : 0;
}

// ---------------------------------------------------------------------------
// Battle results phase: once every enemy is dead, the game draws "Earned N
// EXP/TP/item" message windows into the same RenderTexture/Node/Label
// children enforce_battle_ui_hide blanks every tick below -- so without this
// the player sees a black battle screen with no results text and it looks
// stalled. battle_results_phase() detects that state from the live actor
// array (same pointer chase as nativeReadBattleActors: g_battle_node ->
// +0x320 SceneBattle -> +0x68 chara array, stride 0x80/slot, u16 curHP at
// +0x03, u16 maxHP at +0x05, party in slots 0-2, enemies in slots 3+), so
// enforce_battle_ui_hide can stop blanking (and actively restore opacity on)
// those specific child types while still hiding the battle cocos2d::Menu,
// which has nothing useful to show post-victory. Local offset/stride
// constants (not the BTLCHARA_* ones below, which are defined further down
// the file, after this point -- statics/macros must precede use in C).
// ---------------------------------------------------------------------------

#define BTLRES_CHARA_OFFSET  0x68
#define BTLRES_ACTOR_STRIDE  0x80
#define BTLRES_ACTOR_SLOTS   10
#define BTLRES_CURHP_OFFSET  0x03
#define BTLRES_MAXHP_OFFSET  0x05
#define BTLRES_PARTY_SLOTS   3 // slots 0-2 are party; 3+ are enemies

static int g_results_unhide = 0;
static int g_battle_results_phase; // current per-tick predicate value, read by enforce_battle_ui_hide
static int g_battle_results_was;   // previous value, for edge-triggered enter/leave logging

// True iff the live actor array shows at least one enemy slot (index >=
// BTLRES_PARTY_SLOTS) with maxHP>0 was observed, and every such enemy slot
// has curHP==0 (i.e. the battle is won and results are pending). False
// whenever any link in the safe_read chain is unreadable, or no enemy slot
// has been populated yet -- the latter guards against a false positive at
// battle start, before the array fills in (an all-zero fresh array would
// otherwise read as "every enemy at 0 HP").
static int battle_results_phase(void) {
    if (!g_battle_node) return 0;
    uint8_t *sb = NULL;
    if (!safe_read((uint8_t *)g_battle_node + 0x320, &sb, sizeof(sb)) || !plausible_any(sb)) {
        return 0;
    }
    uint8_t *chara_ptr = NULL;
    if (!safe_read(sb + BTLRES_CHARA_OFFSET, &chara_ptr, sizeof(chara_ptr))
            || !plausible_any(chara_ptr)) {
        return 0;
    }

    int saw_enemy = 0;
    for (int i = BTLRES_PARTY_SLOTS; i < BTLRES_ACTOR_SLOTS; i++) {
        uint8_t *slot = chara_ptr + (size_t)i * BTLRES_ACTOR_STRIDE;
        uint16_t curHp, maxHp;
        if (!safe_read(slot + BTLRES_CURHP_OFFSET, &curHp, sizeof(curHp))) return 0;
        if (!safe_read(slot + BTLRES_MAXHP_OFFSET, &maxHp, sizeof(maxHp))) return 0;
        if (maxHp == 0) continue; // slot not present (no enemy here)
        saw_enemy = 1;
        if (curHp != 0) return 0; // still-living enemy -- not results phase
    }
    return saw_enemy;
}

// True for the specific child node types that results-phase message windows
// draw into (RenderTexture cell layer, plain Node, Label) -- these are
// exempted from the blanking loop below (and actively restored to opacity
// 255) while battle_results_phase() holds. cocos2d::Menu is deliberately not
// included here: it has nothing useful to show post-victory and stays
// hidden.
static int is_battle_results_child(const char *tn) {
    if (!tn) return 0;
    return strcmp(tn, "N7cocos2d13RenderTextureE") == 0 ||
           strcmp(tn, "N7cocos2d4NodeE") == 0 ||
           strcmp(tn, "N7cocos2d5LabelE") == 0;
}

static int should_hide_battle_child(const char *tn) {
    if (!tn) return 0;
    if (g_hide_battle_submenus &&
            (strcmp(tn, "N16nsBattleListMenu14BattleTechMenuE") == 0 ||
             strcmp(tn, "N16nsBattleListMenu14BattleItemMenuE") == 0)) {
        return 1;
    }
    if (strstr(tn, "Battle")) return 0;
    if (strstr(tn, "cocos2d") && strstr(tn, "4MenuE")) return 1;
    if (strcmp(tn, "N7cocos2d13RenderTextureE") == 0) return 1;
    if (strcmp(tn, "N7cocos2d4NodeE") == 0) return 1;
    if (strcmp(tn, "N7cocos2d5LabelE") == 0) return 1;
    return 0;
}

static void enforce_battle_ui_hide(void) {
    // Computed every tick regardless of the checks below, so entering/
    // leaving results phase is logged (and g_battle_results_phase stays
    // current for the child loop) even on a tick where the rest of this
    // function bails early.
    int results_phase = battle_results_phase();
    if (results_phase != g_battle_results_was) {
        LOGI("battle-ui: results phase %s", results_phase ? "entered" : "left");
        g_battle_results_was = results_phase;
    }
    g_battle_results_phase = results_phase;

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
        // Results phase: the RenderTexture/Node/Label children are where the
        // "Earned N EXP/TP/item" windows get drawn, so unblank (and actively
        // restore, since the blanking loop above already forced them to 0 on
        // prior ticks) instead of re-blanking. cocos2d::Menu (hide_type from
        // the "4MenuE" check above) is not in is_battle_results_child, so it
        // still falls through to the setOpacity(0) below and stays hidden.
        // Results-phase unhide is OFF: the Earned EXP/TP/G windows are now
        // mirrored on the bottom screen from the battle work struct (see
        // nativeGetBattleResults), so the cell layer stays blanked and the
        // party HP box never reappears. Flip g_results_unhide to restore.
        if (g_results_unhide && g_battle_results_phase && is_battle_results_child(tn)) {
            if (p_node_setOpacity) p_node_setOpacity(child, 255);
            continue;
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

// ---------------------------------------------------------------------------
// Battle results accumulator (EXP/Gold/TP/item drops), read from the native
// "battlework" struct at *(SceneBattle+0x60) -- a separate allocation from
// both the SNES-emulated Asm memory (*(sb+0x8)) and the actor array
// (sb+0x68, see nativeReadBattleActors). See scratchpad/battle_results_report.md
// for the disassembly this is derived from (SceneBattle::exp_get/comment_out2).
// Layout, all offsets relative to bw = *(u64*)(sb+0x60):
//   +0x1640 u32 total EXP gained this fight
//   +0x1694 u32 total Gold gained
//   +0x1758 u32 total TP gained
//   +0x16b8 u8  step-enable bitfield (bit meanings per comment_out2's guards)
//   +0x16b0 i32[] item-drop list, walked forward here (up to 8 slots)
// sb+0x22f4 (i32) is comment_out2's own results-phase step index (0=EXP,
// 2=TP, 4=Gold, 8/16/24=items, ... 32=idle) -- see battle_results_phase()
// above, which reads the same field for a different purpose (party/enemy
// scene visibility, not the message content itself).
// ---------------------------------------------------------------------------

// Sanity bounds for the candidate exp/gold/tp values tried below -- plainly
// implausible results screens (garbage memory misread as these fields) blow
// past these, a real fight never will.
static const uint32_t RESULTS_EXP_MAX  = 100000u;
static const uint32_t RESULTS_GOLD_MAX = 1000000u;
static const uint32_t RESULTS_TP_MAX   = 1000u;

// Reads the candidate exp/gold/tp/flags fields at the same fixed relative
// offsets (+0x1640/+0x1694/+0x1758/+0x16b8) off `base`, whatever `base`
// turns out to actually be for a given candidate. Returns 1 and fills
// *exp/*gold/*tp/*flags only when all three of exp/gold/tp read cleanly and
// fall within the sanity bounds above; 0 otherwise (candidate rejected).
static int results_try_base(uint8_t *base, uint32_t *exp, uint32_t *gold, uint32_t *tp,
                             uint8_t *flags) {
    uint32_t e = 0, g = 0, t = 0;
    if (!safe_read(base + 0x1640, &e, sizeof(e))) return 0;
    if (!safe_read(base + 0x1694, &g, sizeof(g))) return 0;
    if (!safe_read(base + 0x1758, &t, sizeof(t))) return 0;
    if (e > RESULTS_EXP_MAX || g > RESULTS_GOLD_MAX || t > RESULTS_TP_MAX) return 0;
    uint8_t f = 0;
    safe_read(base + 0x16b8, &f, sizeof(f));
    *exp = e; *gold = g; *tp = t; *flags = f;
    return 1;
}

// Returns NULL when there's no active battle node or SceneBattle can't be
// resolved. Otherwise an int array [step, exp, gold, tp, flags, itemCount,
// items...].
//
// The battlework base at *(sb+0x60) fails plausible_ptr in practice (see
// battle_results_report.md), so this tries three candidate interpretations
// of "where the accumulator struct actually is", in order, and uses the
// first one whose exp/gold/tp come out sane:
//   A: the raw u64 at sb+0x60, treated as a pointer, if it passes
//      plausible_ptr (the original assumption).
//   B: an embedded struct living directly at sb+0x60 itself (no extra
//      indirection) -- i.e. exp read from sb+0x60+0x1640, etc.
//   C: the pointer at sb+0x8, the SNES-emulated Asm memory base already
//      used elsewhere (see nativeReadBattleActors's comment) -- the
//      accumulator might live in that emulated RAM at the same relative
//      offsets.
// If none is sane, returns [step, -1, -1, -1, 0, 0] (flags 0, itemCount 0)
// with step still populated, so callers can distinguish "not in results
// yet" from "results active but accumulator unreadable". Safe to call from
// any thread -- plain safe_read chase off the cached g_battle_node pointer,
// like nativeReadBattleActors/battle_results_phase.
JNIEXPORT jintArray JNICALL
Java_com_kalenjohnson_chronoduo_GameState_nativeGetBattleResults(JNIEnv *env, jclass cls) {
    if (!g_battle_node) return NULL;

    uint8_t *sb = NULL;
    if (!safe_read((uint8_t *)g_battle_node + 0x320, &sb, sizeof(sb)) || !plausible_any(sb)) {
        return NULL;
    }

    int32_t step = 0;
    if (!safe_read(sb + 0x22f4, &step, sizeof(step))) return NULL;

    static int32_t g_last_logged_results_step = INT32_MIN;
    int step_changed = (step != g_last_logged_results_step);

    uint64_t raw_bw = 0;
    safe_read(sb + 0x60, &raw_bw, sizeof(raw_bw));
    if (step_changed) {
        LOGI("battle-results: step=%d raw(sb+0x60)=0x%016llx", step,
             (unsigned long long) raw_bw);
    }

    uint64_t asm_ptr = 0;
    safe_read(sb + 0x8, &asm_ptr, sizeof(asm_ptr));

    uint8_t *candidates[3] = {NULL, NULL, NULL};
    if (plausible_any((void *)raw_bw)) candidates[0] = (uint8_t *)raw_bw;
    candidates[1] = sb + 0x60;
    if (plausible_ptr((void *)asm_ptr)) candidates[2] = (uint8_t *)asm_ptr;

    static const char kLabels[3] = {'A', 'B', 'C'};
    uint32_t exp = 0, gold = 0, tp = 0;
    uint8_t flags = 0;
    uint8_t *bwp = NULL;
    char used = 0;

    for (int i = 0; i < 3; i++) {
        if (!candidates[i]) continue;
        if (results_try_base(candidates[i], &exp, &gold, &tp, &flags)) {
            bwp = candidates[i];
            used = kLabels[i];
            break;
        }
    }

    if (!bwp) {
        jint fallback[6] = {step, -1, -1, -1, 0, 0};
        jintArray arr = (*env)->NewIntArray(env, 6);
        if (!arr) return NULL;
        (*env)->SetIntArrayRegion(env, arr, 0, 6, fallback);
        if (step_changed) {
            LOGI("battle-results: step=%d (no candidate base sane)", step);
            g_last_logged_results_step = step;
        }
        return arr;
    }

    int32_t items[8];
    int itemCount = 0;
    for (int i = 0; i < 8; i++) {
        int32_t v;
        if (!safe_read(bwp + 0x16b0 + (size_t)i * 4, &v, sizeof(v))) break;
        if (v <= 0 || v > 0xFFFF) break;
        items[itemCount++] = v;
    }

    if (step_changed) {
        LOGI("battle-results: base=%c exp=%u gold=%u tp=%u", used, exp, gold, tp);
        g_last_logged_results_step = step;
    }

    jint buf[6 + 8];
    buf[0] = step;
    buf[1] = (jint) exp;
    buf[2] = (jint) gold;
    buf[3] = (jint) tp;
    buf[4] = flags;
    buf[5] = itemCount;
    for (int i = 0; i < itemCount; i++) buf[6 + i] = items[i];

    jintArray arr = (*env)->NewIntArray(env, 6 + itemCount);
    if (!arr) return NULL;
    (*env)->SetIntArrayRegion(env, arr, 0, 6 + itemCount, buf);
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
