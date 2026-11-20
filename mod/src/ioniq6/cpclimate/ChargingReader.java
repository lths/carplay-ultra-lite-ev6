package ioniq6.cpclimate;

import android.os.*;
import dalvik.system.DexFile;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;

/** Allowlisted OEM getters, resolved by name/signature. No setters or new listener registrations. */
final class ChargingReader {
    static String configuredPowerUnit = "unverified", configuredTimeUnit = "unverified";
    final ClassLoader loader = CarApi.hscanLoader();
    final Map<String, Query> queries = new LinkedHashMap<String, Query>();
    final Map<String, Map<Integer, String>> enums = new HashMap<String, Map<Integer, String>>();
    final VsChannel.Log out;
    final String powerUnit, timeUnit;
    String last;
    int logCount;
    ChargingReader(VsChannel.Log out, String powerUnit, String timeUnit) {
        this.out = out; this.powerUnit = powerUnit; this.timeUnit = timeUnit;
        String[] names = { "getChargingStatus", "getChargingFinish", "getRemainChargingTime", "getChargingInfo", "getChargeVCU9Info",
                "getVCMS_EvsePwrDis", "getCCM_EvseMainsPwr", "getOBC_AcPwrDis", "getChargeRate", "getChargeEVSE", "getEvseStatus", "getEVSEStatus",
                "isCharging", "isChargeCableConnected", "getChargingPowerKw", "getChargingPowerW", "getRemainingChargingTimeMinutes", "getRemainingChargingTimeSeconds" };
        for (String name : names) for (String iface : new String[] { CarApi.HSCAN, CarApi.HEV }) {
            try {
                Class<?> type = Class.forName(iface, false, loader);
                Method found = null;
                for (Method m : type.getMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    if (found != null) { found = null; break; } found = m;
                }
                if (found == null) continue;
                Class<?> returns = found.getReturnType();
                if (!(returns == int.class || returns == byte.class || returns == float.class || returns == double.class
                        || returns == long.class || returns == boolean.class || Parcelable.class.isAssignableFrom(returns))) continue;
                int tx = CarApi.code(loader, iface, name, CarApi.descriptor(found));
                if (tx < 0) continue;
                queries.put(name, new Query(iface, tx, found.getReturnType()));
                out.log("VEH charge getter " + iface + "." + name + CarApi.descriptor(found));
                break;
            } catch (Throwable unavailable) { }
        }
        discoverEnums();
    }
    static final class Query {
        final String iface;
        final int tx;
        final Class<?> returns;
        Query(String iface, int tx, Class<?> returns) { this.iface = iface; this.tx = tx; this.returns = returns; }
    }
    Object read(String name) {
        Query q = queries.get(name);
        if (q == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder binder = CarClimate.service(CarApi.HSCAN.equals(q.iface) ? CarApi.HSCAN_SERVICE : CarVehicle.HEV);
            if (binder == null) return null;
            data.writeInterfaceToken(q.iface);
            if (!binder.transact(q.tx, data, reply, 0)) return null;
            reply.readException();
            if (q.returns == int.class || q.returns == byte.class) return reply.readInt();
            if (q.returns == float.class) return reply.readFloat();
            if (q.returns == double.class) return reply.readDouble();
            if (q.returns == long.class) return reply.readLong();
            if (q.returns == boolean.class) return reply.readInt() != 0;
            if (Parcelable.class.isAssignableFrom(q.returns) && reply.readInt() != 0) {
                Object creator = q.returns.getField("CREATOR").get(null);
                if (creator instanceof Parcelable.Creator) return ((Parcelable.Creator<?>) creator).createFromParcel(reply);
            }
        } catch (Throwable unavailable) { /* Missing data stays unknown and cannot generate a transition. */ }
        finally { data.recycle(); reply.recycle(); }
        return null;
    }
    void discoverEnums() {
        for (String path : new String[] { CarApi.HSCAN_JAR, "/system/framework/automotive.jar" }) {
            if (!new File(path).isFile()) continue;
            try {
                DexFile dex = new DexFile(path);
                try {
                    Enumeration<String> names = dex.entries();
                    while (names.hasMoreElements()) {
                        String name = names.nextElement();
                        if (!name.matches("com\\.hkmc\\..*[.$](CHARGING_STATUS|CHARGE_STATUS|CHARGE_RATE|CHARGE_EVSE)$")) continue;
                        Class<?> type = Class.forName(name, false, loader);
                        Map<Integer, String> values = new LinkedHashMap<Integer, String>();
                        Set<Integer> ambiguous = new HashSet<Integer>();
                        for (Field f : type.getFields()) {
                            if (!Modifier.isStatic(f.getModifiers())) continue;
                            Object value = f.get(null);
                            Integer number = value instanceof Number ? ((Number) value).intValue() : enumValue(value);
                            if (number == null) continue;
                            String previous = values.put(number, f.getName());
                            if (previous != null && !previous.equals(f.getName())) ambiguous.add(number);
                        }
                        for (Integer n : ambiguous) values.remove(n);
                        String key = name.substring(Math.max(name.lastIndexOf('$'), name.lastIndexOf('.')) + 1);
                        // Two different enum classes with the same name must agree before their values are usable.
                        Map<Integer, String> before = enums.get(key);
                        if (before != null && !before.equals(values)) values.clear();
                        enums.put(key, values);
                        out.log("VEH charge enum " + name + " " + values);
                    }
                } finally { dex.close(); }
            } catch (Throwable unavailable) { out.log("VEH charge enum inventory unavailable: " + unavailable.getClass().getSimpleName()); }
        }
    }
    static Integer enumValue(Object value) {
        if (!(value instanceof Enum)) return null;
        for (String name : new String[] { "getValue", "getCode" }) try {
            Object n = value.getClass().getMethod(name).invoke(value);
            if (n instanceof Number) return ((Number) n).intValue();
        } catch (Throwable absent) { }
        return null; // An enum ordinal is not a verified wire value.
    }
    String symbol(Object value, String type) {
        if (value instanceof Enum) return ((Enum<?>) value).name();
        Map<Integer, String> table = enums.get(type);
        if (!(value instanceof Number) || table == null) return null;
        double n = ((Number) value).doubleValue();
        return Double.isNaN(n) || Double.isInfinite(n) || n != Math.rint(n) || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE ? null : table.get((int) n);
    }
    static Number number(Object value) { return value instanceof Number ? (Number) value : null; }
    static Object member(Object value, String... methods) {
        if (value == null) return null;
        for (String name : methods) {
            try {
                Method m = value.getClass().getMethod(name);
                if (m.getParameterTypes().length == 0) {
                    Object result = m.invoke(value);
                    if (scalar(result)) return result;
                }
            } catch (Throwable absent) { }
            String stem = name.startsWith("get") ? name.substring(3) : name.startsWith("is") ? name.substring(2) : name;
            if (stem.isEmpty()) continue;
            String field = Character.toLowerCase(stem.charAt(0)) + stem.substring(1);
            String snake = field.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.US);
            for (String candidate : new String[] { name, field, snake, "m" + stem }) try {
                Field f = value.getClass().getDeclaredField(candidate);
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object result = f.get(value);
                if (scalar(result)) return result;
            } catch (Throwable absent) { }
        }
        return null;
    }
    static boolean scalar(Object v) { return v instanceof Number || v instanceof Boolean || v instanceof Enum; }
    interface Source { Object read(String name); String symbol(Object value, String type); }

    static ChargingData.Sample project(Source source, String powerUnit, String timeUnit) {
        ChargingData.Sample s = new ChargingData.Sample();
        Object status = source.read("getChargingStatus"), info = source.read("getChargingInfo");
        if (info == null) info = source.read("getChargeVCU9Info");
        s.rawStatus = number(status); s.rawFinish = number(source.read("getChargingFinish"));
        String name = source.symbol(status, "CHARGING_STATUS");
        if (name == null) name = source.symbol(status, "CHARGE_STATUS");
        s.state = ChargingData.state(name); s.statusSource = name == null ? "unmapped" : name;
        if (s.state == ChargingData.State.UNKNOWN) {
            Object state = member(info, "getChargingStatus", "getChargeStatus", "getStatus", "getState");
            name = source.symbol(state, "CHARGING_STATUS");
            if (name == null) name = source.symbol(state, "CHARGE_STATUS");
            s.state = ChargingData.state(name);
            if (name != null) s.statusSource = name;
        }
        Object active = source.read("isCharging");
        if (!(active instanceof Boolean)) active = member(info, "isCharging");
        if (s.state == ChargingData.State.UNKNOWN && active instanceof Boolean) {
            s.state = (Boolean) active ? ChargingData.State.CHARGING : ChargingData.State.IDLE; s.statusSource = "isCharging";
        }
        Object connected = source.read("isChargeCableConnected");
        if (!(connected instanceof Boolean)) connected = member(info, "isChargeCableConnected", "isConnected", "isPluggedIn");
        if (s.state == ChargingData.State.UNKNOWN && Boolean.FALSE.equals(connected)) { s.state = ChargingData.State.DISCONNECTED; s.statusSource = "isChargeCableConnected"; }
        if (s.state == ChargingData.State.UNKNOWN) {
            Object rate = source.read("getChargeRate");
            if (rate == null) rate = member(info, "getChargeRate", "getRate");
            String rateName = source.symbol(rate, "CHARGE_RATE");
            s.state = ChargingData.state(rateName); // FAST/NORMAL by themselves remain unknown.
            if (s.state != ChargingData.State.UNKNOWN) s.statusSource = "CHARGE_RATE." + rateName;
        }
        Object evse = source.read("getChargeEVSE");
        if (evse == null) evse = source.read("getEvseStatus");
        if (evse == null) evse = source.read("getEVSEStatus");
        if (evse == null) evse = member(info, "getChargeEVSE", "getEVSE", "getEvse", "getConnectorType");
        String evseName = source.symbol(evse, "CHARGE_EVSE");
        s.connector = ChargingData.connector(evseName);
        if (s.connector == null && connected instanceof Boolean) s.connector = (Boolean) connected ? "Connected" : "Disconnected";
        if (s.state == ChargingData.State.UNKNOWN) {
            s.state = ChargingData.state(evseName);
            if (s.state != ChargingData.State.UNKNOWN) s.statusSource = "CHARGE_EVSE." + evseName;
        }
        Object kw = source.read("getChargingPowerKw"), watts = kw == null ? source.read("getChargingPowerW") : null;
        s.rawPower = number(kw != null ? kw : watts != null ? watts : source.read("getVCMS_EvsePwrDis"));
        String pu = kw != null ? "kw" : watts != null ? "watts" : powerUnit;
        s.powerSource = kw != null ? "getChargingPowerKw" : watts != null ? "getChargingPowerW" : "getVCMS_EvsePwrDis";
        s.power = ChargingData.power(s.rawPower, pu); s.powerUnitKnown = !"unverified".equals(pu);
        Object min = source.read("getRemainingChargingTimeMinutes"), sec = min == null ? source.read("getRemainingChargingTimeSeconds") : null;
        s.rawTime = number(min != null ? min : sec != null ? sec : source.read("getRemainChargingTime"));
        String tu = min != null ? "minutes" : sec != null ? "seconds" : timeUnit;
        s.timeSource = min != null ? "getRemainingChargingTimeMinutes" : sec != null ? "getRemainingChargingTimeSeconds" : "getRemainChargingTime";
        s.minutes = ChargingData.minutes(s.rawTime, tu); s.timeUnitKnown = !"unverified".equals(tu);
        s.rawAvailablePower = number(source.read("getCCM_EvseMainsPwr")); s.rawAcPower = number(source.read("getOBC_AcPwrDis"));
        return s;
    }

    ChargingData.Sample sample() {
        ChargingData.Sample s = project(new Source() {
            public Object read(String name) { return ChargingReader.this.read(name); }
            public String symbol(Object value, String type) { return ChargingReader.this.symbol(value, type); }
        }, powerUnit, timeUnit);
        String log = "VEH charge raw status=" + s.rawStatus + " finish=" + s.rawFinish + " decoded=" + s.state + " source=" + s.statusSource
                + " power=" + s.rawPower + " powerSource=" + s.powerSource + " time=" + s.rawTime + " timeSource=" + s.timeSource
                + " available=" + s.rawAvailablePower + " obc=" + s.rawAcPower + " connection=" + s.connector;
        if (!log.equals(last) && logCount++ < 200) { out.log(log); last = log; }
        return s;
    }
}
