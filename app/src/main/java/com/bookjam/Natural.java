package com.bookjam;

import java.util.Comparator;

/**
 * Orders file names the way a person reads them: "2" before "10", "Chapter 9"
 * before "Chapter 10", "01" level with "1", and capitals ignored. Plain string
 * order would play chapter 10 straight after chapter 1.
 */
final class Natural implements Comparator<String> {

    static final Natural ORDER = new Natural();

    private Natural() {
    }

    @Override
    public int compare(String a, String b) {
        int i = 0, j = 0;
        final int na = a.length(), nb = b.length();
        while (i < na && j < nb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (digit(ca) && digit(cb)) {
                // Compare the two runs of digits as numbers of any length:
                // skip leading zeros, then the longer run is the bigger number.
                int si = i, sj = j;
                while (si < na && a.charAt(si) == '0') si++;
                while (sj < nb && b.charAt(sj) == '0') sj++;
                int ei = si, ej = sj;
                while (ei < na && digit(a.charAt(ei))) ei++;
                while (ej < nb && digit(b.charAt(ej))) ej++;
                int la = ei - si, lb = ej - sj;
                if (la != lb) return la < lb ? -1 : 1;
                for (int k = 0; k < la; k++) {
                    int d = a.charAt(si + k) - b.charAt(sj + k);
                    if (d != 0) return d;
                }
                i = ei;
                j = ej;
            } else {
                int d = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (d != 0) return d;
                i++;
                j++;
            }
        }
        int d = (na - i) - (nb - j);
        if (d != 0) return d;
        // Equal as a reader sees them ("01" and "1"): fall back to plain order
        // so sorting is still stable and total.
        return a.compareTo(b);
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }
}
