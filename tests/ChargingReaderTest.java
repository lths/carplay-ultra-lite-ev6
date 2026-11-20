package ioniq6.cpclimate;

import java.util.*;

/** The OEM adapter must not turn an unmapped number, wrong type or missing getter into a live reading. */
public final class ChargingReaderTest {
    static int fails;
    static void check(String name, boolean ok) { if (!ok) fails++; System.out.println((ok ? "ok   " : "FAIL ") + name); }
    enum Status { CHARGING, NOT_CHARGING }
    enum OrdinalOnly { ZERO, ONE }
    public static final class Info {
        public Status getStatus() { return Status.CHARGING; }
        public boolean isCharging() { return true; }
    }
    public static final class FieldInfo { private Status charging_status = Status.CHARGING; }
    static final class Fields implements ChargingReader.Source {
        final Map<String, Object> data = new HashMap<String, Object>();
        public Object read(String name) { return data.get(name); }
        public String symbol(Object v, String type) { return v instanceof Enum ? ((Enum<?>) v).name() : null; }
    }
    public static void main(String[] args) {
        Fields f = new Fields();
        f.data.put("getChargingStatus", 1); f.data.put("getVCMS_EvsePwrDis", 72); f.data.put("getRemainChargingTime", 65);
        ChargingData.Sample s = ChargingReader.project(f, "unverified", "unverified");
        check("numeric status and undocumented units remain unknown", s.state == ChargingData.State.UNKNOWN && s.power == null && s.minutes == null);
        f.data.put("getChargingInfo", new Info());
        s = ChargingReader.project(f, "tenths_kw", "minutes");
        check("actual typed charging enum and calibrated units are decoded", s.state == ChargingData.State.CHARGING && s.power == 7.2 && s.minutes == 65);
        f.data.clear(); f.data.put("isCharging", false); f.data.put("getChargingPowerKw", 7.2); f.data.put("getRemainingChargingTimeSeconds", 3600);
        s = ChargingReader.project(f, "unverified", "unverified");
        check("a typed boolean gives a real not-charging state", s.state == ChargingData.State.IDLE);
        check("explicitly unit-named getters work without overrides", s.power == 7.2 && s.minutes == 60 && s.powerUnitKnown && s.timeUnitKnown);
        check("idle UI does not expose these leftover power/time values", VehiclePage.NONE.equals(ChargingData.values(s, 60, 100, false, 0).get("charge_power")));
        f.data.clear(); f.data.put("isChargeCableConnected", false);
        check("an explicit disconnected boolean is understood", ChargingReader.project(f, "unverified", "unverified").state == ChargingData.State.DISCONNECTED);
        f.data.put("isChargeCableConnected", true);
        check("connection alone does not imply active charging", "Connected".equals(ChargingReader.project(f, "unverified", "unverified").connector)
                && ChargingReader.project(f, "unverified", "unverified").state == ChargingData.State.UNKNOWN);
        f.data.clear(); f.data.put("getChargeVCU9Info", new Info());
        check("the typed VCU9 information is used when ChargingInfo is absent", ChargingReader.project(f, "unverified", "unverified").state == ChargingData.State.CHARGING);
        f.data.clear(); f.data.put("getChargingInfo", new FieldInfo());
        check("allowlisted typed fields in an OEM data object are understood", ChargingReader.project(f, "unverified", "unverified").state == ChargingData.State.CHARGING);
        f.data.clear(); f.data.put("getChargeRate", Status.CHARGING);
        check("an explicit charging semantic in an OEM rate enum is accepted", ChargingReader.project(f, "unverified", "unverified").state == ChargingData.State.CHARGING);
        f.data.clear(); f.data.put("getEVSEStatus", Status.NOT_CHARGING);
        check("an explicit not-charging EVSE enum cannot imply charging", ChargingReader.project(f, "unverified", "unverified").state == ChargingData.State.IDLE);
        check("enum ordinals are never treated as wire values", ChargingReader.enumValue(OrdinalOnly.ONE) == null);
        ChargingReader reader = new ChargingReader(new VsChannel.Log() { public void log(String s) { } }, "unverified", "unverified");
        Map<Integer, String> map = new HashMap<Integer, String>(); map.put(0, "NOT_CHARGING"); reader.enums.put("CHARGE_STATUS", map);
        check("NaN, infinities and fractional status cannot alias integer zero", reader.symbol(Double.NaN, "CHARGE_STATUS") == null && reader.symbol(Double.POSITIVE_INFINITY, "CHARGE_STATUS") == null && reader.symbol(.1, "CHARGE_STATUS") == null);
        check("a verified numeric enum value resolves by its real code", "NOT_CHARGING".equals(reader.symbol(0, "CHARGE_STATUS")));
        ChargePort.Gate gate = new ChargePort.Gate();
        check("charge-port commands require confirmed Park", !gate.allowed(-1, 10000) && !gate.allowed(0, 10000) && !gate.allowed(2, 10000) && gate.allowed(1, 10000));
        gate.sent(10000);
        check("charge-port rate limit survives rapid opposite requests and clock reversal", !gate.allowed(1, 10001) && !gate.allowed(1, 14999) && !gate.allowed(1, 9999) && gate.allowed(1, 15000));
        if (fails > 0) throw new AssertionError(fails + " failed");
    }
}
