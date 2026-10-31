package cn.tohsaka.zstdnet.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProxyHudSnapshotTest {
    @Test
    void proxyDirectionsTotalsAndOverheadSurviveParsingWithoutBeingSwappedOrClamped() {
        var parsed = ProxyHudSnapshot.parse(valid().toString());
        assertEquals(25565, parsed.listenPort());
        assertEquals(3, parsed.connections());
        assertEquals(1000, parsed.rawUpBytes());
        assertEquals(2000, parsed.rawDownBytes());
        assertEquals(1500, parsed.wireUpBytes());
        assertEquals(3000, parsed.wireDownBytes());
        assertEquals(10, parsed.rawUpRate());
        assertEquals(20, parsed.rawDownRate());
        assertEquals(15, parsed.wireUpRate());
        assertEquals(30, parsed.wireDownRate());
        assertEquals(150, parsed.ratioPercent());
    }

    @Test
    void remoteMetadataIsBoundedBeforeItCanReachTheHud() {
        for (String field : new String[] {"listenPort", "connections", "rawUpBytes", "wireDownRate"}) {
            JsonObject bad = valid();
            bad.addProperty(field, -1);
            assertThrows(RuntimeException.class, () -> ProxyHudSnapshot.parse(bad.toString()));
        }
        JsonObject largePort = valid();
        largePort.addProperty("listenPort", 65536);
        assertThrows(RuntimeException.class, () -> ProxyHudSnapshot.parse(largePort.toString()));
        JsonObject tooManyConnections = valid();
        tooManyConnections.addProperty("connections", 1_000_001);
        assertThrows(RuntimeException.class, () -> ProxyHudSnapshot.parse(tooManyConnections.toString()));
        JsonObject infiniteRatio = valid();
        infiniteRatio.addProperty("ratioPercent", "NaN");
        assertThrows(RuntimeException.class, () -> ProxyHudSnapshot.parse(infiniteRatio.toString()));
        assertThrows(RuntimeException.class, () -> ProxyHudSnapshot.parse(" ".repeat(8193)));
    }

    @Test
    void malformedCountersUnknownModesAndMissingFieldsCannotBecomePlausibleStatistics() {
        JsonObject fractional = valid();
        fractional.addProperty("rawUpBytes", 1.5);
        assertThrows(RuntimeException.class, () -> ProxyHudSnapshot.parse(fractional.toString()));
        JsonObject overflow = valid();
        overflow.addProperty("rawDownBytes", new BigInteger("9223372036854775808"));
        assertThrows(RuntimeException.class, () -> ProxyHudSnapshot.parse(overflow.toString()));
        JsonObject missing = valid();
        missing.remove("wireUpRate");
        assertThrows(RuntimeException.class, () -> ProxyHudSnapshot.parse(missing.toString()));
        JsonObject unknownMode = valid();
        unknownMode.addProperty("mode", "BACKEND");
        assertThrows(RuntimeException.class, () -> ProxyHudSnapshot.parse(unknownMode.toString()));
        assertThrows(RuntimeException.class, () -> ProxyHudSnapshot.parse("[]"));
        assertThrows(RuntimeException.class, () -> ProxyHudSnapshot.parse(null));
    }

    private static JsonObject valid() {
        return JsonParser.parseString("""
            {"mode":"VELOCITY","listenHost":"0.0.0.0","listenPort":25565,"connections":3,
             "rawUpBytes":1000,"rawDownBytes":2000,"wireUpBytes":1500,"wireDownBytes":3000,
             "rawUpRate":10,"rawDownRate":20,"wireUpRate":15,"wireDownRate":30,"ratioPercent":150}
            """).getAsJsonObject();
    }
}
