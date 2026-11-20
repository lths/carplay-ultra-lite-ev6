package ioniq6.cpclimate;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * M4: the car's climate on CarPlay, both ways. Once a second (while the phone is on a vehicle
 * state stream) it reads DATCService's voice-control status flags and BCMService's seat heat
 * states, adds the temperatures DATCService's callback reports, and hands the result
 * (ClimateMap) to the session's VehicleState, which tells the phone what changed. What the phone
 * writes comes back as phoneWrote and goes to the car, in order, on a thread of its own, as
 * the commands ClimateMap.commands chooses (the ones tested on the car on 2026-11-07).
 */
final class CarClimate extends Thread implements VehicleState.Car {
    static volatile CarClimate instance;
    static final String DATC = "com.hkmc.micom.datc", DATC_IFACE = "com.hkmc.datc.IDATCService";
    static final String LISTENER_IFACE = "com.hkmc.datc.IDATCListener";
    static final String USM = "com.hkmc.micom.usm", USM_IFACE = "com.hkmc.usm.IUSMService";
    /** DATCService listener call (valid because its fingerprint is checked before registering) */
    static final int ON_TEMPERATURES = 0x1;
    static final int F_FAN = 5, F_MODE = 6, F_AUTO = 8, F_AC = 9, F_DEFROST = 10, F_INTAKE = 11, F_ON = 12;
    static final int F_DRIVER_F = 2, F_PASSENGER_RAW = 29, F_SYNC = 22;
    static final long PERIOD_MS = 1000;
    /** the rear defroster: its state from BCMService's listener, set through VRService (1 on, 0 off; READY only) */
    static final String BCM = "com.hkmc.micom.bcm", BCM_IFACE = "com.hkmc.bcm.IBCMService";
    static final String BCM_LISTENER_IFACE = "com.hkmc.bcm.IBCMListener";
    static final String VR_SERVICE = "com.hkmc.vr", VR_IFACE = "com.hkmc.vr.IVRService";
    /** BCMService listener calls (valid because its fingerprint is checked before registering) */
    static final int BCM_LAST_CALL = 0x37;
    static final int ON_TELE_HEATING = 0x14, ON_DIGITAL_KEY_DATA = 0x25, ON_MODEM_INFO = 0x27;
    /** the rear_defrost setting (CpClimate) */
    static volatile boolean rearDefrost;

    /** the car's calls, looked up by name and signature in its framework (CarApi); -1: not on this firmware */
    static final int txRegisterDatc = CarApi.code(CarApi.DATC, "registerDATCListener", "(Lcom/hkmc/datc/IDATCListener;Landroid/os/IBinder;)V");
    static final int txUnit = CarApi.code(CarApi.DATC, "getTemperatureUnit", "()I");
    static final int txDriverOnly = CarApi.code(CarApi.DATC, "getDriverOnlyValue", "()I");
    static final int txGetStatus = CarApi.code(CarApi.DATC, "getVRClimateStatus", "(I)F");
    static final int txSetStatus = CarApi.code(CarApi.DATC, "setVRClimateStatus", "(III)V");
    static final int txSetFront = CarApi.code(CarApi.DATC, "setFrontClimateControl", "(II)V");
    static final int txDriverSeat = CarApi.code(CarApi.USM, "getFrontSeatHeat_DrvState", "()I");
    static final int txPassengerSeat = CarApi.code(CarApi.USM, "getFrontSeatHeat_AstState", "()I");
    static final int txSetDriverSeat = CarApi.code(CarApi.USM, "setFrontSeatHeatDrvStep", "(I)V");
    static final int txSetPassengerSeat = CarApi.code(CarApi.USM, "setFrontSeatHeatAstStep", "(I)V");
    static final int txRear = CarApi.code(CarApi.VR, "set_RrDefogger_Cmd_AVN", "(I)V");
    static final int txRegisterBcm = CarApi.code(CarApi.BCM, "registerBCMListener", "(Lcom/hkmc/bcm/IBCMListener;Landroid/os/IBinder;)V");
    static final int txBodyDriverSeat = CarApi.code(CarApi.BCM, "getDrvSeatHeatState", "()I");
    static final int txBodyPassengerSeat = CarApi.code(CarApi.BCM, "getAstSeatHeatState", "()I");
    static final int txDriverSeatOption = CarApi.code(CarApi.BCM, "getDrvSeatHeatOption", "()I");
    static final int txPassengerSeatOption = CarApi.code(CarApi.BCM, "getAstSeatHeatOption", "()I");
    static final int txWheel = CarApi.code(CarApi.VR, "set_StrgWhlHeated_Cmd_AVN", "(I)V");
    /** the listeners are answered by hand, so only when they are exactly the ones this was written for */
    static final boolean datcListenerOk = CarApi.DATC_LISTENER_PRINT.equals(CarApi.fingerprint(CarApi.DATC_LISTENER));
    static final boolean bcmListenerOk = CarApi.BCM_LISTENER_PRINT.equals(CarApi.fingerprint(CarApi.BCM_LISTENER));
    static final List<String> climateFlagMismatches = CarApi.climateFlagMismatches();
    static final boolean climateFlagsOk = climateFlagMismatches.isEmpty();

