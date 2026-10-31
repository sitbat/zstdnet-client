package cn.tohsaka.zstdnet.client;

import cn.tohsaka.zstdnet.transport.TransportConnection;
import cn.tohsaka.zstdnet.transport.TransportStats;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the actual ViaVersion handlers bundled in ViaFabricPlus 5.1.1. The UserConnection
 * proxy supplies controlled transformations; this is a handler-order fixture, not the
 * full ViaVersion protocol engine or an authenticated Minecraft connection. The remaining
 * handlers model Minecraft's packet / zlib / VarInt frame boundaries and continuous AES.
 */
class ViaTransportCompatibilityTest {
    private static final TransportConnection.Layout LAYOUT = new TransportConnection.Layout(
        "splitter", "encoder", "decompress", "compress", "prepender", 8 * 1024 * 1024);
    private static final int CLIENTBOUND_CUSTOM_PAYLOAD = 0x18;
    private static final byte[] PAYLOAD = payload();

    @ParameterizedTest(name = "real Via encoder survives Zstd upgrade: {0}")
    @CsvSource({"custom_payload, 22, 21", "debug_subscription_request, 23, 22"})
    void normalPacketWritesAreTranslatedBeforeZstd(String packetName, int clientId, int serverId) throws Exception {
        try (Pair pair = pair(clientId, serverId, true)) {
            // Establish both the normal Via path and an AES stream before upgrading.
            pair.client.channel.writeAndFlush(new Packet(clientId, PAYLOAD));
            transfer(pair.client.channel, pair.remote.channel, 3);
            assertPacket(pair.remote.channel, serverId, PAYLOAD);
            assertEquals(1, pair.client.translations.outgoing.get());
            assertEquals(1, pair.client.nativeCodecs.encodes);
            assertEquals(1, pair.remote.nativeCodecs.decodes);

            activate(pair);
            pair.client.channel.writeAndFlush(new Packet(clientId, PAYLOAD));
            flush(pair.client.channel);
            transfer(pair.client.channel, pair.remote.channel, 7);

            // In particular, 26.3 custom_payload (0x16) must arrive as 1.21.11 0x15,
            // rather than being misinterpreted there as debug_subscription_request.
            assertPacket(pair.remote.channel, serverId, PAYLOAD);
            assertEquals(2, pair.client.translations.outgoing.get(), packetName + " must be translated once per write");
            assertEquals(2, pair.client.packetEncodes.get());
            assertEquals(1, pair.client.nativeCodecs.encodes, "active Zstd must skip native zlib");
            assertEquals(1, pair.remote.nativeCodecs.decodes, "active Zstd must skip native zlib");

            assertIncomingTranslation(pair);
            assertEquals(0, pair.client.nativeCodecs.decodes);
            assertEquals(0, pair.remote.nativeCodecs.encodes);
            assertZstdStats(pair);
        }
    }

    @ParameterizedTest(name = "Via-injected translated packet remains inside Zstd: {0}")
    @CsvSource({"custom_payload, 22, 21", "debug_subscription_request, 23, 22"})
    void packetsInjectedFromViaEncoderContextAreCompressedWithoutBeingTranslatedTwice(
        String packetName, int clientId, int serverId) throws Exception {
        try (Pair pair = pair(clientId, serverId, true)) {
            activate(pair);
            // UserConnectionImpl's client-side sendRawPacketToServer uses this boundary
            // because its packet is already translated. It intentionally skips ViaEncodeHandler.
            ByteBuf alreadyTranslated = encodedPacket(serverId, PAYLOAD);
            ChannelPromise written = pair.client.channel.newPromise();
            pair.client.channel.pipeline().context(ViaEncodeHandler.NAME)
                .writeAndFlush(alreadyTranslated, written);
            flush(pair.client.channel);
            transfer(pair.client.channel, pair.remote.channel, 5);

            assertTrue(written.isSuccess(), () -> String.valueOf(written.cause()));
            assertPacket(pair.remote.channel, serverId, PAYLOAD);
            assertEquals(0, pair.client.translations.outgoing.get(), packetName + " was already translated");
            assertEquals(0, pair.client.packetEncodes.get(), "the injected bytes skip the native packet encoder");
            assertEquals(0, pair.client.nativeCodecs.encodes, "Via-injected bytes still need the Zstd path");
            assertEquals(0, pair.remote.nativeCodecs.decodes);
            assertIncomingTranslation(pair);
            assertZstdStats(pair);
        }
    }

