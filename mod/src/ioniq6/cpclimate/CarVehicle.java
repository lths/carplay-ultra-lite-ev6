package ioniq6.cpclimate;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The car's page in CarPlay's Vehicle app (VehiclePage), filled from the car and acted on:
 *
 * - Live readings, every few seconds while the phone is on a vehicle state stream: battery level, range and
 *   odometer from the HSCAN service's getters (tm_hscan_service; the ones the EV routing mod and ev-calib read),
 *   shown in the unit the car is set to. Getters only: no listener is registered and nothing is written, and
 *   HMG-API's own vehicle readings are not used (its vehicle service crashes system_server on the Ioniq 6).
 *   The calls are looked up by name and signature (CarApi) in com.hkmc.ext.hcan.jar; one this firmware doesn't
 *   have leaves its line at a dash. The charging and tyre status getters are read and logged only, until what
 *   their values mean has been seen on the car.
 * - Buttons that open a screen on the head unit: the car's home screen, the HMG-API app, NEMESIS Manager, the
 *   ambient light app, and the car's own radio, EV and vehicle settings screens. Only the ones this unit has.
 * - The ambient light effect's switch (Light Vibes): DiPO can't write another app's settings, so the request
 *   goes to a file that the mod's root service (svc/helper.sh) applies, and the state comes back the same way.
 * - Battery preconditioning's switch, through Launchpad (paytondev/gen5w-preconditioning): its own Start and Stop
 *   go to its PreconditioningService, so Launchpad stays the one sender of the car's commands, with its firmware
 *   check, warm-battery advice, two-minute timeout and recovery. That service isn't exported, but the system uid
 *   DiPO runs as may start it, as the head unit's own system apps may. Never Start anyway: a warm battery's advice
 *   stands. The switch shows on while Launchpad holds a request (its journal, read by the root service) or the
 *   car reports conditioning (BCM getBattCondIndSta).
 */
final class CarVehicle extends Thread implements VehiclePage.Listener {
    static final String HSCAN = "tm_hscan_service", HSCAN_IFACE = "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";
    static final String HSCAN_JAR = "/system/framework/com.hkmc.ext.hcan.jar";
    static final String USM = "com.hkmc.micom.usm";
    static final long PERIOD_MS = 5000;
    static final String LIGHTS_REQUEST = "cpc-lights-req", LIGHTS_STATE = "cpc-lights-state";
    static final String S_CAR = "Vehicle", S_LIGHTS = "Ambient light", S_OPEN = "Head unit";
    /** a screen a button opens: its id, label, symbol, then package[/class] candidates, first one installed wins */
    static final String[][] SCREENS = {
            { "open_hmgapi", "HMG-API", "puzzlepiece.extension", "hmgapi.app/.MainActivity" },
            { "open_nemesis", "NEMESIS Manager", "shippingbox", "com.nemesis.manager/.MainActivity" },
            { "open_radio", "Radio", "radio", "com.hkmc.av.app.radiona" },
            { "open_ev", "EV", "bolt.car", "com.hkmc.daudio.app.ev3" },
            { "open_vehicle_settings", "Vehicle settings", "gearshape", "com.android.vehiclesettings" },
    };
    static final String[] LIGHT_APPS = { "org.gen5w.ambientsound", "lightvibes.app" };
    /** Launchpad: its current package, then the one older releases used */
    static final String[] LAUNCHPAD_APPS = { "com.paytondev.launchpad", "com.paytondev.precondition" };
    static final String LAUNCHPAD_SERVICE = "com.paytondev.launchpad.PreconditioningService";
    static final String PRECONDITION_START = "com.paytondev.launchpad.PRECONDITION_START";
    static final String PRECONDITION_STOP = "com.paytondev.launchpad.PRECONDITION_STOP";
    static final String PRECOND_STATE = "cpc-precond-state";
    static final String HEV = "com.hkmc.micom.hev", HEV_IFACE = "com.hkmc.hev.IHEVService";
    static final String BCM = "com.hkmc.micom.bcm";
    /** how long the switch keeps what was just pressed, while Launchpad and the root service catch up */
    static final long PRECOND_HOLD_MS = 8000;
    static final long LIGHTS_HOLD_MS = 4000;

