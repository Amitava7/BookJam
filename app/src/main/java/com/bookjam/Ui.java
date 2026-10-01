package com.bookjam;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * Colours, a few view factories, and window-inset handling. No XML layouts.
 *
 * The look follows One UI: big rounded corners, a quiet palette that follows
 * the phone's dark mode, and the controls you touch kept low on the screen.
 */
final class Ui {

    static boolean dark;
    static int BG;          // window
    static int CARD;        // grouped lists and sheets
    static int BUTTON;      // reads as a button against BG and CARD
    static int TEXT;
    static int DIM;
    static int FAINT;
    static int LINE;
    static int ACCENT;
    static int ON_ACCENT;
    static int RED;

    static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private Ui() {
    }

    /** Picks the palette for the current dark-mode setting. Call in every onCreate. */
    static void init(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            BG = 0xFF000000;
            CARD = 0xFF171717;
            BUTTON = 0xFF262626;
            TEXT = 0xFFF4F4F4;
            DIM = 0xFF9E9E9E;
            FAINT = 0xFF4C4C4C;
            LINE = 0xFF262626;
            ACCENT = 0xFF6EA8FF;
            ON_ACCENT = 0xFF00142E;
            RED = 0xFFFF7A70;
        } else {
            BG = 0xFFF3F3F6;
            CARD = 0xFFFFFFFF;
            BUTTON = 0xFFE8E8EC;
            TEXT = 0xFF111114;
            DIM = 0xFF6B6B72;
            FAINT = 0xFFC2C2C8;
            LINE = 0xFFECECEF;
            ACCENT = 0xFF2C67E8;
            ON_ACCENT = 0xFFFFFFFF;
            RED = 0xFFD93A2F;
        }
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static Typeface weight(int w) {
        return Typeface.create(Typeface.DEFAULT, w, false);
    }

    static TextView text(Context c, String s, float sizeSp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(weight(weight));
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView line(Context c, String s, float sizeSp, int color, int weight) {
        TextView t = text(c, s, sizeSp, color, weight);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /**
     * Sets text only when it differs. The player refreshes four times a
     * second; rewriting identical text would relayout and keep firing
     * accessibility events for nothing.
     */
    static void setText(TextView t, CharSequence s) {
        if (!TextUtils.equals(t.getText(), s)) t.setText(s);
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** A background that ripples when pressed. Pass a transparent colour for none. */
    static Drawable ripple(int color, float radiusPx) {
        Drawable content = Color.alpha(color) == 0 ? null : round(color, radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(TEXT, 0x22)), content,
                round(0xFFFFFFFF, radiusPx));
    }

    static Drawable rippleOval(int color) {
        Drawable content = Color.alpha(color) == 0 ? null : oval(color);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(TEXT, 0x22)), content,
                oval(0xFFFFFFFF));
    }

    /** A round icon button. Sizes in dp. */
    static ImageButton iconButton(Context c, int icon, int sizeDp, int iconDp, int bg, int fg,
                                  String description) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(fg));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(c, (sizeDp - iconDp) / 2f);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(rippleOval(bg));
        b.setContentDescription(description);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        squish(b);
        return b;
    }

    /** A rounded chip with an optional icon in front of its label. */
    static LinearLayout chip(Context c, int icon, String label, int bg, int fg) {
        LinearLayout l = row(c);
        l.setGravity(Gravity.CENTER);
        l.setBackground(ripple(bg, dp(c, 999)));
        l.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        l.setMinimumHeight(dp(c, 48));
        if (icon != 0) {
            ImageView i = new ImageView(c);
            i.setImageResource(icon);
            i.setImageTintList(ColorStateList.valueOf(fg));
            LinearLayout.LayoutParams p = lp(dp(c, 18), dp(c, 18));
            p.rightMargin = dp(c, 8);
            l.addView(i, p);
        }
        TextView t = line(c, label, 14f, fg, 600);
        l.addView(t);
        l.setClickable(true);
        squish(l);
        return l;
    }

    /** The label of a chip made by {@link #chip}. */
    static TextView chipText(LinearLayout chip) {
        return (TextView) chip.getChildAt(chip.getChildCount() - 1);
    }

    /** A full-width pill button, the main action of a sheet or an empty screen. */
    static TextView bigButton(Context c, String label, int bg, int fg) {
        TextView t = text(c, label, 16f, fg, 600);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ripple(bg, dp(c, 999)));
        t.setMinHeight(dp(c, 52));
        t.setPadding(dp(c, 24), 0, dp(c, 24), 0);
        t.setClickable(true);
        squish(t);
        return t;
    }

    /** Shrinks a view a little while it is held down, the One UI press feel. */
    static void squish(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Clips a view, and whatever it draws, to rounded corners. */
    static void clipRound(View v, final float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static View gap(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(lp(MATCH, dp(c, dp)));
        return v;
    }

    static View divider(Context c, int insetLeftDp) {
        View v = new View(c);
        v.setBackground(new ColorDrawable(LINE));
        LinearLayout.LayoutParams p = lp(MATCH, Math.max(1, dp(c, 0.7f)));
        p.leftMargin = dp(c, insetLeftDp);
        v.setLayoutParams(p);
        return v;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Mixes two colours; t = 0 is all a, t = 1 is all b. */
    static int blend(int a, int b, float t) {
        float s = 1f - t;
        return Color.rgb(
                Math.round(Color.red(a) * s + Color.red(b) * t),
                Math.round(Color.green(a) * s + Color.green(b) * t),
                Math.round(Color.blue(a) * s + Color.blue(b) * t));
    }

    /**
     * Apps targeting Android 15 draw behind the system bars. Hands the space the
     * bars and the display cutout cover to the callback whenever it changes.
     */
    static void onInsets(View root, final Consumer<Insets> then) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            then.accept(insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()));
            return insets;
        });
        root.requestApplyInsets();
    }

    /** Pads a view by the system bars on top of the padding it already has. */
    static void fitSystemWindows(final View root) {
        final int l = root.getPaddingLeft(), t = root.getPaddingTop();
        final int r = root.getPaddingRight(), b = root.getPaddingBottom();
        onInsets(root, bars -> root.setPadding(l + bars.left, t + bars.top, r + bars.right,
                b + bars.bottom));
    }
}
