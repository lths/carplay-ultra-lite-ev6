package ioniq6.cpclimate;

import hmgapi.hook.Hooks;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;

/** Passive, bounded logging of the stock touchscreen's commands. Never changes arguments or replies. */
final class StockTrace {
    private static int lines;
    private static boolean installed;

    static synchronized void install(final ModContext mod, final LoadedApp app) {
        if (installed || app.packageName == null) return;
        String pkg = app.packageName;
        if (!(pkg.startsWith("com.hkmc.") || pkg.startsWith("com.daudio.") || pkg.contains("climate")
                || pkg.contains("vehiclesettings"))) return;
        installed = true;
        if (!mod.settingBoolean("trace_stock_climate", true)) return;
        trace(mod, app, CarApi.DATC, "setVRClimateStatus", 3);
        trace(mod, app, CarApi.DATC, "setFrontClimateControl", 2);
        trace(mod, app, CarApi.USM, "setFrontSeatHeatDrvStep", 1);
        trace(mod, app, CarApi.USM, "setFrontSeatHeatAstStep", 1);
    }

    static void trace(final ModContext mod, final LoadedApp app, String iface, final String name, int count) {
        try {
            Object[] parameters = new Object[count + 1];
            for (int i = 0; i < count; i++) parameters[i] = int.class;
            parameters[count] = new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    try {
                        synchronized (StockTrace.class) { if (lines++ >= 160) return; }
                        mod.log("STOCK " + app.packageName + " " + name + java.util.Arrays.toString(p.args));
                    } catch (Throwable ignored) { /* A diagnostic must never interrupt the stock command. */ }
                }
            };
            Hooks.findAndHookMethod(iface + "$Stub$Proxy", app.classLoader, name, parameters);
            mod.log("STOCK observing " + app.packageName + " " + name);
        } catch (Throwable unavailable) {
            mod.log("STOCK observer unavailable " + app.packageName + " " + name + ": " + unavailable.getClass().getSimpleName());
        }
    }
}
