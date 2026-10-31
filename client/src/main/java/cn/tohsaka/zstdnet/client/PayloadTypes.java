package cn.tohsaka.zstdnet.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** Companion-only channels; these deliberately differ from the original mod's packet formats. */
public final class PayloadTypes {
    private PayloadTypes() { }

    static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(ProxyHud.TYPE, ProxyHud.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ReportRequest.TYPE, ReportRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ReportResponse.TYPE, ReportResponse.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ProxyHud.TYPE, (payload, context) -> {
            var listener = context.client().getConnection();
            if (listener != null) ClientHud.acceptRemote(listener.getConnection(), payload.json());
        });
        ClientPlayNetworking.registerGlobalReceiver(ReportResponse.TYPE, (payload, context) -> {
            var listener = context.client().getConnection();
            if (listener != null) ClientReportService.accept(listener.getConnection(), payload);
        });
    }

    public record ProxyHud(String json) implements CustomPacketPayload {
        public static final Type<ProxyHud> TYPE = PayloadTypes.type("proxy_hud");
        public static final StreamCodec<RegistryFriendlyByteBuf, ProxyHud> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.json(), 8192), buffer -> new ProxyHud(buffer.readUtf(8192)));
        @Override public Type<ProxyHud> type() { return TYPE; }
    }

    public record ReportRequest(UUID requestId, String range) implements CustomPacketPayload {
        public static final Type<ReportRequest> TYPE = PayloadTypes.type("proxy_report_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ReportRequest> CODEC = StreamCodec.of(
            (buffer, payload) -> { buffer.writeUUID(payload.requestId()); buffer.writeUtf(payload.range(), 16); },
            buffer -> new ReportRequest(buffer.readUUID(), buffer.readUtf(16)));
        @Override public Type<ReportRequest> type() { return TYPE; }
    }

    public record ReportResponse(UUID requestId, boolean success, int index, int totalChunks, int totalBytes,
                                 byte[] bytes) implements CustomPacketPayload {
        public static final Type<ReportResponse> TYPE = PayloadTypes.type("proxy_report_response");
        public static final StreamCodec<RegistryFriendlyByteBuf, ReportResponse> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.requestId()); buffer.writeBoolean(payload.success());
                buffer.writeVarInt(payload.index()); buffer.writeVarInt(payload.totalChunks());
                buffer.writeVarInt(payload.totalBytes()); buffer.writeByteArray(payload.bytes());
            }, buffer -> new ReportResponse(buffer.readUUID(), buffer.readBoolean(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(ReportAssembler.MAX_CHUNK_BYTES)));
        @Override public Type<ReportResponse> type() { return TYPE; }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("zstdnet", path));
    }
}
