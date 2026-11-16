/* cp-climate M1: tell the iPhone the car speaks the vehicle state protocol.
 *
 *  - /info gets vehicleStateProtocolInfo (LIVI's description, built at compile time as a
 *    binary plist) and displayPanels (from the car's own "displays", as LIVI builds them).
 *  - /info's main display gets one full-screen viewArea with the status bar (the dock) at the
 *    bottom edge, as LIVI sends by default: iOS only gives the dock its climate controls (a
 *    temperature at each end) in that layout; without a viewArea it draws the side dock
 *    ("No climate secondary dock frame").
 *    With a CarPlay size below 100 % (cp-climate's setting, in DiPO's files/cpc-size), the
 *    main display's physical size is reported larger by 100/size, so iOS draws everything
 *    smaller and the screen holds more points (pixels unchanged).
 *  - The session SETUP reply gets "enabledFeatures": ["vehicleStateProtocol"] when the
 *    phone offered it: iOS only accepts vehicleStateProtocolInfo in /info for a feature
 *    negotiated there ("entry for vehicleStateProtocolInfo found but not negotiated").
 *
 * Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
 *
 * Runs inside DiPO, as code mapped next to libCarPlayCore: no globals, no libc. Everything
 * the library has is reached through cpc_table (see cpc.h). */
#include "cpc.h"

/* test variants (tools/build.py --variant): what M1 adds */
#ifndef CPC_INFO
#define CPC_INFO 1       /* /info: vehicleStateProtocolInfo + displayPanels */
#endif
#ifndef CPC_CLUSTER
#define CPC_CLUSTER 1    /* displayPanels: LIVI's extra instrument panel */
#endif
#ifndef CPC_FEATURES
#define CPC_FEATURES 1   /* SETUP reply: enabledFeatures [vehicleStateProtocol] */
#endif
#ifndef CPC_PANELS
#define CPC_PANELS 1     /* /info: displayPanels */
#endif
#ifndef CPC_VIEWAREA
#define CPC_VIEWAREA 1   /* /info: the main display's bottom-dock viewArea */
#endif
#ifndef CPC_DIAG
#define CPC_DIAG 0       /* /info and SETUP stock; save what /info would be */
#endif

typedef const void *CF;
typedef long CFIndex;

extern const int cpc_table[] __attribute__((visibility("hidden")));
extern const unsigned char cpc_vspi[] __attribute__((visibility("hidden")));
#ifdef CPC_HOST_TEST
extern unsigned long cpc_vspi_len;   /* tests/host_test.c */
#define VSPI_LEN cpc_vspi_len
#else
extern const unsigned char cpc_vspi_end[] __attribute__((visibility("hidden")));
#define VSPI_LEN ((unsigned long)(cpc_vspi_end - cpc_vspi))
#endif

#define AT(i) ((void *)((const char *)cpc_table + cpc_table[i]))
#define FN(i, type) ((type)AT(i))

#define UTF8 0x08000100u
#define LEVEL 50   /* Notice: what the Core category prints */

/* CFLite's constant strings: a static header, then the characters */
#define CFS(name, text) \
    static const struct { unsigned type, refs; char s[sizeof(text)]; } name \
        __attribute__((aligned(4))) = { 0x10756u, 0x7fffffffu, text }

CFS(K_vspi, "vehicleStateProtocolInfo");
CFS(K_vsp, "vehicleStateProtocol");
CFS(K_features, "features");                 /* the phone's offer, in its SETUP request */
CFS(K_enabledFeatures, "enabledFeatures");   /* what the reply negotiates (LIVI stack/setup.rs) */
CFS(K_displays, "displays");
CFS(K_displayPanels, "displayPanels");
CFS(K_uuid, "uuid");
CFS(K_uid, "uid");
CFS(K_widthPixels, "widthPixels");
CFS(K_heightPixels, "heightPixels");
CFS(K_widthPhysical, "widthPhysical");
CFS(K_heightPhysical, "heightPhysical");
CFS(K_properties, "properties");
CFS(K_initialVideoStreams, "initialVideoStreams");
CFS(K_originXPixels, "originXPixels");
CFS(K_originYPixels, "originYPixels");
CFS(K_zIndex, "zIndex");
CFS(K_displayPlugins, "displayPlugins");
CFS(K_videoStreams, "videoStreams");
CFS(K_showsInstruments, "showsInstruments");
CFS(K_viewAreas, "viewAreas");
CFS(K_initialViewArea, "initialViewArea");
CFS(K_adjacentViewAreas, "adjacentViewAreas");
CFS(K_statusBarEdge, "viewAreaStatusBarEdge");
CFS(K_transitionControl, "viewAreaTransitionControl");
CFS(K_focusTransfer, "viewAreaSupportsFocusTransfer");
CFS(K_safeArea, "safeArea");
CFS(K_sourceVersion, "sourceVersion");
CFS(K_features_d, "features");
CFS(K_uiAppMode, "uiAppearanceMode");
CFS(K_uiAppSetting, "uiAppearanceSetting");
CFS(K_mapAppMode, "mapAppearanceMode");
CFS(K_mapAppSetting, "mapAppearanceSetting");
CFS(K_liviVersion, "950.7.1");          /* LIVI's AirPlay receiver version */
CFS(K_drawOutside, "drawUIOutsideSafeArea");
CFS(K_cornerMasks, "cornerMasks");
CFS(K_clusterPanel, "b7e6c5a0-3333-4000-8000-000000000003-panel");
CFS(K_clusterPlugin, "b7e6c5a0-3333-4000-8000-000000000003-plugin");
/* the Ultra experiment: a virtual cluster display (LIVI info.rs ALT_UUID, STREAM_TYPE_ALT_SCREEN) */
CFS(K_dispType, "type");
CFS(K_altUuid, "b7e6c5a0-2222-4000-8000-000000000002");
CFS(K_maxFPS, "maxFPS");
CFS(K_primaryInput, "primaryInputDevice");
CFS(K_altScreen, "altScreen");
CFS(K_uiSync, "uiSync");
CFS(K_uiSyncInfo, "uiSyncInfo");
CFS(K_iapChannel, "iAPChannel");   /* iAP over the CarPlay session (LIVI stack/setup.rs) */
CFS(K_videoPlayback, "videoPlayback");   /* LIVI stack/setup.rs enables it whenever the phone offers it */
CFS(K_hevc, "hevc");                     /* LIVI enables it with its HEVC decoder */
CFS(K_vpInfo, "videoPlaybackInfo");      /* LIVI video_playback.rs info_entries */
CFS(K_vpAllowed, "videoPlaybackAllowed");
CFS(K_vpFeaturesEx, "featuresEx");
CFS(K_playbackCaps, "playbackCapabilities");
CFS(K_fpsSecureStop, "supportsFPSSecureStop");
CFS(K_audioOnlyUI, "supportsUIForAudioOnlyContent");
/* the theme's own display ids (Apple's CarDDPAssets package.json, as LIVI uses them) */
CFS(K_centerId, "Center_Display");
CFS(K_clusterId, "Cluster_Display");
CFS(K_hidDevices, "hidDevices");
CFS(K_displayUUID, "displayUUID");
/* the /info feature experiment (LIVI info.rs CARPLAY_FEATURES, extendedFeatures) */
CFS(K_extendedFeatures, "extendedFeatures");
CFS(K_enhancedCarUI, "enhancedRequestCarUI");

