package ioniq6.cpclimate;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** In DiPO only: authenticate stock peers, read live feedback and persist in this app's own files. */
final class IntakeLearner extends Thread {
    interface Session { boolean active(); }
    final Context context;
    final File dir;
    final VsChannel.Log out;
    final Session session;
    final String product = IntakeLearning.product();
    final int namedFront = IntakeLearning.frontSelector();
    final IntakeTrainer trainer = new IntakeTrainer(product, namedFront);
    final AtomicReference<IntakeTrainer.Capture> pending = new AtomicReference<IntakeTrainer.Capture>();
    final ConcurrentMap<Integer, Boolean> trusted = new ConcurrentHashMap<Integer, Boolean>();
    volatile String status = "Starting Kia command observer";
    volatile boolean ready;
    volatile long lastCommandAt;
    int previous = -1, lines;
    long logWindow;
    IntakeLearner(Context context, File dir, VsChannel.Log out, Session session) {
        super("cp-climate-intake-handoff"); this.context = context; this.dir = dir; this.out = out; this.session = session; setDaemon(true);
    }
    synchronized void log(String text) {
        long now = SystemClock.elapsedRealtime();
        if (now < logWindow || now - logWindow >= 60000) { lines = 0; logWindow = now; }
        if (lines++ < 100) out.log("CAR intake " + text);
    }
    boolean allowed(Credentials peer) {
        if (peer == null || peer.getPid() == android.os.Process.myPid()) return false;
        if (peer.getUid() == android.os.Process.myUid()) return IntakeProtocol.peerAllowed(peer.getUid(), peer.getPid(), android.os.Process.myUid(), android.os.Process.myPid(), false);
        Boolean cached = trusted.get(peer.getUid());
        if (cached != null) return IntakeProtocol.peerAllowed(peer.getUid(), peer.getPid(), android.os.Process.myUid(), android.os.Process.myPid(), cached);
        boolean ok = false;
        try {
            PackageManager pm = context.getPackageManager();
            String[] packages = pm.getPackagesForUid(peer.getUid());
            if (packages != null) for (String pkg : packages) if (IntakeLearning.stockApp(context, pkg)) ok = true;
        } catch (Throwable unavailable) { }
        trusted.put(peer.getUid(), ok);
        return IntakeProtocol.peerAllowed(peer.getUid(), peer.getPid(), android.os.Process.myUid(), android.os.Process.myPid(), ok);
    }
    @Override public void run() {
        LocalServerSocket server = null;
        ThreadPoolExecutor workers = null;
        try {
            if (!CarApi.ev6Target(product) || !CarApi.climateFlagMismatches().isEmpty()) throw new IOException("firmware check failed");
            server = new LocalServerSocket(IntakeProtocol.SOCKET);
            workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(4), new ThreadFactory() {
                public Thread newThread(Runnable r) { Thread t = new Thread(r, "cp-climate-intake-peer"); t.setDaemon(true); return t; }
            });
            ready = true; status = "Use Kia recirculation OFF, then ON, then OFF to learn";
            log("receiver ready; named front selector=" + namedFront + "; front controls require an observed ON/OFF pair");
            Thread confirm = new Thread(new Runnable() { public void run() { confirmations(); } }, "cp-climate-intake-confirm");
            confirm.setDaemon(true); confirm.start();
            while (true) {
                final LocalSocket socket = server.accept();
                try { workers.execute(new Runnable() { public void run() { receive(socket); } }); }
                catch (RejectedExecutionException full) { socket.close(); log("handoff busy; stock operation continues"); }
            }
        } catch (Throwable t) { status = "Learning unavailable; export diagnostic log"; log("receiver unavailable: " + t.getClass().getSimpleName()); }
        finally {
            ready = false;
            if (workers != null) workers.shutdownNow();
            if (server != null) try { server.close(); } catch (IOException ignored) { }
        }
    }
    void receive(LocalSocket socket) {
        IntakeTrainer.Capture capture = null;
        try {
            socket.setSoTimeout(IntakeProtocol.TIMEOUT_MS);
            Credentials peer = socket.getPeerCredentials();
            if (!allowed(peer)) { log("peer rejected uid=" + (peer == null ? -1 : peer.getUid())); return; }
            ClimateMap.Command c = IntakeProtocol.read(socket.getInputStream());
            DataOutputStream reply = new DataOutputStream(socket.getOutputStream());
            if (!session.active() || !trainer.accepts(c)) { reply.writeInt(-1); reply.flush(); return; }
            int before = IntakeLearning.read();
            capture = trainer.begin(c, before, SystemClock.elapsedRealtime());
            reply.writeInt(capture == null ? -1 : before); reply.flush();
            if (capture == null) { status = "Intake feedback unavailable; use Kia controls"; log("candidate withheld: intake=" + before); return; }
            lastCommandAt = SystemClock.elapsedRealtime();
            log("candidate " + c + " before=" + before);
            int completed = new DataInputStream(socket.getInputStream()).readInt();
            if (completed != 1) { trainer.cancel(capture); log("stock command failed; candidate discarded"); return; }
            synchronized (trainer) {
                if (trainer.current(capture)) { pending.set(capture); status = "Kia command received; confirming intake change"; }
            }
        } catch (Throwable t) { if (capture != null) trainer.cancel(capture); log("handoff not completed: " + t.getClass().getSimpleName()); }
        finally { try { socket.close(); } catch (IOException ignored) { } }
    }
    void confirmations() {
        while (ready) try {
            IntakeTrainer.Capture c = pending.getAndSet(null);
            if (c == null) { Thread.sleep(100); continue; }
            int consecutive = 0, reading = -1, target = c.before == 1 ? 2 : 1;
            while (trainer.current(c) && session.active() && SystemClock.elapsedRealtime() - c.at <= IntakeTrainer.CONFIRM_MS) {
                reading = IntakeLearning.read(); consecutive = reading == target ? consecutive + 1 : 0;
                if (consecutive == 2) break;
                Thread.sleep(250);
            }
            if (!trainer.current(c)) continue;
            if (consecutive != 2 || !session.active()) {
                trainer.cancel(c); status = "Kia command seen; change not confirmed (export log)";
                log("feedback not confirmed for " + c.command + " before=" + c.before + " last=" + reading); continue;
            }
            FreshAirProfile profile = trainer.finish(c, reading, reading, SystemClock.elapsedRealtime());
            if (profile != null) {
                profile.save(dir);
                if (FreshAirProfile.read(dir, product, namedFront) == null) throw new IOException("saved profile did not validate");
                status = "Kia command learned"; out.log("CAR learned Kia fresh-air command " + profile.command + " (saved by CarPlay)");
            } else if (c.before == 2) { status = "Kia ON observed; now turn recirculation OFF"; log("confirmed recirculation ON " + c.command); }
            else { status = "Kia OFF observed; repeat OFF, ON, OFF to learn both directions"; log("fresh feedback confirmed; ON/OFF pair not established for " + c.command); }
        } catch (Throwable t) { status = "Learning could not save; export diagnostic log"; log("confirmation unavailable: " + t.getClass().getSimpleName()); }
    }
    synchronized void feedback(int value) {
        if (value != 1 && value != 2) return;
        if (ready && previous >= 0 && value != previous && SystemClock.elapsedRealtime() - lastCommandAt > IntakeTrainer.CONFIRM_MS
                && FreshAirProfile.read(dir, product, namedFront) == null) {
            status = "Kia state changed; command not captured (export log)";
            log("state changed " + previous + "->" + value + " without a stock command handoff");
        }
        previous = value;
    }
}
