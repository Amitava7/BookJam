package com.bookjam;

import java.time.Instant;
import java.time.ZoneId;

/**
 * How far to go back when you start listening again after a break, so you
 * hear the last few sentences before the story carries on. No Android
 * classes, so the JVM tests can reach it.
 *
 * Under an hour away: straight on. Away since another day (by the calendar,
 * so last night counts even if it was only a few hours ago): back a minute.
 * Otherwise five hours or more: back 30 seconds.
 */
final class Recap {

    static final long HOUR = 3_600_000;
    static final long SOON = HOUR;
    static final long LONG = 5 * HOUR;
    static final long SHORT_BACK = 30_000;
    static final long DAY_BACK = 60_000;

    private Recap() {
    }

    /** ms to go back, given when you last listened (0 for never) and now, both wall clock. */
    static long back(long lastPlayed, long now, ZoneId zone) {
        // Never played, or a clock that went backwards: nothing to recap.
        if (lastPlayed <= 0 || now - lastPlayed < SOON) return 0;
        if (!Instant.ofEpochMilli(lastPlayed).atZone(zone).toLocalDate()
                .equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())) return DAY_BACK;
        return now - lastPlayed >= LONG ? SHORT_BACK : 0;
    }
}
