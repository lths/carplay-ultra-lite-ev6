package ioniq6.cpclimate.check;

/** A shared native library alone must never produce a positive climate or vehicle verdict. */
public final class CheckReportTest {
    static void pass(Main m, String area, String name) {
        m.add(area, name, "PASS", "fixture", "fixture", "fixture");
    }

    static String climate(Main m) {
        return m.verdicts().get(0)[1];
    }

    public static void main(String[] args) {
        Main m = new Main();
        pass(m, "CarPlay (DiPO)", "/system/lib/libCarPlayCore.so");
        pass(m, "Car framework", "IDATCService.getVRClimateStatus(I)F");
        pass(m, "Car framework", "IDATCService.setVRClimateStatus(III)V");
        if (!"NO".equals(climate(m))) throw new AssertionError("missing flags must prevent climate verdict");
        pass(m, "Car framework", "Climate flag numbers (DATCFlag, DATCControl)");
        pass(m, "Live climate", "Read-only climate readings");
        if (!"NO".equals(climate(m))) throw new AssertionError("unknown units must prevent climate verdict");
        pass(m, "Live climate", "Temperature unit");
        if (!"YES".equals(climate(m))) throw new AssertionError("all climate requirements should pass");
        for (String[] v : m.verdicts()) {
            if (v[0].startsWith("Vehicle page") && !"NO".equals(v[1]))
                throw new AssertionError("missing HSCAN must prevent vehicle verdict");
            if (v[0].startsWith("Volume link") && !"NO".equals(v[1]))
                throw new AssertionError("missing audio must prevent volume verdict");
        }
        if (Main.readable("NaN") || Main.readable("Infinity") || Main.readable("-1") || Main.readable(null)
                || Main.readable("error: unavailable") || !Main.readable("0") || !Main.readable("72.5"))
            throw new AssertionError("invalid readings must not pass");
        if (Main.seatFeedback("-1", "-1") != -1 || Main.seatFeedback("0", "9") != -1
                || Main.seatFeedback(null, "error: unavailable") != -1 || Main.seatFeedback("2", "-1") != 2
                || Main.seatFeedback("3", "8") != 3 || Main.seatFeedback("-1", "7") != 7)
            throw new AssertionError("invalid seat feedback must not pass; prefer the body controller when both are valid");
        pass(m, "Car framework", "IUSMService.setFrontSeatHeatDrvStep(I)V");
        pass(m, "Car framework", "IUSMService.setFrontSeatHeatAstStep(I)V");
        if (!"NO".equals(m.verdicts().get(2)[1])) throw new AssertionError("signatures without live seat feedback must not yield YES");
        pass(m, "Live climate", "Seat heat states");
        if (!"YES".equals(m.verdicts().get(2)[1])) throw new AssertionError("valid feedback and signatures should pass the seat interface check");
        m.facts.put("model", "EV6");
        if (!m.json().contains("\"meaning\"")) throw new AssertionError("JSON must explain check results");
        System.out.println("ok   incomplete climate, vehicle and volume checks do not yield a positive verdict");
    }
}
