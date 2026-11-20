package ioniq6.cpclimate;

import java.util.*;

/** Debounced real events; gaps and unknown readings never imply completion, disconnection or recovery. */
final class VehicleEvents {
    static volatile boolean enabled = true;
    static final long GAP_MS = 30000;
    static final class Event {
        final String label, detail, symbol;
        final int severity;
        Event(String label, String detail, String symbol, int severity) { this.label = label; this.detail = detail; this.symbol = symbol; this.severity = severity; }
    }
    long lastAt = -1;
    ChargingData.State last = ChargingData.State.UNKNOWN, candidate = ChargingData.State.UNKNOWN;
    int candidateCount, conditioning = -1, conditioningCandidate = -1, conditioningCount;
    boolean lowWarned, criticalWarned;
    List<Event> update(long now, double soc, ChargingData.State state, int condition) {
        List<Event> events = new ArrayList<Event>();
        boolean gap = lastAt < 0 || now < lastAt || now - lastAt > GAP_MS;
        lastAt = now;
        if (gap) { last = state; candidate = state; candidateCount = 0; conditioning = condition; conditioningCandidate = condition; conditioningCount = 0; }
        if (state != ChargingData.State.UNKNOWN && !gap) {
            candidateCount = candidate == state ? candidateCount + 1 : 1; candidate = state;
            if (candidateCount >= 2 && state != last) {
                if (last == ChargingData.State.UNKNOWN) { last = state; }
                else if (state == ChargingData.State.CHARGING) events.add(new Event("Charging started", "The car reports active charging.", "bolt.car", 0));
                else if (state == ChargingData.State.COMPLETE) events.add(new Event("Charging complete", "The car reports charging complete.", "battery.100", 0));
                else if (state == ChargingData.State.FAULT) events.add(new Event("Charging fault", "Check the charger and Kia's EV screen.", "exclamationmark.triangle", 1));
                else if (last == ChargingData.State.CHARGING) events.add(new Event("Charging stopped", "The car no longer reports active charging.", "bolt.slash", 0));
                last = state;
            }
        } else if (state == ChargingData.State.UNKNOWN) { candidate = state; candidateCount = 0; }
        if (!Double.isNaN(soc) && !Double.isInfinite(soc) && soc >= 0 && soc <= 100) {
            if (soc >= 22) lowWarned = false;
            if (soc >= 12) criticalWarned = false;
            if (state != ChargingData.State.CHARGING) {
                if (soc <= 10 && !criticalWarned) { events.add(new Event("Battery very low", "Battery charge is " + (int) Math.floor(soc) + "%.", "battery.0", 2)); criticalWarned = lowWarned = true; }
                else if (soc <= 20 && !lowWarned) { events.add(new Event("Battery low", "Battery charge is " + (int) Math.floor(soc) + "%.", "battery.25", 1)); lowWarned = true; }
            }
        }
        if ((condition == 0 || condition == 1) && !gap) {
            conditioningCount = conditioningCandidate == condition ? conditioningCount + 1 : 1; conditioningCandidate = condition;
            if (conditioningCount >= 2 && condition != conditioning) {
                if (conditioning >= 0) events.add(new Event(condition == 1 ? "Battery conditioning active" : "Battery conditioning stopped",
                        condition == 1 ? "The car confirms battery conditioning is active." : "The car no longer reports active battery conditioning.", "thermometer.medium", 0));
                conditioning = condition;
            }
        } else if (condition != 0 && condition != 1) { conditioningCandidate = -1; conditioningCount = 0; }
        return events;
    }
}
