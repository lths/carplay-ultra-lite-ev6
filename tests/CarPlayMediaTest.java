package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Connection must leave phone UI/audio alone; only explicit media buttons reach the receiver. */
public final class CarPlayMediaTest {
    static int fails;
    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    public enum Consumer { Play, Pause, PlayOrPause, ScanNextTrack, ScanPreviousTrack }

    public static final class Receiver {
        final List<String> calls = new ArrayList<String>();
        boolean failDown;
        public String requestUI(String url) {
            calls.add(url);
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
        public void onConsumer(ToggleOnly button, boolean down) { throw new AssertionError("must not toggle"); }
    }

    public static final class NoUiReceiver {
        final List<String> calls = new ArrayList<String>();
        public void onConsumer(Consumer button, boolean down) { calls.add(button.name() + (down ? " down" : " up")); }
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
        f.media.started(f.receiver);
        check("connection schedules no UI or audio actions", f.calls() && f.timer.work.isEmpty());
        f.media.started(f.receiver);
        f.timer.advance(60000);
        check("duplicate starts and elapsed startup time never request Now Playing or Play", f.calls() && f.timer.work.isEmpty());
        f.media.ended(f.receiver);
        f.media.started(f.receiver);
        f.timer.advance(60000);
        check("reconnection does not resume audio or navigate", f.calls() && f.timer.work.isEmpty());

        for (String[] action : new String[][] { { "play", "Play" }, { "pause", "Pause" }, { "nextItem", "ScanNextTrack" }, { "previousItem", "ScanPreviousTrack" } }) {
            f = new Fixture();
            f.media.started(f.receiver);
            f.media.pressed(action[0], null);
            f.timer.advance(1000);
            check("explicit " + action[0] + " sends a paired key without opening Now Playing", f.calls(action[1] + " down", action[1] + " up"));
        }

        for (String action : new String[] { "changeMediaSource", "tuneToIdentifier", "tuneToFrequency" }) {
            f = new Fixture();
            f.media.started(f.receiver);
            f.media.pressed(action, VehicleState.dict("identifier", "FM"));
            f.timer.advance(60000);
            check("Radio " + action + " does not open Now Playing or start phone audio", f.calls() && f.timer.work.isEmpty());
        }

        f = new Fixture();
        f.media.started(f.receiver);
        f.media.pressed("play", null);
        f.media.ended(f.receiver);
        f.timer.advance(1000);
        check("disconnect cancels queued explicit button actions", f.calls());

        f = new Fixture();
        f.media.started(f.receiver);
        f.media.pressed("play", null);
        f.timer.advance(0);
        f.media.ended(f.receiver);
        f.media.started(f.receiver);
        f.timer.advance(1000);
        check("old button release cannot enter a replacement session", f.calls("Play down"));

        f = new Fixture();
        Receiver old = new Receiver();
        f.media.started(old);
        f.media.started(f.receiver);
        f.media.ended(old);
        f.media.pressed("pause", null);
        f.timer.advance(1000);
        check("a stale disconnect cannot detach the new session", old.calls.isEmpty() && f.calls("Pause down", "Pause up"));

        f = new Fixture();
        f.media.started(new UnsupportedReceiver());
        f.media.pressed("play", null);
        f.timer.advance(1000);
        boolean unavailable = false;
        for (String line : f.logs) unavailable |= line.startsWith("MEDIA Play unavailable:");
        check("missing explicit Play is reported without using a toggle", unavailable);

        f = new Fixture();
        NoUiReceiver noUi = new NoUiReceiver();
        f.media.started(noUi);
        f.media.pressed("play", null);
        f.timer.advance(1000);
        check("manual media controls do not require a UI API", noUi.calls.equals(Arrays.asList("Play down", "Play up")));

        f = new Fixture();
        f.receiver.failDown = true;
        f.media.started(f.receiver);
        f.media.pressed("play", null);
        f.timer.advance(60000);
        check("a failed manual key press is released once without retries", f.calls("Play down", "Play up"));
        if (fails != 0) throw new AssertionError(fails + " failed");
    }
}