static CF get(CF d, CF k) { return FN(T_CFDictionaryGetValue, CF (*)(CF, CF))(d, k); }
static void set(CF d, CF k, CF v) { FN(T_CFDictionarySetValue, void (*)(CF, CF, CF))(d, k, v); }
static void set_int(CF d, CF k, long long v) { FN(T_CFDictionarySetInt64, void (*)(CF, CF, long long))(d, k, v); }
static void release(CF o) { FN(T_CFRelease, void (*)(CF))(o); }
static CFIndex count(CF a) { return FN(T_CFArrayGetCount, CFIndex (*)(CF))(a); }
static CF at(CF a, CFIndex i) { return FN(T_CFArrayGetValueAtIndex, CF (*)(CF, CFIndex))(a, i); }
static int equal(CF a, CF b) { return FN(T_CFEqual, int (*)(CF, CF))(a, b) != 0; }
static void append(CF a, CF v) { FN(T_CFArrayAppendValue, void (*)(CF, CF))(a, v); }
static unsigned long type_of(CF o) { return FN(T_CFGetTypeID, unsigned long (*)(CF))(o); }
static int is(CF o, int type_fn) { return o && type_of(o) == FN(type_fn, unsigned long (*)(void))(); }

static CF new_array(void) {
    return FN(T_CFArrayCreateMutable, CF (*)(CF, CFIndex, const void *))(0, 0, *(void **)AT(T_GOT_ARR));
}

static CF new_dict(void) {
    return FN(T_CFDictionaryCreateMutable, CF (*)(CF, CFIndex, const void *, const void *))(
        0, 0, *(void **)AT(T_GOT_DKEY), *(void **)AT(T_GOT_DVAL));
}

#define LOG(...) FN(T_LogPrintF, void (*)(void *, const char *, int, const char *, ...))( \
    AT(T_LOGCAT), "cp-climate", LEVEL, __VA_ARGS__)

/* puts a new value in d under k and drops our reference */
static void put(CF d, CF k, CF v) {
    if (!v) return;
    set(d, k, v);
    release(v);
}

static void copy_size(CF to, CF from, CF key) {
    CF v = get(from, key);
    if (v) set(to, key, v);
    else set_int(to, key, 0);
}

/* one call per key: an array of key pointers would need relocations this code can't have */
static void copy_sizes(CF to, CF from) {
    copy_size(to, from, &K_widthPixels);
    copy_size(to, from, &K_heightPixels);
    copy_size(to, from, &K_widthPhysical);
    copy_size(to, from, &K_heightPhysical);
}

static long long dict_int(CF d, CF key) {
    int err = 0;
    long long v = FN(T_CFDictionaryGetInt64, long long (*)(CF, CF, int *))(d, key, &err);
    return err ? 0 : v;
}

#define STREAM_MAIN_SCREEN 110  /* LIVI info.rs: the main display's type */
#define STREAM_ALT_SCREEN 111   /* LIVI info.rs: the cluster's video stream and display type */

/* "<uuid>-panel", or 0 */
static CF panel_uid(CF uuid) {
    const char *u = FN(T_CFStringGetCStringPtr, const char *(*)(CF, unsigned))(uuid, UTF8);
    if (!u) return 0;
    char buf[96];
    int n = 0;
    while (u[n] && n < 80) { buf[n] = u[n]; n++; }
    const char *tail = "-panel";
    for (int i = 0; tail[i]; i++) buf[n++] = tail[i];
    buf[n] = 0;
    return FN(T_CFStringCreateWithCString, CF (*)(CF, const char *, unsigned))(0, buf, UTF8);
}

/* LIVI's add_display_panels without a theme: a panel per display of info showing its
 * stream, and an instrument panel (no stream) the size of the first display; put in into */
