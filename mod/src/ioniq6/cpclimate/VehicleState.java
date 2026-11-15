package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The car's side of CarPlay's vehicle state protocol, as M3: answers the phone's configRequest,
 * registerRequest, readRequest, writeRequest and controlRequest from LIVI's description (VsData)
 * and keeps what the phone writes, with LIVI's rules for how one value moves another (auto
 * climate, max A/C, synced zones, levels and their on switches, mute). Nothing reaches the car
 * yet: a write only changes what the phone is told. A port of LIVI vehicle_state.rs handle()
 * and rules.rs; tests/VehicleStateTest.java checks it answers byte for byte as LIVI does.
 * Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
 */
final class VehicleState {
    static final int NORMAL = 0, HIGH = 1;     // lanes, numbered as VsChannel numbers them

    static final long PLUGIN_ID = 0;
    static final long NO_VALUE = -6727, WRITTEN = 0, NO_SUCH_COMMAND = -6735;

    static final long HVAC_ON = 514, COMPRESSOR = 515, MAX_AC = 516, FAN_AUTO = 526, RECIRCULATION = 528;
    static final long AUTO_CLIMATE = 530, DRIVER_TARGET = 520, PASSENGER_TARGET = 534, ZONES_SYNCED = 538;
    static final long[] VENTS = { 548, 558 };   // upper and lower: the Ioniq 6 has no body-only vent
    static final long FAN_LEVEL = 525, FAN_ON = 524;
    /** level, then its on switch: the two seat heaters and the fan */
    static final long[][] LEVELLED = { { 1026, 1027 }, { 1030, 1031 }, { FAN_LEVEL, FAN_ON } };
    static final double TEMPERATURE_LO = 16.0;
    static final long VOLUME = 2306, MUTE = 2309;
    static final long HISTORY = 3584, TEMPORARY_ZONE = 3085, VERSION_LINE = 1281;
    static final int HISTORY_SLOTS = 10;

    /** one message for the phone, and the lane it goes on */
    static final class Out {
        final int lane;
        final byte[] message;

        Out(int lane, byte[] message) {
            this.lane = lane;
            this.message = message;
        }
    }

    /** the real car, when one is attached (CarClimate): it gets what the phone writes */
    interface Car {
        void phoneWrote(Map<Long, Object> written, Map<Long, Object> now);
    }

    /** how long a value the phone wrote wins over the car's old one, while the car catches up */
    static final long HOLD_MS = 2500;

    final VsChannel.Log out;
    volatile Car car;
    /** when the session that made this state started (the second lane joins within VsChannel.SESSION_PAIRING_MS) */
    volatile long startedAt;
    private final Map<Long, Long> heldSince = new HashMap<Long, Long>();
    final TreeMap<Long, Object> values = new TreeMap<Long, Object>();
    final TreeSet<Long> followed = new TreeSet<Long>();
    private final Map<Long, Object> resume = new HashMap<Long, Object>();
    private List<Object[]> beforeMaxAc;
    private final Set<Long> writable = new HashSet<Long>(), shared = new HashSet<Long>();
    private final Map<Long, Long> notifiers = new HashMap<Long, Long>();
    private final Map<Long, String> names = new HashMap<Long, String>();
    private final Map<Long, String> controls = new HashMap<Long, String>();
    private final OpackCodec.Raw accessories;

    /** the vehicle variant the phone resolves a CarPlay theme's assets by (setting vehicle_variant), "" for LIVI's stock description */
    static volatile String variant = "";
    static final long VARIANT_SERVICE = 769, INTERIOR_STYLE = 770, EXTERIOR_STYLE = 771, VEHICLE_LAYOUT = 772;

    /**
     * The car's notifications, newest first, at most HISTORY_SLOTS: what CarPlay's Vehicle app lists as its
     * notification history (accessory 3584, one service per slot: label, description, time in epoch seconds,
     * symbol, severity 0-2, dismissible, hidden). Kept for the life of the process, so every CarPlay session
     * shows them; an entry the driver dismissed stays hidden, as in LIVI.
     */
    static final class Notice {
        final String label, description, symbol;
        final long severity, atSeconds;
        volatile boolean hidden;

