package cn.tohsaka.zstdnet.client.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.BandwidthDebugMonitor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ConnectionAccessor {
    @Accessor("channel")
    Channel zstdnetProxy$getChannel();

    @Accessor("bandwidthDebugMonitor")
    BandwidthDebugMonitor zstdnetProxy$getBandwidthMonitor();
}
