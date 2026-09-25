package com.terfrecipes.data;

/** Small text formatting helpers shared by the parser and the JEI categories. */
public final class Format {
    private Format() {
    }

    /** 20 ticks = 1 s. */
    public static String ticks(long ticks) {
        double seconds = ticks / 20.0;
        if (seconds < 60) return trim(seconds) + "s";
        if (seconds < 3600) return trim(seconds / 60.0) + "min";
        if (seconds < 86400) return trim(seconds / 3600.0) + "h";
        return trim(seconds / 86400.0) + "d";
    }

    public static String number(double v) {
        if (Math.abs(v) >= 1_000_000_000) return trim(v / 1_000_000_000.0) + "G";
        if (Math.abs(v) >= 1_000_000) return trim(v / 1_000_000.0) + "M";
        if (Math.abs(v) >= 10_000) return trim(v / 1_000.0) + "k";
        return trim(v);
    }

    public static String trim(double v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        String s = String.format(java.util.Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** "terf.heavy_water" -> "Heavy Water", "water" -> "Water". */
    public static String fluidName(String id) {
        String path = id;
        int dot = path.indexOf('.');
        if (dot >= 0 && dot < path.length() - 1) path = path.substring(dot + 1);
        return MachineDefs.prettify(path);
    }
}
