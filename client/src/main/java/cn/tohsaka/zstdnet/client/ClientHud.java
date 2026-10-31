package cn.tohsaka.zstdnet.client;

import cn.tohsaka.zstdnet.transport.TransportConnection;
import cn.tohsaka.zstdnet.transport.TransportStats;
import io.netty.channel.Channel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/** Small always-available bandwidth display, separate from Minecraft's F3 charts. */
public final class ClientHud {
    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnet-proxy-client");
    private static final ClientTrafficSampler SAMPLER = new ClientTrafficSampler();
    private static boolean enabled = false;
    private static boolean connected;
    private static boolean active;
    private static boolean countersAvailable;
    private static ClientTrafficSampler.Snapshot snapshot = ClientTrafficSampler.Snapshot.EMPTY;
    private static ClientTrafficSampler.Counters localTotals;
    private static int localPort;
    private static Channel remoteChannel;
    private static ProxyHudSnapshot remoteSnapshot;
    private static long remoteReceivedNanos;

    private ClientHud() { }

    static void initialize(ClientConfig config) {
        enabled = config.hud();
        ClientTickEvents.END_CLIENT_TICK.register(ClientHud::tick);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("zstdnet_proxy", "bandwidth"), (graphics, delta) -> extract(graphics));
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void setEnabled(boolean show) {
        enabled = show;
        ClientConfig.saveHud(show, LOGGER);
    }

