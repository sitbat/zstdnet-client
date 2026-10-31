package cn.tohsaka.zstdnet.client;

import java.util.Locale;

public final class ClientHudFormat {
    private ClientHudFormat() { }

    public static String rate(double bytesPerSecond) {
        if (!Double.isFinite(bytesPerSecond) || bytesPerSecond < 0) return "—";
        if (bytesPerSecond >= 1024 * 1024) return String.format(Locale.ROOT, "%.2f MiB/s", bytesPerSecond / (1024 * 1024));
        if (bytesPerSecond >= 1024) return String.format(Locale.ROOT, "%.1f KiB/s", bytesPerSecond / 1024);
        return String.format(Locale.ROOT, "%.0f B/s", bytesPerSecond);
    }

    public static String percent(double ratio) {
        return Double.isFinite(ratio) && ratio >= 0 ? String.format(Locale.ROOT, "%.1f%%", ratio) : "—";
    }

    public static String size(double bytes) {
        if (!Double.isFinite(bytes) || bytes < 0) return "—";
        if (bytes < 1024) return String.format(Locale.ROOT, "%.0f B", bytes);
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = bytes / 1024;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }
}
