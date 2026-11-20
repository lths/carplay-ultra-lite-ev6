package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Ioniq 6's climate as CarPlay's vehicle state values (M4, read side). The inputs are what
 * CarClimate reads: DATCService's voice-control status flags, its temperature callback and
 * USMService's seat heat states. Values that weren't read (NaN, -1) are left out, so the phone
 * keeps what it had. Decoded from the car on 2026-11-07 (physical buttons pressed while the
 * flags were logged):
 *
 *   flag 12 on/off 1/2, 8 AUTO 0/1, 9 A/C 0/1, 10 front defrost 0/1, 11 intake 1 recirculate
 *   2 fresh, 5 fan raw 2-9 = the panel's 1-8 (0 off), 6 vent mode 1 face, 2 face+feet, 3 feet,
 *   4 feet+windshield, 0 under AUTO, 10 under defrost; 2 driver set temperature in the car's
 *   unit (getTemperatureUnit 1 °C, 2 °F; only °F seen so far); 22 SYNC
 *   1 on; 29 passenger raw code ((°C - 14) * 2; 0 while driver only is on).
 *   getDriverOnlyValue: 1 driver only (the passenger zone off), 0 off. CarPlay's passenger zone
 *   on/off is the car's Driver Only, written with setFrontClimateControl(24, 1 on / 0 off).
 *   The car keeps °F and °C setpoints on their own scales (73 °F is its 22.5 °C, which the
 *   phone would show as 72 °F), so the °F values are used and rounded to the half degree the
 *   phone turns back into the same °F. Seat heat state 2 off, 6/7/8 low/mid/high (as the Ioniq 5 logged; 3 levels here too).
 */
final class ClimateMap {
    static final long HVAC_ON = 514, COMPRESSOR = 515, MAX_DEFROST = 517, FAN_ON = 524, FAN_LEVEL = 525;
    static final long FAN_AUTO = 526, RECIRCULATION = 528, AUTO_CLIMATE = 530, ZONES_SYNCED = 538;
    static final long DRIVER_ON = 519, DRIVER_TARGET = 520, PASSENGER_ON = 533, PASSENGER_TARGET = 534;
    /** Apple's per-seat power (CAFZoneOn, added in data/vsdump-src climate.rs): the Climate app's seat power buttons */
    static final long DRIVER_ZONE_ON = 564, PASSENGER_ZONE_ON = 567;
    /** CarPlay's window heating levels (0/1); the Ioniq 6 has no heated windshield, so the front one is its defrost */
    static final long FRONT_WINDOW_HEATING = 541, REAR_WINDOW_HEATING = 545;
    static final long UPPER_ON = 549, UPPER_AUTO = 550, LOWER_ON = 559, LOWER_AUTO = 560;
    static final long DRIVER_SEAT_LEVEL = 1026, DRIVER_SEAT_ON = 1027, PASSENGER_SEAT_LEVEL = 1030, PASSENGER_SEAT_ON = 1031;
    /** CarPlay's range: its ends show as LO and HI, as the car's do below 62 °F and above 90 °F */
    static final double LO = 16.0, HI = 32.5;
    static final int TEMP_NORMAL = 0xffff;

    /** one reading of the car */
    static final class Reading {
        float on = Float.NaN, auto = Float.NaN, ac = Float.NaN, defrost = Float.NaN, intake = Float.NaN;
        float fan = Float.NaN, mode = Float.NaN, driverF = Float.NaN, passengerRaw = Float.NaN, sync = Float.NaN;
        /** from the temperature callback: °C, °F and the display state, or NaN before the first one */
        float driverC = Float.NaN, passengerC = Float.NaN, passengerF = Float.NaN;
        int driverState = -1, passengerState = -1;
        int driverSeat = -1, passengerSeat = -1;
        int wheel = -1;
        /** getDriverOnlyValue: 1 driver only, 0 off, -1 not read */
        int driverOnly = -1;
        /** the car's temperature unit: 1 °C, 2 °F, -1 not read */
        int unit = -1;
        /** BCMService's rear defroster (mDefoggerState): 1 on, 0 off, -1 not known (or the setting is off) */
        int rearDefog = -1;
    }

