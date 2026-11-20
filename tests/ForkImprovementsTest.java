package ioniq6.cpclimate;

import java.util.*;

/** New front-comfort schema, phone writes and car feedback must agree; old EV6 commands stay intact. */
public final class ForkImprovementsTest {
    static int fails;
    static void check(String name, boolean ok) { if (!ok) fails++; System.out.println((ok ? "ok   " : "FAIL ") + name); }
    static final VsChannel.Log SILENT = new VsChannel.Log() { public void log(String s) { } };
    static List<?> accessories(VehicleState s) { return (List<?>) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) Bplist.decode(s.vspi())).get("pluginConfigs")).get(0)).get("accessories"); }
    static Map<?, ?> find(Object o, long iid) {
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (Long.valueOf(iid).equals(m.get("iid"))) return m;
            for (Object v : m.values()) { Map<?, ?> f = find(v, iid); if (f != null) return f; }
        } else if (o instanceof List) for (Object v : (List<?>) o) { Map<?, ?> f = find(v, iid); if (f != null) return f; }
        return null;
    }
    static boolean ids(Object o, Set<Object> all) {
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.containsKey("iid") && !all.add(m.get("iid"))) return false;
            for (Object v : m.values()) if (!ids(v, all)) return false;
        } else if (o instanceof List) for (Object v : (List<?>) o) if (!ids(v, all)) return false;
        return true;
    }
    static Map<Long, Object> one(long iid, Object v) { Map<Long, Object> m = new LinkedHashMap<Long, Object>(); m.put(iid, v); return m; }
    static String commands(Map<Long, Object> written, Map<Long, Object> now) { return ClimateMap.commands(written, now, 2).toString(); }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        check("compact OPACK -1 is accepted", Long.valueOf(-1).equals(OpackCodec.decode(new byte[] { 7 })));
        List<?> compact = (List<?>) OpackCodec.decode(new byte[] { (byte) 0xd4, 7, 0x42, 'o', 'k', (byte) 0xa0, 7 });
        check("compact -1 does not shift later object references", compact.equals(Arrays.asList(-1L, "ok", "ok", -1L)));
        VehicleDescription.seatCooling = true; VehicleDescription.wheelHeat = true;
        VehicleState.variant = "DBX"; VehicleState.page = null;
        VehicleState s = new VehicleState(SILENT);
        List<?> acc = accessories(s);
        check("the complete schema has globally unique IIDs", ids(acc, new HashSet<Object>()));
        check("last media source starts empty instead of DAB", "".equals(find(acc, 4354).get("initialValue")) && "".equals(s.values.get(4354L)));
        check("HVAC power is declared and writable", Boolean.TRUE.equals(find(acc, 514).get("writable")) && Long.valueOf(0x31000002L).equals(find(acc, 514).get("type")));
        check("both front seats expose cooling through -4", Long.valueOf(-4).equals(find(acc, 1026).get("minimumValue")) && Long.valueOf(-4).equals(find(acc, 1030).get("minimumValue")));
        Map<?, ?> wheel = find(acc, 572);
        check("wheel service is heat only, zero or one", Long.valueOf(0x11000003L).equals(find(acc, 571).get("type")) && Long.valueOf(0).equals(wheel.get("minimumValue")) && Long.valueOf(1).equals(wheel.get("maximumValue")));
        check("all three theme variant keys agree at connection", "DBX".equals(find(acc, 770).get("initialValue")) && "DBX".equals(find(acc, 771).get("initialValue")) && "DBX".equals(find(acc, 772).get("initialValue")));
        Set<Object> before = new HashSet<Object>(), after = new HashSet<Object>();
        ids(OpackCodec.decode(VsData.bytes(VsData.ACCESSORIES)), before); ids(acc, after); after.removeAll(before);
        check("only front comfort and missing variant keys are added", after.equals(new HashSet<Object>(Arrays.asList(514L, 571L, 572L, 771L, 772L))));

        final List<String> sent = new ArrayList<String>();
        s.car = new VehicleState.Car() { public void phoneWrote(Map<Long, Object> w, Map<Long, Object> n) { sent.add(commands(w, n)); } };
        s.handle(0, VehicleState.wrap(VehicleState.dict("command", "writeRequest", "transactionID", 1L, "values", VehicleState.dict(572L, 1L, 514L, false))));
        check("native wheel and HVAC writes reach existing commands", sent.size() == 1 && sent.get(0).contains("wheel(1)") && sent.get(0).contains("vr(5, 0, 0)"));
        for (int raw = 3; raw <= 5; raw++) {
            ClimateMap.Reading r = new ClimateMap.Reading(); r.driverSeat = raw;
            Map<Long, Object> v = ClimateMap.toCarPlay(r);
            check("ventilation " + (raw - 2) + " survives phone feedback and a write", Boolean.TRUE.equals(v.get(ClimateMap.DRIVER_SEAT_ON))
                    && commands(one(ClimateMap.DRIVER_SEAT_LEVEL, v.get(ClimateMap.DRIVER_SEAT_LEVEL)), v).contains(", " + raw + ")"));
        }
        check("the third cooling dot uses the middle physical level", commands(one(1026, -3L), s.values).contains(", 4)"));
        Map<Long, Object> off = one(1026, -4L); off.put(1027L, false);
        check("OFF beats a cooling level in the same phone write", commands(off, s.values).equals("[seat(" + ClimateMap.SET_DRIVER_SEAT + ", 2)]"));
        check("native wheel rejects unsupported levels", commands(one(572, 2L), s.values).equals("[]"));
        ClimateMap.Reading r = new ClimateMap.Reading(); r.wheel = -1;
        check("unknown wheel feedback is withheld", !ClimateMap.toCarPlay(r).containsKey(572L));
        r.wheel = 0;
        check("known wheel OFF clears the native indicator", Long.valueOf(0).equals(ClimateMap.toCarPlay(r).get(572L)));
        ClimateMap.lastDriver = null; ClimateMap.lastPassenger = null;
        r = new ClimateMap.Reading(); r.on = 2; r.driverF = 17;
        check("first off-state does not fabricate a temperature", !ClimateMap.toCarPlay(r).containsKey(520L));
        r.on = 1; r.unit = 1; r.driverF = 24; r.sync = 0; r.passengerRaw = 20;
        Map<Long, Object> on = ClimateMap.toCarPlay(r);
        r.on = 2; r.driverF = 17; r.passengerRaw = 6;
        Map<Long, Object> idle = ClimateMap.toCarPlay(r);
        check("HVAC off retains both last valid setpoints", on.get(520L).equals(idle.get(520L)) && on.get(534L).equals(idle.get(534L)));
        check("retained setpoints do not turn HVAC on", Boolean.FALSE.equals(idle.get(514L)));

        List<Object> changed = (List<Object>) OpackCodec.decode(VsData.bytes(VsData.ACCESSORIES));
        ((Map<Object, Object>) find(changed, 520)).put("initialValue", 26.5);
        byte[] versioned = VehicleDescription.apply(OpackCodec.encode(changed), new HashMap<Long, Object>(), new HashMap<Long, String>(), new HashSet<Long>(), new HashSet<Long>());
        check("live defaults do not change the accessory schema version", find(acc, 512).get("version").equals(find(OpackCodec.decode(versioned), 512).get("version")));
        VehicleDescription.seatCooling = false; VehicleDescription.wheelHeat = false;
        List<?> legacy = accessories(new VehicleState(SILENT));
        check("optional native controls can be disabled", find(legacy, 571) == null && Long.valueOf(0).equals(find(legacy, 1026).get("minimumValue")));
        check("descriptor versions change when native controls change", !find(acc, 512).get("version").equals(find(legacy, 512).get("version")));
        System.exit(fails == 0 ? 0 : 1);
    }
}