static CFIndex add_display_panels(CF info, CF into, int cluster) {
    CF displays = get(info, &K_displays);
    if (!is(displays, T_CFArrayGetTypeID)) return -1;
    CF panels = new_array();
    if (!panels) return -1;
    CF first = 0, alt = 0;
    CFIndex n = count(displays);
    /* themed (the displays carry the theme's ids): LIVI's panels with the theme's names, the
     * cluster's own panel carrying the plugin, no extra instrument panel */
    int themed = 0;
    for (CFIndex i = 0; i < n; i++) {
        CF d = at(displays, i);
        if (is(d, T_CFDictionaryGetTypeID) && dict_int(d, &K_dispType) == STREAM_ALT_SCREEN) {
            CF u = get(d, &K_uuid);
            themed = u && equal(u, &K_clusterId);
        }
    }
    for (CFIndex i = 0; i < n; i++) {
        CF d = at(displays, i);
        if (!is(d, T_CFDictionaryGetTypeID)) continue;
        if (!first) first = d;
        int is_alt = dict_int(d, &K_dispType) == STREAM_ALT_SCREEN;
        if (is_alt && !alt) alt = d;
        CF uuid = get(d, &K_uuid);
        if (!is(uuid, T_CFStringGetTypeID)) continue;
        CF p = new_dict();
        if (!p) continue;
        if (themed) set(p, &K_uid, uuid);
        else put(p, &K_uid, panel_uid(uuid));
        copy_sizes(p, d);
        /* the cluster display's panel shows instruments (LIVI's panel(d, .., is_alt(d), ..)) */
        CF own = new_array();
        if (own && is_alt) append(own, &K_showsInstruments);
        put(p, &K_properties, own);
        CF streams = new_array();
        CF stream = new_dict();
        if (streams && stream) {
            set(stream, &K_uuid, uuid);
            set_int(stream, &K_originXPixels, 0);
            set_int(stream, &K_originYPixels, 0);
            set_int(stream, &K_zIndex, 0);
            append(streams, stream);
        }
        if (stream) release(stream);
        put(p, &K_initialVideoStreams, streams);
        if (themed && is_alt) {
            CF plugins = new_array();
            CF plugin = new_dict();
            CF composed = new_array();
            if (plugins && plugin && composed) {
                set(plugin, &K_uid, uuid);
                append(composed, uuid);
                set(plugin, &K_videoStreams, composed);
                append(plugins, plugin);
            }
            if (composed) release(composed);
            if (plugin) release(plugin);
            put(p, &K_displayPlugins, plugins);
        } else if (cluster == 4 && d == first) {
            /* experiment: the plugin on the main panel, composing its stream (LIVI's form) */
            CF plugins = new_array();
            CF plugin = new_dict();
            CF composed = new_array();
            if (plugins && plugin && composed) {
                put(plugin, &K_uid, panel_uid(uuid));
                append(composed, uuid);
                set(plugin, &K_videoStreams, composed);
                append(plugins, plugin);
            }
            if (composed) release(composed);
            if (plugin) release(plugin);
            put(p, &K_displayPlugins, plugins);
        }
        append(panels, p);
        release(p);
    }
    if (first && CPC_CLUSTER && cluster && cluster != 4 && !themed) {
        CF p = new_dict();
        if (p) {
            set(p, &K_uid, &K_clusterPanel);
            copy_sizes(p, alt ? alt : first);   /* LIVI: the cluster display's size, if there is one */
            /* cluster 2: without showsInstruments; 3: without the display plugin (experiments) */
            CF props = new_array();
            if (props && cluster != 2) append(props, &K_showsInstruments);
            put(p, &K_properties, props);
            put(p, &K_initialVideoStreams, new_array());
            if (cluster != 3) {
                CF plugins = new_array();
                CF plugin = new_dict();
                if (plugins && plugin) {
                    set(plugin, &K_uid, &K_clusterPlugin);
                    CF composed = new_array();
                    /* 5: the plugin composes the main stream (an experiment) */
                    if (composed && cluster == 5) {
                        CF uuid = get(first, &K_uuid);
                        if (is(uuid, T_CFStringGetTypeID)) append(composed, uuid);
                    }
                    put(plugin, &K_videoStreams, composed);
                    append(plugins, plugin);
                }
                if (plugin) release(plugin);
                put(p, &K_displayPlugins, plugins);
            }
            append(panels, p);
            release(p);
        }
    }
    n = count(panels);
    put(into, &K_displayPanels, panels);
    return n;
}

/* false, as a one-object binary plist: the table has no kCFBooleanFalse to point at */
static const unsigned char bplist_false[42] = {
    'b', 'p', 'l', 'i', 's', 't', '0', '0', 0x08, 0x08,
    0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9 };

/* true, the same way */
static const unsigned char bplist_true[42] = {
    'b', 'p', 'l', 'i', 's', 't', '0', '0', 0x09, 0x08,
    0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9 };

static CF boolean(const unsigned char *plist) {
    int err = 0;
    return FN(T_CFBinaryPlistV0CreateWithData, CF (*)(const void *, unsigned long, int *))(plist, 42, &err);
}

static void copy_entry(CF key, CF value, void *into) { set((CF)into, key, value); }

#define STATUS_BAR_BOTTOM 1   /* LIVI DockPosition::Bottom */

/* d's physical size key scaled by 100/pct */
static void scale_physical(CF to, CF d, CF key, int pct) {
    int err = 0;
    long long mm = FN(T_CFDictionaryGetInt64, long long (*)(CF, CF, int *))(d, key, &err);
    if (err || mm <= 0 || mm > 10000) return;
    /* (mm * 100 + pct / 2) / pct, rounded, without a divide (none on this core, no libgcc here) */
    unsigned rest = (unsigned)mm * 100 + (unsigned)pct / 2, q = 0;
    while (rest >= (unsigned)pct) { rest -= (unsigned)pct; q++; }
    set_int(to, key, q);
}

/* info's displays again, the first with LIVI's viewArea (the whole screen, the dock at the
 * bottom) and, for a CarPlay size pct of 50-99, a physical size larger by 100/pct; the
 * dictionaries are copied, as TCC's may not be mutable. 1 if done */
#define DISPLAY_FEATURE_KNOBS 0x02   /* LIVI info.rs */
#define APPEARANCE_DARK 1
#define APPEARANCE_AUTOMATIC 0

/* one view area: x..x+w of a w×h stream, the dock at edge, a safe area over all of it (drawing
 * outside allowed), transitions as told */
static CF make_area(long long x, long long w, long long h, int edge, CF transition, CF no, CF yes) {
    CF area = new_dict();
    if (!area) return 0;
    set_int(area, &K_widthPixels, w);
    set_int(area, &K_heightPixels, h);
    set_int(area, &K_originXPixels, x);
    set_int(area, &K_originYPixels, 0);
    set_int(area, &K_statusBarEdge, edge);
    if (transition) set(area, &K_transitionControl, transition);
    if (no) set(area, &K_focusTransfer, no);
    CF safe = new_dict();
    if (safe) {
        set_int(safe, &K_widthPixels, w);
        set_int(safe, &K_heightPixels, h);
        set_int(safe, &K_originXPixels, x);
        set_int(safe, &K_originYPixels, 0);
        if (yes) set(safe, &K_drawOutside, yes);
        put(area, &K_safeArea, safe);
    }
    return area;
}

