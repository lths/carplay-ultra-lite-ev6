package ioniq6.cpclimate;

/** A missing, non-Park or stale sample cannot authorize video. Times use elapsedRealtime. */
final class ParkedState {
    static final long FRESH_MS = 1500;
    private int raw = -1;
    private long sampledAt = -1;
    synchronized void sample(int value, long now) { raw = value; sampledAt = now; }
    synchronized boolean allowed(long now) {
        return raw == 1 && sampledAt >= 0 && now >= sampledAt && now - sampledAt <= FRESH_MS;
    }
}