    static final int CELSIUS = 1;
    /** Last valid setpoints while HVAC was on; the off-state 17 C reading is not a setpoint. */
    static Double lastDriver, lastPassenger;

    static Map<Long, Object> toCarPlay(Reading r) {
        Map<Long, Object> v = new LinkedHashMap<Long, Object>();
        if (known(r.on)) {
            boolean on = r.on == 1;
            v.put(HVAC_ON, on);
            v.put(DRIVER_ON, on);
            v.put(DRIVER_ZONE_ON, on);
            if (known(r.fan)) {
                long level = on ? fanLevel(r.fan) : 0;
                v.put(FAN_LEVEL, level);
                v.put(FAN_ON, level > 0);
            }
        }
        if (known(r.auto)) {
            v.put(AUTO_CLIMATE, r.auto == 1);
            v.put(FAN_AUTO, r.auto == 1);
        }
        if (known(r.ac)) v.put(COMPRESSOR, r.ac == 1);
        if (known(r.defrost)) {
            v.put(MAX_DEFROST, r.defrost == 1);
            v.put(FRONT_WINDOW_HEATING, r.defrost == 1 ? 1L : 0L);
        }
        if (r.rearDefog >= 0) v.put(REAR_WINDOW_HEATING, r.rearDefog == 1 ? 1L : 0L);
        if (r.intake == 1 || r.intake == 2) v.put(RECIRCULATION, r.intake == 1);
        if (known(r.mode)) vents(v, (int) r.mode);
        boolean celsius = r.unit == CELSIUS;
        boolean off = known(r.on) && r.on != 1;
        Double driver = off ? lastDriver : known(r.driverF) ? Double.valueOf(celsius ? temperature(r.driverF) : fahrenheit(r.driverF))
                : r.driverState == TEMP_NORMAL && known(r.driverC) ? Double.valueOf(temperature(r.driverC)) : null;
        if (r.on == 1 && driver != null) lastDriver = driver;
        if (driver != null) v.put(DRIVER_TARGET, driver);
        if (known(r.sync) && known(r.passengerRaw)) {
            boolean synced = r.sync == 1;
            boolean zone = r.driverOnly >= 0 ? r.driverOnly == 0 : synced || r.passengerRaw != 0;
            v.put(ZONES_SYNCED, synced);
            if (known(r.on)) {
                v.put(PASSENGER_ON, r.on == 1 && zone);
                v.put(PASSENGER_ZONE_ON, r.on == 1 && zone);
            }
            Double passenger = synced ? driver
                    : off ? lastPassenger
                    : !zone ? null
                    : r.passengerState == TEMP_NORMAL && celsius && known(r.passengerC) ? Double.valueOf(temperature(r.passengerC))
                    : r.passengerState == TEMP_NORMAL && !celsius && known(r.passengerF) ? Double.valueOf(fahrenheit(r.passengerF))
                    : Double.valueOf(temperature(r.passengerRaw / 2.0 + 14));
            if (passenger != null) v.put(PASSENGER_TARGET, passenger);
            if (r.on == 1 && zone && passenger != null) lastPassenger = passenger;
        }
        seat(v, r.driverSeat, DRIVER_SEAT_LEVEL, DRIVER_SEAT_ON);
        seat(v, r.passengerSeat, PASSENGER_SEAT_LEVEL, PASSENGER_SEAT_ON);
        if (VehicleDescription.wheelHeat && (r.wheel == 0 || r.wheel == 1)) v.put(VehicleDescription.WHEEL_LEVEL, (long) r.wheel);
        return v;
    }

