package cn.tohsaka.zstdnet.client;

import cn.tohsaka.zstdnet.report.TrafficReportGenerator;
import cn.tohsaka.zstdnet.transport.TransportConnection;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ClientReportService {
    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnet-proxy-client");
    private static final Set<String> RANGES = Set.of("today", "session", "24h", "7d", "30d");
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "zstdnet-report-writer");
            thread.setDaemon(true);
            return thread;
        });
    private static Pending pending;
    private static volatile UUID generating;
    private static Connection generatingConnection;

    private record Pending(Connection connection, String serverIdentity, ReportAssembler assembler) { }

    private ClientReportService() { }

    static void initialize() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Connection current = currentConnection();
            if ((pending != null && pending.connection() != current)
                || (generating != null && generatingConnection != current)) clear();
            if (pending != null && pending.assembler().expired(System.nanoTime())) {
                pending = null;
                message("zstdnet_proxy.report.timeout", true);
            }
        });
    }

    static int request(String range) {
        if (!RANGES.contains(range)) return 0;
        Connection connection = currentConnection();
        if (connection == null || connection.isMemoryConnection() || ClientTransport.channel(connection) == null
            || !ClientTransport.channel(connection).isActive()) {
            message("zstdnet_proxy.report.not_connected", true);
            return 0;
        }
        if (pending != null || generating != null) {
            message("zstdnet_proxy.report.busy", true);
            return 0;
        }
        if (!TransportConnection.isActive(ClientTransport.channel(connection))
            && !ClientHud.hasRemoteSnapshot(ClientTransport.channel(connection))
            && !ClientPlayNetworking.canSend(PayloadTypes.ReportRequest.TYPE)) {
            message("zstdnet_proxy.report.unsupported", true);
            return 0;
        }
        Minecraft client = Minecraft.getInstance();
        String identity = client.getCurrentServer() == null ? connection.getRemoteAddress().toString()
            : client.getCurrentServer().ip;
        UUID requestId = UUID.randomUUID();
        pending = new Pending(connection, identity, new ReportAssembler(requestId, System.nanoTime()));
        connection.send(new ServerboundCustomPayloadPacket(new PayloadTypes.ReportRequest(requestId, range)));
        message("zstdnet_proxy.report.requesting", false, range);
        return 1;
    }

    static void accept(Connection source, PayloadTypes.ReportResponse response) {
        Pending request = pending;
        if (request == null || source != request.connection() || source != currentConnection()) return;
        if (request.assembler().expired(System.nanoTime())) {
            pending = null;
            message("zstdnet_proxy.report.timeout", true);
            return;
        }
        ReportAssembler.Complete complete;
        try {
            complete = request.assembler().accept(new ReportAssembler.Chunk(response.requestId(), response.success(),
                response.index(), response.totalChunks(), response.totalBytes(), response.bytes()), System.nanoTime());
        } catch (IllegalArgumentException | IllegalStateException error) {
            pending = null;
            LOGGER.warn("Rejected malformed report response: {}", error.getMessage());
            message("zstdnet_proxy.report.invalid", true);
            return;
        }
        if (complete == null) return;
        pending = null;
        if (!complete.success()) {
            try {
                String reason = decodeUtf8(complete.bytes()).strip();
                if (reason.length() > 1024) reason = reason.substring(0, 1024) + "…";
                message("zstdnet_proxy.report.denied", true, reason);
            } catch (Exception error) {
                message("zstdnet_proxy.report.invalid", true);
            }
            return;
        }
        UUID token = response.requestId();
        generating = token;
        generatingConnection = source;
        try {
            WRITER.execute(() -> generate(request, token, complete.bytes()));
        } catch (RejectedExecutionException error) {
            generating = null;
            generatingConnection = null;
            message("zstdnet_proxy.report.busy", true);
        }
    }

    private static void generate(Pending request, UUID token, byte[] bytes) {
        try {
            if (!token.equals(generating)) return;
            String json = decodeUtf8(bytes);
            Path reportsRoot = FabricLoader.getInstance().getConfigDir().resolve("zstdnet").resolve("reports");
            var report = TrafficReportGenerator.generate(reportsRoot, request.serverIdentity(), json);
            Minecraft.getInstance().execute(() -> {
                if (!token.equals(generating) || request.connection() != currentConnection()) return;
                generating = null;
                generatingConnection = null;
                Component open = Component.translatable("zstdnet_proxy.report.open").withStyle(style -> style
                    .withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenFile(report.latest()))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(report.latest().toString()))));
                chat(Component.translatable("zstdnet_proxy.report.saved").withStyle(ChatFormatting.GRAY).append(open));
                chat(Component.literal(report.archive().toString()).withStyle(ChatFormatting.DARK_GRAY));
            });
        } catch (Exception error) {
            LOGGER.error("Cannot generate the local traffic report", error);
            Minecraft.getInstance().execute(() -> {
                if (!token.equals(generating) || request.connection() != currentConnection()) return;
                generating = null;
                generatingConnection = null;
                message("zstdnet_proxy.report.failed", true);
            });
        }
    }

    private static String decodeUtf8(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static Connection currentConnection() {
        Minecraft client = Minecraft.getInstance();
        return client.getConnection() == null ? null : client.getConnection().getConnection();
    }

    private static void clear() {
        pending = null;
        generating = null;
        generatingConnection = null;
        WRITER.getQueue().clear();
    }

    private static void message(String key, boolean error, Object... arguments) {
        chat(Component.literal("[ZstdNet] ").append(Component.translatable(key, arguments))
            .withStyle(error ? ChatFormatting.RED : ChatFormatting.GRAY));
    }

    private static void chat(Component message) {
        Minecraft.getInstance().gui.getChat().addMessage(message);
    }
}
