package cn.tohsaka.zstdnet.client;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.impl.networking.NetworkingImpl;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.fabricmc.fabric.impl.networking.client.ClientConfigurationNetworkAddon;
import net.fabricmc.fabric.impl.networking.client.ClientNetworkingImpl;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/** Uses Fabric's actual registration -> START callback -> production HELLO, without a game window. */
final class ConfigurationDiscoverySmoke {
    static void verify() throws Exception {
        if (PayloadTypeRegistryImpl.CONFIGURATION_S2C.get(RegistrationPayload.REGISTER) == null) NetworkingImpl.init();
        verify(false);
        verify(true);
        ClientTransport.initialize(new ClientConfig(true, 3, false));
    }

    private static void verify(boolean enabled) throws Exception {
        ClientTransport.initialize(new ClientConfig(enabled, 3, false));
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        EmbeddedChannel channel = new EmbeddedChannel();
        ClientConfigurationNetworkAddon addon = null;
        try {
            Connection.configureSerialization(channel.pipeline(), PacketFlow.CLIENTBOUND, false, null);
            connection.configurePacketHandler(channel.pipeline());
            channel.pipeline().fireChannelActive();
            connection.send(new ClientIntentionPacket(SharedConstants.getCurrentVersion().protocolVersion(), "smoke.invalid", 25565, ClientIntent.LOGIN));
            drain(channel);
            connection.setupOutboundProtocol(LoginProtocols.SERVERBOUND);
            connection.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
            drain(channel);
            connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
            connection.setupCompression(256, false);
            connection.setEncryptionKey(cipher(Cipher.DECRYPT_MODE), cipher(Cipher.ENCRYPT_MODE));

            // Skip the graphical client/listener constructors only. The addon and its event handlers
            // are real Fabric objects, and the Connection uses native Minecraft codecs and AES.
            Minecraft client = allocate(HeadlessMinecraft.class);
            var listener = allocate(ClientConfigurationPacketListenerImpl.class);
            field(listener, "connection", connection);
            field(listener, "minecraft", client);
            field(listener, "localGameProfile", new GameProfile(new UUID(1, 2), "DiscoverySmoke"));
            addon = new ClientConfigurationNetworkAddon(listener, client);
            ClientNetworkingImpl.setClientConfigurationAddon(addon);
            addon.lateInit();
            channel.runPendingTasks();
            require(channel.readOutbound() == null, "Fabric must not emit HELLO before a server readiness packet");

            RegistrationPayload registration = registration();
            require(addon.handle(registration), "Fabric did not consume the server registration");
            channel.runPendingTasks();
            ByteBuf all = Unpooled.buffer();
            try {
                ByteBuf wire;
                while ((wire = channel.readOutbound()) != null) {
                    try { all.writeBytes(wire); } finally { wire.release(); }
                }
                byte[] encrypted = new byte[all.readableBytes()]; all.getBytes(0, encrypted);
                if (enabled && fixtures() != null) Files.write(fixtures().resolve("hello-" + SharedConstants.getCurrentVersion().protocolVersion() + ".bin"), encrypted);
                ByteBuf plain = Unpooled.wrappedBuffer(encrypted.length == 0 ? new byte[0] : cipher(Cipher.DECRYPT_MODE).update(encrypted));
                int hellos = 0;
                try {
                    while (plain.isReadable()) {
                        int size = varInt(plain);
                        ByteBuf frame = plain.readRetainedSlice(size);
                        ByteBuf packet = uncompress(frame);
                        try {
                            if (varInt(packet) != 2) continue;
                            FriendlyByteBuf payload = new FriendlyByteBuf(packet);
                            if (!payload.readUtf().equals("zstdnet:proxy_transport")) continue;
                            require(payload.readVarInt() == TransportPayload.HELLO && payload.readVarInt() == TransportPayload.VERSION && !payload.isReadable(), "Invalid production HELLO");
                            hellos++;
                        } finally { packet.release(); }
                    }
                } finally { plain.release(); }
                require(hellos == (enabled ? 1 : 0), "Fabric START did not produce the expected HELLO count: " + hellos);
            } finally { all.release(); }
            addon.handle(registration);
            channel.runPendingTasks();
            require(channel.readOutbound() == null, "Repeated registration must not duplicate HELLO");
            System.out.println("ZSTDNET_DISCOVERY_EVENT_OK: enabled=" + enabled + ", no HELLO before registration; actual Fabric START handled");
        } finally {
            if (addon != null) addon.endSession();
            ClientNetworkingImpl.setClientConfigurationAddon(null);
            channel.finishAndReleaseAll();
        }
    }

    private static RegistrationPayload registration() throws Exception {
        ByteBuf packet;
        if (fixtures() != null) {
            byte[] wire = Files.readAllBytes(fixtures().resolve("discovery-" + SharedConstants.getCurrentVersion().protocolVersion() + ".bin"));
            ByteBuf plain = Unpooled.wrappedBuffer(cipher(Cipher.DECRYPT_MODE).update(wire));
            try {
                int size = varInt(plain);
                require(size == plain.readableBytes(), "Expected exactly one real Velocity discovery frame");
                packet = uncompress(plain.readRetainedSlice(size));
            } finally { plain.release(); }
        } else {
            packet = Unpooled.buffer();
            ConfigurationProtocols.CLIENTBOUND.codec().encode(packet, new ClientboundCustomPayloadPacket(
                    new RegistrationPayload(RegistrationPayload.REGISTER, java.util.List.of(TransportPayload.TYPE.id()))));
        }
        try {
            var decoded = ConfigurationProtocols.CLIENTBOUND.codec().decode(packet);
            require(decoded instanceof ClientboundCustomPayloadPacket, "Not a CONFIG custom payload");
            Object payload = ((ClientboundCustomPayloadPacket) decoded).payload();
            require(payload instanceof RegistrationPayload, "Fabric did not decode minecraft:register");
            return (RegistrationPayload) payload;
        } finally { packet.release(); }
    }

    private static ByteBuf uncompress(ByteBuf frame) throws Exception {
        try {
            int raw = varInt(frame);
            if (raw == 0) return frame.readRetainedSlice(frame.readableBytes());
            byte[] compressed = new byte[frame.readableBytes()]; frame.readBytes(compressed);
            var inflater = new java.util.zip.Inflater();
            try {
                inflater.setInput(compressed); byte[] output = new byte[raw];
                require(inflater.inflate(output) == raw && inflater.finished(), "Bad zlib frame");
                return Unpooled.wrappedBuffer(output);
            } finally { inflater.end(); }
        } finally { frame.release(); }
    }

    private static Path fixtures() {
        String value = System.getenv("ZSTDNET_DISCOVERY_FIXTURES");
        return value == null ? null : Path.of(value);
    }
    private static Cipher cipher(int mode) throws Exception {
        byte[] secret = new byte[16]; Arrays.fill(secret, (byte) 71);
        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(mode, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
        return cipher;
    }
    private static int varInt(ByteBuf buf) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) { int b = buf.readUnsignedByte(); value |= (b & 127) << shift; if ((b & 128) == 0) return value; }
        throw new AssertionError("Bad VarInt");
    }
    private static void drain(EmbeddedChannel channel) { Object value; while ((value = channel.readOutbound()) != null) io.netty.util.ReferenceCountUtil.release(value); }
    private static void field(Object object, String name, Object value) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class HeadlessMinecraft extends Minecraft {
        private HeadlessMinecraft() { super(null); }
        @Override public void execute(Runnable task) { task.run(); }
    }
}
