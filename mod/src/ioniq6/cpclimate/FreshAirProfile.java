package ioniq6.cpclimate;

import java.io.*;

/** A replayable intake command observed in Kia's UI, bound to the exact product and named selector. */
final class FreshAirProfile {
    static final String FILE = "cpc-fresh-air-v1";
    final String product;
    final ClimateMap.Command command;
    FreshAirProfile(String product, ClimateMap.Command command) { this.product = product; this.command = command; }
    static boolean transition(int before, int first, int second) { return before == 1 && first == 2 && second == 2; }
    static boolean permitted(ClimateMap.Command c, int frontSelector) {
        return c != null && (c.kind == ClimateMap.VR && c.a == 11 && c.b != 1
                || c.kind == ClimateMap.FRONT && frontSelector >= 0 && c.a == frontSelector)
                && c.b >= 0 && c.b <= 255 && c.c >= 0 && c.c <= 255 && (c.kind != ClimateMap.FRONT || c.c == 0);
    }
    String encode() { return "fresh-air-v1 " + product + " " + command.kind + " " + command.a + " " + command.b + " " + command.c + "\n"; }
    static FreshAirProfile decode(String text, String product, int frontSelector) {
        if (text == null || text.length() > 512 || !CarApi.ev6Target(product)) return null;
        try {
            String[] p = text.trim().split("\\s+");
            if (p.length != 6 || !"fresh-air-v1".equals(p[0]) || !product.equals(p[1])) return null;
            ClimateMap.Command c = new ClimateMap.Command(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]));
            return permitted(c, frontSelector) ? new FreshAirProfile(product, c) : null;
        } catch (RuntimeException bad) { return null; }
    }
    static FreshAirProfile read(File dir, String product, int frontSelector) {
        try {
            File f = new File(dir, FILE);
            if (f.length() <= 0 || f.length() > 512) return null;
            BufferedReader reader = new BufferedReader(new FileReader(f));
            try { return decode(reader.readLine(), product, frontSelector); } finally { reader.close(); }
        } catch (IOException absent) { return null; }
    }
    void save(File dir) throws IOException {
        File target = new File(dir, FILE), tmp = new File(dir, FILE + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try { out.write(encode().getBytes("US-ASCII")); out.getFD().sync(); } finally { out.close(); }
        if (!tmp.renameTo(target)) { tmp.delete(); throw new IOException("intake profile rename failed"); }
    }
}
