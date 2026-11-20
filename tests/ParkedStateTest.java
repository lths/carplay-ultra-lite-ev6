package ioniq6.cpclimate;

public final class ParkedStateTest {
    static int fails;
    static void check(String name, boolean ok) { if (!ok) fails++; System.out.println((ok ? "ok   " : "FAIL ") + name); }
    public static void main(String[] args) {
        ParkedState state = new ParkedState();
        check("no feedback cannot authorize video", !state.allowed(0));
        state.sample(1, 1000);
        check("fresh confirmed Park allows video", state.allowed(1000) && state.allowed(2000));
        check("stale Park disables video", !state.allowed(2501));
        check("a reversed clock cannot authorize video", !state.allowed(999));
        for (int raw : new int[] { -1, 0, 2, 3, 255 }) {
            state.sample(raw, 3000);
            check("non-Park/unknown " + raw + " cuts off the previous Park state", !state.allowed(3000));
        }
        state.sample(1, 4000);
        check("fresh Park can authorize again after feedback recovers", state.allowed(4000));
        System.exit(fails == 0 ? 0 : 1);
    }
}
