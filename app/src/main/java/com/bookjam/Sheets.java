package com.bookjam;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/** The floating One UI bottom sheets: speed, sleep timer, chapters, book options. */
final class Sheets {

    private Sheets() {
    }

    /** A sheet: the dialog, and the column to put things in under its title. */
    static final class Sheet {
        final Dialog dialog;
        final LinearLayout body;

        Sheet(Dialog dialog, LinearLayout body) {
            this.dialog = dialog;
            this.body = body;
        }

        void show() {
            dialog.show();
        }
    }

    static Sheet sheet(Activity a, String title, String subtitle) {
        Dialog d = new Dialog(a, R.style.Sheet);
        FrameLayout outer = new FrameLayout(a);
        int m = Ui.dp(a, 10);
        outer.setPadding(m, 0, m, m);
        LinearLayout card = Ui.column(a);
        card.setBackground(Ui.round(Ui.CARD, Ui.dp(a, 30)));
        int p = Ui.dp(a, 24);
        card.setPadding(p, Ui.dp(a, 22), p, Ui.dp(a, 18));
        TextView t = Ui.text(a, title, 21f, Ui.TEXT, 700);
        card.addView(t);
        if (subtitle != null) {
            TextView s = Ui.line(a, subtitle, 14f, Ui.DIM, 400);
            s.setPadding(0, Ui.dp(a, 6), 0, 0);
            card.addView(s);
        }
        LinearLayout body = Ui.column(a);
        body.setPadding(0, Ui.dp(a, 16), 0, 0);
        card.addView(body);
        outer.addView(card);
        // Clear of the gesture bar, should the window reach behind it.
        Ui.onInsets(outer, bars -> outer.setPadding(m + bars.left, 0, m + bars.right, m + bars.bottom));
        d.setContentView(outer);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        return new Sheet(d, body);
    }

    /** A plain full-width choice in a sheet. */
    static TextView choice(Context c, String label, int color) {
        TextView t = Ui.text(c, label, 17f, color, 500);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(Ui.dp(c, 54));
        t.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
        t.setBackground(Ui.ripple(0, Ui.dp(c, 16)));
        t.setClickable(true);
        return t;
    }

    // ---- speed --------------------------------------------------------------

    private static final float[] PRESETS = {0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    static void speed(Activity a, final Player p) {
        if (p.book == null) return;
        Sheet s = sheet(a, "Playback speed", "Voices keep their pitch at any speed.");
        final TextView value = Ui.text(a, Fmt.speed(p.book.speed), 46f, Ui.TEXT, 700);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 12));
        s.body.addView(value, Ui.lp(Ui.MATCH, Ui.WRAP));

