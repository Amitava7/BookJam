package com.bookjam;

import java.util.Locale;

/** Text for times and speeds. No Android classes, so the JVM tests can reach it. */
final class Fmt {

    private Fmt() {
    }

    /** A playback clock: 4:05, or 1:02:03 once there are hours. */
    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** A rough length: 7h 5m, 45m, 1m. Never says zero for something left. */
    static String span(long ms) {
        long m = Math.max(1, Math.round(Math.max(0, ms) / 60000.0));
        long h = m / 60;
        m %= 60;
        if (h == 0) return m + "m";
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    /** Time left on the sleep timer: whole minutes, rounded up, then seconds in the last one. */
    static String sleepLeft(long ms) {
        if (ms >= 60_000) return ((ms + 59_999) / 60_000) + " min";
        return ((Math.max(0, ms) + 999) / 1000) + " s";
    }

    /** 1.0×, 1.25×, 1.5×, 2.0× */
    static String speed(float s) {
        String t = String.format(Locale.US, "%.2f", s);
        if (t.endsWith("0")) t = t.substring(0, t.length() - 1);
        return t + "×";
    }

    /** Snaps a speed to the 0.05 steps the speed sheet offers, within 0.5 to 3. */
    static float snapSpeed(float s) {
        float v = Math.round(s * 20f) / 20f;
        return Math.max(0.5f, Math.min(3f, v));
    }

    /** A file name without its extension: "01 Intro.mp3" is "01 Intro". */
    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && name.length() - dot <= 5 ? name.substring(0, dot) : name;
    }

    /** How a file inside a sub-folder is shown: "CD1/03" becomes "CD1 · 03". */
    static String title(String relativeName) {
        return relativeName.replace("/", " · ");
    }
}
