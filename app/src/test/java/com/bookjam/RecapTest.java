package com.bookjam;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

public class RecapTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private static long at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute).atZone(ZONE).toInstant()
                .toEpochMilli();
    }

    private static long back(long last, long now) {
        return Recap.back(last, now, ZONE);
    }

    @Test
    public void neverPlayed() {
        assertEquals(0, back(0, at(17, 20, 0)));
    }

    @Test
    public void underAnHour() {
        assertEquals(0, back(at(17, 20, 0), at(17, 20, 0)));
        assertEquals(0, back(at(17, 20, 0), at(17, 20, 59)));
        // Across midnight still counts as straight on.
        assertEquals(0, back(at(16, 23, 40), at(17, 0, 20)));
    }

    @Test
    public void sameDayBetweenOneAndFiveHours() {
        assertEquals(0, back(at(17, 9, 0), at(17, 10, 0)));
        assertEquals(0, back(at(17, 9, 0), at(17, 13, 59)));
    }

    @Test
    public void sameDayFiveHoursOrMore() {
        assertEquals(30_000, back(at(17, 9, 0), at(17, 14, 0)));
        assertEquals(30_000, back(at(17, 0, 5), at(17, 23, 55)));
    }

    @Test
    public void anotherDay() {
        // Last evening to this evening.
        assertEquals(60_000, back(at(16, 20, 0), at(17, 20, 0)));
        // Late last night to early this morning: the date changed, not 24 hours.
        assertEquals(60_000, back(at(16, 23, 0), at(17, 1, 0)));
        assertEquals(60_000, back(at(16, 22, 0), at(17, 7, 30)));
        // Weeks away is still a minute.
        assertEquals(60_000, back(at(1, 20, 0), at(17, 20, 0)));
    }

    @Test
    public void clockWentBackwards() {
        assertEquals(0, back(at(17, 20, 0), at(17, 18, 0)));
    }
}
