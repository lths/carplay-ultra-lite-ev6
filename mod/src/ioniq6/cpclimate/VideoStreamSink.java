package ioniq6.cpclimate;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;

/** Drain the undocumented encrypted video settings/overlay streams and acknowledge sync frames.
 * Their contents are not interpreted, recorded or exported. */
final class VideoStreamSink extends Thread {
    final File dir;
    final VsChannel.Log out;
    final int lane, port;
    VideoStreamSink(File dir, VsChannel.Log out, int lane, int port) {
        super("cp-climate-video-side-" + lane);
        this.dir = dir; this.out = out; this.lane = lane; this.port = port; setDaemon(true);
    }
    @Override public void run() {
        try {
            ServerSocket server = new ServerSocket(port);
            while (true) {
                final Socket socket = server.accept();
                Thread connection = new Thread(new Runnable() {
                    @Override public void run() { serve(socket); }
                }, "cp-climate-video-side-connection");
                connection.setDaemon(true); connection.start();
            }
        } catch (Throwable t) { out.log("VIDEO side listener " + lane + " unavailable: " + t.getClass().getSimpleName()); }
    }
    void serve(Socket socket) {
        try {
            byte[] keys = new byte[72];
            DataInputStream file = new DataInputStream(new FileInputStream(new File(dir, "cpc-vs-" + lane + ".key")));
            try { file.readFully(keys); } finally { file.close(); }
            ChaChaPoly cipher = new ChaChaPoly(java.util.Arrays.copyOfRange(keys, 8, 40), java.util.Arrays.copyOfRange(keys, 40, 72));
            java.util.Arrays.fill(keys, (byte) 0);
            VsChannel.Writer writer = new VsChannel.Writer(socket, socket.getOutputStream(), cipher);
            InputStream input = socket.getInputStream();
            byte[] sealed = new byte[65536];
            int have = 0;
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            while (true) {
                if (have == sealed.length) throw new java.io.IOException("oversized encrypted frame");
                int n = input.read(sealed, have, sealed.length - have);
                if (n < 0) return;
                have += n;
                int used = cipher.open(sealed, have, plain);
                if (used < 0) throw new java.io.IOException("unauthenticated frame");
                System.arraycopy(sealed, used, sealed, 0, have - used);
                have -= used;
                byte[] data = plain.toByteArray();
                int at = 0;
                while (data.length - at >= VsChannel.HEADER) {
                    int size = VsChannel.be32(data, at);
                    if (size < VsChannel.HEADER || size > VsChannel.MAX_PACKAGE) throw new java.io.IOException("invalid package size");
                    if (data.length - at < size) break;
                    if (VsChannel.be32(data, at + 4) == VsChannel.SYNC) {
                        long token = (long) VsChannel.be32(data, at + 20) << 32 | (VsChannel.be32(data, at + 24) & 0xffffffffL);
                        writer.send(VsChannel.pack(VsChannel.REPLY, 0, token, new byte[0]));
                    }
                    at += size;
                }
                plain.reset(); plain.write(data, at, data.length - at);
                if (plain.size() > VsChannel.MAX_PACKAGE) throw new java.io.IOException("oversized package");
            }
        } catch (Throwable t) { out.log("VIDEO side stream " + lane + " ended: " + t.getClass().getSimpleName()); }
        finally { VsChannel.close(socket); }
    }
}
