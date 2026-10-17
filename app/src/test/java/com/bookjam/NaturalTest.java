package com.bookjam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class NaturalTest {

    private static List<String> sorted(String... names) {
        List<String> l = new ArrayList<>(Arrays.asList(names));
        Collections.shuffle(l, new java.util.Random(7));
        l.sort(Natural.ORDER);
        return l;
    }

    @Test
    public void numbersCountUpInsteadOfSortingAsText() {
        assertEquals(Arrays.asList("1", "2", "3", "4", "5", "10", "11", "20", "100"),
                sorted("10", "2", "100", "1", "11", "3", "20", "4", "5"));
    }

    @Test
    public void numbersInsideNames() {
        assertEquals(Arrays.asList("Chapter 1", "Chapter 2", "Chapter 9", "Chapter 10", "Chapter 12"),
                sorted("Chapter 10", "Chapter 2", "Chapter 12", "Chapter 1", "Chapter 9"));
    }

    @Test
    public void leadingZerosAndCaseDoNotMatter() {
        assertEquals(Arrays.asList("001 intro", "2 The Start", "03 middle", "10 End"),
                sorted("10 End", "03 middle", "001 intro", "2 The Start"));
        assertTrue(Natural.ORDER.compare("Track 007", "track 7") != 0);
        assertTrue(Natural.ORDER.compare("Part 2", "part 10") < 0);
    }

    @Test
    public void subFoldersKeepTheirOrder() {
        assertEquals(Arrays.asList("CD1/01", "CD1/02", "CD1/10", "CD2/01", "CD10/01"),
                sorted("CD10/01", "CD1/10", "CD2/01", "CD1/02", "CD1/01"));
    }

    @Test
    public void shorterNameFirstWhenOneStartsTheOther() {
        assertEquals(Arrays.asList("Book", "Book 1", "Book 1 part 2"),
                sorted("Book 1 part 2", "Book", "Book 1"));
    }

    @Test
    public void hugeNumbersDoNotOverflow() {
        assertTrue(Natural.ORDER.compare("12345678901234567890", "99999999999999999999") < 0);
        assertTrue(Natural.ORDER.compare("123456789012345678901", "99999999999999999999") > 0);
    }

    @Test
    public void orderIsConsistent() {
        String[] names = {"a1", "a01", "A1", "a2", "a10", "b", "", "1", "01", "a", "a 1"};
        for (String x : names) {
            assertEquals(0, Natural.ORDER.compare(x, x));
            for (String y : names) {
                assertEquals(x + " vs " + y, Integer.signum(Natural.ORDER.compare(x, y)),
                        -Integer.signum(Natural.ORDER.compare(y, x)));
            }
        }
    }
}
