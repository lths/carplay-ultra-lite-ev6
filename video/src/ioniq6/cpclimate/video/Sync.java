package ioniq6.cpclimate.video;

/**
 * Keeps the car's picture with the phone's sound. The sound is the phone's own, played over CarPlay's normal
 * audio, and it keeps going when the car's picture stalls to load (on mobile data, often): each stall left the
 * picture that much behind for the rest of the video.
 *
 * The phone tells the car nothing of its sound's position, so this keeps a clock of where the sound should be:
 * set where the video first started playing, moved by every seek (the phone's or the car's), held while the
 * video is paused (by the phone's setRate or the car's button), and running otherwise, stalls included. step()
 * compares it with the picture: a picture behind plays faster (up to MAX_FAST; it has no sound of its own, so
 * that can't be heard), one ahead slower, and one more than JUMP_MS behind jumps to the sound when that point is
 * already loaded. It speeds up only with AHEAD_MS loaded, so catching up doesn't empty the buffer into another
 * stall on a slow connection. The speed is proportional to the gap and the correction runs until the gap is
 * within DONE_MS: 1.1.13 stopped at 60 ms and started at 150 ms, which left the picture 60-150 ms behind.
 *
 * Plain Java, the time passed in, so it is tested off the car (tests/SyncTest.java). Main thread only.
 */
final class Sync {
    /** behind or ahead by more than START_MS: correct; until back within DONE_MS */
    static final long START_MS = 80, DONE_MS = 20;
    /** behind by more than JUMP_MS, with the sound's point loaded and JUMP_MARGIN_MS after it: jump there */
    static final long JUMP_MS = 8000, JUMP_MARGIN_MS = 2000;
    /** speed up only with this much loaded ahead of the picture */
    static final long AHEAD_MS = 5000;
    /**
     * catching up: 1 + behind / GAIN_MS, between MIN_FAST and MAX_FAST (500 ms behind: the most); ahead,
     * 1 - ahead / GAIN_MS between MIN_SLOW and MAX_SLOW. Near the sound it corrects gently (2%: 10 ms a tick)
     */
    static final long GAIN_MS = 2000;
    static final float MIN_FAST = 1.02f, MAX_FAST = 1.25f, MAX_SLOW = 0.98f, MIN_SLOW = 0.9f;

    long anchorPos, anchorAt;
    boolean started, running;
    float speed = 1f;

    /** what to do now: the speed to play at, a position to jump to (-1: none), and the gap it saw */
    static final class Step {
        float speed = 1f;
        long seekTo = -1;
        /** where the sound should be minus where the picture is (positive: the picture is behind); 0 before start */
        long behind;
    }

    /** a new video: nothing to follow until it first plays */
    void reset() {
        started = running = false;
        speed = 1f;
    }

    /** the video plays for the first time (or again after reset), from pos */
    void start(long pos, long now) {
        started = running = true;
        anchorPos = pos;
        anchorAt = now;
    }

    /** where the phone's sound should be, or -1 before the video first played */
    long target(long now) {
        if (!started) return -1;
        return running ? anchorPos + Math.max(0, now - anchorAt) : anchorPos;
    }

    void pause(long now) {
        if (!started || !running) return;
        anchorPos = target(now);
        running = false;
    }

    void resume(long now) {
        if (!started || running) return;
        anchorAt = now;
        running = true;
    }

    void seek(long pos, long now) {
        if (!started) return;
        anchorPos = Math.max(0, pos);
        anchorAt = now;
    }

    /**
     * position: the picture's; buffered: how far it is loaded; playing: it is moving (not paused, not stalled).
     * While it isn't moving the speed is left alone: it's applied again once it moves.
     */
    Step step(long now, long position, long buffered, boolean playing) {
        Step s = new Step();
        long t = target(now);
        s.behind = t < 0 ? 0 : t - position;
        if (t < 0 || !running || !playing) {
            s.speed = speed;
            return s;
        }
        long behind = s.behind;
        if (behind > JUMP_MS && buffered >= t + JUMP_MARGIN_MS) {
            s.seekTo = t;
            speed = 1f;
        } else if ((behind > START_MS || (speed > 1f && behind > DONE_MS)) && buffered - position >= AHEAD_MS) {
            speed = Math.max(MIN_FAST, Math.min(MAX_FAST, 1f + behind / (float) GAIN_MS));
        } else if (behind < -START_MS || (speed < 1f && behind < -DONE_MS)) {
            speed = Math.max(MIN_SLOW, Math.min(MAX_SLOW, 1f + behind / (float) GAIN_MS));
        } else {
            speed = 1f;
        }
        s.speed = speed;
        return s;
    }
}
