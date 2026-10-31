package cn.tohsaka.zstdnet.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientTrafficSamplerTest {
    private static final double EPSILON = 0.000001;
    private final ClientTrafficSampler sampler = new ClientTrafficSampler();
    private final Object connection = new Object();

    @Test
    void firstObservationEstablishesABaselineWithoutInventingABandwidthSpike() {
        var first = sampler.update(connection, counters(8000, 4000, 1200, 600), 8_000_000_000L);
        assertFalse(first.sampled());
        assertEquals(0, first.rxRawPerSecond());
        assertEquals(0, first.rxWirePerSecond());
        assertEquals(0, first.txRawPerSecond());
        assertEquals(0, first.txWirePerSecond());
        assertEquals(50, first.wireToRawPercent(), EPSILON);
    }

    @Test
    void ratesUseActualElapsedTimeAndKeepUploadAndDownloadDirectionsSeparate() {
        sampler.update(connection, counters(1000, 500, 100, 50), 1_000_000_000L);
        var quarterSecond = sampler.update(connection, counters(2000, 750, 300, 150), 1_250_000_000L);
        assertTrue(quarterSecond.sampled());
        assertEquals(4000, quarterSecond.rxRawPerSecond(), EPSILON);
        assertEquals(1000, quarterSecond.rxWirePerSecond(), EPSILON);
        assertEquals(800, quarterSecond.txRawPerSecond(), EPSILON);
        assertEquals(400, quarterSecond.txWirePerSecond(), EPSILON);
        assertEquals(900.0 / 2300 * 100, quarterSecond.wireToRawPercent(), EPSILON);

        var delayedFrame = sampler.update(connection, counters(5000, 2250, 900, 450), 2_000_000_000L);
        assertEquals(4000, delayedFrame.rxRawPerSecond(), EPSILON, "750 ms must not be treated as 250 ms");
        assertEquals(2000, delayedFrame.rxWirePerSecond(), EPSILON);
        assertEquals(800, delayedFrame.txRawPerSecond(), EPSILON);
        assertEquals(400, delayedFrame.txWirePerSecond(), EPSILON);
    }

    @Test
    void shortRenderFramesDoNotDiscardTrafficBeforeTheSamplingInterval() {
        var zero = counters(0, 0, 0, 0);
        var first = sampler.update(connection, zero, 0);
        assertSame(first, sampler.update(connection, counters(100, 50, 10, 5), 100_000_000L));
        assertSame(first, sampler.update(connection, counters(200, 100, 20, 10), 200_000_000L));
        var sample = sampler.update(connection, counters(250, 125, 25, 10), 250_000_000L);
        assertTrue(sample.sampled());
        assertEquals(1000, sample.rxRawPerSecond(), EPSILON);
        assertEquals(500, sample.rxWirePerSecond(), EPSILON);
        assertEquals(100, sample.txRawPerSecond(), EPSILON);
        assertEquals(40, sample.txWirePerSecond(), EPSILON);
    }

    @Test
    void unchangedCountersBecomeZeroRateAfterTheNextInterval() {
        sampler.update(connection, counters(0, 0, 0, 0), 0);
        var totals = counters(1000, 200, 100, 20);
        assertTrue(sampler.update(connection, totals, 250_000_000L).rxRawPerSecond() > 0);
        var idle = sampler.update(connection, totals, 500_000_000L);
        assertTrue(idle.sampled());
        assertEquals(0, idle.rxRawPerSecond());
        assertEquals(0, idle.rxWirePerSecond());
        assertEquals(0, idle.txRawPerSecond());
        assertEquals(0, idle.txWirePerSecond());
        assertEquals(20, idle.wireToRawPercent(), EPSILON);
    }

    @Test
    void aNewConnectionResetsTheBaselineEvenWhenItsCountersAreHigher() {
        sampler.update(connection, counters(0, 0, 0, 0), 0);
        sampler.update(connection, counters(100, 50, 10, 5), 250_000_000L);
        Object nextConnection = new Object();
        var initial = sampler.update(nextConnection, counters(100_000, 50_000, 1000, 500), 500_000_000L);
        assertFalse(initial.sampled());
        assertEquals(0, initial.rxRawPerSecond());
        var next = sampler.update(nextConnection, counters(100_250, 50_125, 1025, 505), 750_000_000L);
        assertEquals(1000, next.rxRawPerSecond(), EPSILON);
        assertEquals(500, next.rxWirePerSecond(), EPSILON);
        assertEquals(100, next.txRawPerSecond(), EPSILON);
        assertEquals(20, next.txWirePerSecond(), EPSILON);
    }

    @Test
    void anyCounterRollbackStartsANewBaselineInsteadOfDisplayingNegativeRates() {
        for (var rollback : new ClientTrafficSampler.Counters[] {
            counters(99, 60, 30, 20), counters(200, 49, 30, 20),
            counters(200, 60, 9, 20), counters(200, 60, 30, 4)
        }) {
            sampler.reset();
            sampler.update(connection, counters(100, 50, 10, 5), 0);
            var reset = sampler.update(connection, rollback, 250_000_000L);
            assertFalse(reset.sampled());
            assertEquals(0, reset.rxRawPerSecond());
            assertEquals(0, reset.rxWirePerSecond());
            assertEquals(0, reset.txRawPerSecond());
            assertEquals(0, reset.txWirePerSecond());
        }
    }

    @Test
    void aBackwardTimestampDiscardsStaleRatesAndRebasesTheNextSample() {
        sampler.update(connection, counters(0, 0, 0, 0), 1_000_000_000L);
        sampler.update(connection, counters(1000, 500, 100, 50), 1_250_000_000L);
        var reset = sampler.update(connection, counters(1100, 550, 110, 55), 500_000_000L);
        assertFalse(reset.sampled());
        assertEquals(0, reset.rxRawPerSecond());
        var next = sampler.update(connection, counters(1350, 675, 135, 60), 750_000_000L);
        assertTrue(next.sampled());
        assertEquals(1000, next.rxRawPerSecond(), EPSILON);
        assertEquals(500, next.rxWirePerSecond(), EPSILON);
        assertEquals(100, next.txRawPerSecond(), EPSILON);
        assertEquals(20, next.txWirePerSecond(), EPSILON);
    }

    @Test
    void resetAndDisconnectedInputsClearThePreviousConnection() {
        sampler.update(connection, counters(0, 0, 0, 0), 0);
        sampler.update(connection, counters(1000, 500, 100, 50), 250_000_000L);
        assertEquals(ClientTrafficSampler.Snapshot.EMPTY, sampler.update(null, counters(2000, 600, 200, 60), 500_000_000L));
        assertFalse(sampler.update(connection, counters(2000, 600, 200, 60), 750_000_000L).sampled());
        assertEquals(ClientTrafficSampler.Snapshot.EMPTY, sampler.update(connection, null, 1_000_000_000L));
        sampler.update(connection, counters(2000, 600, 200, 60), 1_250_000_000L);
        sampler.reset();
        assertFalse(sampler.update(connection, counters(3000, 1000, 300, 100), 1_500_000_000L).sampled());
    }

    @Test
    void wireOverheadCanExceedOneHundredPercentAndAnEmptyDenominatorIsUnknown() {
        assertTrue(Double.isNaN(sampler.update(connection, counters(0, 0, 0, 0), 0).wireToRawPercent()));
        var overhead = sampler.update(connection, counters(100, 150, 100, 150), 250_000_000L);
        assertEquals(150, overhead.wireToRawPercent(), EPSILON);
        assertTrue(overhead.rxWirePerSecond() > overhead.rxRawPerSecond());
    }

    private static ClientTrafficSampler.Counters counters(long rxRaw, long rxWire, long txRaw, long txWire) {
        return new ClientTrafficSampler.Counters(rxRaw, rxWire, txRaw, txWire);
    }
}
