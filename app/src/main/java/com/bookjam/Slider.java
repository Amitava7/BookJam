package com.bookjam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * A thick, rounded One UI style slider. Grab it anywhere along its length; it
 * thickens while held. Values run from 0 to 1 and the owner maps them.
 */
final class Slider extends View {

    interface Listener {
        /** While the finger moves. */
        void onDrag(float value);

        /** When the finger lifts: the value to act on. */
        void onRelease(float value);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float thin, thick, knob;
    private float value;
    private boolean dragging;
    private Listener listener;
    int trackColor = Ui.withAlpha(Ui.TEXT, 0x2E);
    int fillColor = Ui.TEXT;

    Slider(Context c) {
        super(c);
        thin = Ui.dp(c, 6);
        thick = Ui.dp(c, 12);
        knob = Ui.dp(c, 8);
        setMinimumHeight(Ui.dp(c, 44));
        setFocusable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Ignored while the user is dragging, so the playhead does not fight the finger. */
    void setValue(float v) {
        if (dragging) return;
        v = Math.max(0f, Math.min(1f, v));
        if (v != value) {
            value = v;
            invalidate();
        }
    }

    float value() {
        return value;
    }

    boolean dragging() {
        return dragging;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w),
                resolveSize(getSuggestedMinimumHeight(), h));
    }

    private float left() {
        return getPaddingLeft() + knob;
    }

    private float right() {
        return getWidth() - getPaddingRight() - knob;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float l = left(), r = right(), cy = getHeight() / 2f;
        float h = dragging ? thick : thin;
        float x = l + (r - l) * value;
        paint.setColor(trackColor);
        canvas.drawRoundRect(l - h / 2, cy - h / 2, r + h / 2, cy + h / 2, h / 2, h / 2, paint);
        paint.setColor(fillColor);
        canvas.drawRoundRect(l - h / 2, cy - h / 2, x + h / 2, cy + h / 2, h / 2, h / 2, paint);
        canvas.drawCircle(x, cy, dragging ? knob * 1.3f : knob, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                moveTo(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                moveTo(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
                moveTo(e.getX());
                dragging = false;
                invalidate();
                if (listener != null) listener.onRelease(value);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void moveTo(float x) {
        float l = left(), r = right();
        value = r > l ? Math.max(0f, Math.min(1f, (x - l) / (r - l))) : 0f;
        invalidate();
        if (listener != null) listener.onDrag(value);
    }
}
