package ioniq6.cpclimate;

import java.io.File;
import java.util.*;

/** Real-event boundaries and unit/state handling must stay conservative with missing OEM data. */
public final class EnergyAndIntakeTest {
    static int fails;
    static void check(String name, boolean ok) { if (!ok) fails++; System.out.println((ok ? "ok   " : "FAIL ") + name); }
    static boolean has(List<VehicleEvents.Event> events, String title) { for (VehicleEvents.Event e : events) if (e.label.equals(title)) return true; return false; }
    static VehicleEvents monitor() { VehicleEvents e = new VehicleEvents(); e.update(0, 60, ChargingData.State.IDLE, 0); return e; }
    public static void main(String[] args) throws Exception {
        check("NOT_CHARGING is not classified by a CHARGING substring", ChargingData.state("NOT_CHARGING") == ChargingData.State.IDLE);
        check("unmapped codes and rate-only names do not imply active charging", ChargingData.state("1") == ChargingData.State.UNKNOWN && ChargingData.state("FAST") == ChargingData.State.UNKNOWN);
        check("explicit complete and fault states are separate", ChargingData.state("COMPLETED") == ChargingData.State.COMPLETE && ChargingData.state("CHARGING_ERROR") == ChargingData.State.FAULT);
        check("charge connector requires an explicit AC/DC name", "AC".equals(ChargingData.connector("AC_240V")) && "DC".equals(ChargingData.connector("CCS1")) && ChargingData.connector("FAST") == null);
        check("power requires verified units", ChargingData.power(7200, "unverified") == null && ChargingData.power(72, "tenths_kw") == 7.2 && ChargingData.power(7200, "watts") == 7.2);
        check("invalid or implausible power is unavailable", ChargingData.power(-1, "kw") == null && ChargingData.power(Double.NaN, "kw") == null && ChargingData.power(Double.POSITIVE_INFINITY, "kw") == null && ChargingData.power(401, "kw") == null);
        check("remaining time requires units, handles seconds and rejects sentinels", ChargingData.minutes(3600, "seconds") == 60 && ChargingData.minutes(60, "unverified") == null && ChargingData.minutes(65535, "minutes") == null && ChargingData.minutes(-1, "minutes") == null);
        check("remaining-time formatting does not truncate partial minutes", "1 h 1 min".equals(ChargingData.duration(60.1)) && "59 min".equals(ChargingData.duration(59.0)));
        ChargingData.Sample s = new ChargingData.Sample(); s.state = ChargingData.State.CHARGING; s.power = 7.2; s.minutes = 61.0; s.connector = "AC"; s.powerUnitKnown = s.timeUnitKnown = true;
        Map<String, Object> v = ChargingData.values(s, 61, 200, true, 1);
        check("charging panel shows typed power, time, real SOC and range units", "7.2 kW".equals(v.get("charge_power")) && "1 h 1 min".equals(v.get("charge_remaining")) && "61 %".equals(v.get("charge_battery")) && ((String) v.get("charge_range")).endsWith(" mi"));
        s.state = ChargingData.State.DISCONNECTED;
        v = ChargingData.values(s, 61, 200, false, -1);
        check("unplugged state hides old power/time and unknown conditioning", VehiclePage.NONE.equals(v.get("charge_power")) && VehiclePage.NONE.equals(v.get("charge_remaining")) && VehiclePage.NONE.equals(v.get("charge_conditioning")));
        s.state = ChargingData.State.UNKNOWN;
        check("unknown state does not display leftover charging power", VehiclePage.NONE.equals(ChargingData.values(s, 61, 200, false, 0).get("charge_power")));
        VehicleEvents e = monitor();
        check("one noisy active sample creates no notification", e.update(5000, 60, ChargingData.State.CHARGING, 0).isEmpty());
        check("two active samples create one real start event", has(e.update(10000, 60, ChargingData.State.CHARGING, 0), "Charging started"));
        check("unchanged charging does not repeat notifications", e.update(15000, 60, ChargingData.State.CHARGING, 0).isEmpty());
        check("unavailable data never means charging stopped", e.update(20000, 60, ChargingData.State.UNKNOWN, -1).isEmpty());
        e.update(25000, 60, ChargingData.State.COMPLETE, 0);
        check("completion requires confirmed complete state", has(e.update(30000, 60, ChargingData.State.COMPLETE, 0), "Charging complete"));
        check("reconnection after a long gap creates no synthetic completion/start", e.update(100000, 80, ChargingData.State.CHARGING, 0).isEmpty());
        e.update(105000, 80, ChargingData.State.IDLE, 0);
        List<VehicleEvents.Event> stopped = e.update(110000, 80, ChargingData.State.IDLE, 0);
        check("a stopped charge is not labelled complete", has(stopped, "Charging stopped") && !has(stopped, "Charging complete"));
        e.update(115000, 80, ChargingData.State.FAULT, 0);
        check("a confirmed fault produces an actionable notification", has(e.update(120000, 80, ChargingData.State.FAULT, 0), "Charging fault"));
        e = new VehicleEvents(); e.update(0, 60, ChargingData.State.UNKNOWN, -1); e.update(5000, 60, ChargingData.State.CHARGING, -1);
        check("first decoded state establishes a baseline instead of claiming a start", e.update(10000, 60, ChargingData.State.CHARGING, -1).isEmpty());
        e = monitor();
        check("low SOC posts one real battery notification", has(e.update(5000, 19.9, ChargingData.State.IDLE, 0), "Battery low"));
        check("SOC jitter and repeated low readings do not spam", e.update(10000, 20.1, ChargingData.State.IDLE, 0).isEmpty() && e.update(15000, 19.9, ChargingData.State.IDLE, 0).isEmpty());
        check("critical SOC escalates once", has(e.update(20000, 9.5, ChargingData.State.IDLE, 0), "Battery very low") && e.update(25000, 9.4, ChargingData.State.IDLE, 0).isEmpty());
        e.update(30000, 25, ChargingData.State.IDLE, 0);
        check("observed recovery rearms a later low-battery event", has(e.update(35000, 19, ChargingData.State.IDLE, 0), "Battery low"));
        e = new VehicleEvents();
        check("invalid SOC cannot create a warning", e.update(0, Double.NaN, ChargingData.State.UNKNOWN, -1).isEmpty() && e.update(5000, -1, ChargingData.State.UNKNOWN, -1).isEmpty() && e.update(10000, 255, ChargingData.State.UNKNOWN, -1).isEmpty());
        e = new VehicleEvents();
        check("active charging suppresses low-battery warning", e.update(0, 9, ChargingData.State.CHARGING, -1).isEmpty());
        e = monitor(); e.update(5000, 60, ChargingData.State.IDLE, 1);
        check("conditioning notification requires actual stable indication", has(e.update(10000, 60, ChargingData.State.IDLE, 1), "Battery conditioning active"));
        check("missing conditioning signal cannot claim it stopped", e.update(15000, 60, ChargingData.State.IDLE, -1).isEmpty());
        e.update(20000, 60, ChargingData.State.IDLE, 0);
        check("stable inactive indication confirms conditioning stopped", has(e.update(25000, 60, ChargingData.State.IDLE, 0), "Battery conditioning stopped"));
        String product = "CVEV.USA.0000.V015.012.260507";
        FreshAirProfile profile = new FreshAirProfile(product, new ClimateMap.Command(ClimateMap.FRONT, 17, 0, 0));
        check("fresh-air profile round-trips a learned command for its exact product", FreshAirProfile.decode(profile.encode(), product, 17) != null);
        check("a different unit or selector cannot replay a profile", FreshAirProfile.decode(profile.encode(), "CVEV.USA.0000.V015.013.260507", 17) == null && FreshAirProfile.decode(profile.encode(), product, 18) == null);
        check("non-intake and out-of-range profiles are rejected", FreshAirProfile.decode("fresh-air-v1 " + product + " 0 2 72 2", product, 17) == null && FreshAirProfile.decode("fresh-air-v1 " + product + " 0 11 256 0", product, 17) == null);
        check("the already-known recirculation ON command can never be learned as fresh air", FreshAirProfile.decode("fresh-air-v1 " + product + " 0 11 1 0", product, 17) == null);
        check("only ON then two stable fresh readings can train a profile", FreshAirProfile.transition(1, 2, 2) && !FreshAirProfile.transition(2, 2, 2) && !FreshAirProfile.transition(-1, 2, 2) && !FreshAirProfile.transition(1, 1, 2));
        File dir = new File(args[0], "fresh-air"); dir.mkdir();
        check("absent profile is unavailable", FreshAirProfile.read(dir, product, 17) == null);
        profile.save(dir);
        check("confirmed profile persists and remains firmware-bound", FreshAirProfile.read(dir, product, 17) != null && FreshAirProfile.read(dir, "CVEV.USA.0000.V015.012.260508", 17) == null);
        if (fails > 0) throw new AssertionError(fails + " failed");
    }
}
