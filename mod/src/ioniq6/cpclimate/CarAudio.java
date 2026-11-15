package ioniq6.cpclimate;

import android.os.IBinder;
import android.os.Parcel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The car's volume and CarPlay's volume as one thing, both ways (setting volume_link).
 *
 * CarPlay's Volume page (the audio settings accessory in LIVI's description) has a level 0-100 for media,
 * navigation, phone and Siri, and a mute switch. The head unit keeps a level 0-45 per sound stream in
 * MicomAudioService (service com.hkmc.micom.audiosetup); its volume knob and the steering wheel buttons change
 * the stream that is playing (DiPOMUSIC, 14, while CarPlay plays music). Stock DiPO tells the phone none of it.
 *
 * - Car to phone: the four levels are read five times a second while CarPlay is connected and told to the phone.
 *   Read, not listened for: the service's listener has 79 callbacks answered inside system_server, and a
 *   listener answered wrongly has boot-looped this car before (see CarClimate.Body).
 * - Phone to car: a level the phone writes becomes setVolume(stream, level, 0) (tested from adb on 2026-11-10:
 *   stream 14, 8 to 9 and back; flag 0 shows no volume panel). Only after this session has shown the phone the
 *   car's own level: the description's first value is 50, which would be level 22.
 * - Mute: the phone's switch is muteAV()/unMuteAV() (the micom's AV mute). The car's own mute (getUserMuteState)
 *   shows as on too.
 * Every call is found by name and descriptor; a firmware without one leaves that part out.
 */
final class CarAudio extends Thread {
    static final String SERVICE = "com.hkmc.micom.audiosetup", IFACE = "com.hkmc.media.IMicomAudioService";
    static final long PERIOD_MS = 200;
    /** the head unit's volume steps (MicomAudioService logs "MaxVolume : 45") */
    static final int MAX_LEVEL = 45;
    /** CarPlay's volume value, then the car's stream: media DiPOMUSIC, navigation DiPOALT, phone DiPOCALL, Siri DiPOSIRI */
    static final long[][] LINKS = { { VehicleState.VOLUME, 14 }, { 2311, 13 }, { 2314, 11 }, { 2321, 12 } };

    static volatile CarAudio instance;
    /** sent to DiPO by VolumeKeys (system_server) right after a volume or mute key: read the levels now */
    static final String ACTION_CHANGED = "ioniq6.cpclimate.VOLUME_CHANGED";
    private final Object wake = new Object();
    private boolean woken;

    /** reads the car's levels now instead of at the next turn (the volume keys were used) */
    void readNow() {
        synchronized (wake) {
            woken = true;
            wake.notifyAll();
        }
    }

