package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host test of the volume scale (CarAudio.percent/level) and of the notification history (VehicleState.post):
 * a posted notice fills slot 0 for a phone that follows the history, older ones move down, a dismissed one stays
 * hidden, and the media controls reach the listener.
 */
public class AudioNoticeTest {
    static int fails;

    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> inner(VehicleState.Out o) {
        Map<Object, Object> outer = (Map<Object, Object>) OpackCodec.decode(o.message);
        return (Map<Object, Object>) OpackCodec.decode((byte[]) outer.get("pluginData"));
    }

    static byte[] request(String command, Object values) {
        Map<Object, Object> r = VehicleState.dict("command", command, "transactionID", 7L);
        if (values != null) r.put("values", values);
        return OpackCodec.encode(VehicleState.dict("pluginID", 0L, "pluginData", OpackCodec.encode(r)));
    }

    @SuppressWarnings("unchecked")
    static Object value(List<VehicleState.Out> outs, long iid) {
        for (VehicleState.Out o : outs) {
            Object v = inner(o).get("values");
            if (v instanceof Map)
                for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet())
                    if (Long.valueOf(iid).equals(OpackCodec.asLong(e.getKey()))) return e.getValue();
        }
        return null;
    }

    public static void main(String[] a) {
        boolean steps = true, back = true;
        for (int level = 0; level <= 45; level++) back &= CarAudio.level(CarAudio.percent(level)) == level;
        for (int pct = 0; pct <= 100; pct++) steps &= CarAudio.level(pct) >= 0 && CarAudio.level(pct) <= 45;
        check("every car level 0-45 survives the trip to CarPlay's percent and back", back);
        check("every percent maps inside 0-45; 0 is 0, 100 is 45, 50 is 23, out of range is clamped",
                steps && CarAudio.level(0) == 0 && CarAudio.level(100) == 45 && CarAudio.level(50) == 23
                        && CarAudio.level(250) == 45 && CarAudio.percent(8) == 18 && CarAudio.percent(99) == 100);

        VehicleState.NOTICES.clear();
        VehicleState vs = new VehicleState(new VsChannel.Log() {
            public void log(String m) {
            }
        });
        long slot0 = VehicleState.historySlot(0), slot1 = VehicleState.historySlot(1);
        check("nothing is sent for a notice while the phone doesn't follow the history", vs.posted().isEmpty());
        VehicleState.post("First", "one", "car", 0);
        List<Object> follow = new ArrayList<Object>(Arrays.asList((Object) (slot0 + 1)));
        List<VehicleState.Out> got = vs.handle(1, request("registerRequest", follow));
        check("following the history brings every slot: the notice in slot 0, shown; slot 1 empty and hidden",
                "First".equals(value(got, slot0 + 1)) && "one".equals(value(got, slot0 + 2)) && "car".equals(value(got, slot0 + 4))
                        && Boolean.FALSE.equals(value(got, slot0 + 7)) && OpackCodec.asLong(value(got, slot0 + 3)) > 1700000000L
                        && "".equals(value(got, slot1 + 1)) && Boolean.TRUE.equals(value(got, slot1 + 7)));
        VehicleState.post("Second", "two", "bolt.car", 9);
        got = vs.posted();
        check("a newer notice takes slot 0 and the older one moves to slot 1; severity is kept within 0-2",
                "Second".equals(value(got, slot0 + 1)) && "First".equals(value(got, slot1 + 1))
                        && Long.valueOf(2).equals(OpackCodec.asLong(value(got, slot0 + 5))));
        Map<Object, Object> dismiss = new LinkedHashMap<Object, Object>();
        dismiss.put(slot1 + 7, true);
        vs.handle(1, request("writeRequest", dismiss));
        got = vs.posted();
        check("a notice the driver dismissed stays hidden, the other stays shown",
                Boolean.TRUE.equals(value(got, slot1 + 7)) && Boolean.FALSE.equals(value(got, slot0 + 7)) && VehicleState.NOTICES.get(1).hidden);
        for (int i = 0; i < 12; i++) VehicleState.post("n" + i, "", "car", 0);
        check("ten notices are kept", VehicleState.NOTICES.size() == VehicleState.HISTORY_SLOTS && "n11".equals(VehicleState.NOTICES.get(0).label));

        final List<String> pressed = new ArrayList<String>();
        VehicleState.mediaControls = new VehicleState.Controls() {
            public void pressed(String name, Object value) {
                pressed.add(name);
            }
        };
        Map<Object, Object> play = new LinkedHashMap<Object, Object>();
        play.put(4360L, null);
        vs.handle(1, request("controlRequest", play));
        check("the phone's play control reaches the media listener", pressed.equals(Arrays.asList("play")));
        VehicleState.mediaControls = null;
        VehicleState.NOTICES.clear();
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
