package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** CAF schema fixes adapted from joshuaauger/carplay-ultra-lite-gen5w v1.1.15 (GPL-3.0-or-later).
 * Only front comfort controls are added; the EV6's tested vehicle commands remain in CarClimate. */
final class VehicleDescription {
    static volatile boolean seatCooling, wheelHeat;
    static final long NOW_PLAYING_SOURCE = 4354, WHEEL_SERVICE = 571, WHEEL_LEVEL = 572;
    static final long COOLING_MIN = -4;

    @SuppressWarnings("unchecked")
    static byte[] apply(byte[] bytes, Map<Long, Object> values, Map<Long, String> names,
            java.util.Set<Long> writable, java.util.Set<Long> shared) {
        List<Object> accessories = (List<Object>) OpackCodec.decode(bytes);
        Map<Object, Object> seat = null, climate = null;
        for (Object a : accessories) for (Object s : (List<Object>) ((Map<?, ?>) a).get("services")) {
            Map<Object, Object> service = (Map<Object, Object>) s;
            if (Long.valueOf(513).equals(service.get("iid"))) climate = service;
            for (Object c : (List<Object>) service.get("characteristics")) {
                Map<Object, Object> ch = (Map<Object, Object>) c;
                long iid = OpackCodec.asLong(ch.get("iid"));
                if (iid == NOW_PLAYING_SOURCE) ch.put("initialValue", "");
                if (iid == ClimateMap.DRIVER_SEAT_LEVEL) seat = ch;
                if (seatCooling && (iid == ClimateMap.DRIVER_SEAT_LEVEL || iid == ClimateMap.PASSENGER_SEAT_LEVEL))
                    ch.put("minimumValue", COOLING_MIN);
            }
        }
        if (climate == null || seat == null) throw new IllegalStateException("missing climate/seat templates");
        List<Object> cabin = (List<Object>) climate.get("characteristics");
        Map<Object, Object> compressor = null;
        for (Object c : cabin) if (Long.valueOf(ClimateMap.COMPRESSOR).equals(((Map<?, ?>) c).get("iid"))) compressor = (Map<Object, Object>) c;
        if (compressor == null) throw new IllegalStateException("missing cabin power template");
        Map<Object, Object> power = new LinkedHashMap<Object, Object>(compressor);
        power.put("iid", ClimateMap.HVAC_ON);
        power.put("type", 0x31000002L);
        power.put("initialValue", false);
        cabin.add(0, power);
        names.put(ClimateMap.HVAC_ON, "hvacOn");
        if (wheelHeat) {
            Map<Object, Object> wheel = new LinkedHashMap<Object, Object>(seat);
            wheel.put("iid", WHEEL_LEVEL);
            wheel.put("minimumValue", 0L);
            wheel.put("maximumValue", 1L);
            wheel.put("stepValue", 1L);
            wheel.put("initialValue", 0L);
            for (Object a : accessories) if (Long.valueOf(512).equals(((Map<?, ?>) a).get("iid")))
                ((List<Object>) ((Map<?, ?>) a).get("services")).add(VehicleState.dict("iid", WHEEL_SERVICE,
                        "type", 0x11000003L, "characteristics", new ArrayList<Object>(Arrays.asList((Object) wheel))));
            names.put(WHEEL_LEVEL, "steeringWheelHeatingLevel");
        }
        // The final schema supplies defaults and permissions, including the newly added power/wheel values.
        for (Object a : accessories) {
            Map<Object, Object> accessory = (Map<Object, Object>) a;
            List<Object> services = (List<Object>) accessory.get("services");
            for (Object s : services) for (Object c : (List<Object>) ((Map<?, ?>) s).get("characteristics")) {
                Map<?, ?> ch = (Map<?, ?>) c;
                long iid = OpackCodec.asLong(ch.get("iid"));
                if (ch.containsKey("initialValue")) values.put(iid, ch.get("initialValue"));
                if (Boolean.TRUE.equals(ch.get("writable"))) { writable.add(iid); shared.add(iid); }
            }
            // A changed accessory needs a changed version for the phone's cached schema.
            // Ignore live defaults: a battery/temperature update must not invalidate its schema.
            CRC32 crc = new CRC32();
            crc.update(OpackCodec.encode(schema(services)));
            accessory.put("version", "1." + crc.getValue());
        }
        return OpackCodec.encode(accessories);
    }

    private static Object schema(Object value) {
        if (value instanceof Map) {
            Map<Object, Object> result = new LinkedHashMap<Object, Object>();
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if ("initialValue".equals(e.getKey()) && Boolean.TRUE.equals(map.get("mutable"))) continue;
                result.put(e.getKey(), schema(e.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object x : (List<?>) value) result.add(schema(x));
            return result;
        }
        return value;
    }
}
