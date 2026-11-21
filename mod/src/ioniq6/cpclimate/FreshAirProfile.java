package ioniq6.cpclimate;

import java.io.*;

/** An observed command, firmware-bound; unnamed front selectors require an observed ON/OFF pair. */
final class FreshAirProfile {
    static final String FILE = "cpc-fresh-air-v2", LEGACY_FILE = "cpc-fresh-air-v1";
    final String product;
    final ClimateMap.Command command, on;
    FreshAirProfile(String product, ClimateMap.Command command) { this(product, command, null); }
    FreshAirProfile(String product, ClimateMap.Command command, ClimateMap.Command on) { this.product = product; this.command = command; this.on = on; }
    static boolean transition(int before, int first, int second) { return before == 1 && first == 2 && second == 2; }
    static boolean permitted(ClimateMap.Command c, int frontSelector) {
        return c != null && (c.kind == ClimateMap.VR && c.a == 11 && !(c.b == 1 && c.c == 0)
                || c.kind == ClimateMap.FRONT && frontSelector >= 0 && c.a == frontSelector)
                && c.b >= 0 && c.b <= 255 && c.c >= 0 && c.c <= 255 && (c.kind != ClimateMap.FRONT || c.c == 0);
    }
    String encode() { return (on == null ? "fresh-air-v1 " : "fresh-air-v2 ") + product + " " + command.kind + " " + command.a + " " + command.b + " " + command.c
            + (on == null ? "" : " " + on.b + " " + on.c) + "\n"; }
    static FreshAirProfile decode(String text, String product, int frontSelector) {
        if (text == null || text.length() > 512 || !CarApi.ev6Target(product)) return null;
        try {
            String[] p = text.trim().split("\\s+");
            boolean pair = p.length == 8 && "fresh-air-v2".equals(p[0]);
            if (!(pair || p.length == 6 && "fresh-air-v1".equals(p[0])) || !product.equals(p[1])) return null;
            ClimateMap.Command c = new ClimateMap.Command(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]));
            if (pair) {
                ClimateMap.Command on = new ClimateMap.Command(c.kind, c.a, Integer.parseInt(p[6]), Integer.parseInt(p[7]));
                return c.kind == ClimateMap.FRONT && IntakeProtocol.candidate(c) && IntakeProtocol.candidate(on)
                        && c.b != on.b && (frontSelector < 0 || c.a == frontSelector) ? new FreshAirProfile(product, c, on) : null;
            }
            return permitted(c, frontSelector) ? new FreshAirProfile(product, c) : null;
        } catch (RuntimeException bad) { return null; }
    }
    static FreshAirProfile read(File dir, String product, int frontSelector) {
        try {
            File f = new File(dir, FILE);
            if (!f.exists()) f = new File(dir, LEGACY_FILE);
            if (f.length() <= 0 || f.length() > 512) return null;
            BufferedReader reader = new BufferedReader(new FileReader(f));
            try { return decode(reader.readLine(), product, frontSelector); } finally { reader.close(); }
        } catch (IOException absent) { return null; }
    }
    static void forget(File dir) { new File(dir, FILE).delete(); new File(dir, LEGACY_FILE).delete(); }
    void save(File dir) throws IOException {
        File target = new File(dir, FILE), tmp = new File(dir, FILE + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try { out.write(encode().getBytes("US-ASCII")); out.getFD().sync(); } finally { out.close(); }
        if (!tmp.renameTo(target)) { tmp.delete(); throw new IOException("intake profile rename failed"); }
    }
}