    final VehicleState[] state;
    final VsChannel[] lanes;
    final VsChannel.Log out;
    private IBinder datc;
    private final Temperatures temps = new Temperatures();
    private int failures;
    private volatile int unit = -1;
    private final LinkedBlockingQueue<ClimateMap.Command> commands = new LinkedBlockingQueue<ClimateMap.Command>();
    private final Body body = new Body();
    private final Binder bodyToken = new Binder();
    private IBinder bcm;
    private boolean bodyTried;
    private volatile int driverSeatState = -1, passengerSeatState = -1, driverSeatOption = -1, passengerSeatOption = -1;
    private volatile long seatReadAt;
    private String lastSeatFeedback;

    /**
     * BCMService's listener, answered exactly as the stock com.hkmc.bcm.IBCMListener.Stub (automotive.jar,
     * CEEV 260507) does: 55 calls (1-0x37), all plain reads and writeNoException, except onDigitalKeyDataChanged
     * (0x25) and onModemInfoChanged (0x27), whose byte[] is an out parameter: the length is read and an array of
     * that length must go back, or system_server's proxy throws "bad array lengths" and the unit reboots (what
     * HMG-API's vehicle listener did, see the boot loop of 2026-11-07). Nothing here may throw back into
     * system_server. onRemoteTeleHeatingStatusChanged (0x14) is (mStWheelHeatState, mDefoggerState), sent on
     * every change and once at registration.
     */
    static final class Body extends Binder {
        volatile int defogger = -1, wheel = -1;

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(BCM_LISTENER_IFACE);
                return true;
            }
            if (code < FIRST_CALL_TRANSACTION || code > BCM_LAST_CALL) return false;
            boolean array = code == ON_DIGITAL_KEY_DATA || code == ON_MODEM_INFO;
            byte[] back = null;
            try {
                data.enforceInterface(BCM_LISTENER_IFACE);
                if (array) {
                    int n = data.readInt();
                    back = n < 0 ? null : new byte[n];
                } else if (code == ON_TELE_HEATING) {
                    int w = data.readInt();
                    int d = data.readInt();
                    wheel = w;
                    defogger = d;
                }
            } catch (Throwable t) {
                // a call we can't read: answer it all the same
            }
            if (reply != null) {
                reply.writeNoException();
                if (array) reply.writeByteArray(back);
            }
            return true;
        }
    }

    /** DATCService's temperature callback (synchronous: every call is answered at once) */
    static final class Temperatures extends Binder {
        volatile float driverC = Float.NaN, passengerC = Float.NaN, passengerF = Float.NaN;
        volatile int driverState = -1, passengerState = -1;

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(LISTENER_IFACE);
                return true;
            }
            try {
                if (code == ON_TEMPERATURES) {
                    data.enforceInterface(LISTENER_IFACE);
                    float dc = data.readFloat();
                    int ds = data.readInt();
                    data.readFloat();
                    data.readInt();
                    float pc = data.readFloat();
                    int ps = data.readInt();
                    float pf = data.readFloat();
                    driverC = dc;
                    passengerF = pf;
                    driverState = ds;
                    passengerC = pc;
                    passengerState = ps;
                }
            } catch (Throwable t) {
                // a callback we can't read: keep the last values
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    }

    CarClimate(VehicleState[] state, VsChannel[] lanes, VsChannel.Log out) {
        super("cp-climate-car");
        this.state = state;
        this.lanes = lanes;
        this.out = out;
        setDaemon(true);
        instance = this;
    }

    /** Explicit Vehicle-page ventilation controls, using the same documented USM step as the stock climate app. */
    boolean comfortPressed(String id) {
        boolean driver = id.startsWith("driver_"), passenger = id.startsWith("passenger_");
        if (!driver && !passenger) return false;
        boolean off = id.endsWith("_seat_off");
        int step = off ? 2 : id.endsWith("_vent_low") ? 3 : id.endsWith("_vent_medium") ? 4 : id.endsWith("_vent_high") ? 5 : -1;
        if (step < 0) return false;
        int raw = driver ? driverSeatState : passengerSeatState;
        int option = driver ? driverSeatOption : passengerSeatOption;
        if (!seatFeedbackFresh() || !ClimateMap.seatStateKnown(raw) || (!off && option != 6)) {
            out.log("CAR " + id + " withheld: seat state " + raw + ", option " + option + " (ventilation requires option 6)");
            return true;
        }
        commands.add(new ClimateMap.Command(ClimateMap.SEAT, driver ? ClimateMap.SET_DRIVER_SEAT : ClimateMap.SET_PASSENGER_SEAT, step, 0));
        return true;
    }

    void wheelHeat(boolean on) {
        if (txWheel < 0 || (body.wheel != 0 && body.wheel != 1)) {
            out.log("CAR wheel heat withheld: method or live state unavailable");
            return;
        }
        commands.add(new ClimateMap.Command(ClimateMap.WHEEL, on ? 1 : 0, 0, 0));
    }

    Map<String, Object> comfortValues() {
        Map<String, Object> values = new java.util.LinkedHashMap<String, Object>();
        values.put("driver_seat_status", ClimateMap.seatText(seatFeedbackFresh() ? driverSeatState : -1) + (driverSeatOption == 6 ? "" : " (ventilation unavailable)"));
        values.put("passenger_seat_status", ClimateMap.seatText(seatFeedbackFresh() ? passengerSeatState : -1) + (passengerSeatOption == 6 ? "" : " (ventilation unavailable)"));
        values.put("wheel_heat_status", body.wheel == 0 ? "Off" : body.wheel == 1 ? "On" : "State unavailable (control withheld)");
        if (body.wheel == 0 || body.wheel == 1) values.put("wheel_heat", body.wheel == 1);
        return values;
    }

    boolean seatFeedbackFresh() {
        long age = System.currentTimeMillis() - seatReadAt;
        return seatReadAt > 0 && age >= 0 && age <= 3 * PERIOD_MS;
    }

    @Override
    public void phoneWrote(Map<Long, Object> written, Map<Long, Object> now) {
        List<ClimateMap.Command> list = ClimateMap.commands(written, now, unit);
        if (!rearDefrost) {
            for (java.util.Iterator<ClimateMap.Command> i = list.iterator(); i.hasNext(); )
                if (i.next().kind == ClimateMap.REAR) i.remove();
        }
        CarAudio audio = CarAudio.instance;
        if (audio != null) audio.phoneWrote(written);
        commands.addAll(list);
    }

    /** sends the queued commands, one at a time */
    void startSender() {
        Thread t = new Thread("cp-climate-car-send") {
            @Override
            public void run() {
                while (true) {
                    ClimateMap.Command c;
                    try {
                        c = commands.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (send(c)) out.log("CAR sent " + c);
                    } catch (Throwable t) {
                        out.log("CAR " + c + " failed: " + t);
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /** sends a command; false if nothing needed sending */
    boolean send(ClimateMap.Command c) throws Exception {
        if (c.kind == ClimateMap.REAR) return pressRear(c.a);
        if (c.kind == ClimateMap.WHEEL) return pressWheel(c.a);
        if (c.kind == ClimateMap.SEAT && (!seatFeedbackFresh()
                || !ClimateMap.seatStateKnown(c.a == ClimateMap.SET_DRIVER_SEAT ? driverSeatState : passengerSeatState)))
            throw new IllegalStateException("seat command withheld until live body-controller feedback is known");
        if (c.kind == ClimateMap.SEAT && c.b >= 3 && c.b <= 5
                && (c.a == ClimateMap.SET_DRIVER_SEAT ? driverSeatOption : passengerSeatOption) != 6)
            throw new IllegalStateException("ventilation withheld: seat option 6 not confirmed");
        if ((c.kind == ClimateMap.VR || c.kind == ClimateMap.FRONT)
                && (!climateFlagsOk || (unit != ClimateMap.CELSIUS && unit != ClimateMap.FAHRENHEIT)))
            throw new IllegalStateException("climate command withheld: flag layout or temperature unit is not verified");
        int code = c.kind == ClimateMap.SEAT
                ? (c.a == ClimateMap.SET_DRIVER_SEAT ? txSetDriverSeat : c.a == ClimateMap.SET_PASSENGER_SEAT ? txSetPassengerSeat : -1)
                : c.kind == ClimateMap.VR ? txSetStatus : txSetFront;
        if (code < 0) throw new IllegalStateException("this firmware has no such call");
        IBinder b = service(c.kind == ClimateMap.SEAT ? USM : DATC);
        if (b == null) throw new IllegalStateException("no service");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(c.kind == ClimateMap.SEAT ? USM_IFACE : DATC_IFACE);
            if (c.kind == ClimateMap.SEAT) {
                data.writeInt(c.b);
            } else {
                data.writeInt(c.a);
                data.writeInt(c.b);
                if (c.kind == ClimateMap.VR) data.writeInt(c.c);
            }
            if (!b.transact(code, data, reply, 0)) throw new IllegalStateException("vehicle service rejected transaction " + code);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
        return true;
    }

    boolean pressWheel(int on) throws Exception {
        if (txWheel < 0 || (on != 0 && on != 1)) throw new IllegalStateException("wheel heat unavailable");
        IBinder vr = service(VR_SERVICE);
        if (vr == null) throw new IllegalStateException("no " + VR_SERVICE);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VR_IFACE);
            data.writeInt(on);
            if (!vr.transact(txWheel, data, reply, 0)) throw new IllegalStateException("wheel transaction rejected");
            reply.readException();
            return true;
        } finally { data.recycle(); reply.recycle(); }
    }

    /**
     * the car's rear defroster: VRService set_RrDefogger_Cmd_AVN puts its value straight on the CAN signal
     * C_RrDefoggerCmdAVN, 1 on and 0 off (what the stock voice handler sends; tested 2026-11-08: 0 turned it off in
     * 90 ms). Not a toggle, so it is safe to send whatever the car's state; READY only.
     */
    boolean pressRear(int want) throws Exception {
        if (txRear < 0) throw new IllegalStateException("this firmware has no set_RrDefogger_Cmd_AVN(int)");
        IBinder vr = service(VR_SERVICE);
        if (vr == null) throw new IllegalStateException("no " + VR_SERVICE);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VR_IFACE);
            data.writeInt(want == 1 ? 1 : 0);
            vr.transact(txRear, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
        return true;
    }

    /** Registers only the previously verified BCM listener, for rear-defrost and wheel feedback. */
    void watchBody() {
        if (bodyTried) return;
        bodyTried = true;
        if (!bcmListenerOk || txRegisterBcm < 0) {
            out.log("CAR body listener left out: " + (bcmListenerOk ? "no registerBCMListener" : "IBCMListener differs from the one cp-climate answers")
                    + "; the rear window button still works, without the car's state");
            return;
        }
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder b = service(BCM);
            if (b == null) throw new IllegalStateException("no " + BCM);
            data.writeInterfaceToken(BCM_IFACE);
            data.writeStrongBinder(body);
            data.writeStrongBinder(bodyToken);
            b.transact(txRegisterBcm, data, reply, 0);
            reply.readException();
            bcm = b;
            out.log("CAR body listener registered, rear defroster " + body.defogger);
        } catch (Throwable t) {
            out.log("CAR body listener not registered: " + t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public void run() {
        out.log("CAR reader and sender started; " + calls());
        if (!climateFlagsOk) out.log("CAR DATC controls disabled: " + climateFlagMismatches);
        startSender();
        String last = null;
        while (true) {
            try {
                Thread.sleep(PERIOD_MS);
                VehicleState vs = state[0];
                if (vs == null || !(lanes[0].isOpen() || lanes[1].isOpen())) continue;
                ClimateMap.Reading r = read();
                if (r == null) continue;
                Map<Long, Object> values = ClimateMap.toCarPlay(r);
                String seen = values.toString();
                if (!seen.equals(last)) {
                    out.log("CAR " + describe(r));
                    last = seen;
                }
                lanes[1].deliver(vs.fromCar(values));
                failures = 0;
            } catch (Throwable t) {
                datc = null;
                if (failures++ < 5) out.log("CAR read failed: " + t);
            }
        }
    }

    /** which of the car's calls this firmware has, and whether they are the Ioniq 6's (CEEV 260507) numbers */
    static String calls() {
        int[] got = { txRegisterDatc, txUnit, txDriverOnly, txGetStatus, txSetStatus, txSetFront,
                txDriverSeat, txPassengerSeat, txSetDriverSeat, txSetPassengerSeat, txRear, txRegisterBcm };
        int[] ioniq6 = { 0x1, 0x3, 0x27, 0x29, 0x28, 0x41, 0x247, 0x248, 0xd5, 0xd6, 0xf, 0x1 };
        StringBuilder s = new StringBuilder("car calls");
        boolean same = true;
        for (int i = 0; i < got.length; i++) {
            s.append(i == 0 ? " " : ",").append(got[i] < 0 ? "missing" : Integer.toHexString(got[i]));
            same &= got[i] == ioniq6[i];
        }
        return s.append(same ? " (as the Ioniq 6's)" : " (NOT the Ioniq 6's numbers)")
                .append("; DATC listener ").append(datcListenerOk ? "verified" : "differs, not registered")
                .append(", BCM listener ").append(bcmListenerOk ? "verified" : "differs, not registered")
                .append("; climate flags ").append(climateFlagsOk ? "verified" : "differ, DATC disabled").toString();
    }

    ClimateMap.Reading read() throws Exception {
        if (txGetStatus < 0 || !climateFlagsOk) return null;
        if (datc == null) {
            datc = service(DATC);
            if (datc == null) return null;
            if (datcListenerOk && txRegisterDatc >= 0) register(datc);
        }
        ClimateMap.Reading r = new ClimateMap.Reading();
        r.unit = unit = temperatureUnit();
        if (unit != ClimateMap.CELSIUS && unit != ClimateMap.FAHRENHEIT)
            throw new IllegalStateException("unrecognized temperature unit " + unit);
        r.on = status(F_ON);
        r.auto = status(F_AUTO);
        r.ac = status(F_AC);
        r.defrost = status(F_DEFROST);
        r.intake = status(F_INTAKE);
        r.fan = status(F_FAN);
        r.mode = status(F_MODE);
        r.driverF = status(F_DRIVER_F);
        r.passengerRaw = status(F_PASSENGER_RAW);
        r.sync = status(F_SYNC);
        r.driverOnly = driverOnly();
        r.driverC = temps.driverC;
        r.driverState = temps.driverState;
        r.passengerC = temps.passengerC;
        r.passengerF = temps.passengerF;
        r.passengerState = temps.passengerState;
        IBinder bodyService = service(BCM), usm = service(USM);
        int bd = readInt(bodyService, BCM_IFACE, txBodyDriverSeat), bp = readInt(bodyService, BCM_IFACE, txBodyPassengerSeat);
        int ud = seat(usm, txDriverSeat), up = seat(usm, txPassengerSeat);
        r.driverSeat = driverSeatState = ClimateMap.seatFeedback(bd, ud);
        r.passengerSeat = passengerSeatState = ClimateMap.seatFeedback(bp, up);
        driverSeatOption = readInt(bodyService, BCM_IFACE, txDriverSeatOption);
        passengerSeatOption = readInt(bodyService, BCM_IFACE, txPassengerSeatOption);
        seatReadAt = System.currentTimeMillis();
        String feedback = "BCM " + bd + "/" + bp + ", USM " + ud + "/" + up + ", options " + driverSeatOption + "/" + passengerSeatOption
                + ", wheel " + body.wheel;
        if (!feedback.equals(lastSeatFeedback)) { out.log("CAR seat feedback " + feedback); lastSeatFeedback = feedback; }
        if (rearDefrost || txWheel >= 0) {
            watchBody();
            r.wheel = body.wheel;
            if (rearDefrost) r.rearDefog = body.defogger;
        }
        return r;
    }

    void register(IBinder b) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DATC_IFACE);
            data.writeStrongBinder(temps);
            data.writeStrongBinder(new Binder());
            b.transact(txRegisterDatc, data, reply, 0);
            reply.readException();
            out.log("CAR temperature listener registered");
        } catch (Throwable t) {
            out.log("CAR temperature listener not registered: " + t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    int driverOnly() {
        if (txDriverOnly < 0) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DATC_IFACE);
            datc.transact(txDriverOnly, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    int temperatureUnit() throws Exception {
        if (txUnit < 0) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DATC_IFACE);
            datc.transact(txUnit, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    float status(int flag) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DATC_IFACE);
            data.writeInt(flag);
            datc.transact(txGetStatus, data, reply, 0);
            reply.readException();
            return reply.readFloat();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static int seat(IBinder usm, int code) {
        return readInt(usm, USM_IFACE, code);
    }

    static int readInt(IBinder binder, String iface, int code) {
        if (binder == null || code < 0) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            if (!binder.transact(code, data, reply, 0)) return -1;
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static IBinder service(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
    }

    static String describe(ClimateMap.Reading r) {
        return "on " + (int) r.on + " auto " + (int) r.auto + " ac " + (int) r.ac + " defrost " + (int) r.defrost
                + " intake " + (int) r.intake + " sync " + (int) r.sync + " driverOnly " + r.driverOnly + " unit " + r.unit + " fan " + (int) r.fan + " mode " + (int) r.mode + " driver " + r.driverF
                + "F/" + r.driverC + "C (" + Integer.toHexString(r.driverState) + ") passenger raw " + r.passengerRaw + " "
                + r.passengerC + "C/" + r.passengerF + "F (" + Integer.toHexString(r.passengerState) + ") seats " + r.driverSeat + "/" + r.passengerSeat
                + " rear defroster " + r.rearDefog;
    }
}