        Notice(String label, String description, String symbol, long severity, long atSeconds) {
            this.label = label;
            this.description = description;
            this.symbol = symbol;
            this.severity = Math.max(0, Math.min(2, severity));
            this.atSeconds = atSeconds;
        }
    }

    static final List<Notice> NOTICES = new ArrayList<Notice>();

    /** adds a notification as the newest; a session shows it with posted() */
    static void post(String label, String description, String symbol, long severity) {
        synchronized (NOTICES) {
            NOTICES.add(0, new Notice(label, description, symbol, severity, System.currentTimeMillis() / 1000));
            while (NOTICES.size() > HISTORY_SLOTS) NOTICES.remove(NOTICES.size() - 1);
        }
    }

    /** the history as it is now, for a phone that follows it (after post()); empty if it doesn't yet */
    synchronized List<Out> posted() {
        return followed.contains(historySlot(0) + 1) ? historyUpdate() : new ArrayList<Out>();
    }

    /** the phone's media controls (play, pause, changeMediaSource, tuneTo...), for whoever runs the car's radio */
    interface Controls {
        void pressed(String name, Object value);
    }

    static volatile Controls mediaControls;

    /** the car's page in CarPlay's Vehicle app (setting vehicle_page), null for LIVI's two version lines */
    static volatile VehiclePage page;
    private final VehiclePage paged;

