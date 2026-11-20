package ioniq6.cpclimate;

import java.io.*;
import java.net.*;
import java.util.*;

/** The unused video side streams must drain fragmented encrypted data without saving its contents. */
public final class VideoStreamSinkTest {
    static int fails;
    static void check(String name, boolean ok) { if (!ok) fails++; System.out.println((ok ? "ok   " : "FAIL ") + name); }
    static byte[] join(byte[] a, byte[] b) { byte[] x = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, x, a.length, b.length); return x; }
    static Thread serve(final ServerSocket server, final VideoStreamSink sink) {
        Thread t = new Thread(new Runnable() { public void run() { try { sink.serve(server.accept()); } catch (IOException e) { throw new RuntimeException(e); } } });
        t.setDaemon(true); t.start(); return t;
    }
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0], "video-side"); dir.mkdir();
        byte[] keys = new byte[72]; new java.security.SecureRandom().nextBytes(keys);
        FileOutputStream key = new FileOutputStream(new File(dir, "cpc-vs-4.key")); key.write(keys); key.close();
        byte[] rd = Arrays.copyOfRange(keys, 8, 40), wr = Arrays.copyOfRange(keys, 40, 72);
        final StringBuffer log = new StringBuffer();
        VideoStreamSink sink = new VideoStreamSink(dir, new VsChannel.Log() { public void log(String s) { log.append(s).append('\n'); } }, 4, 0);
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Thread connection = serve(server, sink);
        Socket phone = new Socket("127.0.0.1", server.getLocalPort()); phone.setSoTimeout(3000);
        ChaChaPoly cipher = new ChaChaPoly(wr, rd);
        byte[] privateData = new byte[40000]; Arrays.fill(privateData, (byte) 'q');
        byte[] sync = VsChannel.pack(VsChannel.SYNC, 0, 0x1234567887654321L, new byte[0]);
        byte[] wire = cipher.seal(join(VsChannel.pack(VsChannel.ASYNC, 0, 0, privateData), sync));
        for (int i = 0; i < wire.length;) { int n = Math.min((i % 1597) + 1, wire.length - i); phone.getOutputStream().write(wire, i, n); i += n; }
        phone.getOutputStream().flush();
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        byte[] incoming = new byte[4096]; int have = 0;
        while (plain.size() < VsChannel.HEADER) {
            int n = phone.getInputStream().read(incoming, have, incoming.length - have);
            if (n < 0) throw new IOException("no sync reply");
            have += n; int used = cipher.open(incoming, have, plain);
            if (used < 0) throw new IOException("bad reply authentication");
            System.arraycopy(incoming, used, incoming, 0, have - used); have -= used;
        }
        byte[] reply = plain.toByteArray();
        check("fragmented multi-frame input receives an authenticated sync reply", VsChannel.be32(reply, 4) == VsChannel.REPLY);
        check("sync reply has no copied content", reply.length == VsChannel.HEADER && VsChannel.be32(reply, 0) == VsChannel.HEADER);
        check("sync token is preserved", VsChannel.be32(reply, 20) == 0x12345678 && VsChannel.be32(reply, 24) == 0x87654321);
        phone.close(); connection.join(3000);
        check("phone disconnect closes the stream", !connection.isAlive());
        check("side-stream contents are never recorded", Arrays.equals(dir.list(), new String[] { "cpc-vs-4.key" }));
        check("side-stream contents are never logged", log.indexOf("qqqq") < 0);
        connection = serve(server, sink);
        phone = new Socket("127.0.0.1", server.getLocalPort()); phone.setSoTimeout(3000);
        cipher = new ChaChaPoly(wr, rd);
        byte[] bad = cipher.seal(sync); bad[bad.length - 1] ^= 1;
        phone.getOutputStream().write(bad); phone.getOutputStream().flush();
        check("authentication failure closes the connection", phone.getInputStream().read() == -1);
        phone.close(); connection.join(3000); server.close();
        check("authentication failure is bounded to an error type", log.toString().contains("stream 4 ended: IOException"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
