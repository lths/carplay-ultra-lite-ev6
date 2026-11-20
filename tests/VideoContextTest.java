package ioniq6.cpclimate;

public final class VideoContextTest {
    static int fails;
    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
    static final class Source implements VideoContext.Clock, VideoContext.Foreground {
        long now;
        String top = "com.daudio.app.dipo";
        boolean denied;
        public long now() { return now; }
        public String topPackage() throws Exception {
            if (denied) throw new Exception("unavailable");
            return top;
        }
    }
    public static void main(String[] args) {
        Source source = new Source();
        VideoContext context = new VideoContext(source, source);
        context.session(true);
        check("normal foreground playback is distinguished from app switches", context.describe().contains("carplayVisible=true")
                && context.describe().contains("lastVehicleLaunch=none"));
        VideoContext.Opening request = context.opening("open_hmgapi", "hmgapi.app");
        source.now = 20;
        check("an error during startActivity still records the pending launch", context.describe().contains("phase=requested")
                && context.describe().contains("launchAgoMs=20"));
        context.opened(request, true);
        source.now = 270;
        source.top = "hmgapi.app";
        check("normal HMG-API transition records hidden CarPlay and recent successful launch", context.describe().contains("carplayVisible=false")
                && context.describe().contains("topPackage=hmgapi.app") && context.describe().contains("phase=opened")
                && context.describe().contains("recentVehicleLaunch=true") && context.describe().contains("launchAgoMs=270"));
        source.now = VideoContext.RECENT_MS + 1;
        check("an old launch is not labelled recent", context.describe().contains("recentVehicleLaunch=false"));
        request = context.opening("open_nemesis", "com.nemesis.manager");
        context.opened(request, false);
        check("failed activity launch is not reported as opened", context.describe().contains("phase=failed"));
        source.denied = true;
        check("unreadable foreground is unknown rather than hidden", context.describe().contains("carplayVisible=unknown")
                && context.describe().contains("topPackage=unknown"));
        context.session(false);
        check("session finalization appears in the video context", context.describe().contains("sessionActive=false"));
        context.session(true);
        check("a new session clears stale shortcut history", context.describe().contains("lastVehicleLaunch=none"));
        VideoContext.Opening old = context.opening("open_hmgapi", "hmgapi.app");
        context.opening("open_ev", null);
        context.opened(old, true);
        check("an older launch completion cannot replace newer context", context.describe().contains("lastVehicleLaunch=open_ev")
                && context.describe().contains("target=unknown phase=requested"));
        if (fails != 0) throw new AssertionError(fails + " failed");
    }
}
