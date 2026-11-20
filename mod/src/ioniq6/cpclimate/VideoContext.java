package ioniq6.cpclimate;

/** Passive context at video events. Foreground is a task snapshot, not a claim that frames are moving. */
final class VideoContext {
    static final long RECENT_MS = 10000;
    interface Clock { long now(); }
    interface Foreground { String topPackage() throws Exception; }

    static final class Opening {
        final String id, target, phase;
        final long at;
        Opening(String id, String target, String phase, long at) {
            this.id = id;
            this.target = target;
            this.phase = phase;
            this.at = at;
        }
    }

    private final Clock clock;
    private final Foreground foreground;
    private boolean active;
    private Opening last;

    VideoContext(Clock clock, Foreground foreground) {
        this.clock = clock;
        this.foreground = foreground;
    }

    synchronized void session(boolean on) {
        if (on && !active) last = null;
        active = on;
    }

    synchronized Opening opening(String id, String target) {
        return last = new Opening(id, target == null ? "unknown" : target, "requested", clock.now());
    }

    synchronized void opened(Opening request, boolean success) {
        if (last == request) last = new Opening(request.id, request.target, success ? "opened" : "failed", request.at);
    }

    String describe() {
        String top = null;
        try { top = foreground.topPackage(); } catch (Throwable ignored) { /* unknown, not false */ }
        final boolean connected;
        final Opening opening;
        synchronized (this) {
            connected = active;
            opening = last;
        }
        String visible = top == null ? "unknown" : Boolean.toString(connected && "com.daudio.app.dipo".equals(top));
        String text = "sessionActive=" + connected + " carplayVisible=" + visible + " topPackage=" + (top == null ? "unknown" : top);
        if (opening == null) return text + " lastVehicleLaunch=none";
        long age = Math.max(0, clock.now() - opening.at);
        return text + " lastVehicleLaunch=" + opening.id + " target=" + opening.target + " phase=" + opening.phase
                + " launchAgoMs=" + age + " recentVehicleLaunch=" + (age <= RECENT_MS);
    }
}