    final Context context;
    final File dir;
    final VehicleState[] state;
    final VsChannel[] lanes;
    final VsChannel.Log out;
    final VehiclePage page = new VehiclePage();
    private final Map<String, Intent> screens = new LinkedHashMap<String, Intent>();
    private final LinkedBlockingQueue<Runnable> work = new LinkedBlockingQueue<Runnable>();
    private boolean lights;
    private Intent launchpad;
    private volatile long precondAskedAt, lightsAskedAt;
    private volatile boolean precondAskedOn;
    private int txCondIndicator = -1, txHevStatus = -1;
    private int txOdometer = -1, txRange = -1, txSoc = -1, txChargeStatus = -1, txChargeFinish = -1, txChargeTime = -1, txUnit = -1;
    private final int[] txTyres = { -1, -1, -1, -1 };
    private IBinder hscan;
    private int failures;

    /**
     * Setting drive_log (a test aid): more of the car's getters, read with the others every 5 s while CarPlay is
     * connected and written to DRIVE_LOG in DiPO's files folder when a value changed. Getters only, found by name
     * and descriptor; none takes anything but the one byte HSCAN's unit-less calls take (0). For working out what
     * the car reports on the move (tyre states need the wheels to have turned) before any of it becomes a row.
     */
    static volatile boolean driveLog;
    static final String DRIVE_LOG = "cpc-drive.log";
    static final long DRIVE_LOG_MAX = 4L << 20;
    /** service, interface, method, descriptor */
    static final String[][] DRIVE_PROBES = {
            { HSCAN, HSCAN_IFACE, "getCarSpeed", "(B)F" }, { HSCAN, HSCAN_IFACE, "getGearPosition", "()I" },
            { HSCAN, HSCAN_IFACE, "getGearType", "()I" }, { HSCAN, HSCAN_IFACE, "realTimeAbleDriveDistance", "()I" },
            { HSCAN, HSCAN_IFACE, "ableDistanceCaseAirConOff", "()F" }, { HSCAN, HSCAN_IFACE, "getDistanceEmptyInfo", "()I" },
            { HSCAN, HSCAN_IFACE, "driveLineEleConsum", "()F" }, { HSCAN, HSCAN_IFACE, "airConEleConsum", "()I" },
            { HSCAN, HSCAN_IFACE, "electricConsum", "()I" }, { HSCAN, HSCAN_IFACE, "heaterEleConsum", "()I" },
            { HSCAN, HSCAN_IFACE, "getBatSoc", "()I" }, { HSCAN, HSCAN_IFACE, "getStartOdometer", "(B)F" },
            { HSCAN, HSCAN_IFACE, "getSpeedUnit", "()I" }, { HSCAN, HSCAN_IFACE, "getTripUnit", "()B" },
            { HSCAN, HSCAN_IFACE, "getChargeMode", "()I" }, { HSCAN, HSCAN_IFACE, "quickRemainChargeTime", "()J" },
            { HSCAN, HSCAN_IFACE, "remainChargeTime", "()J" }, { HSCAN, HSCAN_IFACE, "getStandardModeCharge", "()I" },
            { HSCAN, HSCAN_IFACE, "getFastModeCharge", "()I" }, { HSCAN, HSCAN_IFACE, "get_BAT_AuxBattOpt", "()B" },
            { BCM, CarApi.BCM, "getSpeed_MPHUnit", "()F" }, { BCM, CarApi.BCM, "getSpeed_KMHUnit", "()F" },
            { BCM, CarApi.BCM, "getDriveTime", "()J" }, { BCM, CarApi.BCM, "getIGNStatus", "()Z" },
            { BCM, CarApi.BCM, "getDrvWdwOpenState", "()I" }, { BCM, CarApi.BCM, "getAstWdwOpenState", "()I" },
            { BCM, CarApi.BCM, "getRLWdwOpenState", "()I" }, { BCM, CarApi.BCM, "getRRWdwOpenState", "()I" },
            { BCM, CarApi.BCM, "getEpbStatus", "()I" }, { BCM, CarApi.BCM, "getBatSocSegment", "()I" },
            { BCM, CarApi.BCM, "getSocStatus", "()I" }, { BCM, CarApi.BCM, "getHsOpnClsState", "()I" },
            { BCM, CarApi.BCM, "getVehicleSlopeState", "()I" }, { BCM, CarApi.BCM, "getSunRoofOpnSta", "()I" },
            { USM, CarApi.USM, "getTireAirPressure", "()I" },
    };
    private final int[] driveTx = new int[DRIVE_PROBES.length];
    private String driveLast;

