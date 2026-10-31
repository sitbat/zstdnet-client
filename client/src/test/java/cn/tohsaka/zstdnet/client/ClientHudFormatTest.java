package cn.tohsaka.zstdnet.client;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class ClientHudFormatTest {
    @Test
    void ratesUseBinaryUnitsAtTheirExactThresholds() {
        assertEquals("0 B/s", ClientHudFormat.rate(0));
        assertEquals("1 B/s", ClientHudFormat.rate(1));
        assertEquals("1023 B/s", ClientHudFormat.rate(1023));
        assertEquals("1.0 KiB/s", ClientHudFormat.rate(1024));
        assertEquals("1.5 KiB/s", ClientHudFormat.rate(1536));
        assertEquals("1.00 MiB/s", ClientHudFormat.rate(1024 * 1024));
        assertEquals("1.50 MiB/s", ClientHudFormat.rate(1.5 * 1024 * 1024));
    }

    @Test
    void unknownOrInvalidRatesAndRatiosNeverRenderNaNInfinityOrNegativeBandwidth() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            assertEquals("—", ClientHudFormat.rate(value));
            assertEquals("—", ClientHudFormat.percent(value));
        }
    }

    @Test
    void ratiosShowActualOverheadInsteadOfClampingAtOneHundredPercent() {
        assertEquals("0.0%", ClientHudFormat.percent(0));
        assertEquals("35.3%", ClientHudFormat.percent(35.25));
        assertEquals("100.0%", ClientHudFormat.percent(100));
        assertEquals("150.0%", ClientHudFormat.percent(150));
    }

    @Test
    void cumulativeByteTotalsRemainReadableAboveTheRateDisplayRange() {
        assertEquals("1023 B", ClientHudFormat.size(1023));
        assertEquals("1.00 KiB", ClientHudFormat.size(1024));
        assertEquals("1.00 MiB", ClientHudFormat.size(1024.0 * 1024));
        assertEquals("1.00 GiB", ClientHudFormat.size(1024.0 * 1024 * 1024));
        assertEquals("1.00 TiB", ClientHudFormat.size(Math.pow(1024, 4)));
        assertEquals("1.00 PiB", ClientHudFormat.size(Math.pow(1024, 5)));
        assertEquals("—", ClientHudFormat.size(Double.NaN));
        assertEquals("—", ClientHudFormat.size(-1));
    }

    @Test
    void machineLocaleDoesNotChangeTheHudDecimalSeparator() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.5 KiB/s", ClientHudFormat.rate(1536));
            assertEquals("12.5%", ClientHudFormat.percent(12.5));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
