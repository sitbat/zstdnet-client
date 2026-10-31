package cn.tohsaka.zstdnet.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Private protocol for the matching proxy plugin; not the original ZstdNet protocol. */
public record TransportPayload(int opcode, int version) implements CustomPacketPayload {
    public static final int VERSION = 2;
    public static final int HELLO = 0;
    public static final int PREPARE = 1;
    public static final int READY = 2;
    public static final int ACTIVATE = 3;
    public static final Type<TransportPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath("zstdnet", "proxy_transport"));
    public static final StreamCodec<FriendlyByteBuf, TransportPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, TransportPayload::opcode,
        ByteBufCodecs.VAR_INT, TransportPayload::version,
        TransportPayload::new);

    public static TransportPayload message(int opcode) {
        return new TransportPayload(opcode, VERSION);
    }

    @Override
    public Type<TransportPayload> type() {
        return TYPE;
    }
}