    /**
     * The page's rows in their order. have: what the unit has (open_home, precondition, lights, a screen's id),
     * null for every row, which is what tools/MkVspi.java writes into the description sent at connect.
     */
    static VehiclePage describe(VehiclePage page, String model, java.util.Set<String> have, boolean lightsOn) {
        page.sectionName(S_CAR, model);
        page.tile(S_CAR, "car").tile(S_LIGHTS, "lightbulb").tile(S_OPEN, "square.grid.2x2");
        if (have == null || have.contains("open_home")) page.button(S_CAR, "open_home", "Car home screen", "house");
        page.line(S_CAR, "battery", "Battery", "battery.100");
        if (have == null || have.contains("precondition"))
            page.toggle(S_CAR, "precondition", "Battery preconditioning", "thermometer.medium", false);
        page.line(S_CAR, "range", "Range", "gauge");
        page.line(S_CAR, "odometer", "Odometer", "speedometer");
        if (have == null || have.contains("lights")) {
            page.toggle(S_LIGHTS, "lights", "Lights follow the music", "light.max", lightsOn);
            page.button(S_LIGHTS, "open_lights", "Light Vibes", "slider.horizontal.3");
        }
        for (String[] s : SCREENS)
            if (have == null || have.contains(s[0])) page.button(S_OPEN, s[0], s[1], s[2]);
        page.line(S_OPEN, "version", "CarPlay Ultra-Lite", "info.circle");
        return page;
    }

    static List<String> ids(VehiclePage page) {
        List<String> l = new java.util.ArrayList<String>();
        for (VehiclePage.Item it : page.items()) l.add(it.id);
        return l;
    }

    /** the car's model, from the firmware's product code (ro.build.product), for the car's section of the page */
    static String model(String product) {
        String p = product == null ? "" : product;
        if (p.startsWith("CEEV")) return "Ioniq 6";
        if (p.startsWith("NEEV")) return "Ioniq 5";
        if (p.startsWith("CVEV")) return "EV6";
        return S_CAR;
    }