    /**
     * LIVI vehicle_state.rs with_variant: the service holding interiorStyle gets the exterior style, interior style
     * and vehicle layout, each a fixed string naming the model (a theme's variant, e.g. DB12Coupe); null if the
     * accessory list has no such service.
     */
    @SuppressWarnings("unchecked")
    static byte[] withVariant(byte[] accessories, String model, Map<Long, Object> values) {
        Object list = OpackCodec.decode(accessories);
        if (!(list instanceof List)) return null;
        for (Object a : (List<Object>) list) {
            Object services = a instanceof Map ? ((Map<Object, Object>) a).get("services") : null;
            if (!(services instanceof List)) continue;
            for (Object s : (List<Object>) services) {
                if (!(s instanceof Map) || !(((Map<Object, Object>) s).get("characteristics") instanceof List)) continue;
                List<Object> chars = (List<Object>) ((Map<Object, Object>) s).get("characteristics");
                Map<Object, Object> style = null;
                for (Object c : chars)
                    if (c instanceof Map && Long.valueOf(INTERIOR_STYLE).equals(OpackCodec.asLong(((Map<Object, Object>) c).get("iid"))))
                        style = (Map<Object, Object>) c;
                if (style == null) continue;
                List<Object> three = new ArrayList<Object>();
                long[][] kinds = {{EXTERIOR_STYLE, 0x48000003L}, {INTERIOR_STYLE, 0x48000004L}, {VEHICLE_LAYOUT, 0x48000005L}};
                for (long[] k : kinds) {
                    Map<Object, Object> c = new java.util.LinkedHashMap<Object, Object>(style);
                    c.put("iid", k[0]);
                    c.put("type", k[1]);
                    c.put("mutable", Boolean.FALSE);
                    c.put("initialValue", model);
                    three.add(c);
                    values.put(k[0], model);
                }
                ((Map<Object, Object>) s).put("characteristics", three);
                return OpackCodec.encode(list);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    VehicleState(VsChannel.Log out) {
        this.out = out;
        Map<Object, Object> initial = (Map<Object, Object>) OpackCodec.decode(VsData.bytes(VsData.VALUES));
        for (Map.Entry<Object, Object> e : initial.entrySet()) values.put(OpackCodec.asLong(e.getKey()), e.getValue());
        for (int iid : VsData.WRITABLE) writable.add((long) iid);
        for (int iid : VsData.SHARED) shared.add((long) iid);
        for (int i = 0; i < VsData.NOTIFIERS.length; i += 2) notifiers.put((long) VsData.NOTIFIERS[i], (long) VsData.NOTIFIERS[i + 1]);
        for (int i = 0; i < VsData.NAME_IIDS.length; i++) names.put((long) VsData.NAME_IIDS[i], VsData.NAMES[i]);
        for (int i = 0; i < VsData.CONTROL_IIDS.length; i++) controls.put((long) VsData.CONTROL_IIDS[i], VsData.CONTROL_NAMES[i]);
        byte[] acc = VsData.bytes(VsData.ACCESSORIES);
        String model = variant;
        if (model != null && !model.isEmpty()) {
            Map<Long, Object> styles = new HashMap<Long, Object>();
            byte[] varied = withVariant(acc, model, styles);
            if (varied != null) {
                acc = varied;
                values.putAll(styles);
                names.put(EXTERIOR_STYLE, "exteriorStyle");
                names.put(VEHICLE_LAYOUT, "vehicleLayout");
                out.log("VS vehicle variant " + model + " (exteriorStyle, interiorStyle, vehicleLayout)");
            } else {
                out.log("VS vehicle variant " + model + " not set: no interiorStyle service");
            }
        }
        VehiclePage p = page;
        byte[] withPage = p == null ? null : p.install(acc, values, names, writable, shared);
        if (withPage != null) {
            acc = withPage;
            out.log("VS vehicle page: " + p.items().size() + " items");
        } else if (p != null) {
            out.log("VS vehicle page not set: no automaker settings accessory");
        }
        paged = withPage != null ? p : null;
        accessories = new OpackCodec.Raw(acc);
    }

    synchronized List<Out> handle(int lane, byte[] message) {
        List<Out> answers = new ArrayList<Out>();
        Object outer;
        try {
            outer = OpackCodec.decode(message);
        } catch (IllegalArgumentException e) {
            out.log("VS a " + message.length + " B message is not OPACK");
            return answers;
        }
        Long plugin = OpackCodec.asLong(get(outer, "pluginID"));
        Object request = null;
        if (get(outer, "pluginData") instanceof byte[]) {
            try {
                request = OpackCodec.decode((byte[]) get(outer, "pluginData"));
            } catch (IllegalArgumentException e) {
                // dropped below
            }
        }
        if (request == null || plugin == null || plugin != PLUGIN_ID) {
            out.log("VS message for plugin " + plugin + " without readable data, dropped");
            return answers;
        }
        Object transaction = get(request, "transactionID");
        Object given = get(request, "values");
        Object command = get(request, "command");
        if ("configRequest".equals(command)) {
            out.log("VS phone reads the vehicle description");
            answers.add(new Out(lane, wrap(dict("command", "configResponse", "transactionID", transaction,
                    "values", dict("accessories", accessories)))));
        } else if ("registerRequest".equals(command)) {
            List<Object> iids = list(given);
            answers.add(new Out(lane, wrap(follow(iids, transaction))));
            // the version line's logo (VERSION_LINE + 5) needs an image archive, which M3 has none of
            if (hasIid(iids, historySlot(0) + 1)) answers.addAll(historyUpdate());
            if (hasIid(iids, TEMPORARY_ZONE + 1)) out.log("VS phone is ready for notifications (not shown by M3)");
        } else if ("readRequest".equals(command)) {
            answers.add(new Out(lane, wrap(read(list(given), transaction))));
        } else if ("writeRequest".equals(command)) {
            answers.addAll(write(lane, map(given), transaction));
        } else if ("controlRequest".equals(command)) {
            answers.add(new Out(lane, wrap(control(map(given), transaction))));
        } else {
            out.log("VS phone sent " + (command == null ? "no command" : "\"" + command + "\"") + ", not handled");
        }
        return answers;
    }

    /**
     * Values the car reports (CarClimate), told to the phone. The car is right about its own
     * state, so LIVI's rules don't run: each value is simply set, as LIVI's car_sets does.
     */
    synchronized List<Out> fromCar(Map<Long, Object> reported) {
        Map<Object, Object> changed = new LinkedHashMap<Object, Object>();
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, Object> e : reported.entrySet()) {
            Long since = heldSince.get(e.getKey());
            if (since != null) {
                if (now - since < HOLD_MS && !same(values.get(e.getKey()), e.getValue())) continue;
                heldSince.remove(e.getKey());
            }
            carSets(e.getKey(), e.getValue(), changed);
        }
        List<Out> answers = new ArrayList<Out>();
        Out notify = updateNotify(changed);
        if (notify != null) answers.add(notify);
        return answers;
    }

    private Map<Object, Object> follow(List<Object> iids, Object transaction) {
        Map<Object, Object> held = new LinkedHashMap<Object, Object>();
        Map<Object, Object> missing = new LinkedHashMap<Object, Object>();
        List<String> named = new ArrayList<String>();
        for (Object o : iids) {
            Long iid = OpackCodec.asLong(o);
            if (iid == null) continue;
            followed.add(iid);
            if (values.containsKey(iid)) held.put(iid, values.get(iid));
            else missing.put(iid, NO_VALUE);
            if (names.containsKey(iid)) named.add(names.get(iid));
        }
        out.log("VS phone follows " + iids.size() + " values (" + join(named) + "), " + held.size() + " of them set");
        Map<Object, Object> answer = dict("command", "registerResponse", "transactionID", transaction, "values", held);
        if (!missing.isEmpty()) answer.put("errors", missing);
        return answer;
    }

    private Map<Object, Object> read(List<Object> iids, Object transaction) {
        Map<Object, Object> held = new LinkedHashMap<Object, Object>();
        Map<Object, Object> missing = new LinkedHashMap<Object, Object>();
        List<String> named = new ArrayList<String>();
        for (Object o : iids) {
            Long iid = OpackCodec.asLong(o);
            if (iid == null) continue;
            if (names.containsKey(iid)) named.add(names.get(iid));
            if (values.containsKey(iid)) held.put(iid, values.get(iid));
            else missing.put(iid, NO_VALUE);
        }
        out.log("VS phone reads " + join(named));
        Map<Object, Object> answer = dict("command", "readResponse", "transactionID", transaction);
        if (!held.isEmpty()) answer.put("values", held);
        if (!missing.isEmpty()) answer.put("errors", missing);
        return answer;
    }

    private List<Out> write(int lane, Map<Object, Object> written, Object transaction) {
        Map<Object, Object> results = new LinkedHashMap<Object, Object>();
        Map<Object, Object> changed = new LinkedHashMap<Object, Object>();
        for (Map.Entry<Object, Object> e : written.entrySet()) {
            Long iid = OpackCodec.asLong(e.getKey());
            if (iid == null) continue;
            Object v = e.getValue();
            if (!writable.contains(iid)) {
                out.log("VS phone may not write value " + iid);
                results.put(iid, NO_VALUE);
                continue;
            }
            if (shared.contains(iid)) out.log("VS phone set " + names.get(iid) + " to " + shown(v));
            else out.log("VS phone wrote " + names.get(iid));
            results.put(iid, WRITTEN);
            // the driver dismissed a notification: its "hidden" (slot + 7) is written true, and it stays hidden
            long inHistory = iid - (HISTORY + 1);
            if (inHistory >= 0 && inHistory < 8L * HISTORY_SLOTS && inHistory % 8 == 7 && Boolean.TRUE.equals(v)) {
                synchronized (NOTICES) {
                    int n = (int) (inHistory / 8);
                    if (n < NOTICES.size()) NOTICES.get(n).hidden = true;
                }
            }
            Object before = values.put(iid, v);
            if (!same(before, v) && followed.contains(iid)) changed.put(iid, v);
        }
        // the vehicle page's switches and buttons are its own: no rules, and the car's climate never sees them
        Map<Long, Object> onPage = new LinkedHashMap<Long, Object>();
        if (paged != null) {
            for (Map.Entry<Object, Object> e : written.entrySet()) {
                Long iid = OpackCodec.asLong(e.getKey());
                if (iid != null && paged.owns(iid)) onPage.put(iid, e.getValue());
            }
            for (Map.Entry<Long, Object> e : paged.phoneWrote(onPage).entrySet()) carSets(e.getKey(), e.getValue(), changed);
        }
        Car c = car;
        if (c == null) {
            settle(written, changed);
        } else {
            // a real car: it does what LIVI's rules do itself, and the reader shows the result
            Map<Long, Object> sent = new LinkedHashMap<Long, Object>();
            long now = System.currentTimeMillis();
            for (Map.Entry<Object, Object> e : written.entrySet()) {
                Long iid = OpackCodec.asLong(e.getKey());
                if (iid == null || !writable.contains(iid) || onPage.containsKey(iid)) continue;
                sent.put(iid, e.getValue());
                heldSince.put(iid, now);
            }
            c.phoneWrote(sent, new TreeMap<Long, Object>(values));
        }
        List<Out> answers = new ArrayList<Out>();
        answers.add(new Out(lane, wrap(dict("command", "writeResponse", "transactionID", transaction, "errors", results))));
        Out notify = updateNotify(changed);
        if (notify != null) answers.add(notify);
        return answers;
    }

    private Map<Object, Object> control(Map<Object, Object> commands, Object transaction) {
        Map<Object, Object> answered = new LinkedHashMap<Object, Object>();
        Map<Object, Object> refused = new LinkedHashMap<Object, Object>();
        for (Map.Entry<Object, Object> e : commands.entrySet()) {
            Long iid = OpackCodec.asLong(e.getKey());
            String name = iid == null ? null : controls.get(iid);
            if (name == null) {
                out.log("VS phone sent command " + shown(e.getKey()) + ", the car has none such");
                refused.put(e.getKey(), NO_SUCH_COMMAND);
                continue;
            }
            out.log("VS phone pressed " + name + " " + shown(e.getValue()));
            Controls c = mediaControls;
            if (c != null) {
                try {
                    c.pressed(name, e.getValue());
                } catch (Throwable t) {
                    out.log("VS " + name + " not handled: " + t);
                }
            }
            answered.put(e.getKey(), null);
        }
        Map<Object, Object> answer = dict("command", "controlResponse", "transactionID", transaction);
        if (!answered.isEmpty()) answer.put("values", answered);
        if (!refused.isEmpty()) answer.put("errors", refused);
        return answer;
    }

    /** every slot of the notification history, as LIVI sends it when the phone follows it: the notices, then empty slots */
    private List<Out> historyUpdate() {
        List<Out> answers = new ArrayList<Out>();
        List<Notice> notices;
        synchronized (NOTICES) {
            notices = new ArrayList<Notice>(NOTICES);
        }
        for (int n = 0; n < HISTORY_SLOTS; n++) {
            long iid = historySlot(n);
            Notice x = n < notices.size() ? notices.get(n) : null;
            Object[] group = x == null
                    ? new Object[] { iid + 1, "", iid + 2, "", iid + 3, 0L, iid + 4, "", iid + 5, 0L, iid + 6, true, iid + 7, true }
                    : new Object[] { iid + 1, x.label, iid + 2, x.description, iid + 3, x.atSeconds, iid + 4, x.symbol, iid + 5, x.severity,
                            iid + 6, true, iid + 7, x.hidden };
            Map<Object, Object> pairs = new LinkedHashMap<Object, Object>();
            for (int i = 0; i < group.length; i += 2) {
                values.put((Long) group[i], group[i + 1]);
                pairs.put(group[i], group[i + 1]);
            }
            answers.add(updateNotify(pairs));
        }
        return answers;
    }

    static long historySlot(int n) {
        return HISTORY + 1 + 8 * n;
    }

    // ---- LIVI rules.rs ----

    private void settle(Map<Object, Object> written, Map<Object, Object> changed) {
        keepZonesSynced(written, changed);
        switchByLevel(written, changed);
        followAuto(written, changed);
        runMaxAc(written, changed);
        muteByVolume(written, changed);
    }

    /** the value written for iid, or NONE */
    private static Object find(Map<Object, Object> written, long iid) {
        for (Map.Entry<Object, Object> e : written.entrySet()) {
            Long k = OpackCodec.asLong(e.getKey());
            if (k != null && k == iid) return e.getValue();
        }
        return NONE;
    }

    private static final Object NONE = new Object();

    private void muteByVolume(Map<Object, Object> written, Map<Object, Object> changed) {
        Long volume = OpackCodec.asLong(values.get(VOLUME));
        long level = volume == null ? 0 : volume;
        Object mute = find(written, MUTE), loud = find(written, VOLUME);
        if (Boolean.TRUE.equals(mute) && level > 0) {
            resume.put(VOLUME, level);
            carSets(VOLUME, 0L, changed);
        } else if (Boolean.FALSE.equals(mute) && level == 0) {
            Object back = resume.containsKey(VOLUME) ? resume.get(VOLUME) : (Object) 50L;
            carSets(VOLUME, back, changed);
        } else if (mute == NONE && loud instanceof Long && (Long) loud > 0) {
            carSets(MUTE, false, changed);
        }
    }

    private void keepZonesSynced(Map<Object, Object> written, Map<Object, Object> changed) {
        if (!Boolean.TRUE.equals(values.get(ZONES_SYNCED))) return;
        if (find(written, PASSENGER_TARGET) != NONE && find(written, ZONES_SYNCED) == NONE) {
            carSets(ZONES_SYNCED, false, changed);
        } else if (values.containsKey(DRIVER_TARGET)) {
            carSets(PASSENGER_TARGET, values.get(DRIVER_TARGET), changed);
        }
    }

    private void switchByLevel(Map<Object, Object> written, Map<Object, Object> changed) {
        for (long[] pair : LEVELLED) {
            long levelIid = pair[0], onIid = pair[1];
            Object level = find(written, levelIid), on = find(written, onIid);
            if (level instanceof Long) {
                carSets(onIid, (Long) level > 0, changed);
            } else if (on instanceof Boolean) {
                boolean isOn = (Boolean) on;
                boolean held = values.containsKey(levelIid);
                Object now = values.get(levelIid);
                boolean zero = held && Long.valueOf(0).equals(now);
                if (!isOn && !zero) {
                    if (held) resume.put(levelIid, now);
                    carSets(levelIid, 0L, changed);
                } else if (isOn && zero) {
                    Object back = resume.containsKey(levelIid) ? resume.get(levelIid) : (Object) 1L;
                    carSets(levelIid, back, changed);
                }
            }
        }
    }

    private void followAuto(Map<Object, Object> written, Map<Object, Object> changed) {
        Object auto = find(written, AUTO_CLIMATE);
        if (auto instanceof Boolean) {
            carSets(FAN_AUTO, auto, changed);
            for (long vent : VENTS) carSets(vent + 2, auto, changed);
            return;
        }
        boolean fanByHand = find(written, FAN_LEVEL) != NONE || find(written, FAN_ON) != NONE;
        boolean ventsByHand = false;
        for (long vent : VENTS) ventsByHand |= find(written, vent + 1) != NONE;
        if (fanByHand) carSets(FAN_AUTO, false, changed);
        if (ventsByHand) for (long vent : VENTS) carSets(vent + 2, false, changed);
        if (fanByHand || ventsByHand) carSets(AUTO_CLIMATE, false, changed);
    }

    private void runMaxAc(Map<Object, Object> written, Map<Object, Object> changed) {
        Object[][] takes = { { DRIVER_TARGET, TEMPERATURE_LO }, { PASSENGER_TARGET, TEMPERATURE_LO },
                { COMPRESSOR, true }, { RECIRCULATION, true }, { FAN_LEVEL, 7L }, { FAN_ON, true },
                { FAN_AUTO, false }, { AUTO_CLIMATE, false } };
        Object max = find(written, MAX_AC);
        boolean tookWritten = false;
        for (Object[] t : takes) tookWritten |= find(written, (Long) t[0]) != NONE;
        if (Boolean.TRUE.equals(max) && beforeMaxAc == null) {
            beforeMaxAc = new ArrayList<Object[]>();
            for (Object[] t : takes) if (values.containsKey(t[0])) beforeMaxAc.add(new Object[] { t[0], values.get(t[0]) });
            for (Object[] t : takes) carSets((Long) t[0], t[1], changed);
        } else if (Boolean.FALSE.equals(max)) {
            List<Object[]> before = beforeMaxAc;
            beforeMaxAc = null;
            if (before != null) for (Object[] t : before) carSets((Long) t[0], t[1], changed);
        } else if (beforeMaxAc != null && tookWritten) {
            beforeMaxAc = null;
            carSets(MAX_AC, false, changed);
        }
    }

    private void carSets(long iid, Object v, Map<Object, Object> changed) {
        if (values.containsKey(iid) && same(values.get(iid), v)) return;
        if (names.containsKey(iid)) out.log("VS car set " + names.get(iid) + " to " + shown(v));
        values.put(iid, v);
        if (followed.contains(iid)) changed.put(iid, v);
        Long notifier = notifiers.get(iid);
        if (notifier != null && names.containsKey(iid)) {
            Long count = OpackCodec.asLong(values.get(notifier));
            long next = ((count == null ? 0 : count) + 1) % 256;
            values.put(notifier, next);
            if (followed.contains(notifier)) changed.put(notifier, next);
        }
    }

    // ---- LIVI wire.rs ----

    private static Out updateNotify(Map<Object, Object> changed) {
        if (changed.isEmpty()) return null;
        return new Out(HIGH, wrap(dict("command", "updateNotify", "values", changed)));
    }

    static byte[] wrap(Object inner) {
        return OpackCodec.encode(dict("pluginID", PLUGIN_ID, "pluginData", OpackCodec.encode(inner)));
    }

    static String shown(Object v) {
        if (v instanceof byte[]) return ((byte[]) v).length + " B";
        if (v instanceof List && ((List<?>) v).size() > 4) return ((List<?>) v).size() + " items";
        if (v == null) return "null";
        if (v instanceof Map || v instanceof List) return Opack.show(OpackCodec.encode(v));
        return String.valueOf(v);
    }

    // ---- small helpers ----

    static Map<Object, Object> dict(Object... kv) {
        Map<Object, Object> m = new LinkedHashMap<Object, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static Object get(Object dict, String key) {
        return dict instanceof Map ? ((Map<?, ?>) dict).get(key) : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> map(Object v) {
        return v instanceof Map ? (Map<Object, Object>) v : new LinkedHashMap<Object, Object>();
    }

    static boolean hasIid(List<Object> iids, long iid) {
        for (Object o : iids) {
            Long n = OpackCodec.asLong(o);
            if (n != null && n == iid) return true;
        }
        return false;
    }

    /** OPACK value equality, as LIVI's Value compares (data by content) */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof byte[] && b instanceof byte[]) return Arrays.equals((byte[]) a, (byte[]) b);
        if (a instanceof List && b instanceof List) {
            List<?> x = (List<?>) a, y = (List<?>) b;
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            List<?> x = new ArrayList<Object>(((Map<?, ?>) a).entrySet()), y = new ArrayList<Object>(((Map<?, ?>) b).entrySet());
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) {
                Map.Entry<?, ?> p = (Map.Entry<?, ?>) x.get(i), q = (Map.Entry<?, ?>) y.get(i);
                if (!same(p.getKey(), q.getKey()) || !same(p.getValue(), q.getValue())) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    static String join(List<String> parts) {
        StringBuilder s = new StringBuilder();
        for (String p : parts) s.append(s.length() == 0 ? "" : " ").append(p);
        return s.toString();
    }
}
