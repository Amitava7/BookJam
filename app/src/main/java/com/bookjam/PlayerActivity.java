package com.bookjam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The player. Cover on top, controls in the bottom half where a thumb
 * reaches: the title, the slider for the current file, back 10 / play /
 * forward 10, and speed, sleep timer and chapters underneath.
 *
 * Each tap on back or forward moves 10 seconds, so a double tap is 20 and a
 * triple tap 30; the running total flashes over the cover.
 */
public final class PlayerActivity extends Activity implements Player.Listener {

    static final String EXTRA_BOOK = "book";

    private static final long TAP_WINDOW = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Player player;

    private FrameLayout root;
    private ImageView art;
    private TextView title, bookName, where, elapsed, remaining, flash, undo;
    private Slider slider;
    private ImageButton play;
    private LinearLayout speedChip, sleepChip, chaptersChip;
    private long boundBook = -1;

    private long lastTap;
    private int taps, tapDirection;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 250);
        }
    };

    private final Runnable hideFlash = () -> flash.animate().alpha(0f).setDuration(250).start();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        player = Player.get(this);
        if (!loadFrom(getIntent())) {
            finish();
            return;
        }
        build();
        player.addListener(this);
        bind();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadFrom(intent);
        bind();
    }

    /** Loads the book the intent names, or whatever was playing last. */
    private boolean loadFrom(Intent intent) {
        long id = intent == null ? -1 : intent.getLongExtra(EXTRA_BOOK, -1);
        if (id >= 0) {
            player.open(id, intent.getBooleanExtra("play", false));
        } else if (player.book == null) {
            Library.Book last = Library.get(this).last();
            if (last != null) player.open(last.id, false);
        }
        return player.book != null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(ticker);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(ticker);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (player != null) player.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        back();
    }

    private void back() {
        if (isTaskRoot()) startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    public void onPlayerChanged() {
        bind();
    }

    @Override
    public void onPlayerClosed() {
        finishAndRemoveTask();
    }

    // ---- layout -------------------------------------------------------------

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        boolean wide = getResources().getDisplayMetrics().widthPixels
                > getResources().getDisplayMetrics().heightPixels;
        LinearLayout page = wide ? Ui.row(this) : Ui.column(this);
        int side = Ui.dp(this, 24);
        page.setPadding(side, 0, side, Ui.dp(this, 12));

        // The cover, with the multi-tap total and the undo pill over it.
        FrameLayout artBox = new FrameLayout(this);
        art = new ImageView(this) {
            @Override
            protected void onMeasure(int w, int h) {
                int s = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
                setMeasuredDimension(s, s);
            }
        };
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.clipRound(art, Ui.dp(this, 28));
        art.setElevation(Ui.dp(this, 10));
        art.setContentDescription("Cover");
        artBox.addView(art, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH, Gravity.CENTER));

        flash = Ui.text(this, "", 30f, 0xFFFFFFFF, 700);
        flash.setBackground(Ui.round(0x99000000, Ui.dp(this, 999)));
        flash.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));
        flash.setAlpha(0f);
        flash.setElevation(Ui.dp(this, 12));
        artBox.addView(flash, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));

        undo = Ui.text(this, "", 14f, Ui.BG, 600);
        undo.setSingleLine(true);
        undo.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        undo.setBackground(Ui.ripple(Ui.TEXT, Ui.dp(this, 999)));
        undo.setPadding(Ui.dp(this, 18), Ui.dp(this, 11), Ui.dp(this, 18), Ui.dp(this, 11));
        undo.setElevation(Ui.dp(this, 14));
        undo.setVisibility(View.GONE);
        undo.setOnClickListener(v -> player.undo());
        FrameLayout.LayoutParams ul = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ul.bottomMargin = Ui.dp(this, 16);
        ul.leftMargin = ul.rightMargin = Ui.dp(this, 16);
        artBox.addView(undo, ul);

        LinearLayout controls = Ui.column(this);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);

        title = Ui.text(this, "", 23f, Ui.TEXT, 700);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        controls.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP));
        bookName = Ui.line(this, "", 16f, Ui.DIM, 500);
        bookName.setPadding(0, Ui.dp(this, 6), 0, 0);
        controls.addView(bookName, Ui.lp(Ui.MATCH, Ui.WRAP));
        where = Ui.line(this, "", 13f, Ui.DIM, 400);
        where.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        controls.addView(where, Ui.lp(Ui.MATCH, Ui.WRAP));

        slider = new Slider(this);
        slider.setContentDescription("Position in this file");
        slider.setListener(new Slider.Listener() {
            @Override
            public void onDrag(float v) {
                long d = player.duration();
                elapsed.setText(Fmt.clock((long) (v * d)));
                remaining.setText("−" + Fmt.clock(d - (long) (v * d)));
            }

            @Override
            public void onRelease(float v) {
                player.scrubTo((long) (v * player.duration()));
            }
        });
        controls.addView(slider, Ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout times = Ui.row(this);
        int knob = Ui.dp(this, 4);
        times.setPadding(knob, 0, knob, 0);
        elapsed = Ui.text(this, "0:00", 13f, Ui.DIM, 500);
        elapsed.setFontFeatureSettings("tnum");
        remaining = Ui.text(this, "", 13f, Ui.DIM, 500);
        remaining.setFontFeatureSettings("tnum");
        remaining.setGravity(Gravity.END);
        times.addView(elapsed, Ui.lp(0, Ui.WRAP, 1f));
        times.addView(remaining, Ui.lp(0, Ui.WRAP, 1f));
        controls.addView(times, Ui.lp(Ui.MATCH, Ui.WRAP));

        // The three big buttons.
        LinearLayout buttons = Ui.row(this);
        buttons.setGravity(Gravity.CENTER);
        ImageButton rewind = Ui.iconButton(this, R.drawable.ic_replay_10, 80, 40,
                Ui.withAlpha(Ui.TEXT, 0x16), Ui.TEXT, "Back 10 seconds");
        skipOnTap(rewind, -1);
        play = Ui.iconButton(this, R.drawable.ic_play, 100, 46, Ui.TEXT, Ui.BG, "Play");
        play.setElevation(Ui.dp(this, 6));
        play.setOnClickListener(v -> {
            if (!player.isPlaying()) askForNotifications(this);
            player.toggle();
        });
        ImageButton forward = Ui.iconButton(this, R.drawable.ic_forward_10, 80, 40,
                Ui.withAlpha(Ui.TEXT, 0x16), Ui.TEXT, "Forward 10 seconds");
        skipOnTap(forward, 1);
        LinearLayout.LayoutParams gap = (LinearLayout.LayoutParams) play.getLayoutParams();
        gap.leftMargin = gap.rightMargin = Ui.dp(this, 26);
        buttons.addView(rewind);
        buttons.addView(play, gap);
        buttons.addView(forward);
        LinearLayout.LayoutParams bl = Ui.lp(Ui.MATCH, Ui.WRAP);
        bl.topMargin = Ui.dp(this, 14);
        bl.bottomMargin = Ui.dp(this, 22);
        controls.addView(buttons, bl);

        // Speed, sleep timer, chapters.
        LinearLayout chips = Ui.row(this);
        int chipBg = Ui.withAlpha(Ui.TEXT, 0x12);
        speedChip = Ui.chip(this, R.drawable.ic_speed, "1.0×", chipBg, Ui.TEXT);
        speedChip.setContentDescription("Playback speed");
        speedChip.setOnClickListener(v -> Sheets.speed(this, player));
        sleepChip = Ui.chip(this, R.drawable.ic_moon, "Sleep", chipBg, Ui.TEXT);
        sleepChip.setContentDescription("Sleep timer");
        sleepChip.setOnClickListener(v -> Sheets.sleep(this, player));
        chaptersChip = Ui.chip(this, R.drawable.ic_list, "1/1", chipBg, Ui.TEXT);
        chaptersChip.setContentDescription("Chapters");
        chaptersChip.setOnClickListener(v -> Sheets.chapters(this, player));
        for (LinearLayout c : new LinearLayout[]{speedChip, sleepChip, chaptersChip}) {
            c.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
            LinearLayout.LayoutParams cl = Ui.lp(0, Ui.dp(this, 50), 1f);
            cl.leftMargin = cl.rightMargin = Ui.dp(this, 4);
            chips.addView(c, cl);
        }
        controls.addView(chips, Ui.lp(Ui.MATCH, Ui.WRAP));

        if (wide) {
            page.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams al = Ui.lp(0, Ui.MATCH, 1f);
            al.topMargin = al.bottomMargin = Ui.dp(this, 16);
            al.rightMargin = Ui.dp(this, 28);
            page.addView(artBox, al);
            page.addView(controls, Ui.lp(0, Ui.WRAP, 1.2f));
        } else {
            LinearLayout.LayoutParams al = Ui.lp(Ui.MATCH, 0, 1f);
            al.topMargin = Ui.dp(this, 8);
            al.bottomMargin = Ui.dp(this, 26);
            page.addView(topBar(), Ui.lp(Ui.MATCH, Ui.WRAP));
            page.addView(artBox, al);
            page.addView(controls, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        root.addView(page, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        setContentView(root);
        Ui.fitSystemWindows(root);
    }

    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setMinimumHeight(Ui.dp(this, 56));
        ImageButton backButton = Ui.iconButton(this, R.drawable.ic_back, 48, 24, 0, Ui.TEXT, "Library");
        backButton.setOnClickListener(v -> back());
        LinearLayout.LayoutParams bl = (LinearLayout.LayoutParams) backButton.getLayoutParams();
        bl.leftMargin = -Ui.dp(this, 12);
        bar.addView(backButton, bl);
        TextView label = Ui.line(this, "Now playing", 15f, Ui.DIM, 600);
        label.setGravity(Gravity.CENTER);
        bar.addView(label, Ui.lp(0, Ui.WRAP, 1f));
        View balance = new View(this);
        bar.addView(balance, Ui.lp(Ui.dp(this, 36), 1));
        return bar;
    }

    // ---- state --------------------------------------------------------------

    private void bind() {
        if (title == null) return;
        Player p = player;
        if (p.book == null) {
            finish();
            return;
        }
        if (p.book.id != boundBook) {
            boundBook = p.book.id;
            Covers.into(art, p.book, 1024, this::tint);
        }
        title.setText(p.tracks.isEmpty() ? p.book.name : p.title());
        bookName.setText(p.book.name);
        boolean playing = p.isPlaying();
        play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setContentDescription(playing ? "Pause" : "Play");
        Ui.chipText(speedChip).setText(Fmt.speed(p.book.speed));
        Ui.chipText(chaptersChip).setText(p.tracks.isEmpty() ? "0"
                : (p.index + 1) + "/" + p.tracks.size());
        update();
    }

    /** Position, timer and undo: refreshed four times a second while visible. */
    private void update() {
        Player p = player;
        if (p.book == null || title == null) return;
        long pos = p.position(), dur = p.duration();
        if (!slider.dragging()) {
            slider.setValue(dur > 0 ? pos / (float) dur : 0f);
            Ui.setText(elapsed, Fmt.clock(pos));
            Ui.setText(remaining, dur > 0 ? "\u2212" + Fmt.clock(dur - pos) : "");
        }
        if (p.error != null) {
            Ui.setText(where, p.error);
            where.setTextColor(Ui.RED);
        } else {
            long left = p.bookLeft();
            String count = p.tracks.isEmpty() ? "" : "File " + (p.index + 1) + " of " + p.tracks.size();
            Ui.setText(where, left >= 0 ? count + " \u00B7 " + Fmt.span(left) + " left in the book" : count);
            where.setTextColor(Ui.DIM);
        }
        long sleep = p.sleepLeft();
        Ui.setText(Ui.chipText(sleepChip), sleep > 0 ? Fmt.sleepLeft(sleep)
                : p.sleepsAtEndOfTrack() ? "Chapter end" : "Sleep");
        if (p.canUndo()) {
            Ui.setText(undo, p.undoLabel());
            undo.setVisibility(View.VISIBLE);
        } else {
            undo.setVisibility(View.GONE);
        }
    }

    /** Colours the background from the cover, fading into the page colour. */
    private void tint(int color) {
        int top = Ui.dark ? Ui.blend(color, 0xFF000000, 0.55f) : Ui.blend(color, 0xFFFFFFFF, 0.72f);
        int mid = Ui.dark ? Ui.blend(color, 0xFF000000, 0.82f) : Ui.blend(color, Ui.BG, 0.9f);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, mid, Ui.BG});
        root.setBackground(g);
    }

    /**
     * Counts every lift of the finger as a tap. A plain click listener drops
     * a tap now and then when they come quickly, because the pressed state
     * from the last tap is cleared just as the next one lands; for a button
     * that is meant to be tapped three times in a row, that loses 10 s.
     * TalkBack and keyboards still go through the click listener.
     */
    private void skipOnTap(final View b, final int direction) {
        b.setOnClickListener(v -> skip(direction));
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.drawableHotspotChanged(e.getX(), e.getY());
                    v.setPressed(true);
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                    boolean inside = e.getX() >= 0 && e.getY() >= 0
                            && e.getX() <= v.getWidth() && e.getY() <= v.getHeight();
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && inside) skip(direction);
                    break;
                default:
                    break;
            }
            return true;
        });
    }

    /** One tap is 10 seconds; each further tap within a moment adds another 10. */
    private void skip(int direction) {
        long now = SystemClock.uptimeMillis();
        if (direction == tapDirection && now - lastTap < TAP_WINDOW) taps++;
        else taps = 1;
        tapDirection = direction;
        lastTap = now;
        player.seekBy(direction * Player.SKIP);
        flash.setText((direction < 0 ? "−" : "+") + (taps * Player.SKIP / 1000) + " s");
        flash.animate().cancel();
        flash.setAlpha(1f);
        handler.removeCallbacks(hideFlash);
        handler.postDelayed(hideFlash, TAP_WINDOW);
        update();
    }

    /**
     * The lock-screen controls live in a notification, which Android asks
     * about. Ask once, on the first press of play, not at launch.
     */
    static void askForNotifications(Activity a) {
        if (a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = a.getSharedPreferences("ui", MODE_PRIVATE);
        if (prefs.getBoolean("asked_notifications", false)) return;
        prefs.edit().putBoolean("asked_notifications", true).apply();
        a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }
}
