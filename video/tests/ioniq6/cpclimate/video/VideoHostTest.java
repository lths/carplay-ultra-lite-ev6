package ioniq6.cpclimate.video;

import java.io.*;
import java.net.*;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local-only fixtures for URL redaction, bundled roots, redirects, chunking and parallel HTTP ranges. */
public final class VideoHostTest {
    static int fails;
    static void check(String name, boolean ok) { if (!ok) fails++; System.out.println((ok ? "ok   " : "FAIL ") + name); }
    static byte[] all(InputStream in) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int n; while ((n = in.read(block)) >= 0) b.write(block, 0, n); return b.toByteArray(); }
    static final byte[] DATA = new byte[2 * 1024 * 1024 + 7];
    static final AtomicBoolean failRange = new AtomicBoolean(true);
    static void write(OutputStream out, String text) throws IOException { out.write(text.getBytes("US-ASCII")); }
    static void serve(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
            String request = r.readLine(), range = null, line;
            if (request == null) return;
            while ((line = r.readLine()) != null && line.length() > 0) if (line.toLowerCase(Locale.US).startsWith("range:")) range = line.substring(6).trim();
            OutputStream out = socket.getOutputStream();
            if (request.contains(" /redirect ")) {
                write(out, "HTTP/1.1 302 Found\r\nLocation: /blob\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            } else if (request.contains(" /chunked ")) {
                write(out, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n3\r\nhel\r\n2\r\nlo\r\n0\r\n\r\n");
            } else {
                int start = 0, end = DATA.length - 1;
                if (range != null) {
                    String[] bounds = range.substring("bytes=".length()).split("-");
                    start = Integer.parseInt(bounds[0]);
                    if (bounds.length > 1) end = Math.min(end, Integer.parseInt(bounds[1]));
                }
                if (start == ParallelStream.CHUNK && failRange.compareAndSet(true, false)) {
                    write(out, "HTTP/1.1 503 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                } else {
                    write(out, "HTTP/1.1 " + (range == null ? "200 OK" : "206 Partial Content") + "\r\nContent-Length: " + (end - start + 1)
                            + "\r\nContent-Range: bytes " + start + "-" + end + "/" + DATA.length + "\r\nConnection: close\r\n\r\n");
                    out.write(DATA, start, end - start + 1);
                }
            }
            out.flush();
        } catch (IOException ignored) { }
        finally { try { socket.close(); } catch (IOException ignored) { } }
    }
    public static void main(String[] args) throws Exception {
        String safe = VideoLog.clean("load https://user:pass@test.invalid/private-title/seg.ts?token=secret failed\nProxy-Authorization: Basic private-key\nstatus=503");
        check("URLs and auth headers are removed from exception diagnostics", !safe.contains("secret") && !safe.contains("private-") && !safe.contains("user:pass") && safe.contains("status=503"));
        check("video diagnostic entries are bounded", VideoLog.clean(String.join("", Collections.nCopies(10000, "x"))).length() <= 4097);
        check("useful codec and host-only messages are retained", "host cdn.invalid codec avc1".equals(VideoLog.clean("host cdn.invalid codec avc1")));
        int roots = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(ExtraRoots.PEM.getBytes("US-ASCII"))).size();
        check("the bundled supplemental trust roots parse", roots == ExtraRoots.COUNT && roots > 20);
        for (int i = 0; i < DATA.length; i++) DATA[i] = (byte) (i * 31 + i / 257);
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread listener = new Thread(new Runnable() { public void run() {
            while (!server.isClosed()) try {
                final Socket socket = server.accept();
                Thread worker = new Thread(new Runnable() { public void run() { serve(socket); } }); worker.setDaemon(true); worker.start();
            } catch (IOException done) { return; }
        } }); listener.setDaemon(true); listener.start();
        TunnelHttp http = new TunnelHttp(new TunnelHttp.Tunnels() { public Socket open(String host, int port) throws IOException { Socket s = new Socket(host, port); s.setSoTimeout(5000); return s; } });
        String base = "http://127.0.0.1:" + server.getLocalPort();
        TunnelHttp.Response response = http.get(base + "/chunked", 0, -1, null);
        check("chunked HTTP bodies are decoded", "hello".equals(new String(all(response.body), "US-ASCII"))); response.close();
        response = http.get(base + "/redirect", 0, -1, null);
        check("relative redirects retain the complete body", response.url.equals(base + "/blob") && Arrays.equals(all(response.body), DATA)); response.close();
        response = http.get(base + "/blob", 100, 1000, null);
        check("byte ranges retain their exact boundaries", response.status == 206 && Arrays.equals(all(response.body), Arrays.copyOfRange(DATA, 100, 1100))); response.close();
        response = http.get(base + "/blob", 0, ParallelStream.CHUNK, null);
        InputStream parallel = new ParallelStream(http, base + "/blob", null, response, ParallelStream.CHUNK, DATA.length);
        check("parallel ranges deliver ordered bytes through a retried failure", Arrays.equals(all(parallel), DATA) && !failRange.get()); parallel.close();
        server.close();
        System.exit(fails == 0 ? 0 : 1);
    }
}