static int add_view_area(CF info, int pct, int edge, int extras, int wide) {
    CF displays = get(info, &K_displays);
    if (!is(displays, T_CFArrayGetTypeID) || count(displays) < 1) return 0;
    CF first = at(displays, 0);
    if (!is(first, T_CFDictionaryGetTypeID) || get(first, &K_viewAreas)) return 0;
    CF out = new_array();
    if (!out) return 0;
    CFIndex n = count(displays);
    for (CFIndex i = 0; i < n; i++) {
        CF d = at(displays, i);
        if (i > 0 || !is(d, T_CFDictionaryGetTypeID)) {
            append(out, d);
            continue;
        }
        CF copy = new_dict();
        CF areas = new_array();
        CF area = new_dict();
        if (!copy || !areas || !area) {
            if (copy) release(copy);
            if (areas) release(areas);
            if (area) release(area);
            release(out);
            return 0;
        }
        FN(T_CFDictionaryApplyFunction, void (*)(CF, void (*)(CF, CF, void *), void *))(d, copy_entry, (void *)copy);
        if (pct >= 50 && pct < 100) {
            scale_physical(copy, d, &K_widthPhysical, pct);
            scale_physical(copy, d, &K_heightPhysical, pct);
        }
        if (extras) {
            /* the display fields LIVI sends and the stock reply lacks: knobs, and appearance */
            int err = 0;
            long long f = FN(T_CFDictionaryGetInt64, long long (*)(CF, CF, int *))(d, &K_features_d, &err);
            set_int(copy, &K_features_d, (err ? 0 : f) | DISPLAY_FEATURE_KNOBS);
            set_int(copy, &K_uiAppMode, APPEARANCE_DARK);
            set_int(copy, &K_uiAppSetting, APPEARANCE_AUTOMATIC);
            set_int(copy, &K_mapAppMode, APPEARANCE_DARK);
            set_int(copy, &K_mapAppSetting, APPEARANCE_AUTOMATIC);
        }
        release(area);   /* built by make_area below */
        CF no = boolean(bplist_false), yes = boolean(bplist_true);
        if (no && !get(copy, &K_cornerMasks)) set(copy, &K_cornerMasks, no);
        long long w = dict_int(d, &K_widthPixels), h = dict_int(d, &K_heightPixels);
        /* Apple's widescreen profile (Xcode's CarPlay Simulator, as DiPlay reports it): the whole
         * 1920x720 and its right 1280x720, with transitions; otherwise LIVI's single area */
        /* w / 3 without a divide (none on this core, and clang turns a loop back into one) */
        long long third = w > 0 && w < 4096 ? (long long)(((unsigned)w * 21846u) >> 16) : 0;
        int two = wide && third > 0;
        CF full = make_area(0, w, h, edge, two ? yes : no, no, yes);
        if (full) { append(areas, full); release(full); }
        if (two) {
            CF right = make_area(third, w - third, h, edge, yes, no, yes);
            if (right) { append(areas, right); release(right); }
        }
        if (no) release(no);
        if (yes) release(yes);
        put(copy, &K_viewAreas, areas);
        set_int(copy, &K_initialViewArea, two && wide == 2 ? 1 : 0);
        put(copy, &K_adjacentViewAreas, new_array());
        append(out, copy);
        release(copy);
    }
    put(info, &K_displays, out);
    return 1;
}

/* the Ultra experiment: info's displays plus a virtual cluster (LIVI's cluster display: 800x480 at
 * its fallback 200 dpi, knobs, dark), whose video the Java side takes and throws away. 1 if added */
#define ALT_W 800
#define ALT_H 480
/* This display is discarded by ClusterSink. Avoid asking the phone to render it at 30 fps. */
#define ALT_FPS 5
#define PRIMARY_INPUT_KNOBS 3
#define DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH 0x08

/* info's hidDevices again, those on the display old moved to the display now */
#include "livi_hid.h"

#ifndef DIR
#define DIR "/data/data/com.daudio.app.dipo/files/"   /* DiPO's files folder (also below, with the file helpers) */
#endif

static int read_number(const char *path);

/* the LIVI input devices experiment (files/cpc-hid): 1 adds LIVI's knob, media and telephony devices on
 * Center_Display and a knob on Cluster_Display to /info's hidDevices, and makes knobs the main display's
 * primary input (LIVI info.rs); only with the theme display ids */
static int livi_hid_mode(void) {
    return read_number(DIR "cpc-hid") == 1;
}

static void add_livi_hid(CF info) {
    int err = 0;
    CF more = FN(T_CFBinaryPlistV0CreateWithData, CF (*)(const void *, unsigned long, int *))(livi_hid, sizeof livi_hid, &err);
    if (!is(more, T_CFArrayGetTypeID)) {
        if (more) release(more);
        LOG("### cp-climate: LIVI input devices didn't parse (%d)\n", err);
        return;
    }
    CF hid = get(info, &K_hidDevices), out = new_array();
    if (!out) {
        release(more);
        return;
    }
    CFIndex n = is(hid, T_CFArrayGetTypeID) ? count(hid) : 0, m = count(more);
    for (CFIndex i = 0; i < n; i++) append(out, at(hid, i));
    for (CFIndex i = 0; i < m; i++) append(out, at(more, i));
    put(info, &K_hidDevices, out);
    release(more);
    LOG("cp-climate: /info hidDevices: %d stock + %d LIVI input devices (knob, media, telephony, cluster knob) (experiment)\n", (int)n, (int)m);
}

static void move_hid(CF info, CF old, CF now) {
    CF hid = get(info, &K_hidDevices);
    if (!old || !is(hid, T_CFArrayGetTypeID)) return;
    CF out = new_array();
    if (!out) return;
    CFIndex n = count(hid), moved = 0;
    for (CFIndex i = 0; i < n; i++) {
        CF h = at(hid, i);
        CF on = is(h, T_CFDictionaryGetTypeID) ? get(h, &K_displayUUID) : 0;
        CF copy = on && equal(on, old) ? new_dict() : 0;
        if (copy) {
            FN(T_CFDictionaryApplyFunction, void (*)(CF, void (*)(CF, CF, void *), void *))(h, copy_entry, (void *)copy);
            set(copy, &K_displayUUID, now);
            append(out, copy);
            release(copy);
            moved++;
        } else {
            append(out, h);
        }
    }
    put(info, &K_hidDevices, out);
    LOG("cp-climate: Ultra theme: %d hidDevices moved to Center_Display\n", (int)moved);
}

static int add_alt_display(CF info, int themed) {
    CF displays = get(info, &K_displays);
    if (!is(displays, T_CFArrayGetTypeID) || count(displays) < 1) return 0;
    CF out = new_array();
    if (!out) return 0;
    CFIndex n = count(displays);
    for (CFIndex i = 0; i < n; i++) {
        CF d = at(displays, i);
        if (is(d, T_CFDictionaryGetTypeID) && dict_int(d, &K_dispType) == STREAM_ALT_SCREEN) {
            release(out);
            return 0;
        }
        /* the main display gets LIVI's type 110 (TCC's has none), so the two can be told apart;
         * themed, also the theme's id for it, and the touch screen and buttons follow it */
        CF main = i == 0 && is(d, T_CFDictionaryGetTypeID) && (themed || !get(d, &K_dispType)) ? new_dict() : 0;
        if (main) {
            FN(T_CFDictionaryApplyFunction, void (*)(CF, void (*)(CF, CF, void *), void *))(d, copy_entry, (void *)main);
            set_int(main, &K_dispType, STREAM_MAIN_SCREEN);
            if (themed) {
                move_hid(info, get(d, &K_uuid), &K_centerId);
                set(main, &K_uuid, &K_centerId);
                if (livi_hid_mode()) set_int(main, &K_primaryInput, PRIMARY_INPUT_KNOBS);
            }
            append(out, main);
            release(main);
        } else {
            append(out, d);
        }
    }
    CF alt = new_dict();
    if (!alt) {
        release(out);
        return 0;
    }
    set(alt, &K_uuid, themed ? (CF)&K_clusterId : (CF)&K_altUuid);
    set_int(alt, &K_dispType, STREAM_ALT_SCREEN);
    set_int(alt, &K_maxFPS, ALT_FPS);
    /* themed: the theme's cluster size (1920x720), as large as the main screen */
    set_int(alt, &K_widthPixels, themed ? 1920 : ALT_W);
    set_int(alt, &K_heightPixels, themed ? 720 : ALT_H);
    set_int(alt, &K_widthPhysical, themed ? 292 : 102);   /* LIVI mm_at_dpi(800, 200) */
    set_int(alt, &K_heightPhysical, themed ? 110 : 61);
    set_int(alt, &K_features_d, DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH | DISPLAY_FEATURE_KNOBS);
    set_int(alt, &K_primaryInput, PRIMARY_INPUT_KNOBS);
    CF no = boolean(bplist_false);
    if (no) { set(alt, &K_cornerMasks, no); release(no); }
    put(alt, &K_adjacentViewAreas, new_array());
    set_int(alt, &K_uiAppMode, APPEARANCE_DARK);
    set_int(alt, &K_uiAppSetting, APPEARANCE_AUTOMATIC);
    set_int(alt, &K_mapAppMode, APPEARANCE_DARK);
    set_int(alt, &K_mapAppSetting, APPEARANCE_AUTOMATIC);
    append(out, alt);
    release(alt);
    put(info, &K_displays, out);
    return 1;
}

