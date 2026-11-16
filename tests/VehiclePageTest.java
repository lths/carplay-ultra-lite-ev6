package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host test of the car's page in CarPlay's Vehicle app (VehiclePage in a VehicleState session): the automaker
 * settings accessory describes the page with Apple's setting types, the phone gets the car's readings when they
 * change, a pressed button reaches the listener once and goes back to Ready, a switch reaches the listener, the
 * page's values never reach the climate (VehicleState.Car), and without a page the description is LIVI's.
 */
public class VehiclePageTest {
    static int fails;

    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static final List<String> did = new ArrayList<String>();

    static VehiclePage page() {
        VehiclePage p = new VehiclePage()
                .sectionName("Vehicle", "Ioniq 6")
                .button("Vehicle", "open_home", "Car home screen", "house")
                .line("Vehicle", "battery", "Battery", "battery.100")
                .line("Vehicle", "range", "Range", "gauge")
                .toggle("Ambient light", "lights", "Lights follow the music", "light.max", true)
                .button("Head unit", "open_hmgapi", "HMG-API", "puzzlepiece.extension");
        p.listener = new VehiclePage.Listener() {
            public void pressed(String id) {
                did.add("pressed " + id);
            }

            public void switched(String id, boolean on) {
                did.add("switched " + id + " " + on);
            }
        };
        return p;
    }

    static VehicleState session() {
        return new VehicleState(new VsChannel.Log() {
            public void log(String m) {
            }
        });
    }

    static byte[] ask(String command, Object values) {
        return VehicleState.wrap(VehicleState.dict("command", command, "transactionID", 7L, "values", values));
    }

