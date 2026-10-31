package cn.tohsaka.zstdnet.client;

import cn.tohsaka.zstdnet.client.mixin.ConnectionAccessor;
import cn.tohsaka.zstdnet.transport.TransportConnection;
import cn.tohsaka.zstdnet.transport.TransportStats;
import cn.tohsaka.zstdnet.transport.ZstdFramedCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.server.Bootstrap;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs under real Fabric/Knot, then exits before Minecraft initializes a display. */
public final class TransportSmoke implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            runChecks();
            System.out.println("ZSTDNET_CLIENT_SMOKE_OK: Minecraft " + SharedConstants.getCurrentVersion().name()
                + ", Java " + Runtime.version().feature() + ", Knot/Mixin, native Minecraft packet encoder, Zstd, AES, Netty "
                + io.netty.util.Version.identify().get("netty-common"));
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void runChecks() throws Exception {
        SharedConstants.tryDetectVersion();
        require("26.3".equals(SharedConstants.getCurrentVersion().id()),
            "Smoke test requires Minecraft 26.3, got " + SharedConstants.getCurrentVersion().id());
        require(Runtime.version().feature() >= 25, "Minecraft 26.3 smoke test requires Java 25 or newer");
        Bootstrap.bootStrap();
        new ZstdNetProxyClient().onInitializeClient();
        var registry = Class.forName("net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl");
        var hudRoot = registry.getMethod("getRoot", Identifier.class).invoke(null,
            net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT);
        var layers = (java.util.List<?>) hudRoot.getClass().getMethod("layers").invoke(hudRoot);
        var hudId = Class.forName("net.fabricmc.fabric.impl.client.rendering.hud.HudLayer").getMethod("id");
        boolean registered = false;
        for (Object layer : layers) registered |= "zstdnet_proxy:bandwidth".equals(hudId.invoke(layer).toString());
        require(registered, "HUD layer was not registered");
        require(cn.tohsaka.zstdnet.client.mixin.ClientCommonPacketListenerAccessor.class.isAssignableFrom(
            Class.forName("net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl")),
            "Configuration connection accessor did not apply");
        verifyConfigurationHello();
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        require(connection instanceof ConnectionAccessor, "ConnectionAccessor Mixin did not apply");
        EmbeddedChannel client = new EmbeddedChannel();
        EmbeddedChannel decode = new EmbeddedChannel(new ZstdFramedCodec.Decoder(ignored -> { }, ignored -> { }));
        try {
            Connection.configureSerialization(client.pipeline(), PacketFlow.CLIENTBOUND, false, null);
            connection.configurePacketHandler(client.pipeline());
            client.pipeline().fireChannelActive();
            require(((ConnectionAccessor) connection).zstdnetProxy$getChannel() == client,
                "Connection channel accessor returned the wrong channel");

            // No packet listener exists. Only our actual injected cancellation prevents vanilla's
            // 'Received a packet before the packet listener was initialized' failure here.
            var pipelineNames = client.pipeline().names();
            String beforeConnection = pipelineNames.get(pipelineNames.indexOf("packet_handler") - 1);
            client.pipeline().context(beforeConnection).fireChannelRead(
                new ClientboundCustomPayloadPacket(new TransportPayload(TransportPayload.PREPARE, 999)));
            client.checkException();
            require(client.isActive(), "Unsupported transport message was not safely consumed by the Mixin");

            // Minecraft installs a new protocol only after the previous terminal packet.
            connection.send(new ClientIntentionPacket(SharedConstants.getCurrentVersion().protocolVersion(),
                "smoke.invalid", 25565, ClientIntent.LOGIN));
            ByteBuf handshake = client.readOutbound();
            require(handshake != null, "Native handshake encoder produced no output");
            handshake.release();
            connection.setupOutboundProtocol(LoginProtocols.SERVERBOUND);
            connection.setupCompression(256, false);
            ServerboundHelloPacket packet = new ServerboundHelloPacket("ZstdSmoke", new UUID(1, 2));
            connection.send(packet);
            ByteBuf vanilla = client.readOutbound();
            require(vanilla != null, "Native Minecraft packet encoder produced no output");
            byte[] expected;
            try {
                int framedSize = readVarInt(vanilla);
                require(framedSize == vanilla.readableBytes(), "Native frame length mismatch");
                require(readVarInt(vanilla) == 0, "Small baseline packet unexpectedly used zlib");
                expected = new byte[vanilla.readableBytes()];
                vanilla.readBytes(expected);
            } finally {
                vanilla.release();
            }

            byte[] keyBytes = new byte[16];
            Arrays.fill(keyBytes, (byte) 0x31);
            SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
            connection.setEncryptionKey(cipher(Cipher.DECRYPT_MODE, key), cipher(Cipher.ENCRYPT_MODE, key));
            require(client.pipeline().get("decrypt") instanceof net.minecraft.network.CipherDecoder
                && client.pipeline().get("encrypt") instanceof net.minecraft.network.CipherEncoder,
                "Minecraft AES handlers were not installed");
            TransportConnection.Layout layout = new TransportConnection.Layout(
                "splitter", "encoder", "decompress", "compress", "prepender", 8 * 1024 * 1024);
            TransportStats stats = new TransportStats();
            AtomicBoolean prepared = new AtomicBoolean();
            TransportConnection.prepare(client, layout, stats, () -> prepared.set(true));
            client.runPendingTasks();
            require(prepared.get(), "Transport decoder was not prepared");
            TransportConnection.enableOutbound(client, 3, layout, stats);
            connection.send(packet);
            client.advanceTimeBy(3, TimeUnit.MILLISECONDS);
            client.runScheduledPendingTasks();
            client.checkException();

            ByteBuf encrypted = client.readOutbound();
            require(encrypted != null, "Zstd encoder produced no output");
            byte[] wire;
            try {
                wire = new byte[encrypted.readableBytes()];
                encrypted.readBytes(wire);
            } finally {
                encrypted.release();
            }
            byte[] plain = cipher(Cipher.DECRYPT_MODE, key).update(wire);
            require(plain.length >= 12 && (plain[0] & 0xff) == 0x80,
                "Wire data was not AES encrypted Zstd, or native prepender ran twice");
            decode.writeInbound(Unpooled.wrappedBuffer(plain));
            ZstdFramedCodec.DecodedBatch batch = decode.readInbound();
            require(batch != null, "Runtime Zstd decoder produced no batch");
            try {
                ByteBuf data = batch.data();
                require(readVarInt(data) == expected.length, "Zstd Minecraft frame length mismatch");
                byte[] actual = new byte[data.readableBytes()];
                data.readBytes(actual);
                require(Arrays.equals(expected, actual), "Native packet bytes changed during Zstd/AES transport");
            } finally {
                batch.data().release();
            }
            require(stats.txRaw() > 0 && stats.txWire() == wire.length, "Client traffic counters did not update");

            // Real F3 logger: vanilla is counted by its splitter; only Zstd wire bytes are
            // bridged by the actual injected Connection.tick(), never raw bytes or upload.
            LocalSampleLogger bandwidth = new LocalSampleLogger(1);
            connection.setBandwidthLogger(bandwidth);
            var monitor = ((ConnectionAccessor) connection).zstdnetProxy$getBandwidthMonitor();
            EmbeddedChannel vanillaMeter = new EmbeddedChannel(new Varint21FrameDecoder(monitor));
            try {
                vanillaMeter.writeInbound(Unpooled.buffer().writeByte(3).writeBytes(new byte[] {1,2,3}));
                ByteBuf metered = vanillaMeter.readInbound();
                require(metered != null, "Vanilla F3 baseline frame did not decode");
                metered.release();
                connection.tick();
                require(bandwidth.get(bandwidth.size()-1) == 4, "Vanilla F3 wire bytes were not counted once");
                stats.addRxRaw(8192); stats.addRxWire(73);
                connection.tick();
                require(bandwidth.get(bandwidth.size()-1) == 73, "F3 did not count only received Zstd wire bytes");
                connection.tick();
                require(bandwidth.get(bandwidth.size()-1) == 0, "F3 duplicated a prior Zstd batch");
                LocalSampleLogger replacement = new LocalSampleLogger(1);
                connection.setBandwidthLogger(replacement);
                stats.addRxWire(29); stats.addRxRaw(4096);
                connection.tick();
                require(replacement.get(replacement.size()-1) == 29, "F3 bridge retained a stale monitor");
            } finally { vanillaMeter.finishAndReleaseAll(); }
        } finally {
            client.finishAndReleaseAll();
            decode.finishAndReleaseAll();
        }
    }

    private static void verifyConfigurationHello() {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Connection.configureSerialization(channel.pipeline(), PacketFlow.CLIENTBOUND, false, null);
            connection.configurePacketHandler(channel.pipeline());
            channel.pipeline().fireChannelActive();
            connection.send(new ClientIntentionPacket(SharedConstants.getCurrentVersion().protocolVersion(),
                "smoke.invalid", 25565, ClientIntent.LOGIN));
            ByteBuf handshake = channel.readOutbound(); require(handshake != null, "No configuration handshake"); handshake.release();
            connection.setupOutboundProtocol(LoginProtocols.SERVERBOUND);
            connection.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
            ByteBuf ack = channel.readOutbound(); require(ack != null, "No login acknowledgement"); ack.release();
            connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
            connection.setupCompression(256, false);
            ClientTransport.onJoin(connection);
            channel.runPendingTasks();
            ByteBuf hello = channel.readOutbound();
            require(hello != null, "Configuration HELLO was not encoded");
            try {
                String bytes = hello.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
                require(bytes.contains("zstdnet:proxy_transport"), "HELLO is not a configuration custom payload");
            } finally { hello.release(); }
            channel.checkException();
        } finally { channel.finishAndReleaseAll(); }
    }

    private static Cipher cipher(int mode, SecretKeySpec key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(mode, key, new IvParameterSpec(key.getEncoded()));
        return cipher;
    }

    private static int readVarInt(ByteBuf buffer) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = buffer.readUnsignedByte();
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new AssertionError("Invalid Minecraft VarInt");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
