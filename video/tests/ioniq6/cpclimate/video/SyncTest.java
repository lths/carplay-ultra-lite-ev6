package ioniq6.cpclimate.video;

/** Sync: the picture follows the phone's sound through stalls, pauses and seeks. usage: SyncTest */
public class SyncTest {
    static int fails;
    static void check(String w, boolean ok) { if (!ok) fails++; System.out.println((ok ? "ok   " : "FAIL ") + w); }
    public static void main(String[] a) {
        Sync s = new Sync();
        Sync.Step st = s.step(0, 0, 0, true);
        check("before the first play: nothing to follow", st.speed == 1f && st.seekTo < 0 && st.behind == 0);

        s.start(0, 1000);
        st = s.step(11000, 10000, 40000, true);
        check("in step: normal speed", st.speed == 1f && st.behind == 0);

        // a 3 s stall: the sound kept going
        st = s.step(14000, 10000, 10000, false);
        check("stalled: the gap is seen, speed left alone", st.behind == 3000 && st.speed == 1f && st.seekTo < 0);
        st = s.step(15000, 10500, 30000, true);
        check("after the stall, 2.5 s behind: fastest catch-up", st.speed == Sync.MAX_FAST && st.seekTo < 0);
        st = s.step(20000, 18900, 40000, true);
        check("100 ms behind while catching up: gently", st.speed > 1f && st.speed <= 1.05f);
        st = s.step(20000, 18970, 40000, true);
        check("30 ms behind while catching up: still going (stops within 20 ms)", st.speed == Sync.MIN_FAST);
        st = s.step(20000, 18985, 40000, true);
        check("15 ms behind: done, normal speed", st.speed == 1f);
        st = s.step(20000, 18940, 40000, true);
        check("60 ms behind from normal speed: inside the dead band", st.speed == 1f);
        st = s.step(20000, 18900, 40000, true);
        check("100 ms behind from normal speed: corrected", st.speed > 1f);
        st = s.step(25000, 22500, 24000, true);
        check("2.5 s behind but little loaded: no speed-up (would stall again)", st.speed == 1f);

        // a long stall: jump when the sound's point is loaded, catch up by speed otherwise
        s.start(0, 0);
        st = s.step(20000, 5000, 18000, true);
        check("15 s behind, the sound's point not loaded: speed, no jump", st.seekTo < 0 && st.speed == Sync.MAX_FAST);
        st = s.step(20000, 5000, 30000, true);
        check("15 s behind, loaded past it: jump to the sound", st.seekTo == 20000 && st.speed == 1f);

        // ahead of the sound
        s.start(0, 0);
        st = s.step(10000, 10300, 40000, true);
        check("300 ms ahead: slower", st.speed == Sync.MIN_SLOW);
        st = s.step(10000, 10040, 40000, true);
        check("40 ms ahead while slowing: still slower", st.speed == Sync.MAX_SLOW);
        st = s.step(10000, 10010, 40000, true);
        check("10 ms ahead: back to normal", st.speed == 1f);

        // pauses hold the sound's clock, seeks move it
        s.start(0, 0);
        s.pause(5000);
        check("paused: the clock holds", s.target(60000) == 5000);
        st = s.step(60000, 5000, 40000, false);
        check("paused: nothing to correct", st.speed == 1f && st.seekTo < 0 && st.behind == 0);
        s.resume(60000);
        check("resumed: runs on from where it stopped", s.target(61000) == 6000);
        s.seek(120000, 61000);
        check("a seek moves it", s.target(62000) == 121000);
        s.pause(62000);
        s.seek(30000, 63000);
        check("a seek while paused stays paused", s.target(70000) == 30000);

        s.reset();
        check("a new video: nothing to follow", s.target(1000) == -1);
        s.pause(1000);
        s.resume(1000);
        s.seek(5000, 1000);
        check("pause, resume and seek before it plays change nothing", s.target(2000) == -1);

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED"); System.exit(fails == 0 ? 0 : 1);
    }
}