    /** the car's section is named after the model the head unit reports */
    CarVehicle(Context context, File dir, VehicleState[] state, VsChannel[] lanes, VsChannel.Log out, String version) {
        super("cp-climate-vehicle");
        this.context = context;
        this.dir = dir;
        this.state = state;
        this.lanes = lanes;
        this.out = out;
        setDaemon(true);
        String product = "";
        try {
            product = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "ro.build.product");
        } catch (Throwable t) {
            // no model: the section keeps its plain name
        }
        // what this unit has, then the rows in their fixed order
        java.util.Set<String> have = new java.util.HashSet<String>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        if (resolves(home)) {
            screens.put("open_home", home);
            have.add("open_home");
        }
        for (String p : LAUNCHPAD_APPS) {
            Intent s = new Intent().setClassName(p, LAUNCHPAD_SERVICE);
            if (launchpad == null && resolvesService(s)) {
                launchpad = s;
                have.add("precondition");
            }
        }
        Intent lightApp = null;
        for (String p : LIGHT_APPS) if (lightApp == null) lightApp = launch(p);
        if (lightApp != null) {
            lights = true;
            screens.put("open_lights", lightApp);
            have.add("lights");
        }
        for (String[] s : SCREENS) {
            Intent i = null;
            for (int k = 3; k < s.length && i == null; k++) i = launch(s[k]);
            if (i == null) continue;
            screens.put(s[0], i);
            have.add(s[0]);
        }
        describe(page, model(product), have, !"0".equals(firstLine(new File(dir, LIGHTS_STATE))));
        // the phone takes the page from the description sent when CarPlay connects (the native part's
        // vehicleStateProtocolInfo, written at build time with every row): a unit without one of them differs
        List<String> full = ids(describe(new VehiclePage(), model(product), null, true)), mine = ids(page);
        if (!full.equals(mine)) out.log("VEH this unit's rows " + mine + " differ from the " + full.size() + " described at connect");
        Map<String, Object> v = new LinkedHashMap<String, Object>();
        v.put("version", version);
        page.update(v);
        page.listener = this;
        // CarPlay's own Radio app (the car's media source in LIVI's description): its play and source buttons
        // open the head unit's radio, which takes the sound; the stations themselves aren't in CarPlay yet
        if (screens.containsKey("open_radio")) {
            VehicleState.mediaControls = new VehicleState.Controls() {
                @Override
                public void pressed(String name, Object value) {
                    if ("play".equals(name) || "changeMediaSource".equals(name) || name.startsWith("tuneTo")) CarVehicle.this.pressed("open_radio");
                }
            };
        }
        out.log("VEH page: " + screens.keySet() + (lights ? ", light switch" : ", no light app")
                + (launchpad != null ? ", preconditioning through " + launchpad.getComponent().getPackageName() : ", no Launchpad"));
    }

    // ---- what the phone does (called under VehicleState's lock: only queue) ----

    @Override
    public void pressed(final String id) {
        work.add(new Runnable() {
            @Override
            public void run() {
                Intent i = screens.get(id);
                if (i == null) return;
                try {
                    context.startActivity(new Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    out.log("VEH opened " + id);
                } catch (Throwable t) {
                    out.log("VEH " + id + " didn't open: " + t);
                }
            }
        });
    }

    @Override
    public void switched(final String id, final boolean on) {
        if ("precondition".equals(id)) {
            precondAskedAt = System.currentTimeMillis();
            precondAskedOn = on;
            work.add(new Runnable() {
                @Override
                public void run() {
                    if (launchpad == null) return;
                    try {
                        // Launchpad decides: Start does nothing unless the car is READY, conditioning is enabled
                        // in its EV settings and nothing runs yet; Stop only ends a request Launchpad made
                        context.startService(new Intent(launchpad).setAction(on ? PRECONDITION_START : PRECONDITION_STOP));
                        out.log("VEH preconditioning " + (on ? "start" : "stop") + " asked of Launchpad");
                    } catch (Throwable t) {
                        out.log("VEH Launchpad didn't take the " + (on ? "start" : "stop") + ": " + t);
                    }
                }
            });
            return;
        }
        if (!"lights".equals(id)) return;
        lightsAskedAt = System.currentTimeMillis();
        work.add(new Runnable() {
            @Override
            public void run() {
                File request = new File(dir, LIGHTS_REQUEST);
                try {
                    FileWriter w = new FileWriter(request);
                    w.write(on ? "1\n" : "0\n");
                    w.close();
                    request.setReadable(true, false);
                    out.log("VEH lights " + (on ? "on" : "off") + " asked");
                } catch (Throwable t) {
                    out.log("VEH lights request not written: " + t);
                }
            }
        });
    }

    void startWorker() {
        Thread t = new Thread("cp-climate-vehicle-do") {
            @Override
            public void run() {
                while (true) {
                    try {
                        work.take().run();
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        out.log("VEH action failed: " + t);
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    // ---- the readings ----

    @Override
    public void run() {
        lookUp();
        startWorker();
        String last = null;
        while (true) {
            try {
                Thread.sleep(PERIOD_MS);
                VehicleState vs = state[0];
                if (vs == null || !(lanes[0].isOpen() || lanes[1].isOpen())) continue;
                Map<String, Object> fresh = new LinkedHashMap<String, Object>();
                boolean miles = intCall(USM, CarApi.USM, txUnit, 0) != 0;
                float soc = floatCall(txSoc);
                int range = hscanInt(txRange, true);
                float odometer = floatCall(txOdometer, true);
                fresh.put("battery", VehiclePage.percent(soc));
                fresh.put("range", txRange < 0 ? VehiclePage.NONE : VehiclePage.distance(range, miles));
                // the cluster drops the odometer's tenths of a km before it converts: 17324.7 km showed as 10,764 mi
                // (17324 km), not 10,765 (2026-11-10)
                fresh.put("odometer", VehiclePage.distance(Math.floor(odometer), miles));
                if (lights) {
                    String s = firstLine(new File(dir, LIGHTS_STATE));
                    // what was just pressed stands until the root service has applied it and reported back
                    if (s != null && !new File(dir, LIGHTS_REQUEST).exists() && System.currentTimeMillis() - lightsAskedAt > LIGHTS_HOLD_MS)
                        fresh.put("lights", !"0".equals(s));
                }
                String precond = "";
                if (launchpad != null) {
                    int indicator = intCall(BCM, CarApi.BCM, txCondIndicator, -1);
                    String owned = firstLine(new File(dir, PRECOND_STATE));
                    fresh.put("precondition", preconditionShown(indicator, "1".equals(owned),
                            System.currentTimeMillis() - precondAskedAt, precondAskedOn));
                    precond = " | conditioning indicator " + indicator + " launchpad " + owned + " enabled "
                            + intCall(HEV, HEV_IFACE, txHevStatus, -1, 94) + " ready " + intCall(HEV, HEV_IFACE, txHevStatus, -1, 44);
                }
                String seen = "soc " + soc + " range " + range + " km odometer " + odometer + " km unit " + (miles ? "mi" : "km")
                        + " | charging status " + hscanInt(txChargeStatus, false) + " finish " + hscanInt(txChargeFinish, false)
                        + " time " + hscanInt(txChargeTime, false) + " | tyres " + hscanInt(txTyres[0], false) + " "
                        + hscanInt(txTyres[1], false) + " " + hscanInt(txTyres[2], false) + " " + hscanInt(txTyres[3], false) + precond;
                if (!seen.equals(last)) {
                    out.log("VEH " + seen);
                    last = seen;
                }
                if (driveLog) driveLog(seen);
                Map<Long, Object> changed = page.update(fresh);
                if (!changed.isEmpty()) lanes[1].deliver(vs.fromCar(changed));
                failures = 0;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                hscan = null;
                if (failures++ < 5) out.log("VEH read failed: " + t);
            }
        }
    }

    /** the HSCAN calls by name; its interface isn't on the boot class path, so from its own jar */
    private void lookUp() {
        ClassLoader cl = CarVehicle.class.getClassLoader();
        try {
            Class.forName(HSCAN_IFACE, false, cl);
        } catch (Throwable notLoaded) {
            try {
                cl = new dalvik.system.PathClassLoader(HSCAN_JAR, cl);
            } catch (Throwable t) {
                out.log("VEH no HSCAN interface: " + t);
            }
        }
        int[] tx = hscanCalls(cl);
        txOdometer = tx[0];
        txRange = tx[1];
        txSoc = tx[2];
        txChargeStatus = tx[3];
        txChargeFinish = tx[4];
        txChargeTime = tx[5];
        System.arraycopy(tx, 6, txTyres, 0, 4);
        txUnit = CarApi.code(CarApi.USM, "getDistanceSpeedUnit", "()I");
        txCondIndicator = CarApi.code(CarApi.BCM, "getBattCondIndSta", "()I");
        txHevStatus = CarApi.code(HEV_IFACE, "getHEVStatus", "(I)I");
        out.log("VEH car calls " + calls() + ", conditioning " + txCondIndicator + "/" + txHevStatus);
        if (driveLog) {
            int found = 0;
            for (int i = 0; i < DRIVE_PROBES.length; i++) {
                String[] p = DRIVE_PROBES[i];
                driveTx[i] = HSCAN.equals(p[0]) ? CarApi.code(cl, p[1], p[2], p[3]) : CarApi.code(p[1], p[2], p[3]);
                if (driveTx[i] >= 0) found++;
            }
            out.log("VEH drive log on: " + found + " of " + DRIVE_PROBES.length + " getters found, to files/" + DRIVE_LOG);
        }
    }

    /** one getter's answer as text: a float for F, else the number; "x" if the call failed, "-" if it isn't there */
    private String probe(int i) {
        if (driveTx[i] < 0) return "-";
        String[] p = DRIVE_PROBES[i];
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder b = HSCAN.equals(p[0]) ? hscan() : CarClimate.service(p[0]);
            if (b == null) return "x";
            data.writeInterfaceToken(p[1]);
            if (p[3].startsWith("(B)")) data.writeByte((byte) 0);
            b.transact(driveTx[i], data, reply, 0);
            reply.readException();
            if (p[3].endsWith("F")) return String.valueOf(reply.readFloat());
            if (p[3].endsWith("J")) return String.valueOf(reply.readLong());
            return String.valueOf(reply.readInt());
        } catch (Throwable t) {
            return "x";
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** the drive log's line: the usual readings, then every probe as name=value; written only when it changed */
    private void driveLog(String seen) {
        StringBuilder b = new StringBuilder(seen);
        for (int i = 0; i < DRIVE_PROBES.length; i++) b.append(' ').append(DRIVE_PROBES[i][2]).append('=').append(probe(i));
        String line = b.toString();
        if (line.equals(driveLast)) return;
        driveLast = line;
        File f = new File(dir, DRIVE_LOG);
        if (f.length() > DRIVE_LOG_MAX) return;
        try {
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " " + line + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            if (failures++ < 5) out.log("VEH drive log not written: " + t);
        }
    }

    /**
     * Whether the preconditioning switch shows on: what was just pressed, for a few seconds while Launchpad and
     * the root service catch up; after that, on while Launchpad holds a request (pending or active) or the car
     * reports conditioning (indicator 1, which also covers conditioning the car started for a navigation route).
     */
    static boolean preconditionShown(int indicator, boolean launchpadRequested, long sinceAskedMs, boolean askedOn) {
        if (sinceAskedMs >= 0 && sinceAskedMs < PRECOND_HOLD_MS) return askedOn;
        return indicator == 1 || launchpadRequested;
    }

    /** odometer, range, battery level, charging status, finish and time, then the four tyres; -1 for one not there */
    static final String[][] HSCAN_CALLS = { { "getOdometer", "(B)F" }, { "ableDriveDistance", "(B)I" }, { "remainBatteryPercentage", "()F" },
            { "getChargingStatus", "()I" }, { "getChargingFinish", "()I" }, { "getRemainChargingTime", "()I" },
            { "getFrontLeftTirePressureStatus", "()B" }, { "getFrontRightTirePressureStatus", "()B" },
            { "getRearLeftTirePressureStatus", "()B" }, { "getRearRightTirePressureStatus", "()B" } };
    static final int[] HSCAN_IONIQ6 = { 1, 73, 74, 86, 83, 150, 176, 177, 178, 179 };

    static int[] hscanCalls(ClassLoader cl) {
        int[] tx = new int[HSCAN_CALLS.length];
        for (int i = 0; i < tx.length; i++) tx[i] = CarApi.code(cl, HSCAN_IFACE, HSCAN_CALLS[i][0], HSCAN_CALLS[i][1]);
        return tx;
    }

    /** the calls found, and whether they are the Ioniq 6's (CEEV 260507) numbers */
    String calls() {
        int[] got = { txOdometer, txRange, txSoc, txChargeStatus, txChargeFinish, txChargeTime, txTyres[0], txTyres[1], txTyres[2], txTyres[3] };
        int[] ioniq6 = HSCAN_IONIQ6;
        StringBuilder s = new StringBuilder();
        boolean same = true;
        for (int i = 0; i < got.length; i++) {
            s.append(i > 0 ? "," : "").append(got[i]);
            same &= got[i] == ioniq6[i];
        }
        return s + (same ? " (as the Ioniq 6's)" : " (not the Ioniq 6's)") + ", unit " + txUnit;
    }

    private float floatCall(int code) throws Exception {
        return floatCall(code, false);
    }

    /** an HSCAN getter answering a float; withByte: it takes one byte argument, 0 (as ev-calib passes) */
    private float floatCall(int code, boolean withByte) throws Exception {
        if (code < 0) return Float.NaN;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HSCAN_IFACE);
            if (withByte) data.writeByte((byte) 0);
            hscan().transact(code, data, reply, 0);
            reply.readException();
            return reply.readFloat();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** an HSCAN getter answering an int or byte; -1 if this firmware has no such call */
    private int hscanInt(int code, boolean withByte) throws Exception {
        if (code < 0) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HSCAN_IFACE);
            if (withByte) data.writeByte((byte) 0);
            hscan().transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private IBinder hscan() throws Exception {
        if (hscan == null) hscan = CarClimate.service(HSCAN);
        if (hscan == null) throw new IllegalStateException("no " + HSCAN);
        return hscan;
    }

    /** a getter taking int arguments (none or more) on another service; fallback if it isn't there or fails */
    private int intCall(String service, String iface, int code, int fallback, int... args) {
        if (code < 0) return fallback;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder b = CarClimate.service(service);
            if (b == null) return fallback;
            data.writeInterfaceToken(iface);
            for (int a : args) data.writeInt(a);
            b.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            return fallback;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ---- the head unit's screens ----

    private boolean resolvesService(Intent i) {
        try {
            return context != null && context.getPackageManager().resolveService(i, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean resolves(Intent i) {
        try {
            return context != null && context.getPackageManager().resolveActivity(i, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** an intent opening "package" (its main activity) or "package/class"; null if this unit doesn't have it */
    private Intent launch(String target) {
        if (context == null) return null;
        try {
            int slash = target.indexOf('/');
            if (slash > 0) {
                String pkg = target.substring(0, slash), cls = target.substring(slash + 1);
                Intent i = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, cls.startsWith(".") ? pkg + cls : cls));
                return resolves(i) ? i : null;
            }
            Intent i = context.getPackageManager().getLaunchIntentForPackage(target);
            if (i != null) return i;
            // the car's own apps have no launcher category: the stock home screen lists them itself
            List<ResolveInfo> mains = context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).setPackage(target), 0);
            if (mains == null || mains.isEmpty()) return null;
            ResolveInfo r = mains.get(0);
            return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
        } catch (Throwable t) {
            return null;
        }
    }

    static String firstLine(File f) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            try {
                String s = r.readLine();
                return s == null ? null : s.trim();
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