/* M1's additions for the /info dictionary info, put in into: the number of displayPanels
 * (-1: none), or -2 if vehicleStateProtocolInfo didn't parse */
static CFIndex add_additions(CF info, CF into, int cluster) {
    int err = 0;
    CF vspi = FN(T_CFBinaryPlistV0CreateWithData, CF (*)(const void *, unsigned long, int *))(
        cpc_vspi, VSPI_LEN, &err);
    if (!vspi) {
        LOG("### cp-climate: vehicleStateProtocolInfo didn't parse: %d\n", err);
        return -2;
    }
    put(into, &K_vspi, vspi);
    if (!CPC_PANELS) return -1;
    return add_display_panels(info, into, cluster);
}

/* files in DiPO's own files folder (no libc here: plain system calls) */
#ifndef DIR
#define DIR "/data/data/com.daudio.app.dipo/files/"
#endif
#ifdef CPC_HOST_TEST
#include <fcntl.h>
#include <unistd.h>
static long sys_open(const char *p, int mode) { return open(p, O_WRONLY | O_CREAT | O_TRUNC, mode); }
static long sys_open_read(const char *p) { return open(p, O_RDONLY); }
static long sys_read(long fd, void *b, unsigned long n) { return read((int)fd, b, n); }
static long sys_write(long fd, const void *b, unsigned long n) { return write((int)fd, b, n); }
static void sys_close(long fd) { close((int)fd); }
#else
long cpc_sys3(long nr, long a, long b, long c) __attribute__((visibility("hidden")));   /* seg.S */
static long sys_open(const char *p, int mode) { return cpc_sys3(5, (long)p, 01 | 0100 | 01000, mode); }   /* O_WRONLY|O_CREAT|O_TRUNC */
static long sys_write(long fd, const void *b, unsigned long n) { return cpc_sys3(4, fd, (long)b, (long)n); }
static void sys_close(long fd) { cpc_sys3(6, fd, 0, 0); }
static long sys_open_read(const char *p) { return cpc_sys3(5, (long)p, 0, 0); }   /* O_RDONLY */
static long sys_read(long fd, void *b, unsigned long n) { return cpc_sys3(3, fd, (long)b, (long)n); }
#endif

#if CPC_DIAG
/* diagnostic variant d: /info goes out stock; what it would be is saved in DiPO's files */

/* DiPO's own encoder on o, saved to path; checks the bytes decode back to an equal object */
static void save(CF o, const char *path) {
    unsigned long len = 0;
    unsigned char *bytes = FN(T_CFBinaryPlistV0Create, unsigned char *(*)(CF, unsigned long *, int *))(o, &len, 0);
    if (!bytes) {
        LOG("### cp-climate: encoding for %s failed\n", path);
        return;
    }
    long fd = sys_open(path, 0644), wrote = -1;
    if (fd >= 0) {
        wrote = sys_write(fd, bytes, len);
        sys_close(fd);
    }
    int err = 0;
    CF back = FN(T_CFBinaryPlistV0CreateWithData, CF (*)(const void *, unsigned long, int *))(bytes, len, &err);
    int same = back && equal(o, back);
    if (back) release(back);
    FN(T_free, void (*)(void *))(bytes);
    LOG("cp-climate: saved %s: %d bytes (written %d, fd %d), decodes back %s (err %d)\n",
        path, (int)len, (int)wrote, (int)fd, same ? "EQUAL" : "DIFFERENT", err);
}

static void diag(CF info) {
    save(info, DIR "cpc-info-stock.bplist");
    CF adds = new_dict();
    if (adds) {
        add_additions(info, adds, 1);
        save(adds, DIR "cpc-info-additions.bplist");
        release(adds);
    }
    CF full = new_dict();
    if (full) {
        FN(T_CFDictionaryApplyFunction, void (*)(CF, void (*)(CF, CF, void *), void *))(info, copy_entry, (void *)full);
        add_additions(info, full, 1);
        save(full, DIR "cpc-info-full.bplist");
        release(full);
    }
    LOG("cp-climate: /info left stock (diagnostic variant)\n");
}
#endif

/* a number cp-climate's Java side wrote from its settings, or -1 */
static int read_number(const char *path) {
    char buf[8];
    long fd = sys_open_read(path);
    if (fd < 0) return -1;
    long n = sys_read(fd, buf, sizeof buf - 1);
    sys_close(fd);
    int v = 0, digits = 0;
    for (long i = 0; i < n && buf[i] >= '0' && buf[i] <= '9' && digits < 4; i++, digits++) v = v * 10 + (buf[i] - '0');
    return digits ? v : -1;
}

/* the CarPlay size in %; 100 if not set */
static int size_percent(void) {
    int pct = read_number(DIR "cpc-size");
    return pct >= 50 && pct <= 100 ? pct : 100;
}

/* the receiver version setting: 1 for LIVI's 950.7.1 in /info, else DiPO's own */
static int newer_version(void) {
    return read_number(DIR "cpc-version") == 1;
}

/* the instrument panel setting (files/cpc-nocluster): 1 leaves LIVI's instrument panel out of
 * displayPanels, 2 sends it without showsInstruments, 3 without its display plugin; else full */
static int cluster_mode(void) {
    int c = read_number(DIR "cpc-nocluster");
    return c == 1 ? 0 : c >= 2 && c <= 5 ? c : 1;
}

