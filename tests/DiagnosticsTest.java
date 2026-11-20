package ioniq6.cpclimate;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Structural logging, parse diagnostics and video context without private captures or OEM files. */
public final class DiagnosticsTest {
    static int fails;
    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static void error(String what, byte[] bytes, String reason, int offset, int tagOffset, int tag) {
        try {
            OpackCodec.decode(bytes);
            check(what, false);
        } catch (OpackCodec.DecodeException e) {
            check(what, e.reason.startsWith(reason) && e.offset == offset && e.tagOffset == tagOffset && e.tag == tag);
        }
    }

    static final class Log implements VsChannel.Log {
        final List<String> lines = new ArrayList<String>();
        public void log(String message) { lines.add(message); }
        String text() { return String.join("\n", lines); }
    }

    public static void main(String[] args) {
        error("unsupported root tag identifies its byte", new byte[] {(byte) 0xff}, "unsupported tag", 1, 0, 255);
        error("nested tag offset survives unwinding", new byte[] {(byte) 0xd1, (byte) 0xff}, "unsupported tag", 2, 1, 255);
        error("short integer identifies cursor and original tag", new byte[] {0x32, 1, 2}, "unexpected end", 3, 0, 0x32);
        error("short string identifies value start", new byte[] {0x44, 0x61}, "short value", 1, 0, 0x44);
        error("invalid length identifies its length prefix", new byte[] {0x62, (byte) 0xff, (byte) 0xff}, "invalid length", 3, 0, 0x62);
        error("invalid back-reference identifies its tag", new byte[] {(byte) 0xa0}, "invalid reference", 1, 0, 0xa0);
        error("missing open-array terminator reports EOF", new byte[] {(byte) 0xdf, 8}, "unexpected end", 2, 2, -1);
        error("trailing data identifies first unconsumed byte", new byte[] {1, (byte) 0xff}, "trailing bytes", 1, 1, 255);
        byte[] deep = new byte[66];
        Arrays.fill(deep, (byte) 0xd1);
        deep[65] = 4;
        error("depth guard retains failing nested tag", deep, "nesting limit", 64, 64, 0xd1);

        for (int size : new int[] {65535, 65536, 73861, 131072}) {
            byte[] data = new byte[size];
            data[size - 1] = 0x7f;
            check("valid data of " + size + " bytes still round-trips",
                    Arrays.equals(data, (byte[]) OpackCodec.decode(OpackCodec.encode(data))));
        }

        List<Object> route = new ArrayList<Object>();
        for (int i = 0; i < 4000; i++) route.add(VehicleState.dict("latitude", 12.345678, "longitude", 98.765432));
        Map<Object, Object> values = VehicleState.dict(260L, route, 266L, VehicleState.dict("address", "PRIVATE_DESTINATION_SENTINEL"),
                268L, VehicleState.dict("address", "PRIVATE_ORIGIN_SENTINEL"), 271L, "PRIVATE_APP_SENTINEL");
        byte[] packet = VehicleState.wrap(VehicleState.dict("command", "writeRequest", "transactionID", 9L, "values", values));
        Log log = new Log();
        VehicleState state = new VehicleState(log);
        List<VehicleState.Out> answers = state.handle(1, packet, "VS1 asyn/comm " + packet.length + " B");
        String text = log.text();
        check("large route still receives its normal write response", packet.length > 73861 && answers.size() == 1
                && "writeResponse".equals(VehicleState.get(OpackCodec.decode((byte[]) VehicleState.get(
                        OpackCodec.decode(answers.get(0).message), "pluginData")), "command")));
        check("route log includes counts and schema names", text.contains("legs:list(4000)") && text.contains("destination:map(1)")
                && text.contains("origin:map(1)") && text.contains("userVisibleApplicationName:string("));
        check("route log omits addresses, coordinates and app text", !text.contains("PRIVATE_") && !text.contains("12.345678") && !text.contains("98.765432"));
        check("large route logging stays bounded", text.length() < 1500);

        List<Object> unwalkable = new AbstractList<Object>() {
            @Override public int size() { return 10000000; }
            @Override public Object get(int i) { throw new AssertionError("logging walked payload"); }
            @Override public String toString() { throw new AssertionError("logging rendered payload"); }
        };
        check("value summaries never walk nested route lists", "list(10000000)".equals(VehicleState.shown(unwalkable)));
        Map<Long, String> names = new HashMap<Long, String>();
        names.put(260L, "legs");
        check("field summaries never walk nested route lists", OpackSummary.fields(VehicleState.dict(260L, unwalkable), names).contains("legs:list(10000000)"));
        Map<Object, Object> unusual = VehicleState.dict("PRIVATE_KEY_SENTINEL", "PRIVATE_VALUE_SENTINEL");
        check("unknown field names and strings are withheld", !OpackSummary.fields(unusual, null).contains("PRIVATE_")
                && !VehicleState.shown(unusual).contains("PRIVATE_"));
        Map<Object, Object> wide = new java.util.LinkedHashMap<Object, Object>();
        for (int i = 0; i < 10000; i++) wide.put((long) i, unwalkable);
        check("field listing stops at its fixed limit", OpackSummary.fields(wide, null).length() < 400);

        log.lines.clear();
        byte[] invalid = new byte[73861];
        state.handle(1, invalid);
        check("failed large packet includes lane, reason, cursor and tag", log.text().contains("lane=1 layer=outer packetBytes=73861")
                && log.text().contains("unsupported tag offset=1 tag=0x0 tagOffset=0"));
        log.lines.clear();
        state.handle(0, OpackCodec.encode(VehicleState.dict("pluginID", 0L, "pluginData", new byte[] {(byte) 0xd1, (byte) 0xff})));
        check("nested failure identifies the plugin-data buffer separately", log.text().contains("lane=0 layer=pluginData")
                && log.text().contains("dataBytes=2") && log.text().contains("offset=2 tag=0xff tagOffset=1"));
        log.lines.clear();
        state.handle(0, VehicleState.wrap(VehicleState.dict("command", "PRIVATE_COMMAND_SENTINEL")), "VS0 asyn/comm");
        check("unknown command text is not copied into the log", !log.text().contains("PRIVATE_COMMAND_SENTINEL"));
        for (int i = 0; i < 100; i++) state.handle(1, new byte[] {0});
        check("repeated parse error logging is capped", log.lines.size() < 40 && log.text().contains("further parse errors suppressed"));
        if (fails != 0) throw new AssertionError(fails + " failed");
    }
}
