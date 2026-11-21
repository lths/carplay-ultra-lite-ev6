package ioniq6.cpclimate;

import java.io.*;

/** Fixed-size local handoff; no filenames, package claims or arbitrary payloads from the client. */
final class IntakeProtocol {
    static final String SOCKET = "hmg-cpc-intake-v2";
    static final int MAGIC = 0x43504932, TIMEOUT_MS = 500;
    static boolean peerAllowed(int uid, int pid, int ownerUid, int ownerPid, boolean trustedStock) {
        return uid >= 0 && pid > 0 && ownerPid > 0 && pid != ownerPid && (uid == ownerUid || trustedStock);
    }
    static boolean candidate(ClimateMap.Command c) {
        return c != null && (c.kind == ClimateMap.VR && c.a == 11
                || c.kind == ClimateMap.FRONT && c.a >= 0 && c.a <= 255 && c.c == 0)
                && c.b >= 0 && c.b <= 255 && c.c >= 0 && c.c <= 255;
    }
    static void write(OutputStream out, ClimateMap.Command c) throws IOException {
        if (!candidate(c)) throw new IOException("not a climate candidate");
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(MAGIC); d.writeInt(c.kind); d.writeInt(c.a); d.writeInt(c.b); d.writeInt(c.c); d.flush();
    }
    static ClimateMap.Command read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        if (d.readInt() != MAGIC) throw new IOException("invalid intake handoff");
        ClimateMap.Command c = new ClimateMap.Command(d.readInt(), d.readInt(), d.readInt(), d.readInt());
        if (!candidate(c)) throw new IOException("not a climate candidate");
        return c;
    }
}
