package cn.tohsaka.zstdnet.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Aggregate proxy counters. Up is client-to-proxy; down is proxy-to-client. */
public record ProxyHudSnapshot(int listenPort, long rawUpBytes, long rawDownBytes, long wireUpBytes, long wireDownBytes,
                               long rawUpRate, long rawDownRate, long wireUpRate, long wireDownRate,
                               double ratioPercent, int connections) {
    public static ProxyHudSnapshot parse(String json) {
        if (json == null || json.length() > 8192) throw new IllegalArgumentException("HUD payload is too large");
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        if (!"VELOCITY".equals(object.get("mode").getAsString())) throw new IllegalArgumentException("unknown proxy mode");
        if (object.has("listenHost") && object.get("listenHost").getAsString().length() > 512)
            throw new IllegalArgumentException("invalid proxy host");
        long port = number(object, "listenPort");
        long connections = number(object, "connections");
        double ratio = object.get("ratioPercent").getAsDouble();
        if (port > 65535 || connections > 1_000_000 || !Double.isFinite(ratio) || ratio < 0)
            throw new IllegalArgumentException("invalid proxy HUD metadata");
        return new ProxyHudSnapshot((int) port, number(object, "rawUpBytes"), number(object, "rawDownBytes"),
            number(object, "wireUpBytes"), number(object, "wireDownBytes"), number(object, "rawUpRate"),
            number(object, "rawDownRate"), number(object, "wireUpRate"), number(object, "wireDownRate"),
            ratio, (int) connections);
    }

    private static long number(JsonObject object, String field) {
        long value = object.get(field).getAsBigDecimal().longValueExact();
        if (value < 0) throw new IllegalArgumentException("negative HUD counter: " + field);
        return value;
    }
}
