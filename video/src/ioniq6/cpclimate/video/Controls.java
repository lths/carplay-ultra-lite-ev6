package ioniq6.cpclimate.video;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * The player's controls, drawn in code (the hook jar carries no Android resources, so ExoPlayer's own
 * player view, which inflates layouts, can't be used). Laid out after LIVI's video controls and Apple's
 * players: dark gradients at the top and bottom, a close button, picture fit and subtitles at the top
 * right, a big play/pause with skip back and forward in the middle, and a seek bar with the elapsed and
 * remaining time. They fade out after HIDE_MS while playing; a tap brings them back, a double tap on
 * the left or right third skips back or forward.
 *
 * Sizes follow the screen's height (720 px on Gen5W), so they also fit a smaller screen.
 */
final class Controls {
    static final long HIDE_MS = 4000, TICK_MS = 250, HINT_MS = 1200;
    static final int SKIP_BACK_MS = 10000, SKIP_FORWARD_MS = 10000;

    /** what the controls ask of the player (Video) */
    interface Actions {
        void close();

        void playPause();

        void seekTo(long ms);

        void seekBy(long ms);

        /** the next picture fit; returns its name for the hint */
        String nextFit();

        /** the next subtitle choice; returns its name for the hint, null if the video has none */
        String nextSubtitles();
    }

    /** what the controls show, read on every tick */
    static final class State {
        long position, duration, buffered;
        boolean playing, wanted, loading, hasSubtitles, subtitlesOn;
        String error;
    }

    final Context context;
    final Handler main;
    final Actions actions;
    final FrameLayout root;            // the overlay over the picture: subtitles below, controls on top
    final FrameLayout panel;           // everything that fades
    final TextView subtitles, hint, message, elapsed, remaining;
    final Icon close, fit, cc, back, playPause, forward;
    final Scrub scrub;
    final ProgressBar spinner;
    final int unit;
    boolean shown = true;
    long lastTouch;
    State last = new State();

    Controls(Context c, Handler main, Actions actions) {
        this.context = c;
        this.main = main;
        this.actions = actions;
        int h = c.getResources().getDisplayMetrics().heightPixels;
        int w = c.getResources().getDisplayMetrics().widthPixels;
        unit = Math.max(320, Math.min(h, w));
        int margin = px(0.045), small = px(0.1), big = px(0.17), mid = px(0.12);

        root = new FrameLayout(c);

        subtitles = new TextView(c);
        subtitles.setTextColor(Color.WHITE);
        subtitles.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(0.05));
        subtitles.setGravity(Gravity.CENTER);
        subtitles.setShadowLayer(px(0.008), 0, 0, Color.BLACK);
        subtitles.setBackgroundColor(0x00000000);
        subtitles.setPadding(margin, 0, margin, 0);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        sl.bottomMargin = margin;
        root.addView(subtitles, sl);

