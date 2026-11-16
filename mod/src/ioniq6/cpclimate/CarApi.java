package ioniq6.cpclimate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The car's own binder calls, looked up by name in its framework (automotive.jar is on the boot class path)
 * instead of numbers written for one firmware: AIDL numbers its calls in declaration order, so a firmware
 * whose services gained or lost a method numbers them differently, and a hard-coded number would call
 * something else. A call whose method is missing, or has other parameter or return types, comes back as -1
 * and cp-climate leaves that control out.
 *
 * The listeners cp-climate answers by hand (DATCService's and BCMService's) are checked as a whole: every
 * call's number, name and parameter types must give the fingerprint of the firmware they were written
 * against (CEEV USA 260507, tools/listener_fingerprint.py computes the same from baksmali output). A
 * listener that answers a call differently from the stock stub can crash system_server.
 */
public final class CarApi {
    public static final String DATC = "com.hkmc.datc.IDATCService", DATC_LISTENER = "com.hkmc.datc.IDATCListener";
    public static final String USM = "com.hkmc.usm.IUSMService", VR = "com.hkmc.vr.IVRService";
    public static final String BCM = "com.hkmc.bcm.IBCMService", BCM_LISTENER = "com.hkmc.bcm.IBCMListener";
    public static final String AUDIO = "com.hkmc.media.IMicomAudioService", AUDIO_SERVICE = "com.hkmc.micom.audiosetup";
    public static final String HSCAN = "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";
    public static final String HSCAN_JAR = "/system/framework/com.hkmc.ext.hcan.jar", HSCAN_SERVICE = "tm_hscan_service";
    public static final String HEV = "com.hkmc.hev.IHEVService";

    /** Owner-supplied charging leads. Names alone do not establish units, a service binding or a callback ABI. */
    public static final String[] CHARGING_CANDIDATES = { "getVCMS_EvsePwrDis", "getCCM_EvseMainsPwr", "getOBC_AcPwrDis",
            "getXEV_BatPwrSum_Kwh", "getBMS8_CR_Bms_DisplaySoc", "getBatSoc", "getBMS5_CR_Bms_ScChgRemainedTime_240V",
            "getBMS8_CR_Bms_ScChgRemainedTime_120_min", "getRemainChargingTime", "getChargingInfo", "getChargeVCU9Info",
            "addChargeStatusListener", "onEVSEStatusChanged" };

    /** Reflection only: does not invoke getters, setters, adapters or listener registration. */
    public static List<String> chargingInventory() {
        ClassLoader loader = hscanLoader();
        List<String> report = new ArrayList<String>();
        for (String name : CHARGING_CANDIDATES) {
            boolean found = false;
            for (String iface : new String[] { HSCAN, HEV }) {
                try {
                    for (Method method : Class.forName(iface, false, loader).getMethods()) {
                        if (!name.equals(method.getName())) continue;
                        String desc = descriptor(method);
                        report.add(iface + "." + name + desc + " tx=" + code(loader, iface, name, desc));
                        found = true;
                    }
                } catch (Throwable unavailable) { /* This inventory cannot enable a control. */ }
            }
            if (!found) report.add(name + ": not found in checked HSCAN/HEV interfaces");
        }
        return report;
    }

    /** Shared by the runtime and checker. Transaction numbers can move; these flag meanings must not. */
    public static final String[][] CLIMATE_FLAGS = {
            { "com.hkmc.datc.DATCFlag", "TEMP", "2" }, { "com.hkmc.datc.DATCFlag", "BLOWER_LEVEL", "5" },
            { "com.hkmc.datc.DATCFlag", "BLOWER_MODE", "6" }, { "com.hkmc.datc.DATCFlag", "AUTO", "8" },
            { "com.hkmc.datc.DATCFlag", "AC", "9" }, { "com.hkmc.datc.DATCFlag", "FRONT_DEFOG", "10" },
            { "com.hkmc.datc.DATCFlag", "INTAKE", "11" }, { "com.hkmc.datc.DATCFlag", "OP_STATE", "12" },
            { "com.hkmc.datc.DATCFlag", "SYNC", "22" }, { "com.hkmc.datc.DATCFlag", "TEMP_PS_C", "29" },
            { "com.hkmc.datc.DATCFlag$DATCControl", "DRIVER_ONLY", "24" },
            { "com.hkmc.datc.DATCFlag$DATCControl", "TEMP_DR_C", "28" },
            { "com.hkmc.datc.DATCFlag$DATCControl", "TEMP_PS_C", "29" },
            { "com.hkmc.datc.DATCFlag$DATCControl", "TEMP_PS_F", "31" }
    };

    /** Vehicle-page getters, in CarVehicle's order; none is a setter or registers a callback. */
    public static final String[][] HSCAN_CALLS = {
            { "getOdometer", "(B)F" }, { "ableDriveDistance", "(B)I" }, { "remainBatteryPercentage", "()F" },
            { "getChargingStatus", "()I" }, { "getChargingFinish", "()I" }, { "getRemainChargingTime", "()I" },
            { "getFrontLeftTirePressureStatus", "()B" }, { "getFrontRightTirePressureStatus", "()B" },
            { "getRearLeftTirePressureStatus", "()B" }, { "getRearRightTirePressureStatus", "()B" }
    };

