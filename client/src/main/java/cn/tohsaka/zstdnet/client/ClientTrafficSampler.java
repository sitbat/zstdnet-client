package cn.tohsaka.zstdnet.client;

/** Wall-clock sampling, kept independent of Minecraft so connection resets and units are testable. */
public final class ClientTrafficSampler {
    public static final long INTERVAL_NANOS = 250_000_000L;
    private Object identity;
    private Counters previous;
    private long previousNanos;
    private Snapshot snapshot = Snapshot.EMPTY;

    public record Counters(long rxRaw, long rxWire, long txRaw, long txWire) {
        boolean precedes(Counters other) {
            return rxRaw < other.rxRaw || rxWire < other.rxWire || txRaw < other.txRaw || txWire < other.txWire;
        }

        double ratio() {
            double raw = (double) rxRaw + txRaw;
            return raw > 0 ? ((double) rxWire + txWire) / raw * 100.0 : Double.NaN;
        }
    }

    public record Snapshot(boolean sampled, double rxRawPerSecond, double rxWirePerSecond,
                           double txRawPerSecond, double txWirePerSecond, double wireToRawPercent) {
        public static final Snapshot EMPTY = new Snapshot(false, 0, 0, 0, 0, Double.NaN);
    }

    public Snapshot update(Object connectionIdentity, Counters totals, long nowNanos) {
        if (connectionIdentity == null || totals == null) {
            reset();
            return snapshot;
        }
        if (identity != connectionIdentity || previous == null || totals.precedes(previous)) {
            identity = connectionIdentity;
            previous = totals;
            previousNanos = nowNanos;
            snapshot = new Snapshot(false, 0, 0, 0, 0, totals.ratio());
            return snapshot;
        }
        long elapsed = nowNanos - previousNanos;
        if (elapsed < 0) {
            previous = totals;
            previousNanos = nowNanos;
            snapshot = new Snapshot(false, 0, 0, 0, 0, totals.ratio());
            return snapshot;
        }
        if (elapsed < INTERVAL_NANOS) {
            return snapshot;
        }
        double seconds = elapsed / 1_000_000_000.0;
        snapshot = new Snapshot(true,
            (totals.rxRaw - previous.rxRaw) / seconds,
            (totals.rxWire - previous.rxWire) / seconds,
            (totals.txRaw - previous.txRaw) / seconds,
            (totals.txWire - previous.txWire) / seconds,
            totals.ratio());
        previous = totals;
        previousNanos = nowNanos;
        return snapshot;
    }

    public void reset() {
        identity = null;
        previous = null;
        previousNanos = 0;
        snapshot = Snapshot.EMPTY;
    }
}
