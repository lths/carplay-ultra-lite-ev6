package ioniq6.cpclimate;

/** Attributes stable feedback to the latest completed stock command across all client processes. */
final class IntakeTrainer {
    static final long CONFIRM_MS = 4000, PAIR_MS = 30000;
    final String product;
    final int namedFront;
    private long sequence;
    private ClimateMap.Command on;
    private long onAt;
    IntakeTrainer(String product, int namedFront) { this.product = product; this.namedFront = namedFront; }
    static final class Capture {
        final ClimateMap.Command command;
        final int before;
        final long ticket, at;
        Capture(ClimateMap.Command c, int before, long ticket, long at) { command = c; this.before = before; this.ticket = ticket; this.at = at; }
    }
    boolean accepts(ClimateMap.Command c) {
        return CarApi.ev6Target(product) && IntakeProtocol.candidate(c)
                && !(c.kind == ClimateMap.FRONT && namedFront >= 0 && c.a != namedFront);
    }
    synchronized Capture begin(ClimateMap.Command c, int before, long now) {
        if (!accepts(c)) return null;
        ++sequence; // Even an unreadable newer operation prevents attribution to an earlier one.
        return (before == 1 || before == 2) && now >= 0 ? new Capture(c, before, sequence, now) : null;
    }
    synchronized boolean current(Capture c) { return c != null && c.ticket == sequence; }
    synchronized void cancel(Capture c) { if (current(c)) ++sequence; }
    synchronized FreshAirProfile finish(Capture c, int first, int second, long now) {
        if (!current(c)) return null;
        ++sequence; // A sample pair is consumed once.
        if (now < c.at || now - c.at > CONFIRM_MS || first != second) return null;
        if (c.before == 2 && first == 1) {
            on = c.command; onAt = now;
            return null;
        }
        if (!FreshAirProfile.transition(c.before, first, second)) return null;
        if (c.command.kind == ClimateMap.VR) {
            return FreshAirProfile.permitted(c.command, namedFront) ? new FreshAirProfile(product, c.command) : null;
        }
        // An unnamed front selector is learned only from both directions of the same stock control.
        if (on == null || now < onAt || now - onAt > PAIR_MS || on.kind != c.command.kind
                || on.a != c.command.a || on.b == c.command.b || !IntakeProtocol.candidate(on)) return null;
        return new FreshAirProfile(product, c.command, on);
    }
}
