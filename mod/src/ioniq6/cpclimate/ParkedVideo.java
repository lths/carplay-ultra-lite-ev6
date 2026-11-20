package ioniq6.cpclimate;

import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

/** Read-only Park feedback from the named, signature-checked Micom system-service getter. */
final class ParkedVideo extends Thread {
    static final String SERVICE = "com.hkmc.micom.system", IFACE = "com.hkmc.micom.ISystemService";
    static volatile ParkedVideo instance;
    final ParkedState state = new ParkedState();
    final int code = CarApi.code(IFACE, "getGearPStatus", "()I");
    final VsChannel.Log out;
    volatile boolean session;
    ParkedVideo(VsChannel.Log out) { super("cp-climate-video-park"); this.out = out; setDaemon(true); instance = this; }
    static boolean allowed() {
        ParkedVideo p = instance;
        return p != null && p.session && p.state.allowed(SystemClock.elapsedRealtime());
    }
    @Override public void run() {
        int lastRaw = Integer.MIN_VALUE;
        while (true) {
            int raw = -1;
            long sampledAt = SystemClock.elapsedRealtime();
            try {
                IBinder service = CarClimate.service(SERVICE);
                if (code >= 0 && service != null) {
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(IFACE);
                        if (!service.transact(code, data, reply, 0)) throw new IllegalStateException("Park getter rejected");
                        reply.readException();
                        raw = reply.readInt();
                    } finally { data.recycle(); reply.recycle(); }
                }
            } catch (Throwable unavailable) { /* Unknown feedback disables video. */ }
            // A slow binder reply must not make an old sample look newly fresh.
            state.sample(raw, sampledAt);
            boolean now = allowed();
            if (raw != lastRaw) { out.log("VIDEO Park feedback=" + raw + " (1 required)"); lastRaw = raw; }
            if (!now) VideoMessages.stopEngine("Park feedback lost or gear left P");
            try { Thread.sleep(500); } catch (InterruptedException done) { return; }
        }
    }
}
