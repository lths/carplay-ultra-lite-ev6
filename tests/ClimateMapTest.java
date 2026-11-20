package ioniq6.cpclimate;

import java.util.List;
import java.util.Map;

/**
 * Host test of M4's read side: ClimateMap on states logged from the car (2026-11-07), and
 * VehicleState.fromCar telling the phone only what changed.
 * usage: ClimateMapTest
 */
public class ClimateMapTest {
    static int fails;

    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static ClimateMap.Reading car(float on, float auto, float ac, float defrost, float intake, float fan, float mode, float driverF, float passengerRaw) {
        ClimateMap.Reading r = new ClimateMap.Reading();
        r.on = on; r.auto = auto; r.ac = ac; r.defrost = defrost; r.intake = intake;
        r.fan = fan; r.mode = mode; r.driverF = driverF; r.passengerRaw = passengerRaw; r.sync = 0;
        return r;
    }

    static Map<Long, Object> one(long iid, Object v) {
        Map<Long, Object> m = new java.util.LinkedHashMap<Long, Object>();
        m.put(iid, v);
        return m;
    }

    static String cmds(Map<Long, Object> written, Map<Long, Object> now) {
        return ClimateMap.commands(written, now, 2).toString();
    }

    public static void main(String[] a) {
        // 16:52:59: on, fan 3, face+feet, fresh air, 77 °F, zones synced
        Map<Long, Object> v = ClimateMap.toCarPlay(car(1, 0, 0, 0, 2, 3, 2, 77, 0));
        check("on", Boolean.TRUE.equals(v.get(ClimateMap.HVAC_ON)));
        check("raw fan 3 is the panel's 2, on", Long.valueOf(2).equals(v.get(ClimateMap.FAN_LEVEL)) && Boolean.TRUE.equals(v.get(ClimateMap.FAN_ON)));
        check("raw 9 is 8, raw 2 is 1, raw 1 (AUTO) is 1", ClimateMap.fanLevel(9) == 8 && ClimateMap.fanLevel(2) == 1 && ClimateMap.fanLevel(1) == 1);
        check("face+feet: both vents, not auto", Boolean.TRUE.equals(v.get(ClimateMap.UPPER_ON)) && Boolean.TRUE.equals(v.get(ClimateMap.LOWER_ON))
                && Boolean.FALSE.equals(v.get(ClimateMap.UPPER_AUTO)));
        check("fresh air: no recirculation", Boolean.FALSE.equals(v.get(ClimateMap.RECIRCULATION)));
        check("77 °F = 25.0 °C", Double.valueOf(25.0).equals(v.get(ClimateMap.DRIVER_TARGET)));
        boolean roundTrip = true;
        for (int f = 62; f <= 90; f++) {
            double c = ClimateMap.fahrenheit(f);
            if (Math.round(c * 1.8 + 32) != f || c <= ClimateMap.LO || c >= ClimateMap.HI) {
                roundTrip = false;
                System.out.println("  " + f + " F -> " + c + " C -> " + (c * 1.8 + 32) + " F");
            }
        }
        check("62-90 °F each come back as the same °F on the phone, inside LO and HI", roundTrip);
        check("73 °F (the car's 22.5 °C) is sent as 23.0, not 22.5", Double.valueOf(23.0).equals(
                ClimateMap.toCarPlay(car(1, 0, 0, 0, 2, 3, 2, 73, 0)).get(ClimateMap.DRIVER_TARGET)));
        check("passenger zone off (raw 0, not synced): off, no temperature", Boolean.FALSE.equals(v.get(ClimateMap.PASSENGER_ON))
                && Boolean.FALSE.equals(v.get(ClimateMap.ZONES_SYNCED)) && !v.containsKey(ClimateMap.PASSENGER_TARGET));
        ClimateMap.Reading sy = car(1, 0, 0, 0, 2, 3, 2, 72, 16);
        sy.sync = 1;
        v = ClimateMap.toCarPlay(sy);
        check("SYNC on: synced, passenger on at the driver's 72", Boolean.TRUE.equals(v.get(ClimateMap.ZONES_SYNCED))
                && Boolean.TRUE.equals(v.get(ClimateMap.PASSENGER_ON)) && v.get(ClimateMap.PASSENGER_TARGET).equals(ClimateMap.fahrenheit(72)));
        ClimateMap.Reading dual = car(1, 0, 0, 0, 2, 3, 2, 73, 17);
        v = ClimateMap.toCarPlay(dual);
        check("dual, no callback yet: passenger from the raw code (17 = 22.5 °C)", Boolean.TRUE.equals(v.get(ClimateMap.PASSENGER_ON))
                && Double.valueOf(22.5).equals(v.get(ClimateMap.PASSENGER_TARGET)));
        v = ClimateMap.toCarPlay(car(1, 0, 0, 0, 2, 3, 2, 77, 0));
        check("no seat reading: seats left out", !v.containsKey(ClimateMap.DRIVER_SEAT_LEVEL));
        // 16:54:04: off
        v = ClimateMap.toCarPlay(car(2, 0, 0, 0, 2, 0, 0, 77, 0));
        check("off: hvac off, fan 0 and off, passenger off", Boolean.FALSE.equals(v.get(ClimateMap.PASSENGER_ON)) && Boolean.FALSE.equals(v.get(ClimateMap.HVAC_ON)) && Long.valueOf(0).equals(v.get(ClimateMap.FAN_LEVEL))
                && Boolean.FALSE.equals(v.get(ClimateMap.FAN_ON)));
        // 16:55:35: recirculation; 16:55:44 feet; 16:55:52 face
        check("recirculation", Boolean.TRUE.equals(ClimateMap.toCarPlay(car(1, 0, 0, 0, 1, 4, 2, 77, 0)).get(ClimateMap.RECIRCULATION)));
        v = ClimateMap.toCarPlay(car(1, 0, 0, 0, 2, 4, 3, 77, 0));
        check("feet: lower only", Boolean.FALSE.equals(v.get(ClimateMap.UPPER_ON)) && Boolean.TRUE.equals(v.get(ClimateMap.LOWER_ON)));
        v = ClimateMap.toCarPlay(car(1, 0, 0, 0, 2, 4, 1, 77, 0));
        check("face: upper only", Boolean.TRUE.equals(v.get(ClimateMap.UPPER_ON)) && Boolean.FALSE.equals(v.get(ClimateMap.LOWER_ON)));
        // 16:56:47: AUTO
        v = ClimateMap.toCarPlay(car(1, 1, 0, 0, 2, 1, 0, 77, 0));
        check("AUTO: auto, fan auto, vents auto", Boolean.TRUE.equals(v.get(ClimateMap.AUTO_CLIMATE)) && Boolean.TRUE.equals(v.get(ClimateMap.FAN_AUTO))
                && Boolean.TRUE.equals(v.get(ClimateMap.UPPER_AUTO)) && !v.containsKey(ClimateMap.UPPER_ON));
        // 16:57:16: front defrost
        v = ClimateMap.toCarPlay(car(1, 0, 1, 1, 2, 4, 10, 77, 0));
        check("defrost: max defrost, A/C, vents off", Boolean.TRUE.equals(v.get(ClimateMap.MAX_DEFROST)) && Boolean.TRUE.equals(v.get(ClimateMap.COMPRESSOR))
                && Boolean.FALSE.equals(v.get(ClimateMap.UPPER_ON)) && Boolean.FALSE.equals(v.get(ClimateMap.LOWER_ON)));
        // passenger on its own, from the temperature callback
        ClimateMap.Reading r = car(1, 0, 0, 0, 2, 3, 2, 77, 24);
        r.driverC = 25.0f; r.driverState = 0xffff; r.passengerC = 23.5f; r.passengerF = 74; r.passengerState = 0xffff;
        v = ClimateMap.toCarPlay(r);
        check("dual: passenger zone on, 74 °F from the callback", Boolean.FALSE.equals(v.get(ClimateMap.ZONES_SYNCED))
                && Boolean.TRUE.equals(v.get(ClimateMap.PASSENGER_ON)) && Double.valueOf(23.5).equals(v.get(ClimateMap.PASSENGER_TARGET)));
        r.passengerState = 0xff;
        check("dual, callback invalid: passenger from the raw code (24 = 26.0 °C)", Double.valueOf(26.0).equals(ClimateMap.toCarPlay(r).get(ClimateMap.PASSENGER_TARGET)));
        check("LO and HI at the ends", ClimateMap.temperature(15.0) == 16.0 && ClimateMap.temperature(16.4) == 16.5
                && ClimateMap.temperature(33.0) == 32.5 && ClimateMap.temperature(22.26) == 22.5);
        // seats
        r = car(1, 0, 0, 0, 2, 3, 2, 77, 0);
        r.driverSeat = 8; r.passengerSeat = 2;
        v = ClimateMap.toCarPlay(r);
        check("seat heat: driver high (3, on), passenger off", Long.valueOf(3).equals(v.get(ClimateMap.DRIVER_SEAT_LEVEL)) && Boolean.TRUE.equals(v.get(ClimateMap.DRIVER_SEAT_ON))
                && Long.valueOf(0).equals(v.get(ClimateMap.PASSENGER_SEAT_LEVEL)) && Boolean.FALSE.equals(v.get(ClimateMap.PASSENGER_SEAT_ON)));
        r.driverSeat = 0;
        check("an unknown seat state is left out", !ClimateMap.toCarPlay(r).containsKey(ClimateMap.DRIVER_SEAT_LEVEL));
        r.driverSeat = 5;
        check("ventilation clears a previously active seat-heating indicator",
                Long.valueOf(0).equals(ClimateMap.toCarPlay(r).get(ClimateMap.DRIVER_SEAT_LEVEL))
                && Boolean.FALSE.equals(ClimateMap.toCarPlay(r).get(ClimateMap.DRIVER_SEAT_ON)));
        check("real BCM feedback is used when USM reports -1, including off and ventilation",
                ClimateMap.seatFeedback(8, -1) == 8 && ClimateMap.seatFeedback(2, -1) == 2
                && ClimateMap.seatFeedback(4, -1) == 4 && ClimateMap.seatFeedback(-1, 7) == 7
                && ClimateMap.seatFeedback(-1, -1) == -1);
        check("nothing read: nothing told", ClimateMap.toCarPlay(new ClimateMap.Reading()).isEmpty());

        // write side: the commands tested on the car
        Map<Long, Object> now = new java.util.TreeMap<Long, Object>();
        now.put(ClimateMap.FAN_LEVEL, 1L);
        now.put(ClimateMap.UPPER_ON, true);
        now.put(ClimateMap.LOWER_ON, false);
        check("A/C on", cmds(one(ClimateMap.COMPRESSOR, true), now).equals("[vr(9, 1, 0)]"));
        check("fan 4 is raw 5; 0 is off; 9 clamps to 8", cmds(one(ClimateMap.FAN_LEVEL, 4L), now).equals("[vr(5, 5, 0)]")
                && cmds(one(ClimateMap.FAN_LEVEL, 0L), now).equals("[vr(5, 0, 0)]") && cmds(one(ClimateMap.FAN_LEVEL, 9L), now).equals("[vr(5, 9, 0)]"));
        check("climate off is fan 0; on is the last step", cmds(one(ClimateMap.HVAC_ON, false), now).equals("[vr(5, 0, 0)]")
                && cmds(one(ClimateMap.HVAC_ON, true), now).equals("[vr(5, 2, 0)]"));
        check("driver 72 °F (22.0 °C) in °F", cmds(one(ClimateMap.DRIVER_TARGET, 22.0), now).equals("[vr(2, 72, 2)]"));
        check("driver LO and HI clamp to 62 and 90", cmds(one(ClimateMap.DRIVER_TARGET, 16.0), now).equals("[vr(2, 62, 2)]")
                && cmds(one(ClimateMap.DRIVER_TARGET, 32.5), now).equals("[vr(2, 90, 2)]"));
        check("passenger 73 °F by the stock screen's call (raw 17)", cmds(one(ClimateMap.PASSENGER_TARGET, 23.0), now).equals("[front(31, 17)]"));
        check("intake requests distinguish recirculate from learned fresh-air command", cmds(one(ClimateMap.RECIRCULATION, true), now).equals("[intake(recirculate)]")
                && cmds(one(ClimateMap.RECIRCULATION, false), now).equals("[intake(fresh)]"));
        check("unknown intake feedback does not falsely show fresh air", !ClimateMap.toCarPlay(car(1, 0, 0, 0, 0, 4, 2, 77, 0)).containsKey(ClimateMap.RECIRCULATION));
        check("SYNC on 2, off 1", cmds(one(ClimateMap.ZONES_SYNCED, true), now).equals("[vr(22, 2, 0)]")
                && cmds(one(ClimateMap.ZONES_SYNCED, false), now).equals("[vr(22, 1, 0)]"));
        check("AUTO and defrost", cmds(one(ClimateMap.AUTO_CLIMATE, true), now).equals("[vr(8, 1, 0)]")
                && cmds(one(ClimateMap.MAX_DEFROST, false), now).equals("[vr(10, 0, 0)]"));
        check("vents: face only is mode 1", cmds(one(ClimateMap.UPPER_ON, true), now).equals("[vr(6, 1, 0)]"));
        now.put(ClimateMap.LOWER_ON, true);
        check("vents: both is mode 2", cmds(one(ClimateMap.LOWER_ON, true), now).equals("[vr(6, 2, 0)]"));
        now.put(ClimateMap.UPPER_ON, false);
        check("vents: feet only is mode 3", cmds(one(ClimateMap.UPPER_ON, false), now).equals("[vr(6, 3, 0)]"));
        now.put(ClimateMap.LOWER_ON, false);
        check("vents: none on sends nothing", cmds(one(ClimateMap.LOWER_ON, false), now).equals("[]"));
        check("seat levels 1-3 are 6-8, 0 is 2 (both seats)", cmds(one(ClimateMap.DRIVER_SEAT_LEVEL, 3L), now).equals("[seat(213, 8)]")
                && cmds(one(ClimateMap.PASSENGER_SEAT_LEVEL, 1L), now).equals("[seat(214, 6)]")
                && cmds(one(ClimateMap.DRIVER_SEAT_LEVEL, 0L), now).equals("[seat(213, 2)]"));
        check("seat on (level 0) is low; off is 2", cmds(one(ClimateMap.DRIVER_SEAT_ON, true), now).equals("[seat(213, 6)]")
                && cmds(one(ClimateMap.PASSENGER_SEAT_ON, false), now).equals("[seat(214, 2)]"));
        Map<Long, Object> combined = new java.util.LinkedHashMap<Long, Object>();
        combined.put(ClimateMap.PASSENGER_SEAT_ON, false);
        combined.put(ClimateMap.PASSENGER_SEAT_LEVEL, 3L);
        check("an OFF plus stale level 3 sends only OFF", cmds(combined, now).equals("[seat(214, 2)]"));
        combined.clear();
        combined.put(ClimateMap.PASSENGER_SEAT_LEVEL, 3L);
        combined.put(ClimateMap.PASSENGER_SEAT_ON, false);
        check("seat OFF wins independently of incoming map order", cmds(combined, now).equals("[seat(214, 2)]"));
        check("MAX A/C on: A/C, recirculation, fan 8", cmds(one(ClimateMap.MAX_AC, true), now).equals("[vr(9, 1, 0), vr(11, 1, 0), vr(5, 9, 0)]"));
        check("°C car: driver 22.5 °C by the stock screen's call (raw 17), passenger 21.0 (raw 14)",
                ClimateMap.commands(one(ClimateMap.DRIVER_TARGET, 22.5), now, 1).toString().equals("[front(28, 17)]")
                && ClimateMap.commands(one(ClimateMap.PASSENGER_TARGET, 21.0), now, 1).toString().equals("[front(29, 14)]"));
        check("°C car: LO and HI clamp to 17 and 32 °C (raw 6 and 36)", ClimateMap.celsiusRaw(16.0) == 6 && ClimateMap.celsiusRaw(32.5) == 36);
        ClimateMap.Reading cr = car(1, 0, 0, 0, 2, 3, 2, 22.5f, 18);
        cr.unit = 1;
        check("°C car: flag 2 read as °C (22.5)", Double.valueOf(22.5).equals(ClimateMap.toCarPlay(cr).get(ClimateMap.DRIVER_TARGET)));
        now.put(ClimateMap.HVAC_ON, true);
        check("passenger zone off is Driver Only on (24, 1); on is (24, 0)", cmds(one(ClimateMap.PASSENGER_ON, false), now).equals("[front(24, 1, 0)]".replace(", 0)", ")"))
                && cmds(one(ClimateMap.PASSENGER_ON, true), now).equals("[front(24, 0)]"));
        check("passenger seat power (CAFZoneOn 567): off is Driver Only on, on is Driver Only off",
                cmds(one(ClimateMap.PASSENGER_ZONE_ON, false), now).equals("[front(24, 1)]")
                        && cmds(one(ClimateMap.PASSENGER_ZONE_ON, true), now).equals("[front(24, 0)]"));
        check("driver seat power (CAFZoneOn 564) is the whole system: off fan 0, on fan step 1",
                cmds(one(ClimateMap.DRIVER_ZONE_ON, false), now).equals("[vr(5, 0, 0)]")
                        && cmds(one(ClimateMap.DRIVER_ZONE_ON, true), now).equals("[vr(5, 2, 0)]"));
        now.put(ClimateMap.HVAC_ON, false);
        check("passenger seat power on with the system off starts the system, then Driver Only off",
                cmds(one(ClimateMap.PASSENGER_ZONE_ON, true), now).equals("[vr(5, 2, 0), front(24, 0)]")
                        && cmds(one(ClimateMap.PASSENGER_ON, true), now).equals("[vr(5, 2, 0), front(24, 0)]"));
        Map<Long, Object> both = new java.util.LinkedHashMap<Long, Object>();
        both.put(ClimateMap.HVAC_ON, true);
        both.put(ClimateMap.PASSENGER_ZONE_ON, true);
        Map<Long, Object> after = new java.util.TreeMap<Long, Object>(now);
        after.putAll(both);
        check("hvacOn and passenger seat power on in one write: one fan command", cmds(both, after).equals("[vr(5, 2, 0), front(24, 0)]"));
        check("passenger seat power off with the system off: just Driver Only on", cmds(one(ClimateMap.PASSENGER_ZONE_ON, false), now).equals("[front(24, 1)]"));
        Map<Long, Object> zoneOnly = new java.util.TreeMap<Long, Object>(now);
        zoneOnly.remove(ClimateMap.HVAC_ON);
        zoneOnly.put(ClimateMap.DRIVER_ZONE_ON, true);
        check("no hvacOn in the description: the driver's seat power says the system is on, so no fan command",
                cmds(one(ClimateMap.PASSENGER_ZONE_ON, true), zoneOnly).equals("[front(24, 0)]"));
        now.put(ClimateMap.HVAC_ON, true);
        ClimateMap.Reading dO = car(1, 0, 0, 0, 2, 3, 2, 73, 0);
        dO.driverOnly = 1;
        v = ClimateMap.toCarPlay(dO);
        check("Driver Only read: passenger zone off, no passenger temperature", Boolean.FALSE.equals(v.get(ClimateMap.PASSENGER_ON))
                && !v.containsKey(ClimateMap.PASSENGER_TARGET));
        check("Driver Only read: passenger seat power off, driver seat power on", Boolean.FALSE.equals(v.get(ClimateMap.PASSENGER_ZONE_ON))
                && Boolean.TRUE.equals(v.get(ClimateMap.DRIVER_ZONE_ON)));
        ClimateMap.Reading dz = car(1, 0, 0, 0, 2, 3, 2, 73, 25);
        dz.driverOnly = 0;
        v = ClimateMap.toCarPlay(dz);
        check("Driver Only off: passenger zone on at its own raw 25 (26.5 °C)", Boolean.TRUE.equals(v.get(ClimateMap.PASSENGER_ON))
                && Double.valueOf(26.5).equals(v.get(ClimateMap.PASSENGER_TARGET)));
        check("Driver Only off: both seat powers on", Boolean.TRUE.equals(v.get(ClimateMap.PASSENGER_ZONE_ON))
                && Boolean.TRUE.equals(v.get(ClimateMap.DRIVER_ZONE_ON)));
        ClimateMap.Reading zOff = car(2, 0, 0, 0, 2, 0, 0, 73, 0);
        zOff.driverOnly = 0;
        v = ClimateMap.toCarPlay(zOff);
        check("system off: both seat powers off", Boolean.FALSE.equals(v.get(ClimateMap.PASSENGER_ZONE_ON))
                && Boolean.FALSE.equals(v.get(ClimateMap.DRIVER_ZONE_ON)));
        check("front window heating is the front defrost (no heated windshield): on vr(10, 1), off vr(10, 0)",
                cmds(one(ClimateMap.FRONT_WINDOW_HEATING, 1L), now).equals("[vr(10, 1, 0)]")
                        && cmds(one(ClimateMap.FRONT_WINDOW_HEATING, 0L), now).equals("[vr(10, 0, 0)]"));
        check("rear window heating asks for the rear defroster on/off (CarClimate presses the toggle if needed)",
                cmds(one(ClimateMap.REAR_WINDOW_HEATING, 1L), now).equals("[rear(1)]")
                        && cmds(one(ClimateMap.REAR_WINDOW_HEATING, 0L), now).equals("[rear(0)]"));
        ClimateMap.Reading df = car(1, 0, 1, 1, 2, 3, 10, 73, 0);
        df.rearDefog = 1;
        v = ClimateMap.toCarPlay(df);
        check("defrost read: front window heating 1 with maxDefrost, rear window heating from BCMService's state",
                Long.valueOf(1).equals(v.get(ClimateMap.FRONT_WINDOW_HEATING)) && Boolean.TRUE.equals(v.get(ClimateMap.MAX_DEFROST))
                        && Long.valueOf(1).equals(v.get(ClimateMap.REAR_WINDOW_HEATING)));
        df.rearDefog = 0;
        df.defrost = 0;
        v = ClimateMap.toCarPlay(df);
        check("defrost read: both off", Long.valueOf(0).equals(v.get(ClimateMap.FRONT_WINDOW_HEATING))
                && Long.valueOf(0).equals(v.get(ClimateMap.REAR_WINDOW_HEATING)));
        df.rearDefog = -1;
        check("rear state not known (setting off): rear window heating left alone", !ClimateMap.toCarPlay(df).containsKey(ClimateMap.REAR_WINDOW_HEATING));
        check("a value of the wrong type: nothing", cmds(one(ClimateMap.FAN_LEVEL, "4"), now).equals("[]"));

        // VehicleState: only changes of followed values reach the phone, on the high lane
        VehicleState vs = new VehicleState(new VsChannel.Log() {
            public void log(String m) {
                System.out.println("  log: " + m);
            }
        });
        vs.handle(VehicleState.HIGH, VehicleState.wrap(VehicleState.dict("command", "registerRequest", "transactionID", 1L,
                "values", java.util.Arrays.asList((Object) 520L, 525L, 514L))));
        Map<Long, Object> first = ClimateMap.toCarPlay(car(1, 0, 0, 0, 2, 3, 2, 77, 0));
        List<VehicleState.Out> outs = vs.fromCar(first);
        check("first reading: one updateNotify on the high lane", outs.size() == 1 && outs.get(0).lane == VehicleState.HIGH);
        String shown = outs.isEmpty() ? "" : Opack.show((byte[]) VehicleState.get(OpackCodec.decode(outs.get(0).message), "pluginData"));
        System.out.println("  " + shown);
        check("it carries the driver's 25.0 (followed) but not recirculation (not followed)", shown.contains("520: 25.0") && !shown.contains("528"));
        check("the same reading again: nothing", vs.fromCar(first).isEmpty());
        check("fan 3 -> 4: one update", vs.fromCar(ClimateMap.toCarPlay(car(1, 0, 0, 0, 2, 4, 2, 77, 0))).size() == 1);

        // with a car attached: writes go to it (no LIVI rules), and the phone's value holds while the car catches up
        final List<Map<Long, Object>> sent = new java.util.ArrayList<Map<Long, Object>>();
        vs.car = new VehicleState.Car() {
            public void phoneWrote(Map<Long, Object> written, Map<Long, Object> state) {
                sent.add(written);
            }
        };
        vs.handle(VehicleState.HIGH, VehicleState.wrap(VehicleState.dict("command", "writeRequest", "transactionID", 2L,
                "values", VehicleState.dict(525L, 6L))));
        check("the write reached the car", sent.size() == 1 && Long.valueOf(6).equals(sent.get(0).get(525L)));
        check("the car's old fan (raw 4 = 3) doesn't undo it while held", vs.fromCar(ClimateMap.toCarPlay(car(1, 0, 0, 0, 2, 4, 2, 77, 0))).isEmpty()
                && Long.valueOf(6).equals(vs.values.get(525L)));
        check("the car catching up (raw 7 = 6) ends the hold quietly", vs.fromCar(ClimateMap.toCarPlay(car(1, 0, 0, 0, 2, 7, 2, 77, 0))).isEmpty());
        List<VehicleState.Out> later = vs.fromCar(ClimateMap.toCarPlay(car(1, 0, 0, 0, 2, 3, 2, 77, 0)));
        check("after that, a change on the panel shows again", later.size() == 1 && Long.valueOf(2).equals(vs.values.get(525L)));
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