    /** an answer's inner message */
    static Object inner(VehicleState.Out o) {
        return OpackCodec.decode((byte[]) VehicleState.get(OpackCodec.decode(o.message), "pluginData"));
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> accessory(VehicleState vs, long iid) {
        Object config = inner(vs.handle(0, ask("configRequest", null)).get(0));
        List<Object> all = (List<Object>) VehicleState.get(VehicleState.get(config, "values"), "accessories");
        for (Object a : all) if (Long.valueOf(iid).equals(OpackCodec.asLong(((Map<Object, Object>) a).get("iid")))) return (Map<Object, Object>) a;
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> characteristic(Map<Object, Object> accessory, long iid) {
        for (Object s : (List<Object>) accessory.get("services"))
            for (Object c : (List<Object>) ((Map<Object, Object>) s).get("characteristics"))
                if (Long.valueOf(iid).equals(OpackCodec.asLong(((Map<Object, Object>) c).get("iid")))) return (Map<Object, Object>) c;
        return null;
    }

    static Object value(Object answer, long iid) {
        Object values = VehicleState.get(answer, "values");
        if (!(values instanceof Map)) return null;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) values).entrySet())
            if (Long.valueOf(iid).equals(OpackCodec.asLong(e.getKey()))) return e.getValue();
        return null;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] a) {
        VehiclePage complete = CarVehicle.describe(new VehiclePage(), "EV6", null, false);
        java.util.Set<String> minimalApps = new java.util.HashSet<String>();
        minimalApps.add("open_home");
        VehiclePage minimal = CarVehicle.describe(new VehiclePage(), "EV6", minimalApps, false);
        boolean sameRows = true;
        for (VehiclePage.Item item : complete.items())
            sameRows &= minimal.item(item.id) != null && minimal.item(item.id).iid == item.iid;
        check("missing optional apps never renumber Vehicle-page readings or actions", sameRows);
        check("legacy battery, range, odometer and radio retain the native description's identities",
                minimal.item("battery").valueIid() == 20595 && minimal.item("range").valueIid() == 20627
                && minimal.item("odometer").valueIid() == 20643 && minimal.item("open_radio").valueIid() == 20723);
        java.util.Set<Long> pinned = new java.util.HashSet<Long>();
        for (VehiclePage.Item item : complete.items()) pinned.add(item.iid);
        check("comfort rows have unique identities outside the legacy rows", pinned.size() == complete.items().size()
                && complete.item("wheel_heat").iid >= VehiclePage.BASE + 0x1100);
        // without a page: LIVI's two version lines, untouched
        VehicleState.page = null;
        Map<Object, Object> livi = accessory(session(), VehiclePage.ACCESSORY);
        check("no page set: the accessory keeps LIVI's services (first is the version line 1281)",
                Long.valueOf(1281).equals(OpackCodec.asLong(((Map<Object, Object>) ((List<Object>) livi.get("services")).get(0)).get("iid"))));

        VehiclePage p = page();
        VehicleState.page = p;
        VehicleState vs = session();
        Map<Object, Object> acc = accessory(vs, VehiclePage.ACCESSORY);
        List<Object> services = (List<Object>) acc.get("services");
        List<Long> kinds = new ArrayList<Long>();
        for (Object s : services) kinds.add(OpackCodec.asLong(((Map<Object, Object>) s).get("type")));
        check("the page is 3 sections, then a button, 2 lines, a switch and a button: " + kinds,
                kinds.equals(Arrays.asList(VehiclePage.SECTION, VehiclePage.SECTION, VehiclePage.SECTION, VehiclePage.BUTTON_SETTING,
                        VehiclePage.STATIC_SETTING, VehiclePage.STATIC_SETTING, VehiclePage.BOOLEAN_SETTING, VehiclePage.BUTTON_SETTING)));
        check("the accessory is still the automaker settings one (0x5000001)", Long.valueOf(0x5000001L).equals(OpackCodec.asLong(acc.get("type"))));

        long battery = p.item("battery").valueIid(), home = p.item("open_home").valueIid(), lights = p.item("lights").valueIid();
        Map<Object, Object> line = characteristic(acc, battery), button = characteristic(acc, home), on = characteristic(acc, lights);
        check("a line's value is a string the car changes and the phone can't write",
                Long.valueOf(0x30000017L).equals(OpackCodec.asLong(line.get("type"))) && Long.valueOf(10).equals(OpackCodec.asLong(line.get("format")))
                        && Boolean.TRUE.equals(line.get("mutable")) && Boolean.FALSE.equals(line.get("writable")) && VehiclePage.NONE.equals(line.get("initialValue")));
        check("a button's action is a 0-1 number both sides write, Ready at first",
                Long.valueOf(0x36000010L).equals(OpackCodec.asLong(button.get("type"))) && Boolean.TRUE.equals(button.get("writable"))
                        && Long.valueOf(1).equals(OpackCodec.asLong(button.get("maximumValue"))) && Long.valueOf(0).equals(OpackCodec.asLong(button.get("initialValue"))));
        check("the switch is a bool both sides write, on at first",
                Long.valueOf(0x30000002L).equals(OpackCodec.asLong(on.get("type"))) && Long.valueOf(0).equals(OpackCodec.asLong(on.get("format")))
                        && Boolean.TRUE.equals(on.get("writable")) && Boolean.TRUE.equals(on.get("initialValue")));
        Map<Object, Object> label = characteristic(acc, p.item("battery").iid + 2), section = characteristic(acc, p.item("battery").iid + 7);
        check("the line is labelled Battery, in the section Vehicle, and the section exists",
                "Battery".equals(label.get("initialValue")) && "Vehicle".equals(section.get("initialValue"))
                        && "Vehicle".equals(characteristic(acc, VehiclePage.BASE + 1).get("initialValue")));
        check("the car's section shows the car's model and keeps its id, which its items point at",
                "Ioniq 6".equals(characteristic(acc, VehiclePage.BASE + 3).get("initialValue"))
                        && "Vehicle".equals(characteristic(acc, VehiclePage.BASE + 1).get("initialValue"))
                        && "Ambient light".equals(characteristic(acc, VehiclePage.BASE + VehiclePage.STEP + 3).get("initialValue")));
        check("the model comes from the product code: CEEV Ioniq 6, NEEV23 Ioniq 5, CVEV EV6, else Vehicle",
                "Ioniq 6".equals(CarVehicle.model("CEEV.USA.0000.V015.012.260507")) && "Ioniq 5".equals(CarVehicle.model("NEEV23.USA.x"))
                        && "EV6".equals(CarVehicle.model("CVEV.CAN.x")) && "Vehicle".equals(CarVehicle.model("")) && "Vehicle".equals(CarVehicle.model(null)));
        java.util.Set<Long> iids = new java.util.HashSet<Long>();
        int count = 0;
        for (Object s : services) {
            count++;
            iids.add(OpackCodec.asLong(((Map<Object, Object>) s).get("iid")));
            for (Object c : (List<Object>) ((Map<Object, Object>) s).get("characteristics")) {
                count++;
                iids.add(OpackCodec.asLong(((Map<Object, Object>) c).get("iid")));
            }
        }
        check("every iid on the page is its own (" + count + ") and clear of LIVI's", iids.size() == count && java.util.Collections.min(iids) >= VehiclePage.BASE);

        // the phone follows the page
        Object registered = inner(vs.handle(0, ask("registerRequest", Arrays.asList(battery, home, lights))).get(0));
        check("following the page gives its values and no errors",
                VehiclePage.NONE.equals(value(registered, battery)) && Long.valueOf(0).equals(OpackCodec.asLong(value(registered, home)))
                        && Boolean.TRUE.equals(value(registered, lights)) && VehicleState.get(registered, "errors") == null);

        // the car's readings
        Map<String, Object> fresh = new LinkedHashMap<String, Object>();
        fresh.put("battery", VehiclePage.percent(81.5f));
        fresh.put("range", VehiclePage.distance(402, true));
        List<VehicleState.Out> told = vs.fromCar(p.update(fresh));
        check("a new battery level goes to the phone as one updateNotify on the high lane (range isn't followed)",
                told.size() == 1 && told.get(0).lane == VehicleState.HIGH && "updateNotify".equals(VehicleState.get(inner(told.get(0)), "command"))
                        && "81 %".equals(value(inner(told.get(0)), battery)) && value(inner(told.get(0)), p.item("range").valueIid()) == null);
        check("the same reading again tells the phone nothing", p.update(fresh).isEmpty());

        // a button
        did.clear();
        Map<Object, Object> press = new LinkedHashMap<Object, Object>();
        press.put(home, VehiclePage.PERFORM);
        List<VehicleState.Out> pressed = vs.handle(0, ask("writeRequest", press));
        Object back = pressed.size() > 1 ? inner(pressed.get(1)) : null;
        check("a press is accepted, reaches the listener once, and the button goes back to Ready",
                did.equals(Arrays.asList("pressed open_home")) && "writeResponse".equals(VehicleState.get(inner(pressed.get(0)), "command"))
                        && Long.valueOf(0).equals(OpackCodec.asLong(value(back, home))) && Long.valueOf(0).equals(OpackCodec.asLong(vs.values.get(home))));
        did.clear();
        press.put(home, VehiclePage.READY);
        vs.handle(0, ask("writeRequest", press));
        check("writing Ready presses nothing", did.isEmpty());

        // the switch, with a climate value in the same write, and a car attached
        final List<Map<Long, Object>> toCar = new ArrayList<Map<Long, Object>>();
        vs.car = new VehicleState.Car() {
            public void phoneWrote(Map<Long, Object> written, Map<Long, Object> now) {
                toCar.add(written);
            }
        };
        did.clear();
        Map<Object, Object> both = new LinkedHashMap<Object, Object>();
        both.put(lights, Boolean.FALSE);
        both.put(VehicleState.FAN_LEVEL, 3L);
        vs.handle(0, ask("writeRequest", both));
        check("the switch reaches the listener, and only the fan level reaches the climate",
                did.equals(Arrays.asList("switched lights false")) && toCar.size() == 1 && toCar.get(0).keySet().equals(java.util.Collections.singleton(VehicleState.FAN_LEVEL)));
        fresh.clear();
        fresh.put("lights", Boolean.TRUE);
        List<VehicleState.Out> lit = vs.fromCar(p.update(fresh));
        check("the car turning the lights on is told at once (no hold on a page value)", lit.size() == 1 && Boolean.TRUE.equals(value(inner(lit.get(0)), lights)));

        // what the phone may not write
        Map<Object, Object> bad = new LinkedHashMap<Object, Object>();
        bad.put(battery, "100 %");
        Object refused = inner(vs.handle(0, ask("writeRequest", bad)).get(0));
        Object errors = VehicleState.get(refused, "errors");
        Object code = null;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) errors).entrySet()) code = e.getValue();
        check("the phone can't write a line", Long.valueOf(VehicleState.NO_VALUE).equals(OpackCodec.asLong(code)) && "81 %".equals(vs.values.get(battery)));

        // a new session starts from what the car last said
        Object again = inner(session().handle(0, ask("registerRequest", Arrays.asList(battery, lights))).get(0));
        check("a new session starts with the last readings", "81 %".equals(value(again, battery)) && Boolean.TRUE.equals(value(again, lights)));

        // how readings are shown
        check("battery: 81.5 is 81 % (rounded down), and 0, 255 and NaN are a dash",
                "81 %".equals(VehiclePage.percent(81.5f)) && "100 %".equals(VehiclePage.percent(100.4f)) && VehiclePage.NONE.equals(VehiclePage.percent(0f))
                        && VehiclePage.NONE.equals(VehiclePage.percent(255f)) && VehiclePage.NONE.equals(VehiclePage.percent(Float.NaN)));
        check("distance: 402 km is 249 mi (rounded down) or 402 km, 20000 km is 12,427 mi, -1 is a dash",
                "249 mi".equals(VehiclePage.distance(402, true)) && "10,759 mi".equals(VehiclePage.distance(17315.8, true)) && "402 km".equals(VehiclePage.distance(402, false))
                        && "12,427 mi".equals(VehiclePage.distance(20000, true)) && VehiclePage.NONE.equals(VehiclePage.distance(-1, true)));

        // tiles: one per section, flagged for the Vehicle app's first screen, its rows as children
        for (String mode : new String[] { "dict", "array" }) {
            VehiclePage.tiles = mode;
            VehiclePage tp = page().tile("Vehicle", "car");
            VehicleState.page = tp;
            Map<Object, Object> ta = accessory(session(), VehiclePage.ACCESSORY);
            List<Object> ts = (List<Object>) ta.get("services");
            long tile = VehiclePage.BASE + 3L * VehiclePage.STEP;
            Object prominence = characteristic(ta, tile + 8).get("initialValue");
            Object first = "array".equals(mode) ? ((List<Object>) prominence).get(0) : prominence;
            check(mode + ": 3 sections, 3 tiles, 5 rows; the car's tile is a static setting named Ioniq 6 with the symbol car",
                    ts.size() == 11 && Long.valueOf(VehiclePage.STATIC_SETTING).equals(OpackCodec.asLong(((Map<Object, Object>) ts.get(3)).get("type")))
                            && "Ioniq 6".equals(characteristic(ta, tile + 2).get("initialValue")) && "car".equals(characteristic(ta, tile + 5).get("initialValue"))
                            && "tile.Vehicle".equals(characteristic(ta, tile + 1).get("initialValue")));
            check(mode + ": its prominence is appHomeTile (level 1), sent as " + ("array".equals(mode) ? "an array of one dictionary" : "a dictionary"),
                    Long.valueOf(VehiclePage.T_PROMINENCE).equals(OpackCodec.asLong(characteristic(ta, tile + 8).get("type")))
                            && Long.valueOf("array".equals(mode) ? 13 : 12).equals(OpackCodec.asLong(characteristic(ta, tile + 8).get("format")))
                            && Long.valueOf(1).equals(OpackCodec.asLong(((Map<Object, Object>) first).get("prominenceLevel")))
                            && "Ioniq 6".equals(((Map<Object, Object>) first).get("userVisibleLabel")));
            check(mode + ": its children are the car's rows, Car home screen first",
                    Arrays.asList("open_home", "battery", "range").equals(characteristic(ta, tile + 9).get("initialValue"))
                            && Arrays.asList("lights").equals(characteristic(ta, tile + VehiclePage.STEP + 9).get("initialValue")));
            java.util.Set<Long> all = new java.util.HashSet<Long>();
            int n = 0;
            for (Object sv : ts) {
                n++;
                all.add(OpackCodec.asLong(((Map<Object, Object>) sv).get("iid")));
                for (Object ch : (List<Object>) ((Map<Object, Object>) sv).get("characteristics")) {
                    n++;
                    all.add(OpackCodec.asLong(((Map<Object, Object>) ch).get("iid")));
                }
            }
            check(mode + ": every iid is still its own (" + n + ")", all.size() == n);
        }
        VehiclePage.tiles = "off";

        // the preconditioning switch (CarVehicle): what was pressed holds briefly, then Launchpad's request or the car
        check("preconditioning: just pressed on shows on, even before Launchpad or the car answer",
                CarVehicle.preconditionShown(0, false, 1000, true));
        check("preconditioning: just pressed off shows off, even while the car still conditions",
                !CarVehicle.preconditionShown(1, true, 1000, false));
        check("preconditioning: after the hold, a pending Launchpad request shows on",
                CarVehicle.preconditionShown(0, true, CarVehicle.PRECOND_HOLD_MS, true));
        check("preconditioning: after the hold, Launchpad declined (warm battery, not READY) shows off",
                !CarVehicle.preconditionShown(0, false, CarVehicle.PRECOND_HOLD_MS + 1, true));
        check("preconditioning: the car conditioning on its own (a navigation route) shows on",
                CarVehicle.preconditionShown(1, false, Long.MAX_VALUE, false));
        check("preconditioning: an unknown indicator (-1) and no request shows off",
                !CarVehicle.preconditionShown(-1, false, Long.MAX_VALUE, false));

        VehicleState.page = null;
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
