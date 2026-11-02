package dev.holmzi.eldervale.updater.core;

import java.io.IOException;
import java.math.BigInteger;
import java.util.regex.Pattern;

public final class Versions {
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

    private Versions() {}

    public static BigInteger[] parse(String value) throws IOException {
        var matcher = VERSION.matcher(value == null ? "" : value);
        if (!matcher.matches()) {
            throw new IOException("Version must use the format 1.0.0 or v1.0.0: " + value);
        }
        return new BigInteger[] {
            new BigInteger(matcher.group(1)), new BigInteger(matcher.group(2)),
            new BigInteger(matcher.group(3))
        };
    }

    public static int compare(String first, String second) throws IOException {
        var left = parse(first);
        var right = parse(second);
        for (int index = 0; index < 3; index++) {
            int difference = left[index].compareTo(right[index]);
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }
}
