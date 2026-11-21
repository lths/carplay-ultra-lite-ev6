package ioniq6.cpclimate;

import java.io.*;
import java.util.Arrays;

/** Cross-client attribution, unnamed stock selectors and fixed local handoff boundaries. */
public final class IntakeLearningTest {
    static final String PRODUCT = "CVEV.USA.0000.V015.012.260507";
    static int fails;
    static void check(String name, boolean ok) { if (!ok) fails++; System.out.println((ok ? "ok   " : "FAIL ") + name); }
    static ClimateMap.Command front(int selector, int value) { return new ClimateMap.Command(ClimateMap.FRONT, selector, value, 0); }
    static ClimateMap.Command vr(int value) { return new ClimateMap.Command(ClimateMap.VR, 11, value, 0); }
    static boolean rejects(byte[] bytes) { try { IntakeProtocol.read(new ByteArrayInputStream(bytes)); return false; } catch (IOException expected) { return true; } }
    static void on(IntakeTrainer t, ClimateMap.Command c, long at) { t.finish(t.begin(c, 2, at), 1, 1, at + 500); }
    public static void main(String[] args) throws Exception {
        check("another system process can hand off a stock call", IntakeProtocol.peerAllowed(1000, 11, 1000, 22, false));
        check("CarPlay cannot train its own replay", !IntakeProtocol.peerAllowed(1000, 22, 1000, 22, true));
        check("an unsigned different-uid client is rejected", !IntakeProtocol.peerAllowed(10001, 11, 1000, 22, false));
        check("a verified platform-signed stock app can use a different uid", IntakeProtocol.peerAllowed(10001, 11, 1000, 22, true));
        check("missing peer credentials cannot authorize learning", !IntakeProtocol.peerAllowed(-1, 11, 1000, 22, true) && !IntakeProtocol.peerAllowed(1000, 0, 1000, 22, true));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        IntakeProtocol.write(bytes, front(37, 3));
        ClimateMap.Command decoded = IntakeProtocol.read(new ByteArrayInputStream(bytes.toByteArray()));
        check("local handoff is exactly 20 bytes and retains the observed command", bytes.size() == 20 && decoded.kind == ClimateMap.FRONT && decoded.a == 37 && decoded.b == 3);
        check("a partial handoff cannot become a command", rejects(Arrays.copyOf(bytes.toByteArray(), 19)));
        byte[] bad = bytes.toByteArray(); bad[0] = 0;
        check("wrong protocol magic is rejected", rejects(bad));
        check("seat, rear and wheel commands cannot enter intake learning", !IntakeProtocol.candidate(new ClimateMap.Command(ClimateMap.SEAT, 37, 3, 0)) && !IntakeProtocol.candidate(new ClimateMap.Command(ClimateMap.WHEEL, 1, 0, 0)));
        check("only the named intake VR selector can enter learning", !IntakeProtocol.candidate(new ClimateMap.Command(ClimateMap.VR, 2, 3, 0)));
        check("front and value bounds reject malformed packets", !IntakeProtocol.candidate(front(-1, 0)) && !IntakeProtocol.candidate(front(256, 0)) && !IntakeProtocol.candidate(front(37, -1)) && !IntakeProtocol.candidate(new ClimateMap.Command(ClimateMap.FRONT, 37, 2, 1)));
        IntakeTrainer t = new IntakeTrainer(PRODUCT, -1);
        check("a confirmed fresh transition alone does not authorize an unnamed front selector", t.finish(t.begin(front(37, 3), 1, 0), 2, 2, 500) == null);
        on(t, front(37, 7), 1000);
        FreshAirProfile learned = t.finish(t.begin(decoded, 1, 2000), 2, 2, 2500);
        check("confirmed stock ON and OFF of one unnamed selector establish a replayable pair", learned != null && learned.command.a == 37 && learned.command.b == 3 && learned.on.b == 7);
        check("paired profile retains both observed values without requiring a guessed flag name", learned != null && FreshAirProfile.decode(learned.encode(), PRODUCT, -1) != null);
        check("a named-selector contradiction still rejects a paired profile", learned != null && FreshAirProfile.decode(learned.encode(), PRODUCT, 38) == null);
        check("a legacy front profile without an ON pair cannot authorize an unnamed selector", FreshAirProfile.decode("fresh-air-v1 " + PRODUCT + " 1 37 3 0", PRODUCT, -1) == null);
        check("a paired profile remains bound to the full firmware product", learned != null && FreshAirProfile.decode(learned.encode(), "CVEV.USA.0000.V015.013.260507", -1) == null);
        check("same-value or malformed pair cannot be read as an OFF profile", FreshAirProfile.decode("fresh-air-v2 " + PRODUCT + " 1 37 3 0 3 0", PRODUCT, -1) == null && FreshAirProfile.decode("fresh-air-v2 " + PRODUCT + " 1 37 3 0 7 1", PRODUCT, -1) == null);
        t = new IntakeTrainer(PRODUCT, -1); on(t, front(37, 7), 0);
        check("an ON from another front selector cannot train this OFF", t.finish(t.begin(front(38, 3), 1, 1000), 2, 2, 1500) == null);
        t = new IntakeTrainer(PRODUCT, -1); on(t, front(37, 7), 0);
        check("an expired ON cannot authorize a later OFF", t.finish(t.begin(front(37, 3), 1, 32000), 2, 2, 32500) == null);
        t = new IntakeTrainer(PRODUCT, -1);
        IntakeTrainer.Capture old = t.begin(vr(3), 1, 0);
        t.begin(front(42, 1), 1, 100);
        check("a newer client command invalidates attribution to an earlier client", t.finish(old, 2, 2, 500) == null);
        t = new IntakeTrainer(PRODUCT, -1); old = t.begin(vr(3), 1, 0); t.cancel(old);
        check("a failed or timed-out stock operation cannot train a command", t.finish(old, 2, 2, 500) == null);
        t = new IntakeTrainer(PRODUCT, -1); old = t.begin(vr(3), 1, 0);
        check("missing baseline feedback cannot be trained", t.begin(vr(3), -1, 100) == null && t.finish(old, 2, 2, 500) == null);
        t = new IntakeTrainer(PRODUCT, -1);
        check("one changed sample cannot train a fresh command", t.finish(t.begin(vr(3), 1, 0), 1, 2, 500) == null);
        check("late feedback cannot be attributed to an old command", t.finish(t.begin(vr(3), 1, 1000), 2, 2, 5001) == null);
        check("clock reversal cannot authorize a profile", t.finish(t.begin(vr(3), 1, 6000), 2, 2, 5999) == null);
        check("the known recirculation ON value is still never an OFF profile", t.finish(t.begin(vr(1), 1, 7000), 2, 2, 7500) == null);
        check("a distinct observed third VR argument is not mistaken for the exact known ON tuple",
                t.finish(t.begin(new ClimateMap.Command(ClimateMap.VR, 11, 1, 2), 1, 7600), 2, 2, 7900) != null);
        old = t.begin(vr(3), 1, 8000);
        check("a confirmed named VR command is consumed only once", t.finish(old, 2, 2, 8500) != null && t.finish(old, 2, 2, 8600) == null);
        check("an unrelated named front selector is not a candidate", !new IntakeTrainer(PRODUCT, 37).accepts(front(38, 3)));
        check("unsupported firmware never admits a handoff", new IntakeTrainer("CVEV.USA.x.260508", -1).begin(vr(3), 1, 0) == null);
        File dir = new File(args[0], "intake-owner"); dir.mkdir();
        learned.save(dir);
        check("owner persistence accepts the authenticated paired handoff without a client filesystem path", FreshAirProfile.read(dir, PRODUCT, -1) != null);
        try (FileWriter w = new FileWriter(new File(dir, FreshAirProfile.LEGACY_FILE))) { w.write("fresh-air-v1 " + PRODUCT + " 0 11 3 0\n"); }
        FreshAirProfile.forget(dir);
        check("invalidating a profile also removes legacy fallback", FreshAirProfile.read(dir, PRODUCT, -1) == null);
        if (fails != 0) throw new AssertionError(fails + " failed");
    }
}
