package cn.tohsaka.zstdnet.client.mixin;

import cn.tohsaka.zstdnet.client.ClientTransport;
import cn.tohsaka.zstdnet.transport.TransportConnection;
import cn.tohsaka.zstdnet.transport.TransportStats;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class ConnectionMixin {
    @Unique private TransportStats zstdnetProxy$lastStats;
    @Unique private long zstdnetProxy$lastWire;

    @Inject(method = "tick", at = @At("HEAD"))
    private void zstdnetProxy$sampleF3Bandwidth(CallbackInfo ci) {
        ConnectionAccessor accessor = (ConnectionAccessor) this;
        Channel channel = accessor.zstdnetProxy$getChannel();
        TransportStats stats = channel == null ? null : TransportConnection.stats(channel);
        if (stats == null) {
            zstdnetProxy$lastStats = null;
            zstdnetProxy$lastWire = 0;
            return;
        }
        if (stats != zstdnetProxy$lastStats) {
            zstdnetProxy$lastStats = stats;
            zstdnetProxy$lastWire = 0;
        }
        long current = stats.rxWire();
        long delta = Math.max(0L, current - zstdnetProxy$lastWire);
        zstdnetProxy$lastWire = current;
        var monitor = accessor.zstdnetProxy$getBandwidthMonitor();
        if (monitor != null && delta > 0) {
            // Only Zstd batches bypass the vanilla splitter. Vanilla frames are counted there.
            // Read the CURRENT monitor every tick: setBandwidthLogger can replace its instance.
            monitor.onReceive((int) Math.min(Integer.MAX_VALUE, delta));
        }
    }

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void zstdnetProxy$transportControl(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (ClientTransport.handle((Connection) (Object) this, context.channel(), packet)) {
            ci.cancel();
        }
    }
}
