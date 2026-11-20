package ioniq6.cpclimate;

import java.util.*;

/** Unit-aware charging display. Raw undocumented numbers never become a guessed state or measurement. */
final class ChargingData {
    enum State { UNKNOWN, DISCONNECTED, IDLE, CHARGING, COMPLETE, FAULT }
    static final class Sample {
        State state = State.UNKNOWN;
        String connector;
        Double power, minutes;
        Number rawPower, rawTime, rawStatus, rawFinish, rawAvailablePower, rawAcPower;
        String powerSource = "none", timeSource = "none", statusSource = "none";
        boolean powerUnitKnown, timeUnitKnown;
    }
    static State state(String symbol) {
        if (symbol == null) return State.UNKNOWN;
        String n = symbol.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.US).replace('-', '_').replace(' ', '_');
        if (Arrays.asList("CHARGING", "CHARGE_ON", "ACTIVE", "CHARGE_START", "CHARGING_START", "CHARGE_IN_PROGRESS", "IN_PROGRESS", "AC_CHARGING", "DC_CHARGING", "NORMAL_CHARGING", "QUICK_CHARGING", "CHARGING_NORMAL", "CHARGING_QUICK").contains(n)) return State.CHARGING;
        if (Arrays.asList("NOT_CHARGING", "IDLE", "STOPPED", "PAUSED", "WAITING", "CHARGE_OFF").contains(n)) return State.IDLE;
        if (Arrays.asList("DISCONNECTED", "NOT_CONNECTED", "UNPLUGGED").contains(n)) return State.DISCONNECTED;
        if (Arrays.asList("COMPLETE", "COMPLETED", "CHARGE_FINISH", "CHARGING_FINISHED", "CHARGE_COMPLETE", "CHARGING_COMPLETE", "CHARGE_DONE", "CHARGING_DONE", "FINISHED", "FINISH", "FULLY_CHARGED").contains(n)) return State.COMPLETE;
        if (Arrays.asList("FAULT", "ERROR", "CHARGING_ERROR").contains(n)) return State.FAULT;
        return State.UNKNOWN;
    }
    static String connector(String symbol) {
        if (symbol == null) return null;
        String s = symbol.toUpperCase(Locale.US);
        if (Arrays.asList("AC", "AC_120V", "AC_240V", "AC_NORMAL", "NORMAL_AC", "AC_CONNECTED", "AC_CHARGING").contains(s)) return "AC";
        if (Arrays.asList("DC", "DC_FAST", "CCS", "CCS1", "CCS2", "QUICK_DC", "DC_CONNECTED", "DC_CHARGING").contains(s)) return "DC";
        if (Arrays.asList("NONE", "NOT_CONNECTED", "DISCONNECTED", "UNPLUGGED").contains(s)) return "Disconnected";
        return null;
    }
    static Double power(Number raw, String unit) {
        if (raw == null) return null;
        double multiplier = "kw".equals(unit) ? 1 : "tenths_kw".equals(unit) ? .1 : "watts".equals(unit) ? .001 : Double.NaN;
        double n = raw.doubleValue() * multiplier;
        return Double.isNaN(n) || Double.isInfinite(n) || n < 0 || n > 400 ? null : n;
    }
    static Double minutes(Number raw, String unit) {
        if (raw == null) return null;
        double n = raw.doubleValue() / ("minutes".equals(unit) ? 1 : "seconds".equals(unit) ? 60 : Double.NaN);
        return Double.isNaN(n) || Double.isInfinite(n) || n < 0 || n > 7 * 24 * 60 ? null : n;
    }
    static String duration(Double minutes) {
        if (minutes == null) return VehiclePage.NONE;
        int n = (int) Math.ceil(minutes);
        return n < 60 ? n + " min" : n / 60 + " h" + (n % 60 == 0 ? "" : " " + n % 60 + " min");
    }
    static Map<String, Object> values(Sample s, double soc, int range, boolean miles, int conditioning) {
        Map<String, Object> v = new LinkedHashMap<String, Object>();
        v.put("charge_battery", VehiclePage.percent((float) soc));
        v.put("charge_range", range >= 0 ? VehiclePage.distance(range, miles) : VehiclePage.NONE);
        String label = s.state == State.CHARGING ? "Charging" : s.state == State.COMPLETE ? "Complete"
                : s.state == State.DISCONNECTED ? "Disconnected" : s.state == State.IDLE ? "Not charging"
                : s.state == State.FAULT ? "Charging fault" : "State unavailable";
        v.put("charge_status", label);
        v.put("charge_connection", s.connector == null ? VehiclePage.NONE : s.connector);
        boolean charging = s.state == State.CHARGING;
        v.put("charge_power", charging && s.power != null ? String.format(Locale.US, "%.1f kW", s.power) : VehiclePage.NONE);
        v.put("charge_remaining", charging ? duration(s.minutes) : VehiclePage.NONE);
        v.put("charge_conditioning", conditioning == 1 ? "Active" : conditioning == 0 ? "Inactive" : VehiclePage.NONE);
        String setup = s.state == State.UNKNOWN ? "Live state not decoded; collect charging log"
                : !charging ? "Live state received"
                : !s.powerUnitKnown || !s.timeUnitKnown ? "Confirm power/time units in mod settings"
                : s.power == null || s.minutes == null ? "Some charging values unavailable" : "Live data received";
        v.put("charge_data", setup);
        return v;
    }
}
