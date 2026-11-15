package ioniq6.cpclimate;

import android.app.ActivityManager;
import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.view.KeyEvent;

import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;

import java.util.List;

/**
 * Experiment (setting carplay_volume_slider, off by default): while CarPlay is on the screen, the volume knob
 * and the steering wheel's volume buttons change the volume without the head unit's own volume pop-up, so only
 * CarPlay's slider shows (CarPlay draws one when the car reports a new level, which CarAudio does).
 *
 * In system_server, at the car's key entry (WindowManagerService.interceptKey, the point HMG-API's own button
 * bindings use): a volume key pressed while DiPO's activity is in front is answered here with the same call
 * the car's key handler makes, MicomAudioService.adjustsuggestedStreamVolume(3, direction, 1, flags), with
 * flags 8 instead of the car's 9: bit 1 is "show the volume panel" (MicomAudioService.setVolumeInternal).
 * The key is then used up (-1), so the car doesn't step the volume a second time.
 *
 * The volume knob must never end up doing nothing, so: anything unexpected (no audio service, no such call, an
 * exception, CarPlay not in front, the setting off) leaves the key to the car exactly as without this class;
 * the key is only used up after the car's audio service has taken the step without an error.
 */
final class VolumeKeys {
    private VolumeKeys() {}

    static final String DIPO = "com.daudio.app.dipo";
    /** the car's own flags for a volume key are 9; without bit 1 the head unit shows no volume panel */
    static final int FLAGS_NO_PANEL = 8;
    /** what the car's key handler passes as the suggested stream (ModeManagerService: 3, music) */
    static final int SUGGESTED_STREAM = 3;

    private static volatile boolean installed;
    private static int txAdjust = -1, txHide = -1;
    private static int[] up = {}, down = {}, mute = {};
    private static android.os.Handler later;
    /** after the mute key: when the car's own mute pop-up is dismissed (ms; it is posted while the key is handled) */
    static final long[] HIDE_AFTER_MS = { 20, 90, 250 };

