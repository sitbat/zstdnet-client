package cn.tohsaka.zstdnet.client;

import cn.tohsaka.zstdnet.client.mixin.ConnectionAccessor;
import cn.tohsaka.zstdnet.transport.TransportConnection;
import cn.tohsaka.zstdnet.transport.TransportStats;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** All transport transitions run on the actual connection's event loop. */
public final class ClientTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnet-proxy-client");
    private static final AttributeKey<Phase> PHASE = AttributeKey.valueOf("zstdnet_proxy_client_phase_v2");
    private static final TransportConnection.Layout LAYOUT = new TransportConnection.Layout(
        "splitter", "encoder", "decompress", "compress", "prepender", 8 * 1024 * 1024,
        TransportConnection.ReceivePolicy.TRUSTED_SERVER);
    private static ClientConfig config = new ClientConfig(true, 3, false);

    private ClientTransport() {
    }

    static void initialize(ClientConfig clientConfig) {
        config = clientConfig;
    }

    static void onJoin(Connection connection) {
        if (connection == null || connection.isMemoryConnection()) return;
        Channel channel = channel(connection);
        if (channel == null || !config.enabled()) {
            return;
        }
        channel.eventLoop().execute(() -> {
            // A backend switch can trigger another JOIN on the same frontend connection.
            if (!channel.isActive() || channel.attr(PHASE).get() != null) {
                return;
            }
            channel.attr(PHASE).set(Phase.HELLO_SENT);
            connection.send(new ServerboundCustomPayloadPacket(TransportPayload.message(TransportPayload.HELLO)));
            debug("Client transport HELLO sent on {}", channel.id());
        });
    }

    public static boolean handle(Connection connection, Channel channel, Packet<?> packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket incoming)
            || !(incoming.payload() instanceof TransportPayload control)) {
            return false;
        }
        // Consume every message on our private channel, including unsupported versions/opcodes.
        if (control.version() != TransportPayload.VERSION || !config.enabled()) {
            return true;
        }
        if (!channel.eventLoop().inEventLoop()) {
            throw new IllegalStateException("Transport controls must run on the connection event loop");
        }
        Phase phase = channel.attr(PHASE).get();
        if (control.opcode() == TransportPayload.PREPARE && phase == Phase.HELLO_SENT) {
            channel.attr(PHASE).set(Phase.PREPARING);
            TransportConnection.prepare(channel, LAYOUT, new TransportStats(), () -> {
                if (!channel.isActive() || channel.attr(PHASE).get() != Phase.PREPARING) {
                    return;
                }
                channel.attr(PHASE).set(Phase.READY);
                connection.send(new ServerboundCustomPayloadPacket(TransportPayload.message(TransportPayload.READY)));
                debug("Client transport decoder ready on {}", channel.id());
            });
        } else if (control.opcode() == TransportPayload.ACTIVATE && phase == Phase.READY) {
            try {
                TransportConnection.enableOutbound(channel, config.level(), LAYOUT, TransportConnection.stats(channel));
                channel.attr(PHASE).set(Phase.ACTIVE);
                LOGGER.info("Zstd connection active on {} (client level {})", channel.id(), config.level());
            } catch (RuntimeException error) {
                // Once the activation has arrived the peer may already be sending Zstd.
                LOGGER.error("Cannot activate Zstd transport; closing the connection", error);
                channel.close();
            }
        }
        return true;
    }

    static String status(Connection connection) {
        Channel channel = channel(connection);
        if (channel == null || !channel.isActive()) {
            return "ZstdNet: no active multiplayer connection.";
        }
        TransportStats stats = TransportConnection.stats(channel);
        String state = TransportConnection.isActive(channel) ? "active" : "vanilla";
        Phase phase = channel.attr(PHASE).get();
        String details = phase == null ? "" : " (" + phase.name().toLowerCase(java.util.Locale.ROOT) + ")";
        if (stats == null) {
            return "ZstdNet: " + state + details + ".";
        }
        return "ZstdNet: " + state + details + "; received raw/wire " + stats.rxRaw() + "/" + stats.rxWire()
            + " B; sent raw/wire " + stats.txRaw() + "/" + stats.txWire() + " B.";
    }

    static Channel channel(Connection connection) {
        return connection == null ? null : ((ConnectionAccessor) connection).zstdnetProxy$getChannel();
    }

    private static void debug(String message, Object... args) {
        if (config.debug()) {
            LOGGER.info(message, args);
        }
    }

    private enum Phase {
        HELLO_SENT, PREPARING, READY, ACTIVE
    }
}
