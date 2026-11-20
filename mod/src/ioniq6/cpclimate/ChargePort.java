package ioniq6.cpclimate;

import android.os.*;

/** EV6 charge-door command documented by HMG-API; boolean false opens, true closes. */
final class ChargePort {
    static final class Gate {
        long lastAt = -1;
        boolean allowed(int park, long now) { return park == 1 && now >= 0 && (lastAt < 0 || now >= lastAt && now - lastAt >= 5000); }
        void sent(long now) { lastAt = now; }
    }
    final Gate gate = new Gate();
    volatile String status = "No request";
    synchronized String request(boolean close, boolean session) {
        if (!session) return status = "CarPlay is disconnected";
        if (!CarApi.ev6Target(IntakeLearning.product())) return status = "Unsupported firmware";
        int txPark = CarApi.code("com.hkmc.micom.ISystemService", "getGearPStatus", "()I");
        int txDoor = CarApi.code(CarApi.BCM, "requestChargeLockDoor", "(Z)V");
        if (txPark < 0 || txDoor < 0) return status = "Control unavailable";
        IBinder system, body;
        try { system = CarClimate.service("com.hkmc.micom.system"); body = CarClimate.service(CarClimate.BCM); }
        catch (Throwable unavailable) { return status = "Vehicle service unavailable"; }
        int park = CarClimate.readInt(system, "com.hkmc.micom.ISystemService", txPark);
        long now = SystemClock.elapsedRealtime();
        if (!gate.allowed(park, now)) return status = park == 1 ? "Wait 5 seconds between requests" : "Confirmed Park is required";
        if (body == null) return status = "Vehicle service unavailable";
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CarApi.BCM); data.writeInt(close ? 1 : 0);
            // Count a possibly submitted request even if its reply fails; do not retry motor commands.
            gate.sent(now);
            if (!body.transact(txDoor, data, reply, 0)) return status = "Request rejected";
            reply.readException();
            return status = (close ? "Close" : "Open") + " requested; confirm at the port";
        } catch (Throwable unavailable) { return status = "Request not confirmed"; }
        finally { data.recycle(); reply.recycle(); }
    }
}