        panel = new FrameLayout(c);
        root.addView(panel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View top = new View(c);
        top.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0xB0000000, 0x00000000 }));
        panel.addView(top, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, small + 3 * margin, Gravity.TOP));
        View bottom = new View(c);
        bottom.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] { 0xC0000000, 0x00000000 }));
        panel.addView(bottom, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, small + 4 * margin, Gravity.BOTTOM));

        close = icon(Icon.CLOSE, small, new Runnable() {
            @Override
            public void run() {
                actions.close();
            }
        });
        panel.addView(close, at(small, Gravity.TOP | Gravity.LEFT, margin, margin, 0, 0));

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.HORIZONTAL);
        cc = icon(Icon.CC, small, new Runnable() {
            @Override
            public void run() {
                String s = actions.nextSubtitles();
                showHint(s == null ? "No subtitles" : s);
            }
        });
        fit = icon(Icon.FIT, small, new Runnable() {
            @Override
            public void run() {
                showHint(actions.nextFit());
            }
        });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(small, small);
        gap.leftMargin = margin;
        right.addView(cc, new LinearLayout.LayoutParams(small, small));
        right.addView(fit, gap);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, small, Gravity.TOP | Gravity.RIGHT);
        rl.topMargin = margin;
        rl.rightMargin = margin;
        panel.addView(right, rl);

        LinearLayout centre = new LinearLayout(c);
        centre.setOrientation(LinearLayout.HORIZONTAL);
        centre.setGravity(Gravity.CENTER_VERTICAL);
        back = icon(Icon.BACK, mid, new Runnable() {
            @Override
            public void run() {
                actions.seekBy(-SKIP_BACK_MS);
            }
        });
        playPause = icon(Icon.PAUSE, big, new Runnable() {
            @Override
            public void run() {
                actions.playPause();
            }
        });
        forward = icon(Icon.FORWARD, mid, new Runnable() {
            @Override
            public void run() {
                actions.seekBy(SKIP_FORWARD_MS);
            }
        });
        int spread = px(0.12);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(mid, mid);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(big, big);
        pl.leftMargin = spread;
        pl.rightMargin = spread;
        centre.addView(back, bl);
        centre.addView(playPause, pl);
        centre.addView(forward, new LinearLayout.LayoutParams(mid, mid));
        panel.addView(centre, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        elapsed = label(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        remaining = label(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        scrub = new Scrub(c, px(0.012), px(0.022));
        int labelW = px(0.16);
        bar.addView(elapsed, new LinearLayout.LayoutParams(labelW, FrameLayout.LayoutParams.MATCH_PARENT));
        bar.addView(scrub, new LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, 1));
        bar.addView(remaining, new LinearLayout.LayoutParams(labelW, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams barl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, small, Gravity.BOTTOM);
        barl.leftMargin = margin;
        barl.rightMargin = margin;
        barl.bottomMargin = margin;
        panel.addView(bar, barl);

        spinner = new ProgressBar(c, null, android.R.attr.progressBarStyleLarge);
        spinner.setIndeterminate(true);
        spinner.setVisibility(View.GONE);
        root.addView(spinner, new FrameLayout.LayoutParams(big, big, Gravity.CENTER));

        message = new TextView(c);
        message.setTextColor(Color.WHITE);
        message.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(0.04));
        message.setGravity(Gravity.CENTER);
        message.setVisibility(View.GONE);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        ml.bottomMargin = small + 3 * margin;
        root.addView(message, ml);

        hint = new TextView(c);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(0.045));
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setPadding(margin, margin / 2, margin, margin / 2);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xB0000000);
        hb.setCornerRadius(px(0.03));
        hint.setBackgroundDrawable(hb);
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        hl.topMargin = small + 2 * margin;
        root.addView(hint, hl);

        final GestureDetector taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (shown) hide();
                else show();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                int w3 = root.getWidth() / 3;
                if (e.getX() < w3) {
                    actions.seekBy(-SKIP_BACK_MS);
                    showHint("− 10 s");
                } else if (e.getX() > root.getWidth() - w3) {
                    actions.seekBy(SKIP_FORWARD_MS);
                    showHint("+ 10 s");
                } else {
                    actions.playPause();
                }
                return true;
            }
        });
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return taps.onTouchEvent(e);
            }
        });
        scrub.listener = new Scrub.Listener() {
            @Override
            public void moved(float f) {
                touched();
                if (last.duration > 0) elapsed.setText(time((long) (f * last.duration)));
            }

            @Override
            public void released(float f) {
                touched();
                if (last.duration > 0) actions.seekTo((long) (f * last.duration));
            }
        };
        touched();
        main.post(tick);
    }

    // ---- building ----

    int px(double ofUnit) {
        return Math.max(1, (int) Math.round(unit * ofUnit));
    }

    Icon icon(int kind, int size, final Runnable action) {
        Icon i = new Icon(context, kind);
        i.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                touched();
                action.run();
            }
        });
        return i;
    }

    TextView label(int gravity) {
        TextView t = new TextView(context);
        t.setTextColor(0xE6FFFFFF);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(0.035));
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setGravity(gravity);
        t.setSingleLine(true);
        return t;
    }

    static FrameLayout.LayoutParams at(int size, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(size, size, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    // ---- showing and hiding ----

    void touched() {
        lastTouch = android.os.SystemClock.uptimeMillis();
        if (!shown) show();
    }

    void show() {
        shown = true;
        lastTouch = android.os.SystemClock.uptimeMillis();
        panel.setVisibility(View.VISIBLE);
        panel.animate().cancel();
        panel.animate().alpha(1f).setDuration(150).setListener(null);
    }

    void hide() {
        shown = false;
        panel.animate().cancel();
        panel.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (!shown) panel.setVisibility(View.INVISIBLE);
            }
        });
    }

    final Runnable clearHint = new Runnable() {
        @Override
        public void run() {
            hint.setVisibility(View.GONE);
        }
    };

    void showHint(String s) {
        hint.setText(s);
        hint.setVisibility(View.VISIBLE);
        main.removeCallbacks(clearHint);
        main.postDelayed(clearHint, HINT_MS);
    }

    void setSubtitle(CharSequence text) {
        subtitles.setText(text == null ? "" : text);
    }

    // ---- the tick: progress, play state and auto-hide ----

    boolean stopped;

    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            try {
                State s = Video.controlsState();
                if (s != null) update(s);
                if (shown && last.playing && !scrub.dragging && android.os.SystemClock.uptimeMillis() - lastTouch > HIDE_MS) hide();
            } catch (Throwable ignored) {
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    /** the tick again, after stop() (the window is shown again) */
    void start() {
        if (!stopped) return;
        stopped = false;
        main.post(tick);
    }

    void stop() {
        stopped = true;
        main.removeCallbacks(tick);
        main.removeCallbacks(clearHint);
    }

    void update(State s) {
        last = s;
        playPause.setKind(s.wanted ? Icon.PAUSE : Icon.PLAY);
        cc.setOn(s.subtitlesOn);
        cc.setAlpha(s.hasSubtitles ? 1f : 0.4f);
        boolean seekable = s.duration > 0;
        scrub.setEnabled(seekable);
        back.setAlpha(seekable ? 1f : 0.4f);
        forward.setAlpha(seekable ? 1f : 0.4f);
        if (!scrub.dragging) {
            scrub.set(seekable ? (float) s.position / s.duration : 0, seekable ? (float) s.buffered / s.duration : 0);
            elapsed.setText(time(s.position));
        }
        remaining.setText(seekable ? "−" + time(Math.max(0, s.duration - s.position)) : s.duration < 0 ? "LIVE" : "");
        spinner.setVisibility(s.loading && s.error == null ? View.VISIBLE : View.GONE);
        if (s.error != null) {
            message.setText(s.error);
            message.setVisibility(View.VISIBLE);
            if (!shown) show();
        } else {
            message.setVisibility(View.GONE);
        }
    }

    static String time(long ms) {
        long t = Math.max(0, ms) / 1000;
        long h = t / 3600, m = t / 60 % 60, sec = t % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    // ---- views ----

    /** a round button with an icon drawn as paths (no image resources) */
    static final class Icon extends View {
        static final int PLAY = 0, PAUSE = 1, CLOSE = 2, BACK = 3, FORWARD = 4, FIT = 5, CC = 6;
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fg = new Paint(Paint.ANTI_ALIAS_FLAG),
                line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF r = new RectF();
        int kind;
        boolean on;

        Icon(Context c, int kind) {
            super(c);
            this.kind = kind;
            bg.setColor(0x59000000);
            fg.setColor(Color.WHITE);
            line.setColor(Color.WHITE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            setClickable(true);
        }

        void setKind(int k) {
            if (k != kind) {
                kind = k;
                invalidate();
            }
        }

        void setOn(boolean o) {
            if (o != on) {
                on = o;
                invalidate();
            }
        }

        @Override
        public void setPressed(boolean p) {
            super.setPressed(p);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h), cx = w / 2, cy = h / 2;
            bg.setColor(isPressed() ? 0x99FFFFFF : 0x59000000);
            c.drawCircle(cx, cy, s / 2, bg);
            line.setStrokeWidth(s * 0.07f);
            path.reset();
            float u = s * 0.22f;   // the icon's half size
            switch (kind) {
                case PLAY:
                    path.moveTo(cx - u * 0.7f, cy - u);
                    path.lineTo(cx + u * 1.0f, cy);
                    path.lineTo(cx - u * 0.7f, cy + u);
                    path.close();
                    c.drawPath(path, fg);
                    break;
                case PAUSE:
                    r.set(cx - u * 0.8f, cy - u, cx - u * 0.2f, cy + u);
                    c.drawRoundRect(r, u * 0.12f, u * 0.12f, fg);
                    r.set(cx + u * 0.2f, cy - u, cx + u * 0.8f, cy + u);
                    c.drawRoundRect(r, u * 0.12f, u * 0.12f, fg);
                    break;
                case CLOSE:
                    u *= 0.8f;
                    c.drawLine(cx - u, cy - u, cx + u, cy + u, line);
                    c.drawLine(cx + u, cy - u, cx - u, cy + u, line);
                    break;
                case BACK:
                case FORWARD: {
                    boolean fwd = kind == FORWARD;
                    float rad = u * 1.25f;
                    line.setStrokeWidth(s * 0.055f);
                    r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                    // an open circle with an arrow head at its top, turning back or forward
                    c.drawArc(r, fwd ? -60 : -120, fwd ? 300 : -300, false, line);
                    float ax = cx + (fwd ? 1 : -1) * rad * 0.5f, ay = cy - rad * 0.866f;
                    path.moveTo(ax + (fwd ? 1 : -1) * u * 0.45f, ay);
                    path.lineTo(ax - (fwd ? 1 : -1) * u * 0.15f, ay - u * 0.42f);
                    path.lineTo(ax - (fwd ? 1 : -1) * u * 0.15f, ay + u * 0.42f);
                    path.close();
                    c.drawPath(path, fg);
                    text.setTextSize(s * 0.24f);
                    c.drawText("10", cx, cy - (text.descent() + text.ascent()) / 2, text);
                    break;
                }
                case FIT: {
                    // a frame with four corner marks: the picture's fit
                    float a = u * 1.1f, b = u * 0.75f, k = u * 0.45f;
                    line.setStrokeWidth(s * 0.055f);
                    corner(c, cx - a, cy - b, k, k);
                    corner(c, cx + a, cy - b, -k, k);
                    corner(c, cx - a, cy + b, k, -k);
                    corner(c, cx + a, cy + b, -k, -k);
                    break;
                }
                case CC: {
                    float a = u * 1.2f, b = u * 0.85f;
                    r.set(cx - a, cy - b, cx + a, cy + b);
                    line.setStrokeWidth(s * 0.05f);
                    if (on) c.drawRoundRect(r, u * 0.3f, u * 0.3f, fg);
                    else c.drawRoundRect(r, u * 0.3f, u * 0.3f, line);
                    text.setTextSize(s * 0.24f);
                    text.setColor(on ? Color.BLACK : Color.WHITE);
                    c.drawText("CC", cx, cy - (text.descent() + text.ascent()) / 2, text);
                    text.setColor(Color.WHITE);
                    break;
                }
                default:
                    break;
            }
        }

        void corner(Canvas c, float x, float y, float dx, float dy) {
            path.reset();
            path.moveTo(x, y + dy);
            path.lineTo(x, y);
            path.lineTo(x + dx, y);
            c.drawPath(path, line);
        }
    }

    /** the seek bar: the track, what's loaded, what's played, and a thumb that grows while dragged */
    static final class Scrub extends View {
        interface Listener {
            void moved(float f);

            void released(float f);
        }

        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), loaded = new Paint(Paint.ANTI_ALIAS_FLAG),
                played = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final int bar, knob;
        float position, buffered, drag;
        boolean dragging;
        Listener listener;

        Scrub(Context c, int bar, int knob) {
            super(c);
            this.bar = bar;
            this.knob = knob;
            track.setColor(0x4DFFFFFF);
            loaded.setColor(0x80FFFFFF);
            played.setColor(Color.WHITE);
            thumb.setColor(Color.WHITE);
        }

        void set(float pos, float buf) {
            position = clamp(pos);
            buffered = clamp(Math.max(buf, pos));
            invalidate();
        }

        static float clamp(float f) {
            return f < 0 ? 0 : f > 1 ? 1 : f;
        }

        float left() {
            return getPaddingLeft() + knob * 1.5f;
        }

        float right() {
            return getWidth() - getPaddingRight() - knob * 1.5f;
        }

        float at(float x) {
            float l = left(), w = right() - l;
            return w <= 0 ? 0 : clamp((x - l) / w);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    drag = at(e.getX());
                    if (listener != null) listener.moved(drag);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    drag = at(e.getX());
                    dragging = false;
                    position = drag;
                    if (listener != null) listener.released(drag);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float l = left(), rt = right(), cy = getHeight() / 2f, half = bar / 2f;
            if (rt <= l) return;
            float w = rt - l, p = dragging ? drag : position;
            float h = dragging ? half * 1.6f : half;
            r.set(l, cy - h, rt, cy + h);
            c.drawRoundRect(r, h, h, track);
            r.set(l, cy - h, l + w * buffered, cy + h);
            c.drawRoundRect(r, h, h, loaded);
            r.set(l, cy - h, l + w * p, cy + h);
            c.drawRoundRect(r, h, h, played);
            if (isEnabled()) c.drawCircle(l + w * p, cy, dragging ? knob * 1.4f : knob, thumb);
        }
    }
}
