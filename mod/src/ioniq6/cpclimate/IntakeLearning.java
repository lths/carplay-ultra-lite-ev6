package ioniq6.cpclimate;

import android.os.IBinder;
import android.os.Parcel;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import hmgapi.hook.*;

/** Passive capture of a stock ON -> fresh-air transition. The observer never sends a vehicle command. */
final class IntakeLearning {
    static final Set<Method> installed = new HashSet<Method>();
    static volatile boolean observing;
    static int warnings;
    static boolean stockApp(Context context, String pkg) {
        if (context == null || pkg == null || CpClimate.DIPO.equals(pkg)) return false;
        try {
            PackageManager pm = context.getPackageManager();
            return (pm.getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || pm.checkSignatures(CpClimate.DIPO, pkg) == PackageManager.SIGNATURE_MATCH;
        } catch (Throwable unavailable) { return false; }
    }
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
    /** Preinstall framework proxy hooks before apps start; appLoaded also tries an app's own loader. */
    static void zygote(ModContext mod) { install(mod, IntakeLearning.class.getClassLoader()); }
    static void appLoaded(ModContext mod, LoadedApp app) {
        observing = stockApp(app.context, app.packageName);
        if (!observing) return;
        install(mod, app.classLoader);
        mod.log("CAR intake observer app=" + app.packageName + " uid=" + android.os.Process.myUid() + " methods=" + installed.size());
    }
    static synchronized void warning(ModContext mod, Throwable t) {
        if (warnings++ < 8) mod.log("CAR intake handoff unavailable: " + t.getClass().getSimpleName());
    }
    static synchronized void install(final ModContext mod, ClassLoader loader) {
        if (!CarApi.ev6Target(product())) return; // Passive observer; DiPO separately validates flags before accepting candidates.
        for (final int kind : new int[] { ClimateMap.VR, ClimateMap.FRONT }) {
            final String name = kind == ClimateMap.VR ? "setVRClimateStatus" : "setFrontClimateControl";
            try {
                Class<?> proxy = Class.forName(CarApi.DATC + "$Stub$Proxy", false, loader);
                Class<?>[] args = kind == ClimateMap.VR ? new Class<?>[] { int.class, int.class, int.class } : new Class<?>[] { int.class, int.class };
                Method method = proxy.getDeclaredMethod(name, args);
                if (method.getReturnType() != void.class || installed.contains(method)) continue;
                MethodHook hook = new MethodHook() {
                    final ThreadLocal<Capture> pending = new ThreadLocal<Capture>();
                    @Override protected void beforeHookedMethod(Param p) {
                        Capture prior = pending.get(); pending.remove();
                        if (prior != null) prior.close();
                        if (!observing || !mod.enabled()) return;
                        LocalSocket socket = null;
                        try {
                            ClimateMap.Command c = new ClimateMap.Command(kind, (Integer) p.args[0], (Integer) p.args[1], kind == ClimateMap.VR ? (Integer) p.args[2] : 0);
                            if (!IntakeProtocol.candidate(c)) return;
                            socket = new LocalSocket();
                            socket.connect(new LocalSocketAddress(IntakeProtocol.SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
                            socket.setSoTimeout(IntakeProtocol.TIMEOUT_MS);
                            IntakeProtocol.write(socket.getOutputStream(), c);
                            int before = new DataInputStream(socket.getInputStream()).readInt();
                            if (before == 1 || before == 2) { pending.set(new Capture(p, socket)); socket = null; }
                        } catch (Throwable t) { warning(mod, t); /* The stock operation always proceeds. */ }
                        finally { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }
                    }
                    @Override protected void afterHookedMethod(Param p) {
                        final Capture capture = pending.get(); pending.remove();
                        if (capture == null) return;
                        try {
                            DataOutputStream d = new DataOutputStream(capture.socket.getOutputStream());
                            d.writeInt(capture.param == p && !p.hasThrowable() && mod.enabled() ? 1 : 0); d.flush();
                        } catch (Throwable t) { warning(mod, t); }
                        finally { capture.close(); }
                    }
                };
                if (kind == ClimateMap.VR) Hooks.findAndHookMethod(proxy, name, int.class, int.class, int.class, hook);
                else Hooks.findAndHookMethod(proxy, name, int.class, int.class, hook);
                installed.add(method);
                mod.log("CAR observing Kia climate command " + name + " (local handoff)");
            } catch (Throwable t) { mod.log("CAR intake observer unavailable " + name + ": " + t.getClass().getSimpleName()); }
        }
    }
    static final class Capture {
        final MethodHook.Param param;
        final LocalSocket socket;
        Capture(MethodHook.Param p, LocalSocket socket) { param = p; this.socket = socket; }
        void close() { try { socket.close(); } catch (IOException ignored) { } }
    }
}