    /** the panel's fan step for a raw blower value (raw 1 shows as 1, as under AUTO) */
    static long fanLevel(float raw) {
        return raw <= 0 ? 0 : Math.max(1, (long) raw - 1);
    }

    /** the car's vent mode as CarPlay's two vents; under AUTO they follow it */
    static void vents(Map<Long, Object> v, int mode) {
        boolean upper, lower;
        switch (mode) {
            case 0:
                v.put(UPPER_AUTO, true);
                v.put(LOWER_AUTO, true);
                return;
            case 1: upper = true; lower = false; break;
            case 2: upper = true; lower = true; break;
            case 3:
            case 4: upper = false; lower = true; break;
            case 10: upper = false; lower = false; break;
            default: return;
        }
        v.put(UPPER_ON, upper);
        v.put(LOWER_ON, lower);
        v.put(UPPER_AUTO, false);
        v.put(LOWER_AUTO, false);
    }

    static void seat(Map<Long, Object> v, int state, long levelIid, long onIid) {
        long level;
        switch (state) {
            case 2: level = 0; break;
            case 3: case 4: case 5: level = VehicleDescription.seatCooling ? coolingLevel(state) : 0; break;
            case 6: level = 1; break;
            case 7: level = 2; break;
            case 8: level = 3; break;
            default: return;
        }
        v.put(levelIid, level);
        v.put(onIid, level != 0);
    }

    /** v1.1.15's four-dot cooling range: the car still has three physical ventilation levels. */
    static long coolingLevel(int state) { return state == 5 ? -4 : state == 4 ? -2 : -1; }
    static int coolingState(long level) { return level <= -4 ? 5 : level <= -2 ? 4 : 3; }

    /** a °F setpoint as the half degree °C the phone shows as the same °F */
    static double fahrenheit(double f) {
        return temperature((f - 32) / 1.8);
    }

    /** °C on CarPlay's half-degree steps, LO and HI at the ends */
    static double temperature(double c) {
        double half = Math.round(c * 2) / 2.0;
        return half <= LO ? LO : half >= HI ? HI : half;
    }

    static boolean known(float f) {
        return !Float.isNaN(f) && f >= 0;
    }

    // ---- write side: what the phone wrote, as the car's own commands ----

    /**
     * a command: DATCService setVRClimateStatus(a, b, c), setFrontClimateControl(a, b), USMService a(b), or
     * the rear defroster on (a 1) or off (a 0), which CarClimate sends through VRService
     */
    static final int VR = 0, FRONT = 1, SEAT = 2, REAR = 3, WHEEL = 4, INTAKE = 5;
    static final int SET_DRIVER_SEAT = 0xd5, SET_PASSENGER_SEAT = 0xd6;
    static final int FRONT_DRIVER_ONLY = 24;
    static final int FAHRENHEIT = 2, FRONT_DRIVER_C = 28, FRONT_PASSENGER_C = 29, FRONT_PASSENGER_F = 31;
    static final int MIN_F = 62, MAX_F = 90;

    static final class Command {
        final int kind, a, b, c;

        Command(int kind, int a, int b, int c) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.c = c;
        }

