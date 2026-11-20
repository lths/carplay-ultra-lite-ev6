package ioniq6.cpclimate;

import java.lang.reflect.Method;

/**
 * Resume the iPhone's current Now Playing session through DiPO's own CarPlayService.
 * No app ID is saved: the iPhone owns the last audio app, queue and playback position.
 * Play is deliberately distinct from PlayOrPause: reconnecting must not pause audio
 * which the phone has already resumed. There is no FM or Bluetooth fallback.
 */
final class CarPlayMedia implements VehicleState.Controls {
    static final long CONNECT_DELAY_MS = 3000, PLAY_DELAY_MS = 250, RELEASE_DELAY_MS = 100;

    interface Later {
        void post(Runnable action, long delayMs);
    }

    private final Later later;
    private final VsChannel.Log out;
    private Session current;
    private long request;

    private static final class Session {
        final Object service;
        final Method requestUI, onConsumer;
        final Class<?> consumer;

        Session(Object service) throws Exception {
            this.service = service;
            requestUI = service.getClass().getMethod("requestUI", String.class);
            Method found = null;
            for (Method m : service.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!"onConsumer".equals(m.getName()) || p.length != 2 || !p[0].isEnum() || p[1] != boolean.class) continue;
                if (found != null) throw new IllegalStateException("ambiguous onConsumer signature");
                found = m;
            }
            if (found == null) throw new NoSuchMethodException("onConsumer(enum, boolean)");
            onConsumer = found;
            consumer = found.getParameterTypes()[0];
            // Resolve the explicit Play button before scheduling anything. A firmware with
            // only a toggle cannot safely implement unattended resume.
            button("Play");
        }

        Object button(String name) throws Exception {
            Object value = consumer.getField(name).get(null);
            if (!consumer.isInstance(value)) throw new IllegalStateException("invalid consumer " + name);
            return value;
        }
    }

    CarPlayMedia(Later later, VsChannel.Log out) {
        this.later = later;
        this.out = out;
    }

    synchronized void started(Object service, boolean autoResume) {
        if (current != null && current.service == service) return; // one attempt per session, not per callback
        current = null;
        final long ticket = ++request;
        final Session session;
        try {
            session = new Session(service);
        } catch (Throwable t) {
            out.log("MEDIA resume unavailable: " + t);
            return;
        }
        current = session;
        out.log("MEDIA CarPlay connected; automatic resume " + (autoResume ? "on" : "off"));
        if (autoResume) later.post(new Runnable() {
            @Override public void run() {
                resume(session, ticket, "connect");
            }
        }, CONNECT_DELAY_MS);
    }

    synchronized void ended(Object service) {
        if (current == null || current.service != service) return;
        current = null;
        request++;
        out.log("MEDIA CarPlay ended; pending resume cancelled");
    }

    void resume() {
        final Session session;
        final long ticket;
        synchronized (this) {
            session = current;
            ticket = ++request; // a tap replaces the pending automatic attempt
        }
        if (session == null) {
            out.log("MEDIA resume unavailable: no active CarPlay session");
            return;
        }
        later.post(new Runnable() {
            @Override public void run() {
                resume(session, ticket, "shortcut");
            }
        }, 0);
    }

    private synchronized boolean pending(Session session, long ticket) {
        return current == session && request == ticket;
    }

    private void resume(final Session session, final long ticket, String reason) {
        if (!pending(session, ticket)) return;
        try {
            Object result = session.requestUI.invoke(session.service, "nowplaying:");
            out.log("MEDIA " + reason + " requested Now Playing: " + result);
        } catch (Throwable t) {
            out.log("MEDIA Now Playing request failed: " + t);
        }
        later.post(new Runnable() {
            @Override public void run() {
                if (pending(session, ticket)) press(session, "Play");
            }
        }, PLAY_DELAY_MS);
    }

    private void press(final Session session, final String name) {
        final Object button;
        synchronized (this) {
            if (current != session) return;
        }
        try {
            button = session.button(name);
        } catch (Throwable t) {
            out.log("MEDIA " + name + " unavailable: " + t);
            return;
        }
        // Always pair a press with a release, even when invocation reports an error.
        try {
            out.log("MEDIA " + name + " down: " + session.onConsumer.invoke(session.service, button, true));
        } catch (Throwable t) {
            out.log("MEDIA " + name + " down failed: " + t);
        } finally {
            later.post(new Runnable() {
                @Override public void run() {
                    synchronized (CarPlayMedia.this) {
                        if (current != session) return; // never release into a new connection
                    }
                    try {
                        out.log("MEDIA " + name + " up: " + session.onConsumer.invoke(session.service, button, false));
                    } catch (Throwable t) {
                        out.log("MEDIA " + name + " up failed: " + t);
                    }
                }
            }, RELEASE_DELAY_MS);
        }
    }

    @Override public void pressed(String name, Object value) {
        if ("play".equals(name) || "changeMediaSource".equals(name)
                || "tuneToIdentifier".equals(name) || "tuneToFrequency".equals(name)) {
            resume();
            return;
        }
        final String button = "pause".equals(name) ? "Pause"
                : "nextItem".equals(name) ? "ScanNextTrack" : "previousItem".equals(name) ? "ScanPreviousTrack" : null;
        if (button == null) return;
        final Session session;
        synchronized (this) {
            session = current;
            request++; // a user's media action cancels delayed automatic play
        }
        if (session == null) return;
        later.post(new Runnable() {
            @Override public void run() { press(session, button); }
        }, 0);
    }
}
