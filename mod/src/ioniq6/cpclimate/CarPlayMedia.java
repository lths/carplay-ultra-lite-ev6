package ioniq6.cpclimate;

import java.lang.reflect.Method;

/**
 * Forward explicit media-button actions through DiPO's CarPlayService.
 * Connecting only attaches the receiver: no UI request, autoplay or source selection.
 */
final class CarPlayMedia implements VehicleState.Controls {
    static final long RELEASE_DELAY_MS = 100;

    interface Later {
        void post(Runnable action, long delayMs);
    }

    private final Later later;
    private final VsChannel.Log out;
    private Session current;

    private static final class Session {
        final Object service;
        final Method onConsumer;
        final Class<?> consumer;

        Session(Object service) throws Exception {
            this.service = service;
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

    synchronized void started(Object service) {
        if (current != null && current.service == service) return;
        current = null;
        final Session session;
        try {
            session = new Session(service);
        } catch (Throwable t) {
            out.log("MEDIA controls unavailable: " + t);
            return;
        }
        current = session;
        out.log("MEDIA CarPlay connected; manual media controls attached");
    }

    synchronized void ended(Object service) {
        if (current == null || current.service != service) return;
        current = null;
        out.log("MEDIA CarPlay ended; manual media controls detached");
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
        final String button = "play".equals(name) ? "Play" : "pause".equals(name) ? "Pause"
                : "nextItem".equals(name) ? "ScanNextTrack" : "previousItem".equals(name) ? "ScanPreviousTrack" : null;
        if (button == null) return;
        final Session session;
        synchronized (this) {
            session = current;
        }
        if (session == null) return;
        later.post(new Runnable() {
            @Override public void run() { press(session, button); }
        }, 0);
    }
}