    /** Volume link, followed by the two optional volume-key experiment calls. */
    public static final String[][] AUDIO_CALLS = {
            { "getVolume", "(II)I" }, { "setVolume", "(III)V" }, { "muteAV", "()V" }, { "unMuteAV", "()V" },
            { "getUserMuteState", "()Z" }, { "getCurStreamType", "()I" },
            { "adjustsuggestedStreamVolume", "(IIII)V" }, { "hideVolumeDisplay", "(Z)V" }
    };
    /** the Ioniq 6 (CEEV USA 260507) listeners' fingerprints: 7 calls, and 55 with byte[] out at 37 and 39 */
    public static final String DATC_LISTENER_PRINT = "8a834814775381cc8a77646c06710f8483a5b4b0bb309d49bc2c742719781d0c";
    public static final String BCM_LISTENER_PRINT = "b5a76b60550eed8c576003be0018d2cc00cd893b1d71d5872d4e53a966f4b861";

    private CarApi() {
    }

    public static boolean ev6Target(String product) {
        return product != null && product.startsWith("CVEV.USA.") && product.endsWith(".260507");
    }

    /** No unchecked climate selector is sent when a firmware changes or omits a flag. */
    public static List<String> climateFlagMismatches() {
        return climateFlagMismatches(CarApi.class.getClassLoader());
    }

    public static List<String> climateFlagMismatches(ClassLoader loader) {
        List<String> bad = new ArrayList<String>();
        for (String[] f : CLIMATE_FLAGS) {
            Integer value = null;
            try {
                value = Class.forName(f[0], false, loader).getField(f[1]).getInt(null);
            } catch (Throwable unavailable) {
                // Missing or differently typed constants are incompatible, just like changed values.
            }
            if (value == null || value != Integer.parseInt(f[2]))
                bad.add(f[0] + "." + f[1] + "=" + value + " (expected " + f[2] + ")");
        }
        return bad;
    }

    /** HSCAN is outside the boot class path. Reflection also lets the checker compile without Android. */
    public static ClassLoader hscanLoader() {
        ClassLoader loader = CarApi.class.getClassLoader();
        try {
            Class.forName(HSCAN, false, loader);
            return loader;
        } catch (Throwable outsideBootPath) {
            try {
                return (ClassLoader) Class.forName("dalvik.system.PathClassLoader")
                        .getConstructor(String.class, ClassLoader.class).newInstance(HSCAN_JAR, loader);
            } catch (Throwable unavailable) {
                return loader; // code() then reports missing calls; no guessed transaction numbers.
            }
        }
    }

    /** the transaction code of iface.method if the method exists with exactly this descriptor, else -1 */
    public static int code(String iface, String method, String descriptor) {
        return code(CarApi.class.getClassLoader(), iface, method, descriptor);
    }

    /** the same for an interface outside the boot class path (HSCAN's is in com.hkmc.ext.hcan.jar), from its loader */
    public static int code(ClassLoader from, String iface, String method, String descriptor) {
        try {
            Class<?> i = Class.forName(iface, false, from);
            Method m = only(i, method);
            if (m == null || !descriptor.equals(descriptor(m))) return -1;
            Field f = Class.forName(iface + "$Stub", false, from).getDeclaredField("TRANSACTION_" + method);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** the SHA-256 of an AIDL interface's calls ("<code>:<name>(<parameter types>)" by code), or null */
    public static String fingerprint(String iface) {
        try {
            StringBuilder s = new StringBuilder();
            for (String line : lines(Class.forName(iface), Class.forName(iface + "$Stub"))) {
                if (s.length() > 0) s.append('\n');
                s.append(line);
            }
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static List<String> lines(Class<?> iface, Class<?> stub) throws Exception {
        final List<Object[]> calls = new ArrayList<Object[]>();
        for (Field f : stub.getDeclaredFields()) {
            if (!f.getName().startsWith("TRANSACTION_") || f.getType() != int.class || !Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            String name = f.getName().substring("TRANSACTION_".length());
            Method m = only(iface, name);
            if (m == null) throw new IllegalStateException(name + " is not declared once");
            StringBuilder line = new StringBuilder().append(f.getInt(null)).append(':').append(name).append('(');
            Class<?>[] p = m.getParameterTypes();
            for (int k = 0; k < p.length; k++) line.append(k > 0 ? "," : "").append(p[k].getName());
            calls.add(new Object[] { f.getInt(null), line.append(')').toString() });
        }
        Collections.sort(calls, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return ((Integer) a[0]).compareTo((Integer) b[0]);
            }
        });
        List<String> out = new ArrayList<String>();
        for (Object[] c : calls) out.add((String) c[1]);
        return out;
    }

    /** the one method of that name, or null if there is none or more than one */
    static Method only(Class<?> c, String name) {
        Method found = null;
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (found != null) return null;
            found = m;
        }
        return found;
    }

    /** a JVM method descriptor, e.g. "(III)V" */
    static String descriptor(Method m) {
        StringBuilder s = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) s.append(desc(p));
        return s.append(')').append(desc(m.getReturnType())).toString();
    }

    static String desc(Class<?> c) {
        if (c.isArray()) return "[" + desc(c.getComponentType());
        if (c == int.class) return "I";
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c == short.class) return "S";
        if (c == char.class) return "C";
        return "L" + c.getName().replace('.', '/') + ";";
    }
}
