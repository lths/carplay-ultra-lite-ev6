package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Exercise session timing and the real reflective receiver adapter without manufacturer files. */
public final class CarPlayMediaTest {
    static int fails;
    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    public enum Consumer { Play, Pause, PlayOrPause, ScanNextTrack, ScanPreviousTrack }

    public static final class Receiver {
        final List<String> calls = new ArrayList<String>();
        boolean failUI, failDown;
        public String requestUI(String url) {
            calls.add(url);
            if (failUI) throw new IllegalStateException("test UI failure");
            return "Submitted";
        }
        public String onConsumer(Consumer button, boolean down) {
            calls.add(button.name() + (down ? " down" : " up"));
            if (down && failDown) throw new IllegalStateException("test key failure");
            return "Submitted";
        }
    }

    public enum ToggleOnly { PlayOrPause }
    public static final class UnsupportedReceiver {
        public void requestUI(String url) { throw new AssertionError("must not send without explicit Play"); }
        public void onConsumer(ToggleOnly button, boolean down) { throw new AssertionError("must not toggle"); }
    }

    static final class Timer implements CarPlayMedia.Later {
        long now, sequence;
        static final class Task {
            long when, sequence;
            Runnable run;
        }
        final PriorityQueue<Task> work = new PriorityQueue<Task>(11, new Comparator<Task>() {
            public int compare(Task a, Task b) {
                int at = Long.compare(a.when, b.when);
                return at != 0 ? at : Long.compare(a.sequence, b.sequence);
            }
        });
        public void post(Runnable run, long delayMs) {
            Task t = new Task();
            t.when = now + delayMs;
            t.sequence = ++sequence;
            t.run = run;
            work.add(t);
        }
        void advance(long ms) {
            long until = now + ms;
            int count = 0;
            while (!work.isEmpty() && work.peek().when <= until) {
                if (++count > 100) throw new AssertionError("unbounded command loop");
                Task t = work.remove();
                now = t.when;
                t.run.run();
            }
            now = until;
        }
    }

    static final class Fixture {
        final Timer timer = new Timer();
        final Receiver receiver = new Receiver();
        final List<String> logs = new ArrayList<String>();
        final CarPlayMedia media = new CarPlayMedia(timer, new VsChannel.Log() {
            public void log(String message) { logs.add(message); }
        });
        boolean calls(String... expected) { return receiver.calls.equals(Arrays.asList(expected)); }
    }

    public static void main(String[] args) {
        Fixture f = new Fixture();
        f.media.started(f.receiver, true);
        f.media.started(f.receiver, true);
        f.timer.advance(CarPlayMedia.CONNECT_DELAY_MS - 1);
        check("connection waits for receiver startup", f.calls());
        f.timer.advance(10000);
        check("duplicate start callbacks send Now Playing and explicit Play exactly once",
                f.calls("nowplaying:", "Play down", "Play up"));
        f.timer.advance(60000);
        check("automatic resume has no repeated play loop", f.calls("nowplaying:", "Play down", "Play up"));

        f = new Fixture();
        f.media.started(f.receiver, false);
        f.timer.advance(10000);
        check("automatic resume setting off sends nothing", f.calls());
        f.media.resume();
        f.timer.advance(1000);
        check("manual shortcut still resumes with automatic resume off", f.calls("nowplaying:", "Play down", "Play up"));

        f = new Fixture();
        f.media.started(f.receiver, true);
        f.media.resume();
        f.timer.advance(10000);
        check("manual resume replaces pending automatic resume", f.calls("nowplaying:", "Play down", "Play up"));

        f = new Fixture();
        f.media.started(f.receiver, true);
        f.media.pressed("pause", null);
        f.timer.advance(10000);
        check("manual pause cancels pending automatic play", f.calls("Pause down", "Pause up"));

        f = new Fixture();
        f.media.started(f.receiver, true);
        f.timer.advance(1000);
        f.media.ended(f.receiver);
        f.timer.advance(10000);
        check("disconnect before resume cancels all commands", f.calls());
        f.media.started(f.receiver, true);
        f.timer.advance(10000);
        check("a later connection to the same service gets its own single attempt", f.calls("nowplaying:", "Play down", "Play up"));

        f = new Fixture();
        f.media.started(f.receiver, true);
        f.timer.advance(CarPlayMedia.CONNECT_DELAY_MS);
        f.media.ended(f.receiver);
        f.timer.advance(1000);
        check("disconnect after opening Now Playing cancels delayed Play", f.calls("nowplaying:"));

        f = new Fixture();
        f.media.started(f.receiver, false);
        f.media.resume();
        f.timer.advance(CarPlayMedia.PLAY_DELAY_MS);
        f.media.ended(f.receiver);
        f.media.started(f.receiver, false);
        f.timer.advance(1000);
        check("old button release cannot enter a replacement session", f.calls("nowplaying:", "Play down"));

        f = new Fixture();
        f.media.started(new UnsupportedReceiver(), true);
        f.timer.advance(10000);
        check("toggle-only receiver is withheld and reported instead of risking Pause",
                f.logs.size() == 1 && f.logs.get(0).startsWith("MEDIA resume unavailable:"));

        f = new Fixture();
        f.receiver.failUI = true;
        f.media.started(f.receiver, true);
        f.timer.advance(10000);
        check("UI failure does not prevent the independent explicit Play request", f.calls("nowplaying:", "Play down", "Play up"));

        f = new Fixture();
        f.receiver.failDown = true;
        f.media.started(f.receiver, true);
        f.timer.advance(60000);
        check("a failed key press is released once without retries", f.calls("nowplaying:", "Play down", "Play up"));

        for (String action : new String[] { "play", "changeMediaSource", "tuneToIdentifier", "tuneToFrequency" }) {
            f = new Fixture();
            f.media.started(f.receiver, false);
            f.media.pressed(action, VehicleState.dict("identifier", "FM"));
            f.timer.advance(1000);
            check("Radio " + action + " requests phone Now Playing and Play", f.calls("nowplaying:", "Play down", "Play up"));
        }
        if (fails != 0) throw new AssertionError(fails + " failed");
    }
}