        @Override
        public String toString() {
            if (kind == REAR) return "rear(" + a + ")";
            if (kind == WHEEL) return "wheel(" + a + ")";
            if (kind == INTAKE) return "intake(" + (a == 1 ? "recirculate" : "fresh") + ")";
            return (kind == VR ? "vr" : kind == FRONT ? "front" : "seat") + "(" + a + ", " + b + (kind == VR ? ", " + c : "") + ")";
        }
    }

    /**
     * The commands for what the phone wrote (iid -> value), given the values after the write
     * (now), in the car's temperature unit (unit: 1 °C, otherwise °F). Rear and front window heating, MAX A/C off and anything else the car has no command
     * for are left out: the reader puts the car's own state back on the phone.
     */
    static List<Command> commands(Map<Long, Object> written, Map<Long, Object> now, int unit) {
        boolean celsius = unit == CELSIUS;
        List<Command> out = new ArrayList<Command>();
        boolean vents = false;
        for (Map.Entry<Long, Object> e : written.entrySet()) {
            long iid = e.getKey();
            Object v = e.getValue();
            if ((iid == HVAC_ON || iid == DRIVER_ZONE_ON) && v instanceof Boolean) {
                // the driver's seat power is the whole system's: the car can't run the passenger zone alone
                out.add(fan((Boolean) v ? Math.max(1, number(now.get(FAN_LEVEL), 1)) : 0));
            } else if (iid == FAN_ON && v instanceof Boolean) {
                out.add(fan((Boolean) v ? Math.max(1, number(now.get(FAN_LEVEL), 1)) : 0));
            } else if (iid == FAN_LEVEL && v instanceof Long) {
                out.add(fan((Long) v));
            } else if ((iid == AUTO_CLIMATE || iid == FAN_AUTO) && v instanceof Boolean) {
                out.add(new Command(VR, 8, (Boolean) v ? 1 : 0, 0));
            } else if (iid == COMPRESSOR && v instanceof Boolean) {
                out.add(new Command(VR, 9, (Boolean) v ? 1 : 0, 0));
            } else if (iid == MAX_DEFROST && v instanceof Boolean) {
                out.add(new Command(VR, 10, (Boolean) v ? 1 : 0, 0));
            } else if (iid == FRONT_WINDOW_HEATING && v instanceof Long) {
                // no heated windshield on this car: the front window button is its front defrost
                out.add(new Command(VR, 10, (Long) v > 0 ? 1 : 0, 0));
            } else if (iid == REAR_WINDOW_HEATING && v instanceof Long) {
                out.add(new Command(REAR, (Long) v > 0 ? 1 : 0, 0, 0));
            } else if (iid == RECIRCULATION && v instanceof Boolean) {
                out.add(new Command(INTAKE, (Boolean) v ? 1 : 0, 0, 0));
            } else if (VehicleDescription.wheelHeat && iid == VehicleDescription.WHEEL_LEVEL && v instanceof Long
                    && ((Long) v == 0 || (Long) v == 1)) {
                out.add(new Command(WHEEL, ((Long) v).intValue(), 0, 0));
            } else if ((iid == PASSENGER_ON || iid == PASSENGER_ZONE_ON) && v instanceof Boolean) {
                // the passenger zone off is the car's Driver Only; turning it on while the system is off
                // starts the system first (unless the phone turned hvacOn on in the same write)
                if ((Boolean) v && !Boolean.TRUE.equals(now.get(HVAC_ON)) && !Boolean.TRUE.equals(now.get(DRIVER_ZONE_ON)))
                    out.add(fan(Math.max(1, number(now.get(FAN_LEVEL), 1))));
                out.add(new Command(FRONT, FRONT_DRIVER_ONLY, (Boolean) v ? 0 : 1, 0));
            } else if (iid == ZONES_SYNCED && v instanceof Boolean) {
                out.add(new Command(VR, 22, (Boolean) v ? 2 : 1, 0));
            } else if (iid == MAX_AC && Boolean.TRUE.equals(v)) {
                // the car has no MAX A/C command: A/C, recirculation and the top fan step, as LIVI's rule does
                out.add(new Command(VR, 9, 1, 0));
                out.add(new Command(VR, 11, 1, 0));
                out.add(fan(8));
            } else if (iid == DRIVER_TARGET && v instanceof Double) {
                // °C: the stock screen's call (setVRClimateStatus only takes whole degrees); not yet tried on a car
                out.add(celsius ? new Command(FRONT, FRONT_DRIVER_C, celsiusRaw((Double) v), 0)
                        : new Command(VR, 2, toFahrenheit((Double) v), FAHRENHEIT));
            } else if (iid == PASSENGER_TARGET && v instanceof Double) {
                // setVRClimateStatus's passenger path names a signal the car doesn't have; the stock screen's call
                out.add(celsius ? new Command(FRONT, FRONT_PASSENGER_C, celsiusRaw((Double) v), 0)
                        : new Command(FRONT, FRONT_PASSENGER_F, toFahrenheit((Double) v) - 56, 0));
            } else if (iid == UPPER_ON || iid == LOWER_ON) {
                vents = true;
            }
        }
        seatWrite(out, written, now, DRIVER_SEAT_LEVEL, DRIVER_SEAT_ON, SET_DRIVER_SEAT);
        seatWrite(out, written, now, PASSENGER_SEAT_LEVEL, PASSENGER_SEAT_ON, SET_PASSENGER_SEAT);
        if (vents) {
            boolean upper = Boolean.TRUE.equals(now.get(UPPER_ON)), lower = Boolean.TRUE.equals(now.get(LOWER_ON));
            if (upper || lower) out.add(new Command(VR, 6, upper && lower ? 2 : upper ? 1 : 3, 0));
        }
        return out;
    }

    /** Resolve one desired state per seat. A stale level in the same request must not undo an explicit OFF. */
    static void seatWrite(List<Command> out, Map<Long, Object> written, Map<Long, Object> now, long level, long on, int code) {
        if (!written.containsKey(level) && !written.containsKey(on)) return;
        Object l = written.get(level), enabled = written.get(on);
        if (Boolean.FALSE.equals(enabled) || Long.valueOf(0).equals(l)) {
            out.add(new Command(SEAT, code, 2, 0));
        } else {
            Command c = written.containsKey(level) ? seat(code, false, l, null) : seat(code, true, enabled, now.get(level));
            if (c != null) out.add(c);
        }
    }

    static boolean seatStateKnown(int raw) { return raw >= 2 && raw <= 8; }

    static int seatFeedback(int body, int usm) {
        return seatStateKnown(body) ? body : seatStateKnown(usm) ? usm : -1;
    }

    static String seatText(int raw) {
        if (raw == 2) return "Off";
        if (raw >= 3 && raw <= 5) return "Ventilation " + (raw - 2);
        if (raw >= 6 && raw <= 8) return "Heat " + (raw - 5);
        return "State unavailable";
    }

    static final long MAX_AC = 516;

    /** the panel's fan step 1-8 as the raw blower value (step + 1); 0 turns the climate off */
    static Command fan(long step) {
        long s = Math.max(0, Math.min(8, step));
        return new Command(VR, 5, s == 0 ? 0 : (int) s + 1, 0);
    }

    /** seat heat level 0-3 (or on/off, which turns on at the last level or low) as USMService's state */
    static Command seat(int code, boolean onOff, Object v, Object level) {
        long l;
        if (onOff) {
            if (!(v instanceof Boolean)) return null;
            long last = number(level, 1);
            l = (Boolean) v ? (last != 0 && (last > 0 || VehicleDescription.seatCooling) ? last : 1) : 0;
        } else {
            if (!(v instanceof Long)) return null;
            l = Math.max(VehicleDescription.seatCooling ? -4 : 0, Math.min(3, (Long) v));
        }
        if (l < 0) return new Command(SEAT, code, coolingState(l), 0);
        return new Command(SEAT, code, l == 0 ? 2 : (int) l + 5, 0);
    }

    /** a CarPlay °C setpoint as the car's whole °F, inside its 62-90 range */
    static int toFahrenheit(double c) {
        return (int) Math.max(MIN_F, Math.min(MAX_F, Math.round(c * 1.8 + 32)));
    }

    /** a CarPlay °C setpoint as DATCService's raw °C ((°C - 14) * 2), inside 17-32 °C */
    static int celsiusRaw(double c) {
        return (int) Math.max(6, Math.min(36, Math.round(c * 2) - 28));
    }

    static long number(Object v, long otherwise) {
        return v instanceof Long ? (Long) v : otherwise;
    }
}