/* the Apple widescreen view areas setting: 1 declares the whole screen and its right two thirds,
 * starting in the whole; 2 the same, starting in the right two thirds */
static int widescreen_areas(void) {
    int w = read_number(DIR "cpc-wide");
    return w == 1 || w == 2 ? w : 0;
}

/* the CarPlay Ultra experiment (files/cpc-ultra): 1 adds the virtual cluster, altScreen and viewAreas;
 * 2 the same with the theme's display ids (Center_Display, Cluster_Display); else 0 */
static int ultra_mode(void) {
    int u = read_number(DIR "cpc-ultra");
    return u == 1 || u == 2 ? u : 0;
}

/* the iAPChannel experiment (files/cpc-iap): 1 accepts iAP over the CarPlay session */
static int iap_mode(void) {
    return read_number(DIR "cpc-iap") == 1;
}

/* the uiSync experiment (files/cpc-uisync): 1 negotiates the theme's layout/palette channel (LIVI ui_sync.rs) */
static int ui_sync_mode(void) {
    return read_number(DIR "cpc-uisync") == 1;
}

/* the extra features experiment (files/cpc-xenable): bit 1 videoPlayback, bit 2 hevc, when the phone offers them */
static int extra_features(void) {
    int x = read_number(DIR "cpc-xenable");
    return x >= 1 && x <= 3 ? x : 0;
}

/* the LIVI display extras setting: 1 adds the knobs feature and appearance keys */
static int display_extras(void) {
    return read_number(DIR "cpc-extras") == 1;
}

/* the dock edge (LIVI DockPosition: 0 auto, 1 bottom, 2 side); bottom if not set */
static int dock_edge(void) {
    int e = read_number(DIR "cpc-dock");
    return e >= 0 && e <= 4 ? e : STATUS_BAR_BOTTOM;   /* 3, 4: undocumented, an experiment */
}

/* what the EV Routing mod's CarPlayStartSession (iAP2 0x4301) needs from /info, for iap2server (also
 * uid system) to read: files/cpc-airplay, lines pi=, deviceID=, sourceVersion= (as the phone hears them) */
CFS(K_pi, "pi");
CFS(K_deviceID, "deviceID");
static int put_line(char *buf, int at, int cap, const char *key, CF info, CF k) {
    CF v = get(info, k);
    const char *c = is(v, T_CFStringGetTypeID) ? FN(T_CFStringGetCStringPtr, const char *(*)(CF, unsigned))(v, UTF8) : 0;
    if (!c) return at;
    for (int i = 0; key[i] && at < cap; i++) buf[at++] = key[i];
    for (int i = 0; c[i] && i < 96 && at < cap; i++) buf[at++] = c[i] >= 0x20 && c[i] < 0x7f ? c[i] : '?';
    if (at < cap) buf[at++] = '\n';
    return at;
}
static void save_airplay(CF info) {
    char buf[384];
    int n = put_line(buf, 0, sizeof buf, "pi=", info, &K_pi);
    n = put_line(buf, n, sizeof buf, "deviceID=", info, &K_deviceID);
    n = put_line(buf, n, sizeof buf, "sourceVersion=", info, &K_sourceVersion);
    long fd = sys_open(DIR "cpc-airplay", 0600);
    if (fd < 0) return;
    sys_write(fd, buf, (unsigned long)n);
    sys_close(fd);
}

/* the /info feature bits experiment (files/cpc-feat): bits to add to /info's "features", in hex; 0 if not set */
static unsigned long long feature_bits(void) {
    char buf[20];
    long fd = sys_open_read(DIR "cpc-feat");
    if (fd < 0) return 0;
    long n = sys_read(fd, buf, sizeof buf - 1);
    sys_close(fd);
    unsigned long long v = 0;
    for (long i = 0, digits = 0; i < n && digits < 16; i++, digits++) {
        char c = buf[i];
        int d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
        if (d < 0) break;
        v = v << 4 | (unsigned)d;   /* a constant shift: no libgcc helper */
    }
    return v;
}

/* the enhancedRequestCarUI experiment (files/cpc-xfeat): 1 adds it to /info's extendedFeatures */
static int enhanced_car_ui(void) {
    return read_number(DIR "cpc-xfeat") == 1;
}

/* the feature experiments on /info; logged when one changed it */
static void add_features(CF info) {
    unsigned long long bits = feature_bits();
    if (bits) {
        int err = 0;
        long long f = FN(T_CFDictionaryGetInt64, long long (*)(CF, CF, int *))(info, &K_features_d, &err);
        unsigned long long was = err ? 0 : (unsigned long long)f, now = was | bits;
        set_int(info, &K_features_d, (long long)now);
        LOG("cp-climate: /info features 0x%x%08x -> 0x%x%08x (experiment)\n", (unsigned)(was >> 32), (unsigned)was,
            (unsigned)(now >> 32), (unsigned)now);
    }
    if (enhanced_car_ui()) {
        CF old = get(info, &K_extendedFeatures), ext = new_array();
        if (!ext) return;
        int have = 0;
        if (is(old, T_CFArrayGetTypeID))
            for (CFIndex i = 0, n = count(old); i < n; i++) {
                append(ext, at(old, i));
                have |= equal(at(old, i), &K_enhancedCarUI);
            }
        if (!have) append(ext, &K_enhancedCarUI);
        put(info, &K_extendedFeatures, ext);
        LOG("cp-climate: /info extendedFeatures has enhancedRequestCarUI (experiment)\n");
    }
    /* LIVI stack/control.rs: an empty uiSyncInfo when uiSync is on */
    if (ui_sync_mode()) {
        CF d = new_dict();
        if (!d) return;
        put(info, &K_uiSyncInfo, d);
        LOG("cp-climate: /info has uiSyncInfo (experiment)\n");
    }
}

/* LIVI video_playback.rs features_ex: the feature bits, little-endian without trailing zero bytes, base64
 * without padding */
static int features_ex(unsigned long long f, char *out) {
    static const char b64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    unsigned char b[8];
    unsigned lo = (unsigned)f, hi = (unsigned)(f >> 32);
    int n = 0;
    for (int i = 0; i < 4; i++) { b[i] = (unsigned char)(lo >> (8 * i)); b[4 + i] = (unsigned char)(hi >> (8 * i)); }
    for (int i = 0; i < 8; i++) if (b[i]) n = i + 1;
    int o = 0;
    for (int i = 0; i < n; i += 3) {
        unsigned v = (unsigned)b[i] << 16 | (i + 1 < n ? (unsigned)b[i + 1] << 8 : 0) | (i + 2 < n ? b[i + 2] : 0);
        out[o++] = b64[(v >> 18) & 63];
        out[o++] = b64[(v >> 12) & 63];
        if (i + 1 < n) out[o++] = b64[(v >> 6) & 63];
        if (i + 2 < n) out[o++] = b64[v & 63];
    }
    out[o] = 0;
    return o;
}

