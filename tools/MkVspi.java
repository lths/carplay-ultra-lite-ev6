package ioniq6.cpclimate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build tool (host): prints, as JSON, the automaker settings accessory's services with the car's whole page in
 * them (CarVehicle.describe with every row, plain rows in sections). tools/mkvspi.py puts them into
 * data/vspi.bplist, the description the native part sends when CarPlay connects. The phone builds the Vehicle
 * app from that description, not from the configResponse (seen on the car, 2026-11-10: 1.1.0 changed only the
 * configResponse and the phone kept showing LIVI's two version lines).
 *
 * usage: java ioniq6.cpclimate.MkVspi [model]
 */
public class MkVspi {
    @SuppressWarnings("unchecked")
    public static void main(String[] a) {
        String model = a.length > 0 ? a[0] : "EV6";
        VehiclePage.tiles = "off";
        VehiclePage page = CarVehicle.describe(new VehiclePage(), model, null, true);
        Map<String, Object> v = new LinkedHashMap<String, Object>();
        v.put("version", CpClimate.VERSION);
        page.update(v);
        byte[] acc = page.install(VsData.bytes(VsData.ACCESSORIES), new HashMap<Long, Object>(), new HashMap<Long, String>(),
                new HashSet<Long>(), new HashSet<Long>());
        if (acc == null) throw new IllegalStateException("no automaker settings accessory in the description");
        for (Object o : (List<Object>) OpackCodec.decode(acc)) {
            Map<Object, Object> m = (Map<Object, Object>) o;
            if (Long.valueOf(VehiclePage.ACCESSORY).equals(OpackCodec.asLong(m.get("iid")))) {
                StringBuilder b = new StringBuilder();
                json(b, m.get("services"));
                System.out.println(b);
                return;
            }
        }
        throw new IllegalStateException("accessory not found after install");
    }

    @SuppressWarnings("unchecked")
    static void json(StringBuilder b, Object o) {
        if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                json(b, String.valueOf(e.getKey()));
                b.append(':');
                json(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object x : (List<Object>) o) {
                if (!first) b.append(',');
                first = false;
                json(b, x);
            }
            b.append(']');
        } else if (o instanceof Boolean || o instanceof Long || o instanceof Integer) {
            b.append(o);
        } else if (o instanceof String) {
            b.append('"');
            for (char c : ((String) o).toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
            b.append('"');
        } else {
            throw new IllegalStateException("a " + (o == null ? "null" : o.getClass().getName()) + " in the description");
        }
    }
}