    @Test
    void realViaHandlersAlsoRemainInTheBytePathWhenVanillaCompressionIsDisabled() throws Exception {
        try (Pair pair = pair(0x16, 0x15, false)) {
            activate(pair);
            pair.client.channel.writeAndFlush(new Packet(0x16, PAYLOAD));
            pair.client.channel.pipeline().context(ViaEncodeHandler.NAME)
                .writeAndFlush(encodedPacket(0x15, PAYLOAD));
            flush(pair.client.channel);
            transfer(pair.client.channel, pair.remote.channel, 2);

            assertPacket(pair.remote.channel, 0x15, PAYLOAD);
            assertPacket(pair.remote.channel, 0x15, PAYLOAD);
            assertEquals(1, pair.client.translations.outgoing.get());
            assertEquals(1, pair.client.packetEncodes.get());
            assertIncomingTranslation(pair);
            assertZstdStats(pair);
        }
    }

    private static void assertIncomingTranslation(Pair pair) {
        // Clientbound custom_payload has the same ID in these two versions. A controlled
        // body change proves that the real ViaDecodeHandler ran, exactly once, after Zstd.
        pair.remote.channel.writeAndFlush(new Packet(CLIENTBOUND_CUSTOM_PAYLOAD, new byte[] {0x40, 7, 9}));
        flush(pair.remote.channel);
        transfer(pair.remote.channel, pair.client.channel, 1);
        assertPacket(pair.client.channel, CLIENTBOUND_CUSTOM_PAYLOAD, new byte[] {0x41, 7, 9});
        assertEquals(1, pair.client.translations.incoming.get());
        assertNull(pair.client.channel.readInbound());
        assertNull(pair.remote.channel.readInbound());
    }

    private static void assertZstdStats(Pair pair) {
        assertTrue(pair.client.stats.txRaw() > 0, "all client packets, including injected bytes, must enter Zstd");
        assertTrue(pair.remote.stats.txRaw() > 0);
        assertEquals(pair.client.stats.txRaw(), pair.remote.stats.rxRaw());
        assertEquals(pair.client.stats.txWire(), pair.remote.stats.rxWire());
        assertEquals(pair.remote.stats.txRaw(), pair.client.stats.rxRaw());
        assertEquals(pair.remote.stats.txWire(), pair.client.stats.rxWire());
        assertTrue(pair.client.cipher.encodes > 0, "Zstd stays inside the AES boundary");
        assertTrue(pair.client.cipher.decodes > 0);
        assertTrue(pair.remote.cipher.encodes > 0);
        assertTrue(pair.remote.cipher.decodes > 0);
        assertEquals(0, pair.client.nativeCodecs.removals, "native codecs stay installed until close");
        assertEquals(0, pair.remote.nativeCodecs.removals);
    }

    private static Pair pair(int clientId, int serverId, boolean compression) throws Exception {
        Peer client = peer(new Translations(clientId, serverId), compression);
        try {
            return new Pair(client, peer(null, compression));
        } catch (Throwable error) {
            client.channel.finishAndReleaseAll();
            throw error;
        }
    }