    /** listens for VolumeKeys' word that a key changed the volume, so CarPlay's slider moves at once */
    void listen(android.content.Context context) {
        if (context == null) return;
        try {
            context.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    readNow();
                }
            }, new android.content.IntentFilter(ACTION_CHANGED));
        } catch (Throwable t) {
            out.log("AUDIO no word from the volume keys (the levels are still read " + (1000 / PERIOD_MS) + " times a second): " + t);
        }
    }

    final int txGet = CarApi.code(IFACE, "getVolume", "(II)I"), txSet = CarApi.code(IFACE, "setVolume", "(III)V");
    final int txMute = CarApi.code(IFACE, "muteAV", "()V"), txUnmute = CarApi.code(IFACE, "unMuteAV", "()V");
    final int txUserMute = CarApi.code(IFACE, "getUserMuteState", "()Z");
    final int txCurrent = CarApi.code(IFACE, "getCurStreamType", "()I");
    /**
     * The stream CarPlay's first (media) level stands for: the one the car's knob moves now, if it has a level
     * (a call, Siri, the radio), else CarPlay's music. On the car, 2026-11-10: with CarPlay silent the current
     * stream was 33, which has no level (getVolume -1), and the knob's level is not DiPOMUSIC's.
     */
    private volatile int mainStream = 14;

    private final VehicleState[] state;
    private final VsChannel[] lanes;
    private final VsChannel.Log out;
    /** the session that has been shown the car's levels; the phone's writes count only for it */
    private volatile VehicleState shown;
    private volatile boolean muted;
    private final LinkedBlockingQueue<int[]> sends = new LinkedBlockingQueue<int[]>();
    private final int[] wanted = new int[LINKS.length];
    private int failures;

    CarAudio(VehicleState[] state, VsChannel[] lanes, VsChannel.Log out) {
        super("cp-climate-audio");
        this.state = state;
        this.lanes = lanes;
        this.out = out;
        setDaemon(true);
    }

    /** a level 0-45 as CarPlay's 0-100 */
    static long percent(int level) {
        return Math.round(Math.max(0, Math.min(MAX_LEVEL, level)) * 100.0 / MAX_LEVEL);
    }

    /** CarPlay's 0-100 as a level 0-45 */
    static int level(long percent) {
        return (int) Math.round(Math.max(0, Math.min(100, percent)) * MAX_LEVEL / 100.0);
    }

    /**
     * What the phone wrote, from VehicleState's thread: volume levels and mute are queued for the car. True if
     * any of the values was one of this class's (CarClimate then doesn't report it as unknown).
     */
    boolean phoneWrote(Map<Long, Object> written) {
        boolean mine = false;
        for (int i = 0; i < LINKS.length; i++) {
            Object v = written.get(LINKS[i][0]);
            if (v == null) continue;
            mine = true;
            Long pct = OpackCodec.asLong(v);
            if (pct == null || txSet < 0) continue;
            if (shown == null || shown != state[0]) {
                out.log("AUDIO volume from the phone ignored: the car's level hasn't been shown to it yet");
                continue;
            }
            int level = level(pct);
            synchronized (wanted) {
                wanted[i] = level;
            }
            sends.add(new int[] { i, level });
        }
        Object m = written.get(VehicleState.MUTE);
        if (m instanceof Boolean) {
            mine = true;
            sends.add(new int[] { -1, (Boolean) m ? 1 : 0 });
        }
        return mine;
    }

    private void startSender() {
        Thread t = new Thread("cp-climate-audio-send") {
            @Override
            public void run() {
                while (true) {
                    int[] s;
                    try {
                        s = sends.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (s[0] < 0) {
                            call(s[1] == 1 ? txMute : txUnmute);
                            muted = s[1] == 1;
                            out.log("AUDIO " + (muted ? "muted" : "un-muted") + " for the phone");
                            continue;
                        }
                        // a slider being dragged writes many levels: only the newest goes to the car
                        synchronized (wanted) {
                            if (wanted[s[0]] != s[1]) continue;
                        }
                        int stream = LINKS[s[0]][0] == VehicleState.VOLUME ? mainStream : (int) LINKS[s[0]][1];
                        call(txSet, stream, s[1], 0);
                        out.log("AUDIO stream " + stream + " set to " + s[1] + " for the phone");
                    } catch (Throwable t) {
                        out.log("AUDIO not sent: " + t);
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        if (txGet < 0) {
            out.log("AUDIO volume link left out: this firmware has no getVolume(int, int)");
            return;
        }
        out.log("AUDIO volume link: calls " + txGet + "," + txSet + "," + txMute + "," + txUnmute + "," + txUserMute
                + (txGet == 8 && txSet == 7 && txMute == 106 && txUnmute == 107 && txUserMute == 73 ? " (as the Ioniq 6's)" : " (not the Ioniq 6's)"));
        startSender();
        String last = null;
        while (true) {
            try {
                synchronized (wake) {
                    if (!woken) wake.wait(PERIOD_MS);
                    woken = false;
                }
                VehicleState vs = state[0];
                if (vs == null || !(lanes[0].isOpen() || lanes[1].isOpen())) continue;
                Map<Long, Object> fresh = new LinkedHashMap<Long, Object>();
                StringBuilder seen = new StringBuilder();
                int main = 14;
                if (txCurrent >= 0) {
                    int current = call(txCurrent);
                    int l = current > 0 ? call(txGet, current, 0) : -1;
                    if (l >= 0 && l <= MAX_LEVEL) main = current;
                }
                mainStream = main;
                seen.append("main=").append(main).append(' ');
                for (long[] link : LINKS) {
                    int level = call(txGet, link[0] == VehicleState.VOLUME ? main : (int) link[1], 0);
                    if (level < 0 || level > MAX_LEVEL) continue;   // a stream this car doesn't have, or another scale
                    fresh.put(link[0], percent(level));
                    seen.append(link[1]).append('=').append(level).append(' ');
                }
                boolean mute = muted || (txUserMute >= 0 && call(txUserMute) != 0);
                fresh.put(VehicleState.MUTE, mute);
                seen.append("mute=").append(mute);
                if (!seen.toString().equals(last)) {
                    last = seen.toString();
                    out.log("AUDIO " + last);
                }
                lanes[1].deliver(vs.fromCar(fresh));
                shown = vs;
                failures = 0;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                if (failures++ < 5) out.log("AUDIO read failed: " + t);
            }
        }
    }

    /** a call taking ints and answering an int, a boolean or nothing (0) */
    private int call(int code, int... args) throws Exception {
        if (code < 0) throw new IllegalStateException("this firmware has no such call");
        IBinder b = CarClimate.service(SERVICE);
        if (b == null) throw new IllegalStateException("no " + SERVICE);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE);
            for (int a : args) data.writeInt(a);
            b.transact(code, data, reply, 0);
            reply.readException();
            return reply.dataAvail() >= 4 ? reply.readInt() : 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
