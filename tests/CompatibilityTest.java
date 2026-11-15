package ioniq6.cpclimate;

/** Runs against independent firmware fixtures: reordered AIDL, changed flags, and absent constants. */
public final class CompatibilityTest {
    public static void main(String[] args) {
        int bad = Integer.parseInt(args[0]);
        if (CarApi.climateFlagMismatches().size() != bad) throw new AssertionError(CarApi.climateFlagMismatches());
        if (CarApi.code(CarApi.DATC, "getVRClimateStatus", "(I)F") != 917)
            throw new AssertionError("must resolve reordered transaction 917 by name");
        if (CarApi.code(CarApi.DATC, "getVRClimateStatus", "(I)I") != -1)
            throw new AssertionError("wrong return type must be rejected");
        if (CarApi.code(CarApi.DATC, "absent", "()V") != -1)
            throw new AssertionError("missing call must be rejected");
        if (!CarApi.ev6Target("CVEV.USA.0000.V015.012.260507")
                || CarApi.ev6Target("CVEV.CAN.0000.V015.012.260507")
                || CarApi.ev6Target("CVEV.USA.0000.V015.012.260903")
                || CarApi.ev6Target("CEEV.USA.0000.V015.012.260507") || CarApi.ev6Target(null))
            throw new AssertionError("EV6 profile must be restricted to USA 260507");
        System.out.println("ok   reordered transactions, exact signatures, target firmware and " + bad + " incompatible flags");
    }
}