    private static Peer peer(Translations translations, boolean compression) throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.freezeTime();
        NativeCodecs nativeCodecs = new NativeCodecs();
        CipherCounts cipher = new CipherCounts();
        AtomicInteger packetEncodes = new AtomicInteger();
        byte[] keyBytes = new byte[16];
        Arrays.fill(keyBytes, (byte) 53);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(keyBytes));
        Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(keyBytes));
        channel.pipeline().addLast("decrypt", new MessageToMessageDecoder<ByteBuf>() {
            @Override protected void decode(ChannelHandlerContext ctx, ByteBuf input, List<Object> out) {
                cipher.decodes++;
                out.add(crypt(ctx, decrypt, input));
            }
        });
        channel.pipeline().addLast("splitter", new FrameSplitter());
        channel.pipeline().addLast("encrypt", new MessageToMessageEncoder<ByteBuf>() {
            @Override protected void encode(ChannelHandlerContext ctx, ByteBuf input, List<Object> out) {
                cipher.encodes++;
                out.add(crypt(ctx, encrypt, input));
            }
        });
        channel.pipeline().addLast("prepender", new MessageToMessageEncoder<ByteBuf>() {
            @Override protected void encode(ChannelHandlerContext ctx, ByteBuf input, List<Object> out) {
                ByteBuf framed = ctx.alloc().buffer(input.readableBytes() + 5);
                writeVarInt(framed, input.readableBytes());
                framed.writeBytes(input, input.readerIndex(), input.readableBytes());
                out.add(framed);
            }
        });
        if (compression) {
            channel.pipeline().addLast("decompress", new ZlibDecoder(nativeCodecs));
            channel.pipeline().addLast("compress", new ZlibEncoder(nativeCodecs));
        }
        if (translations != null) {
            UserConnection connection = (UserConnection) Proxy.newProxyInstance(
                UserConnection.class.getClassLoader(), new Class<?>[] {UserConnection.class}, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "ControlledViaUserConnectionFixture";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> throw new UnsupportedOperationException(method.toString());
                        };
                    }
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);
                    return switch (method.getName()) {
                        case "shouldTransformPacket", "isClientSide", "isActive", "checkClientboundPacket", "checkServerboundPacket" -> true;
                        case "getChannel" -> channel;
                        case "transformServerbound" -> {
                            ByteBuf packet = (ByteBuf) arguments[0];
                            assertEquals(translations.clientId, packet.getUnsignedByte(packet.readerIndex()));
                            packet.setByte(packet.readerIndex(), translations.serverId);
                            translations.outgoing.incrementAndGet();
                            yield null;
                        }
                        case "transformClientbound" -> {
                            ByteBuf packet = (ByteBuf) arguments[0];
                            assertEquals(CLIENTBOUND_CUSTOM_PAYLOAD, packet.getUnsignedByte(packet.readerIndex()));
                            assertEquals(0x40, packet.getUnsignedByte(packet.readerIndex() + 1), "incoming transformation must not repeat");
                            packet.setByte(packet.readerIndex() + 1, 0x41);
                            translations.incoming.incrementAndGet();
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException("Unexpected Via fixture operation: " + method);
                    };
                });
            // This is ViaFabricPlus's post-compression ordering: native zlib, Via, packet codec.
            // These two handlers come from the extracted, unmodified ViaFabricPlus dependency.
            channel.pipeline().addLast(ViaDecodeHandler.NAME, new ViaDecodeHandler(connection));
            channel.pipeline().addLast(ViaEncodeHandler.NAME, new ViaEncodeHandler(connection));
        }
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new MessageToMessageEncoder<Packet>() {
            @Override protected void encode(ChannelHandlerContext ctx, Packet packet, List<Object> out) {
                packetEncodes.incrementAndGet();
                out.add(encodedPacket(packet.id(), packet.payload()));
            }
        });
        return new Peer(channel, new TransportStats(), translations, nativeCodecs, cipher, packetEncodes);
    }

    private static ByteBuf crypt(ChannelHandlerContext ctx, Cipher cipher, ByteBuf input) {
        byte[] bytes = new byte[input.readableBytes()];
        input.getBytes(input.readerIndex(), bytes);
        byte[] transformed = cipher.update(bytes);
        return ctx.alloc().buffer(transformed.length).writeBytes(transformed);
    }

    private static void activate(Pair pair) {
        for (Peer peer : List.of(pair.client, pair.remote)) {
            AtomicInteger ready = new AtomicInteger();
            TransportConnection.prepare(peer.channel, LAYOUT, peer.stats, ready::incrementAndGet);
            peer.channel.runPendingTasks();
            peer.channel.checkException();
            assertEquals(1, ready.get());
            TransportConnection.enableOutbound(peer.channel, 3, LAYOUT, peer.stats);
        }
    }

    private static void flush(EmbeddedChannel channel) {
        channel.advanceTimeBy(5, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();
        channel.checkException();
    }

    private static void transfer(EmbeddedChannel sender, EmbeddedChannel receiver, int slice) {
        ByteBuf wire = Unpooled.buffer();
        ByteBuf outbound;
        while ((outbound = sender.readOutbound()) != null) {
            try { wire.writeBytes(outbound); } finally { outbound.release(); }
        }
        try {
            assertTrue(wire.isReadable(), "sender must produce encrypted bytes");
            while (wire.isReadable()) receiver.writeInbound(wire.readRetainedSlice(Math.min(slice, wire.readableBytes())));
            receiver.checkException();
        } finally { wire.release(); }
    }

    private static ByteBuf encodedPacket(int id, byte[] payload) {
        ByteBuf encoded = Unpooled.buffer(payload.length + 5);
        writeVarInt(encoded, id);
        return encoded.writeBytes(payload);
    }

    private static void assertPacket(EmbeddedChannel channel, int expectedId, byte[] expectedBody) {
        ByteBuf packet = channel.readInbound();
        assertNotNull(packet, "peer must receive a packet");
        try {
            assertEquals(expectedId, readVarInt(packet));
            byte[] body = new byte[packet.readableBytes()];
            packet.readBytes(body);
            assertArrayEquals(expectedBody, body);
        } finally { packet.release(); }
    }

    private static int readVarInt(ByteBuf input) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int current = input.readUnsignedByte();
            value |= (current & 0x7f) << (7 * i);
            if ((current & 0x80) == 0) return value;
        }
        throw new DecoderException("fixture VarInt exceeds five bytes");
    }

    private static void writeVarInt(ByteBuf output, int value) {
        do {
            int next = value & 0x7f;
            value >>>= 7;
            output.writeByte(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private static byte[] payload() {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 19);
        return payload;
    }

    private static final class FrameSplitter extends ByteToMessageDecoder {
        @Override protected void decode(ChannelHandlerContext ctx, ByteBuf input, List<Object> out) {
            input.markReaderIndex();
            int length = 0;
            for (int i = 0; i < 5; i++) {
                if (!input.isReadable()) { input.resetReaderIndex(); return; }
                int current = input.readUnsignedByte();
                length |= (current & 0x7f) << (7 * i);
                if ((current & 0x80) != 0) continue;
                if (length <= 0 || length > 8 * 1024 * 1024) throw new DecoderException("invalid fixture frame size");
                if (input.readableBytes() < length) { input.resetReaderIndex(); return; }
                out.add(input.readRetainedSlice(length));
                return;
            }
            throw new DecoderException("fixture frame VarInt exceeds five bytes");
        }
    }

    private static final class ZlibEncoder extends MessageToMessageEncoder<ByteBuf> {
        private final NativeCodecs counts;
        private ZlibEncoder(NativeCodecs counts) { this.counts = counts; }
        @Override protected void encode(ChannelHandlerContext ctx, ByteBuf input, List<Object> out) {
            counts.encodes++;
            byte[] raw = new byte[input.readableBytes()];
            input.getBytes(input.readerIndex(), raw);
            Deflater deflater = new Deflater();
            try {
                deflater.setInput(raw);
                deflater.finish();
                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                byte[] chunk = new byte[512];
                while (!deflater.finished()) compressed.write(chunk, 0, deflater.deflate(chunk));
                ByteBuf encoded = ctx.alloc().buffer(compressed.size() + 5);
                writeVarInt(encoded, raw.length);
                encoded.writeBytes(compressed.toByteArray());
                out.add(encoded);
            } finally { deflater.end(); }
        }
        @Override public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
            counts.removals++;
            super.handlerRemoved(ctx);
        }
    }

    private static final class ZlibDecoder extends MessageToMessageDecoder<ByteBuf> {
        private final NativeCodecs counts;
        private ZlibDecoder(NativeCodecs counts) { this.counts = counts; }
        @Override protected void decode(ChannelHandlerContext ctx, ByteBuf input, List<Object> out) throws Exception {
            counts.decodes++;
            int size = readVarInt(input);
            if (size == 0) { out.add(input.readRetainedSlice(input.readableBytes())); return; }
            if (size < 0 || size > 8 * 1024 * 1024) throw new DecoderException("invalid fixture inflated size");
            byte[] compressed = new byte[input.readableBytes()];
            input.readBytes(compressed);
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(compressed);
                byte[] raw = new byte[size];
                int written = 0;
                while (!inflater.finished() && written < size) {
                    int count = inflater.inflate(raw, written, size - written);
                    if (count == 0) break;
                    written += count;
                }
                if (written != size || !inflater.finished()) throw new DecoderException("invalid fixture zlib packet");
                out.add(ctx.alloc().buffer(raw.length).writeBytes(raw));
            } finally { inflater.end(); }
        }
        @Override public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
            counts.removals++;
            super.handlerRemoved(ctx);
        }
    }

    private record Packet(int id, byte[] payload) { }
    private record Peer(EmbeddedChannel channel, TransportStats stats, Translations translations,
                        NativeCodecs nativeCodecs, CipherCounts cipher, AtomicInteger packetEncodes) { }
    private record Pair(Peer client, Peer remote) implements AutoCloseable {
        @Override public void close() {
            try { client.channel.finishAndReleaseAll(); }
            finally { remote.channel.finishAndReleaseAll(); }
        }
    }
    private static final class NativeCodecs { int encodes; int decodes; int removals; }
    private static final class CipherCounts { int encodes; int decodes; }
    private static final class Translations {
        final int clientId;
        final int serverId;
        final AtomicInteger outgoing = new AtomicInteger();
        final AtomicInteger incoming = new AtomicInteger();
        private Translations(int clientId, int serverId) { this.clientId = clientId; this.serverId = serverId; }
    }
}