/* the videoPlayback experiment: /info says what LIVI says when it enables videoPlayback */
static void add_video_playback(CF info) {
    if (!(extra_features() & 1)) return;
    int err = 0;
    long long f = FN(T_CFDictionaryGetInt64, long long (*)(CF, CF, int *))(info, &K_features_d, &err);
    char ex[16];
    features_ex(err ? 0 : (unsigned long long)f, ex);
    CF vp = new_dict(), caps = new_dict(), yes = boolean(bplist_true), no = boolean(bplist_false);
    CF exs = FN(T_CFStringCreateWithCString, CF (*)(CF, const char *, unsigned))(0, ex, UTF8);
    if (vp && caps && yes && no && exs) {
        set(vp, &K_vpAllowed, yes);
        set(vp, &K_vpFeaturesEx, exs);
        set(caps, &K_fpsSecureStop, no);
        set(caps, &K_audioOnlyUI, no);
        set(info, &K_vpInfo, vp);
        set(info, &K_playbackCaps, caps);
        LOG("cp-climate: /info has videoPlaybackInfo (featuresEx %s) and playbackCapabilities (experiment)\n", ex);
    }
    if (vp) release(vp);
    if (caps) release(caps);
    if (yes) release(yes);
    if (no) release(no);
    if (exs) release(exs);
}

__attribute__((visibility("hidden"))) void cpc_info_hook(CF info) {
#if CPC_DIAG
    diag(info);
    return;
#endif
    if (!CPC_INFO) {
        LOG("cp-climate: /info left stock (test variant)\n");
        return;
    }
    if (get(info, &K_vspi)) return;
    int pct = size_percent(), edge = dock_edge(), newer = newer_version(), extras = display_extras();
    int cluster = cluster_mode(), wide = widescreen_areas();
    if (newer) set(info, &K_sourceVersion, &K_liviVersion);
    /* the displays first, so displayPanels copy the sizes the phone is told */
    int area = CPC_VIEWAREA ? add_view_area(info, pct, edge, extras, wide) : 0;
    int mode = ultra_mode();
    int ultra = mode && add_alt_display(info, mode == 2) ? mode : 0;
    if (ultra == 2 && livi_hid_mode()) add_livi_hid(info);
    CFIndex panels = add_additions(info, info, cluster);
    if (panels == -2) return;
    add_features(info);
    add_video_playback(info);
    save_airplay(info);
    LOG("cp-climate: /info has vehicleStateProtocolInfo (%d bytes), %d displayPanels, %s, dock edge %d, CarPlay size %d%%, sourceVersion %s, LIVI display extras %s, instrument panel mode %d (0 off, 1 full, 2 no showsInstruments, 3 no plugin, 4 plugin on main, 5 plugin with stream), view areas %d (start %s), Ultra virtual cluster %s\n",
        (int)VSPI_LEN, (int)panels, area ? "a viewArea" : "no viewArea added", edge, pct, newer ? "950.7.1" : "stock", extras ? "on" : "off", cluster, wide ? 2 : 1, wide == 2 ? "right" : "whole", ultra == 2 ? "1920x720 (type 111, theme display ids)" : ultra ? "800x480 (type 111)" : "off");
}

__attribute__((visibility("hidden"))) void cpc_setup_hook(CF request, CF reply) {
    if (!request || !reply) return;
    CF offered = get(request, &K_features);
    int found = 0, alt = 0, areas = 0, sync = 0, iap = 0, video = 0, hevc = 0;
    if (is(offered, T_CFArrayGetTypeID)) {
        CFIndex n = count(offered);
        for (CFIndex i = 0; i < n; i++) {
            CF f = at(offered, i);
            found |= equal(f, &K_vsp);
            alt |= equal(f, &K_altScreen);
            areas |= equal(f, &K_viewAreas);
            sync |= equal(f, &K_uiSync);
            iap |= equal(f, &K_iapChannel);
            video |= equal(f, &K_videoPlayback);
            hevc |= equal(f, &K_hevc);
        }
    }
    int ultra = ultra_mode(), iapx = iap_mode(), syncx = ui_sync_mode();
    if (is(offered, T_CFArrayGetTypeID))
        LOG("cp-climate: SETUP: the phone offers vehicleStateProtocol %d, altScreen %d, viewAreas %d, uiSync %d, iAPChannel %d (Ultra experiment %s, iAPChannel experiment %s, uiSync experiment %s)\n",
            found, alt, areas, sync, iap, ultra ? "on" : "off", iapx ? "on" : "off", syncx ? "on" : "off");
    if (!found) {
        LOG("cp-climate: SETUP without vehicleStateProtocol from the phone: reply unchanged\n");
        return;
    }
    if (!CPC_FEATURES) {
        LOG("cp-climate: SETUP reply left stock (test variant)\n");
        return;
    }
    CF features = new_array();
    if (!features) return;
    append(features, &K_vsp);
    /* LIVI stack/setup.rs: viewAreas always, altScreen with a cluster display */
    if (ultra) append(features, &K_viewAreas);
    if (ultra && alt) append(features, &K_altScreen);
    if (iapx && iap) append(features, &K_iapChannel);
    if (syncx && sync) append(features, &K_uiSync);
    int extra = extra_features();
    if ((extra & 1) && video) append(features, &K_videoPlayback);
    if ((extra & 2) && hevc) append(features, &K_hevc);
    if (extra)
        LOG("cp-climate: SETUP extra features experiment: videoPlayback %s, hevc %s\n",
            (extra & 1) ? (video ? "enabled" : "not offered") : "off", (extra & 2) ? (hevc ? "enabled" : "not offered") : "off");
    put(reply, &K_enabledFeatures, features);
    LOG("cp-climate: SETUP reply has enabledFeatures [vehicleStateProtocol%s%s%s%s%s%s]\n",
        ultra ? ", viewAreas" : "", ultra && alt ? ", altScreen" : "", iapx && iap ? ", iAPChannel" : "",
        syncx && sync ? ", uiSync" : "", (extra & 1) && video ? ", videoPlayback" : "", (extra & 2) && hevc ? ", hevc" : "");
}

/* ---- M2: the vehicle state data streams (SETUP type 130) ---- */

