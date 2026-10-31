package cn.tohsaka.zstdnet.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class ZstdNetProxyClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnet-proxy-client");

    @Override
    public void onInitializeClient() {
        ClientConfig config = ClientConfig.load(LOGGER);
        ClientTransport.initialize(config);
        ClientHud.initialize(config);
        ClientReportService.initialize();
        PayloadTypes.initialize();
        PayloadTypeRegistry.clientboundPlay().register(TransportPayload.TYPE, TransportPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TransportPayload.TYPE, TransportPayload.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(TransportPayload.TYPE, TransportPayload.CODEC);
        // Declares the client capability; ConnectionMixin handles this before game-thread dispatch.
        ClientPlayNetworking.registerGlobalReceiver(TransportPayload.TYPE, (payload, context) -> { });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientTransport.onJoin(handler.getConnection()));
        ClientConfigurationConnectionEvents.START.register((handler, client) -> ClientTransport.onJoin(
            ((cn.tohsaka.zstdnet.client.mixin.ClientCommonPacketListenerAccessor) handler).zstdnetProxy$getConnection()));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("zstdnet")
                .executes(context -> showStatus())
                .then(literal("status").executes(context -> showStatus()))
                .then(literal("hud")
                    .executes(context -> setHud(!ClientHud.isEnabled()))
                    .then(literal("on").executes(context -> setHud(true)))
                    .then(literal("off").executes(context -> setHud(false)))
                    .then(literal("toggle").executes(context -> setHud(!ClientHud.isEnabled())))));
            dispatcher.register(literal("zstdhud")
                .executes(context -> setHud(!ClientHud.isEnabled()))
                .then(literal("on").executes(context -> setHud(true)))
                .then(literal("off").executes(context -> setHud(false)))
                .then(literal("toggle").executes(context -> setHud(!ClientHud.isEnabled()))));
            var report = literal("zstdreport").executes(context -> ClientReportService.request("today"));
            for (String range : java.util.List.of("today", "session", "24h", "7d", "30d")) {
                report.then(literal(range).executes(context -> ClientReportService.request(range)));
            }
            dispatcher.register(report);
        });
        LOGGER.info("ZstdNet Proxy Client loaded (Minecraft 26.3; transport v{})", TransportPayload.VERSION);
    }

    private static int showStatus() {
        Minecraft minecraft = Minecraft.getInstance();
        Connection connection = minecraft.getConnection() == null ? null : minecraft.getConnection().getConnection();
        minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal(ClientTransport.status(connection)));
        return 1;
    }

    private static int setHud(boolean enabled) {
        ClientHud.setEnabled(enabled);
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.translatable(
            enabled ? "zstdnet_proxy.hud.enabled" : "zstdnet_proxy.hud.disabled"));
        return 1;
    }
}