    /** in the zygote: when system_server has loaded the car's services, watch its keys */
    static void zygote(final ModContext mod) {
        mod.hook(null, "framework.hkmc_loader.automotive_service", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (installed || p.getResult() == null || !(p.args[0] instanceof Context)) return;
                installed = true;
                try {
                    install(mod, (Context) p.args[0]);
                } catch (Throwable t) {
                    mod.error("volume keys left to the car", t);
                }
            }
        });
    }

    private static int[] codes(String... names) {
        int[] found = new int[names.length];
        int n = 0;
        for (String name : names) {
            try {
                found[n++] = KeyEvent.class.getField(name).getInt(null);
            } catch (Throwable t) {
                // a key this car doesn't have
            }
        }
        return java.util.Arrays.copyOf(found, n);
    }

    private static boolean has(int[] codes, int code) {
        for (int c : codes) if (c == code) return true;
        return false;
    }

    private static void install(final ModContext mod, final Context context) {
        txAdjust = CarApi.code(CarAudio.IFACE, "adjustsuggestedStreamVolume", "(IIII)V");
        up = codes("KEYCODE_VOLUME_UP", "KEYCODE_VOLUME_UP_DUMMY");
        down = codes("KEYCODE_VOLUME_DOWN", "KEYCODE_VOLUME_DOWN_DUMMY");
        // the mute key stays the car's (its mute state is the car's own, also outside CarPlay); only the pop-up
        // it shows is dismissed, with MicomAudioService.hideVolumeDisplay(false)
        mute = codes("KEYCODE_MUTE", "KEYCODE_VOLUME_MUTE");
        txHide = CarApi.code(CarAudio.IFACE, "hideVolumeDisplay", "(Z)V");
        android.os.HandlerThread thread = new android.os.HandlerThread("cp-climate-volume-keys");
        thread.start();
        later = new android.os.Handler(thread.getLooper());
        if (txAdjust < 0 || up.length == 0 || down.length == 0) {
            mod.log("VOLKEY left to the car: call " + txAdjust + ", " + up.length + " up and " + down.length + " down key codes");
            return;
        }
        mod.hook(null, "framework.window_manager_service.intercept_key", new MethodHook() {
            private int logged;

            @Override
            protected void beforeHookedMethod(Param p) {
                try {
                    if (!(p.args[0] instanceof KeyEvent)) return;
                    KeyEvent e = (KeyEvent) p.args[0];
                    int code = e.getKeyCode();
                    int direction = has(up, code) ? 1 : has(down, code) ? -1 : 0;
                    boolean muteKey = direction == 0 && has(mute, code);
                    if (direction == 0 && !muteKey) return;
                    if (!mod.settingBoolean("carplay_volume_slider", false) || !carPlayInFront(context)) return;
                    if (muteKey) {
                        // not used up: the car mutes as always; its pop-up goes, and CarPlay hears of it at once
                        if (e.getAction() == KeyEvent.ACTION_UP || e.getAction() == KeyEvent.ACTION_DOWN) {
                            for (long ms : HIDE_AFTER_MS) later.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    hidePanel();
                                    tell(context);
                                }
                            }, ms);
                        }
                        return;
                    }
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        step(direction, steps(e));
                        tell(context);
                    } else if (e.getAction() != KeyEvent.ACTION_UP) {
                        return;
                    }
                    p.setResult(-1);
                    if (logged++ < 24) mod.log("VOLKEY " + (direction > 0 ? "up" : "down") + " taken for CarPlay (key " + code + ", action " + e.getAction()
                            + ", scan " + e.getScanCode() + ", meta " + e.getMetaState() + ", flags " + e.getFlags() + ", repeat " + e.getRepeatCount()
                            + ", device " + e.getDeviceId() + "): " + steps(e) + " step(s)");
                } catch (Throwable t) {
                    // the car handles this key itself
                    if (logged++ < 10) mod.log("VOLKEY left to the car: " + t);
                }
            }
        });
        mod.log("VOLKEY watching the volume keys (call " + txAdjust + ", setting " + mod.settingBoolean("carplay_volume_slider", false) + ")");
    }

    /** true while DiPO's activity (CarPlay) is the one on screen */
    static boolean carPlayInFront(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> tasks = am == null ? null : am.getRunningTasks(1);
        return tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null
                && DIPO.equals(tasks.get(0).topActivity.getPackageName());
    }

    /** tells CarAudio (in DiPO) to read the levels now, so CarPlay's slider follows the key without waiting */
    static void tell(final Context context) {
        later.post(new Runnable() {
            @Override
            public void run() {
                try {
                    context.sendBroadcast(new android.content.Intent(CarAudio.ACTION_CHANGED).setPackage(DIPO));
                } catch (Throwable t) {
                    // CarAudio reads on its own a moment later
                }
            }
        });
    }

    /** dismisses the head unit's volume pop-up, if it is showing */
    static void hidePanel() {
        if (txHide < 0) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder b = CarClimate.service(CarAudio.SERVICE);
            if (b == null) return;
            data.writeInterfaceToken(CarAudio.IFACE);
            data.writeInt(0);
            b.transact(txHide, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            // the pop-up stays this once
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * How many steps a key press stands for. The volume knob is a relative input (/dev/input "…_knob", REL_Y
     * with the detents turned since the last report, about every 140 ms: 1 when turned slowly, 3-5 when spun);
     * the car's key handler passes that count on as adjustStreamVolume's "repeat" and steps that many levels.
     * In the key event the count sits in the flags, above bit 20: seen on the car (2026-11-10, 1.3.2's log)
     * 0x180000, 0x280000, 0x480000, 0x580000 for 1, 2, 4 and 5 detents (0x80000 is always set, and the key up
     * adds 0x2000); scan code, meta state and repeat count are 0. Anything but 1-8 counts as one step.
     */
    static int steps(KeyEvent e) {
        int n = (e.getFlags() >>> 20) & 0xff;
        return n >= 1 && n <= MAX_STEPS ? n : 1;
    }

    static final int MAX_STEPS = 8;

    /** volume steps, as the car's key handler asks for them, without the volume panel; throws if not taken */
    static void step(int direction, int count) throws Exception {
        IBinder b = CarClimate.service(CarAudio.SERVICE);
        if (b == null) throw new IllegalStateException("no " + CarAudio.SERVICE);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CarAudio.IFACE);
            data.writeInt(SUGGESTED_STREAM);
            data.writeInt(direction);
            data.writeInt(Math.max(1, Math.min(MAX_STEPS, count)));
            data.writeInt(FLAGS_NO_PANEL);
            if (!b.transact(txAdjust, data, reply, 0)) throw new IllegalStateException("call not taken");
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