CFS(K_type, "type");
CFS(K_seed, "seed");
CFS(K_clientTypeUUID, "clientTypeUUID");
CFS(K_streamID, "streamID");
CFS(K_dataPort, "dataPort");
CFS(K_vsNormal, "3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E");   /* vehicle state            */
CFS(K_vsHigh, "FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634");     /* vehicle state, high lane */
CFS(K_iapStream, "E9459FD0-BCAD-4C45-820F-1E72447EF2F2");  /* iAP over CarPlay (LIVI stack.rs), "lane" 2 */
CFS(K_syncStream, "07D9F906-8D64-4B54-A808-A20BCA2C51C2"); /* uiSync (LIVI ui_sync.rs), "lane" 3 */
/* the videoPlayback experiment's data streams (LIVI video_playback.rs): accepted and thrown away */
CFS(K_vpData, "A6B27562-B43A-4F2D-B75F-82391E250194");
CFS(K_vpSettings, "BB493F61-A6B8-4769-8D74-80C23A9F71C4");
CFS(K_vpOverlay, "E3DC3EA6-E6C3-4B30-847C-B7ACFEBEA654");

#define STREAM_DATA 130
#define CPC_PORT 7130   /* + lane: the mod's Java side listens there (mod.json opens them on the hotspot) */
#define CPC_IAP_PORT 7133   /* lane 2, the iAP stream (7132 is the Ultra cluster's) */
#define CPC_VP_PORT 7134    /* the videoPlayback experiment's streams: the Java side reads them and throws them away */
#define CPC_SYNC_PORT 7135  /* lane 3, the uiSync stream */

/* the lane's keys for the Java side, before the phone can connect: the seed (8 bytes, little-endian),
 * the key to read the phone with (DataStream-Output-Encryption-Key), the key to write with (-Input-) */
static int save_keys(int lane, unsigned long long seed, const unsigned char *rd, const unsigned char *wr) {
    unsigned char buf[8 + 32 + 32];
    unsigned lo = (unsigned)seed, hi = (unsigned)(seed >> 32);   /* 32-bit shifts: no libgcc helpers here */
    for (int i = 0; i < 4; i++) { buf[i] = (unsigned char)(lo >> (8 * i)); buf[4 + i] = (unsigned char)(hi >> (8 * i)); }
    for (int i = 0; i < 32; i++) { buf[8 + i] = rd[i]; buf[40 + i] = wr[i]; }
    long fd = sys_open(lane == 3 ? DIR "cpc-vs-3.key" : lane == 2 ? DIR "cpc-vs-2.key" : lane ? DIR "cpc-vs-1.key" : DIR "cpc-vs-0.key", 0600);
    if (fd < 0) return (int)fd;
    long n = sys_write(fd, buf, sizeof buf);
    sys_close(fd);
    for (unsigned i = 0; i < sizeof buf; i++) ((volatile unsigned char *)buf)[i] = 0;
    return n == (long)sizeof buf ? 0 : -1;
}

/* a stream SETUP DiPO doesn't handle: 1 if it's one of ours (set up, in the reply), 0 for DiPO's own path */
#define CPC_ALT_PORT 7132   /* the Ultra experiment's cluster video: the Java side reads it and throws it away */

/* the cluster's screen stream (type 111): set up on CPC_ALT_PORT. 1 if set up */
static int alt_stream(CF reply) {
    CF d = new_dict();
    if (!d) return 0;
    set_int(d, &K_type, STREAM_ALT_SCREEN);
    set_int(d, &K_dataPort, CPC_ALT_PORT);
    int st = FN(T_ADD_STREAM_REPLY, int (*)(CF, CF))(reply, d);
    release(d);
    LOG("cp-climate: Ultra experiment: cluster screen stream set up on port %d (reply %d)\n", CPC_ALT_PORT, st);
    return st == 0;
}

__attribute__((visibility("hidden"))) int cpc_stream_hook(void *session, CF stream, CF reply, long type) {
    if (!stream || !reply) return 0;
    if (type == STREAM_ALT_SCREEN) return ultra_mode() ? alt_stream(reply) : 0;
    if (type != STREAM_DATA) return 0;
    CF kind = get(stream, &K_clientTypeUUID);
    if (kind && (extra_features() & 1) && (equal(kind, &K_vpData) || equal(kind, &K_vpSettings) || equal(kind, &K_vpOverlay))) {
        int which = equal(kind, &K_vpData) ? 0 : equal(kind, &K_vpSettings) ? 1 : 2;
        CF d = new_dict();
        if (!d) return 0;
        set_int(d, &K_type, STREAM_DATA);
        set_int(d, &K_streamID, STREAM_DATA + 10 + which);
        set_int(d, &K_dataPort, CPC_VP_PORT);
        int st = FN(T_ADD_STREAM_REPLY, int (*)(CF, CF))(reply, d);
        release(d);
        LOG("cp-climate: videoPlayback %s stream set up on port %d (reply %d)\n", which == 0 ? "data" : which == 1 ? "settings" : "overlay", CPC_VP_PORT, st);
        return st == 0;
    }
    int lane = !kind ? -1 : equal(kind, &K_vsNormal) ? 0 : equal(kind, &K_vsHigh) ? 1
        : equal(kind, &K_iapStream) && iap_mode() ? 2 : equal(kind, &K_syncStream) && ui_sync_mode() ? 3 : -1;
    if (lane < 0) return 0;
    int err = 0;
    unsigned long long seed = (unsigned long long)FN(T_CFDictionaryGetInt64, long long (*)(CF, CF, int *))(stream, &K_seed, &err);
    unsigned char rd[32], wr[32];
    int st = err ? err : FN(T_DERIVE_STREAM_KEYS, int (*)(void *, int, unsigned long long, unsigned, void *, void *))(
        session, 0, seed, 32, wr, rd);
    if (!st) st = save_keys(lane, seed, rd, wr);
    for (int i = 0; i < 32; i++) { ((volatile unsigned char *)rd)[i] = 0; ((volatile unsigned char *)wr)[i] = 0; }
    if (st) {
        LOG("### cp-climate: data stream %d not set up: %d\n", lane, st);
        return 0;
    }
    CF d = new_dict();
    if (!d) return 0;
    set_int(d, &K_type, STREAM_DATA);
    set_int(d, &K_streamID, STREAM_DATA + lane);
    int port = lane == 3 ? CPC_SYNC_PORT : lane == 2 ? CPC_IAP_PORT : CPC_PORT + lane;
    set_int(d, &K_dataPort, port);
    st = FN(T_ADD_STREAM_REPLY, int (*)(CF, CF))(reply, d);
    release(d);
    LOG("cp-climate: %s stream %d set up on port %d (reply %d)\n", lane == 3 ? "uiSync" : lane == 2 ? "iAP" : "vehicle state", lane, port, st);
    return st == 0;
}
