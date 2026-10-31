package cn.tohsaka.zstdnet.client;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.UUID;

/** Bounded, strictly ordered reassembly for one user-initiated traffic report. */
public final class ReportAssembler {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_CHUNKS = 256;
    public static final int MAX_CHUNK_BYTES = 24576;
    public static final long TIMEOUT_NANOS = 30_000_000_000L;
    private final UUID requestId;
    private final long startedNanos;
    private ByteArrayOutputStream buffer;
    private int nextIndex;
    private int expectedChunks;
    private int expectedBytes;
    private boolean success;
    private boolean closed;

    public record Chunk(UUID requestId, boolean success, int index, int totalChunks, int totalBytes, byte[] bytes) { }
    public record Complete(boolean success, byte[] bytes) { }

    public ReportAssembler(UUID requestId, long startedNanos) {
        this.requestId = Objects.requireNonNull(requestId);
        this.startedNanos = startedNanos;
    }

    public UUID requestId() { return requestId; }
    public boolean expired(long nowNanos) { return nowNanos - startedNanos >= TIMEOUT_NANOS; }

    /** Unrelated UUIDs are ignored. A malformed response for our UUID invalidates the request. */
    public Complete accept(Chunk chunk, long nowNanos) {
        Objects.requireNonNull(chunk);
        if (!requestId.equals(chunk.requestId())) return null;
        if (closed) throw new IllegalStateException("report request is already closed");
        if (expired(nowNanos)) return reject("report timed out");
        byte[] bytes = chunk.bytes();
        if (chunk.totalChunks() < 1 || chunk.totalChunks() > MAX_CHUNKS
            || chunk.totalBytes() < 1 || chunk.totalBytes() > MAX_BYTES
            || chunk.index() != nextIndex || chunk.index() >= chunk.totalChunks()
            || bytes == null || bytes.length < 1 || bytes.length > MAX_CHUNK_BYTES) {
            return reject("invalid report chunk bounds or order");
        }
        if (!chunk.success() && (chunk.totalChunks() != 1 || chunk.totalBytes() > 8192)) {
            return reject("invalid error response");
        }
        if (buffer == null) {
            expectedChunks = chunk.totalChunks();
            expectedBytes = chunk.totalBytes();
            success = chunk.success();
            if (expectedChunks > expectedBytes || (long) expectedChunks * MAX_CHUNK_BYTES < expectedBytes) {
                return reject("impossible report chunk sizes");
            }
            buffer = new ByteArrayOutputStream(expectedBytes);
        }
        if (chunk.totalChunks() != expectedChunks || chunk.totalBytes() != expectedBytes || chunk.success() != success
            || buffer.size() + bytes.length > expectedBytes) {
            return reject("inconsistent report metadata");
        }
        buffer.writeBytes(bytes);
        nextIndex++;
        if (nextIndex < expectedChunks) {
            if (buffer.size() >= expectedBytes) return reject("report ended before its final chunk");
            return null;
        }
        if (buffer.size() != expectedBytes) return reject("report length does not match its declaration");
        closed = true;
        byte[] complete = buffer.toByteArray();
        buffer = null;
        return new Complete(success, complete);
    }

    private Complete reject(String reason) {
        closed = true;
        buffer = null;
        throw new IllegalArgumentException(reason);
    }
}
