package com.bookjam;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FmtTest {

    @Test
    public void clock() {
        assertEquals("0:00", Fmt.clock(0));
        assertEquals("0:00", Fmt.clock(-5000));
        assertEquals("0:09", Fmt.clock(9999));
        assertEquals("4:05", Fmt.clock(245_000));
        assertEquals("59:59", Fmt.clock(3_599_000));
        assertEquals("1:00:00", Fmt.clock(3_600_000));
        assertEquals("12:02:03", Fmt.clock((12 * 3600 + 2 * 60 + 3) * 1000L));
    }

    @Test
    public void span() {
        assertEquals("1m", Fmt.span(0));
        assertEquals("1m", Fmt.span(20_000));
        assertEquals("45m", Fmt.span(45 * 60_000));
        assertEquals("1h", Fmt.span(60 * 60_000));
        assertEquals("7h 5m", Fmt.span((7 * 60 + 5) * 60_000L));
    }

    @Test
    public void sleepLeft() {
        assertEquals("5 min", Fmt.sleepLeft(300_000));
        assertEquals("5 min", Fmt.sleepLeft(299_000));
        assertEquals("2 min", Fmt.sleepLeft(60_001));
        assertEquals("1 min", Fmt.sleepLeft(60_000));
        assertEquals("59 s", Fmt.sleepLeft(58_100));
        assertEquals("0 s", Fmt.sleepLeft(0));
    }

    @Test
    public void speed() {
        assertEquals("1.0×", Fmt.speed(1f));
        assertEquals("1.25×", Fmt.speed(1.25f));
        assertEquals("1.5×", Fmt.speed(1.5f));
        assertEquals("0.75×", Fmt.speed(0.75f));
        assertEquals("2.0×", Fmt.speed(2f));
        assertEquals(1.25f, Fmt.snapSpeed(1.26f), 0.0001f);
        assertEquals(0.5f, Fmt.snapSpeed(0.1f), 0.0001f);
        assertEquals(3f, Fmt.snapSpeed(9f), 0.0001f);
    }

    @Test
    public void names() {
        assertEquals("01 Intro", Fmt.stripExtension("01 Intro.mp3"));
        assertEquals("Book.Part.1", Fmt.stripExtension("Book.Part.1.m4b"));
        assertEquals(".hidden", Fmt.stripExtension(".hidden"));
        assertEquals("No extension here", Fmt.stripExtension("No extension here"));
        assertEquals("CD1 · 03", Fmt.title("CD1/03"));
    }
}
