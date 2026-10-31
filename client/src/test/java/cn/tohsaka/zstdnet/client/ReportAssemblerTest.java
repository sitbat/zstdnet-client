package cn.tohsaka.zstdnet.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReportAssemblerTest {
    private static final UUID REQUEST = new UUID(7, 13);

    @Test
    void orderedByteChunksPreserveUtf8CharactersEvenWhenEveryCodePointIsSplit() {
        byte[] utf8 = "{\"name\":\"中文🎶服务器\",\"series\":[]}".getBytes(StandardCharsets.UTF_8);
        ReportAssembler assembler = new ReportAssembler(REQUEST, 0);
        ReportAssembler.Complete result = null;
        for (int i = 0; i < utf8.length; i++) {
            result = assembler.accept(chunk(i, utf8.length, utf8.length, new byte[] {utf8[i]}), i);
            if (i + 1 < utf8.length) assertNull(result);
        }
        assertNotNull(result);
        assertTrue(result.success());
        assertArrayEquals(utf8, result.bytes());
        assertEquals("{\"name\":\"中文🎶服务器\",\"series\":[]}", new String(result.bytes(), StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> assembler.accept(chunk(0, 1, 1, new byte[] {1}), 100));
    }

    @Test
    void unrelatedRequestIdsAreIgnoredWithoutAdvancingTheExpectedChunkIndex() {
        ReportAssembler assembler = new ReportAssembler(REQUEST, 0);
        assertNull(assembler.accept(new ReportAssembler.Chunk(UUID.randomUUID(), true, 99, 0, -1, null), 1));
        var complete = assembler.accept(chunk(0, 1, 2, new byte[] {1, 2}), 2);
        assertArrayEquals(new byte[] {1, 2}, complete.bytes());
    }

    @Test
    void duplicateAndOutOfOrderChunksInvalidateTheMatchingRequest() {
        ReportAssembler duplicate = new ReportAssembler(REQUEST, 0);
        assertNull(duplicate.accept(chunk(0, 2, 4, new byte[] {1, 2}), 1));
        assertThrows(IllegalArgumentException.class, () -> duplicate.accept(chunk(0, 2, 4, new byte[] {1, 2}), 2));
        assertThrows(IllegalStateException.class, () -> duplicate.accept(chunk(1, 2, 4, new byte[] {3, 4}), 3));

        ReportAssembler reordered = new ReportAssembler(REQUEST, 0);
        assertThrows(IllegalArgumentException.class, () -> reordered.accept(chunk(1, 2, 4, new byte[] {3, 4}), 1));
    }

    @Test
    void declaredMetadataCannotChangePartWayThroughAReport() {
        for (var inconsistent : new ReportAssembler.Chunk[] {
            chunk(1, 3, 4, new byte[] {3, 4}),
            chunk(1, 2, 5, new byte[] {3, 4}),
            new ReportAssembler.Chunk(REQUEST, false, 1, 2, 4, new byte[] {3, 4})
        }) {
            ReportAssembler assembler = new ReportAssembler(REQUEST, 0);
            assertNull(assembler.accept(chunk(0, 2, 4, new byte[] {1, 2}), 1));
            assertThrows(IllegalArgumentException.class, () -> assembler.accept(inconsistent, 2));
        }
    }

    @Test
    void sizeAndCountBoundsAreCheckedBeforeAllocatingADeclaredReportBuffer() {
        for (var invalid : new ReportAssembler.Chunk[] {
            chunk(0, 1, ReportAssembler.MAX_BYTES + 1, new byte[] {1}),
            chunk(0, ReportAssembler.MAX_CHUNKS + 1, 300, new byte[] {1}),
            chunk(-1, 1, 1, new byte[] {1}),
            chunk(0, 0, 1, new byte[] {1}),
            chunk(0, 1, 0, new byte[] {1}),
            chunk(0, 1, 1, null),
            chunk(0, 1, 1, new byte[0]),
            chunk(0, 2, 50_000, new byte[ReportAssembler.MAX_CHUNK_BYTES + 1]),
            chunk(0, 2, 1, new byte[] {1}),
            chunk(0, 1, ReportAssembler.MAX_CHUNK_BYTES + 1, new byte[] {1})
        }) {
            ReportAssembler assembler = new ReportAssembler(REQUEST, 0);
            assertThrows(IllegalArgumentException.class, () -> assembler.accept(invalid, 1));
        }
    }

    @Test
    void theFourMiBLimitIsAcceptedExactlyWithoutTruncatingTheLastChunk() {
        byte[] report = new byte[ReportAssembler.MAX_BYTES];
        for (int i = 0; i < report.length; i++) report[i] = (byte) i;
        int chunks = (report.length + ReportAssembler.MAX_CHUNK_BYTES - 1) / ReportAssembler.MAX_CHUNK_BYTES;
        ReportAssembler assembler = new ReportAssembler(REQUEST, 0);
        ReportAssembler.Complete result = null;
        for (int index = 0; index < chunks; index++) {
            int start = index * ReportAssembler.MAX_CHUNK_BYTES;
            byte[] bytes = Arrays.copyOfRange(report, start, Math.min(report.length, start + ReportAssembler.MAX_CHUNK_BYTES));
            result = assembler.accept(chunk(index, chunks, report.length, bytes), index);
        }
        assertNotNull(result);
        assertArrayEquals(report, result.bytes());
    }

    @Test
    void earlyCompletionAndAShortFinalChunkAreRejected() {
        ReportAssembler premature = new ReportAssembler(REQUEST, 0);
        assertThrows(IllegalArgumentException.class, () -> premature.accept(chunk(0, 2, 3, new byte[] {1, 2, 3}), 1));
        ReportAssembler incomplete = new ReportAssembler(REQUEST, 0);
        assertNull(incomplete.accept(chunk(0, 2, 4, new byte[] {1, 2}), 1));
        assertThrows(IllegalArgumentException.class, () -> incomplete.accept(chunk(1, 2, 4, new byte[] {3}), 2));
    }

    @Test
    void receivedBytesCannotExceedTheDeclaredLength() {
        ReportAssembler assembler = new ReportAssembler(REQUEST, 0);
        assertNull(assembler.accept(chunk(0, 2, 3, new byte[] {1, 2}), 1));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(chunk(1, 2, 3, new byte[] {3, 4}), 2));
    }

    @Test
    void aResponseAtTheDeadlineIsRejectedAndTheTimeoutIsInclusive() {
        long started = 1_000_000_000L;
        ReportAssembler assembler = new ReportAssembler(REQUEST, started);
        assertFalse(assembler.expired(started + ReportAssembler.TIMEOUT_NANOS - 1));
        assertTrue(assembler.expired(started + ReportAssembler.TIMEOUT_NANOS));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(chunk(0, 1, 1, new byte[] {1}),
            started + ReportAssembler.TIMEOUT_NANOS));
    }

    @Test
    void errorResponsesAreBoundedSingleChunksAndRemainDistinctFromSuccessfulHtmlReports() {
        byte[] denial = "没有查看全局报告的权限".getBytes(StandardCharsets.UTF_8);
        ReportAssembler assembler = new ReportAssembler(REQUEST, 0);
        var complete = assembler.accept(new ReportAssembler.Chunk(REQUEST, false, 0, 1, denial.length, denial), 1);
        assertFalse(complete.success());
        assertArrayEquals(denial, complete.bytes());
        ReportAssembler splitError = new ReportAssembler(REQUEST, 0);
        assertThrows(IllegalArgumentException.class, () -> splitError.accept(
            new ReportAssembler.Chunk(REQUEST, false, 0, 2, 2, new byte[] {1}), 1));
        ReportAssembler hugeError = new ReportAssembler(REQUEST, 0);
        assertThrows(IllegalArgumentException.class, () -> hugeError.accept(
            new ReportAssembler.Chunk(REQUEST, false, 0, 1, 8193, new byte[] {1}), 1));
    }

    private static ReportAssembler.Chunk chunk(int index, int count, int total, byte[] bytes) {
        return new ReportAssembler.Chunk(REQUEST, true, index, count, total, bytes);
    }
}