        final Slider slider = new Slider(a);
        final LinearLayout presets = Ui.row(a);
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            float sp = p.book == null ? 1f : p.book.speed;
            value.setText(Fmt.speed(sp));
            slider.setValue((sp - Player.MIN_SPEED) / (Player.MAX_SPEED - Player.MIN_SPEED));
            for (int i = 0; i < presets.getChildCount(); i++) {
                LinearLayout chip = (LinearLayout) presets.getChildAt(i);
                boolean on = Math.abs(PRESETS[i] - sp) < 0.01f;
                chip.setBackground(Ui.ripple(on ? Ui.TEXT : Ui.BUTTON, Ui.dp(a, 999)));
                Ui.chipText(chip).setTextColor(on ? Ui.CARD : Ui.TEXT);
            }
        };

        LinearLayout row = Ui.row(a);
        View minus = Ui.bigButton(a, "−", Ui.BUTTON, Ui.TEXT);
        minus.setPadding(0, 0, 0, 0);
        minus.setContentDescription("Slower");
        minus.setOnClickListener(v -> {
            p.setSpeed(p.book.speed - 0.05f);
            refresh[0].run();
        });
        View plus = Ui.bigButton(a, "+", Ui.BUTTON, Ui.TEXT);
        plus.setPadding(0, 0, 0, 0);
        plus.setContentDescription("Faster");
        plus.setOnClickListener(v -> {
            p.setSpeed(p.book.speed + 0.05f);
            refresh[0].run();
        });
        row.addView(minus, Ui.lp(Ui.dp(a, 52), Ui.dp(a, 52)));
        slider.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 8), 0);
        row.addView(slider, Ui.lp(0, Ui.dp(a, 52), 1f));
        row.addView(plus, Ui.lp(Ui.dp(a, 52), Ui.dp(a, 52)));
        slider.setListener(new Slider.Listener() {
            @Override
            public void onDrag(float v) {
                float sp = Fmt.snapSpeed(Player.MIN_SPEED + v * (Player.MAX_SPEED - Player.MIN_SPEED));
                value.setText(Fmt.speed(sp));
                p.setSpeed(sp);
            }

            @Override
            public void onRelease(float v) {
                refresh[0].run();
            }
        });
        s.body.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP));
        s.body.addView(Ui.gap(a, 16));

        for (final float f : PRESETS) {
            LinearLayout chip = Ui.chip(a, 0, Fmt.speed(f), Ui.BUTTON, Ui.TEXT);
            chip.setPadding(0, 0, 0, 0);
            chip.setOnClickListener(v -> {
                p.setSpeed(f);
                refresh[0].run();
            });
            LinearLayout.LayoutParams lp = Ui.lp(0, Ui.dp(a, 44), 1f);
            lp.leftMargin = lp.rightMargin = Ui.dp(a, 3);
            presets.addView(chip, lp);
        }
        s.body.addView(presets, Ui.lp(Ui.MATCH, Ui.WRAP));
        refresh[0].run();
        s.show();
    }

    // ---- sleep timer --------------------------------------------------------

    private static final int[] MINUTES = {5, 10, 15, 20, 30, 45, 60, 90};

    static void sleep(final Activity a, final Player p) {
        final Sheet s = sheet(a, "Sleep timer",
                "Fades out, stops, and closes BookJam. Your place is saved.");
        final TextView status = Ui.text(a, "", 17f, Ui.ACCENT, 600);
        status.setPadding(0, 0, 0, Ui.dp(a, 14));
        s.body.addView(status);

        final LinearLayout running = Ui.row(a);
        LinearLayout more = Ui.chip(a, 0, "+5 min", Ui.BUTTON, Ui.TEXT);
        more.setOnClickListener(v -> p.sleepIn(p.sleepLeft() + 5 * 60_000L));
        LinearLayout off = Ui.chip(a, 0, "Turn off", Ui.BUTTON, Ui.RED);
        off.setOnClickListener(v -> {
            p.sleepOff();
            s.dialog.dismiss();
        });
        LinearLayout.LayoutParams half = Ui.lp(0, Ui.dp(a, 48), 1f);
        half.rightMargin = Ui.dp(a, 6);
        running.addView(more, half);
        LinearLayout.LayoutParams half2 = Ui.lp(0, Ui.dp(a, 48), 1f);
        half2.leftMargin = Ui.dp(a, 6);
        running.addView(off, half2);
        s.body.addView(running, Ui.lp(Ui.MATCH, Ui.WRAP));
        final View runningGap = Ui.gap(a, 16);
        s.body.addView(runningGap);

        for (int r = 0; r < 2; r++) {
            LinearLayout line = Ui.row(a);
            for (int k = 0; k < 4; k++) {
                final int min = MINUTES[r * 4 + k];
                LinearLayout chip = Ui.chip(a, 0, min + " min", Ui.BUTTON, Ui.TEXT);
                chip.setPadding(0, 0, 0, 0);
                chip.setOnClickListener(v -> {
                    p.sleepIn(min * 60_000L);
                    s.dialog.dismiss();
                });
                LinearLayout.LayoutParams lp = Ui.lp(0, Ui.dp(a, 48), 1f);
                lp.leftMargin = lp.rightMargin = Ui.dp(a, 3);
                lp.bottomMargin = Ui.dp(a, 6);
                line.addView(chip, lp);
            }
            s.body.addView(line, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        LinearLayout eoc = Ui.chip(a, R.drawable.ic_list, "End of this chapter", Ui.BUTTON, Ui.TEXT);
        eoc.setOnClickListener(v -> {
            p.sleepAtEndOfTrack();
            s.dialog.dismiss();
        });
        LinearLayout.LayoutParams eocLp = Ui.lp(Ui.MATCH, Ui.dp(a, 48));
        eocLp.leftMargin = eocLp.rightMargin = Ui.dp(a, 3);
        s.body.addView(eoc, eocLp);

        // Any number of minutes.
        s.body.addView(Ui.gap(a, 18));
        LinearLayout customHead = Ui.row(a);
        customHead.addView(Ui.text(a, "Custom", 15f, Ui.DIM, 600), Ui.lp(0, Ui.WRAP, 1f));
        final TextView customValue = Ui.text(a, "25 min", 15f, Ui.TEXT, 600);
        customHead.addView(customValue);
        s.body.addView(customHead, Ui.lp(Ui.MATCH, Ui.WRAP));
        final int[] chosen = {25};
        final Slider custom = new Slider(a);
        final TextView start = Ui.bigButton(a, "Start 25 min timer", Ui.TEXT, Ui.CARD);
        custom.setValue((25 - 1) / 179f);
        custom.setListener(new Slider.Listener() {
            @Override
            public void onDrag(float v) {
                chosen[0] = 1 + Math.round(v * 179f);
                customValue.setText(chosen[0] + " min");
                start.setText("Start " + chosen[0] + " min timer");
            }

            @Override
            public void onRelease(float v) {
                onDrag(v);
            }
        });
        s.body.addView(custom, Ui.lp(Ui.MATCH, Ui.WRAP));
        start.setOnClickListener(v -> {
            p.sleepIn(chosen[0] * 60_000L);
            s.dialog.dismiss();
        });
        LinearLayout.LayoutParams startLp = Ui.lp(Ui.MATCH, Ui.WRAP);
        startLp.topMargin = Ui.dp(a, 6);
        s.body.addView(start, startLp);

        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                long left = p.sleepLeft();
                boolean on = left > 0 || p.sleepsAtEndOfTrack();
                Ui.setText(status, left > 0 ? "Stops in " + Fmt.sleepLeft(left)
                        : p.sleepsAtEndOfTrack() ? "Stops at the end of this chapter" : "Off");
                status.setTextColor(on ? Ui.ACCENT : Ui.DIM);
                running.setVisibility(on ? View.VISIBLE : View.GONE);
                runningGap.setVisibility(on ? View.VISIBLE : View.GONE);
                more.setVisibility(left > 0 ? View.VISIBLE : View.GONE);
                h.postDelayed(this, 500);
            }
        };
        tick.run();
        s.dialog.setOnDismissListener(d -> h.removeCallbacks(tick));
        s.show();
    }

    // ---- chapters -----------------------------------------------------------

    static void chapters(final Activity a, final Player p) {
        if (p.book == null) return;
        int n = p.tracks.size();
        final Sheet s = sheet(a, "Chapters", p.book.name + " · " + n + (n == 1 ? " file" : " files"));
        final ListView list = new ListView(a);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setVerticalScrollBarEnabled(true);
        list.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return p.tracks.size();
            }

            @Override
            public Object getItem(int i) {
                return p.tracks.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout row = (LinearLayout) convert;
                if (row == null) {
                    row = Ui.row(a);
                    row.setMinimumHeight(Ui.dp(a, 58));
                    row.setPadding(Ui.dp(a, 6), 0, Ui.dp(a, 10), 0);
                    row.setBackground(Ui.ripple(0, Ui.dp(a, 16)));
                    TextView num = Ui.text(a, "", 14f, Ui.DIM, 500);
                    num.setGravity(Gravity.CENTER);
                    row.addView(num, Ui.lp(Ui.dp(a, 40), Ui.WRAP));
                    TextView name = Ui.text(a, "", 16f, Ui.TEXT, 400);
                    name.setMaxLines(2);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams nl = Ui.lp(0, Ui.WRAP, 1f);
                    nl.leftMargin = Ui.dp(a, 6);
                    nl.rightMargin = Ui.dp(a, 10);
                    row.addView(name, nl);
                    TextView time = Ui.text(a, "", 13f, Ui.DIM, 400);
                    row.addView(time);
                }
                Library.Track t = p.tracks.get(i);
                boolean current = i == p.index;
                TextView num = (TextView) row.getChildAt(0);
                TextView name = (TextView) row.getChildAt(1);
                TextView time = (TextView) row.getChildAt(2);
                num.setText(current ? "▶" : String.valueOf(i + 1));
                num.setTextColor(current ? Ui.ACCENT : Ui.DIM);
                name.setText(Fmt.title(t.name));
                name.setTextColor(current ? Ui.ACCENT : t.done ? Ui.DIM : Ui.TEXT);
                name.setTypeface(Ui.weight(current ? 700 : 400));
                String right;
                if (current) right = Fmt.clock(p.position()) + " / " + Fmt.clock(p.duration());
                else if (t.done) right = "✓ " + (t.dur > 0 ? Fmt.clock(t.dur) : "");
                else if (t.pos > 0 && t.dur > 0) right = Fmt.clock(t.pos) + " / " + Fmt.clock(t.dur);
                else right = t.dur > 0 ? Fmt.clock(t.dur) : "";
                time.setText(right.trim());
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, i, id) -> {
            p.jumpTo(i);
            s.dialog.dismiss();
        });
        int rows = Math.min(n, 7);
        int height = Math.min(Ui.dp(a, 58) * Math.max(rows, 1),
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.6f));
        s.body.addView(list, Ui.lp(Ui.MATCH, height));
        list.setSelection(Math.max(0, p.index - 2));
        s.show();
    }
}
