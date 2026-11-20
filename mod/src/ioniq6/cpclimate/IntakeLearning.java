package ioniq6.cpclimate;

import android.os.IBinder;
import android.os.Parcel;
import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import hmgapi.hook.*;

/** Passive capture of a stock ON -> fresh-air transition. The observer never sends a vehicle command. */
final class IntakeLearning {
    static final File DIR = new File("/data/data/" + CpClimate.DIPO + "/files");
    static final AtomicLong sequence = new AtomicLong();
    static String product() {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "ro.build.product"); }
        catch (Throwable missing) { return ""; }
    }
    static int frontSelector() {
        Integer found = null;
        try {
            Class<?> type = Class.forName("com.hkmc.datc.DATCFlag$DATCControl");
            for (String name : new String[] { "INTAKE", "AIR_INTAKE", "RECIRCULATION" }) {
                try {
                    Field f = type.getField(name);
                    if (f.getType() != int.class) continue;
                    int value = f.getInt(null);
                    if (found != null && found != value) return -1;
                    if (value >= 0 && value <= 255) found = value;
                } catch (NoSuchFieldException absent) { }
            }
        } catch (Throwable unavailable) { }
        return found == null ? -1 : found;
    }
    static int read() {
        int code = CarApi.code(CarApi.DATC, "getVRClimateStatus", "(I)F");
        if (code < 0) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder b = CarClimate.service(CarClimate.DATC);
            if (b == null) return -1;
            data.writeInterfaceToken(CarApi.DATC); data.writeInt(11);
            if (!b.transact(code, data, reply, 0)) return -1;
            reply.readException(); float raw = reply.readFloat();
            return raw == 1 ? 1 : raw == 2 ? 2 : -1;
        } catch (Throwable unavailable) { return -1; }
        finally { data.recycle(); reply.recycle(); }
    }
    static void install(final ModContext mod, LoadedApp app) {
        final String product = product();
        if (CpClimate.DIPO.equals(app.packageName) || !CarApi.ev6Target(product) || !CarApi.climateFlagMismatches().isEmpty()) return;
        final int front = frontSelector();
        for (final int kind : new int[] { ClimateMap.VR, ClimateMap.FRONT }) {
            if (kind == ClimateMap.FRONT && front < 0) continue;
            final String name = kind == ClimateMap.VR ? "setVRClimateStatus" : "setFrontClimateControl";
            try {
                MethodHook hook = new MethodHook() {
                    final ThreadLocal<Capture> pending = new ThreadLocal<Capture>();
                    @Override protected void beforeHookedMethod(Param p) {
                        try {
                            pending.remove();
                            int selector = (Integer) p.args[0];
                            if (selector != (kind == ClimateMap.VR ? 11 : front)) return;
                            int before = read();
                            long ticket = sequence.incrementAndGet();
                            if (before != 1) return;
                            ClimateMap.Command c = new ClimateMap.Command(kind, selector, (Integer) p.args[1], kind == ClimateMap.VR ? (Integer) p.args[2] : 0);
                            if (FreshAirProfile.permitted(c, front)) pending.set(new Capture(p, ticket, c));
                        } catch (Throwable ignored) { /* The stock operation always proceeds. */ }
                    }
                    @Override protected void afterHookedMethod(Param p) {
                        final Capture capture = pending.get(); pending.remove();
                        if (capture == null || capture.param != p || p.hasThrowable()) return;
                        Thread confirm = new Thread(new Runnable() { public void run() {
                            try {
                                Thread.sleep(400); int first = read();
                                Thread.sleep(500); int second = read();
                                if (sequence.get() != capture.ticket || !FreshAirProfile.transition(1, first, second)) return;
                                new FreshAirProfile(product, capture.command).save(DIR);
                                mod.log("CAR learned Kia fresh-air command " + capture.command);
                            } catch (Throwable t) { mod.log("CAR fresh-air capture unavailable: " + t.getClass().getSimpleName()); }
                        } }, "cp-climate-intake-learn");
                        confirm.setDaemon(true); confirm.start();
                    }
                };
                if (kind == ClimateMap.VR) Hooks.findAndHookMethod(CarApi.DATC + "$Stub$Proxy", app.classLoader, name, int.class, int.class, int.class, hook);
                else Hooks.findAndHookMethod(CarApi.DATC + "$Stub$Proxy", app.classLoader, name, int.class, int.class, hook);
                mod.log("CAR observing Kia intake command " + name);
            } catch (Throwable t) { mod.log("CAR intake observer unavailable " + name + ": " + t.getClass().getSimpleName()); }
        }
    }
    static final class Capture {
        final MethodHook.Param param;
        final long ticket;
        final ClimateMap.Command command;
        Capture(MethodHook.Param p, long ticket, ClimateMap.Command command) { this.param = p; this.ticket = ticket; this.command = command; }
    }
}