    static void acceptRemote(Connection source, String json) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null || client.getConnection().getConnection() != source) return;
        Channel channel = ClientTransport.channel(source);
        if (channel == null || !channel.isActive()) return;
        try {
            remoteSnapshot = ProxyHudSnapshot.parse(json);
            remoteChannel = channel;
            remoteReceivedNanos = System.nanoTime();
        } catch (RuntimeException error) {
            LOGGER.debug("Ignored malformed proxy HUD snapshot: {}", error.getMessage());
        }
    }

    static boolean hasRemoteSnapshot(Channel channel) {
        return remoteSnapshot != null && channel == remoteChannel && System.nanoTime() - remoteReceivedNanos < 5_000_000_000L;
    }

    private static void tick(Minecraft client) {
        Connection connection = client.getConnection() == null ? null : client.getConnection().getConnection();
        Channel channel = connection == null || connection.isMemoryConnection() ? null : ClientTransport.channel(connection);
        connected = channel != null && channel.isActive();
        if (!connected) {
            SAMPLER.reset();
            snapshot = ClientTrafficSampler.Snapshot.EMPTY;
            active = false;
            countersAvailable = false;
            localTotals = null;
            remoteChannel = null;
            remoteSnapshot = null;
            return;
        }
        if (!hasRemoteSnapshot(channel)) {
            remoteChannel = null;
            remoteSnapshot = null;
        }
        localPort = channel.remoteAddress() instanceof InetSocketAddress address ? address.getPort() : 0;
        active = TransportConnection.isActive(channel);
        TransportStats stats = TransportConnection.stats(channel);
        countersAvailable = stats != null;
        ClientTrafficSampler.Counters counters = stats == null ? null : new ClientTrafficSampler.Counters(
            stats.rxRaw(), stats.rxWire(), stats.txRaw(), stats.txWire());
        localTotals = counters;
        snapshot = SAMPLER.update(channel, counters, System.nanoTime());
    }

    private static void extract(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        // 26.3 stores F1 visibility on Hud; the CHAT attachment also inherits this condition.
        if (!enabled || !connected || client.gui.hud.isHidden() || client.getConnection() == null) return;

        Component state = Component.translatable(active ? "zstdnet_proxy.hud.active" : "zstdnet_proxy.hud.vanilla");
        List<Component> lines = new ArrayList<>();
        ProxyHudSnapshot proxy = remoteSnapshot;
        if (proxy != null) {
            lines.add(Component.translatable("zstdnet_proxy.hud.proxy_title", proxy.listenPort()));
            lines.add(Component.translatable("zstdnet_proxy.hud.proxy_connections", proxy.connections(), state));
            // Up in proxy statistics means client -> proxy, so map it to the upward arrow.
            lines.add(Component.translatable("zstdnet_proxy.hud.wire",
                ClientHudFormat.rate(proxy.wireDownRate()), ClientHudFormat.rate(proxy.wireUpRate())));
            lines.add(Component.translatable("zstdnet_proxy.hud.raw",
                ClientHudFormat.rate(proxy.rawDownRate()), ClientHudFormat.rate(proxy.rawUpRate())));
            lines.add(Component.translatable("zstdnet_proxy.hud.wire_total",
                ClientHudFormat.size((double) proxy.wireDownBytes() + proxy.wireUpBytes())));
            lines.add(Component.translatable("zstdnet_proxy.hud.raw_total",
                ClientHudFormat.size((double) proxy.rawDownBytes() + proxy.rawUpBytes())));
            lines.add(Component.translatable("zstdnet_proxy.hud.ratio", ClientHudFormat.percent(proxy.ratioPercent())));
            lines.add(Component.translatable("zstdnet_proxy.hud.local_wire",
                formatRate(snapshot.rxWirePerSecond()), formatRate(snapshot.txWirePerSecond())));
            lines.add(Component.translatable("zstdnet_proxy.hud.local_raw",
                formatRate(snapshot.rxRawPerSecond()), formatRate(snapshot.txRawPerSecond())));
        } else {
            lines.add(Component.translatable("zstdnet_proxy.hud.local_title", localPort));
            lines.add(state);
            lines.add(Component.translatable("zstdnet_proxy.hud.wire",
                formatRate(snapshot.rxWirePerSecond()), formatRate(snapshot.txWirePerSecond())));
            lines.add(Component.translatable("zstdnet_proxy.hud.raw",
                formatRate(snapshot.rxRawPerSecond()), formatRate(snapshot.txRawPerSecond())));
            lines.add(Component.translatable("zstdnet_proxy.hud.wire_total", localTotals == null ? "—"
                : ClientHudFormat.size((double) localTotals.rxWire() + localTotals.txWire())));
            lines.add(Component.translatable("zstdnet_proxy.hud.raw_total", localTotals == null ? "—"
                : ClientHudFormat.size((double) localTotals.rxRaw() + localTotals.txRaw())));
            lines.add(!countersAvailable ? Component.translatable("zstdnet_proxy.hud.waiting")
                : !snapshot.sampled() ? Component.translatable("zstdnet_proxy.hud.sampling")
                : Component.translatable("zstdnet_proxy.hud.ratio", ClientHudFormat.percent(snapshot.wireToRawPercent())));
        }
        int width = 0;
        for (Component line : lines) width = Math.max(width, client.font.width(line));
        int rowHeight = client.font.lineHeight + 1;
        int height = rowHeight * lines.size();
        boolean charts = client.getDebugOverlay().showNetworkCharts() || client.getDebugOverlay().showFpsCharts();
        // Stay above the hotbar/status bars; lift above the 60px F3 charts when they are visible.
        int bottomInset = charts ? 88 : 56;
        int x = 6;
        int y = Math.max(6, graphics.guiHeight() - bottomInset - height);
        int accent = active ? 0xff72d7b0 : 0xffe8bf75;
        graphics.fill(x - 3, y - 3, Math.min(graphics.guiWidth() - 3, x + width + 3), y + height + 2, 0x99080c12);
        graphics.fill(x - 3, y - 3, x - 2, y + height + 2, accent);
        for (int index = 0; index < lines.size(); index++) {
            int color = index == 0 ? accent : index == 1 ? 0xffedf1f7 : 0xffb7c1ce;
            graphics.text(client.font, lines.get(index), x, y + index * rowHeight, color, false);
        }
    }

    private static String formatRate(double rate) {
        return snapshot.sampled() ? ClientHudFormat.rate(rate) : "—";
    }
}
